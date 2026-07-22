package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

internal enum class BattleDirectHudElement {
    EDIT,
    REMATCH,
    TOGGLE,
    RECORDING,
    OWN_RECOGNITION,
    SPEED,
    STATUS,
    ASSUMPTION,
    OPPONENT_LEFT,
    OPPONENT_RIGHT,
    OWN_LEFT,
    OWN_RIGHT,
    DAMAGE,
    DETAIL,
}

internal enum class BattleDirectHudRecordingState(
    val buttonLabel: String,
    val canToggle: Boolean,
) {
    UNAVAILABLE("录像", false),
    IDLE("录像", true),
    PREPARING("录像准备", false),
    RUNNING("停止录像", true),
    STOPPING("录像保存", false),
}

internal enum class BattleDirectHudSection(val label: String) {
    BATTLEFIELD("战场状态"),
    OPPONENT_CONFIG("对手配置"),
    SPEED_LINE("速度线"),
}

internal data class BattleDirectHudAnchor(
    val xFraction: Float,
    val yFraction: Float,
    val widthFraction: Float? = null,
    val centeredX: Boolean = false,
)

internal object BattleDirectHudLayout {
    val anchors = mapOf(
        BattleDirectHudElement.EDIT to BattleDirectHudAnchor(0.295f, 0.015f, centeredX = true),
        BattleDirectHudElement.REMATCH to BattleDirectHudAnchor(0.38f, 0.015f, centeredX = true),
        BattleDirectHudElement.TOGGLE to BattleDirectHudAnchor(0.465f, 0.015f, centeredX = true),
        BattleDirectHudElement.RECORDING to BattleDirectHudAnchor(0.55f, 0.015f, centeredX = true),
        BattleDirectHudElement.OWN_RECOGNITION to BattleDirectHudAnchor(0.465f, 0.09f, centeredX = true),
        BattleDirectHudElement.SPEED to BattleDirectHudAnchor(0.015f, 0.266f, 0.205f),
        BattleDirectHudElement.STATUS to BattleDirectHudAnchor(0.015f, 0.092f),
        BattleDirectHudElement.ASSUMPTION to BattleDirectHudAnchor(0.775f, 0.335f),
        BattleDirectHudElement.OPPONENT_LEFT to BattleDirectHudAnchor(0.591f, 0.158f, 0.192f),
        BattleDirectHudElement.OPPONENT_RIGHT to BattleDirectHudAnchor(0.797f, 0.158f, 0.203f),
        BattleDirectHudElement.OWN_LEFT to BattleDirectHudAnchor(0.053f, 0.762f, 0.188f),
        BattleDirectHudElement.OWN_RIGHT to BattleDirectHudAnchor(0.251f, 0.762f, 0.193f),
        BattleDirectHudElement.DAMAGE to BattleDirectHudAnchor(0.021f, 0.665f, 0.43f),
        BattleDirectHudElement.DETAIL to BattleDirectHudAnchor(0.937f, 0.328f),
    )
}

internal fun resolveBattleDirectHudBounds(
    region: OverlayBounds,
    anchor: BattleDirectHudAnchor,
    desiredWidth: Int,
    desiredHeight: Int,
): OverlayBounds {
    val width = (anchor.widthFraction?.let { (region.width * it).roundToInt() } ?: desiredWidth)
        .coerceIn(1, region.width.coerceAtLeast(1))
    val height = desiredHeight.coerceIn(1, region.height.coerceAtLeast(1))
    val anchorX = region.left + (region.width * anchor.xFraction).roundToInt()
    val proposedX = if (anchor.centeredX) anchorX - width / 2 else anchorX
    val proposedY = region.top + (region.height * anchor.yFraction).roundToInt()
    val left = proposedX.coerceIn(region.left, (region.right - width).coerceAtLeast(region.left))
    val top = proposedY.coerceIn(region.top, (region.bottom - height).coerceAtLeast(region.top))
    return OverlayBounds(left, top, left + width, top + height)
}

