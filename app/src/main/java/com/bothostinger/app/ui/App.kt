package com.bothostinger.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.EmojiPeople
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Style
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothostinger.app.bot.BotRuntime
import com.bothostinger.app.bot.BotService
import com.bothostinger.app.bot.ConnState
import com.bothostinger.app.bot.modules.Modules
import com.bothostinger.app.data.BotSettings
import com.bothostinger.app.data.SettingsStore

fun moduleIcon(id: String): ImageVector = when (id) {
    "general" -> Icons.Filled.Info
    "moderation" -> Icons.Filled.Gavel
    "niveaux" -> Icons.Filled.MilitaryTech
    "economie" -> Icons.Filled.MonetizationOn
    "giveaways" -> Icons.Filled.Redeem
    "tickets" -> Icons.Filled.ConfirmationNumber
    "bienvenue" -> Icons.Filled.EmojiPeople
    "logs" -> Icons.Filled.History
    "suggestions" -> Icons.Filled.Lightbulb
    "roles" -> Icons.Filled.Style
    "fun" -> Icons.Filled.Casino
    "utilitaire" -> Icons.Filled.Campaign
    else -> Icons.Filled.Extension
}

@Composable
fun BotHostingerApp() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val modules = remember { Modules.all() }
    var settings by remember { mutableStateOf(store.load()) }
    // "home", "console", "settings" ou "module:<id>"
    var screen by rememberSaveable { mutableStateOf("home") }
    val runtime by BotRuntime.state.collectAsStateWithLifecycle()
    val logs by BotRuntime.logs.collectAsStateWithLifecycle()
    val running = runtime.conn == ConnState.ONLINE || runtime.conn == ConnState.CONNECTING

    fun update(new: BotSettings, action: String? = null) {
        settings = new
        store.save(new)
        if (running && action != null) BotService.send(context, action)
    }

    BackHandler(enabled = screen != "home") { screen = "home" }

    BotHostingerTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(Palette.Black)
                .background(Palette.backgroundGlow)
                .safeDrawingPadding()
        ) {
            AnimatedContent(targetState = screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { current ->
                when {
                    current == "console" -> ConsoleScreen(logs, onBack = { screen = "home" }, onClear = BotRuntime::clearLogs)
                    current == "settings" -> SettingsScreen(
                        settings = settings,
                        runtime = runtime,
                        running = running,
                        onBack = { screen = "home" },
                        onTokenChange = { update(settings.copy(token = it)) },
                        onPresenceChange = { update(it, BotService.ACTION_PRESENCE) },
                        onAutoStartChange = { update(settings.copy(autoStart = it)) },
                    )
                    current.startsWith("module:") -> {
                        val module = modules.first { it.id == current.removePrefix("module:") }
                        ModuleScreen(
                            module = module,
                            enabled = module.alwaysOn || module.id !in settings.disabledModules,
                            onToggle = { on -> update(settings.toggleModule(module.id, on), BotService.ACTION_RELOAD) },
                            onBack = { screen = "home" },
                        )
                    }
                    else -> HomeScreen(
                        settings = settings,
                        runtime = runtime,
                        running = running,
                        modules = modules,
                        onStart = { BotService.send(context, BotService.ACTION_START) },
                        onStop = { BotService.send(context, BotService.ACTION_STOP) },
                        onToggleModule = { id, on -> update(settings.toggleModule(id, on), BotService.ACTION_RELOAD) },
                        onOpen = { screen = it },
                    )
                }
            }
        }
    }
}

private fun BotSettings.toggleModule(id: String, on: Boolean) =
    copy(disabledModules = if (on) disabledModules - id else disabledModules + id)
