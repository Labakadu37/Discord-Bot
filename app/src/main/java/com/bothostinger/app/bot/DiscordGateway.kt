package com.bothostinger.app.bot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Reçoit les évènements du Gateway. Appelé depuis le thread du Gateway : ne pas bloquer. */
interface GatewayListener {
    fun onReady(d: JSONObject)
    fun onDispatch(type: String, d: JSONObject)
    fun onLatency(ms: Long) {}
}

/**
 * Connexion au Gateway Discord (WebSocket) : identification, heartbeat,
 * reprise de session, reconnexion. Ne contient aucune logique de bot.
 *
 * Tout l'état interne est manipulé sur un seul thread ([loop]).
 */
class DiscordGateway(
    private val token: String,
    private var presence: JSONObject,
    private var intents: Int,
    /** Intents à retirer si Discord les refuse (code 4014) au lieu d'arrêter le bot. */
    private val optionalPrivilegedIntents: Int,
    private val listener: GatewayListener,
    private val onFatal: (String) -> Unit,
    private val gatewayUrl: String = GATEWAY,
    client: OkHttpClient? = null,
) {
    private val client = (client ?: OkHttpClient()).newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val loop = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + loop)

    private var ws: WebSocket? = null
    /** Incrémenté à chaque (re)connexion pour ignorer les évènements d'un ancien socket. */
    private var generation = 0
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var seq: Long? = null
    private var sessionId: String? = null
    private var resumeUrl: String? = null
    private var awaitingAck = false
    private var lastBeatAt = 0L
    private var stopped = false
    private var failures = 0

    fun start() {
        scope.launch { connect() }
    }

    fun stop() {
        scope.launch {
            stopped = true
            generation++
            heartbeatJob?.cancel()
            reconnectJob?.cancel()
            // Code 1000 : Discord ferme la session et le bot passe hors ligne tout de suite.
            ws?.close(1000, "Arrêt")
            ws = null
        }.invokeOnCompletion { scope.cancel() }
    }

    fun updatePresence(p: JSONObject) {
        scope.launch {
            presence = p
            send(JSONObject().put("op", OP_PRESENCE).put("d", p))
        }
    }

    // ---------------------------------------------------------------- connexion

    private fun connect() {
        if (stopped) return
        val gen = ++generation
        val base = if (sessionId != null) resumeUrl ?: gatewayUrl else gatewayUrl
        val url = "${base.trimEnd('/')}/?v=10&encoding=json"
        BotRuntime.update { it.copy(conn = ConnState.CONNECTING, error = null) }

        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { if (gen == generation) handle(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                scope.launch { if (gen == generation) onDisconnected(code, reason) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { if (gen == generation) onDisconnected(-1, t.message ?: t.javaClass.simpleName) }
            }
        })
    }

    private fun onDisconnected(code: Int, reason: String) {
        generation++
        heartbeatJob?.cancel()
        ws = null
        if (stopped) return

        when (code) {
            4004 -> return fatal("Token invalide. Vérifie le token dans les paramètres.")
            4013 -> return fatal("Intents invalides (erreur 4013).")
            4014 -> {
                if (intents and optionalPrivilegedIntents != 0) {
                    intents = intents and optionalPrivilegedIntents.inv()
                    BotRuntime.update { it.copy(missingMembersIntent = true) }
                    BotRuntime.log(
                        "« SERVER MEMBERS INTENT » n'est pas activé : bienvenue, au revoir et autorôle sont en pause. " +
                            "Active-le sur discord.com/developers → Bot.",
                        isError = true,
                    )
                    sessionId = null
                    seq = null
                    scheduleConnect(1000)
                    return
                }
                return fatal("Intents privilégiés refusés par Discord (4014).")
            }
            4010, 4011, 4012 -> return fatal("Erreur Gateway $code : $reason")
            4007, 4009 -> {
                sessionId = null
                seq = null
            }
        }
        val why = if (code == -1) reason else "code $code${if (reason.isNotBlank()) " : $reason" else ""}"
        BotRuntime.log("Connexion perdue ($why). Reconnexion…", isError = true)
        BotRuntime.update { it.copy(conn = ConnState.CONNECTING) }
        failures++
        scheduleConnect(minOf(60_000L, 1000L shl minOf(failures, 6)))
    }

    /** Ferme volontairement le socket actuel (en gardant la session) puis se reconnecte. */
    private fun reconnectNow(delayMs: Long = 500) {
        val old = ws
        generation++
        heartbeatJob?.cancel()
        ws = null
        old?.close(4000, "Reconnexion")
        scheduleConnect(delayMs)
    }

    private fun scheduleConnect(delayMs: Long) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            connect()
        }
    }

    private fun fatal(message: String) {
        stopped = true
        reconnectJob?.cancel()
        BotRuntime.log(message, isError = true)
        BotRuntime.update { it.copy(conn = ConnState.ERROR, error = message) }
        onFatal(message)
    }

    // ---------------------------------------------------------------- protocole

    private fun handle(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (!msg.isNull("s")) seq = msg.getLong("s")

        when (msg.optInt("op", -1)) {
            OP_HELLO -> {
                startHeartbeat(msg.getJSONObject("d").getLong("heartbeat_interval"))
                if (sessionId != null && seq != null) resume() else identify()
            }
            OP_HEARTBEAT_ACK -> {
                awaitingAck = false
                listener.onLatency(System.currentTimeMillis() - lastBeatAt)
            }
            OP_HEARTBEAT -> sendHeartbeat()
            OP_RECONNECT -> {
                BotRuntime.log("Discord demande une reconnexion.")
                reconnectNow()
            }
            OP_INVALID_SESSION -> {
                if (!msg.optBoolean("d", false)) {
                    sessionId = null
                    seq = null
                }
                BotRuntime.log("Session invalide, nouvelle identification…")
                reconnectNow(Random.nextLong(1000, 5000))
            }
            OP_DISPATCH -> {
                val type = msg.optString("t")
                val d = msg.optJSONObject("d") ?: JSONObject()
                runCatching { dispatch(type, d) }
                    .onFailure { BotRuntime.log("Erreur sur $type : ${it.message}", isError = true) }
            }
        }
    }

    private fun dispatch(type: String, d: JSONObject) {
        when (type) {
            "READY" -> {
                failures = 0
                sessionId = d.getString("session_id")
                resumeUrl = d.optString("resume_gateway_url").ifBlank { null }
                listener.onReady(d)
            }
            "RESUMED" -> {
                failures = 0
                BotRuntime.update { it.copy(conn = ConnState.ONLINE) }
                BotRuntime.log("Session reprise.")
            }
            else -> listener.onDispatch(type, d)
        }
    }

    private fun startHeartbeat(interval: Long) {
        heartbeatJob?.cancel()
        awaitingAck = false
        heartbeatJob = scope.launch {
            delay((interval * Random.nextDouble()).toLong())
            while (isActive) {
                if (awaitingAck) {
                    BotRuntime.log("Discord ne répond plus, reconnexion…", isError = true)
                    reconnectNow()
                    return@launch
                }
                sendHeartbeat()
                delay(interval)
            }
        }
    }

    private fun sendHeartbeat() {
        awaitingAck = true
        lastBeatAt = System.currentTimeMillis()
        send(JSONObject().put("op", OP_HEARTBEAT).put("d", seq ?: JSONObject.NULL))
    }

    private fun identify() {
        val d = JSONObject()
            .put("token", token)
            .put("intents", intents)
            .put(
                "properties",
                JSONObject().put("os", "android").put("browser", "BotHostinger").put("device", "BotHostinger")
            )
            .put("presence", presence)
        send(JSONObject().put("op", OP_IDENTIFY).put("d", d))
    }

    private fun resume() {
        val d = JSONObject()
            .put("token", token)
            .put("session_id", sessionId)
            .put("seq", seq)
        send(JSONObject().put("op", OP_RESUME).put("d", d))
    }

    private fun send(payload: JSONObject) {
        ws?.send(payload.toString())
    }

    companion object {
        private const val GATEWAY = "wss://gateway.discord.gg"

        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_PRESENCE = 3
        private const val OP_RESUME = 6
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11

        const val INTENT_GUILDS = 1 shl 0
        const val INTENT_GUILD_MEMBERS = 1 shl 1
        const val INTENT_GUILD_MESSAGES = 1 shl 9
    }
}
