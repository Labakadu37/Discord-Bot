package com.monimage.launcher

import android.app.DownloadManager
import android.content.Context
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
    private const val KEY_LEGACY_DOWNLOAD = "model_download_id"

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

    fun isModelReady(context: Context): Boolean = modelFile(context).exists() && !ModelDownloadService.State.running

    /** L'ancienne version passait par le gestionnaire de téléchargements d'Android : on l'annule. */
    fun cancelLegacyDownload(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_LEGACY_DOWNLOAD, -1L)
        if (id < 0) return
        runCatching { context.getSystemService(DownloadManager::class.java).remove(id) }
        modelFile(context).delete()
        prefs.edit().remove(KEY_LEGACY_DOWNLOAD).apply()
    }

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
