package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
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

private const val DIRECT_HUD_LOG_TAG = "BattleDirectHud"

internal enum class BattleDirectHudElement {
    EDIT,
    REMATCH,
    TOGGLE,
    RECORDING,
    FORMAT,
    OWN_RECOGNITION,
    OWN_RECOGNITION_STATUS,
    MATCHUP,
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
        BattleDirectHudElement.FORMAT to BattleDirectHudAnchor(0.635f, 0.015f, centeredX = true),
        BattleDirectHudElement.OWN_RECOGNITION to BattleDirectHudAnchor(0.75f, 0.015f, centeredX = true),
        BattleDirectHudElement.OWN_RECOGNITION_STATUS to BattleDirectHudAnchor(0.465f, 0.14f, centeredX = true),
        BattleDirectHudElement.MATCHUP to BattleDirectHudAnchor(0.437f, 0.148f, 0.304f),
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

internal fun prioritizeBattleDirectHudSlot(slots: List<Int>, selectedSlot: Int, teamSize: Int): List<Int> {
    if (teamSize <= 0) return emptyList()
    val selected = selectedSlot.coerceIn(0, teamSize - 1)
    return normalizeBattleDirectHudSlots(
        listOf(selected) + slots.filter { it != selected },
        teamSize,
    )
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

internal data class BattleDirectHudPickerSpec(
    val element: BattleDirectHudElement,
    val side: SpeedSide,
    val displayIndex: Int,
    val positionLabel: String,
)

internal fun battleDirectHudSlotsPerSide(battleType: String): Int =
    if (battleType == "DOUBLE") 2 else 1

internal fun battleDirectHudPickerSpecs(battleType: String): List<BattleDirectHudPickerSpec> =
    if (battleType == "DOUBLE") {
        listOf(
            BattleDirectHudPickerSpec(BattleDirectHudElement.OPPONENT_LEFT, SpeedSide.OPPONENT, 0, "左位"),
            BattleDirectHudPickerSpec(BattleDirectHudElement.OPPONENT_RIGHT, SpeedSide.OPPONENT, 1, "右位"),
            BattleDirectHudPickerSpec(BattleDirectHudElement.OWN_LEFT, SpeedSide.OWN, 0, "左位"),
            BattleDirectHudPickerSpec(BattleDirectHudElement.OWN_RIGHT, SpeedSide.OWN, 1, "右位"),
        )
    } else {
        listOf(
            BattleDirectHudPickerSpec(BattleDirectHudElement.OPPONENT_RIGHT, SpeedSide.OPPONENT, 0, "场上"),
            BattleDirectHudPickerSpec(BattleDirectHudElement.OWN_LEFT, SpeedSide.OWN, 0, "场上"),
        )
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

internal fun battleDamageCacheKey(request: String): String = JSONObject(request).apply {
    remove("requestId")
}.toString()

internal data class BattleDirectHudModel(
    val battleType: String,
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
    val mode: BattleDirectHudMode = BattleDirectHudMode.TYPE_MATCHUP,
    val sessionReady: Boolean = true,
    val damageValues: List<String> = listOf("1 …", "2 …", "3 …", "4 …"),
    val ownTeamRecognition: OwnTeamRecognitionHudState = OwnTeamRecognitionHudState(),
    val typeMatchups: List<BattleTypeMatchup> = emptyList(),
)

internal data class BattleTypeMatchup(
    val speciesName: String,
    val groups: Map<String, List<String>>,
)

internal data class OwnTeamRecognitionHudState(
    val buttonLabel: String = "识别我方",
    val message: String? = null,
    val inProgress: Boolean = false,
)

internal data class BattleDirectHudPresetOption(
    val profileId: String,
    val label: String,
)

internal fun shouldRebuildBattleDirectHudWindows(
    previous: BattleDirectHudModel?,
    next: BattleDirectHudModel,
    layoutEditing: Boolean,
    hasWindows: Boolean,
): Boolean = previous == null ||
    layoutEditing ||
    !hasWindows ||
    previous.sessionReady != next.sessionReady ||
    previous.battleType != next.battleType ||
    previous.mode != next.mode ||
    previous.typeMatchups != next.typeMatchups ||
    previous.ownTeamRecognition != next.ownTeamRecognition

internal class BattleDirectOverlayUi(
    private val context: Context,
    private val windowManager: WindowManager,
    private val safeArea: OverlaySafeAreaProvider,
    private val onSelectSlot: (SpeedSide, Int) -> Unit,
    private val onReplaceSlot: (SpeedSide, Int, Int) -> Unit,
    private val onCycleMode: () -> Unit,
    private val onToggleBattleType: () -> Unit,
    private val onRecognizeTeamPreview: () -> Unit,
    private val onRecognizeOwnTeam: () -> Unit,
    private val onToggleRecording: () -> Unit,
    private val onOpenStatusSection: (BattleDirectHudSection) -> Unit,
    private val onSelectAssumption: (String) -> Unit,
    private val onOpenDetails: () -> Unit,
) {
    private data class WindowRecord(
        val view: View,
        val params: WindowManager.LayoutParams,
        val desiredWidth: Int,
        val desiredHeight: Int,
    )
    private data class PickerViews(
        val root: LinearLayout,
        val name: Button,
        val side: SpeedSide,
        val displayIndex: Int,
    )

    private val density = context.resources.displayMetrics.density
    private val layoutStore = BattleDirectHudLayoutStore(context)
    private val windows = mutableMapOf<BattleDirectHudElement, WindowRecord>()
    private val layoutDrafts = mutableMapOf<String, MutableMap<BattleDirectHudElement, BattleDirectHudPlacement>>()
    private var model: BattleDirectHudModel? = null
    private var layoutEditing = false
    private var activeProfileKey = ""
    private var activePlacements: MutableMap<BattleDirectHudElement, BattleDirectHudPlacement> = mutableMapOf()
    private var damageLabels: List<TextView> = emptyList()
    private val pickerViews = mutableMapOf<BattleDirectHudElement, PickerViews>()
    private var toggleButton: Button? = null
    private var recordingButton: Button? = null
    private var speedContainer: LinearLayout? = null
    private var statusControl: Button? = null
    private var assumptionControl: Button? = null

    val isVisible: Boolean get() = model != null
    val isCalculationShown: Boolean get() = model?.mode == BattleDirectHudMode.CALCULATION

    fun show(next: BattleDirectHudModel) {
        val previous = model
        if (!shouldRebuildBattleDirectHudWindows(previous, next, layoutEditing, windows.isNotEmpty())) {
            model = next
            updateWindowsInPlace(next)
            return
        }
        rebuild(next)
    }

    private fun rebuild(next: BattleDirectHudModel) {
        model = next
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
            battleDirectHudToggleLabel(next.sessionReady, next.mode),
        ) {
            val current = model ?: return@compactButton
            if (current.sessionReady) onCycleMode()
        }.apply {
            isEnabled = next.sessionReady
            alpha = if (isEnabled) 1f else 0.62f
            applyToggleButtonStyle(this, isEnabled)
        }
        this.toggleButton = toggleButton
        addWindow(
            BattleDirectHudElement.TOGGLE,
            toggleButton,
            region,
            desiredWidth = dp(84),
            desiredHeight = dp(30),
            interactive = true,
        )
        val currentRecordingButton = compactButton(next.recordingState.buttonLabel, onToggleRecording).apply {
            contentDescription = next.recordingState.buttonLabel
            isEnabled = next.recordingState.canToggle
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
        val formatButton = compactButton(
            if (!next.sessionReady) "格式" else if (next.battleType == "DOUBLE") "双打" else "单打",
            onToggleBattleType,
        ).apply {
            contentDescription = when {
                !next.sessionReady -> "等待阵容后可切换单打或双打"
                next.battleType == "DOUBLE" -> "当前双打，点击切换到单打"
                else -> "当前单打，点击切换到双打"
            }
            isEnabled = next.sessionReady
            alpha = if (isEnabled) 1f else 0.62f
        }
        addWindow(
            BattleDirectHudElement.FORMAT,
            formatButton,
            region,
            desiredWidth = dp(64),
            desiredHeight = dp(30),
            interactive = true,
        )
        val ownRecognitionButton = compactButton(
            next.ownTeamRecognition.buttonLabel,
            onRecognizeOwnTeam,
        ).apply {
            contentDescription = next.ownTeamRecognition.message ?: "识别我的队伍"
            isEnabled = !next.ownTeamRecognition.inProgress
            alpha = if (isEnabled) 1f else 0.72f
        }
        addWindow(
            BattleDirectHudElement.OWN_RECOGNITION,
            ownRecognitionButton,
            region,
            desiredWidth = dp(112),
            desiredHeight = dp(30),
            interactive = true,
        )
        next.ownTeamRecognition.message?.takeIf(String::isNotBlank)?.let { message ->
            addWindow(
                BattleDirectHudElement.OWN_RECOGNITION_STATUS,
                recognitionStatusView(message),
                region,
                desiredWidth = dp(230),
                desiredHeight = dp(30),
                interactive = false,
            )
        }
        if (!next.sessionReady) {
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
            restoreWindowLayerOrder()
            return
        }
        if (next.mode == BattleDirectHudMode.HIDDEN) {
            addLayoutEditButton(region)
            restoreWindowLayerOrder()
            return
        }
        if (next.mode == BattleDirectHudMode.TYPE_MATCHUP) {
            addWindow(
                BattleDirectHudElement.MATCHUP,
                typeMatchupView(next.typeMatchups),
                region,
                desiredWidth = dp(244),
                desiredHeight = (region.height * 0.72f).roundToInt(),
                interactive = false,
            )
            addLayoutEditButton(region)
            restoreWindowLayerOrder()
            return
        }
        val speedZoneHeight = (region.height * (0.665f - 0.266f)).roundToInt() - dp(4)
        val preferredSpeedHeight = if (next.battleType == "DOUBLE") dp(154) else dp(96)
        val speedHeight = minOf(preferredSpeedHeight, speedZoneHeight).coerceAtLeast(minOf(dp(96), region.height))

        addWindow(
            BattleDirectHudElement.SPEED,
            speedView(next),
            region,
            desiredWidth = dp(180),
            desiredHeight = speedHeight,
            interactive = false,
        )
        addWindow(
            BattleDirectHudElement.STATUS,
            statusButton(next.statusText),
            region,
            desiredWidth = dp(150),
            desiredHeight = dp(34),
            interactive = true,
        )
        addWindow(
            BattleDirectHudElement.ASSUMPTION,
            assumptionPicker(next),
            region,
            desiredWidth = dp(112),
            desiredHeight = dp(32),
            interactive = true,
        )
        battleDirectHudPickerSpecs(next.battleType).forEach { picker ->
            addPicker(
                picker.element,
                picker.side,
                picker.displayIndex,
                picker.positionLabel,
                next,
                region,
            )
        }
        addWindow(
            BattleDirectHudElement.DAMAGE,
            damageView(next.damageValues),
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
        restoreWindowLayerOrder()
    }

    private fun updateWindowsInPlace(next: BattleDirectHudModel) {
        toggleButton?.apply {
            text = battleDirectHudToggleLabel(next.sessionReady, next.mode)
            isEnabled = next.sessionReady
            alpha = if (isEnabled) 1f else 0.62f
            applyToggleButtonStyle(this, isEnabled)
        }
        updateRecordingState(next.recordingState)
        if (!next.sessionReady || next.mode != BattleDirectHudMode.CALCULATION) return
        renderSpeedView(next)
        statusControl?.text = next.statusText
        updateAssumptionControl(next)
        pickerViews.values.forEach { updatePicker(it, next) }
        updateDamage(next.damageValues)
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
            if (current.mode != BattleDirectHudMode.HIDDEN) rebuild(current) else onCycleMode()
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
        rebuild(current)
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

    fun updateOwnTeamRecognitionState(state: OwnTeamRecognitionHudState) {
        model?.let { show(it.copy(ownTeamRecognition = state)) }
    }

    fun reflow() {
        val current = model ?: return
        if (windows.isEmpty()) {
            rebuild(current)
            return
        }

        val region = safeArea.currentRegion()
        prepareActiveLayout(region)
        windows.forEach { (element, record) ->
            val (desiredWidth, desiredHeight) = desiredSizeForReflow(element, record, current, region)
            val bounds = resolveWindowBounds(element, region, desiredWidth, desiredHeight)
            record.params.width = bounds.width
            record.params.height = bounds.height
            record.params.x = bounds.left
            record.params.y = bounds.top
            runCatching { windowManager.updateViewLayout(record.view, record.params) }
                .onFailure { error ->
                    Log.e(
                        DIRECT_HUD_LOG_TAG,
                        "Could not reflow $element inside $region; keeping the existing HUD window attached",
                        error,
                    )
                }
        }
    }

    private fun desiredSizeForReflow(
        element: BattleDirectHudElement,
        record: WindowRecord,
        current: BattleDirectHudModel,
        region: OverlayBounds,
    ): Pair<Int, Int> {
        if (element != BattleDirectHudElement.SPEED) {
            return record.desiredWidth to record.desiredHeight
        }
        val speedZoneHeight = (region.height * (0.665f - 0.266f)).roundToInt() - dp(4)
        val preferredSpeedHeight = if (current.battleType == "DOUBLE") dp(154) else dp(96)
        val speedHeight = minOf(preferredSpeedHeight, speedZoneHeight)
            .coerceAtLeast(minOf(dp(96), region.height))
        return record.desiredWidth to speedHeight
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
        pickerViews.clear()
        toggleButton = null
        recordingButton = null
        speedContainer = null
        statusControl = null
        assumptionControl = null
    }

    private fun addPicker(
        element: BattleDirectHudElement,
        side: SpeedSide,
        displayIndex: Int,
        positionLabel: String,
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
            currentPickerSlot(side, displayIndex)?.let { onSelectSlot(side, it) }
        }.apply {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        val arrow = compactButton("⌄") { }.apply {
            contentDescription = "更换${if (side == SpeedSide.OWN) "我方" else "对方"}${positionLabel}宝可梦"
            setOnClickListener { anchor ->
                val current = this@BattleDirectOverlayUi.model ?: return@setOnClickListener
                val currentTeamNames = if (side == SpeedSide.OWN) current.ownTeamNames else current.opponentTeamNames
                val currentTeamSlot = currentPickerSlot(side, displayIndex) ?: 0
                PopupMenu(context, anchor).apply {
                    currentTeamNames.forEachIndexed { index, pokemonName ->
                        menu.add(0, index, index, "${index + 1}. $pokemonName${if (index == currentTeamSlot) " · 当前" else ""}")
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
        pickerViews[element] = PickerViews(root, name, side, displayIndex)
        addWindow(element, root, region, desiredWidth = dp(170), desiredHeight = dp(38), interactive = true)
    }

    private fun currentPickerSlot(side: SpeedSide, displayIndex: Int): Int? {
        val current = model ?: return null
        val slots = if (side == SpeedSide.OWN) current.ownSlots else current.opponentSlots
        val teamNames = if (side == SpeedSide.OWN) current.ownTeamNames else current.opponentTeamNames
        if (teamNames.isEmpty()) return null
        return slots.getOrElse(displayIndex) { 0 }.coerceIn(0, teamNames.lastIndex)
    }

    private fun updatePicker(views: PickerViews, model: BattleDirectHudModel) {
        val teamNames = if (views.side == SpeedSide.OWN) model.ownTeamNames else model.opponentTeamNames
        val slots = if (views.side == SpeedSide.OWN) model.ownSlots else model.opponentSlots
        val selectedSlot = if (views.side == SpeedSide.OWN) model.selectedOwnSlot else model.selectedOpponentSlot
        val teamSlot = slots.getOrElse(views.displayIndex) { 0 }.coerceIn(0, teamNames.lastIndex.coerceAtLeast(0))
        views.name.text = teamNames.getOrElse(teamSlot) { "未确认" }
        views.root.background = roundedBackground(
            BACKGROUND,
            if (teamSlot == selectedSlot) SELECTED else BORDER,
            9f,
            if (teamSlot == selectedSlot) 2 else 1,
        )
    }

    private fun speedView(model: BattleDirectHudModel): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(5), dp(4), dp(5), dp(4))
        background = roundedBackground(BACKGROUND, BORDER, 9f)
        speedContainer = this
        renderSpeedView(model)
    }

    private fun renderSpeedView(model: BattleDirectHudModel) {
        val container = speedContainer ?: return
        container.removeAllViews()
        val actionCount = battleDirectHudSlotsPerSide(model.battleType) * 2
        val visibleActions = model.speedActions.take(actionCount)
        val countLabel = if (model.battleType == "DOUBLE") "四只顺序" else "两只顺序"
        container.addView(textView(if (model.trickRoom) "$countLabel · 慢→快" else "$countLabel · 快→慢", bold = true))
        visibleActions.forEachIndexed { index, action ->
            container.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(3), dp(1), dp(3), dp(1))
                val current = (action.side == SpeedSide.OWN && action.slot == model.selectedOwnSlot) ||
                    (action.side == SpeedSide.OPPONENT && action.slot == model.selectedOpponentSlot)
                background = roundedBackground(if (current) SELECTED_SURFACE else SURFACE, if (action.side == SpeedSide.OWN) OWN else OPPONENT, 5f)
                addView(textView("${index + 1} ${if (action.side == SpeedSide.OWN) "我" else "对"}·${action.pokemonName}"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(textView(if (action.isPoint) action.speed.first.toString() else "${action.speed.first}–${action.speed.last}"))
            })
            if (index < visibleActions.lastIndex) {
                val next = visibleActions[index + 1]
                container.addView(textView(
                    if (battleDirectSpeedRangesOverlap(action.speed, next.speed)) "≈ 顺序未定" else "↓",
                    muted = true,
                    centered = true,
                ))
            }
        }
    }

    private fun typeMatchupView(matchups: List<BattleTypeMatchup>): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(2), dp(2), dp(2))
        background = roundedBackground(Color.argb(92, 7, 13, 20), Color.argb(150, 91, 105, 117), 8f)
        val visibleMatchups = matchups.take(6)
        visibleMatchups.forEachIndexed { index, matchup ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                contentDescription = "${matchup.speciesName}属性相性"
                addView(
                    typeMatchupRow("抗", matchup.groups, RESIST_MULTIPLIERS, RESIST_SURFACE, RESIST_BORDER),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                        bottomMargin = dp(1)
                    },
                )
                addView(
                    typeMatchupRow("弱", matchup.groups, WEAK_MULTIPLIERS, WEAK_SURFACE, WEAK_BORDER),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                if (index > 0) topMargin = dp(1)
            })
        }
    }

    private fun typeMatchupRow(
        category: String,
        groups: Map<String, List<String>>,
        multipliers: List<Pair<String, String>>,
        fill: Int,
        stroke: Int,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), 0, dp(2), 0)
        background = roundedBackground(fill, stroke, 5f)
        addView(textView(category, bold = true, centered = true), LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            multipliers.forEach { (key, label) ->
                addView(typeMatchupGroup(label, groups[key].orEmpty()))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun typeMatchupGroup(multiplier: String, types: List<String>): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(1), 0, dp(1), 0)
        addView(textView(multiplier, bold = true, centered = true), LinearLayout.LayoutParams(dp(15), ViewGroup.LayoutParams.MATCH_PARENT))
        types.forEach { type ->
            addView(typeIcon(type), LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                marginStart = dp(1)
            })
        }
    }

