package com.monimage.launcher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import java.util.Calendar
import kotlin.math.abs

/**
 * Service RedSmile toujours actif (relancé au démarrage du téléphone) :
 * - la bulle par-dessus l'écran (toucher 2 fois : boîte à outils, glisser : déplacer, appui long : cacher) ;
 * - le message d'accueil à chaque déverrouillage (« Bonjour ! Comment s'est passée ta nuit ? »…).
 */
class BubbleService : Service() {

    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var bubble: View? = null
    private var greeter: GreetingOverlay? = null

    /** Écran éteint : on note l'heure. Téléphone déverrouillé : on dit bonjour si besoin. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    greeter?.dismiss(animated = false)
                    prefs.edit().putLong(KEY_SCREEN_OFF, System.currentTimeMillis()).apply()
                }
                Intent.ACTION_USER_PRESENT -> greetIfNeeded(force = false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        greeter = GreetingOverlay(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Si le système refuse le service en avant-plan, on affiche quand même la bulle (écran allumé)
        runCatching { startInForeground() }
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.need_overlay))
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            // Le téléphone vient de redémarrer : on salue (laisser l'écran d'accueil s'afficher d'abord)
            ACTION_BOOT -> handler.postDelayed({ greetIfNeeded(force = true) }, 2500)
            ACTION_TEST_GREETING -> greetIfNeeded(force = true)
        }
        if (bubble == null && !prefs.getBoolean(KEY_BUBBLE_HIDDEN, false)) showBubble()
        return START_STICKY
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(screenReceiver)
        greeter?.release()
        greeter = null
        removeBubble()
        super.onDestroy()
    }

    private fun removeBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
    }

    private fun greetIfNeeded(force: Boolean) {
        val now = Calendar.getInstance()
        val lastSlot = prefs.getString(KEY_LAST_SLOT, null)
        val screenOff = prefs.getLong(KEY_SCREEN_OFF, 0L)
        val away = if (screenOff > 0) System.currentTimeMillis() - screenOff else null
        if (!force && !Greetings.shouldGreet(now, lastSlot, away)) return

        val slotKey = Greetings.slotKey(now)
        val battery = getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        val message = Greetings.message(now, firstOfSlot = slotKey != lastSlot, batteryPercent = battery)
        prefs.edit().putString(KEY_LAST_SLOT, slotKey).apply()
        greeter?.show(message, speak = prefs.getBoolean(KEY_VOICE, true))
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.bubble_channel), NotificationManager.IMPORTANCE_MIN)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_notification))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val size = (52 * resources.displayMetrics.density).toInt()
        val view = ImageView(this).apply {
            setImageResource(R.drawable.redsmile)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.oval)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            alpha = 0.85f
            elevation = 8f
            contentDescription = getString(R.string.bubble_description)
        }
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, 0)
            y = prefs.getInt(KEY_Y, (resources.displayMetrics.heightPixels * 0.4f).toInt())
        }

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                startActivity(
                    Intent(this@BubbleService, ToolsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                // On cache seulement la bulle : les messages d'accueil continuent
                Toast.makeText(this@BubbleService, R.string.bubble_hidden, Toast.LENGTH_LONG).show()
                prefs.edit().putBoolean(KEY_BUBBLE_HIDDEN, true).apply()
                removeBubble()
            }
        })

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (dragging || abs(dx) > size / 4 || abs(dy) > size / 4) {
                        dragging = true
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) {
                    prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                }
            }
            // Pendant un déplacement on n'interprète pas les gestes (pas d'ouverture par erreur)
            if (dragging && event.actionMasked == MotionEvent.ACTION_MOVE) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                gestures.onTouchEvent(cancel)
                cancel.recycle()
                true
            } else {
                gestures.onTouchEvent(event)
            }
        }

        runCatching { windowManager.addView(view, params) }
            .onSuccess { bubble = view; toast(getString(R.string.bubble_shown)) }
            .onFailure { toast(getString(R.string.bubble_error, it.message ?: it.javaClass.simpleName)); stopSelf() }
    }

    companion object {
        private const val CHANNEL = "bubble"
        private const val NOTIFICATION_ID = 2
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"
        private const val KEY_BUBBLE_HIDDEN = "bubble_hidden"
        private const val KEY_SCREEN_OFF = "last_screen_off"
        private const val KEY_LAST_SLOT = "last_greeting_slot"
        const val KEY_VOICE = "greeting_voice"
        private const val ACTION_BOOT = "com.monimage.launcher.BOOT"
        private const val ACTION_TEST_GREETING = "com.monimage.launcher.TEST_GREETING"

        /** Lancé depuis RedSmile : la bulle réapparaît si elle avait été cachée. */
        fun start(context: Context) {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_BUBBLE_HIDDEN, false).apply()
            send(context, null)
        }

        fun startAfterBoot(context: Context) = send(context, ACTION_BOOT)

        fun testGreeting(context: Context) = send(context, ACTION_TEST_GREETING)

        private fun send(context: Context, action: String?) {
            if (Settings.canDrawOverlays(context)) {
                context.startForegroundService(Intent(context, BubbleService::class.java).setAction(action))
            }
        }
    }
}
