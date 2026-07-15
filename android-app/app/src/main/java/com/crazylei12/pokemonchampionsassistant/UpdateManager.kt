package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.Executors

internal object AppUpdateConfig {
    const val REPOSITORY = "crazylei12/Pokemon-Champions-dmg_cal"
    const val RELEASES_PAGE_URL = "https://github.com/$REPOSITORY/releases"
    const val RELEASES_API_URL = "https://api.github.com/repos/$REPOSITORY/releases"
}

internal enum class UpdateChannel(val storedValue: String) {
    STABLE("stable"),
    PREVIEW("preview");

    companion object {
        fun fromStoredValue(value: String?): UpdateChannel = entries
            .firstOrNull { it.storedValue == value }
            ?: STABLE
    }
}

internal data class InstalledVersion(
    val name: String,
    val code: Long,
)

internal data class ReleaseInfo(
    val tagName: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val prerelease: Boolean,
    val draft: Boolean = false,
)

internal sealed interface UpdateCheckResult {
    data class Available(val release: ReleaseInfo) : UpdateCheckResult
    data class Current(val release: ReleaseInfo) : UpdateCheckResult
    data class NoRelease(val message: String) : UpdateCheckResult
    data class Failure(val message: String) : UpdateCheckResult
}

internal object AppUpdatePreferences {
    private const val PREFERENCES_NAME = "app-update-settings"
    private const val CHANNEL_KEY = "channel"

    fun loadChannel(context: Context): UpdateChannel = UpdateChannel.fromStoredValue(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(CHANNEL_KEY, null)
    )

    fun saveChannel(context: Context, channel: UpdateChannel) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(CHANNEL_KEY, channel.storedValue)
            .apply()
    }
}

internal fun installedVersion(context: Context): InstalledVersion {
    val info = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(0),
    )
    return InstalledVersion(
        name = info.versionName ?: "0.0.0",
        code = info.longVersionCode,
    )
}

internal object ReleaseVersion {
    private val pattern = Regex(
        "^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$"
    )

    private data class SemanticVersion(
        val major: Long,
        val minor: Long,
        val patch: Long,
        val prerelease: List<String>,
    )

