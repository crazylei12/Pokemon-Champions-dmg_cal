package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class AppBackupSummary(
    val teamCount: Int,
    val hasBattleSession: Boolean,
)

object AppDataBackup {
    private const val SCHEMA_VERSION = 1
    private const val KIND = "PokemonChampionsAssistantBackup"
    private const val MAX_BACKUP_BYTES = 16 * 1024 * 1024
    private val rootFiles = listOf("pending-own-team.json", "own-team-import-draft.json")

    fun exportTo(context: Context, uri: Uri): AppBackupSummary {
        val root = buildEnvelope(context)
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(root.toString(2))
        } ?: error("无法打开所选导出位置")
        val data = root.getJSONObject("data")
        return AppBackupSummary(
            teamCount = data.getJSONArray("savedTeams").length(),
            hasBattleSession = data.has("currentBattleSession"),
        )
    }

    fun restoreFrom(context: Context, uri: Uri): AppBackupSummary {
        val bytes = context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            input.readNBytes(MAX_BACKUP_BYTES + 1)
        } ?: error("无法读取所选备份文件")
        require(bytes.size <= MAX_BACKUP_BYTES) { "备份文件超过 16 MB，已拒绝导入" }
        val validated = validateEnvelope(JSONObject(bytes.toString(Charsets.UTF_8)))
        val previousFiles = snapshotManagedFiles(context)
        val previousChannel = AppUpdatePreferences.loadChannel(context)
        runCatching {
            replaceManagedFiles(context, validated)
            AppUpdatePreferences.saveChannel(context, validated.updateChannel)
        }.onFailure {
            restoreSnapshot(context, previousFiles)
            AppUpdatePreferences.saveChannel(context, previousChannel)
        }.getOrThrow()
        return AppBackupSummary(validated.savedTeams.size, validated.currentBattleSession != null)
    }

    internal fun buildEnvelope(context: Context): JSONObject {
        val savedTeams = context.filesDir.resolve("saved-teams")
            .listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }
            .map { JSONObject(it.readText(Charsets.UTF_8)) }
        val battleDirectory = context.filesDir.resolve("battle-session")
        fun jsonFile(file: java.io.File): JSONObject? = file.takeIf(java.io.File::isFile)
            ?.let { JSONObject(it.readText(Charsets.UTF_8)) }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("kind", KIND)
            put("exportedAt", Instant.now().toString())
            put("appVersion", installedVersion(context).name)
            put("data", JSONObject().apply {
                put("savedTeams", JSONArray().apply { savedTeams.forEach(::put) })
                jsonFile(battleDirectory.resolve("current-battle-session.json"))
                    ?.let { put("currentBattleSession", it) }
                jsonFile(battleDirectory.resolve("current-team-preview.json"))
                    ?.let { put("currentTeamPreview", it) }
                jsonFile(context.filesDir.resolve("pending-own-team.json"))
                    ?.let { put("pendingOwnTeam", it) }
                jsonFile(context.filesDir.resolve("own-team-import-draft.json"))
                    ?.let { put("ownTeamImportDraft", it) }
                put("updateChannel", AppUpdatePreferences.loadChannel(context).storedValue)
            })
        }
    }

    private data class ValidatedBackup(
        val savedTeams: List<JSONObject>,
        val currentBattleSession: JSONObject?,
        val currentTeamPreview: JSONObject?,
        val pendingOwnTeam: JSONObject?,
        val ownTeamImportDraft: JSONObject?,
        val updateChannel: UpdateChannel,
    )

    private fun validateEnvelope(root: JSONObject): ValidatedBackup {
        require(root.optInt("schemaVersion") == SCHEMA_VERSION) { "不支持的备份版本" }
        require(root.optString("kind") == KIND) { "所选文件不是冠军伤害计算器备份" }
        val data = root.getJSONObject("data")
        val teamsArray = data.getJSONArray("savedTeams")
        require(teamsArray.length() <= 100) { "备份中的队伍数量异常" }
        val ids = HashSet<String>()
        val teams = (0 until teamsArray.length()).map { index ->
            val teamJson = teamsArray.getJSONObject(index)
            val parsed = TeamRepository.parseTeam(teamJson, userSaved = true)
            require(parsed.pokemon.size == 6) { "队伍“${parsed.name}”不是 6 只宝可梦" }
            require(parsed.id.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "队伍 ID 含有不安全字符" }
            require(ids.add(parsed.id)) { "备份包含重复队伍 ID：${parsed.id}" }
            teamJson
        }
        val session = data.optJSONObject("currentBattleSession")?.also { validateSession(it, ids) }
        val preview = data.optJSONObject("currentTeamPreview")?.also {
            require(it.optString("kind") == "TeamPreviewRecognitionResult") { "当前队伍预览结构无效" }
        }
        val pending = data.optJSONObject("pendingOwnTeam")?.also {
            TeamRepository.parseTeam(it, userSaved = true)
        }
        val draft = data.optJSONObject("ownTeamImportDraft")?.also {
            require(it.optString("kind") == "OwnTeamImportDraft") { "我方队伍导入草稿结构无效" }
        }
        return ValidatedBackup(
            savedTeams = teams,
            currentBattleSession = session,
            currentTeamPreview = preview,
            pendingOwnTeam = pending,
            ownTeamImportDraft = draft,
            updateChannel = UpdateChannel.fromStoredValue(data.optString("updateChannel")),
        )
    }

    private fun validateSession(session: JSONObject, teamIds: Set<String>) {
        require(session.optString("kind") == "BattleSession") { "当前对局结构无效" }
        session.getString("sessionId")
        session.getString("createdAt")
        val teamId = session.getString("selectedOwnTeamId")
        require(teamId in teamIds) { "当前对局引用的我方队伍不在备份中" }
        require(session.getJSONArray("opponentTeam").length() == 6) { "当前对局的对方阵容不是 6 只" }
        BattleCalculationState.fromJson(session.optJSONObject("calculationSelection"))
    }

    private fun replaceManagedFiles(context: Context, backup: ValidatedBackup) {
        clearManagedFiles(context)
        val savedDirectory = context.filesDir.resolve("saved-teams").apply { mkdirs() }
        backup.savedTeams.forEach { team ->
            val id = team.getString("savedTeamId")
            savedDirectory.resolve("$id.json").writeUtf8Atomically(team.toString(2))
        }
        val battleDirectory = context.filesDir.resolve("battle-session")
        backup.currentBattleSession?.let {
            battleDirectory.resolve("current-battle-session.json").writeUtf8Atomically(it.toString(2))
        }
        backup.currentTeamPreview?.let {
            battleDirectory.resolve("current-team-preview.json").writeUtf8Atomically(it.toString(2))
        }
        backup.pendingOwnTeam?.let {
            context.filesDir.resolve("pending-own-team.json").writeUtf8Atomically(it.toString(2))
        }
        backup.ownTeamImportDraft?.let {
            context.filesDir.resolve("own-team-import-draft.json").writeUtf8Atomically(it.toString(2))
        }
    }

    private fun snapshotManagedFiles(context: Context): Map<String, ByteArray> = buildMap {
        listOf("saved-teams", "battle-session").forEach { directoryName ->
            val directory = context.filesDir.resolve(directoryName)
            directory.walkTopDown().filter(java.io.File::isFile).forEach { file ->
                put(file.relativeTo(context.filesDir).invariantSeparatorsPath, file.readBytes())
            }
        }
        rootFiles.forEach { name ->
            context.filesDir.resolve(name).takeIf(java.io.File::isFile)?.let { put(name, it.readBytes()) }
        }
    }

    private fun restoreSnapshot(context: Context, snapshot: Map<String, ByteArray>) {
        clearManagedFiles(context)
        snapshot.forEach { (relative, bytes) ->
            val target = context.filesDir.resolve(relative)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
    }

    private fun clearManagedFiles(context: Context) {
        context.filesDir.resolve("saved-teams").deleteRecursively()
        context.filesDir.resolve("battle-session").deleteRecursively()
        rootFiles.forEach { context.filesDir.resolve(it).delete() }
    }
}
