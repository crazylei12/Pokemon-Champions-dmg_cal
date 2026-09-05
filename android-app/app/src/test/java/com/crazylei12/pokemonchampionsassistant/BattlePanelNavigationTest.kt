package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BattlePanelNavigationTest {
    @Test
    fun `details always opens the damage page after collapsing a subpage`() {
        BattlePanelPage.entries.filterNot { it == BattlePanelPage.DAMAGE }.forEach { page ->
            val navigation = BattlePanelNavigation()
            navigation.show(page)

            navigation.collapse()
            assertFalse(navigation.isVisible)
            navigation.openDetails()

            assertEquals(BattlePanelPage.DAMAGE, navigation.currentPage)
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
        navigation.openDetails()
        assertEquals(BattlePanelPage.DAMAGE, navigation.currentPage)
        assertTrue(navigation.isVisible)
    }
}
