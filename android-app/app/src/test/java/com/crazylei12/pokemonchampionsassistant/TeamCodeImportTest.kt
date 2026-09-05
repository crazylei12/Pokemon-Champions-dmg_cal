package com.crazylei12.pokemonchampionsassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
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
    fun cryptoMatchesNodeReferenceAndRoundTripsBothProtocolLayers() {
        val nodePmc = "mom4vuwrVZPPfb2H5Jl7h2oTkK1QkKVyk1Eqw6KGkiw="
        assertEquals(
            "1234cc65ecbbc6daaab55873dc928b27cd3a",
            TeamCodeProtocolCrypto.createApiRequestHash(
                pmc = nodePmc,
                dummy = "1234567890123456789",
                token = "csrf",
                sessionId = "session",
                randomValue = 0x1234,
            ),
        )
        assertEquals(
            "{\"ok\":true}",
            TeamCodeProtocolCrypto.decryptApiPayload(
                pmc = nodePmc,
                dummy = "1234567890123456789",
                token = "csrf",
                sessionId = "session",
                requestUserDataVersion = 77,
            ).text,
        )

        val androidPmc = TeamCodeProtocolCrypto.encryptApiPayload(
            text = "{\"source\":\"android\"}",
            dummy = "6000000000000000001",
            token = "token",
            sessionId = "sid",
            userDataVersion = 9,
        )
        assertEquals(
            "{\"source\":\"android\"}",
            TeamCodeProtocolCrypto.decryptApiPayload(
                pmc = androidPmc,
                dummy = "6000000000000000001",
                token = "token",
                sessionId = "sid",
                requestUserDataVersion = 9,
            ).text,
        )

        val authPmc = TeamCodeProtocolCrypto.encryptAuthPayload(
            text = "{\"token\":\"fresh\"}",
            dummy = "6000000000000000002",
            bucket = 2_981_000,
        )
        assertEquals(
            "{\"token\":\"fresh\"}",
            TeamCodeProtocolCrypto.decryptAuthPayload(
                pmc = authPmc,
                dummy = "6000000000000000002",
                candidateBuckets = listOf(2_980_999, 2_981_000, 2_981_001),
            ),
        )
    }

    @Test
    fun directOfficialClientGetsFreshSessionAndMapsArbitraryCodeWithoutAResolver() {
        val transport = FakeOfficialTransport()
        val client = PokemonChampionsOfficialTeamCodeClient(
            identityUuid = TEST_GUEST_UUID,
            entityMap = loadEntityMap(),
            transport = transport,
            deviceName = "TEST DEVICE",
            osName = "Android OS test",
            nowSeconds = { FIXED_SERVER_TIME },
            randomInt = { origin, _ -> origin },
        )

        val result = client.resolve("61v6 v4s9rx")

        assertEquals("61V6V4S9RX", result.code)
        assertEquals("ytess", result.trainerName)
        assertEquals(
            listOf("Charizard", "Azumarill", "Steelix", "Whimsicott", "Gengar", "Drampa"),
            result.members.map { it.speciesId },
        )
        assertEquals(listOf("/auth/get-token", "/auth/login", "/api/trainingcode/search"),
            transport.requests.map { it.path })
        val tokenPmp = JSONObject(transport.requests.first().body).getJSONObject("pmp")
        assertEquals(TEST_GUEST_UUID, tokenPmp.getString("uuid"))
        assertTrue(transport.requests.drop(1).all { it.headers["Cookie"] == "pc_session=test-cookie" })

        val missingClient = PokemonChampionsOfficialTeamCodeClient(
            identityUuid = TEST_GUEST_UUID,
            entityMap = loadEntityMap(),
            transport = FakeOfficialTransport(returnMissingTeam = true),
            deviceName = "TEST DEVICE",
            osName = "Android OS test",
            nowSeconds = { FIXED_SERVER_TIME },
            randomInt = { origin, _ -> origin },
        )
        assertTrue(runCatching { missingClient.resolve("BBBBBBBBBB") }.exceptionOrNull() is TeamCodeNotFoundException)
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
        assertEquals("pokemon_champions_official_api", root.getJSONObject("source").getString("backend"))
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

    private fun loadEntityMap(): TeamCodeEntityMap {
        val relative = Path.of("tools", "team-code-resolver", "data", "champions-entity-map.v17.json")
        val workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        val source = generateSequence(workingDirectory) { it.parent }
            .map { it.resolve(relative) }
            .firstOrNull(Files::isRegularFile)
            ?: error("Unable to locate $relative from $workingDirectory")
        return TeamCodeEntityMap.fromJson(Files.readAllBytes(source).toString(Charsets.UTF_8))
    }

    private class FakeOfficialTransport(
        private val returnMissingTeam: Boolean = false,
    ) : OfficialTeamCodeTransport {
        val requests = mutableListOf<OfficialHttpRequest>()

        override fun post(request: OfficialHttpRequest): OfficialHttpResponse {
            requests += request
            val responseDummy = "6000000000000000042"
            val responsePayload = when (request.path) {
                "/auth/get-token" -> JSONObject().put("token", "csrf")
                "/auth/login" -> JSONObject()
                    .put("sid", "session")
                    .put("UD", JSONObject().put("udVer", 77))
                "/api/trainingcode/search" -> if (returnMissingTeam) JSONObject() else secondOfficialPayload()
                else -> error("Unexpected path ${request.path}")
            }
            val encrypted = if (request.path.startsWith("/auth/")) {
                TeamCodeProtocolCrypto.encryptAuthPayload(
                    text = responsePayload.toString(),
                    dummy = responseDummy,
                    bucket = FIXED_SERVER_TIME / 600,
                )
            } else {
                TeamCodeProtocolCrypto.encryptApiPayload(
                    text = responsePayload.toString(),
                    dummy = responseDummy,
                    token = "csrf",
                    sessionId = "session",
                    userDataVersion = 77,
                )
            }
            val outer = JSONObject().put("code", 0).put("pmc", encrypted)
            if (request.path == "/auth/get-token") {
                outer.put("tmV", 17).put("ppV", 4).put("wvV", 3).put("inH", "test")
            }
            return OfficialHttpResponse(
                status = 200,
                headers = buildMap {
                    put("X-PKB-DMY-VAL", listOf(responseDummy))
                    put("X-PKB-S-TIME", listOf(FIXED_SERVER_TIME.toString()))
                    if (request.path == "/auth/get-token") {
                        put("Set-Cookie", listOf("pc_session=test-cookie; Path=/; Secure"))
                    }
                },
                body = outer.toString(),
            )
        }
    }

    companion object {
        private const val TEST_GUEST_UUID = "6pRYms5COTckyNxTau6aKz4xDxBtHqEX1788532952"
        private const val FIXED_SERVER_TIME = 1_788_566_400L

        private fun secondOfficialPayload() = JSONObject().put("tng", JSONObject()
            .put("unam", "ytess")
            .put("mem", JSONArray()
                .put(memberRow(6, 0, 0, 66, 3, 2, 32, 0, 32, 0, 0, 394, 200, 488, 14))
                .put(memberRow(184, 0, 0, 37, 3, 32, 32, 0, 2, 0, 0, 583, 453, 276, 187))
                .put(memberRow(208, 0, 0, 5, 3, 32, 32, 0, 2, 0, 0, 484, 89, 776, 446))
                .put(memberRow(547, 0, 1, 158, 10, 2, 0, 0, 32, 32, 0, 585, 202, 73, 262))
                .put(memberRow(94, 0, 1, 130, 10, 2, 0, 0, 32, 32, 0, 247, 482, 196, 194))
                .put(memberRow(780, 0, 1, 201, 15, 32, 0, 0, 2, 32, 0, 434, 304, 53, 85)))
            .put("itms", JSONArray()
                .put(JSONObject().put("idx", 0).put("i", 760))
                .put(JSONObject().put("idx", 1).put("i", 158))
                .put(JSONObject().put("idx", 2).put("i", 234))
                .put(JSONObject().put("idx", 3).put("i", 214))
                .put(JSONObject().put("idx", 4).put("i", 275))
                .put(JSONObject().put("idx", 5).put("i", 217))))

        private fun memberRow(
            species: Int,
            form: Int,
            gender: Int,
            ability: Int,
            nature: Int,
            hp: Int,
            atk: Int,
            def: Int,
            spe: Int,
            spa: Int,
            spd: Int,
            vararg moves: Int,
        ) = JSONObject()
            .put("b0", species)
            .put("b1", form)
            .put("b2", gender)
            .put("b5", ability)
            .put("b8", nature)
            .put("b9", hp)
            .put("ba", atk)
            .put("bb", def)
            .put("bc", spe)
            .put("bd", spa)
            .put("be", spd)
            .put("bf", JSONArray().apply { moves.forEach(::put) })
    }
}
