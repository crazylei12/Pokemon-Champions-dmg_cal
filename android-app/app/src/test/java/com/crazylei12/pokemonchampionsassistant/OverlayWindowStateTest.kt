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
        assertTrue(
            OVERLAY_PANEL_WINDOW_FLAGS and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH != 0,
        )
        assertTrue(
            overlayPanelWindowFlags(focusable = false) and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0,
        )
        assertFalse(
            overlayPanelWindowFlags(focusable = true) and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0,
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
    fun `panel subpages share position without sharing size or scroll`() {
        val sharedPosition = OverlayWindowState()
        val damagePanel = OverlayWindowState(width = 900, height = 640, scrollY = 180)
        val speedLine = OverlayWindowState(width = 1080, height = 720, scrollY = 40)

        damagePanel.rememberPosition(x = 24, y = 96)
        assertTrue(sharedPosition.rememberPositionFrom(damagePanel))
        assertTrue(speedLine.rememberPositionFrom(sharedPosition))

        assertEquals(24, speedLine.x)
        assertEquals(96, speedLine.y)
        assertEquals(1080, speedLine.width)
        assertEquals(720, speedLine.height)
        assertEquals(40, speedLine.scrollY)
    }

    @Test
    fun `scroll position cannot become negative`() {
        val state = OverlayWindowState()

        state.rememberScroll(scrollY = -20)

        assertEquals(0, state.scrollY)
    }

    @Test
    fun `drag direction follows the finger and stays on screen`() {
        assertEquals(
            OverlayPosition(x = 140, y = 70),
            boundedOverlayPosition(
                startX = 200,
                startY = 50,
                deltaX = -60,
                deltaY = 20,
                windowWidth = 400,
                windowHeight = 300,
                screenWidth = 1200,
                screenHeight = 800,
            ),
        )
    }

    @Test
    fun `resize remains valid when requested minimum exceeds landscape height`() {
        assertEquals(
            OverlaySize(width = 900, height = 1240),
            boundedOverlaySize(
                startWidth = 900,
                startHeight = 1000,
                deltaWidth = 0,
                deltaHeight = 300,
                requestedMinWidth = 980,
                requestedMinHeight = 1470,
                availableWidth = 900,
                availableHeight = 1240,
            ),
        )
    }
}
