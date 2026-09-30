package com.image3d.app.anim

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Petites opérations matricielles 4x4 (colonne majeure, comme OpenGL et glTF). */
object Mat4 {
    fun identity(m: FloatArray) {
        m.fill(0f, 0, 16)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
    }

    /** out = a * b */
    fun mul(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (c in 0..3) for (r in 0..3) {
            var s = 0f
            for (k in 0..3) s += a[k * 4 + r] * b[c * 4 + k]
            out[c * 4 + r] = s
        }
    }

    fun fromTRS(t: FloatArray, to: Int, q: FloatArray, qo: Int, s: FloatArray, so: Int, out: FloatArray) {
        val x = q[qo]; val y = q[qo + 1]; val z = q[qo + 2]; val w = q[qo + 3]
        val sx = s[so]; val sy = s[so + 1]; val sz = s[so + 2]
        out[0] = (1 - 2 * (y * y + z * z)) * sx
        out[1] = (2 * (x * y + z * w)) * sx
        out[2] = (2 * (x * z - y * w)) * sx
        out[3] = 0f
        out[4] = (2 * (x * y - z * w)) * sy
        out[5] = (1 - 2 * (x * x + z * z)) * sy
        out[6] = (2 * (y * z + x * w)) * sy
        out[7] = 0f
        out[8] = (2 * (x * z + y * w)) * sz
        out[9] = (2 * (y * z - x * w)) * sz
        out[10] = (1 - 2 * (x * x + y * y)) * sz
        out[11] = 0f
        out[12] = t[to]; out[13] = t[to + 1]; out[14] = t[to + 2]; out[15] = 1f
    }

    /** out = a * b (quaternions x,y,z,w) ; out peut être a ou b. */
    fun quatMul(a: FloatArray, ao: Int, b: FloatArray, bo: Int, out: FloatArray, oo: Int) {
        val ax = a[ao]; val ay = a[ao + 1]; val az = a[ao + 2]; val aw = a[ao + 3]
        val bx = b[bo]; val by = b[bo + 1]; val bz = b[bo + 2]; val bw = b[bo + 3]
        var x = aw * bx + ax * bw + ay * bz - az * by
        var y = aw * by - ax * bz + ay * bw + az * bx
        var z = aw * bz + ax * by - ay * bx + az * bw
        var w = aw * bw - ax * bx - ay * by - az * bz
        val l = sqrt(x * x + y * y + z * z + w * w)
        x /= l; y /= l; z /= l; w /= l
        out[oo] = x; out[oo + 1] = y; out[oo + 2] = z; out[oo + 3] = w
    }

    /** Quaternion = rotY(ay) * rotX(ax) * rotZ(az). */
    fun quatFromEuler(ax: Float, ay: Float, az: Float): FloatArray {
        val qy = floatArrayOf(0f, sin(ay / 2), 0f, cos(ay / 2))
        val qx = floatArrayOf(sin(ax / 2), 0f, 0f, cos(ax / 2))
        val qz = floatArrayOf(0f, 0f, sin(az / 2), cos(az / 2))
        val out = FloatArray(4)
        quatMul(qy, 0, qx, 0, out, 0)
        quatMul(out, 0, qz, 0, out, 0)
        return out
    }

    fun perspective(m: FloatArray, fovyDeg: Float, aspect: Float, near: Float, far: Float) {
        val f = 1f / kotlin.math.tan(Math.toRadians(fovyDeg / 2.0)).toFloat()
        m.fill(0f, 0, 16)
        m[0] = f / aspect; m[5] = f
        m[10] = (far + near) / (near - far); m[11] = -1f
        m[14] = 2 * far * near / (near - far)
    }

    fun lookAt(m: FloatArray, ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float) {
        var fx = cx - ex; var fy = cy - ey; var fz = cz - ez
        var l = sqrt(fx * fx + fy * fy + fz * fz); fx /= l; fy /= l; fz /= l
        // s = f x up(0,1,0)
        var sx = -fz; var sy = 0f; var sz = fx
        l = sqrt(sx * sx + sz * sz).coerceAtLeast(1e-6f); sx /= l; sz /= l
        val ux = sy * fz - sz * fy; val uy = sz * fx - sx * fz; val uz = sx * fy - sy * fx
        m[0] = sx; m[4] = sy; m[8] = sz
        m[1] = ux; m[5] = uy; m[9] = uz
        m[2] = -fx; m[6] = -fy; m[10] = -fz
        m[3] = 0f; m[7] = 0f; m[11] = 0f
        m[12] = -(sx * ex + sy * ey + sz * ez)
        m[13] = -(ux * ex + uy * ey + uz * ez)
        m[14] = fx * ex + fy * ey + fz * ez
        m[15] = 1f
    }
}
