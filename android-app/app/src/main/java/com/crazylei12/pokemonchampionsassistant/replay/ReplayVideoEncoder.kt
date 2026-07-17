package com.crazylei12.pokemonchampionsassistant.replay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal class ReplayVideoEncoder(
    private val muxer: Mp4MuxerCoordinator,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val codec: MediaCodec
    val inputSurface: Surface
    val codecName: String

    private val drainFinished = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>(null)
    private val eosSignaled = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    init {
        var createdCodec: MediaCodec? = null
        var createdSurface: Surface? = null
        try {
            val activeCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .also { createdCodec = it }
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                REPLAY_VIDEO_WIDTH,
                REPLAY_VIDEO_HEIGHT,
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, REPLAY_VIDEO_BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, REPLAY_VIDEO_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            activeCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = activeCodec.createInputSurface().also { createdSurface = it }
            activeCodec.start()
            codec = activeCodec
            inputSurface = surface
            codecName = activeCodec.name
            thread(name = "replay-video-drain") { drainLoop() }
        } catch (error: Throwable) {
            runCatching { createdSurface?.release() }
            runCatching { createdCodec?.stop() }
            runCatching { createdCodec?.release() }
            throw error
        }
    }

    fun signalEndAndAwait(timeoutSeconds: Long = 8L) {
        if (eosSignaled.compareAndSet(false, true)) codec.signalEndOfInputStream()
        check(drainFinished.await(timeoutSeconds, TimeUnit.SECONDS)) { "Timed out draining H.264 encoder" }
        failure.get()?.let { throw it }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            var outputEnded = false
            while (!outputEnded) {
                when (val index = codec.dequeueOutputBuffer(info, 20_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        muxer.addTrack(ReplayTrackKind.VIDEO, codec.outputFormat)
                    else -> if (index >= 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null && info.size > 0) {
                            muxer.writeSample(ReplayTrackKind.VIDEO, output, info)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (error: Throwable) {
            if (!released.get() && failure.compareAndSet(null, error)) onFailure(error)
        } finally {
            drainFinished.countDown()
        }
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        runCatching { inputSurface.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}
