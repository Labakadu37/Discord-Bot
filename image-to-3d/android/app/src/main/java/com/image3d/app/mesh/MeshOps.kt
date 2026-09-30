package com.image3d.app.mesh

import kotlin.math.sqrt

object MeshOps {

    /** Supprime les petits morceaux flottants : garde les composantes ayant >= minRatio des faces de la plus grande. */
    fun keepMainComponents(positions: FloatArray, indices: IntArray, minRatio: Float = 0.05f): SurfaceNets.Result {
        val nv = positions.size / 3
        if (indices.isEmpty()) return SurfaceNets.Result(positions, indices)
        val parent = IntArray(nv) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        for (f in indices.indices step 3) {
            union(indices[f], indices[f + 1]); union(indices[f], indices[f + 2])
        }
        val faceCount = IntArray(nv)
        for (f in indices.indices step 3) faceCount[find(indices[f])]++
        val threshold = faceCount.max() * minRatio
        val remap = IntArray(nv) { -1 }
        val keptIdx = IntList(indices.size)
        var next = 0
        for (f in indices.indices step 3) {
            if (faceCount[find(indices[f])] < threshold) continue
            for (c in 0..2) {
                val v = indices[f + c]
                if (remap[v] < 0) remap[v] = next++
                keptIdx.add(remap[v])
            }
        }
        val pos = FloatArray(next * 3)
        for (v in 0 until nv) if (remap[v] >= 0) System.arraycopy(positions, v * 3, pos, remap[v] * 3, 3)
        return SurfaceNets.Result(pos, keptIdx.toArray())
    }

    /** Lissage de Taubin : adoucit l'effet "escalier" de la grille sans faire rétrécir l'objet. */
    fun taubinSmooth(positions: FloatArray, indices: IntArray, iterations: Int = 3) {
        val nv = positions.size / 3
        val sum = FloatArray(nv * 3)
        val cnt = IntArray(nv)
        for (it in 0 until iterations * 2) {
            val factor = if (it % 2 == 0) 0.5f else -0.53f
            sum.fill(0f); cnt.fill(0)
            for (f in indices.indices step 3) for (e in 0..2) {
                val a = indices[f + e]
                val b = indices[f + (e + 1) % 3]
                for (d in 0..2) {
                    sum[a * 3 + d] += positions[b * 3 + d]
                    sum[b * 3 + d] += positions[a * 3 + d]
                }
                cnt[a]++; cnt[b]++
            }
            for (v in 0 until nv) {
                if (cnt[v] == 0) continue
                for (d in 0..2) {
                    val i = v * 3 + d
                    positions[i] += factor * (sum[i] / cnt[v] - positions[i])
                }
            }
        }
    }

    /** Normales par sommet, moyenne des normales de faces pondérée par l'aire. */
    fun vertexNormals(positions: FloatArray, indices: IntArray): FloatArray {
        val n = FloatArray(positions.size)
        for (f in indices.indices step 3) {
            val a = indices[f] * 3; val b = indices[f + 1] * 3; val c = indices[f + 2] * 3
            val ux = positions[b] - positions[a]; val uy = positions[b + 1] - positions[a + 1]; val uz = positions[b + 2] - positions[a + 2]
            val vx = positions[c] - positions[a]; val vy = positions[c + 1] - positions[a + 1]; val vz = positions[c + 2] - positions[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (i in intArrayOf(a, b, c)) { n[i] += nx; n[i + 1] += ny; n[i + 2] += nz }
        }
        for (i in n.indices step 3) {
            val l = sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2])
            if (l > 1e-12f) { n[i] /= l; n[i + 1] /= l; n[i + 2] /= l } else { n[i + 1] = 1f }
        }
        return n
    }

    /**
     * Repère TripoSR (Z en haut, face vers +X) -> repère glTF (Y en haut, face vers +Z),
     * puis pose l'objet sur le sol (y = 0) et le centre en x/z.
     */
    fun toYUpOnGround(positions: FloatArray) {
        for (i in positions.indices step 3) {
            val x = positions[i]; val y = positions[i + 1]; val z = positions[i + 2]
            positions[i] = y; positions[i + 1] = z; positions[i + 2] = x
        }
        val min = FloatArray(3) { Float.MAX_VALUE }
        val max = FloatArray(3) { -Float.MAX_VALUE }
        for (i in positions.indices step 3) for (a in 0..2) {
            min[a] = minOf(min[a], positions[i + a]); max[a] = maxOf(max[a], positions[i + a])
        }
        val shift = floatArrayOf(-(min[0] + max[0]) / 2, -min[1], -(min[2] + max[2]) / 2)
        for (i in positions.indices step 3) for (a in 0..2) positions[i + a] += shift[a]
    }

    fun signedVolume(positions: FloatArray, indices: IntArray): Double {
        var vol = 0.0
        for (f in indices.indices step 3) {
            val a = indices[f] * 3; val b = indices[f + 1] * 3; val c = indices[f + 2] * 3
            val cx = positions[b + 1] * positions[c + 2] - positions[b + 2] * positions[c + 1]
            val cy = positions[b + 2] * positions[c] - positions[b] * positions[c + 2]
            val cz = positions[b] * positions[c + 1] - positions[b + 1] * positions[c]
            vol += positions[a] * cx + positions[a + 1] * cy + positions[a + 2] * cz
        }
        return vol / 6
    }
}
