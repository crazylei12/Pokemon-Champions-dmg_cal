package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class PreviewCandidate(
    val entity: EntityValue,
    val confidence: Double,
    val source: String,
)

data class PreviewSlotChoice(
    val side: String,
    val slotIndex: Int,
    val candidates: List<PreviewCandidate>,
)

data class TeamPreviewDraft(
    val capturedAt: String,
    val ownSlots: List<PreviewSlotChoice>,
    val opponentSlots: List<PreviewSlotChoice>,
)

data class BattleStatStages(
    val atk: Int = 0,
    val def: Int = 0,
    val spa: Int = 0,
    val spd: Int = 0,
    val spe: Int = 0,
) {
    fun toJson() = JSONObject().apply {
        if (atk != 0) put("atk", atk.coerceIn(-6, 6))
        if (def != 0) put("def", def.coerceIn(-6, 6))
        if (spa != 0) put("spa", spa.coerceIn(-6, 6))
        if (spd != 0) put("spd", spd.coerceIn(-6, 6))
        if (spe != 0) put("spe", spe.coerceIn(-6, 6))
    }

    companion object {
        fun fromJson(json: JSONObject?) = BattleStatStages(
            atk = json?.optInt("atk") ?: 0,
            def = json?.optInt("def") ?: 0,
            spa = json?.optInt("spa") ?: 0,
            spd = json?.optInt("spd") ?: 0,
            spe = json?.optInt("spe") ?: 0,
        )
    }
}

data class SpeedPokemonModifiers(
    val stage: Int = 0,
    val paralyzed: Boolean = false,
    val doubled: Boolean = false,
    val choiceScarf: Boolean? = null,
) {
    fun toJson() = JSONObject().apply {
        put("stage", stage.coerceIn(-6, 6))
        put("paralyzed", paralyzed)
        put("doubled", doubled)
        choiceScarf?.let { put("choiceScarf", it) }
    }

    companion object {
        fun fromJson(json: JSONObject) = SpeedPokemonModifiers(
            stage = json.optInt("stage").coerceIn(-6, 6),
            paralyzed = json.optBoolean("paralyzed"),
            doubled = json.optBoolean("doubled"),
            choiceScarf = if (json.has("choiceScarf")) json.optBoolean("choiceScarf") else null,
        )
    }
}

data class SpeedLineState(
    val ownTailwind: Boolean = false,
    val opponentTailwind: Boolean = false,
    val trickRoom: Boolean = false,
    val ownPokemon: Map<Int, SpeedPokemonModifiers> = emptyMap(),
    val opponentPokemon: Map<Int, SpeedPokemonModifiers> = emptyMap(),
) {
    fun toJson() = JSONObject().apply {
        put("ownTailwind", ownTailwind)
        put("opponentTailwind", opponentTailwind)
        put("trickRoom", trickRoom)
        put("ownPokemon", ownPokemon.toSpeedModifiersJson())
        put("opponentPokemon", opponentPokemon.toSpeedModifiersJson())
    }

    companion object {
        fun fromJson(json: JSONObject?) = if (json == null) SpeedLineState() else SpeedLineState(
            ownTailwind = json.optBoolean("ownTailwind"),
            opponentTailwind = json.optBoolean("opponentTailwind"),
            trickRoom = json.optBoolean("trickRoom"),
            ownPokemon = json.optJSONObject("ownPokemon").toSpeedModifiers(),
            opponentPokemon = json.optJSONObject("opponentPokemon").toSpeedModifiers(),
        )
    }
}

data class BattleDirectHudState(
    val ownSlots: List<Int> = listOf(0, 1),
    val opponentSlots: List<Int> = listOf(0, 1),
    val visible: Boolean = true,
) {
    fun toJson() = JSONObject().apply {
        put("ownSlots", JSONArray().apply { ownSlots.take(2).forEach(::put) })
        put("opponentSlots", JSONArray().apply { opponentSlots.take(2).forEach(::put) })
        put("visible", visible)
    }

    companion object {
        fun fromJson(json: JSONObject?) = if (json == null) {
            BattleDirectHudState()
        } else {
            BattleDirectHudState(
                ownSlots = json.optJSONArray("ownSlots").toHudSlots(),
                opponentSlots = json.optJSONArray("opponentSlots").toHudSlots(),
                visible = json.optBoolean("visible", true),
            )
        }

        private fun JSONArray?.toHudSlots(): List<Int> = this?.let { array ->
            (0 until array.length()).map(array::optInt).take(2)
        }.orEmpty().takeIf { it.size == 2 } ?: listOf(0, 1)
    }
}

data class OpponentManualOverride(
    val baseProfileId: String,
    val statPoints: StatFields,
    val statAlignment: EntityValue?,
    val ability: EntityValue?,
    val itemOverrideEnabled: Boolean = false,
    val item: EntityValue? = null,
) {
    fun toJson() = JSONObject().apply {
        put("baseProfileId", baseProfileId)
        put("statPoints", statPoints.toJson(includeZero = true))
        statAlignment?.let { put("statAlignment", it.toJson()) }
        ability?.let { put("ability", it.toJson()) }
        put("itemOverrideEnabled", itemOverrideEnabled)
        if (itemOverrideEnabled) item?.let { put("item", it.toJson()) }
    }

    companion object {
        fun fromJson(json: JSONObject) = OpponentManualOverride(
            baseProfileId = json.getString("baseProfileId"),
            statPoints = json.optJSONObject("statPoints").toStatFields(),
            statAlignment = json.optJSONObject("statAlignment")?.toEntityValue(),
            ability = json.optJSONObject("ability")?.toEntityValue(),
            itemOverrideEnabled = json.optBoolean("itemOverrideEnabled", json.has("item")),
            item = json.optJSONObject("item")?.toEntityValue(),
        )
    }
}

