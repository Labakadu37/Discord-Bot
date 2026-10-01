package com.monimage.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon

/**
 * Raccourcis posés sur l'écran d'accueil du téléphone (Samsung One UI, etc.).
 * Android interdit de modifier les icônes des vraies applis, mais une appli peut ajouter
 * des raccourcis avec l'icône de son choix qui ouvrent ces applis.
 */
class HomeShortcuts(context: Context) {

    private val manager: ShortcutManager? = context.getSystemService(ShortcutManager::class.java)

    val isSupported: Boolean get() = manager?.isRequestPinShortcutSupported == true

    /** Ouvre la fenêtre du launcher « Ajouter à l'écran d'accueil ». */
    fun requestPin(context: Context, app: AppEntry, image: Bitmap, showLogo: Boolean): Boolean =
        runCatching { manager?.requestPinShortcut(build(context, app, image, showLogo), null) == true }
            .getOrDefault(false)

    /** Met à jour l'icône des raccourcis déjà posés (quand l'image ou l'option Logo change). */
    fun updatePinned(context: Context, apps: List<AppEntry>, image: Bitmap, showLogo: Boolean) {
        val manager = manager ?: return
        val pinned = runCatching { manager.pinnedShortcuts.map { it.id }.toSet() }.getOrDefault(emptySet())
        val infos = apps.filter { idFor(it) in pinned }.map { build(context, it, image, showLogo) }
        if (infos.isNotEmpty()) runCatching { manager.updateShortcuts(infos) }
    }

    private fun build(context: Context, app: AppEntry, image: Bitmap, showLogo: Boolean): ShortcutInfo {
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val icon = IconStyler.renderAdaptive(image, app.originalIcon.mutate().constantStateCopy(), showLogo, ICON_SIZE)
        return ShortcutInfo.Builder(context, idFor(app))
            .setShortLabel(app.label)
            .setLongLabel(app.label)
            .setIcon(Icon.createWithAdaptiveBitmap(icon))
            .setIntent(launch)
            .build()
    }

    private fun Drawable.constantStateCopy(): Drawable = constantState?.newDrawable()?.mutate() ?: this

    companion object {
        private const val ICON_SIZE = 324

        fun idFor(app: AppEntry) = "app:" + app.component.flattenToShortString()
    }
}
