package com.crazylei12.pokemonchampionsassistant

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.time.Instant

class TeamCodeImportTest {
    @Test
    fun normalizesCopiedCodeAndRejectsInvalidInput() {
        assertEquals("A4RBRNN9YE", normalizeTeamCode("  a4rbr nn9ye\n"))
        assertEquals(null, normalizeTeamCode("A4RBRNN9Y"))
        assertEquals(null, normalizeTeamCode("A4RBRNN9Y!"))
    }

    @Test
    fun parsesACompleteSixMemberResponseWithoutChangingOrder() {
        val parsed = parseTeamCodeResponse(validResponse().toString(), "a4rbrnn9ye")

        assertEquals("A4RBRNN9YE", parsed.code)
        assertEquals("ワトソン", parsed.trainerName)
        assertEquals(6, parsed.members.size)
        assertEquals("Dragonite", parsed.members.first().speciesId)
        assertEquals("Modest", parsed.members.first().natureId)
        assertEquals("32", parsed.members.first().statPoints.spa)
        assertEquals(listOf("Dragon Pulse", "Heat Wave", "Extreme Speed", "Protect"), parsed.members.first().moveIds)
        assertEquals("Floette-Eternal", parsed.members.last().speciesId)
    }

    @Test
    fun rejectsMismatchedCodesMalformedTeamsAndOverBudgetPoints() {
        assertTrue(runCatching {
            parseTeamCodeResponse(validResponse().put("code", "BBBBBBBBBB").toString(), "A4RBRNN9YE")
        }.exceptionOrNull() is TeamCodeDataException)

        val fiveMembers = validResponse().apply { getJSONArray("members").remove(5) }
        assertTrue(runCatching {
            parseTeamCodeResponse(fiveMembers.toString(), "A4RBRNN9YE")
        }.exceptionOrNull() is TeamCodeDataException)

        val overBudget = validResponse().apply {
            getJSONArray("members").getJSONObject(0).getJSONObject("statPoints")
                .put("hp", 32).put("atk", 32).put("def", 32)
        }
        assertTrue(runCatching {
            parseTeamCodeResponse(overBudget.toString(), "A4RBRNN9YE")
        }.exceptionOrNull() is TeamCodeDataException)
    }

    @Test
    fun resolverEndpointRequiresHttpsExceptForKnownLocalDebugHosts() {
        assertEquals("https", validateTeamCodeResolverEndpoint("https://resolver.example/v1/resolve", false).scheme)
        assertEquals("10.0.2.2", validateTeamCodeResolverEndpoint("http://10.0.2.2:8765/v1/resolve", true).host)
        assertTrue(runCatching {
            validateTeamCodeResolverEndpoint("http://resolver.example/v1/resolve", true)
        }.exceptionOrNull() is TeamCodeResolverUnavailableException)
        assertTrue(runCatching {
            validateTeamCodeResolverEndpoint("http://10.0.2.2:8765/v1/resolve", false)
        }.exceptionOrNull() is TeamCodeResolverUnavailableException)
    }