data class BattlePokemonCondition(
    val burned: Boolean = false,
    val stages: BattleStatStages = BattleStatStages(),
) {
    fun toJson() = JSONObject().apply {
        put("burned", burned)
        put("stages", stages.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject?) = if (json == null) BattlePokemonCondition() else BattlePokemonCondition(
            burned = json.optBoolean("burned"),
            stages = BattleStatStages.fromJson(json.optJSONObject("stages")),
        )
    }
}

data class BattleCalculationState(
    val direction: String = "OWN_TO_OPPONENT",
    val ownSlot: Int = 0,
    val opponentSlot: Int = 0,
    val selectedPresetId: String? = null,
    val opponentPresetIds: Map<Int, String> = emptyMap(),
    val selectedMoveId: String? = null,
    val ownFormOverrides: Map<Int, EntityValue> = emptyMap(),
    val opponentFormOverrides: Map<Int, EntityValue> = emptyMap(),
    val opponentManualOverrides: Map<Int, OpponentManualOverride> = emptyMap(),
    val battleType: String = "SINGLE",
    val weather: String = "NONE",
    val terrain: String = "NONE",
    val ownReflect: Boolean = false,
    val ownLightScreen: Boolean = false,
    val ownAuroraVeil: Boolean = false,
    val opponentReflect: Boolean = false,
    val opponentLightScreen: Boolean = false,
    val opponentAuroraVeil: Boolean = false,
    val ownProtected: Boolean = false,
    val opponentProtected: Boolean = false,
    val helpingHand: Boolean = false,
    val critical: Boolean = false,
    val spread: Boolean = false,
    val ownConditions: Map<Int, BattlePokemonCondition> = emptyMap(),
    val opponentConditions: Map<Int, BattlePokemonCondition> = emptyMap(),
    val speedLine: SpeedLineState = SpeedLineState(),
    val directHud: BattleDirectHudState = BattleDirectHudState(),
) {
    fun ownCondition(slot: Int = ownSlot): BattlePokemonCondition = ownConditions[slot] ?: BattlePokemonCondition()

    fun opponentCondition(slot: Int = opponentSlot): BattlePokemonCondition =
        opponentConditions[slot] ?: BattlePokemonCondition()

    fun withOwnCondition(slot: Int = ownSlot, condition: BattlePokemonCondition): BattleCalculationState = copy(
        ownConditions = ownConditions.withCondition(slot, condition),
    )

    fun withOpponentCondition(slot: Int = opponentSlot, condition: BattlePokemonCondition): BattleCalculationState = copy(
        opponentConditions = opponentConditions.withCondition(slot, condition),
    )

    fun opponentPresetId(slot: Int = opponentSlot): String? = opponentPresetIds[slot]
        ?: selectedPresetId.takeIf { slot == opponentSlot }

    fun withOpponentPreset(profileId: String?, slot: Int = opponentSlot): BattleCalculationState {
        val remembered = opponentPresetIds.toMutableMap().apply {
            if (profileId.isNullOrBlank()) remove(slot) else put(slot, profileId)
        }
        return copy(
            selectedPresetId = if (slot == opponentSlot) profileId else selectedPresetId,
            opponentPresetIds = remembered,
        )
    }

    fun withOpponentSlot(slot: Int): BattleCalculationState {
        val remembered = opponentPresetIds.toMutableMap().apply {
            selectedPresetId?.takeIf(String::isNotBlank)?.let { put(opponentSlot, it) }
        }
        return copy(
            opponentSlot = slot,
            selectedPresetId = remembered[slot],
            opponentPresetIds = remembered,
        )
    }

    fun withBattleTypeDefaults(nextBattleType: String): BattleCalculationState {
        val normalizedBattleType = if (nextBattleType == "DOUBLE") "DOUBLE" else "SINGLE"
        return copy(
            battleType = normalizedBattleType,
            spread = normalizedBattleType == "DOUBLE",
            helpingHand = helpingHand && normalizedBattleType == "DOUBLE",
        )
    }

    fun toJson() = JSONObject().apply {
        put("direction", direction)
        put("ownSlot", ownSlot)
        put("opponentSlot", opponentSlot)
        selectedPresetId?.let { put("selectedPresetId", it) }
        put("opponentPresetIds", opponentPresetIds.toMutableMap().apply {
            selectedPresetId?.takeIf(String::isNotBlank)?.let { put(opponentSlot, it) }
        }.toStringMapJson())
        selectedMoveId?.let { put("selectedMoveId", it) }
        put("ownFormOverrides", ownFormOverrides.toJson())
        put("opponentFormOverrides", opponentFormOverrides.toJson())
        put("opponentManualOverrides", opponentManualOverrides.toManualOverridesJson())
        put("battleType", battleType)
        put("weather", weather)
        put("terrain", terrain)
        put("ownReflect", ownReflect)
        put("ownLightScreen", ownLightScreen)
        put("ownAuroraVeil", ownAuroraVeil)
        put("opponentReflect", opponentReflect)
        put("opponentLightScreen", opponentLightScreen)
        put("opponentAuroraVeil", opponentAuroraVeil)
        put("ownProtected", ownProtected)
        put("opponentProtected", opponentProtected)
        put("helpingHand", helpingHand)
        put("critical", critical)
        put("spread", spread)
        put("ownConditions", ownConditions.toConditionsJson())
        put("opponentConditions", opponentConditions.toConditionsJson())
        put("speedLine", speedLine.toJson())
        put("directHud", directHud.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject?): BattleCalculationState {
            if (json == null) return BattleCalculationState()
            val ownSlot = json.optInt("ownSlot", 0)
            val opponentSlot = json.optInt("opponentSlot", 0)
            val legacySelectedPresetId = json.optString("selectedPresetId").takeIf(String::isNotBlank)
            val opponentPresetIds = json.optJSONObject("opponentPresetIds").toStringMap().toMutableMap().apply {
                legacySelectedPresetId?.let { putIfAbsent(opponentSlot, it) }
            }
            val ownConditions = json.optJSONObject("ownConditions").toConditions().ifEmpty {
                val legacy = BattlePokemonCondition(
                    burned = json.optBoolean("ownBurned"),
                    stages = BattleStatStages.fromJson(json.optJSONObject("ownStages")),
                )
                if (legacy.isDefault()) emptyMap() else mapOf(ownSlot to legacy)
            }
            val opponentConditions = json.optJSONObject("opponentConditions").toConditions().ifEmpty {
                val legacy = BattlePokemonCondition(
                    burned = json.optBoolean("opponentBurned"),
                    stages = BattleStatStages.fromJson(json.optJSONObject("opponentStages")),
                )
                if (legacy.isDefault()) emptyMap() else mapOf(opponentSlot to legacy)
            }
            return BattleCalculationState(
            direction = json.optString("direction", "OWN_TO_OPPONENT"),
            ownSlot = ownSlot,
            opponentSlot = opponentSlot,
            selectedPresetId = legacySelectedPresetId ?: opponentPresetIds[opponentSlot],
            opponentPresetIds = opponentPresetIds,
            selectedMoveId = json.optString("selectedMoveId").takeIf(String::isNotBlank),
            ownFormOverrides = json.optJSONObject("ownFormOverrides").toEntityOverrides(),
            opponentFormOverrides = json.optJSONObject("opponentFormOverrides").toEntityOverrides(),
            opponentManualOverrides = json.optJSONObject("opponentManualOverrides").toManualOverrides(),
            battleType = json.optString("battleType", "SINGLE"),
            weather = json.optString("weather", "NONE"),
            terrain = json.optString("terrain", "NONE"),
            ownReflect = json.optBoolean("ownReflect"),
            ownLightScreen = json.optBoolean("ownLightScreen"),
            ownAuroraVeil = json.optBoolean("ownAuroraVeil"),
            opponentReflect = json.optBoolean("opponentReflect"),
            opponentLightScreen = json.optBoolean("opponentLightScreen"),
            opponentAuroraVeil = json.optBoolean("opponentAuroraVeil"),
            ownProtected = json.optBoolean("ownProtected"),
            opponentProtected = json.optBoolean("opponentProtected"),
            helpingHand = json.optBoolean("helpingHand"),
            critical = json.optBoolean("critical"),
            spread = json.optBoolean("spread"),
            ownConditions = ownConditions,
            opponentConditions = opponentConditions,
            speedLine = SpeedLineState.fromJson(json.optJSONObject("speedLine")),
            directHud = BattleDirectHudState.fromJson(json.optJSONObject("directHud")),
        )
        }
    }
}

