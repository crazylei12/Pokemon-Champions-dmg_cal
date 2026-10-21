package com.crazylei12.pokemonchampionsassistant

import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in network check; never launches an Activity or the game. */
class TeamCodeLiveQueryTest {
    @Test
    fun queriesPublicCodesWithFreshAppAuthentication() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveTeamCodeCheck") == "true")
        val codes = args.getString("teamCodes")?.split(',').orEmpty()
        require(codes.isNotEmpty() && codes.all { normalizeTeamCode(it) != null })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (code in codes) {
            val steps = JSONArray()
            val report = JSONObject().put("code", code).put("steps", steps)
            val delegate = PokemonChampionsOfficialTransport()
            val transport = OfficialTeamCodeTransport { request ->
                delegate.post(request).also { response ->
                    steps.put(JSONObject().put("path", request.path).put("http", response.status)
                        .put("apiCode", JSONObject(response.body).optInt("code", -1)))
                }
            }
            try {
                val client = PokemonChampionsOfficialTeamCodeClient(
                    identityUuid = BuildConfig.TEAM_CODE_GUEST_UUID,
                    entityMap = TeamCodeEntityMap.fromContext(context),
                    integrityProvider = PlayTeamCodeIntegrityProvider(context),
                    transport = transport,
                    deviceName = Build.MANUFACTURER.uppercase() + " " + Build.MODEL,
                    osName = "Android OS ${Build.VERSION.RELEASE} / API-${Build.VERSION.SDK_INT}",
                )
                val team = client.resolve(code)
                assertEquals(normalizeTeamCode(code), team.code)
                assertEquals(6, team.members.size)
                assertTrue(team.members.all { it.moveIds.size in 1..4 })
                report.put("success", true).put("trainerName", team.trainerName)
                    .put("members", JSONArray(team.members.map { member ->
                        JSONObject().put("speciesId", member.speciesId).put("natureId", member.natureId)
                            .put("abilityId", member.abilityId).put("itemId", member.itemId)
                            .put("moves", JSONArray(member.moveIds))
                    }))
            } catch (error: Exception) {
                report.put("success", false).put("errorType", error.javaClass.simpleName)
                    .put("message", error.message)
                throw error
            } finally {
                // Public data and status only: no proof, token, Cookie, session, or raw envelope.
                instrumentation.sendStatus(0, Bundle().apply { putString("teamCodeResult", report.toString()) })
            }
        }
    }
}