internal fun normalizeBattleDirectHudSlots(slots: List<Int>, teamSize: Int): List<Int> {
    if (teamSize <= 0) return emptyList()
    val normalized = slots.map { it.coerceIn(0, teamSize - 1) }.distinct().toMutableList()
    (0 until teamSize).firstOrNull { it !in normalized }?.let { if (normalized.size < 2) normalized += it }
    while (normalized.size < 2) normalized += normalized.firstOrNull() ?: 0
    return normalized.take(2)
}

internal fun includeBattleDirectHudSlot(slots: List<Int>, selectedSlot: Int, teamSize: Int): List<Int> {
    val normalized = normalizeBattleDirectHudSlots(slots, teamSize)
    if (normalized.isEmpty()) return normalized
    val selected = selectedSlot.coerceIn(0, teamSize - 1)
    if (selected in normalized) return normalized
    return listOf(selected, normalized.firstOrNull { it != selected } ?: selected)
}

internal fun replaceBattleDirectHudSlot(slots: List<Int>, displayIndex: Int, teamSlot: Int): List<Int> {
    val result = slots.take(2).toMutableList()
    if (result.size < 2 || displayIndex !in 0..1) return result
    val otherIndex = 1 - displayIndex
    if (result[otherIndex] == teamSlot) {
        result[otherIndex] = result[displayIndex]
    }
    result[displayIndex] = teamSlot
    return result
}

internal fun activeBattleDirectSpeedActions(
    actions: List<SpeedLineAction>,
    ownSlots: List<Int>,
    opponentSlots: List<Int>,
): List<SpeedLineAction> = actions.filter { action ->
    action.priority == 0 && action.moveName == null && when (action.side) {
        SpeedSide.OWN -> action.slot in ownSlots
        SpeedSide.OPPONENT -> action.slot in opponentSlots
    }
}

private class BattleDirectHudEditFrame(
    context: Context,
    private val resizeZonePx: Int,
    private val onGestureStart: () -> Unit,
    private val onGestureDelta: (resizing: Boolean, deltaX: Int, deltaY: Int) -> Unit,
) : FrameLayout(context) {
    private var resizing = false
    private var startRawX = 0f
    private var startRawY = 0f

    init {
        isClickable = true
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizing = event.x >= width - resizeZonePx && event.y >= height - resizeZonePx
                startRawX = event.rawX
                startRawY = event.rawY
                onGestureStart()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                onGestureDelta(
                    resizing,
                    (event.rawX - startRawX).roundToInt(),
                    (event.rawY - startRawY).roundToInt(),
                )
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                resizing = false
                return true
            }
        }
        return true
    }
}

internal fun battleDirectSpeedRangesOverlap(first: IntRange, second: IntRange): Boolean =
    maxOf(first.first, second.first) <= minOf(first.last, second.last)

internal fun parseBattleDirectDamageValues(raw: String, configuredMoves: List<MoveValue>): List<String> {
    val envelope = JSONObject(raw)
    if (!envelope.optBoolean("ok")) return List(4) { index -> "${index + 1} ?" }
    val results = envelope.getJSONObject("result").getJSONArray("moveResults")
    val byId = (0 until results.length()).associate { index ->
        val result = results.getJSONObject(index)
        normalizeDirectId(result.optString("moveId")) to result
    }
    return (0 until 4).map { index ->
        val move = configuredMoves.getOrNull(index) ?: return@map "${index + 1} —"
        val result = sequenceOf(move.entity.canonicalId, move.entity.showdownId, move.entity.displayName)
            .map(::normalizeDirectId)
            .mapNotNull(byId::get)
            .firstOrNull()
            ?: return@map "${index + 1} ?"
        if (result.optString("moveCategory") == "Status") return@map "${index + 1} —"
        val range = result.optJSONObject("selectedProfileRange") ?: return@map "${index + 1} ?"
        val minimum = range.optDouble("minPercent", Double.NaN)
        val maximum = range.optDouble("maxPercent", Double.NaN)
        if (!minimum.isFinite() || !maximum.isFinite()) return@map "${index + 1} ?"
        if (maximum <= 0.0) "${index + 1} 0%" else String.format(
            Locale.US,
            "%d %.1f–%.1f%%",
            index + 1,
            minimum,
            maximum,
        )
    }
}

