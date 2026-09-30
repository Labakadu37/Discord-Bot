package com.image3d.app

import com.image3d.app.anim.Animations
import com.image3d.app.anim.Pose
import com.image3d.app.anim.Rig
import com.image3d.app.anim.Skinning
import com.image3d.app.export.GlbWriter
import com.image3d.app.mesh.Mesh
import com.image3d.app.mesh.MeshOps
import com.image3d.app.mesh.SurfaceNets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

class MeshPipelineTest {

    /** Champ de densité façon TripoSR : > iso à l'intérieur des sphères. */
    private fun spheres(res: Int, vararg s: FloatArray): Triple<FloatArray, Float, Float> {
        val min = -0.87f
        val step = 1.74f / (res - 1)
        val field = FloatArray(res * res * res)
        for (i in 0 until res) for (j in 0 until res) for (k in 0 until res) {
            val x = min + i * step; val y = min + j * step; val z = min + k * step
            var d = 0f
            for (c in s) {
                val r = sqrt((x - c[0]) * (x - c[0]) + (y - c[1]) * (y - c[1]) + (z - c[2]) * (z - c[2]))
                d = maxOf(d, 50f * (1 - r / c[3]))
            }
            field[(i * res + j) * res + k] = d
        }
        return Triple(field, min, step)
    }

    @Test
    fun sphereIsClosedAndOrientedOutward() {
        val (field, min, step) = spheres(48, floatArrayOf(0.1f, 0f, -0.1f, 0.5f))
        val m = SurfaceNets.extract(field, 48, 25f, min, step)
        // iso 25 sur 50 * (1 - r / 0.5) -> rayon 0.25
        val expected = 4.0 / 3 * PI * 0.25 * 0.25 * 0.25
        val vol = MeshOps.signedVolume(m.positions, m.indices)
        assertTrue("volume $vol attendu $expected", abs(vol - expected) / expected < 0.05)

        val edges = HashMap<Long, Int>()
        for (f in m.indices.indices step 3) for (e in 0..2) {
            val a = m.indices[f + e]; val b = m.indices[f + (e + 1) % 3]
            val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
            edges[key] = (edges[key] ?: 0) + 1
        }
        assertTrue("maillage non étanche", edges.values.all { it == 2 })
    }

    @Test
    fun smallFloatersAreRemoved() {
        val (field, min, step) = spheres(48, floatArrayOf(0f, 0f, 0f, 0.6f), floatArrayOf(0.6f, 0.6f, 0.6f, 0.12f))
        val m = SurfaceNets.extract(field, 48, 25f, min, step)
        val kept = MeshOps.keepMainComponents(m.positions, m.indices)
        assertTrue(kept.indices.size < m.indices.size)
        for (v in 0 until kept.positions.size / 3) {
            assertTrue(kept.positions[v * 3] < 0.45f)
        }
    }

    private fun sampleMesh(): Mesh {
        val (field, min, step) = spheres(40, floatArrayOf(0f, 0f, 0f, 0.6f), floatArrayOf(0f, 0f, 0.45f, 0.35f))
        val r = SurfaceNets.extract(field, 40, 25f, min, step)
        val pos = r.positions
        MeshOps.taubinSmooth(pos, r.indices, 2)
        MeshOps.toYUpOnGround(pos)
        val colors = FloatArray(pos.size) { (it % 3) * 0.4f + 0.1f }
        return Mesh(pos, MeshOps.vertexNormals(pos, r.indices), colors, r.indices)
    }

    @Test
    fun restPoseSkinningIsIdentity() {
        val mesh = sampleMesh()
        val rig = Rig.build(mesh)
        val out = FloatArray(rig.boneCount * 16)
        Skinning.skinMatrices(rig, Pose(rig.boneCount), out)
        for (b in 0 until rig.boneCount) for (i in 0 until 16) {
            assertEquals(if (i % 5 == 0) 1f else 0f, out[b * 16 + i], 1e-5f)
        }
        // L'objet est posé sur le sol, et les poids somment à 1
        assertEquals(0f, mesh.bounds()[1], 1e-5f)
        for (v in 0 until mesh.vertexCount) assertEquals(1f, rig.weights[v * 2] + rig.weights[v * 2 + 1], 1e-5f)
        for (a in Animations.ALL) a.sample(Pose(rig.boneCount), 0.3f, rig.height)
    }

    @Test
    fun glbStructureIsValid() {
        val mesh = sampleMesh()
        val bytes = ByteArrayOutputStream().also { GlbWriter.write(mesh, Rig.build(mesh), "Test \"3D\"", it) }.toByteArray()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, bb.getInt(0))
        assertEquals(2, bb.getInt(4))
        assertEquals(bytes.size, bb.getInt(8))
        val jsonLen = bb.getInt(12)
        assertEquals(0x4E4F534A, bb.getInt(16))
        assertEquals(0, jsonLen % 4)
        val json = String(bytes, 20, jsonLen, Charsets.UTF_8)
        assertTrue(json.contains("\"animations\":[{"))
        val binLen = bb.getInt(20 + jsonLen)
        assertEquals(0x004E4942, bb.getInt(24 + jsonLen))
        assertEquals(bytes.size, 28 + jsonLen + binLen)
        System.getProperty("glbOut")?.let { File(it).writeBytes(bytes) }
    }
}
