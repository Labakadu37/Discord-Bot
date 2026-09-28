package com.bothostinger.app.bot

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
import com.bothostinger.app.MainActivity
import com.bothostinger.app.R
import com.bothostinger.app.bot.engine.BotContext
import com.bothostinger.app.bot.engine.BotEngine
import com.bothostinger.app.bot.modules.Modules
import com.bothostinger.app.data.BotDatabase
import com.bothostinger.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/**
 * Service au premier plan qui fait tourner le bot, même écran éteint
 * ou application fermée. Une notification permanente l'indique.
 */
class BotService : Service() {

    /** Une instance du bot en marche : Gateway + moteur + coroutines associées. */
    private class Running(val gateway: DiscordGateway, val engine: BotEngine, val scope: CoroutineScope) {
        fun stop() {
            gateway.stop()
            engine.stop()
            scope.cancel()
        }
    }

    private var running: Running? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http by lazy { OkHttpClient() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        uiScope.launch {
            BotRuntime.state
                .map { Triple(it.conn, it.botName, it.guildCount) }
                .distinctUntilChanged()
                .collect { if (running != null) notify(buildNotification()) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null : le système a relancé le service après l'avoir tué.
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> if (running == null) startBot() else promote()
            ACTION_RELOAD -> {
                promote()
                running?.stop()
                running = null
                startBot()
            }
            ACTION_PRESENCE -> {
                promote()
                val r = running
                if (r != null) r.gateway.updatePresence(Presence.json(SettingsStore(this).load())) else stopForegroundAndSelf()
            }
            ACTION_STOP -> {
                stopBot()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startBot() {
        promote()
        val settings = SettingsStore(this).load()
        if (settings.token.isBlank()) {
            BotRuntime.log("Aucun token configuré.", isError = true)
            stopBot()
            return
        }
        acquireWakeLock()
        BotRuntime.update { it.copy(missingMembersIntent = false, error = null) }
        BotRuntime.log("Démarrage du bot…")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val enabled = Modules.all().map { it.id }.toSet() - settings.disabledModules
        val ctx = BotContext(
            rest = DiscordRest(http, settings.token),
            db = BotDatabase(File(filesDir, "bot-data")),
            scope = scope,
            enabledModules = enabled,
        )
        val engine = BotEngine(ctx, Modules.all())
        val gateway = DiscordGateway(
            token = settings.token,
            presence = Presence.json(settings),
            intents = engine.intents,
            optionalPrivilegedIntents = DiscordGateway.INTENT_GUILD_MEMBERS,
            listener = engine,
            onFatal = { reason -> uiScope.launch { onFatal(reason) } },
            client = http,
        )
        BotRuntime.log("${engine.modules.size} systèmes actifs, ${engine.commandCount} commandes.")
        running = Running(gateway, engine, scope)
        engine.start()
        gateway.start()
    }

    private fun onFatal(reason: String) {
        running?.stop()
        running = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notify(errorNotification(reason))
        stopSelf()
    }

    private fun stopBot() {
        running?.stop()
        running = null
        releaseWakeLock()
        BotRuntime.update { it.copy(conn = ConnState.OFFLINE, latencyMs = -1) }
        BotRuntime.log("Bot arrêté.")
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Obligatoire après startForegroundService, même si on s'arrête aussitôt. */
    private fun promote() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        running?.stop()
        running = null
        releaseWakeLock()
        uiScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- wakelock

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BotHostinger::gateway").apply {
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(
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
            ConnState.ONLINE -> "${s.botName ?: "Bot"} est en ligne"
            ConnState.CONNECTING -> "Connexion à Discord…"
            ConnState.ERROR -> "Erreur"
            ConnState.OFFLINE -> "Démarrage…"
        }
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, BotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFFF6A00.toInt())
            .setContentTitle(title)
            .setContentText(if (s.conn == ConnState.ONLINE) "${s.guildCount} serveur(s) · BotHostinger" else "BotHostinger")
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
            .setColor(0xFFFF6A00.toInt())
            .setContentTitle("Le bot s'est arrêté")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "bot"
        private const val NOTIF_ID = 1

        const val ACTION_START = "com.bothostinger.app.START"
        const val ACTION_STOP = "com.bothostinger.app.STOP"
        const val ACTION_RELOAD = "com.bothostinger.app.RELOAD"
        const val ACTION_PRESENCE = "com.bothostinger.app.PRESENCE"

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
