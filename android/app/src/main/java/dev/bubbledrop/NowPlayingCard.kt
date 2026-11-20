package dev.bubbledrop

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlin.math.abs
import kotlin.math.sin

/**
 * "Now playing in Spotify" with one button to move it to the paired Mac. On the tap the cover
 * turns into a spinning record sending out rings, then flies off the card as the Mac takes over.
 */
@Composable
fun NowPlayingSection() {
    val context = LocalContext.current
    val ios = Ios.colors
    val relay by NotificationRelay.enabled.collectAsState()
    val now by SpotifyHandoff.nowPlaying.collectAsState()
    val status by SpotifyHandoff.status.collectAsState()
    val peers by Graph.discovery.peers.collectAsState()
    val trusted by Graph.trust.devices.collectAsState()
    if (trusted.isEmpty()) return
    val mac = remember(peers, trusted) { SpotifyHandoff.pairedMac() }

    if (!relay) {
        GroupedSection(header = "Spotify") {
            ListRow(
                title = "Play Spotify on Your Mac",
                subtitle = "Allow Notification access so BubbleDrop can see what's playing",
                leading = { IconTile(Icons.Rounded.MusicNote, ios.green) },
                trailing = {
                    IosButton("Turn On", { NotificationRelay.openSettings(context) }, style = ButtonStyle.Tinted, large = false)
                },
            )
        }
        return
    }
    val track = now ?: return

    val failed = status as? SpotifyHandoff.Status.Failed
    GroupedSection(
        header = "Now Playing in Spotify",
        footer = failed?.message
            ?: "Pauses here and carries on from the same second on ${mac?.name ?: "your Mac"}." +
                if (SpotifyTile.canRequestAdd) "" else " Also in Quick Settings as \"Play on Mac\".",
    ) {
        NowPlayingCard(track, status, mac)
        if (SpotifyTile.canRequestAdd) {
            Hairline(Modifier.padding(start = 16.dp))
            ListRow(title = "Add \"Play on Mac\" to Quick Settings", titleColor = ios.blue,
                onClick = { SpotifyTile.requestAdd(context) })
        }
    }
}

@Composable
private fun NowPlayingCard(track: SpotifyHandoff.NowPlaying, status: SpotifyHandoff.Status, mac: Peer?) {
    val ios = Ios.colors
    val haptics = LocalHapticFeedback.current
    val tint = remember(track.art) { track.art?.let(::averageColor) } ?: ios.green
    val sending = status is SpotifyHandoff.Status.Sending
    val done = status as? SpotifyHandoff.Status.Done
    val failed = status is SpotifyHandoff.Status.Failed

    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.32f), tint.copy(alpha = 0.06f))))
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverToRecord(track.art, tint, sending, done, failed)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val headline = when {
                        done != null && done.playing -> "Now playing on ${done.macName}"
                        done != null -> done.message ?: "Opened on ${done.macName}"
                        else -> track.title
                    }
                    AnimatedContent(
                        targetState = headline,
                        transitionSpec = {
                            (slideInVertically(spring(dampingRatio = 0.8f)) { it } + fadeIn()) togetherWith
                                (slideOutVertically { -it } + fadeOut())
                        },
                        label = "headline",
                    ) { text ->
                        Text(text, style = IosType.headline, color = ios.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(track.artist, style = IosType.subheadline, color = ios.secondaryLabel, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveBars(active = track.playing || sending, color = tint)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                sending -> "Moving to ${(status as SpotifyHandoff.Status.Sending).macName}"
                                done != null -> "Paused on this phone"
                                track.playing -> "Playing on this phone"
                                else -> "Paused"
                            },
                            style = IosType.caption, color = ios.secondaryLabel,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            HandoffButton(
                label = when (status) {
                    is SpotifyHandoff.Status.Sending -> "Sending to ${status.macName}…"
                    is SpotifyHandoff.Status.Done -> if (status.playing) "Playing on ${status.macName}" else "Opened on ${status.macName}"
                    is SpotifyHandoff.Status.Failed -> "Try Again"
                    SpotifyHandoff.Status.Idle -> if (mac != null) "Play on ${mac.name}" else "Mac Not Nearby"
                },
                color = when {
                    done != null -> ios.green
                    failed -> ios.red
                    else -> ios.green
                },
                icon = when {
                    done != null -> Icons.Rounded.Check
                    failed -> Icons.Rounded.PriorityHigh
                    else -> Icons.Rounded.LaptopMac
                },
                enabled = mac != null && !sending && done == null,
                shimmer = sending,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                SpotifyHandoff.handoff()
            }
        }
    }
}

/**
 * The album cover. While sending it rounds into a spinning record (with a spindle hole) and
 * rings ripple off it; once the Mac has it, it flies up off the card and a check pops in; if it
 * fails, it shakes.
 */
