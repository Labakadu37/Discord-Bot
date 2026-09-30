package com.image3d.app.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.image3d.app.Screen
import com.image3d.app.ai.GenerationSettings
import com.image3d.app.ai.ImagePrep
import com.image3d.app.ai.ModelStore
import com.image3d.app.ai.Quality
import com.image3d.app.work.JobState
import com.image3d.app.work.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val RESOLUTIONS = listOf(128 to "Rapide", 192 to "Normal", 256 to "Détaillé")

@Composable
fun CreateScreen(sharedImage: Uri?, back: () -> Unit, go: (Screen) -> Unit, replace: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by Jobs.state.collectAsState()
    val preparedFile = remember { File(ctx.cacheDir, "prepared.png") }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var prepared by remember { mutableStateOf<Bitmap?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var removeBg by rememberSaveable { mutableStateOf(true) }
    var smooth by rememberSaveable { mutableStateOf(true) }
    var resolution by rememberSaveable { mutableIntStateOf(192) }
    val ready = remember { ModelStore.readyQualities(ctx) }
    var quality by rememberSaveable { mutableStateOf(ModelStore.quality(ctx).takeIf { it in ready } ?: ready.firstOrNull() ?: Quality.STANDARD) }
    val livePreview by Jobs.preview.collectAsState()

    fun useImage(uri: Uri) {
        scope.launch {
            error = null
            runCatching { withContext(Dispatchers.IO) { ImagePrep.load(ctx, uri) } }
                .onSuccess { original = it; prepared = null; showOriginal = false }
                .onFailure { error = "Image illisible : ${it.message}" }
        }
    }

    // Aperçu : l'image exactement telle que l'IA la recevra (détourée, cadrée, fond gris)
    LaunchedEffect(original, removeBg) {
        val src = original ?: return@LaunchedEffect
        preparing = true
        error = null
        runCatching {
            withContext(Dispatchers.Default) {
                ImagePrep.prepareForAI(ctx, src, removeBg).also { bmp ->
                    preparedFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }.onSuccess { prepared = it }.onFailure { prepared = null; error = it.message ?: "Détourage impossible" }
        preparing = false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(::useImage) }
    val cameraFile = remember { File(ctx.cacheDir, "camera.jpg") }
    val cameraUri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", cameraFile) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) useImage(cameraUri) }

    LaunchedEffect(sharedImage) { sharedImage?.let(::useImage) }
    LaunchedEffect(Unit) {
        // Une génération déjà en cours : on réaffiche son image
        if (job is JobState.Generating && preparedFile.exists() && prepared == null) {
            prepared = withContext(Dispatchers.IO) { runCatching { ImagePrep.loadFile(preparedFile) }.getOrNull() }
        }
    }
    // N'ouvre la visionneuse que pour une génération suivie depuis cet écran
    var watching by remember { mutableStateOf(job is JobState.Generating) }
    LaunchedEffect(job) {
        val j = job
        if (j is JobState.Done && watching) {
            Jobs.acknowledge()
            replace(Screen.Viewer(j.creationId))
        }
    }

    val running = job is JobState.Generating
    Scaffold(topBar = { BackBar("Nouvelle création", back) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val live = livePreview
                val shown = if (showOriginal && !running) original else prepared ?: original
                when {
                    running && live != null -> {
                        LivePreview3D(live, Modifier.fillMaxSize())
                        Text(
                            "Aperçu 3D — la version détaillée arrive…",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.align(Alignment.TopCenter).padding(10.dp),
                        )
                    }
                    shown != null -> Image(shown.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else -> Text(
                        "Choisis une photo : un objet, un jouet, un personnage, un animal…\nUn seul sujet, bien visible et entier, donne les meilleurs résultats.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
                if (preparing) {
                    Column(
                        Modifier.fillMaxSize().background(Color(0x99000000)),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text("  Détourage…", Modifier.padding(top = 12.dp))
                    }
                }
            }
            if (!running && original != null && prepared != null) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !showOriginal, onClick = { showOriginal = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                        Text("Vu par l'IA")
                    }
                    SegmentedButton(selected = showOriginal, onClick = { showOriginal = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                        Text("Original")
                    }
                }
                Text(
                    "Vérifie que l'objet est bien découpé et entier : c'est exactement cette image que l'IA transforme en 3D.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!running) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.weight(1f)) {
                        Icon(Icons.Default.PhotoLibrary, null); Text("  Galerie")
                    }
                    FilledTonalButton(onClick = { camera.launch(cameraUri) }, Modifier.weight(1f)) {
                        Icon(Icons.Default.PhotoCamera, null); Text("  Photo")
                    }
                }

                Setting("Supprimer le fond automatiquement", "Désactive si le détourage coupe une partie de l'objet ou si le fond est déjà uni.") {
                    Switch(removeBg, { removeBg = it })
                }
                Setting("Lisser la surface", "Adoucit l'effet « escalier » du maillage.") {
                    Switch(smooth, { smooth = it })
                }
                Text("Niveau de détail", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    RESOLUTIONS.forEachIndexed { i, (r, label) ->
                        SegmentedButton(
                            selected = resolution == r,
                            onClick = { resolution = r },
                            shape = SegmentedButtonDefaults.itemShape(i, RESOLUTIONS.size),
                        ) { Text(label) }
                    }
                }
                Text(
                    "Plus de détail = maillage plus fin, mais calcul plus long (x3 à x8).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ready.size > 1) {
                    Text("Qualité de l'IA", style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ready.forEachIndexed { i, q ->
                            SegmentedButton(selected = quality == q, onClick = { quality = q }, shape = SegmentedButtonDefaults.itemShape(i, ready.size)) { Text(q.label) }
                        }
                    }
                }
                ramWarning(ctx, quality)?.let { Text(it, color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodySmall) }
                (error ?: (job as? JobState.Failed)?.message?.let { "Échec : $it" })?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = {
                        Jobs.acknowledge()
                        watching = true
                        ModelStore.setQuality(ctx, quality)
                        Jobs.generate(ctx, preparedFile, GenerationSettings(resolution, smooth, quality))
                    },
                    enabled = prepared != null && !preparing && ready.isNotEmpty() && !Jobs.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.AutoAwesome, null); Text("  Générer en 3D")
                }
            } else {
                JobProgress(job)
                OutlinedButton(onClick = { Jobs.cancel(ctx) }, Modifier.fillMaxWidth()) { Text("Annuler") }
            }
        }
    }
}

@Composable
private fun Setting(title: String, subtitle: String, control: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        control()
    }
}

private fun ramWarning(ctx: Context, q: Quality): String? {
    val info = ActivityManager.MemoryInfo()
    (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    val gb = info.totalMem / 1e9
    return if (gb < q.minRamGb) {
        "Ton téléphone a %.1f Go de RAM : la qualité « %s » peut manquer de mémoire. Ferme les autres applications ou choisis « Standard ».".format(gb, q.label)
    } else null
}
