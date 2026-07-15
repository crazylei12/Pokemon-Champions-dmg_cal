package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class BattleMoveSelectionTest {
    @Test
    fun `form change preserves selected move while it remains legal`() {
        val configured = listOf(move("Shadow Ball", 80), move("Icy Wind", 55))
        val compatible = compatibleConfiguredMoves(configured, configured.reversed())

        assertEquals("Shadow Ball", chooseCompatibleMoveId(compatible, "shadow-ball", false))
    }

    @Test
    fun `form change falls back only when selected move is no longer legal`() {
        val configured = listOf(move("Shadow Ball", 80), move("Icy Wind", 55))
        val compatible = compatibleConfiguredMoves(configured, listOf(move("Icy Wind", 55)))

        assertEquals(listOf("Icy Wind"), compatible.map { it.entity.showdownId })
        assertEquals("Icy Wind", chooseCompatibleMoveId(compatible, "Shadow Ball", false))
    }

    @Test
    fun `missing legality data does not discard configured moves`() {
        val configured = listOf(move("Shadow Ball", 80), move("Icy Wind", 55))

        assertEquals(configured, compatibleConfiguredMoves(configured, emptyList()))
        assertEquals("Icy Wind", chooseCompatibleMoveId(configured, "Icy Wind", false))
    }

    private fun move(showdownId: String, basePower: Int) = MoveValue(
        entity = EntityValue("move.${showdownId.lowercase().replace(' ', '-')}", showdownId, showdownId, "move"),
        basePower = basePower,
    )
}