@Composable
private fun CoverToRecord(art: Bitmap?, tint: Color, sending: Boolean, done: SpotifyHandoff.Status.Done?, failed: Boolean) {
    val ios = Ios.colors
    val loop = rememberInfiniteTransition(label = "record")
    val angle by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "spin")
    val ripple by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "ripple")
    val disc by animateFloatAsState(if (sending || done != null) 1f else 0f,
        spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow), label = "disc")
    val fly = remember { Animatable(0f) }
    LaunchedEffect(done != null) {
        if (done != null) fly.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        else fly.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow))
    }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(failed) {
        if (!failed) return@LaunchedEffect
        for (x in listOf(12f, -10f, 8f, -6f, 3f, 0f)) shake.animateTo(x, tween(55))
    }
    val check by animateFloatAsState(if (done != null) 1f else 0f,
        spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow), label = "check")

    Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
        // Rings rippling off the record while it sends.
        Canvas(Modifier.fillMaxSize()) {
            if (disc < 0.02f || fly.value > 0.5f) return@Canvas
            for (i in 0 until 3) {
                val t = (ripple + i / 3f) % 1f
                drawCircle(
                    tint.copy(alpha = (1f - t) * 0.7f * disc * (1f - fly.value * 2f)),
                    radius = size.minDimension / 2f * (1f + t * 0.85f),
                    style = Stroke(width = (2.5f * (1f - t) + 0.8f) * density),
                )
            }
        }
        val corner = lerp(12.dp, 38.dp, disc.coerceIn(0f, 1f))
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = angle * disc
                    translationX = fly.value * 70.dp.toPx() + shake.value.dp.toPx()
                    translationY = -fly.value * 110.dp.toPx()
                    val s = (1f - 0.1f * disc) * (1f - 0.7f * fly.value)
                    scaleX = s
                    scaleY = s
                    alpha = 1f - fly.value
                    shape = RoundedCornerShape(corner)
                    clip = true
                }
                .background(tint)
        ) {
            if (art != null) {
                Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(34.dp))
            }
            // Grooves and the spindle hole appear as it becomes a record.
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = disc.coerceIn(0f, 1f) }) {
                val c = center
                val r = size.minDimension / 2f
                // Vinyl outside, the cover left showing as the label.
                drawCircle(Color(0xFF0E0E0E), radius = r * 0.72f, center = c, style = Stroke(r * 0.56f))
                var gr = r * 0.48f
                while (gr < r * 0.97f) {
                    drawCircle(Color.White.copy(alpha = 0.07f), radius = gr, center = c, style = Stroke(0.6f * density))
                    gr += r * 0.06f
                }
                drawCircle(Color.White.copy(alpha = 0.55f), radius = r * 0.05f, center = Offset(c.x + r * 0.3f, c.y))
                drawCircle(Color(0xFF0A0A0A), radius = r * 0.07f, center = c)
            }
        }
        // The check that takes its place.
        if (check > 0.01f) {
            Box(
                Modifier
                    .size(52.dp)
                    .graphicsLayer {
                        scaleX = check
                        scaleY = check
                        alpha = check.coerceIn(0f, 1f)
                    }
                    .clip(CircleShape)
                    .background(if (done?.playing != false) ios.green else ios.blue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (done?.playing != false) Icons.Rounded.Check else Icons.Rounded.LaptopMac, null,
                    tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** Little equalizer bars that dance while music plays and settle when it stops. */
@Composable
private fun LiveBars(active: Boolean, color: Color) {
    val loop = rememberInfiniteTransition(label = "bars")
    val t by loop.animateFloat(0f, 1000f, infiniteRepeatable(tween(1_000_000, easing = LinearEasing)), label = "t")
    val level by animateFloatAsState(if (active) 1f else 0f, tween(400), label = "level")
    Canvas(Modifier.size(width = 16.dp, height = 12.dp)) {
        val w = size.width / 4f
        for (i in 0 until 4) {
            val bounce = abs(sin(t * (5f + i * 1.7f) + i * 1.3f))
            val h = size.height * (0.25f + 0.75f * bounce * level)
            drawRoundRect(color, topLeft = Offset(i * w + w * 0.15f, size.height - h),
                size = androidx.compose.ui.geometry.Size(w * 0.7f, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.35f))
        }
    }
}

/** The big capsule button; a band of light sweeps across it while sending. */
@Composable
private fun HandoffButton(
    label: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    shimmer: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    val loop = rememberInfiniteTransition(label = "shimmer")
    val sweep by loop.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "sweep")
    val bg = if (enabled || shimmer || label.startsWith("Playing") || label.startsWith("Opened")) color else Ios.colors.gray
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(46.dp)
            .clip(CircleShape)
            .background(bg)
            .drawWithContent {
                drawContent()
                if (shimmer) {
                    val x = size.width * sweep
                    drawRect(Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent),
                        start = Offset(x - size.width * 0.25f, 0f), end = Offset(x + size.width * 0.25f, size.height)))
                }
            }
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = label to icon,
            transitionSpec = {
                (slideInVertically(spring(dampingRatio = 0.75f)) { it / 2 } + fadeIn()) togetherWith
                    (slideOutVertically { -it / 2 } + fadeOut())
            },
            label = "button",
        ) { (text, glyph) ->
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(glyph, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(text, color = Color.White, style = IosType.headline.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The cover's average colour, for tinting the card. */
private fun averageColor(art: Bitmap): Color? = runCatching {
    val soft = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
    val pixel = Bitmap.createScaledBitmap(soft, 1, 1, true).getPixel(0, 0)
    Color(pixel).copy(alpha = 1f)
}.getOrNull()
