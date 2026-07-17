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
    ) : ReplayPreparationResult

    data class AudioUnavailable(val summary: ReplayPcmSignalSummary) : ReplayPreparationResult
    data class Failed(val error: Throwable) : ReplayPreparationResult
}

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
                val capture = GameAudioCapture.create(context = applicationContext, projection = projection)
                    .also { preparingAudio = it }
                val summary = capture.preflight()
                Log.i(
                    LOG_TAG,
                    "Audio preflight: uid=${capture.gameUid}, channels=${capture.channelCount}, " +
                        "samples=${summary.totalSamples}, nonZeroRatio=${summary.nonZeroRatio}, " +
                        "peak=${summary.peakAmplitude}, dbfs=${summary.dbfs}",
                )
                if (!summary.signalDetected) {
                    capture.close()
                    preparingAudio = null
                    return ReplayPreparationResult.AudioUnavailable(summary)
                }
                capture
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

    fun start() {
        check(prepared && !started) { "Replay recorder is not ready to start" }
        val coordinator = checkNotNull(muxer)
        audioCapture?.start(coordinator, onRuntimeFailure)
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        checkNotNull(router).startRecording()
        started = true
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
