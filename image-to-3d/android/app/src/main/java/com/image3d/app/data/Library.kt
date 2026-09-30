package com.image3d.app.data

import android.content.Context
import com.image3d.app.mesh.Mesh
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Une création enregistrée : maillage + image d'origine + infos. */
data class Creation(
    val id: String,
    val dir: File,
    val name: String,
    val createdAt: Long,
    val vertices: Int,
    val triangles: Int,
    val resolution: Int,
    val quality: String,
    val seconds: Int,
) {
    val meshFile get() = File(dir, "mesh.bin")
    val inputFile get() = File(dir, "input.png")
    val thumbFile get() = File(dir, "thumb.png")
    val displayThumb get() = if (thumbFile.exists()) thumbFile else inputFile
    fun loadMesh() = Mesh.load(meshFile)
}

object Library {
    fun root(ctx: Context) = File(ctx.filesDir, "creations").apply { mkdirs() }

    fun list(ctx: Context): List<Creation> =
        root(ctx).listFiles()?.mapNotNull { read(it) }?.sortedByDescending { it.createdAt } ?: emptyList()

    fun get(ctx: Context, id: String): Creation? = read(File(root(ctx), id))

    fun newDir(ctx: Context): File {
        val id = "c" + System.currentTimeMillis()
        return File(root(ctx), "$id.tmp").apply { mkdirs() }
    }

    fun commit(tmp: File, meta: JSONObject): String {
        File(tmp, "meta.json").writeText(meta.toString())
        val final = File(tmp.parentFile, tmp.name.removeSuffix(".tmp"))
        tmp.renameTo(final)
        return final.name
    }

    fun defaultName(time: Long): String =
        "Création du " + SimpleDateFormat("d MMM à HH:mm", Locale.FRANCE).format(Date(time))

    fun rename(c: Creation, name: String) {
        val f = File(c.dir, "meta.json")
        val j = JSONObject(f.readText())
        j.put("name", name.trim().ifEmpty { c.name })
        f.writeText(j.toString())
    }

    fun delete(c: Creation) = c.dir.deleteRecursively()

    /** Nettoie les générations interrompues. */
    fun cleanup(ctx: Context) {
        root(ctx).listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.deleteRecursively() }
    }

    private fun read(dir: File): Creation? {
        if (dir.name.endsWith(".tmp")) return null
        val f = File(dir, "meta.json")
        if (!f.exists() || !File(dir, "mesh.bin").exists()) return null
        return runCatching {
            val j = JSONObject(f.readText())
            Creation(
                id = dir.name,
                dir = dir,
                name = j.getString("name"),
                createdAt = j.getLong("createdAt"),
                vertices = j.optInt("vertices"),
                triangles = j.optInt("triangles"),
                resolution = j.optInt("resolution"),
                quality = j.optString("quality"),
                seconds = j.optInt("seconds"),
            )
        }.getOrNull()
    }
}
