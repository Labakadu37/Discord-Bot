package com.pocketbot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketbot.app.data.BotSettings

private val statuses = listOf(
    "online" to "🟢 En ligne",
    "idle" to "🌙 Inactif",
    "dnd" to "⛔ Ne pas déranger",
    "invisible" to "⚫ Invisible",
)

private val activityTypes = listOf(
    0 to "Joue à",
    2 to "Écoute",
    3 to "Regarde",
    5 to "Participe à",
    4 to "Statut perso",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier,
    settings: BotSettings,
    running: Boolean,
    onApplyPresence: (BotSettings) -> Unit,
    onApplyWelcome: (BotSettings) -> Unit,
) {
    // Brouillons locaux : rien n'est envoyé tant qu'on n'appuie pas sur « Appliquer ».
    var presence by remember(settings) { mutableStateOf(settings) }
    var welcome by remember(settings) { mutableStateOf(settings) }
    val applyLabel = if (running) "Appliquer" else "Enregistrer"

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Réglages", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        SectionCard("Statut et activité") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                statuses.forEach { (value, label) ->
                    FilterChip(
                        selected = presence.status == value,
                        onClick = { presence = presence.copy(status = value) },
                        label = { Text(label) },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                activityTypes.forEach { (value, label) ->
                    FilterChip(
                        selected = presence.activityType == value,
                        onClick = { presence = presence.copy(activityType = value) },
                        label = { Text(label) },
                    )
                }
            }
            OutlinedTextField(
                value = presence.activityText,
                onValueChange = { presence = presence.copy(activityText = it.take(128)) },
                label = { Text("Texte de l'activité (vide = aucune)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    onApplyPresence(
                        settings.copy(
                            status = presence.status,
                            activityType = presence.activityType,
                            activityText = presence.activityText,
                        )
                    )
                },
            ) { Text(applyLabel) }
        }

        SectionCard("Message de bienvenue") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Accueillir les nouveaux membres", Modifier.weight(1f))
                Switch(checked = welcome.welcomeEnabled, onCheckedChange = { welcome = welcome.copy(welcomeEnabled = it) })
            }
            Text(
                "Nécessite « SERVER MEMBERS INTENT » activé dans le portail développeur. " +
                    "Pour l'ID du salon : active le mode développeur dans Discord, puis appui long sur le salon → Copier l'identifiant.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = welcome.welcomeChannelId,
                onValueChange = { v -> welcome = welcome.copy(welcomeChannelId = v.filter { it.isDigit() }) },
                label = { Text("ID du salon") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = welcome.welcomeMessage,
                onValueChange = { welcome = welcome.copy(welcomeMessage = it.take(2000)) },
                label = { Text("Message") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !welcome.welcomeEnabled || welcome.welcomeChannelId.isNotBlank(),
                onClick = {
                    onApplyWelcome(
                        settings.copy(
                            welcomeEnabled = welcome.welcomeEnabled,
                            welcomeChannelId = welcome.welcomeChannelId,
                            welcomeMessage = welcome.welcomeMessage,
                        )
                    )
                },
            ) { Text(applyLabel) }
        }
    }
}
