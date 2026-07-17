package com.crazylei12.pokemonchampionsassistant.replay

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal const val REPLAY_AUDIO_SAMPLE_RATE = 48_000
internal const val REPLAY_AUDIO_BIT_RATE = 96_000
internal const val POKEMON_CHAMPIONS_GAME_PACKAGE = "jp.pokemon.pokemonchampions"

internal data class ReplayVideoProfile(
    val width: Int,
    val height: Int,
    val framesPerSecond: Int,
    val bitRate: Int,
) {
    init {
        require(width > 0 && height > 0)
        require(framesPerSecond > 0 && bitRate > 0)
    }

    val displayLabel: String
        get() = "${width}×$height / $framesPerSecond fps"
}

internal val DEFAULT_REPLAY_VIDEO_PROFILE = ReplayVideoProfile(
    width = 960,
    height = 540,
    framesPerSecond = 24,
    bitRate = 1_500_000,
)

internal fun replayVideoProfilesForAlignment(
    widthAlignment: Int,
    heightAlignment: Int,
): List<ReplayVideoProfile> {
    require(widthAlignment > 0 && heightAlignment > 0)
    fun alignedDown(value: Int, alignment: Int): Int = (value / alignment * alignment).coerceAtLeast(alignment)
    val profiles = listOf(
        DEFAULT_REPLAY_VIDEO_PROFILE,
        ReplayVideoProfile(
            width = alignedDown(854, widthAlignment),
            height = alignedDown(480, heightAlignment),
            framesPerSecond = 20,
            bitRate = 1_000_000,
        ),
        ReplayVideoProfile(
            width = alignedDown(640, widthAlignment),
            height = alignedDown(360, heightAlignment),
            framesPerSecond = 20,
            bitRate = 750_000,
        ),
    )
    return profiles.distinctBy { listOf(it.width, it.height, it.framesPerSecond, it.bitRate) }
}

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
    targetWidth: Int = DEFAULT_REPLAY_VIDEO_PROFILE.width,
    targetHeight: Int = DEFAULT_REPLAY_VIDEO_PROFILE.height,
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

internal fun rgbaToArgb(red: Int, green: Int, blue: Int, alpha: Int): Int {
    require(red in 0..255 && green in 0..255 && blue in 0..255 && alpha in 0..255)
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

internal fun sourceRowForBottomUpRgba(targetRow: Int, height: Int): Int {
    require(height > 0 && targetRow in 0 until height)
    return height - 1 - targetRow
}

internal class ReplayFrameThrottle(
    private val framesPerSecond: Int = DEFAULT_REPLAY_VIDEO_PROFILE.framesPerSecond,
) {
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

    fun skipFrames(frameCount: Long) {
        require(frameCount >= 0L)
        submittedFrames += frameCount
    }

    fun nextPresentationTimeUs(frameCount: Int): Long {
        require(frameCount >= 0)
        val ptsUs = submittedFrames * 1_000_000L / sampleRate
        submittedFrames += frameCount
        return ptsUs
    }
}

internal data class PcmRingReadResult(
    val bytesRead: Int,
    val droppedBytesBeforeRead: Long,
)

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
    val visiblePixels: Int,
    val markerPatternDetected: Boolean,
) {
    val markerDetected: Boolean
        get() = sampledPixels >= 256 && markerPatternDetected

    val contentVisible: Boolean
        get() = sampledPixels >= 256 && visiblePixels * 20 >= sampledPixels
}

private enum class ReplayMarkerColor {
    MAGENTA,
    CYAN,
    OTHER,
}

private fun replayMarkerColor(red: Int, green: Int, blue: Int): ReplayMarkerColor = when {
    red >= 210 && green <= 55 && blue >= 210 -> ReplayMarkerColor.MAGENTA
    red <= 55 && green >= 210 && blue >= 210 -> ReplayMarkerColor.CYAN
    else -> ReplayMarkerColor.OTHER
}

