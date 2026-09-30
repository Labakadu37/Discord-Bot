package com.image3d.app.ai

import android.content.Context
import android.graphics.Bitmap
import com.image3d.app.data.Library
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

data class GenerationSettings(
    val resolution: Int = 192,
    val smooth: Boolean = true,
    val quality: Quality = Quality.STANDARD,
)

/**
 * Image préparée (voir [ImagePrep.prepareForAI]) -> modèle 3D coloré, entièrement sur le téléphone :
 * TripoSR (encodeur : image -> triplan) -> aperçu 64³ -> grille de densité (décodeur)
 * -> surface nets -> nettoyage/lissage -> couleurs par sommet.
 */
class Generator(private val ctx: Context) {

    enum class Stage(val label: String) {
        ENCODE("L'IA imagine la forme en 3D"),
        PREVIEW("Aperçu 3D rapide"),
        DENSITY("Calcul du volume"),
        MESH("Construction du maillage"),
        COLOR("Mise en couleur"),
        SAVE("Enregistrement"),
    }

    class Progress(val stage: Stage, val fraction: Float?)

    /** Renvoie l'identifiant de la création enregistrée. */
    suspend fun generate(
        framed: Bitmap,
        s: GenerationSettings,
        onPreview: (com.image3d.app.mesh.Mesh) -> Unit,
        onProgress: (Progress) -> Unit,
    ): String {
        require(framed.width == ImagePrep.SIZE && framed.height == ImagePrep.SIZE)
        val started = System.currentTimeMillis()
        val out = Library.newDir(ctx)
        try {
            File(out, "input.png").outputStream().use { framed.compress(Bitmap.CompressFormat.PNG, 100, it) }
            currentCoroutineContext().ensureActive()

            // 1. Encodeur TripoSR (le gros morceau : quelques dizaines de secondes à quelques minutes)
            onProgress(Progress(Stage.ENCODE, null))
            val triplane = OnnxModel.openMapped(ModelStore.file(ctx, s.quality.encoderFile)).use { enc ->
                cancellable(enc) {
                    enc.run(mapOf("image" to (ImagePrep.toTensor(framed) to longArrayOf(1, 3, 512, 512))))[0]
                }
            }
            currentCoroutineContext().ensureActive()

            val mesh = OnnxModel.open(ModelStore.file(ctx, ModelStore.DECODER)).use { dec ->
                TriplaneMesher.build(dec, triplane, s.resolution, s.smooth, onPreview) { stage, f ->
                    currentCoroutineContext().ensureActive()
                    onProgress(Progress(stage, f))
                }
            }

            // 2. Enregistrement
            onProgress(Progress(Stage.SAVE, null))
            mesh.save(File(out, "mesh.bin"))
            val now = System.currentTimeMillis()
            val meta = JSONObject()
                .put("name", Library.defaultName(now))
                .put("createdAt", now)
                .put("vertices", mesh.vertexCount)
                .put("triangles", mesh.triangleCount)
                .put("resolution", s.resolution)
                .put("quality", s.quality.label)
                .put("seconds", ((now - started) / 1000).toInt())
            return Library.commit(out, meta)
        } catch (e: Throwable) {
            out.deleteRecursively()
            throw e
        }
    }

    /** Si la coroutine est annulée pendant un long calcul ONNX, on demande à ONNX Runtime de s'arrêter. */
    private suspend fun <T> cancellable(model: OnnxModel, block: () -> T): T = coroutineScope {
        val watcher = launch(Dispatchers.Default) {
            try { awaitCancellation() } finally { model.cancel() }
        }
        try { block() } finally { watcher.cancel() }
    }
}
