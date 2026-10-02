package com.monimage.launcher

import java.io.File
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * Terminal : un shell qui garde son état entre les commandes (dossier courant, variables…).
 * Par défaut le shell d'Android (/system/bin/sh) ; [command] permet de lancer le Linux intégré.
 */
class Shell(
    private val home: File,
    private val command: List<String> = listOf("/system/bin/sh"),
    private val extraEnv: Map<String, String> = emptyMap(),
    private val onOutput: (String) -> Unit,
) {

    private var process: Process? = null

    fun start() {
        stop()
        val p = runCatching {
            ProcessBuilder(command)
                .directory(home)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = home.absolutePath
                    environment()["TERM"] = "dumb"
                    environment().putAll(extraEnv)
                }
                .start()
        }.getOrElse {
            onOutput("[impossible de lancer le shell : ${it.message}]\n")
            return
        }
        process = p
        thread(isDaemon = true) {
            val reader = InputStreamReader(p.inputStream)
            val buffer = CharArray(4096)
            while (true) {
                val n = runCatching { reader.read(buffer) }.getOrDefault(-1)
                if (n < 0) break
                onOutput(String(buffer, 0, n))
            }
        }
    }

    fun run(command: String) {
        val p = process?.takeIf { it.isAlive } ?: run { start(); process } ?: return
        runCatching {
            p.outputStream.write((command + "\n").toByteArray())
            p.outputStream.flush()
        }.onFailure { onOutput("[shell fermé, redémarrage]\n"); start() }
    }

    /** Arrête la commande en cours (relance un shell neuf). */
    fun stop() {
        process?.destroy()
        process = null
    }
}
