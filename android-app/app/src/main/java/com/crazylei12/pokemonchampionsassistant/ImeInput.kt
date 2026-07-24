package com.crazylei12.pokemonchampionsassistant

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

internal fun EditText.finishInputOnImeDone() {
    finishInputOnImeAction(EditorInfo.IME_ACTION_DONE)
}

internal fun EditText.finishInputOnImeSearch() {
    finishInputOnImeAction(EditorInfo.IME_ACTION_SEARCH)
}

private fun EditText.finishInputOnImeAction(expectedAction: Int) {
    imeOptions = expectedAction
    setOnEditorActionListener { input, actionId, event ->
        val completed = isImeCompletionAction(
            actionId = actionId,
            expectedAction = expectedAction,
            keyCode = event?.keyCode,
            keyAction = event?.action,
        )
        if (completed) {
            context.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        }
        completed
    }
}

internal fun isImeCompletionAction(
    actionId: Int,
    expectedAction: Int,
    keyCode: Int?,
    keyAction: Int?,
): Boolean = actionId == expectedAction ||
    (keyCode == KeyEvent.KEYCODE_ENTER && keyAction == KeyEvent.ACTION_UP)
