package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ResolvedTeamCodeMember(
    val speciesId: String,
    val level: Int,
    val gender: String?,
    val natureId: String,
    val abilityId: String,
    val itemId: String?,
    val statPoints: StatFields,
    val moveIds: List<String>,
)

data class ResolvedTeamCode(
    val code: String,
    val trainerName: String?,
    val members: List<ResolvedTeamCodeMember>,
)

open class TeamCodeImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

class TeamCodeNotFoundException(message: String = "没有找到这个公开队伍码；请检查是否输入正确或已经失效") :
    TeamCodeImportException(message)

class TeamCodeResolverUnavailableException(
    message: String = "Pokemon Champions 官方服务暂不可用，请稍后重试",
    cause: Throwable? = null,
) : TeamCodeImportException(message, cause)

class TeamCodeDataException(message: String, cause: Throwable? = null) : TeamCodeImportException(message, cause)

internal fun normalizeTeamCode(raw: String): String? {
    val normalized = raw.trim().replace(Regex("\\s+"), "").uppercase(Locale.ROOT)
    return normalized.takeIf { TEAM_CODE_PATTERN.matches(it) }
}

internal fun parseTeamCodeResponse(body: String, requestedCode: String): ResolvedTeamCode {
    val requested = normalizeTeamCode(requestedCode)
        ?: throw TeamCodeDataException("队伍码必须是 10 位英文字母或数字")
    val root = try {
        JSONObject(body)
    } catch (error: Exception) {
        throw TeamCodeDataException("队伍查询返回了无法读取的数据", error)
    }
    if (root.optInt("schemaVersion") != TEAM_CODE_SCHEMA_VERSION ||
        root.optString("kind") != TEAM_CODE_RESPONSE_KIND
    ) {
        throw TeamCodeDataException("队伍查询返回的数据版本不受支持")
    }
    val code = normalizeTeamCode(root.optString("code"))
        ?: throw TeamCodeDataException("解析结果缺少有效队伍码")
    if (code != requested) throw TeamCodeDataException("解析结果与请求的队伍码不一致")
    val trainerName = root.optString("trainerName")
        .trim()
        .takeIf(String::isNotBlank)
        ?.also { if (it.length > 80) throw TeamCodeDataException("解析结果中的训练家名称过长") }
    val membersJson = root.optJSONArray("members")
        ?: throw TeamCodeDataException("解析结果缺少队伍成员")
    if (membersJson.length() != 6) throw TeamCodeDataException("解析结果必须恰好包含 6 只宝可梦")

    val members = (0 until membersJson.length()).map { index ->
        val position = index + 1
        val member = membersJson.optJSONObject(index)
            ?: throw TeamCodeDataException("第 $position 个队伍成员格式无效")
        fun requiredId(key: String, label: String): String {
            val value = member.optString(key).trim()
            if (value.isBlank() || value.length > 80) {
                throw TeamCodeDataException("第 $position 只宝可梦缺少有效$label")
            }
            return value
        }
        val pointsJson = member.optJSONObject("statPoints")
            ?: throw TeamCodeDataException("第 $position 只宝可梦缺少能力点")
        fun point(key: String): String {
            if (!pointsJson.has(key)) throw TeamCodeDataException("第 $position 只宝可梦缺少 $key 能力点")
            val value = pointsJson.optInt(key, -1)
            if (value !in 0..32) throw TeamCodeDataException("第 $position 只宝可梦的 $key 能力点无效")
            return value.toString()
        }
        val statPoints = StatFields(
            hp = point("hp"),
            atk = point("atk"),
            def = point("def"),
            spa = point("spa"),
            spd = point("spd"),
            spe = point("spe"),
        )
        if (statPoints.asMap().values.sumOf { it.toInt() } > 66) {
            throw TeamCodeDataException("第 $position 只宝可梦的能力点总和超过 66")
        }
        val moveIdsJson = member.optJSONArray("moveIds")
            ?: throw TeamCodeDataException("第 $position 只宝可梦缺少招式")
        if (moveIdsJson.length() !in 1..4) {
            throw TeamCodeDataException("第 $position 只宝可梦必须包含 1–4 个招式")
        }
        val moveIds = (0 until moveIdsJson.length()).map { moveIndex ->
            moveIdsJson.optString(moveIndex).trim().takeIf { it.isNotBlank() && it.length <= 80 }
                ?: throw TeamCodeDataException("第 $position 只宝可梦的第 ${moveIndex + 1} 个招式无效")
        }
        if (moveIds.distinctBy(::normalizeShowdownId).size != moveIds.size) {
            throw TeamCodeDataException("第 $position 只宝可梦包含重复招式")
        }
        val level = member.optInt("level", 50)
        if (level != 50) throw TeamCodeDataException("第 $position 只宝可梦不是 Champions 对战使用的 50 级配置")
        val gender = member.optString("gender").trim().takeIf(String::isNotBlank)
        if (gender != null && gender.length > 24) {
            throw TeamCodeDataException("第 $position 只宝可梦的性别字段无效")
        }
        ResolvedTeamCodeMember(
            speciesId = requiredId("speciesId", "宝可梦 ID"),
            level = level,
            gender = gender,
            natureId = requiredId("natureId", "性格"),
            abilityId = requiredId("abilityId", "特性"),
            itemId = member.optString("itemId").trim().takeIf(String::isNotBlank),
            statPoints = statPoints,
            moveIds = moveIds,
        )
    }
    return ResolvedTeamCode(code, trainerName, members)
}

internal class TeamCodeImportWorker(
    private val lookup: TeamCodeLookup,
) : AutoCloseable {
    constructor(context: Context) : this(PokemonChampionsOfficialTeamCodeClient.fromContext(context))

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)

    fun resolve(code: String, callback: (Result<ResolvedTeamCode>) -> Unit) {
        executor.execute {
            val result = runCatching { lookup.resolve(code) }
            if (!closed.get()) mainHandler.post {
                if (!closed.get()) callback(result)
            }
        }
    }

    override fun close() {
        closed.set(true)
        executor.shutdownNow()
    }
}

private const val TEAM_CODE_SCHEMA_VERSION = 1
private const val TEAM_CODE_RESPONSE_KIND = "PokemonChampionsPublicTeam"
private val TEAM_CODE_PATTERN = Regex("[A-Z0-9]{10}")
