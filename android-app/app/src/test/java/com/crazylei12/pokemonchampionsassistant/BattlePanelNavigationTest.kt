package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BattlePanelNavigationTest {
    @Test
    fun `collapsed panel reopens on each previous subpage`() {
        BattlePanelPage.entries.filterNot { it == BattlePanelPage.DAMAGE }.forEach { page ->
            val navigation = BattlePanelNavigation()
            navigation.show(page)

            navigation.collapse()

            assertFalse(navigation.isVisible)
            assertEquals(page, navigation.reopen())
            assertTrue(navigation.isVisible)
        }
    }

    @Test
    fun `team recognition resets the panel to damage page`() {
        val navigation = BattlePanelNavigation()
        navigation.show(BattlePanelPage.OPPONENT_EDITOR)
        navigation.collapse()

        navigation.resetForTeamRecognition()

        assertFalse(navigation.isVisible)
        assertEquals(BattlePanelPage.DAMAGE, navigation.reopen())
        assertTrue(navigation.isVisible)
    }
}