data class BattleSession(
    val sessionId: String,
    val createdAt: String,
    val previewCapturedAt: String,
    val selectedOwnTeamId: String,
    val opponentTeam: List<EntityValue>,
    val calculation: BattleCalculationState = BattleCalculationState(),
)

class BattleSessionRepository(private val context: Context) {
    private val directory get() = context.filesDir.resolve("battle-session")
    private val previewFile get() = directory.resolve("current-team-preview.json")
    private val sessionFile get() = directory.resolve("current-battle-session.json")

    fun loadPreview(): TeamPreviewDraft? {
        if (!previewFile.isFile) return null
        val root = JSONObject(previewFile.readText(Charsets.UTF_8))
        fun slots(key: String) = root.optJSONArray(key).toObjects().map { slot ->
            PreviewSlotChoice(
                side = slot.optString("side"),
                slotIndex = slot.optInt("slotIndex"),
                candidates = slot.optJSONArray("candidates").toObjects().map { candidate ->
                    PreviewCandidate(
                        entity = candidate.toEntityValue(),
                        confidence = candidate.optDouble("confidence"),
                        source = candidate.optString("source"),
                    )
                },
            )
        }.sortedBy(PreviewSlotChoice::slotIndex)
        return TeamPreviewDraft(
            capturedAt = root.optString("capturedAt"),
            ownSlots = slots("ownTeamCandidates"),
            opponentSlots = slots("opponentTeamCandidates"),
        )
    }

    fun createSession(preview: TeamPreviewDraft, ownTeamId: String, opponents: List<EntityValue>): BattleSession {
        require(opponents.size == 6) { "请确认全部 6 只对方宝可梦" }
        return BattleSession(
            sessionId = "battle-${System.currentTimeMillis()}",
            createdAt = Instant.now().toString(),
            previewCapturedAt = preview.capturedAt,
            selectedOwnTeamId = ownTeamId,
            opponentTeam = opponents,
            calculation = BattleCalculationState(
                battleType = "DOUBLE",
                spread = true,
            ),
        ).also(::save)
    }

    fun loadSession(): BattleSession? {
        if (!sessionFile.isFile) return null
        val root = JSONObject(sessionFile.readText(Charsets.UTF_8))
        return BattleSession(
            sessionId = root.getString("sessionId"),
            createdAt = root.getString("createdAt"),
            previewCapturedAt = root.optString("previewCapturedAt"),
            selectedOwnTeamId = root.getString("selectedOwnTeamId"),
            opponentTeam = root.getJSONArray("opponentTeam").toObjects().map(JSONObject::toEntityValue),
            calculation = BattleCalculationState.fromJson(root.optJSONObject("calculationSelection")),
        )
    }

