package com.crazylei12.pokemonchampionsassistant

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.crazylei12.pokemonchampionsassistant.replay.POKEMON_CHAMPIONS_GAME_PACKAGE

class ReplayPermissionActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_MODE = "capture_mode"
        private const val EXTRA_AUDIO_SIGNAL_UNAVAILABLE = "audio_signal_unavailable"

        fun intent(context: Context, mode: CaptureSessionMode): Intent =
            Intent(context, ReplayPermissionActivity::class.java)
                .putExtra(EXTRA_MODE, mode.wireName)

        fun silentFallbackIntent(context: Context, mode: CaptureSessionMode): Intent =
            intent(context, mode).putExtra(EXTRA_AUDIO_SIGNAL_UNAVAILABLE, true)
    }

    private var mode: CaptureSessionMode? = null
    private var resolved = false
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            finishWith(ReplayAudioDecision.WITH_AUDIO)
        } else {
            showSilentRecordingChoice(signalUnavailable = false)
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
        if (intent.getBooleanExtra(EXTRA_AUDIO_SIGNAL_UNAVAILABLE, false)) {
            showSilentRecordingChoice(signalUnavailable = true)
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

    private fun showSilentRecordingChoice(signalUnavailable: Boolean) {
        if (isFinishing || resolved) return
        AlertDialog.Builder(this)
            .setTitle(if (signalUnavailable) "未检测到游戏内部声音" else "未取得游戏声音权限")
            .setMessage(
                if (signalUnavailable) {
                    "开始前的本地 PCM 检测没有收到 Pokémon Champions 声音。可以取消并检查游戏音量，或明确继续录制无声视频；助手不会改用麦克风。"
                } else {
                    "可以取消本次会话，或明确选择继续无声录制。助手不会静默降级，也不会改用麦克风。"
                },
            )
            .setPositiveButton("录制无声视频") { _, _ -> finishWith(ReplayAudioDecision.SILENT) }
            .setNegativeButton("取消") { _, _ -> finishWith(ReplayAudioDecision.CANCEL) }
            .setOnCancelListener { finishWith(ReplayAudioDecision.CANCEL) }
            .show()
    }

    private fun finishWith(decision: ReplayAudioDecision) {
        if (resolved) return
        resolved = true
        if (decision != ReplayAudioDecision.CANCEL) {
            packageManager.getLaunchIntentForPackage(POKEMON_CHAMPIONS_GAME_PACKAGE)?.let { launchIntent ->
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(launchIntent)
            }
        }
        mode?.let { OverlayCaptureService.resolveReplayPermission(this, it, decision) }
        finish()
    }
}
