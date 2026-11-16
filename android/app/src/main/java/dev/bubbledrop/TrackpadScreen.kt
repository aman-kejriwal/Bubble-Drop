package dev.bubbledrop

import android.view.HapticFeedbackConstants
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The full-screen trackpad, shown over the app while [Trackpad] has a session. */
@Composable
fun TrackpadOverlay() {
    val state by Trackpad.state.collectAsState()
    if (state == Trackpad.State.Idle) return
    val ios = Ios.colors
    val view = LocalView.current
    val context = LocalContext.current
    val keyboard = remember { TrackpadKeyboardView(context) }
    val hideKeyboard = {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
        Unit
    }
    BackHandler { Trackpad.close() }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            hideKeyboard()
        }
    }
    val active = state as? Trackpad.State.Active
    // A session that stopped has nothing to type into.
    LaunchedEffect(active != null) { if (active == null) hideKeyboard() }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // The keyboard's height while it's up; the pad and keys sit above it.
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val typing = imeBottom > 0.dp
    val name = when (val s = state) {
        is Trackpad.State.Connecting -> s.name
        is Trackpad.State.Active -> s.name
        is Trackpad.State.Failed -> s.name
        Trackpad.State.Idle -> ""
    }

    Column(
        Modifier.fillMaxSize().background(ios.groupedBackground)
            // Nothing underneath should react to touches that miss the pad.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }
            .padding(top = statusBar, bottom = maxOf(navBar, imeBottom) + 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Trackpad", style = IosType.headline, color = ios.label)
                Text(name, style = IosType.footnote, color = ios.secondaryLabel, maxLines = 1)
            }
            if (active?.keyboard == true) {
                // What the phone's keyboard types into; it has nothing to show.
                AndroidView(factory = { keyboard }, modifier = Modifier.size(1.dp))
                Icon(
                    if (typing) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard,
                    if (typing) "Hide keyboard" else "Type on the Mac",
                    tint = ios.blue,
                    modifier = Modifier.clip(CircleShape)
                        .clickable { if (typing) hideKeyboard() else keyboard.show() }.padding(8.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            TextAction("Done", style = IosType.headline) { Trackpad.close() }
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(28.dp)).background(ios.cell)
                .then(if (state is Trackpad.State.Active) Modifier.pointerInput(Unit) { trackpadGestures(view::performHapticFeedback) } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                is Trackpad.State.Connecting -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ActivityIndicator(24.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Connecting to ${s.name}…", style = IosType.subheadline, color = ios.secondaryLabel)
                }
                // With the keyboard up the pad is too short for the hints.
                is Trackpad.State.Active -> if (!typing) Text(
                    "Slide to move the pointer · Tap to click\n" +
                        "Two fingers to scroll · Two-finger tap to right-click\n" +
                        "Tap, then touch and slide to drag · Lift and touch again to keep dragging\n" +
                        "Pinch to zoom\n" +
                        "Three fingers left or right to switch apps · Up for Mission Control",
                    style = IosType.footnote, color = ios.tertiaryLabel, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
                is Trackpad.State.Failed -> Column(
                    Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Trackpad Stopped", style = IosType.headline, color = ios.label)
                    Spacer(Modifier.height(6.dp))
                    Text(s.message, style = IosType.subheadline, color = ios.secondaryLabel, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(18.dp))
                    IosButton("Try Again", { Trackpad.retry() }, large = false)
                }
                Trackpad.State.Idle -> Unit
            }
        }
        if (state is Trackpad.State.Active) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (typing) {
                    // The keys a phone's keyboard doesn't have.
                    MacKey("esc", Trackpad.KEY_ESCAPE, repeats = false)
                    MacKey("tab", Trackpad.KEY_TAB, repeats = false)
                    MacKey("←", Trackpad.KEY_LEFT, repeats = true)
                    MacKey("↓", Trackpad.KEY_DOWN, repeats = true)
                    MacKey("↑", Trackpad.KEY_UP, repeats = true)
                    MacKey("→", Trackpad.KEY_RIGHT, repeats = true)
                    return@Row
                }
                MacKey(Icons.Rounded.BrightnessLow, "Brightness down", Trackpad.KEY_BRIGHTNESS_DOWN, repeats = true)
                MacKey(Icons.Rounded.BrightnessHigh, "Brightness up", Trackpad.KEY_BRIGHTNESS_UP, repeats = true)
                MacKey(Icons.Rounded.PlayArrow, "Play or pause", Trackpad.KEY_PLAY_PAUSE, repeats = false)
                MacKey(Icons.AutoMirrored.Rounded.VolumeOff, "Mute", Trackpad.KEY_MUTE, repeats = false)
                MacKey(Icons.AutoMirrored.Rounded.VolumeDown, "Volume down", Trackpad.KEY_VOLUME_DOWN, repeats = true)
                MacKey(Icons.AutoMirrored.Rounded.VolumeUp, "Volume up", Trackpad.KEY_VOLUME_UP, repeats = true)
            }
        }
    }
}

