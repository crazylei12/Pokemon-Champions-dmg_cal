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
        assertTrue(BattleAssistantMode.STANDARD.usesFloatingBubble)
        assertTrue(BattleAssistantMode.HUD.autoOpenDirectHud)
        assertFalse(BattleAssistantMode.HUD.usesFloatingBubble)
        assertEquals(BattleAssistantMode.HUD, BattleAssistantMode.fromWireName("hud"))
    }

    @Test
    fun `team preview recognition keeps direct hud minimized until recognition completes`() {
        assertFalse(shouldKeepHudMinimizedDuringTeamPreviewRecognition(BattleAssistantMode.STANDARD))
        assertTrue(shouldKeepHudMinimizedDuringTeamPreviewRecognition(BattleAssistantMode.HUD))
    }
}
