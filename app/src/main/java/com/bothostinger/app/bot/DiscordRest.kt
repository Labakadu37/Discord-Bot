package com.bothostinger.app.bot

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Appels à l'API HTTP de Discord (bloquants : à appeler hors du thread principal). */
class DiscordRest(
    private val client: OkHttpClient,
    private val token: String,
    private val apiBase: String = API,
) {

    class ApiException(val status: Int, val code: Int, message: String) : Exception(message) {
        /** Message compréhensible à afficher sur Discord. */
        fun friendly(): String = when (code) {
            50013 -> "Je n'ai pas la permission de faire ça. Vérifie mes permissions et que mon rôle est au-dessus de celui du membre."
            50001 -> "Je n'ai pas accès à ce salon."
            10007 -> "Ce membre n'est pas sur le serveur."
            10013 -> "Utilisateur introuvable."
            10026 -> "Cet utilisateur n'est pas banni."
            10008 -> "Message introuvable."
            10003 -> "Salon introuvable."
            10011 -> "Rôle introuvable."
            50034 -> "Impossible de supprimer des messages de plus de 14 jours."
            50007 -> "Impossible d'envoyer un message privé à cet utilisateur."
            else -> when (status) {
                403 -> "Je n'ai pas la permission de faire ça."
                404 -> "Élément introuvable."
                else -> "Erreur Discord ($status) : $message"
            }
        }
    }

    private val json = "application/json".toMediaType()

    // ------------------------------------------------------------ commandes / interactions

    fun overwriteGlobalCommands(applicationId: String, commands: JSONArray) {
        request("PUT", "/applications/$applicationId/commands", commands.toString())
    }

    fun interactionCallback(interactionId: String, interactionToken: String, payload: JSONObject) {
        request("POST", "/interactions/$interactionId/$interactionToken/callback", payload.toString())
    }

    fun editOriginal(applicationId: String, interactionToken: String, payload: JSONObject) {
        request("PATCH", "/webhooks/$applicationId/$interactionToken/messages/@original", payload.toString())
    }

    fun followUp(applicationId: String, interactionToken: String, payload: JSONObject) {
        request("POST", "/webhooks/$applicationId/$interactionToken", payload.toString())
    }

    // ------------------------------------------------------------ messages

    fun createMessage(channelId: String, payload: JSONObject): JSONObject =
        JSONObject(request("POST", "/channels/$channelId/messages", payload.toString()))

    fun editMessage(channelId: String, messageId: String, payload: JSONObject) {
        request("PATCH", "/channels/$channelId/messages/$messageId", payload.toString())
    }

    fun getMessage(channelId: String, messageId: String): JSONObject =
        JSONObject(request("GET", "/channels/$channelId/messages/$messageId", null))

    fun deleteMessage(channelId: String, messageId: String, reason: String? = null) {
        request("DELETE", "/channels/$channelId/messages/$messageId", null, reason)
    }

    fun getMessages(channelId: String, limit: Int): JSONArray =
        JSONArray(request("GET", "/channels/$channelId/messages?limit=${limit.coerceIn(1, 100)}", null))

    fun bulkDelete(channelId: String, ids: List<String>, reason: String? = null) {
        val body = JSONObject().put("messages", JSONArray(ids))
        request("POST", "/channels/$channelId/messages/bulk-delete", body.toString(), reason)
    }

    fun addReaction(channelId: String, messageId: String, emoji: String) {
        val e = URLEncoder.encode(emoji, "UTF-8")
        request("PUT", "/channels/$channelId/messages/$messageId/reactions/$e/@me", null)
    }

    fun sendDm(userId: String, payload: JSONObject) {
        val dm = JSONObject(request("POST", "/users/@me/channels", JSONObject().put("recipient_id", userId).toString()))
        createMessage(dm.getString("id"), payload)
    }

    // ------------------------------------------------------------ membres

    fun ban(guildId: String, userId: String, deleteMessageSeconds: Int, reason: String?) {
        val body = JSONObject().put("delete_message_seconds", deleteMessageSeconds)
        request("PUT", "/guilds/$guildId/bans/$userId", body.toString(), reason)
    }

    fun unban(guildId: String, userId: String, reason: String?) {
        request("DELETE", "/guilds/$guildId/bans/$userId", null, reason)
    }

    fun kick(guildId: String, userId: String, reason: String?) {
        request("DELETE", "/guilds/$guildId/members/$userId", null, reason)
    }

    /** [untilIso] null = retirer l'exclusion temporaire. */
    fun timeout(guildId: String, userId: String, untilIso: String?, reason: String?) {
        val body = JSONObject().put("communication_disabled_until", untilIso ?: JSONObject.NULL)
        request("PATCH", "/guilds/$guildId/members/$userId", body.toString(), reason)
    }

    /** Déplace un membre dans un salon vocal (null = le déconnecter). */
    fun moveMember(guildId: String, userId: String, channelId: String?, reason: String? = null) {
        val body = JSONObject().put("channel_id", channelId ?: JSONObject.NULL)
        request("PATCH", "/guilds/$guildId/members/$userId", body.toString(), reason)
    }

    fun addRole(guildId: String, userId: String, roleId: String, reason: String? = null) {
        request("PUT", "/guilds/$guildId/members/$userId/roles/$roleId", null, reason)
    }

    fun removeRole(guildId: String, userId: String, roleId: String, reason: String? = null) {
        request("DELETE", "/guilds/$guildId/members/$userId/roles/$roleId", null, reason)
    }

    fun getUser(userId: String): JSONObject = JSONObject(request("GET", "/users/$userId", null))

    // ------------------------------------------------------------ salons

    fun getChannel(channelId: String): JSONObject = JSONObject(request("GET", "/channels/$channelId", null))

    fun modifyChannel(channelId: String, payload: JSONObject, reason: String? = null) {
        request("PATCH", "/channels/$channelId", payload.toString(), reason)
    }

    /** [type] 0 = rôle, 1 = membre. */
    fun editPermission(channelId: String, overwriteId: String, type: Int, allow: Long, deny: Long, reason: String? = null) {
        val body = JSONObject().put("type", type).put("allow", allow.toString()).put("deny", deny.toString())
        request("PUT", "/channels/$channelId/permissions/$overwriteId", body.toString(), reason)
    }

    fun createChannel(guildId: String, payload: JSONObject, reason: String? = null): JSONObject =
        JSONObject(request("POST", "/guilds/$guildId/channels", payload.toString(), reason))

    fun deleteChannel(channelId: String, reason: String? = null) {
        request("DELETE", "/channels/$channelId", null, reason)
    }

    // ------------------------------------------------------------ bas niveau

    private fun request(method: String, path: String, body: String?, reason: String? = null, retry: Boolean = true): String {
        val payload = body ?: if (method == "PUT" || method == "POST" || method == "PATCH") "{}" else null
        val builder = Request.Builder()
            .url(apiBase + path)
            .header("Authorization", "Bot $token")
            .header("User-Agent", "DiscordBot (https://github.com/labakadu37/discord-bot, 2.0)")
            .method(method, payload?.toRequestBody(json))
        if (!reason.isNullOrBlank()) {
            builder.header("X-Audit-Log-Reason", URLEncoder.encode(reason.take(400), "UTF-8").replace("+", "%20"))
        }
        client.newCall(builder.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.code == 429 && retry) {
                val wait = runCatching { JSONObject(text).optDouble("retry_after", 1.0) }.getOrDefault(1.0)
                Thread.sleep((wait * 1000).toLong().coerceIn(100, 15_000))
                return request(method, path, body, reason, retry = false)
            }
            if (!res.isSuccessful) {
                val err = runCatching { JSONObject(text) }.getOrNull()
                throw ApiException(res.code, err?.optInt("code") ?: 0, err?.optString("message") ?: text.take(200))
            }
            return text
        }
    }

    companion object {
        const val API = "https://discord.com/api/v10"
    }
}
