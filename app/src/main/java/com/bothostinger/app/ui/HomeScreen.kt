package com.bothostinger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothostinger.app.bot.ConnState
import com.bothostinger.app.bot.RuntimeState
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.formatDuration
import com.bothostinger.app.data.BotSettings
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    settings: BotSettings,
    runtime: RuntimeState,
    running: Boolean,
    modules: List<Module>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleModule: (String, Boolean) -> Unit,
    onOpen: (String) -> Unit,
) {
    val activeCount = modules.count { it.alwaysOn || it.id !in settings.disabledModules }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        full { Header(onConsole = { onOpen("console") }, onSettings = { onOpen("settings") }) }
        full { StatusPanel(runtime) }
        full {
            if (running) {
                GradientButton(
                    "Arrêter le bot", onStop, Modifier.fillMaxWidth(), Icons.Filled.Stop,
                    brush = Brush.horizontalGradient(listOf(Color(0xFFFF3B30), Color(0xFFB00020))),
                    height = 60,
                )
            } else {
                GradientButton(
                    "Lancer le bot", onStart, Modifier.fillMaxWidth(), Icons.Filled.PlayArrow,
                    enabled = settings.token.isNotBlank(), height = 60,
                )
            }
        }
        if (settings.token.isBlank()) {
            full {
                Alert("Ajoute le token de ton bot pour commencer.", "Paramètres") { onOpen("settings") }
            }
        }
        if (runtime.missingMembersIntent && running) {
            full {
                Alert(
                    "« SERVER MEMBERS INTENT » désactivé : bienvenue, au revoir, autorôle et logs d'arrivée sont en pause.",
                    "Comment faire",
                ) { onOpen("settings") }
            }
        }
        full {
            Spacer(Modifier.height(6.dp))
            SectionLabel("Systèmes", trailing = "$activeCount/${modules.size} actifs")
        }
        items(modules, key = { it.id }) { m ->
            ModuleTile(
                module = m,
                enabled = m.alwaysOn || m.id !in settings.disabledModules,
                onToggle = { onToggleModule(m.id, it) },
                onClick = { onOpen("module:${m.id}") },
            )
        }
        full {
            Text(
                "Bot hébergé sur ce téléphone · ${modules.sumOf { it.commands.size }} commandes",
                color = Palette.TextDim,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun Header(onConsole: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .background(Palette.gradient),
            contentAlignment = Alignment.Center,
        ) {
            Text("B", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 26.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GradientText("BotHostinger", fontSize = 24.sp)
            Text("HÉBERGEUR DE BOT DISCORD", color = Palette.TextDim, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        }
        SquareIconButton(Icons.Filled.Terminal, "Console", onConsole)
        Spacer(Modifier.width(8.dp))
        SquareIconButton(Icons.Filled.Settings, "Paramètres", onSettings)
    }
}

@Composable
private fun StatusPanel(runtime: RuntimeState) {
    val (color, label) = when (runtime.conn) {
        ConnState.ONLINE -> Palette.Green to "En ligne"
        ConnState.CONNECTING -> Palette.Yellow to "Connexion…"
        ConnState.ERROR -> Palette.Red to "Erreur"
        ConnState.OFFLINE -> Palette.TextDim to "Hors ligne"
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(runtime.conn) {
        while (runtime.conn == ConnState.ONLINE) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val online = runtime.conn == ConnState.ONLINE

    Panel(Modifier.fillMaxWidth(), borderBrush = if (online) Palette.gradient else SolidColor(Palette.Border)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color))
            Spacer(Modifier.width(10.dp))
            Text(label.uppercase(), color = color, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            if (runtime.botName != null) {
                Text(runtime.botName, color = Palette.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Serveurs", if (online) runtime.guildCount.toString() else "—", Modifier.weight(1f))
            Stat("Ping", if (online && runtime.latencyMs >= 0) "${runtime.latencyMs} ms" else "—", Modifier.weight(1f))
            Stat("En ligne", if (online && runtime.onlineSince > 0) uptime(now - runtime.onlineSince) else "—", Modifier.weight(1f))
        }
        runtime.error?.let { Hint(it, Palette.Red) }
    }
}

/** Durée arrondie à la minute : « 2 h 5 min ». */
private fun uptime(ms: Long): String = if (ms < 60_000) "< 1 min" else formatDuration(ms - ms % 60_000)

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .background(Palette.Black)
            .padding(10.dp)
    ) {
        Text(value, color = Palette.Text, fontWeight = FontWeight.Black, fontSize = 16.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(label.uppercase(), color = Palette.TextDim, fontSize = 10.sp, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun Alert(text: String, action: String, onClick: () -> Unit) {
    Panel(Modifier.fillMaxWidth(), borderBrush = SolidColor(Palette.Yellow), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = Palette.Yellow, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
        Text("$action →".uppercase(), color = Palette.Yellow, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun ModuleTile(module: Module, enabled: Boolean, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Panel(
        Modifier.fillMaxWidth().height(150.dp),
        borderBrush = if (enabled) SolidColor(Color(0x66FF6A00)) else SolidColor(Palette.Border),
        onClick = onClick,
        padding = 14,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(if (enabled) Palette.gradient else SolidColor(Palette.SurfaceHigh)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(moduleIcon(module.id), null, tint = if (enabled) Color.Black else Palette.TextDim, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            if (module.alwaysOn) Badge("Toujours") else SquareToggle(enabled, onToggle)
        }
        Spacer(Modifier.weight(1f))
        Text(
            module.title.uppercase(),
            color = if (enabled) Palette.Text else Palette.TextDim,
            fontWeight = FontWeight.Black,
            fontSize = 14.sp,
            letterSpacing = 1.sp,
        )
        Text("${module.commands.size} commande${if (module.commands.size > 1) "s" else ""}", color = Palette.TextDim, fontSize = 12.sp)
    }
}
