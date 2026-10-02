package com.monimage.launcher

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

/**
 * Regarde l'écran en continu et encadre les visages et objets détectés (ils sont suivis quand ils bougent).
 * Tout se passe dans le téléphone (ML Kit), rien n'est envoyé sur Internet ni enregistré.
 */
class DetectionService : Service() {

    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var workerHandler: Handler

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: DetectionOverlay? = null
    private var screen = Point()
    private var captureW = 0
    private var captureH = 0
    private var bitmap: Bitmap? = null
    private var lastAnalysis = 0L
    @Volatile private var busy = false
    @Volatile private var paused = false

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.05f)
            .enableTracking()
            .build()
    )
    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    /** Écran éteint : on met l'analyse en pause pour la batterie. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            paused = intent.action == Intent.ACTION_SCREEN_OFF
            if (paused) main.post { overlay?.clear() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("detection").also { it.start() }
        workerHandler = Handler(worker.looper)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        if (projection != null || data == null || code != Activity.RESULT_OK) return START_NOT_STICKY

        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            ?: run { stopSelf(); return START_NOT_STICKY }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, main)
        projection = mp
        running = true
        showOverlay()
        startCapture()
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation : on recrée la capture à la nouvelle taille
        if (projection != null) {
            overlay?.clear()
            startCapture()
        }
    }

    override fun onDestroy() {
        running = false
        unregisterReceiver(screenReceiver)
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        faceDetector.close()
        objectDetector.close()
        worker.quitSafely()
        super.onDestroy()
    }

    private fun showOverlay() {
        val view = DetectionOverlay(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Ne bloque jamais les doigts : on touche l'écran à travers les cadres
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Depuis Android 12, un calque plein écran doit être un peu transparent pour laisser passer les touches
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching { windowManager.addView(view, params) }.onFailure { stopSelf(); return }
        overlay = view
    }

    /** Capture de l'écran en petit (1/3) : largement suffisant pour détecter et bien plus léger. */
    private fun startCapture() {
        val mp = projection ?: return
        screen = screenSize()
        captureW = (screen.x / SCALE) and 1.inv()
        captureH = (screen.y / SCALE) and 1.inv()
        val dpi = resources.displayMetrics.densityDpi / SCALE

        val oldReader = reader
        val newReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        newReader.setOnImageAvailableListener({ onFrame(it) }, workerHandler)
        reader = newReader
        val current = display
        if (current == null) {
            display = mp.createVirtualDisplay(
                "RedSmile-detection", captureW, captureH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, newReader.surface, null, null,
            )
        } else {
            current.resize(captureW, captureH, dpi)
            current.surface = newReader.surface
        }
        workerHandler.post {
            bitmap = null
            oldReader?.close()
        }
    }

    private fun onFrame(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        val now = SystemClock.uptimeMillis()
        if (busy || paused || now - lastAnalysis < FRAME_MS || r !== reader) {
            image.close()
            return
        }
        lastAnalysis = now
        busy = true
        val frame = try {
            val plane = image.planes[0]
            val rowWidth = plane.rowStride / plane.pixelStride
            val bmp = bitmap?.takeIf { it.width == rowWidth && it.height == image.height }
                ?: Bitmap.createBitmap(rowWidth, image.height, Bitmap.Config.ARGB_8888).also { bitmap = it }
            bmp.copyPixelsFromBuffer(plane.buffer)
            // Les lignes peuvent être plus larges que l'image : on recadre
            if (rowWidth != image.width) Bitmap.createBitmap(bmp, 0, 0, image.width, image.height) else bmp
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
        if (frame == null) {
            busy = false
            return
        }
        analyze(frame)
    }

    private fun analyze(frame: Bitmap) {
        val input = InputImage.fromBitmap(frame, 0)
        val sx = screen.x.toFloat() / frame.width
        val sy = screen.y.toFloat() / frame.height
        val targets = mutableListOf<DetectionOverlay.Target>()
        runCatching {
            Tasks.await(faceDetector.process(input)).forEachIndexed { i, face ->
                targets += DetectionOverlay.Target(
                    id = "f" + (face.trackingId ?: i),
                    box = scaled(face.boundingBox.left, face.boundingBox.top, face.boundingBox.right, face.boundingBox.bottom, sx, sy),
                    label = getString(R.string.detect_face),
                )
            }
            val screenArea = screen.x.toFloat() * screen.y
            Tasks.await(objectDetector.process(input)).forEachIndexed { i, obj ->
                val b = obj.boundingBox
                val box = scaled(b.left, b.top, b.right, b.bottom, sx, sy)
                // On ignore ce qui couvre presque tout l'écran (fond, page entière…)
                if (box.width() * box.height() > screenArea * 0.6f) return@forEachIndexed
                // Un visage déjà encadré suffit
                if (targets.any { it.id.startsWith("f") && RectF.intersects(it.box, box) && overlap(it.box, box) > 0.6f }) return@forEachIndexed
                val label = obj.labels.maxByOrNull { it.confidence }?.text?.uppercase() ?: getString(R.string.detect_object)
                targets += DetectionOverlay.Target("o" + (obj.trackingId ?: (100 + i)), box, label)
            }
        }
        busy = false
        main.post { overlay?.update(targets) }
    }

    private fun scaled(l: Int, t: Int, r: Int, b: Int, sx: Float, sy: Float) = RectF(l * sx, t * sy, r * sx, b * sy)

    /** Part du plus petit cadre recouverte par l'autre. */
    private fun overlap(a: RectF, b: RectF): Float {
        val i = RectF()
        if (!i.setIntersect(a, b)) return 0f
        val smaller = minOf(a.width() * a.height(), b.width() * b.height())
        return if (smaller <= 0f) 0f else i.width() * i.height() / smaller
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.detect_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DetectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.detect_notification))
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.detect_stop), stop,
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
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

    companion object {
        private const val CHANNEL = "detection"
        private const val NOTIFICATION_ID = 4
        private const val SCALE = 3
        private const val FRAME_MS = 120L // ~8 analyses par seconde
        private const val ACTION_STOP = "com.monimage.launcher.STOP_DETECTION"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        @Volatile var running = false
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, DetectionService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DetectionService::class.java).setAction(ACTION_STOP))
        }
    }
}
