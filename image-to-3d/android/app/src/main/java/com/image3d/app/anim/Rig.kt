package com.image3d.app.anim

import com.image3d.app.mesh.Mesh

/**
 * Squelette automatique : une chaîne verticale d'os du pied au sommet de l'objet.
 * Chaque sommet est lié aux deux os les plus proches en hauteur (poids linéaires),
 * ce qui permet de plier, tordre, écraser ou faire onduler n'importe quel objet.
 */
class Rig(val jointPositions: FloatArray, val joints: ByteArray, val weights: FloatArray, val height: Float) {
    val boneCount get() = jointPositions.size / 3

    companion object {
        const val BONES = 5

        fun build(mesh: Mesh): Rig {
            val b = mesh.bounds()
            val minY = b[1]
            val h = (b[4] - b[1]).coerceAtLeast(1e-4f)
            val cx = (b[0] + b[3]) / 2
            val cz = (b[2] + b[5]) / 2
            val jp = FloatArray(BONES * 3)
            for (i in 0 until BONES) {
                jp[i * 3] = cx
                jp[i * 3 + 1] = minY + h * i / (BONES - 1)
                jp[i * 3 + 2] = cz
            }
            val n = mesh.vertexCount
            val joints = ByteArray(n * 2)
            val weights = FloatArray(n * 2)
            for (v in 0 until n) {
                val t = ((mesh.positions[v * 3 + 1] - minY) / h * (BONES - 1)).coerceIn(0f, (BONES - 1).toFloat())
                val i0 = t.toInt().coerceAtMost(BONES - 2)
                val f = t - i0
                joints[v * 2] = i0.toByte()
                joints[v * 2 + 1] = (i0 + 1).toByte()
                weights[v * 2] = 1f - f
                weights[v * 2 + 1] = f
            }
            return Rig(jp, joints, weights, h)
        }
    }
}

/** Pose d'animation : translation additionnelle, rotation (quaternion x,y,z,w) et échelle de chaque os. */
class Pose(val bones: Int) {
    val t = FloatArray(bones * 3)
    val r = FloatArray(bones * 4)
    val s = FloatArray(bones * 3)

    init { reset() }

    fun reset() {
        t.fill(0f); s.fill(1f); r.fill(0f)
        for (i in 0 until bones) r[i * 4 + 3] = 1f
    }

    fun rotate(bone: Int, ax: Float, ay: Float, az: Float) {
        // Angles en radians, ordre Y puis X puis Z
        val q = Mat4.quatFromEuler(ax, ay, az)
        Mat4.quatMul(r, bone * 4, q, 0, r, bone * 4)
    }

    fun scale(bone: Int, sx: Float, sy: Float, sz: Float) {
        s[bone * 3] *= sx; s[bone * 3 + 1] *= sy; s[bone * 3 + 2] *= sz
    }

    fun translate(bone: Int, x: Float, y: Float, z: Float) {
        t[bone * 3] += x; t[bone * 3 + 1] += y; t[bone * 3 + 2] += z
    }
}

object Skinning {
    /** Translation locale au repos de chaque os (relative à son parent ; l'os 0 est relatif à l'origine). */
    fun restLocal(rig: Rig, bone: Int, out: FloatArray) {
        for (a in 0..2) {
            out[a] = rig.jointPositions[bone * 3 + a] - if (bone > 0) rig.jointPositions[(bone - 1) * 3 + a] else 0f
        }
    }

    /** Calcule les matrices de skinning (monde * inverse bind), 16 floats par os, colonne majeure. */
    fun skinMatrices(rig: Rig, pose: Pose, out: FloatArray) {
        val world = FloatArray(16)
        val local = FloatArray(16)
        val tmp = FloatArray(16)
        val tr = FloatArray(3)
        Mat4.identity(world)
        for (b in 0 until rig.boneCount) {
            restLocal(rig, b, tr)
            for (a in 0..2) tr[a] += pose.t[b * 3 + a]
            Mat4.fromTRS(tr, 0, pose.r, b * 4, pose.s, b * 3, local)
            Mat4.mul(world, local, tmp)
            System.arraycopy(tmp, 0, world, 0, 16)
            // inverse bind = translation(-position de l'os au repos)
            Mat4.identity(local)
            local[12] = -rig.jointPositions[b * 3]; local[13] = -rig.jointPositions[b * 3 + 1]; local[14] = -rig.jointPositions[b * 3 + 2]
            Mat4.mul(world, local, tmp)
            System.arraycopy(tmp, 0, out, b * 16, 16)
        }
    }
}
