package com.pocketbot.app.bot

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Appels à l'API HTTP de Discord (bloquants : à appeler depuis Dispatchers.IO). */
class DiscordRest(
    private val client: OkHttpClient,
    private val token: String,
    private val apiBase: String = API,
) {

    class ApiException(val code: Int, body: String) : Exception("HTTP $code : ${body.take(300)}")

    private val json = "application/json".toMediaType()

    /** Remplace toutes les commandes slash globales de l'application. */
    fun overwriteGlobalCommands(applicationId: String, commands: JSONArray) {
        request("PUT", "/applications/$applicationId/commands", commands.toString())
    }

    fun respondToInteraction(interactionId: String, interactionToken: String, content: String, ephemeral: Boolean) {
        val data = JSONObject().put("content", content.take(2000))
        if (ephemeral) data.put("flags", 64)
        val body = JSONObject().put("type", 4).put("data", data)
        request("POST", "/interactions/$interactionId/$interactionToken/callback", body.toString())
    }

    fun sendMessage(channelId: String, content: String, replyToMessageId: String? = null) {
        val body = JSONObject().put("content", content.take(2000))
        if (replyToMessageId != null) {
            body.put(
                "message_reference",
                JSONObject().put("message_id", replyToMessageId).put("fail_if_not_exists", false)
            )
        }
        request("POST", "/channels/$channelId/messages", body.toString())
    }

    private fun request(method: String, path: String, body: String?, retry: Boolean = true): String {
        val req = Request.Builder()
            .url(apiBase + path)
            .header("Authorization", "Bot $token")
            .header("User-Agent", "DiscordBot (https://github.com/labakadu37/discord-bot, 1.0)")
            .method(method, body?.toRequestBody(json))
            .build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.code == 429 && retry) {
                val wait = runCatching { JSONObject(text).optDouble("retry_after", 1.0) }.getOrDefault(1.0)
                Thread.sleep((wait * 1000).toLong().coerceIn(100, 10_000))
                return request(method, path, body, retry = false)
            }
            if (!res.isSuccessful) throw ApiException(res.code, text)
            return text
        }
    }

    companion object {
        const val API = "https://discord.com/api/v10"
    }
}
