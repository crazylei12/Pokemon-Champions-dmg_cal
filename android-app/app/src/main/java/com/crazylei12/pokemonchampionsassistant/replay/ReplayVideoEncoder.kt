package com.crazylei12.pokemonchampionsassistant.replay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
    val profile: ReplayVideoProfile

    private val drainFinished = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>(null)
    private val eosSignaled = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    init {
        var createdCodec: MediaCodec? = null
        var createdSurface: Surface? = null
        try {
            val configured = configureEncoder().also {
                createdCodec = it.codec
                createdSurface = it.surface
            }
            val activeCodec = configured.codec
            val surface = configured.surface
            activeCodec.start()
            codec = activeCodec
            inputSurface = surface
            codecName = activeCodec.name
            profile = configured.profile
            thread(name = "replay-video-drain") { drainLoop() }
        } catch (error: Throwable) {
            runCatching { createdSurface?.release() }
            runCatching { createdCodec?.stop() }
            runCatching { createdCodec?.release() }
            throw error
        }
    }

    private data class ConfiguredEncoder(
        val codec: MediaCodec,
        val surface: Surface,
        val profile: ReplayVideoProfile,
    )

    private fun configureEncoder(): ConfiguredEncoder {
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .filter { info ->
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
            }
            .sortedWith(
                compareBy<MediaCodecInfo> { !it.isHardwareAccelerated }
                    .thenBy { it.isSoftwareOnly }
                    .thenBy { it.name },
            )
            .toList()
        check(codecInfos.isNotEmpty()) { "No H.264 encoder is available" }

        val failures = mutableListOf<Throwable>()
        codecInfos.forEach { info ->
            val capabilities = runCatching {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            }.getOrElse { error ->
                failures += error
                return@forEach
            }
            if (!capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) {
                return@forEach
            }
            val videoCapabilities = capabilities.videoCapabilities ?: return@forEach
            replayVideoProfilesForAlignment(
                widthAlignment = videoCapabilities.widthAlignment,
                heightAlignment = videoCapabilities.heightAlignment,
            ).forEach { requestedProfile ->
                if (!videoCapabilities.areSizeAndRateSupported(
                        requestedProfile.width,
                        requestedProfile.height,
                        requestedProfile.framesPerSecond.toDouble(),
                    )
                ) return@forEach
                val selectedProfile = requestedProfile.copy(
                    bitRate = requestedProfile.bitRate.coerceIn(
                        videoCapabilities.bitrateRange.lower,
                        videoCapabilities.bitrateRange.upper,
                    ),
                )
                var candidateCodec: MediaCodec? = null
                var candidateSurface: Surface? = null
                try {
                    val format = MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                        selectedProfile.width,
                        selectedProfile.height,
                    ).apply {
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                        )
                        setInteger(MediaFormat.KEY_BIT_RATE, selectedProfile.bitRate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, selectedProfile.framesPerSecond)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    }
                    val activeCodec = MediaCodec.createByCodecName(info.name).also { candidateCodec = it }
                    activeCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val surface = activeCodec.createInputSurface().also { candidateSurface = it }
                    return ConfiguredEncoder(activeCodec, surface, selectedProfile)
                } catch (error: Throwable) {
                    failures += IllegalStateException(
                        "${info.name} rejected ${selectedProfile.displayLabel}",
                        error,
                    )
                    runCatching { candidateSurface?.release() }
                    runCatching { candidateCodec?.release() }
                }
            }
        }
        throw IllegalStateException(
            "No H.264 encoder accepted the replay profiles: " +
                failures.joinToString(limit = 4) { it.message.orEmpty() },
            failures.firstOrNull(),
        )
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
