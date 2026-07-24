package com.crazylei12.pokemonchampionsassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDataBackupTest {
    @Test
    fun legacyBackupWithoutUserPresetsKeepsTheFieldAbsent() {
        assertNull(AppDataBackup.extractUserOpponentPresets(JSONObject()))
    }

    @Test
    fun validUserPresetRootIsAccepted() {
        val presets = OpponentUserPresetStore.emptyRoot()
        val extracted = AppDataBackup.extractUserOpponentPresets(
            JSONObject().put("userOpponentPresets", presets),
        )

        assertEquals("OpponentUserPresets", extracted?.getString("kind"))
    }

    @Test
    fun presentButMalformedUserPresetFieldIsRejected() {
        listOf(
            JSONObject.NULL,
            JSONArray(),
            "not-an-object",
            42,
        ).forEach { malformed ->
            val result = runCatching {
                AppDataBackup.extractUserOpponentPresets(
                    JSONObject().put("userOpponentPresets", malformed),
                )
            }

            assertTrue("Expected malformed value to be rejected: $malformed", result.isFailure)
        }
    }

    @Test
    fun presentButInvalidUserPresetObjectIsRejected() {
        val result = runCatching {
            AppDataBackup.extractUserOpponentPresets(
                JSONObject().put("userOpponentPresets", JSONObject()),
            )
        }

        assertTrue(result.isFailure)
    }
}
