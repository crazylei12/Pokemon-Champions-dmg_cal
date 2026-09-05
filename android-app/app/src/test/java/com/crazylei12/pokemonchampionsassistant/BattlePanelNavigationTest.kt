package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `visible subpages are restored after applying a recalculation`() {
        BattlePanelPage.entries.filterNot { it == BattlePanelPage.DAMAGE }.forEach { page ->
            val navigation = BattlePanelNavigation()
            navigation.show(page)

            val pageToRestore = navigation.visibleSubpageForRefresh()
            navigation.show(BattlePanelPage.DAMAGE)
            pageToRestore?.let(navigation::show)

            assertEquals(page, navigation.currentPage)
            assertTrue(navigation.isVisible)
        }
    }

    @Test
    fun `damage and collapsed pages are not restored after recalculation`() {
        val navigation = BattlePanelNavigation()

        navigation.show(BattlePanelPage.DAMAGE)
        assertNull(navigation.visibleSubpageForRefresh())

        navigation.show(BattlePanelPage.SPEED_LINE)
        navigation.collapse()
        assertNull(navigation.visibleSubpageForRefresh())
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