    fun save(session: BattleSession) {
        val root = JSONObject().apply {
            put("schemaVersion", 6)
            put("kind", "BattleSession")
            put("sessionId", session.sessionId)
            put("createdAt", session.createdAt)
            put("previewCapturedAt", session.previewCapturedAt)
            put("selectedOwnTeamId", session.selectedOwnTeamId)
            put("opponentTeam", JSONArray().apply { session.opponentTeam.forEach { put(it.toJson()) } })
            put("calculationSelection", session.calculation.toJson())
        }
        sessionFile.writeUtf8Atomically(root.toString(2))
    }
}

data class OpponentPreset(
    val profileId: String,
    val profileName: String,
    val source: String,
    val level: Int,
    val statPoints: StatFields,
    val actualStats: StatFields,
    val statAlignment: EntityValue?,
    val ability: EntityValue?,
    val item: EntityValue?,
    val moves: List<MoveValue>,
) {
    fun toProfileJson(stages: BattleStatStages, burned: Boolean) = JSONObject().apply {
        put("profileId", profileId)
        put("profileName", profileName)
        put("source", source)
        put("isSelected", true)
        put("includedInEnvelope", true)
        put("level", level)
        put("statPoints", statPoints.toJson(includeZero = true))
        if (actualStats.toJson().length() > 0) put("actualStats", actualStats.toJson())
        statAlignment?.let { put("statAlignment", it.toJson()) }
        ability?.let { put("ability", it.toJson()) }
        item?.let { put("item", it.toJson()) }
        if (moves.isNotEmpty()) put("moves", JSONArray().apply {
            moves.forEach { move -> put(JSONObject().put("move", move.entity.toJson()).put("source", "PROFILE_PRESET")) }
        })
        if (stages.toJson().length() > 0) put("statStages", stages.toJson())
        if (burned) put("status", "brn")
    }
}

data class UserOpponentPresetEntry(
    val species: EntityValue,
    val preset: OpponentPreset,
)

data class SpeciesFormOption(
    val familyId: String,
    val species: EntityValue,
    val baseStats: StatFields,
    val defaultAbility: EntityValue?,
    val abilities: List<EntityValue>,
    val learnableMoves: List<MoveValue>,
)

data class NatureOption(
    val entity: EntityValue,
    val plus: String?,
    val minus: String?,
)

class OpponentPresetRepository(private val context: Context) {
    val speciesCatalog: List<EntityValue>
    val itemCatalog: List<EntityValue>
    val natures: List<NatureOption>
    val legalMovePoolVersion: String
    val legalMovePoolSource: String
    val legalMovePoolDataDate: String?
    private val speciesByShowdown: Map<String, EntityValue>
    private val presetsBySpecies: Map<String, List<OpponentPreset>>
    private val formBySpecies: Map<String, SpeciesFormOption>
    private val formsByFamily: Map<String, List<SpeciesFormOption>>
    private val moveTypeByShowdown: Map<String, String>
    private val movePriorityByShowdown: Map<String, Int>
    private val typeNameByShowdown: Map<String, String>
    private val userPresetStore = OpponentUserPresetStore(context.filesDir.resolve(USER_OPPONENT_PRESETS_FILE))

    init {
        val localization = JSONArray(context.assets.open("recognition/zh-Hans.json").bufferedReader().use { it.readText() })
        val localizedEntities = localization.toObjects()
        speciesCatalog = localizedEntities
            .filter { it.optString("entityType") == "species" }
            .map(JSONObject::toEntityValue)
            .sortedBy(EntityValue::displayName)
        itemCatalog = localizedEntities
            .filter { it.optString("entityType") == "item" }
            .map(JSONObject::toEntityValue)
            .sortedBy(EntityValue::displayName)
        typeNameByShowdown = localizedEntities
            .filter { it.optString("entityType") == "type" }
            .map(JSONObject::toEntityValue)
            .associate { normalizeShowdownId(it.showdownId) to it.displayName }
        speciesByShowdown = speciesCatalog.associateBy { normalizeShowdownId(it.showdownId) }

        val root = JSONObject(context.assets.open("damage/champions-presets.json").bufferedReader().use { it.readText() })
        legalMovePoolVersion = root.getString("learnsetRulesetVersion")
        legalMovePoolSource = root.getString("learnsetPoolSource")
        legalMovePoolDataDate = root.optString("learnsetDataDate").takeIf(String::isNotBlank)
        val moveTypes = root.optJSONObject("moveTypes") ?: JSONObject()
        moveTypeByShowdown = moveTypes.keys().asSequence().associateWith(moveTypes::getString)
        val movePriorities = root.optJSONObject("movePriorities") ?: JSONObject()
        movePriorityByShowdown = movePriorities.keys().asSequence().associateWith(movePriorities::getInt)
        val forms = root.getJSONArray("speciesForms").toObjects().map { entry ->
            SpeciesFormOption(
                familyId = entry.getString("familyId"),
                species = entry.getJSONObject("species").toEntityValue(),
                baseStats = entry.getJSONObject("baseStats").toStatFields(),
                defaultAbility = entry.optJSONObject("defaultAbility")?.toEntityValue(),
                abilities = entry.optJSONArray("abilities").toObjects().map(JSONObject::toEntityValue),
                learnableMoves = entry.optJSONArray("learnableMoves").toObjects().mapNotNull(::parseMove),
            )
        }
        formBySpecies = forms.associateBy { normalizeShowdownId(it.species.showdownId) }
        formsByFamily = forms.groupBy(SpeciesFormOption::familyId).mapValues { (_, family) ->
            family.sortedWith(compareBy<SpeciesFormOption> { if (it.species.showdownId.contains("Mega", true)) 1 else 0 }
                .thenBy { it.species.displayName })
        }
        presetsBySpecies = root.getJSONArray("species").toObjects().associate { entry ->
            normalizeShowdownId(entry.getJSONObject("species").getString("showdownId")) to
                entry.getJSONArray("profiles").toObjects().map(::parsePreset)
        }
        natures = root.optJSONArray("natures").toObjects().map { entry ->
            NatureOption(
                entity = entry.getJSONObject("nature").toEntityValue(),
                plus = entry.optString("plus").takeIf(String::isNotBlank),
                minus = entry.optString("minus").takeIf(String::isNotBlank),
            )
        }
    }

