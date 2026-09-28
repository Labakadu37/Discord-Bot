package com.pocketbot.app.bot

import com.pocketbot.app.data.BotCommand
import com.pocketbot.app.data.BotSettings
import com.pocketbot.app.data.CommandType
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Simule le Gateway et l'API de Discord pour vérifier le protocole de bout en bout. */
class DiscordGatewayTest {

    private val server = MockWebServer()
    private val restCalls = CopyOnWriteArrayList<RecordedRequest>()
    private val gatewayPayloads = CopyOnWriteArrayList<JSONObject>()
    private var closeWith: Int? = null
    private lateinit var restDone: CountDownLatch

    private val settings = BotSettings(token = "fake-token", activityType = 3, activityText = "le serveur")
    private val commands = listOf(
        BotCommand(type = CommandType.SLASH, name = "ping", description = "Pong", response = "Pong {user} sur {server} dans {channel}"),
        BotCommand(type = CommandType.PREFIX, name = "!dis", response = "{mention} a dit : {args}"),
    )

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Upgrade") == "websocket") {
                    return MockResponse().withWebSocketUpgrade(FakeGateway())
                }
                restCalls += request
                restDone.countDown()
                return MockResponse().setBody("{}")
            }
        }
        server.start()
    }

    private var gw: DiscordGateway? = null

    @After
    fun tearDown() {
        gw?.stop()
        // stop() est asynchrone : on laisse le temps à la poignée de main de fermeture.
        Thread.sleep(300)
        runCatching { server.shutdown() }
    }

    private fun gateway(onFatal: (String) -> Unit = {}) = DiscordGateway(
        settings,
        commands,
        onFatal,
        gatewayUrl = server.url("/").toString().replace("http", "ws"),
        apiBase = server.url("/api").toString(),
    ).also { gw = it }

    @Test
    fun identifyThenAnswerSlashAndPrefixCommands() {
        restDone = CountDownLatch(3) // enregistrement des commandes + réponse slash + réponse préfixe
        gateway().start()
        assertTrue("Discord n'a pas reçu les 3 appels HTTP", restDone.await(10, TimeUnit.SECONDS))

        val identify = gatewayPayloads.first { it.getInt("op") == 2 }.getJSONObject("d")
        assertEquals("fake-token", identify.getString("token"))
        // GUILDS | GUILD_MESSAGES | DIRECT_MESSAGES | MESSAGE_CONTENT (commande à préfixe présente)
        assertEquals(1 or 512 or 4096 or 32768, identify.getInt("intents"))
        val activity = identify.getJSONObject("presence").getJSONArray("activities").getJSONObject(0)
        assertEquals(3, activity.getInt("type"))
        assertEquals("le serveur", activity.getString("name"))

        val byPath = restCalls.associateBy { it.path!! }
        assertTrue(byPath["/api/applications/app1/commands"]!!.getHeader("Authorization") == "Bot fake-token")
        val registered = JSONArray(byPath["/api/applications/app1/commands"]!!.body.readUtf8())
        assertEquals(1, registered.length())
        assertEquals("ping", registered.getJSONObject(0).getString("name"))

        val slashReply = JSONObject(byPath["/api/interactions/i1/itoken/callback"]!!.body.readUtf8())
        assertEquals(4, slashReply.getInt("type"))
        assertEquals("Pong Alice sur Mon Serveur dans <#c1>", slashReply.getJSONObject("data").getString("content"))

        val prefixReply = JSONObject(byPath["/api/channels/c1/messages"]!!.body.readUtf8())
        assertEquals("<@u1> a dit : bonjour tout le monde", prefixReply.getString("content"))

        assertEquals(ConnState.ONLINE, BotRuntime.state.value.conn)
        assertEquals("TestBot", BotRuntime.state.value.botName)

        // Au moins un heartbeat envoyé (intervalle de 200 ms côté faux serveur).
        Thread.sleep(500)
        assertTrue(gatewayPayloads.any { it.getInt("op") == 1 })
    }

    @Test
    fun invalidTokenStopsWithError() {
        restDone = CountDownLatch(1)
        closeWith = 4004
        val fatal = CountDownLatch(1)
        var reason = ""
        gateway { reason = it; fatal.countDown() }.start()
        assertTrue(fatal.await(10, TimeUnit.SECONDS))
        assertTrue(reason.contains("Token invalide"))
        assertEquals(ConnState.ERROR, BotRuntime.state.value.conn)
    }

    private inner class FakeGateway : WebSocketListener() {
        private var seq = 0

        private fun WebSocket.dispatch(type: String, d: JSONObject) {
            send(JSONObject().put("op", 0).put("t", type).put("s", ++seq).put("d", d).toString())
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"op":10,"d":{"heartbeat_interval":200}}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = JSONObject(text)
            gatewayPayloads += msg
            when (msg.getInt("op")) {
                1 -> webSocket.send("""{"op":11}""")
                2 -> {
                    closeWith?.let { webSocket.close(it, "Authentication failed."); return }
                    webSocket.dispatch(
                        "READY",
                        JSONObject("""{"session_id":"s1","resume_gateway_url":"ws://unused",
                            "user":{"id":"b1","username":"TestBot"},"application":{"id":"app1"},
                            "guilds":[{"id":"g1","unavailable":true}]}""")
                    )
                    webSocket.dispatch("GUILD_CREATE", JSONObject("""{"id":"g1","name":"Mon Serveur"}"""))
                    webSocket.dispatch(
                        "INTERACTION_CREATE",
                        JSONObject("""{"id":"i1","token":"itoken","type":2,"guild_id":"g1","channel_id":"c1",
                            "data":{"name":"ping"},"member":{"user":{"id":"u1","username":"alice","global_name":"Alice"}}}""")
                    )
                    webSocket.dispatch(
                        "MESSAGE_CREATE",
                        JSONObject("""{"id":"m1","channel_id":"c1","guild_id":"g1",
                            "author":{"id":"u1","username":"alice"},"content":"!dis bonjour tout le monde"}""")
                    )
                    // Les messages de bots sont ignorés : ne doit PAS produire de 4e appel HTTP.
                    webSocket.dispatch(
                        "MESSAGE_CREATE",
                        JSONObject("""{"id":"m2","channel_id":"c1","author":{"id":"b2","username":"x","bot":true},"content":"!dis spam"}""")
                    )
                }
            }
        }
    }
}
