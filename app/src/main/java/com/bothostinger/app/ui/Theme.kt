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
    /** Surfaces semi-transparentes : le dégradé du fond se voit à travers. */
    val Surface = Color(0x99000000)
    val SurfaceHigh = Color(0x33FFFFFF)
    val Border = Color(0x26FFFFFF)
    /** Fond des boutons secondaires (comme « Connexion » sur Discord), transparent. */
    val SecondaryButton = Color(0x1FFFFFFF)
    val Text = Color(0xFFF5F5F5)
    val TextDim = Color(0xFF9A9A9A)

    val AccentLight = Color(0xFF4FC3FF)
    val Accent = Color(0xFF3D6BFF)
    val AccentDeep = Color(0xFF2A35D9)

    val Green = Color(0xFF2ECC71)
    val Yellow = Color(0xFFFFC107)
    val Red = Color(0xFFFF3B30)
    val Stream = Color(0xFF9146FF)

    /** Le dégradé bleu de BotHostinger. */
    val gradient = Brush.linearGradient(listOf(AccentLight, Accent, AccentDeep))
    val gradientHorizontal = Brush.horizontalGradient(listOf(AccentLight, Accent, AccentDeep))

    /** Fond : bleu transparent en haut qui se fond dans le noir en bas. */
    val backgroundGlow = Brush.verticalGradient(
        0f to Color(0xB32F5BFF),
        0.3f to Color(0x4D2A4BFF),
        0.6f to Color(0xFF03050D),
        1f to Black,
    )
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    secondary = Palette.AccentLight,
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
