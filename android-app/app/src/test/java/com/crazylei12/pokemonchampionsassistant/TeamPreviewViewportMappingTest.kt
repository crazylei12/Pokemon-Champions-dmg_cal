package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class TeamPreviewViewportMappingTest {
    private val aspectRatio = parseTeamPreviewAspectRatio("16:9")

    @Test
    fun `detects centered game viewports for tablet and phone captures`() {
        assertEquals(
            TeamPreviewViewport(left = 0, top = 246, width = 3392, height = 1908),
            centeredTeamPreviewViewport(3392, 2400, aspectRatio),
        )
        assertEquals(
            TeamPreviewViewport(left = 284, top = 0, width = 2204, height = 1240),
            centeredTeamPreviewViewport(2772, 1240, aspectRatio),
        )
    }

    @Test
    fun `maps base safe zones through the centered 16 by 9 viewport`() {
        assertEquals(
            TeamPreviewPixelRect(left = 844, top = 183, right = 976, bottom = 298),
            mapTeamPreviewRectToImage(
                baseImageWidth = 3392,
                baseImageHeight = 2400,
                baseRect = TeamPreviewBaseRect(left = 862, top = 528, right = 1065, bottom = 705),
                imageWidth = 2772,
                imageHeight = 1240,
                targetAspectRatio = aspectRatio,
            ),
        )
        assertEquals(
            TeamPreviewPixelRect(left = 2087, top = 173, right = 2266, bottom = 305),
            mapTeamPreviewRectToImage(
                baseImageWidth = 3392,
                baseImageHeight = 2400,
                baseRect = TeamPreviewBaseRect(left = 2774, top = 512, right = 3050, bottom = 716),
                imageWidth = 2772,
                imageHeight = 1240,
                targetAspectRatio = aspectRatio,
            ),
        )
    }

    @Test
    fun `keeps the original base screenshot coordinates unchanged`() {
        val source = TeamPreviewBaseRect(left = 2774, top = 1637, right = 3050, bottom = 1835)
        assertEquals(
            TeamPreviewPixelRect(source.left, source.top, source.right, source.bottom),
            mapTeamPreviewRectToImage(3392, 2400, source, 3392, 2400, aspectRatio),
        )
    }
}
