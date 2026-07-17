package com.crazylei12.pokemonchampionsassistant

enum class CaptureSessionMode(
    val wireName: String,
    val displayName: String,
    val includesRecognition: Boolean,
    val includesReplay: Boolean,
) {
    RECOGNIZE_AND_RECORD(
        wireName = "recognize_and_record",
        displayName = "识别并录屏",
        includesRecognition = true,
        includesReplay = true,
    ),
    RECOGNIZE_ONLY(
        wireName = "recognize_only",
        displayName = "仅识别",
        includesRecognition = true,
        includesReplay = false,
    ),
    RECORD_ONLY(
        wireName = "record_only",
        displayName = "仅录屏",
        includesRecognition = false,
        includesReplay = true,
    ),
    ;

    companion object {
        fun fromWireName(value: String?): CaptureSessionMode? = entries.firstOrNull { it.wireName == value }
    }
}

enum class CaptureSessionState {
    IDLE,
    PREPARING_PROJECTION,
    AWAITING_MODE,
    AWAITING_AUDIO_PERMISSION,
    AWAITING_AUDIO_FALLBACK,
    STARTING,
    RUNNING,
    STOPPING,
    SAVED,
    FAILED,
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

class CaptureSessionStateMachine {
    var state: CaptureSessionState = CaptureSessionState.IDLE
        private set
    var mode: CaptureSessionMode? = null
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
        state = CaptureSessionState.AWAITING_MODE
    }

    fun selectMode(selectedMode: CaptureSessionMode, audioPermissionGranted: Boolean) {
        requireState(CaptureSessionState.AWAITING_MODE)
        check(mode == null) { "A capture mode is already locked for this projection token" }
        mode = selectedMode
        state = if (selectedMode.includesReplay && !audioPermissionGranted) {
            CaptureSessionState.AWAITING_AUDIO_PERMISSION
        } else {
            if (selectedMode.includesReplay) {
                audioDecision = ReplayAudioDecision.WITH_AUDIO
            }
            CaptureSessionState.STARTING
        }
    }

    fun resolveAudioPermission(decision: ReplayAudioDecision) {
        requireState(CaptureSessionState.AWAITING_AUDIO_PERMISSION)
        audioDecision = decision
        state = if (decision == ReplayAudioDecision.CANCEL) {
            CaptureSessionState.STOPPING
        } else {
            CaptureSessionState.STARTING
        }
    }

    fun audioSignalUnavailable() {
        requireState(CaptureSessionState.STARTING)
        check(mode?.includesReplay == true)
        check(audioDecision == ReplayAudioDecision.WITH_AUDIO)
        state = CaptureSessionState.AWAITING_AUDIO_FALLBACK
    }

    fun resolveAudioFallback(decision: ReplayAudioDecision) {
        requireState(CaptureSessionState.AWAITING_AUDIO_FALLBACK)
        check(decision != ReplayAudioDecision.WITH_AUDIO) {
            "Audio signal fallback must be canceled or explicitly silent"
        }
        audioDecision = decision
        state = if (decision == ReplayAudioDecision.CANCEL) {
            CaptureSessionState.STOPPING
        } else {
            CaptureSessionState.STARTING
        }
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

    fun requestStop(): Boolean {
        if (state == CaptureSessionState.STOPPING || state == CaptureSessionState.SAVED || state == CaptureSessionState.FAILED) {
            return false
        }
        state = CaptureSessionState.STOPPING
        return true
    }

    fun stopped(replaySaved: Boolean = false) {
        requireState(CaptureSessionState.STOPPING)
        if (replaySaved) {
            state = CaptureSessionState.SAVED
        } else {
            state = CaptureSessionState.IDLE
            mode = null
            audioDecision = null
            virtualDisplayCreated = false
        }
    }

    fun fail() {
        check(state != CaptureSessionState.SAVED) { "A saved session cannot fail" }
        state = CaptureSessionState.FAILED
    }

    private fun requireState(expected: CaptureSessionState) {
        check(state == expected) { "Expected capture state $expected but was $state" }
    }
}
