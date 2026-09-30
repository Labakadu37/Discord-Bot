package com.image3d.app.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.image3d.app.anim.Animations
import com.image3d.app.anim.Rig
import com.image3d.app.data.Library
import com.image3d.app.export.ExportFiles
import com.image3d.app.export.ExportFormat
import com.image3d.app.mesh.Mesh
import com.image3d.app.render.ModelView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ViewerScreen(id: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var creation by remember { mutableStateOf(Library.get(ctx, id)) }
    val c = creation
    if (c == null) {
        LaunchedEffect(Unit) { back() }
        return
    }
    var loaded by remember(id) { mutableStateOf<Pair<Mesh, Rig>?>(null) }
    LaunchedEffect(id) {
        loaded = withContext(Dispatchers.IO) { c.loadMesh().let { it to Rig.build(it) } }
    }
    var animation by remember { mutableStateOf(Animations.byId("idle")) }
    var speed by remember { mutableFloatStateOf(1f) }
    var showExport by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<ModelView?>(null) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, view) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Scaffold(
        topBar = {
            BackBar(c.name, back) {
                IconButton(onClick = { showRename = true }) { Icon(Icons.Default.Edit, "Renommer") }
                IconButton(onClick = { showDelete = true }) { Icon(Icons.Default.Delete, "Supprimer") }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val data = loaded
                if (data == null) CircularProgressIndicator()
                else AndroidView(
                    factory = { context ->
                        ModelView(context).also { v ->
                            v.setMesh(data.first, data.second)
                            view = v
                            // Vignette 3D pour la bibliothèque (une seule fois)
                            if (!c.thumbFile.exists()) {
                                v.postDelayed({
                                    v.capture(384) { bmp ->
                                        runCatching { c.thumbFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) } }
                                    }
                                }, 1200)
                            }
                        }
                    },
                    update = { v ->
                        v.setAnimation(animation)
                        v.setSpeed(speed)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    "${c.vertices / 1000}k sommets · ${c.triangles / 1000}k triangles · ${c.resolution}³ · ${formatDuration(c.seconds * 1000L)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                )
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(Animations.ALL) { a ->
                            FilterChip(selected = a === animation, onClick = { animation = a }, label = { Text(a.label) })
                        }
                    }
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Vitesse", style = MaterialTheme.typography.bodySmall)
                        Slider(speed, { speed = it }, valueRange = 0.25f..2.5f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        Text("x%.1f".format(speed), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(36.dp))
                    }
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Button(onClick = { showExport = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.FileDownload, null); Text("  Exporter")
                        }
                        androidx.compose.material3.FilledTonalButton(
                            onClick = {
                                busy = true
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { ExportFiles.share(ctx, c, ExportFormat.GLB) } }
                                        .onFailure { Toast.makeText(ctx, "Erreur : ${it.message}", Toast.LENGTH_LONG).show() }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Default.Share, null); Text("  Partager") }
                    }
                }
            }
        }
    }

    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("Exporter dans Téléchargements") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ExportFormat.entries.forEach { f ->
                        TextButton(onClick = {
                            showExport = false
                            busy = true
                            scope.launch {
                                val r = runCatching { withContext(Dispatchers.IO) { ExportFiles.saveToDownloads(ctx, c, f) } }
                                busy = false
                                Toast.makeText(
                                    ctx,
                                    r.fold({ "Enregistré : $it" }, { "Erreur : ${it.message}" }),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(f.label, style = MaterialTheme.typography.titleSmall)
                                Text(f.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExport = false }) { Text("Fermer") } },
        )
    }

    if (showRename) {
        var name by remember { mutableStateOf(c.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Renommer") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    Library.rename(c, name)
                    creation = Library.get(ctx, id)
                    showRename = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Annuler") } },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Supprimer cette création ?") },
            text = { Text("Le modèle 3D sera effacé du téléphone (les fichiers déjà exportés sont conservés).") },
            confirmButton = {
                TextButton(onClick = { Library.delete(c); showDelete = false; back() }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Annuler") } },
        )
    }
}
