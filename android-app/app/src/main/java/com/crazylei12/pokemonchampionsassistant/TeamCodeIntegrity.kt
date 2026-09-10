package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.IntegrityTokenRequest
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal fun interface TeamCodeIntegrityProvider {
    fun tokenFor(csrfToken: String): String
}

internal fun teamCodeIntegrityNonce(csrfToken: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(csrfToken.toByteArray(Charsets.UTF_8))

/** Generates this application's genuine proof, bound to the current login challenge. */
internal class PlayTeamCodeIntegrityProvider(context: Context) : TeamCodeIntegrityProvider {
    private val manager = IntegrityManagerFactory.create(context.applicationContext)

    // Public project identifier used by the official login verifier, not a credential.
    override fun tokenFor(csrfToken: String): String = try {
        Tasks.await(manager.requestIntegrityToken(
            IntegrityTokenRequest.builder()
                .setCloudProjectNumber(171315076361L)
                .setNonce(teamCodeIntegrityNonce(csrfToken))
                .build(),
        ), 60, TimeUnit.SECONDS).token()
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw TeamCodeResolverUnavailableException("队伍码查询已中断，请重试", error)
    } catch (error: TimeoutException) {
        throw TeamCodeResolverUnavailableException("设备完整性服务响应超时，请检查网络后重试", error)
    } catch (error: ExecutionException) {
        val code = (error.cause as? IntegrityServiceException)?.errorCode
        val message = when (code) {
            -2, -6, -14, -15 -> "队伍码直连需要可用的 Google Play 商店和服务，请安装或更新后重试"
            -3 -> "无法连接 Google Play 完整性服务，请检查网络后重试"
            -8 -> "完整性请求过于频繁，请稍等一分钟再查询"
            else -> "无法取得设备完整性证明，请检查 Google Play 服务后重试"
        }
        throw TeamCodeResolverUnavailableException(message + (code?.let { "（代码 $it）" } ?: ""), error)
    }
}
