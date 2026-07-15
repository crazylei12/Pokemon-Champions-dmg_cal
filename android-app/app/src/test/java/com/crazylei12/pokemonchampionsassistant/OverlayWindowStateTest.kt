package com.crazylei12.pokemonchampionsassistant

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWindowStateTest {
    @Test
    fun `panel accepts touches inside without blocking the game outside`() {
        assertTrue(
            OVERLAY_PANEL_WINDOW_FLAGS and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0,
        )
        assertFalse(
            OVERLAY_PANEL_WINDOW_FLAGS and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0,
        )
    }

    @Test
    fun `position size and scroll survive window recreation`() {
        val state = OverlayWindowState(width = 900, height = 640)

        state.rememberPosition(x = 180, y = -120)
        state.rememberSize(width = 840, height = 600)
        state.rememberScroll(scrollY = 360)

        val recreatedState = state.copy()
        assertEquals(180, recreatedState.x)
        assertEquals(-120, recreatedState.y)
        assertEquals(840, recreatedState.width)
        assertEquals(600, recreatedState.height)
        assertEquals(360, recreatedState.scrollY)
    }

    @Test
    fun `scroll position cannot become negative`() {
        val state = OverlayWindowState()

        state.rememberScroll(scrollY = -20)

        assertEquals(0, state.scrollY)
    }
}