    @Test
    fun resolverClientSeparatesMissingCodeFromServiceFailure() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/resolve") { exchange ->
            val code = runCatching {
                JSONObject(exchange.requestBody.bufferedReader().use { it.readText() }).getString("code")
            }.getOrDefault("")
            val (status, body) = when (code) {
                "A4RBRNN9YE" -> 200 to validResponse().toString()
                "CCCCCCCCCC" -> 503 to "{\"error\":{\"code\":\"UPSTREAM_DOWN\"}}"
                else -> 404 to "{\"error\":{\"code\":\"TEAM_CODE_NOT_FOUND\"}}"
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = TeamCodeResolverClient(
                endpointUrl = "http://127.0.0.1:${server.address.port}/resolve",
                allowLocalHttp = true,
            )

            assertEquals(6, client.resolve("A4RBRNN9YE").members.size)
            assertTrue(runCatching { client.resolve("BBBBBBBBBB") }.exceptionOrNull() is TeamCodeNotFoundException)
            assertTrue(runCatching { client.resolve("CCCCCCCCCC") }.exceptionOrNull() is TeamCodeResolverUnavailableException)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun importedOwnTeamJsonPersistsNatureAndPublicSourceOnly() {
        val pokemon = (0 until 6).map { index -> pokemon(index) }
        val root = createImportedTeamJson(
            savedTeamId = "team-code-test",
            teamName = "公开雨天队",
            publicCode = "A4RBRNN9YE",
            trainerName = "ワトソン",
            pokemon = pokemon,
            now = Instant.parse("2026-09-05T00:00:00Z"),
        )
        val parsed = TeamRepository.parseTeam(root, userSaved = true)

        assertEquals("TEAM_CODE", root.getString("importSource"))
        assertEquals("A4RBRNN9YE", root.getJSONObject("source").getString("publicCode"))
        assertFalse(root.toString().contains("token", ignoreCase = true))
        assertEquals(6, parsed.pokemon.size)
        assertEquals("Modest", parsed.pokemon.first().statAlignment?.showdownId)
        assertTrue(parsed.damageReady)
    }

    private fun validResponse() = JSONObject().apply {
        put("schemaVersion", 1)
        put("kind", "PokemonChampionsPublicTeam")
        put("code", "A4RBRNN9YE")
        put("trainerName", "ワトソン")
        put("members", JSONArray().apply {
            put(member("Dragonite", "Modest", "Multiscale", "Dragoninite", 2, 0, 0, 32, 0, 32,
                "Dragon Pulse", "Heat Wave", "Extreme Speed", "Protect"))
            put(member("Sneasler", "Jolly", "Poison Touch", "Focus Sash", 2, 32, 0, 0, 0, 32,
                "Close Combat", "Dire Claw", "Fake Out", "Feint"))
            put(member("Basculegion", "Adamant", "Adaptability", "Life Orb", 4, 18, 4, 0, 15, 25,
                "Wave Crash", "Last Respects", "Aqua Jet", "Protect"))
            put(member("Kingambit", "Adamant", "Defiant", "Chople Berry", 32, 15, 0, 0, 19, 0,
                "Sucker Punch", "Kowtow Cleave", "Low Kick", "Iron Head"))
            put(member("Garchomp", "Adamant", "Rough Skin", "Choice Scarf", 10, 20, 9, 0, 0, 27,
                "Dragon Claw", "Stomping Tantrum", "Earthquake", "Rock Slide"))
            put(member("Floette-Eternal", "Timid", "Flower Veil", "Floettite", 4, 0, 8, 32, 0, 22,
                "Moonblast", "Dazzling Gleam", "Light of Ruin", "Protect"))
        })
    }

    private fun member(
        species: String,
        nature: String,
        ability: String,
        item: String?,
        hp: Int,
        atk: Int,
        def: Int,
        spa: Int,
        spd: Int,
        spe: Int,
        vararg moves: String,
    ) = JSONObject().apply {
        put("speciesId", species)
        put("level", 50)
        put("gender", "unknown")
        put("natureId", nature)
        put("abilityId", ability)
        item?.let { put("itemId", it) }
        put("statPoints", JSONObject().put("hp", hp).put("atk", atk).put("def", def)
            .put("spa", spa).put("spd", spd).put("spe", spe))
        put("moveIds", JSONArray().apply { moves.forEach(::put) })
    }

    private fun pokemon(index: Int): PokemonConfig {
        val species = EntityValue("species.test$index", "Test-$index", "测试$index", "species")
        return PokemonConfig(
            species = species,
            level = 50,
            actualStats = StatFields("150", "120", "110", "130", "115", "140"),
            statPoints = StatFields("2", "0", "0", "32", "0", "32"),
            ability = EntityValue("ability.multiscale", "Multiscale", "多重鳞片", "ability"),
            item = EntityValue("item.dragoninite", "Dragoninite", "快龙进化石", "item"),
            moves = listOf(MoveValue(EntityValue("move.protect", "Protect", "守住", "move"))),
            statAlignment = EntityValue("nature.modest", "Modest", "内敛", "nature"),
        )
    }
}
