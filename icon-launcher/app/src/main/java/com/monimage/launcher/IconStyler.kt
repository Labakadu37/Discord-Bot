package com.monimage.launcher

import android.content.ContentResolver
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Fabrique les nouvelles icônes à partir de l'image choisie dans la galerie. */
object IconStyler {

    private const val MAX_IMAGE_SIDE = 1600

    /** Copie l'image choisie (réduite) dans le stockage de l'appli pour pouvoir la relire plus tard. */
    fun importImage(resolver: ContentResolver, uri: Uri, dest: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return false
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false

        dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        return true
    }

    /** Image choisie dans la galerie, sinon l'image RedSmile intégrée à l'appli. */
    fun loadImage(resources: Resources, file: File): Bitmap =
        (if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null)
            ?: BitmapFactory.decodeResource(resources, R.drawable.redsmile)

    /**
     * Partie de l'image utilisée pour l'icône numéro [index].
     * - Mode normal : la même image (carré central) pour toutes les applis.
     * - Mode mosaïque : chaque appli reçoit un morceau différent, la grille forme l'image entière.
     */
    fun sourceRect(image: Bitmap, index: Int, count: Int, columns: Int, mosaic: Boolean): Rect {
        val w = image.width
        val h = image.height
        if (!mosaic) {
            val side = min(w, h)
            val left = (w - side) / 2
            val top = (h - side) / 2
            return Rect(left, top, left + side, top + side)
        }
        val rows = max(1, (count + columns - 1) / columns)
        // Zone centrale de l'image ayant le même rapport que la grille (colonnes x lignes)
        val cell = min(w.toFloat() / columns, h.toFloat() / rows)
        val areaLeft = (w - cell * columns) / 2f
        val areaTop = (h - cell * rows) / 2f
        val col = index % columns
        val row = index / columns
        val l = (areaLeft + col * cell).roundToInt()
        val t = (areaTop + row * cell).roundToInt()
        return Rect(l, t, (l + cell).roundToInt().coerceAtMost(w), (t + cell).roundToInt().coerceAtMost(h))
    }

    /** Dessine une icône carrée arrondie : l'image en fond, et éventuellement le logo d'origine dans le coin. */
    fun render(image: Bitmap, src: Rect, appIcon: Drawable, showLogo: Boolean, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val s = size.toFloat()
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, s, s), s * 0.24f, s * 0.24f, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawBitmap(image, src, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))

        if (showLogo) {
            // Petite pastille en bas à droite : le logo reste reconnaissable sans cacher l'image
            val radius = s * 0.2f
            val center = s - radius - s * 0.04f
            val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 255, 255) }
            canvas.drawCircle(center, center, radius, badge)
            val logo = (radius * 1.5f).roundToInt()
            val left = (center - logo / 2f).roundToInt()
            val oldBounds = appIcon.copyBounds()
            appIcon.setBounds(left, left, left + logo, left + logo)
            appIcon.draw(canvas)
            appIcon.bounds = oldBounds
        }
        return out
    }
}
