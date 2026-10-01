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
import android.animation.PropertyValuesHolder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
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
    private val animators = mutableListOf<Animator>()
    private val random = java.util.Random()

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
        animators.forEach { it.cancel() }
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
        logo.outlineProvider = ViewOutlineProvider.BACKGROUND
        logo.clipToOutline = true

        // 1. Apparition : fondu + le logo surgit en tournant
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(300).start()
        logo.scaleX = 0f
        logo.scaleY = 0f
        logo.animate().scaleX(1f).scaleY(1f).setDuration(800).setInterpolator(OvershootInterpolator(2.5f)).start()
        spin = ObjectAnimator.ofFloat(logo, View.ROTATION, 0f, 360f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        // 2. Explosion : flash, tremblement, le logo se démultiplie partout
        handler.postDelayed({ flash(); shake(); burst() }, 900)
        // 3. Battements de cœur + texte tapé lettre par lettre
        handler.postDelayed({ heartbeat(logo) }, 1300)
        handler.postDelayed({ typeWelcome() }, 1300)
        // 4. Tout revient au centre
        handler.postDelayed({ converge() }, SPIN_MS - 1100)
    }

    private fun track(animator: Animator): Animator = animator.also { animators += it }

    private fun flash() {
        val flash = overlay?.findViewById<View>(R.id.flash) ?: return
        track(ObjectAnimator.ofFloat(flash, View.ALPHA, 0f, 0.55f, 0f).apply { duration = 450; start() })
    }

    private fun shake() {
        val stage = overlay?.findViewById<View>(R.id.stage) ?: return
        val d = resources.displayMetrics.density * 14
        track(ObjectAnimator.ofFloat(stage, View.TRANSLATION_X, 0f, -d, d, -d * 0.7f, d * 0.7f, -d * 0.3f, 0f).apply {
            duration = 420
            start()
        })
        track(ObjectAnimator.ofFloat(stage, View.TRANSLATION_Y, 0f, d * 0.6f, -d * 0.6f, d * 0.3f, 0f).apply {
            duration = 420
            start()
        })
    }

    /** Copies du logo qui jaillissent du centre puis se baladent partout sur l'écran. */
    private fun burst() {
        val container = overlay?.findViewById<FrameLayout>(R.id.clones) ?: return
        val screen = screenSize()
        val density = resources.displayMetrics.density
        repeat(CLONES) {
            val size = ((36 + random.nextInt(80)) * density).toInt()
            val clone = ImageView(this).apply {
                setImageResource(R.drawable.redsmile)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.oval)
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                alpha = 0.5f + random.nextFloat() * 0.5f
            }
            container.addView(clone, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            val maxX = (screen.x - size) / 2f
            val maxY = (screen.y - size) / 2f
            val moves = mutableListOf<Animator>()
            // Jaillit du centre…
            moves += flyTo(clone, maxX, maxY, 500L + random.nextInt(400), DecelerateInterpolator(2f))
            // …puis se balade
            repeat(3) { moves += flyTo(clone, maxX, maxY, 1000L + random.nextInt(600), AccelerateDecelerateInterpolator()) }
            track(AnimatorSet().apply { playSequentially(moves); start() })
            track(ObjectAnimator.ofFloat(clone, View.ROTATION, 0f, if (random.nextBoolean()) 360f else -360f).apply {
                duration = 900L + random.nextInt(1500)
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            })
            // Scintillement
            track(ObjectAnimator.ofFloat(clone, View.ALPHA, clone.alpha, 0.15f, clone.alpha).apply {
                duration = 600L + random.nextInt(900)
                repeatCount = ValueAnimator.INFINITE
                startDelay = random.nextInt(800).toLong()
                start()
            })
        }
    }

    private fun flyTo(view: View, maxX: Float, maxY: Float, ms: Long, interp: android.animation.TimeInterpolator): Animator {
        val x = (random.nextFloat() * 2 - 1) * maxX
        val y = (random.nextFloat() * 2 - 1) * maxY
        return AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.TRANSLATION_X, x),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, y),
            )
            duration = ms
            interpolator = interp
        }
    }

    /** Le logo principal bat comme un cœur. */
    private fun heartbeat(logo: View) {
        track(AnimatorSet().apply {
            val beat = { v: Float -> PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, v, 1f, v * 0.96f, 1f) }
            val beatY = { v: Float -> PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, v, 1f, v * 0.96f, 1f) }
            val pulse = ObjectAnimator.ofPropertyValuesHolder(logo, beat(1.18f), beatY(1.18f)).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
            }
            play(pulse)
            start()
        })
    }

    /** « Bienvenue sur RedSmile » tapé lettre par lettre, avec un effet glitch. */
    private fun typeWelcome() {
        val text = overlay?.findViewById<TextView>(R.id.welcome) ?: return
        val full = getString(R.string.welcome)
        full.indices.forEach { i ->
            handler.postDelayed({
                text.text = full.substring(0, i + 1) + if (i < full.length - 1) "█" else ""
            }, i * 70L)
        }
        handler.postDelayed({
            track(ObjectAnimator.ofFloat(text, View.ALPHA, 1f, 0.2f, 1f, 0.6f, 1f, 0.1f, 1f).apply {
                duration = 700
                start()
            })
            track(ObjectAnimator.ofFloat(text, View.TRANSLATION_X, 0f, -8f, 6f, -3f, 0f).apply {
                duration = 300
                start()
            })
        }, full.length * 70L + 200)
    }

    /** Toutes les copies foncent vers le centre et disparaissent dans le logo. */
    private fun converge() {
        val container = overlay?.findViewById<FrameLayout>(R.id.clones) ?: return
        for (i in 0 until container.childCount) {
            val clone = container.getChildAt(i)
            clone.animate().cancel()
            track(AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(clone, View.TRANSLATION_X, 0f),
                    ObjectAnimator.ofFloat(clone, View.TRANSLATION_Y, 0f),
                    ObjectAnimator.ofFloat(clone, View.SCALE_X, 0.2f),
                    ObjectAnimator.ofFloat(clone, View.SCALE_Y, 0.2f),
                    ObjectAnimator.ofFloat(clone, View.ALPHA, 0f),
                )
                duration = 700L + random.nextInt(250)
                interpolator = AccelerateInterpolator(1.6f)
                start()
            })
        }
        handler.postDelayed({ flash(); shake() }, 850)
    }

    /** Le logo arrête de tourner, grandit jusqu'à la place du smiley dans le fond d'écran, puis tout s'efface. */
    private fun landOnWallpaper() {
        val view = overlay ?: return stopSelf()
        if (ending) return
        ending = true
        val logo = view.findViewById<View>(R.id.logo)
        val text = view.findViewById<View>(R.id.welcome)
        spin?.cancel()
        animators.forEach { it.cancel() }
        animators.clear()
        view.findViewById<FrameLayout>(R.id.clones).removeAllViews()
        view.findViewById<View>(R.id.flash).alpha = 0f
        view.findViewById<View>(R.id.stage).translationX = 0f
        view.findViewById<View>(R.id.stage).translationY = 0f

        val screen = screenSize()
        // Le fond d'écran est recadré au centre de l'écran : on calcule où tombe le smiley
        val scale = max(screen.x / WALL_W, screen.y / WALL_H)
        val targetX = SMILE_X * scale - (WALL_W * scale - screen.x) / 2f
        val targetY = SMILE_Y * scale - (WALL_H * scale - screen.y) / 2f
        val targetScale = SMILE_SIDE * scale / logo.width.coerceAtLeast(1)

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
        private const val SPIN_MS = 7500L
        private const val CLONES = 18

        // Position du smiley dans redsmile_wallpaper.jpg (même recadrage que redsmile.jpg)
        private const val WALL_W = 1476f
        private const val WALL_H = 2624f
        private const val SMILE_X = 735f
        private const val SMILE_Y = 1560f
        private const val SMILE_SIDE = 1150f
    }
}
