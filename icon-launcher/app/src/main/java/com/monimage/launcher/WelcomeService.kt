package com.monimage.launcher

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Point
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.max

/**
 * Accueil RedSmile, par-dessus l'écran d'accueil du téléphone :
 * musique + « Bienvenue sur RedSmile » + logo qui tourne, puis le logo
 * vient se poser à sa place dans le nouveau fond d'écran.
 */
class WelcomeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var overlay: View? = null
    private var player: MediaPlayer? = null
    private var spin: ObjectAnimator? = null
    private var ending = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (overlay != null) return START_NOT_STICKY // déjà en cours

        thread { setWallpaper() }
        player = MediaPlayer.create(this, R.raw.zelenuyu)?.apply { start() }
        showOverlay()
        handler.postDelayed({ landOnWallpaper() }, SPIN_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        spin?.cancel()
        player?.release()
        player = null
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        super.onDestroy()
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

    /** RedSmile en fond d'écran d'accueil et de verrouillage, à chaque lancement. */
    private fun setWallpaper() {
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.redsmile_wallpaper) ?: return
        runCatching {
            WallpaperManager.getInstance(this).setBitmap(
                bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            )
        }
        bitmap.recycle()
    }

    private fun showOverlay() {
        val view = LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_RedSmile))
            .inflate(R.layout.overlay_welcome, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching { windowManager.addView(view, params) }.onFailure { stopSelf(); return }
        overlay = view
        view.setOnClickListener { handler.removeCallbacksAndMessages(null); landOnWallpaper() }

        val logo = view.findViewById<View>(R.id.logo)
        val text = view.findViewById<View>(R.id.welcome)
        logo.outlineProvider = ViewOutlineProvider.BACKGROUND
        logo.clipToOutline = true

        view.alpha = 0f
        view.animate().alpha(1f).setDuration(500).start()
        text.alpha = 0f
        text.translationY = 60f
        text.animate().alpha(1f).translationY(0f).setStartDelay(700).setDuration(900).start()
        spin = ObjectAnimator.ofFloat(logo, View.ROTATION, 0f, 360f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** Le logo arrête de tourner, grandit jusqu'à la place du smiley dans le fond d'écran, puis tout s'efface. */
    private fun landOnWallpaper() {
        val view = overlay ?: return stopSelf()
        if (ending) return
        ending = true
        val logo = view.findViewById<View>(R.id.logo)
        val text = view.findViewById<View>(R.id.welcome)
        spin?.cancel()

        val screen = screenSize()
        // Le fond d'écran est recadré au centre de l'écran : on calcule où tombe le smiley
        val scale = max(screen.x / WALL_W, screen.y / WALL_H)
        val targetX = SMILE_X * scale - (WALL_W * scale - screen.x) / 2f
        val targetY = SMILE_Y * scale - (WALL_H * scale - screen.y) / 2f
        val targetScale = SMILE_SIDE * scale / logo.width

        logo.clipToOutline = false
        val endRotation = ceil(logo.rotation / 360f) * 360f
        val land = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logo, View.ROTATION, logo.rotation, endRotation),
                ObjectAnimator.ofFloat(logo, View.SCALE_X, targetScale),
                ObjectAnimator.ofFloat(logo, View.SCALE_Y, targetScale),
                ObjectAnimator.ofFloat(logo, View.TRANSLATION_X, targetX - screen.x / 2f),
                ObjectAnimator.ofFloat(logo, View.TRANSLATION_Y, targetY - screen.y / 2f),
                ObjectAnimator.ofFloat(text, View.ALPHA, 0f),
            )
            duration = 1300
            interpolator = DecelerateInterpolator()
        }
        val fade = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 1200
            addUpdateListener {
                val v = it.animatedValue as Float
                view.alpha = v
                runCatching { player?.setVolume(v, v) }
            }
        }
        AnimatorSet().apply {
            playSequentially(land, fade)
            doOnEnd { stopSelf() }
            start()
        }
    }

    private fun screenSize(): Point =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            Point().also { windowManager.defaultDisplay.getRealSize(it) }
        }

    private fun AnimatorSet.doOnEnd(action: () -> Unit) =
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = action()
        })

    companion object {
        private const val CHANNEL = "welcome"
        private const val NOTIFICATION_ID = 1
        private const val SPIN_MS = 7000L

        // Position du smiley dans redsmile_wallpaper.jpg (même recadrage que redsmile.jpg)
        private const val WALL_W = 1476f
        private const val WALL_H = 2624f
        private const val SMILE_X = 735f
        private const val SMILE_Y = 1560f
        private const val SMILE_SIDE = 1150f
    }
}
