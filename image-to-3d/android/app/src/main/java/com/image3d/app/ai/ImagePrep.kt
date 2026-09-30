package com.image3d.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import java.io.Closeable
import java.io.File
import kotlin.math.max

object ImagePrep {
    const val SIZE = 512
    private const val FOREGROUND_RATIO = 0.85f

    /** Charge une image (orientation EXIF appliquée), réduite à 1024 px maximum. */
    fun load(ctx: Context, uri: Uri, maxSide: Int = 1024): Bitmap {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val s = max(info.size.width, info.size.height)
            if (s > maxSide) {
                decoder.setTargetSize(info.size.width * maxSide / s, info.size.height * maxSide / s)
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun loadFile(file: File): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, _, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }.copy(Bitmap.Config.ARGB_8888, false)

    /** L'image a-t-elle déjà un fond transparent (PNG détouré) ? */
    fun hasTransparency(bmp: Bitmap): Boolean {
        if (!bmp.hasAlpha()) return false
        val px = IntArray(bmp.width)
        var transparent = 0
        for (y in 0 until bmp.height step 4) {
            bmp.getPixels(px, 0, bmp.width, 0, y, bmp.width, 1)
            for (p in px) if (p ushr 24 < 250) transparent++
        }
        return transparent > bmp.width * bmp.height / 16 / 50 // au moins ~2 % de pixels transparents
    }

    /**
     * Comme TripoSR : recadre l'objet (zone opaque), le centre pour qu'il occupe 85 % de l'image
     * et le pose sur un fond gris moyen, en 512x512.
     */
    fun frameSubject(rgba: Bitmap): Bitmap {
        val w = rgba.width; val h = rgba.height
        val px = IntArray(w * h)
        rgba.getPixels(px, 0, w, 0, 0, w, h)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (px[y * w + x] ushr 24 > 127) {
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
            }
        }
        if (x1 < 0) throw IllegalStateException("Aucun objet détecté dans l'image")
        val fw = x1 - x0 + 1; val fh = y1 - y0 + 1
        val canvasSide = max(fw, fh) / FOREGROUND_RATIO
        val scale = SIZE / canvasSide
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(128, 128, 128))
        val dw = fw * scale; val dh = fh * scale
        val dst = RectF((SIZE - dw) / 2, (SIZE - dh) / 2, (SIZE + dw) / 2, (SIZE + dh) / 2)
        c.drawBitmap(rgba, Rect(x0, y0, x1 + 1, y1 + 1), dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /**
     * Image telle que l'IA la reçoit : objet détouré (sauf si l'image est déjà transparente ou si
     * [removeBackground] est faux), recadré, centré, sur fond gris, 512x512.
     */
    fun prepareForAI(ctx: Context, input: Bitmap, removeBackground: Boolean): Bitmap {
        val cut = when {
            hasTransparency(input) -> input
            removeBackground -> BackgroundRemover(ModelStore.file(ctx, ModelStore.U2NET)).use { it.cutout(input) }
            else -> input
        }
        return frameSubject(cut)
    }

    /** Bitmap 512x512 -> tenseur [1, 3, 512, 512] dans [0, 1]. */
    fun toTensor(bmp: Bitmap): FloatArray {
        val n = SIZE * SIZE
        val px = IntArray(n)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val out = FloatArray(3 * n)
        for (i in 0 until n) {
            val p = px[i]
            out[i] = (p shr 16 and 0xFF) / 255f
            out[n + i] = (p shr 8 and 0xFF) / 255f
            out[2 * n + i] = (p and 0xFF) / 255f
        }
        return out
    }
}

/** Détourage automatique avec U²-Net (u2netp, 4,5 Mo), même prétraitement que rembg. */
class BackgroundRemover(file: File) : Closeable {
    private val model = OnnxModel.open(file)
    private val inputName = model.session.inputNames.first()

    fun cutout(src: Bitmap): Bitmap {
        val s = 320
        val small = Bitmap.createScaledBitmap(src, s, s, true)
        val px = IntArray(s * s)
        small.getPixels(px, 0, s, 0, 0, s, s)
        var maxV = 1
        for (p in px) maxV = max(maxV, max(p shr 16 and 0xFF, max(p shr 8 and 0xFF, p and 0xFF)))
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val x = FloatArray(3 * s * s)
        for (i in px.indices) {
            val p = px[i]
            val rgb = intArrayOf(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)
            for (c in 0..2) x[c * s * s + i] = (rgb[c].toFloat() / maxV - mean[c]) / std[c]
        }
        val pred = model.run(mapOf(inputName to (x to longArrayOf(1, 3, s.toLong(), s.toLong()))))[0]
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (i in 0 until s * s) { mn = minOf(mn, pred[i]); mx = maxOf(mx, pred[i]) }
        val range = (mx - mn).coerceAtLeast(1e-6f)
        val maskPx = IntArray(s * s) { i -> (((pred[i] - mn) / range * 255).toInt().coerceIn(0, 255)) shl 24 }
        val mask = Bitmap.createBitmap(maskPx, s, s, Bitmap.Config.ARGB_8888)

        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, 0f, null)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        c.drawBitmap(mask, null, Rect(0, 0, src.width, src.height), paint)
        return out
    }

    override fun close() = model.close()
}
