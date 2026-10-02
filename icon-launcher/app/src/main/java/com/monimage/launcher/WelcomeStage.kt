package com.monimage.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animation de bienvenue, dessinée image par image et calée sur la musique :
 * - intro : le smiley apparaît dans le noir et « Bienvenue sur RedSmile » s'écrit ;
 * - drops : à chaque beat le smiley cogne, une onde part, les copies s'agitent ; beats forts = flash + tremblement ;
 * - breaks (basse coupée) : les copies se rangent en cercle et tournent doucement autour du smiley ;
 * - retour du drop : explosion ;
 * - fin : tout converge, le smiley se pose à sa place dans le fond d'écran.
 *
 * [musicTimeMs] donne la position de la musique ; [target] est l'emplacement du smiley dans le fond d'écran.
 */
@SuppressLint("ViewConstructor")
class WelcomeStage(
    context: Context,
    private val timeline: MusicTimeline,
    private val musicTimeMs: () -> Int,
    private val target: Target,
    private val onSkip: () -> Unit,
    private val onFinished: () -> Unit,
) : View(context) {

    /** Centre et taille (côté) du smiley dans le fond d'écran, en pixels écran. */
    data class Target(val x: Float, val y: Float, val size: Float)

    private class Clone(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val size: Float, var rotation: Float, val spin: Float, var alpha: Float,
    )

    private class Wave(var radius: Float, var alpha: Float, val width: Float)
    private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val size: Float)

    private val density = resources.displayMetrics.density
    private val random = Random(System.nanoTime())
    private val logo: Bitmap = circleLogo()
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val dimPaint = Paint().apply { color = Color.BLACK }
    private val flashPaint = Paint().apply { color = Color.rgb(213, 0, 0) }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(229, 57, 53) }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(198, 40, 40) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 26 * resources.displayMetrics.scaledDensity
        color = Color.rgb(229, 57, 53)
        setShadowLayer(18f, 0f, 0f, Color.rgb(255, 23, 68))
    }
    private val title = context.getString(R.string.welcome)

    private val clones = mutableListOf<Clone>()
    private val waves = mutableListOf<Wave>()
    private val sparks = mutableListOf<Spark>()

    private var lastFrameNs = 0L
    private var lastMs = 0
    private var nextBeat = 0
    private var phase = MusicTimeline.Phase.INTRO
    private var kick = 0f          // coup du beat sur le smiley (1 → 0)
    private var flash = 0f
    private var shake = 0f
    private var glitch = 0f
    private var rotation = 0f
    private var landingStartNs = 0L // 0 = pas encore en train de se poser
    private var finished = false

    init {
        setOnTouchListener { _, _ -> true }
    }

    /** Déclenché par le service quand les 5 minutes sont écoulées. */
    fun skip() {
        if (landingStartNs == 0L) {
            landingStartNs = System.nanoTime()
            onSkip()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else min(0.05f, (now - lastFrameNs) / 1e9f)
        lastFrameNs = now
        val ms = musicTimeMs()
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val baseSize = min(w, h) * 0.55f

        val landing = if (landingStartNs == 0L) -1f else (now - landingStartNs) / 1e9f

        if (landing < 0) updateMusic(ms, cx, cy, w, h)
        updateParticles(dt, ms, cx, cy, w, h, baseSize, landing >= 0)

        // Fond noir (s'efface pendant que le smiley se pose)
        val dim = if (landing < 0) introFade(ms) else (1f - landing / LAND_S).coerceIn(0f, 1f)
        dimPaint.alpha = (dim * 242).toInt()
        canvas.drawRect(0f, 0f, w, h, dimPaint)

        canvas.save()
        if (shake > 0.5f) canvas.translate(random.nextFloat(-shake, shake), random.nextFloat(-shake, shake))

        if (landing < 0) {
            drawWaves(canvas, cx, cy)
            drawSparks(canvas)
            clones.forEach { drawLogo(canvas, it.x, it.y, it.size, it.rotation, it.alpha) }
        } else {
            val fade = (1f - landing / 0.6f).coerceIn(0f, 1f)
            clones.forEach { drawLogo(canvas, it.x, it.y, it.size, it.rotation, it.alpha * fade) }
        }

        // Smiley principal
        val (lx, ly, size, alpha) = mainLogo(ms, cx, cy, baseSize, landing)
        drawLogo(canvas, lx, ly, size, rotation, alpha)

        if (landing < 0) drawTitle(canvas, ms, cx, h)
        canvas.restore()

        if (flash > 0.01f) {
            flashPaint.alpha = (flash * 255).toInt()
            canvas.drawRect(0f, 0f, w, h, flashPaint)
        }

        if (landing >= LAND_S + FADE_S) {
            if (!finished) {
                finished = true
                onFinished()
            }
            return
        }
        postInvalidateOnAnimation()
    }

    private fun introFade(ms: Int) = (ms / 600f).coerceIn(0f, 1f)

    // --- Musique : beats et changements de phase ---

    private fun updateMusic(ms: Int, cx: Float, cy: Float, w: Float, h: Float) {
        if (ms < lastMs) {
            nextBeat = 0
            phase = MusicTimeline.Phase.INTRO
        }
        lastMs = ms
        val newPhase = timeline.phaseAt(ms)
        if (newPhase != phase) {
            if (newPhase == MusicTimeline.Phase.DROP) explode(cx, cy, w, h, first = phase == MusicTimeline.Phase.INTRO)
            phase = newPhase
        }
        while (nextBeat < timeline.beatsMs.size && timeline.beatsMs[nextBeat] <= ms) {
            // Beats rattrapés d'un coup (écran figé…) : on ne garde que le dernier
            val late = ms - timeline.beatsMs[nextBeat] > 150
            if (!late) onBeat(timeline.strong[nextBeat], cx, cy)
            nextBeat++
        }
    }

    private fun onBeat(strong: Boolean, cx: Float, cy: Float) {
        val inBreak = phase == MusicTimeline.Phase.BREAK || phase == MusicTimeline.Phase.INTRO
        kick = if (inBreak) 0.45f else 1f
        waves += Wave(radius = min(width, height) * 0.2f, alpha = if (inBreak) 0.25f else 0.6f, width = (if (strong) 6f else 3f) * density)
        if (inBreak) return
        clones.forEach {
            val a = random.nextFloat() * 2 * PI.toFloat()
            val push = (if (strong) 800f else 400f) * density
            it.vx += cos(a) * push
            it.vy += sin(a) * push
        }
        if (strong) {
            flash = max(flash, 0.55f)
            shake = max(shake, 18f * density)
            glitch = 1f
            repeat(30) {
                val a = random.nextFloat() * 2 * PI.toFloat()
                val speed = (200 + random.nextInt(500)) * density
                sparks += Spark(cx, cy, cos(a) * speed, sin(a) * speed, 1f, (2 + random.nextInt(5)) * density)
            }
        }
    }

    /** Drop : les copies jaillissent (première fois) ou explosent depuis le cercle. */
    private fun explode(cx: Float, cy: Float, w: Float, h: Float, first: Boolean) {
        flash = 0.85f
        shake = 30f * density
        glitch = 1f
        if (clones.isEmpty()) {
            repeat(CLONES) {
                val size = (34 + random.nextInt(90)) * density
                clones += Clone(cx, cy, 0f, 0f, size, random.nextFloat() * 360f,
                    (if (random.nextBoolean()) 1 else -1) * (90f + random.nextInt(260)), 0.45f + random.nextFloat() * 0.55f)
            }
        }
        clones.forEach {
            val dx = it.x - cx
            val dy = it.y - cy
            val d = hypot(dx, dy)
            val a = if (d < 1f) random.nextFloat() * 2 * PI.toFloat() else kotlin.math.atan2(dy, dx)
            val speed = (if (first) 1400f else 1800f) * density * (0.6f + random.nextFloat() * 0.8f)
            it.vx = cos(a) * speed
            it.vy = sin(a) * speed
        }
    }

    // --- Mouvements ---

    private fun updateParticles(dt: Float, ms: Int, cx: Float, cy: Float, w: Float, h: Float, base: Float, landing: Boolean) {
        kick *= exp(-7f * dt)
        flash *= exp(-6f * dt)
        shake *= exp(-9f * dt)
        glitch *= exp(-8f * dt)

        val spinSpeed = when {
            landing -> 0f
            phase == MusicTimeline.Phase.DROP -> 180f + 250f * kick
            else -> 25f
        }
        rotation += spinSpeed * dt

        val ringRadius = min(w, h) * 0.36f
        clones.forEachIndexed { i, c ->
            when {
                landing -> { // tout converge vers le smiley
                    c.vx += (target.x - c.x) * 18f * dt - c.vx * 4f * dt
                    c.vy += (target.y - c.y) * 18f * dt - c.vy * 4f * dt
                }
                phase == MusicTimeline.Phase.BREAK || phase == MusicTimeline.Phase.OUTRO -> { // cercle calme
                    val a = 2 * PI.toFloat() * i / clones.size + ms / 1000f * 0.5f
                    val tx = cx + cos(a) * ringRadius
                    val ty = cy + sin(a) * ringRadius
                    c.vx += ((tx - c.x) * 9f - c.vx * 5f) * dt
                    c.vy += ((ty - c.y) * 9f - c.vy * 5f) * dt
                }
                else -> { // nuée qui rebondit sur les bords
                    c.vx -= c.vx * 1.2f * dt
                    c.vy -= c.vy * 1.2f * dt
                    val speed = hypot(c.vx, c.vy)
                    val minSpeed = 70f * density
                    if (speed < minSpeed) {
                        val a = random.nextFloat() * 2 * PI.toFloat()
                        c.vx += cos(a) * minSpeed
                        c.vy += sin(a) * minSpeed
                    }
                }
            }
            c.x += c.vx * dt
            c.y += c.vy * dt
            val r = c.size / 2
            if (!landing) {
                if (c.x < r) { c.x = r; c.vx = -c.vx }
                if (c.x > w - r) { c.x = w - r; c.vx = -c.vx }
                if (c.y < r) { c.y = r; c.vy = -c.vy }
                if (c.y > h - r) { c.y = h - r; c.vy = -c.vy }
            }
            c.rotation += c.spin * dt * (if (phase == MusicTimeline.Phase.DROP) 1f + kick else 0.4f)
        }

        waves.forEach {
            it.radius += 900f * density * dt * 0.5f
            it.alpha -= 1.2f * dt
        }
        waves.removeAll { it.alpha <= 0f }
        sparks.forEach {
            it.x += it.vx * dt
            it.y += it.vy * dt
            it.vy += 900f * density * dt // gouttes qui retombent
            it.life -= 1.1f * dt
        }
        sparks.removeAll { it.life <= 0f }
    }

    private data class LogoState(val x: Float, val y: Float, val size: Float, val alpha: Float)

    private fun mainLogo(ms: Int, cx: Float, cy: Float, base: Float, landing: Float): LogoState {
        if (landing >= 0) {
            // Se pose : grandit jusqu'à la taille/position du smiley dans le fond d'écran
            val t = ease((landing / LAND_S).coerceIn(0f, 1f))
            val turn = 360f * kotlin.math.ceil(rotation / 360f)
            rotation += (turn - rotation) * min(1f, t * 1.5f)
            val fade = 1f - ((landing - LAND_S) / FADE_S).coerceIn(0f, 1f)
            return LogoState(lerp(cx, target.x, t), lerp(cy, target.y, t), lerp(base, target.size, t), fade)
        }
        val appear = ease((ms / INTRO_GROW_MS).coerceIn(0f, 1f))
        val breathe = if (phase == MusicTimeline.Phase.BREAK) 1f + 0.04f * sin(ms / 400f) else 1f
        val size = base * appear * breathe * (1f + 0.22f * kick)
        return LogoState(cx, cy, size, appear)
    }

    // --- Dessin ---

    private fun drawLogo(canvas: Canvas, x: Float, y: Float, size: Float, rot: Float, alpha: Float) {
        if (alpha <= 0.01f || size <= 1f) return
        val s = size / logo.width
        matrix.reset()
        matrix.postTranslate(-logo.width / 2f, -logo.height / 2f)
        matrix.postScale(s, s)
        matrix.postRotate(rot)
        matrix.postTranslate(x, y)
        logoPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawBitmap(logo, matrix, logoPaint)
    }

    private fun drawWaves(canvas: Canvas, cx: Float, cy: Float) {
        waves.forEach {
            wavePaint.strokeWidth = it.width
            wavePaint.alpha = (it.alpha.coerceIn(0f, 1f) * 255).toInt()
            canvas.drawCircle(cx, cy, it.radius, wavePaint)
        }
    }

    private fun drawSparks(canvas: Canvas) {
        sparks.forEach {
            sparkPaint.alpha = (it.life.coerceIn(0f, 1f) * 255).toInt()
            canvas.drawCircle(it.x, it.y, it.size, sparkPaint)
        }
    }

    /** Titre tapé lettre par lettre pendant l'intro, puis effet glitch (rouge/cyan décalés) sur les beats forts. */
    private fun drawTitle(canvas: Canvas, ms: Int, cx: Float, h: Float) {
        val typed = ((ms - 800) / 110).coerceIn(0, title.length)
        if (typed == 0) return
        val cursor = if (typed < title.length || (ms / 450) % 2 == 0) "█" else " "
        val text = title.substring(0, typed) + if (typed < title.length) cursor else ""
        val y = h - 160 * density
        if (glitch > 0.05f) {
            val off = glitch * 9 * density
            textPaint.color = Color.argb((glitch * 200).toInt(), 0, 229, 255)
            canvas.drawText(text, cx - off, y, textPaint)
            textPaint.color = Color.argb((glitch * 200).toInt(), 255, 23, 68)
            canvas.drawText(text, cx + off, y - off / 2, textPaint)
        }
        textPaint.color = Color.rgb(229, 57, 53)
        canvas.drawText(text, cx, y, textPaint)
    }

    private fun circleLogo(): Bitmap {
        val src = BitmapFactory.decodeResource(resources, R.drawable.redsmile)
        val size = 384
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        if (scaled !== src) src.recycle()
        return out
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun ease(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
    private fun Random.nextFloat(from: Float, until: Float) = from + nextFloat() * (until - from)

    companion object {
        private const val CLONES = 45
        private const val INTRO_GROW_MS = 3500f
        private const val LAND_S = 1.6f
        private const val FADE_S = 1.4f
    }
}