    private fun typeIcon(type: String): TextView = TextView(context).apply {
        text = TYPE_GLYPHS[type] ?: type.take(1)
        textSize = 8f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(TYPE_COLORS[type] ?: BORDER)
            setStroke(dp(1).coerceAtLeast(1), Color.argb(150, 255, 255, 255))
        }
        contentDescription = type
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

    private fun applyToggleButtonStyle(button: Button, enabled: Boolean) {
        val colors = battleDirectHudToggleColors(enabled)
        button.backgroundTintList = ColorStateList.valueOf(colors.backgroundColor)
        button.setTextColor(colors.textColor)
    }

    private fun recognitionStatusView(message: String): TextView = textView(
        value = message,
        bold = true,
        centered = true,
    ).apply {
        setPadding(dp(6), 0, dp(6), 0)
        background = roundedBackground(BACKGROUND, BORDER, 8f)
        contentDescription = message
    }

    private fun statusButton(text: String): Button = compactButton(text) {}.apply {
        statusControl = this
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
        return compactButton("") {}.apply {
            assumptionControl = this
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { anchor ->
                val current = this@BattleDirectOverlayUi.model ?: return@setOnClickListener
                PopupMenu(context, anchor).apply {
                    current.assumptionOptions.forEachIndexed { index, option ->
                        menu.add(0, index, index, option.label).apply {
                            isCheckable = true
                            isChecked = option.profileId == current.selectedAssumptionId
                        }
                    }
                    menu.setGroupCheckable(0, true, true)
                    setOnMenuItemClickListener { item ->
                        current.assumptionOptions.getOrNull(item.itemId)?.let { option ->
                            onSelectAssumption(option.profileId)
                            true
                        } ?: false
                    }
                    show()
                }
            }
            updateAssumptionControl(model)
        }
    }

