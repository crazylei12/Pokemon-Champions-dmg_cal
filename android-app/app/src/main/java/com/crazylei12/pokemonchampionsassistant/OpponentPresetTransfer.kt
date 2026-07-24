package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.time.Instant

data class OpponentPresetTransferSummary(
    val imported: Int,
    val added: Int,
    val updated: Int,
    val unchanged: Int,
)

object OpponentPresetTransfer {
    private const val SCHEMA_VERSION = 1
    private const val KIND = "PokemonChampionsOpponentPresetShare"
    private const val MAX_IMPORT_BYTES = 4 * 1024 * 1024

    fun exportTo(context: Context, uri: Uri): Int {
        val presets = OpponentUserPresetStore(
            context.filesDir.resolve(USER_OPPONENT_PRESETS_FILE),
        ).exportRoot()
        val root = buildEnvelope(
            presets = presets,
            exportedAt = Instant.now().toString(),
            appVersion = installedVersion(context).name,
        )
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(root.toString(2))
        } ?: error("无法打开所选导出位置")
        return presets.getJSONArray("presets").length()
    }

    fun importFrom(context: Context, uri: Uri): OpponentPresetTransferSummary {
        val bytes = context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            input.readNBytes(MAX_IMPORT_BYTES + 1)
        } ?: error("无法读取所选配置文件")
        require(bytes.size <= MAX_IMPORT_BYTES) { "配置分享文件超过 4 MB，已拒绝导入" }
        val presets = extractPresets(JSONObject(bytes.toString(Charsets.UTF_8)))
        val result = OpponentUserPresetStore(
            context.filesDir.resolve(USER_OPPONENT_PRESETS_FILE),
        ).mergeFrom(presets)
        return OpponentPresetTransferSummary(
            imported = result.imported,
            added = result.added,
            updated = result.updated,
            unchanged = result.unchanged,
        )
    }

    internal fun buildEnvelope(
        presets: JSONObject,
        exportedAt: String,
        appVersion: String,
    ): JSONObject {
        OpponentUserPresetStore.validateRoot(presets)
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("kind", KIND)
            put("exportedAt", exportedAt)
            put("appVersion", appVersion)
            put("userOpponentPresets", presets)
        }
    }

    internal fun extractPresets(root: JSONObject): JSONObject {
        require(root.optInt("schemaVersion") == SCHEMA_VERSION) { "不支持的宝可梦配置分享版本" }
        require(root.optString("kind") == KIND) { "所选文件不是宝可梦配置分享文件" }
        return root.getJSONObject("userOpponentPresets").also(OpponentUserPresetStore::validateRoot)
    }
}
