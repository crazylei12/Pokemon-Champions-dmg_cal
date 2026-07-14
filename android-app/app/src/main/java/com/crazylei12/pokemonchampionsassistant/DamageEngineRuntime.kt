package com.crazylei12.pokemonchampionsassistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.json.JSONTokener

class DamageEngineRuntime(context: Context) {
    var status by mutableStateOf("正在加载离线伤害引擎…")
        private set

    var engineInfo by mutableStateOf("")
        private set

    private var ready = false

    val isReady: Boolean
        get() = ready

    @SuppressLint("SetJavaScriptEnabled")
    val webView = WebView(context.applicationContext).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.allowContentAccess = false
        settings.allowFileAccess = true
        settings.blockNetworkLoads = true
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = request?.url?.scheme != "file"

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(
                    "window.PokemonChampionsDamageEngine && " +
                        "window.PokemonChampionsDamageEngine.getEngineInfo()"
                ) { encoded ->
                    val decoded = decodeJavascriptValue(encoded)
                    ready = decoded.isNotBlank() && decoded != "null"
                    if (ready) {
                        engineInfo = decoded
                        status = "离线伤害引擎已就绪"
                    } else {
                        status = "伤害引擎加载失败"
                    }
                }
            }
        }
        loadUrl("file:///android_asset/engine-host.html")
    }

    fun calculate(requestJson: String, callback: (Result<String>) -> Unit) {
        if (!ready) {
            callback(Result.failure(IllegalStateException(status)))
            return
        }

        val quotedRequest = JSONObject.quote(requestJson)
        val script = "window.PokemonChampionsDamageEngine.calculateDamage($quotedRequest)"
        webView.evaluateJavascript(script) { encoded ->
            runCatching { decodeJavascriptValue(encoded) }
                .onSuccess { callback(Result.success(it)) }
                .onFailure { callback(Result.failure(it)) }
        }
    }

    fun destroy() {
        ready = false
        webView.stopLoading()
        webView.destroy()
    }

    private fun decodeJavascriptValue(encoded: String?): String {
        if (encoded == null || encoded == "null") return ""
        val value = JSONTokener(encoded).nextValue()
        return when (value) {
            is String -> value
            else -> value?.toString().orEmpty()
        }
    }
}
