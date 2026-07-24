package com.crazylei12.pokemonchampionsassistant

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object CaptureUiState {
    val message = mutableStateOf("对局助手尚未启动")
    val running = mutableStateOf(false)
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
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
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
    private lateinit var ocrEngine: OwnTeamOcrEngine
    private lateinit var importRepository: OwnTeamImportRepository
    private lateinit var teamPreviewEngine: TeamPreviewRecognitionEngine
    private lateinit var teamPreviewRepository: TeamPreviewResultRepository
    private lateinit var damageRuntime: DamageEngineRuntime
    private lateinit var battleOverlayController: BattleOverlayController
    private lateinit var ownTeamCorrectionController: OwnTeamCorrectionOverlayController
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
        ocrEngine = OwnTeamOcrEngine(this)
        importRepository = OwnTeamImportRepository(this)
        teamPreviewEngine = TeamPreviewRecognitionEngine(this)
        teamPreviewEngine.prepare()
        teamPreviewRepository = TeamPreviewResultRepository(this)
        damageRuntime = DamageEngineRuntime(this)
        val battleSessionRepository = BattleSessionRepository(this)
        battleOverlayController = BattleOverlayController(
            context = overlayWindowContext,
            windowManager = windowManager,
            safeArea = safeArea,
            runtime = damageRuntime,
            sessionRepository = battleSessionRepository,
            presetRepository = OpponentPresetRepository(this),
            publish = ::publish,
            onOverlayVisible = { visible ->
                setBubbleWindowPresent(!visible)
                if (!visible && projection == null) stopSelf()
            },
            shouldAutoOpenDirectHud = { assistantMode.autoOpenDirectHud },
            // The direct HUD has no PopupMenu phase and therefore no frozen menu frame.
            // Wait for a clean live frame after the HUD windows are dismissed instead.
            onRecognizeTeamPreview = { captureAndRecognizeTeamPreview(useFrozenMenuFrame = false) },
            onRecognizeOwnTeam = ::captureAndRecognizeOwnTeam,
        )
        ownTeamCorrectionController = OwnTeamCorrectionOverlayController(
            context = overlayWindowContext,
            windowManager = windowManager,
            safeArea = safeArea,
            importRepository = importRepository,
            presetRepository = OpponentPresetRepository(this),
            publish = ::publish,
            onOverlayVisible = { visible ->
                setBubbleWindowPresent(!visible)
            },
            onSaved = { saved ->
                CaptureUiState.ownTeamDraftRevision.value += 1
                CaptureUiState.teamLibraryRevision.value += 1
                publish(saved.message)
            },
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_OPEN_OWN_TEAM_CORRECTION) {
            openOwnTeamCorrection()
            return START_NOT_STICKY
        }
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent?.action == ACTION_DEBUG_RECOGNIZE_TEAM_PREVIEW) {
            if (projection == null) {
                publish("对局助手尚未准备好，请返回 App 重新启动")
            } else {
                captureAndRecognizeTeamPreview(useFrozenMenuFrame = false)
            }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
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
        runCatching { startProjection(resultCode, resultData) }
            .onSuccess {
                showAssistantEntry()
                CaptureUiState.running.value = true
                publish(
                    if (assistantMode.usesFloatingBubble) {
                        "对局助手已启动；打开 Pokémon Champions 后点击悬浮按钮"
                    } else {
                        "HUD 对局助手已启动；请在 HUD 中使用“再战”识别双方阵容"
                    },
                )
            }
            .onFailure {
                Log.e(LOG_TAG, "Could not start capture projection", it)
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
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Pokémon Champions 对局助手")
            .setContentText("仅在点击悬浮按钮或 HUD 识别按钮后读取游戏画面；数据保存在本机")
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "结束助手", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        releaseProjection()
        val bounds = windowManager.currentWindowMetrics.bounds
        val initialSpec = changedCaptureBufferSpec(
            current = null,
            width = bounds.width(),
            height = bounds.height(),
            densityDpi = resources.displayMetrics.densityDpi,
            rotation = currentCaptureRotation(),
        ) ?: error("当前屏幕尺寸无效：${bounds.width()}×${bounds.height()}")
        val manager = getSystemService(MediaProjectionManager::class.java)
        val activeProjection = manager.getMediaProjection(resultCode, resultData)
            ?: error("MediaProjectionManager 未返回有效会话")
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                publish("系统已停止屏幕共享，对局助手即将结束")
                requestStop()
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                scheduleCaptureResize(width, height, "capturedContentResize")
            }
        }
        activeProjection.registerCallback(callback, mainHandler)
        val thread = HandlerThread("capture-frames").apply { start() }
        val handler = Handler(thread.looper)
        val generation = captureGeneration + 1
        val reader = createImageReader(initialSpec, handler, generation)
        val display = activeProjection.createVirtualDisplay(
            "pokemon-champions-own-team",
            initialSpec.virtualDisplayWidth,
            initialSpec.virtualDisplayHeight,
            initialSpec.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        ) ?: error("MediaProjection 未返回有效虚拟显示")
        applyVirtualDisplayRotation(display, initialSpec.rotation)
        projection = activeProjection
        projectionCallback = callback
        imageReader = reader
        imageThread = thread
        imageHandler = handler
        virtualDisplay = display
        captureBufferSpec = initialSpec
        captureGeneration = generation
        frameTrackingEnabled = true
        Log.i(LOG_TAG, "Capture surface ready: $initialSpec, generation=$generation")
        publish("对局助手已就绪")
    }

    private fun createImageReader(
        spec: CaptureBufferSpec,
        handler: Handler,
        generation: Long,
    ): ImageReader = ImageReader.newInstance(
        spec.virtualDisplayWidth,
        spec.virtualDisplayHeight,
        PixelFormat.RGBA_8888,
        2,
    ).also { reader ->
        reader.setOnImageAvailableListener(
            {
                source -> updateLatestFrame(
                    source,
                    spec.virtualDisplayWidth,
                    spec.virtualDisplayHeight,
                    generation,
                )
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
            val oldReader = imageReader ?: return@post
            val nextGeneration = captureGeneration + 1
            val nextReader = runCatching { createImageReader(nextSpec, handler, nextGeneration) }
                .getOrElse { error ->
                    Log.e(LOG_TAG, "Could not create capture surface for $nextSpec", error)
                    publish("画面方向变化后暂时无法继续识别，请结束并重新启动对局助手")
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
                publish("画面方向变化后暂时无法继续识别，请结束并重新启动对局助手")
            }
        }
    }

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
                setStroke((2 * density).toInt(), Color.WHITE)
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
            val margin = (24 * density).toInt()
            val bounds = safeArea.currentRegion(preferEnd = true)
            x = (bounds.right - size - margin).coerceAtLeast(bounds.left)
            y = (bounds.top + (220 * density).toInt())
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
                    if (kotlin.math.hypot(event.rawX - downX, event.rawY - downY) < 18 * density) {
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
            battleOverlayController.showDirectHudEntry()
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
        if (::battleOverlayController.isInitialized) battleOverlayController.onSafeAreaChanged()
        if (::ownTeamCorrectionController.isInitialized) ownTeamCorrectionController.onSafeAreaChanged()
    }

    private fun scheduleOverlaySafeAreaRefresh() {
        if (destroyed) return
        // Some OEMs report the display change before WindowMetrics has published its
        // rotated bounds. Reflow once immediately and once after that update settles.
        mainHandler.post(::onOverlaySafeAreaChanged)
        mainHandler.postDelayed(::onOverlaySafeAreaChanged, 250L)
    }

    private fun showBubbleMenu(anchor: View) {
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
        PopupMenu(overlayWindowContext, anchor).apply {
            menu.add(0, 1, 0, "录入我的队伍")
            menu.add(0, 2, 1, "识别双方阵容")
            if (battleOverlayController.hasSession) {
                menu.add(0, 4, 2, "显示对战 HUD")
                menu.add(0, 8, 3, "打开详细面板")
            }
            if (importRepository.hasCorrectionDraft()) {
                menu.add(0, 7, 4, "继续核对我的队伍")
            }
            if (importRepository.hasPendingTeam()) {
                menu.add(0, 5, 5, "为我的队伍命名并保存")
            }
            menu.add(0, 6, 6, "结束对局助手")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> captureAndRecognizeOwnTeam()
                    2 -> captureAndRecognizeTeamPreview()
                    4 -> battleOverlayController.revealDirectHud()
                    5 -> showTeamNamePrompt()
                    6 -> requestStop()
                    7 -> openOwnTeamCorrection()
                    8 -> battleOverlayController.showPanel()
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
            if (destroyed) return@capture
            val hideWaitMs = (System.nanoTime() - requestedAt) / 1_000_000.0
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
                frameTrackingEnabled = true
                recognizing = false
                publish("暂时无法读取当前画面，请稍后重试")
                return@capture
            }
            publish("正在识别当前画面…")
            if (destroyed) {
                frame.recycle()
                return@capture
            }
            onFrame(frame, TeamPreviewCaptureTiming(requestedAt, hideWaitMs, frameCopyMs))
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
        if (importRepository.hasPendingTeam()) {
            publish("请先为上一支已识别队伍命名并保存")
            showTeamNamePrompt()
            return
        }
        captureFrame { frame, _ ->
            ocrEngine.recognize(frame) callback@{ result ->
                frame.recycle()
                if (destroyed) return@callback
                mainHandler.post {
                    if (destroyed) return@post
                    frameTrackingEnabled = true
                    recognizing = false
                    result.onSuccess { page ->
                        val saved = importRepository.accept(page)
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
        battleOverlayController.onTeamRecognitionStarted()
        captureFrame(useFrozenMenuFrame = useFrozenMenuFrame) { frame, captureTiming ->
            teamPreviewEngine.recognize(frame, captureTiming) callback@{ result ->
                frame.recycle()
                if (destroyed) return@callback
                mainHandler.post {
                    if (destroyed) return@post
                    frameTrackingEnabled = true
                    recognizing = false
                    result.onSuccess { preview ->
                        runCatching { teamPreviewRepository.save(preview) }
                            .onSuccess { saved ->
                                val clickToSavedMs = (System.nanoTime() - captureTiming.requestedAtNanos) / 1_000_000.0
                                Log.i(
                                    "TeamPreviewPerf",
                                    "privateReplaceMs=${saved.privateWriteMs}, clickToSavedMs=$clickToSavedMs",
                                )
                                publish(preview.summary())
                                battleOverlayController.showSetup()
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
        if (!importRepository.hasCorrectionDraft()) {
            publish("没有需要继续核对的队伍")
            if (projection == null) stopSelf()
            return
        }
        ownTeamCorrectionController.show()
    }

    private fun showTeamNamePrompt() {
        if (!importRepository.hasPendingTeam()) {
            publish("没有等待保存的队伍")
            return
        }
        if (teamNamePrompt != null) return
        removeBubble()
        val density = overlayWindowContext.resources.displayMetrics.density
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
                runCatching { importRepository.savePendingTeam(name) }
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
        val density = overlayWindowContext.resources.displayMetrics.density
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
                description = "点击悬浮按钮或 HUD 识别按钮时读取当前游戏画面并进行本地识别"
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
        val pending = pendingFrameCapture ?: return
        pendingFrameCapture = null
        pending.cancel()
        mainHandler.removeCallbacks(pending)
        recognizing = false
    }

    private fun requestStop() {
        cancelPendingFrameCapture()
        stopSelf()
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
        cancelPendingFrameCapture()
        ownTeamCorrectionController.close()
        battleOverlayController.closeAll()
        dismissTeamNamePrompt()
        removeBubble()
        bubbleParams = null
        releaseProjection()
        ocrEngine.close()
        teamPreviewEngine.close()
        damageRuntime.destroy()
        CaptureUiState.running.value = false
        CaptureUiState.message.value = "对局助手已结束"
        super.onDestroy()
    }
}
