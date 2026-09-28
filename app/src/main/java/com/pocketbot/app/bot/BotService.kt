package com.pocketbot.app.bot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pocketbot.app.MainActivity
import com.pocketbot.app.R
import com.pocketbot.app.data.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Service au premier plan qui fait tourner le bot, même écran éteint
 * ou application fermée. Une notification permanente l'indique.
 */
class BotService : Service() {

    private var gateway: DiscordGateway? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch {
            BotRuntime.state
                .map { Triple(it.conn, it.botName, it.guildCount) }
                .distinctUntilChanged()
                .collect { if (gateway != null) notify(buildNotification()) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null : le système a relancé le service après l'avoir tué.
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> if (gateway == null) startBot() else promote()
            ACTION_RELOAD -> {
                gateway?.stop()
                gateway = null
                startBot()
            }
            ACTION_PRESENCE -> gateway?.updatePresence(ConfigStore(this).loadSettings()) ?: promoteAndStop()
            ACTION_STOP -> {
                stopBot()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startBot() {
        promote()
        val store = ConfigStore(this)
        val settings = store.loadSettings()
        if (settings.token.isBlank()) {
            BotRuntime.log("Aucun token configuré.", isError = true)
            stopBot()
            return
        }
        acquireWakeLock()
        BotRuntime.log("Démarrage du bot…")
        gateway = DiscordGateway(settings, store.loadCommands(), onFatal = { reason ->
            scope.launch {
                gateway?.stop()
                gateway = null
                releaseWakeLock()
                removeForeground()
                notify(errorNotification(reason))
                stopSelf()
            }
        }).also { it.start() }
    }

    private fun stopBot() {
        gateway?.stop()
        gateway = null
        releaseWakeLock()
        BotRuntime.update { it.copy(conn = ConnState.OFFLINE) }
        BotRuntime.log("Bot arrêté.")
        removeForeground()
        stopSelf()
    }

    /** Appelé via startForegroundService : il faut passer au premier plan même si on s'arrête aussitôt. */
    private fun promoteAndStop() {
        promote()
        removeForeground()
        stopSelf()
    }

    private fun removeForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun promote() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        gateway?.stop()
        gateway = null
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- wakelock

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketBot::gateway").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // ---------------------------------------------------------------- notifications

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bot en ligne", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Indique que ton bot Discord tourne"
                setShowBadge(false)
            }
        )
    }

    private fun notify(n: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(): Notification {
        val s = BotRuntime.state.value
        val title = when (s.conn) {
            ConnState.ONLINE -> "🟢 ${s.botName ?: "Bot"} est en ligne"
            ConnState.CONNECTING -> "🟡 Connexion à Discord…"
            ConnState.ERROR -> "🔴 Erreur"
            ConnState.OFFLINE -> "Démarrage…"
        }
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, BotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (s.conn == ConnState.ONLINE) "${s.guildCount} serveur(s)" else "PocketBot")
            .setContentIntent(openAppIntent())
            .addAction(0, "Arrêter", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun errorNotification(reason: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🔴 Le bot s'est arrêté")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "bot"
        private const val NOTIF_ID = 1

        const val ACTION_START = "com.pocketbot.app.START"
        const val ACTION_STOP = "com.pocketbot.app.STOP"
        const val ACTION_RELOAD = "com.pocketbot.app.RELOAD"
        const val ACTION_PRESENCE = "com.pocketbot.app.PRESENCE"

        fun send(context: Context, action: String) {
            val intent = Intent(context, BotService::class.java).setAction(action)
            if (action == ACTION_STOP) {
                context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
