package com.pocketbot.app.bot

import com.pocketbot.app.data.BotCommand
import com.pocketbot.app.data.BotSettings
import com.pocketbot.app.data.CommandType
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Connexion au Gateway Discord (WebSocket) : identification, heartbeat,
 * reprise de session et exécution des commandes configurées.
 *
 * Tout l'état interne est manipulé sur un seul thread ([loop]), les appels
 * HTTP partent sur Dispatchers.IO.
 */
class DiscordGateway(
    private val settings: BotSettings,
    private val commands: List<BotCommand>,
    private val onFatal: (String) -> Unit,
    private val gatewayUrl: String = GATEWAY,
    apiBase: String = DiscordRest.API,
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val rest = DiscordRest(client, settings.token, apiBase)

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
    private var stopped = false
    private var failures = 0
    private val guildNames = HashMap<String, String>()

    private val slashCommands = commands.filter { it.type == CommandType.SLASH && SLASH_NAME.matches(it.name) }
    private val prefixCommands = commands.filter { it.type == CommandType.PREFIX && it.name.isNotBlank() }

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

    fun updatePresence(s: BotSettings) {
        scope.launch {
            send(JSONObject().put("op", 3).put("d", presenceJson(s)))
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
            4004 -> return fatal("Token invalide. Vérifie le token dans l'onglet Bot.")
            4013 -> return fatal("Intents invalides (erreur 4013).")
            4014 -> return fatal(
                "Intents privilégiés non autorisés. Sur discord.com/developers → ton application → Bot, " +
                    "active « MESSAGE CONTENT INTENT » (commandes à préfixe) et « SERVER MEMBERS INTENT » (bienvenue)."
            )
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
            OP_HEARTBEAT_ACK -> awaitingAck = false
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
                val d = msg.optJSONObject("d") ?: JSONObject()
                runCatching { dispatch(msg.optString("t"), d) }
                    .onFailure { BotRuntime.log("Erreur sur ${msg.optString("t")} : ${it.message}", isError = true) }
            }
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
        send(JSONObject().put("op", OP_HEARTBEAT).put("d", seq ?: JSONObject.NULL))
    }

    private fun identify() {
        var intents = INTENT_GUILDS
        if (prefixCommands.isNotEmpty()) {
            intents = intents or INTENT_GUILD_MESSAGES or INTENT_DIRECT_MESSAGES or INTENT_MESSAGE_CONTENT
        }
        if (settings.welcomeEnabled) intents = intents or INTENT_GUILD_MEMBERS

        val d = JSONObject()
            .put("token", settings.token)
            .put("intents", intents)
            .put(
                "properties",
                JSONObject().put("os", "android").put("browser", "PocketBot").put("device", "PocketBot")
            )
            .put("presence", presenceJson(settings))
        send(JSONObject().put("op", OP_IDENTIFY).put("d", d))
    }

    private fun resume() {
        val d = JSONObject()
            .put("token", settings.token)
            .put("session_id", sessionId)
            .put("seq", seq)
        send(JSONObject().put("op", OP_RESUME).put("d", d))
    }

    private fun send(payload: JSONObject) {
        ws?.send(payload.toString())
    }

    private fun presenceJson(s: BotSettings): JSONObject {
        val activities = JSONArray()
        val text = s.activityText.trim()
        if (text.isNotEmpty()) {
            val activity = if (s.activityType == 4) {
                JSONObject().put("type", 4).put("name", "Custom Status").put("state", text)
            } else {
                JSONObject().put("type", s.activityType).put("name", text)
            }
            activities.put(activity)
        }
        return JSONObject()
            .put("since", JSONObject.NULL)
            .put("afk", false)
            .put("status", s.status)
            .put("activities", activities)
    }

    // ---------------------------------------------------------------- évènements

    private fun dispatch(type: String, d: JSONObject) {
        when (type) {
            "READY" -> {
                failures = 0
                sessionId = d.getString("session_id")
                resumeUrl = d.optString("resume_gateway_url").ifBlank { null }
                val user = d.getJSONObject("user")
                val appId = d.getJSONObject("application").getString("id")
                val guilds = d.optJSONArray("guilds")?.length() ?: 0
                val name = user.optString("username")
                BotRuntime.update {
                    it.copy(conn = ConnState.ONLINE, botName = name, applicationId = appId, guildCount = guilds, error = null)
                }
                BotRuntime.log("✅ Connecté en tant que $name sur $guilds serveur(s).")
                registerSlashCommands(appId)
            }
            "RESUMED" -> {
                failures = 0
                BotRuntime.update { it.copy(conn = ConnState.ONLINE) }
                BotRuntime.log("Session reprise.")
            }
            "GUILD_CREATE" -> {
                guildNames[d.getString("id")] = d.optString("name")
                BotRuntime.update { it.copy(guildCount = maxOf(it.guildCount, guildNames.size)) }
            }
            "GUILD_DELETE" -> if (!d.optBoolean("unavailable", false)) {
                guildNames.remove(d.getString("id"))
                BotRuntime.update { it.copy(guildCount = guildNames.size) }
            }
            "INTERACTION_CREATE" -> onInteraction(d)
            "MESSAGE_CREATE" -> onMessage(d)
            "GUILD_MEMBER_ADD" -> onMemberJoin(d)
        }
    }

    private fun onInteraction(d: JSONObject) {
        if (d.optInt("type") != 2) return // 2 = commande slash
        val name = d.getJSONObject("data").getString("name")
        val user = d.optJSONObject("member")?.optJSONObject("user") ?: d.optJSONObject("user")
        val guildId = d.optString("guild_id").ifBlank { null }
        val channelId = d.optString("channel_id")
        val cmd = slashCommands.firstOrNull { it.name == name }

        val content = cmd?.let { format(it.response, user, guildId, channelId, "") }
            ?: "Cette commande n'existe plus."
        BotRuntime.log("/$name utilisée par ${displayName(user)}")
        io("réponse à /$name") {
            rest.respondToInteraction(d.getString("id"), d.getString("token"), content, cmd?.ephemeral ?: true)
        }
    }

    private fun onMessage(d: JSONObject) {
        if (prefixCommands.isEmpty()) return
        val author = d.getJSONObject("author")
        if (author.optBoolean("bot", false)) return
        val content = d.optString("content").trim()
        val cmd = prefixCommands.firstOrNull {
            content.equals(it.name, ignoreCase = true) || content.startsWith(it.name + " ", ignoreCase = true)
        } ?: return

        val args = content.drop(cmd.name.length).trim()
        val guildId = d.optString("guild_id").ifBlank { null }
        val channelId = d.getString("channel_id")
        val text = format(cmd.response, author, guildId, channelId, args)
        BotRuntime.log("${cmd.name} utilisée par ${displayName(author)}")
        io("réponse à ${cmd.name}") { rest.sendMessage(channelId, text, d.getString("id")) }
    }

    private fun onMemberJoin(d: JSONObject) {
        if (!settings.welcomeEnabled || settings.welcomeChannelId.isBlank()) return
        val user = d.getJSONObject("user")
        val text = format(settings.welcomeMessage, user, d.optString("guild_id"), settings.welcomeChannelId, "")
        BotRuntime.log("Nouveau membre : ${displayName(user)}")
        io("message de bienvenue") { rest.sendMessage(settings.welcomeChannelId, text) }
    }

    private fun registerSlashCommands(appId: String) {
        val arr = JSONArray()
        slashCommands.forEach {
            arr.put(
                JSONObject()
                    .put("type", 1)
                    .put("name", it.name)
                    .put("description", it.description.ifBlank { "Commande personnalisée" }.take(100))
            )
        }
        io("enregistrement des commandes slash") {
            rest.overwriteGlobalCommands(appId, arr)
            BotRuntime.log("${slashCommands.size} commande(s) slash synchronisée(s) avec Discord.")
        }
    }

    private fun io(what: String, block: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            runCatching(block).onFailure { BotRuntime.log("Échec ($what) : ${it.message}", isError = true) }
        }
    }

    private fun displayName(user: JSONObject?): String =
        user?.optString("global_name")?.takeIf { it.isNotBlank() && it != "null" }
            ?: user?.optString("username").orEmpty()

    private fun format(template: String, user: JSONObject?, guildId: String?, channelId: String, args: String): String =
        template
            .replace("{user}", displayName(user))
            .replace("{username}", user?.optString("username").orEmpty())
            .replace("{mention}", user?.optString("id")?.let { "<@$it>" }.orEmpty())
            .replace("{server}", guildId?.let { guildNames[it] } ?: "Messages privés")
            .replace("{channel}", "<#$channelId>")
            .replace("{args}", args)

    companion object {
        private const val GATEWAY = "wss://gateway.discord.gg"
        val SLASH_NAME = Regex("^[-_a-z0-9]{1,32}$")

        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_RESUME = 6
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11

        private const val INTENT_GUILDS = 1 shl 0
        private const val INTENT_GUILD_MEMBERS = 1 shl 1
        private const val INTENT_GUILD_MESSAGES = 1 shl 9
        private const val INTENT_DIRECT_MESSAGES = 1 shl 12
        private const val INTENT_MESSAGE_CONTENT = 1 shl 15
    }
}
