package com.pocketbot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketbot.app.bot.DiscordGateway
import com.pocketbot.app.data.BotCommand
import com.pocketbot.app.data.CommandType

@Composable
fun CommandsScreen(
    modifier: Modifier,
    commands: List<BotCommand>,
    onChange: (List<BotCommand>) -> Unit,
) {
    var editing by remember { mutableStateOf<BotCommand?>(null) }
    var toDelete by remember { mutableStateOf<BotCommand?>(null) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Commandes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Variables : {user} {username} {mention} {server} {channel} {args}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (commands.isEmpty()) {
                item {
                    Text(
                        "Aucune commande. Appuie sur « Ajouter » pour en créer une.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            items(commands, key = { it.id }) { cmd ->
                Card(
                    onClick = { editing = cmd },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (cmd.type == CommandType.SLASH) "/${cmd.name}" else cmd.name,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            Text(
                                cmd.response,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { toDelete = cmd }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                        }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { editing = BotCommand() },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Ajouter") },
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }

    editing?.let { cmd ->
        CommandEditor(
            initial = cmd,
            others = commands.filter { it.id != cmd.id },
            onDismiss = { editing = null },
            onSave = { saved ->
                val exists = commands.any { it.id == saved.id }
                onChange(if (exists) commands.map { if (it.id == saved.id) saved else it } else commands + saved)
                editing = null
            },
        )
    }

    toDelete?.let { cmd ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer la commande ?") },
            text = { Text(cmd.name) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(commands.filter { it.id != cmd.id })
                    toDelete = null
                }) { Text("Supprimer", color = DndRed) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun CommandEditor(
    initial: BotCommand,
    others: List<BotCommand>,
    onDismiss: () -> Unit,
    onSave: (BotCommand) -> Unit,
) {
    var cmd by remember { mutableStateOf(initial) }
    val slash = cmd.type == CommandType.SLASH

    val nameError = when {
        cmd.name.isBlank() -> null
        slash && !DiscordGateway.SLASH_NAME.matches(cmd.name) -> "Minuscules, chiffres, - et _ uniquement (32 max)"
        others.any { it.type == cmd.type && it.name.equals(cmd.name, ignoreCase = true) } -> "Cette commande existe déjà"
        else -> null
    }
    val valid = cmd.name.isNotBlank() && cmd.response.isNotBlank() && nameError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "Nouvelle commande" else "Modifier la commande") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = slash,
                        onClick = { cmd = cmd.copy(type = CommandType.SLASH, name = cmd.name.trimStart('!', '.', '?', '$')) },
                        label = { Text("Slash /") },
                    )
                    FilterChip(
                        selected = !slash,
                        onClick = { cmd = cmd.copy(type = CommandType.PREFIX) },
                        label = { Text("Préfixe !") },
                    )
                }
                Text(
                    if (slash) {
                        "Commande « / » native de Discord. Aucune option spéciale à activer."
                    } else {
                        "Répond quand un message commence par ce texte. Nécessite « MESSAGE CONTENT INTENT » " +
                            "activé dans le portail développeur."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = cmd.name,
                    onValueChange = {
                        val v = if (slash) it.lowercase().replace(' ', '-') else it.trimStart()
                        cmd = cmd.copy(name = v)
                    },
                    label = { Text(if (slash) "Nom (ex. ping)" else "Déclencheur (ex. !ping)") },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (slash) {
                    OutlinedTextField(
                        value = cmd.description,
                        onValueChange = { cmd = cmd.copy(description = it.take(100)) },
                        label = { Text("Description") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = cmd.response,
                    onValueChange = { cmd = cmd.copy(response = it.take(2000)) },
                    label = { Text("Réponse du bot") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (slash) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = cmd.ephemeral, onCheckedChange = { cmd = cmd.copy(ephemeral = it) })
                        Text("Visible seulement par l'utilisateur")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(cmd.copy(name = cmd.name.trim())) }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
