package com.crazylei12.pokemonchampionsassistant.replay

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.crazylei12.pokemonchampionsassistant.CaptureBufferSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class EglProjectionRouter(
    encoderSurface: Surface,
    initialSpec: CaptureBufferSpec,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val PROBE_WIDTH = 160
        private const val PROBE_HEIGHT = 90
        private const val PROBE_FRAME_COUNT = 3

        private val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoordinate;
            uniform mat4 uTextureMatrix;
            varying vec2 vTextureCoordinate;
            void main() {
                gl_Position = aPosition;
                vTextureCoordinate = (uTextureMatrix * aTextureCoordinate).xy;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoordinate;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTextureCoordinate);
            }
        """.trimIndent()

        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
    }

    private val thread = HandlerThread("replay-egl-router").apply { start() }
    private val handler = Handler(thread.looper)
    private val failureReported = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lastPresentationNanos = AtomicLong(0L)

    private var spec = initialSpec
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var oesTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var probeTexture = 0
    private var probeFramebuffer = 0
    private var recording = false
    private var throttle = ReplayFrameThrottle()
    private var probeFramesRemaining = 0
    private var probeDetected = false
    private var probeCallback: ((Boolean) -> Unit)? = null

    val captureSurface: Surface

    init {
        val ready = CountDownLatch(1)
        var setupFailure: Throwable? = null
        handler.post {
            try {
                setup(encoderSurface)
            } catch (error: Throwable) {
                setupFailure = error
            } finally {
                ready.countDown()
            }
        }
        check(ready.await(5, TimeUnit.SECONDS)) { "Timed out creating EGL replay router" }
        setupFailure?.let { throw it }
        captureSurface = checkNotNull(inputSurface)
    }

    fun startIsolationProbe(callback: (passed: Boolean) -> Unit) {
        handler.post {
            probeFramesRemaining = PROBE_FRAME_COUNT
            probeDetected = false
            probeCallback = callback
        }
    }

    fun cancelIsolationProbe() {
        handler.post {
            probeFramesRemaining = 0
            probeCallback = null
        }
    }

    fun startRecording() {
        handler.post {
            throttle = ReplayFrameThrottle()
            lastPresentationNanos.set(0L)
            recording = true
        }
    }

    fun stopInputAndAwait() {
        val stopped = CountDownLatch(1)
        if (!handler.post {
                recording = false
                stopped.countDown()
            }
        ) return
        check(stopped.await(3, TimeUnit.SECONDS)) { "Timed out stopping EGL replay input" }
    }

    fun updateInputSpec(nextSpec: CaptureBufferSpec) {
        handler.post {
            spec = nextSpec
            surfaceTexture?.setDefaultBufferSize(nextSpec.virtualDisplayWidth, nextSpec.virtualDisplayHeight)
        }
    }

    fun elapsedMs(): Long = lastPresentationNanos.get() / 1_000_000L

    private fun setup(encoderSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Could not get EGL display" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) { "Could not initialize EGL" }
        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val configCount = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1, configCount, 0)) {
            "Could not choose EGL config"
        }
        val config = configs[0] ?: error("No recordable EGL config")
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Could not create EGL context" }
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            encoderSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Could not create encoder EGL surface" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "Could not make replay EGL context current"
        }

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        oesTexture = createExternalTexture()
        createProbeFramebuffer()
        val texture = SurfaceTexture(oesTexture).apply {
            setDefaultBufferSize(spec.virtualDisplayWidth, spec.virtualDisplayHeight)
            setOnFrameAvailableListener({ handleFrame() }, handler)
        }
        surfaceTexture = texture
        inputSurface = Surface(texture)
    }

    private fun handleFrame() {
        if (closed.get()) return
        try {
            val texture = surfaceTexture ?: return
            texture.updateTexImage()
            val textureMatrix = FloatArray(16)
            texture.getTransformMatrix(textureMatrix)
            val combinedMatrix = rotatedTextureMatrix(textureMatrix, spec.rotation)
            if (probeFramesRemaining > 0) {
                renderProbe(combinedMatrix)
            }
            if (recording) {
                val presentation = throttle.accept(texture.timestamp)
                if (presentation != null) {
                    renderToEncoder(combinedMatrix, presentation)
                    lastPresentationNanos.set(presentation)
                }
            }
        } catch (error: Throwable) {
            reportFailure(error)
        }
    }

    private fun renderProbe(textureMatrix: FloatArray) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probeFramebuffer)
        drawTexture(textureMatrix, PROBE_WIDTH, PROBE_HEIGHT)
        val pixels = ByteBuffer.allocateDirect(PROBE_WIDTH * PROBE_HEIGHT * 4)
            .order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(
            0,
            0,
            PROBE_WIDTH,
            PROBE_HEIGHT,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            pixels,
        )
        checkGl("read isolation probe")
        probeDetected = probeDetected || analyzeReplayIsolationFrame(pixels, PROBE_WIDTH, PROBE_HEIGHT).markerDetected
        probeFramesRemaining -= 1
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (probeFramesRemaining == 0) {
            val callback = probeCallback
            probeCallback = null
            callback?.invoke(!probeDetected)
        }
    }

    private fun renderToEncoder(textureMatrix: FloatArray, presentationNanos: Long) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        drawTexture(textureMatrix, REPLAY_VIDEO_WIDTH, REPLAY_VIDEO_HEIGHT)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationNanos)
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "Could not submit replay frame" }
    }

    private fun drawTexture(textureMatrix: FloatArray, outputWidth: Int, outputHeight: Int) {
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val viewport = fitReplayViewport(spec.width, spec.height, outputWidth, outputHeight)
        GLES20.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
        GLES20.glUseProgram(program)
        val data = ByteBuffer.allocateDirect(QUAD.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(QUAD); position(0) }
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val textureCoordinate = GLES20.glGetAttribLocation(program, "aTextureCoordinate")
        val matrix = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        data.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, data)
        data.position(2)
        GLES20.glEnableVertexAttribArray(textureCoordinate)
        GLES20.glVertexAttribPointer(textureCoordinate, 2, GLES20.GL_FLOAT, false, 16, data)
        GLES20.glUniformMatrix4fv(matrix, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(textureCoordinate)
        checkGl("draw replay texture")
    }

    private fun rotatedTextureMatrix(surfaceMatrix: FloatArray, rotation: Int): FloatArray {
        if (rotation == 0) return surfaceMatrix
        val rotationMatrix = FloatArray(16)
        Matrix.setIdentityM(rotationMatrix, 0)
        Matrix.translateM(rotationMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(rotationMatrix, 0, -90f * rotation, 0f, 0f, 1f)
        Matrix.translateM(rotationMatrix, 0, -0.5f, -0.5f, 0f)
        return FloatArray(16).also {
            Matrix.multiplyMM(it, 0, surfaceMatrix, 0, rotationMatrix, 0)
        }
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        checkGl("create OES texture")
        return textures[0]
    }

    private fun createProbeFramebuffer() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        probeTexture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, probeTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            PROBE_WIDTH,
            PROBE_HEIGHT,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        probeFramebuffer = framebuffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probeFramebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            probeTexture,
            0,
        )
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Isolation probe framebuffer is incomplete"
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Could not link replay shader: ${GLES20.glGetProgramInfoLog(program)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Could not compile replay shader: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "$operation failed with GL error 0x${error.toString(16)}" }
    }

    private fun reportFailure(error: Throwable) {
        if (failureReported.compareAndSet(false, true)) onFailure(error)
    }

    private fun releaseGl() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        runCatching { inputSurface?.release() }
        runCatching { surfaceTexture?.release() }
        inputSurface = null
        surfaceTexture = null
        if (probeFramebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(probeFramebuffer), 0)
        if (probeTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(probeTexture), 0)
        if (oesTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val finished = CountDownLatch(1)
        val posted = handler.post {
            try {
                releaseGl()
            } finally {
                finished.countDown()
            }
        }
        if (posted) finished.await(5, TimeUnit.SECONDS)
        thread.quitSafely()
    }
}
