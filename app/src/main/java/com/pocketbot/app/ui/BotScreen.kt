package com.pocketbot.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketbot.app.bot.ConnState
import com.pocketbot.app.bot.RuntimeState
import com.pocketbot.app.data.BotSettings

@Composable
fun BotScreen(
    modifier: Modifier,
    settings: BotSettings,
    runtime: RuntimeState,
    running: Boolean,
    onSettingsChange: (BotSettings) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    var showToken by rememberSaveable { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(isIgnoringBattery(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryOk = isIgnoringBattery(context)
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("PocketBot", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Héberge ton bot Discord gratuitement, directement sur ton téléphone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(runtime)

        SectionCard("Token du bot") {
            OutlinedTextField(
                value = settings.token,
                onValueChange = { onSettingsChange(settings.copy(token = it.trim())) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !running,
                placeholder = { Text("Colle le token ici") },
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(
                            if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showToken) "Masquer" else "Afficher",
                        )
                    }
                },
            )
            Text(
                "Crée une application sur le portail développeur Discord, onglet « Bot » → « Reset Token ». " +
                    "Le token reste sur ton téléphone et n'est envoyé qu'à Discord.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { openUrl(context, "https://discord.com/developers/applications") }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Portail développeur Discord")
            }
        }

        if (running) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DndRed),
            ) {
                Icon(Icons.Filled.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text("Arrêter le bot")
            }
        } else {
            Button(
                onClick = onStart,
                enabled = settings.token.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OnlineGreen),
            ) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Mettre le bot en ligne")
            }
        }

        runtime.applicationId?.let { appId ->
            OutlinedButton(
                onClick = {
                    openUrl(
                        context,
                        "https://discord.com/oauth2/authorize?client_id=$appId" +
                            "&scope=bot%20applications.commands&permissions=$INVITE_PERMISSIONS",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PersonAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Inviter le bot sur un serveur")
            }
        }

        SectionCard("Fonctionnement en arrière-plan") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Démarrer avec le téléphone")
                    Text(
                        "Relance le bot automatiquement après un redémarrage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.autoStart, onCheckedChange = { onSettingsChange(settings.copy(autoStart = it)) })
            }
            if (!batteryOk) {
                Text(
                    "L'optimisation de batterie peut couper le bot quand l'écran est éteint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = IdleYellow,
                )
                OutlinedButton(onClick = { requestIgnoreBattery(context) }) {
                    Icon(Icons.Filled.BatteryAlert, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Autoriser en arrière-plan")
                }
            } else {
                Text(
                    "✓ Optimisation de batterie désactivée pour PocketBot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnlineGreen,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(runtime: RuntimeState) {
    val (color, label) = when (runtime.conn) {
        ConnState.ONLINE -> OnlineGreen to "En ligne"
        ConnState.CONNECTING -> IdleYellow to "Connexion…"
        ConnState.ERROR -> DndRed to "Erreur"
        ConnState.OFFLINE -> Color.Gray to "Hors ligne"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (runtime.botName != null && runtime.conn == ConnState.ONLINE) {
                Text("${runtime.botName} · ${runtime.guildCount} serveur(s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            runtime.error?.let { Text(it, color = DndRed, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** Voir, envoyer des messages, intégrer des liens, lire l'historique, ajouter des réactions. */
private const val INVITE_PERMISSIONS = 1024L + 2048L + 16384L + 65536L + 64L

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun isIgnoringBattery(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

private fun requestIgnoreBattery(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        )
    }
}
