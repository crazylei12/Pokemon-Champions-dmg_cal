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

internal data class OpponentPresetMergeResult(
    val imported: Int,
    val added: Int,
    val updated: Int,
    val unchanged: Int,
)

internal data class OpponentPresetStorageProblem(
    val message: String,
)

internal class OpponentUserPresetStore(
    private val file: File,
) {
    private var loaded = load(file)
    private var entries = loaded.entries.toMutableList()
    private var storageProblem = loaded.problem
    private var observedRevision = sharedRevision.get()
    private var observedFileStamp = fileStamp()

    @Synchronized
    fun storageProblem(): OpponentPresetStorageProblem? {
        refreshIfChanged()
        return storageProblem
    }

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
    fun exportRoot(): JSONObject {
        refreshIfChanged()
        requireHealthyStorage()
        return createRoot(entries)
    }

    @Synchronized
    fun mergeFrom(root: JSONObject): OpponentPresetMergeResult {
        refreshIfChanged()
        requireHealthyStorage()
        val incoming = parseRoot(root)
        val merged = entries.toMutableList()
        var added = 0
        var updated = 0
        var unchanged = 0
        incoming.forEach { entry ->
            val index = merged.indexOfFirst { it.preset.profileId == entry.preset.profileId }
            when {
                index < 0 -> {
                    merged.add(entry)
                    added += 1
                }
                merged[index] == entry -> unchanged += 1
                else -> {
                    merged[index] = entry
                    updated += 1
                }
            }
        }
        require(merged.size <= MAX_PRESETS) { "合并后最多只能有 $MAX_PRESETS 个用户对手预设" }
        if (added > 0 || updated > 0) {
            entries = merged
            persist()
        }
        return OpponentPresetMergeResult(
            imported = incoming.size,
            added = added,
            updated = updated,
            unchanged = unchanged,
        )
    }

    @Synchronized
    fun save(speciesId: String, preset: OpponentPreset) {
        refreshIfChanged()
        requireHealthyStorage()
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
        requireHealthyStorage()
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
        requireHealthyStorage()
        val removed = entries.removeAll { it.preset.profileId == profileId }
        if (removed) persist()
        return removed
    }

    @Synchronized
    fun preserveCorruptedFileAndReset(): File {
        refreshIfChanged()
        requireNotNull(storageProblem) { "当前保存配置文件没有检测到损坏" }
        require(file.isFile) { "找不到需要保留的损坏配置文件" }
        val recovery = nextRecoveryFile()
        file.copyTo(recovery, overwrite = false)
        entries = mutableListOf()
        try {
            persist()
            storageProblem = null
        } catch (error: Exception) {
            storageProblem = OpponentPresetStorageProblem(STORAGE_PROBLEM_MESSAGE)
            throw error
        }
        return recovery
    }

    private fun refreshIfChanged() {
        val revision = sharedRevision.get()
        val stamp = fileStamp()
        if (revision == observedRevision && stamp == observedFileStamp) return
        loaded = load(file)
        entries = loaded.entries.toMutableList()
        storageProblem = loaded.problem
        observedRevision = revision
        observedFileStamp = stamp
    }

    private fun requireHealthyStorage() {
        check(storageProblem == null) {
            "$STORAGE_PROBLEM_MESSAGE 请回到首页保留原文件副本并重置后重试。"
        }
    }

    private fun nextRecoveryFile(): File {
        val directory = requireNotNull(file.parentFile) { "保存配置文件必须有父目录" }
        val baseName = file.nameWithoutExtension
        val extension = file.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
        val timestamp = System.currentTimeMillis()
        var suffix = 0
        while (true) {
            val numbered = if (suffix == 0) "" else "-$suffix"
            val candidate = directory.resolve("$baseName.corrupt-$timestamp$numbered$extension")
            if (!candidate.exists()) return candidate
            suffix += 1
        }
    }

    private fun fileStamp(): Pair<Long, Long> =
        if (file.isFile) file.lastModified() to file.length() else 0L to 0L

    private fun persist() {
        file.writeUtf8Atomically(createRoot(entries).toString(2))
        observedRevision = sharedRevision.incrementAndGet()
        observedFileStamp = fileStamp()
    }

    companion object {
        const val USER_PRESET_SOURCE = "USER_SAVED"
        private const val SCHEMA_VERSION = 1
        private const val KIND = "OpponentUserPresets"
        private const val MAX_PRESETS = 500
        private const val STORAGE_PROBLEM_MESSAGE =
            "保存的宝可梦配置文件无法读取，已停止写入以免覆盖原数据。"
        private val sharedRevision = AtomicLong(0)
        private data class LoadResult(
            val entries: List<StoredOpponentPreset>,
            val problem: OpponentPresetStorageProblem?,
        )

        fun emptyRoot(): JSONObject = createRoot(emptyList())

        fun validateRoot(root: JSONObject) {
            parseRoot(root)
        }

        fun notifyExternalChange() {
            sharedRevision.incrementAndGet()
        }

        private fun load(file: File): LoadResult {
            if (!file.isFile) return LoadResult(emptyList(), null)
            return runCatching {
                LoadResult(parseRoot(JSONObject(file.readText(Charsets.UTF_8))), null)
            }.getOrElse {
                LoadResult(emptyList(), OpponentPresetStorageProblem(STORAGE_PROBLEM_MESSAGE))
            }
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

        private fun createRoot(entries: List<StoredOpponentPreset>) = JSONObject().apply {
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
