package com.pocketbot.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketbot.app.bot.BotRuntime
import com.pocketbot.app.bot.BotService
import com.pocketbot.app.bot.ConnState
import com.pocketbot.app.data.ConfigStore

val Blurple = Color(0xFF5865F2)
val OnlineGreen = Color(0xFF23A55A)
val IdleYellow = Color(0xFFF0B232)
val DndRed = Color(0xFFF23F43)

private val colors = darkColorScheme(
    primary = Blurple,
    onPrimary = Color.White,
    secondary = Color(0xFF949CF7),
    background = Color(0xFF1E1F22),
    surface = Color(0xFF1E1F22),
    surfaceContainer = Color(0xFF2B2D31),
    surfaceContainerHigh = Color(0xFF313338),
    surfaceContainerHighest = Color(0xFF383A40),
    error = DndRed,
)

private enum class Tab(val label: String, val icon: ImageVector) {
    BOT("Bot", Icons.Filled.SmartToy),
    COMMANDS("Commandes", Icons.Filled.Code),
    SETTINGS("Réglages", Icons.Filled.Settings),
    CONSOLE("Console", Icons.Filled.Terminal),
}

@Composable
fun PocketBotApp() {
    val context = LocalContext.current
    val store = remember { ConfigStore(context) }
    var settings by remember { mutableStateOf(store.loadSettings()) }
    var commands by remember { mutableStateOf(store.loadCommands()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val runtime by BotRuntime.state.collectAsStateWithLifecycle()
    val logs by BotRuntime.logs.collectAsStateWithLifecycle()
    val running = runtime.conn == ConnState.ONLINE || runtime.conn == ConnState.CONNECTING

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            val modifier = Modifier.padding(padding)
            when (Tab.entries[tab]) {
                Tab.BOT -> BotScreen(
                    modifier = modifier,
                    settings = settings,
                    runtime = runtime,
                    running = running,
                    onSettingsChange = {
                        settings = it
                        store.saveSettings(it)
                    },
                    onStart = { BotService.send(context, BotService.ACTION_START) },
                    onStop = { BotService.send(context, BotService.ACTION_STOP) },
                )
                Tab.COMMANDS -> CommandsScreen(
                    modifier = modifier,
                    commands = commands,
                    onChange = {
                        commands = it
                        store.saveCommands(it)
                        if (running) BotService.send(context, BotService.ACTION_RELOAD)
                    },
                )
                Tab.SETTINGS -> SettingsScreen(
                    modifier = modifier,
                    settings = settings,
                    running = running,
                    onApplyPresence = {
                        settings = it
                        store.saveSettings(it)
                        if (running) BotService.send(context, BotService.ACTION_PRESENCE)
                    },
                    onApplyWelcome = {
                        settings = it
                        store.saveSettings(it)
                        // Les intents changent : il faut se réidentifier.
                        if (running) BotService.send(context, BotService.ACTION_RELOAD)
                    },
                )
                Tab.CONSOLE -> ConsoleScreen(modifier = modifier, logs = logs, onClear = BotRuntime::clearLogs)
            }
        }
    }
}
