package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.nio.file.Files

class BattleStateAndReadinessTest {
    @Test
    fun battlefieldSettingsExposeWeatherAndTerrainChoicesFirstClass() {
        assertEquals(listOf("NONE", "Sun", "Rain", "Sand", "Snow"), BATTLE_WEATHER_VALUES)
        assertEquals(listOf("NONE", "Electric", "Grassy", "Psychic", "Misty"), BATTLE_TERRAIN_VALUES)
    }

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
    fun directHudSelectionAndModeRoundTripWhileOldSessionsMigrateVisibility() {
        val state = BattleCalculationState(
            directHud = BattleDirectHudState(
                ownSlots = listOf(2, 5),
                opponentSlots = listOf(1, 4),
                mode = BattleDirectHudMode.HIDDEN,
            ),
        )

        assertEquals(state.directHud, BattleCalculationState.fromJson(state.toJson()).directHud)
        assertEquals(BattleDirectHudState(), BattleCalculationState.fromJson(JSONObject()).directHud)
        assertEquals(
            BattleDirectHudMode.CALCULATION,
            BattleDirectHudState.fromJson(JSONObject().put("visible", true)).mode,
        )
        assertEquals(
            BattleDirectHudMode.HIDDEN,
            BattleDirectHudState.fromJson(JSONObject().put("visible", false)).mode,
        )
    }

    @Test
    fun battleTypeSwitchControlsDoubleOnlyDamageModifiers() {
        val double = BattleCalculationState(
            battleType = "SINGLE",
            spread = false,
            helpingHand = true,
        ).withBattleTypeDefaults("DOUBLE")
        val single = double.copy(helpingHand = true).withBattleTypeDefaults("SINGLE")

        assertEquals("DOUBLE", double.battleType)
        assertTrue(double.spread)
        assertTrue(double.helpingHand)
        assertEquals("SINGLE", single.battleType)
        assertFalse(single.spread)
        assertFalse(single.helpingHand)
    }

    @Test
    fun activeAuraAbilitiesApplyGloballyAcrossBothSides() {
        val effects = resolveActiveBattleAbilityEffects(
            battleType = "DOUBLE",
            calculationDirection = "OWN_TO_OPPONENT",
            ownSlot = 0,
            opponentSlot = 0,
            activeOwnAbilities = mapOf(0 to "Intimidate", 1 to "Fairy Aura"),
            activeOpponentAbilities = mapOf(0 to "Dark Aura", 1 to "Pressure"),
        )

        assertTrue(effects.fairyAura)
        assertTrue(effects.darkAura)
    }

    @Test
    fun friendGuardProtectsOnlyTheActiveDefendersPartnerInDoubles() {
        val partnerProtection = resolveActiveBattleAbilityEffects(
            battleType = "DOUBLE",
            calculationDirection = "OWN_TO_OPPONENT",
            ownSlot = 0,
            opponentSlot = 0,
            activeOwnAbilities = emptyMap(),
            activeOpponentAbilities = mapOf(0 to "Pressure", 1 to "Friend Guard"),
        )
        val selfDoesNotProtect = resolveActiveBattleAbilityEffects(
            battleType = "DOUBLE",
            calculationDirection = "OWN_TO_OPPONENT",
            ownSlot = 0,
            opponentSlot = 0,
            activeOwnAbilities = emptyMap(),
            activeOpponentAbilities = mapOf(0 to "Friend Guard", 1 to "Pressure"),
        )
        val ownPartnerProtection = resolveActiveBattleAbilityEffects(
            battleType = "DOUBLE",
            calculationDirection = "OPPONENT_TO_OWN",
            ownSlot = 0,
            opponentSlot = 0,
            activeOwnAbilities = mapOf(0 to "Pressure", 1 to "Friend Guard"),
            activeOpponentAbilities = emptyMap(),
        )
        val singlesHasNoPartner = resolveActiveBattleAbilityEffects(
            battleType = "SINGLE",
            calculationDirection = "OPPONENT_TO_OWN",
            ownSlot = 0,
            opponentSlot = 0,
            activeOwnAbilities = mapOf(1 to "Friend Guard"),
            activeOpponentAbilities = emptyMap(),
        )

        assertTrue(partnerProtection.defendingFriendGuard)
        assertFalse(selfDoesNotProtect.defendingFriendGuard)
        assertTrue(ownPartnerProtection.defendingFriendGuard)
        assertFalse(singlesHasNoPartner.defendingFriendGuard)
    }

    @Test
    fun activeBattleSlotsNeverScanBenchPokemon() {
        val doubles = BattleCalculationState(
            battleType = "DOUBLE",
            ownSlot = 3,
            opponentSlot = 2,
            directHud = BattleDirectHudState(
                ownSlots = listOf(0, 1),
                opponentSlots = listOf(2, 4),
            ),
        )

        assertEquals(listOf(3, 0), activeBattleSlots(doubles, teamSize = 6, ownSide = true))
        assertEquals(listOf(2, 4), activeBattleSlots(doubles, teamSize = 6, ownSide = false))
        assertEquals(
            listOf(3),
            activeBattleSlots(doubles.copy(battleType = "SINGLE"), teamSize = 6, ownSide = true),
        )
    }

