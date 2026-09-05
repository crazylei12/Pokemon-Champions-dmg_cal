package com.crazylei12.pokemonchampionsassistant

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.SocketTimeoutException
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
    message: String = "队伍码解析服务暂不可用，请稍后重试",
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
        throw TeamCodeDataException("解析服务返回了无法读取的数据", error)
    }
    if (root.optInt("schemaVersion") != TEAM_CODE_SCHEMA_VERSION ||
        root.optString("kind") != TEAM_CODE_RESPONSE_KIND
    ) {
        throw TeamCodeDataException("解析服务返回的数据版本不受支持")
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

internal fun validateTeamCodeResolverEndpoint(endpointUrl: String, allowLocalHttp: Boolean): URI {
    val uri = runCatching { URI(endpointUrl.trim()) }
        .getOrElse { throw TeamCodeResolverUnavailableException("此构建的队伍码解析服务地址无效", it) }
    val host = uri.host?.lowercase(Locale.ROOT)
        ?: throw TeamCodeResolverUnavailableException("此构建的队伍码解析服务地址无效")
    val isHttps = uri.scheme.equals("https", ignoreCase = true)
    val isLocalDebugHttp = allowLocalHttp && uri.scheme.equals("http", ignoreCase = true) &&
        host in LOCAL_DEBUG_HOSTS
    if (!isHttps && !isLocalDebugHttp) {
        throw TeamCodeResolverUnavailableException("队伍码解析服务必须使用 HTTPS")
    }
    if (uri.userInfo != null || uri.fragment != null) {
        throw TeamCodeResolverUnavailableException("此构建的队伍码解析服务地址无效")
    }
    return uri
}

internal class TeamCodeResolverClient(
    private val endpointUrl: String = BuildConfig.TEAM_CODE_RESOLVER_URL,
    private val allowLocalHttp: Boolean = BuildConfig.DEBUG,
) {
    fun resolve(rawCode: String): ResolvedTeamCode {
        val code = normalizeTeamCode(rawCode)
            ?: throw TeamCodeDataException("队伍码必须是 10 位英文字母或数字")
        if (endpointUrl.isBlank()) {
            throw TeamCodeResolverUnavailableException("此版本尚未配置队伍码解析服务")
        }
        val endpoint = validateTeamCodeResolverEndpoint(endpointUrl, allowLocalHttp)
        val connection = try {
            URL(endpoint.toASCIIString()).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw TeamCodeResolverUnavailableException(cause = error)
        }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("User-Agent", "PokemonChampionsAssistant/${BuildConfig.VERSION_NAME}")
            val request = JSONObject()
                .put("schemaVersion", TEAM_CODE_SCHEMA_VERSION)
                .put("code", code)
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(request.size)
            connection.outputStream.use { it.write(request) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1)
                if (bytes.size > MAX_RESPONSE_BYTES) throw TeamCodeDataException("解析服务返回的数据过大")
                bytes.toString(Charsets.UTF_8)
            }.orEmpty()
            return when (status) {
                HttpURLConnection.HTTP_OK -> parseTeamCodeResponse(body, code)
                HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_GONE, 422 -> throw TeamCodeNotFoundException()
                HttpURLConnection.HTTP_BAD_REQUEST -> throw TeamCodeDataException("队伍码格式无效")
                else -> throw TeamCodeResolverUnavailableException(
                    if (status == 429) "队伍码解析服务请求过于频繁，请稍后重试"
                    else "队伍码解析服务暂不可用（HTTP $status）",
                )
            }
        } catch (error: TeamCodeImportException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw TeamCodeResolverUnavailableException("队伍码解析服务响应超时，请稍后重试", error)
        } catch (error: IOException) {
            throw TeamCodeResolverUnavailableException(cause = error)
        } finally {
            connection.disconnect()
        }
    }
}

internal class TeamCodeImportWorker(
    private val client: TeamCodeResolverClient = TeamCodeResolverClient(),
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)

    fun resolve(code: String, callback: (Result<ResolvedTeamCode>) -> Unit) {
        executor.execute {
            val result = runCatching { client.resolve(code) }
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
private const val MAX_RESPONSE_BYTES = 512 * 1024
private val TEAM_CODE_PATTERN = Regex("[A-Z0-9]{10}")
private val LOCAL_DEBUG_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2", "10.0.3.2")
