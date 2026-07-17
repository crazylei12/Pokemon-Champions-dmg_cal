package com.crazylei12.pokemonchampionsassistant.replay

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal const val REPLAY_VIDEO_WIDTH = 960
internal const val REPLAY_VIDEO_HEIGHT = 540
internal const val REPLAY_VIDEO_FPS = 24
internal const val REPLAY_VIDEO_BIT_RATE = 1_500_000
internal const val REPLAY_AUDIO_SAMPLE_RATE = 48_000
internal const val REPLAY_AUDIO_BIT_RATE = 96_000
internal const val POKEMON_CHAMPIONS_GAME_PACKAGE = "jp.pokemon.pokemonchampions"

internal enum class ReplayTrackKind {
    VIDEO,
    AUDIO,
}

internal data class ReplayViewport(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal fun fitReplayViewport(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int = REPLAY_VIDEO_WIDTH,
    targetHeight: Int = REPLAY_VIDEO_HEIGHT,
): ReplayViewport {
    require(sourceWidth > 0 && sourceHeight > 0)
    require(targetWidth > 0 && targetHeight > 0)
    val sourceAspect = sourceWidth.toDouble() / sourceHeight
    val targetAspect = targetWidth.toDouble() / targetHeight
    val width: Int
    val height: Int
    if (sourceAspect >= targetAspect) {
        width = targetWidth
        height = (targetWidth / sourceAspect).roundToInt().coerceIn(1, targetHeight)
    } else {
        height = targetHeight
        width = (targetHeight * sourceAspect).roundToInt().coerceIn(1, targetWidth)
    }
    return ReplayViewport(
        x = (targetWidth - width) / 2,
        y = (targetHeight - height) / 2,
        width = width,
        height = height,
    )
}

internal class ReplayFrameThrottle(private val framesPerSecond: Int = REPLAY_VIDEO_FPS) {
    init {
        require(framesPerSecond > 0)
    }

    private val minimumIntervalNanos = 1_000_000_000L / framesPerSecond
    private var firstTimestampNanos: Long? = null
    private var nextAcceptedTimestampNanos: Long? = null

    fun accept(timestampNanos: Long): Long? {
        if (timestampNanos < 0L) return null
        val first = firstTimestampNanos
        if (first == null) {
            firstTimestampNanos = timestampNanos
            nextAcceptedTimestampNanos = timestampNanos + minimumIntervalNanos
            return 0L
        }
        val next = checkNotNull(nextAcceptedTimestampNanos)
        if (timestampNanos < next) return null
        val elapsed = timestampNanos - first
        val presentation = elapsed / minimumIntervalNanos * minimumIntervalNanos
        nextAcceptedTimestampNanos = first + presentation + minimumIntervalNanos
        return presentation
    }
}

internal class AudioPtsClock(
    private val sampleRate: Int = REPLAY_AUDIO_SAMPLE_RATE,
) {
    init {
        require(sampleRate > 0)
    }

    private var submittedFrames = 0L

    fun nextPresentationTimeUs(frameCount: Int): Long {
        require(frameCount >= 0)
        val ptsUs = submittedFrames * 1_000_000L / sampleRate
        submittedFrames += frameCount
        return ptsUs
    }
}

internal data class ReplayPcmSignalSummary(
    val totalSamples: Long,
    val nonZeroSamples: Long,
    val peakAmplitude: Int,
    val rms: Double,
    val dbfs: Double,
) {
    val nonZeroRatio: Double
        get() = if (totalSamples == 0L) 0.0 else nonZeroSamples.toDouble() / totalSamples

    val signalDetected: Boolean
        get() = totalSamples > 0L && nonZeroRatio >= 0.001 && dbfs > -60.0
}

internal class ReplayPcmSignalAccumulator {
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

    fun summary(): ReplayPcmSignalSummary {
        val rms = if (totalSamples == 0L) 0.0 else sqrt(squareSum / totalSamples)
        val dbfs = if (rms == 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rms / 32768.0)
        return ReplayPcmSignalSummary(totalSamples, nonZeroSamples, peakAmplitude, rms, dbfs)
    }
}

internal data class ReplayIsolationSummary(
    val sampledPixels: Int,
    val magentaPixels: Int,
    val cyanPixels: Int,
) {
    val markerDetected: Boolean
        get() = sampledPixels >= 256 && magentaPixels >= 8 && cyanPixels >= 8
}

internal fun analyzeReplayIsolationFrame(
    rgba: ByteBuffer,
    width: Int,
    height: Int,
): ReplayIsolationSummary {
    require(width > 0 && height > 0)
    val required = width * height * 4
    require(rgba.capacity() >= required)
    var sampled = 0
    var magenta = 0
    var cyan = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val offset = (y * width + x) * 4
            val red = rgba.get(offset).toInt() and 0xff
            val green = rgba.get(offset + 1).toInt() and 0xff
            val blue = rgba.get(offset + 2).toInt() and 0xff
            sampled += 1
            if (red >= 220 && green <= 40 && blue >= 220) magenta += 1
            if (red <= 40 && green >= 220 && blue >= 220) cyan += 1
        }
    }
    return ReplayIsolationSummary(sampled, magenta, cyan)
}

internal class MuxerTrackGate(expectAudio: Boolean) {
    private val required = if (expectAudio) {
        setOf(ReplayTrackKind.VIDEO, ReplayTrackKind.AUDIO)
    } else {
        setOf(ReplayTrackKind.VIDEO)
    }
    private val ready = linkedSetOf<ReplayTrackKind>()

    val canStart: Boolean
        get() = ready.containsAll(required)

    fun markReady(kind: ReplayTrackKind): Boolean {
        check(kind in required) { "Unexpected muxer track: $kind" }
        check(ready.add(kind)) { "Muxer track already registered: $kind" }
        return canStart
    }
}

internal class PcmRingBuffer(capacity: Int) {
    private val storage = ByteArray(capacity.also { require(it > 0) })
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private var readIndex = 0
    private var writeIndex = 0
    private var size = 0
    private var closed = false

    fun write(source: ByteArray, offset: Int = 0, length: Int = source.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= source.size)
        lock.withLock {
            check(!closed) { "PCM ring buffer is closed" }
            var sourceOffset = offset
            var remaining = length
            if (remaining >= storage.size) {
                sourceOffset += remaining - storage.size
                remaining = storage.size
                readIndex = 0
                writeIndex = 0
                size = 0
            }
            val overflow = (size + remaining - storage.size).coerceAtLeast(0)
            if (overflow > 0) {
                readIndex = (readIndex + overflow) % storage.size
                size -= overflow
            }
            repeat(remaining) { index ->
                storage[writeIndex] = source[sourceOffset + index]
                writeIndex = (writeIndex + 1) % storage.size
            }
            size += remaining
            notEmpty.signalAll()
        }
    }

    fun read(target: ByteArray, maximumBytes: Int = target.size, timeoutMs: Long = 100L): Int {
        require(maximumBytes in 0..target.size)
        lock.withLock {
            var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (size == 0 && !closed && remainingNanos > 0L) {
                remainingNanos = notEmpty.awaitNanos(remainingNanos)
            }
            if (size == 0) return if (closed) -1 else 0
            val count = minOf(size, maximumBytes)
            repeat(count) { index ->
                target[index] = storage[readIndex]
                readIndex = (readIndex + 1) % storage.size
            }
            size -= count
            return count
        }
    }

    fun close() {
        lock.withLock {
            closed = true
            notEmpty.signalAll()
        }
    }
}
