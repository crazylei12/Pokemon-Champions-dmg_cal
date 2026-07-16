package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
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
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

internal val OWN_TEAM_ACTUAL_STAT_INPUT_ROWS = listOf(
    listOf("hp" to "HP", "atk" to "攻击", "def" to "防御"),
    listOf("spa" to "特攻", "spd" to "特防", "spe" to "速度"),
)

class OwnTeamCorrectionOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val importRepository: OwnTeamImportRepository,
    private val presetRepository: OpponentPresetRepository,
    private val publish: (String) -> Unit,
    private val onOverlayVisible: (Boolean) -> Unit,
    private val onSaved: (ImportSaveResult) -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val panelState = OverlayWindowState()
    private val searchState = OverlayWindowState()
    private var panelView: View? = null
    private var searchView: View? = null
    private var draft: OwnTeamCorrectionDraft? = null
    private var slots: List<OwnTeamCorrectionSlot> = emptyList()
    private var selectedSlot = 0
    private var teamName = ""

    val isVisible: Boolean get() = panelView != null || searchView != null

    fun show() {
        val loaded = runCatching { importRepository.loadCorrectionDraft() }.getOrElse { error ->
            publish("无法打开手动修正：${error.message}")
            return
        }
        draft = loaded
        slots = loaded.slots
        selectedSlot = selectedSlot.coerceIn(0, slots.lastIndex)
        onOverlayVisible(true)
        renderPanel()
    }

    fun close() {
        dismissSearch(showPanel = false)
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        hideKeyboard()
        onOverlayVisible(false)
    }

    private fun renderPanel() {
        val currentDraft = draft ?: return
        panelView?.let { runCatching { windowManager.removeView(it) } }
        val current = slots[selectedSlot]
        val root = panelRoot()
        val params = panelParams(panelState, defaultWidthDp = 440, defaultHeightDp = 720)
        val dragHandle = vertical().apply {
            addView(text("确认 / 手动修正识别结果", 16f, bold = true))
            addView(text("拖动标题栏移动", 11f, color = MUTED))
        }
        val header = horizontal(spacingDp = 8).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(dragHandle, weighted())
            addView(button("稍后处理", compact = true) { close() })
        }
        makeDraggable(header, root, params, panelState)
        root.addView(header)
        val content = vertical()
        content.addView(text(
            "招式/道具 ${currentDraft.moveRecognized}/${currentDraft.moveTotal} · " +
                "能力值 ${currentDraft.statsRecognized}/${currentDraft.statsTotal} · " +
                "待处理 ${slots.count { !it.isComplete() }} 只",
            13f,
            color = ACCENT,
        ))
        if (slots.all(OwnTeamCorrectionSlot::isComplete)) {
            content.addView(text("识别字段已齐全，请逐项确认；发现错误可直接调整。", 13f, color = ACCENT))
        }

        val nameInput = editText("队伍名称", teamName).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            addTextChangedListener(afterTextChanged { teamName = it.take(30) })
            setOnEditorActionListener { view, actionId, event ->
                val completed = actionId == EditorInfo.IME_ACTION_DONE ||
                    event?.let { it.keyCode == KeyEvent.KEYCODE_ENTER && it.action == KeyEvent.ACTION_UP } == true
                if (completed) {
                    teamName = text.toString().take(30)
                    context.getSystemService(InputMethodManager::class.java)
                        .hideSoftInputFromWindow(view.windowToken, 0)
                    clearFocus()
                }
                completed
            }
        }
        content.addView(nameInput, matchWidth())

        content.addView(text("选择要修正的槽位", 13f, color = MUTED))
        val slotPicker = Spinner(context).apply {
            usePanelStyle()
            adapter = readableAdapter(
                slots.mapIndexed { index, slot ->
                    val state = if (slot.isComplete()) "已完成" else "缺 ${slot.unresolvedFields().size} 项"
                    "${index + 1}. ${slot.species?.displayName ?: "未识别宝可梦"} · $state"
                },
            )
            setSelection(selectedSlot, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position != selectedSlot) {
                        selectedSlot = position
                        renderPanel()
                    }
                }
            }
        }
        content.addView(slotPicker, matchWidth())

        val editor = vertical().apply {
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = roundedBackground(SURFACE, SURFACE_BORDER, 10)
            addView(text("槽位 ${current.slotIndex + 1}", 15f, bold = true))
            addView(button("宝可梦：${current.species?.displayName ?: "未识别"}") {
                showEntitySearch("选择宝可梦", presetRepository.speciesCatalog) { selected ->
                    updateCurrent(current.copy(species = selected, speciesConfirmed = true))
                }
            }, matchWidth())
            if (current.species != null && !current.speciesConfirmed) {
                addView(text("两页识别到的宝可梦不一致，请确认或重新选择。", 13f, color = WARNING))
                addView(button("确认当前为 ${current.species.displayName}") {
                    updateCurrent(current.copy(speciesConfirmed = true))
                }, matchWidth())
            }

            val abilityOptions = current.species?.let { species ->
                (listOfNotNull(current.ability) + presetRepository.abilitiesFor(species))
                    .distinctBy { normalizeSearchText(it.showdownId) }
            }.orEmpty()
            addView(text("特性", 13f, color = MUTED))
            val abilityPicker = Spinner(context).apply {
                usePanelStyle()
                val options = listOf<EntityValue?>(null) + abilityOptions
                adapter = readableAdapter(
                    options.map { it?.displayName ?: "未识别" },
                )
                val selected = options.indexOfFirst {
                    normalizeSearchText(it?.showdownId.orEmpty()) == normalizeSearchText(current.ability?.showdownId.orEmpty())
                }.coerceAtLeast(0)
                setSelection(selected, false)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val ability = options.getOrNull(position)
                        if (ability != slots[selectedSlot].ability) updateCurrentInPlace(slots[selectedSlot].copy(ability = ability))
                    }
                }
            }
            addView(abilityPicker, matchWidth())

            val itemRow = horizontal(spacingDp = 8).apply {
                addView(button(
                    when {
                        current.item != null -> "道具：${current.item.displayName}"
                        current.itemResolved -> "道具：无（已确认）"
                        else -> "道具：未识别"
                    },
                ) {
                    showEntitySearch("选择道具", presetRepository.itemCatalog) { selected ->
                        updateCurrent(current.copy(item = selected, itemResolved = true))
                    }
                }, weighted())
                addView(button("确认无道具") {
                    updateCurrent(current.copy(item = null, itemResolved = true))
                })
            }
            addView(itemRow, matchWidth())

            addView(text("招式（${current.moves.size}/4）", 14f, bold = true))
            current.moves.forEachIndexed { index, move ->
                val moveRow = horizontal(spacingDp = 8).apply {
                    addView(button("${index + 1}. ${move.entity.displayName}") {
                        showMoveSearch(index)
                    }, weighted())
                    addView(button("移除") {
                        val remainingMoves = current.moves.toMutableList().apply { removeAt(index) }
                        updateCurrent(
                            current.copy(
                                moves = remainingMoves,
                                recognizedMoveSlotIndexes = remainingMoves.indices.toSet(),
                            ),
                        )
                    })
                }
                addView(moveRow, matchWidth())
            }
            if (current.species != null && current.moves.size < 4) {
                addView(button("补充招式") { showMoveSearch(current.moves.size) }, matchWidth())
            }

            addView(text("实际能力值", 14f, bold = true))
            val statValues = current.actualStats.asMap().toMutableMap()
            OWN_TEAM_ACTUAL_STAT_INPUT_ROWS.forEach { rowStats ->
                val row = horizontal(spacingDp = 6)
                rowStats.forEach { (key, label) ->
                    val input = statEditText(label, statValues[key].orEmpty()).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER
                        setSelectAllOnFocus(true)
                        addTextChangedListener(afterTextChanged { value ->
                            statValues[key] = value.filter(Char::isDigit).take(3)
                            updateCurrentInPlace(slots[selectedSlot].copy(actualStats = StatFields.fromMap(statValues)))
                        })
                    }
                    row.addView(input, weighted())
                }
                addView(row, matchWidth())
            }

            val unresolved = current.unresolvedFields()
            if (unresolved.isNotEmpty()) {
                addView(text("仍需处理：${unresolved.joinToString("、")}", 13f, color = WARNING))
            }
            current.reminders().forEach { reminder ->
                addView(text("提醒：$reminder", 13f, color = ACCENT))
            }
            val navigation = horizontal(spacingDp = 8).apply {
                addView(button("上一个") {
                    selectedSlot = (selectedSlot - 1).coerceAtLeast(0)
                    renderPanel()
                }.apply { isEnabled = selectedSlot > 0 }, weighted())
                addView(button("下一个") {
                    selectedSlot = (selectedSlot + 1).coerceAtMost(slots.lastIndex)
                    renderPanel()
                }.apply { isEnabled = selectedSlot < slots.lastIndex }, weighted())
            }
            addView(navigation, matchWidth())
            addView(button("确认配置并保存队伍", emphasized = true) { saveTeam() }, matchWidth(heightDp = 48))
        }
        content.addView(editor, matchWidth())
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            addView(
                content,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            setOnScrollChangeListener { _, _, scrollY, _, _ -> panelState.rememberScroll(scrollY) }
            post { scrollTo(0, panelState.scrollY) }
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val resizeHandle = text("↘ 缩放", 11f, color = MUTED).apply {
            minHeight = dp(30)
            minimumHeight = dp(30)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
        }
        makeResizable(resizeHandle, root, params, panelState)
        root.addView(resizeHandle, matchWidth())
        configureOverlayFocus(context, windowManager, root, params)
        windowManager.addView(root, params)
        panelView = root
    }

    private fun showMoveSearch(target: Int) {
        val current = slots[selectedSlot]
        val species = current.species ?: run {
            publish("请先选择宝可梦")
            return
        }
        val moves = presetRepository.movesFor(species, current.moves)
        showSearch(
            title = if (target < current.moves.size) "更换招式" else "补充招式",
            source = moves,
            label = { move -> "[${presetRepository.moveTypeLabel(move)}] ${move.entity.displayName}" },
            matches = MoveValue::matchesSearch,
            sorter = { values, mode -> sortMoves(values, mode, presetRepository::moveTypeFor) },
        ) { selected ->
            val latest = slots[selectedSlot]
            val updated = latest.moves.toMutableList()
            if (target < updated.size) updated[target] = selected else updated.add(selected)
            val distinctMoves = updated.distinctBy { normalizeSearchText(it.entity.showdownId) }.take(4)
            updateCurrent(
                latest.copy(
                    moves = distinctMoves,
                    recognizedMoveSlotIndexes = distinctMoves.indices.toSet(),
                ),
            )
        }
    }

    private fun showEntitySearch(title: String, entities: List<EntityValue>, onSelected: (EntityValue) -> Unit) {
        showSearch(
            title = title,
            source = entities,
            label = EntityValue::displayName,
            matches = EntityValue::matchesSearch,
            onSelected = onSelected,
        )
    }

    private fun <T> showSearch(
        title: String,
        source: List<T>,
        label: (T) -> String,
        matches: (T, String) -> Boolean,
        sorter: ((List<T>, MoveSortMode) -> List<T>)? = null,
        onSelected: (T) -> Unit,
    ) {
        dismissSearch(showPanel = false)
        panelView?.visibility = View.INVISIBLE
        val root = panelRoot()
        val params = panelParams(searchState, defaultWidthDp = 400, defaultHeightDp = 620)
        val dragHandle = vertical().apply {
            addView(text(title, 16f, bold = true))
            addView(text("拖动标题栏移动", 11f, color = MUTED))
        }
        val header = horizontal(spacingDp = 8).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(dragHandle, weighted())
            addView(button("返回", compact = true) { dismissSearch() })
        }
        makeDraggable(header, root, params, searchState)
        root.addView(header)
        val search = editText("搜索中文名或英文名 / ID", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        root.addView(search, matchWidth())
        var sortMode = MoveSortMode.PINYIN
        var visible = emptyList<T>()
        val list = ListView(context).apply { dividerHeight = dp(1) }
        val adapter = readableAdapter(emptyList())
        list.adapter = adapter

        fun refresh(query: String) {
            val matchesQuery = source.filter { matches(it, query) }
            visible = (sorter?.invoke(matchesQuery, sortMode) ?: matchesQuery).take(150)
            adapter.clear()
            adapter.addAll(visible.map(label))
            adapter.notifyDataSetChanged()
        }
        sorter?.let {
            root.addView(horizontal(spacingDp = 8).apply {
                addView(button("名称拼音") { sortMode = MoveSortMode.PINYIN; refresh(search.text.toString()) }, weighted())
                addView(button("招式属性") { sortMode = MoveSortMode.TYPE; refresh(search.text.toString()) }, weighted())
            }, matchWidth())
        }
        search.addTextChangedListener(afterTextChanged(::refresh))
        list.setOnItemClickListener { _, _, position, _ ->
            visible.getOrNull(position)?.let { selected ->
                dismissSearch(showPanel = false)
                onSelected(selected)
            }
        }
        refresh("")
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val resizeHandle = text("↘ 缩放", 11f, color = MUTED).apply {
            minHeight = dp(30)
            minimumHeight = dp(30)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
        }
        makeResizable(resizeHandle, root, params, searchState)
        root.addView(resizeHandle, matchWidth())
        configureOverlayFocus(context, windowManager, root, params, initiallyFocusable = true)
        windowManager.addView(root, params)
        searchView = root
        search.requestFocus()
        handler.postDelayed({
            context.getSystemService(InputMethodManager::class.java)
                .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun saveTeam() {
        val currentDraft = draft ?: return
        val name = teamName.trim()
        val firstIncomplete = slots.indexOfFirst { !it.isComplete() }
        when {
            name.isBlank() -> publish("请输入队伍名称")
            firstIncomplete >= 0 -> {
                selectedSlot = firstIncomplete
                publish("请先补全槽位 ${firstIncomplete + 1}：${slots[firstIncomplete].unresolvedFields().joinToString("、")}")
                renderPanel()
            }
            else -> runCatching { importRepository.saveCorrectedTeam(name, currentDraft, slots) }
                .onSuccess { saved ->
                    onSaved(saved)
                    close()
                }
                .onFailure { error -> publish(error.message ?: "保存失败") }
        }
    }

    private fun updateCurrent(updated: OwnTeamCorrectionSlot) {
        updateCurrentInPlace(updated)
        dismissSearch(showPanel = false)
        renderPanel()
    }

    private fun updateCurrentInPlace(updated: OwnTeamCorrectionSlot) {
        slots = slots.toMutableList().apply { set(selectedSlot, updated) }
    }

    private fun dismissSearch(showPanel: Boolean = true) {
        searchView?.let { runCatching { windowManager.removeView(it) } }
        searchView = null
        hideKeyboard()
        if (showPanel) panelView?.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        val token = searchView?.windowToken ?: panelView?.windowToken
        token?.let {
            context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it, 0)
        }
    }

    private fun panelRoot() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = roundedBackground(BACKGROUND, PRIMARY, 16)
        elevation = dp(18).toFloat()
    }

    private fun vertical(spacingDp: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (spacingDp > 0) setPadding(0, dp(spacingDp), 0, dp(spacingDp))
    }

    private fun horizontal(spacingDp: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        if (spacingDp > 0) showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        if (spacingDp > 0) dividerDrawable = object : GradientDrawable() {
            override fun getIntrinsicWidth(): Int = dp(spacingDp)
        }
    }

    private fun text(value: String, size: Float, bold: Boolean = false, color: Int = TEXT) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun button(
        label: String,
        emphasized: Boolean = false,
        compact: Boolean = false,
        onClick: () -> Unit,
    ) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = if (compact) 11f else 13f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(
            dp(if (compact) 9 else 12),
            dp(if (compact) 4 else 7),
            dp(if (compact) 9 else 12),
            dp(if (compact) 4 else 7),
        )
        setTextColor(if (emphasized) PRIMARY_TEXT else TEXT)
        backgroundTintList = ColorStateList.valueOf(if (emphasized) PRIMARY else SURFACE_ALT)
        setOnClickListener { onClick() }
    }

    private fun editText(hintText: String, value: String) = EditText(context).apply {
        hint = hintText
        setHintTextColor(MUTED)
        setTextColor(TEXT)
        setText(value)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            setColor(SURFACE_ALT)
            cornerRadius = dp(9).toFloat()
            setStroke(dp(1).coerceAtLeast(1), SURFACE_BORDER)
        }
    }

    private fun statEditText(label: String, value: String) = editText(label, value).apply {
        setHintTextColor(STAT_PLACEHOLDER)
        gravity = Gravity.CENTER
        textSize = 14f
        isSingleLine = true
        contentDescription = "$label 实际能力值"
    }

    private fun Spinner.usePanelStyle() {
        backgroundTintList = null
        background = OverlaySpinnerBackgroundDrawable(
            fillColor = SURFACE_ALT,
            strokeColor = SURFACE_BORDER,
            arrowColor = ACCENT,
            density = density,
        )
        setPadding(0, 0, dp(20), 0)
    }

    private fun roundedBackground(color: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1).coerceAtLeast(1), stroke)
    }

    private fun readableAdapter(values: List<String>) = object : ArrayAdapter<String>(
        context,
        android.R.layout.simple_spinner_dropdown_item,
        values.toMutableList(),
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            style(super.getView(position, convertView, parent))

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            style(super.getDropDownView(position, convertView, parent))

        private fun style(view: View): View = view.apply {
            setBackgroundColor(SURFACE_ALT)
            (this as? TextView)?.apply {
                setTextColor(TEXT)
                textSize = 15f
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
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

    private fun afterTextChanged(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
    }

    private fun panelParams(state: OverlayWindowState, defaultWidthDp: Int, defaultHeightDp: Int): WindowManager.LayoutParams {
        val bounds = windowManager.maximumWindowMetrics.bounds
        val maxWidth = (bounds.width() - dp(32)).coerceAtLeast(1)
        val maxHeight = (bounds.height() - dp(32)).coerceAtLeast(1)
        val minWidth = minOf(dp(280), maxWidth)
        val minHeight = minOf(dp(240), maxHeight)
        val width = (state.width.takeIf { it > 0 } ?: minOf(dp(defaultWidthDp), maxWidth))
            .coerceIn(minWidth, maxWidth)
        val height = (state.height.takeIf { it > 0 } ?: minOf(dp(defaultHeightDp), maxHeight))
            .coerceIn(minHeight, maxHeight)
        state.rememberSize(width, height)
        if (!state.positionInitialized) {
            state.rememberPosition(
                x = (bounds.width() - width - dp(20)).coerceAtLeast(0),
                y = dp(16).coerceAtMost((bounds.height() - height).coerceAtLeast(0)),
            )
        } else {
            state.rememberPosition(
                x = state.x.coerceIn(0, (bounds.width() - width).coerceAtLeast(0)),
                y = state.y.coerceIn(0, (bounds.height() - height).coerceAtLeast(0)),
            )
        }
        return WindowManager.LayoutParams(
            width,
            height,
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

    private fun makeDraggable(
        handle: View,
        root: View,
        params: WindowManager.LayoutParams,
        state: OverlayWindowState,
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val bounds = windowManager.maximumWindowMetrics.bounds
                    val position = boundedOverlayPosition(
                        startX = startX,
                        startY = startY,
                        deltaX = (event.rawX - downRawX).toInt(),
                        deltaY = (event.rawY - downRawY).toInt(),
                        windowWidth = params.width,
                        windowHeight = params.height,
                        screenWidth = bounds.width(),
                        screenHeight = bounds.height(),
                    )
                    params.x = position.x
                    params.y = position.y
                    state.rememberPosition(params.x, params.y)
                    runCatching { windowManager.updateViewLayout(root, params) }
                    true
                }
                else -> true
            }
        }
    }

    private fun makeResizable(
        handle: View,
        root: View,
        params: WindowManager.LayoutParams,
        state: OverlayWindowState,
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var startWidth = 0
        var startHeight = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startWidth = params.width
                    startHeight = params.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val bounds = windowManager.maximumWindowMetrics.bounds
                    val size = boundedOverlaySize(
                        startWidth = startWidth,
                        startHeight = startHeight,
                        deltaWidth = (event.rawX - downRawX).toInt(),
                        deltaHeight = (event.rawY - downRawY).toInt(),
                        requestedMinWidth = dp(280),
                        requestedMinHeight = dp(240),
                        availableWidth = bounds.width() - params.x - dp(8),
                        availableHeight = bounds.height() - params.y - dp(8),
                    )
                    params.width = size.width
                    params.height = size.height
                    state.rememberSize(params.width, params.height)
                    runCatching { windowManager.updateViewLayout(root, params) }
                    true
                }
                else -> true
            }
        }
    }

    private fun matchWidth(heightDp: Int? = null) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        heightDp?.let(::dp) ?: ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val BACKGROUND = 0xFF0E1420.toInt()
        const val SURFACE = 0xFF171F2E.toInt()
        const val SURFACE_ALT = 0xFF263145.toInt()
        const val SURFACE_BORDER = 0xFF415069.toInt()
        const val TEXT = 0xFFF1F4FF.toInt()
        const val MUTED = 0xFFB7C1D6.toInt()
        const val STAT_PLACEHOLDER = 0xFFDCE3F2.toInt()
        const val ACCENT = 0xFF77D7C4.toInt()
        const val PRIMARY = 0xFFFFD54F.toInt()
        const val PRIMARY_TEXT = 0xFF261E00.toInt()
        const val WARNING = 0xFFFFB74D.toInt()
    }
}
