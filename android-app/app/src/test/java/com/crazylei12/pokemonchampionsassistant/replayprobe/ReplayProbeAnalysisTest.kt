package com.crazylei12.pokemonchampionsassistant.replayprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayProbeAnalysisTest {
    @Test
    fun `digital silence is not reported as a captured signal`() {
        val accumulator = PcmEnergyAccumulator()
        accumulator.add(ShortArray(4096), 4096)

        val summary = accumulator.summary()
        assertEquals(4096, summary.totalSamples)
        assertEquals(0, summary.nonZeroSamples)
        assertEquals(0, summary.peakAmplitude)
        assertFalse(summary.signalDetected)
    }

    @Test
    fun `audible PCM is reported as a captured signal`() {
        val accumulator = PcmEnergyAccumulator()
        val samples = ShortArray(4096) { index -> if (index % 2 == 0) 8_000 else -8_000 }
        accumulator.add(samples, samples.size)

        val summary = accumulator.summary()
        assertEquals(8_000, summary.peakAmplitude)
        assertTrue(summary.dbfs > -20.0)
        assertTrue(summary.signalDetected)
    }

    @Test
    fun `marker requires both high contrast colors`() {
        assertFalse(MarkerColorSummary(10_000, 500, 0).markerDetected)
        assertFalse(MarkerColorSummary(10_000, 0, 500).markerDetected)
        assertTrue(MarkerColorSummary(10_000, 500, 500).markerDetected)
    }

    @Test
    fun `scenario wire names are stable`() {
        assertEquals(
            ReplayProbeScenario.OTHER_APP_TONE,
            ReplayProbeScenario.fromWireName("other-app-tone"),
        )
        assertEquals(null, ReplayProbeScenario.fromWireName("unknown"))
    }
}
