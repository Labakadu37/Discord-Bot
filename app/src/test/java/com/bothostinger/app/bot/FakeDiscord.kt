package com.bothostinger.app.bot

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Faux serveur de l'API HTTP Discord : enregistre chaque appel et renvoie des réponses plausibles. */
class FakeDiscord {
    class Call(val method: String, val path: String, val body: String, val reason: String?) {
        val json: JSONObject get() = JSONObject(body)
        override fun toString() = "$method $path $body"
    }

    val server = MockWebServer()
    val calls = LinkedBlockingQueue<Call>()
    private var nextId = 1000

    /** Messages renvoyés par GET /channels/{id}/messages. */
    var channelMessages: JSONArray = JSONArray()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.removePrefix("/api")
                val call = Call(request.method!!, path, request.body.readUtf8(), request.getHeader("X-Audit-Log-Reason"))
                calls += call
                val body = when {
                    call.method == "GET" && path.contains("/messages") -> channelMessages.toString()
                    call.method == "POST" && path.endsWith("/messages") -> JSONObject().put("id", "msg${nextId++}").toString()
                    call.method == "POST" && path.matches(Regex("/guilds/[^/]+/channels")) -> JSONObject().put("id", "${nextId++}").toString()
                    call.method == "POST" && path == "/users/@me/channels" -> JSONObject().put("id", "dm1").toString()
                    call.method == "GET" && path.startsWith("/channels/") -> JSONObject().put("id", "c").put("permission_overwrites", JSONArray()).toString()
                    else -> "{}"
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
    }

    val apiBase: String get() = server.url("/api").toString()

    /** Attend le prochain appel HTTP (ou échoue au bout de [seconds] s). */
    fun next(seconds: Long = 5): Call = calls.poll(seconds, TimeUnit.SECONDS) ?: throw AssertionError("Aucun appel HTTP reçu")

    /** Attend un appel qui correspond, en ignorant les autres. */
    fun await(method: String, pathRegex: String, seconds: Long = 5): Call {
        val regex = Regex(pathRegex)
        while (true) {
            val c = next(seconds)
            if (c.method == method && regex.matches(c.path)) return c
        }
    }

    fun assertNoMoreCalls(waitMs: Long = 300) {
        val c = calls.poll(waitMs, TimeUnit.MILLISECONDS)
        if (c != null) throw AssertionError("Appel inattendu : $c")
    }

    fun shutdown() = runCatching { server.shutdown() }
}
