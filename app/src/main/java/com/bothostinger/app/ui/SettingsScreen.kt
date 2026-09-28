package com.bothostinger.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bothostinger.app.bot.Presence
import com.bothostinger.app.bot.RuntimeState
import com.bothostinger.app.data.BotSettings

private val statuses = listOf(
    "online" to "🟢 En ligne",
    "idle" to "🌙 Inactif",
    "dnd" to "⛔ Occupé",
    "invisible" to "⚫ Invisible",
)

@Composable
fun SettingsScreen(
    settings: BotSettings,
    runtime: RuntimeState,
    running: Boolean,
    onBack: () -> Unit,
    onTokenChange: (String) -> Unit,
    onPresenceChange: (BotSettings) -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var showToken by rememberSaveable { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(isIgnoringBattery(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryOk = isIgnoringBattery(context) }
    val appId = runtime.applicationId ?: applicationIdFromToken(settings.token)

    Column(Modifier.fillMaxSize()) {
        TopBar("Paramètres", onBack)
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Panel(Modifier.fillMaxWidth()) {
                SectionLabel("Token du bot")
                SquareTextField(
                    value = settings.token,
                    onValueChange = { onTokenChange(it.trim()) },
                    placeholder = "Colle le token ici",
                    enabled = !running,
                    password = !showToken,
                    trailing = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                if (showToken) "Masquer" else "Afficher",
                                tint = Palette.TextDim,
                            )
                        }
                    },
                )
                Hint(
                    if (running) "Arrête le bot pour changer de token." else
                        "Le token reste sur ton téléphone et n'est envoyé qu'à Discord. Ne le partage jamais."
                )
                OutlineButton("Portail développeur", { openUrl(context, "https://discord.com/developers/applications") }, Modifier.fillMaxWidth(), Icons.AutoMirrored.Filled.OpenInNew)
                if (appId != null) {
                    OutlineButton(
                        "Inviter le bot sur un serveur",
                        { openUrl(context, "https://discord.com/oauth2/authorize?client_id=$appId&scope=bot%20applications.commands&permissions=8") },
                        Modifier.fillMaxWidth(),
                        Icons.Filled.PersonAdd,
                    )
                }
            }

            Panel(Modifier.fillMaxWidth()) {
                SectionLabel("Statut Discord")
                statuses.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { (value, label) ->
                            SquareChoice(label, settings.status == value, { onPresenceChange(settings.copy(status = value)) }, Modifier.weight(1f))
                        }
                    }
                }
                SectionLabel("Activité", Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SquareChoice("🎮 Joue à", settings.presenceMode == "playing", { onPresenceChange(settings.copy(presenceMode = "playing")) }, Modifier.weight(1f))
                    SquareChoice("📺 Stream", settings.presenceMode == "streaming", { onPresenceChange(settings.copy(presenceMode = "streaming")) }, Modifier.weight(1f))
                }
                PresencePreview(settings, runtime.botName)
            }

            Panel(Modifier.fillMaxWidth()) {
                SectionLabel("Arrière-plan")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Démarrer avec le téléphone", color = Palette.Text, fontWeight = FontWeight.Bold)
                        Hint("Relance le bot après un redémarrage.")
                    }
                    SquareToggle(settings.autoStart, onAutoStartChange)
                }
                if (batteryOk) {
                    Hint("✓ Optimisation de batterie désactivée : le bot reste en ligne écran éteint.", Palette.Green)
                } else {
                    Hint("L'optimisation de batterie peut couper le bot quand l'écran est éteint.", Palette.Yellow)
                    OutlineButton("Autoriser en arrière-plan", { requestIgnoreBattery(context) }, Modifier.fillMaxWidth(), Icons.Filled.BatteryAlert)
                }
            }

            Panel(Modifier.fillMaxWidth()) {
                SectionLabel("Créer ton bot")
                listOf(
                    "Sur le portail développeur : New Application.",
                    "Onglet Bot → Reset Token → colle-le ci-dessus.",
                    "Onglet Bot → active SERVER MEMBERS INTENT (bienvenue, autorôle, logs).",
                    "Lance le bot, puis « Inviter le bot sur un serveur ».",
                ).forEachIndexed { n, step ->
                    Row {
                        Text("${n + 1}", color = Palette.Orange, fontWeight = FontWeight.Black, fontSize = 14.sp, modifier = Modifier.width(22.dp))
                        Hint(step, Palette.Text)
                    }
                }
            }
        }
    }
}

private val TwitchPurple = Color(0xFF9146FF)

/** Aperçu du profil tel qu'il apparaît sur Discord. */
@Composable
private fun PresencePreview(settings: BotSettings, botName: String?) {
    val dotColor = when (settings.status) {
        "online" -> Palette.Green
        "idle" -> Palette.Yellow
        "dnd" -> Palette.Red
        else -> Palette.TextDim
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Black)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(if (settings.presenceMode == "streaming") TwitchPurple else dotColor))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(botName ?: "Ton bot", color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                if (settings.presenceMode == "streaming") "Stream ${Presence.NAME}" else "Joue à ${Presence.NAME}",
                color = Palette.TextDim,
                fontSize = 12.sp,
            )
        }
    }
}

/** Le début du token encode l'identifiant du bot (= celui de l'application). */
private fun applicationIdFromToken(token: String): String? = runCatching {
    val first = token.substringBefore('.').let { it + "=".repeat((4 - it.length % 4) % 4) }
    String(java.util.Base64.getDecoder().decode(first)).takeIf { id -> id.isNotEmpty() && id.all { it.isDigit() } }
}.getOrNull()

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun isIgnoringBattery(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
}.getOrDefault(false)

private fun requestIgnoreBattery(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
    }
}
