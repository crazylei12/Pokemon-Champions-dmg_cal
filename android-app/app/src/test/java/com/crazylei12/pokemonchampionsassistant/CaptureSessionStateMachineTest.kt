package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionStateMachineTest {
    @Test
    fun `projection starts recognition before recording is requested`() {
        val machine = runningMachine()

        assertEquals(CaptureSessionState.RUNNING, machine.state)
        assertEquals(ReplaySessionState.IDLE, machine.replayState)
        assertTrue(machine.virtualDisplayCreated)
        assertNull(machine.audioDecision)
    }

    @Test
    fun `recording waits for audio permission without changing assistant state`() {
        val machine = runningMachine()

        assertTrue(machine.requestReplayStart(audioPermissionGranted = false))

        assertEquals(CaptureSessionState.RUNNING, machine.state)
        assertEquals(ReplaySessionState.AWAITING_AUDIO_PERMISSION, machine.replayState)
        assertNull(machine.audioDecision)
    }

    @Test
    fun `granted audio starts recording independently`() {
        val machine = runningMachine()

        assertTrue(machine.requestReplayStart(audioPermissionGranted = true))
        assertEquals(ReplaySessionState.STARTING, machine.replayState)
        assertEquals(ReplayAudioDecision.WITH_AUDIO, machine.audioDecision)
        machine.replayStarted()

        assertEquals(CaptureSessionState.RUNNING, machine.state)
        assertEquals(ReplaySessionState.RUNNING, machine.replayState)
    }

    @Test
    fun `stopping recording leaves the assistant running`() {
        val machine = recordingMachine()

        assertTrue(machine.requestReplayStop())
        assertEquals(ReplaySessionState.STOPPING, machine.replayState)
        machine.replayStopped()

        assertEquals(CaptureSessionState.RUNNING, machine.state)
        assertEquals(ReplaySessionState.IDLE, machine.replayState)
        assertNull(machine.audioDecision)
        assertTrue(machine.virtualDisplayCreated)
    }

    @Test
    fun `recording can start again after an independent stop`() {
        val machine = recordingMachine()
        machine.requestReplayStop()
        machine.replayStopped()

        assertTrue(machine.requestReplayStart(audioPermissionGranted = true))
        assertEquals(ReplaySessionState.STARTING, machine.replayState)
    }

    @Test
    fun `canceling audio permission only cancels recording`() {
        val machine = runningMachine()
        machine.requestReplayStart(audioPermissionGranted = false)

        machine.resolveAudioPermission(ReplayAudioDecision.CANCEL)

        assertEquals(CaptureSessionState.RUNNING, machine.state)
        assertEquals(ReplaySessionState.IDLE, machine.replayState)
    }

    @Test
    fun `missing playback signal requires an explicit silent fallback`() {
        val machine = runningMachine()
        machine.requestReplayStart(audioPermissionGranted = true)

        machine.audioSignalUnavailable()
        assertEquals(ReplaySessionState.AWAITING_AUDIO_FALLBACK, machine.replayState)
        machine.resolveAudioFallback(ReplayAudioDecision.SILENT)

        assertEquals(ReplaySessionState.STARTING, machine.replayState)
        assertEquals(ReplayAudioDecision.SILENT, machine.audioDecision)
    }

    @Test
    fun `duplicate recording start and stop requests are idempotent`() {
        val machine = runningMachine()

        assertTrue(machine.requestReplayStart(audioPermissionGranted = true))
        assertFalse(machine.requestReplayStart(audioPermissionGranted = true))
        machine.replayStarted()
        assertTrue(machine.requestReplayStop())
        assertFalse(machine.requestReplayStop())
    }

    @Test
    fun `ending assistant also marks active recording as stopping`() {
        val machine = recordingMachine()

        assertTrue(machine.requestStop())

        assertEquals(CaptureSessionState.STOPPING, machine.state)
        assertEquals(ReplaySessionState.STOPPING, machine.replayState)
        machine.stopped()
        assertEquals(CaptureSessionState.IDLE, machine.state)
        assertEquals(ReplaySessionState.IDLE, machine.replayState)
        assertFalse(machine.virtualDisplayCreated)
    }

    @Test
    fun `projection still creates only one virtual display`() {
        val machine = CaptureSessionStateMachine().apply {
            beginProjectionPreparation()
            projectionReady()
            markVirtualDisplayCreated()
        }

        expectIllegalState(machine::markVirtualDisplayCreated)
    }

    @Test
    fun `wire names parse without accepting unknown values`() {
        assertEquals(ReplayAudioDecision.SILENT, ReplayAudioDecision.fromWireName("silent"))
        assertNull(ReplayAudioDecision.fromWireName("unsupported"))
    }

    private fun runningMachine(): CaptureSessionStateMachine = CaptureSessionStateMachine().apply {
        beginProjectionPreparation()
        projectionReady()
        markVirtualDisplayCreated()
        started()
    }

    private fun recordingMachine(): CaptureSessionStateMachine = runningMachine().apply {
        requestReplayStart(audioPermissionGranted = true)
        replayStarted()
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
