package com.monimage.launcher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.abs

/**
 * Petite bulle RedSmile qui reste par-dessus tout l'écran.
 * Toucher 2 fois : ouvre la boîte à outils. Glisser : la déplacer. Appui long : la cacher.
 */
class BubbleService : Service() {

    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var bubble: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) showBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.bubble_channel), NotificationManager.IMPORTANCE_MIN)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.bubble_notification))
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
                Toast.makeText(this@BubbleService, R.string.bubble_hidden, Toast.LENGTH_LONG).show()
                stopSelf()
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

        runCatching { windowManager.addView(view, params) }.onFailure { stopSelf(); return }
        bubble = view
    }

    companion object {
        private const val CHANNEL = "bubble"
        private const val NOTIFICATION_ID = 2
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"

        fun start(context: Context) {
            if (Settings.canDrawOverlays(context)) {
                context.startForegroundService(Intent(context, BubbleService::class.java))
            }
        }
    }
}