private fun hasReplayIsolationMarkerPattern(
    rgba: ByteBuffer,
    width: Int,
    height: Int,
): Boolean {
    fun colorAt(x: Int, y: Int): ReplayMarkerColor {
        val offset = (y * width + x) * 4
        return replayMarkerColor(
            red = rgba.get(offset).toInt() and 0xff,
            green = rgba.get(offset + 1).toInt() and 0xff,
            blue = rgba.get(offset + 2).toInt() and 0xff,
        )
    }

    fun isBlackAt(x: Int, y: Int): Boolean {
        val offset = (y * width + x) * 4
        val red = rgba.get(offset).toInt() and 0xff
        val green = rgba.get(offset + 1).toInt() and 0xff
        val blue = rgba.get(offset + 2).toInt() and 0xff
        return maxOf(red, green, blue) <= 55
    }

    val maximumRadius = minOf(24, minOf(width, height) / 3)
    for (radius in 4..maximumRadius) {
        val innerOffsets = intArrayOf(
            maxOf(1, radius / 3),
            maxOf(2, radius * 2 / 3),
        ).distinct()
        val edge = maxOf(1, radius * 9 / 10)
        for (centerY in radius until height - radius) {
            for (centerX in radius until width - radius) {
                var matches = 0
                var samples = 0
                for (offset in innerOffsets) {
                    val expectations = arrayOf(
                        Triple(-offset, -offset, ReplayMarkerColor.MAGENTA),
                        Triple(offset, -offset, ReplayMarkerColor.CYAN),
                        Triple(-offset, offset, ReplayMarkerColor.CYAN),
                        Triple(offset, offset, ReplayMarkerColor.MAGENTA),
                    )
                    expectations.forEach { (deltaX, deltaY, expected) ->
                        samples += 1
                        if (colorAt(centerX + deltaX, centerY + deltaY) == expected) matches += 1
                    }
                }
                if (matches < samples - 1) continue
                val blackCorners = listOf(
                    centerX - edge to centerY - edge,
                    centerX + edge to centerY - edge,
                    centerX - edge to centerY + edge,
                    centerX + edge to centerY + edge,
                ).count { (x, y) -> isBlackAt(x, y) }
                if (blackCorners >= 3) return true
            }
        }
    }
    return false
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
    var visible = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val offset = (y * width + x) * 4
            val red = rgba.get(offset).toInt() and 0xff
            val green = rgba.get(offset + 1).toInt() and 0xff
            val blue = rgba.get(offset + 2).toInt() and 0xff
            sampled += 1
            when (replayMarkerColor(red, green, blue)) {
                ReplayMarkerColor.MAGENTA -> magenta += 1
                ReplayMarkerColor.CYAN -> cyan += 1
                ReplayMarkerColor.OTHER -> Unit
            }
            if (maxOf(red, green, blue) > 24) visible += 1
        }
    }
    return ReplayIsolationSummary(
        sampledPixels = sampled,
        magentaPixels = magenta,
        cyanPixels = cyan,
        visiblePixels = visible,
        markerPatternDetected = hasReplayIsolationMarkerPattern(rgba, width, height),
    )
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

internal class PcmRingBuffer(
    capacity: Int,
    private val frameSizeBytes: Int = 1,
) {
    private val storage = ByteArray(capacity.also { require(it > 0) })
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private var readIndex = 0
    private var writeIndex = 0
    private var size = 0
    private var closed = false
    private var droppedBytesSinceRead = 0L

    init {
        require(frameSizeBytes > 0)
        require(capacity % frameSizeBytes == 0) {
            "PCM ring capacity must contain complete sample frames"
        }
    }

    fun write(source: ByteArray, offset: Int = 0, length: Int = source.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= source.size)
        require(length % frameSizeBytes == 0) { "PCM writes must contain complete sample frames" }
        lock.withLock {
            check(!closed) { "PCM ring buffer is closed" }
            var sourceOffset = offset
            var remaining = length
            if (remaining >= storage.size) {
                droppedBytesSinceRead += size.toLong() + remaining - storage.size
                sourceOffset += remaining - storage.size
                remaining = storage.size
                readIndex = 0
                writeIndex = 0
                size = 0
            }
            val overflow = (size + remaining - storage.size).coerceAtLeast(0)
            if (overflow > 0) {
                droppedBytesSinceRead += overflow.toLong()
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

    fun read(
        target: ByteArray,
        maximumBytes: Int = target.size,
        timeoutMs: Long = 100L,
    ): PcmRingReadResult {
        require(maximumBytes in 0..target.size)
        lock.withLock {
            var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (size == 0 && !closed && remainingNanos > 0L) {
                remainingNanos = notEmpty.awaitNanos(remainingNanos)
            }
            val droppedBytes = droppedBytesSinceRead.also { droppedBytesSinceRead = 0L }
            if (size == 0) {
                return PcmRingReadResult(
                    bytesRead = if (closed) -1 else 0,
                    droppedBytesBeforeRead = droppedBytes,
                )
            }
            val alignedMaximum = maximumBytes - maximumBytes % frameSizeBytes
            if (alignedMaximum == 0) return PcmRingReadResult(0, droppedBytes)
            val count = minOf(size, alignedMaximum)
            repeat(count) { index ->
                target[index] = storage[readIndex]
                readIndex = (readIndex + 1) % storage.size
            }
            size -= count
            return PcmRingReadResult(count, droppedBytes)
        }
    }

    fun close() {
        lock.withLock {
            closed = true
            notEmpty.signalAll()
        }
    }
}
