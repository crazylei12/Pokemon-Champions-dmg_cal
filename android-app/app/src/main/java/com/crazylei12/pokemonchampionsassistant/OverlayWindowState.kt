package com.crazylei12.pokemonchampionsassistant

import android.view.WindowManager

internal val OVERLAY_PANEL_WINDOW_FLAGS =
    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

internal fun overlayPanelWindowFlags(focusable: Boolean): Int =
    if (focusable) {
        OVERLAY_PANEL_WINDOW_FLAGS
    } else {
        OVERLAY_PANEL_WINDOW_FLAGS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    }

internal data class OverlayWindowState(
    var width: Int = 0,
    var height: Int = 0,
    var x: Int = 0,
    var y: Int = 0,
    var scrollY: Int = 0,
    var positionInitialized: Boolean = false,
) {
    fun rememberPosition(x: Int, y: Int) {
        this.x = x
        this.y = y
        positionInitialized = true
    }

    fun rememberPositionFrom(other: OverlayWindowState): Boolean {
        if (!other.positionInitialized) return false
        rememberPosition(other.x, other.y)
        return true
    }

    fun rememberSize(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    fun rememberScroll(scrollY: Int) {
        this.scrollY = scrollY.coerceAtLeast(0)
    }
}

internal data class OverlayPosition(val x: Int, val y: Int)

internal data class OverlaySize(val width: Int, val height: Int)

internal fun boundedOverlayPosition(
    startX: Int,
    startY: Int,
    deltaX: Int,
    deltaY: Int,
    windowWidth: Int,
    windowHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
): OverlayPosition {
    val maxX = (screenWidth - windowWidth).coerceAtLeast(0)
    val maxY = (screenHeight - windowHeight).coerceAtLeast(0)
    return OverlayPosition(
        x = (startX + deltaX).coerceIn(0, maxX),
        y = (startY + deltaY).coerceIn(0, maxY),
    )
}

internal fun boundedOverlaySize(
    startWidth: Int,
    startHeight: Int,
    deltaWidth: Int,
    deltaHeight: Int,
    requestedMinWidth: Int,
    requestedMinHeight: Int,
    availableWidth: Int,
    availableHeight: Int,
): OverlaySize {
    val safeMaxWidth = availableWidth.coerceAtLeast(1)
    val safeMaxHeight = availableHeight.coerceAtLeast(1)
    val safeMinWidth = requestedMinWidth.coerceIn(1, safeMaxWidth)
    val safeMinHeight = requestedMinHeight.coerceIn(1, safeMaxHeight)
    return OverlaySize(
        width = (startWidth + deltaWidth).coerceIn(safeMinWidth, safeMaxWidth),
        height = (startHeight + deltaHeight).coerceIn(safeMinHeight, safeMaxHeight),
    )
}