    fun compare(left: String, right: String): Int? {
        val leftVersion = parse(left) ?: return null
        val rightVersion = parse(right) ?: return null
        compareValues(leftVersion.major, rightVersion.major).takeIf { it != 0 }?.let { return it }
        compareValues(leftVersion.minor, rightVersion.minor).takeIf { it != 0 }?.let { return it }
        compareValues(leftVersion.patch, rightVersion.patch).takeIf { it != 0 }?.let { return it }

        if (leftVersion.prerelease.isEmpty() && rightVersion.prerelease.isEmpty()) return 0
        if (leftVersion.prerelease.isEmpty()) return 1
        if (rightVersion.prerelease.isEmpty()) return -1

        val maxIdentifiers = maxOf(leftVersion.prerelease.size, rightVersion.prerelease.size)
        for (index in 0 until maxIdentifiers) {
            if (index >= leftVersion.prerelease.size) return -1
            if (index >= rightVersion.prerelease.size) return 1
            comparePrereleaseIdentifier(
                leftVersion.prerelease[index],
                rightVersion.prerelease[index],
            ).takeIf { it != 0 }?.let { return it }
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current)?.let { it > 0 } == true

    fun isValid(value: String): Boolean = parse(value) != null

    private fun parse(value: String): SemanticVersion? {
        val match = pattern.matchEntire(value.trim()) ?: return null
        return runCatching {
            SemanticVersion(
                major = match.groupValues[1].toLong(),
                minor = match.groupValues[2].toLong(),
                patch = match.groupValues[3].toLong(),
                prerelease = match.groupValues[4]
                    .takeIf(String::isNotBlank)
                    ?.split('.')
                    .orEmpty(),
            )
        }.getOrNull()
    }

    private fun comparePrereleaseIdentifier(left: String, right: String): Int {
        val leftNumber = left.toLongOrNull()
        val rightNumber = right.toLongOrNull()
        return when {
            leftNumber != null && rightNumber != null -> compareValues(leftNumber, rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
    }
}

internal object ReleaseSelector {
    fun newest(releases: List<ReleaseInfo>, channel: UpdateChannel): ReleaseInfo? = releases
        .asSequence()
        .filterNot(ReleaseInfo::draft)
        .filter { channel == UpdateChannel.PREVIEW || !it.prerelease }
        .filter { ReleaseVersion.isValid(it.tagName) }
        .maxWithOrNull(Comparator { left, right ->
            ReleaseVersion.compare(left.tagName, right.tagName) ?: 0
        })
}

internal class AppUpdateChecker {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = GitHubReleaseClient()

    fun check(currentVersion: String, channel: UpdateChannel, callback: (UpdateCheckResult) -> Unit) {
        executor.execute {
            val result = client.check(currentVersion, channel)
            mainHandler.post { callback(result) }
        }
    }

    fun close() {
        executor.shutdownNow()
    }
}

private class GitHubReleaseClient {
    fun check(currentVersion: String, channel: UpdateChannel): UpdateCheckResult = try {
        val releases = fetchReleases(channel)
        val newest = ReleaseSelector.newest(releases, channel)
            ?: return UpdateCheckResult.NoRelease(
                if (channel == UpdateChannel.STABLE) {
                    "稳定版频道还没有可用 Release。"
                } else {
                    "预览版频道还没有可用 Release。"
                }
            )
        if (ReleaseVersion.isNewer(newest.tagName, currentVersion)) {
            UpdateCheckResult.Available(newest)
        } else {
            UpdateCheckResult.Current(newest)
        }
    } catch (_: UnknownHostException) {
        UpdateCheckResult.Failure("无法连接 GitHub，请检查网络后重试。")
    } catch (_: SocketTimeoutException) {
        UpdateCheckResult.Failure("连接 GitHub 超时，请稍后重试。")
    } catch (error: GitHubHttpException) {
        when (error.statusCode) {
            404 -> UpdateCheckResult.NoRelease("当前频道还没有可用 Release，或更新源尚未开放。")
            403, 429 -> UpdateCheckResult.Failure("GitHub 更新接口访问频率受限，请稍后再试。")
            else -> UpdateCheckResult.Failure("检查更新失败（GitHub ${error.statusCode}）。")
        }
    } catch (error: Exception) {
        UpdateCheckResult.Failure("检查更新失败：${error.message ?: "未知错误"}")
    }

    private fun fetchReleases(channel: UpdateChannel): List<ReleaseInfo> = when (channel) {
        UpdateChannel.STABLE -> listOf(
            parseRelease(requestJson("${AppUpdateConfig.RELEASES_API_URL}/latest"))
        )
        UpdateChannel.PREVIEW -> {
            val response = JSONArray(requestText("${AppUpdateConfig.RELEASES_API_URL}?per_page=20"))
            (0 until response.length()).mapNotNull { index ->
                parseRelease(response.getJSONObject(index)).takeUnless(ReleaseInfo::draft)
            }
        }
    }

    private fun parseRelease(release: JSONObject): ReleaseInfo {
        val assets = release.optJSONArray("assets") ?: JSONArray()
        val apkUrl = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .filter { asset ->
                asset.optString("name").endsWith(".apk", ignoreCase = true) ||
                    asset.optString("content_type") == "application/vnd.android.package-archive"
            }
            .sortedWith(compareBy<JSONObject> { apkPriority(it.optString("name")) }
                .thenBy { it.optString("name").lowercase() })
            .firstOrNull()
            ?.optString("browser_download_url")
            ?.takeIf(String::isNotBlank)

        val tagName = release.getString("tag_name")
        return ReleaseInfo(
            tagName = tagName,
            title = release.optString("name").ifBlank { tagName },
            notes = release.optString("body"),
            pageUrl = release.optString("html_url").ifBlank { AppUpdateConfig.RELEASES_PAGE_URL },
            apkUrl = apkUrl,
            prerelease = release.optBoolean("prerelease"),
            draft = release.optBoolean("draft"),
        )
    }

    private fun apkPriority(name: String): Int {
        val normalized = name.lowercase()
        return when {
            "arm64" in normalized || "arm64-v8a" in normalized -> 0
            "universal" in normalized -> 1
            else -> 2
        }
    }

    private fun requestJson(url: String): JSONObject = JSONObject(requestText(url))

    private fun requestText(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Pokemon-Champions-Assistant-Android")
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw GitHubHttpException(statusCode)
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

private class GitHubHttpException(val statusCode: Int) : Exception("GitHub HTTP $statusCode")
