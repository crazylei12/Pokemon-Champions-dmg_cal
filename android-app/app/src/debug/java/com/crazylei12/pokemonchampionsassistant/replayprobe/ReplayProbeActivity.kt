package com.crazylei12.pokemonchampionsassistant.replayprobe

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File

class ReplayProbeActivity : ComponentActivity() {
    private lateinit var status: TextView
    private var pendingScenario: ReplayProbeScenario? = null

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) requestProjection() else showStatus("RECORD_AUDIO permission was denied")
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val scenario = pendingScenario
        val data = result.data
        if (scenario != null && result.resultCode == Activity.RESULT_OK && data != null) {
            ReplayProbeService.start(this, result.resultCode, data, scenario)
            showStatus("Probe started: ${scenario.wireName}. Keep Pokemon Champions in the requested state for 10 seconds.")
        } else {
            showStatus("Screen-capture authorization was cancelled")
        }
    }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val path = intent?.getStringExtra(ReplayProbeService.EXTRA_REPORT_PATH)
            val error = intent?.getStringExtra(ReplayProbeService.EXTRA_ERROR)
            showStatus(if (error == null) "Probe finished: $path" else "Probe failed: $error\nReport: $path")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Replay Phase 0 Probe"

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            text = "Debug package: $packageName\nChoose a scenario, then select single app -> Pokemon Champions."
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 48, 32, 48)
            setBackgroundColor(Color.rgb(18, 24, 36))
            addView(status, matchWidth())
            addView(button("1. Game audible") { begin(ReplayProbeScenario.AUDIBLE_GAME) }, matchWidth(top = 32))
            addView(button("2. Game muted") { begin(ReplayProbeScenario.MUTED_GAME) }, matchWidth(top = 20))
            addView(button("3. Other-app 440 Hz tone") { begin(ReplayProbeScenario.OTHER_APP_TONE) }, matchWidth(top = 20))
            addView(button("Open overlay permission") { openOverlayPermission() }, matchWidth(top = 32))
            addView(button("Show latest JSON") { showLatestReport() }, matchWidth(top = 20))
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            resultReceiver,
            IntentFilter(ReplayProbeService.ACTION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(resultReceiver) }
        super.onStop()
    }

    private fun begin(scenario: ReplayProbeScenario) {
        pendingScenario = scenario
        if (!Settings.canDrawOverlays(this)) {
            showStatus("Overlay permission is required for the isolation marker. Grant it, then tap the scenario again.")
            openOverlayPermission()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestProjection()
        }
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(captureIntent)
    }

    private fun openOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun showLatestReport() {
        val latest = File(filesDir, "replay-probe/latest.json")
        showStatus(if (latest.isFile) latest.readText() else "No replay probe report exists yet")
    }

    private fun showStatus(message: String) {
        status.text = message
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun matchWidth(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }
}
