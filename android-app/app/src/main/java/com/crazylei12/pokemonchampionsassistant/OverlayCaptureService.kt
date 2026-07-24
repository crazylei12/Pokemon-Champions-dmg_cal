package com.crazylei12.pokemonchampionsassistant

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.util.Consumer
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.crazylei12.pokemonchampionsassistant.replay.ReplayFinalizeResult
import com.crazylei12.pokemonchampionsassistant.replay.ReplayMediaStore
import com.crazylei12.pokemonchampionsassistant.replay.ReplayPreparationResult
import com.crazylei12.pokemonchampionsassistant.replay.ReplayRecorder
import com.crazylei12.pokemonchampionsassistant.replay.SavedReplay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object CaptureUiState {
    val message = mutableStateOf("对局助手尚未启动")
    val running = mutableStateOf(false)
    val sessionState = mutableStateOf(CaptureSessionState.IDLE)
    val replayState = mutableStateOf(ReplaySessionState.IDLE)
    val teamLibraryRevision = mutableStateOf(0)
    val ownTeamDraftRevision = mutableStateOf(0)
}

class OverlayCaptureService : Service() {
    companion object {
        private const val ACTION_START = "com.crazylei12.pokemonchampionsassistant.START_CAPTURE"
        private const val ACTION_STOP = "com.crazylei12.pokemonchampionsassistant.STOP_CAPTURE"
        private const val ACTION_DEBUG_RECOGNIZE_TEAM_PREVIEW =
            "com.crazylei12.pokemonchampionsassistant.DEBUG_RECOGNIZE_TEAM_PREVIEW"
        private const val ACTION_OPEN_OWN_TEAM_CORRECTION =
            "com.crazylei12.pokemonchampionsassistant.OPEN_OWN_TEAM_CORRECTION"
        private const val ACTION_RESOLVE_REPLAY_PERMISSION =
            "com.crazylei12.pokemonchampionsassistant.RESOLVE_REPLAY_PERMISSION"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_AUDIO_DECISION = "audio_decision"
        private const val EXTRA_ASSISTANT_MODE = "assistant_mode"
        private const val CHANNEL_ID = "own_team_capture"
        private const val NOTIFICATION_ID = 4102
        private const val LOG_TAG = "OverlayCaptureService"
        private const val CAPTURE_RESIZE_DEBOUNCE_MS = 150L

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            mode: BattleAssistantMode = BattleAssistantMode.STANDARD,
        ) {
            val intent = Intent(context, OverlayCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_ASSISTANT_MODE, mode.wireName)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayCaptureService::class.java).setAction(ACTION_STOP))
        }

        fun requestOwnTeamCorrection(context: Context) {
            context.startService(
                Intent(context, OverlayCaptureService::class.java).setAction(ACTION_OPEN_OWN_TEAM_CORRECTION),
            )
        }

        fun resolveReplayPermission(
            context: Context,
            decision: ReplayAudioDecision,
        ) {
            context.startService(
                Intent(context, OverlayCaptureService::class.java).apply {
                    action = ACTION_RESOLVE_REPLAY_PERMISSION
                    putExtra(EXTRA_AUDIO_DECISION, decision.wireName)
                },
            )
        }
    }

    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private lateinit var overlayWindowContext: Context
    private lateinit var displayManager: DisplayManager
    private lateinit var windowManager: WindowManager
    private lateinit var safeArea: OverlaySafeAreaProvider
    private var overlayDisplayId = Display.DEFAULT_DISPLAY
    private var windowInfoTracker: WindowInfoTrackerCallbackAdapter? = null
    private val overlayComponentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            scheduleOverlaySafeAreaRefresh()
        }

        override fun onLowMemory() = Unit
    }
    private val windowLayoutInfoListener = Consumer<WindowLayoutInfo> { layoutInfo ->
        if (destroyed || !::safeArea.isInitialized) return@Consumer
        safeArea.updateWindowLayoutInfo(layoutInfo)
        scheduleOverlaySafeAreaRefresh()
    }
    private val overlayDisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != overlayDisplayId || destroyed) return
            scheduleCaptureResize(reason = "displayChanged")
            scheduleOverlaySafeAreaRefresh()
        }
    }
    private val sessionStateMachine = CaptureSessionStateMachine()
    private var recognitionFeatureHost: RecognitionFeatureHost? = null
    private var replayRecorder: ReplayRecorder? = null
    private var replayIsolationMarker: View? = null
    private var replayIsolationTimeout: Runnable? = null
    private var replayTicker: Runnable? = null
    private var replayPreparationGeneration = 0L
    private var replayStartRequestedAtElapsedMs = 0L
    private var replayPreparationStartedAtElapsedMs = 0L
    private var replayRecognitionGeneration = 0L
    private var replayMenuCapturePending = false
    private var stopServiceAfterReplayFinalize = false
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var teamNamePrompt: View? = null
    private var teamNamePromptParams: WindowManager.LayoutParams? = null
    private var activeToast: Toast? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private var captureBufferSpec: CaptureBufferSpec? = null
    private var pendingCaptureResize: Runnable? = null
    private var pendingCapturedContentSize: Pair<Int, Int>? = null
    private var pendingFrameCapture: CancelableDelayedTask? = null
    @Volatile private var captureGeneration = 0L
    private val bitmapLock = Any()
    private var latestBitmap: Bitmap? = null
    private var frozenMenuBitmap: Bitmap? = null
    private var frozenMenuFrameCopyMs = 0.0
    @Volatile private var frameTrackingEnabled = true
    @Volatile private var recognizing = false
    @Volatile private var destroyed = false
    private var assistantMode = BattleAssistantMode.STANDARD

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(DisplayManager::class.java)
        val overlayDisplay = displayManager
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?: error("Default display is unavailable for application overlays")
        overlayDisplayId = overlayDisplay.displayId
        overlayWindowContext = createDisplayContext(overlayDisplay).createWindowContext(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            null,
        )
        windowManager = overlayWindowContext.getSystemService(WindowManager::class.java)
        safeArea = OverlaySafeAreaProvider(overlayWindowContext)
        overlayWindowContext.registerComponentCallbacks(overlayComponentCallbacks)
        displayManager.registerDisplayListener(overlayDisplayListener, mainHandler)
        WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(this)).also { tracker ->
            windowInfoTracker = tracker
            runCatching {
                tracker.addWindowLayoutInfoListener(
                    overlayWindowContext,
                    mainExecutor,
                    windowLayoutInfoListener,
                )
            }.onFailure { error ->
                Log.w(LOG_TAG, "Fold posture updates are unavailable; window metrics remain active", error)
            }
        }
        createNotificationChannel()
        syncSessionUiState()
        thread(name = "replay-media-cleanup") {
            runCatching {
                val mediaStore = ReplayMediaStore(applicationContext)
                mediaStore.cleanupStalePending() to mediaStore.migrateLegacyReplaysToGallery()
            }
                .onSuccess { (removed, migrated) ->
                    if (removed > 0) Log.i(LOG_TAG, "Removed $removed stale pending replay item(s)")
                    if (migrated > 0) Log.i(LOG_TAG, "Moved $migrated replay item(s) into the gallery path")
                }
                .onFailure { Log.w(LOG_TAG, "Could not maintain replay MediaStore entries", it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestSessionStop()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESOLVE_REPLAY_PERMISSION) {
            handleReplayPermissionResult(intent)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_OPEN_OWN_TEAM_CORRECTION) {
            openOwnTeamCorrection()
            return START_NOT_STICKY
        }
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent?.action == ACTION_DEBUG_RECOGNIZE_TEAM_PREVIEW) {
            if (sessionStateMachine.state != CaptureSessionState.RUNNING) {
                publish("对局助手尚未准备好，请返回 App 重新启动")
            } else {
                captureAndRecognizeTeamPreview(useFrozenMenuFrame = false)
            }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        if (sessionStateMachine.state != CaptureSessionState.IDLE) {
            publish("已有对局助手会话，请先结束后再重新授权")
            return START_NOT_STICKY
        }
        assistantMode = BattleAssistantMode.fromWireName(intent.getStringExtra(EXTRA_ASSISTANT_MODE))
        startProjectionForeground()
        if (!Settings.canDrawOverlays(this)) {
            publish("请先在 App 中授予悬浮窗权限")
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            publish("屏幕共享授权已失效，请返回 App 重新启动")
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching {
            sessionStateMachine.beginProjectionPreparation()
            syncSessionUiState()
            prepareProjection(resultCode, resultData)
            sessionStateMachine.projectionReady()
            ensureRecognitionFeatureHost()
            createCaptureSurface()
            sessionStateMachine.markVirtualDisplayCreated()
            sessionStateMachine.started()
            syncSessionUiState()
        }
            .onSuccess {
                showAssistantEntry()
                CaptureUiState.running.value = true
                updateBubbleAppearance()
                val controlSurface = if (assistantMode.usesFloatingBubble) "悬浮菜单" else "HUD"
                updateProjectionNotification("对局助手已就绪；录屏可从${controlSurface}独立开始")
                publish(
                    if (assistantMode.usesFloatingBubble) {
                        "对局助手已就绪；打开 Pokémon Champions 后点击悬浮按钮"
                    } else {
                        "HUD 对局助手已就绪；请在 HUD 中使用“再战”识别双方阵容"
                    },
                )
            }
            .onFailure {
                Log.e(LOG_TAG, "Could not start capture projection", it)
                sessionStateMachine.fail()
                syncSessionUiState()
                publish("对局助手启动失败，请返回 App 后重试")
                stopSelf()
            }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scheduleCaptureResize(reason = "configurationChanged")
        scheduleOverlaySafeAreaRefresh()
    }

    private fun startProjectionForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = buildProjectionNotification(
            contentText = "正在准备投屏授权",
            contentIntent = openApp,
            extraAction = null,
            stopIntent = stop,
        )
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildProjectionNotification(
        contentText: String,
        contentIntent: PendingIntent,
        extraAction: Pair<String, PendingIntent>?,
        stopIntent: PendingIntent,
    ): android.app.Notification = android.app.Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("Pokémon Champions 对局助手")
        .setContentText(contentText)
        .setContentIntent(contentIntent)
        .apply {
            extraAction?.let { (label, pendingIntent) ->
                addAction(android.R.drawable.ic_menu_view, label, pendingIntent)
            }
        }
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "结束助手", stopIntent)
        .setOngoing(true)
        .build()

    private fun updateProjectionNotification(
        contentText: String,
        continuationIntent: PendingIntent? = null,
    ) {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = buildProjectionNotification(
            contentText = contentText,
            contentIntent = continuationIntent ?: openApp,
            extraAction = continuationIntent?.let { "继续录屏授权" to it },
            stopIntent = stop,
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun requestReplayStart() {
        if (sessionStateMachine.state != CaptureSessionState.RUNNING) {
            toast("对局助手尚未准备好")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            toast("录屏首发仅支持 Android 16；当前设备仍可使用识别和伤害功能")
            return
        }
        val audioPermissionGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!sessionStateMachine.requestReplayStart(audioPermissionGranted)) {
            toast("录屏已经开始或正在准备")
            return
        }
        replayStartRequestedAtElapsedMs = SystemClock.elapsedRealtime()
        syncSessionUiState()
        updateBubbleAppearance()
        if (sessionStateMachine.replayState == ReplaySessionState.AWAITING_AUDIO_PERMISSION) {
            requestReplayAudioPermission()
        } else {
            prepareReplaySession()
        }
    }

    private fun requestReplayAudioPermission() {
        val permissionIntent = ReplayPermissionActivity.intent(this).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        val continuation = PendingIntent.getActivity(
            this,
            20,
            ReplayPermissionActivity.intent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        updateProjectionNotification("录屏等待游戏内部声音授权", continuation)
        publish("请完成游戏内部声音授权；如果权限页未打开，请点击通知中的“继续录屏授权”")
        runCatching { startActivity(permissionIntent) }
            .onFailure { Log.w(LOG_TAG, "Overlay could not open replay permission activity", it) }
    }

    private fun requestSilentReplayFallback() {
        val permissionIntent = ReplayPermissionActivity.silentFallbackIntent(this).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        val continuation = PendingIntent.getActivity(
            this,
            40,
            ReplayPermissionActivity.silentFallbackIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        updateProjectionNotification("未检测到游戏内部声音；等待用户选择", continuation)
        publish("未检测到 Pokémon Champions 内部声音；请选择取消或明确继续无声录制")
        runCatching { startActivity(permissionIntent) }
            .onFailure { Log.w(LOG_TAG, "Overlay could not open silent replay fallback", it) }
    }

    private fun handleReplayPermissionResult(intent: Intent) {
        val decision = ReplayAudioDecision.fromWireName(intent.getStringExtra(EXTRA_AUDIO_DECISION))
        val permissionState = sessionStateMachine.replayState == ReplaySessionState.AWAITING_AUDIO_PERMISSION
        val fallbackState = sessionStateMachine.replayState == ReplaySessionState.AWAITING_AUDIO_FALLBACK
        if (decision == null || (!permissionState && !fallbackState)) {
            Log.w(LOG_TAG, "Ignoring stale or invalid replay permission result")
            // A permission Activity can outlive a session that was stopped from the
            // notification. Its late result may create a fresh service instance; do not
            // leave that IDLE instance running without a projection or foreground state.
            if (sessionStateMachine.state == CaptureSessionState.IDLE) stopSelf()
            return
        }
        if (permissionState &&
            decision == ReplayAudioDecision.WITH_AUDIO &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            publish("系统未授予游戏声音权限，请重新选择")
            requestReplayAudioPermission()
            return
        }
        if (fallbackState) {
            if (decision == ReplayAudioDecision.WITH_AUDIO) {
                Log.w(LOG_TAG, "Ignoring invalid with-audio fallback result")
                requestSilentReplayFallback()
                return
            }
            sessionStateMachine.resolveAudioFallback(decision)
        } else {
            sessionStateMachine.resolveAudioPermission(decision)
        }
        syncSessionUiState()
        if (decision == ReplayAudioDecision.CANCEL) {
            updateBubbleAppearance()
            updateProjectionNotification("对局助手继续运行；录屏未开始")
            publish("已取消录屏；对局助手继续运行")
        } else {
            prepareReplaySession()
        }
    }

    private fun prepareReplaySession() {
        check(sessionStateMachine.replayState == ReplaySessionState.STARTING)
        val activeProjection = projection ?: run {
            failReplayStart("MediaProjection 会话已经失效", null)
            return
        }
        val initialSpec = captureBufferSpec ?: run {
            failReplayStart("当前游戏画面尚未准备好", null)
            return
        }
        val withAudio = sessionStateMachine.audioDecision == ReplayAudioDecision.WITH_AUDIO
        val generation = ++replayPreparationGeneration
        replayPreparationStartedAtElapsedMs = SystemClock.elapsedRealtime()
        var recorderRef: ReplayRecorder? = null
        val recorder = ReplayRecorder(
            context = this,
            projection = activeProjection,
            initialSpec = initialSpec,
            requestAudio = withAudio,
            onRuntimeFailure = { error ->
                mainHandler.post {
                    if (destroyed) return@post
                    if (generation != replayPreparationGeneration || replayRecorder !== recorderRef) {
                        Log.w(LOG_TAG, "Ignoring stale replay runtime failure", error)
                        return@post
                    }
                    Log.e(LOG_TAG, "Replay runtime failed", error)
                    when (replayRuntimeFailureAction(sessionStateMachine.replayState)) {
                        ReplayRuntimeFailureAction.ABORT_START ->
                            failReplayStart("录屏准备过程中发生异常", error)
                        ReplayRuntimeFailureAction.FINALIZE_RUNNING_REPLAY -> {
                            CaptureUiState.message.value = "录屏运行异常，正在收尾"
                            requestReplayStop()
                        }
                        ReplayRuntimeFailureAction.IGNORE -> Log.w(
                            LOG_TAG,
                            "Ignoring replay runtime failure in ${sessionStateMachine.replayState}",
                            error,
                        )
                    }
                }
            },
        )
        recorderRef = recorder
        replayRecorder?.close()
        replayRecorder = recorder
        CaptureUiState.message.value = if (withAudio) {
            "正在检测 Pokémon Champions 内部声音…"
        } else {
            "正在准备无声视频编码器…"
        }
        updateProjectionNotification(CaptureUiState.message.value)
        thread(name = "replay-prepare") {
            val result = recorder.prepare()
            mainHandler.post {
                if (
                    destroyed || generation != replayPreparationGeneration ||
                    sessionStateMachine.replayState != ReplaySessionState.STARTING ||
                    replayRecorder !== recorder
                ) {
                    recorder.close()
                    return@post
                }
                when (result) {
                    is ReplayPreparationResult.AudioUnavailable -> {
                        recorder.close()
                        replayRecorder = null
                        sessionStateMachine.audioSignalUnavailable()
                        syncSessionUiState()
                        updateBubbleAppearance()
                        requestSilentReplayFallback()
                    }
                    is ReplayPreparationResult.Failed ->
                        failReplayStart("录屏准备失败", result.error)
                    is ReplayPreparationResult.Ready ->
                        beginReplayIsolationCheck(recorder, result, initialSpec)
                }
            }
        }
    }

    private fun beginReplayIsolationCheck(
        recorder: ReplayRecorder,
        preparation: ReplayPreparationResult.Ready,
        initialSpec: CaptureBufferSpec,
    ) {
        runCatching {
            showReplayIsolationMarker()
            recorder.startIsolationProbe { passed ->
                mainHandler.post { completeReplayIsolationCheck(recorder, passed, timedOut = false) }
            }
            switchToReplayCaptureSurface(
                surface = preparation.captureSurface,
                spec = initialSpec,
            ) { switchResult ->
                switchResult.onSuccess {
                    val timeout = Runnable {
                        replayIsolationTimeout = null
                        recorder.cancelIsolationProbe()
                        completeReplayIsolationCheck(recorder, passed = false, timedOut = true)
                    }
                    replayIsolationTimeout = timeout
                    mainHandler.postDelayed(timeout, 8_000L)
                    updateProjectionNotification("正在确认单应用画面隔离")
                    CaptureUiState.message.value = "正在确认悬浮层不会进入回放…"
                    Log.i(
                        LOG_TAG,
                        "Replay pipeline prepared: codec=${preparation.videoCodecName}, " +
                            "profile=${preparation.videoProfile.displayLabel}, " +
                            "audio=${preparation.hasAudio}, channels=${preparation.audioChannelCount}, " +
                            "gameUid=${preparation.gameUid}, spec=$initialSpec",
                    )
                }.onFailure { error ->
                    failReplayStart("无法切换到录屏捕获 Surface", error)
                }
            }
        }.onFailure { error ->
            failReplayStart("无法创建录屏捕获 Surface", error)
        }
    }

    private fun completeReplayIsolationCheck(
        recorder: ReplayRecorder,
        passed: Boolean,
        timedOut: Boolean,
    ) {
        if (
            destroyed || replayRecorder !== recorder ||
            sessionStateMachine.replayState != ReplaySessionState.STARTING
        ) return
        replayIsolationTimeout?.let(mainHandler::removeCallbacks)
        replayIsolationTimeout = null
        hideReplayIsolationMarker()
        if (!passed) {
            CaptureUiState.message.value = if (timedOut) {
                "未取得画面隔离检测帧，已拒绝开始录屏"
            } else {
                "检测到助手悬浮层进入捕获画面；请重新授权并选择 Pokémon Champions 单个应用"
            }
            updateProjectionNotification(CaptureUiState.message.value)
            failReplayStart(CaptureUiState.message.value, null)
            return
        }
        runCatching {
            val startInfo = recorder.start()
            sessionStateMachine.replayStarted()
            syncSessionUiState()
            startReplayTicker()
            updateBubbleAppearance()
            startInfo
        }.onSuccess {
            val now = SystemClock.elapsedRealtime()
            val requestToRunningMs = if (replayStartRequestedAtElapsedMs > 0L) {
                now - replayStartRequestedAtElapsedMs
            } else {
                -1L
            }
            val preparationToRunningMs = if (replayPreparationStartedAtElapsedMs > 0L) {
                now - replayPreparationStartedAtElapsedMs
            } else {
                -1L
            }
            Log.i(
                LOG_TAG,
                "Replay start complete: requestToRunningMs=$requestToRunningMs, " +
                    "preparationToRunningMs=$preparationToRunningMs, profile=${it.videoProfile.displayLabel}, " +
                    "audioChannels=${it.audioChannelCount}",
            )
            val audioLabel = if (sessionStateMachine.audioDecision == ReplayAudioDecision.WITH_AUDIO) {
                "游戏内部声音"
            } else {
                "无声"
            }
            publish("录屏已开始：${it.videoProfile.displayLabel} / $audioLabel")
        }.onFailure { error ->
            failReplayStart("录屏编码器无法启动", error)
        }
    }

    private fun failReplayStart(message: String, error: Throwable?) {
        error?.let { Log.e(LOG_TAG, message, it) }
        replayPreparationGeneration += 1L
        replayIsolationTimeout?.let(mainHandler::removeCallbacks)
        replayIsolationTimeout = null
        hideReplayIsolationMarker()
        replayRecorder?.close()
        replayRecorder = null
        replayPreparationStartedAtElapsedMs = 0L
        if (
            sessionStateMachine.replayState in setOf(
                ReplaySessionState.AWAITING_AUDIO_PERMISSION,
                ReplaySessionState.AWAITING_AUDIO_FALLBACK,
                ReplaySessionState.STARTING,
            )
        ) {
            sessionStateMachine.abortReplayStart()
        }
        restoreRecognitionCaptureSurface { restoreResult ->
            restoreResult.onFailure { restoreError ->
                Log.e(LOG_TAG, "Could not restore recognition after replay startup failure", restoreError)
            }
            syncSessionUiState()
            updateBubbleAppearance()
            updateProjectionNotification("对局助手继续运行；录屏未开始")
            publish("$message；对局助手继续运行")
        }
    }

    private fun ensureRecognitionFeatureHost(): RecognitionFeatureHost {
        recognitionFeatureHost?.let { return it }
        val host = RecognitionFeatureHost(
            context = overlayWindowContext,
            windowManager = windowManager,
            safeArea = safeArea,
            publish = ::publish,
            onBattleOverlayVisible = { visible ->
                setBubbleWindowPresent(!visible)
                if (!visible && projection == null) stopSelf()
            },
            onCorrectionOverlayVisible = { visible ->
                setBubbleWindowPresent(!visible)
            },
            onOwnTeamSaved = { saved ->
                CaptureUiState.ownTeamDraftRevision.value += 1
                CaptureUiState.teamLibraryRevision.value += 1
                publish(saved.message)
            },
            shouldAutoOpenDirectHud = { assistantMode.autoOpenDirectHud },
            // The direct HUD has no PopupMenu phase and therefore no frozen menu frame.
            // Wait for a clean live frame after the HUD windows are dismissed instead.
            onRecognizeTeamPreview = { captureAndRecognizeTeamPreview(useFrozenMenuFrame = false) },
            onRecognizeOwnTeam = ::captureAndRecognizeOwnTeam,
            recordingState = ::directHudRecordingState,
            onToggleRecording = ::toggleReplayFromDirectHud,
        )
        recognitionFeatureHost = host
        Log.i(LOG_TAG, "RecognitionFeatureHost initialized")
        return host
    }

    private fun syncSessionUiState() {
        CaptureUiState.sessionState.value = sessionStateMachine.state
        CaptureUiState.replayState.value = sessionStateMachine.replayState
        CaptureUiState.running.value = sessionStateMachine.state in setOf(
            CaptureSessionState.PREPARING_PROJECTION,
            CaptureSessionState.STARTING,
            CaptureSessionState.RUNNING,
            CaptureSessionState.STOPPING,
        )
        recognitionFeatureHost?.battleOverlayController?.onRecordingStateChanged()
    }

    private fun directHudRecordingState(): BattleDirectHudRecordingState = when (sessionStateMachine.replayState) {
        ReplaySessionState.IDLE -> BattleDirectHudRecordingState.IDLE
        ReplaySessionState.RUNNING -> BattleDirectHudRecordingState.RUNNING
        ReplaySessionState.STOPPING -> BattleDirectHudRecordingState.STOPPING
        ReplaySessionState.AWAITING_AUDIO_PERMISSION,
        ReplaySessionState.AWAITING_AUDIO_FALLBACK,
        ReplaySessionState.STARTING -> BattleDirectHudRecordingState.PREPARING
    }

    private fun toggleReplayFromDirectHud() {
        when (sessionStateMachine.replayState) {
            ReplaySessionState.IDLE -> requestReplayStart()
            ReplaySessionState.RUNNING -> requestReplayStop()
            ReplaySessionState.AWAITING_AUDIO_PERMISSION -> requestReplayAudioPermission()
            ReplaySessionState.AWAITING_AUDIO_FALLBACK -> requestSilentReplayFallback()
            ReplaySessionState.STARTING,
            ReplaySessionState.STOPPING -> Unit
        }
    }

    private fun prepareProjection(resultCode: Int, resultData: Intent) {
        releaseProjection()
        val manager = getSystemService(MediaProjectionManager::class.java)
        val activeProjection = manager.getMediaProjection(resultCode, resultData)
            ?: error("MediaProjectionManager 未返回有效会话")
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                publish("系统已停止屏幕共享，对局助手即将结束")
                requestSessionStop()
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                scheduleCaptureResize(width, height, "capturedContentResize")
            }
        }
        activeProjection.registerCallback(callback, mainHandler)
        projection = activeProjection
        projectionCallback = callback
        Log.i(LOG_TAG, "Projection token acquired; starting the recognition session")
    }

    private fun createCaptureSurface() {
        check(virtualDisplay == null) { "A projection token may create only one VirtualDisplay" }
        val activeProjection = projection ?: error("MediaProjection 会话已经失效")
        val bounds = windowManager.currentWindowMetrics.bounds
        val initialSpec = changedCaptureBufferSpec(
            current = null,
            width = bounds.width(),
            height = bounds.height(),
            densityDpi = resources.displayMetrics.densityDpi,
            rotation = currentCaptureRotation(),
        ) ?: error("当前屏幕尺寸无效：${bounds.width()}×${bounds.height()}")
        val thread = HandlerThread("capture-frames").apply { start() }
        val handler = Handler(thread.looper)
        val generation = captureGeneration + 1
        val reader = createImageReader(
            spec = initialSpec,
            handler = handler,
            generation = generation,
            trackRecognitionFrames = true,
        )
        val display = try {
            activeProjection.createVirtualDisplay(
                "pokemon-champions-assistant",
                initialSpec.virtualDisplayWidth,
                initialSpec.virtualDisplayHeight,
                initialSpec.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )?.also { applyVirtualDisplayRotation(it, initialSpec.rotation) }
                ?: error("MediaProjection 未返回有效虚拟显示")
        } catch (error: Throwable) {
            reader.setOnImageAvailableListener(null, null)
            reader.close()
            thread.quitSafely()
            throw error
        }
        imageReader = reader
        imageThread = thread
        imageHandler = handler
        virtualDisplay = display
        captureBufferSpec = initialSpec
        captureGeneration = generation
        frameTrackingEnabled = true
        Log.i(LOG_TAG, "Recognition capture surface ready: $initialSpec, generation=$generation")
    }

    private fun switchToReplayCaptureSurface(
        surface: Surface,
        spec: CaptureBufferSpec,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        val display = virtualDisplay ?: return onComplete(Result.failure(IllegalStateException("虚拟显示尚未创建")))
        val handler = imageHandler ?: return onComplete(Result.failure(IllegalStateException("捕获线程尚未创建")))
        val oldReader = imageReader
        if (!handler.post {
            val result = runCatching {
                display.resize(spec.virtualDisplayWidth, spec.virtualDisplayHeight, spec.densityDpi)
                applyVirtualDisplayRotation(display, spec.rotation)
                display.setSurface(surface)
                oldReader?.setOnImageAvailableListener(null, null)
                oldReader?.close()
                imageReader = null
                captureBufferSpec = spec
                captureGeneration += 1
                frameTrackingEnabled = false
                synchronized(bitmapLock) {
                    latestBitmap?.recycle()
                    latestBitmap = null
                    frozenMenuBitmap?.recycle()
                    frozenMenuBitmap = null
                }
                Log.i(LOG_TAG, "Capture surface switched to replay: $spec, generation=$captureGeneration")
                Unit
            }
            mainHandler.post { onComplete(result) }
        }) {
            onComplete(Result.failure(IllegalStateException("无法调度录屏捕获 Surface")))
        }
    }

    private fun restoreRecognitionCaptureSurface(onComplete: (Result<Unit>) -> Unit) {
        if (imageReader != null) {
            onComplete(Result.success(Unit))
            return
        }
        val display = virtualDisplay ?: return onComplete(Result.failure(IllegalStateException("虚拟显示尚未创建")))
        val handler = imageHandler ?: return onComplete(Result.failure(IllegalStateException("捕获线程尚未创建")))
        val spec = captureBufferSpec ?: return onComplete(Result.failure(IllegalStateException("捕获尺寸尚未建立")))
        val generation = captureGeneration + 1
        if (!handler.post {
            var nextReader: ImageReader? = null
            val result = runCatching {
                nextReader = createImageReader(
                    spec = spec,
                    handler = handler,
                    generation = generation,
                    trackRecognitionFrames = true,
                )
                display.resize(spec.virtualDisplayWidth, spec.virtualDisplayHeight, spec.densityDpi)
                applyVirtualDisplayRotation(display, spec.rotation)
                display.setSurface(checkNotNull(nextReader).surface)
                imageReader = nextReader
                captureGeneration = generation
                frameTrackingEnabled = true
                Log.i(LOG_TAG, "Capture surface restored to recognition: $spec, generation=$generation")
                Unit
            }.onFailure {
                nextReader?.setOnImageAvailableListener(null, null)
                nextReader?.close()
            }
            mainHandler.post { onComplete(result) }
        }) {
            onComplete(Result.failure(IllegalStateException("无法调度识别捕获 Surface")))
        }
    }

    private fun createImageReader(
        spec: CaptureBufferSpec,
        handler: Handler,
        generation: Long,
        trackRecognitionFrames: Boolean,
    ): ImageReader = ImageReader.newInstance(
        spec.virtualDisplayWidth,
        spec.virtualDisplayHeight,
        PixelFormat.RGBA_8888,
        2,
    ).also { reader ->
        reader.setOnImageAvailableListener(
            {
                source ->
                if (trackRecognitionFrames) {
                    updateLatestFrame(
                        source,
                        spec.virtualDisplayWidth,
                        spec.virtualDisplayHeight,
                        generation,
                    )
                } else {
                    // Recognition modes own this ImageReader. Record-only sessions use the EGL
                    // router and never enter this draining branch.
                    runCatching { source.acquireLatestImage() }.getOrNull()?.close()
                }
            },
            handler,
        )
    }

    private fun scheduleCaptureResize(
        width: Int? = null,
        height: Int? = null,
        reason: String,
    ) {
        if (width != null && width > 0 && height != null && height > 0) {
            pendingCapturedContentSize = width to height
        }
        pendingCaptureResize?.let(mainHandler::removeCallbacks)
        val request = Runnable {
            pendingCaptureResize = null
            val bounds = windowManager.currentWindowMetrics.bounds
            val capturedContentSize = pendingCapturedContentSize.also {
                pendingCapturedContentSize = null
            }
            resizeCaptureSurface(
                width = capturedContentSize?.first ?: bounds.width(),
                height = capturedContentSize?.second ?: bounds.height(),
                densityDpi = resources.displayMetrics.densityDpi,
                rotation = currentCaptureRotation(),
                reason = reason,
            )
        }
        pendingCaptureResize = request
        mainHandler.postDelayed(request, CAPTURE_RESIZE_DEBOUNCE_MS)
    }

    private fun resizeCaptureSurface(
        width: Int,
        height: Int,
        densityDpi: Int,
        rotation: Int,
        reason: String,
    ) {
        val handler = imageHandler ?: return
        handler.post {
            val currentSpec = captureBufferSpec ?: return@post
            val nextSpec = changedCaptureBufferSpec(
                currentSpec,
                width,
                height,
                densityDpi,
                rotation,
            ) ?: return@post
            val display = virtualDisplay ?: return@post
            val recorder = replayRecorder
            if (recorder != null && imageReader == null) {
                runCatching {
                    display.resize(
                        nextSpec.virtualDisplayWidth,
                        nextSpec.virtualDisplayHeight,
                        nextSpec.densityDpi,
                    )
                    applyVirtualDisplayRotation(display, nextSpec.rotation)
                    recorder.updateInputSpec(nextSpec)
                }.onSuccess {
                    captureBufferSpec = nextSpec
                    Log.i(LOG_TAG, "Replay capture resized: $currentSpec -> $nextSpec, reason=$reason")
                    mainHandler.post {
                        if (!destroyed) CaptureUiState.message.value = "录屏方向已调整，固定 960×540 画布继续编码"
                    }
                }.onFailure { error ->
                    Log.e(LOG_TAG, "Could not resize replay capture from $currentSpec to $nextSpec", error)
                    mainHandler.post { requestReplayStop() }
                }
                return@post
            }
            val oldReader = imageReader ?: return@post
            val nextGeneration = captureGeneration + 1
            val nextReader = runCatching {
                createImageReader(
                    spec = nextSpec,
                    handler = handler,
                    generation = nextGeneration,
                    trackRecognitionFrames = true,
                )
            }
                .getOrElse { error ->
                    Log.e(LOG_TAG, "Could not create capture surface for $nextSpec", error)
                    publish(captureResizeFailureMessage())
                    return@post
                }

            runCatching {
                // ImageReader callbacks and Surface replacement share this looper. Closing an
                // ImageReader from the main thread while copyPixelsFromBuffer() is still reading
                // one of its planes can crash in native memcpy on Android 16.
                display.resize(
                    nextSpec.virtualDisplayWidth,
                    nextSpec.virtualDisplayHeight,
                    nextSpec.densityDpi,
                )
                applyVirtualDisplayRotation(display, nextSpec.rotation)
                display.setSurface(nextReader.surface)
            }.onSuccess {
                captureGeneration = nextGeneration
                captureBufferSpec = nextSpec
                imageReader = nextReader
                oldReader.setOnImageAvailableListener(null, null)
                oldReader.close()
                synchronized(bitmapLock) {
                    latestBitmap?.recycle()
                    latestBitmap = null
                    frozenMenuBitmap?.recycle()
                    frozenMenuBitmap = null
                    frameTrackingEnabled = true
                }
                Log.i(
                    LOG_TAG,
                    "Capture surface resized: $currentSpec -> $nextSpec, generation=$nextGeneration, reason=$reason",
                )
                mainHandler.post {
                    if (destroyed) return@post
                    CaptureUiState.message.value = "画面方向已调整，可以继续识别"
                }
            }.onFailure { error ->
                nextReader.setOnImageAvailableListener(null, null)
                runCatching {
                    display.resize(
                        currentSpec.virtualDisplayWidth,
                        currentSpec.virtualDisplayHeight,
                        currentSpec.densityDpi,
                    )
                    applyVirtualDisplayRotation(display, currentSpec.rotation)
                    display.setSurface(oldReader.surface)
                }
                nextReader.close()
                Log.e(LOG_TAG, "Could not resize capture surface from $currentSpec to $nextSpec", error)
                publish(captureResizeFailureMessage())
            }
        }
    }

    private fun captureResizeFailureMessage(): String =
        "画面方向变化后暂时无法继续识别，请结束并重新启动对局助手"

    private fun currentCaptureRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)
                ?.rotation
                ?: Surface.ROTATION_0
        } else {
            // VirtualDisplay.setRotation() is unavailable before Android 16.
            // Preserve the existing size-only behavior on older releases.
            Surface.ROTATION_0
        }

    private fun applyVirtualDisplayRotation(display: VirtualDisplay, rotation: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            display.setRotation(rotation)
        }
    }

    private fun updateLatestFrame(
        reader: ImageReader,
        width: Int,
        height: Int,
        generation: Long,
    ) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (!frameTrackingEnabled || generation != captureGeneration) return
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val paddedWidth = width + rowPadding / plane.pixelStride
            synchronized(bitmapLock) {
                if (!frameTrackingEnabled || generation != captureGeneration) return
                var reusable = latestBitmap
                if (reusable == null || reusable.width != paddedWidth || reusable.height != height) {
                    reusable?.recycle()
                    reusable = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    latestBitmap = reusable
                }
                plane.buffer.rewind()
                reusable.copyPixelsFromBuffer(plane.buffer)
            }
        } catch (_: IllegalStateException) {
            // The reader can invalidate an already-acquired frame while a projection is stopping.
            // A later callback will supply a fresh frame when the session remains active.
        } finally {
            runCatching { image.close() }
        }
    }

    private fun showReplayIsolationMarker() {
        if (replayIsolationMarker != null) return
        val size = (resources.displayMetrics.density * 144).toInt()
        val marker = ReplayIsolationMarkerView(overlayWindowContext)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 180
        }
        windowManager.addView(marker, params)
        replayIsolationMarker = marker
    }

    private fun hideReplayIsolationMarker() {
        replayIsolationMarker?.let { marker -> runCatching { windowManager.removeView(marker) } }
        replayIsolationMarker = null
    }

    private fun startReplayTicker() {
        stopReplayTicker()
        val ticker = object : Runnable {
            override fun run() {
                if (
                    destroyed || sessionStateMachine.state != CaptureSessionState.RUNNING ||
                    sessionStateMachine.replayState != ReplaySessionState.RUNNING
                ) return
                val duration = formatReplayDuration(replayRecorder?.elapsedMs() ?: 0L)
                updateBubbleAppearance()
                val controlSurface = if (assistantMode.usesFloatingBubble) "悬浮菜单" else "HUD"
                updateProjectionNotification("正在录屏 $duration · 可从${controlSurface}单独结束并保存")
                mainHandler.postDelayed(this, 1_000L)
            }
        }
        replayTicker = ticker
        mainHandler.post(ticker)
    }

    private fun stopReplayTicker() {
        replayTicker?.let(mainHandler::removeCallbacks)
        replayTicker = null
    }

    private fun formatReplayDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
        return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun showBubble() {
        if (destroyed || !assistantMode.usesFloatingBubble || bubble != null) return
        val density = overlayWindowContext.resources.displayMetrics.density
        val size = (64 * density).toInt()
        val view = TextView(overlayWindowContext).apply {
            text = "对局\n助手"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(78, 92, 190))
                setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
            }
            elevation = 12f
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val margin = (24 * resources.displayMetrics.density).toInt()
            val bounds = safeArea.currentRegion(preferEnd = true)
            x = (bounds.right - size - margin).coerceAtLeast(bounds.left)
            y = (bounds.top + (220 * resources.displayMetrics.density).toInt())
                .coerceIn(bounds.top, (bounds.bottom - size).coerceAtLeast(bounds.top))
            bubbleParams?.let { previous ->
                val remembered = safeArea.clampPosition(previous.x, previous.y, size, size)
                x = remembered.x
                y = remembered.y
            }
        }
        var downX = 0f
        var downY = 0f
        var originalX = 0
        var originalY = 0
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; originalX = params.x; originalY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val position = safeArea.clampPosition(
                        proposedX = (originalX + event.rawX - downX).toInt(),
                        proposedY = (originalY + event.rawY - downY).toInt(),
                        width = params.width,
                        height = params.height,
                    )
                    params.x = position.x
                    params.y = position.y
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.hypot(event.rawX - downX, event.rawY - downY) < 18 * resources.displayMetrics.density) {
                        showBubbleMenu(view)
                    }
                    true
                }
                else -> false
            }
        }
        windowManager.addView(view, params)
        bubble = view
        bubbleParams = params
        updateBubbleAppearance()
    }

    private fun removeBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
    }

    private fun showAssistantEntry() {
        if (destroyed) return
        if (assistantMode.usesFloatingBubble) {
            showBubble()
        } else {
            removeBubble()
            recognitionFeatureHost?.battleOverlayController?.showDirectHudEntry()
        }
    }

    private fun setBubbleWindowPresent(present: Boolean) {
        if (present) showAssistantEntry() else removeBubble()
    }

    private fun onOverlaySafeAreaChanged() {
        if (destroyed) return
        val bubbleView = bubble
        val params = bubbleParams
        if (bubbleView != null && params != null) {
            val position = safeArea.clampPosition(params.x, params.y, params.width, params.height)
            params.x = position.x
            params.y = position.y
            runCatching { windowManager.updateViewLayout(bubbleView, params) }
        }
        reflowTeamNamePrompt()
        recognitionFeatureHost?.onSafeAreaChanged()
    }

    private fun scheduleOverlaySafeAreaRefresh() {
        if (destroyed) return
        // Some OEMs report the display change before WindowMetrics has published its
        // rotated bounds. Reflow once immediately and once after that update settles.
        mainHandler.post(::onOverlaySafeAreaChanged)
        mainHandler.postDelayed(::onOverlaySafeAreaChanged, 250L)
    }

    private fun updateBubbleAppearance() {
        val textView = bubble as? TextView ?: return
        val (label, color) = when (sessionStateMachine.state) {
            CaptureSessionState.RUNNING -> when (sessionStateMachine.replayState) {
                ReplaySessionState.RUNNING ->
                    "●\n${formatReplayDuration(replayRecorder?.elapsedMs() ?: 0L)}" to Color.rgb(170, 62, 62)
                ReplaySessionState.AWAITING_AUDIO_PERMISSION,
                ReplaySessionState.AWAITING_AUDIO_FALLBACK,
                ReplaySessionState.STARTING -> "录屏\n准备" to Color.rgb(184, 112, 24)
                ReplaySessionState.STOPPING -> "录屏\n保存" to Color.rgb(184, 112, 24)
                ReplaySessionState.IDLE -> "对局\n助手" to Color.rgb(78, 92, 190)
            }
            else -> "对局\n助手" to Color.rgb(86, 91, 105)
        }
        textView.text = label
        textView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
        }
    }

    private fun showBubbleMenu(anchor: View) {
        when (sessionStateMachine.state) {
            CaptureSessionState.RUNNING -> showActiveSessionMenu(anchor)
            CaptureSessionState.STOPPING -> toast("对局助手正在结束")
            else -> toast("对局助手尚未准备好")
        }
    }

    private fun showActiveSessionMenu(anchor: View) {
        val host = recognitionFeatureHost ?: run {
            publish("识别组件尚未准备好，请结束后重试")
            return
        }
        if (sessionStateMachine.replayState == ReplaySessionState.RUNNING) {
            prepareReplayRecognitionMenu(anchor, host)
            return
        }
        if (
            sessionStateMachine.replayState == ReplaySessionState.STARTING ||
            sessionStateMachine.replayState == ReplaySessionState.STOPPING
        ) {
            toast("录屏正在切换捕获通道，请稍候")
            return
        }
        // Freeze the last complete screen frame before PopupMenu creates its
        // separate overlay window. The bubble itself is outside all 12
        // TEAM_PREVIEW icon ROIs, while Photos may transiently redraw the
        // underlying image as black when the popup is dismissed.
        val freezeStarted = System.nanoTime()
        synchronized(bitmapLock) {
            frameTrackingEnabled = false
            frozenMenuBitmap?.recycle()
            frozenMenuBitmap = copyLatestFrameLocked()
        }
        frozenMenuFrameCopyMs = (System.nanoTime() - freezeStarted) / 1_000_000.0
        showRecognitionSessionMenu(anchor, host)
    }

    private fun prepareReplayRecognitionMenu(
        anchor: View,
        host: RecognitionFeatureHost,
    ) {
        if (replayMenuCapturePending || recognizing) {
            toast("识别画面仍在准备，请稍候")
            return
        }
        val recorder = replayRecorder ?: run {
            publish("录屏帧路由尚未准备好，请结束后重试")
            return
        }
        replayMenuCapturePending = true
        val generation = ++replayRecognitionGeneration
        CaptureUiState.message.value = "正在准备原分辨率识别画面…"
        recorder.requestRecognitionFrame { result ->
            mainHandler.post {
                val captured = result.getOrNull()
                if (
                    destroyed || generation != replayRecognitionGeneration ||
                    sessionStateMachine.state != CaptureSessionState.RUNNING ||
                    sessionStateMachine.replayState != ReplaySessionState.RUNNING
                ) {
                    captured?.bitmap?.recycle()
                    return@post
                }
                replayMenuCapturePending = false
                result.onSuccess { frame ->
                    synchronized(bitmapLock) {
                        frozenMenuBitmap?.recycle()
                        frozenMenuBitmap = frame.bitmap
                    }
                    frozenMenuFrameCopyMs = frame.totalMs
                    CaptureUiState.message.value = "识别画面已冻结，录屏仍在继续"
                }.onFailure { error ->
                    Log.w(LOG_TAG, "Could not freeze replay recognition menu frame", error)
                    synchronized(bitmapLock) {
                        frozenMenuBitmap?.recycle()
                        frozenMenuBitmap = null
                    }
                    frozenMenuFrameCopyMs = 0.0
                    CaptureUiState.message.value = "菜单已打开；点击识别后将重新读取画面"
                }
                showRecognitionSessionMenu(anchor, host)
            }
        }
    }

    private fun showRecognitionSessionMenu(
        anchor: View,
        host: RecognitionFeatureHost,
    ) {
        PopupMenu(overlayWindowContext, anchor).apply {
            if (sessionStateMachine.replayState == ReplaySessionState.RUNNING) {
                menu.add(
                    0,
                    200,
                    0,
                    "正在录屏 ${formatReplayDuration(replayRecorder?.elapsedMs() ?: 0L)}",
                ).isEnabled = false
            }
            menu.add(0, 1, 0, "录入我的队伍")
            menu.add(0, 2, 1, "识别双方阵容")
            if (host.battleOverlayController.hasSession) {
                menu.add(0, 4, 2, "显示对战 HUD")
                menu.add(0, 9, 3, "打开详细面板")
            }
            if (host.importRepository.hasCorrectionDraft()) {
                menu.add(0, 7, 4, "继续核对我的队伍")
            }
            if (host.importRepository.hasPendingTeam()) {
                menu.add(0, 5, 5, "为我的队伍命名并保存")
            }
            when (sessionStateMachine.replayState) {
                ReplaySessionState.IDLE -> {
                    menu.add(0, 8, 6, "开始录屏").isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
                        menu.add(0, 201, 7, "录屏首发仅支持 Android 16").isEnabled = false
                    }
                }
                ReplaySessionState.RUNNING -> menu.add(0, 8, 6, "结束录屏并保存 MP4")
                ReplaySessionState.AWAITING_AUDIO_PERMISSION -> menu.add(0, 8, 6, "继续游戏声音授权")
                ReplaySessionState.AWAITING_AUDIO_FALLBACK -> menu.add(0, 8, 6, "继续无声录屏确认")
                ReplaySessionState.STARTING -> menu.add(0, 201, 6, "正在准备录屏…").isEnabled = false
                ReplaySessionState.STOPPING -> menu.add(0, 201, 6, "正在结束并保存录屏…").isEnabled = false
            }
            menu.add(0, 6, 8, "结束对局助手")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> captureAndRecognizeOwnTeam()
                    2 -> captureAndRecognizeTeamPreview()
                    4 -> host.battleOverlayController.revealDirectHud()
                    5 -> showTeamNamePrompt()
                    6 -> requestSessionStop()
                    7 -> openOwnTeamCorrection()
                    8 -> when (sessionStateMachine.replayState) {
                        ReplaySessionState.IDLE -> requestReplayStart()
                        ReplaySessionState.RUNNING -> requestReplayStop()
                        ReplaySessionState.AWAITING_AUDIO_PERMISSION -> requestReplayAudioPermission()
                        ReplaySessionState.AWAITING_AUDIO_FALLBACK -> requestSilentReplayFallback()
                        else -> Unit
                    }
                    9 -> host.battleOverlayController.showPanel()
                }
                true
            }
            setOnDismissListener {
                if (!recognizing) {
                    synchronized(bitmapLock) {
                        frozenMenuBitmap?.recycle()
                        frozenMenuBitmap = null
                        frameTrackingEnabled = true
                    }
                }
            }
            show()
        }
    }

    private fun captureFrame(
        useFrozenMenuFrame: Boolean = false,
        onFrame: (Bitmap, TeamPreviewCaptureTiming) -> Unit,
    ) {
        if (destroyed) return
        if (recognizing) {
            toast("上一次识别仍在进行，请稍候")
            return
        }
        recognizing = true
        val generation = ++replayRecognitionGeneration
        val requestedAt = System.nanoTime()
        removeBubble()
        // Toasts are part of the MediaProjection output. In landscape they sit
        // across the bottom two cards, so cancel any result Toast and update
        // status without drawing a new overlay before copying the clean frame.
        activeToast?.cancel()
        activeToast = null
        CaptureUiState.message.value = "正在读取当前画面…"
        if (!useFrozenMenuFrame) {
            synchronized(bitmapLock) {
                frozenMenuBitmap?.recycle()
                frozenMenuBitmap = null
                frameTrackingEnabled = true
            }
        }
        val request = CancelableDelayedTask capture@{
            pendingFrameCapture = null
            if (destroyed || generation != replayRecognitionGeneration) return@capture
            val hideWaitMs = (System.nanoTime() - requestedAt) / 1_000_000.0
            if (sessionStateMachine.replayState == ReplaySessionState.RUNNING) {
                captureReplayRecognitionFrame(
                    generation = generation,
                    requestedAt = requestedAt,
                    hideWaitMs = hideWaitMs,
                    useFrozenMenuFrame = useFrozenMenuFrame,
                    onFrame = onFrame,
                )
                return@capture
            }
            val copyStarted = System.nanoTime()
            val frame = synchronized(bitmapLock) {
                if (destroyed) return@synchronized null
                frameTrackingEnabled = false
                if (useFrozenMenuFrame) frozenMenuBitmap.also { frozenMenuBitmap = null }
                else copyLatestFrameLocked()
            }
            if (destroyed) {
                frame?.recycle()
                return@capture
            }
            val selectionCopyMs = (System.nanoTime() - copyStarted) / 1_000_000.0
            val frameCopyMs = selectionCopyMs + if (useFrozenMenuFrame) frozenMenuFrameCopyMs else 0.0
            showAssistantEntry()
            if (frame == null) {
                failRecognitionFrameCapture("暂时无法读取当前画面，请稍后重试")
                return@capture
            }
            deliverRecognitionFrame(frame, requestedAt, hideWaitMs, frameCopyMs, onFrame)
        }
        pendingFrameCapture = request
        if (!mainHandler.postDelayed(request, if (useFrozenMenuFrame) 0 else 1_000)) {
            pendingFrameCapture = null
            request.cancel()
            recognizing = false
            showAssistantEntry()
            synchronized(bitmapLock) { frameTrackingEnabled = true }
            publish("暂时无法开始识别，请重试")
        }
    }

    private fun captureReplayRecognitionFrame(
        generation: Long,
        requestedAt: Long,
        hideWaitMs: Double,
        useFrozenMenuFrame: Boolean,
        onFrame: (Bitmap, TeamPreviewCaptureTiming) -> Unit,
    ) {
        val frozen = if (useFrozenMenuFrame) {
            synchronized(bitmapLock) { frozenMenuBitmap.also { frozenMenuBitmap = null } }
        } else {
            null
        }
        if (frozen != null) {
            deliverRecognitionFrame(
                frame = frozen,
                requestedAt = requestedAt,
                hideWaitMs = hideWaitMs,
                frameCopyMs = frozenMenuFrameCopyMs,
                onFrame = onFrame,
            )
            return
        }
        val recorder = replayRecorder ?: run {
            failRecognitionFrameCapture("录屏帧路由不可用，请结束后重试")
            return
        }
        recorder.requestRecognitionFrame { result ->
            mainHandler.post {
                val captured = result.getOrNull()
                if (
                    destroyed || generation != replayRecognitionGeneration ||
                    sessionStateMachine.state != CaptureSessionState.RUNNING
                ) {
                    captured?.bitmap?.recycle()
                    return@post
                }
                result.onSuccess { frame ->
                    deliverRecognitionFrame(
                        frame = frame.bitmap,
                        requestedAt = requestedAt,
                        hideWaitMs = hideWaitMs,
                        frameCopyMs = frame.totalMs,
                        onFrame = onFrame,
                    )
                }.onFailure { error ->
                    Log.w(LOG_TAG, "Replay recognition frame readback failed without stopping recording", error)
                    failRecognitionFrameCapture("暂时无法读取原分辨率游戏画面；录屏仍在继续")
                }
            }
        }
    }

    private fun deliverRecognitionFrame(
        frame: Bitmap,
        requestedAt: Long,
        hideWaitMs: Double,
        frameCopyMs: Double,
        onFrame: (Bitmap, TeamPreviewCaptureTiming) -> Unit,
    ) {
        showAssistantEntry()
        if (destroyed || sessionStateMachine.state != CaptureSessionState.RUNNING) {
            frame.recycle()
            return
        }
        publish(
            if (sessionStateMachine.replayState == ReplaySessionState.RUNNING) {
                "正在识别当前画面；录屏继续运行…"
            } else {
                "正在识别当前画面…"
            },
        )
        onFrame(frame, TeamPreviewCaptureTiming(requestedAt, hideWaitMs, frameCopyMs))
    }

    private fun failRecognitionFrameCapture(message: String) {
        showAssistantEntry()
        synchronized(bitmapLock) { frameTrackingEnabled = true }
        recognizing = false
        publish(message)
    }

    private fun copyLatestFrameLocked(): Bitmap? {
        val source = latestBitmap ?: return null
        val spec = captureBufferSpec ?: return null
        val bufferWidth = source.width.coerceAtMost(spec.virtualDisplayWidth)
        val bufferHeight = source.height.coerceAtMost(spec.virtualDisplayHeight)
        if (spec.rotation == Surface.ROTATION_0) {
            return Bitmap.createBitmap(
                bufferWidth,
                bufferHeight,
                Bitmap.Config.ARGB_8888,
            ).also { Canvas(it).drawBitmap(source, 0f, 0f, null) }
        }
        return Bitmap.createBitmap(
            source,
            0,
            0,
            bufferWidth,
            bufferHeight,
            Matrix().apply { postRotate(spec.bitmapRotationDegrees) },
            true,
        )
    }

    private fun captureAndRecognizeOwnTeam() {
        val host = recognitionFeatureHost ?: run {
            publish("当前会话没有启用识别组件")
            return
        }
        if (host.importRepository.hasPendingTeam()) {
            publish("请先为上一支已识别队伍命名并保存")
            showTeamNamePrompt()
            return
        }
        captureFrame { frame, _ ->
            host.ocrEngine.recognize(frame) callback@{ result ->
                frame.recycle()
                if (destroyed) return@callback
                mainHandler.post {
                    if (destroyed) return@post
                    frameTrackingEnabled = true
                    recognizing = false
                    if (sessionStateMachine.state != CaptureSessionState.RUNNING) return@post
                    result.onSuccess { page ->
                        val saved = host.importRepository.accept(page)
                        CaptureUiState.ownTeamDraftRevision.value += 1
                        publish(saved.message)
                        when (saved.nextStep) {
                            OwnTeamImportNextStep.MANUAL_CORRECTION -> openOwnTeamCorrection()
                            OwnTeamImportNextStep.NAME_TEAM -> showTeamNamePrompt()
                            else -> Unit
                        }
                    }.onFailure { error ->
                        Log.e(LOG_TAG, "Own-team recognition failed", error)
                        publish("无法识别我的队伍，请确认当前页面完整显示后重试")
                    }
                }
            }
        }
    }

    private fun captureAndRecognizeTeamPreview(useFrozenMenuFrame: Boolean = true) {
        val host = recognitionFeatureHost ?: run {
            publish("当前会话没有启用识别组件")
            return
        }
        host.battleOverlayController.onTeamRecognitionStarted()
        captureFrame(useFrozenMenuFrame = useFrozenMenuFrame) { frame, captureTiming ->
            host.teamPreviewEngine.recognize(frame, captureTiming) callback@{ result ->
                frame.recycle()
                if (destroyed) return@callback
                mainHandler.post {
                    if (destroyed) return@post
                    frameTrackingEnabled = true
                    recognizing = false
                    if (sessionStateMachine.state != CaptureSessionState.RUNNING) return@post
                    result.onSuccess { preview ->
                        runCatching { host.teamPreviewRepository.save(preview) }
                            .onSuccess { saved ->
                                val clickToSavedMs = (System.nanoTime() - captureTiming.requestedAtNanos) / 1_000_000.0
                                Log.i(
                                    "TeamPreviewPerf",
                                    "privateReplaceMs=${saved.privateWriteMs}, clickToSavedMs=$clickToSavedMs",
                                )
                                publish(preview.summary())
                                host.battleOverlayController.showSetup()
                            }
                            .onFailure { error ->
                                Log.e(LOG_TAG, "Team preview save failed", error)
                                publish("无法保存双方阵容，请重新识别")
                            }
                    }.onFailure { error ->
                        Log.e(LOG_TAG, "Team preview recognition failed", error)
                        publish("无法识别双方阵容，请确认队伍预览页面完整显示后重试")
                    }
                }
            }
        }
    }

    private fun openOwnTeamCorrection() {
        if (!Settings.canDrawOverlays(this)) {
            publish("请先在 App 中授予悬浮窗权限")
            if (projection == null) stopSelf()
            return
        }
        val host = recognitionFeatureHost ?: ensureRecognitionFeatureHost()
        if (!host.importRepository.hasCorrectionDraft()) {
            publish("没有需要继续核对的队伍")
            if (projection == null) stopSelf()
            return
        }
        host.ownTeamCorrectionController.show()
    }

    private fun showTeamNamePrompt() {
        val host = recognitionFeatureHost ?: run {
            publish("当前会话没有启用识别组件")
            return
        }
        if (!host.importRepository.hasPendingTeam()) {
            publish("没有等待保存的队伍")
            return
        }
        if (teamNamePrompt != null) return
        removeBubble()
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val root = LinearLayout(overlayWindowContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                setColor(Color.rgb(31, 40, 58))
                cornerRadius = 18 * density
                setStroke((1 * density).toInt().coerceAtLeast(1), Color.rgb(119, 215, 196))
            }
        }
        root.addView(TextView(overlayWindowContext).apply {
            text = "保存我的队伍"
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(overlayWindowContext).apply {
            text = "两张队伍页面均已识别。请为这支队伍命名，保存后可在首页和计算页使用。"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, (12 * density).toInt(), 0, (8 * density).toInt())
        })
        val input = EditText(overlayWindowContext).apply {
            hint = "例如：雨天队"
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            maxLines = 1
            finishInputOnImeDone()
        }
        root.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        val actions = LinearLayout(overlayWindowContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        actions.addView(Button(overlayWindowContext).apply {
            text = "稍后命名"
            setOnClickListener {
                dismissTeamNamePrompt()
                publish("队伍尚未保存；可再次从当前对局助手入口选择“为我的队伍命名并保存”")
            }
        })
        actions.addView(Button(overlayWindowContext).apply {
            text = "保存队伍"
            setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    input.error = "请输入队伍名称"
                    return@setOnClickListener
                }
                runCatching { host.importRepository.savePendingTeam(name) }
                    .onSuccess { saved ->
                        CaptureUiState.teamLibraryRevision.value += 1
                        dismissTeamNamePrompt()
                        publish(saved.message)
                    }
                    .onFailure { error ->
                        Log.e(LOG_TAG, "Could not save named own team", error)
                        input.error = "保存队伍失败，请重试"
                    }
            }
        })
        root.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        val bounds = safeArea.currentRegion(preferEnd = true).inset((24 * density).toInt())
        val params = WindowManager.LayoutParams(
            minOf((720 * density).toInt(), bounds.width.coerceAtLeast(1)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left + (bounds.width - width) / 2
            y = bounds.top
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        windowManager.addView(root, params)
        teamNamePrompt = root
        teamNamePromptParams = params
        root.post(::reflowTeamNamePrompt)
        input.requestFocus()
        mainHandler.postDelayed({
            if (!destroyed && teamNamePrompt === root) {
                getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 200)
    }

    private fun dismissTeamNamePrompt() {
        teamNamePrompt?.let { prompt ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(prompt.windowToken, 0)
            runCatching { windowManager.removeView(prompt) }
        }
        teamNamePrompt = null
        teamNamePromptParams = null
        showAssistantEntry()
    }

    private fun reflowTeamNamePrompt() {
        val prompt = teamNamePrompt ?: return
        val params = teamNamePromptParams ?: return
        val measuredHeight = prompt.height.coerceAtLeast(1)
        val reference = OverlayBounds(
            params.x,
            params.y,
            params.x + params.width.coerceAtLeast(1),
            params.y + measuredHeight,
        )
        val density = resources.displayMetrics.density
        val bounds = safeArea.currentRegion(reference, preferEnd = true).inset((24 * density).toInt())
        params.width = minOf((720 * density).toInt(), bounds.width.coerceAtLeast(1))
        params.x = bounds.left + (bounds.width - params.width) / 2
        params.y = bounds.top + (bounds.height - measuredHeight).coerceAtLeast(0) / 2
        runCatching { windowManager.updateViewLayout(prompt, params) }
    }

    private fun publish(message: String) {
        if (destroyed) return
        mainHandler.post {
            if (destroyed) return@post
            CaptureUiState.message.value = message
            toast(message)
        }
    }

    private fun toast(message: String) {
        activeToast?.cancel()
        activeToast = Toast.makeText(this, message, Toast.LENGTH_LONG).also(Toast::show)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "对局助手", NotificationManager.IMPORTANCE_LOW).apply {
                description = "管理单应用投屏、悬浮按钮或 HUD 本地识别和回放会话"
            }
        )
    }

    private fun releaseProjection() {
        activeToast?.cancel()
        activeToast = null
        cancelPendingFrameCapture()
        pendingCaptureResize?.let(mainHandler::removeCallbacks)
        pendingCaptureResize = null
        pendingCapturedContentSize = null
        frameTrackingEnabled = false
        captureGeneration += 1

        val activeProjection = projection
        val callback = projectionCallback
        val display = virtualDisplay
        val reader = imageReader
        val thread = imageThread
        val handler = imageHandler

        callback?.let { activeProjection?.unregisterCallback(it) }
        projectionCallback = null
        projection = null
        virtualDisplay = null
        imageReader = null
        imageThread = null
        imageHandler = null
        captureBufferSpec = null

        val cleanup = Runnable {
            runCatching { reader?.setOnImageAvailableListener(null, null) }
            runCatching { display?.setSurface(null) }
            runCatching { display?.release() }
            runCatching { reader?.close() }
        }
        if (handler != null && thread != null && Thread.currentThread() !== thread) {
            handler.removeCallbacksAndMessages(null)
            val finished = CountDownLatch(1)
            val posted = handler.post {
                try {
                    cleanup.run()
                } finally {
                    finished.countDown()
                }
            }
            if (!posted || !finished.await(5, TimeUnit.SECONDS)) {
                Log.e(LOG_TAG, "Timed out while serializing capture surface teardown")
            }
        } else {
            cleanup.run()
        }
        runCatching { activeProjection?.stop() }
        thread?.quitSafely()
        synchronized(bitmapLock) {
            latestBitmap?.recycle(); latestBitmap = null
            frozenMenuBitmap?.recycle(); frozenMenuBitmap = null
        }
    }

    private fun cancelPendingFrameCapture() {
        replayRecognitionGeneration += 1
        replayMenuCapturePending = false
        replayRecorder?.cancelRecognitionFrameRequest()
        val pending = pendingFrameCapture
        pendingFrameCapture = null
        pending?.cancel()
        pending?.let(mainHandler::removeCallbacks)
        recognizing = false
    }

    private fun requestReplayStop() {
        cancelPendingFrameCapture()
        if (!sessionStateMachine.requestReplayStop()) return
        stopServiceAfterReplayFinalize = false
        beginReplayFinalization()
    }

    private fun requestSessionStop() {
        cancelPendingFrameCapture()
        if (!sessionStateMachine.requestStop()) return
        stopServiceAfterReplayFinalize = true
        val recorder = replayRecorder
        if (recorder == null || !recorder.isStarted) {
            replayPreparationGeneration += 1
            replayIsolationTimeout?.let(mainHandler::removeCallbacks)
            replayIsolationTimeout = null
            hideReplayIsolationMarker()
            stopReplayTicker()
            recorder?.close()
            replayRecorder = null
            syncSessionUiState()
            updateBubbleAppearance()
            stopSelf()
            return
        }
        beginReplayFinalization()
    }

    private fun beginReplayFinalization() {
        replayPreparationGeneration += 1
        replayIsolationTimeout?.let(mainHandler::removeCallbacks)
        replayIsolationTimeout = null
        hideReplayIsolationMarker()
        stopReplayTicker()
        syncSessionUiState()
        updateBubbleAppearance()
        val recorder = replayRecorder
        if (recorder == null || !recorder.isStarted) {
            recorder?.close()
            replayRecorder = null
            if (stopServiceAfterReplayFinalize) {
                stopSelf()
            } else {
                if (sessionStateMachine.replayState == ReplaySessionState.STOPPING) {
                    sessionStateMachine.replayStopped()
                }
                syncSessionUiState()
                updateBubbleAppearance()
            }
            return
        }
        CaptureUiState.message.value = "正在结束编码并保存 MP4…"
        updateProjectionNotification("正在结束编码并保存 MP4…")
        thread(name = "replay-finalize") {
            val result = recorder.stopAndFinalize()
            mainHandler.post {
                if (destroyed) return@post
                replayRecorder = null
                var savedReplay: SavedReplay? = null
                val resultMessage = when (result) {
                    is ReplayFinalizeResult.Saved -> {
                        val replay = result.replay
                        savedReplay = replay
                        val sizeMiB = replay.sizeBytes / 1024.0 / 1024.0
                        "回放已保存：${formatReplayDuration(replay.durationMs)}，%.1f MiB".format(sizeMiB)
                    }
                    is ReplayFinalizeResult.Failed -> {
                        Log.e(LOG_TAG, "Replay could not be finalized", result.error)
                        "回放保存失败；损坏的 pending 文件已清理"
                    }
                }
                if (stopServiceAfterReplayFinalize) {
                    CaptureUiState.message.value = resultMessage
                    savedReplay?.let(::postReplaySavedNotification)
                    stopSelf()
                    return@post
                }
                restoreRecognitionCaptureSurface { restoreResult ->
                    restoreResult.onFailure { error ->
                        Log.e(LOG_TAG, "Could not restore recognition after replay finalization", error)
                    }
                    if (sessionStateMachine.replayState == ReplaySessionState.STOPPING) {
                        sessionStateMachine.replayStopped()
                    }
                    syncSessionUiState()
                    updateBubbleAppearance()
                    val continuation = if (restoreResult.isSuccess) {
                        "；对局助手继续运行"
                    } else {
                        "；识别画面恢复失败，请重新启动对局助手"
                    }
                    CaptureUiState.message.value = resultMessage + continuation
                    updateProjectionNotification(CaptureUiState.message.value)
                    savedReplay?.let(::postReplaySavedNotification)
                    publish(CaptureUiState.message.value)
                }
            }
        }
    }

    private fun postReplaySavedNotification(replay: SavedReplay) {
        val openReplay = PendingIntent.getActivity(
            this,
            70,
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(replay.uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle("对局回放已保存")
            .setContentText("${formatReplayDuration(replay.durationMs)} · 点击打开 ${replay.displayName}")
            .setContentIntent(openReplay)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, notification)
    }

    override fun onDestroy() {
        destroyed = true
        if (::displayManager.isInitialized) {
            displayManager.unregisterDisplayListener(overlayDisplayListener)
        }
        windowInfoTracker?.let { tracker ->
            runCatching { tracker.removeWindowLayoutInfoListener(windowLayoutInfoListener) }
        }
        windowInfoTracker = null
        if (::overlayWindowContext.isInitialized) {
            overlayWindowContext.unregisterComponentCallbacks(overlayComponentCallbacks)
        }
        replayPreparationGeneration += 1
        cancelPendingFrameCapture()
        replayIsolationTimeout?.let(mainHandler::removeCallbacks)
        replayIsolationTimeout = null
        hideReplayIsolationMarker()
        stopReplayTicker()
        replayRecorder?.close()
        replayRecorder = null
        recognitionFeatureHost?.close()
        recognitionFeatureHost = null
        dismissTeamNamePrompt()
        removeBubble()
        bubbleParams = null
        releaseProjection()
        if (sessionStateMachine.state == CaptureSessionState.STOPPING) {
            sessionStateMachine.stopped()
        }
        syncSessionUiState()
        if (sessionStateMachine.state != CaptureSessionState.FAILED) {
            CaptureUiState.message.value = "对局助手已结束"
        }
        super.onDestroy()
    }

    private class ReplayIsolationMarkerView(context: Context) : View(context) {
        private val paint = Paint().apply { isAntiAlias = false }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK)
            val inset = width * 0.08f
            val middleX = width / 2f
            val middleY = height / 2f
            paint.color = Color.MAGENTA
            canvas.drawRect(inset, inset, middleX, middleY, paint)
            canvas.drawRect(middleX, middleY, width - inset, height - inset, paint)
            paint.color = Color.CYAN
            canvas.drawRect(middleX, inset, width - inset, middleY, paint)
            canvas.drawRect(inset, middleY, middleX, height - inset, paint)
        }
    }
}
