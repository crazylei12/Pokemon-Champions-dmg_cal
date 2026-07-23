package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class BattleMoveSelectionTest {
    @Test
    fun `actual configured moves remain available when the snapshot omits one`() {
        val configured = listOf(move("Shadow Ball", 80), move("Icy Wind", 55))
        val actual = actualConfiguredMoves(configured)

        assertEquals(configured, actual)
        assertEquals("Shadow Ball", chooseCompatibleMoveId(actual, "shadow-ball", false))
    }

    @Test
    fun `actual configured moves are deduplicated without consulting the snapshot`() {
        val configured = listOf(move("Shadow Ball", 80), move("shadow-ball", 80), move("Icy Wind", 55))

        assertEquals(
            listOf("Shadow Ball", "Icy Wind"),
            actualConfiguredMoves(configured).map { it.entity.showdownId },
        )
    }

    @Test
    fun `configured move outside the snapshot is serialized as an own build move`() {
        val config = PokemonConfig(
            species = EntityValue("species.incineroar", "Incineroar", "Incineroar", "species"),
            level = 50,
            actualStats = StatFields(),
            statPoints = StatFields(),
            ability = null,
            item = null,
            moves = listOf(move("Knock Off", 65)),
        )

        val moveEntry = PokemonEditorState.from(config)
            .toBuildJson("OWN_BUILD")
            .getJSONArray("moves")
            .getJSONObject(0)

        assertEquals("Knock Off", moveEntry.getJSONObject("move").getString("showdownId"))
        assertEquals("OWN_BUILD", moveEntry.getString("source"))
    }

    @Test
    fun `preset preferences cannot add moves outside the legal snapshot`() {
        val legal = listOf(move("Icy Wind", 55), move("Protect", 0))
        val preferred = listOf(move("Shadow Ball", 80), move("Protect", 0))

        assertEquals(
            listOf("Protect", "Icy Wind"),
            prioritizeLegalMoves(preferred, legal).map { it.entity.showdownId },
        )
    }

    @Test
    fun `preset preferences stay empty when the legal snapshot is missing`() {
        assertEquals(emptyList<MoveValue>(), prioritizeLegalMoves(listOf(move("Shadow Ball", 80)), emptyList()))
    }

    @Test
    fun `configured move options put actual moves before legal alternatives`() {
        val configured = listOf(move("Shadow Ball", 80))
        val legal = listOf(move("Icy Wind", 55), move("Shadow Ball", 80), move("Protect", 0))

        assertEquals(
            listOf("Shadow Ball", "Icy Wind", "Protect"),
            configuredMoveOptions(configured, legal).map { it.entity.showdownId },
        )
    }

    private fun move(showdownId: String, basePower: Int) = MoveValue(
        entity = EntityValue("move.${showdownId.lowercase().replace(' ', '-')}", showdownId, showdownId, "move"),
        basePower = basePower,
    )
}
