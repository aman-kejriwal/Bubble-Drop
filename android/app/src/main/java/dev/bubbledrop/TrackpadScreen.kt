package dev.bubbledrop

import androidx.activity.compose.BackHandler
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
import kotlinx.coroutines.delay
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
    BackHandler { Trackpad.close() }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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
            .padding(top = statusBar, bottom = navBar + 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Trackpad", style = IosType.headline, color = ios.label)
                Text(name, style = IosType.footnote, color = ios.secondaryLabel, maxLines = 1)
            }
            TextAction("Done", style = IosType.headline) { Trackpad.close() }
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(28.dp)).background(ios.cell)
                .then(if (state is Trackpad.State.Active) Modifier.pointerInput(Unit) { trackpadGestures() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                is Trackpad.State.Connecting -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ActivityIndicator(24.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Connecting to ${s.name}…", style = IosType.subheadline, color = ios.secondaryLabel)
                }
                is Trackpad.State.Active -> Text(
                    "Slide to move the pointer · Tap to click\n" +
                        "Two fingers to scroll · Two-finger tap to right-click\n" +
                        "Tap, then touch and slide to drag\n" +
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

/** One of the Mac's volume, brightness or play keys. Holding a [repeats] key keeps pressing it. */
@Composable
private fun MacKey(icon: ImageVector, label: String, key: Int, repeats: Boolean) {
    val ios = Ios.colors
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(ios.cell)
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown().consume()
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
        Icon(icon, label, tint = ios.label)
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

/**
 * One finger moves the pointer and two scroll. A tap clicks (two fingers: right-click). A touch
 * right after a tap holds the button down once it moves, so tap-then-slide drags. Three fingers
 * swiping left, right, up or down are sent as one swipe, like on the Mac's own trackpad. Two
 * fingers moving apart or together zoom.
 */
private suspend fun PointerInputScope.trackpadGestures() {
    val slop = viewConfiguration.touchSlop
    var lastTapAt = 0L
    var lastTapClicks = 0
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val startedAt = down.uptimeMillis
        val afterTap = startedAt - lastTapAt < TAP_GAP_MS
        var fingers = 1
        var travelled = Offset.Zero
        var moved = false
        var dragging = false
        var swipe = Offset.Zero
        var swiped = false
        // The gap between two fingers when both first touched, and at the last event.
        var firstSpread = -1f
        var lastSpread = 0f
        var pinching = false
        var endedAt = startedAt
        try {
            while (true) {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
                endedAt = event.changes.maxOf { it.uptimeMillis }
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
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
                    if (afterTap && fingers == 1) {
                        dragging = true
                        Trackpad.button(Trackpad.BUTTON_LEFT, down = true)
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
                            Trackpad.swipe(when {
                                across >= along -> if (swipe.x < 0) Trackpad.SWIPE_LEFT else Trackpad.SWIPE_RIGHT
                                else -> if (swipe.y < 0) Trackpad.SWIPE_UP else Trackpad.SWIPE_DOWN
                            })
                        }
                    }
                    dragging || fingers == 1 -> Trackpad.move(delta.x / density, delta.y / density)
                    // After a scroll, the last finger to lift shouldn't nudge the pointer.
                    pressed.size >= 2 -> Trackpad.scroll(delta.x / density, delta.y / density)
                }
            }
        } finally {
            // Also when the gesture is cancelled, so the Mac's button is never left held.
            if (dragging) Trackpad.button(Trackpad.BUTTON_LEFT, down = false)
            if (pinching) Trackpad.zoom(Trackpad.ZOOM_END)
        }
        if (moved || endedAt - startedAt > TAP_MAX_MS) {
            lastTapAt = 0
        } else if (fingers >= 3) {
            // A three-finger tap does nothing.
            lastTapAt = 0
        } else if (fingers == 2) {
            Trackpad.click(Trackpad.BUTTON_RIGHT)
            lastTapAt = 0
        } else {
            val clicks = if (afterTap) minOf(lastTapClicks + 1, 3) else 1
            Trackpad.click(Trackpad.BUTTON_LEFT, clicks)
            lastTapAt = endedAt
            lastTapClicks = clicks
        }
    }
}
