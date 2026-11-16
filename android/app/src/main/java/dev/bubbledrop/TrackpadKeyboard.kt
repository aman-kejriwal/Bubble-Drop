package dev.bubbledrop

import android.content.Context
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * An invisible view the phone's keyboard types into while the trackpad is open. It keeps no text
 * of its own: whatever the keyboard enters or deletes goes straight to the Mac.
 */
class TrackpadKeyboardView(context: Context) : View(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** Brings up the phone's keyboard. */
    fun show() {
        requestFocus()
        // Once the focus change has reached the keyboard, or it ignores the request.
        post { context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0) }
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // No suggestions: the keyboard can't see the Mac's text, so it would only guess wrong.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return Connection()
    }

    /** Keys from a keyboard plugged into the phone. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = press(event) || super.onKeyDown(keyCode, event)

    /** Sends a key press to the Mac. False for keys that aren't typing, like Back. */
    private fun press(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> Trackpad.KEY_DELETE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Trackpad.KEY_RETURN
            KeyEvent.KEYCODE_TAB -> Trackpad.KEY_TAB
            KeyEvent.KEYCODE_ESCAPE -> Trackpad.KEY_ESCAPE
            KeyEvent.KEYCODE_DPAD_LEFT -> Trackpad.KEY_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Trackpad.KEY_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> Trackpad.KEY_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Trackpad.KEY_DOWN
            else -> {
                val char = event.unicodeChar
                if (char <= 0 || char and KeyCharacterMap.COMBINING_ACCENT != 0) return false
                Trackpad.text(String(Character.toChars(char)))
                return true
            }
        }
        Trackpad.key(key)
        return true
    }

    private inner class Connection : BaseInputConnection(this, false) {
        /** The word the keyboard is still working on, already typed on the Mac as it stands. */
        private var composing = ""

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            composing = ""
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (composing.isEmpty()) repeat(beforeLength.coerceIn(0, 64)) { Trackpad.key(Trackpad.KEY_DELETE) }
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
            deleteSurroundingText(beforeLength, afterLength)

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) press(event)
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            Trackpad.key(Trackpad.KEY_RETURN)
            return true
        }

        /** Makes the Mac's copy of the word being composed read [next]: deletes what changed, types the rest. */
        private fun replaceComposing(next: String) {
            val kept = composing.commonPrefixWith(next).length
            val stale = composing.substring(kept)
            repeat(stale.codePointCount(0, stale.length)) { Trackpad.key(Trackpad.KEY_DELETE) }
            Trackpad.text(next.substring(kept))
            composing = next
        }
    }
}
