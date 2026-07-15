package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

internal fun configureOverlayFocus(
    context: Context,
    windowManager: WindowManager,
    root: View,
    params: WindowManager.LayoutParams,
    initiallyFocusable: Boolean = false,
) {
    val inputMethodManager = context.getSystemService(InputMethodManager::class.java)

    fun setFocusable(focusable: Boolean, updateWindow: Boolean = true) {
        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (updateWindow && root.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(root, params) }
        }
    }

    fun bindTextInputs(view: View) {
        if (view is EditText) {
            view.setOnTouchListener { input, event ->
                if (
                    event.actionMasked == MotionEvent.ACTION_DOWN &&
                    params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
                ) {
                    setFocusable(true)
                    input.post {
                        input.requestFocus()
                        (input as EditText).setSelection(input.text.length)
                        inputMethodManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                false
            }
        }
        if (view is ViewGroup) {
            repeat(view.childCount) { bindTextInputs(view.getChildAt(it)) }
        }
    }

    setFocusable(initiallyFocusable, updateWindow = false)
    bindTextInputs(root)
    root.setOnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            root.clearFocus()
            inputMethodManager.hideSoftInputFromWindow(root.windowToken, 0)
            setFocusable(false)
        }
        false
    }
}
