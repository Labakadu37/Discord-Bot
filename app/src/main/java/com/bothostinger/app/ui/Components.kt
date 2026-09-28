package com.bothostinger.app.ui

import androidx.compose.animation.core.animateDpAsState
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

/** Bouton principal : rectangle plein avec le dégradé orange. */
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
            .background(brush, RectangleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.Black, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text.uppercase(), color = Color.Black, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 1.5.sp)
    }
}

/** Bouton secondaire : contour orange. */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .height(48.dp)
            .border(1.dp, Palette.gradientHorizontal, RectangleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Palette.Orange, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), color = Palette.Orange, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
    }
}

/** Carré d'icône (bouton de la barre du haut). */
@Composable
fun SquareIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .background(Palette.Surface)
            .border(1.dp, Palette.Border)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = Palette.Orange, modifier = Modifier.size(22.dp))
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
            .border(1.dp, if (checked) Palette.Orange else Palette.Border)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = knob, y = 3.dp)
                .size(20.dp)
                .background(if (checked) Color.Black else Palette.TextDim)
        )
    }
}

/** Choix sélectionnable (remplace les « chips » arrondies). */
@Composable
fun SquareChoice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(44.dp)
            .background(if (selected) Color(0x26FF6A00) else Palette.Surface)
            .border(1.dp, if (selected) Palette.gradientHorizontal else SolidColor(Palette.Border), RectangleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Palette.Orange else Palette.Text,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp,
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
        shape = RoundedCornerShape(0.dp),
        placeholder = { Text(placeholder, color = Palette.TextDim) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Orange,
            unfocusedBorderColor = Palette.Border,
            disabledBorderColor = Palette.Border,
            focusedContainerColor = Palette.Black,
            unfocusedContainerColor = Palette.Black,
            disabledContainerColor = Palette.Black,
            cursorColor = Palette.Orange,
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
fun Badge(text: String, color: Color = Palette.Orange) {
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
