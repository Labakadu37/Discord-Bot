package com.image3d.app.mesh

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Maillage coloré : positions/normales/couleurs (rgb 0..1) par sommet, triangles indexés. Repère Y en haut. */
class Mesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val colors: FloatArray,
    val indices: IntArray,
) {
    val vertexCount get() = positions.size / 3
    val triangleCount get() = indices.size / 3

    fun bounds(): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until vertexCount) for (a in 0..2) {
            val v = positions[i * 3 + a]
            if (v < b[a]) b[a] = v
            if (v > b[a + 3]) b[a + 3] = v
        }
        return b
    }

    fun save(file: File) {
        DataOutputStream(file.outputStream().buffered(1 shl 16)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(vertexCount)
            out.writeInt(indices.size)
            for (v in positions) out.writeFloat(v)
            for (v in normals) out.writeFloat(v)
            for (v in colors) out.writeFloat(v)
            for (v in indices) out.writeInt(v)
        }
    }

    companion object {
        private const val MAGIC = 0x49334431 // "I3D1"

        fun load(file: File): Mesh = DataInputStream(file.inputStream().buffered(1 shl 16)).use { input ->
            require(input.readInt() == MAGIC) { "fichier de maillage invalide" }
            val nv = input.readInt()
            val ni = input.readInt()
            fun floats(n: Int) = FloatArray(n) { input.readFloat() }
            val p = floats(nv * 3)
            val n = floats(nv * 3)
            val c = floats(nv * 3)
            Mesh(p, n, c, IntArray(ni) { input.readInt() })
        }
    }
}

/** Tableaux extensibles sans boxing (les maillages dépassent souvent le million d'éléments). */
class FloatList(capacity: Int = 1024) {
    var data = FloatArray(capacity); private set
    var size = 0; private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray() = data.copyOf(size)
}

class IntList(capacity: Int = 1024) {
    var data = IntArray(capacity); private set
    var size = 0; private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray() = data.copyOf(size)
}
