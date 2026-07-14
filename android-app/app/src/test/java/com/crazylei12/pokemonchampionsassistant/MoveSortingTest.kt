package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class MoveSortingTest {
    @Test
    fun `pinyin mode orders Chinese move names by pronunciation`() {
        val moves = listOf(
            move("Play Rough", "嬉闹", "Fairy"),
            move("Flamethrower", "喷射火焰", "Fire"),
            move("Thunder Punch", "雷电拳", "Electric"),
            move("Ice Punch", "冰冻拳", "Ice"),
        )

        assertEquals(
            listOf("冰冻拳", "雷电拳", "喷射火焰", "嬉闹"),
            sortMoves(moves, MoveSortMode.PINYIN).map { it.entity.displayName },
        )
    }

    @Test
    fun `type mode groups by stable type order and uses pinyin within each type`() {
        val moves = listOf(
            move("Unknown Move", "未知招式", null),
            move("Water Pulse", "水之波动", "Water"),
            move("Will-O-Wisp", "鬼火", "Fire"),
            move("Flamethrower", "喷射火焰", "Fire"),
            move("Tackle", "撞击", "Normal"),
        )

        assertEquals(
            listOf("Tackle", "Will-O-Wisp", "Flamethrower", "Water Pulse", "Unknown Move"),
            sortMoves(moves, MoveSortMode.TYPE).map { it.entity.showdownId },
        )
    }

    private fun move(showdownId: String, displayName: String, type: String?) = MoveValue(
        entity = EntityValue("move.${showdownId.lowercase().replace(' ', '-')}", showdownId, displayName, "move"),
        type = type,
    )
}
