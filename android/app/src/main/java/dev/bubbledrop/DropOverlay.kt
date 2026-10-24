package dev.bubbledrop

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** What the orb at the top of the transfer card is showing. */
sealed interface OrbPhase {
    data object Waiting : OrbPhase
    data class Progress(val fraction: Float) : OrbPhase
    data object Done : OrbPhase
    data object Failed : OrbPhase
}

private val SuccessGreen = Color(0xFF34C759)

/**
 * Full-screen AirDrop-style layer: an incoming [offer] takes priority, otherwise the live
 * [transfer] is shown through to its finish. Nothing is drawn when both are null.
 */
@Composable
fun DropOverlay(
    offer: Prompts.Pending?,
    transfer: Transfer?,
    onDismissTransfer: (Transfer) -> Unit,
) {
    val offerKind = offer?.kind as? PromptKind.Offer
    val visible = offerKind != null || transfer != null
    // Keep the last content around so the exit animation has something to fade.
    var lastOffer by remember { mutableStateOf<Pair<String, AskRequest>?>(null) }
    var lastTransfer by remember { mutableStateOf<Transfer?>(null) }
    var showOffer by remember { mutableStateOf(false) }
    if (offerKind != null) {
        lastOffer = offer.id to offerKind.request
        showOffer = true
    } else if (transfer != null) {
        lastTransfer = transfer
        showOffer = false
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(260))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.85f) +
                    fadeIn(),
                exit = scaleOut(targetScale = 0.9f) + fadeOut(),
            ) {
                Surface(
                    shape = RoundedCornerShape(36.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 24.dp,
                    modifier = Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth(),
                ) {
                    val pendingOffer = lastOffer
                    if (showOffer && pendingOffer != null) {
                        OfferCard(pendingOffer.second) { accept -> Graph.prompts.respond(pendingOffer.first, accept) }
                    } else {
                        lastTransfer?.let { TransferCard(it) { onDismissTransfer(it) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfferCard(request: AskRequest, respond: (Boolean) -> Unit) {
    CardColumn {
        DropOrb(request.sender.platform, OrbPhase.Waiting)
        Spacer(Modifier.height(24.dp))
        Text(request.sender.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "wants to share " + if (request.files.size == 1) "a file" else "${request.files.size} files",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                request.files.take(3).forEach {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(it.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Text(formatBytes(it.size), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (request.files.size > 3) {
                    Text("and ${request.files.size - 3} more", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text("${formatBytes(request.totalSize)} in total · saves to Downloads/BubbleDrop",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            IosButton("Decline", { respond(false) }, Modifier.weight(1f), style = ButtonStyle.Tinted, color = Ios.colors.gray)
            IosButton("Accept", { respond(true) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TransferCard(transfer: Transfer, onClose: () -> Unit) {
    val context = LocalContext.current
    val phase = when (val status = transfer.status) {
        Transfer.Status.Waiting -> OrbPhase.Waiting
        Transfer.Status.Active -> OrbPhase.Progress(transfer.fraction)
        Transfer.Status.Done -> OrbPhase.Done
        is Transfer.Status.Failed -> OrbPhase.Failed
    }
    val speed = rememberSpeed(transfer)

    CardColumn {
        DropOrb(transfer.peerPlatform, phase)
        Spacer(Modifier.height(24.dp))
        val title = when (transfer.status) {
            Transfer.Status.Waiting -> when {
                transfer.incoming -> "Connecting…"
                transfer.link != null -> "Sending to ${transfer.peerName}"
                else -> "Waiting for ${transfer.peerName}"
            }
            Transfer.Status.Active -> if (transfer.incoming) "Receiving from ${transfer.peerName}" else "Sending to ${transfer.peerName}"
            Transfer.Status.Done -> when {
                transfer.link != null && transfer.incoming -> "Link received"
                transfer.link != null && transfer.linkOpened -> "Opened"
                transfer.incoming -> "Received"
                else -> "Sent"
            }
            is Transfer.Status.Failed -> if (transfer.incoming) "Couldn't receive" else "Couldn't send"
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(transfer.summary, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        val detail = when (val status = transfer.status) {
            Transfer.Status.Waiting -> if (transfer.incoming || transfer.link != null) "" else "They'll be asked to accept"
            Transfer.Status.Active -> buildString {
                append("${formatBytes(transfer.doneBytes)} of ${formatBytes(transfer.totalBytes)}")
                if (speed > 0) {
                    append(" · ${formatBytes(speed.toLong())}/s")
                    val remaining = (transfer.totalBytes - transfer.doneBytes) / speed
                    if (remaining >= 1) append(" · ${formatEta(remaining)} left")
                }
            }
            Transfer.Status.Done -> when {
                transfer.link != null && transfer.linkOpened -> "In ${transfer.peerName}'s browser"
                transfer.link != null && !transfer.incoming -> "${transfer.peerName} got a notification to open it"
                transfer.incoming -> "From ${transfer.peerName} · ${formatBytes(transfer.totalBytes)}"
                else -> "Delivered to ${transfer.peerName}"
            }
            is Transfer.Status.Failed -> status.message
        }
        Text(detail, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            color = if (transfer.status is Transfer.Status.Failed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            when {
                transfer.status == Transfer.Status.Done && transfer.incoming -> {
                    IosButton("Done", onClose, Modifier.weight(1f), style = ButtonStyle.Tinted)
                    IosButton("Open", { openReceived(context, transfer); onClose() }, Modifier.weight(1f))
                }
                transfer.status == Transfer.Status.Done || transfer.status is Transfer.Status.Failed ->
                    IosButton("Done", onClose, Modifier.fillMaxWidth())
                else -> IosButton("Hide", onClose, Modifier.fillMaxWidth(), style = ButtonStyle.Plain)
            }
        }
    }
}

@Composable
private fun CardColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 24.dp, vertical = 32.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/**
 * The device avatar wrapped in a progress ring. Ripples radiate while waiting; on success the
 * ring closes, the core turns green, a checkmark draws itself and a ring of sparks bursts out.
 */
@Composable
fun DropOrb(platform: String, phase: OrbPhase, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val coreIdle = MaterialTheme.colorScheme.primaryContainer

    val infinite = rememberInfiniteTransition(label = "orb")
    val ripple by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "ripple")
    val spin by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    val breathe by infinite.animateFloat(0.96f, 1.04f,
        infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")

    val target = when (phase) {
        OrbPhase.Waiting -> 0f
        is OrbPhase.Progress -> phase.fraction.coerceIn(0f, 1f)
        OrbPhase.Done, OrbPhase.Failed -> 1f
    }
    val progress by animateFloatAsState(target, spring(stiffness = Spring.StiffnessLow), label = "progress")
    val coreColor by animateColorAsState(
        when (phase) {
            OrbPhase.Done -> SuccessGreen
            OrbPhase.Failed -> error
            else -> coreIdle
        }, tween(400), label = "core",
    )
    val check = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }

    LaunchedEffect(phase is OrbPhase.Done, phase is OrbPhase.Failed) {
        when (phase) {
            OrbPhase.Done -> {
                view.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
                pop.snapTo(0.85f)
                coroutineScope {
                    launch { pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow)) }
                    launch { burst.snapTo(0f); burst.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
                    launch { delay(120); check.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
                }
            }
            OrbPhase.Failed -> {
                if (Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                check.snapTo(0f)
            }
            else -> {
                check.snapTo(0f)
                burst.snapTo(0f)
            }
        }
    }

    Box(modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val ring = size.minDimension * 0.30f
            val strokeW = 7.dp.toPx()

            if (phase == OrbPhase.Waiting) {
                for (i in 0 until 3) {
                    val t = (ripple + i / 3f) % 1f
                    drawCircle(primary.copy(alpha = (1f - t) * 0.45f), radius = ring * (1f + 0.65f * t),
                        center = c, style = Stroke(2.dp.toPx()))
                }
            }

            drawCircle(track, radius = ring, center = c, style = Stroke(strokeW))
            val ringColor = if (phase == OrbPhase.Failed) Brush.linearGradient(listOf(error, error))
                else if (phase == OrbPhase.Done) Brush.linearGradient(listOf(SuccessGreen, SuccessGreen))
                else Brush.sweepGradient(listOf(primary, tertiary, primary), c)
            val arcTopLeft = Offset(c.x - ring, c.y - ring)
            val arcSize = androidx.compose.ui.geometry.Size(ring * 2, ring * 2)
            if (phase == OrbPhase.Waiting) {
                rotate(spin, c) {
                    drawArc(ringColor, 0f, 70f, false, arcTopLeft, arcSize, style = Stroke(strokeW, cap = StrokeCap.Round))
                }
            } else if (progress > 0f) {
                rotate(-90f, c) {
                    drawArc(ringColor, 0f, 360f * progress, false, arcTopLeft, arcSize,
                        style = Stroke(strokeW, cap = StrokeCap.Round))
                }
            }

            val coreRadius = (ring - strokeW * 1.6f) * pop.value * (if (phase == OrbPhase.Waiting) breathe else 1f)
            drawCircle(coreColor, radius = coreRadius, center = c)

            if (burst.value > 0f && burst.value < 1f) {
                val sparks = 14
                for (i in 0 until sparks) {
                    val angle = Math.toRadians((i * 360.0 / sparks) + 8)
                    val dist = ring * (1.05f + 0.75f * burst.value)
                    val p = Offset(c.x + (cos(angle) * dist).toFloat(), c.y + (sin(angle) * dist).toFloat())
                    val color = if (i % 2 == 0) SuccessGreen else tertiary
                    drawCircle(color.copy(alpha = 1f - burst.value), radius = 4.dp.toPx() * (1f - burst.value * 0.6f), center = p)
                }
            }

            if (check.value > 0f) {
                val s = coreRadius * 0.9f
                val path = Path().apply {
                    moveTo(c.x - s * 0.42f, c.y + s * 0.02f)
                    lineTo(c.x - s * 0.12f, c.y + s * 0.32f)
                    lineTo(c.x + s * 0.45f, c.y - s * 0.30f)
                }
                val measure = PathMeasure().apply { setPath(path, false) }
                val partial = Path()
                measure.getSegment(0f, measure.length * check.value, partial, true)
                drawPath(partial, Color.White, style = Stroke(min(9.dp.toPx(), s * 0.16f), cap = StrokeCap.Round,
                    join = StrokeJoin.Round))
            }
        }
        when (phase) {
            OrbPhase.Done -> Unit
            OrbPhase.Failed -> Text("!", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White)
            else -> Icon(platformIcon(platform), contentDescription = null, tint = primary, modifier = Modifier.size(52.dp))
        }
    }
}

/** Bytes per second since this transfer started moving, or 0 until there's enough to measure. */
@Composable
private fun rememberSpeed(transfer: Transfer): Double {
    var start by remember(transfer.id) { mutableStateOf<Pair<Long, Long>?>(null) }
    if (transfer.status == Transfer.Status.Active && start == null) {
        start = SystemClock.elapsedRealtime() to transfer.doneBytes
    }
    val (t0, b0) = start ?: return 0.0
    val elapsed = (SystemClock.elapsedRealtime() - t0) / 1000.0
    return if (elapsed < 0.5) 0.0 else (transfer.doneBytes - b0) / elapsed
}

private fun formatEta(seconds: Double): String {
    val s = seconds.toLong()
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}

internal fun openReceived(context: Context, transfer: Transfer) {
    val uri = transfer.savedUris.singleOrNull()
    val intent = if (uri != null) {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else {
        Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
