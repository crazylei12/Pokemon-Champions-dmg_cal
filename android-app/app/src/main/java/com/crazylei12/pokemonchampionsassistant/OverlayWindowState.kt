package com.crazylei12.pokemonchampionsassistant

import android.view.WindowManager

internal val OVERLAY_PANEL_WINDOW_FLAGS =
    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

internal data class OverlayWindowState(
    var width: Int = 0,
    var height: Int = 0,
    var x: Int = 0,
    var y: Int = 0,
    var scrollY: Int = 0,
) {
    fun rememberPosition(x: Int, y: Int) {
        this.x = x
        this.y = y
    }

    fun rememberSize(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    fun rememberScroll(scrollY: Int) {
        this.scrollY = scrollY.coerceAtLeast(0)
    }
}
