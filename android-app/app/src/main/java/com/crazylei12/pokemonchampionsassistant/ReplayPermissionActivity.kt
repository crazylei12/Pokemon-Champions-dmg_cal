package com.crazylei12.pokemonchampionsassistant

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class ReplayPermissionActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_MODE = "capture_mode"

        fun intent(context: Context, mode: CaptureSessionMode): Intent =
            Intent(context, ReplayPermissionActivity::class.java)
                .putExtra(EXTRA_MODE, mode.wireName)
    }

    private var mode: CaptureSessionMode? = null
    private var resolved = false
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            finishWith(ReplayAudioDecision.WITH_AUDIO)
        } else {
            showSilentRecordingChoice()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = CaptureSessionMode.fromWireName(intent.getStringExtra(EXTRA_MODE))
            ?.takeIf(CaptureSessionMode::includesReplay)
        if (mode == null) {
            finish()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finishWith(ReplayAudioDecision.WITH_AUDIO)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("允许捕获游戏内部声音")
            .setMessage(
                "Android 会把播放声音捕获显示为录音权限。助手只按 Pokémon Champions 的应用 UID 捕获播放声音，不会创建麦克风输入。",
            )
            .setPositiveButton("继续授权") { _, _ ->
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setNegativeButton("取消") { _, _ -> finishWith(ReplayAudioDecision.CANCEL) }
            .setOnCancelListener { finishWith(ReplayAudioDecision.CANCEL) }
            .show()
    }

    private fun showSilentRecordingChoice() {
        if (isFinishing || resolved) return
        AlertDialog.Builder(this)
            .setTitle("未取得游戏声音权限")
            .setMessage("可以取消本次会话，或明确选择继续无声录制。助手不会静默降级，也不会改用麦克风。")
            .setPositiveButton("录制无声视频") { _, _ -> finishWith(ReplayAudioDecision.SILENT) }
            .setNegativeButton("取消") { _, _ -> finishWith(ReplayAudioDecision.CANCEL) }
            .setOnCancelListener { finishWith(ReplayAudioDecision.CANCEL) }
            .show()
    }

    private fun finishWith(decision: ReplayAudioDecision) {
        if (resolved) return
        resolved = true
        mode?.let { OverlayCaptureService.resolveReplayPermission(this, it, decision) }
        finish()
    }
}