    private fun updateAssumptionControl(model: BattleDirectHudModel) {
        val selected = model.assumptionOptions.firstOrNull { it.profileId == model.selectedAssumptionId }
            ?: model.assumptionOptions.firstOrNull()
        assumptionControl?.apply {
            text = "耐久：${selected?.label ?: "默认配置"} ▾"
            contentDescription = "选择敌方耐久预设，当前为${selected?.label ?: "默认配置"}"
            isEnabled = model.assumptionOptions.isNotEmpty()
            alpha = if (isEnabled) 1f else 0.62f
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
        val bounds = resolveWindowBounds(element, region, desiredWidth, desiredHeight)
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
            editableContainer(element, view, params, desiredWidth, desiredHeight)
        } else {
            view
        }
        if (interactive && !editable) {
            configureOverlayFocus(context, windowManager, windowView, params, initiallyFocusable = false)
        }
        windowManager.addView(windowView, params)
        windows[element] = WindowRecord(windowView, params, desiredWidth, desiredHeight)
    }

    private fun restoreWindowLayerOrder() {
        INTERACTIVE_LAYER_ORDER.mapNotNull(windows::get).forEach { record ->
            runCatching {
                if (record.view.isAttachedToWindow) windowManager.removeViewImmediate(record.view)
                windowManager.addView(record.view, record.params)
            }.onFailure { error ->
                Log.e(DIRECT_HUD_LOG_TAG, "Could not raise an interactive HUD window above passive content", error)
            }
        }
    }

