package com.crazylei12.pokemonchampionsassistant

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeInputTest {
    @Test
    fun `done input does not treat next as completion`() {
        assertTrue(
            isImeCompletionAction(
                actionId = EditorInfo.IME_ACTION_DONE,
                expectedAction = EditorInfo.IME_ACTION_DONE,
                keyCode = null,
                keyAction = null,
            )
        )
        assertFalse(
            isImeCompletionAction(
                actionId = EditorInfo.IME_ACTION_NEXT,
                expectedAction = EditorInfo.IME_ACTION_DONE,
                keyCode = null,
                keyAction = null,
            )
        )
    }

    @Test
    fun `search and hardware enter complete their inputs`() {
        assertTrue(
            isImeCompletionAction(
                actionId = EditorInfo.IME_ACTION_SEARCH,
                expectedAction = EditorInfo.IME_ACTION_SEARCH,
                keyCode = null,
                keyAction = null,
            )
        )
        assertTrue(
            isImeCompletionAction(
                actionId = EditorInfo.IME_ACTION_UNSPECIFIED,
                expectedAction = EditorInfo.IME_ACTION_DONE,
                keyCode = KeyEvent.KEYCODE_ENTER,
                keyAction = KeyEvent.ACTION_UP,
            )
        )
        assertFalse(
            isImeCompletionAction(
                actionId = EditorInfo.IME_ACTION_UNSPECIFIED,
                expectedAction = EditorInfo.IME_ACTION_DONE,
                keyCode = KeyEvent.KEYCODE_ENTER,
                keyAction = KeyEvent.ACTION_DOWN,
            )
        )
    }
}
