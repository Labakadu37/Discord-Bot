package com.monimage.launcher

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Linux intégré : Alpine Linux lancé avec proot (même principe que Termux), sans root.
 * Vrai shell + gestionnaire de paquets `apk` : python3, pip, git, nano…
 */
object LinuxEnv {

    private const val ALPINE = "v3.24"
    private const val ROOTFS_VERSION = "3.24.2"
    private const val MIRROR = "https://dl-cdn.alpinelinux.org/alpine"

    fun dir(context: Context) = File(context.filesDir, "linux")
    private fun rootfs(context: Context) = File(dir(context), "rootfs")
    private fun proot(context: Context) = File(dir(context), "proot")
    private fun marker(context: Context) = File(dir(context), ".installed")

    fun isInstalled(context: Context) = marker(context).exists()

    /** Architecture Alpine correspondant au processeur du téléphone. */
    private fun arch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "armv7"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> throw IOException("processeur non pris en charge : ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    /** Télécharge et installe Linux (environ 4 Mo). [progress] reçoit un texte d'avancement. */
    fun install(context: Context, progress: (String) -> Unit) {
        val arch = arch()
        val base = dir(context)
        base.deleteRecursively()
        base.mkdirs()
        File(base, "tmp").mkdirs()

        progress("Recherche de proot…")
        val listing = download("$MIRROR/$ALPINE/community/$arch/").bufferedReader().use { it.readText() }
        val prootApk = Regex("proot-static-[0-9][^\"<>/]*\\.apk").findAll(listing).map { it.value }.maxOrNull()
            ?: throw IOException("proot introuvable sur le serveur Alpine")

        progress("Téléchargement de proot…")
        GZIPInputStream(download("$MIRROR/$ALPINE/community/$arch/$prootApk")).use { gz ->
            if (!extractOne(TarReader(gz), "usr/bin/proot.static", proot(context))) {
                throw IOException("proot absent de l'archive")
            }
        }
        Os.chmod(proot(context).path, "755".toInt(8))

        progress("Téléchargement d'Alpine Linux…")
        val url = "$MIRROR/$ALPINE/releases/$arch/alpine-minirootfs-$ROOTFS_VERSION-$arch.tar.gz"
        GZIPInputStream(download(url)).use { gz ->
            extractAll(TarReader(gz), rootfs(context)) { count -> progress("Installation… $count fichiers") }
        }

        configure(rootfs(context))
        marker(context).writeText(arch)
        progress("Linux installé ✔")
    }

    /** Réglages après extraction : réseau, cache des paquets, invite de commande. */
    private fun configure(root: File) {
        File(root, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        // Absent de l'image minimale : sans lui, `apk update` échoue
        File(root, "var/cache/apk").mkdirs()
        File(root, "root").mkdirs()
        File(root, "etc/profile.d").mkdirs()
        File(root, "etc/profile.d/redsmile.sh").writeText(
            """
            export PS1='redsmile:\w# '
            export PYTHONUNBUFFERED=1
            # pip installe directement (pas besoin de venv)
            export PIP_BREAK_SYSTEM_PACKAGES=1
            # Sans écran de terminal, « python3 » seul attendrait la fin du texte : on ouvre la console
            python3() { if [ ${'$'}# -eq 0 ]; then command python3 -i; else command python3 "${'$'}@"; fi; }
            python() { python3 "${'$'}@"; }
            """.trimIndent() + "\n"
        )
    }

    /** Commande qui ouvre un shell Linux interactif dans proot. */
    fun shellCommand(context: Context): List<String> = listOf(
        proot(context).path,
        "--kill-on-exit", "-0",
        "-r", rootfs(context).path,
        "-b", "/dev", "-b", "/proc", "-b", "/sys",
        "-w", "/root",
        "/usr/bin/env", "-i",
        "HOME=/root", "TERM=dumb", "LANG=C.UTF-8", "PYTHONUNBUFFERED=1",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "/bin/sh", "-l", "-i",
    )

    fun shellEnv(context: Context): Map<String, String> = mapOf("PROOT_TMP_DIR" to File(dir(context), "tmp").path)

    fun uninstall(context: Context) {
        dir(context).deleteRecursively()
    }

    /** Extrait un seul fichier de l'archive. */
    fun extractOne(tar: TarReader, name: String, out: File): Boolean {
        while (true) {
            val e = tar.next() ?: return false
            if (e.name == name) {
                writeFile(tar, out)
                return true
            }
        }
    }

    /** Extrait toute l'archive dans [root] en gardant liens et permissions. */
    fun extractAll(tar: TarReader, root: File, onProgress: (Int) -> Unit = {}) {
        var count = 0
        while (true) {
            val e = tar.next() ?: break
            if (e.name.isEmpty() || e.name.split('/').contains("..")) continue
            val out = File(root, e.name)
            when (e.type) {
                '5' -> out.mkdirs()
                '2' -> {
                    out.parentFile?.mkdirs()
                    out.delete()
                    Os.symlink(e.linkName, out.path)
                }
                '1' -> {
                    out.parentFile?.mkdirs()
                    // Les liens physiques sont interdits aux applis Android : on copie
                    File(root, e.linkName.removePrefix("./")).copyTo(out, overwrite = true)
                }
                '0', '7' -> writeFile(tar, out)
                else -> continue
            }
            if (e.type != '2') runCatching { Os.chmod(out.path, e.mode and "7777".toInt(8)) }
            if (++count % 50 == 0) onProgress(count)
        }
    }

    private fun download(url: String): InputStream {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "RedSmile/1.0 (Android)")
        if (conn.responseCode !in 200..299) throw IOException("le serveur répond ${conn.responseCode} ($url)")
        return conn.inputStream
    }

    private fun writeFile(tar: TarReader, out: File) {
        out.parentFile?.mkdirs()
        out.delete()
        FileOutputStream(out).use { o ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = tar.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
            }
        }
    }
}