    fun localizeSpecies(species: EntityValue): EntityValue =
        speciesByShowdown[normalizeShowdownId(species.showdownId)] ?: species

    fun formsFor(species: EntityValue): List<SpeciesFormOption> {
        val selected = formBySpecies[normalizeShowdownId(species.showdownId)] ?: return emptyList()
        return formsByFamily[selected.familyId].orEmpty()
    }

    fun effectiveOwnPokemon(config: PokemonConfig, override: EntityValue?): PokemonConfig {
        val selected = override ?: config.species
        if (normalizeShowdownId(selected.showdownId) == normalizeShowdownId(config.species.showdownId)) return config
        val sourceForm = formBySpecies[normalizeShowdownId(config.species.showdownId)] ?: return config.copy(species = selected)
        val targetForm = formBySpecies[normalizeShowdownId(selected.showdownId)] ?: return config.copy(species = selected)
        return config.copy(
            species = targetForm.species,
            actualStats = transformActualStats(config.actualStats, sourceForm.baseStats, targetForm.baseStats),
            ability = targetForm.defaultAbility ?: config.ability,
        )
    }

    fun profilesFor(species: EntityValue): List<OpponentPreset> = orderOpponentProfiles(
        userPresets = userPresetStore.profilesFor(species.showdownId),
        generatedPresets = generatedProfiles(species),
        builtInPresets = presetsBySpecies[normalizeShowdownId(species.showdownId)].orEmpty(),
    )

    fun saveUserPreset(
        species: EntityValue,
        name: String,
        current: OpponentPreset,
    ): OpponentPreset {
        val normalizedName = name.trim()
        require(normalizedName.isNotBlank()) { "请填写预设名称" }
        require(normalizedName.length <= 24) { "预设名称最多 24 个字符" }
        val saved = current.copy(
            profileId = "user.${UUID.randomUUID()}",
            profileName = normalizedName,
            source = OpponentUserPresetStore.USER_PRESET_SOURCE,
        )
        userPresetStore.save(species.showdownId, saved)
        return saved
    }

    fun userPresets(): List<UserOpponentPresetEntry> = userPresetStore.all().map { stored ->
        val species = speciesByShowdown[normalizeShowdownId(stored.speciesId)] ?: EntityValue(
            canonicalId = "species.${stored.speciesId}",
            showdownId = stored.speciesId,
            displayName = stored.speciesId,
            entityType = "species",
        )
        UserOpponentPresetEntry(species, stored.preset)
    }

    fun userPresetStorageProblem(): String? = userPresetStore.storageProblem()?.message

    fun preserveCorruptedUserPresetFileAndReset(): String =
        userPresetStore.preserveCorruptedFileAndReset().name

    fun updateUserPreset(
        species: EntityValue,
        preset: OpponentPreset,
    ): OpponentPreset {
        val points = sanitizedPoints(preset.statPoints)
        val form = formBySpecies[normalizeShowdownId(species.showdownId)]
        val updated = preset.copy(
            profileName = preset.profileName.trim(),
            source = OpponentUserPresetStore.USER_PRESET_SOURCE,
            statPoints = points,
            actualStats = form?.let { calculateStats(it.baseStats, points, preset.statAlignment) }
                ?: preset.actualStats,
        )
        userPresetStore.update(species.showdownId, updated)
        return updated
    }

    fun deleteUserPreset(profileId: String): Boolean {
        val deleted = userPresetStore.delete(profileId)
        if (deleted) clearBattleSessionPresetReferences(profileId)
        return deleted
    }

    fun abilitiesFor(species: EntityValue): List<EntityValue> {
        val form = formBySpecies[normalizeShowdownId(species.showdownId)] ?: return emptyList()
        return (listOfNotNull(form.defaultAbility) + form.abilities)
            .distinctBy { normalizeShowdownId(it.showdownId) }
    }

    fun movesFor(species: EntityValue, preferred: List<MoveValue> = emptyList()): List<MoveValue> =
        prioritizeLegalMoves(
            preferredMoves = preferred + profilesFor(species).flatMap(OpponentPreset::moves),
            legalMoves = formBySpecies[normalizeShowdownId(species.showdownId)]?.learnableMoves.orEmpty(),
        )

    fun configuredMoveOptionsFor(
        species: EntityValue,
        configuredMoves: List<MoveValue>,
    ): List<MoveValue> = configuredMoveOptions(configuredMoves, movesFor(species))

    fun moveTypeFor(move: MoveValue): String? = move.type
        ?: moveTypeByShowdown[normalizeShowdownId(move.entity.showdownId)]

    fun moveTypeLabel(move: MoveValue): String = moveTypeFor(move)
        ?.let { typeNameByShowdown[normalizeShowdownId(it)] ?: it }
        ?: "未知"

    fun movePriority(move: MoveValue): Int =
        movePriorityByShowdown[normalizeShowdownId(move.entity.showdownId)] ?: 0

    fun isSpeedLinePriorityMove(move: MoveValue): Boolean =
        movePriority(move) > 0 && !isProtectivePriorityMove(move.entity.showdownId)

    fun speedLinePriorityMovesFor(species: EntityValue, preferred: List<MoveValue> = emptyList()): List<MoveValue> =
        movesFor(species, preferred).filter(::isSpeedLinePriorityMove)

    fun possibleSpeedRangeFor(species: EntityValue): IntRange? {
        val baseSpeed = formBySpecies[normalizeShowdownId(species.showdownId)]
            ?.baseStats?.spe?.toIntOrNull() ?: return null
        val minimum = ((baseSpeed + 20) * 9) / 10
        val maximum = ((baseSpeed + 52) * 11) / 10
        return minimum..maximum
    }

