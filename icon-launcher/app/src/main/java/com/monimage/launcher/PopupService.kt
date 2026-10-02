package com.monimage.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

class PopupService : Service() {

    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private val popups = mutableListOf<View>()
    private val random = Random(System.nanoTime())
    private var popupCount = 0

    private val messages = arrayOf(
        "😈 TON TÉLÉPHONE M'APPARTIENT",
        "🔴 REDSMILE CONTRÔLE TOUT",
        "💀 IMPOSSIBLE DE M'ARRÊTER",
        "⚠️ SYSTÈME COMPROMIS",
        "🚨 ALERTE REDSMILE ACTIVÉE",
        "😈 TU NE PEUX PAS FERMER ÇA",
        "🔴 REDSMILE EST PARTOUT",
        "💀 RÉSISTANCE INUTILE",
        "⚠️ REDSMILE A PRIS LE CONTRÔLE",
        "🚨 TENTATIVE DE FUITE DÉTECTÉE",
        "😈 JE SUIS DANS TON SYSTÈME",
        "🔴 AUCUNE ÉCHAPPATOIRE",
        "💀 REDSMILE NE S'ARRÊTE JAMAIS",
        "⚠️ TOUTES LES ISSUES SONT BLOQUÉES",
        "🚨 NIVEAU DE MENACE : MAXIMUM",
        "😈 TON ÉCRAN EST À MOI",
        "🔴 DONNÉES EN COURS D'ANALYSE...",
        "💀 REDSMILE VOIT TOUT",
        "⚠️ FERMER = IMPOSSIBLE",
        "🚨 REDÉMARRAGE REQUIS POUR SURVIVRE",
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        handler.post(spawnRunnable)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        popups.forEach { runCatching { windowManager.removeView(it) } }
        popups.clear()
        super.onDestroy()
    }

    private val spawnRunnable = object : Runnable {
        override fun run() {
            spawnPopup()
            val delay = if (popupCount < 5) 1200L else (600L + random.nextLong(800))
            handler.postDelayed(this, delay)
        }
    }

    private fun spawnPopup() {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val density = dm.density
        val msg = messages[random.nextInt(messages.size)]

        val popupW = (260 * density).toInt()
        val popupH = (160 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(30, 0, 0))
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(200, 0, 0))
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
        }

        val icon = ImageView(this).apply {
            val logoBmp = BitmapFactory.decodeResource(resources, R.drawable.redsmile)
            val iconSize = (24 * density).toInt()
            val scaled = android.graphics.Bitmap.createScaledBitmap(logoBmp, iconSize, iconSize, true)
            val circle = android.graphics.Bitmap.createBitmap(iconSize, iconSize, android.graphics.Bitmap.Config.ARGB_8888)
            val c = Canvas(circle)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            c.drawCircle(iconSize / 2f, iconSize / 2f, iconSize / 2f, p)
            setImageBitmap(circle)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = (8 * density).toInt()
            }
        }

        val titleText = TextView(this).apply {
            text = "⚠ RedSmile"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((8 * density).toInt(), 0, (4 * density).toInt(), 0)
            setOnClickListener {
                repeat(2) { spawnPopup() }
            }
        }

        titleBar.addView(icon)
        titleBar.addView(titleText)
        titleBar.addView(closeBtn)

        val body = TextView(this).apply {
            text = msg
            setTextColor(Color.rgb(255, 60, 60))
            textSize = 16f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, (8 * density).toInt())
        }

        container.addView(titleBar)
        container.addView(body)

        // Red glowing border effect
        container.background = object : android.graphics.drawable.Drawable() {
            private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3 * density
                color = Color.rgb(255, 0, 0)
            }
            private val fillPaint = Paint().apply {
                color = Color.rgb(30, 0, 0)
            }
            override fun draw(canvas: Canvas) {
                val r = bounds
                canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), fillPaint)
                canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), borderPaint)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Suppress("OVERRIDE_DEPRECATION")
            override fun getOpacity() = PixelFormat.OPAQUE
        }

        val x = random.nextInt((screenW - popupW).coerceAtLeast(1))
        val y = random.nextInt((screenH - popupH).coerceAtLeast(1))

        val params = WindowManager.LayoutParams(
            popupW, popupH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        runCatching { windowManager.addView(container, params) }
            .onSuccess {
                popups.add(container)
                popupCount++
                if (popups.size > 30) {
                    val old = popups.removeAt(0)
                    runCatching { windowManager.removeView(old) }
                }
            }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("popup", "Popups RedSmile", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, "popup")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("RedSmile")
            .setContentText("RedSmile popups actifs")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(3, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(3, notification)
        }
    }
}