    private fun resolveWindowBounds(
        element: BattleDirectHudElement,
        region: OverlayBounds,
        desiredWidth: Int,
        desiredHeight: Int,
    ): OverlayBounds {
        val anchor = requireNotNull(BattleDirectHudLayout.anchors[element])
        val defaultBounds = resolveBattleDirectHudBounds(region, anchor, desiredWidth, desiredHeight)
        val (minimumWidth, minimumHeight) = minimumHudSize(element, desiredWidth, desiredHeight, region)
        return activePlacements[element]?.let { placement ->
            resolveBattleDirectHudPlacement(region, placement, minimumWidth, minimumHeight)
        } ?: defaultBounds
    }

    private fun minimumHudSize(
        element: BattleDirectHudElement,
        desiredWidth: Int,
        desiredHeight: Int,
        region: OverlayBounds,
    ): Pair<Int, Int> {
        val requested = when (element) {
            BattleDirectHudElement.MATCHUP -> dp(220) to dp(108)
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
        params: WindowManager.LayoutParams,
        desiredWidth: Int,
        desiredHeight: Int,
    ): View {
        var startX = params.x
        var startY = params.y
        var startWidth = params.width
        var startHeight = params.height
        lateinit var container: BattleDirectHudEditFrame

        fun updatePlacement() {
            val region = safeArea.currentRegion()
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
                val region = safeArea.currentRegion()
                val (minimumWidth, minimumHeight) = minimumHudSize(
                    element,
                    desiredWidth,
                    desiredHeight,
                    region,
                )
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
        val RESIST_SURFACE = Color.argb(218, 5, 53, 72)
        val RESIST_BORDER = Color.rgb(17, 181, 224)
        val WEAK_SURFACE = Color.argb(218, 101, 42, 23)
        val WEAK_BORDER = Color.rgb(240, 111, 67)
        val RESIST_MULTIPLIERS = listOf("0" to "0", "0.25" to "¼", "0.5" to "½")
        val WEAK_MULTIPLIERS = listOf("4" to "4", "2" to "2")
        val INTERACTIVE_LAYER_ORDER = listOf(
            BattleDirectHudElement.STATUS,
            BattleDirectHudElement.ASSUMPTION,
            BattleDirectHudElement.OPPONENT_LEFT,
            BattleDirectHudElement.OPPONENT_RIGHT,
            BattleDirectHudElement.OWN_LEFT,
            BattleDirectHudElement.OWN_RIGHT,
            BattleDirectHudElement.DETAIL,
            BattleDirectHudElement.REMATCH,
            BattleDirectHudElement.TOGGLE,
            BattleDirectHudElement.RECORDING,
            BattleDirectHudElement.FORMAT,
            BattleDirectHudElement.OWN_RECOGNITION,
            BattleDirectHudElement.EDIT,
        )
        val TYPE_GLYPHS = mapOf(
            "Normal" to "普", "Fighting" to "斗", "Flying" to "飞", "Poison" to "毒",
            "Ground" to "地", "Rock" to "岩", "Bug" to "虫", "Ghost" to "幽",
            "Steel" to "钢", "Fire" to "火", "Water" to "水", "Grass" to "草",
            "Electric" to "电", "Psychic" to "超", "Ice" to "冰", "Dragon" to "龙",
            "Dark" to "恶", "Fairy" to "妖",
        )
        val TYPE_COLORS = mapOf(
            "Normal" to Color.rgb(146, 157, 163), "Fighting" to Color.rgb(206, 65, 107),
            "Flying" to Color.rgb(143, 169, 222), "Poison" to Color.rgb(170, 107, 200),
            "Ground" to Color.rgb(217, 120, 69), "Rock" to Color.rgb(197, 183, 140),
            "Bug" to Color.rgb(145, 193, 47), "Ghost" to Color.rgb(82, 105, 173),
            "Steel" to Color.rgb(90, 142, 162), "Fire" to Color.rgb(255, 157, 85),
            "Water" to Color.rgb(80, 144, 214), "Grass" to Color.rgb(99, 188, 90),
            "Electric" to Color.rgb(244, 210, 60), "Psychic" to Color.rgb(250, 113, 121),
            "Ice" to Color.rgb(115, 206, 192), "Dragon" to Color.rgb(11, 109, 195),
            "Dark" to Color.rgb(90, 84, 101), "Fairy" to Color.rgb(236, 143, 230),
        )
        const val PASSIVE_FLAGS =
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
}
