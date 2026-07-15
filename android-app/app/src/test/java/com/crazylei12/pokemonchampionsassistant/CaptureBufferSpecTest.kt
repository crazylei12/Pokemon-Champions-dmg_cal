package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureBufferSpecTest {
    @Test
    fun `portrait capture is replaced when the display rotates to landscape`() {
        val portrait = CaptureBufferSpec(width = 1240, height = 2772, densityDpi = 560, rotation = 0)

        assertEquals(
            CaptureBufferSpec(width = 2772, height = 1240, densityDpi = 560, rotation = 1),
            changedCaptureBufferSpec(
                portrait,
                width = 2772,
                height = 1240,
                densityDpi = 560,
                rotation = 1,
            ),
        )
    }

    @Test
    fun `opposite landscape rotations rebuild even when dimensions are unchanged`() {
        val clockwiseLandscape = CaptureBufferSpec(
            width = 2772,
            height = 1240,
            densityDpi = 560,
            rotation = 1,
        )

        assertEquals(
            CaptureBufferSpec(width = 2772, height = 1240, densityDpi = 560, rotation = 3),
            changedCaptureBufferSpec(
                clockwiseLandscape,
                width = 2772,
                height = 1240,
                densityDpi = 560,
                rotation = 3,
            ),
        )
    }

    @Test
    fun `rotated capture uses an unrotated buffer and restores oriented bitmap coordinates`() {
        val counterClockwiseLandscape = CaptureBufferSpec(
            width = 2772,
            height = 1240,
            densityDpi = 560,
            rotation = 3,
        )

        assertEquals(1240, counterClockwiseLandscape.virtualDisplayWidth)
        assertEquals(2772, counterClockwiseLandscape.virtualDisplayHeight)
        assertEquals(-270f, counterClockwiseLandscape.bitmapRotationDegrees)

        val clockwiseLandscape = counterClockwiseLandscape.copy(rotation = 1)
        assertEquals(-90f, clockwiseLandscape.bitmapRotationDegrees)
        assertEquals(0f, clockwiseLandscape.copy(rotation = 0).bitmapRotationDegrees)
        assertEquals(-180f, clockwiseLandscape.copy(rotation = 2).bitmapRotationDegrees)
    }

    @Test
    fun `unchanged and invalid dimensions do not rebuild the capture surface`() {
        val current = CaptureBufferSpec(width = 2772, height = 1240, densityDpi = 560)

        assertNull(changedCaptureBufferSpec(current, width = 2772, height = 1240, densityDpi = 560))
        assertNull(changedCaptureBufferSpec(current, width = 0, height = 1240, densityDpi = 560))
        assertNull(
            changedCaptureBufferSpec(
                current,
                width = 2772,
                height = 1240,
                densityDpi = 560,
                rotation = 4,
            ),
        )
    }
}
