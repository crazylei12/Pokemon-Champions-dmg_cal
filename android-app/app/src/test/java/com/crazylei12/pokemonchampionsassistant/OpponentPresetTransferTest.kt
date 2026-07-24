package com.crazylei12.pokemonchampionsassistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpponentPresetTransferTest {
    @Test
    fun shareEnvelopeRoundTripsTheValidatedPresetPayload() {
        val presets = OpponentUserPresetStore.emptyRoot()
        val envelope = OpponentPresetTransfer.buildEnvelope(
            presets = presets,
            exportedAt = "2026-07-24T00:00:00Z",
            appVersion = "1.1.3",
        )

        val extracted = OpponentPresetTransfer.extractPresets(JSONObject(envelope.toString()))

        assertEquals("PokemonChampionsOpponentPresetShare", envelope.getString("kind"))
        assertEquals(0, extracted.getJSONArray("presets").length())
    }

    @Test
    fun dedicatedImportRejectsAFullApplicationBackup() {
        val fullBackup = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "PokemonChampionsAssistantBackup")
            .put("data", JSONObject())

        val result = runCatching { OpponentPresetTransfer.extractPresets(fullBackup) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("不是宝可梦配置分享文件") == true)
    }
}