    fun recommendedProfile(species: EntityValue): OpponentPreset {
        val profiles = profilesFor(species)
        return profiles.firstOrNull { preset -> preset.moves.any { (it.basePower ?: 0) > 0 } }
            ?: profiles.first()
    }

    fun configFor(species: EntityValue, profile: OpponentPreset = recommendedProfile(species)): PokemonConfig {
        val form = formBySpecies[normalizeShowdownId(species.showdownId)]
        val actualStats = if (profile.actualStats.toJson().length() > 0) profile.actualStats
            else form?.let { calculateStats(it.baseStats, profile.statPoints, profile.statAlignment) } ?: StatFields()
        val legalMoves = movesFor(species, profile.moves)
        val moves = if (profile.moves.isEmpty()) {
            legalMoves.filter { (it.basePower ?: 0) > 0 }.take(4)
        } else {
            legalMoves.take(4)
        }
        return PokemonConfig(
            species = localizeSpecies(species),
            level = profile.level,
            actualStats = actualStats,
            statPoints = profile.statPoints,
            ability = profile.ability ?: abilitiesFor(species).firstOrNull(),
            item = profile.item,
            moves = moves,
        )
    }

    fun effectivePreset(
        species: EntityValue,
        base: OpponentPreset,
        override: OpponentManualOverride?,
    ): OpponentPreset {
        if (override == null || override.baseProfileId != base.profileId) return base
        val points = sanitizedPoints(override.statPoints)
        val form = formBySpecies[normalizeShowdownId(species.showdownId)]
        val nature = override.statAlignment
        return base.copy(
            profileId = "${base.profileId}.manual",
            profileName = "自定义配置（基于 ${base.profileName}）",
            source = "MANUAL_CURRENT",
            statPoints = points,
            actualStats = form?.let { calculateStats(it.baseStats, points, nature) } ?: base.actualStats,
            statAlignment = nature,
            ability = override.ability,
            item = if (override.itemOverrideEnabled) override.item else base.item,
        )
    }

    private fun generatedProfiles(species: EntityValue) = listOf(
        generated(species, "generated.default", "无加点", StatFields()),
        generated(species, "generated.physical-bulk", "偏物耐", StatFields(hp = "32", def = "32")),
        generated(species, "generated.special-bulk", "偏特耐", StatFields(hp = "32", spd = "32")),
        generated(species, "generated.physical-offense", "物攻与速度", StatFields(atk = "32", spe = "32")),
        generated(species, "generated.special-offense", "特攻与速度", StatFields(spa = "32", spe = "32")),
    )

    private fun generated(species: EntityValue, id: String, name: String, points: StatFields) = OpponentPreset(
        profileId = id,
        profileName = name,
        source = "GENERATED_TEMPLATE",
        level = 50,
        statPoints = points,
        actualStats = formBySpecies[normalizeShowdownId(species.showdownId)]?.baseStats
            ?.let { calculateStats(it, points, null) } ?: StatFields(),
        statAlignment = null,
        ability = null,
        item = null,
        moves = emptyList(),
    )

    private fun parsePreset(json: JSONObject) = OpponentPreset(
        profileId = json.getString("profileId"),
        profileName = "常用配置：${json.getString("profileName")}",
        source = json.optString("source", "OPEN_SOURCE_PRESET"),
        level = json.optInt("level", 50),
        statPoints = json.optJSONObject("statPoints").toStatFields(),
        actualStats = json.optJSONObject("actualStats").toStatFields(),
        statAlignment = json.optJSONObject("statAlignment")?.toEntityValue(),
        ability = json.optJSONObject("ability")?.toEntityValue(),
        item = json.optJSONObject("item")?.toEntityValue(),
        moves = json.optJSONArray("moves").toObjects().mapNotNull(::parseMove),
    )

    private fun parseMove(json: JSONObject): MoveValue? = json.optJSONObject("move")?.let {
        MoveValue(
            entity = it.toEntityValue(),
            basePower = json.optInt("basePower").takeIf { value -> value > 0 },
            type = json.optString("type").takeIf(String::isNotBlank),
        )
    }

    private fun transformActualStats(actual: StatFields, sourceBase: StatFields, targetBase: StatFields): StatFields {
        val actualValues = actual.asMap().mapValues { it.value.toIntOrNull() }
        if (actualValues.values.any { it == null }) return actual
        val source = sourceBase.asMap().mapValues { it.value.toIntOrNull() ?: return actual }
        val target = targetBase.asMap().mapValues { it.value.toIntOrNull() ?: return actual }
        val natureCandidates = listOf(null to null) + NON_HP_STATS.flatMap { plus ->
            NON_HP_STATS.filter { it != plus }.map { minus -> plus to minus }
        }
        val best = natureCandidates.minByOrNull { (plus, minus) ->
            STATS.sumOf { stat ->
                val expected = actualValues.getValue(stat)!!
                (0..32).minOf { points -> kotlin.math.abs(championsStat(stat, source.getValue(stat), points, plus, minus) - expected) }
            }
        } ?: (null to null)
        val points = STATS.associateWith { stat ->
            val expected = actualValues.getValue(stat)!!
            (0..32).minByOrNull { value ->
                kotlin.math.abs(championsStat(stat, source.getValue(stat), value, best.first, best.second) - expected)
            } ?: 0
        }
        return StatFields.fromMap(STATS.associateWith { stat ->
            championsStat(stat, target.getValue(stat), points.getValue(stat), best.first, best.second).toString()
        })
    }

