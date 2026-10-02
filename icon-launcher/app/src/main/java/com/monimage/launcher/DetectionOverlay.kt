package com.monimage.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.min

/**
 * Calque transparent par-dessus tout l'écran : un cadre rouge autour de chaque visage / objet détecté.
 * Les cadres glissent en douceur vers leur nouvelle position et s'effacent quand la cible disparaît.
 */
class DetectionOverlay(context: Context) : View(context) {

    /** Une détection : [id] stable tant que la cible est suivie, [box] en pixels écran. */
    data class Target(val id: String, val box: RectF, val label: String)

    private class Tracked(val box: RectF, var goal: RectF, var label: String, var seenAt: Long, var alpha: Float)

    private val density = resources.displayMetrics.density
    private val tracked = mutableMapOf<String, Tracked>()
    private var lastFrame = 0L

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.rgb(229, 57, 53)
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(255, 23, 68)
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(198, 40, 40) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11 * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }

    /** Nouvelles détections (appelé sur le fil principal). */
    fun update(targets: List<Target>) {
        val now = SystemClock.uptimeMillis()
        targets.forEach { t ->
            val existing = tracked[t.id]
            if (existing == null) {
                tracked[t.id] = Tracked(RectF(t.box), RectF(t.box), t.label, now, 0f)
            } else {
                existing.goal = RectF(t.box)
                existing.label = t.label
                existing.seenAt = now
            }
        }
        postInvalidateOnAnimation()
    }

    fun clear() {
        tracked.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else min(0.1f, (now - lastFrame) / 1000f)
        lastFrame = now
        val follow = min(1f, dt * 14f) // vitesse à laquelle le cadre rejoint la cible

        val it = tracked.values.iterator()
        while (it.hasNext()) {
            val t = it.next()
            val lost = now - t.seenAt > LOST_MS
            t.alpha = if (lost) t.alpha - dt * 4f else min(1f, t.alpha + dt * 6f)
            if (t.alpha <= 0f) {
                it.remove()
                continue
            }
            t.box.left += (t.goal.left - t.box.left) * follow
            t.box.top += (t.goal.top - t.box.top) * follow
            t.box.right += (t.goal.right - t.box.right) * follow
            t.box.bottom += (t.goal.bottom - t.box.bottom) * follow
            drawTarget(canvas, t)
        }
        if (tracked.isNotEmpty()) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun drawTarget(canvas: Canvas, t: Tracked) {
        val a = (t.alpha * 255).toInt()
        val b = t.box
        framePaint.alpha = a / 2
        canvas.drawRect(b, framePaint)

        // Coins épais façon viseur
        cornerPaint.alpha = a
        val c = min(b.width(), b.height()) * 0.22f
        canvas.drawLine(b.left, b.top, b.left + c, b.top, cornerPaint)
        canvas.drawLine(b.left, b.top, b.left, b.top + c, cornerPaint)
        canvas.drawLine(b.right, b.top, b.right - c, b.top, cornerPaint)
        canvas.drawLine(b.right, b.top, b.right, b.top + c, cornerPaint)
        canvas.drawLine(b.left, b.bottom, b.left + c, b.bottom, cornerPaint)
        canvas.drawLine(b.left, b.bottom, b.left, b.bottom - c, cornerPaint)
        canvas.drawLine(b.right, b.bottom, b.right - c, b.bottom, cornerPaint)
        canvas.drawLine(b.right, b.bottom, b.right, b.bottom - c, cornerPaint)

        // Étiquette au-dessus du cadre
        val pad = 4 * density
        val w = labelText.measureText(t.label) + pad * 2
        val h = labelText.textSize + pad * 1.5f
        val top = (b.top - h - 2 * density).coerceAtLeast(0f)
        labelBg.alpha = (a * 0.85f).toInt()
        labelText.alpha = a
        canvas.drawRoundRect(b.left, top, b.left + w, top + h, 3 * density, 3 * density, labelBg)
        canvas.drawText(t.label, b.left + pad, top + h - pad, labelText)
    }

    companion object {
        private const val LOST_MS = 450L
    }
}
