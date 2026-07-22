package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleAssistantModeTest {
    @Test
    fun `standard is the safe default while hud mode opts into automatic display`() {
        assertEquals(BattleAssistantMode.STANDARD, BattleAssistantMode.fromWireName(null))
        assertEquals(BattleAssistantMode.STANDARD, BattleAssistantMode.fromWireName("unknown"))
        assertFalse(BattleAssistantMode.STANDARD.autoOpenDirectHud)
        assertTrue(BattleAssistantMode.HUD.autoOpenDirectHud)
        assertEquals(BattleAssistantMode.HUD, BattleAssistantMode.fromWireName("hud"))
    }
}
