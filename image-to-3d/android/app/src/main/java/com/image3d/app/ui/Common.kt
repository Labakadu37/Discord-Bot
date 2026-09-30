package com.image3d.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.image3d.app.work.JobState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
        actions = { actions() },
    )
}

@Composable
fun FileImage(file: File, modifier: Modifier = Modifier, key: Any? = null) {
    var bmp by remember(file, key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file, key) {
        bmp = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.2f Go".format(b / 1e9)
    b >= 1_000_000 -> "%.0f Mo".format(b / 1e6)
    else -> "%.0f Ko".format(b / 1e3)
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 60) "${s / 60} min ${"%02d".format(s % 60)} s" else "$s s"
}

/** Carte de progression de la tâche en cours (téléchargement ou génération). */
@Composable
fun JobProgress(state: JobState, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state is JobState.Generating) {
        while (true) { now = System.currentTimeMillis(); delay(500) }
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            is JobState.Downloading -> {
                val p = state.progress
                Text(if (p.verifying) "Vérification de ${p.file}…" else "Téléchargement ${p.index + 1}/${p.count} : ${p.file}", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                Text("${formatBytes(p.done)} / ${formatBytes(p.total)}", style = MaterialTheme.typography.bodySmall)
            }
            is JobState.Generating -> {
                Text(state.stage.label + "…", style = MaterialTheme.typography.titleSmall)
                val f = state.fraction
                if (f == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Temps écoulé : ${formatDuration(now - state.startedAt)}", style = MaterialTheme.typography.bodySmall)
                    Text("Étape ${state.stage.ordinal + 1}/6", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Tu peux quitter l'application : le calcul continue et une notification te prévient.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Spacer(Modifier.height(0.dp))
        }
    }
}
