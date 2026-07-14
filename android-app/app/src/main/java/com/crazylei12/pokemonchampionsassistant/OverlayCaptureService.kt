package com.crazylei12.pokemonchampionsassistant

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import android.view.Gravity
import android.view.MotionEvent
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

object CaptureUiState {
    val message = mutableStateOf("尚未启动悬浮识别会话")
    val running = mutableStateOf(false)
    val lastSavedFile = mutableStateOf("")
    val teamLibraryRevision = mutableStateOf(0)
}

class OverlayCaptureService : Service() {
    companion object {
        private const val ACTION_START = "com.crazylei12.pokemonchampionsassistant.START_CAPTURE"
        private const val ACTION_STOP = "com.crazylei12.pokemonchampionsassistant.STOP_CAPTURE"
        private const val ACTION_DEBUG_RECOGNIZE_TEAM_PREVIEW =
            "com.crazylei12.pokemonchampionsassistant.DEBUG_RECOGNIZE_TEAM_PREVIEW"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "own_team_capture"
        private const val NOTIFICATION_ID = 4102

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, OverlayCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayCaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var ocrEngine: OwnTeamOcrEngine
    private lateinit var importRepository: OwnTeamImportRepository
    private lateinit var teamPreviewEngine: TeamPreviewRecognitionEngine
    private lateinit var teamPreviewRepository: TeamPreviewResultRepository
    private lateinit var damageRuntime: DamageEngineRuntime
    private lateinit var battleOverlayController: BattleOverlayController
    private var bubble: View? = null
    private var teamNamePrompt: View? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private val bitmapLock = Any()
    private var latestBitmap: Bitmap? = null
    private var frozenMenuBitmap: Bitmap? = null
    private var frozenMenuFrameCopyMs = 0.0
    @Volatile private var frameTrackingEnabled = true
    @Volatile private var recognizing = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        ocrEngine = OwnTeamOcrEngine(this)
        importRepository = OwnTeamImportRepository(this)
        teamPreviewEngine = TeamPreviewRecognitionEngine(this)
        teamPreviewEngine.prepare()
        teamPreviewRepository = TeamPreviewResultRepository(this)
        damageRuntime = DamageEngineRuntime(this)
        val battleSessionRepository = BattleSessionRepository(this)
        battleOverlayController = BattleOverlayController(
            context = this,
            windowManager = windowManager,
            runtime = damageRuntime,
            sessionRepository = battleSessionRepository,
            presetRepository = OpponentPresetRepository(this),
            publish = ::publish,
            onOverlayVisible = { visible ->
                bubble?.visibility = if (visible) View.INVISIBLE else View.VISIBLE
            },
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent?.action == ACTION_DEBUG_RECOGNIZE_TEAM_PREVIEW) {
            if (projection == null) {
                publish("屏幕截图会话尚未就绪")
            } else {
                captureAndRecognizeTeamPreview(useFrozenMenuFrame = false)
            }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        startProjectionForeground()
        if (!Settings.canDrawOverlays(this)) {
            publish("缺少悬浮窗权限；请回到 App 授权后重新开始")
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
            publish("屏幕截图授权无效，请重新授权")
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { startProjection(resultCode, resultData) }
            .onSuccess {
                showBubble()
                CaptureUiState.running.value = true
                publish("悬浮识别已启动；打开照片后点击悬浮按钮")
            }
            .onFailure {
                publish("启动截图会话失败：${it.message}")
                stopSelf()
            }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            .setContentTitle("冠军实战伤害助手")
            .setContentText("本地截图识别与伤害计算；不录制、不上传")
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "结束会话", stop)
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
        val bounds = windowManager.maximumWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val density = resources.displayMetrics.densityDpi
        val manager = getSystemService(MediaProjectionManager::class.java)
        val activeProjection = manager.getMediaProjection(resultCode, resultData)
            ?: error("MediaProjectionManager 未返回有效会话")
        activeProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                publish("系统已结束屏幕截图会话")
                stopSelf()
            }
        }, mainHandler)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val thread = HandlerThread("capture-frames").apply { start() }
        reader.setOnImageAvailableListener({ source -> updateLatestFrame(source, width, height) }, Handler(thread.looper))
        val display = activeProjection.createVirtualDisplay(
            "pokemon-champions-own-team",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            Handler(thread.looper),
        )
        projection = activeProjection
        imageReader = reader
        imageThread = thread
        virtualDisplay = display
        frameTrackingEnabled = true
        publish("截图会话已就绪：${width}×$height")
    }

    private fun updateLatestFrame(reader: ImageReader, width: Int, height: Int) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (!frameTrackingEnabled) return
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val paddedWidth = width + rowPadding / plane.pixelStride
            synchronized(bitmapLock) {
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
        if (bubble != null) return
        val size = (64 * resources.displayMetrics.density).toInt()
        val view = TextView(this).apply {
            text = "实战\n助手"
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
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 220
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
                    params.x = (originalX - (event.rawX - downX)).toInt().coerceAtLeast(0)
                    params.y = (originalY + (event.rawY - downY)).toInt().coerceAtLeast(0)
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
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "识别屏幕上的队伍")
            menu.add(0, 2, 1, "识别当前屏幕上的双方队伍")
            if (battleOverlayController.hasPreview) {
                menu.add(0, 3, 2, "确认双方队伍并开始对战")
            }
            if (battleOverlayController.hasSession) {
                menu.add(0, 4, 3, "打开实战伤害面板")
            }
            if (importRepository.hasPendingTeam()) {
                menu.add(0, 5, 4, "为已识别队伍命名并保存")
            }
            menu.add(0, 6, 5, "结束实战助手")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> captureAndRecognizeOwnTeam()
                    2 -> captureAndRecognizeTeamPreview()
                    3 -> battleOverlayController.showSetup()
                    4 -> battleOverlayController.showPanel()
                    5 -> showTeamNamePrompt()
                    6 -> stopSelf()
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
        actionLabel: String,
        useFrozenMenuFrame: Boolean = false,
        onFrame: (Bitmap, TeamPreviewCaptureTiming) -> Unit,
    ) {
        if (recognizing) {
            toast("上一张图片仍在识别")
            return
        }
        recognizing = true
        val requestedAt = System.nanoTime()
        bubble?.visibility = View.INVISIBLE
        publish("正在抓取当前画面…")
        if (!useFrozenMenuFrame) {
            synchronized(bitmapLock) {
                frozenMenuBitmap?.recycle()
                frozenMenuBitmap = null
                frameTrackingEnabled = true
            }
        }
        mainHandler.postDelayed({
            val hideWaitMs = (System.nanoTime() - requestedAt) / 1_000_000.0
            val copyStarted = System.nanoTime()
            val frame = synchronized(bitmapLock) {
                frameTrackingEnabled = false
                if (useFrozenMenuFrame) frozenMenuBitmap.also { frozenMenuBitmap = null }
                else copyLatestFrameLocked()
            }
            val selectionCopyMs = (System.nanoTime() - copyStarted) / 1_000_000.0
            val frameCopyMs = selectionCopyMs + if (useFrozenMenuFrame) frozenMenuFrameCopyMs else 0.0
            bubble?.visibility = View.VISIBLE
            if (frame == null) {
                frameTrackingEnabled = true
                recognizing = false
                publish("尚未取得屏幕帧，请稍后重试")
                return@postDelayed
            }
            publish("已抓取 ${frame.width}×${frame.height}，正在离线$actionLabel…")
            onFrame(frame, TeamPreviewCaptureTiming(requestedAt, hideWaitMs, frameCopyMs))
        }, if (useFrozenMenuFrame) 0 else 1_000)
    }

    private fun copyLatestFrameLocked(): Bitmap? = latestBitmap?.let { source ->
        Bitmap.createBitmap(
            source.width.coerceAtMost(windowManager.maximumWindowMetrics.bounds.width()),
            source.height,
            Bitmap.Config.ARGB_8888,
        ).also { Canvas(it).drawBitmap(source, 0f, 0f, null) }
    }

    private fun captureAndRecognizeOwnTeam() {
        if (importRepository.hasPendingTeam()) {
            publish("请先为上一支已识别队伍命名并保存")
            showTeamNamePrompt()
            return
        }
        captureFrame(" OCR") { frame, _ ->
            ocrEngine.recognize(frame) { result ->
                frame.recycle()
                mainHandler.post {
                    frameTrackingEnabled = true
                    recognizing = false
                    result.onSuccess { page ->
                        val saved = importRepository.accept(page)
                        saved.savedFileName?.let { CaptureUiState.lastSavedFile.value = it }
                        publish(saved.message)
                        if (saved.savedJson != null && saved.savedFileName == null) {
                            showTeamNamePrompt()
                        }
                    }.onFailure { error ->
                        publish("识别失败：${error.message}")
                    }
                }
            }
        }
    }

    private fun captureAndRecognizeTeamPreview(useFrozenMenuFrame: Boolean = true) {
        captureFrame("双方队伍 ROI", useFrozenMenuFrame = useFrozenMenuFrame) { frame, captureTiming ->
            teamPreviewEngine.recognize(frame, captureTiming) { result ->
                frame.recycle()
                mainHandler.post {
                    frameTrackingEnabled = true
                    recognizing = false
                    result.onSuccess { preview ->
                        runCatching { teamPreviewRepository.save(preview) }
                            .onSuccess { saved ->
                                CaptureUiState.lastSavedFile.value = saved.fileName
                                val clickToSavedMs = (System.nanoTime() - captureTiming.requestedAtNanos) / 1_000_000.0
                                Log.i(
                                    "TeamPreviewPerf",
                                    "privateReplaceMs=${saved.privateWriteMs}, clickToSavedMs=$clickToSavedMs",
                                )
                                publish(preview.summary(saved.fileName))
                                battleOverlayController.showSetup()
                            }
                            .onFailure { error -> publish("双方队伍 ROI 结果保存失败：${error.message}") }
                    }.onFailure { error ->
                        publish("双方队伍 ROI 识别失败：${error.message}")
                    }
                }
            }
        }
    }

    private fun showTeamNamePrompt() {
        if (!importRepository.hasPendingTeam()) {
            publish("没有等待命名的队伍")
            return
        }
        if (teamNamePrompt != null) return
        bubble?.visibility = View.INVISIBLE
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                setColor(Color.rgb(31, 40, 58))
                cornerRadius = 18 * density
                setStroke((1 * density).toInt().coerceAtLeast(1), Color.rgb(119, 215, 196))
            }
        }
        root.addView(TextView(this).apply {
            text = "保存自己的队伍"
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "双图识别已完成。请输入以后在首页和计算页看到的队伍名称。"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, (12 * density).toInt(), 0, (8 * density).toInt())
        })
        val input = EditText(this).apply {
            hint = "例如：雨天队"
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            maxLines = 1
        }
        root.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        actions.addView(Button(this).apply {
            text = "稍后命名"
            setOnClickListener {
                dismissTeamNamePrompt()
                publish("队伍尚未保存；可再次点击悬浮按钮选择“为已识别队伍命名并保存”")
            }
        })
        actions.addView(Button(this).apply {
            text = "保存队伍"
            setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    input.error = "请输入队伍名称"
                    return@setOnClickListener
                }
                runCatching { importRepository.savePendingTeam(name) }
                    .onSuccess { saved ->
                        saved.savedFileName?.let { CaptureUiState.lastSavedFile.value = it }
                        CaptureUiState.teamLibraryRevision.value += 1
                        dismissTeamNamePrompt()
                        publish(saved.message)
                    }
                    .onFailure { error -> input.error = error.message ?: "保存失败" }
            }
        })
        root.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        val bounds = windowManager.maximumWindowMetrics.bounds
        val params = WindowManager.LayoutParams(
            minOf((720 * density).toInt(), bounds.width() - (48 * density).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        windowManager.addView(root, params)
        teamNamePrompt = root
        input.requestFocus()
        mainHandler.postDelayed({
            getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun dismissTeamNamePrompt() {
        teamNamePrompt?.let { prompt ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(prompt.windowToken, 0)
            runCatching { windowManager.removeView(prompt) }
        }
        teamNamePrompt = null
        bubble?.visibility = View.VISIBLE
    }

    private fun publish(message: String) {
        mainHandler.post {
            CaptureUiState.message.value = message
            toast(message)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "悬浮截图识别", NotificationManager.IMPORTANCE_LOW).apply {
                description = "用户主动点击悬浮按钮时读取单张当前画面"
            }
        )
    }

    private fun releaseProjection() {
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.stop(); projection = null
        imageThread?.quitSafely(); imageThread = null
        synchronized(bitmapLock) {
            latestBitmap?.recycle(); latestBitmap = null
            frozenMenuBitmap?.recycle(); frozenMenuBitmap = null
        }
        frameTrackingEnabled = false
    }

    override fun onDestroy() {
        battleOverlayController.closeAll()
        dismissTeamNamePrompt()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        releaseProjection()
        ocrEngine.close()
        teamPreviewEngine.close()
        damageRuntime.destroy()
        CaptureUiState.running.value = false
        CaptureUiState.message.value = "悬浮识别会话已结束"
        super.onDestroy()
    }
}
