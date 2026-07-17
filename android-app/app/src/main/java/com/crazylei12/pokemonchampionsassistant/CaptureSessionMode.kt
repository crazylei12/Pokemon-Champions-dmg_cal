package com.crazylei12.pokemonchampionsassistant

enum class CaptureSessionState {
    IDLE,
    PREPARING_PROJECTION,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
}

enum class ReplaySessionState {
    IDLE,
    AWAITING_AUDIO_PERMISSION,
    AWAITING_AUDIO_FALLBACK,
    STARTING,
    RUNNING,
    STOPPING,
}

enum class ReplayAudioDecision(val wireName: String) {
    WITH_AUDIO("with_audio"),
    SILENT("silent"),
    CANCEL("cancel"),
    ;

    companion object {
        fun fromWireName(value: String?): ReplayAudioDecision? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * Owns the long-lived projection/recognition session and the independently toggled replay state.
 * Recording must never decide whether recognition, correction, or the damage overlay remains alive.
 */
class CaptureSessionStateMachine {
    var state: CaptureSessionState = CaptureSessionState.IDLE
        private set
    var replayState: ReplaySessionState = ReplaySessionState.IDLE
        private set
    var audioDecision: ReplayAudioDecision? = null
        private set
    var virtualDisplayCreated: Boolean = false
        private set

    fun beginProjectionPreparation() {
        requireState(CaptureSessionState.IDLE)
        state = CaptureSessionState.PREPARING_PROJECTION
    }

    fun projectionReady() {
        requireState(CaptureSessionState.PREPARING_PROJECTION)
        state = CaptureSessionState.STARTING
    }

    fun markVirtualDisplayCreated() {
        requireState(CaptureSessionState.STARTING)
        check(!virtualDisplayCreated) { "A projection token may create only one VirtualDisplay" }
        virtualDisplayCreated = true
    }

    fun started() {
        requireState(CaptureSessionState.STARTING)
        check(virtualDisplayCreated) { "The capture surface must exist before the session can run" }
        state = CaptureSessionState.RUNNING
    }

    fun requestReplayStart(audioPermissionGranted: Boolean): Boolean {
        requireState(CaptureSessionState.RUNNING)
        if (replayState != ReplaySessionState.IDLE) return false
        audioDecision = if (audioPermissionGranted) ReplayAudioDecision.WITH_AUDIO else null
        replayState = if (audioPermissionGranted) {
            ReplaySessionState.STARTING
        } else {
            ReplaySessionState.AWAITING_AUDIO_PERMISSION
        }
        return true
    }

    fun resolveAudioPermission(decision: ReplayAudioDecision) {
        requireReplayState(ReplaySessionState.AWAITING_AUDIO_PERMISSION)
        audioDecision = decision
        replayState = if (decision == ReplayAudioDecision.CANCEL) {
            audioDecision = null
            ReplaySessionState.IDLE
        } else {
            ReplaySessionState.STARTING
        }
    }

    fun audioSignalUnavailable() {
        requireReplayState(ReplaySessionState.STARTING)
        check(audioDecision == ReplayAudioDecision.WITH_AUDIO)
        replayState = ReplaySessionState.AWAITING_AUDIO_FALLBACK
    }

    fun resolveAudioFallback(decision: ReplayAudioDecision) {
        requireReplayState(ReplaySessionState.AWAITING_AUDIO_FALLBACK)
        check(decision != ReplayAudioDecision.WITH_AUDIO) {
            "Audio signal fallback must be canceled or explicitly silent"
        }
        audioDecision = decision
        replayState = if (decision == ReplayAudioDecision.CANCEL) {
            audioDecision = null
            ReplaySessionState.IDLE
        } else {
            ReplaySessionState.STARTING
        }
    }

    fun replayStarted() {
        requireState(CaptureSessionState.RUNNING)
        requireReplayState(ReplaySessionState.STARTING)
        replayState = ReplaySessionState.RUNNING
    }

    fun requestReplayStop(): Boolean {
        if (state != CaptureSessionState.RUNNING || replayState != ReplaySessionState.RUNNING) return false
        replayState = ReplaySessionState.STOPPING
        return true
    }

    fun replayStopped() {
        requireReplayState(ReplaySessionState.STOPPING)
        replayState = ReplaySessionState.IDLE
        audioDecision = null
    }

    fun abortReplayStart() {
        check(
            replayState in setOf(
                ReplaySessionState.AWAITING_AUDIO_PERMISSION,
                ReplaySessionState.AWAITING_AUDIO_FALLBACK,
                ReplaySessionState.STARTING,
            ),
        ) { "Replay startup is not in progress" }
        replayState = ReplaySessionState.IDLE
        audioDecision = null
    }

    fun requestStop(): Boolean {
        if (state == CaptureSessionState.STOPPING || state == CaptureSessionState.FAILED) return false
        state = CaptureSessionState.STOPPING
        if (replayState == ReplaySessionState.RUNNING) replayState = ReplaySessionState.STOPPING
        return true
    }

    fun stopped() {
        requireState(CaptureSessionState.STOPPING)
        state = CaptureSessionState.IDLE
        replayState = ReplaySessionState.IDLE
        audioDecision = null
        virtualDisplayCreated = false
    }

    fun fail() {
        state = CaptureSessionState.FAILED
        replayState = ReplaySessionState.IDLE
        audioDecision = null
    }

    private fun requireState(expected: CaptureSessionState) {
        check(state == expected) { "Expected capture state $expected but was $state" }
    }

    private fun requireReplayState(expected: ReplaySessionState) {
        check(replayState == expected) { "Expected replay state $expected but was $replayState" }
    }
}
