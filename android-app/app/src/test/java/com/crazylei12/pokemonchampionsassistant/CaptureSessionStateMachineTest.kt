package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionStateMachineTest {
    @Test
    fun `recognition-only mode starts without audio permission and creates one display`() {
        val machine = preparedMachine()

        machine.selectMode(CaptureSessionMode.RECOGNIZE_ONLY, audioPermissionGranted = false)
        assertEquals(CaptureSessionState.STARTING, machine.state)
        assertNull(machine.audioDecision)
        machine.markVirtualDisplayCreated()
        machine.started()

        assertEquals(CaptureSessionState.RUNNING, machine.state)
        assertTrue(machine.virtualDisplayCreated)
        assertTrue(machine.mode!!.includesRecognition)
        assertFalse(machine.mode!!.includesReplay)
    }

    @Test
    fun `replay mode waits for an explicit audio choice`() {
        val machine = preparedMachine()

        machine.selectMode(CaptureSessionMode.RECORD_ONLY, audioPermissionGranted = false)
        assertEquals(CaptureSessionState.AWAITING_AUDIO_PERMISSION, machine.state)
        assertFalse(machine.mode!!.includesRecognition)

        machine.resolveAudioPermission(ReplayAudioDecision.SILENT)
        assertEquals(CaptureSessionState.STARTING, machine.state)
        assertEquals(ReplayAudioDecision.SILENT, machine.audioDecision)
    }

    @Test
    fun `granted replay audio skips the permission wait`() {
        val machine = preparedMachine()

        machine.selectMode(CaptureSessionMode.RECOGNIZE_AND_RECORD, audioPermissionGranted = true)

        assertEquals(CaptureSessionState.STARTING, machine.state)
        assertEquals(ReplayAudioDecision.WITH_AUDIO, machine.audioDecision)
    }

    @Test
    fun `canceling replay permission moves the session to stopping`() {
        val machine = preparedMachine()
        machine.selectMode(CaptureSessionMode.RECORD_ONLY, audioPermissionGranted = false)

        machine.resolveAudioPermission(ReplayAudioDecision.CANCEL)

        assertEquals(CaptureSessionState.STOPPING, machine.state)
    }

    @Test
    fun `missing playback signal requires an explicit silent fallback`() {
        val machine = preparedMachine()
        machine.selectMode(CaptureSessionMode.RECORD_ONLY, audioPermissionGranted = true)

        machine.audioSignalUnavailable()
        assertEquals(CaptureSessionState.AWAITING_AUDIO_FALLBACK, machine.state)
        machine.resolveAudioFallback(ReplayAudioDecision.SILENT)

        assertEquals(CaptureSessionState.STARTING, machine.state)
        assertEquals(ReplayAudioDecision.SILENT, machine.audioDecision)
    }

    @Test
    fun `mode is locked and a projection token cannot create two displays`() {
        val machine = preparedMachine()
        machine.selectMode(CaptureSessionMode.RECOGNIZE_ONLY, audioPermissionGranted = false)
        machine.markVirtualDisplayCreated()

        expectIllegalState { machine.markVirtualDisplayCreated() }
        expectIllegalState { machine.selectMode(CaptureSessionMode.RECORD_ONLY, audioPermissionGranted = true) }
    }

    @Test
    fun `repeated stop is idempotent`() {
        val machine = runningMachine()

        assertTrue(machine.requestStop())
        assertFalse(machine.requestStop())
        machine.stopped()

        assertEquals(CaptureSessionState.IDLE, machine.state)
    }

    @Test
    fun `a stopped session clears its token-scoped mode and can prepare again`() {
        val machine = runningMachine()
        machine.requestStop()

        machine.stopped()
        assertNull(machine.mode)
        assertNull(machine.audioDecision)
        assertFalse(machine.virtualDisplayCreated)

        machine.beginProjectionPreparation()
        machine.projectionReady()
        machine.selectMode(CaptureSessionMode.RECORD_ONLY, audioPermissionGranted = true)
        assertEquals(CaptureSessionMode.RECORD_ONLY, machine.mode)
    }

    @Test
    fun `session cannot run before its display exists`() {
        val machine = preparedMachine()
        machine.selectMode(CaptureSessionMode.RECOGNIZE_ONLY, audioPermissionGranted = false)

        expectIllegalState(machine::started)
    }

    @Test
    fun `wire names parse without accepting unknown values`() {
        assertEquals(
            CaptureSessionMode.RECOGNIZE_AND_RECORD,
            CaptureSessionMode.fromWireName("recognize_and_record"),
        )
        assertNull(CaptureSessionMode.fromWireName("unsupported"))
        assertEquals(ReplayAudioDecision.SILENT, ReplayAudioDecision.fromWireName("silent"))
    }

    private fun preparedMachine(): CaptureSessionStateMachine = CaptureSessionStateMachine().apply {
        beginProjectionPreparation()
        projectionReady()
    }

    private fun runningMachine(): CaptureSessionStateMachine = preparedMachine().apply {
        selectMode(CaptureSessionMode.RECOGNIZE_ONLY, audioPermissionGranted = false)
        markVirtualDisplayCreated()
        started()
    }

    private fun expectIllegalState(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }
}
