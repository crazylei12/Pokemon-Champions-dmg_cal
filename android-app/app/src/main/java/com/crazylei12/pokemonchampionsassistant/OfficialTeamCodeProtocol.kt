package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal fun interface TeamCodeLookup {
    fun resolve(rawCode: String): ResolvedTeamCode
}

internal data class OfficialHttpRequest(
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

internal data class OfficialHttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    fun firstHeader(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()

    fun allHeaders(name: String): List<String> = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        .orEmpty()
}

internal fun interface OfficialTeamCodeTransport {
    @Throws(IOException::class)
    fun post(request: OfficialHttpRequest): OfficialHttpResponse
}

internal class PokemonChampionsOfficialTransport : OfficialTeamCodeTransport {
    override fun post(request: OfficialHttpRequest): OfficialHttpResponse {
        require(request.path in OFFICIAL_PATHS) { "Unsupported official API path" }
        val connection = URL("$OFFICIAL_ORIGIN${request.path}").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            request.headers.forEach(connection::setRequestProperty)
            val requestBytes = request.body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(requestBytes.size)
            connection.outputStream.use { it.write(requestBytes) }

            val status = connection.responseCode
            val responseHeaders = connection.headerFields.entries
                .mapNotNull { (name, values) -> name?.let { it to values.orEmpty() } }
                .toMap()
            val rawStream = if (status in 200..299) connection.inputStream else connection.errorStream
            val decodedStream = when (responseHeaders.firstHeaderValue("Content-Encoding")?.lowercase()) {
                "gzip" -> rawStream?.let(::GZIPInputStream)
                "deflate" -> rawStream?.let(::InflaterInputStream)
                else -> rawStream
            }
            val body = decodedStream?.use { input ->
                val bytes = input.readNBytes(MAX_OFFICIAL_RESPONSE_BYTES + 1)
                if (bytes.size > MAX_OFFICIAL_RESPONSE_BYTES) {
                    throw TeamCodeDataException("官方队伍数据响应过大")
                }
                bytes.toString(Charsets.UTF_8)
            }.orEmpty()
            return OfficialHttpResponse(status, responseHeaders, body)
        } finally {
            connection.disconnect()
        }
    }
}

internal data class DecryptedApiPayload(val text: String, val userDataVersion: Long)

internal object TeamCodeProtocolCrypto {
    fun createAuthRequestHash(pmc: String, dummy: String, randomValue: Int): String {
        val digest = md5("${pmc.length}#${md5(pmc)}#$randomValue#$dummy")
        return digest + randomValue.toString(16).padStart(4, '0')
    }

    fun createApiRequestHash(
        pmc: String,
        dummy: String,
        token: String,
        sessionId: String,
        randomValue: Int,
    ): String {
        val inner = sha1("$sessionId@$token@$dummy")
        val digest = md5("${sha1(pmc)}-$randomValue-$inner")
        return randomValue.toString(16).padStart(4, '0') + digest
    }

    fun encryptAuthPayload(text: String, dummy: String, bucket: Long): String {
        val first = md5(dummy)
        val combined = first + md5("$bucket\$$first")
        val offset = (bucket % 32).toInt()
        return encrypt(text, combined.rotateLeft(offset), md5("$dummy;$bucket"))
    }