@Composable
private fun MacKey(icon: ImageVector, label: String, key: Int, repeats: Boolean) =
    MacKey(key, repeats) { Icon(icon, label, tint = Ios.colors.label) }

@Composable
private fun MacKey(label: String, key: Int, repeats: Boolean) =
    MacKey(key, repeats) { Text(label, style = IosType.headline, color = Ios.colors.label) }

/** One of the Mac's keys under the pad. Holding a [repeats] key keeps pressing it. */
@Composable
private fun MacKey(key: Int, repeats: Boolean, content: @Composable () -> Unit) {
    val ios = Ios.colors
    val view = LocalView.current
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(ios.cell)
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    Trackpad.key(key)
                    var wait = 400L
                    // Until the finger lifts: a repeating key presses again after each wait.
                    while (true) {
                        val lifted = withTimeoutOrNull(wait) {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                            }
                            true
                        }
                        if (lifted == true) break
                        if (repeats) Trackpad.key(key)
                        wait = 120L
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** A touch shorter than this that stays put is a tap. */
private const val TAP_MAX_MS = 250L
/** A touch that starts this soon after a tap continues it: a double click, or a drag if it moves. */
private const val TAP_GAP_MS = 300L
/** How much further apart two fingers move, in dp, to double the size. */
private const val ZOOM_DP = 150f
/** How far three fingers travel, in dp, before it counts as a swipe. */
private const val SWIPE_DP = 40f
/** After a drag's finger lifts, a touch this soon carries the drag on, with the button still held. */
private const val DRAG_LOCK_MS = 300L
/** Fingers that sat still this long before lifting meant to stop the scroll, not flick it. */
private const val MOMENTUM_STILL_MS = 40L
/** A scroll keeps going after the fingers lift if they were moving at least this fast, in dp a second. */
private const val MOMENTUM_MIN = 200f
/** The fastest a scroll carries on at, in dp a second. */
private const val MOMENTUM_MAX = 6000f
/** A scroll that has slowed to this, in dp a second, stops. */
private const val MOMENTUM_STOP = 15f
/** How long a scroll takes to lose about two thirds of its speed, in seconds. */
private const val MOMENTUM_SECONDS = 0.35f

/**
 * One finger moves the pointer and two scroll. A tap clicks (two fingers: right-click). A touch
 * right after a tap holds the button down once it moves, so tap-then-slide drags. Three fingers
 * swiping left, right, up or down are sent as one swipe, like on the Mac's own trackpad. Two
 * fingers moving apart or together zoom. A scroll whose fingers lift while moving carries on and
 * slows down, until the next touch. A drag's button stays held for a moment after the finger
 * lifts, so touching again carries the drag on from a new spot on the pad.
 *
 * [haptic] plays one of [HapticFeedbackConstants] for clicks, drags and swipes.
 */
private suspend fun PointerInputScope.trackpadGestures(haptic: (Int) -> Unit) = coroutineScope {
    val scope = this
    val slop = viewConfiguration.touchSlop
    var lastTapAt = 0L
    var lastTapClicks = 0
    var momentum: Job? = null
    // Set while a lifted drag's button is still held, waiting to see if a touch carries it on.
    var heldDrag: Any? = null
    var dragRelease: Job? = null
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        // Touching the pad stops a scroll that was still coasting.
        momentum?.cancel()
        val resumedDrag = heldDrag != null
        heldDrag = null
        dragRelease?.cancel()
        val startedAt = down.uptimeMillis
        val afterTap = startedAt - lastTapAt < TAP_GAP_MS
        var fingers = 1
        var travelled = Offset.Zero
        var moved = false
        var dragging = resumedDrag
        var swipe = Offset.Zero
        var swiped = false
        // The gap between two fingers when both first touched, and at the last event.
        var firstSpread = -1f
        var lastSpread = 0f
        var pinching = false
        var endedAt = startedAt
        // How far the scroll has gone, in dp, for working out how fast it was going at the end.
        val scrollSpeed = VelocityTracker()
        var scrolled = Offset.Zero
        var lastScrollAt = 0L
        var scrollLiftedAt = 0L
        var finished = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
                endedAt = event.changes.maxOf { it.uptimeMillis }
                val pressed = event.changes.filter { it.pressed }
                if (lastScrollAt != 0L && scrollLiftedAt == 0L && pressed.size < 2) scrollLiftedAt = endedAt
                if (pressed.isEmpty()) {
                    finished = true
                    break
                }
                fingers = maxOf(fingers, pressed.size)
                if (pinching) {
                    // Once it's a pinch it stays one; a finger lifting early doesn't nudge the pointer.
                    if (pressed.size == 2) {
                        val spread = (pressed[0].position - pressed[1].position).getDistance()
                        if (spread != lastSpread) {
                            Trackpad.zoom(Trackpad.ZOOM_CHANGE, (spread - lastSpread) / density / ZOOM_DP)
                            lastSpread = spread
                        }
                    }
                    continue
                }
                if (pressed.size == 2 && fingers == 2 && !dragging && !moved) {
                    // Fingers moving apart or together, rather than side by side, are a pinch.
                    val spread = (pressed[0].position - pressed[1].position).getDistance()
                    if (firstSpread < 0) firstSpread = spread
                    if (kotlin.math.abs(spread - firstSpread) > slop * 2) {
                        pinching = true
                        moved = true
                        lastSpread = spread
                        Trackpad.zoom(Trackpad.ZOOM_BEGIN)
                        continue
                    }
                }
                // The fingers' average movement; one that just landed hasn't moved yet.
                val moving = pressed.filter { it.previousPressed }
                if (moving.isEmpty()) continue
                var delta = Offset.Zero
                // The changes were consumed above, which makes plain positionChange() report zero.
                moving.forEach { delta += it.positionChangeIgnoreConsumed() }
                delta /= moving.size.toFloat()
                if (!moved) {
                    // Small wobble during a tap isn't movement.
                    travelled += delta
                    if (travelled.getDistance() < slop) continue
                    moved = true
                    if (!dragging && afterTap && fingers == 1) {
                        dragging = true
                        Trackpad.button(Trackpad.BUTTON_LEFT, down = true)
                        haptic(HapticFeedbackConstants.LONG_PRESS)
                    }
                    continue
                }
                if (delta == Offset.Zero) continue
                when {
                    // Once a third finger has landed the touch is a swipe, sent once, and nothing else.
                    !dragging && fingers >= 3 -> if (!swiped && pressed.size >= 3) {
                        swipe += delta / density
                        val across = kotlin.math.abs(swipe.x)
                        val along = kotlin.math.abs(swipe.y)
                        if (maxOf(across, along) >= SWIPE_DP) {
                            swiped = true
                            haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                            Trackpad.swipe(when {
                                across >= along -> if (swipe.x < 0) Trackpad.SWIPE_LEFT else Trackpad.SWIPE_RIGHT
                                else -> if (swipe.y < 0) Trackpad.SWIPE_UP else Trackpad.SWIPE_DOWN
                            })
                        }
                    }
                    dragging || fingers == 1 -> Trackpad.move(delta.x / density, delta.y / density)
                    // After a scroll, the last finger to lift shouldn't nudge the pointer.
                    pressed.size >= 2 -> {
                        val step = delta / density
                        Trackpad.scroll(step.x, step.y)
                        scrolled += step
                        scrollSpeed.addPosition(endedAt, scrolled)
                        lastScrollAt = endedAt
                        scrollLiftedAt = 0L
                    }
                }
            }
        } finally {
            if (dragging && finished && moved) {
                // The button stays down for a moment: a touch that lands in time carries the drag on.
                val token = Any()
                heldDrag = token
                dragRelease = scope.launch {
                    try {
                        delay(DRAG_LOCK_MS)
                    } finally {
                        // Not if the next touch took the drag over; also runs if the pad goes away.
                        if (heldDrag === token) {
                            heldDrag = null
                            Trackpad.button(Trackpad.BUTTON_LEFT, down = false)
                        }
                    }
                }
            } else if (dragging) {
                // A tap ends a held drag. Also when the gesture is cancelled, so the Mac's button
                // is never left held.
                Trackpad.button(Trackpad.BUTTON_LEFT, down = false)
            }
            if (pinching) Trackpad.zoom(Trackpad.ZOOM_END)
        }
        if (fingers == 2 && !pinching && scrollLiftedAt != 0L && scrollLiftedAt - lastScrollAt <= MOMENTUM_STILL_MS) {
            val speed = scrollSpeed.calculateVelocity()
            var vx = speed.x
            var vy = speed.y
            val fastest = kotlin.math.hypot(vx, vy)
            if (fastest >= MOMENTUM_MIN) {
                if (fastest > MOMENTUM_MAX) {
                    vx *= MOMENTUM_MAX / fastest
                    vy *= MOMENTUM_MAX / fastest
                }
                momentum = scope.launch {
                    var last = System.nanoTime()
                    while (kotlin.math.hypot(vx, vy) > MOMENTUM_STOP) {
                        delay(8)
                        val now = System.nanoTime()
                        val seconds = (now - last) / 1e9f
                        last = now
                        Trackpad.scroll(vx * seconds, vy * seconds)
                        val slowing = kotlin.math.exp(-seconds / MOMENTUM_SECONDS)
                        vx *= slowing
                        vy *= slowing
                    }
                }
            }
        }
        if (resumedDrag) {
            // A tap after a drag only let go of it.
            lastTapAt = 0
        } else if (moved || endedAt - startedAt > TAP_MAX_MS) {
            lastTapAt = 0
        } else if (fingers >= 3) {
            // A three-finger tap does nothing.
            lastTapAt = 0
        } else if (fingers == 2) {
            haptic(HapticFeedbackConstants.LONG_PRESS)
            Trackpad.click(Trackpad.BUTTON_RIGHT)
            lastTapAt = 0
        } else {
            val clicks = if (afterTap) minOf(lastTapClicks + 1, 3) else 1
            haptic(HapticFeedbackConstants.CONTEXT_CLICK)
            Trackpad.click(Trackpad.BUTTON_LEFT, clicks)
            lastTapAt = endedAt
            lastTapClicks = clicks
        }
    }
}
