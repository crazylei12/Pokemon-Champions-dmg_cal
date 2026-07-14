package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class EntityValue(
    val canonicalId: String,
    val showdownId: String,
    val displayName: String,
    val entityType: String,
)

data class MoveValue(
    val entity: EntityValue,
    val basePower: Int? = null,
    val type: String? = null,
)

data class StatFields(
    val hp: String = "",
    val atk: String = "",
    val def: String = "",
    val spa: String = "",
    val spd: String = "",
    val spe: String = "",
) {
    fun asMap(): Map<String, String> = linkedMapOf(
        "hp" to hp,
        "atk" to atk,
        "def" to def,
        "spa" to spa,
        "spd" to spd,
        "spe" to spe,
    )

    fun toJson(includeZero: Boolean = false): JSONObject {
        val result = JSONObject()
        listOf(
            "hp" to hp,
            "atk" to atk,
            "def" to def,
            "spa" to spa,
            "spd" to spd,
            "spe" to spe,
        ).forEach { (key, raw) ->
            raw.toIntOrNull()?.let { value ->
                if (includeZero || value > 0) result.put(key, value)
            }
        }
        return result
    }

    companion object {
        fun fromMap(values: Map<String, String>) = StatFields(
            hp = values["hp"].orEmpty(),
            atk = values["atk"].orEmpty(),
            def = values["def"].orEmpty(),
            spa = values["spa"].orEmpty(),
            spd = values["spd"].orEmpty(),
            spe = values["spe"].orEmpty(),
        )
    }
}

data class PokemonConfig(
    val species: EntityValue,
    val level: Int,
    val actualStats: StatFields,
    val statPoints: StatFields,
    val ability: EntityValue?,
    val item: EntityValue?,
    val moves: List<MoveValue>,
) {
    fun toSavedJson(): JSONObject = JSONObject().apply {
        put("species", species.toJson())
        put("level", level.coerceIn(1, 100))
        put("actualStats", actualStats.toJson())
        put("statPoints", statPoints.toJson(includeZero = true))
        ability?.let { put("ability", it.toJson()) }
        item?.let { put("item", it.toJson()) }
        put("moves", JSONArray().apply {
            moves.take(4).forEach { move ->
                put(JSONObject().put("move", move.entity.toJson()).put("source", "MANUAL_CURRENT"))
            }
        })
    }

    fun hasCompleteActualStats(): Boolean = actualStats.asMap().values.all { (it.toIntOrNull() ?: 0) > 0 }
}

data class SavedTeam(
    val id: String,
    val name: String,
    val status: String,
    val damageReady: Boolean,
    val speciesSummary: String,
    val pokemon: List<PokemonConfig>,
    val userSaved: Boolean,
)

data class PokemonEditorState(
    val speciesId: String,
    val speciesDisplay: String,
    val level: String,
    val abilityId: String,
    val itemId: String,
    val movesText: String,
    val actualStats: StatFields,
    val statPoints: StatFields,
    val knownSpecies: EntityValue,
    val knownAbility: EntityValue?,
    val knownItem: EntityValue?,
    val knownMoves: List<MoveValue>,
) {
    companion object {
        fun from(config: PokemonConfig) = PokemonEditorState(
            speciesId = config.species.showdownId,
            speciesDisplay = config.species.displayName,
            level = config.level.toString(),
            abilityId = config.ability?.showdownId.orEmpty(),
            itemId = config.item?.showdownId.orEmpty(),
            movesText = config.moves.joinToString(", ") { it.entity.showdownId },
            actualStats = config.actualStats,
            statPoints = config.statPoints,
            knownSpecies = config.species,
            knownAbility = config.ability,
            knownItem = config.item,
            knownMoves = config.moves,
        )
    }

    fun toBuildJson(moveSource: String): JSONObject {
        val speciesEntity = entityForInput("species", speciesId, knownSpecies)
        return JSONObject().apply {
            put("species", speciesEntity)
            put("level", level.toIntOrNull()?.coerceIn(1, 100) ?: 50)
            put("actualStats", actualStats.toJson())
            put("statPoints", statPoints.toJson(includeZero = true))
            if (abilityId.isNotBlank()) {
                put("ability", entityForInput("ability", abilityId, knownAbility))
            }
            if (itemId.isNotBlank()) {
                put("item", entityForInput("item", itemId, knownItem))
            }
            put("moves", JSONArray().apply {
                parsedMoveIds().forEach { moveId ->
                    val known = knownMoves.firstOrNull {
                        it.entity.showdownId.equals(moveId, ignoreCase = true)
                    }?.entity
                    put(JSONObject().apply {
                        put("move", entityForInput("move", moveId, known))
                        put("source", moveSource)
                    })
                }
            })
        }
    }

    fun parsedMoveIds(): List<String> = movesText
        .split(',', '\n', '，')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
}

