package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.nio.file.Files

class BattleStateAndReadinessTest {
    @Test
    fun battleConditionsAreIsolatedByTeamSlot() {
        val boosted = BattlePokemonCondition(
            burned = true,
            stages = BattleStatStages(atk = 2),
        )
        val state = BattleCalculationState().withOwnCondition(slot = 1, condition = boosted)

        assertTrue(state.ownCondition(1).burned)
        assertTrue(state.ownCondition(1).stages.atk == 2)
        assertFalse(state.ownCondition(0).burned)
        assertTrue(state.ownCondition(0).stages == BattleStatStages())
    }

    @Test
    fun readinessRequiresStatsAbilityAndAtLeastOneMove() {
        val ready = pokemon(moves = listOf(move("Protect")))

        assertTrue(ready.isDamageReady())
        assertTrue(ready.moveSlotReminder()?.contains("空技能槽") == true)
        assertFalse(ready.copy(ability = null).isDamageReady())
        assertFalse(ready.copy(moves = emptyList()).isDamageReady())
        assertFalse(ready.copy(actualStats = StatFields()).isDamageReady())
    }

    @Test
    fun legacyGlobalConditionMigratesOnlyToThePreviouslySelectedSlot() {
        val legacy = JSONObject()
            .put("ownSlot", 2)
            .put("opponentSlot", 4)
            .put("ownBurned", true)
            .put("ownStages", JSONObject().put("spa", 2))
            .put("opponentStages", JSONObject().put("def", -1))

        val migrated = BattleCalculationState.fromJson(legacy)

        assertTrue(migrated.ownCondition(2).burned)
        assertEquals(2, migrated.ownCondition(2).stages.spa)
        assertEquals(-1, migrated.opponentCondition(4).stages.def)
        assertEquals(BattlePokemonCondition(), migrated.ownCondition(0))
        val persisted = migrated.toJson()
        assertTrue(persisted.has("ownConditions"))
        assertFalse(persisted.has("ownBurned"))
    }

    @Test
    fun opponentItemOverrideDistinguishesInheritExplicitNoneAndObservedItem() {
        val item = EntityValue("item.choice-band", "Choice Band", "讲究头带", "item")
        val explicitNone = OpponentManualOverride("base", StatFields(), null, null, true, null)
        val observed = explicitNone.copy(item = item)

        assertTrue(explicitNone.toJson().getBoolean("itemOverrideEnabled"))
        assertFalse(explicitNone.toJson().has("item"))
        assertEquals("Choice Band", OpponentManualOverride.fromJson(observed.toJson()).item?.showdownId)
        assertFalse(OpponentManualOverride.fromJson(JSONObject().put("baseProfileId", "base")).itemOverrideEnabled)
    }

    @Test
    fun aNewTeamPreviewInvalidatesThePreviousBattleSession() {
        val filesDir = Files.createTempDirectory("team-preview-reset").toFile()
        try {
            val battleDirectory = filesDir.resolve("battle-session").apply { mkdirs() }
            val previousSession = battleDirectory.resolve("current-battle-session.json").apply {
                writeText("old battle state")
            }

            replaceCurrentTeamPreview(filesDir, "new preview")

            assertFalse(previousSession.exists())
            assertEquals("new preview", battleDirectory.resolve("current-team-preview.json").readText())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private fun pokemon(moves: List<MoveValue>) = PokemonConfig(
        species = EntityValue("species.test", "Test", "测试", "species"),
        level = 50,
        actualStats = StatFields("100", "100", "100", "100", "100", "100"),
        statPoints = StatFields(),
        ability = EntityValue("ability.test", "Test Ability", "测试特性", "ability"),
        item = null,
        moves = moves,
    )

    private fun move(id: String) = MoveValue(EntityValue("move.$id", id, id, "move"))
}
