package com.crazylei12.pokemonchampionsassistant.replay

import android.content.Context
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.crazylei12.pokemonchampionsassistant.CaptureBufferSpec
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface ReplayPreparationResult {
    data class Ready(
        val captureSurface: Surface,
        val hasAudio: Boolean,
        val audioChannelCount: Int,
        val gameUid: Int?,
        val videoCodecName: String,
        val videoProfile: ReplayVideoProfile,
    ) : ReplayPreparationResult

    data class AudioUnavailable(val summary: ReplayPcmSignalSummary) : ReplayPreparationResult
    data class Failed(val error: Throwable) : ReplayPreparationResult
}

internal data class ReplayStartInfo(
    val audioChannelCount: Int,
    val videoProfile: ReplayVideoProfile,
)

internal sealed interface ReplayFinalizeResult {
    data class Saved(val replay: SavedReplay) : ReplayFinalizeResult
    data class Failed(val error: Throwable) : ReplayFinalizeResult
}

internal class ReplayRecorder(
    context: Context,
    private val projection: MediaProjection,
    private val initialSpec: CaptureBufferSpec,
    private val requestAudio: Boolean,
    private val onRuntimeFailure: (Throwable) -> Unit,
) : AutoCloseable {
    companion object {
        private const val LOG_TAG = "ReplayRecorder"
        private const val STEREO_PREFLIGHT_MAX_MS = 700L
        private const val MONO_PREFLIGHT_MAX_MS = 500L
    }

    private val applicationContext = context.applicationContext
    private val mediaStore = ReplayMediaStore(applicationContext)
    private val closing = AtomicBoolean(false)
    private var pending: PendingReplayMedia? = null
    private var muxer: Mp4MuxerCoordinator? = null
    private var videoEncoder: ReplayVideoEncoder? = null
    private var audioCapture: GameAudioCapture? = null
    private var router: EglProjectionRouter? = null
    private var prepared = false
    private var started = false
    private var published = false
    private var hasAudioTrack = false
    private var startedAtElapsedMs = 0L

    val isStarted: Boolean
        get() = started

    fun prepare(): ReplayPreparationResult {
        check(!prepared) { "Replay recorder was already prepared" }
        var preparingAudio: GameAudioCapture? = null
        return try {
            val audio = if (requestAudio) {
                var selectedCapture: GameAudioCapture? = null
                var bestSummary = ReplayPcmSignalSummary(0L, 0L, 0, 0.0, Double.NEGATIVE_INFINITY)
                var lastFailure: Throwable? = null
                val candidates = listOf(
                    2 to STEREO_PREFLIGHT_MAX_MS,
                    1 to MONO_PREFLIGHT_MAX_MS,
                )
                candidates.forEach { (channelCount, maxDurationMs) ->
                    if (selectedCapture != null) return@forEach
                    val preflightCapture = try {
                        GameAudioCapture.create(
                            context = applicationContext,
                            projection = projection,
                            channelCount = channelCount,
                        ).also { preparingAudio = it }
                    } catch (error: Throwable) {
                        lastFailure = error
                        Log.w(LOG_TAG, "Could not create $channelCount-channel playback preflight", error)
                        return@forEach
                    }
                    val summary = try {
                        preflightCapture.preflight(maxDurationMs)
                    } catch (error: Throwable) {
                        lastFailure = error
                        Log.w(LOG_TAG, "$channelCount-channel playback preflight failed", error)
                        null
                    } finally {
                        preflightCapture.close()
                        preparingAudio = null
                    }
                    if (summary != null) {
                        if (summary.totalSamples > bestSummary.totalSamples || summary.peakAmplitude > bestSummary.peakAmplitude) {
                            bestSummary = summary
                        }
                        Log.i(
                            LOG_TAG,
                            "Audio preflight: uid=${preflightCapture.gameUid}, channels=$channelCount, " +
                                "samples=${summary.totalSamples}, nonZeroRatio=${summary.nonZeroRatio}, " +
                                "peak=${summary.peakAmplitude}, dbfs=${summary.dbfs}",
                        )
                        if (summary.signalDetected) {
                            selectedCapture = try {
                                // The production AudioRecord must be fresh. Some ColorOS builds leave a
                                // stopped playback-capture instance in a terminal state after preflight.
                                GameAudioCapture.create(
                                    context = applicationContext,
                                    projection = projection,
                                    channelCount = channelCount,
                                ).also { preparingAudio = it }
                            } catch (error: Throwable) {
                                lastFailure = error
                                Log.w(LOG_TAG, "Could not create production $channelCount-channel capture", error)
                                null
                            }
                        }
                    }
                }
                selectedCapture ?: if (bestSummary.totalSamples > 0L) {
                    return ReplayPreparationResult.AudioUnavailable(bestSummary)
                } else {
                    throw lastFailure ?: IllegalStateException("No playback-capture audio format is available")
                }
            } else {
                null
            }
            audioCapture = audio
            preparingAudio = null
            hasAudioTrack = audio != null
            val pendingItem = mediaStore.createPending()
            pending = pendingItem
            val coordinator = Mp4MuxerCoordinator(pendingItem.descriptor, expectAudio = audio != null)
            muxer = coordinator
            val video = ReplayVideoEncoder(coordinator, onRuntimeFailure)
            videoEncoder = video
            val eglRouter = EglProjectionRouter(
                encoderSurface = video.inputSurface,
                initialSpec = initialSpec,
                videoProfile = video.profile,
                onFailure = onRuntimeFailure,
            )
            router = eglRouter
            prepared = true
            ReplayPreparationResult.Ready(
                captureSurface = eglRouter.captureSurface,
                hasAudio = audio != null,
                audioChannelCount = audio?.channelCount ?: 0,
                gameUid = audio?.gameUid,
                videoCodecName = video.codecName,
                videoProfile = video.profile,
            )
        } catch (error: Throwable) {
            preparingAudio?.close()
            close()
            ReplayPreparationResult.Failed(error)
        }
    }

    fun startIsolationProbe(callback: (Boolean) -> Unit) {
        check(prepared && !started)
        checkNotNull(router).startIsolationProbe(callback)
    }

    fun cancelIsolationProbe() {
        router?.cancelIsolationProbe()
    }

    fun start(): ReplayStartInfo {
        check(prepared && !started) { "Replay recorder is not ready to start" }
        val coordinator = checkNotNull(muxer)
        val audioChannelCount = startAudioWithFallback(coordinator)
        checkNotNull(router).startRecordingAndAwait()
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        started = true
        return ReplayStartInfo(
            audioChannelCount = audioChannelCount,
            videoProfile = checkNotNull(videoEncoder).profile,
        )
    }

    private fun startAudioWithFallback(coordinator: Mp4MuxerCoordinator): Int {
        val initialCapture = audioCapture ?: return 0
        try {
            initialCapture.start(coordinator, onRuntimeFailure)
            return initialCapture.channelCount
        } catch (stereoFailure: Throwable) {
            if (initialCapture.channelCount != 2) throw stereoFailure
            Log.w(LOG_TAG, "Stereo playback capture could not start; retrying with mono", stereoFailure)
            initialCapture.close()
            audioCapture = null
            val monoCapture = try {
                GameAudioCapture.create(
                    context = applicationContext,
                    projection = projection,
                    channelCount = 1,
                )
            } catch (monoCreateFailure: Throwable) {
                monoCreateFailure.addSuppressed(stereoFailure)
                throw monoCreateFailure
            }
            audioCapture = monoCapture
            try {
                monoCapture.start(coordinator, onRuntimeFailure)
            } catch (monoStartFailure: Throwable) {
                monoStartFailure.addSuppressed(stereoFailure)
                throw monoStartFailure
            }
            return monoCapture.channelCount
        }
    }

    fun updateInputSpec(spec: CaptureBufferSpec) {
        router?.updateInputSpec(spec)
    }

    fun requestRecognitionFrame(callback: (Result<ReplayRecognitionFrame>) -> Unit) {
        if (!started || closing.get()) {
            callback(Result.failure(IllegalStateException("Replay recorder is not running")))
            return
        }
        checkNotNull(router).requestRecognitionFrame(callback)
    }

    fun cancelRecognitionFrameRequest() {
        router?.cancelRecognitionFrameRequest()
    }

    fun elapsedMs(): Long = if (started) {
        maxOf(router?.elapsedMs() ?: 0L, SystemClock.elapsedRealtime() - startedAtElapsedMs)
    } else {
        0L
    }

    fun stopAndFinalize(): ReplayFinalizeResult {
        if (!closing.compareAndSet(false, true)) {
            return ReplayFinalizeResult.Failed(IllegalStateException("Replay finalization already started"))
        }
        return try {
            check(started) { "Replay was not started" }
            router?.stopInputAndAwait()
            audioCapture?.signalEndAndAwait()
            videoEncoder?.signalEndAndAwait()
            val durationFromMuxer = checkNotNull(muxer).finish()
            val durationMs = maxOf(durationFromMuxer, router?.elapsedMs() ?: 0L)
            router?.close(); router = null
            audioCapture?.close(); audioCapture = null
            videoEncoder?.close(); videoEncoder = null
            muxer = null
            val pendingItem = checkNotNull(pending)
            val replay = mediaStore.publish(
                pending = pendingItem,
                durationMs = durationMs,
                hasAudio = hasAudioTrack,
            )
            pending = null
            published = true
            ReplayFinalizeResult.Saved(replay)
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "Replay finalization failed", error)
            discardResources()
            ReplayFinalizeResult.Failed(error)
        }
    }

    private fun discardResources() {
        router?.close(); router = null
        audioCapture?.close(); audioCapture = null
        videoEncoder?.close(); videoEncoder = null
        muxer?.close(); muxer = null
        if (!published) mediaStore.discard(pending)
        pending = null
    }

    override fun close() {
        closing.set(true)
        discardResources()
    }
}
