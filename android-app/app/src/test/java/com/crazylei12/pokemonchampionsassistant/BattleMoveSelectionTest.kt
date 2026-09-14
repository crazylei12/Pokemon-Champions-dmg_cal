package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

class BattleMoveSelectionTest {
    @Test
    fun `generated Pawmot moves remain searchable selectable and serialized including status moves`() {
        val relative = Path.of("src", "data", "damage", "champions-presets.json")
        val source = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .map { it.resolve(relative) }.first(Files::isRegularFile)
        val forms = JSONObject(Files.readAllBytes(source).toString(Charsets.UTF_8)).getJSONArray("speciesForms")
        val form = (0 until forms.length()).map(forms::getJSONObject)
            .first { it.getJSONObject("species").getString("showdownId") == "Pawmot" }
        val entries = form.getJSONArray("learnableMoves")
        val moves = (0 until entries.length()).map { index ->
            val row = entries.getJSONObject(index)
            val entity = row.getJSONObject("move")
            MoveValue(EntityValue(entity.getString("canonicalId"), entity.getString("showdownId"), entity.getString("displayName"), "move"),
                basePower = row.optInt("basePower").takeIf { it > 0 })
        }
        val shock = moves.single { it.entity.showdownId == "Double Shock" }
        val revival = moves.single { it.entity.showdownId == "Revival Blessing" }
        assertTrue(shock.matchesSearch("电光双击"))
        assertTrue(revival.matchesSearch("复生祈祷"))
        assertEquals("Revival Blessing", chooseCompatibleMoveId(moves, "revivalblessing", true))
        val config = PokemonConfig(
            species = EntityValue("species.pawmot", "Pawmot", "巴布土拨", "species"), level = 50,
            actualStats = StatFields(), statPoints = StatFields(), ability = null, item = null,
            moves = actualConfiguredMoves(listOf(shock, revival)),
        )
        val saved = PokemonEditorState.from(config).toBuildJson("OWN_BUILD").getJSONArray("moves")
        assertEquals(2, saved.length())
        assertEquals("Revival Blessing", saved.getJSONObject(1).getJSONObject("move").getString("showdownId"))
        assertEquals("OWN_BUILD", saved.getJSONObject(1).getString("source"))
    }

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