    @Test
    fun hudPresetSelectionReplacesTheCurrentManualOverrideAndUsesThePresetMove() {
        val manual = OpponentManualOverride("old", StatFields(), null, null)
        val state = BattleCalculationState(
            direction = "OPPONENT_TO_OWN",
            opponentSlot = 2,
            selectedPresetId = "old",
            selectedMoveId = "Old Move",
            opponentManualOverrides = mapOf(2 to manual, 4 to manual),
        )
        val statusMove = MoveValue(EntityValue("move.protect", "Protect", "守住", "move"), basePower = 0)
        val damageMove = MoveValue(EntityValue("move.thunderbolt", "Thunderbolt", "十万伏特", "move"), basePower = 90)
        val preset = OpponentPreset(
            profileId = "bulky",
            profileName = "常规耐久",
            source = "OPEN_SOURCE_PRESET",
            level = 50,
            statPoints = StatFields(),
            actualStats = StatFields(),
            statAlignment = null,
            ability = null,
            item = null,
            moves = listOf(statusMove, damageMove),
        )

        val changed = applyOpponentPresetSelection(state, preset)

        assertEquals("bulky", changed.selectedPresetId)
        assertEquals("bulky", changed.opponentPresetIds[2])
        assertEquals("Thunderbolt", changed.selectedMoveId)
        assertFalse(changed.opponentManualOverrides.containsKey(2))
        assertTrue(changed.opponentManualOverrides.containsKey(4))
    }

    @Test
    fun opponentPresetSelectionIsRememberedPerPokemonAndSurvivesPersistence() {
        val first = BattleCalculationState(opponentSlot = 0)
            .withOpponentPreset("fast-attacker")
        val second = first.withOpponentSlot(1)
            .withOpponentPreset("bulky-support")

        assertEquals("fast-attacker", second.withOpponentSlot(0).selectedPresetId)
        assertEquals("bulky-support", second.withOpponentSlot(0).withOpponentSlot(1).selectedPresetId)

        val restored = BattleCalculationState.fromJson(second.toJson())
        assertEquals("fast-attacker", restored.withOpponentSlot(0).selectedPresetId)
        assertEquals("bulky-support", restored.withOpponentSlot(1).selectedPresetId)
    }

    @Test
    fun deletingAUserPresetClearsEverySessionReferenceToIt() {
        val deletedOverride = OpponentManualOverride("user.deleted", StatFields(), null, null)
        val retainedOverride = OpponentManualOverride("user.retained", StatFields(), null, null)
        val state = BattleCalculationState(
            opponentSlot = 2,
            selectedPresetId = "user.deleted",
            opponentPresetIds = mapOf(
                0 to "user.deleted",
                2 to "user.deleted",
                4 to "user.retained",
            ),
            opponentManualOverrides = mapOf(
                0 to deletedOverride,
                1 to deletedOverride,
                4 to retainedOverride,
            ),
        )

        val cleaned = removeOpponentPresetReferences(state, "user.deleted")

        assertEquals(null, cleaned.selectedPresetId)
        assertEquals(mapOf(4 to "user.retained"), cleaned.opponentPresetIds)
        assertEquals(setOf(4), cleaned.opponentManualOverrides.keys)
    }

    @Test
    fun legacySelectedPresetMigratesToTheCurrentOpponentPokemon() {
        val restored = BattleCalculationState.fromJson(
            JSONObject()
                .put("opponentSlot", 3)
                .put("selectedPresetId", "legacy-bulky"),
        )

        assertEquals("legacy-bulky", restored.opponentPresetIds[3])
        assertEquals("legacy-bulky", restored.withOpponentSlot(3).selectedPresetId)
    }

    @Test
    fun panelCalculationOnlyChangesCanRefreshWithoutRebuildingControls() {
        val session = BattleSession(
            sessionId = "battle-test",
            createdAt = "now",
            previewCapturedAt = "preview",
            selectedOwnTeamId = "team",
            opponentTeam = emptyList(),
        )
        val calculationOnly = session.copy(calculation = session.calculation.copy(
            selectedMoveId = "Thunderbolt",
            weather = "Rain",
            terrain = "Electric",
        ))

        assertTrue(isBattlePanelCalculationOnlyChange(session, calculationOnly))
        assertFalse(isBattlePanelCalculationOnlyChange(session, calculationOnly.copy(
            calculation = calculationOnly.calculation.copy(ownSlot = 1),
        )))
        assertFalse(isBattlePanelCalculationOnlyChange(session, calculationOnly.copy(
            selectedOwnTeamId = "other-team",
        )))
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

    @Test
    fun hudLaunchClearsOnlyTransientBattleState() {
        val filesDir = Files.createTempDirectory("hud-launch-reset").toFile()
        try {
            val battleDirectory = filesDir.resolve("battle-session").apply { mkdirs() }
            battleDirectory.resolve("current-battle-session.json").writeText("old battle state")
            battleDirectory.resolve("current-team-preview.json").writeText("old preview")
            val savedTeam = filesDir.resolve("saved-teams/team.json").apply {
                parentFile?.mkdirs()
                writeText("saved team")
            }

            clearTransientBattleState(filesDir)

            assertFalse(battleDirectory.resolve("current-battle-session.json").exists())
            assertFalse(battleDirectory.resolve("current-team-preview.json").exists())
            assertEquals("saved team", savedTeam.readText())
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
