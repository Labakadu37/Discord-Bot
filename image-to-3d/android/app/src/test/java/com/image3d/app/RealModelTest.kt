package com.image3d.app

import com.image3d.app.ai.OnnxModel
import com.image3d.app.ai.TriplaneMesher
import com.image3d.app.anim.Rig
import com.image3d.app.export.GlbWriter
import com.image3d.app.mesh.MeshOps
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Test du vrai pipeline avec les fichiers de l'IA (ignoré sans eux) :
 *   ./gradlew :app:testDebugUnitTest -Dimage3d.models=/chemin/models -Dimage3d.image=/chemin/input512.png
 * input512.png = image déjà préparée (fond gris, 512x512), par ex. out/input.png de tools/reference_pipeline.py
 */
class RealModelTest {
    @Test
    fun imageToAnimatedGlb(): Unit = runBlocking {
        val models = System.getProperty("image3d.models")?.let(::File)
        val image = System.getProperty("image3d.image")?.let(::File)
        assumeTrue(models != null && image != null && models.isDirectory && image.exists())
        val encoder = System.getProperty("image3d.encoder") ?: "triposr_encoder_int8.ort"
        val res = System.getProperty("image3d.resolution")?.toInt() ?: 128

        // javax.imageio existe sur la JVM mais pas dans android.jar : accès par réflexion
        val img = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, image!!)
        val getRGB = img.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val n = 512 * 512
        val x = FloatArray(3 * n)
        for (y in 0 until 512) for (xx in 0 until 512) {
            val p = getRGB.invoke(img, xx, y) as Int
            val i = y * 512 + xx
            x[i] = (p shr 16 and 0xFF) / 255f; x[n + i] = (p shr 8 and 0xFF) / 255f; x[2 * n + i] = (p and 0xFF) / 255f
        }
        var t = System.currentTimeMillis()
        val triplane = OnnxModel.openMapped(File(models, encoder)).use {
            it.run(mapOf("image" to (x to longArrayOf(1, 3, 512, 512))))[0]
        }
        println("REAL encodeur ${System.currentTimeMillis() - t} ms")
        t = System.currentTimeMillis()
        var preview: com.image3d.app.mesh.Mesh? = null
        val mesh = OnnxModel.open(File(models, "triposr_decoder.onnx")).use {
            TriplaneMesher.build(it, triplane, res, smooth = true, onPreview = { m -> preview = m }) { _, _ -> }
        }
        println("REAL aperçu ${preview?.triangleCount} triangles")
        assertTrue(preview != null && preview!!.triangleCount in 500 until mesh.triangleCount)
        val vol = MeshOps.signedVolume(mesh.positions, mesh.indices)
        println("REAL maillage ${mesh.vertexCount} sommets ${mesh.triangleCount} triangles volume $vol en ${System.currentTimeMillis() - t} ms")
        assertTrue(mesh.triangleCount > 1000 && vol > 0)
        System.getProperty("image3d.glbOut")?.let { File(it).outputStream().use { o -> GlbWriter.write(mesh, Rig.build(mesh), "Test", o) } }
    }
}
