package com.crazylei12.pokemonchampionsassistant.replayprobe

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

internal enum class ReplayProbeScenario(val wireName: String) {
    AUDIBLE_GAME("audible-game"),
    MUTED_GAME("muted-game"),
    OTHER_APP_TONE("other-app-tone");

    companion object {
        fun fromWireName(value: String?): ReplayProbeScenario? = entries.firstOrNull { it.wireName == value }
    }
}

internal data class PcmEnergySummary(
    val totalSamples: Long,
    val nonZeroSamples: Long,
    val peakAmplitude: Int,
    val rms: Double,
    val dbfs: Double,
) {
    val nonZeroRatio: Double
        get() = if (totalSamples == 0L) 0.0 else nonZeroSamples.toDouble() / totalSamples

    val signalDetected: Boolean
        get() = totalSamples > 0 && nonZeroRatio >= 0.001 && dbfs > -60.0
}

internal class PcmEnergyAccumulator {
    private var totalSamples = 0L
    private var nonZeroSamples = 0L
    private var peakAmplitude = 0
    private var squareSum = 0.0

    fun add(samples: ShortArray, count: Int) {
        require(count in 0..samples.size)
        repeat(count) { index ->
            val value = samples[index].toInt()
            val amplitude = abs(value)
            totalSamples += 1
            if (value != 0) nonZeroSamples += 1
            if (amplitude > peakAmplitude) peakAmplitude = amplitude
            squareSum += value.toDouble() * value.toDouble()
        }
    }

    fun summary(): PcmEnergySummary {
        val rms = if (totalSamples == 0L) 0.0 else sqrt(squareSum / totalSamples)
        val dbfs = if (rms == 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rms / 32768.0)
        return PcmEnergySummary(
            totalSamples = totalSamples,
            nonZeroSamples = nonZeroSamples,
            peakAmplitude = peakAmplitude,
            rms = rms,
            dbfs = dbfs,
        )
    }
}

internal data class MarkerColorSummary(
    val sampledPixels: Long,
    val magentaPixels: Long,
    val cyanPixels: Long,
) {
    val markerDetected: Boolean
        get() = sampledPixels >= 256 && magentaPixels >= 128 && cyanPixels >= 128
}
