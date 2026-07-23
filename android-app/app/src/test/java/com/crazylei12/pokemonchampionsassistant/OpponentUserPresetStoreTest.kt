package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
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
