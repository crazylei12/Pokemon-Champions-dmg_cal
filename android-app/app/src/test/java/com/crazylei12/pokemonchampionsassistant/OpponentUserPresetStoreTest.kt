package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class OpponentUserPresetStoreTest {
    @Test
    fun savedPresetsPersistPerSpeciesInSaveOrder() {
        val directory = Files.createTempDirectory("opponent-user-presets").toFile()
        try {
            val file = directory.resolve(USER_OPPONENT_PRESETS_FILE)
            val store = OpponentUserPresetStore(file)
            store.save("Pikachu", preset("user.first", "耐久"))
            store.save("Eevee", preset("user.other", "伊布配置"))
            store.save("Pikachu", preset("user.second", "围巾极速"))

            val reloaded = OpponentUserPresetStore(file)
            val pikachu = reloaded.profilesFor("Pikachu")
            val generated = preset("user.generated", "内置模板").copy(
                profileId = "generated.default",
                source = "GENERATED_TEMPLATE",
            )
            val builtIn = preset("user.built-in", "常见配置").copy(
                profileId = "open-source.common",
                source = "OPEN_SOURCE_PRESET",
            )
            val displayed = orderOpponentProfiles(pikachu, listOf(generated), listOf(builtIn))

            assertEquals(listOf("耐久", "围巾极速"), pikachu.map(OpponentPreset::profileName))
            assertEquals(
                listOf("耐久", "围巾极速", "内置模板", "常见配置"),
                displayed.map(OpponentPreset::profileName),
            )
            assertEquals("Choice Scarf", pikachu.last().item?.showdownId)
            assertEquals("Static", pikachu.last().ability?.showdownId)
            assertEquals("Thunderbolt", pikachu.last().moves.single().entity.showdownId)
            assertEquals(listOf("伊布配置"), reloaded.profilesFor("Eevee").map(OpponentPreset::profileName))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun profilesForMultipleSharedFormsKeepsTheGlobalSaveOrder() {
        val directory = Files.createTempDirectory("opponent-shared-user-presets").toFile()
        try {
            val store = OpponentUserPresetStore(directory.resolve(USER_OPPONENT_PRESETS_FILE))
            store.save("Staraptor", preset("user.base-first", "普通一"))
            store.save("Rotom-Wash", preset("user.unrelated", "清洗洛托姆"))
            store.save("Staraptor-Mega", preset("user.mega", "超级形态保存"))
            store.save("Staraptor", preset("user.base-second", "普通二"))

            val shared = store.entriesFor(listOf("Staraptor", "Staraptor-Mega"))

            assertEquals(
                listOf("普通一", "超级形态保存", "普通二"),
                shared.map { it.preset.profileName },
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun onlyMegaFormsShareWithTheirUnderlyingBaseForm() {
        val slowbroFamily = listOf(
            form("slowbro", "Slowbro"),
            form("slowbro", "Slowbro-Galar"),
            form("slowbro", "Slowbro-Mega"),
        )
        val charizardFamily = listOf(
            form("charizard", "Charizard"),
            form("charizard", "Charizard-Mega-X"),
            form("charizard", "Charizard-Mega-Y"),
        )
        val floetteFamily = listOf(
            form("floetteeternal", "Floette-Eternal"),
            form("floetteeternal", "Floette-Mega"),
        )
        val meowsticFamily = listOf(
            form("meowstic", "Meowstic"),
            form("meowstic", "Meowstic-F"),
            form("meowstic", "Meowstic-F-Mega"),
            form("meowstic", "Meowstic-M-Mega"),
        )

        fun sharedIds(selected: SpeciesFormOption, family: List<SpeciesFormOption>) =
            userOpponentPresetSharingForms(selected, family).map { it.species.showdownId }

        assertEquals(listOf("Slowbro", "Slowbro-Mega"), sharedIds(slowbroFamily[0], slowbroFamily))
        assertEquals(listOf("Slowbro-Galar"), sharedIds(slowbroFamily[1], slowbroFamily))
        assertEquals(listOf("Slowbro", "Slowbro-Mega"), sharedIds(slowbroFamily[2], slowbroFamily))
        assertEquals(
            listOf("Charizard", "Charizard-Mega-X", "Charizard-Mega-Y"),
            sharedIds(charizardFamily[2], charizardFamily),
        )
        assertEquals(
            listOf("Floette-Eternal", "Floette-Mega"),
            sharedIds(floetteFamily[1], floetteFamily),
        )
        assertEquals(
            listOf("Meowstic-F", "Meowstic-F-Mega"),
            sharedIds(meowsticFamily[2], meowsticFamily),
        )
        assertEquals(
            listOf("Meowstic", "Meowstic-M-Mega"),
            sharedIds(meowsticFamily[3], meowsticFamily),
        )
        assertFalse(isMegaSpeciesForm("Meganium"))
        assertTrue(isMegaSpeciesForm("Meganium-Mega"))
    }

    @Test
    fun sharedPresetUsesTargetFormStatsAndAValidTargetAbility() {
        val megaAbility = EntityValue("ability.contrary", "Contrary", "唱反调", "ability")
        val targetStats = StatFields("120", "140", "100", "70", "90", "125")

        val adapted = adaptSharedUserOpponentPreset(
            preset = preset("user.shared", "共享配置"),
            targetActualStats = targetStats,
            targetAbilities = listOf(megaAbility),
            targetDefaultAbility = megaAbility,
        )

        assertEquals(targetStats, adapted.actualStats)
        assertEquals(megaAbility, adapted.ability)
        assertEquals("32", adapted.statPoints.hp)
        assertEquals("Choice Scarf", adapted.item?.showdownId)
        assertEquals("Thunderbolt", adapted.moves.single().entity.showdownId)
    }

    @Test
    fun temporaryOverrideKeepsItsEditsAndCorrectsAnInvalidAbilityForMega() {
        val megaAbility = EntityValue("ability.contrary", "Contrary", "唱反调", "ability")
        val manual = OpponentManualOverride(
            baseProfileId = "open-source.base",
            statPoints = StatFields(hp = "28", atk = "32", spe = "16"),
            statAlignment = EntityValue("nature.adamant", "Adamant", "固执", "nature"),
            ability = EntityValue("ability.intimidate", "Intimidate", "威吓", "ability"),
            itemOverrideEnabled = true,
            item = EntityValue("item.choice-band", "Choice Band", "讲究头带", "item"),
        )

        val adapted = adaptSharedOpponentManualOverride(
            override = manual,
            targetBaseProfileId = "generated.default",
            targetAbilities = listOf(megaAbility),
            targetDefaultAbility = megaAbility,
        )

        assertEquals("generated.default", adapted.baseProfileId)
        assertEquals(manual.statPoints, adapted.statPoints)
        assertEquals(manual.statAlignment, adapted.statAlignment)
        assertEquals(megaAbility, adapted.ability)
        assertTrue(adapted.itemOverrideEnabled)
        assertEquals(manual.item, adapted.item)
    }

    @Test
    fun formChangeRetainsSharedSavedAndTemporaryConfigurationsButClearsOtherForms() {
        val manual = OpponentManualOverride(
            baseProfileId = "user.shared",
            statPoints = StatFields(hp = "28", spe = "32"),
            statAlignment = null,
            ability = EntityValue("ability.intimidate", "Intimidate", "威吓", "ability"),
            itemOverrideEnabled = true,
            item = EntityValue("item.choice-scarf", "Choice Scarf", "讲究围巾", "item"),
        )
        val state = BattleCalculationState(
            opponentSlot = 1,
            selectedPresetId = "user.shared",
            opponentPresetIds = mapOf(1 to "user.shared"),
            opponentManualOverrides = mapOf(1 to manual),
        )
        val megaAbility = EntityValue("ability.contrary", "Contrary", "唱反调", "ability")
        val targetProfiles = listOf(preset("user.shared", "共享配置"))

        val shared = retainOpponentFormConfiguration(
            state = state,
            slot = 1,
            targetProfiles = targetProfiles,
            sharesConfiguration = true,
        ) { override, targetBaseProfileId ->
            adaptSharedOpponentManualOverride(
                override = override,
                targetBaseProfileId = targetBaseProfileId,
                targetAbilities = listOf(megaAbility),
                targetDefaultAbility = megaAbility,
            )
        }
        val isolated = retainOpponentFormConfiguration(
            state = state,
            slot = 1,
            targetProfiles = targetProfiles,
            sharesConfiguration = false,
        ) { override, _ -> override }
        val builtInState = state.withOpponentPreset("open-source.base", 1).copy(
            opponentManualOverrides = mapOf(1 to manual.copy(baseProfileId = "open-source.base")),
        )
        val rebased = retainOpponentFormConfiguration(
            state = builtInState,
            slot = 1,
            targetProfiles = listOf(
                preset("user.generated", "无加点").copy(
                    profileId = "generated.default",
                    source = "GENERATED_TEMPLATE",
                ),
            ),
            sharesConfiguration = true,
        ) { override, targetBaseProfileId -> override.copy(baseProfileId = targetBaseProfileId) }

        assertEquals("user.shared", shared.opponentPresetId(1))
        assertEquals(manual.statPoints, shared.opponentManualOverrides.getValue(1).statPoints)
        assertEquals(megaAbility, shared.opponentManualOverrides.getValue(1).ability)
        assertNull(isolated.opponentPresetId(1))
        assertFalse(isolated.opponentManualOverrides.containsKey(1))
        assertEquals("generated.default", rebased.opponentPresetId(1))
        assertEquals("generated.default", rebased.opponentManualOverrides.getValue(1).baseProfileId)
        assertEquals(manual.statPoints, rebased.opponentManualOverrides.getValue(1).statPoints)
    }

    @Test
    fun customPresetNameMustBeNonBlankAndAtMostTwentyFourCharacters() {
        val directory = Files.createTempDirectory("opponent-user-preset-validation").toFile()
        try {
            val store = OpponentUserPresetStore(directory.resolve(USER_OPPONENT_PRESETS_FILE))

            assertTrue(runCatching { store.save("Pikachu", preset("user.blank", "")) }.isFailure)
            assertTrue(runCatching {
                store.save("Pikachu", preset("user.long", "一".repeat(25)))
            }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun updatesAndDeletesAreVisibleToOtherStoreInstances() {
        val directory = Files.createTempDirectory("opponent-user-preset-management").toFile()
        try {
            val file = directory.resolve(USER_OPPONENT_PRESETS_FILE)
            val writer = OpponentUserPresetStore(file)
            val observer = OpponentUserPresetStore(file)
            writer.save("Pikachu", preset("user.managed", "旧名称"))

            val updated = preset("user.managed", "新名称").copy(
                statPoints = StatFields(hp = "32", def = "32"),
            )
            writer.update("Pikachu", updated)

            assertEquals("新名称", observer.all().single().preset.profileName)
            assertEquals("32", observer.profilesFor("Pikachu").single().statPoints.def)
            assertTrue(writer.delete("user.managed"))
            assertTrue(observer.all().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingSelectedPresetFallsBackToTheFirstAvailableProfile() {
        val profiles = listOf(
            preset("user.first", "第一个"),
            preset("user.second", "第二个"),
        )

        assertEquals("user.second", selectAvailableOpponentPreset(profiles, "user.second").profileId)
        assertEquals("user.first", selectAvailableOpponentPreset(profiles, "user.deleted").profileId)
    }

    @Test
    fun blankHomepageDraftBelongsToTheManuallySelectedPokemon() {
        val species = EntityValue(
            canonicalId = "species.gengar",
            showdownId = "Gengar",
            displayName = "耿鬼",
            entityType = "species",
        )

        val draft = blankUserOpponentPresetDraft(species)

        assertEquals(species, draft.species)
        assertTrue(draft.preset.profileId.startsWith("user.draft.gengar"))
        assertEquals(OpponentUserPresetStore.USER_PRESET_SOURCE, draft.preset.source)
        assertEquals(50, draft.preset.level)
        assertTrue(draft.preset.statPoints.asMap().values.all(String::isBlank))
        assertNull(draft.preset.statAlignment)
        assertNull(draft.preset.ability)
        assertNull(draft.preset.item)
        assertTrue(draft.preset.moves.isEmpty())
    }

    @Test
    fun sharedPresetsMergeWithoutDuplicatingARepeatedImport() {
        val directory = Files.createTempDirectory("opponent-user-preset-merge").toFile()
        try {
            val local = OpponentUserPresetStore(directory.resolve("local.json"))
            local.save("Pikachu", preset("user.shared", "本机旧名称"))
            local.save("Eevee", preset("user.local-only", "只在本机"))

            val shared = OpponentUserPresetStore(directory.resolve("shared.json"))
            shared.save("Pikachu", preset("user.shared", "分享的新名称"))
            shared.save("Gengar", preset("user.new", "新导入"))

            val first = local.mergeFrom(shared.exportRoot())
            val second = local.mergeFrom(shared.exportRoot())

            assertEquals(OpponentPresetMergeResult(2, 1, 1, 0), first)
            assertEquals(OpponentPresetMergeResult(2, 0, 0, 2), second)
            assertEquals(
                listOf("分享的新名称", "只在本机", "新导入"),
                local.all().map { it.preset.profileName },
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptedStorageBlocksWritesUntilTheOriginalFileIsPreservedAndReset() {
        val directory = Files.createTempDirectory("opponent-user-preset-corruption").toFile()
        try {
            val file = directory.resolve(USER_OPPONENT_PRESETS_FILE)
            val corruptedBody = """{"schemaVersion":1,"kind":"OpponentUserPresets","presets":["""
            file.writeText(corruptedBody, Charsets.UTF_8)
            val store = OpponentUserPresetStore(file)

            assertNotNull(store.storageProblem())
            assertTrue(store.all().isEmpty())
            assertTrue(runCatching {
                store.save("Pikachu", preset("user.blocked", "不应覆盖"))
            }.isFailure)
            assertEquals(corruptedBody, file.readText(Charsets.UTF_8))

            val recovery = store.preserveCorruptedFileAndReset()

            assertTrue(recovery.isFile)
            assertEquals(corruptedBody, recovery.readText(Charsets.UTF_8))
            assertNull(store.storageProblem())
            store.save("Pikachu", preset("user.recovered", "重置后可保存"))
            assertEquals(listOf("重置后可保存"), store.all().map { it.preset.profileName })
            assertEquals(listOf("重置后可保存"), OpponentUserPresetStore(file).all().map { it.preset.profileName })
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun preset(id: String, name: String) = OpponentPreset(
        profileId = id,
        profileName = name,
        source = OpponentUserPresetStore.USER_PRESET_SOURCE,
        level = 50,
        statPoints = StatFields(hp = "32", spe = "32"),
        actualStats = StatFields("110", "75", "60", "105", "70", "142"),
        statAlignment = EntityValue("nature.timidity", "Timid", "胆小", "nature"),
        ability = EntityValue("ability.static", "Static", "静电", "ability"),
        item = EntityValue("item.choice-scarf", "Choice Scarf", "讲究围巾", "item"),
        moves = listOf(
            MoveValue(
                entity = EntityValue("move.thunderbolt", "Thunderbolt", "十万伏特", "move"),
                basePower = 90,
                type = "Electric",
            ),
        ),
    )

    private fun form(familyId: String, showdownId: String) = SpeciesFormOption(
        familyId = familyId,
        species = EntityValue(
            canonicalId = "species.${showdownId.lowercase().replace(Regex("[^a-z0-9]+"), "")}",
            showdownId = showdownId,
            displayName = showdownId,
            entityType = "species",
        ),
        baseStats = StatFields("100", "100", "100", "100", "100", "100"),
        defaultAbility = null,
        abilities = emptyList(),
        learnableMoves = emptyList(),
    )
}
