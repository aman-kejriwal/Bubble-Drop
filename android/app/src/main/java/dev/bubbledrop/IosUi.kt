package dev.bubbledrop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.cos
import kotlin.math.sin

// Building blocks that mimic UIKit: inset-grouped lists, settings icon tiles, switches, alerts.

private val SectionShape = RoundedCornerShape(12.dp)

/** An inset-grouped table section: uppercase header, rounded card of rows, optional footer. */
@Composable
fun GroupedSection(
    header: String? = null,
    footer: String? = null,
    headerAction: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ios = Ios.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (header != null || headerAction != null) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Bottom) {
                Text(header.orEmpty().uppercase(), style = IosType.footnote, color = ios.secondaryLabel,
                    modifier = Modifier.weight(1f))
                headerAction?.invoke()
            }
        }
        Column(Modifier.fillMaxWidth().clip(SectionShape).background(ios.cell), content = content)
        if (footer != null) {
            Text(footer, style = IosType.footnote, color = ios.secondaryLabel,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        }
    }
}

/** A table cell. [leading] is usually an [IconTile] or [DeviceAvatar]; the separator is inset past it. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    titleAccessory: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    chevron: Boolean = false,
    divider: Boolean = false,
    titleColor: Color = Ios.colors.label,
    subtitleColor: Color = Ios.colors.secondaryLabel,
    onClick: (() -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val ios = Ios.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(if (pressed) ios.cellPressed else ios.cell, tween(if (pressed) 0 else 250), label = "cell")
    Column(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, onClick = onClick) else Modifier)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = IosType.body, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (titleAccessory != null) {
                        Spacer(Modifier.width(6.dp))
                        titleAccessory()
                    }
                }
                if (subtitle != null) {
                    Text(subtitle, style = IosType.footnote, color = subtitleColor, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
                below?.invoke()
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (chevron) {
                Spacer(Modifier.width(8.dp))
                Chevron()
            }
        }
        if (divider) Hairline(Modifier.padding(start = if (leading != null) 60.dp else 16.dp))
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(Ios.colors.separator))
}

/** The thin gray disclosure chevron at the end of a navigable row. */
@Composable
fun Chevron(color: Color = Ios.colors.tertiaryLabel) {
    Canvas(Modifier.size(width = 8.dp, height = 14.dp)) {
        val w = 2.dp.toPx()
        drawLine(color, Offset(w, w), Offset(size.width - w, size.height / 2), w, StrokeCap.Round)
        drawLine(color, Offset(size.width - w, size.height / 2), Offset(w, size.height - w), w, StrokeCap.Round)
    }
}

/** The colored rounded square that fronts a row in iOS Settings. */
@Composable
fun IconTile(icon: ImageVector, tint: Color, size: Dp = 30.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.24f))
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.82f).compositeOver(Color.White), tint))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a),
        blue * a + background.blue * (1 - a), 1f)
}

fun platformIcon(platform: String): ImageVector =
    if (platform == "macos") Icons.Rounded.Laptop else Icons.Rounded.PhoneAndroid

/** A round contact-style avatar for a device, Mac in blue and phones in green. */
@Composable
fun DeviceAvatar(platform: String, size: Dp = 44.dp) {
    val ios = Ios.colors
    val colors = if (platform == "macos") listOf(ios.teal, ios.blue) else listOf(ios.green, ios.teal)
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(platformIcon(platform), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** A UISwitch: green track, white thumb that slides with a spring. */
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val ios = Ios.colors
    val track by animateColorAsState(if (checked) ios.green else ios.fill, tween(220), label = "track")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp,
        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), label = "thumb")
    Box(
        Modifier.size(width = 51.dp, height = 31.dp).clip(CircleShape).background(track)
            .clickable(remember { MutableInteractionSource() }, indication = null) { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier.offset(x = x, y = 2.dp).size(27.dp)
                .shadow(3.dp, CircleShape, ambientColor = Color.Black.copy(0.2f), spotColor = Color.Black.copy(0.25f))
                .background(Color.White, CircleShape)
        )
    }
}

