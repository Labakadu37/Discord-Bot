package com.image3d.app.mesh

/**
 * Extraction de surface par "surface nets" (même algorithme que tools/reference_pipeline.py).
 *
 * La grille contient res³ valeurs, indexées (i * res + j) * res + k pour le point
 * (min + i * step, min + j * step, min + k * step). L'intérieur de l'objet est field > iso.
 * Un sommet par cellule traversée par la surface, un quad par arête de grille traversée ;
 * les triangles sont orientés vers l'extérieur.
 */
object SurfaceNets {

    class Result(val positions: FloatArray, val indices: IntArray)

    private val CORNERS = Array(8) { intArrayOf(it shr 2 and 1, it shr 1 and 1, it and 1) }
    private val EDGES: List<IntArray> = buildList {
        for (a in 0 until 8) for (b in a + 1 until 8) {
            val d = (0..2).sumOf { kotlin.math.abs(CORNERS[a][it] - CORNERS[b][it]) }
            if (d == 1) add(intArrayOf(a, b))
        }
    }

    fun extract(
        field: FloatArray,
        res: Int,
        iso: Float,
        min: Float,
        step: Float,
        progress: (Float) -> Unit = {},
    ): Result {
        require(field.size == res * res * res)
        val n = res - 1
        val vid = IntArray(n * n * n) { -1 }
        val verts = FloatList(1 shl 16)
        val corner = FloatArray(8)
        val off = IntArray(8) { c -> (CORNERS[c][0] * res + CORNERS[c][1]) * res + CORNERS[c][2] }

        for (i in 0 until n) {
            for (j in 0 until n) {
                for (k in 0 until n) {
                    val base = (i * res + j) * res + k
                    var mask = 0
                    for (c in 0 until 8) {
                        val v = field[base + off[c]]
                        corner[c] = v
                        if (v > iso) mask = mask or (1 shl c)
                    }
                    if (mask == 0 || mask == 0xFF) continue
                    var sx = 0f; var sy = 0f; var sz = 0f; var cnt = 0
                    for (e in EDGES) {
                        val a = e[0]; val b = e[1]
                        if ((mask shr a and 1) == (mask shr b and 1)) continue
                        val t = (iso - corner[a]) / (corner[b] - corner[a])
                        val ca = CORNERS[a]; val cb = CORNERS[b]
                        sx += ca[0] + t * (cb[0] - ca[0])
                        sy += ca[1] + t * (cb[1] - ca[1])
                        sz += ca[2] + t * (cb[2] - ca[2])
                        cnt++
                    }
                    vid[(i * n + j) * n + k] = verts.size / 3
                    verts.add(min + (i + sx / cnt) * step)
                    verts.add(min + (j + sy / cnt) * step)
                    verts.add(min + (k + sz / cnt) * step)
                }
            }
            progress(0.5f * (i + 1) / n)
        }

        val tris = IntList(verts.size * 2)
        val p = IntArray(3)
        val c = IntArray(3)
        val quad = IntArray(4)
        val du = intArrayOf(-1, 0, 0, -1)
        val dv = intArrayOf(-1, -1, 0, 0)
        for (axis in 0..2) {
            val u = (axis + 1) % 3
            val v = (axis + 2) % 3
            val stride = intArrayOf(res * res, res, 1)[axis]
            for (i in 0 until res) for (j in 0 until res) for (k in 0 until res) {
                p[0] = i; p[1] = j; p[2] = k
                if (p[axis] >= n || p[u] < 1 || p[u] > n - 1 || p[v] < 1 || p[v] > n - 1) continue
                val idx = (i * res + j) * res + k
                val a = field[idx] > iso
                val b = field[idx + stride] > iso
                if (a == b) continue
                var ok = true
                for (q in 0 until 4) {
                    c[0] = p[0]; c[1] = p[1]; c[2] = p[2]
                    c[u] += du[q]; c[v] += dv[q]
                    val id = vid[(c[0] * n + c[1]) * n + c[2]]
                    if (id < 0) { ok = false; break }
                    quad[q] = id
                }
                if (!ok) continue
                if (!a) { // extérieur -> intérieur : la normale pointe vers -axis, on inverse le sens
                    val t = quad[1]; quad[1] = quad[3]; quad[3] = t
                }
                tris.add(quad[0]); tris.add(quad[1]); tris.add(quad[2])
                tris.add(quad[0]); tris.add(quad[2]); tris.add(quad[3])
            }
            progress(0.5f + 0.5f * (axis + 1) / 3)
        }
        return Result(verts.toArray(), tris.toArray())
    }
}
