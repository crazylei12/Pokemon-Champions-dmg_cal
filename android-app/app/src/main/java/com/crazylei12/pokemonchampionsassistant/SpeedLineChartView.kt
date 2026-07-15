package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

class SpeedLineChartView(
    context: Context,
    private val actions: List<SpeedLineAction>,
    private val trickRoom: Boolean,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 237, 248)
        textSize = sp(11f)
    }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(151, 164, 188)
        textSize = sp(10f)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(61, 76, 101)
        strokeWidth = dp(1).toFloat()
    }
    private val ownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(104, 194, 255) }
    private val opponentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 170, 91) }
    private val opponentEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 213, 151)
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
    }
    private val groupWidth = dp(430)
    private val rowHeight = dp(30)
    private val topHeight = dp(48)
    private val bottomPadding = dp(8)
    private val horizontalPadding = dp(22)

    private val groups = actions.groupBy(SpeedLineAction::priority)
        .toSortedMap(compareByDescending { it })

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = max(dp(720), groups.size.coerceAtLeast(1) * groupWidth)
        val largestGroup = groups.values.maxOfOrNull { it.size } ?: 1
        val desiredHeight = topHeight + largestGroup * rowHeight + bottomPadding
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (actions.isEmpty()) {
            canvas.drawText("暂无可绘制的速度数据", dp(18).toFloat(), dp(34).toFloat(), textPaint)
            return
        }
        groups.entries.forEachIndexed { groupIndex, (priority, groupActions) ->
            drawGroup(canvas, groupIndex, priority, groupActions)
        }
    }

    private fun drawGroup(
        canvas: Canvas,
        groupIndex: Int,
        priority: Int,
        groupActions: List<SpeedLineAction>,
    ) {
        val groupStart = groupIndex * groupWidth
        val groupEnd = groupStart + groupWidth
        val plotStart = groupStart + horizontalPadding
        val plotEnd = groupEnd - horizontalPadding
        val minimum = groupActions.minOf { it.speed.first }
        val maximum = groupActions.maxOf { it.speed.last }

        textPaint.textSize = sp(13f)
        textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText(
            if (priority == 0) "普通行动 · 先制度 0" else "先制行动 · +$priority",
            plotStart.toFloat(),
            dp(18).toFloat(),
            textPaint,
        )
        textPaint.textSize = sp(11f)
        textPaint.typeface = android.graphics.Typeface.DEFAULT
        canvas.drawText(
            if (trickRoom) "戏法空间：同先制度慢者在左" else "同先制度：快者在左",
            plotStart.toFloat(),
            dp(35).toFloat(),
            mutedPaint,
        )
        if (groupIndex > 0) {
            canvas.drawLine(groupStart.toFloat(), dp(8).toFloat(), groupStart.toFloat(), height.toFloat(), guidePaint)
        }

        groupActions.forEachIndexed { row, action ->
            val centerY = topHeight + row * rowHeight + rowHeight / 2f
            canvas.drawLine(plotStart.toFloat(), centerY, plotEnd.toFloat(), centerY, guidePaint)
            val startX = speedX(action.speed.first, minimum, maximum, plotStart, plotEnd)
            val endX = speedX(action.speed.last, minimum, maximum, plotStart, plotEnd)
            val left = minOf(startX, endX)
            val right = maxOf(startX, endX)
            if (action.isPoint) {
                val paint = if (action.side == SpeedSide.OWN) ownPaint else opponentPaint
                canvas.drawCircle(startX, centerY, dp(5).toFloat(), paint)
                drawPointLabel(canvas, action, startX, centerY, groupStart, groupEnd)
            } else {
                val bar = RectF(left, centerY - dp(5), right, centerY + dp(5))
                canvas.drawRoundRect(bar, dp(5).toFloat(), dp(5).toFloat(), opponentPaint)
                canvas.drawRoundRect(bar, dp(5).toFloat(), dp(5).toFloat(), opponentEdgePaint)
                mutedPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(
                    "${action.label}  ${action.speed.first}–${action.speed.last}",
                    (left + right) / 2f,
                    centerY - dp(8),
                    mutedPaint,
                )
                mutedPaint.textAlign = Paint.Align.LEFT
            }
        }

        mutedPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            if (trickRoom) minimum.toString() else maximum.toString(),
            plotStart.toFloat(),
            (topHeight - dp(3)).toFloat(),
            mutedPaint,
        )
        mutedPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            if (trickRoom) maximum.toString() else minimum.toString(),
            plotEnd.toFloat(),
            (topHeight - dp(3)).toFloat(),
            mutedPaint,
        )
        mutedPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawPointLabel(
        canvas: Canvas,
        action: SpeedLineAction,
        x: Float,
        centerY: Float,
        groupStart: Int,
        groupEnd: Int,
    ) {
        val label = "${action.label}  ${action.speed.first}"
        textPaint.textAlign = if (x > (groupStart + groupEnd) / 2f) Paint.Align.RIGHT else Paint.Align.LEFT
        val offset = if (textPaint.textAlign == Paint.Align.RIGHT) -dp(10) else dp(10)
        canvas.drawText(label, x + offset, centerY - dp(8), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun speedX(speed: Int, minimum: Int, maximum: Int, plotStart: Int, plotEnd: Int): Float =
        plotStart + speedAxisFraction(speed, minimum, maximum, trickRoom) * (plotEnd - plotStart)

    private fun dp(value: Int) = (value * density).roundToInt()
    private fun sp(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )
}
