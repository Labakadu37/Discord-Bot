package com.image3d.app.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.image3d.app.anim.Rig
import com.image3d.app.data.Creation
import com.image3d.app.mesh.Mesh
import java.io.File
import java.io.OutputStream

enum class ExportFormat(val ext: String, val label: String, val description: String) {
    GLB("glb", "GLB animé", "Couleurs + squelette + 9 animations (Blender, Unity, Godot…)"),
    OBJ("obj", "OBJ", "Maillage + couleurs par sommet (tous les logiciels 3D)"),
    STL("stl", "STL", "Pour l'impression 3D (sans couleurs)"),
}

object ExportFiles {
    private fun safeName(c: Creation) = c.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "modele" }

    private fun write(format: ExportFormat, mesh: Mesh, name: String, out: OutputStream) = when (format) {
        ExportFormat.GLB -> GlbWriter.write(mesh, Rig.build(mesh), name, out)
        ExportFormat.OBJ -> ObjWriter.write(mesh, out)
        ExportFormat.STL -> StlWriter.write(mesh, out)
    }

    /** Enregistre dans Téléchargements/Image3D. Renvoie le chemin affichable. */
    fun saveToDownloads(ctx: Context, c: Creation, format: ExportFormat): String {
        val mesh = c.loadMesh()
        val fileName = "${safeName(c)}.${format.ext}"
        val dir = Environment.DIRECTORY_DOWNLOADS + "/Image3D"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, dir)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Impossible de créer le fichier")
        try {
            resolver.openOutputStream(uri)!!.buffered(1 shl 16).use { write(format, mesh, c.name, it) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "$dir/$fileName"
    }

    /** Partage le fichier (Drive, Discord, e-mail, Bluetooth…). */
    fun share(ctx: Context, c: Creation, format: ExportFormat) {
        val dir = File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "${safeName(c)}.${format.ext}")
        file.outputStream().buffered(1 shl 16).use { write(format, c.loadMesh(), c.name, it) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Partager le modèle 3D").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
