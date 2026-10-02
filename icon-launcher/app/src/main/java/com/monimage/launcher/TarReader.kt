package com.monimage.launcher

import java.io.InputStream

/**
 * Lecteur d'archive tar minimal (ustar + noms longs PAX/GNU), suffisant pour Alpine.
 * Les paquets .apk d'Alpine sont des tar.gz concaténés : on continue après les blocs vides.
 */
class TarReader(private val input: InputStream) {

    class Entry(val name: String, val type: Char, val mode: Int, val size: Long, val linkName: String)

    private var remaining = 0L
    private var padding = 0L

    /** Entrée suivante, ou null à la fin. Le contenu se lit avec [read] avant d'appeler [next]. */
    fun next(): Entry? {
        skipCurrent()
        var longName: String? = null
        var longLink: String? = null
        while (true) {
            val header = ByteArray(BLOCK)
            if (!readFully(header)) return null
            if (header.all { it.toInt() == 0 }) continue // fin d'un segment, il peut en suivre un autre
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            remaining = size
            padding = (BLOCK - size % BLOCK) % BLOCK
            when (type) {
                'x' -> { // en-tête PAX : path=, linkpath=
                    val (path, link) = parsePax(String(readAll(), Charsets.UTF_8))
                    if (path != null) longName = path
                    if (link != null) longLink = link
                    continue
                }
                'g' -> { skipCurrent(); continue }
                'L' -> { longName = String(readAll(), Charsets.UTF_8).trimEnd('\u0000'); continue }
                'K' -> { longLink = String(readAll(), Charsets.UTF_8).trimEnd('\u0000'); continue }
            }
            val prefix = text(header, 345, 155)
            val baseName = text(header, 0, 100)
            val name = longName ?: if (prefix.isNotEmpty()) "$prefix/$baseName" else baseName
            val link = longLink ?: text(header, 157, 100)
            val kind = if (type == '\u0000') '0' else type
            return Entry(name.removePrefix("./").trimEnd('/'), kind, octal(header, 100, 8).toInt(), size, link)
        }
    }

    /** Lit le contenu de l'entrée courante. */
    fun read(buffer: ByteArray): Int {
        if (remaining <= 0) return -1
        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    private fun readAll(): ByteArray {
        val out = ByteArray(remaining.toInt())
        var off = 0
        while (off < out.size) {
            val n = input.read(out, off, out.size - off)
            if (n < 0) break
            off += n
        }
        remaining = 0
        skipBytes(padding)
        padding = 0
        return out
    }

    private fun skipCurrent() {
        skipBytes(remaining + padding)
        remaining = 0
        padding = 0
    }

    private fun skipBytes(count: Long) {
        var left = count
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
    }

    private fun readFully(buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun parsePax(data: String): Pair<String?, String?> {
        var path: String? = null
        var link: String? = null
        data.lineSequence().forEach { line ->
            val record = line.substringAfter(' ', "")
            when {
                record.startsWith("path=") -> path = record.removePrefix("path=")
                record.startsWith("linkpath=") -> link = record.removePrefix("linkpath=")
            }
        }
        return path to link
    }

    private fun text(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long =
        text(b, off, len).trim().ifEmpty { "0" }.toLong(8)

    companion object {
        private const val BLOCK = 512
    }
}
