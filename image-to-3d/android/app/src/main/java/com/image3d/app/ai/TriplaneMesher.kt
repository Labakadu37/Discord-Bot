package com.image3d.app.ai

import com.image3d.app.mesh.Mesh
import com.image3d.app.mesh.MeshOps
import com.image3d.app.mesh.SurfaceNets

/**
 * Triplan TripoSR -> maillage coloré (sans dépendance Android, testé sur PC).
 * Grille de densité res³ via le décodeur, surface nets, nettoyage, lissage, couleurs.
 */
object TriplaneMesher {
    const val RADIUS = 0.87f
    const val THRESHOLD = 25f
    /** Résolution de l'aperçu 3D rapide (~1 % du calcul d'un modèle 256³). */
    const val PREVIEW_RES = 64
    private const val CHUNK = 32768
    private val TRIPLANE_SHAPE = longArrayOf(3, 40, 64, 64)

    /**
     * [onPreview] reçoit d'abord un modèle grossier (64³) pour l'afficher pendant le calcul complet.
     * [onProgress] est appelé régulièrement ; il peut lever une exception pour annuler.
     */
    suspend fun build(
        decoder: OnnxModel,
        triplane: FloatArray,
        res: Int,
        smooth: Boolean,
        onPreview: ((Mesh) -> Unit)? = null,
        onProgress: suspend (Generator.Stage, Float?) -> Unit,
    ): Mesh {
        fun query(points: FloatArray, count: Int): List<FloatArray> = decoder.run(
            mapOf("triplane" to (triplane to TRIPLANE_SHAPE), "positions" to (points to longArrayOf(count.toLong(), 3))),
        )
        if (onPreview != null && res > PREVIEW_RES) {
            onProgress(Generator.Stage.PREVIEW, null)
            // Un aperçu vide n'empêche pas le calcul complet
            runCatching { extract(::query, PREVIEW_RES, smooth = true, report = false, onProgress) }
                .onSuccess(onPreview)
                .onFailure { if (it !is IllegalStateException) throw it }
        }
        return extract(::query, res, smooth, report = true, onProgress)
    }

    private suspend fun extract(
        query: (FloatArray, Int) -> List<FloatArray>,
        res: Int,
        smooth: Boolean,
        report: Boolean,
        onProgress: suspend (Generator.Stage, Float?) -> Unit,
    ): Mesh {
        suspend fun progress(stage: Generator.Stage, f: Float?) {
            if (report) onProgress(stage, f) else onProgress(Generator.Stage.PREVIEW, null)
        }

        // Grille de densité (repère TripoSR), indexée (i * res + j) * res + k
        val total = res * res * res
        val field = FloatArray(total)
        val step = 2 * RADIUS / (res - 1)
        val pts = FloatArray(CHUNK * 3)
        var start = 0
        while (start < total) {
            val n = minOf(CHUNK, total - start)
            val buf = if (n == CHUNK) pts else FloatArray(n * 3)
            for (q in 0 until n) {
                val p = start + q
                buf[q * 3] = -RADIUS + (p / (res * res)) * step
                buf[q * 3 + 1] = -RADIUS + (p / res % res) * step
                buf[q * 3 + 2] = -RADIUS + (p % res) * step
            }
            query(buf, n)[0].copyInto(field, start)
            start += n
            progress(Generator.Stage.DENSITY, start.toFloat() / total)
        }

        progress(Generator.Stage.MESH, 0f)
        val raw = SurfaceNets.extract(field, res, THRESHOLD, -RADIUS, step)
        if (raw.indices.isEmpty()) {
            throw IllegalStateException("L'IA n'a trouvé aucune forme : essaie une image où l'objet est bien visible et centré")
        }
        val clean = MeshOps.keepMainComponents(raw.positions, raw.indices)
        val positions = clean.positions
        if (smooth) MeshOps.taubinSmooth(positions, clean.indices, 2)
        progress(Generator.Stage.MESH, 1f)

        // Couleurs : le décodeur donne la couleur en chaque point de l'espace
        val nv = positions.size / 3
        val colors = FloatArray(nv * 3)
        var v = 0
        while (v < nv) {
            val n = minOf(CHUNK, nv - v)
            query(positions.copyOfRange(v * 3, (v + n) * 3), n)[1].copyInto(colors, v * 3)
            v += n
            progress(Generator.Stage.COLOR, v.toFloat() / nv)
        }
        MeshOps.toYUpOnGround(positions)
        return Mesh(positions, MeshOps.vertexNormals(positions, clean.indices), colors, clean.indices)
    }
}