object TeamRepository {
    fun load(context: Context): List<SavedTeam> {
        val privateDirectory = context.filesDir.resolve("saved-teams")
        val saved = privateDirectory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file ->
                runCatching {
                    parseTeam(JSONObject(file.readText(Charsets.UTF_8)), userSaved = true)
                }.onFailure { error ->
                    Log.w("TeamRepository", "Ignoring unreadable saved team ${file.name}", error)
                }.getOrNull()
            }
        return saved.distinctBy(SavedTeam::id)
    }

    fun rename(context: Context, savedTeamId: String, newName: String) {
        val name = newName.trim()
        require(name.isNotEmpty()) { "队伍名称不能为空" }
        require(name.length <= 30) { "队伍名称不能超过 30 个字符" }
        val file = findTeamFile(context, savedTeamId) ?: error("找不到要重命名的队伍")
        val json = JSONObject(file.readText(Charsets.UTF_8)).apply {
            put("teamName", name)
            put("teamSlotName", name)
            put("updatedAt", Instant.now().toString())
        }
        file.writeUtf8Atomically(json.toString(2))
    }

    fun updatePokemon(context: Context, savedTeamId: String, slot: Int, config: PokemonConfig) {
        val file = findTeamFile(context, savedTeamId) ?: error("找不到要调整的队伍")
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val members = json.optJSONArray("pokemon") ?: json.optJSONArray("members")
            ?: error("队伍文件缺少宝可梦列表")
        require(slot in 0 until members.length()) { "队伍槽位无效" }
        val existing = members.getJSONObject(slot)
        if (existing.has("build")) existing.put("build", config.toSavedJson())
        else members.put(slot, config.toSavedJson())

        val parsedMembers = (0 until members.length()).map { index ->
            val member = members.getJSONObject(index)
            parsePokemon(member.optJSONObject("build") ?: member)
        }
        json.apply {
            put("speciesSummary", parsedMembers.joinToString(" / ") { it.species.displayName })
            put("damageReady", parsedMembers.all(PokemonConfig::hasCompleteActualStats))
            put(
                "importStatus",
                if (parsedMembers.all(PokemonConfig::hasCompleteActualStats)) "COMPLETE_ACTUAL_STATS" else "INCOMPLETE",
            )
            put("userConfirmed", true)
            put("updatedAt", Instant.now().toString())
        }
        file.writeUtf8Atomically(json.toString(2))
    }

    fun delete(context: Context, savedTeamId: String) {
        val file = findTeamFile(context, savedTeamId) ?: error("找不到要删除的队伍")
        check(file.delete()) { "删除队伍失败" }
        val sessionFile = context.filesDir.resolve("battle-session").resolve("current-battle-session.json")
        if (sessionFile.isFile) {
            val selectedTeamId = runCatching {
                JSONObject(sessionFile.readText(Charsets.UTF_8)).optString("selectedOwnTeamId")
            }.getOrNull()
            if (selectedTeamId == savedTeamId) sessionFile.delete()
        }
    }

    private fun findTeamFile(context: Context, savedTeamId: String) =
        context.filesDir.resolve("saved-teams")
            .listFiles { candidate -> candidate.isFile && candidate.extension == "json" }
            .orEmpty()
            .firstOrNull { candidate ->
                runCatching {
                    JSONObject(candidate.readText(Charsets.UTF_8)).optString("savedTeamId") == savedTeamId
                }.getOrDefault(false)
            }

    internal fun parseTeam(json: JSONObject, userSaved: Boolean): SavedTeam {
        val members = json.optJSONArray("pokemon") ?: json.getJSONArray("members")
        val pokemon = (0 until members.length()).map { index ->
            val member = members.getJSONObject(index)
            parsePokemon(member.optJSONObject("build") ?: member)
        }
        val name = json.optString("teamSlotName").takeIf(String::isNotBlank)
            ?: json.optString("teamName").takeIf(String::isNotBlank)
            ?: json.getString("savedTeamId")
        val summary = json.optString("speciesSummary").takeIf(String::isNotBlank)
            ?: pokemon.joinToString(" / ") { it.species.displayName }
        return SavedTeam(
            id = json.getString("savedTeamId"),
            name = name,
            status = json.optString("importStatus").takeIf(String::isNotBlank)
                ?: json.optString("status", "UNKNOWN"),
            damageReady = if (json.has("damageReady")) {
                json.optBoolean("damageReady", false)
            } else {
                json.optBoolean("userConfirmed", false)
            },
            speciesSummary = summary,
            pokemon = pokemon,
            userSaved = userSaved,
        )
    }

    private fun parsePokemon(json: JSONObject): PokemonConfig {
        val moves = json.optJSONArray("moves") ?: JSONArray()
        return PokemonConfig(
            species = parseEntity(json.getJSONObject("species")),
            level = json.optInt("level", 50),
            actualStats = parseStats(json.optJSONObject("actualStats")),
            statPoints = parseStats(json.optJSONObject("statPoints")),
            ability = json.optJSONObject("ability")?.let(::parseEntity),
            item = json.optJSONObject("item")?.let(::parseEntity),
            moves = (0 until moves.length()).mapNotNull { index ->
                moves.optJSONObject(index)?.optJSONObject("move")?.let { MoveValue(parseEntity(it)) }
            },
        )
    }

    private fun parseEntity(json: JSONObject) = EntityValue(
        canonicalId = json.getString("canonicalId"),
        showdownId = json.getString("showdownId"),
        displayName = json.optString("displayName", json.getString("showdownId")),
        entityType = json.optString("entityType", "species"),
    )

    private fun parseStats(json: JSONObject?): StatFields {
        fun value(key: String) = if (json?.has(key) == true) json.optInt(key).toString() else ""
        return StatFields(
            hp = value("hp"),
            atk = value("atk"),
            def = value("def"),
            spa = value("spa"),
            spd = value("spd"),
            spe = value("spe"),
        )
    }
}

