package io.ucc.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Restrained "premium network tool" palette: charcoal background, graphite
 * surfaces, one teal accent, white/soft-grey text. Deliberately not dynamic
 * colour — the brand should look identical on every device.
 */
object UccColors {
    val Background = Color(0xFF0E1114)
    val Surface = Color(0xFF151A1F)
    val SurfaceHigh = Color(0xFF1C2229)
    val SurfaceHighest = Color(0xFF232B33)
    val Outline = Color(0xFF2E3740)
    val Accent = Color(0xFF3CC8C2)
    val AccentDim = Color(0xFF1F6F6C)
    val OnAccent = Color(0xFF031617)
    val Text = Color(0xFFECEFF2)
    val TextMuted = Color(0xFF9AA4AE)
    val Success = Color(0xFF4FD08A)
    val Warning = Color(0xFFE6B450)
    val Error = Color(0xFFF06A6A)

    val LightBackground = Color(0xFFF4F6F8)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceHigh = Color(0xFFEDF1F4)
    val LightOutline = Color(0xFFD5DCE2)
    val LightAccent = Color(0xFF0F8F8A)
    val LightText = Color(0xFF14191E)
    val LightTextMuted = Color(0xFF5C6873)
}

private val DarkScheme = darkColorScheme(
    primary = UccColors.Accent,
    onPrimary = UccColors.OnAccent,
    primaryContainer = UccColors.AccentDim,
    onPrimaryContainer = UccColors.Text,
    secondary = UccColors.TextMuted,
    onSecondary = UccColors.Background,
    secondaryContainer = UccColors.SurfaceHighest,
    onSecondaryContainer = UccColors.Text,
    tertiary = UccColors.Warning,
    tertiaryContainer = Color(0xFF3A3117),
    onTertiaryContainer = UccColors.Text,
    background = UccColors.Background,
    onBackground = UccColors.Text,
    surface = UccColors.Background,
    onSurface = UccColors.Text,
    surfaceVariant = UccColors.SurfaceHigh,
    onSurfaceVariant = UccColors.TextMuted,
    surfaceContainer = UccColors.Surface,
    surfaceContainerLow = UccColors.Surface,
    surfaceContainerHigh = UccColors.SurfaceHigh,
    surfaceContainerHighest = UccColors.SurfaceHighest,
    outline = UccColors.Outline,
    outlineVariant = UccColors.Outline,
    error = UccColors.Error,
    onError = UccColors.Background,
    errorContainer = Color(0xFF3A1D1D),
    onErrorContainer = UccColors.Text,
)

private val LightScheme = lightColorScheme(
    primary = UccColors.LightAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCBEDEB),
    onPrimaryContainer = UccColors.LightText,
    secondary = UccColors.LightTextMuted,
    secondaryContainer = UccColors.LightSurfaceHigh,
    onSecondaryContainer = UccColors.LightText,
    tertiary = Color(0xFF9A6B00),
    tertiaryContainer = Color(0xFFFFEBBD),
    onTertiaryContainer = UccColors.LightText,
    background = UccColors.LightBackground,
    onBackground = UccColors.LightText,
    surface = UccColors.LightBackground,
    onSurface = UccColors.LightText,
    surfaceVariant = UccColors.LightSurfaceHigh,
    onSurfaceVariant = UccColors.LightTextMuted,
    surfaceContainer = UccColors.LightSurface,
    surfaceContainerLow = UccColors.LightSurface,
    surfaceContainerHigh = UccColors.LightSurfaceHigh,
    surfaceContainerHighest = Color(0xFFE3E9EE),
    outline = UccColors.LightOutline,
    outlineVariant = UccColors.LightOutline,
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val UccShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun UccTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, shapes = UccShapes, content = content)
}

/** Semantic colour for a connection state, shared by Home, notification-like chips and Servers. */
object StateColors {
    val connected: Color @Composable get() = UccColors.Success
    val transitioning: Color @Composable get() = MaterialTheme.colorScheme.primary
    val reconnecting: Color @Composable get() = UccColors.Warning
    val error: Color @Composable get() = MaterialTheme.colorScheme.error
    val idle: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
}