enum class ButtonStyle { Filled, Tinted, Plain }

/** A capsule button that dims and shrinks slightly while pressed, like UIButton.Configuration. */
@Composable
fun IosButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Filled,
    color: Color = Ios.colors.blue,
    large: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "scale")
    val (bg, fg) = when (style) {
        ButtonStyle.Filled -> color to Color.White
        ButtonStyle.Tinted -> color.copy(alpha = 0.15f) to color
        ButtonStyle.Plain -> Color.Transparent to color
    }
    Box(
        modifier
            .scale(scale)
            .height(if (large) 50.dp else 30.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (large) 20.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (pressed) fg.copy(alpha = 0.7f) else fg, maxLines = 1,
            style = if (large) IosType.headline else IosType.subheadline.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** Plain tinted text that fades while pressed, for header actions like "Clear". */
@Composable
fun TextAction(text: String, color: Color = Ios.colors.blue, style: TextStyle = IosType.subheadline, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Text(text, style = style, color = color.copy(alpha = if (pressed) 0.4f else 1f),
        modifier = Modifier.clickable(interaction, indication = null, onClick = onClick).padding(4.dp))
}

/** UIProgressView: a thin rounded bar. */
@Composable
fun IosProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Ios.colors.blue) {
    val ios = Ios.colors
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessLow), label = "bar")
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(ios.fill)) {
        Box(Modifier.fillMaxWidth(animated).height(4.dp).clip(CircleShape).background(color))
    }
}

/** UIActivityIndicatorView: eight fading spokes. */
@Composable
fun ActivityIndicator(size: Dp = 20.dp, color: Color = Ios.colors.secondaryLabel) {
    val spin by rememberInfiniteTransition(label = "spinner")
        .animateFloat(0f, 8f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "step")
    Canvas(Modifier.size(size)) {
        val step = spin.toInt()
        val r = this.size.minDimension / 2
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0 - 90)
            val fade = ((i - step + 8) % 8) / 8f
            val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            drawLine(color.copy(alpha = 0.25f + 0.75f * fade), center + dir * (r * 0.45f), center + dir * (r * 0.95f),
                r * 0.22f, StrokeCap.Round)
        }
    }
}

class AlertAction(val label: String, val bold: Boolean = false, val destructive: Boolean = false, val onClick: () -> Unit)

/** UIAlertController: a compact centered card with side-by-side actions split by hairlines. */
@Composable
fun IosAlert(
    title: String,
    message: String? = null,
    actions: List<AlertAction>,
    onDismiss: () -> Unit = {},
    content: (@Composable () -> Unit)? = null,
) {
    val ios = Ios.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(290.dp).clip(RoundedCornerShape(16.dp))
                .background(if (ios.dark) ios.elevated else Color(0xFFF7F7F9)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = IosType.headline, color = ios.label, textAlign = TextAlign.Center)
                if (message != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(message, style = IosType.footnote, color = ios.label, textAlign = TextAlign.Center)
                }
                content?.invoke()
            }
            Hairline()
            val stacked = actions.size > 2
            if (stacked) {
                actions.forEachIndexed { i, action ->
                    if (i > 0) Hairline()
                    AlertButton(action, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth().height(46.dp)) {
                    actions.forEachIndexed { i, action ->
                        if (i > 0) Box(Modifier.width(0.5.dp).height(46.dp).background(ios.separator))
                        AlertButton(action, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertButton(action: AlertAction, modifier: Modifier) {
    val ios = Ios.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier.height(46.dp).background(if (pressed) ios.fill else Color.Transparent)
            .clickable(interaction, indication = null, onClick = action.onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(action.label, style = if (action.bold) IosType.headline else IosType.body,
            color = if (action.destructive) ios.red else ios.blue)
    }
}

/** A small rounded status badge, e.g. "Paired". */
@Composable
fun Badge(text: String, color: Color, icon: ImageVector? = null) {
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(11.dp))
        Text(text, style = IosType.caption.copy(fontWeight = FontWeight.SemiBold), color = color)
    }
}
