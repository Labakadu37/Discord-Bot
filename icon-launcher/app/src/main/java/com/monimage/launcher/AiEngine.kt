package com.monimage.launcher

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File
import java.util.concurrent.Executors

/**
 * IA qui tourne dans le téléphone (Qwen 2.5 1.5B via MediaPipe), sans Internet une fois téléchargée.
 * Le modèle reste chargé tant que l'appli tourne (la bulle la garde en vie) : réouverture rapide.
 */
object AiEngine {

    const val MODEL_URL = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
        "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    private const val MODEL_FILE = "redsmile-ai.task"
    private const val MAX_TOKENS = 1280
    private const val REPLY_RESERVE = 400
    private const val KEY_DOWNLOAD = "model_download_id"

    private const val SYSTEM_PROMPT =
        "Tu es l'IA de RedSmile, un assistant personnel. Réponds en français, de façon naturelle et utile."

    enum class Role { USER, AI }
    data class Message(val role: Role, var text: String)

    /** Conversation en cours (gardée tant que l'appli est en mémoire). */
    val messages = mutableListOf<Message>()

    private val worker = Executors.newSingleThreadExecutor()
    private var llm: LlmInference? = null
    private var session: LlmInferenceSession? = null
    private var usedTokens = 0

    fun modelFile(context: Context) = File(context.getExternalFilesDir(null), MODEL_FILE)

    fun isModelReady(context: Context): Boolean = modelFile(context).let { it.exists() && downloadId(context) == null }

    // --- Téléchargement du modèle (1,6 Go) avec le gestionnaire de téléchargements d'Android ---

    fun startDownload(context: Context) {
        val dm = context.getSystemService(DownloadManager::class.java)
        modelFile(context).delete()
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("RedSmile IA")
            .setDescription("Téléchargement du modèle (1,6 Go)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, MODEL_FILE)
        prefs(context).edit().putLong(KEY_DOWNLOAD, dm.enqueue(request)).apply()
    }

    /** Progression 0..100, ou -1 si le téléchargement a échoué. Null s'il n'y a pas de téléchargement. */
    fun downloadProgress(context: Context): Int? {
        val id = downloadId(context) ?: return null
        val dm = context.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return clearDownload(context, failed = true)
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> clearDownload(context, failed = false)
                DownloadManager.STATUS_FAILED -> clearDownload(context, failed = true)
                else -> if (total > 0) (done * 100 / total).toInt() else 0
            }
        }
    }

    private fun clearDownload(context: Context, failed: Boolean): Int? {
        prefs(context).edit().remove(KEY_DOWNLOAD).apply()
        if (failed) modelFile(context).delete()
        return if (failed) -1 else null
    }

    private fun downloadId(context: Context): Long? =
        prefs(context).getLong(KEY_DOWNLOAD, -1L).takeIf { it >= 0 }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // --- Discussion ---

    /** Charge le modèle si besoin (quelques secondes la première fois). */
    fun load(context: Context, onReady: (Throwable?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val error = runCatching {
                if (llm == null) {
                    llm = LlmInference.createFromOptions(
                        app,
                        LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(modelFile(app).absolutePath)
                            .setMaxTokens(MAX_TOKENS)
                            .build()
                    )
                }
            }.exceptionOrNull()
            onReady(error)
        }
    }

    /** Envoie un message ; [onPartial] reçoit la réponse au fur et à mesure, [onDone] à la fin. */
    fun ask(text: String, onPartial: (String) -> Unit, onDone: (Throwable?) -> Unit) {
        worker.execute {
            runCatching {
                val model = llm ?: error("Modèle non chargé")
                var current = session
                val cost = current?.sizeInTokens(text) ?: 0
                // Mémoire courte du petit modèle : quand elle est pleine, on repart d'une page blanche
                if (current == null || usedTokens + cost + REPLY_RESERVE > MAX_TOKENS) {
                    current?.close()
                    current = LlmInferenceSession.createFromOptions(
                        model,
                        LlmInferenceSession.LlmInferenceSessionOptions.builder()
                            .setTemperature(0.8f)
                            .setTopK(40)
                            .build()
                    )
                    session = current
                    current.addQueryChunk("$SYSTEM_PROMPT\n\n")
                    usedTokens = current.sizeInTokens(SYSTEM_PROMPT)
                }
                current.addQueryChunk(text)
                val reply = StringBuilder()
                val answer = current.generateResponseAsync { partial, _ ->
                    reply.append(partial)
                    onPartial(reply.toString())
                }.get()
                usedTokens += current.sizeInTokens(text) + current.sizeInTokens(answer)
            }.let { onDone(it.exceptionOrNull()) }
        }
    }

    fun newConversation() {
        messages.clear()
        worker.execute {
            session?.close()
            session = null
            usedTokens = 0
        }
    }
}
