package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlaySafeAreaTest {
    @Test
    fun `safe regions exclude system bars cutouts and navigation`() {
        assertEquals(
            listOf(OverlayBounds(20, 105, 2380, 1000)),
            calculateOverlaySafeRegions(
                windowBounds = OverlayBounds(0, 0, 2400, 1080),
                insets = OverlayInsets(left = 20, top = 105, right = 20, bottom = 80),
                occlusions = emptyList(),
                foldPadding = 16,
            ),
        )
    }

    @Test
    fun `separating hinge creates two continuous safe regions`() {
        assertEquals(
            listOf(
                OverlayBounds(0, 80, 1164, 1080),
                OverlayBounds(1236, 80, 2400, 1080),
            ),
            calculateOverlaySafeRegions(
                windowBounds = OverlayBounds(0, 0, 2400, 1080),
                insets = OverlayInsets(top = 80),
                occlusions = listOf(
                    OverlayOcclusion(
                        bounds = OverlayBounds(1180, 0, 1220, 1080),
                        orientation = OverlayFoldOrientation.VERTICAL,
                    ),
                ),
                foldPadding = 16,
            ),
        )
    }

    @Test
    fun `zero width separating fold still keeps controls away from the crease`() {
        assertEquals(
            listOf(
                OverlayBounds(0, 0, 1184, 1080),
                OverlayBounds(1216, 0, 2400, 1080),
            ),
            calculateOverlaySafeRegions(
                windowBounds = OverlayBounds(0, 0, 2400, 1080),
                insets = OverlayInsets(),
                occlusions = listOf(
                    OverlayOcclusion(
                        bounds = OverlayBounds(1200, 0, 1200, 1080),
                        orientation = OverlayFoldOrientation.VERTICAL,
                    ),
                ),
                foldPadding = 16,
            ),
        )
    }

    @Test
    fun `existing overlay stays in the region containing most of it`() {
        val regions = listOf(
            OverlayBounds(0, 80, 1164, 1080),
            OverlayBounds(1236, 80, 2400, 1080),
        )

        assertEquals(
            regions[1],
            chooseOverlaySafeRegion(
                regions = regions,
                reference = OverlayBounds(1500, 100, 2200, 900),
            ),
        )
    }

    @Test
    fun `new right rail prefers the end safe region`() {
        val regions = listOf(
            OverlayBounds(0, 80, 1164, 1080),
            OverlayBounds(1236, 80, 2400, 1080),
        )

        assertEquals(regions[1], chooseOverlaySafeRegion(regions, preferEnd = true))
    }

    @Test
    fun `floating control cannot be dragged past any safe edge`() {
        assertEquals(
            OverlayPosition(x = 2316, y = 936),
            clampOverlayPosition(
                regions = listOf(OverlayBounds(20, 105, 2380, 1000)),
                proposedX = 3000,
                proposedY = 1600,
                width = 64,
                height = 64,
            ),
        )
    }

    @Test
    fun `portrait position is clamped into the new landscape safe area`() {
        assertEquals(
            OverlayPosition(x = 1680, y = 200),
            clampOverlayPosition(
                regions = listOf(OverlayBounds(0, 100, 2400, 1000)),
                proposedX = 1700,
                proposedY = 1800,
                width = 720,
                height = 800,
            ),
        )
    }
}
