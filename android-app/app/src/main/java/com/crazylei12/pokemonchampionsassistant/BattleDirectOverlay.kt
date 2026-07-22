package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

internal enum class BattleDirectHudElement {
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
    val assumptionText: String,
    val recordingState: BattleDirectHudRecordingState = BattleDirectHudRecordingState.UNAVAILABLE,
    val hudVisible: Boolean = true,
    val damageValues: List<String> = listOf("1 …", "2 …", "3 …", "4 …"),
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
    private val onOpenDetails: () -> Unit,
) {
    private data class WindowRecord(val view: View, val params: WindowManager.LayoutParams)

    private val density = context.resources.displayMetrics.density
    private val windows = mutableMapOf<BattleDirectHudElement, WindowRecord>()
    private var model: BattleDirectHudModel? = null
    private var damageLabels: List<TextView> = emptyList()
    private var recordingButton: Button? = null

    val isVisible: Boolean get() = model != null
    val isHudShown: Boolean get() = model?.hudVisible == true

    fun show(model: BattleDirectHudModel) {
        this.model = model
        removeWindows()
        val region = safeArea.currentRegion()
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
        addWindow(
            BattleDirectHudElement.TOGGLE,
            compactButton(if (model.hudVisible) "隐藏 HUD" else "显示 HUD") {
                onToggleVisibility(!model.hudVisible)
            },
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
        if (!model.hudVisible) return
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
            compactButton(model.assumptionText) {
                onOpenStatusSection(BattleDirectHudSection.OPPONENT_CONFIG)
            },
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
        val bounds = resolveBattleDirectHudBounds(region, anchor, desiredWidth, desiredHeight)
        val params = WindowManager.LayoutParams(
            bounds.width,
            bounds.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (interactive) overlayPanelWindowFlags(focusable = false) else PASSIVE_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }
        if (interactive) configureOverlayFocus(context, windowManager, view, params, initiallyFocusable = false)
        windowManager.addView(view, params)
        windows[element] = WindowRecord(view, params)
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