    private fun calculateStats(base: StatFields, points: StatFields, nature: EntityValue?): StatFields {
        val bases = base.asMap()
        val pointValues = points.asMap()
        val natureOption = natures.firstOrNull {
            normalizeShowdownId(it.entity.showdownId) == normalizeShowdownId(nature?.showdownId.orEmpty())
        }
        val plus = natureOption?.plus?.takeUnless { it == natureOption.minus }
        val minus = natureOption?.minus?.takeUnless { it == natureOption.plus }
        return StatFields.fromMap(STATS.associateWith { stat ->
            championsStat(stat, bases[stat]?.toIntOrNull() ?: 0, pointValues[stat]?.toIntOrNull() ?: 0, plus, minus).toString()
        })
    }

    private fun sanitizedPoints(points: StatFields) = StatFields.fromMap(points.asMap().mapValues { (_, value) ->
        (value.toIntOrNull() ?: 0).coerceIn(0, 32).toString()
    })

    private fun clearBattleSessionPresetReferences(profileId: String) {
        runCatching {
            val sessions = BattleSessionRepository(context)
            val session = sessions.loadSession() ?: return
            sessions.save(session.copy(
                calculation = removeOpponentPresetReferences(session.calculation, profileId),
            ))
        }
    }

    private fun championsStat(stat: String, base: Int, points: Int, plus: String?, minus: String?): Int {
        if (stat == "hp") return if (base == 1) 1 else base + points + 75
        val multiplier = when (stat) { plus -> 1.1; minus -> 0.9; else -> 1.0 }
        return kotlin.math.floor(multiplier * (base + points + 20)).toInt()
    }

    private companion object {
        val STATS = listOf("hp", "atk", "def", "spa", "spd", "spe")
        val NON_HP_STATS = listOf("atk", "def", "spa", "spd", "spe")
    }
}

internal fun orderOpponentProfiles(
    userPresets: List<OpponentPreset>,
    generatedPresets: List<OpponentPreset>,
    builtInPresets: List<OpponentPreset>,
): List<OpponentPreset> = userPresets + generatedPresets + builtInPresets

internal fun selectAvailableOpponentPreset(
    profiles: List<OpponentPreset>,
    profileId: String?,
): OpponentPreset = profiles.firstOrNull { it.profileId == profileId } ?: profiles.first()

internal fun removeOpponentPresetReferences(
    state: BattleCalculationState,
    profileId: String,
): BattleCalculationState {
    val removedSlots = state.opponentPresetIds
        .filterValues { it == profileId }
        .keys
    return state.copy(
        selectedPresetId = state.selectedPresetId.takeUnless { it == profileId },
        opponentPresetIds = state.opponentPresetIds.filterValues { it != profileId },
        opponentManualOverrides = state.opponentManualOverrides.filterNot { (slot, override) ->
            slot in removedSlots || override.baseProfileId == profileId
        },
    )
}

fun buildBattleDamageRequest(
    session: BattleSession,
    ownTeam: SavedTeam,
    preset: OpponentPreset,
    legalMoves: List<MoveValue>,
    presetRepository: OpponentPresetRepository,
    allOwnMoves: Boolean = false,
): String {
    val state = session.calculation
    val ownSlot = state.ownSlot.coerceIn(0, ownTeam.pokemon.lastIndex)
    val opponentSlot = state.opponentSlot.coerceIn(0, session.opponentTeam.lastIndex)
    val own = presetRepository.effectiveOwnPokemon(ownTeam.pokemon[ownSlot], state.ownFormOverrides[ownSlot])
    val opponent = state.opponentFormOverrides[opponentSlot] ?: session.opponentTeam[opponentSlot]
    val ownCondition = state.ownCondition(ownSlot)
    val opponentCondition = state.opponentCondition(opponentSlot)
    val ownBuild = PokemonEditorState.from(own).toBuildJson("OWN_BUILD").apply {
        if (ownCondition.stages.toJson().length() > 0) put("statStages", ownCondition.stages.toJson())
        if (ownCondition.burned) put("status", "brn")
    }
    val opponentEntity = opponent.toJson()
    val battle = JSONObject().apply {
        put("battleType", state.battleType)
        put("weather", state.weather)
        put("terrain", state.terrain)
        put("isCritical", state.critical)
        put("isSpreadMove", state.battleType == "DOUBLE" && state.spread)
    }
    return JSONObject().apply {
        put("requestId", "android-battle-${System.currentTimeMillis()}")
        put("calculationDirection", state.direction)
        put("calculationMode", "TEMPLATE")
        put("battle", battle)
        if (state.direction == "OWN_TO_OPPONENT") {
            put("attackerSide", "OWN")
            put("defenderSide", "OPPONENT")
            put("attacker", ownBuild)
            put("defenderIdentity", JSONObject().put("species", opponentEntity))
            put("defenderProfileSet", JSONObject().apply {
                put("defenderSpecies", opponentEntity)
                put("selectedProfileId", preset.profileId)
                put("profiles", JSONArray().put(preset.toProfileJson(opponentCondition.stages, opponentCondition.burned)))
            })
            put("moveSelection", JSONObject().apply {
                put("mode", if (allOwnMoves) "ALL_ATTACKER_MOVES" else "ONE_MOVE")
                if (!allOwnMoves) state.selectedMoveId?.let { put("moveId", it) }
            })
            battle.put("attackerSideConditions", JSONObject().put(
                "helpingHand",
                state.battleType == "DOUBLE" && state.helpingHand,
            ))
            battle.put("defenderSideConditions", JSONObject().apply {
                put("reflect", state.opponentReflect)
                put("lightScreen", state.opponentLightScreen)
                put("auroraVeil", state.opponentAuroraVeil)
                put("protected", state.opponentProtected)
            })
        } else {
            put("attackerSide", "OPPONENT")
            put("defenderSide", "OWN")
            put("attackerIdentity", JSONObject().put("species", opponentEntity))
            put("attackerProfileSet", JSONObject().apply {
                put("attackerSpecies", opponentEntity)
                put("selectedProfileId", preset.profileId)
                put("profiles", JSONArray().put(preset.toProfileJson(opponentCondition.stages, opponentCondition.burned)))
            })
            put("attackerLegalMovePool", JSONObject().apply {
                put("species", opponentEntity)
                put("rulesetVersion", presetRepository.legalMovePoolVersion)
                put("source", presetRepository.legalMovePoolSource)
                presetRepository.legalMovePoolDataDate?.let { put("dataDate", it) }
                put("learnableMoves", JSONArray().apply { legalMoves.forEach { put(it.entity.toJson()) } })
            })
            put("defender", ownBuild)
            put("moveSelection", JSONObject().apply {
                put("mode", "ONE_MOVE")
                state.selectedMoveId?.let { put("moveId", it) }
                put("source", "OPPONENT_LEGAL_MOVE_POOL")
                put("legalMovePoolVersion", presetRepository.legalMovePoolVersion)
            })
            battle.put("attackerSideConditions", JSONObject().put(
                "helpingHand",
                state.battleType == "DOUBLE" && state.helpingHand,
            ))
            battle.put("defenderSideConditions", JSONObject().apply {
                put("reflect", state.ownReflect)
                put("lightScreen", state.ownLightScreen)
                put("auroraVeil", state.ownAuroraVeil)
                put("protected", state.ownProtected)
            })
        }
    }.toString()
}