private fun normalizeDirectId(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "")

internal data class BattleDirectHudModel(
    val ownTeamNames: List<String>,
    val opponentTeamNames: List<String>,
    val ownSlots: List<Int>,
    val opponentSlots: List<Int>,
    val selectedOwnSlot: Int,
    val selectedOpponentSlot: Int,
    val speedActions: List<SpeedLineAction>,
    val trickRoom: Boolean,
    val statusText: String,
    val assumptionOptions: List<BattleDirectHudPresetOption>,
    val selectedAssumptionId: String,
    val recordingState: BattleDirectHudRecordingState = BattleDirectHudRecordingState.UNAVAILABLE,
    val hudVisible: Boolean = true,
    val sessionReady: Boolean = true,
    val damageValues: List<String> = listOf("1 …", "2 …", "3 …", "4 …"),
)

internal data class BattleDirectHudPresetOption(
    val profileId: String,
    val label: String,
)

internal class BattleDirectOverlayUi(
    private val context: Context,
    private val windowManager: WindowManager,
    private val safeArea: OverlaySafeAreaProvider,
    private val onSelectSlot: (SpeedSide, Int) -> Unit,
    private val onReplaceSlot: (SpeedSide, Int, Int) -> Unit,
    private val onToggleVisibility: (Boolean) -> Unit,
    private val onRecognizeTeamPreview: () -> Unit,
    private val onRecognizeOwnTeam: () -> Unit,
    private val onToggleRecording: () -> Unit,
    private val onOpenStatusSection: (BattleDirectHudSection) -> Unit,
    private val onSelectAssumption: (String) -> Unit,
    private val onOpenDetails: () -> Unit,
) {
    private data class WindowRecord(val view: View, val params: WindowManager.LayoutParams)

    private val density = context.resources.displayMetrics.density
    private val layoutStore = BattleDirectHudLayoutStore(context)
    private val windows = mutableMapOf<BattleDirectHudElement, WindowRecord>()
    private val layoutDrafts = mutableMapOf<String, MutableMap<BattleDirectHudElement, BattleDirectHudPlacement>>()
    private var model: BattleDirectHudModel? = null
    private var layoutEditing = false
    private var activeProfileKey = ""
    private var activePlacements: MutableMap<BattleDirectHudElement, BattleDirectHudPlacement> = mutableMapOf()
    private var damageLabels: List<TextView> = emptyList()
    private var recordingButton: Button? = null

    val isVisible: Boolean get() = model != null
    val isHudShown: Boolean get() = model?.hudVisible == true

    fun show(model: BattleDirectHudModel) {
        this.model = model
        removeWindows()
        val region = safeArea.currentRegion()
        prepareActiveLayout(region)
        addWindow(
            BattleDirectHudElement.REMATCH,
            compactButton("再战", onRecognizeTeamPreview).apply {
                contentDescription = "重新识别双方队伍"
            },
            region,
            desiredWidth = dp(64),
            desiredHeight = dp(30),
            interactive = true,
        )
        val toggleButton = compactButton(
            when {
                !model.sessionReady -> "等待阵容"
                model.hudVisible -> "隐藏 HUD"
                else -> "显示 HUD"
            },
        ) {
            if (model.sessionReady) onToggleVisibility(!model.hudVisible)
        }.apply {
            isEnabled = model.sessionReady
            alpha = if (isEnabled) 1f else 0.62f
        }
        addWindow(
            BattleDirectHudElement.TOGGLE,
            toggleButton,
            region,
            desiredWidth = dp(84),
            desiredHeight = dp(30),
            interactive = true,
        )
        val currentRecordingButton = compactButton(model.recordingState.buttonLabel, onToggleRecording).apply {
            contentDescription = model.recordingState.buttonLabel
            isEnabled = model.recordingState.canToggle
            alpha = if (isEnabled) 1f else 0.62f
        }
        recordingButton = currentRecordingButton
        addWindow(
            BattleDirectHudElement.RECORDING,
            currentRecordingButton,
            region,
            desiredWidth = dp(70),
            desiredHeight = dp(30),
            interactive = true,
        )
        addWindow(
            BattleDirectHudElement.OWN_RECOGNITION,
            compactButton("识别我方", onRecognizeOwnTeam).apply {
                contentDescription = "识别我的队伍"
            },
            region,
            desiredWidth = dp(84),
            desiredHeight = dp(30),
            interactive = true,
        )
        if (!model.sessionReady) {
            addWindow(
                BattleDirectHudElement.STATUS,
                compactButton("请点击“再战”识别双方阵容", onRecognizeTeamPreview).apply {
                    contentDescription = "尚无本局阵容，点击识别双方阵容"
                },
                region,
                desiredWidth = dp(180),
                desiredHeight = dp(34),
                interactive = true,
            )
            return
        }
        if (!model.hudVisible) {
            addLayoutEditButton(region)
            return
        }
        val speedZoneHeight = (region.height * (0.665f - 0.266f)).roundToInt() - dp(4)
        val speedHeight = minOf(dp(154), speedZoneHeight).coerceAtLeast(minOf(dp(96), region.height))

        addWindow(
            BattleDirectHudElement.SPEED,
            speedView(model),
            region,
            desiredWidth = dp(180),
            desiredHeight = speedHeight,
            interactive = false,
        )
        addWindow(
            BattleDirectHudElement.STATUS,
            statusButton(model.statusText),
            region,
            desiredWidth = dp(150),
            desiredHeight = dp(34),
            interactive = true,
        )
        addWindow(
            BattleDirectHudElement.ASSUMPTION,
            assumptionPicker(model),
            region,
            desiredWidth = dp(112),
            desiredHeight = dp(32),
            interactive = true,
        )
        addPicker(BattleDirectHudElement.OPPONENT_LEFT, SpeedSide.OPPONENT, 0, model, region)
        addPicker(BattleDirectHudElement.OPPONENT_RIGHT, SpeedSide.OPPONENT, 1, model, region)
        addPicker(BattleDirectHudElement.OWN_LEFT, SpeedSide.OWN, 0, model, region)
        addPicker(BattleDirectHudElement.OWN_RIGHT, SpeedSide.OWN, 1, model, region)
        addWindow(
            BattleDirectHudElement.DAMAGE,
            damageView(model.damageValues),
            region,
            desiredWidth = dp(300),
            desiredHeight = dp(40),
            interactive = false,
        )
        addWindow(
            BattleDirectHudElement.DETAIL,
            compactButton("详细", onOpenDetails),
            region,
            desiredWidth = dp(64),
            desiredHeight = dp(34),
            interactive = true,
        )
        addLayoutEditButton(region)
    }

    private fun prepareActiveLayout(region: OverlayBounds) {
        activeProfileKey = battleDirectHudLayoutProfileKey(region)
        activePlacements = if (layoutEditing) {
            layoutDrafts.getOrPut(activeProfileKey) {
                layoutStore.load(activeProfileKey).toMutableMap()
            }
        } else {
            layoutStore.load(activeProfileKey).toMutableMap()
        }
    }

    private fun addLayoutEditButton(region: OverlayBounds) {
        addWindow(
            BattleDirectHudElement.EDIT,
            compactButton(if (layoutEditing) "确认" else "调整", ::toggleLayoutEditing).apply {
                contentDescription = if (layoutEditing) "确认并保存 HUD 布局" else "调整 HUD 布局"
            },
            region,
            desiredWidth = dp(64),
            desiredHeight = dp(30),
            interactive = true,
        )
    }

    private fun toggleLayoutEditing() {
        val current = model ?: return
        if (!layoutEditing) {
            layoutEditing = true
            layoutDrafts.clear()
            Toast.makeText(
                context,
                "拖动部件调整位置；拖动右下角 ↘ 调整大小，部件不会超出安全区",
                Toast.LENGTH_LONG,
            ).show()
            if (current.hudVisible) show(current) else onToggleVisibility(true)
            return
        }

        val saved = layoutDrafts.all { (profileKey, placements) ->
            layoutStore.save(profileKey, placements)
        }
        if (!saved) {
            Toast.makeText(context, "HUD 布局保存失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        layoutEditing = false
        layoutDrafts.clear()
        Toast.makeText(context, "HUD 布局已保存", Toast.LENGTH_SHORT).show()
        show(current)
    }

    fun updateDamage(values: List<String>) {
        val fixed = (values + listOf("1 —", "2 —", "3 —", "4 —")).take(4)
        damageLabels.forEachIndexed { index, label -> label.text = fixed[index] }
        model = model?.copy(damageValues = fixed)
    }

    fun updateRecordingState(state: BattleDirectHudRecordingState) {
        model = model?.copy(recordingState = state)
        recordingButton?.apply {
            text = state.buttonLabel
            contentDescription = state.buttonLabel
            isEnabled = state.canToggle
            alpha = if (isEnabled) 1f else 0.62f
        }
    }

    fun reflow() {
        model?.let(::show)
    }

    fun dismiss() {
        model = null
        layoutEditing = false
        layoutDrafts.clear()
        removeWindows()
    }

    private fun removeWindows() {
        windows.values.forEach { record -> runCatching { windowManager.removeView(record.view) } }
        windows.clear()
        damageLabels = emptyList()
        recordingButton = null
    }

    private fun addPicker(
        element: BattleDirectHudElement,
        side: SpeedSide,
        displayIndex: Int,
        model: BattleDirectHudModel,
        region: OverlayBounds,
    ) {
        val teamNames = if (side == SpeedSide.OWN) model.ownTeamNames else model.opponentTeamNames
        val slots = if (side == SpeedSide.OWN) model.ownSlots else model.opponentSlots
        val selectedSlot = if (side == SpeedSide.OWN) model.selectedOwnSlot else model.selectedOpponentSlot
        val teamSlot = slots.getOrElse(displayIndex) { 0 }.coerceIn(0, teamNames.lastIndex.coerceAtLeast(0))
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(BACKGROUND, if (teamSlot == selectedSlot) SELECTED else BORDER, 9f, if (teamSlot == selectedSlot) 2 else 1)
        }
        val name = compactButton(teamNames.getOrElse(teamSlot) { "未确认" }) {
            onSelectSlot(side, teamSlot)
        }.apply {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        val arrow = compactButton("⌄") { }.apply {
            contentDescription = "更换${if (side == SpeedSide.OWN) "我方" else "对方"}${if (displayIndex == 0) "左位" else "右位"}宝可梦"
            setOnClickListener { anchor ->
                PopupMenu(context, anchor).apply {
                    teamNames.forEachIndexed { index, pokemonName ->
                        menu.add(0, index, index, "${index + 1}. $pokemonName${if (index == teamSlot) " · 当前" else ""}")
                    }
                    setOnMenuItemClickListener { item ->
                        onReplaceSlot(side, displayIndex, item.itemId)
                        true
                    }
                    show()
                }
            }
        }
        root.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(arrow, LinearLayout.LayoutParams(dp(38), ViewGroup.LayoutParams.MATCH_PARENT))
        addWindow(element, root, region, desiredWidth = dp(170), desiredHeight = dp(38), interactive = true)
    }

    private fun speedView(model: BattleDirectHudModel): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(5), dp(4), dp(5), dp(4))
        background = roundedBackground(BACKGROUND, BORDER, 9f)
        addView(textView(if (model.trickRoom) "四只顺序 · 慢→快" else "四只顺序 · 快→慢", bold = true))
        model.speedActions.take(4).forEachIndexed { index, action ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(3), dp(1), dp(3), dp(1))
                val current = (action.side == SpeedSide.OWN && action.slot == model.selectedOwnSlot) ||
                    (action.side == SpeedSide.OPPONENT && action.slot == model.selectedOpponentSlot)
                background = roundedBackground(if (current) SELECTED_SURFACE else SURFACE, if (action.side == SpeedSide.OWN) OWN else OPPONENT, 5f)
                addView(textView("${index + 1} ${if (action.side == SpeedSide.OWN) "我" else "对"}·${action.pokemonName}"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(textView(if (action.isPoint) action.speed.first.toString() else "${action.speed.first}–${action.speed.last}"))
            })
            if (index < model.speedActions.take(4).lastIndex) {
                val next = model.speedActions[index + 1]
                addView(textView(
                    if (battleDirectSpeedRangesOverlap(action.speed, next.speed)) "≈ 顺序未定" else "↓",
                    muted = true,
                    centered = true,
                ))
            }
        }
    }

    private fun damageView(values: List<String>): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(3), dp(3), dp(3), dp(3))
        background = roundedBackground(BACKGROUND, BORDER, 8f)
        damageLabels = (values + listOf("1 —", "2 —", "3 —", "4 —")).take(4).map { value ->
            textView(value, bold = true, centered = true).also { label ->
                label.background = roundedBackground(SURFACE, BORDER, 5f)
                addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = dp(1)
                    marginEnd = dp(1)
                })
            }
        }
    }

    private fun compactButton(text: String, action: () -> Unit): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 10f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(5), 0, dp(5), 0)
        setTextColor(TEXT)
        backgroundTintList = ColorStateList.valueOf(BACKGROUND)
        setOnClickListener { action() }
    }

    private fun statusButton(text: String): Button = compactButton(text) {}.apply {
        contentDescription = "打开状态设置"
        setOnClickListener { anchor ->
            PopupMenu(context, anchor).apply {
                BattleDirectHudSection.values().forEachIndexed { index, section ->
                    menu.add(0, index, index, section.label)
                }
                setOnMenuItemClickListener { item ->
                    BattleDirectHudSection.values().getOrNull(item.itemId)?.let(onOpenStatusSection) != null
                }
                show()
            }
        }
    }

    private fun assumptionPicker(model: BattleDirectHudModel): Button {
        val selected = model.assumptionOptions.firstOrNull { it.profileId == model.selectedAssumptionId }
            ?: model.assumptionOptions.firstOrNull()
        return compactButton("耐久：${selected?.label ?: "默认配置"} ▾") {}.apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = "选择敌方耐久预设，当前为${selected?.label ?: "默认配置"}"
            isEnabled = model.assumptionOptions.isNotEmpty()
            setOnClickListener { anchor ->
                PopupMenu(context, anchor).apply {
                    model.assumptionOptions.forEachIndexed { index, option ->
                        menu.add(0, index, index, option.label).apply {
                            isCheckable = true
                            isChecked = option.profileId == model.selectedAssumptionId
                        }
                    }
                    menu.setGroupCheckable(0, true, true)
                    setOnMenuItemClickListener { item ->
                        model.assumptionOptions.getOrNull(item.itemId)?.let { option ->
                            onSelectAssumption(option.profileId)
                            true
                        } ?: false
                    }
                    show()
                }
            }
        }
    }

    private fun textView(
        value: String,
        bold: Boolean = false,
        muted: Boolean = false,
        centered: Boolean = false,
    ): TextView = TextView(context).apply {
        text = value
        textSize = if (muted) 9f else 10f
        setTextColor(if (muted) TEXT_MUTED else TEXT)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        if (centered) gravity = Gravity.CENTER
    }

    private fun addWindow(
        element: BattleDirectHudElement,
        view: View,
        region: OverlayBounds,
        desiredWidth: Int,
        desiredHeight: Int,
        interactive: Boolean,
    ) {
        val anchor = requireNotNull(BattleDirectHudLayout.anchors[element])
        val defaultBounds = resolveBattleDirectHudBounds(region, anchor, desiredWidth, desiredHeight)
        val (minimumWidth, minimumHeight) = minimumHudSize(element, desiredWidth, desiredHeight, region)
        val bounds = activePlacements[element]?.let { placement ->
            resolveBattleDirectHudPlacement(region, placement, minimumWidth, minimumHeight)
        } ?: defaultBounds
        val editable = layoutEditing && element != BattleDirectHudElement.EDIT
        val params = WindowManager.LayoutParams(
            bounds.width,
            bounds.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (interactive || editable) overlayPanelWindowFlags(focusable = false) else PASSIVE_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }
        val windowView = if (editable) {
            activePlacements[element] = battleDirectHudPlacementFromBounds(region, bounds)
            editableContainer(element, view, region, params, minimumWidth, minimumHeight)
        } else {
            view
        }
        if (interactive && !editable) {
            configureOverlayFocus(context, windowManager, windowView, params, initiallyFocusable = false)
        }
        windowManager.addView(windowView, params)
        windows[element] = WindowRecord(windowView, params)
    }

    private fun minimumHudSize(
        element: BattleDirectHudElement,
        desiredWidth: Int,
        desiredHeight: Int,
        region: OverlayBounds,
    ): Pair<Int, Int> {
        val requested = when (element) {
            BattleDirectHudElement.SPEED -> dp(120) to dp(90)
            BattleDirectHudElement.DAMAGE -> dp(180) to dp(36)
            BattleDirectHudElement.OPPONENT_LEFT,
            BattleDirectHudElement.OPPONENT_RIGHT,
            BattleDirectHudElement.OWN_LEFT,
            BattleDirectHudElement.OWN_RIGHT -> dp(110) to dp(34)
            else -> minOf(desiredWidth, dp(56)) to minOf(desiredHeight, dp(30))
        }
        return requested.first.coerceIn(1, region.width.coerceAtLeast(1)) to
            requested.second.coerceIn(1, region.height.coerceAtLeast(1))
    }

    private fun editableContainer(
        element: BattleDirectHudElement,
        content: View,
        region: OverlayBounds,
        params: WindowManager.LayoutParams,
        minimumWidth: Int,
        minimumHeight: Int,
    ): View {
        var startX = params.x
        var startY = params.y
        var startWidth = params.width
        var startHeight = params.height
        lateinit var container: BattleDirectHudEditFrame

        fun updatePlacement() {
            activePlacements[element] = battleDirectHudPlacementFromBounds(
                region,
                OverlayBounds(params.x, params.y, params.x + params.width, params.y + params.height),
            )
            if (container.isAttachedToWindow) {
                runCatching { windowManager.updateViewLayout(container, params) }
            }
        }

        container = BattleDirectHudEditFrame(
            context = context,
            resizeZonePx = dp(28),
            onGestureStart = {
                startX = params.x
                startY = params.y
                startWidth = params.width
                startHeight = params.height
            },
            onGestureDelta = { resizing, deltaX, deltaY ->
                if (resizing) {
                    val maximumWidth = (region.right - params.x).coerceAtLeast(minimumWidth)
                    val maximumHeight = (region.bottom - params.y).coerceAtLeast(minimumHeight)
                    params.width = (startWidth + deltaX).coerceIn(minimumWidth, maximumWidth)
                    params.height = (startHeight + deltaY).coerceIn(minimumHeight, maximumHeight)
                } else {
                    params.x = (startX + deltaX).coerceIn(
                        region.left,
                        (region.right - params.width).coerceAtLeast(region.left),
                    )
                    params.y = (startY + deltaY).coerceIn(
                        region.top,
                        (region.bottom - params.height).coerceAtLeast(region.top),
                    )
                }
                updatePlacement()
            },
        ).apply {
            foreground = roundedBackground(Color.TRANSPARENT, SELECTED, 8f, 2)
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(TextView(context).apply {
                text = "↘"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(SELECTED)
                background = roundedBackground(Color.argb(220, 14, 20, 27), SELECTED, 4f)
                contentDescription = "拖动以调整大小"
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.END or Gravity.BOTTOM))
        }
        return container
    }

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Float, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(strokeDp).coerceAtLeast(1), stroke)
    }

    private fun dp(value: Int) = (value * density).roundToInt()
    private fun dp(value: Float) = (value * density).roundToInt()

    private companion object {
        val BACKGROUND = Color.argb(222, 14, 20, 27)
        val SURFACE = Color.argb(232, 28, 39, 49)
        val SELECTED_SURFACE = Color.argb(238, 46, 56, 48)
        val BORDER = Color.rgb(86, 105, 118)
        val SELECTED = Color.rgb(244, 197, 66)
        val OWN = Color.rgb(72, 178, 255)
        val OPPONENT = Color.rgb(255, 145, 76)
        val TEXT = Color.rgb(244, 248, 251)
        val TEXT_MUTED = Color.rgb(192, 204, 214)
        const val PASSIVE_FLAGS =
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
}
