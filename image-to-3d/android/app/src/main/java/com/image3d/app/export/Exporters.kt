package com.image3d.app.export

import com.image3d.app.anim.Animations
import com.image3d.app.anim.Pose
import com.image3d.app.anim.Rig
import com.image3d.app.anim.Skinning
import com.image3d.app.mesh.Mesh
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * glTF 2.0 binaire (.glb) : maillage coloré + squelette + toutes les animations.
 * S'ouvre dans Blender, Unity, Godot, Unreal, three.js, Windows 3D Viewer...
 */
object GlbWriter {
    private const val FPS = 30

    fun write(mesh: Mesh, rig: Rig, name: String, out: OutputStream) {
        val bin = ByteArrayOutputStream()
        val views = StringBuilder()
        val accessors = StringBuilder()
        var viewCount = 0
        var accessorCount = 0

        fun addView(bytes: ByteBuffer, target: Int?): Int {
            while (bin.size() % 4 != 0) bin.write(0)
            val offset = bin.size()
            bin.write(bytes.array(), 0, bytes.position())
            if (views.isNotEmpty()) views.append(',')
            views.append("{\"buffer\":0,\"byteOffset\":$offset,\"byteLength\":${bytes.position()}")
            if (target != null) views.append(",\"target\":$target")
            views.append('}')
            return viewCount++
        }

        fun addAccessor(view: Int, componentType: Int, count: Int, type: String, extra: String = ""): Int {
            if (accessors.isNotEmpty()) accessors.append(',')
            accessors.append("{\"bufferView\":$view,\"componentType\":$componentType,\"count\":$count,\"type\":\"$type\"$extra}")
            return accessorCount++
        }

        fun buf(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        fun minMax(values: FloatArray, comps: Int): String {
            val mn = FloatArray(comps) { Float.MAX_VALUE }
            val mx = FloatArray(comps) { -Float.MAX_VALUE }
            for (i in values.indices) {
                val c = i % comps
                mn[c] = minOf(mn[c], values[i]); mx[c] = maxOf(mx[c], values[i])
            }
            return ",\"min\":[${mn.joinToString(",") { num(it) }}],\"max\":[${mx.joinToString(",") { num(it) }}]"
        }

        val nv = mesh.vertexCount
        val floatArrayBuffer = 34962
        val elementBuffer = 34963
        val FLOAT = 5126
        val UBYTE = 5121
        val UINT = 5125

        val pos = buf(nv * 12).apply { mesh.positions.forEach { putFloat(it) } }
        val aPos = addAccessor(addView(pos, floatArrayBuffer), FLOAT, nv, "VEC3", minMax(mesh.positions, 3))
        val nrm = buf(nv * 12).apply { mesh.normals.forEach { putFloat(it) } }
        val aNrm = addAccessor(addView(nrm, floatArrayBuffer), FLOAT, nv, "VEC3")
        val col = buf(nv * 4)
        for (v in 0 until nv) {
            for (c in 0..2) col.put((srgbToLinear(mesh.colors[v * 3 + c]) * 255f).roundToInt().coerceIn(0, 255).toByte())
            col.put(255.toByte())
        }
        val aCol = addAccessor(addView(col, floatArrayBuffer), UBYTE, nv, "VEC4", ",\"normalized\":true")
        val jnt = buf(nv * 4)
        val wgt = buf(nv * 16)
        for (v in 0 until nv) {
            // Influences non nulles en premier ; un poids nul doit pointer vers l'os 0 (règle glTF)
            var slots = 0
            for (k in 0..1) {
                val w = rig.weights[v * 2 + k]
                if (w <= 0f) continue
                jnt.put(rig.joints[v * 2 + k]); wgt.putFloat(w); slots++
            }
            while (slots < 4) { jnt.put(0.toByte()); wgt.putFloat(0f); slots++ }
        }
        val aJnt = addAccessor(addView(jnt, floatArrayBuffer), UBYTE, nv, "VEC4")
        val aWgt = addAccessor(addView(wgt, floatArrayBuffer), FLOAT, nv, "VEC4")
        val idx = buf(mesh.indices.size * 4).apply { mesh.indices.forEach { putInt(it) } }
        val aIdx = addAccessor(addView(idx, elementBuffer), UINT, mesh.indices.size, "SCALAR")

        val bones = rig.boneCount
        val ibm = buf(bones * 64)
        for (b in 0 until bones) {
            val m = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f,
                -rig.jointPositions[b * 3], -rig.jointPositions[b * 3 + 1], -rig.jointPositions[b * 3 + 2], 1f)
            m.forEach { ibm.putFloat(it) }
        }
        val aIbm = addAccessor(addView(ibm, null), FLOAT, bones, "MAT4")

        // Nœuds : 0 = maillage, 1..bones = os (chaîne parent -> enfant)
        val nodes = StringBuilder()
        nodes.append("{\"name\":\"${esc(name)}\",\"mesh\":0,\"skin\":0}")
        val rest = FloatArray(3)
        for (b in 0 until bones) {
            Skinning.restLocal(rig, b, rest)
            nodes.append(",{\"name\":\"os_$b\",\"translation\":[${rest.joinToString(",") { num(it) }}]")
            if (b < bones - 1) nodes.append(",\"children\":[${b + 2}]")
            nodes.append('}')
        }

        // Animations échantillonnées à 30 images/s
        val anims = StringBuilder()
        val pose = Pose(bones)
        for (anim in Animations.ALL) {
            if (anim === Animations.NONE) continue
            val frames = (anim.period * FPS).roundToInt() + 1
            val times = FloatArray(frames) { it * anim.period / (frames - 1) }
            val tBuf = buf(frames * 4).apply { times.forEach { putFloat(it) } }
            val aTime = addAccessor(addView(tBuf, null), FLOAT, frames, "SCALAR", minMax(times, 1))
            val tr = Array(bones) { FloatArray(frames * 3) }
            val ro = Array(bones) { FloatArray(frames * 4) }
            val sc = Array(bones) { FloatArray(frames * 3) }
            for (f in 0 until frames) {
                anim.sample(pose, if (f == frames - 1) 0f else times[f], rig.height)
                for (b in 0 until bones) {
                    Skinning.restLocal(rig, b, rest)
                    for (a in 0..2) {
                        tr[b][f * 3 + a] = rest[a] + pose.t[b * 3 + a]
                        sc[b][f * 3 + a] = pose.s[b * 3 + a]
                    }
                    for (a in 0..3) ro[b][f * 4 + a] = pose.r[b * 4 + a]
                }
            }
            val samplers = StringBuilder()
            val channels = StringBuilder()
            var s = 0
            for (b in 0 until bones) {
                for ((path, data, type) in listOf(Triple("translation", tr[b], "VEC3"), Triple("rotation", ro[b], "VEC4"), Triple("scale", sc[b], "VEC3"))) {
                    val bb = buf(data.size * 4).apply { data.forEach { putFloat(it) } }
                    val acc = addAccessor(addView(bb, null), FLOAT, frames, type)
                    if (s > 0) { samplers.append(','); channels.append(',') }
                    samplers.append("{\"input\":$aTime,\"output\":$acc,\"interpolation\":\"LINEAR\"}")
                    channels.append("{\"sampler\":$s,\"target\":{\"node\":${b + 1},\"path\":\"$path\"}}")
                    s++
                }
            }
            if (anims.isNotEmpty()) anims.append(',')
            anims.append("{\"name\":\"${esc(anim.label)}\",\"samplers\":[$samplers],\"channels\":[$channels]}")
        }

        while (bin.size() % 4 != 0) bin.write(0)
        val jointList = (1..bones).joinToString(",")
        val json = """{"asset":{"version":"2.0","generator":"Image 3D (TripoSR sur téléphone)"},""" +
            """"scene":0,"scenes":[{"name":"${esc(name)}","nodes":[0,1]}],""" +
            """"nodes":[$nodes],""" +
            """"meshes":[{"name":"${esc(name)}","primitives":[{"attributes":{"POSITION":$aPos,"NORMAL":$aNrm,"COLOR_0":$aCol,"JOINTS_0":$aJnt,"WEIGHTS_0":$aWgt},"indices":$aIdx,"material":0}]}],""" +
            """"materials":[{"name":"couleurs","pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1],"metallicFactor":0,"roughnessFactor":0.85},"doubleSided":true}],""" +
            """"skins":[{"name":"squelette","joints":[$jointList],"skeleton":1,"inverseBindMatrices":$aIbm}],""" +
            """"animations":[$anims],""" +
            """"buffers":[{"byteLength":${bin.size()}}],"bufferViews":[$views],"accessors":[$accessors]}"""

        var jsonBytes = json.toByteArray(Charsets.UTF_8)
        val pad = (4 - jsonBytes.size % 4) % 4
        jsonBytes += ByteArray(pad) { ' '.code.toByte() }
        val total = 12 + 8 + jsonBytes.size + 8 + bin.size()
        val header = buf(20)
        header.putInt(0x46546C67); header.putInt(2); header.putInt(total)
        header.putInt(jsonBytes.size); header.putInt(0x4E4F534A)
        out.write(header.array())
        out.write(jsonBytes)
        val binHeader = buf(8)
        binHeader.putInt(bin.size()); binHeader.putInt(0x004E4942)
        out.write(binHeader.array())
        bin.writeTo(out)
        out.flush()
    }

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    // Float.toString donne la représentation exacte (aller-retour) exigée pour min/max
    private fun num(v: Float) = if (v.isFinite()) v.toString() else "0"

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").filter { it >= ' ' }
}