fun buildDamageRequest(
    direction: String,
    own: PokemonEditorState,
    opponent: PokemonEditorState,
    selectedMove: String?,
    battleType: String,
    weather: String,
    terrain: String,
    ownReflect: Boolean,
    ownLightScreen: Boolean,
    opponentReflect: Boolean,
    opponentLightScreen: Boolean,
    critical: Boolean,
    spread: Boolean,
): String {
    val ownBuild = own.toBuildJson("OWN_BUILD")
    val opponentBuild = opponent.toBuildJson("PROFILE_PRESET")
    val root = JSONObject().apply {
        put("requestId", "android-manual-${System.currentTimeMillis()}")
        put("calculationDirection", direction)
        put("calculationMode", "EXACT")
        put("battle", JSONObject().apply {
            put("battleType", battleType)
            put("weather", weather)
            put("terrain", terrain)
            put("isCritical", critical)
            put("isSpreadMove", spread)
            put("attackerSideConditions", JSONObject())
            put("defenderSideConditions", JSONObject())
        })
    }

    if (direction == "OWN_TO_OPPONENT") {
        root.put("attackerSide", "OWN")
        root.put("defenderSide", "OPPONENT")
        root.put("attacker", ownBuild)
        root.put("defenderIdentity", JSONObject().put("species", opponentBuild.getJSONObject("species")))
        root.put("defenderProfileSet", exactDefenderProfile(opponentBuild))
        root.put("moveSelection", if (selectedMove.isNullOrBlank()) {
            JSONObject().put("mode", "ALL_ATTACKER_MOVES")
        } else {
            JSONObject().put("mode", "ONE_MOVE").put("moveId", selectedMove)
        })
        root.getJSONObject("battle").apply {
            put("attackerSideConditions", JSONObject())
            put("defenderSideConditions", JSONObject().apply {
                put("reflect", opponentReflect)
                put("lightScreen", opponentLightScreen)
            })
        }
    } else {
        val opponentSpecies = opponentBuild.getJSONObject("species")
        root.put("attackerSide", "OPPONENT")
        root.put("defenderSide", "OWN")
        root.put("attackerIdentity", JSONObject().put("species", opponentSpecies))
        root.put("attackerProfileSet", JSONObject().apply {
            put("attackerSpecies", opponentSpecies)
            put("selectedProfileId", "manual-exact-attacker")
            put("profiles", JSONArray().put(JSONObject().apply {
                put("profileId", "manual-exact-attacker")
                put("profileName", "手动精确配置")
                put("source", "MANUAL_CURRENT")
                put("isSelected", true)
                copyBuildFields(opponentBuild, this, includeMoves = true)
            }))
        })
        root.put("defender", ownBuild)
        root.put("attackerLegalMovePool", JSONObject().apply {
            put("species", opponentSpecies)
            put("rulesetVersion", "provided-team-v1")
            put("source", "USER_PATCH")
            put("learnableMoves", JSONArray().apply {
                val moves = opponentBuild.getJSONArray("moves")
                for (index in 0 until moves.length()) {
                    put(moves.getJSONObject(index).getJSONObject("move"))
                }
            })
        })
        root.put("moveSelection", JSONObject().apply {
            put("mode", "ONE_MOVE")
            if (!selectedMove.isNullOrBlank()) put("moveId", selectedMove)
            put("source", "OPPONENT_LEGAL_MOVE_POOL")
            put("legalMovePoolVersion", "provided-team-v1")
        })
        root.getJSONObject("battle").apply {
            put("attackerSideConditions", JSONObject())
            put("defenderSideConditions", JSONObject().apply {
                put("reflect", ownReflect)
                put("lightScreen", ownLightScreen)
            })
        }
    }
    return root.toString()
}

