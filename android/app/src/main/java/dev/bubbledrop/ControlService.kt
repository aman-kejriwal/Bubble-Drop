package dev.bubbledrop

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Turns input from a Mac that is showing this phone's screen into touches, system buttons and
 * typing. Android only allows this through an accessibility service the user switches on; events
 * are only accepted while a mirror session the user started is running.
 */
class ControlService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        _enabled.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        _enabled.value = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** One control event from the Mac (PROTOCOL.md "Mirroring"); coordinates are 0–1 of the screen. */
    fun handle(event: JSONObject) = main.post {
        if (MirrorService.active.value == null) return@post
        when (event.optString("t")) {
            "tap" -> gesture(listOf(point(event)), event.optLong("ms", 60))
            "swipe" -> {
                val points = event.optJSONArray("p") ?: return@post
                val path = (0 until points.length()).map { i ->
                    val p = points.getJSONArray(i)
                    toScreen(p.getDouble(0), p.getDouble(1))
                }
                gesture(path, event.optLong("ms", 200))
            }
            "key" -> when (event.optString("k")) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "backspace" -> editFocused { text, start, end ->
                    if (start != end) Edit(text.removeRange(start, end), start)
                    else if (start > 0) Edit(text.removeRange(start - 1, start), start - 1)
                    else null
                }
                "enter" -> enter()
            }
            "text" -> {
                val typed = event.optString("s")
                if (typed.isNotEmpty()) editFocused { text, start, end ->
                    Edit(text.replaceRange(start, end, typed), start + typed.length)
                }
            }
        }
    }

    private fun point(event: JSONObject) = toScreen(event.optDouble("x"), event.optDouble("y"))

    private fun toScreen(x: Double, y: Double): Pair<Float, Float> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return (x.coerceIn(0.0, 1.0) * (metrics.widthPixels - 1)).toFloat() to
            (y.coerceIn(0.0, 1.0) * (metrics.heightPixels - 1)).toFloat()
    }

    private fun gesture(points: List<Pair<Float, Float>>, durationMs: Long) {
        val first = points.firstOrNull() ?: return
        val path = Path().apply {
            moveTo(first.first, first.second)
            points.drop(1).forEach { lineTo(it.first, it.second) }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 10_000))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private data class Edit(val text: String, val cursor: Int)

    /** Rewrites the focused text field with [change] (given its text and selection). */
    private fun editFocused(change: (String, Int, Int) -> Edit?) {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: return
        val text = if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val start = node.textSelectionStart.takeIf { it in 0..text.length } ?: text.length
        val end = node.textSelectionEnd.takeIf { it in start..text.length } ?: start
        val edit = change(text, start, end) ?: return
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, edit.text)
        })
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, edit.cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, edit.cursor)
        })
    }

    private fun enter() {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }

    companion object {
        @Volatile var instance: ControlService? = null
            private set

        private val _enabled = MutableStateFlow(false)
        /** Whether the user has switched the service on (and Android has bound it). */
        val enabled: StateFlow<Boolean> = _enabled

        /** Opens Settings → Accessibility, where the user can switch on "BubbleDrop remote control". */
        fun openSettings(context: Context) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun component(context: Context) = ComponentName(context, ControlService::class.java)
    }
}
