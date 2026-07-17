package com.crazylei12.pokemonchampionsassistant.replayprobe

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class ReplayProbeService : Service() {
    companion object {
        const val ACTION_RESULT = "com.crazylei12.pokemonchampionsassistant.replayprobe.RESULT"
        const val EXTRA_REPORT_PATH = "report_path"
        const val EXTRA_ERROR = "error"

        private const val ACTION_START = "com.crazylei12.pokemonchampionsassistant.replayprobe.START"
        private const val ACTION_STOP = "com.crazylei12.pokemonchampionsassistant.replayprobe.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_SCENARIO = "scenario"
        private const val GAME_PACKAGE = "jp.pokemon.pokemonchampions"
        private const val CHANNEL_ID = "replay_phase0_probe"
        private const val NOTIFICATION_ID = 4210
        private const val LOG_TAG = "ReplayProbeService"
        private const val PROBE_DURATION_MS = 10_000L
        private const val SAMPLE_RATE = 48_000
        private const val CHANNEL_COUNT = 2
        private const val TONE_LOOP_FRAMES = SAMPLE_RATE / 10

        internal fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            scenario: ReplayProbeScenario,
        ) {
            context.startForegroundService(
                Intent(context, ReplayProbeService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_RESULT_DATA, resultData)
                    putExtra(EXTRA_SCENARIO, scenario.wireName)
                },
            )
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopRequested = AtomicBoolean(false)
    private val projectionStopped = AtomicBoolean(false)
    private val frameCount = AtomicLong(0)
    private val analyzedFrameCount = AtomicLong(0)
    private val magentaPixelCount = AtomicLong(0)
    private val cyanPixelCount = AtomicLong(0)
    private val sampledPixelCount = AtomicLong(0)
    private val lastFrameScanMs = AtomicLong(0)
    private val contentSizes = mutableListOf<Pair<Int, Int>>()
    private val visibilityEvents = mutableListOf<Boolean>()

    private var scenario: ReplayProbeScenario? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var markerView: View? = null
    private var markerShown = false
    private var audioRecord: AudioRecord? = null
    private var otherAppTone: AudioTrack? = null
    private var probeStartedAtElapsed = 0L
    private var probeFinishedAtElapsed = 0L
    private var gameUid = -1
    private var streamVolume = -1
    private var streamMaxVolume = -1
    private val otherAppTonePlayed = AtomicBoolean(false)
    private var pcmSummary = PcmEnergySummary(0, 0, 0, 0.0, Double.NEGATIVE_INFINITY)

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Replay Phase 0 probe", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested.set(true)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || scenario != null) return START_NOT_STICKY

        val requestedScenario = ReplayProbeScenario.fromWireName(intent.getStringExtra(EXTRA_SCENARIO))
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (requestedScenario == null || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        scenario = requestedScenario
        startProjectionForeground(requestedScenario)

        runCatching {
            prepareProbe(intent.getIntExtra(EXTRA_RESULT_CODE, 0), resultData)
        }.onFailure { failure ->
            finishProbe(failure)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProjectionForeground(scenario: ReplayProbeScenario) {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ReplayProbeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Replay Phase 0 probe")
            .setContentText("Running ${scenario.wireName} for 10 seconds")
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    @SuppressLint("MissingPermission")
    private fun prepareProbe(resultCode: Int, resultData: Intent) {
        check(Settings.canDrawOverlays(this)) { "Overlay permission is required" }
        gameUid = packageManager.getApplicationInfo(
            GAME_PACKAGE,
            PackageManager.ApplicationInfoFlags.of(0),
        ).uid
        val audioManager = getSystemService(AudioManager::class.java)
        streamVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        streamMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val activeProjection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, resultData)
            ?: error("MediaProjectionManager returned no session")
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                projectionStopped.set(true)
                stopRequested.set(true)
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                synchronized(contentSizes) { contentSizes += width to height }
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                synchronized(visibilityEvents) { visibilityEvents += isVisible }
            }
        }
        activeProjection.registerCallback(callback, mainHandler)

        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val density = resources.displayMetrics.densityDpi
        val thread = HandlerThread("replay-probe-frames").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ source -> analyzeLatestFrame(source) }, handler)
        }
        val display = activeProjection.createVirtualDisplay(
            "pokemon-champions-replay-phase0-probe",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        ) ?: error("MediaProjection returned no VirtualDisplay")

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(activeProjection)
            .addMatchingUid(gameUid)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .setBufferSizeInBytes(max(minimumBuffer * 2, SAMPLE_RATE * CHANNEL_COUNT))
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }

        projection = activeProjection
        projectionCallback = callback
        imageReader = reader
        imageThread = thread
        virtualDisplay = display
        audioRecord = record
        showIsolationMarker()

        thread(name = "replay-probe-audio") {
            runProbeLoop()
        }
    }

    private fun analyzeLatestFrame(source: ImageReader) {
        val image = source.acquireLatestImage() ?: return
        try {
            frameCount.incrementAndGet()
            val now = SystemClock.elapsedRealtime()
            val previous = lastFrameScanMs.get()
            if (now - previous < 200L || !lastFrameScanMs.compareAndSet(previous, now)) return

            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val limit = buffer.limit()
            var sampled = 0L
            var magenta = 0L
            var cyan = 0L
            for (y in 0 until image.height step 8) {
                for (x in 0 until image.width step 8) {
                    val offset = y * rowStride + x * pixelStride
                    if (offset + 2 >= limit) continue
                    val red = buffer.get(offset).toInt() and 0xff
                    val green = buffer.get(offset + 1).toInt() and 0xff
                    val blue = buffer.get(offset + 2).toInt() and 0xff
                    sampled += 1
                    if (red >= 240 && green <= 20 && blue >= 240) magenta += 1
                    if (red <= 20 && green >= 240 && blue >= 240) cyan += 1
                }
            }
            sampledPixelCount.addAndGet(sampled)
            magentaPixelCount.addAndGet(magenta)
            cyanPixelCount.addAndGet(cyan)
            analyzedFrameCount.incrementAndGet()
        } finally {
            image.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun runProbeLoop() {
        var failure: Throwable? = null
        val accumulator = PcmEnergyAccumulator()
        var toneWriter: Thread? = null
        try {
            val record = checkNotNull(audioRecord)
            val currentScenario = checkNotNull(scenario)
            probeStartedAtElapsed = SystemClock.elapsedRealtime()
            val toneOutput = if (currentScenario == ReplayProbeScenario.OTHER_APP_TONE) createOtherAppTone() else null
            val tone = toneOutput?.first
            otherAppTone = tone
            record.startRecording()
            tone?.play()

            val buffer = ShortArray(4096)
            val deadline = probeStartedAtElapsed + PROBE_DURATION_MS
            if (tone != null) {
                val toneLoop = toneOutput.second
                toneWriter = thread(name = "replay-probe-control-tone") {
                    while (!stopRequested.get() && SystemClock.elapsedRealtime() < deadline) {
                        val written = tone.write(toneLoop, 0, toneLoop.size, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) break
                        if (written > 0 && tone.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            otherAppTonePlayed.set(true)
                        }
                    }
                }
            }
            while (!stopRequested.get() && SystemClock.elapsedRealtime() < deadline) {
                val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) accumulator.add(buffer, count)
                if (count == AudioRecord.ERROR_DEAD_OBJECT || count == AudioRecord.ERROR_INVALID_OPERATION) {
                    error("AudioRecord read failed: $count")
                }
            }
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            probeFinishedAtElapsed = SystemClock.elapsedRealtime()
            pcmSummary = accumulator.summary()
            runCatching { otherAppTone?.stop() }
            runCatching { toneWriter?.join(1_000L) }
            runCatching { otherAppTone?.release() }
            otherAppTone = null
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            audioRecord = null
            mainHandler.post { finishProbe(failure) }
        }
    }

    private fun createOtherAppTone(): Pair<AudioTrack, ShortArray> {
        // Some ColorOS audio HALs reject MODE_STATIC tracks. Stream a small,
        // repeated 100 ms buffer instead so the control works on those devices.
        val loop = ShortArray(TONE_LOOP_FRAMES * CHANNEL_COUNT)
        for (frame in 0 until TONE_LOOP_FRAMES) {
            val sample = (sin(2.0 * PI * 440.0 * frame / SAMPLE_RATE) * Short.MAX_VALUE * 0.25).roundToInt().toShort()
            loop[frame * CHANNEL_COUNT] = sample
            loop[frame * CHANNEL_COUNT + 1] = sample
        }
        val minimumBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBuffer > 0) { "AudioTrack minimum buffer query failed: $minimumBuffer" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minimumBuffer, loop.size * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" }
        return track to loop
    }

    private fun showIsolationMarker() {
        val windowManager = getSystemService(WindowManager::class.java)
        val size = (resources.displayMetrics.density * 144).roundToInt()
        val marker = IsolationMarkerView(this)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 180
        }
        windowManager.addView(marker, params)
        markerView = marker
        markerShown = true
    }

    private fun finishProbe(failure: Throwable?) {
        val reportPath = runCatching { writeReport(failure) }.getOrNull()
        Log.i(LOG_TAG, "Replay probe finished: report=$reportPath error=${failure?.message}")
        sendBroadcast(
            Intent(ACTION_RESULT).setPackage(packageName).apply {
                putExtra(EXTRA_REPORT_PATH, reportPath)
                failure?.message?.let { putExtra(EXTRA_ERROR, it) }
            },
        )
        cleanup()
        stopSelf()
    }

    private fun writeReport(failure: Throwable?): String {
        val marker = MarkerColorSummary(
            sampledPixels = sampledPixelCount.get(),
            magentaPixels = magentaPixelCount.get(),
            cyanPixels = cyanPixelCount.get(),
        )
        val currentScenario = scenario
        val sizes = synchronized(contentSizes) {
            JSONArray().also { array ->
                contentSizes.forEach { (width, height) ->
                    array.put(JSONObject().put("width", width).put("height", height))
                }
            }
        }
        val visibility = synchronized(visibilityEvents) {
            JSONArray().also { array -> visibilityEvents.forEach(array::put) }
        }
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("capturedAt", Instant.now().toString())
            .put("scenario", currentScenario?.wireName)
            .put("durationMs", (probeFinishedAtElapsed - probeStartedAtElapsed).coerceAtLeast(0))
            .put("gamePackage", GAME_PACKAGE)
            .put("gameUid", gameUid)
            .put("probePackage", packageName)
            .put("streamVolume", streamVolume)
            .put("streamMaxVolume", streamMaxVolume)
            .put("projectionStopped", projectionStopped.get())
            .put("error", failure?.stackTraceToString())
            .put(
                "video",
                JSONObject()
                    .put("framesSeen", frameCount.get())
                    .put("framesAnalyzed", analyzedFrameCount.get())
                    .put("markerShown", markerShown)
                    .put("sampledPixels", marker.sampledPixels)
                    .put("magentaPixels", marker.magentaPixels)
                    .put("cyanPixels", marker.cyanPixels)
                    .put("markerDetected", marker.markerDetected)
                    .put("singleAppIsolationPass", markerShown && analyzedFrameCount.get() > 0 && !marker.markerDetected)
                    .put("capturedContentSizes", sizes)
                    .put("visibilityEvents", visibility),
            )
            .put(
                "audio",
                JSONObject()
                    .put("sampleRate", SAMPLE_RATE)
                    .put("channelCount", CHANNEL_COUNT)
                    .put("totalSamples", pcmSummary.totalSamples)
                    .put("nonZeroSamples", pcmSummary.nonZeroSamples)
                    .put("nonZeroRatio", pcmSummary.nonZeroRatio)
                    .put("peakAmplitude", pcmSummary.peakAmplitude)
                    .put("rms", pcmSummary.rms)
                    .put("dbfs", if (pcmSummary.dbfs.isFinite()) pcmSummary.dbfs else JSONObject.NULL)
                    .put("signalDetected", pcmSummary.signalDetected)
                    .put("otherAppTonePlayed", otherAppTonePlayed.get()),
            )

        val directory = File(filesDir, "replay-probe").apply { mkdirs() }
        val stamped = File(directory, "${System.currentTimeMillis()}-${currentScenario?.wireName ?: "unknown"}.json")
        val serialized = root.toString(2)
        stamped.writeText(serialized)
        File(directory, "latest.json").writeText(serialized)
        return stamped.absolutePath
    }

    private fun cleanup() {
        markerView?.let { marker ->
            runCatching { getSystemService(WindowManager::class.java).removeView(marker) }
        }
        markerView = null
        imageReader?.setOnImageAvailableListener(null, null)
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        projectionCallback?.let { callback -> runCatching { projection?.unregisterCallback(callback) } }
        runCatching { projection?.stop() }
        imageThread?.quitSafely()
        virtualDisplay = null
        imageReader = null
        projectionCallback = null
        projection = null
        imageThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopRequested.set(true)
        cleanup()
        super.onDestroy()
    }

    private class IsolationMarkerView(context: Context) : View(context) {
        private val paint = Paint().apply { isAntiAlias = false }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK)
            val inset = width * 0.08f
            val middleX = width / 2f
            val middleY = height / 2f
            paint.color = Color.MAGENTA
            canvas.drawRect(inset, inset, middleX, middleY, paint)
            canvas.drawRect(middleX, middleY, width - inset, height - inset, paint)
            paint.color = Color.CYAN
            canvas.drawRect(middleX, inset, width - inset, middleY, paint)
            canvas.drawRect(inset, middleY, middleX, height - inset, paint)
        }
    }
}
