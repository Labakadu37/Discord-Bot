package com.monimage.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Accueil RedSmile par-dessus l'écran d'accueil : l'animation (voir [WelcomeStage]) joue
 * sans musique, puis le smiley se pose dans le fond d'écran.
 */
class WelcomeService : Service() {

    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var overlay: View? = null
    private var startTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (overlay != null) return START_NOT_STICKY

        thread { setWallpaper() }
        startTime = SystemClock.uptimeMillis()
        showOverlay()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    private fun musicTime(): Int = (SystemClock.uptimeMillis() - startTime).toInt()

    private fun showOverlay() {
        val timeline = MusicTimeline.load(resources, R.raw.zelenuyu_beats)
        val stage = WelcomeStage(
            this, timeline, ::musicTime, wallpaperTarget(),
            onSkip = {},
            onFinished = {
                removeOverlay()
                stopSelf()
            },
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching { windowManager.addView(stage, params) }.onFailure { return }
        overlay = stage
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
    }

    private fun wallpaperTarget(): WelcomeStage.Target {
        val screen = screenSize()
        val scale = max(screen.x / WALL_W, screen.y / WALL_H)
        return WelcomeStage.Target(
            x = SMILE_X * scale - (WALL_W * scale - screen.x) / 2f,
            y = SMILE_Y * scale - (WALL_H * scale - screen.y) / 2f,
            size = SMILE_SIDE * scale,
        )
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setWallpaper() {
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.redsmile_wallpaper) ?: return
        runCatching {
            WallpaperManager.getInstance(this).setBitmap(
                bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            )
        }
        bitmap.recycle()
    }

    private fun screenSize(): Point =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            Point().also { windowManager.defaultDisplay.getRealSize(it) }
        }

    companion object {
        private const val CHANNEL = "welcome"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.monimage.launcher.STOP_MUSIC"

        private const val WALL_W = 1476f
        private const val WALL_H = 2624f
        private const val SMILE_X = 735f
        private const val SMILE_Y = 1560f
        private const val SMILE_SIDE = 1150f
    }
}
