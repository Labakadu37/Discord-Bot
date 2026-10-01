package com.monimage.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Télécharge le modèle d'IA (1,6 Go) en arrière-plan, avec la progression dans une notification.
 * Reprend là où il s'était arrêté et réessaie tout seul si la connexion coupe.
 */
class ModelDownloadService : Service() {

    /** État lu par l'écran de l'IA. */
    object State {
        @Volatile var running = false
        @Volatile var downloaded = 0L
        @Volatile var total = -1L
        @Volatile var error: String? = null
    }

    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!State.running) {
            State.running = true
            State.error = null
            thread { runDownload() }
        }
        return START_NOT_STICKY
    }

    private fun runDownload() {
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RedSmile:download")
            .apply { acquire(2 * 60 * 60 * 1000L) }
        try {
            val target = AiEngine.modelFile(this)
            val part = File(target.path + ".part")
            var attempt = 0
            while (true) {
                try {
                    downloadOnce(part)
                    if (!part.renameTo(target)) throw IOException("impossible d'enregistrer le fichier")
                    State.error = null
                    break
                } catch (e: Exception) {
                    attempt++
                    State.error = e.message ?: e.javaClass.simpleName
                    if (attempt >= MAX_ATTEMPTS || e is NotEnoughSpace) break
                    Thread.sleep(3000L * attempt)
                }
            }
        } finally {
            State.running = false
            if (wakeLock.isHeld) wakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun downloadOnce(part: File) {
        val already = if (part.exists()) part.length() else 0L
        val conn = (URL(AiEngine.MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "RedSmile/1.0 (Android)")
            if (already > 0) setRequestProperty("Range", "bytes=$already-")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("le serveur répond $code")
            val resumed = code == HttpURLConnection.HTTP_PARTIAL
            val start = if (resumed) already else 0L
            val length = conn.contentLengthLong
            State.total = if (length > 0) start + length else -1L
            State.downloaded = start

            val free = part.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (length > 0 && length > free) throw NotEnoughSpace(length - free)

            var lastUpdate = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, resumed).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        State.downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 1000) {
                            lastUpdate = now
                            notifications.notify(NOTIFICATION_ID, buildNotification())
                        }
                    }
                }
            }
            if (State.total > 0 && State.downloaded < State.total) throw IOException("connexion coupée")
        } finally {
            conn.disconnect()
        }
    }

    private fun buildNotification(): Notification {
        val total = State.total
        val percent = if (total > 0) (State.downloaded * 100 / total).toInt() else 0
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.ai_title))
            .setContentText(progressText(this))
            .setProgress(100, percent, total <= 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private class NotEnoughSpace(missing: Long) :
        IOException("pas assez de place (il manque ${missing / MB} Mo)")

    companion object {
        private const val CHANNEL = "download"
        private const val NOTIFICATION_ID = 3
        private const val MAX_ATTEMPTS = 6
        private const val MB = 1024L * 1024L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ModelDownloadService::class.java))
        }

        fun percent(): Int = State.total.let { if (it > 0) (State.downloaded * 100 / it).toInt() else 0 }

        /** Ex. : « 42 % – 640 / 1524 Mo ». */
        fun progressText(context: Context): String {
            val done = State.downloaded / MB
            return if (State.total > 0) {
                context.getString(R.string.download_progress, percent(), done, State.total / MB)
            } else {
                context.getString(R.string.download_progress_unknown, done)
            }
        }
    }
}
