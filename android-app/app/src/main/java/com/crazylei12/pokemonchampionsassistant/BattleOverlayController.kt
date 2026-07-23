package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

internal fun applyOpponentPresetSelection(
    state: BattleCalculationState,
    preset: OpponentPreset,
): BattleCalculationState {
    val selectedMoveId = if (state.direction == "OPPONENT_TO_OWN" && preset.moves.isNotEmpty()) {
        (preset.moves.firstOrNull { (it.basePower ?: 0) > 0 } ?: preset.moves.first()).entity.showdownId
    } else {
        state.selectedMoveId
    }
    val manualOverrides = state.opponentManualOverrides.toMutableMap().apply { remove(state.opponentSlot) }
    return state.withOpponentPreset(preset.profileId).copy(
        selectedMoveId = selectedMoveId,
        opponentManualOverrides = manualOverrides,
    )
}

internal fun isBattlePanelCalculationOnlyChange(before: BattleSession, after: BattleSession): Boolean =
    before.copy(calculation = before.calculation.copy(
        selectedMoveId = after.calculation.selectedMoveId,
        weather = after.calculation.weather,
        terrain = after.calculation.terrain,
    )) == after

internal class BattleOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val safeArea: OverlaySafeAreaProvider,
    private val runtime: DamageEngineRuntime,
    private val sessionRepository: BattleSessionRepository,
    private val presetRepository: OpponentPresetRepository,
    private val publish: (String) -> Unit,
    private val onOverlayVisible: (Boolean) -> Unit,
    private val shouldAutoOpenDirectHud: () -> Boolean,
    private val onRecognizeTeamPreview: () -> Unit,
    private val onRecognizeOwnTeam: () -> Unit,
    private val recordingState: () -> BattleDirectHudRecordingState = { BattleDirectHudRecordingState.UNAVAILABLE },
    private val onToggleRecording: () -> Unit = {},
) {
    private data class PanelCalculationBinding(
        val ownTeam: SavedTeam,
        val preset: OpponentPreset,
        val legalMoves: List<MoveValue>,
        val valueView: TextView,
        val detailsView: TextView,
    )

    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var setupView: View? = null
    private var panelView: View? = null
    private var conditionsView: View? = null
    private var opponentEditorView: View? = null
    private var speciesSearchView: View? = null
    private var speedLineView: View? = null
    private var calculationGeneration = 0
    private var directCalculationGeneration = 0
    private var battleContextCache: BattleContext? = null
    private var panelSession: BattleSession? = null
    private var panelCalculationBinding: PanelCalculationBinding? = null
    private val panelDamageCache = object : LinkedHashMap<String, OverlayResult>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OverlayResult>?): Boolean = size > 24
    }
    private val directDamageCache = object : LinkedHashMap<String, List<String>>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean = size > 12
    }
    private var moveSortMode = MoveSortMode.PINYIN
    private var speedEditorSide = SpeedSide.OWN
    private var speedEditorSlot = 0
    private val panelNavigation = BattlePanelNavigation()
    private val setupWindowState = OverlayWindowState()
    private val panelWindowState = OverlayWindowState()
    private val conditionsWindowState = OverlayWindowState()
    private val opponentEditorWindowState = OverlayWindowState()
    private val searchWindowState = OverlayWindowState()
    private val speedLineWindowState = OverlayWindowState()
    private val battlePanelPositionState = OverlayWindowState()
    private val directOverlay by lazy {
        BattleDirectOverlayUi(
            context = context,
            windowManager = windowManager,
            safeArea = safeArea,
            onSelectSlot = ::selectDirectHudSlot,
            onReplaceSlot = ::replaceDirectHudSlot,
            onToggleVisibility = ::setDirectHudVisibility,
            onRecognizeTeamPreview = onRecognizeTeamPreview,
            onRecognizeOwnTeam = ::recognizeOwnTeamFromDirectHud,
            onToggleRecording = onToggleRecording,
            onOpenStatusSection = ::showDirectHudSection,
            onSelectAssumption = ::selectDirectHudPreset,
            onOpenDetails = ::showPanel,
        )
    }

    val hasPreview: Boolean get() = runCatching { sessionRepository.loadPreview() != null }.getOrDefault(false)
    val hasSession: Boolean get() = battleContextCache != null ||
        runCatching { sessionRepository.loadSession() != null }.getOrDefault(false)

    fun onTeamRecognitionStarted() {
        panelNavigation.resetForTeamRecognition()
        directCalculationGeneration++
        battleContextCache = null
        panelDamageCache.clear()
        directDamageCache.clear()
        directOverlay.dismiss()
    }

    fun showSetup() {
        onTeamRecognitionStarted()
        val preview = runCatching { sessionRepository.loadPreview() }.getOrElse {
            Log.e("BattleOverlay", "Could not load team preview", it)
            publish("无法读取双方阵容，请重新识别")
            return
        } ?: run {
            publish("请先识别双方阵容")
            return
        }
        val teams = runCatching { TeamRepository.load(context) }.getOrElse {
            Log.e("BattleOverlay", "Could not load saved teams", it)
            publish("无法读取我的队伍，请返回 App 后重试")
            return
        }.filter { it.pokemon.size == 6 && it.damageReady }
        if (teams.isEmpty()) {
            publish("还没有可用于对局的完整队伍，请先录入 6 只宝可梦的特性、能力值和招式")
            return
        }

        dismissSetup()
        dismissOpponentEditor(showPanel = false)
        dismissSpeedLine(showPanel = false)
        dismissPanel()
        dismissSpeciesSearch(showPrevious = false)
        onOverlayVisible(true)
        val root = compactPanelRoot()
        val params = rightRailPanelParams(setupWindowState, widthDp = 390)
        val header = header("核对双方阵容", "确认后开始本局伤害计算") { dismissSetup() }
        makeDraggable(header.getChildAt(0), root, params, setupWindowState)
        root.addView(header)
        val content = vertical(spacing = 12)
        val teamLabel = label("选择本局使用的我方队伍")
        content.addView(teamLabel)
        val suggestedTeamIndex = teams.indices.maxByOrNull { matchCount(teams[it], preview) } ?: 0
        val teamSpinner = spinner(teams.map { "${it.name} · 与识别阵容匹配 ${matchCount(it, preview)}/6" }, suggestedTeamIndex)
        content.addView(teamSpinner)
        val matchHint = bodyText("")
        content.addView(matchHint)

        val selectedOpponents = MutableList<EntityValue?>(6) { null }
        content.addView(label("核对对手的 6 只宝可梦"))
        content.addView(bodyText("如识别不准确，可点击“更正”并用中文名或英文名搜索。", color = TEXT_MUTED))
        repeat(6) { rowIndex ->
            val slot = preview.opponentSlots.firstOrNull { it.slotIndex == rowIndex }
                ?: preview.opponentSlots.getOrNull(rowIndex)
            val candidates = slot?.candidates.orEmpty().map { candidate ->
                candidate.copy(entity = presetRepository.localizeSpecies(candidate.entity))
            }.distinctBy { normalize(it.entity.showdownId) }
            selectedOpponents[rowIndex] = candidates.firstOrNull()?.entity
            val selectedText = bodyText(selectedOpponents[rowIndex]?.displayName ?: "未确认", bold = true)
            val row = cardColumn().apply {
                addView(horizontal(spacing = 8).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(bodyText("${rowIndex + 1}", bold = true), weighted(width = dp(30)))
                    addView(selectedText, weighted(weight = 1f))
                    addView(actionButton("更正", secondary = true) {
                        showSpeciesSearch(
                            title = "更正对手第 ${rowIndex + 1} 只宝可梦",
                            current = selectedOpponents[rowIndex],
                            suggestions = candidates.map(PreviewCandidate::entity),
                        ) { selected ->
                            selectedOpponents[rowIndex] = selected
                            selectedText.text = selected.displayName
                        }
                    })
                })
                if (candidates.isNotEmpty()) {
                    val picker = spinner(candidates.map { candidate ->
                        "${candidate.entity.displayName} · 匹配度 ${(candidate.confidence * 100).roundToInt()}%"
                    }, 0)
                    picker.onItemSelected { position ->
                        selectedOpponents[rowIndex] = candidates[position].entity
                        selectedText.text = candidates[position].entity.displayName
                    }
                    addView(picker)
                } else {
                    addView(bodyText("未识别到候选，请搜索并选择正确的宝可梦。", color = ACCENT_AMBER))
                }
            }
            content.addView(row)
        }

        fun updateMatchHint(position: Int) {
            val count = matchCount(teams[position], preview)
            matchHint.text = if (count == 6) {
                "已与识别到的我方阵容完全匹配。"
            } else {
                "当前匹配 $count/6，请确认选择的是本局使用的队伍；伤害计算会使用该队伍保存的完整配置。"
            }
            matchHint.setTextColor(if (count >= 5) ACCENT_TEAL else ACCENT_AMBER)
        }
        updateMatchHint(suggestedTeamIndex)
        teamSpinner.onItemSelected(::updateMatchHint)

        content.addView(bodyText("队伍预览无法确定对手的招式、特性和能力值，开始对局后可按实际情况选择。", color = TEXT_MUTED))
        content.addView(horizontal(spacing = 10).apply {
            gravity = Gravity.END
            addView(actionButton("重新识别阵容", secondary = true) {
                dismissSetup()
                publish("请回到双方队伍预览页面，再点击悬浮按钮选择“识别双方阵容”")
            })
            addView(actionButton("确认并开始对局") {
                val opponents = selectedOpponents.filterNotNull()
                if (opponents.size != 6) {
                    publish("请确认对手的全部 6 只宝可梦")
                    return@actionButton
                }
                runCatching {
                    sessionRepository.createSession(preview, teams[teamSpinner.selectedItemPosition].id, opponents)
                }.onSuccess {
                    battleContextCache = null
                    panelDamageCache.clear()
                    directDamageCache.clear()
                    dismissSetup(showBubble = false)
                    onOverlayVisible(false)
                    if (shouldAutoOpenDirectHud()) {
                        publish("本局阵容已确认，已打开对战 HUD")
                        showDirectHud()
                    } else {
                        publish("本局阵容已确认；可从悬浮按钮打开 HUD 或详细面板")
                    }
                }.onFailure {
                    Log.e("BattleOverlay", "Could not create battle session", it)
                    publish("无法开始对局，请重试")
                }
            })
        })

        root.addView(scroll(content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(resizeHandle(root, params, setupWindowState))
        addOverlay(root, params)
        setupView = root
    }

    fun showPanel() {
        directCalculationGeneration++
        directOverlay.dismiss()
        val requestedPage = panelNavigation.reopen()
        val cached = battleContextCache
        val loaded = cached?.session ?: runCatching { sessionRepository.loadSession() }.getOrElse {
            Log.e("BattleOverlay", "Could not load battle session", it)
            publish("无法读取当前对局，请重新核对双方阵容")
            return
        } ?: run {
            if (hasPreview) showSetup() else publish("请先识别并确认双方阵容")
            return
        }
        val teams = cached?.teams ?: runCatching { TeamRepository.load(context) }.getOrElse {
            Log.e("BattleOverlay", "Could not load own teams", it)
            publish("无法读取我的队伍，请返回 App 后重试")
            return
        }.filter { it.pokemon.size == 6 && it.damageReady }
        if (teams.isEmpty()) {
            publish("没有可用于计算的我方队伍")
            return
        }
        val localized = loaded.copy(
            opponentTeam = loaded.opponentTeam.map(presetRepository::localizeSpecies),
            calculation = loaded.calculation.copy(
                ownFormOverrides = loaded.calculation.ownFormOverrides.mapValues { (_, form) ->
                    presetRepository.localizeSpecies(form)
                },
                opponentFormOverrides = loaded.calculation.opponentFormOverrides.mapValues { (_, form) ->
                    presetRepository.localizeSpecies(form)
                },
            ),
        )
        val team = teams.firstOrNull { it.id == localized.selectedOwnTeamId } ?: teams.first()
        val session = ensureValidState(
            switchOwnTeam(localized, team.id),
            team,
        )
        battleContextCache = BattleContext(session, teams, team)
        if (session != loaded) saveSession(session)
        renderPanel(session, teams)
        restorePanelPage(requestedPage, session, teams)
    }

    private data class BattleContext(
        val session: BattleSession,
        val teams: List<SavedTeam>,
        val ownTeam: SavedTeam,
    )

    fun showDirectHud() {
        showDirectHud(loadDirectHudContext() ?: return)
    }

    private fun showDirectHud(directContext: BattleContext) {
        battleContextCache = directContext
        val session = directContext.session
        val ownTeam = directContext.ownTeam
        val state = session.calculation
        val directState = state.directHud
        val ownNames = ownTeam.pokemon.mapIndexed { slot, pokemon ->
            presetRepository.effectiveOwnPokemon(pokemon, state.ownFormOverrides[slot]).species.displayName
        }
        val opponentNames = session.opponentTeam.mapIndexed { slot, pokemon ->
            (state.opponentFormOverrides[slot] ?: pokemon).displayName
        }
        val selectedOpponent = state.opponentFormOverrides[state.opponentSlot]
            ?: session.opponentTeam[state.opponentSlot]
        val assumptionProfiles = presetRepository.profilesFor(selectedOpponent)
        val selectedAssumption = assumptionProfiles.firstOrNull { it.profileId == state.opponentPresetId() }
            ?: assumptionProfiles.first()
        val model = BattleDirectHudModel(
            ownTeamNames = ownNames,
            opponentTeamNames = opponentNames,
            ownSlots = directState.ownSlots,
            opponentSlots = directState.opponentSlots,
            selectedOwnSlot = state.ownSlot,
            selectedOpponentSlot = state.opponentSlot,
            speedActions = directHudSpeedActions(session, ownTeam),
            trickRoom = state.speedLine.trickRoom,
            statusText = directHudStatusText(state),
            assumptionOptions = assumptionProfiles.map {
                BattleDirectHudPresetOption(it.profileId, profileLabel(it))
            },
            selectedAssumptionId = selectedAssumption.profileId,
            recordingState = recordingState(),
            hudVisible = directState.visible,
        )
        directOverlay.show(model)
        onOverlayVisible(true)
        if (directState.visible) scheduleDirectDamage(session, ownTeam)
    }

    fun revealDirectHud() {
        val directContext = loadDirectHudContext() ?: return
        val state = directContext.session.calculation
        var currentContext = directContext
        if (!state.directHud.visible) {
            val changed = directContext.session.copy(calculation = state.copy(
                directHud = state.directHud.copy(visible = true),
            ))
            saveSession(changed)
            currentContext = directContext.copy(session = changed)
        }
        showDirectHud(currentContext)
    }

    fun showDirectHudEntry() {
        if (hasSession) {
            revealDirectHud()
            if (directOverlay.isVisible) return
        }
        directOverlay.show(BattleDirectHudModel(
            ownTeamNames = emptyList(),
            opponentTeamNames = emptyList(),
            ownSlots = emptyList(),
            opponentSlots = emptyList(),
            selectedOwnSlot = 0,
            selectedOpponentSlot = 0,
            speedActions = emptyList(),
            trickRoom = false,
            statusText = "等待双方阵容",
            assumptionOptions = emptyList(),
            selectedAssumptionId = "",
            recordingState = recordingState(),
            hudVisible = true,
            sessionReady = false,
        ))
        onOverlayVisible(true)
    }

    fun onRecordingStateChanged() {
        directOverlay.updateRecordingState(recordingState())
    }

    private fun recognizeOwnTeamFromDirectHud() {
        directCalculationGeneration++
        battleContextCache = null
        panelDamageCache.clear()
        directDamageCache.clear()
        directOverlay.dismiss()
        onRecognizeOwnTeam()
    }

    private fun loadDirectHudContext(): BattleContext? {
        battleContextCache?.let { return it }
        val loaded = runCatching { sessionRepository.loadSession() }.getOrElse {
            Log.e("BattleOverlay", "Could not load direct HUD session", it)
            publish("无法读取当前对局，请重新核对双方阵容")
            return null
        } ?: run {
            publish("请先识别并确认双方阵容")
            return null
        }
        val teams = runCatching { TeamRepository.load(context) }.getOrElse {
            Log.e("BattleOverlay", "Could not load teams for direct HUD", it)
            publish("无法读取我的队伍，请返回 App 后重试")
            return null
        }.filter { it.pokemon.size == 6 && it.damageReady }
        if (teams.isEmpty()) {
            publish("没有可用于计算的我方队伍")
            return null
        }
        val localized = loaded.copy(
            opponentTeam = loaded.opponentTeam.map(presetRepository::localizeSpecies),
            calculation = loaded.calculation.copy(
                ownFormOverrides = loaded.calculation.ownFormOverrides.mapValues { (_, form) ->
                    presetRepository.localizeSpecies(form)
                },
                opponentFormOverrides = loaded.calculation.opponentFormOverrides.mapValues { (_, form) ->
                    presetRepository.localizeSpecies(form)
                },
            ),
        )
        val ownTeam = teams.firstOrNull { it.id == localized.selectedOwnTeamId } ?: teams.first()
        val corrected = ensureValidState(switchOwnTeam(localized, ownTeam.id), ownTeam)
        if (corrected != loaded) sessionRepository.save(corrected)
        return BattleContext(corrected, teams, ownTeam).also { battleContextCache = it }
    }

    private fun saveSession(session: BattleSession) {
        sessionRepository.save(session)
        battleContextCache = battleContextCache?.let { cached ->
            val ownTeam = cached.teams.firstOrNull { it.id == session.selectedOwnTeamId }
                ?: return@let null
            cached.copy(session = session, ownTeam = ownTeam)
        }
    }

    private fun showDirectHudSection(section: BattleDirectHudSection) {
        directCalculationGeneration++
        directOverlay.dismiss()
        val directContext = loadDirectHudContext() ?: return
        val session = directContext.session
        val teams = directContext.teams
        renderPanel(session, teams)
        when (section) {
            BattleDirectHudSection.BATTLEFIELD -> showConditions(session, teams)
            BattleDirectHudSection.SPEED_LINE -> showSpeedLine(session, teams)
            BattleDirectHudSection.OPPONENT_CONFIG -> {
                val state = session.calculation
                val opponent = state.opponentFormOverrides[state.opponentSlot]
                    ?: session.opponentTeam[state.opponentSlot]
                val basePreset = presetRepository.profilesFor(opponent)
                    .first { it.profileId == state.selectedPresetId }
                showOpponentEditor(session, teams, opponent, basePreset)
            }
        }
    }

    private fun directHudStatusText(state: BattleCalculationState): String {
        val active = buildList {
            when (state.weather) {
                "RAIN" -> add("雨")
                "SUN" -> add("晴")
                "SAND" -> add("沙暴")
                "SNOW" -> add("雪")
            }
            when (state.terrain) {
                "ELECTRIC" -> add("电场")
                "GRASSY" -> add("青草场地")
                "PSYCHIC" -> add("精神场地")
                "MISTY" -> add("薄雾场地")
            }
            if (state.speedLine.ownTailwind) add("我方顺风")
            if (state.speedLine.opponentTailwind) add("对方顺风")
            if (state.speedLine.trickRoom) add("戏法空间")
            if (state.ownReflect || state.ownLightScreen || state.ownAuroraVeil) add("我方墙")
            if (state.opponentReflect || state.opponentLightScreen || state.opponentAuroraVeil) add("对方墙")
        }
        return "状态：${active.ifEmpty { listOf("默认") }.joinToString(" · ")}"
    }

    private fun selectDirectHudSlot(side: SpeedSide, teamSlot: Int) {
        val directContext = loadDirectHudContext() ?: return
        val state = directContext.session.calculation
        val changedState = when (side) {
            SpeedSide.OWN -> state.copy(ownSlot = teamSlot, selectedMoveId = null)
            SpeedSide.OPPONENT -> state.withOpponentSlot(teamSlot).copy(
                selectedMoveId = null,
            )
        }
        val changed = ensureValidState(directContext.session.copy(calculation = changedState), directContext.ownTeam)
        saveSession(changed)
        showDirectHud(directContext.copy(session = changed))
    }

    private fun selectDirectHudPreset(profileId: String) {
        val directContext = loadDirectHudContext() ?: return
        val session = directContext.session
        val state = session.calculation
        val opponent = state.opponentFormOverrides[state.opponentSlot] ?: session.opponentTeam[state.opponentSlot]
        val selected = presetRepository.profilesFor(opponent).firstOrNull { it.profileId == profileId } ?: return
        val hasManualOverride = state.opponentManualOverrides.containsKey(state.opponentSlot)
        if (selected.profileId == state.selectedPresetId && !hasManualOverride) return
        val changed = session.copy(calculation = applyOpponentPresetSelection(state, selected))
        saveSession(changed)
        showDirectHud(directContext.copy(session = changed))
    }

    private fun replaceDirectHudSlot(side: SpeedSide, displayIndex: Int, teamSlot: Int) {
        val directContext = loadDirectHudContext() ?: return
        val state = directContext.session.calculation
        val direct = state.directHud
        val changedState = when (side) {
            SpeedSide.OWN -> state.copy(
                ownSlot = teamSlot,
                selectedMoveId = null,
                directHud = direct.copy(ownSlots = replaceBattleDirectHudSlot(direct.ownSlots, displayIndex, teamSlot)),
            )
            SpeedSide.OPPONENT -> state.withOpponentSlot(teamSlot).copy(
                selectedMoveId = null,
                directHud = direct.copy(opponentSlots = replaceBattleDirectHudSlot(direct.opponentSlots, displayIndex, teamSlot)),
            )
        }
        val changed = ensureValidState(directContext.session.copy(calculation = changedState), directContext.ownTeam)
        saveSession(changed)
        showDirectHud(directContext.copy(session = changed))
    }

    private fun setDirectHudVisibility(visible: Boolean) {
        val directContext = loadDirectHudContext() ?: return
        val state = directContext.session.calculation
        val changed = directContext.session.copy(calculation = state.copy(
            directHud = state.directHud.copy(visible = visible),
        ))
        saveSession(changed)
        showDirectHud(directContext.copy(session = changed))
    }

    private fun directHudSpeedActions(session: BattleSession, ownTeam: SavedTeam): List<SpeedLineAction> {
        val state = session.calculation
        val speed = state.speedLine
        val ownInputs = state.directHud.ownSlots.distinct().mapNotNull { slot ->
            val base = ownTeam.pokemon.getOrNull(slot) ?: return@mapNotNull null
            val pokemon = presetRepository.effectiveOwnPokemon(base, state.ownFormOverrides[slot])
            val knownSpeed = pokemon.actualStats.spe.toIntOrNull()?.takeIf { it > 0 }
            SpeedLinePokemonInput(
                side = SpeedSide.OWN,
                slot = slot,
                name = pokemon.species.displayName,
                baseSpeed = knownSpeed?.let { it..it }
                    ?: presetRepository.possibleSpeedRangeFor(pokemon.species)
                    ?: 1..1,
                modifiers = speed.ownPokemon[slot] ?: SpeedPokemonModifiers(),
                tailwind = speed.ownTailwind,
                knownChoiceScarf = normalize(pokemon.item?.showdownId.orEmpty()) == "choicescarf",
                exactBaseSpeed = knownSpeed != null,
            )
        }
        val opponentInputs = state.directHud.opponentSlots.distinct().mapNotNull { slot ->
            val base = session.opponentTeam.getOrNull(slot) ?: return@mapNotNull null
            val pokemon = state.opponentFormOverrides[slot] ?: base
            SpeedLinePokemonInput(
                side = SpeedSide.OPPONENT,
                slot = slot,
                name = pokemon.displayName,
                baseSpeed = presetRepository.possibleSpeedRangeFor(pokemon) ?: 1..1,
                modifiers = speed.opponentPokemon[slot] ?: SpeedPokemonModifiers(),
                tailwind = speed.opponentTailwind,
                exactBaseSpeed = false,
            )
        }
        return buildSpeedLineActions(ownInputs + opponentInputs, speed.trickRoom)
    }

    private data class PreparedDirectDamage(
        val request: String,
        val configuredMoves: List<MoveValue>,
        val cacheKey: String,
    )

    private fun prepareDirectDamage(session: BattleSession, ownTeam: SavedTeam): PreparedDirectDamage {
        val state = session.calculation
        val own = presetRepository.effectiveOwnPokemon(
            ownTeam.pokemon[state.ownSlot],
            state.ownFormOverrides[state.ownSlot],
        )
        val configuredMoves = compatibleConfiguredMoves(own.moves, presetRepository.movesFor(own.species))
        val opponent = state.opponentFormOverrides[state.opponentSlot] ?: session.opponentTeam[state.opponentSlot]
        val profiles = presetRepository.profilesFor(opponent)
        val basePreset = profiles.firstOrNull { it.profileId == state.selectedPresetId } ?: profiles.first()
        val preset = presetRepository.effectivePreset(
            opponent,
            basePreset,
            state.opponentManualOverrides[state.opponentSlot],
        )
        val directSession = session.copy(calculation = state.copy(
            direction = "OWN_TO_OPPONENT",
            selectedMoveId = null,
        ))
        val request = buildBattleDamageRequest(
            directSession,
            ownTeam,
            preset,
            emptyList(),
            presetRepository,
            allOwnMoves = true,
        )
        return PreparedDirectDamage(request, configuredMoves, battleDamageCacheKey(request))
    }

    private fun scheduleDirectDamage(
        session: BattleSession,
        ownTeam: SavedTeam,
        retry: Int = 0,
        generation: Int = ++directCalculationGeneration,
    ) {
        if (!runtime.isReady) {
            handler.postDelayed({
                if (generation != directCalculationGeneration || !directOverlay.isHudShown) return@postDelayed
                directOverlay.updateDamage(List(4) { index -> "${index + 1} …" })
                if (retry < 12) scheduleDirectDamage(session, ownTeam, retry + 1, generation)
                else directOverlay.updateDamage(List(4) { index -> "${index + 1} ?" })
            }, if (retry == 0) 100 else 350)
            return
        }

        val prepared = runCatching { prepareDirectDamage(session, ownTeam) }.getOrElse {
            Log.e("BattleOverlay", "Could not prepare direct HUD damage", it)
            directOverlay.updateDamage(List(4) { index -> "${index + 1} ?" })
            return
        }
        directDamageCache[prepared.cacheKey]?.let { cached ->
            directOverlay.updateDamage(cached)
            return
        }

        handler.postDelayed({
            if (generation != directCalculationGeneration || !directOverlay.isHudShown) return@postDelayed
            runtime.calculate(prepared.request) { rawResult ->
                if (generation != directCalculationGeneration || !directOverlay.isHudShown) return@calculate
                rawResult.fold(
                    onSuccess = { raw ->
                        runCatching { parseBattleDirectDamageValues(raw, prepared.configuredMoves) }
                            .onSuccess { values ->
                                directDamageCache[prepared.cacheKey] = values
                                directOverlay.updateDamage(values)
                            }
                            .onFailure {
                                Log.e("BattleOverlay", "Could not parse direct HUD damage", it)
                                directOverlay.updateDamage(List(4) { index -> "${index + 1} ?" })
                            }
                    },
                    onFailure = {
                        Log.e("BattleOverlay", "Direct HUD damage calculation failed", it)
                        directOverlay.updateDamage(List(4) { index -> "${index + 1} ?" })
                    },
                )
            }
        }, if (retry == 0) 100 else 0)
    }

    fun closeAll() {
        directCalculationGeneration++
        directOverlay.dismiss()
        dismissSpeciesSearch(showPrevious = false)
        dismissOpponentEditor(showPanel = false)
        dismissConditions(showPanel = false)
        dismissSpeedLine(showPanel = false)
        dismissSetup(showBubble = false)
        dismissPanel(showBubble = false)
        panelNavigation.collapse()
        onOverlayVisible(false)
    }

    fun onSafeAreaChanged() {
        directOverlay.reflow()
        listOf(
            Triple(setupView, setupWindowState, null),
            Triple(panelView, panelWindowState, battlePanelPositionState),
            Triple(conditionsView, conditionsWindowState, battlePanelPositionState),
            Triple(opponentEditorView, opponentEditorWindowState, battlePanelPositionState),
            Triple(speciesSearchView, searchWindowState, null),
            Triple(speedLineView, speedLineWindowState, battlePanelPositionState),
        ).forEach { (view, state, sharedPositionState) ->
            view?.let { reflowOverlay(it, state, sharedPositionState) }
        }
    }

    private fun renderPanel(session: BattleSession, teams: List<SavedTeam>) {
        panelNavigation.show(BattlePanelPage.DAMAGE)
        calculationGeneration++
        val rememberedPanelScrollY = panelWindowState.scrollY
        val existingRoot = panelView as? LinearLayout
        val existingParams = existingRoot?.layoutParams as? WindowManager.LayoutParams
        val reuseWindow = existingRoot?.isAttachedToWindow == true && existingParams != null
        dismissOpponentEditor(showPanel = false)
        dismissConditions(showPanel = false)
        dismissSpeedLine(showPanel = false)
        panelWindowState.rememberScroll(rememberedPanelScrollY)
        if (!reuseWindow) onOverlayVisible(true)

        val ownTeam = teams.first { it.id == session.selectedOwnTeamId }
        val state = session.calculation
        val currentOwnBase = ownTeam.pokemon[state.ownSlot]
        val currentOwn = presetRepository.effectiveOwnPokemon(currentOwnBase, state.ownFormOverrides[state.ownSlot])
        val opponentBase = session.opponentTeam[state.opponentSlot]
        val opponent = state.opponentFormOverrides[state.opponentSlot] ?: opponentBase
        val profiles = presetRepository.profilesFor(opponent)
        val basePreset = profiles.first { it.profileId == state.selectedPresetId }
        val manualOverride = state.opponentManualOverrides[state.opponentSlot]
        val preset = presetRepository.effectivePreset(opponent, basePreset, manualOverride)
        val legalMoves = presetRepository.movesFor(opponent, basePreset.moves)
        val ownMoves = compatibleConfiguredMoves(
            currentOwn.moves,
            presetRepository.movesFor(currentOwn.species),
        )
        val moveOptions = sortMoves(
            moves = if (state.direction == "OWN_TO_OPPONENT") ownMoves else legalMoves,
            mode = moveSortMode,
            typeOf = presetRepository::moveTypeFor,
        )

        val root = existingRoot?.takeIf { reuseWindow } ?: compactPanelRoot()
        val params = existingParams?.takeIf { reuseWindow } ?: rightRailPanelParams(
            panelWindowState,
            widthDp = 340,
            sharedPositionState = battlePanelPositionState,
        )
        if (reuseWindow) {
            root.removeAllViews()
            root.visibility = View.VISIBLE
        }

        fun titleBar(title: String) = horizontal(spacing = 6).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(vertical(spacing = 0).apply {
                addView(bodyText(title, bold = true).apply { textSize = 16f })
                addView(bodyText(
                    if (state.direction == "OWN_TO_OPPONENT") {
                        "${currentOwn.species.displayName}  →  ${opponent.displayName}"
                    } else {
                        "${opponent.displayName}  →  ${currentOwn.species.displayName}"
                    },
                    color = TEXT_MUTED,
                ).apply { textSize = 11f })
            }, weighted(weight = 1f))
            addView(miniButton("收起", secondary = true) { collapsePanel() })
        }

        fun directionSelector() = horizontal(spacing = 4).apply {
            addView(miniButton("我方输出", selected = state.direction == "OWN_TO_OPPONENT") {
                val current = currentPanelSession(session)
                if (current.calculation.direction != "OWN_TO_OPPONENT") {
                    val currentTeam = teams.first { it.id == current.selectedOwnTeamId }
                    updateSession(current.copy(calculation = defaultsForDirection(current, currentTeam, "OWN_TO_OPPONENT")), teams)
                }
            }, weighted(weight = 1f))
            addView(miniButton("我方承伤", selected = state.direction == "OPPONENT_TO_OWN") {
                val current = currentPanelSession(session)
                if (current.calculation.direction != "OPPONENT_TO_OWN") {
                    val currentTeam = teams.first { it.id == current.selectedOwnTeamId }
                    updateSession(current.copy(calculation = defaultsForDirection(current, currentTeam, "OPPONENT_TO_OWN")), teams)
                }
            }, weighted(weight = 1f))
        }

        fun teamSelector() = compactLabeledPicker(
            label = "队伍",
            options = teams.map { it.name },
            selected = teams.indexOfFirst { it.id == session.selectedOwnTeamId }.coerceAtLeast(0),
        ) { position ->
            val selected = teams[position]
            val current = currentPanelSession(session)
            if (selected.id != current.selectedOwnTeamId) {
                val changed = switchOwnTeam(current, selected.id)
                updateSession(ensureValidState(changed, selected), teams)
            }
        }

        fun ownSelector() = compactLabeledPicker(
            label = "我方",
            options = ownTeam.pokemon.mapIndexed { slot, pokemon ->
                "${slot + 1}. ${presetRepository.effectiveOwnPokemon(pokemon, state.ownFormOverrides[slot]).species.displayName}"
            },
            selected = state.ownSlot,
        ) { slot ->
            val current = currentPanelSession(session)
            val currentState = current.calculation
            if (slot != currentState.ownSlot) {
                val currentTeam = teams.first { it.id == current.selectedOwnTeamId }
                val changed = current.copy(calculation = currentState.copy(ownSlot = slot, selectedMoveId = null))
                updateSession(ensureValidState(changed, currentTeam), teams)
            }
        }

        fun opponentSelector() = compactLabeledPicker(
            label = "对手",
            options = session.opponentTeam.mapIndexed { slot, pokemon ->
                "${slot + 1}. ${(state.opponentFormOverrides[slot] ?: pokemon).displayName}"
            },
            selected = state.opponentSlot,
        ) { slot ->
            val current = currentPanelSession(session)
            val currentState = current.calculation
            if (slot != currentState.opponentSlot) {
                val currentTeam = teams.first { it.id == current.selectedOwnTeamId }
                val changed = current.copy(calculation = currentState.withOpponentSlot(slot).copy(
                    selectedMoveId = null,
                ))
                updateSession(ensureValidState(changed, currentTeam), teams)
            }
        }

        fun ownSelectorWithForm() = horizontal(spacing = 3).apply {
            val forms = presetRepository.formsFor(currentOwn.species)
            addView(ownSelector(), weighted(weight = if (forms.size > 1) 0.62f else 1f))
            if (forms.size > 1) {
                val selectedFormIndex = forms.indexOfFirst {
                    normalize(it.species.showdownId) == normalize(currentOwn.species.showdownId)
                }.coerceAtLeast(0)
                val formPicker = spinner(forms.map { "形态：${it.species.displayName}" }, selectedFormIndex)
                formPicker.onItemSelected { position ->
                    val selectedForm = forms[position].species
                    if (normalize(selectedForm.showdownId) != normalize(currentOwn.species.showdownId)) {
                        val current = currentPanelSession(session)
                        val currentState = current.calculation
                        val currentTeam = teams.first { it.id == current.selectedOwnTeamId }
                        val currentBase = currentTeam.pokemon[currentState.ownSlot]
                        val overrides = currentState.ownFormOverrides.toMutableMap().apply {
                            if (normalize(selectedForm.showdownId) == normalize(currentBase.species.showdownId)) {
                                remove(currentState.ownSlot)
                            } else {
                                put(currentState.ownSlot, selectedForm)
                            }
                        }
                        updateSession(current.copy(calculation = currentState.copy(
                            ownFormOverrides = overrides,
                        )), teams)
                    }
                }
                addView(formPicker, weighted(weight = 0.38f))
            }
        }

        fun opponentSelectorWithForm() = horizontal(spacing = 3).apply {
            val forms = presetRepository.formsFor(opponent)
            addView(opponentSelector(), weighted(weight = if (forms.size > 1) 0.62f else 1f))
            if (forms.size > 1) {
                val selectedFormIndex = forms.indexOfFirst {
                    normalize(it.species.showdownId) == normalize(opponent.showdownId)
                }.coerceAtLeast(0)
                val formPicker = spinner(forms.map { "形态：${it.species.displayName}" }, selectedFormIndex)
                formPicker.onItemSelected { position ->
                    val selectedForm = forms[position].species
                    if (normalize(selectedForm.showdownId) != normalize(opponent.showdownId)) {
                        val current = currentPanelSession(session)
                        val currentState = current.calculation
                        val currentOpponentBase = current.opponentTeam[currentState.opponentSlot]
                        val overrides = currentState.opponentFormOverrides.toMutableMap().apply {
                            if (normalize(selectedForm.showdownId) == normalize(currentOpponentBase.showdownId)) {
                                remove(currentState.opponentSlot)
                            } else {
                                put(currentState.opponentSlot, selectedForm)
                            }
                        }
                        val manualOverrides = currentState.opponentManualOverrides.toMutableMap().apply {
                            remove(currentState.opponentSlot)
                        }
                        updateSession(current.copy(calculation = currentState.withOpponentPreset(null).copy(
                            opponentFormOverrides = overrides,
                            opponentManualOverrides = manualOverrides,
                        )), teams)
                    }
                }
                addView(formPicker, weighted(weight = 0.38f))
            }
        }

        fun moveSelector() = vertical(spacing = 2).apply {
            if (moveOptions.isEmpty()) {
                addView(bodyText("当前没有可用伤害招式", color = ACCENT_AMBER))
            } else {
                val presetMoveIds = basePreset.moves.map { normalize(it.entity.showdownId) }.toSet()
                val labels = moveOptions.map { move ->
                    val typeLabel = presetRepository.moveTypeLabel(move)
                    if (state.direction == "OPPONENT_TO_OWN") {
                        "${if (normalize(move.entity.showdownId) in presetMoveIds) "配置招式" else "可选招式"} · [$typeLabel] ${move.entity.displayName}"
                    } else {
                        "[$typeLabel] ${move.entity.displayName}"
                    }
                }
                val selectedMoveIndex = moveOptions.indexOfFirst {
                    normalize(it.entity.showdownId) == normalize(state.selectedMoveId.orEmpty())
                }.coerceAtLeast(0)
                addView(horizontal(spacing = 4).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(bodyText("招式", color = TEXT_MUTED).apply { textSize = 12f }, weighted(width = dp(34)))
                    val movePicker = spinner(labels, selectedMoveIndex)
                    movePicker.onItemSelected { position ->
                        val id = moveOptions[position].entity.showdownId
                        updatePanelCalculation(teams) { current ->
                            current.copy(calculation = current.calculation.copy(selectedMoveId = id))
                        }
                    }
                    addView(movePicker, weighted(weight = 1f))
                    addView(miniButton(moveSortMode.label, secondary = true) {
                        moveSortMode = MoveSortMode.entries.toList().next(moveSortMode)
                        renderPanel(currentPanelSession(session), teams)
                    })
                })
            }
        }

        fun presetSelector() = compactLabeledPicker(
            label = "配置",
            options = profiles.map(::profileLabel),
            selected = profiles.indexOfFirst { it.profileId == basePreset.profileId }.coerceAtLeast(0),
        ) { position ->
            val selected = profiles[position]
            val current = currentPanelSession(session)
            val currentState = current.calculation
            if (selected.profileId != currentState.selectedPresetId) {
                updateSession(current.copy(calculation = applyOpponentPresetSelection(currentState, selected)), teams)
            }
        }

        fun abilityPreview() = horizontal(spacing = 4).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(bodyText("特性", color = TEXT_MUTED).apply { textSize = 12f }, weighted(width = dp(34)))
            val abilities = presetRepository.abilitiesFor(opponent)
            if (abilities.isEmpty()) {
                addView(bodyText("暂无候选", color = ACCENT_AMBER), weighted(weight = 1f))
            } else {
                val selectedAbility = abilities.indexOfFirst {
                    normalize(it.showdownId) == normalize(preset.ability?.showdownId.orEmpty())
                }.coerceAtLeast(0)
                addView(spinner(abilities.map { "可能：${it.displayName}" }, selectedAbility), weighted(weight = 1f))
            }
        }

        val resultValue = TextView(context).apply {
            text = if (runtime.isReady) "正在计算…" else "正在加载…"
            textSize = 24f
            setTextColor(PRIMARY)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setSingleLine()
        }
        val resultDetails = bodyText("修改选项后自动刷新", color = TEXT_MUTED).apply {
            textSize = 11f
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        fun resultCard(compact: Boolean = false) = vertical(spacing = 2).apply {
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = roundedBackground(SURFACE, SURFACE_BORDER, 10f)
            addView(bodyText("实时伤害", color = ACCENT_TEAL, bold = true).apply { textSize = 12f })
            addView(resultValue.apply { textSize = if (compact) 21f else 26f })
            addView(resultDetails.apply { maxLines = if (compact) 2 else 3 })
        }

        fun environmentSelectors() = horizontal(spacing = 4).apply {
            val weatherValues = listOf("NONE", "Sun", "Rain", "Sand", "Snow")
            addView(compactPicker(
                "天气",
                weatherValues.map(::weatherLabel),
                weatherValues.indexOf(state.weather),
            ) { position ->
                val selected = weatherValues[position]
                updatePanelCalculation(teams) { current ->
                    current.copy(calculation = current.calculation.copy(weather = selected))
                }
            }, weighted(weight = 1f))
            val terrainValues = listOf("NONE", "Electric", "Grassy", "Psychic", "Misty")
            addView(compactPicker(
                "场地",
                terrainValues.map(::terrainLabel),
                terrainValues.indexOf(state.terrain),
            ) { position ->
                val selected = terrainValues[position]
                updatePanelCalculation(teams) { current ->
                    current.copy(calculation = current.calculation.copy(terrain = selected))
                }
            }, weighted(weight = 1f))
        }

        fun quickToolbar() = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(horizontal(spacing = 4).apply {
        addView(miniButton("战场状态", secondary = true) { showConditions(currentPanelSession(session), teams) })
        addView(miniButton(if (manualOverride == null) "对手配置" else "对手配置*", secondary = true) {
                    showOpponentEditor(currentPanelSession(session), teams, opponent, basePreset)
                })
                addView(miniButton("速度线", secondary = true) { showSpeedLine(currentPanelSession(session), teams) })
        addView(miniButton("重新识别", secondary = true) { showSetup() })
            })
        }

        val header = titleBar("伤害面板")
        makeDraggable(header.getChildAt(0), root, params, panelWindowState, battlePanelPositionState)
        root.addView(header)
        val content = vertical(spacing = 4).apply {
            addView(directionSelector())
            addView(ownSelectorWithForm())
            addView(opponentSelectorWithForm())
            addView(moveSelector())
            addView(teamSelector())
            addView(presetSelector())
            addView(abilityPreview())
            addView(resultCard(compact = true))
            addView(environmentSelectors())
            addView(quickToolbar())
        }
        // Only the title and resize footer stay fixed. Keeping every control in one
        // scroll surface prevents short remembered windows from clipping selectors
        // behind the result card while leaving the footer available to grow again.
        root.addView(
            scroll(content, panelWindowState),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(resizeHandle(root, params, panelWindowState))

        if (!reuseWindow) addOverlay(root, params)
        panelView = root
        panelSession = session
        panelCalculationBinding = PanelCalculationBinding(
            ownTeam = ownTeam,
            preset = preset,
            legalMoves = legalMoves,
            valueView = resultValue,
            detailsView = resultDetails,
        )
        scheduleCalculation(session, ownTeam, preset, legalMoves, resultValue, resultDetails)
    }

    @Suppress("unused")
    private fun renderLegacyPanel(session: BattleSession, teams: List<SavedTeam>) {
        dismissOpponentEditor(showPanel = false)
        dismissConditions(showPanel = false)
        dismissSpeedLine(showPanel = false)
        dismissPanel(showBubble = false)
        onOverlayVisible(true)
        val ownTeam = teams.first { it.id == session.selectedOwnTeamId }
        val state = session.calculation
        val currentOwnBase = ownTeam.pokemon[state.ownSlot]
        val currentOwn = presetRepository.effectiveOwnPokemon(currentOwnBase, state.ownFormOverrides[state.ownSlot])
        val opponentBase = session.opponentTeam[state.opponentSlot]
        val opponent = state.opponentFormOverrides[state.opponentSlot] ?: opponentBase
        val profiles = presetRepository.profilesFor(opponent)
        val basePreset = profiles.first { it.profileId == state.selectedPresetId }
        val manualOverride = state.opponentManualOverrides[state.opponentSlot]
        val preset = presetRepository.effectivePreset(opponent, basePreset, manualOverride)
        val legalMoves = presetRepository.movesFor(opponent, basePreset.moves)

        val root = panelRoot()
        val params = largePanelParams(panelWindowState, defaultWidthDp = 900, defaultHeightDp = 640)
        val header = header("伤害面板", "选择攻防双方与招式，结果会自动更新") {
            dismissPanel()
        }
        makeDraggable(header.getChildAt(0), root, params, panelWindowState, battlePanelPositionState)
        root.addView(header)
        val content = vertical(spacing = 12)

        content.addView(horizontal(spacing = 8).apply {
            addView(toggleButton("我方输出", state.direction == "OWN_TO_OPPONENT") {
                updateSession(session.copy(calculation = defaultsForDirection(session, ownTeam, "OWN_TO_OPPONENT")), teams)
            })
            addView(toggleButton("我方承伤", state.direction == "OPPONENT_TO_OWN") {
                updateSession(session.copy(calculation = defaultsForDirection(session, ownTeam, "OPPONENT_TO_OWN")), teams)
            })
            addView(actionButton("速度线", secondary = true) { showSpeedLine(session, teams) })
        addView(actionButton("重新确认阵容", secondary = true) { showSetup() })
        })

        val sides = horizontal(spacing = 12)
        val ownColumn = cardColumn().apply {
            addView(label("我方队伍"))
            val teamIndex = teams.indexOfFirst { it.id == session.selectedOwnTeamId }
            val teamPicker = spinner(teams.map { it.name }, teamIndex)
            teamPicker.onItemSelected { position ->
                val selected = teams[position]
                if (selected.id != session.selectedOwnTeamId) {
                    val changed = switchOwnTeam(session, selected.id)
                    updateSession(ensureValidState(changed, selected), teams)
                }
            }
            addView(teamPicker)
            addView(bodyText("选择当前我方宝可梦", color = TEXT_MUTED))
            val ownPicker = spinner(ownTeam.pokemon.mapIndexed { slot, pokemon ->
                "${slot + 1}. ${presetRepository.effectiveOwnPokemon(pokemon, state.ownFormOverrides[slot]).species.displayName}"
            }, state.ownSlot)
            ownPicker.onItemSelected { slot ->
                if (slot != state.ownSlot) {
                    val changed = session.copy(calculation = state.copy(ownSlot = slot, selectedMoveId = null))
                    updateSession(ensureValidState(changed, ownTeam), teams)
                }
            }
            addView(ownPicker)
            val ownForms = presetRepository.formsFor(currentOwn.species)
            if (ownForms.size > 1) {
                addView(bodyText("当前形态", color = TEXT_MUTED))
                val selectedFormIndex = ownForms.indexOfFirst {
                    normalize(it.species.showdownId) == normalize(currentOwn.species.showdownId)
                }.coerceAtLeast(0)
                val formPicker = spinner(ownForms.map { it.species.displayName }, selectedFormIndex)
                formPicker.onItemSelected { position ->
                    val selectedForm = ownForms[position].species
                    if (normalize(selectedForm.showdownId) != normalize(currentOwn.species.showdownId)) {
                        val overrides = state.ownFormOverrides.toMutableMap().apply {
                            if (normalize(selectedForm.showdownId) == normalize(currentOwnBase.species.showdownId)) remove(state.ownSlot)
                            else put(state.ownSlot, selectedForm)
                        }
                        updateSession(session.copy(calculation = state.copy(
                            ownFormOverrides = overrides,
                        )), teams)
                    }
                }
                addView(formPicker)
            }
            addView(bodyText(ownPokemonSummary(currentOwn, currentOwnBase), color = TEXT_MUTED))
        }
        val opponentColumn = cardColumn().apply {
            addView(label("本局对手"))
            addView(bodyText("来自已确认的对手阵容", color = TEXT_MUTED))
            addView(bodyText("选择当前对手宝可梦", color = TEXT_MUTED))
            val opponentPicker = spinner(session.opponentTeam.mapIndexed { slot, pokemon ->
                "${slot + 1}. ${(state.opponentFormOverrides[slot] ?: pokemon).displayName}"
            }, state.opponentSlot)
            opponentPicker.onItemSelected { slot ->
                if (slot != state.opponentSlot) {
                    val changed = session.copy(calculation = state.withOpponentSlot(slot).copy(
                        selectedMoveId = null,
                    ))
                    updateSession(ensureValidState(changed, ownTeam), teams)
                }
            }
            addView(opponentPicker)
            val opponentForms = presetRepository.formsFor(opponent)
            if (opponentForms.size > 1) {
                addView(bodyText("当前形态", color = TEXT_MUTED))
                val selectedFormIndex = opponentForms.indexOfFirst {
                    normalize(it.species.showdownId) == normalize(opponent.showdownId)
                }.coerceAtLeast(0)
                val formPicker = spinner(opponentForms.map { it.species.displayName }, selectedFormIndex)
                formPicker.onItemSelected { position ->
                    val selectedForm = opponentForms[position].species
                    if (normalize(selectedForm.showdownId) != normalize(opponent.showdownId)) {
                        val overrides = state.opponentFormOverrides.toMutableMap().apply {
                            if (normalize(selectedForm.showdownId) == normalize(opponentBase.showdownId)) remove(state.opponentSlot)
                            else put(state.opponentSlot, selectedForm)
                        }
                        val manualOverrides = state.opponentManualOverrides.toMutableMap().apply { remove(state.opponentSlot) }
                        updateSession(session.copy(calculation = state.withOpponentPreset(null).copy(
                            opponentFormOverrides = overrides,
                            opponentManualOverrides = manualOverrides,
                        )), teams)
                    }
                }
                addView(formPicker)
            }
            addView(bodyText("当前选择：${opponent.displayName}\n队伍预览无法显示详细配置，请在下方选择一种可能配置。", color = TEXT_MUTED))
            val abilities = presetRepository.abilitiesFor(opponent)
            if (abilities.isEmpty()) {
                addView(bodyText("可能特性：暂无候选数据", color = ACCENT_AMBER))
            } else {
                addView(bodyText("队伍预览无法确认特性；可在“自定义配置”中选择。", color = TEXT_MUTED))
                val selectedAbility = abilities.indexOfFirst {
                    normalize(it.showdownId) == normalize(preset.ability?.showdownId.orEmpty())
                }.coerceAtLeast(0)
                addView(spinner(abilities.map(EntityValue::displayName), selectedAbility))
            }
        }
        sides.addView(ownColumn, weighted(weight = 1f))
        sides.addView(opponentColumn, weighted(weight = 1f))
        content.addView(sides)

        val selectionCard = cardColumn()
        val moveOptions = sortMoves(
            moves = if (state.direction == "OWN_TO_OPPONENT") currentOwn.moves else legalMoves,
            mode = moveSortMode,
            typeOf = presetRepository::moveTypeFor,
        )
        val selectedMoveIndex = moveOptions.indexOfFirst { normalize(it.entity.showdownId) == normalize(state.selectedMoveId.orEmpty()) }
            .coerceAtLeast(0)
        selectionCard.addView(label(if (state.direction == "OWN_TO_OPPONENT") "我方招式" else "选择对手招式"))
        if (moveOptions.isEmpty()) {
            selectionCard.addView(bodyText("这只对手宝可梦没有可选的伤害招式；请切换宝可梦，或先查看我方输出。", color = ACCENT_AMBER))
        } else {
            selectionCard.addView(horizontal(spacing = 8).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(bodyText("排序方式", color = TEXT_MUTED), weighted(weight = 1f))
                MoveSortMode.entries.forEach { mode ->
                    addView(toggleButton(mode.label, moveSortMode == mode) {
                        if (moveSortMode != mode) {
                            moveSortMode = mode
                            renderPanel(session, teams)
                        }
                    })
                }
            })
            val presetMoveIds = basePreset.moves.map { normalize(it.entity.showdownId) }.toSet()
            val moveLabels = moveOptions.map { move ->
                val typeLabel = presetRepository.moveTypeLabel(move)
                if (state.direction == "OPPONENT_TO_OWN") {
                    "${if (normalize(move.entity.showdownId) in presetMoveIds) "配置招式" else "可选招式"} · [$typeLabel] ${move.entity.displayName}"
                } else "[$typeLabel] ${move.entity.displayName}"
            }
            val movePicker = spinner(moveLabels, selectedMoveIndex)
            movePicker.onItemSelected { position ->
                val id = moveOptions[position].entity.showdownId
                if (normalize(id) != normalize(state.selectedMoveId.orEmpty())) {
                    updateSession(session.copy(calculation = state.copy(selectedMoveId = id)), teams)
                }
            }
            selectionCard.addView(movePicker)
        }
        selectionCard.addView(horizontal(spacing = 8).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("对手配置"), weighted(weight = 1f))
            addView(actionButton(if (manualOverride == null) "自定义配置" else "修改自定义配置", secondary = true) {
                showOpponentEditor(session, teams, opponent, basePreset)
            })
        })
        val presetIndex = profiles.indexOfFirst { it.profileId == basePreset.profileId }.coerceAtLeast(0)
        val presetPicker = spinner(profiles.map { profileLabel(it) }, presetIndex)
        presetPicker.onItemSelected { position ->
                val selected = profiles[position]
                if (selected.profileId != state.selectedPresetId) {
                updateSession(session.copy(calculation = applyOpponentPresetSelection(state, selected)), teams)
            }
        }
        selectionCard.addView(presetPicker)
        selectionCard.addView(bodyText(presetSummary(preset), color = TEXT_MUTED))
        content.addView(selectionCard)

        val quick = horizontal(spacing = 8)
        val weatherValues = listOf("NONE", "Sun", "Rain", "Sand", "Snow")
        quick.addView(compactPicker("天气", weatherValues.map(::weatherLabel), weatherValues.indexOf(state.weather)) { position ->
            val selected = weatherValues[position]
            if (selected != state.weather) updateSession(session.copy(calculation = state.copy(weather = selected)), teams)
        }, weighted(weight = 1f))
        val terrainValues = listOf("NONE", "Electric", "Grassy", "Psychic", "Misty")
        quick.addView(compactPicker("场地", terrainValues.map(::terrainLabel), terrainValues.indexOf(state.terrain)) { position ->
            val selected = terrainValues[position]
            if (selected != state.terrain) updateSession(session.copy(calculation = state.copy(terrain = selected)), teams)
        }, weighted(weight = 1f))
        quick.addView(actionButton("战场状态", secondary = true) { showConditions(session, teams) })
        content.addView(quick)

        val resultCard = cardColumn()
        val resultTitle = label("实时伤害")
        val resultValue = TextView(context).apply {
            text = if (runtime.isReady) "正在计算…" else "正在准备伤害计算…"
            textSize = 28f
            setTextColor(PRIMARY)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val resultDetails = bodyText("修改任一选项后会自动刷新。")
        resultCard.addView(resultTitle)
        resultCard.addView(resultValue)
        resultCard.addView(resultDetails)
        content.addView(resultCard)

        root.addView(scroll(content, panelWindowState), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(resizeHandle(root, params, panelWindowState))
        addOverlay(root, params)
        panelView = root
        scheduleCalculation(session, ownTeam, preset, legalMoves, resultValue, resultDetails)
    }

    private fun showConditions(session: BattleSession, teams: List<SavedTeam>) {
        panelNavigation.show(BattlePanelPage.CONDITIONS)
        dismissConditions(showPanel = false)
        panelView?.visibility = View.INVISIBLE
        val original = session.calculation
        var draft = original
        val root = compactPanelRoot()
        val params = rightRailPanelParams(
            conditionsWindowState,
            widthDp = 360,
            sharedPositionState = battlePanelPositionState,
        )
        val header = header(
            "战场状态与能力变化",
            "保存后立即更新伤害结果",
            collapse = ::collapsePanel,
        ) {
            dismissConditions()
        }
        makeDraggable(header.getChildAt(0), root, params, conditionsWindowState, battlePanelPositionState)
        root.addView(header)
        val content = vertical(spacing = 10)
        content.addView(label("基础规则"))
        content.addView(checkRow("双打", draft.battleType == "DOUBLE") { draft = draft.copy(battleType = if (it) "DOUBLE" else "SINGLE") })
        content.addView(checkRow("帮助加成", draft.helpingHand) { draft = draft.copy(helpingHand = it) })
        content.addView(checkRow("会心", draft.critical) { draft = draft.copy(critical = it) })
        content.addView(checkRow("范围招式修正", draft.spread) { draft = draft.copy(spread = it) })
        content.addView(label("我方状态"))
        content.addView(checkRow("反射壁", draft.ownReflect) { draft = draft.copy(ownReflect = it) })
        content.addView(checkRow("光墙", draft.ownLightScreen) { draft = draft.copy(ownLightScreen = it) })
        content.addView(checkRow("极光幕", draft.ownAuroraVeil) { draft = draft.copy(ownAuroraVeil = it) })
        content.addView(checkRow("守住", draft.ownProtected) { draft = draft.copy(ownProtected = it) })
        var ownCondition = draft.ownCondition()
        content.addView(checkRow("烧伤", ownCondition.burned) {
            ownCondition = ownCondition.copy(burned = it)
            draft = draft.withOwnCondition(condition = ownCondition)
        })
        val ownStageHolder = arrayOf(ownCondition.stages)
        content.addView(stageEditor("我方当前宝可梦能力等级", ownStageHolder) {
            ownCondition = ownCondition.copy(stages = it)
            draft = draft.withOwnCondition(condition = ownCondition)
        })
        content.addView(label("对手状态"))
        content.addView(checkRow("反射壁", draft.opponentReflect) { draft = draft.copy(opponentReflect = it) })
        content.addView(checkRow("光墙", draft.opponentLightScreen) { draft = draft.copy(opponentLightScreen = it) })
        content.addView(checkRow("极光幕", draft.opponentAuroraVeil) { draft = draft.copy(opponentAuroraVeil = it) })
        content.addView(checkRow("守住", draft.opponentProtected) { draft = draft.copy(opponentProtected = it) })
        var opponentCondition = draft.opponentCondition()
        content.addView(checkRow("烧伤", opponentCondition.burned) {
            opponentCondition = opponentCondition.copy(burned = it)
            draft = draft.withOpponentCondition(condition = opponentCondition)
        })
        val opponentStageHolder = arrayOf(opponentCondition.stages)
        content.addView(stageEditor("对手当前宝可梦能力等级", opponentStageHolder) {
            opponentCondition = opponentCondition.copy(stages = it)
            draft = draft.withOpponentCondition(condition = opponentCondition)
        })
        content.addView(horizontal(spacing = 10).apply {
            gravity = Gravity.END
            addView(actionButton("全部重置", secondary = true) {
                draft = BattleCalculationState(
                    direction = original.direction,
                    ownSlot = original.ownSlot,
                    opponentSlot = original.opponentSlot,
                    selectedPresetId = original.selectedPresetId,
                    opponentPresetIds = original.opponentPresetIds,
                    selectedMoveId = original.selectedMoveId,
                    ownFormOverrides = original.ownFormOverrides,
                    opponentFormOverrides = original.opponentFormOverrides,
                    opponentManualOverrides = original.opponentManualOverrides,
                    speedLine = original.speedLine,
                )
                updateSession(session.copy(calculation = draft), teams)
                dismissConditions(showPanel = false)
            })
            addView(actionButton("应用并重算") {
                updateSession(session.copy(calculation = draft), teams)
                dismissConditions(showPanel = false)
            })
        })
        root.addView(scroll(content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(resizeHandle(root, params, conditionsWindowState))
        addOverlay(root, params)
        conditionsView = root
    }

    private fun showSpeedLine(session: BattleSession, teams: List<SavedTeam>) {
        panelNavigation.show(BattlePanelPage.SPEED_LINE)
        dismissSpeedLine(showPanel = false)
        panelView?.visibility = View.INVISIBLE
        onOverlayVisible(true)
        val ownTeam = teams.firstOrNull { it.id == session.selectedOwnTeamId } ?: teams.first()
        val speedState = session.calculation.speedLine
        speedEditorSlot = speedEditorSlot.coerceIn(0, 5)
        val root = compactPanelRoot()
        val params = rightRailPanelParams(
            speedLineWindowState,
            widthDp = 400,
            sharedPositionState = battlePanelPositionState,
        )
        val header = header(
            "双方速度线",
            "越靠左越先行动；高先制度始终排在普通行动之前",
            collapse = ::collapsePanel,
        ) { dismissSpeedLine() }
        makeDraggable(header.getChildAt(0), root, params, speedLineWindowState, battlePanelPositionState)
        root.addView(header)
        val content = vertical(spacing = 10)

        content.addView(cardColumn().apply {
            addView(label("全局与队伍效果"))
            addView(horizontal(spacing = 12).apply {
                addView(checkRow("我方顺风（速度×2）", speedState.ownTailwind) {
                    persistSpeedLine(session, teams, speedState.copy(ownTailwind = it))
                }, weighted(weight = 1f))
            addView(checkRow("对手顺风（速度×2）", speedState.opponentTailwind) {
                    persistSpeedLine(session, teams, speedState.copy(opponentTailwind = it))
                }, weighted(weight = 1f))
                addView(checkRow("戏法空间", speedState.trickRoom) {
                    persistSpeedLine(session, teams, speedState.copy(trickRoom = it))
                }, weighted(weight = 1f))
            })
            addView(bodyText(
                if (speedState.trickRoom) "戏法空间已开启：只反转同一先制度内的快慢顺序，先制度本身不反转。"
                else "戏法空间未开启：同一先制度内速度更高者先行动。",
                color = TEXT_MUTED,
            ))
        })

        val actions = speedLineActions(session, ownTeam)
        content.addView(cardColumn().apply {
            addView(label("行动条"))
            addView(bodyText(
                "蓝点＝我方实际速度；橙条＝对手速度范围。非守住类先制招式另列行动点。",
                color = TEXT_MUTED,
            ).apply { textSize = 10f })
            val chart = SpeedLineChartView(context, actions, speedState.trickRoom)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = true
                addView(chart)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        content.addView(cardColumn().apply {
            addView(horizontal(spacing = 8).apply {
                addView(label("逐只调整"), weighted(weight = 1f))
                addView(actionButton("全部重置", secondary = true) {
                    persistSpeedLine(session, teams, SpeedLineState())
                })
            })
            addView(horizontal(spacing = 8).apply {
                addView(toggleButton("我方", speedEditorSide == SpeedSide.OWN) {
                    if (speedEditorSide != SpeedSide.OWN) {
                        speedEditorSide = SpeedSide.OWN
                        showSpeedLine(session, teams)
                    }
                }, weighted(weight = 1f))
            addView(toggleButton("对手", speedEditorSide == SpeedSide.OPPONENT) {
                    if (speedEditorSide != SpeedSide.OPPONENT) {
                        speedEditorSide = SpeedSide.OPPONENT
                        showSpeedLine(session, teams)
                    }
                }, weighted(weight = 1f))
            })
            val pokemonLabels = if (speedEditorSide == SpeedSide.OWN) {
                ownTeam.pokemon.mapIndexed { slot, pokemon ->
                    val current = presetRepository.effectiveOwnPokemon(pokemon, session.calculation.ownFormOverrides[slot])
                    "${slot + 1}. ${current.species.displayName} · 速度 ${current.actualStats.spe.ifBlank { "未知" }}"
                }
            } else {
                session.opponentTeam.mapIndexed { slot, pokemon ->
                    val current = session.calculation.opponentFormOverrides[slot] ?: pokemon
                    val range = presetRepository.possibleSpeedRangeFor(current)
                    "${slot + 1}. ${current.displayName} · ${range?.let { "${it.first}–${it.last}" } ?: "速度未知"}"
                }
            }
            val pokemonPicker = spinner(pokemonLabels, speedEditorSlot)
            pokemonPicker.onItemSelected { slot ->
                if (slot != speedEditorSlot) {
                    speedEditorSlot = slot
                    showSpeedLine(session, teams)
                }
            }
            addView(pokemonPicker)

            val baseForm = if (speedEditorSide == SpeedSide.OWN) {
                ownTeam.pokemon[speedEditorSlot].species
            } else {
                session.opponentTeam[speedEditorSlot]
            }
            val currentForm = if (speedEditorSide == SpeedSide.OWN) {
                session.calculation.ownFormOverrides[speedEditorSlot] ?: baseForm
            } else {
                session.calculation.opponentFormOverrides[speedEditorSlot] ?: baseForm
            }
            val formOptions = presetRepository.formsFor(baseForm)
            if (formOptions.size > 1) {
                addView(bodyText("当前形态（同步到伤害面板）", color = TEXT_MUTED))
                val selectedFormIndex = formOptions.indexOfFirst {
                    normalize(it.species.showdownId) == normalize(currentForm.showdownId)
                }.coerceAtLeast(0)
                val formPicker = spinner(formOptions.map { it.species.displayName }, selectedFormIndex)
                formPicker.onItemSelected { position ->
                    val selectedForm = formOptions[position].species
                    if (normalize(selectedForm.showdownId) != normalize(currentForm.showdownId)) {
                        updateSpeedLineForm(
                            session = session,
                            teams = teams,
                            ownTeam = ownTeam,
                            side = speedEditorSide,
                            slot = speedEditorSlot,
                            selectedForm = selectedForm,
                        )
                    }
                }
                addView(formPicker)
            }

            val modifiers = if (speedEditorSide == SpeedSide.OWN) {
                speedState.ownPokemon[speedEditorSlot] ?: SpeedPokemonModifiers()
            } else {
                speedState.opponentPokemon[speedEditorSlot] ?: SpeedPokemonModifiers()
            }
            val knownScarf = speedEditorSide == SpeedSide.OWN && normalize(
                ownTeam.pokemon[speedEditorSlot].item?.showdownId.orEmpty(),
            ) == "choicescarf"
            fun replaceModifiers(next: SpeedPokemonModifiers?) {
                val own = speedState.ownPokemon.toMutableMap()
                val opponent = speedState.opponentPokemon.toMutableMap()
                val target = if (speedEditorSide == SpeedSide.OWN) own else opponent
                if (next == null) target.remove(speedEditorSlot) else target[speedEditorSlot] = next
                persistSpeedLine(session, teams, speedState.copy(ownPokemon = own, opponentPokemon = opponent))
            }
            addView(horizontal(spacing = 8).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(bodyText("速度能力等级  ${signed(modifiers.stage)}", bold = true), weighted(weight = 1f))
                addView(actionButton("−", secondary = true) {
                    replaceModifiers(modifiers.copy(stage = (modifiers.stage - 1).coerceAtLeast(-6)))
                })
                addView(actionButton("＋", secondary = true) {
                    replaceModifiers(modifiers.copy(stage = (modifiers.stage + 1).coerceAtMost(6)))
                })
                addView(actionButton("重置当前", secondary = true) { replaceModifiers(null) })
            })
            addView(horizontal(spacing = 12).apply {
                addView(checkRow("麻痹（×0.5）", modifiers.paralyzed) {
                    replaceModifiers(modifiers.copy(paralyzed = it))
                }, weighted(weight = 1f))
                addView(checkRow("速度翻倍", modifiers.doubled) {
                    replaceModifiers(modifiers.copy(doubled = it))
                }, weighted(weight = 1f))
                addView(checkRow(
                    if (knownScarf && modifiers.choiceScarf == null) "讲究围巾（队伍已知）" else "讲究围巾（×1.5）",
                    modifiers.choiceScarf ?: knownScarf,
                ) {
                    replaceModifiers(modifiers.copy(choiceScarf = it))
                }, weighted(weight = 1f))
            })
            if (speedEditorSide == SpeedSide.OPPONENT) {
            addView(bodyText("对手速度显示为可能范围，依据当前形态、0～32 速度点和性格计算；只有你手动确认的状态才会作为已知条件。", color = TEXT_MUTED))
            }
        })

        root.addView(scroll(content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(resizeHandle(root, params, speedLineWindowState))
        addOverlay(root, params)
        speedLineView = root
    }

    private fun updateSpeedLineForm(
        session: BattleSession,
        teams: List<SavedTeam>,
        ownTeam: SavedTeam,
        side: SpeedSide,
        slot: Int,
        selectedForm: EntityValue,
    ) {
        val state = session.calculation
        val changedState = if (side == SpeedSide.OWN) {
            val base = ownTeam.pokemon[slot].species
            val overrides = state.ownFormOverrides.toMutableMap().apply {
                if (normalize(selectedForm.showdownId) == normalize(base.showdownId)) remove(slot)
                else put(slot, selectedForm)
            }
            state.copy(
                ownFormOverrides = overrides,
            )
        } else {
            val base = session.opponentTeam[slot]
            val overrides = state.opponentFormOverrides.toMutableMap().apply {
                if (normalize(selectedForm.showdownId) == normalize(base.showdownId)) remove(slot)
                else put(slot, selectedForm)
            }
            val manualOverrides = state.opponentManualOverrides.toMutableMap().apply { remove(slot) }
            state.withOpponentPreset(null, slot).copy(
                opponentFormOverrides = overrides,
                opponentManualOverrides = manualOverrides,
            )
        }
        val changed = ensureValidState(session.copy(calculation = changedState), ownTeam)
        saveSession(changed)
        showSpeedLine(changed, teams)
    }

    private fun persistSpeedLine(session: BattleSession, teams: List<SavedTeam>, speedLine: SpeedLineState) {
        val changed = session.copy(calculation = session.calculation.copy(speedLine = speedLine))
        val ownTeam = teams.firstOrNull { it.id == changed.selectedOwnTeamId } ?: teams.first()
        val corrected = ensureValidState(changed, ownTeam)
        saveSession(corrected)
        showSpeedLine(corrected, teams)
    }

    private fun speedLineActions(session: BattleSession, ownTeam: SavedTeam): List<SpeedLineAction> {
        val state = session.calculation
        val speed = state.speedLine
        val ownInputs = ownTeam.pokemon.mapIndexed { slot, base ->
            val pokemon = presetRepository.effectiveOwnPokemon(base, state.ownFormOverrides[slot])
            val knownSpeed = pokemon.actualStats.spe.toIntOrNull()?.takeIf { it > 0 }
            val fallback = presetRepository.possibleSpeedRangeFor(pokemon.species) ?: 1..1
            val priorityMoves = pokemon.moves.filter(presetRepository::isSpeedLinePriorityMove)
                .map { SpeedLineMove(it.entity.displayName, presetRepository.movePriority(it)) }
            SpeedLinePokemonInput(
                side = SpeedSide.OWN,
                slot = slot,
                name = pokemon.species.displayName,
                baseSpeed = knownSpeed?.let { it..it } ?: fallback,
                modifiers = speed.ownPokemon[slot] ?: SpeedPokemonModifiers(),
                tailwind = speed.ownTailwind,
                knownChoiceScarf = normalize(pokemon.item?.showdownId.orEmpty()) == "choicescarf",
                priorityMoves = priorityMoves,
                exactBaseSpeed = knownSpeed != null,
            )
        }
        val opponentInputs = session.opponentTeam.mapIndexed { slot, base ->
            val pokemon = state.opponentFormOverrides[slot] ?: base
            val range = presetRepository.possibleSpeedRangeFor(pokemon) ?: 1..1
            val priorityMoves = presetRepository.speedLinePriorityMovesFor(pokemon).map {
                SpeedLineMove(it.entity.displayName, presetRepository.movePriority(it))
            }
            SpeedLinePokemonInput(
                side = SpeedSide.OPPONENT,
                slot = slot,
                name = pokemon.displayName,
                baseSpeed = range,
                modifiers = speed.opponentPokemon[slot] ?: SpeedPokemonModifiers(),
                tailwind = speed.opponentTailwind,
                priorityMoves = priorityMoves,
                exactBaseSpeed = false,
            )
        }
        return buildSpeedLineActions(ownInputs + opponentInputs, speed.trickRoom)
    }

    private fun showOpponentEditor(
        session: BattleSession,
        teams: List<SavedTeam>,
        opponent: EntityValue,
        basePreset: OpponentPreset,
    ) {
        panelNavigation.show(BattlePanelPage.OPPONENT_EDITOR)
        dismissOpponentEditor(showPanel = false)
        panelView?.visibility = View.INVISIBLE
        val state = session.calculation
        val existing = state.opponentManualOverrides[state.opponentSlot]
            ?.takeIf { it.baseProfileId == basePreset.profileId }
        val initialPoints = existing?.statPoints ?: basePreset.statPoints
        val initialNature = existing?.statAlignment ?: basePreset.statAlignment
        val initialAbility = if (existing != null) existing.ability else basePreset.ability
        val initialItemOverrideEnabled = existing?.itemOverrideEnabled ?: false
        val initialItem = existing?.item
        val values = initialPoints.asMap().mapValuesTo(linkedMapOf()) { (_, value) ->
            (value.toIntOrNull() ?: 0).coerceIn(0, 32).toString()
        }

        val root = compactPanelRoot()
        val params = rightRailPanelParams(
            opponentEditorWindowState,
            widthDp = 380,
            sharedPositionState = battlePanelPositionState,
        )
        val header = header(
            "调整对手配置 · ${opponent.displayName}",
            "只对当前对局生效，不会修改原配置",
            collapse = ::collapsePanel,
        ) {
            dismissOpponentEditor()
        }
        makeDraggable(header.getChildAt(0), root, params, opponentEditorWindowState, battlePanelPositionState)
        root.addView(header)
        val content = vertical(spacing = 10)
        content.addView(bodyText("当前配置：${profileLabel(basePreset)}", color = TEXT_MUTED))

        content.addView(label("能力点（每项 0–32）"))
        val inputs = linkedMapOf<String, EditText>()
        val statGrid = GridLayout(context).apply { columnCount = 3 }
        STAT_LABELS.forEachIndexed { index, (key, name) ->
            val input = EditText(context).apply {
                setText(values.getValue(key))
                setTextColor(TEXT)
                setHintTextColor(TEXT_MUTED)
                textSize = 16f
                gravity = Gravity.CENTER
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = roundedBackground(SURFACE_ALT, SURFACE_BORDER, 8f)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                        values[key] = text?.toString().orEmpty()
                    }
                    override fun afterTextChanged(text: Editable?) = Unit
                })
            }
            inputs[key] = input
            statGrid.addView(vertical(spacing = 4).apply {
                addView(bodyText(name, color = TEXT_MUTED).apply { gravity = Gravity.CENTER })
                addView(input)
            }, GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(index % 3, 1f)
                rowSpec = GridLayout.spec(index / 3)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        content.addView(statGrid)

        fun applyPointTemplate(points: Map<String, Int>) {
            inputs.forEach { (key, input) -> input.setText((points[key] ?: 0).toString()) }
        }
        content.addView(bodyText("快速模板", color = TEXT_MUTED))
        content.addView(vertical(spacing = 6).apply {
            addView(horizontal(spacing = 6).apply {
                addView(actionButton("清零", secondary = true) { applyPointTemplate(emptyMap()) }, weighted(weight = 1f))
                addView(actionButton("物耐", secondary = true) { applyPointTemplate(mapOf("hp" to 32, "def" to 32)) }, weighted(weight = 1f))
                addView(actionButton("特耐", secondary = true) { applyPointTemplate(mapOf("hp" to 32, "spd" to 32)) }, weighted(weight = 1f))
            })
            addView(horizontal(spacing = 6).apply {
                addView(actionButton("物攻", secondary = true) { applyPointTemplate(mapOf("atk" to 32, "spe" to 32)) }, weighted(weight = 1f))
                addView(actionButton("特攻", secondary = true) { applyPointTemplate(mapOf("spa" to 32, "spe" to 32)) }, weighted(weight = 1f))
            })
        })

        content.addView(label("性格数值修正"))
        val statAdjustmentOptions = listOf(
            null to "不调整（中性）",
            "atk" to "攻击",
            "def" to "防御",
            "spa" to "特攻",
            "spd" to "特防",
            "spe" to "速度",
        )
        val initialNatureOption = presetRepository.natures.firstOrNull {
            normalize(it.entity.showdownId) == normalize(initialNature?.showdownId.orEmpty())
        }
        val initialPlus = initialNatureOption?.plus?.takeUnless { it == initialNatureOption.minus }
        val initialMinus = initialNatureOption?.minus?.takeUnless { it == initialNatureOption.plus }
        val plusPicker = spinner(
            statAdjustmentOptions.map { it.second },
            statAdjustmentOptions.indexOfFirst { it.first == initialPlus }.coerceAtLeast(0),
        )
        val minusPicker = spinner(
            statAdjustmentOptions.map { it.second },
            statAdjustmentOptions.indexOfFirst { it.first == initialMinus }.coerceAtLeast(0),
        )
        content.addView(horizontal(spacing = 10).apply {
            addView(vertical(spacing = 4).apply {
                addView(bodyText("↑ 上升属性", color = ACCENT_TEAL, bold = true))
                addView(plusPicker)
            }, weighted(weight = 1f))
            addView(vertical(spacing = 4).apply {
                addView(bodyText("↓ 下降属性", color = ACCENT_AMBER, bold = true))
                addView(minusPicker)
            }, weighted(weight = 1f))
        })
        val natureHint = bodyText("", color = TEXT_MUTED)
        fun selectedNature(): NatureOption? {
            val plus = statAdjustmentOptions[plusPicker.selectedItemPosition].first
            val minus = statAdjustmentOptions[minusPicker.selectedItemPosition].first
            return if (plus == null || minus == null || plus == minus) null else {
                presetRepository.natures.firstOrNull { it.plus == plus && it.minus == minus }
            }
        }
        fun refreshNatureHint() {
            val plus = statAdjustmentOptions[plusPicker.selectedItemPosition].first
            val minus = statAdjustmentOptions[minusPicker.selectedItemPosition].first
            when {
                plus == null && minus == null -> {
                    natureHint.text = "中性：所有属性均不修正。"
                    natureHint.setTextColor(TEXT_MUTED)
                }
                plus == null || minus == null -> {
                    natureHint.text = "请同时选择上升属性和下降属性。"
                    natureHint.setTextColor(ACCENT_AMBER)
                }
                plus == minus -> {
                    natureHint.text = "上升和下降不能是同一属性；如需中性请两边都选“不调整”。"
                    natureHint.setTextColor(ERROR)
                }
                else -> {
                    val nature = selectedNature()
                    natureHint.text = nature?.let { "对应性格：${it.entity.displayName}" } ?: "找不到对应的标准性格。"
                    natureHint.setTextColor(if (nature == null) ERROR else ACCENT_TEAL)
                }
            }
        }
        plusPicker.onItemSelected { refreshNatureHint() }
        minusPicker.onItemSelected { refreshNatureHint() }
        refreshNatureHint()
        content.addView(natureHint)

        content.addView(label("特性"))
        val abilityOptions = (listOf<EntityValue?>(null) + listOfNotNull(initialAbility) +
            presetRepository.abilitiesFor(opponent)).distinctBy { normalize(it?.showdownId.orEmpty()) }
        val abilityIndex = abilityOptions.indexOfFirst {
            normalize(it?.showdownId.orEmpty()) == normalize(initialAbility?.showdownId.orEmpty())
        }.coerceAtLeast(0)
        val abilityPicker = spinner(abilityOptions.map { it?.displayName ?: "未指定" }, abilityIndex)
        content.addView(abilityPicker)

        content.addView(label("道具"))
        val itemOptions = (listOfNotNull(initialItem, basePreset.item) + presetRepository.itemCatalog)
            .distinctBy { normalize(it.showdownId) }
        val itemLabels = buildList {
            add("沿用当前配置：${basePreset.item?.displayName ?: "未指定"}")
            add("无道具")
            addAll(itemOptions.map(EntityValue::displayName))
        }
        val itemIndex = when {
            !initialItemOverrideEnabled -> 0
            initialItem == null -> 1
            else -> itemOptions.indexOfFirst { normalize(it.showdownId) == normalize(initialItem.showdownId) }
                .takeIf { it >= 0 }?.plus(2) ?: 1
        }
        val itemPicker = spinner(itemLabels, itemIndex)
        content.addView(itemPicker)
        content.addView(bodyText("可选择已观察到的讲究头带、讲究眼镜、讲究围巾、突击背心等道具，也可以设为无道具。", color = TEXT_MUTED))

        content.addView(bodyText("这些调整只对当前对局生效；切换其他配置后会恢复所选配置的设置。", color = TEXT_MUTED))
        content.addView(horizontal(spacing = 10).apply {
            gravity = Gravity.END
            if (existing != null) addView(actionButton("恢复原配置", secondary = true) {
                val overrides = state.opponentManualOverrides.toMutableMap().apply { remove(state.opponentSlot) }
                updateSession(session.copy(calculation = state.copy(opponentManualOverrides = overrides)), teams)
            })
            addView(actionButton("取消", secondary = true) { dismissOpponentEditor() })
            addView(actionButton("应用并重算") {
                val plus = statAdjustmentOptions[plusPicker.selectedItemPosition].first
                val minus = statAdjustmentOptions[minusPicker.selectedItemPosition].first
                val nature = selectedNature()
                val incomplete = (plus == null) != (minus == null)
                val invalidPair = plus != null && (plus == minus || nature == null)
                if (incomplete || invalidPair) {
                    refreshNatureHint()
                    publish("请确认对手的上升属性和下降属性")
                    return@actionButton
                }
                val points = StatFields.fromMap(values.mapValues { (_, raw) ->
                    (raw.toIntOrNull() ?: 0).coerceIn(0, 32).toString()
                })
                val selectedItemIndex = itemPicker.selectedItemPosition
                val manual = OpponentManualOverride(
                    baseProfileId = basePreset.profileId,
                    statPoints = points,
                    statAlignment = nature?.entity,
                    ability = abilityOptions[abilityPicker.selectedItemPosition],
                    itemOverrideEnabled = selectedItemIndex > 0,
                    item = itemOptions.getOrNull(selectedItemIndex - 2),
                )
                val overrides = state.opponentManualOverrides.toMutableMap().apply { put(state.opponentSlot, manual) }
                updateSession(session.copy(calculation = state.copy(opponentManualOverrides = overrides)), teams)
            })
        })

        root.addView(scroll(content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(resizeHandle(root, params, opponentEditorWindowState))
        addOverlay(root, params)
        opponentEditorView = root
    }

    private fun showSpeciesSearch(
        title: String,
        current: EntityValue?,
        suggestions: List<EntityValue>,
        onSelected: (EntityValue) -> Unit,
    ) {
        dismissSpeciesSearch(showPrevious = false)
        setupView?.visibility = View.INVISIBLE
        panelView?.visibility = View.INVISIBLE
        val root = compactPanelRoot()
        val params = rightRailPanelParams(searchWindowState, widthDp = 360)
        val header = header(title, "输入中文名搜索，也支持英文名") { dismissSpeciesSearch() }
        makeDraggable(header.getChildAt(0), root, params, searchWindowState)
        root.addView(header)
        val search = EditText(context).apply {
            hint = "例如：大嘴娃、沙奈朵、超级大嘴娃"
            setHintTextColor(TEXT_MUTED)
            setTextColor(TEXT)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBackground(SURFACE_ALT, ACCENT_TEAL, 10f)
        }
        root.addView(search)
        val list = ListView(context).apply {
            dividerHeight = dp(1)
            setBackgroundColor(SURFACE)
        }
        val adapter = OverlaySpinnerAdapter(context, emptyList())
        list.adapter = adapter
        var visible = emptyList<EntityValue>()
        val priority = (listOfNotNull(current) + suggestions).map(presetRepository::localizeSpecies)
        val all = (priority + presetRepository.speciesCatalog).distinctBy { normalize(it.showdownId) }
        fun refresh(query: String) {
            val text = query.trim()
            val normalized = normalize(text)
            visible = all.asSequence().filter { species ->
                text.isBlank() || species.displayName.contains(text, ignoreCase = true) ||
                    (normalized.isNotBlank() && normalize(species.showdownId).contains(normalized))
            }.take(if (text.isBlank()) 60 else 100).toList()
            adapter.clear()
            adapter.addAll(visible.map(EntityValue::displayName))
            adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = refresh(text?.toString().orEmpty())
            override fun afterTextChanged(text: Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            visible.getOrNull(position)?.let { selected ->
                onSelected(selected)
                dismissSpeciesSearch()
            }
        }
        refresh("")
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        root.addView(resizeHandle(root, params, searchWindowState))
        addOverlay(root, params, initiallyFocusable = true)
        speciesSearchView = root
        handler.post {
            search.requestFocus()
            val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            keyboard?.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun compactPicker(title: String, options: List<String>, selected: Int, onSelected: (Int) -> Unit): View =
        vertical(spacing = 4).apply {
            addView(bodyText(title, color = TEXT_MUTED))
            val picker = spinner(options, selected.coerceAtLeast(0))
            picker.onItemSelected(onSelected)
            addView(picker)
        }

    private fun stageEditor(
        title: String,
        holder: Array<BattleStatStages>,
        onChange: (BattleStatStages) -> Unit,
    ): View = cardColumn().apply {
        addView(label(title))
        listOf("攻击" to "atk", "防御" to "def", "特攻" to "spa", "特防" to "spd", "速度" to "spe").forEach { (name, key) ->
            val row = horizontal(spacing = 8).apply { gravity = Gravity.CENTER_VERTICAL }
            val value = bodyText("", bold = true).apply { gravity = Gravity.CENTER }
            fun current() = when (key) {
                "atk" -> holder[0].atk
                "def" -> holder[0].def
                "spa" -> holder[0].spa
                "spd" -> holder[0].spd
                else -> holder[0].spe
            }
            fun set(delta: Int) {
                val next = (current() + delta).coerceIn(-6, 6)
                holder[0] = when (key) {
                    "atk" -> holder[0].copy(atk = next)
                    "def" -> holder[0].copy(def = next)
                    "spa" -> holder[0].copy(spa = next)
                    "spd" -> holder[0].copy(spd = next)
                    else -> holder[0].copy(spe = next)
                }
                value.text = signed(next)
                onChange(holder[0])
            }
            value.text = signed(current())
            row.addView(bodyText(name), weighted(weight = 1f))
            row.addView(actionButton("−", secondary = true) { set(-1) }, weighted(width = dp(54)))
            row.addView(value, weighted(width = dp(54)))
            row.addView(actionButton("+", secondary = true) { set(1) }, weighted(width = dp(54)))
            addView(row)
        }
    }

    private fun scheduleCalculation(
        session: BattleSession,
        ownTeam: SavedTeam,
        preset: OpponentPreset,
        legalMoves: List<MoveValue>,
        valueView: TextView,
        detailsView: TextView,
        retry: Int = 0,
    ) {
        val generation = ++calculationGeneration
        if (!runtime.isReady) {
            handler.postDelayed({
                if (generation != calculationGeneration || panelView == null) return@postDelayed
                valueView.text = if (retry < 12) "正在准备伤害计算…" else "伤害计算暂时不可用"
                if (retry < 12) scheduleCalculation(session, ownTeam, preset, legalMoves, valueView, detailsView, retry + 1)
            }, if (retry == 0) 140 else 350)
            return
        }

        val request = runCatching {
            buildBattleDamageRequest(session, ownTeam, preset, legalMoves, presetRepository)
        }.getOrElse { error ->
            Log.e("BattleOverlay", "Could not prepare damage calculation", error)
            valueView.text = "计算失败"
            detailsView.text = "请检查当前宝可梦、招式和对手配置后重试"
            valueView.setTextColor(ERROR)
            return
        }
        val cacheKey = battleDamageCacheKey(request)
        panelDamageCache[cacheKey]?.let { cached ->
            applyOverlayResult(valueView, detailsView, cached)
            return
        }

        handler.postDelayed({
            if (generation != calculationGeneration || panelView == null) return@postDelayed
            runtime.calculate(request) { rawResult ->
                if (generation != calculationGeneration || panelView == null) return@calculate
                rawResult.fold(
                    onSuccess = { raw ->
                        runCatching { parseOverlayResult(raw) }
                            .onSuccess { parsed ->
                                if (!parsed.error) panelDamageCache[cacheKey] = parsed
                                applyOverlayResult(valueView, detailsView, parsed)
                            }
                            .onFailure { error ->
                                Log.e("BattleOverlay", "Could not parse damage result", error)
                                valueView.text = "计算失败"
                                detailsView.text = "请检查当前宝可梦、招式和对手配置后重试"
                                valueView.setTextColor(ERROR)
                            }
                    },
                    onFailure = { error ->
                        Log.e("BattleOverlay", "Damage calculation failed", error)
                        valueView.text = "计算失败"
                        detailsView.text = "请稍后重试；如持续失败，请重新确认双方阵容"
                        valueView.setTextColor(ERROR)
                    },
                )
            }
        }, if (retry == 0) 140 else 0)
    }

    private fun applyOverlayResult(valueView: TextView, detailsView: TextView, result: OverlayResult) {
        valueView.text = result.range
        detailsView.text = result.details
        valueView.setTextColor(if (result.error) ERROR else PRIMARY)
    }

    private fun parseOverlayResult(raw: String): OverlayResult {
        val envelope = JSONObject(raw)
        if (!envelope.optBoolean("ok")) {
            return OverlayResult("计算失败", "请检查当前宝可梦、招式和对手配置后重试", true)
        }
        val result = envelope.getJSONObject("result")
        val moves = result.getJSONArray("moveResults")
        val warnings = result.getJSONArray("warnings")
        if (moves.length() == 0) {
            val warningText = (0 until warnings.length()).joinToString("\n") { index ->
                "• ${overlayWarning(warnings.getJSONObject(index).optString("code"))}"
            }
            return OverlayResult("没有直接伤害结果", warningText.ifBlank { "请检查招式和对手配置。" }, true)
        }
        val move = moves.getJSONObject(0)
        val range = move.getJSONObject("selectedProfileRange")
        val ko = move.getJSONObject("koSummary").optString("text")
        val attacker = result.getJSONObject("attackerSummary").optString("speciesName")
        val defender = result.getJSONObject("defenderIdentity").getJSONObject("species").optString("displayName")
        val warningText = (0 until warnings.length()).map { warnings.getJSONObject(it).optString("code") }
            .filterNot { it == "ACTUAL_STATS_APPROXIMATED" }
            .distinct()
            .joinToString("\n") { "• ${overlayWarning(it)}" }
        val percent = String.format(Locale.US, "%.1f%% – %.1f%%", range.getDouble("minPercent"), range.getDouble("maxPercent"))
        val details = buildString {
            append("$attacker → $defender · ${move.optString("moveName")}\n")
            append("${range.optInt("minDamage")} – ${range.optInt("maxDamage")} HP · ${localizeKo(ko)}")
            if (warningText.isNotBlank()) append("\n$warningText")
        }
        return OverlayResult(percent, details, false)
    }

    private fun ensureValidState(session: BattleSession, ownTeam: SavedTeam): BattleSession {
        var state = session.calculation.copy(
            ownSlot = session.calculation.ownSlot.coerceIn(0, ownTeam.pokemon.lastIndex),
            opponentSlot = session.calculation.opponentSlot.coerceIn(0, session.opponentTeam.lastIndex),
        )
        val ownOverrides = state.ownFormOverrides.filter { (slot, form) ->
            val base = ownTeam.pokemon.getOrNull(slot)?.species ?: return@filter false
            presetRepository.formsFor(base).any { normalize(it.species.showdownId) == normalize(form.showdownId) }
        }
        val opponentOverrides = state.opponentFormOverrides.filter { (slot, form) ->
            val base = session.opponentTeam.getOrNull(slot) ?: return@filter false
            presetRepository.formsFor(base).any { normalize(it.species.showdownId) == normalize(form.showdownId) }
        }
        val manualOverrides = state.opponentManualOverrides.filter { (slot, override) ->
            val base = session.opponentTeam.getOrNull(slot) ?: return@filter false
            val species = opponentOverrides[slot] ?: base
            presetRepository.profilesFor(species).any { it.profileId == override.baseProfileId }
        }
        val opponentPresetIds = state.opponentPresetIds.filter { (slot, profileId) ->
            val base = session.opponentTeam.getOrNull(slot) ?: return@filter false
            val species = opponentOverrides[slot] ?: base
            presetRepository.profilesFor(species).any { it.profileId == profileId }
        }
        state = state.copy(
            ownFormOverrides = ownOverrides,
            opponentFormOverrides = opponentOverrides,
            opponentManualOverrides = manualOverrides,
            opponentPresetIds = opponentPresetIds,
            directHud = state.directHud.copy(
                ownSlots = includeBattleDirectHudSlot(state.directHud.ownSlots, state.ownSlot, ownTeam.pokemon.size),
                opponentSlots = includeBattleDirectHudSlot(
                    state.directHud.opponentSlots,
                    state.opponentSlot,
                    session.opponentTeam.size,
                ),
            ),
            speedLine = state.speedLine.copy(
                ownPokemon = state.speedLine.ownPokemon.filterKeys { it in ownTeam.pokemon.indices },
                opponentPokemon = state.speedLine.opponentPokemon.filterKeys { it in session.opponentTeam.indices },
            ),
        )
        val opponent = state.opponentFormOverrides[state.opponentSlot] ?: session.opponentTeam[state.opponentSlot]
        val profiles = presetRepository.profilesFor(opponent)
        val manual = state.opponentManualOverrides[state.opponentSlot]
        val preset = profiles.firstOrNull { it.profileId == state.opponentPresetId() }
            ?: profiles.firstOrNull { it.profileId == manual?.baseProfileId }
            ?: if (state.direction == "OPPONENT_TO_OWN") {
            profiles.firstOrNull { it.moves.isNotEmpty() } ?: profiles.first()
        } else profiles.first()
        val moves = if (state.direction == "OWN_TO_OPPONENT") {
            val ownPokemon = presetRepository.effectiveOwnPokemon(
                ownTeam.pokemon[state.ownSlot],
                state.ownFormOverrides[state.ownSlot],
            )
            compatibleConfiguredMoves(ownPokemon.moves, presetRepository.movesFor(ownPokemon.species))
        } else {
            presetRepository.movesFor(opponent, preset.moves)
        }
        state = state.withOpponentPreset(preset.profileId).copy(
            selectedMoveId = chooseCompatibleMoveId(
                moves = moves,
                selectedMoveId = state.selectedMoveId,
                preferDamagingDefault = state.direction == "OPPONENT_TO_OWN",
            ),
        )
        return session.copy(calculation = state)
    }

    private fun defaultsForDirection(session: BattleSession, ownTeam: SavedTeam, direction: String): BattleCalculationState {
        return ensureValidState(
            session.copy(calculation = session.calculation.copy(
                direction = direction,
                selectedPresetId = null,
                selectedMoveId = null,
            )),
            ownTeam,
        ).calculation
    }

    private fun currentPanelSession(fallback: BattleSession): BattleSession = panelSession ?: fallback

    private fun updatePanelCalculation(
        teams: List<SavedTeam>,
        transform: (BattleSession) -> BattleSession,
    ) {
        val before = panelSession ?: return
        val candidate = transform(before)
        val ownTeam = teams.firstOrNull { it.id == candidate.selectedOwnTeamId } ?: teams.first()
        val corrected = ensureValidState(candidate, ownTeam)
        if (corrected == before) return
        saveSession(corrected)
        panelSession = corrected

        val binding = panelCalculationBinding
        if (
            panelView == null ||
            binding == null ||
            binding.ownTeam.id != ownTeam.id ||
            !isBattlePanelCalculationOnlyChange(before, corrected)
        ) {
            renderPanel(corrected, teams)
            return
        }
        binding.valueView.apply {
            text = if (runtime.isReady) "正在计算…" else "正在加载…"
            setTextColor(PRIMARY)
        }
        binding.detailsView.text = "修改选项后自动刷新"
        scheduleCalculation(
            corrected,
            binding.ownTeam,
            binding.preset,
            binding.legalMoves,
            binding.valueView,
            binding.detailsView,
        )
    }

    private fun updateSession(session: BattleSession, teams: List<SavedTeam>) {
        val correctedTeam = teams.firstOrNull { it.id == session.selectedOwnTeamId } ?: teams.first()
        val corrected = ensureValidState(session, correctedTeam)
        saveSession(corrected)
        renderPanel(corrected, teams)
    }

    private fun switchOwnTeam(session: BattleSession, selectedTeamId: String): BattleSession {
        if (session.selectedOwnTeamId == selectedTeamId) return session
        val state = session.calculation
        return session.copy(
            selectedOwnTeamId = selectedTeamId,
            calculation = state.copy(
                ownSlot = 0,
                selectedMoveId = null,
                ownFormOverrides = emptyMap(),
                ownConditions = emptyMap(),
                directHud = state.directHud.copy(ownSlots = listOf(0, 1)),
                speedLine = state.speedLine.copy(ownPokemon = emptyMap()),
            ),
        )
    }

    private fun matchCount(team: SavedTeam, preview: TeamPreviewDraft): Int {
        val recognized = preview.ownSlots.mapNotNull { it.candidates.firstOrNull()?.entity?.showdownId }.map(::normalize).toSet()
        return team.pokemon.count { normalize(it.species.showdownId) in recognized }
    }

    private fun compactPanelRoot() = vertical(spacing = 6).apply {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = roundedBackground(BACKGROUND, PRIMARY, 16f)
        elevation = dp(18).toFloat()
    }

    private fun compactLabeledPicker(
        label: String,
        options: List<String>,
        selected: Int,
        onSelected: (Int) -> Unit,
    ) = horizontal(spacing = 4).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(bodyText(label, color = TEXT_MUTED).apply { textSize = 12f }, weighted(width = dp(34)))
        val picker = spinner(options, selected)
        picker.onItemSelected(onSelected)
        addView(picker, weighted(weight = 1f))
    }

    private fun miniButton(
        text: String,
        secondary: Boolean = false,
        selected: Boolean? = null,
        action: () -> Unit,
    ) = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 11f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(9), dp(4), dp(9), dp(4))
        val emphasized = selected ?: !secondary
        setTextColor(if (emphasized) Color.rgb(38, 30, 0) else TEXT)
        backgroundTintList = ColorStateList.valueOf(if (emphasized) PRIMARY else SURFACE_ALT)
        setOnClickListener { action() }
    }

    private fun rightRailPanelParams(
        state: OverlayWindowState,
        widthDp: Int,
        sharedPositionState: OverlayWindowState? = null,
    ): WindowManager.LayoutParams {
        val hasRememberedPosition = sharedPositionState?.let(state::rememberPositionFrom) == true ||
            state.positionInitialized
        val reference = state.takeIf { hasRememberedPosition }?.let {
            OverlayBounds(
                it.x,
                it.y,
                it.x + it.width.coerceAtLeast(1),
                it.y + it.height.coerceAtLeast(1),
            )
        }
        val bounds = safeArea.currentRegion(reference, preferEnd = true).inset(dp(8))
        val availableWidth = bounds.width.coerceAtLeast(1)
        val availableHeight = bounds.height.coerceAtLeast(1)
        val minWidth = minOf(dp(280), availableWidth)
        val minHeight = minOf(dp(240), availableHeight)
        state.width = (state.width.takeIf { it > 0 } ?: minOf(dp(widthDp), availableWidth))
            .coerceIn(minWidth, availableWidth)
        state.height = (state.height.takeIf { it > 0 } ?: availableHeight)
            .coerceIn(minHeight, availableHeight)
        if (!hasRememberedPosition) {
            state.rememberPosition(
                x = (bounds.right - state.width).coerceAtLeast(bounds.left),
                y = bounds.top,
            )
        } else {
            state.rememberPosition(
                x = state.x.coerceIn(bounds.left, (bounds.right - state.width).coerceAtLeast(bounds.left)),
                y = state.y.coerceIn(bounds.top, (bounds.bottom - state.height).coerceAtLeast(bounds.top)),
            )
        }
        sharedPositionState?.rememberPositionFrom(state)
        return WindowManager.LayoutParams(
            state.width,
            state.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayPanelWindowFlags(focusable = false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = state.x
            y = state.y
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    private fun panelRoot() = vertical(spacing = 0).apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = roundedBackground(BACKGROUND, PRIMARY, 18f)
        elevation = dp(18).toFloat()
    }

    private fun header(
        title: String,
        subtitle: String,
        collapse: (() -> Unit)? = null,
        close: () -> Unit,
    ) = horizontal(spacing = 6).apply {
        setPadding(0, 0, 0, dp(6))
        gravity = Gravity.CENTER_VERTICAL
        addView(vertical(spacing = 1).apply {
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(TEXT)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(bodyText(subtitle, color = TEXT_MUTED).apply { textSize = 11f })
        }, weighted(weight = 1f))
        addView(miniButton("返回", secondary = true) { close() })
        collapse?.let { action ->
            addView(miniButton("收起", secondary = true) { action() })
        }
    }

    private fun cardColumn() = vertical(spacing = 6).apply {
        setPadding(dp(8), dp(7), dp(8), dp(7))
        background = roundedBackground(SURFACE, SURFACE_BORDER, 10f)
    }

    private fun scroll(child: View, state: OverlayWindowState? = null) = ScrollView(context).apply {
        isFillViewport = true
        addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        state?.let { windowState ->
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                windowState.rememberScroll(scrollY)
            }
            post { scrollTo(0, windowState.scrollY) }
        }
    }

    private fun vertical(spacing: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (spacing > 0) setPadding(0, 0, 0, dp(spacing))
    }.also { layout ->
        if (spacing > 0) layout.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                (child?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                    if (layout.childCount > 1) params.topMargin = dp(spacing)
                }
            }
            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
    }

    private fun horizontal(spacing: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                (child?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                    if (childCount > 1) params.marginStart = dp(spacing)
                }
            }
            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
    }

    private fun label(text: String) = bodyText(text, bold = true).apply { textSize = 14f }

    private fun bodyText(text: String, color: Int = TEXT, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun actionButton(text: String, secondary: Boolean = false, action: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(36)
        minimumHeight = dp(36)
        setPadding(dp(10), dp(5), dp(10), dp(5))
        setTextColor(if (secondary) TEXT else Color.rgb(38, 30, 0))
        backgroundTintList = ColorStateList.valueOf(if (secondary) SURFACE_ALT else PRIMARY)
        setOnClickListener { action() }
    }

    private fun toggleButton(text: String, selected: Boolean, action: () -> Unit) = actionButton(text, secondary = !selected, action)

    private fun checkRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(context).apply {
        this.text = text
        isChecked = checked
        textSize = 12f
        minHeight = dp(34)
        minimumHeight = dp(34)
        setTextColor(TEXT)
        buttonTintList = ColorStateList.valueOf(ACCENT_TEAL)
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    private fun spinner(options: List<String>, selected: Int): Spinner = Spinner(context).apply {
        adapter = OverlaySpinnerAdapter(context, options)
        setSelection(selected.coerceIn(0, options.lastIndex.coerceAtLeast(0)), false)
        backgroundTintList = null
        background = OverlaySpinnerBackgroundDrawable(
            fillColor = SURFACE_ALT,
            strokeColor = SURFACE_BORDER,
            arrowColor = ACCENT_TEAL,
            density = density,
        )
        setPadding(0, 0, dp(20), 0)
    }

    private fun Spinner.onItemSelected(action: (Int) -> Unit) {
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun roundedBackground(color: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.roundToInt()).toFloat()
        setStroke(dp(1).coerceAtLeast(1), stroke)
    }

    private fun addOverlay(
        view: View,
        params: WindowManager.LayoutParams,
        initiallyFocusable: Boolean = false,
    ) {
        configureOverlayFocus(context, windowManager, view, params, initiallyFocusable)
        windowManager.addView(view, params)
    }

    private fun makeDraggable(
        handle: View,
        overlay: View,
        params: WindowManager.LayoutParams,
        state: OverlayWindowState,
        sharedPositionState: OverlayWindowState? = null,
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val position = safeArea.clampPosition(
                        proposedX = startX + (event.rawX - downX).roundToInt(),
                        proposedY = startY + (event.rawY - downY).roundToInt(),
                        width = params.width,
                        height = params.height,
                    )
                    params.x = position.x
                    params.y = position.y
                    state.rememberPosition(params.x, params.y)
                    sharedPositionState?.rememberPositionFrom(state)
                    runCatching { windowManager.updateViewLayout(overlay, params) }
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
    }

    private fun resizeHandle(
        overlay: View,
        params: WindowManager.LayoutParams,
        state: OverlayWindowState,
    ): View = bodyText("↘ 缩放", color = TEXT_MUTED).apply {
        textSize = 11f
        minHeight = dp(30)
        minimumHeight = dp(30)
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(4), 0)
        var downX = 0f
        var downY = 0f
        var startWidth = 0
        var startHeight = 0
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startWidth = params.width
                    startHeight = params.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val bounds = safeArea.currentRegion(
                        OverlayBounds(params.x, params.y, params.x + params.width, params.y + params.height),
                    )
                    val size = boundedOverlaySize(
                        startWidth = startWidth,
                        startHeight = startHeight,
                        deltaWidth = (event.rawX - downX).roundToInt(),
                        deltaHeight = (event.rawY - downY).roundToInt(),
                        requestedMinWidth = dp(280),
                        requestedMinHeight = dp(240),
                        availableWidth = bounds.right - params.x - dp(8),
                        availableHeight = bounds.bottom - params.y - dp(8),
                    )
                    params.width = size.width
                    params.height = size.height
                    state.rememberSize(params.width, params.height)
                    text = "↘ ${(params.width / density).roundToInt()}×${(params.height / density).roundToInt()}"
                    runCatching { windowManager.updateViewLayout(overlay, params) }
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
    }

    private fun largePanelParams(state: OverlayWindowState, defaultWidthDp: Int, defaultHeightDp: Int): WindowManager.LayoutParams =
        panelParams(state, defaultWidthDp, defaultHeightDp, marginDp = 24)

    private fun mediumPanelParams(state: OverlayWindowState, defaultWidthDp: Int, defaultHeightDp: Int): WindowManager.LayoutParams =
        panelParams(state, defaultWidthDp, defaultHeightDp, marginDp = 48)

    private fun panelParams(
        state: OverlayWindowState,
        defaultWidthDp: Int,
        defaultHeightDp: Int,
        marginDp: Int,
    ): WindowManager.LayoutParams {
        val reference = state.takeIf { it.positionInitialized && it.width > 0 && it.height > 0 }?.let {
            OverlayBounds(it.x, it.y, it.x + it.width, it.y + it.height)
        }
        val bounds = safeArea.currentRegion(reference, preferEnd = true).inset(dp(marginDp) / 2)
        val maxWidth = bounds.width.coerceAtLeast(1)
        val maxHeight = bounds.height.coerceAtLeast(1)
        if (state.width <= 0) state.width = minOf(dp(defaultWidthDp), maxWidth)
        if (state.height <= 0) state.height = minOf(dp(defaultHeightDp), maxHeight)
        state.width = state.width.coerceIn(minOf(dp(560), maxWidth), maxWidth)
        state.height = state.height.coerceIn(minOf(dp(440), maxHeight), maxHeight)
        if (!state.positionInitialized) {
            state.rememberPosition(
                x = bounds.left + (bounds.width - state.width) / 2,
                y = bounds.top + (bounds.height - state.height) / 2,
            )
        } else {
            state.rememberPosition(
                x = state.x.coerceIn(bounds.left, (bounds.right - state.width).coerceAtLeast(bounds.left)),
                y = state.y.coerceIn(bounds.top, (bounds.bottom - state.height).coerceAtLeast(bounds.top)),
            )
        }
        return WindowManager.LayoutParams(
            state.width,
            state.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayPanelWindowFlags(focusable = false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = state.x
            y = state.y
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    private fun reflowOverlay(
        view: View,
        state: OverlayWindowState,
        sharedPositionState: OverlayWindowState? = null,
    ) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        sharedPositionState?.let(state::rememberPositionFrom)
        val proposedX = state.x.takeIf { state.positionInitialized } ?: params.x
        val proposedY = state.y.takeIf { state.positionInitialized } ?: params.y
        val reference = OverlayBounds(
            proposedX,
            proposedY,
            proposedX + params.width.coerceAtLeast(view.width),
            proposedY + params.height.coerceAtLeast(view.height),
        )
        val bounds = safeArea.currentRegion(reference).inset(dp(8))
        params.width = (state.width.takeIf { it > 0 } ?: params.width)
            .coerceIn(1, bounds.width.coerceAtLeast(1))
        params.height = (state.height.takeIf { it > 0 } ?: params.height)
            .coerceIn(1, bounds.height.coerceAtLeast(1))
        val position = safeArea.clampPosition(
            proposedX,
            proposedY,
            params.width,
            params.height,
        )
        params.x = position.x
        params.y = position.y
        state.rememberPosition(params.x, params.y)
        sharedPositionState?.rememberPositionFrom(state)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun weighted(width: Int = 0, weight: Float = 0f) = LinearLayout.LayoutParams(
        if (width > 0) width else 0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        weight,
    )

    private fun dp(value: Int) = (value * density).roundToInt()

    private fun dismissSetup(showBubble: Boolean = true) {
        setupView?.let { runCatching { windowManager.removeView(it) } }
        setupView = null
        if (showBubble && panelView == null && conditionsView == null && opponentEditorView == null && speciesSearchView == null && speedLineView == null) onOverlayVisible(false)
    }

    private fun dismissPanel(showBubble: Boolean = true) {
        calculationGeneration++
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        panelSession = null
        panelCalculationBinding = null
        if (showBubble && setupView == null && conditionsView == null && opponentEditorView == null && speciesSearchView == null && speedLineView == null) onOverlayVisible(false)
    }

    private fun collapsePanel() {
        panelNavigation.collapse()
        dismissConditions(showPanel = false)
        dismissOpponentEditor(showPanel = false)
        dismissSpeedLine(showPanel = false)
        dismissPanel()
        showDirectHud()
    }

    private fun restorePanelPage(page: BattlePanelPage, session: BattleSession, teams: List<SavedTeam>) {
        when (page) {
            BattlePanelPage.DAMAGE -> Unit
            BattlePanelPage.CONDITIONS -> showConditions(session, teams)
            BattlePanelPage.SPEED_LINE -> showSpeedLine(session, teams)
            BattlePanelPage.OPPONENT_EDITOR -> {
                val state = session.calculation
                val opponentBase = session.opponentTeam[state.opponentSlot]
                val opponent = state.opponentFormOverrides[state.opponentSlot] ?: opponentBase
                val basePreset = presetRepository.profilesFor(opponent)
                    .first { it.profileId == state.selectedPresetId }
                showOpponentEditor(session, teams, opponent, basePreset)
            }
        }
    }

    private fun dismissConditions(showPanel: Boolean = true) {
        conditionsView?.let { runCatching { windowManager.removeView(it) } }
        conditionsView = null
        if (showPanel) {
            panelNavigation.show(BattlePanelPage.DAMAGE)
            panelView?.let {
                reflowOverlay(it, panelWindowState, battlePanelPositionState)
                it.visibility = View.VISIBLE
            }
        }
        if (panelView == null && setupView == null && opponentEditorView == null && speciesSearchView == null && speedLineView == null) onOverlayVisible(false)
    }

    private fun dismissOpponentEditor(showPanel: Boolean = true) {
        opponentEditorView?.let { runCatching { windowManager.removeView(it) } }
        opponentEditorView = null
        if (showPanel) {
            panelNavigation.show(BattlePanelPage.DAMAGE)
            panelView?.let {
                reflowOverlay(it, panelWindowState, battlePanelPositionState)
                it.visibility = View.VISIBLE
            }
        }
        if (panelView == null && setupView == null && conditionsView == null && speciesSearchView == null && speedLineView == null) onOverlayVisible(false)
    }

    private fun dismissSpeciesSearch(showPrevious: Boolean = true) {
        speciesSearchView?.let { runCatching { windowManager.removeView(it) } }
        speciesSearchView = null
        if (showPrevious) {
            setupView?.visibility = View.VISIBLE
            panelView?.visibility = View.VISIBLE
        }
        if (setupView == null && panelView == null && conditionsView == null && opponentEditorView == null && speedLineView == null) onOverlayVisible(false)
    }

    private fun dismissSpeedLine(showPanel: Boolean = true) {
        speedLineView?.let { runCatching { windowManager.removeView(it) } }
        speedLineView = null
        if (showPanel && panelView != null) {
            panelNavigation.show(BattlePanelPage.DAMAGE)
            showPanel()
        }
        if (panelView == null && setupView == null && conditionsView == null && opponentEditorView == null && speciesSearchView == null) {
            onOverlayVisible(false)
        }
    }

    private fun profileLabel(preset: OpponentPreset): String = when (preset.source) {
        "OPEN_SOURCE_PRESET" -> preset.profileName
        else -> preset.profileName
    }

    private fun ownPokemonSummary(current: PokemonConfig, saved: PokemonConfig): String = buildString {
        append("Lv.${current.level}")
        append(" · 特性 ${current.ability?.displayName ?: "未知"}")
        append(" · 道具 ${current.item?.displayName ?: "无"}")
        append("\n实际能力值：${statsSummary(current.actualStats)}")
        append("\n加点：${pointsSummary(current.statPoints)}")
        if (current.moves.isNotEmpty()) append("\n招式：${current.moves.joinToString(" / ") { it.entity.displayName }}")
        if (normalize(current.species.showdownId) != normalize(saved.species.showdownId)) {
            append("\n形态变化后的实际能力值按已保存的加点与性格换算。")
        }
    }

    private fun presetSummary(preset: OpponentPreset): String {
        return buildString {
            if (preset.source == "MANUAL_CURRENT") append("本局自定义配置已生效\n")
            append("Lv.${preset.level}")
            append(" · 特性 ${preset.ability?.displayName ?: "未知"}")
            append(" · 道具 ${preset.item?.displayName ?: "未知"}")
            append(" · 性格 ${preset.statAlignment?.displayName ?: "未指定"}")
            append("\n能力值：${statsSummary(preset.actualStats)}")
            val points = pointsSummary(preset.statPoints)
            append("\n加点：${if (points == "未设置" && preset.source == "GENERATED_TEMPLATE") "无投入" else points}")
            if (preset.moves.isNotEmpty()) append("\n配置招式：${preset.moves.joinToString(" / ") { it.entity.displayName }}")
            append("\n对手的实际配置可能不同。")
        }
    }

    private fun statsSummary(stats: StatFields): String = STAT_LABELS.joinToString("  ") { (key, label) ->
        "$label ${stats.asMap()[key].orEmpty().ifBlank { "?" }}"
    }

    private fun pointsSummary(stats: StatFields): String {
        val values = STAT_LABELS.mapNotNull { (key, label) ->
            stats.asMap()[key]?.toIntOrNull()?.takeIf { it > 0 }?.let { "$label+$it" }
        }
        return values.takeIf(List<String>::isNotEmpty)?.joinToString("  ") ?: "未设置"
    }

    private fun overlayWarning(code: String) = when (code) {
        "NO_OPPONENT_MOVE_SELECTED" -> "请选择对手招式"
        "LEGAL_MOVE_POOL_MISSING" -> "缺少对手招式数据"
        "ILLEGAL_OPPONENT_MOVE" -> "所选招式不在当前宝可梦的可选招式中"
        "MOVE_NOT_FOUND" -> "暂不支持当前招式"
        "SPECIES_NOT_FOUND" -> "暂不支持当前宝可梦或形态"
        "NO_ATTACKER_MOVES" -> "当前攻击方没有可计算招式"
        else -> "当前配置暂时无法计算"
    }

    private fun localizeKo(value: String): String {
        if (value.equals("No direct damage.", true)) return "无直接伤害"
        val match = Regex("possible (\\d+)HKO", RegexOption.IGNORE_CASE).matchEntire(value)
        if (match != null) return "可能 ${match.groupValues[1]} 次击倒"
        val guaranteed = Regex("guaranteed (\\d+)HKO", RegexOption.IGNORE_CASE).matchEntire(value)
        if (guaranteed != null) return "稳定 ${guaranteed.groupValues[1]} 次击倒"
        val chance = Regex("([0-9.]+)% chance to (\\d+)HKO", RegexOption.IGNORE_CASE).matchEntire(value)
        return chance?.let { "${it.groupValues[1]}% 几率 ${it.groupValues[2]} 次击倒" } ?: value
    }

    private fun weatherLabel(value: String) = mapOf("NONE" to "无", "Sun" to "晴", "Rain" to "雨", "Sand" to "沙暴", "Snow" to "雪")[value] ?: value
    private fun terrainLabel(value: String) = mapOf("NONE" to "无", "Electric" to "电气", "Grassy" to "青草", "Psychic" to "精神", "Misty" to "薄雾")[value] ?: value
    private fun signed(value: Int) = if (value > 0) "+$value" else value.toString()
    private fun normalize(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "")
    private fun <T> List<T>.next(current: T): T = get((indexOf(current).takeIf { it >= 0 } ?: 0).plus(1) % size)

    private data class OverlayResult(val range: String, val details: String, val error: Boolean)

    private class OverlaySpinnerAdapter(context: Context, values: List<String>) : ArrayAdapter<String>(context, 0, values.toMutableList()) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = textView(position, convertView, false)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = textView(position, convertView, true)

        private fun textView(position: Int, convertView: View?, dropdown: Boolean): TextView = (convertView as? TextView ?: TextView(context)).apply {
            text = getItem(position)
            textSize = 12f
            setTextColor(if (dropdown) Color.rgb(25, 29, 40) else TEXT)
            setBackgroundColor(if (dropdown) Color.WHITE else Color.TRANSPARENT)
            val density = resources.displayMetrics.density
            val horizontal = (10 * density).roundToInt()
            val vertical = ((if (dropdown) 10 else 7) * density).roundToInt()
            setPadding(horizontal, vertical, horizontal, vertical)
        }
    }

    private class OverlaySpinnerBackgroundDrawable(
        fillColor: Int,
        strokeColor: Int,
        arrowColor: Int,
        density: Float,
    ) : Drawable() {
        private val radius = 6f * density
        private val strokeWidth = density.coerceAtLeast(1f)
        private val arrowHalfWidth = 4f * density
        private val arrowHalfHeight = 2.5f * density
        private val arrowInset = 12f * density
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.STROKE
            this.strokeWidth = this@OverlaySpinnerBackgroundDrawable.strokeWidth
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = arrowColor
            style = Paint.Style.FILL
        }
        private val rect = RectF()
        private val arrow = Path()

        override fun draw(canvas: Canvas) {
            val inset = strokeWidth / 2f
            rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)

            val centerX = bounds.right - arrowInset
            val centerY = bounds.exactCenterY()
            arrow.reset()
            arrow.moveTo(centerX - arrowHalfWidth, centerY - arrowHalfHeight)
            arrow.lineTo(centerX + arrowHalfWidth, centerY - arrowHalfHeight)
            arrow.lineTo(centerX, centerY + arrowHalfHeight)
            arrow.close()
            canvas.drawPath(arrow, arrowPaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
            arrowPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
            arrowPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        private val BACKGROUND = Color.rgb(14, 20, 32)
        private val SURFACE = Color.rgb(24, 33, 49)
        private val SURFACE_ALT = Color.rgb(42, 54, 76)
        private val SURFACE_BORDER = Color.rgb(65, 80, 105)
        private val TEXT = Color.rgb(241, 244, 255)
        private val TEXT_MUTED = Color.rgb(180, 190, 210)
        private val PRIMARY = Color.rgb(255, 213, 79)
        private val ACCENT_TEAL = Color.rgb(119, 215, 196)
        private val ACCENT_AMBER = Color.rgb(255, 183, 77)
        private val ERROR = Color.rgb(255, 120, 120)
        private val STAT_LABELS = listOf(
            "hp" to "HP",
            "atk" to "攻",
            "def" to "防",
            "spa" to "特攻",
            "spd" to "特防",
            "spe" to "速",
        )
    }
}
