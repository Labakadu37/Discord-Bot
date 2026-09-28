package com.bothostinger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Module

@Composable
fun ModuleScreen(module: Module, enabled: Boolean, onToggle: (Boolean) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(module.title, onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Panel(Modifier.fillMaxWidth(), borderBrush = Palette.gradient) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(56.dp).background(Palette.gradient), contentAlignment = Alignment.Center) {
                            Icon(moduleIcon(module.id), null, tint = Color.Black, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            GradientText(module.title, fontSize = 22.sp)
                            Text("${module.commands.size} commandes", color = Palette.TextDim, fontSize = 12.sp)
                        }
                    }
                    Hint(module.description, Palette.Text)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (module.alwaysOn) "TOUJOURS ACTIF" else if (enabled) "SYSTÈME ACTIVÉ" else "SYSTÈME DÉSACTIVÉ",
                            color = if (enabled) Palette.Orange else Palette.TextDim,
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (!module.alwaysOn) SquareToggle(enabled, onToggle)
                    }
                }
            }
            if (module.setup.isNotBlank()) {
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        SectionLabel("Configuration")
                        Hint(module.setup)
                    }
                }
            }
            item {
                Spacer(Modifier.size(4.dp))
                SectionLabel("Commandes")
            }
            items(module.commands, key = { it.name }) { CommandRow(it) }
        }
    }
}

@Composable
private fun CommandRow(cmd: Command) {
    Panel(Modifier.fillMaxWidth(), padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "/${cmd.name}",
                color = Palette.Orange,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            cmd.permissionLabel?.let { Badge(it) }
        }
        Hint(cmd.description)
        cmd.subcommands.forEach { sub ->
            Row(Modifier.padding(start = 10.dp)) {
                Text("${sub.name} ", color = Palette.Text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("— ${sub.description}", color = Palette.TextDim, fontSize = 13.sp)
            }
        }
    }
}