/** .obj avec couleurs par sommet (Blender, MeshLab, ZBrush...). */
object ObjWriter {
    fun write(mesh: Mesh, out: OutputStream) {
        val w = BufferedWriter(out.writer(Charsets.UTF_8), 1 shl 16)
        w.write("# Image 3D - maillage coloré par sommet\n")
        val p = mesh.positions; val c = mesh.colors; val n = mesh.normals
        for (v in 0 until mesh.vertexCount) {
            w.write(String.format(Locale.US, "v %.5f %.5f %.5f %.4f %.4f %.4f\n", p[v * 3], p[v * 3 + 1], p[v * 3 + 2], c[v * 3], c[v * 3 + 1], c[v * 3 + 2]))
        }
        for (v in 0 until mesh.vertexCount) {
            w.write(String.format(Locale.US, "vn %.4f %.4f %.4f\n", n[v * 3], n[v * 3 + 1], n[v * 3 + 2]))
        }
        val idx = mesh.indices
        for (f in idx.indices step 3) {
            val a = idx[f] + 1; val b = idx[f + 1] + 1; val d = idx[f + 2] + 1
            w.write("f $a//$a $b//$b $d//$d\n")
        }
        w.flush()
    }
}

/** .stl binaire pour l'impression 3D (sans couleurs). */
object StlWriter {
    fun write(mesh: Mesh, out: OutputStream) {
        val tris = mesh.triangleCount
        val header = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN)
        val title = "Image 3D".toByteArray()
        header.put(title); header.position(80); header.putInt(tris)
        out.write(header.array())
        val b = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
        val p = mesh.positions
        for (f in 0 until tris) {
            b.clear()
            val i0 = mesh.indices[f * 3] * 3; val i1 = mesh.indices[f * 3 + 1] * 3; val i2 = mesh.indices[f * 3 + 2] * 3
            val ux = p[i1] - p[i0]; val uy = p[i1 + 1] - p[i0 + 1]; val uz = p[i1 + 2] - p[i0 + 2]
            val vx = p[i2] - p[i0]; val vy = p[i2 + 1] - p[i0 + 1]; val vz = p[i2 + 2] - p[i0 + 2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12f)
            nx /= l; ny /= l; nz /= l
            // STL : Z en haut, en millimètres (objet d'environ 10 cm) ; même permutation pour la normale
            b.putFloat(nz); b.putFloat(nx); b.putFloat(ny)
            for (i in intArrayOf(i0, i1, i2)) { b.putFloat(p[i + 2] * 100); b.putFloat(p[i] * 100); b.putFloat(p[i + 1] * 100) }
            b.putShort(0)
            out.write(b.array())
        }
        out.flush()
    }
}
