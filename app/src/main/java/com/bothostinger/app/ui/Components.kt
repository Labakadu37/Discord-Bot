package com.bothostinger.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Coins des boutons, comme sur Discord. */
val ButtonShape = RoundedCornerShape(14.dp)

/** Bouton principal façon Discord : plein, coins arrondis, dégradé orange. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    brush: Brush = Palette.gradientHorizontal,
    height: Int = 56,
) {
    Row(
        modifier
            .height(height.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(ButtonShape)
            .background(brush)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}

/** Bouton secondaire façon Discord (« Connexion ») : foncé transparent, coins arrondis. */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .height(52.dp)
            .clip(ButtonShape)
            .background(Palette.SecondaryButton)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Palette.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

/** Carré d'icône (bouton de la barre du haut). */
@Composable
fun SquareIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(ButtonShape)
            .background(Palette.SecondaryButton)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = Palette.Accent, modifier = Modifier.size(22.dp))
    }
}

/** Bloc de contenu : fond sombre, bord fin, coins carrés. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    borderBrush: Brush = SolidColor(Palette.Border),
    onClick: (() -> Unit)? = null,
    padding: Int = 16,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(Palette.Surface)
            .border(1.dp, borderBrush, RectangleShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Titre de section : petite barre orange + texte en capitales espacées. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 4.dp, height = 16.dp)
                .background(Palette.gradient)
        )
        Spacer(Modifier.width(10.dp))
        Text(text.uppercase(), color = Palette.Text, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = Palette.TextDim, fontSize = 12.sp)
    }
}

/** Interrupteur carré. */
@Composable
fun SquareToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val knob by animateDpAsState(if (checked) 24.dp else 2.dp, label = "knob")
    Box(
        Modifier
            .size(width = 48.dp, height = 26.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .background(if (checked) Palette.gradientHorizontal else SolidColor(Palette.SurfaceHigh))
            .border(1.dp, if (checked) Palette.Accent else Palette.Border)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = knob, y = 3.dp)
                .size(20.dp)
                .background(if (checked) Color.White else Palette.TextDim)
        )
    }
}

/** Choix sélectionnable, avec une icône dessinée optionnelle. */
@Composable
fun SquareChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .height(48.dp)
            .clip(ButtonShape)
            .background(if (selected) Palette.gradientHorizontal else SolidColor(Palette.SecondaryButton))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            color = if (selected) Color.White else Palette.Text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

@Composable
fun GradientText(text: String, fontSize: TextUnit, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Black) {
    Text(
        text,
        modifier = modifier,
        style = TextStyle(brush = Palette.gradientHorizontal, fontSize = fontSize, fontWeight = weight, letterSpacing = 0.5.sp),
    )
}

@Composable
fun SquareTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    password: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        shape = ButtonShape,
        placeholder = { Text(placeholder, color = Palette.TextDim) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Accent,
            unfocusedBorderColor = Palette.Border,
            disabledBorderColor = Palette.Border,
            focusedContainerColor = Palette.SecondaryButton,
            unfocusedContainerColor = Palette.SecondaryButton,
            disabledContainerColor = Palette.SecondaryButton,
            cursorColor = Palette.Accent,
            disabledTextColor = Palette.TextDim,
        ),
    )
}

/** Barre du haut des écrans secondaires. */
@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SquareIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Retour", onBack)
        Spacer(Modifier.width(14.dp))
        Text(title.uppercase(), color = Palette.Text, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Petite étiquette carrée (ex. permission requise). */
@Composable
fun Badge(text: String, color: Color = Palette.Accent) {
    Box(
        Modifier
            .border(1.dp, color)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text.uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun Hint(text: String, color: Color = Palette.TextDim) {
    Text(text, color = color, fontSize = 13.sp, lineHeight = 18.sp)
}

/** Pastille de statut dessinée à la main, comme celles de Discord. */
@Composable
fun StatusIcon(status: String, modifier: Modifier = Modifier.size(14.dp)) {
    val color = when (status) {
        "online" -> Palette.Green
        "idle" -> Palette.Yellow
        "dnd" -> Palette.Red
        "streaming" -> Palette.Stream
        else -> Palette.TextDim
    }
    Canvas(modifier) {
        val r = size.minDimension / 2
        val disc = Path().apply { addOval(Rect(center, r)) }
        val cut = Path()
        when (status) {
            // Lune : disque moins un disque décalé en haut à gauche.
            "idle" -> cut.addOval(Rect(Offset(center.x - r * 0.45f, center.y - r * 0.45f), r * 0.62f))
            // Occupé : barre horizontale évidée.
            "dnd" -> cut.addRoundRect(
                RoundRect(
                    Rect(Offset(center.x - r * 0.6f, center.y - r * 0.18f), Size(r * 1.2f, r * 0.36f)),
                    CornerRadius(r * 0.18f),
                )
            )
            // Invisible : anneau.
            "invisible" -> cut.addOval(Rect(center, r * 0.45f))
            // Stream : triangle « lecture ».
            "streaming" -> cut.apply {
                moveTo(center.x - r * 0.3f, center.y - r * 0.45f)
                lineTo(center.x + r * 0.5f, center.y)
                lineTo(center.x - r * 0.3f, center.y + r * 0.45f)
                close()
            }
        }
        drawPath(Path.combine(PathOperation.Difference, disc, cut), color)
    }
}

/** Manette de jeu dessinée à la main (activité « Joue à »). */
@Composable
fun GamepadIcon(color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            addRoundRect(RoundRect(Rect(Offset(0f, h * 0.22f), Size(w, h * 0.56f)), CornerRadius(h * 0.28f)))
        }
        val holes = Path().apply {
            // Croix directionnelle
            addRect(Rect(Offset(w * 0.16f, h * 0.45f), Size(w * 0.24f, h * 0.1f)))
            addRect(Rect(Offset(w * 0.23f, h * 0.36f), Size(w * 0.1f, h * 0.28f)))
            // Boutons
            addOval(Rect(Offset(w * 0.7f, h * 0.42f), w * 0.055f))
            addOval(Rect(Offset(w * 0.8f, h * 0.55f), w * 0.055f))
        }
        drawPath(Path.combine(PathOperation.Difference, body, holes), color)
    }
}
