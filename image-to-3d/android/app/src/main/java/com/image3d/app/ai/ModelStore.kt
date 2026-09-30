package com.image3d.app.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.image3d.app.BuildConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Qualité de l'encodeur TripoSR (le décodeur et le détourage sont communs). */
enum class Quality(val encoderFile: String, val label: String, val description: String, val approxBytes: Long, val minRamGb: Int) {
    STANDARD("triposr_encoder_int8.ort", "Standard", "IA compressée (int8) : résultat quasi identique, 4x plus légère.", 455_000_000, 4),
    MAX("triposr_encoder.ort", "Précision maximale", "IA complète (float32) : un peu plus fidèle, plus lente et gourmande.", 1_680_000_000, 6),
}

/**
 * Fichiers de l'IA : téléchargés une seule fois, ensuite tout fonctionne hors ligne.
 * Le téléchargement reprend là où il s'est arrêté et vérifie l'empreinte SHA-256 de chaque fichier.
 */
object ModelStore {
    const val DECODER = "triposr_decoder.onnx"
    const val U2NET = "u2netp.onnx"
    private const val PREFS = "models"

    fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, name: String) = File(dir(ctx), name)

    fun required(q: Quality) = listOf(q.encoderFile, DECODER, U2NET)
    fun isReady(ctx: Context, q: Quality) = required(q).all { file(ctx, it).exists() }
    fun readyQualities(ctx: Context) = Quality.entries.filter { isReady(ctx, it) }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun baseUrl(ctx: Context): String = prefs(ctx).getString("url", null) ?: BuildConfig.MODELS_URL
    fun setBaseUrl(ctx: Context, url: String) {
        val clean = url.trim().let { if (it.isEmpty() || it.endsWith("/")) it else "$it/" }
        prefs(ctx).edit().apply { if (clean.isEmpty()) remove("url") else putString("url", clean) }.apply()
    }

    fun quality(ctx: Context): Quality =
        runCatching { Quality.valueOf(prefs(ctx).getString("quality", null)!!) }.getOrNull()
            ?: readyQualities(ctx).firstOrNull() ?: Quality.STANDARD

    fun setQuality(ctx: Context, q: Quality) = prefs(ctx).edit().putString("quality", q.name).apply()

    fun installedBytes(ctx: Context) = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    fun delete(ctx: Context, q: Quality) {
        file(ctx, q.encoderFile).delete()
        File(dir(ctx), q.encoderFile + ".part").delete()
        if (readyQualities(ctx).isEmpty()) { file(ctx, DECODER).delete(); file(ctx, U2NET).delete() }
    }

    class Progress(val file: String, val index: Int, val count: Int, val done: Long, val total: Long, val verifying: Boolean = false)

    suspend fun download(ctx: Context, q: Quality, onProgress: (Progress) -> Unit) {
        val base = baseUrl(ctx)
        val manifest = runCatching { JSONObject(httpText(base + "manifest.json")).getJSONObject("files") }
            .getOrElse { throw IOException("Impossible de lire ${base}manifest.json : ${it.message}", it) }
        val missing = required(q).filter { !file(ctx, it).exists() }
        for ((i, name) in missing.withIndex()) {
            val info = manifest.optJSONObject(name) ?: throw IOException("$name absent du manifeste")
            val size = info.getLong("size")
            val sha = info.getString("sha256")
            val part = File(dir(ctx), "$name.part")
            if (part.length() > size) part.delete()
            var attempt = 0
            while (part.length() < size) {
                val before = part.length()
                try {
                    fetch(base + name, part, size) { onProgress(Progress(name, i, missing.size, it, size)) }
                    if (part.length() <= before && ++attempt >= 5) throw IOException("Le téléchargement de $name n'avance plus")
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (++attempt >= 5) throw e
                    kotlinx.coroutines.delay(2000L * attempt) // reprise automatique après une coupure réseau
                }
            }
            onProgress(Progress(name, i, missing.size, size, size, verifying = true))
            if (sha256(part) != sha) {
                part.delete()
                throw IOException("$name est corrompu (empreinte SHA-256 différente), relance le téléchargement")
            }
            if (!part.renameTo(file(ctx, name))) throw IOException("Impossible d'enregistrer $name")
        }
    }

    private suspend fun fetch(url: String, part: File, size: Long, onBytes: (Long) -> Unit) {
        val start = part.length()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        if (start > 0) conn.setRequestProperty("Range", "bytes=$start-")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code pour $url")
            val append = start > 0 && code == HttpURLConnection.HTTP_PARTIAL
            var done = if (append) start else 0L
            java.io.FileOutputStream(part, append).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > (1 shl 20) || done == size) { onBytes(done); lastReport = done }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Import manuel (fichiers copiés sur le téléphone depuis un PC par exemple). Renvoie le nom reconnu. */
    fun import(ctx: Context, uri: Uri): String? {
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return null
        val known = Quality.entries.map { it.encoderFile } + listOf(DECODER, U2NET)
        if (name !in known) return null
        val tmp = File(dir(ctx), "$name.import")
        ctx.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            ?: return null
        tmp.renameTo(file(ctx, name))
        return name
    }
}
