package com.bothostinger.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Palette {
    val Black = Color(0xFF050505)
    val Surface = Color(0xFF111111)
    val SurfaceHigh = Color(0xFF1A1A1A)
    val Border = Color(0xFF2A2A2A)
    val Text = Color(0xFFF5F5F5)
    val TextDim = Color(0xFF9A9A9A)

    val OrangeLight = Color(0xFFFFB000)
    val Orange = Color(0xFFFF6A00)
    val OrangeDeep = Color(0xFFFF3D00)

    val Green = Color(0xFF2ECC71)
    val Yellow = Color(0xFFFFC107)
    val Red = Color(0xFFFF3B30)

    /** Le dégradé orange de BotHostinger. */
    val gradient = Brush.linearGradient(listOf(OrangeLight, Orange, OrangeDeep))
    val gradientHorizontal = Brush.horizontalGradient(listOf(OrangeLight, Orange, OrangeDeep))

    /** Fond : noir avec une lueur orange en haut. */
    val backgroundGlow = Brush.verticalGradient(
        0f to Color(0xFF2A1200),
        0.35f to Black,
        1f to Black,
    )
}

private val colors = darkColorScheme(
    primary = Palette.Orange,
    onPrimary = Color.Black,
    secondary = Palette.OrangeLight,
    background = Palette.Black,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    outline = Palette.Border,
    error = Palette.Red,
)

/** Tout est carré : aucun coin arrondi. */
private val squareShapes = RoundedCornerShape(0.dp).let { Shapes(it, it, it, it, it) }

@Composable
fun BotHostingerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, shapes = squareShapes, content = content)
}
