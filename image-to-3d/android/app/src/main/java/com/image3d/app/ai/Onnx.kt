package com.image3d.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Session ONNX Runtime 100 % locale (processeur du téléphone). */
class OnnxModel private constructor(
    val session: OrtSession,
    @Suppress("unused") private val mapped: MappedByteBuffer?, // doit rester vivant tant que la session l'utilise
) : Closeable {
    private var runOptions: OrtSession.RunOptions? = null

    fun run(inputs: Map<String, Pair<FloatArray, LongArray>>): List<FloatArray> {
        val tensors = inputs.mapValues { (_, v) -> OnnxTensor.createTensor(env, FloatBuffer.wrap(v.first), v.second) }
        val opts = OrtSession.RunOptions()
        runOptions = opts
        try {
            session.run(tensors, opts).use { res ->
                return (0 until res.size()).map { i ->
                    val fb = (res.get(i) as OnnxTensor).floatBuffer
                    FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }
        } finally {
            runOptions = null
            opts.close()
            tensors.values.forEach { it.close() }
        }
    }

    /** Interrompt le calcul en cours (bouton Annuler). */
    fun cancel() {
        runCatching { runOptions?.setTerminate(true) }
    }

    override fun close() = session.close()

    companion object {
        val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

        private fun options() = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Évite de dupliquer les poids en mémoire (important pour les gros modèles)
            addConfigEntry("session.disable_prepacking", "1")
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        }

        fun open(file: File): OnnxModel = OnnxModel(env.createSession(file.absolutePath, options()), null)

        /**
         * Modèle au format .ort : les poids sont lus directement dans le fichier mappé en mémoire
         * (pas de copie), ce qui divise presque par deux la RAM nécessaire.
         */
        fun openMapped(file: File): OnnxModel {
            val buf = FileChannel.open(file.toPath(), StandardOpenOption.READ).use {
                it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
            }
            val opts = options().apply {
                addConfigEntry("session.use_ort_model_bytes_directly", "1")
                addConfigEntry("session.use_ort_model_bytes_for_initializers", "1")
            }
            return OnnxModel(env.createSession(buf, opts), buf)
        }
    }
}
