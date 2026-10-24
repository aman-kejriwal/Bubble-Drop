package dev.bubbledrop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Apple's semantic system colors, so the app reads like a native iOS one in both appearances. */
@Immutable
data class IosColors(
    val dark: Boolean,
    val groupedBackground: Color,
    val cell: Color,
    val cellPressed: Color,
    val elevated: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val blue: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
    val indigo: Color,
    val purple: Color,
    val teal: Color,
    val gray: Color,
)

private val LightIos = IosColors(
    dark = false,
    groupedBackground = Color(0xFFF2F2F7),
    cell = Color.White,
    cellPressed = Color(0xFFD1D1D6),
    elevated = Color(0xFFF2F2F7),
    label = Color.Black,
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0xFFC6C6C8),
    fill = Color(0x33787880),
    blue = Color(0xFF007AFF),
    green = Color(0xFF34C759),
    red = Color(0xFFFF3B30),
    orange = Color(0xFFFF9500),
    indigo = Color(0xFF5856D6),
    purple = Color(0xFFAF52DE),
    teal = Color(0xFF30B0C7),
    gray = Color(0xFF8E8E93),
)

private val DarkIos = IosColors(
    dark = true,
    groupedBackground = Color.Black,
    cell = Color(0xFF1C1C1E),
    cellPressed = Color(0xFF3A3A3C),
    elevated = Color(0xFF2C2C2E),
    label = Color.White,
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0xFF38383A),
    fill = Color(0x5C787880),
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    red = Color(0xFFFF453A),
    orange = Color(0xFFFF9F0A),
    indigo = Color(0xFF5E5CE6),
    purple = Color(0xFFBF5AF2),
    teal = Color(0xFF40C8E0),
    gray = Color(0xFF8E8E93),
)

/** The iOS text styles (Large Title … Caption), tracked tighter to bring Roboto closer to SF Pro. */
object IosType {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val title2 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val title3 = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.43).sp)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.43).sp)
    val callout = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.31).sp)
    val subheadline = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.23).sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.08).sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
}

val LocalIos = staticCompositionLocalOf { LightIos }

object Ios {
    val colors: IosColors @Composable get() = LocalIos.current
}

@Composable
fun BubbleDropTheme(content: @Composable () -> Unit) {
    val ios = if (isSystemInDarkTheme()) DarkIos else LightIos
    // Material pieces still in use (the transfer overlay, ripples) pick up the same palette.
    val scheme = if (ios.dark) darkColorScheme(
        primary = ios.blue, onPrimary = Color.White,
        primaryContainer = Color(0xFF0B2A4A), onPrimaryContainer = ios.blue,
        tertiary = ios.teal, error = ios.red,
        background = ios.groupedBackground, onBackground = ios.label,
        surface = ios.cell, onSurface = ios.label, onSurfaceVariant = ios.secondaryLabel,
        surfaceContainerHigh = ios.cell, surfaceContainerHighest = ios.elevated, outline = ios.separator,
    ) else lightColorScheme(
        primary = ios.blue, onPrimary = Color.White,
        primaryContainer = Color(0xFFDDEBFF), onPrimaryContainer = ios.blue,
        tertiary = ios.teal, error = ios.red,
        background = ios.groupedBackground, onBackground = ios.label,
        surface = ios.cell, onSurface = ios.label, onSurfaceVariant = ios.secondaryLabel,
        surfaceContainerHigh = ios.cell, surfaceContainerHighest = ios.elevated, outline = ios.separator,
    )
    val typography = Typography(
        headlineSmall = IosType.title2,
        titleMedium = IosType.headline,
        bodyLarge = IosType.body,
        bodyMedium = IosType.subheadline,
        bodySmall = IosType.footnote,
        labelLarge = IosType.footnote.copy(fontWeight = FontWeight.Medium),
        labelMedium = IosType.caption,
    )
    CompositionLocalProvider(LocalIos provides ios) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
