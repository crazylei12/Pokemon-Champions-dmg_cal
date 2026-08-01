package com.crazylei12.pokemonchampionsassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CrossPlatformGoldenExportTest {
    @Test
    fun exportsCanonicalAndroidResultFromProductionParsersAndBuilders() {
        val descriptorFile = System.getenv("CROSS_PLATFORM_GOLDEN_FIXTURE")
            ?.let(::File)
            ?: locateRepositoryFile("test/fixtures/harmonyos-port/cross-platform-golden.json")
        val descriptor = JSONObject(descriptorFile.readText(Charsets.UTF_8))
        val backup = readRelativeJson(descriptorFile, descriptor.getString("backupFixture"))
        val share = readRelativeJson(descriptorFile, descriptor.getString("presetShareFixture"))
        val damageInput = descriptor.getJSONObject("damageInput")

        val sharedPreset = share.getJSONObject("userOpponentPresets")
            .getJSONArray("presets")
            .getJSONObject(0)
            .getJSONObject("preset")
        assertEquals(damageInput.getString("presetProfileId"), sharedPreset.getString("profileId"))
        sharedPreset.put("actualStats", JSONObject(damageInput.getJSONObject("presetActualStats").toString()))

        val temporaryDirectory = Files.createTempDirectory("cross-platform-golden-android-").toFile()
        try {
            val presetFile = temporaryDirectory.resolve("user-opponent-presets.json")
            val extractedPresets = OpponentPresetTransfer.extractPresets(share)
            presetFile.writeText(extractedPresets.toString(2), Charsets.UTF_8)
            val presetStore = OpponentUserPresetStore(presetFile)
            val normalizedPresets = presetStore.exportRoot()
            val normalizedShare = OpponentPresetTransfer.buildEnvelope(
                presets = normalizedPresets,
                exportedAt = share.getString("exportedAt"),
                appVersion = share.getString("appVersion"),
            )

            val backupData = backup.getJSONObject("data")
            backupData.put("userOpponentPresets", JSONObject(normalizedPresets.toString()))
            val sessionJson = backupData.getJSONObject("currentBattleSession")
            sessionJson.put(
                "calculationSelection",
                JSONObject(damageInput.getJSONObject("calculationSelection").toString()),
            )
            assertEquals(damageInput.getString("ownTeamId"), sessionJson.getString("selectedOwnTeamId"))

            val canonicalBackup = validateAndRoundTripBackup(backup)
            val teamJson = canonicalBackup.getJSONObject("data")
                .getJSONArray("savedTeams")
                .getJSONObject(0)
            val ownTeam = TeamRepository.parseTeam(teamJson, userSaved = true)
            assertEquals(6, ownTeam.pokemon.size)

            val calculation = BattleCalculationState.fromJson(sessionJson.getJSONObject("calculationSelection"))
            val ownSlot = damageInput.getInt("ownSlot")
            val opponentSlot = damageInput.getInt("opponentSlot")
            assertEquals(ownSlot, calculation.ownSlot)
            assertEquals(opponentSlot, calculation.opponentSlot)
            val opponentJson = sessionJson.getJSONArray("opponentTeam").getJSONObject(opponentSlot)
            val session = BattleSession(
                sessionId = sessionJson.getString("sessionId"),
                createdAt = sessionJson.getString("createdAt"),
                previewCapturedAt = sessionJson.optString("previewCapturedAt"),
                selectedOwnTeamId = sessionJson.getString("selectedOwnTeamId"),
                opponentTeam = (0 until sessionJson.getJSONArray("opponentTeam").length()).map { index ->
                    entityFromJson(sessionJson.getJSONArray("opponentTeam").getJSONObject(index))
                },
                calculation = calculation,
            )
            val storedPreset = presetStore.all().single {
                it.preset.profileId == damageInput.getString("presetProfileId")
            }.preset
            val damageRequest = JSONObject(
                buildBattleDamageRequest(
                    session = session,
                    ownTeam = ownTeam,
                    preset = storedPreset,
                    legalMoves = storedPreset.moves,
                    presetRepository = allocateUninitializedPresetRepository(),
                    allOwnMoves = damageInput.optBoolean("allOwnMoves"),
                ),
            ).apply {
                // Request IDs contain the current clock on Android and are deliberately excluded
                // from the semantic golden comparison.
                remove("requestId")
            }
            assertEquals(opponentJson.getString("canonicalId"),
                damageRequest.getJSONObject("defenderIdentity").getJSONObject("species").getString("canonicalId"))

            val result = JSONObject().apply {
                put("backup", canonicalBackup)
                put("presetShare", normalizedShare)
                put("damageRequest", damageRequest)
            }
            val outputFile = System.getenv("CROSS_PLATFORM_GOLDEN_OUTPUT")
                ?.let(::File)
                ?: locateRepositoryFile("android-app/app").resolve("build/cross-platform-golden/android.json")
            outputFile.parentFile.mkdirs()
            outputFile.writeText(result.toString(2), Charsets.UTF_8)
            assertTrue(outputFile.isFile && outputFile.length() > 0)
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    private fun validateAndRoundTripBackup(source: JSONObject): JSONObject {
        val method = AppDataBackup::class.java.getDeclaredMethod("validateEnvelope", JSONObject::class.java)
        method.isAccessible = true
        val validated = method.invoke(AppDataBackup, source)
        val data = JSONObject().apply {
            put("savedTeams", JSONArray().apply {
                privateField<List<JSONObject>>(validated, "savedTeams").forEach { put(JSONObject(it.toString())) }
            })
            privateField<JSONObject?>(validated, "currentBattleSession")
                ?.let { put("currentBattleSession", JSONObject(it.toString())) }
            privateField<JSONObject?>(validated, "currentTeamPreview")
                ?.let { put("currentTeamPreview", JSONObject(it.toString())) }
            privateField<JSONObject?>(validated, "pendingOwnTeam")
                ?.let { put("pendingOwnTeam", JSONObject(it.toString())) }
            privateField<JSONObject?>(validated, "ownTeamImportDraft")
                ?.let { put("ownTeamImportDraft", JSONObject(it.toString())) }
            privateField<JSONObject?>(validated, "userOpponentPresets")
                ?.let { put("userOpponentPresets", JSONObject(it.toString())) }
            put("updateChannel", privateField<UpdateChannel>(validated, "updateChannel").storedValue)
        }
        return JSONObject().apply {
            put("schemaVersion", source.getInt("schemaVersion"))
            put("kind", source.getString("kind"))
            put("exportedAt", source.getString("exportedAt"))
            put("appVersion", source.getString("appVersion"))
            put("data", data)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }

    private fun allocateUninitializedPresetRepository(): OpponentPresetRepository {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, OpponentPresetRepository::class.java) as OpponentPresetRepository
    }

    private fun entityFromJson(json: JSONObject) = EntityValue(
        canonicalId = json.getString("canonicalId"),
        showdownId = json.getString("showdownId"),
        displayName = json.optString("displayName", json.getString("showdownId")),
        entityType = json.optString("entityType", "species"),
    )

    private fun readRelativeJson(descriptor: File, relativePath: String): JSONObject =
        JSONObject(descriptor.parentFile.resolve(relativePath).readText(Charsets.UTF_8))

    private fun locateRepositoryFile(relativePath: String): File {
        var current: File? = File(System.getProperty("user.dir")).canonicalFile
        while (current != null) {
            val candidate = current.resolve(relativePath)
            if (candidate.exists()) return candidate
            current = current.parentFile
        }
        error("Cannot locate repository file: $relativePath")
    }
}
