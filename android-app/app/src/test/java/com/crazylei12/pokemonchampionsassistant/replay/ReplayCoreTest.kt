package com.crazylei12.pokemonchampionsassistant.replay

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayCoreTest {
    @Test
    fun `wide game content is letterboxed without cropping`() {
        assertEquals(
            ReplayViewport(x = 0, y = 55, width = 960, height = 429),
            fitReplayViewport(sourceWidth = 2772, sourceHeight = 1240),
        )
    }

    @Test
    fun `portrait content is pillarboxed without stretching`() {
        assertEquals(
            ReplayViewport(x = 328, y = 0, width = 304, height = 540),
            fitReplayViewport(sourceWidth = 1240, sourceHeight = 2200),
        )
    }

    @Test
    fun `RGBA readback colors convert to Android ARGB without channel swap`() {
        assertEquals(0x7f123456, rgbaToArgb(red = 0x12, green = 0x34, blue = 0x56, alpha = 0x7f))
    }

    @Test
    fun `bottom-up GL rows map to top-down bitmap rows`() {
        assertEquals(2, sourceRowForBottomUpRgba(targetRow = 0, height = 3))
        assertEquals(1, sourceRowForBottomUpRgba(targetRow = 1, height = 3))
        assertEquals(0, sourceRowForBottomUpRgba(targetRow = 2, height = 3))
    }

    @Test
    fun `frame throttle accepts at most twenty four frames per second`() {
        val throttle = ReplayFrameThrottle(24)
        assertEquals(0L, throttle.accept(1_000_000_000L))
        assertNull(throttle.accept(1_020_000_000L))
        assertEquals(41_666_666L, throttle.accept(1_042_000_000L))
    }

    @Test
    fun `thirty fps input is paced close to twenty four fps instead of fifteen`() {
        val throttle = ReplayFrameThrottle(24)
        val accepted = (0 until 30).mapNotNull { frame ->
            throttle.accept(1_000_000_000L + frame * 33_333_333L)
        }

        assertEquals(24, accepted.size)
        assertEquals(958_333_318L, accepted.last())
    }

    @Test
    fun `audio timestamps advance from submitted sample frames`() {
        val clock = AudioPtsClock(48_000)
        assertEquals(0L, clock.nextPresentationTimeUs(480))
        assertEquals(10_000L, clock.nextPresentationTimeUs(960))
        assertEquals(30_000L, clock.nextPresentationTimeUs(0))
    }

    @Test
    fun `audio timestamps preserve a gap for PCM frames dropped under backpressure`() {
        val clock = AudioPtsClock(48_000)
        assertEquals(0L, clock.nextPresentationTimeUs(480))

        clock.skipFrames(960)

        assertEquals(30_000L, clock.nextPresentationTimeUs(480))
    }

    @Test
    fun `digital silence and audible samples are distinguished`() {
        val silent = ReplayPcmSignalAccumulator().apply { add(ShortArray(4_096), 4_096) }.summary()
        val audible = ReplayPcmSignalAccumulator().apply {
            add(ShortArray(4_096) { if (it % 2 == 0) 8_000 else -8_000 }, 4_096)
        }.summary()
        assertFalse(silent.signalDetected)
        assertTrue(audible.signalDetected)
    }

    @Test
    fun `isolation marker requires both marker colors`() {
        val buffer = ByteBuffer.allocate(32 * 16 * 4)
        repeat(32 * 16) { pixel ->
            val offset = pixel * 4
            val magenta = pixel < 16
            buffer.put(offset, if (magenta) 255.toByte() else 0)
            buffer.put(offset + 1, if (magenta) 0 else 255.toByte())
            buffer.put(offset + 2, 255.toByte())
            buffer.put(offset + 3, 255.toByte())
        }
        assertTrue(analyzeReplayIsolationFrame(buffer, 32, 16).markerDetected)
    }

    @Test
    fun `muxer waits for every required track`() {
        val audioVideo = MuxerTrackGate(expectAudio = true)
        assertFalse(audioVideo.markReady(ReplayTrackKind.AUDIO))
        assertTrue(audioVideo.markReady(ReplayTrackKind.VIDEO))
        val videoOnly = MuxerTrackGate(expectAudio = false)
        assertTrue(videoOnly.markReady(ReplayTrackKind.VIDEO))
    }

    @Test
    fun `pcm ring buffer stays bounded and keeps newest audio`() {
        val ring = PcmRingBuffer(4)
        ring.write(byteArrayOf(1, 2, 3))
        ring.write(byteArrayOf(4, 5, 6))
        val output = ByteArray(4)

        val read = ring.read(output, timeoutMs = 0)
        assertEquals(4, read.bytesRead)
        assertEquals(2L, read.droppedBytesBeforeRead)
        assertEquals(listOf<Byte>(3, 4, 5, 6), output.toList())
        ring.close()
        assertEquals(-1, ring.read(ByteArray(1), timeoutMs = 0).bytesRead)
    }

    @Test
    fun `pcm overflow accounting preserves complete stereo frames`() {
        val ring = PcmRingBuffer(capacity = 8, frameSizeBytes = 4)
        ring.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        ring.write(byteArrayOf(9, 10, 11, 12))
        val output = ByteArray(8)

        val read = ring.read(output, timeoutMs = 0)

        assertEquals(8, read.bytesRead)
        assertEquals(4L, read.droppedBytesBeforeRead)
        assertEquals(listOf<Byte>(5, 6, 7, 8, 9, 10, 11, 12), output.toList())
    }

    @Test
    fun `pending cleanup cutoff is fixed before a new recording can be created`() {
        val cleanupStartedAt = 1_700_000_000_000L

        assertEquals(
            cleanupStartedAt / 1_000L - REPLAY_STALE_PENDING_MIN_AGE_MS / 1_000L,
            replayStalePendingCutoffEpochSeconds(cleanupStartedAt),
        )
        assertTrue(
            cleanupStartedAt / 1_000L > replayStalePendingCutoffEpochSeconds(cleanupStartedAt),
        )
    }
}
