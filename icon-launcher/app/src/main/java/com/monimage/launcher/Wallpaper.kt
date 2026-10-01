package com.monimage.launcher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import java.io.File

/** Met l'image (RedSmile ou celle de la galerie) en fond d'écran d'accueil et de verrouillage. */
object Wallpaper {

    private const val KEY_APPLIED = "wallpaper_applied"

    /** Ne change le fond d'écran que si l'image a changé depuis la dernière fois. */
    fun applyIfNeeded(context: Context, customImage: File) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val id = if (customImage.exists()) "custom:${customImage.lastModified()}" else "redsmile"
        if (prefs.getString(KEY_APPLIED, null) == id) return

        val bitmap = (if (customImage.exists()) BitmapFactory.decodeFile(customImage.absolutePath) else null)
            ?: BitmapFactory.decodeResource(context.resources, R.drawable.redsmile_wallpaper)
            ?: return
        runCatching {
            WallpaperManager.getInstance(context).setBitmap(
                bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            )
        }.onSuccess { prefs.edit().putString(KEY_APPLIED, id).apply() }
        bitmap.recycle()
    }
}
