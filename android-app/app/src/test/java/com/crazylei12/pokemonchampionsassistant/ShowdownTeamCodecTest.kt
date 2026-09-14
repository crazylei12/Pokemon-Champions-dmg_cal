package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ShowdownTeamCodecTest {
    private val sample = """
        Cat (Incineroar) (M) @ Safety Goggles
        Ability: Intimidate
        Level: 50
        EVs: 32 HP / 2 Atk / 32 SpD
        Careful Nature
        - Fake Out
        - Knock Off
        - Parting Shot
        - Flare Blitz
    """.trimIndent()

    @Test fun readsNicknamesGenderCrLfAndSixMembers() {
        val parsed = ShowdownTeamCodec.parse("\uFEFF=== [gen9championsvgc2026regma] Team ===\r\n\r\n" + List(6) { sample }.joinToString("\n\n").replace("\n", "\r\n"))
        assertEquals(6, parsed.members.size)
        assertEquals("Incineroar", parsed.members.first().speciesId)
        assertEquals("M", parsed.members.first().gender)
        assertEquals("Safety Goggles", parsed.members.first().itemId)
        assertEquals("32", parsed.members.first().statPoints.spd)
        assertTrue(parsed.warnings.any { "昵称" in it })
    }

    @Test fun distinguishesPointsFromTraditionalEvsAndWarnsAboutIvs() {
        val traditional = sample.replace("32 HP / 2 Atk / 32 SpD", "252 HP / 4 Atk / 252 SpD") + "\nIVs: 0 Spe"
        assertTrue(runCatching { ShowdownTeamCodec.parse(traditional) }.isFailure)
        val parsed = ShowdownTeamCodec.parse(traditional, true)
        assertEquals("32", parsed.members.first().statPoints.hp)
        assertEquals("1", parsed.members.first().statPoints.atk)
        assertEquals("32", parsed.members.first().statPoints.spd)
        assertTrue(parsed.warnings.any { "非 31 IV" in it })
    }

    @Test fun rejectsMalformedAmbiguousAndOversizedTeams() {
        listOf("", List(7) { sample }.joinToString("\n\n"), sample + "\nEVs: 1 HP",
            sample.replace("32 HP / 2 Atk / 32 SpD", "32 HP / 32 Atk / 32 SpD"),
            sample.replace("32 HP / 2 Atk / 32 SpD", "1 HP / 1 HP"),
            sample + "\n- Protect", sample.replace("- Knock Off", "- Fake Out"),
            sample + "\nUnknown: value", sample + "\nSPs: 2 HP",
            sample.replace("Ability: Intimidate\n", ""), sample + "\nIVs: 32 HP"
        ).forEach { assertTrue(it, runCatching { ShowdownTeamCodec.parse(it) }.isFailure) }
    }

    private fun entity(type: String, id: String) = EntityValue("$type.${normalizeShowdownId(id)}", id, id, type)
    private fun config(): PokemonConfig = PokemonConfig(
        species = entity("species", "Incineroar"), level = 50,
        actualStats = StatFields("202", "137", "110", "90", "156", "80"),
        statPoints = StatFields("32", "2", "0", "0", "32", "0"),
        ability = entity("ability", "Intimidate"), item = entity("item", "Safety Goggles"),
        moves = listOf("Fake Out", "Knock Off", "Parting Shot", "Flare Blitz").map { MoveValue(entity("move", it)) },
        statAlignment = entity("nature", "Careful"), gender = "M",
    )

    @Test fun savedTeamReloadAndExportRetainBattleFields() {
        val original = config()
        val json = createImportedTeamJson("test", "PS", "", null, listOf(original), Instant.EPOCH)
        val loaded = TeamRepository.parseTeam(json, true).pokemon.single()
        assertEquals(original, loaded)
        val result = ShowdownTeamCodec.parse(ShowdownTeamCodec.export(listOf(loaded))).members.single()
        assertEquals(ShowdownTeamCodec.parse(sample).members.single(), result)
        assertFalse(ShowdownTeamCodec.export(listOf(loaded)).contains("IVs:"))
    }

    @Test fun refusesToInventMissingExportData() {
        assertTrue(runCatching { ShowdownTeamCodec.export(listOf(config().copy(statAlignment = null))) }.isFailure)
        assertTrue(runCatching { ShowdownTeamCodec.export(listOf(config().copy(statPoints = StatFields()))) }.isFailure)
    }

    @Test fun acceptsNoItemZeroPointsAndExplicitSpWithDefaults() {
        val parsed = ShowdownTeamCodec.parse("Pikachu @ (No Item)\nAbility: Static\nSPs: 0 HP\n- Thunderbolt")
        assertNull(parsed.members.single().itemId)
        assertEquals("Serious", parsed.members.single().natureId)
        assertEquals("0", parsed.members.single().statPoints.spe)
    }
}
