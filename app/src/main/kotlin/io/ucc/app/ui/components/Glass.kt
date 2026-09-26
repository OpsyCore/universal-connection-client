package io.ucc.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * "Glass" card surface (v1.0.4): a translucent white veil over the tinted surface colour, a soft top-left
 * highlight and a hairline border. Drawn in the draw phase only (no recomposition, no `RenderEffect` blur —
 * the app background is a flat colour, so a real blur would cost GPU time for no visible difference and
 * would exclude API < 31). In the light theme it degrades to the plain surface + outline.
 */
@Composable
fun Modifier.glass(shape: Shape = MaterialTheme.shapes.large, base: Color = MaterialTheme.colorScheme.surfaceContainer): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f // follows the app's own theme setting, not the system
    val outline = MaterialTheme.colorScheme.outline
    val veil = if (dark) listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.035f)) else listOf(Color.White, Color.White)
    val borderColor = if (dark) Color.White.copy(alpha = 0.14f) else outline
    return this
        .clip(shape)
        .drawBehind {
            drawRect(base.copy(alpha = if (dark) 0.72f else 1f))
            drawRect(Brush.linearGradient(veil))
            if (dark) drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.15f, 0f), radius = size.maxDimension * 0.9f))
        }
        .border(1.dp, borderColor, shape)
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
