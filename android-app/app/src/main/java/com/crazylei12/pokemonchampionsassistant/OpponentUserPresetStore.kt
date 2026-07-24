package com.crazylei12.pokemonchampionsassistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

internal const val USER_OPPONENT_PRESETS_FILE = "user-opponent-presets.json"

internal data class StoredOpponentPreset(
    val speciesId: String,
    val preset: OpponentPreset,
)

internal class OpponentUserPresetStore(
    private val file: File,
) {
    private var entries = load(file).toMutableList()
    private var observedRevision = sharedRevision.get()
    private var observedFileStamp = fileStamp()

    @Synchronized
    fun profilesFor(speciesId: String): List<OpponentPreset> {
        refreshIfChanged()
        val normalized = normalizeSpeciesId(speciesId)
        return entries.asSequence()
            .filter { normalizeSpeciesId(it.speciesId) == normalized }
            .map(StoredOpponentPreset::preset)
            .toList()
    }

    @Synchronized
    fun all(): List<StoredOpponentPreset> {
        refreshIfChanged()
        return entries.toList()
    }

    @Synchronized
    fun save(speciesId: String, preset: OpponentPreset) {
        refreshIfChanged()
        require(preset.source == USER_PRESET_SOURCE) { "只能保存用户预设" }
        val normalizedSpeciesId = normalizeSpeciesId(speciesId)
        require(normalizedSpeciesId.isNotBlank()) { "宝可梦 ID 不能为空" }
        validatePreset(preset)
        entries.removeAll { it.preset.profileId == preset.profileId }
        require(entries.size < MAX_PRESETS) { "最多保存 $MAX_PRESETS 个用户对手预设" }
        entries.add(StoredOpponentPreset(normalizedSpeciesId, preset))
        persist()
    }

    @Synchronized
    fun update(speciesId: String, preset: OpponentPreset) {
        refreshIfChanged()
        require(preset.source == USER_PRESET_SOURCE) { "只能修改用户预设" }
        val normalizedSpeciesId = normalizeSpeciesId(speciesId)
        require(normalizedSpeciesId.isNotBlank()) { "宝可梦 ID 不能为空" }
        validatePreset(preset)
        val index = entries.indexOfFirst { it.preset.profileId == preset.profileId }
        require(index >= 0) { "找不到要修改的用户预设" }
        entries[index] = StoredOpponentPreset(normalizedSpeciesId, preset)
        persist()
    }

    @Synchronized
    fun delete(profileId: String): Boolean {
        refreshIfChanged()
        val removed = entries.removeAll { it.preset.profileId == profileId }
        if (removed) persist()
        return removed
    }

    private fun refreshIfChanged() {
        val revision = sharedRevision.get()
        val stamp = fileStamp()
        if (revision == observedRevision && stamp == observedFileStamp) return
        entries = load(file).toMutableList()
        observedRevision = revision
        observedFileStamp = stamp
    }

    private fun fileStamp(): Pair<Long, Long> =
        if (file.isFile) file.lastModified() to file.length() else 0L to 0L

    private fun persist() {
        val root = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("kind", KIND)
            put("presets", JSONArray().apply {
                entries.forEach { entry ->
                    put(JSONObject().apply {
                        put("speciesId", entry.speciesId)
                        put("preset", entry.preset.toStorageJson())
                    })
                }
            })
        }
        file.writeUtf8Atomically(root.toString(2))
        observedRevision = sharedRevision.incrementAndGet()
        observedFileStamp = fileStamp()
    }

    companion object {
        const val USER_PRESET_SOURCE = "USER_SAVED"
        private const val SCHEMA_VERSION = 1
        private const val KIND = "OpponentUserPresets"
        private const val MAX_PRESETS = 500
        private val sharedRevision = AtomicLong(0)

        fun validateRoot(root: JSONObject) {
            parseRoot(root)
        }

        private fun load(file: File): List<StoredOpponentPreset> {
            if (!file.isFile) return emptyList()
            return runCatching {
                parseRoot(JSONObject(file.readText(Charsets.UTF_8)))
            }.getOrDefault(emptyList())
        }

        private fun parseRoot(root: JSONObject): List<StoredOpponentPreset> {
            require(root.optInt("schemaVersion") == SCHEMA_VERSION) { "不支持的对手预设版本" }
            require(root.optString("kind") == KIND) { "对手预设文件类型无效" }
            val presets = root.getJSONArray("presets")
            require(presets.length() <= MAX_PRESETS) { "用户对手预设数量异常" }
            val profileIds = HashSet<String>()
            return (0 until presets.length()).map { index ->
                val entry = presets.getJSONObject(index)
                val speciesId = normalizeSpeciesId(entry.getString("speciesId"))
                require(speciesId.isNotBlank()) { "用户对手预设缺少宝可梦 ID" }
                val preset = entry.getJSONObject("preset").toStoredPreset()
                require(profileIds.add(preset.profileId)) { "用户对手预设 ID 重复" }
                StoredOpponentPreset(speciesId, preset)
            }
        }

        private fun validatePreset(preset: OpponentPreset) {
            require(preset.profileId.startsWith("user.") && preset.profileId.length <= 120) {
                "用户对手预设 ID 无效"
            }
            require(preset.profileName.isNotBlank() && preset.profileName.length <= 24) {
                "预设名称应为 1–24 个字符"
            }
            require(preset.level in 1..100) { "预设等级无效" }
            require(preset.moves.size <= 4) { "预设招式数量无效" }
            preset.statPoints.asMap().values.forEach { value ->
                require((value.toIntOrNull() ?: 0) in 0..32) { "预设能力点无效" }
            }
        }

        private fun OpponentPreset.toStorageJson() = JSONObject().apply {
            put("profileId", profileId)
            put("profileName", profileName)
            put("source", source)
            put("level", level)
            put("statPoints", statPoints.toJson(includeZero = true))
            if (actualStats.toJson().length() > 0) put("actualStats", actualStats.toJson())
            statAlignment?.let { put("statAlignment", it.toJson()) }
            ability?.let { put("ability", it.toJson()) }
            item?.let { put("item", it.toJson()) }
            put("moves", JSONArray().apply {
                moves.forEach { move ->
                    put(JSONObject().apply {
                        put("move", move.entity.toJson())
                        move.basePower?.let { put("basePower", it) }
                        move.type?.let { put("type", it) }
                    })
                }
            })
        }

        private fun JSONObject.toStoredPreset(): OpponentPreset {
            val preset = OpponentPreset(
                profileId = getString("profileId"),
                profileName = getString("profileName").trim(),
                source = getString("source"),
                level = getInt("level"),
                statPoints = optJSONObject("statPoints").toStoredStatFields(),
                actualStats = optJSONObject("actualStats").toStoredStatFields(),
                statAlignment = optJSONObject("statAlignment")?.toStoredEntity(),
                ability = optJSONObject("ability")?.toStoredEntity(),
                item = optJSONObject("item")?.toStoredEntity(),
                moves = optJSONArray("moves").toStoredObjects().map { move ->
                    MoveValue(
                        entity = move.getJSONObject("move").toStoredEntity(),
                        basePower = move.optInt("basePower").takeIf { move.has("basePower") },
                        type = move.optString("type").takeIf(String::isNotBlank),
                    )
                },
            )
            require(preset.source == USER_PRESET_SOURCE) { "对手预设来源无效" }
            validatePreset(preset)
            return preset
        }

        private fun JSONObject.toStoredEntity() = EntityValue(
            canonicalId = getString("canonicalId"),
            showdownId = getString("showdownId"),
            displayName = getString("displayName"),
            entityType = getString("entityType").lowercase(),
        )

        private fun JSONObject?.toStoredStatFields(): StatFields {
            fun value(key: String) = if (this?.has(key) == true) optInt(key).toString() else ""
            return StatFields(value("hp"), value("atk"), value("def"), value("spa"), value("spd"), value("spe"))
        }

        private fun JSONArray?.toStoredObjects(): List<JSONObject> = if (this == null) emptyList() else
            (0 until length()).mapNotNull(::optJSONObject)

        private fun normalizeSpeciesId(value: String) =
            value.lowercase().replace(Regex("[^a-z0-9]+"), "")
    }
}
