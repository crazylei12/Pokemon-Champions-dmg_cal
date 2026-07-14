package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedLineTest {
    @Test
    fun `protect family is excluded while other status priority remains visible`() {
        assertTrue(isProtectivePriorityMove("Protect"))
        assertTrue(isProtectivePriorityMove("Wide Guard"))
        assertTrue(!isProtectivePriorityMove("Helping Hand"))
        assertTrue(!isProtectivePriorityMove("Follow Me"))
    }

    @Test
    fun `opponent base speed range covers zero to max points and both natures`() {
        assertEquals(72..123, possibleOpponentSpeedRange(60))
    }

    @Test
    fun `all requested speed modifiers compose with the speed stage`() {
        val modifiers = SpeedPokemonModifiers(
            stage = 1,
            paralyzed = true,
            doubled = true,
            choiceScarf = true,
        )
        assertEquals(450, effectiveSpeed(100, modifiers, tailwind = true))
    }

    @Test
    fun `speed stage rounds before scarf like the bundled Champions engine`() {
        assertEquals(
            226,
            effectiveSpeed(
                101,
                SpeedPokemonModifiers(stage = 1, choiceScarf = true),
                tailwind = false,
            ),
        )
    }

    @Test
    fun `known own choice scarf is automatic but can be explicitly disabled`() {
        assertEquals(150, effectiveSpeed(100, SpeedPokemonModifiers(), tailwind = false, knownChoiceScarf = true))
        assertEquals(
            100,
            effectiveSpeed(
                100,
                SpeedPokemonModifiers(choiceScarf = false),
                tailwind = false,
                knownChoiceScarf = true,
            ),
        )
    }

    @Test
    fun `priority stays ahead while trick room reverses speed only within a bracket`() {
        val fast = SpeedLinePokemonInput(
            side = SpeedSide.OWN,
            slot = 0,
            name = "快",
            baseSpeed = 150..150,
            exactBaseSpeed = true,
            priorityMoves = listOf(SpeedLineMove("先制招式", 1)),
        )
        val slow = SpeedLinePokemonInput(
            side = SpeedSide.OWN,
            slot = 1,
            name = "慢",
            baseSpeed = 50..50,
            exactBaseSpeed = true,
        )
        val actions = buildSpeedLineActions(listOf(fast, slow), trickRoom = true)

        assertEquals(1, actions.first().priority)
        assertEquals(listOf("慢", "快"), actions.filter { it.priority == 0 }.map { it.pokemonName })
        assertTrue(speedAxisFraction(50, 50, 150, trickRoom = true) < speedAxisFraction(150, 50, 150, trickRoom = true))
        assertTrue(speedAxisFraction(150, 50, 150, trickRoom = false) < speedAxisFraction(50, 50, 150, trickRoom = false))
    }
}
