package com.monimage.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Vérifie le lecteur tar sur les vraies archives Alpine.
 * Lancer avec ALPINE_ROOTFS_TGZ et PROOT_APK pointant vers les fichiers téléchargés (sinon ignoré).
 */
class TarReaderTest {

    private fun file(env: String): File? = System.getenv(env)?.let(::File)?.takeIf { it.exists() }

    @Test
    fun readsAlpineRootfs() {
        val archive = file("ALPINE_ROOTFS_TGZ")
        assumeTrue(archive != null)
        val entries = mutableMapOf<String, TarReader.Entry>()
        GZIPInputStream(archive!!.inputStream()).use { gz ->
            val tar = TarReader(gz)
            while (true) {
                val e = tar.next() ?: break
                if (e.name.isNotEmpty()) entries[e.name] = e // l'entrée racine « ./ » est ignorée à l'installation
            }
        }
        val expected = System.getenv("ALPINE_ROOTFS_COUNT")?.toInt()
        if (expected != null) assertEquals(expected, entries.size)
        assertEquals('0', entries.getValue("bin/busybox").type)
        assertEquals('2', entries.getValue("bin/sh").type)
        assertEquals("/bin/busybox", entries.getValue("bin/sh").linkName)
        assertEquals('5', entries.getValue("etc").type)
        assertTrue(entries.containsKey("etc/apk/repositories"))
        assertTrue(entries.containsKey("sbin/apk"))
        assertEquals("755".toInt(8), entries.getValue("bin/busybox").mode and "777".toInt(8))
    }

    @Test
    fun findsProotInAlpinePackage() {
        val apk = file("PROOT_APK")
        assumeTrue(apk != null)
        val out = ByteArrayOutputStream()
        GZIPInputStream(apk!!.inputStream()).use { gz ->
            val tar = TarReader(gz)
            var found = false
            while (!found) {
                val e = tar.next() ?: break
                if (e.name == "usr/bin/proot.static") {
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = tar.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    found = true
                }
            }
            assertTrue("proot.static introuvable", found)
        }
        val expectedSize = System.getenv("PROOT_SIZE")?.toInt()
        if (expectedSize != null) assertEquals(expectedSize, out.size())
        // Exécutable ELF
        assertEquals(0x7f, out.toByteArray()[0].toInt())
        assertEquals("ELF", String(out.toByteArray(), 1, 3))
    }
}
