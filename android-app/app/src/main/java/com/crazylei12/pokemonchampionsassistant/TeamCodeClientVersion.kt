package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal fun interface TeamCodeClientVersionResolver {
    @Throws(IOException::class)
    fun resolveCurrentVersion(): String
}

internal interface TeamCodeClientVersionStore {
    fun load(): String?
    fun save(version: String)
}

internal class PokemonChampionsAppStoreVersionResolver : TeamCodeClientVersionResolver {
    override fun resolveCurrentVersion(): String {
        val connection = URL(POKEMON_CHAMPIONS_APP_STORE_LOOKUP).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "PokemonChampionsAssistant/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("App Store lookup returned HTTP $status")
            val bytes = connection.inputStream.use { it.readNBytes(MAX_APP_STORE_RESPONSE_BYTES + 1) }
            if (bytes.size > MAX_APP_STORE_RESPONSE_BYTES) throw IOException("App Store lookup response is too large")
            return parsePokemonChampionsAppStoreVersion(bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }
}

internal class PokemonChampionsClientVersionPreferences(context: Context) : TeamCodeClientVersionStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        TEAM_CODE_VERSION_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    override fun load(): String? = preferences.getString(TEAM_CODE_VERSION_KEY, null)
        ?.takeIf(::isSupportedClientVersionFormat)

    override fun save(version: String) {
        require(isSupportedClientVersionFormat(version)) { "Invalid Pokemon Champions client version" }
        preferences.edit().putString(TEAM_CODE_VERSION_KEY, version).apply()
    }
}

internal fun parsePokemonChampionsAppStoreVersion(text: String): String {
    val root = try {
        JSONObject(text)
    } catch (error: Exception) {
        throw IOException("App Store lookup response is not valid JSON", error)
    }
    val results = root.optJSONArray("results")
        ?: throw IOException("App Store lookup response has no results")
    if (root.optInt("resultCount", -1) != 1 || results.length() != 1) {
        throw IOException("App Store lookup did not return exactly one app")
    }
    val app = results.optJSONObject(0)
        ?: throw IOException("App Store lookup result is invalid")
    if (app.optLong("trackId", -1L) != POKEMON_CHAMPIONS_APP_STORE_ID ||
        app.optString("bundleId") != POKEMON_CHAMPIONS_BUNDLE_ID
    ) {
        throw IOException("App Store lookup returned a different app")
    }
    return app.optString("version").trim().takeIf(::isSupportedClientVersionFormat)
        ?: throw IOException("App Store lookup returned an invalid client version")
}

internal fun isSupportedClientVersionFormat(version: String): Boolean =
    version.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}")) && version.length <= 32

private const val POKEMON_CHAMPIONS_APP_STORE_ID = 6741503079L
private const val POKEMON_CHAMPIONS_BUNDLE_ID = "jp.pokemon.pokemonchampions"
private const val POKEMON_CHAMPIONS_APP_STORE_LOOKUP =
    "https://itunes.apple.com/lookup?id=$POKEMON_CHAMPIONS_APP_STORE_ID&country=us"
private const val TEAM_CODE_VERSION_PREFERENCES = "team_code_client_version"
private const val TEAM_CODE_VERSION_KEY = "last_accepted_version"
private const val MAX_APP_STORE_RESPONSE_BYTES = 256 * 1024