    fun decryptAuthPayload(pmc: String, dummy: String, candidateBuckets: Iterable<Long>): String {
        var lastError: Exception? = null
        candidateBuckets.distinct().forEach { bucket ->
            try {
                val first = md5(dummy)
                val combined = first + md5("$bucket\$$first")
                return decrypt(pmc, combined.rotateLeft((bucket % 32).toInt()), md5("$dummy;$bucket"))
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw TeamCodeDataException("无法解密官方登录响应", lastError)
    }

    fun encryptApiPayload(
        text: String,
        dummy: String,
        token: String,
        sessionId: String,
        userDataVersion: Long,
    ): String {
        val first = md5("$sessionId\$$token\$$dummy")
        val offset = (dummy.toLong() % 32).toInt()
        val key = first + md5("$userDataVersion${first.rotateLeft(offset)}")
        val iv = md5("$sessionId=$dummy=$token")
        return encrypt(text, key, iv)
    }

    fun decryptApiPayload(
        pmc: String,
        dummy: String,
        token: String,
        sessionId: String,
        requestUserDataVersion: Long,
    ): DecryptedApiPayload {
        val first = md5("$sessionId\$$token\$$dummy")
        val offset = (dummy.toLong() % 32).toInt()
        val rotated = first.rotateLeft(offset)
        val iv = md5("$sessionId=$dummy=$token")
        var lastError: Exception? = null
        listOf(requestUserDataVersion + 1, requestUserDataVersion).forEach { candidateVersion ->
            try {
                val key = first + md5("$candidateVersion$rotated")
                return DecryptedApiPayload(decrypt(pmc, key, iv), candidateVersion)
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw TeamCodeDataException("无法解密官方队伍响应", lastError)
    }

    private fun encrypt(text: String, keyHex: String, ivHex: String): String {
        val zipped = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            output.toByteArray()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyHex.hexBytes(), "AES"),
            IvParameterSpec(ivHex.hexBytes()),
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(zipped))
    }

    private fun decrypt(pmc: String, keyHex: String, ivHex: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyHex.hexBytes(), "AES"),
            IvParameterSpec(ivHex.hexBytes()),
        )
        val zipped = cipher.doFinal(Base64.getDecoder().decode(pmc))
        return GZIPInputStream(ByteArrayInputStream(zipped)).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun md5(value: String): String = digest("MD5", value)
    private fun sha1(value: String): String = digest("SHA-1", value)

    private fun digest(algorithm: String, value: String): String = MessageDigest.getInstance(algorithm)
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal class TeamCodeEntityMap private constructor(
    val masterDataVersion: Int,
    private val species: Map<String, String>,
    private val moves: Map<Int, String>,
    private val abilities: Map<Int, String>,
    private val items: Map<Int, String>,
    private val natures: List<String>,
) {
    fun mapOfficialTeam(code: String, payload: JSONObject): ResolvedTeamCode {
        val team = payload.optJSONObject("tng") ?: throw TeamCodeNotFoundException()
        val membersJson = team.optJSONArray("mem") ?: throw TeamCodeNotFoundException()
        if (membersJson.length() != 6) throw TeamCodeNotFoundException()

        val itemByIndex = mutableMapOf<Int, Int>()
        team.optJSONArray("itms")?.let { itemRows ->
            for (index in 0 until itemRows.length()) {
                itemRows.optJSONObject(index)?.let { row ->
                    val memberIndex = row.optInt("idx", -1)
                    val itemNumber = row.optInt("i", 0)
                    if (memberIndex in 0..5 && itemNumber > 0) itemByIndex[memberIndex] = itemNumber
                }
            }
        }

        val members = (0 until membersJson.length()).map { index ->
            val source = membersJson.optJSONObject(index)
                ?: throw TeamCodeDataException("官方队伍的第 ${index + 1} 个成员格式无效")
            val points = listOf("b9", "ba", "bb", "bd", "be", "bc").map { key ->
                source.requiredInt(key, index).also { value ->
                    if (value !in 0..32) {
                        throw TeamCodeDataException("官方队伍的第 ${index + 1} 个成员能力点无效")
                    }
                }
            }
            if (points.sum() > 66) {
                throw TeamCodeDataException("官方队伍的第 ${index + 1} 个成员能力点总和无效")
            }
            val moveNumbers = source.optJSONArray("bf")?.positiveInts().orEmpty()
            if (moveNumbers.size !in 1..4 || moveNumbers.distinct().size != moveNumbers.size) {
                throw TeamCodeDataException("官方队伍的第 ${index + 1} 个成员招式无效")
            }
            val pokemonNumber = source.requiredInt("b0", index)
            val formNumber = source.requiredInt("b1", index)
            val natureNumber = source.requiredInt("b8", index)
            val itemNumber = itemByIndex[index]
            ResolvedTeamCodeMember(
                speciesId = required(species, "$pokemonNumber:$formNumber", "宝可梦形态"),
                level = 50,
                gender = when (source.requiredInt("b2", index)) {
                    0 -> "male"
                    1 -> "female"
                    2 -> "genderless"
                    else -> "unknown"
                },
                natureId = natures.getOrNull(natureNumber)
                    ?: throw TeamCodeDataException("内置队伍码数据不认识性格编号 $natureNumber"),
                abilityId = required(abilities, source.requiredInt("b5", index), "特性"),
                itemId = itemNumber?.let { required(items, it, "道具") },
                statPoints = StatFields(
                    hp = points[0].toString(),
                    atk = points[1].toString(),
                    def = points[2].toString(),
                    spa = points[3].toString(),
                    spd = points[4].toString(),
                    spe = points[5].toString(),
                ),
                moveIds = moveNumbers.map { required(moves, it, "招式") },
            )
        }
        val trainerName = team.optString("unam").trim().takeIf(String::isNotBlank)?.also {
            if (it.length > 80) throw TeamCodeDataException("官方队伍的训练家名称过长")
        }
        return ResolvedTeamCode(code = code, trainerName = trainerName, members = members)
    }

    private fun <K> required(map: Map<K, String>, key: K, label: String): String = map[key]
        ?: throw TeamCodeDataException("内置队伍码数据不认识${label}编号 $key；可能需要更新 App 数据")

    companion object {
        fun fromContext(context: Context): TeamCodeEntityMap = try {
            context.assets.open(TEAM_CODE_ENTITY_MAP_ASSET).bufferedReader(Charsets.UTF_8).use {
                fromJson(it.readText())
            }
        } catch (error: TeamCodeImportException) {
            throw error
        } catch (error: Exception) {
            throw TeamCodeDataException("无法读取内置队伍码数据", error)
        }

        fun fromJson(text: String): TeamCodeEntityMap {
            val root = try {
                JSONObject(text)
            } catch (error: Exception) {
                throw TeamCodeDataException("内置队伍码数据格式无效", error)
            }
            if (root.optInt("schemaVersion") != 1 || root.optInt("masterDataVersion") != 17) {
                throw TeamCodeDataException("内置队伍码数据版本不受支持")
            }
            val species = root.requiredStringMap("species")
            if (species.size != 361) throw TeamCodeDataException("内置队伍码形态数据不完整")
            val naturesJson = root.optJSONArray("natures")
                ?: throw TeamCodeDataException("内置队伍码数据缺少性格表")
            val natures = (0 until naturesJson.length()).map { index ->
                naturesJson.optString(index).takeIf(String::isNotBlank)
                    ?: throw TeamCodeDataException("内置队伍码性格表无效")
            }
            if (natures.size != 25) throw TeamCodeDataException("内置队伍码性格表不完整")
            return TeamCodeEntityMap(
                masterDataVersion = 17,
                species = species,
                moves = root.requiredIntStringMap("moves"),
                abilities = root.requiredIntStringMap("abilities"),
                items = root.requiredIntStringMap("items"),
                natures = natures,
            )
        }
    }
}

internal class PokemonChampionsOfficialTeamCodeClient(
    private val identityUuid: String,
    private val entityMap: TeamCodeEntityMap,
    private val transport: OfficialTeamCodeTransport = PokemonChampionsOfficialTransport(),
    private val deviceName: String,
    private val osName: String,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
    private val randomInt: (origin: Int, bound: Int) -> Int = secureRandomInt(),
) : TeamCodeLookup {
    init {
        require(identityUuid.matches(Regex("[A-Za-z0-9]{42}"))) {
            "Pokemon Champions guest identity is invalid"
        }
    }

    override fun resolve(rawCode: String): ResolvedTeamCode {
        val code = normalizeTeamCode(rawCode)
            ?: throw TeamCodeDataException("队伍码必须是 10 位英文字母或数字")
        return try {
            resolveWithFreshSession(code)
        } catch (error: TeamCodeImportException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw TeamCodeResolverUnavailableException("Pokemon Champions 官方服务响应超时，请稍后重试", error)
        } catch (error: IOException) {
            throw TeamCodeResolverUnavailableException("无法连接 Pokemon Champions 官方服务，请检查网络后重试", error)
        } catch (error: Exception) {
            throw TeamCodeDataException("无法读取官方队伍数据", error)
        }
    }

    private fun resolveWithFreshSession(code: String): ResolvedTeamCode {
        val ids = newClientIds()
        val cookies = CookieJar()
        val globals = GlobalParameters(masterDataVersion = entityMap.masterDataVersion)

        val tokenDummy = nextDummy()
        val tokenBucket = currentBucket()
        val tokenPmp = ids.putInto(JSONObject()
            .put("uuid", identityUuid)
            .put("hash", md5("$tokenBucket#$tokenDummy#$identityUuid"))
            .put("skipHashValidation", 0))
        val tokenResponse = authRequest(
            path = "/auth/get-token",
            pmp = tokenPmp,
            dummy = tokenDummy,
            bucket = tokenBucket,
            globals = globals,
            cookies = cookies,
        )
        requireSuccessfulApiCode(tokenResponse.outer, "获取查询令牌")
        globals.updateFrom(tokenResponse.outer)
        val tokenPayload = decryptAuthEnvelope(tokenResponse)
        val token = tokenPayload.optString("token").takeIf(String::isNotBlank)
            ?: throw TeamCodeDataException("官方登录响应缺少查询令牌")

        val loginPmp = ids.putInto(JSONObject()
            .put("uuid", identityUuid)
            .put("token", token)
            .put("aga", 1)
            .put("bga", 1)
            .put("dv", deviceName)
            .put("os", osName)
            .put("udVer", 0))
        val loginResponse = authRequest(
            path = "/auth/login",
            pmp = loginPmp,
            dummy = nextDummy(),
            bucket = currentBucket(),
            globals = globals,
            cookies = cookies,
        )
        requireSuccessfulApiCode(loginResponse.outer, "建立查询会话")
        val login = decryptAuthEnvelope(loginResponse)
        val sessionId = login.optString("sid").takeIf(String::isNotBlank)
            ?: throw TeamCodeDataException("官方登录响应缺少会话编号")
        val userDataVersion = login.optJSONObject("UD")?.optLong("udVer", -1L)
            ?.takeIf { it >= 0 }
            ?: throw TeamCodeDataException("官方登录响应缺少数据版本")
        val apiIds = ids.copy(
            cbid = login.optString("cbid").takeIf(String::isNotBlank) ?: ids.cbid,
            rsid = login.optString("rsid").takeIf(String::isNotBlank) ?: ids.rsid,
            rdid = login.optString("rdid").takeIf(String::isNotBlank) ?: ids.rdid,
        )

        val requestDummy = nextDummy()
        val searchPmp = apiIds.putInto(JSONObject()
            .put("code", code)
            .put("udVer", userDataVersion))
        val pmc = TeamCodeProtocolCrypto.encryptApiPayload(
            text = searchPmp.toString(),
            dummy = requestDummy,
            token = token,
            sessionId = sessionId,
            userDataVersion = userDataVersion,
        )
        val randomHashValue = nextHashRandom()
        val searchOuter = globals.putInto(JSONObject().put("pmp", searchPmp))
            .put("hsh", TeamCodeProtocolCrypto.createApiRequestHash(
                pmc = pmc,
                dummy = requestDummy,
                token = token,
                sessionId = sessionId,
                randomValue = randomHashValue,
            ))
            .put("sid", sessionId)
            .put("pmc", pmc)
            .put("asNV", OFFICIAL_ASNV)
        val searchResponse = postEnvelope(
            path = "/api/trainingcode/search",
            outer = searchOuter,
            dummy = requestDummy,
            cookies = cookies,
        )
        if (searchResponse.status in MISSING_CODE_HTTP_STATUSES) throw TeamCodeNotFoundException()
        requireHttpSuccess(searchResponse.status)
        val responseOuter = parseObject(searchResponse.body, "官方队伍响应")
        val responseCode = responseOuter.requiredApiCode()
        if (responseCode in MISSING_CODE_API_CODES) throw TeamCodeNotFoundException()
        if (responseCode != 0) {
            throw TeamCodeResolverUnavailableException("Pokemon Champions 官方服务暂时无法查询队伍（代码 $responseCode）")
        }
        val encryptedPayload = responseOuter.optString("pmc").takeIf(String::isNotBlank)
            ?: throw TeamCodeNotFoundException()
        val responseDummy = searchResponse.firstHeader("X-PKB-DMY-VAL")
            ?: throw TeamCodeDataException("官方队伍响应缺少解密参数")
        val decrypted = TeamCodeProtocolCrypto.decryptApiPayload(
            pmc = encryptedPayload,
            dummy = responseDummy,
            token = token,
            sessionId = sessionId,
            requestUserDataVersion = userDataVersion,
        )
        return entityMap.mapOfficialTeam(code, parseObject(decrypted.text, "官方队伍内容"))
    }

    private fun authRequest(
        path: String,
        pmp: JSONObject,
        dummy: String,
        bucket: Long,
        globals: GlobalParameters,
        cookies: CookieJar,
    ): AuthEnvelope {
        val pmc = TeamCodeProtocolCrypto.encryptAuthPayload(pmp.toString(), dummy, bucket)
        val outer = globals.putInto(JSONObject().put("pmp", pmp))
            .put("hsh", TeamCodeProtocolCrypto.createAuthRequestHash(pmc, dummy, nextHashRandom()))
            .put("pmc", pmc)
            .put("asNV", OFFICIAL_ASNV)
        val response = postEnvelope(path, outer, dummy, cookies)
        requireHttpSuccess(response.status)
        return AuthEnvelope(
            outer = parseObject(response.body, "官方登录响应"),
            response = response,
        )
    }

    private fun decryptAuthEnvelope(envelope: AuthEnvelope): JSONObject {
        val encrypted = envelope.outer.optString("pmc").takeIf(String::isNotBlank)
            ?: throw TeamCodeDataException("官方登录响应缺少加密内容")
        val dummy = envelope.response.firstHeader("X-PKB-DMY-VAL")
            ?: throw TeamCodeDataException("官方登录响应缺少解密参数")
        val serverTimeRaw = envelope.response.firstHeader("X-PKB-S-TIME")?.toLongOrNull()
            ?: throw TeamCodeDataException("官方登录响应缺少服务器时间")
        val serverTimeSeconds = if (serverTimeRaw > 100_000_000_000L) serverTimeRaw / 1_000 else serverTimeRaw
        val serverBucket = serverTimeSeconds / 600
        val buckets = (-2L..2L).map { serverBucket + it } + currentBucket()
        val text = TeamCodeProtocolCrypto.decryptAuthPayload(encrypted, dummy, buckets)
        return parseObject(text, "官方登录内容")
    }

    private fun postEnvelope(
        path: String,
        outer: JSONObject,
        dummy: String,
        cookies: CookieJar,
    ): OfficialHttpResponse {
        val headers = linkedMapOf(
            "User-Agent" to OFFICIAL_USER_AGENT,
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate",
            "X-PKB-DMY-VAL" to dummy,
            "X-PKB-ENCRYPT" to "1",
            "X-PKB-HASH-CHECK" to "1",
            "Content-Type" to "application/json; charset=utf-8",
            "X-Unity-Version" to OFFICIAL_UNITY_VERSION,
        )
        cookies.headerValue().takeIf(String::isNotBlank)?.let { headers["Cookie"] = it }
        val response = transport.post(OfficialHttpRequest(path, headers, outer.toString()))
        cookies.update(response.allHeaders("Set-Cookie"))
        return response
    }

    private fun requireSuccessfulApiCode(outer: JSONObject, step: String) {
        val code = outer.requiredApiCode()
        if (code != 0) {
            throw TeamCodeResolverUnavailableException("Pokemon Champions 官方服务无法$step（代码 $code）")
        }
    }

    private fun requireHttpSuccess(status: Int) {
        if (status !in 200..299) {
            throw TeamCodeResolverUnavailableException("Pokemon Champions 官方服务暂不可用（HTTP $status）")
        }
    }

    private fun currentBucket(): Long = nowSeconds() / 600
    private fun nextDummy(): String = (6_000_000_000_000_000_000L + randomInt(0, 1_000_000_000)).toString()
    private fun nextHashRandom(): Int = randomInt(0x1000, 0xffff)

    private fun newClientIds(): ClientIds {
        val nowMillis = nowSeconds() * 1_000
        val zone = TimeZone.getDefault()
        val totalOffset = zone.getOffset(nowMillis) / 60_000
        val daylightOffset = (zone.getOffset(nowMillis) - zone.rawOffset) / 60_000
        return ClientIds(
            cbid = "cb${randomAlnum(18)}",
            rsid = "rs${randomAlnum(18)}",
            rdid = "rd${randomAlnum(18)}",
            timezoneOffsetMinutes = totalOffset,
            daylightOffsetMinutes = daylightOffset,
        )
    }

    private fun randomAlnum(length: Int): String = buildString(length) {
        repeat(length) { append(ALNUM[randomInt(0, ALNUM.length)]) }
    }

    companion object {
        fun fromContext(context: Context): PokemonChampionsOfficialTeamCodeClient =
            PokemonChampionsOfficialTeamCodeClient(
                identityUuid = BuildConfig.TEAM_CODE_GUEST_UUID,
                entityMap = TeamCodeEntityMap.fromContext(context.applicationContext),
                deviceName = Build.MANUFACTURER.uppercase() + " " + Build.MODEL,
                osName = "Android OS ${Build.VERSION.RELEASE} / API-${Build.VERSION.SDK_INT}",
            )

        private fun secureRandomInt(): (Int, Int) -> Int {
            val random = SecureRandom()
            return { origin, bound -> origin + random.nextInt(bound - origin) }
        }
    }
}

private data class AuthEnvelope(
    val outer: JSONObject,
    val response: OfficialHttpResponse,
)

private data class ClientIds(
    val cbid: String,
    val rsid: String,
    val rdid: String,
    val timezoneOffsetMinutes: Int,
    val daylightOffsetMinutes: Int,
) {
    fun putInto(target: JSONObject): JSONObject = target
        .put("cbid", cbid)
        .put("rsid", rsid)
        .put("rdid", rdid)
        .put("tzoff", timezoneOffsetMinutes)
        .put("dstoff", daylightOffsetMinutes)
}

private class GlobalParameters(private val masterDataVersion: Int) {
    private var timeMasterVersion: Any = 0
    private var playerParameterVersion: Any = 0
    private var webViewVersion: Any = 0
    private var informationHash: Any = "0"

    fun putInto(target: JSONObject): JSONObject = target
        .put("clV", OFFICIAL_CLIENT_VERSION)
        .put("mdV", masterDataVersion)
        .put("pla", 3)
        .put("tmV", timeMasterVersion)
        .put("ppV", playerParameterVersion)
        .put("wvV", webViewVersion)
        .put("inH", informationHash)
        .put("lng", 80)
        .put("cou", 502)

    fun updateFrom(source: JSONObject) {
        source.nonNull("tmV")?.let { timeMasterVersion = it }
        source.nonNull("ppV")?.let { playerParameterVersion = it }
        source.nonNull("wvV")?.let { webViewVersion = it }
        source.nonNull("inH")?.let { informationHash = it }
    }
}

private class CookieJar {
    private val values = linkedMapOf<String, String>()

    fun update(setCookieHeaders: List<String>) {
        setCookieHeaders.forEach { header ->
            val pair = header.substringBefore(';')
            val separator = pair.indexOf('=')
            if (separator > 0) values[pair.substring(0, separator)] = pair.substring(separator + 1)
        }
    }

    fun headerValue(): String = values.entries.joinToString("; ") { (name, value) -> "$name=$value" }
}

private fun JSONObject.requiredApiCode(): Int {
    if (!has("code") || isNull("code")) throw TeamCodeDataException("官方响应缺少状态码")
    return optInt("code", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        ?: throw TeamCodeDataException("官方响应状态码无效")
}

private fun JSONObject.requiredInt(key: String, memberIndex: Int): Int {
    if (!has(key) || isNull(key)) {
        throw TeamCodeDataException("官方队伍的第 ${memberIndex + 1} 个成员缺少 $key")
    }
    return optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        ?: throw TeamCodeDataException("官方队伍的第 ${memberIndex + 1} 个成员字段 $key 无效")
}

private fun JSONObject.nonNull(key: String): Any? = opt(key)?.takeUnless { it === JSONObject.NULL }

private fun JSONObject.requiredStringMap(key: String): Map<String, String> {
    val source = optJSONObject(key) ?: throw TeamCodeDataException("内置队伍码数据缺少 $key")
    return source.keys().asSequence().associateWith { source.optString(it) }
        .also { if (it.isEmpty() || it.values.any(String::isBlank)) throw TeamCodeDataException("内置队伍码数据表 $key 无效") }
}

private fun JSONObject.requiredIntStringMap(key: String): Map<Int, String> = requiredStringMap(key)
    .mapKeys { (number, _) -> number.toIntOrNull() ?: throw TeamCodeDataException("内置队伍码数据表 $key 编号无效") }

private fun JSONArray.positiveInts(): List<Int> = (0 until length())
    .map { optInt(it, 0) }
    .filter { it > 0 }

private fun Map<String, List<String>>.firstHeaderValue(name: String): String? = entries
    .firstOrNull { it.key.equals(name, ignoreCase = true) }
    ?.value
    ?.firstOrNull()

private fun String.rotateLeft(offset: Int): String = substring(offset) + substring(0, offset)

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0) { "Hex input has an odd length" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun parseObject(text: String, label: String): JSONObject = try {
    JSONObject(text)
} catch (error: Exception) {
    throw TeamCodeDataException("$label 格式无效", error)
}

private fun md5(value: String): String = MessageDigest.getInstance("MD5")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private const val OFFICIAL_ORIGIN = "https://api.app.pokemonchampions.jp"
private const val OFFICIAL_CLIENT_VERSION = "1.1.5"
private const val OFFICIAL_UNITY_VERSION = "6000.0.74f1"
private const val OFFICIAL_USER_AGENT = "UnityPlayer/6000.0.74f1 (UnityWebRequest/1.0, libcurl/8.10.1-DEV)"
private const val OFFICIAL_ASNV = "1ASz1456308236"
private const val TEAM_CODE_ENTITY_MAP_ASSET = "team-code/champions-entity-map.v17.json"
private const val MAX_OFFICIAL_RESPONSE_BYTES = 2 * 1024 * 1024
private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
private val OFFICIAL_PATHS = setOf("/auth/get-token", "/auth/login", "/api/trainingcode/search")
private val MISSING_CODE_HTTP_STATUSES = setOf(404, 410, 422)
private val MISSING_CODE_API_CODES = setOf(31502)