private fun exactDefenderProfile(build: JSONObject) = JSONObject().apply {
    put("defenderSpecies", build.getJSONObject("species"))
    put("selectedProfileId", "manual-exact-defender")
    put("profiles", JSONArray().put(JSONObject().apply {
        put("profileId", "manual-exact-defender")
        put("profileName", "手动精确配置")
        put("source", "MANUAL_CURRENT")
        put("isSelected", true)
        put("includedInEnvelope", true)
        copyBuildFields(build, this, includeMoves = false)
    }))
}

private fun copyBuildFields(source: JSONObject, target: JSONObject, includeMoves: Boolean) {
    listOf("level", "actualStats", "statPoints", "ability", "item").forEach { key ->
        if (source.has(key)) target.put(key, source.get(key))
    }
    if (includeMoves && source.has("moves")) target.put("moves", source.getJSONArray("moves"))
}

private fun entityForInput(type: String, input: String, known: EntityValue?): JSONObject {
    val exactKnown = known?.takeIf { it.showdownId.equals(input, ignoreCase = true) }
    val showdownId = input.trim()
    val normalized = showdownId.lowercase().replace(Regex("[^a-z0-9]+"), "")
    return JSONObject().apply {
        put("entityType", type)
        put("canonicalId", exactKnown?.canonicalId ?: "$type.$normalized")
        put("showdownId", exactKnown?.showdownId ?: showdownId)
        put("displayName", exactKnown?.displayName ?: showdownId)
        put("source", "user")
    }
}
