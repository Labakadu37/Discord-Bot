package com.image3d.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.image3d.app.ai.ModelStore
import com.image3d.app.ai.Quality
import com.image3d.app.work.JobState
import com.image3d.app.work.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ModelsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by Jobs.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val ready = remember(job, refresh) { ModelStore.readyQualities(ctx) }
    val used = remember(job, refresh) { ModelStore.installedBytes(ctx) }
    var url by remember { mutableStateOf(ModelStore.baseUrl(ctx)) }
    var advanced by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val names = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { ModelStore.import(ctx, it) }.getOrNull() } }
            refresh++
            Toast.makeText(
                ctx,
                if (names.isEmpty()) "Aucun fichier reconnu (noms attendus : voir la liste)" else "Importé : ${names.joinToString()}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    LaunchedEffect(job) { if (job is JobState.DownloadDone) { Jobs.acknowledge(); refresh++ } }

    Scaffold(topBar = { BackBar("Modèles d'IA", back) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "L'IA (TripoSR + détourage U²-Net) est téléchargée une seule fois puis fonctionne entièrement hors ligne, " +
                    "sans compte ni limite. Espace utilisé : ${formatBytes(used)}.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (job is JobState.Downloading) {
                JobProgress(job)
                OutlinedButton(onClick = { Jobs.cancel(ctx) }, Modifier.fillMaxWidth()) {
                    Text("Mettre en pause (reprendra où il s'est arrêté)")
                }
            }
            (job as? JobState.Failed)?.let { Text("Erreur : ${it.message}", color = MaterialTheme.colorScheme.error) }

            Quality.entries.forEach { q ->
                val installed = q in ready
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(q.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (installed) Icon(Icons.Default.CheckCircle, "Installé", tint = MaterialTheme.colorScheme.secondary)
                        }
                        Text(q.description, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "≈ ${formatBytes(q.approxBytes + 5_000_000)} · conseillé : ${q.minRamGb} Go de RAM ou plus",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (installed) {
                            TextButton(onClick = { ModelStore.delete(ctx, q); refresh++ }, enabled = !Jobs.busy) { Text("Supprimer") }
                        } else {
                            Button(onClick = { Jobs.acknowledge(); Jobs.download(ctx, q) }, enabled = !Jobs.busy) {
                                Icon(Icons.Default.Download, null); Text("  Télécharger")
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Masquer les options avancées" else "Options avancées") }
            if (advanced) {
                Text("Source des fichiers", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(url, { url = it }, label = { Text("Adresse (dossier contenant manifest.json)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ModelStore.setBaseUrl(ctx, url); url = ModelStore.baseUrl(ctx) }) { Text("Enregistrer") }
                    TextButton(onClick = { ModelStore.setBaseUrl(ctx, ""); url = ModelStore.baseUrl(ctx) }) { Text("Par défaut") }
                }
                Text("Importer depuis le téléphone", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Si tu as déjà les fichiers (copiés depuis un PC, une clé USB…), sélectionne-les : " +
                        (Quality.entries.map { it.encoderFile } + listOf(ModelStore.DECODER, ModelStore.U2NET)).joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !Jobs.busy) {
                    Icon(Icons.Default.FolderOpen, null); Text("  Choisir les fichiers")
                }
            }
        }
    }
}
