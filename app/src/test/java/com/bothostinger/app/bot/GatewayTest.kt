package com.bothostinger.app.bot

import com.bothostinger.app.data.BotSettings
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Protocole du Gateway contre un faux serveur WebSocket. */
class GatewayTest {
    private val server = MockWebServer()
    private val identifies = CopyOnWriteArrayList<JSONObject>()
    private val heartbeats = CountDownLatch(1)
    private var gateway: DiscordGateway? = null

    /** Code de fermeture envoyé en réponse à chaque identify (null = READY). */
    private val closeCodes = ArrayDeque<Int?>()

    private val ready = CountDownLatch(1)
    private val listener = object : GatewayListener {
        override fun onReady(d: JSONObject) = ready.countDown()
        override fun onDispatch(type: String, d: JSONObject) {}
    }

    private fun startServer() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"op":10,"d":{"heartbeat_interval":150}}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val msg = JSONObject(text)
                        when (msg.getInt("op")) {
                            1 -> {
                                heartbeats.countDown()
                                webSocket.send("""{"op":11}""")
                            }
                            2 -> {
                                identifies += msg.getJSONObject("d")
                                val code = synchronized(closeCodes) { closeCodes.removeFirstOrNull() }
                                if (code != null) {
                                    webSocket.close(code, "refusé")
                                } else {
                                    webSocket.send(
                                        """{"op":0,"t":"READY","s":1,"d":{"session_id":"s","resume_gateway_url":"ws://unused",
                                        "user":{"id":"1","username":"Bot"},"application":{"id":"1"},"guilds":[]}}"""
                                    )
                                }
                            }
                        }
                    }
                })
        }
        server.start()
    }

    private fun connect(intents: Int, settings: BotSettings = BotSettings(token = "tkn"), onFatal: (String) -> Unit = {}) {
        gateway = DiscordGateway(
            token = settings.token,
            presence = Presence.json(settings),
            intents = intents,
            optionalPrivilegedIntents = DiscordGateway.INTENT_GUILD_MEMBERS,
            listener = listener,
            onFatal = onFatal,
            gatewayUrl = server.url("/").toString().replace("http", "ws"),
        ).also { it.start() }
    }

    @After
    fun tearDown() {
        gateway?.stop()
        Thread.sleep(200)
        runCatching { server.shutdown() }
    }

    @Test
    fun identifiesWithBotHostingerPresenceAndHeartbeats() {
        startServer()
        connect(DiscordGateway.INTENT_GUILDS, BotSettings(token = "tkn", status = "dnd", presenceMode = "streaming"))
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        val identify = identifies.single()
        assertEquals("tkn", identify.getString("token"))
        val presence = identify.getJSONObject("presence")
        assertEquals("dnd", presence.getString("status"))
        val activity = presence.getJSONArray("activities").getJSONObject(0)
        assertEquals(1, activity.getInt("type")) // Stream
        assertEquals("BotHostinger", activity.getString("name"))
        assertTrue(activity.getString("url").startsWith("https://www.twitch.tv/"))
        assertTrue("heartbeat attendu", heartbeats.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun playingPresenceByDefault() {
        val activity = Presence.json(BotSettings()).getJSONArray("activities").getJSONObject(0)
        assertEquals(0, activity.getInt("type"))
        assertEquals("BotHostinger", activity.getString("name"))
    }

    @Test
    fun missingMembersIntentFallsBackInsteadOfStopping() {
        closeCodes += 4014 // premier identify refusé
        startServer()
        val all = DiscordGateway.INTENT_GUILDS or DiscordGateway.INTENT_GUILD_MEMBERS or DiscordGateway.INTENT_GUILD_MESSAGES
        connect(all)
        assertTrue("le bot doit se reconnecter sans l'intent membres", ready.await(8, TimeUnit.SECONDS))
        assertEquals(2, identifies.size)
        assertEquals(all, identifies[0].getInt("intents"))
        assertEquals(DiscordGateway.INTENT_GUILDS or DiscordGateway.INTENT_GUILD_MESSAGES, identifies[1].getInt("intents"))
        assertTrue(BotRuntime.state.value.missingMembersIntent)
    }

    @Test
    fun invalidTokenIsFatal() {
        closeCodes += 4004
        startServer()
        val fatal = CountDownLatch(1)
        var reason = ""
        connect(DiscordGateway.INTENT_GUILDS) { reason = it; fatal.countDown() }
        assertTrue(fatal.await(5, TimeUnit.SECONDS))
        assertTrue(reason.contains("Token invalide"))
        assertEquals(ConnState.ERROR, BotRuntime.state.value.conn)
    }
}
