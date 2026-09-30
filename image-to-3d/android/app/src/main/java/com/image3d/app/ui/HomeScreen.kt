package com.image3d.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.image3d.app.Screen
import com.image3d.app.ai.ModelStore
import com.image3d.app.data.Library
import com.image3d.app.work.JobState
import com.image3d.app.work.Jobs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val job by Jobs.state.collectAsState()
    // Relit la bibliothèque quand une tâche se termine
    val creations = remember(job) { Library.list(ctx) }
    val ready = remember(job) { ModelStore.readyQualities(ctx).isNotEmpty() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Image 3D") },
                actions = {
                    IconButton(onClick = { go(Screen.Models) }) { Icon(Icons.Default.Memory, "Modèles d'IA") }
                    IconButton(onClick = { go(Screen.Info) }) { Icon(Icons.Default.Info, "Infos") }
                },
            )
        },
        floatingActionButton = {
            if (ready) {
                ExtendedFloatingActionButton(
                    onClick = { go(Screen.Create()) },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Nouvelle création") },
                )
            }
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(16.dp, pad.calculateTopPadding(), 16.dp, 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (job is JobState.Generating || job is JobState.Downloading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.clickable { if (job is JobState.Generating) go(Screen.Create()) else go(Screen.Models) }) {
                        JobProgress(job)
                    }
                }
            }
            (job as? JobState.Done)?.let { done ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        onClick = { Jobs.acknowledge(); go(Screen.Viewer(done.creationId)) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Text("Ton modèle 3D est prêt ! Appuie pour le voir.", Modifier.padding(16.dp))
                    }
                }
            }
            if (!ready) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Bienvenue !", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Transforme n'importe quelle photo en modèle 3D coloré et animé. " +
                                    "L'IA tourne entièrement sur ton téléphone : sans compte, sans serveur, sans limite.\n\n" +
                                    "Il faut d'abord télécharger l'IA une seule fois (environ 460 Mo). Ensuite, tout marche hors ligne.",
                            )
                            Button(onClick = { go(Screen.Models) }) {
                                Icon(Icons.Default.Download, null)
                                Text("  Installer l'IA")
                            }
                        }
                    }
                }
            }
            if (creations.isEmpty() && ready) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ViewInAr, null, Modifier.height(64.dp).aspectRatio(1f), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("Aucune création pour l'instant", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Appuie sur « Nouvelle création » et choisis une photo d'objet, de personnage, d'animal…",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
            items(creations, key = { it.id }) { c ->
                Card(
                    onClick = { go(Screen.Viewer(c.id)) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    FileImage(c.displayThumb, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)), key = c.thumbFile.lastModified())
                    Column(Modifier.padding(10.dp)) {
                        Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text("${c.triangles / 1000}k triangles", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