internal fun EntityValue.toJson() = JSONObject().apply {
    put("entityType", entityType.lowercase())
    put("canonicalId", canonicalId)
    put("showdownId", showdownId)
    put("displayName", displayName)
    put("source", "user")
}

private fun JSONObject.toEntityValue(): EntityValue {
    val localizedName = optJSONObject("localizedNames")
        ?.optJSONArray("zh-Hans")
        ?.optString(0)
        ?.takeIf(String::isNotBlank)
    return EntityValue(
        canonicalId = getString("canonicalId"),
        showdownId = getString("showdownId"),
        displayName = optString("displayName").takeIf(String::isNotBlank)
            ?: localizedName
            ?: getString("showdownId"),
        entityType = optString("entityType", "species").lowercase(),
    )
}

private fun JSONObject?.toStatFields(): StatFields {
    fun value(key: String) = if (this?.has(key) == true) optInt(key).toString() else ""
    return StatFields(value("hp"), value("atk"), value("def"), value("spa"), value("spd"), value("spe"))
}

private fun Map<Int, EntityValue>.toJson() = JSONObject().apply {
    this@toJson.forEach { (slot, entity) -> put(slot.toString(), entity.toJson()) }
}

private fun Map<Int, String>.toStringMapJson() = JSONObject().apply {
    this@toStringMapJson.forEach { (slot, value) ->
        if (value.isNotBlank()) put(slot.toString(), value)
    }
}

private fun JSONObject?.toStringMap(): Map<Int, String> {
    if (this == null) return emptyMap()
    return keys().asSequence().mapNotNull { key ->
        val value = optString(key).takeIf(String::isNotBlank) ?: return@mapNotNull null
        key.toIntOrNull()?.let { slot -> slot to value }
    }.toMap()
}

private fun JSONObject?.toEntityOverrides(): Map<Int, EntityValue> {
    if (this == null) return emptyMap()
    return keys().asSequence().mapNotNull { key ->
        key.toIntOrNull()?.let { slot -> optJSONObject(key)?.let { slot to it.toEntityValue() } }
    }.toMap()
}

private fun Map<Int, OpponentManualOverride>.toManualOverridesJson() = JSONObject().apply {
    this@toManualOverridesJson.forEach { (slot, override) -> put(slot.toString(), override.toJson()) }
}

private fun Map<Int, SpeedPokemonModifiers>.toSpeedModifiersJson() = JSONObject().apply {
    forEach { (slot, modifiers) -> put(slot.toString(), modifiers.toJson()) }
}

private fun BattlePokemonCondition.isDefault(): Boolean = !burned && stages == BattleStatStages()

private fun Map<Int, BattlePokemonCondition>.withCondition(
    slot: Int,
    condition: BattlePokemonCondition,
): Map<Int, BattlePokemonCondition> = toMutableMap().apply {
    if (condition.isDefault()) remove(slot) else put(slot, condition)
}

private fun Map<Int, BattlePokemonCondition>.toConditionsJson() = JSONObject().apply {
    this@toConditionsJson.forEach { (slot, condition) -> put(slot.toString(), condition.toJson()) }
}

private fun JSONObject?.toConditions(): Map<Int, BattlePokemonCondition> {
    if (this == null) return emptyMap()
    return keys().asSequence().mapNotNull { key ->
        key.toIntOrNull()?.let { slot ->
            optJSONObject(key)?.let(BattlePokemonCondition::fromJson)?.takeUnless { it.isDefault() }?.let { slot to it }
        }
    }.toMap()
}

private fun JSONObject?.toSpeedModifiers(): Map<Int, SpeedPokemonModifiers> {
    if (this == null) return emptyMap()
    return keys().asSequence().mapNotNull { key ->
        key.toIntOrNull()?.let { slot -> slot to SpeedPokemonModifiers.fromJson(getJSONObject(key)) }
    }.toMap()
}

private fun JSONObject?.toManualOverrides(): Map<Int, OpponentManualOverride> {
    if (this == null) return emptyMap()
    return keys().asSequence().mapNotNull { key ->
        key.toIntOrNull()?.let { slot -> optJSONObject(key)?.let { slot to OpponentManualOverride.fromJson(it) } }
    }.toMap()
}

private fun JSONArray?.toObjects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull(::optJSONObject)

private fun normalizeShowdownId(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "")
