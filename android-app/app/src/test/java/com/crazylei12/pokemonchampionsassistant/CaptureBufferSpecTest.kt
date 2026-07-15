package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureBufferSpecTest {
    @Test
    fun `portrait capture is replaced when the display rotates to landscape`() {
        val portrait = CaptureBufferSpec(width = 1240, height = 2772, densityDpi = 560)

        assertEquals(
            CaptureBufferSpec(width = 2772, height = 1240, densityDpi = 560),
            changedCaptureBufferSpec(portrait, width = 2772, height = 1240, densityDpi = 560),
        )
    }

    @Test
    fun `unchanged and invalid dimensions do not rebuild the capture surface`() {
        val current = CaptureBufferSpec(width = 2772, height = 1240, densityDpi = 560)

        assertNull(changedCaptureBufferSpec(current, width = 2772, height = 1240, densityDpi = 560))
        assertNull(changedCaptureBufferSpec(current, width = 0, height = 1240, densityDpi = 560))
    }
}
