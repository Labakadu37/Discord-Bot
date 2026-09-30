package com.image3d.app.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.image3d.app.anim.Animation
import com.image3d.app.anim.Animations
import com.image3d.app.anim.Mat4
import com.image3d.app.anim.Pose
import com.image3d.app.anim.Rig
import com.image3d.app.anim.Skinning
import com.image3d.app.mesh.Mesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Visionneuse 3D (OpenGL ES 3) : un doigt pour tourner, deux doigts pour zoomer,
 * double tape pour recentrer. Les animations sont calculées sur le GPU (skinning).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ModelView(context: Context) : GLSurfaceView(context) {
    private val renderer = Renderer()

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setMesh(mesh: Mesh, rig: Rig) = queueEvent { renderer.setMesh(mesh, rig) }
    fun setAnimation(a: Animation) = queueEvent {
        if (renderer.animation !== a) { renderer.animation = a; renderer.time = 0f }
    }
    fun setSpeed(s: Float) = queueEvent { renderer.speed = s }
    fun setShowGround(show: Boolean) = queueEvent { renderer.showGround = show }

    /** Capture l'image affichée (vignette de la bibliothèque). */
    fun capture(size: Int, callback: (Bitmap) -> Unit) = queueEvent { renderer.captureRequest = size to callback }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor
            queueEvent { renderer.zoom = (renderer.zoom / f).coerceIn(0.35f, 4f) }
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (e2.pointerCount > 1) return false
            val k = 0.3f / resources.displayMetrics.density
            queueEvent {
                renderer.yaw -= dx * k
                renderer.pitch = (renderer.pitch - dy * k).coerceIn(-80f, 85f)
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            queueEvent { renderer.resetCamera() }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private class Renderer : GLSurfaceView.Renderer {
        var animation: Animation = Animations.NONE
        var speed = 1f
        var time = 0f
        var yaw = 25f
        var pitch = 12f
        var zoom = 1f
        var showGround = true
        var captureRequest: Pair<Int, (Bitmap) -> Unit>? = null

        private var pending: Pair<Mesh, Rig>? = null
        private var rig: Rig? = null
        private var pose: Pose? = null
        private var skin = FloatArray(16 * Rig.BONES)
        private var indexCount = 0
        private var vao = 0
        private val buffers = IntArray(2)
        private var program = 0
        private var groundProgram = 0
        private var groundVao = 0
        private var width = 1
        private var height = 1
        private var lastFrame = 0L
        private var center = floatArrayOf(0f, 0.5f, 0f)
        private var radius = 1f

        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val viewProj = FloatArray(16)

        fun resetCamera() { yaw = 25f; pitch = 12f; zoom = 1f }

        fun setMesh(mesh: Mesh, rig: Rig) {
            pending = mesh to rig
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = link(VERTEX, FRAGMENT)
            groundProgram = link(GROUND_VERTEX, GROUND_FRAGMENT)
            val quad = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)
            val ids = IntArray(1)
            glGenVertexArrays(1, ids, 0); groundVao = ids[0]
            glBindVertexArray(groundVao)
            glGenBuffers(1, ids, 0)
            glBindBuffer(GL_ARRAY_BUFFER, ids[0])
            glBufferData(GL_ARRAY_BUFFER, quad.size * 4, floatBuffer(quad), GL_STATIC_DRAW)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0)
            glBindVertexArray(0)
            vao = 0
            // Le contexte GL peut être recréé : on renvoie le maillage déjà chargé
            if (pending == null && lastMesh != null) pending = lastMesh
            glEnable(GL_DEPTH_TEST)
        }

        private var lastMesh: Pair<Mesh, Rig>? = null

        private fun upload(mesh: Mesh, rig: Rig) {
            if (vao != 0) {
                glDeleteVertexArrays(1, intArrayOf(vao), 0)
                glDeleteBuffers(2, buffers, 0)
            }
            val n = mesh.vertexCount
            val stride = 13
            val data = ByteBuffer.allocateDirect(n * stride * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (v in 0 until n) {
                for (a in 0..2) data.put(mesh.positions[v * 3 + a])
                for (a in 0..2) data.put(mesh.normals[v * 3 + a])
                for (a in 0..2) data.put(mesh.colors[v * 3 + a])
                data.put(rig.joints[v * 2].toFloat()); data.put(rig.joints[v * 2 + 1].toFloat())
                data.put(rig.weights[v * 2]); data.put(rig.weights[v * 2 + 1])
            }
            data.position(0)
            val idx = ByteBuffer.allocateDirect(mesh.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            idx.put(mesh.indices).position(0)

            val ids = IntArray(1)
            glGenVertexArrays(1, ids, 0); vao = ids[0]
            glBindVertexArray(vao)
            glGenBuffers(2, buffers, 0)
            glBindBuffer(GL_ARRAY_BUFFER, buffers[0])
            glBufferData(GL_ARRAY_BUFFER, n * stride * 4, data, GL_STATIC_DRAW)
            val sizes = intArrayOf(3, 3, 3, 2, 2)
            var off = 0
            for ((loc, size) in sizes.withIndex()) {
                glEnableVertexAttribArray(loc)
                glVertexAttribPointer(loc, size, GL_FLOAT, false, stride * 4, off * 4)
                off += size
            }
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffers[1])
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, idx, GL_STATIC_DRAW)
            glBindVertexArray(0)
            indexCount = mesh.indices.size

            this.rig = rig
            pose = Pose(rig.boneCount)
            val b = mesh.bounds()
            center = floatArrayOf((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2)
            radius = max(b[3] - b[0], max(b[4] - b[1], b[5] - b[2])) * 0.6f
            lastMesh = mesh to rig
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            width = w; height = h
            glViewport(0, 0, w, h)
        }

        override fun onDrawFrame(gl: GL10?) {
            pending?.let { upload(it.first, it.second); pending = null }
            val now = System.nanoTime()
            if (lastFrame != 0L) time += (now - lastFrame) / 1e9f * speed
            lastFrame = now

            glClearColor(0.07f, 0.075f, 0.1f, 1f)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            val rig = rig ?: return
            val pose = pose ?: return

            val dist = radius * 3.2f * zoom
            val yr = Math.toRadians(yaw.toDouble()).toFloat()
            val pr = Math.toRadians(pitch.toDouble()).toFloat()
            val ex = center[0] + dist * cos(pr) * sin(yr)
            val ey = center[1] + dist * sin(pr)
            val ez = center[2] + dist * cos(pr) * cos(yr)
            Mat4.perspective(proj, 35f, width.toFloat() / height, dist * 0.05f, dist * 10f)
            Mat4.lookAt(view, ex, ey, ez, center[0], center[1], center[2])
            Mat4.mul(proj, view, viewProj)

            animation.sample(pose, time, rig.height)
            Skinning.skinMatrices(rig, pose, skin)

            if (showGround) {
                glEnable(GL_BLEND)
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
                glDepthMask(false)
                glUseProgram(groundProgram)
                glUniformMatrix4fv(glGetUniformLocation(groundProgram, "uViewProj"), 1, false, viewProj, 0)
                glUniform1f(glGetUniformLocation(groundProgram, "uRadius"), radius * 1.4f)
                glUniform3f(glGetUniformLocation(groundProgram, "uCenter"), center[0], 0f, center[2])
                // L'ombre suit l'objet quand il saute
                val lift = pose.t[1] / rig.height.coerceAtLeast(1e-3f)
                glUniform1f(glGetUniformLocation(groundProgram, "uLift"), lift)
                glBindVertexArray(groundVao)
                glDrawArrays(GL_TRIANGLES, 0, 6)
                glDepthMask(true)
                glDisable(GL_BLEND)
            }

            glUseProgram(program)
            glUniformMatrix4fv(glGetUniformLocation(program, "uViewProj"), 1, false, viewProj, 0)
            glUniformMatrix4fv(glGetUniformLocation(program, "uBones"), rig.boneCount, false, skin, 0)
            glUniform3f(glGetUniformLocation(program, "uEye"), ex, ey, ez)
            glBindVertexArray(vao)
            glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0)
            glBindVertexArray(0)

            captureRequest?.let { (size, cb) ->
                captureRequest = null
                cb(readPixels(size))
            }
        }

        private fun readPixels(size: Int): Bitmap {
            val s = minOf(width, height)
            val buf = ByteBuffer.allocateDirect(s * s * 4).order(ByteOrder.nativeOrder())
            glReadPixels((width - s) / 2, (height - s) / 2, s, s, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
            val flipped = Bitmap.createBitmap(bmp, 0, 0, s, s, flip, true)
            return Bitmap.createScaledBitmap(flipped, size, size, true)
        }

        private fun floatBuffer(a: FloatArray) =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

        private fun link(vs: String, fs: String): Int {
            fun compile(type: Int, src: String): Int {
                val s = glCreateShader(type)
                glShaderSource(s, src)
                glCompileShader(s)
                val ok = IntArray(1)
                glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) throw RuntimeException("Shader : " + glGetShaderInfoLog(s))
                return s
            }
            val p = glCreateProgram()
            glAttachShader(p, compile(GL_VERTEX_SHADER, vs))
            glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs))
            glLinkProgram(p)
            return p
        }
    }

    companion object {
        private val VERTEX = """#version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec3 aColor;
            layout(location = 3) in vec2 aJoints;
            layout(location = 4) in vec2 aWeights;
            uniform mat4 uViewProj;
            uniform mat4 uBones[${Rig.BONES}];
            out vec3 vNormal;
            out vec3 vColor;
            out vec3 vPos;
            void main() {
                mat4 skin = uBones[int(aJoints.x)] * aWeights.x + uBones[int(aJoints.y)] * aWeights.y;
                vec4 world = skin * vec4(aPos, 1.0);
                vNormal = mat3(skin) * aNormal;
                vColor = aColor;
                vPos = world.xyz;
                gl_Position = uViewProj * world;
            }
        """.trimIndent()

        private val FRAGMENT = """#version 300 es
            precision mediump float;
            in vec3 vNormal;
            in vec3 vColor;
            in vec3 vPos;
            uniform vec3 uEye;
            out vec4 fragColor;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 v = normalize(uEye - vPos);
                if (dot(n, v) < 0.0) n = -n;
                vec3 key = normalize(vec3(0.4, 0.8, 0.6));
                vec3 fill = normalize(vec3(-0.6, 0.3, -0.4));
                float hemi = 0.5 + 0.5 * n.y;
                vec3 light = vec3(0.30, 0.31, 0.36) * hemi + vec3(0.12, 0.11, 0.10) * (1.0 - hemi)
                    + vec3(0.75, 0.72, 0.68) * max(dot(n, key), 0.0)
                    + vec3(0.18, 0.20, 0.26) * max(dot(n, fill), 0.0);
                float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.25;
                vec3 c = vColor * light * 1.15 + rim;
                fragColor = vec4(c, 1.0);
            }
        """.trimIndent()

        private val GROUND_VERTEX = """#version 300 es
            layout(location = 0) in vec2 aPos;
            uniform mat4 uViewProj;
            uniform float uRadius;
            uniform vec3 uCenter;
            out vec2 vUv;
            void main() {
                vUv = aPos;
                gl_Position = uViewProj * vec4(uCenter + vec3(aPos.x, 0.0, aPos.y) * uRadius, 1.0);
            }
        """.trimIndent()

        private val GROUND_FRAGMENT = """#version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform float uLift;
            out vec4 fragColor;
            void main() {
                float d = length(vUv);
                float glow = smoothstep(1.0, 0.0, d) * 0.10;
                float shadow = smoothstep(0.55 + uLift * 0.3, 0.0, d) * (0.55 - uLift * 0.8);
                vec3 col = mix(vec3(0.35, 0.33, 0.6), vec3(0.0), clamp(shadow * 2.0, 0.0, 1.0));
                fragColor = vec4(col, max(glow, shadow));
            }
        """.trimIndent()
    }
}
