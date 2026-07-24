package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
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
}
