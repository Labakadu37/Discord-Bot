package com.pocketbot.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class CommandType { SLASH, PREFIX }

data class BotCommand(
    val id: String = UUID.randomUUID().toString(),
    val type: CommandType = CommandType.SLASH,
    /** Slash : nom de la commande (ex. "ping"). Préfixe : texte déclencheur (ex. "!ping"). */
    val name: String = "",
    val description: String = "",
    val response: String = "",
    /** Slash uniquement : réponse visible seulement par l'utilisateur. */
    val ephemeral: Boolean = false,
)

/** Statut Discord : "online", "idle", "dnd" ou "invisible". */
data class BotSettings(
    val token: String = "",
    val status: String = "online",
    /** 0 = Joue à, 2 = Écoute, 3 = Regarde, 5 = Participe à, 4 = Statut perso. */
    val activityType: Int = 0,
    val activityText: String = "",
    val welcomeEnabled: Boolean = false,
    val welcomeChannelId: String = "",
    val welcomeMessage: String = "Bienvenue {mention} sur **{server}** !",
    val autoStart: Boolean = false,
)

/**
 * Stockage local (privé à l'app) de la configuration du bot.
 * Tout reste sur le téléphone : le token n'est envoyé qu'à Discord.
 */
class ConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pocketbot", Context.MODE_PRIVATE)

    fun loadSettings(): BotSettings = BotSettings(
        token = prefs.getString("token", "") ?: "",
        status = prefs.getString("status", "online") ?: "online",
        activityType = prefs.getInt("activityType", 0),
        activityText = prefs.getString("activityText", "") ?: "",
        welcomeEnabled = prefs.getBoolean("welcomeEnabled", false),
        welcomeChannelId = prefs.getString("welcomeChannelId", "") ?: "",
        welcomeMessage = prefs.getString("welcomeMessage", BotSettings().welcomeMessage) ?: "",
        autoStart = prefs.getBoolean("autoStart", false),
    )

    fun saveSettings(s: BotSettings) {
        prefs.edit()
            .putString("token", s.token.trim())
            .putString("status", s.status)
            .putInt("activityType", s.activityType)
            .putString("activityText", s.activityText)
            .putBoolean("welcomeEnabled", s.welcomeEnabled)
            .putString("welcomeChannelId", s.welcomeChannelId.trim())
            .putString("welcomeMessage", s.welcomeMessage)
            .putBoolean("autoStart", s.autoStart)
            .apply()
    }

    fun loadCommands(): List<BotCommand> {
        val raw = prefs.getString("commands", null) ?: return defaultCommands()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BotCommand(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    type = runCatching { CommandType.valueOf(o.optString("type")) }.getOrDefault(CommandType.SLASH),
                    name = o.optString("name"),
                    description = o.optString("description"),
                    response = o.optString("response"),
                    ephemeral = o.optBoolean("ephemeral", false),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveCommands(commands: List<BotCommand>) {
        val arr = JSONArray()
        commands.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("type", c.type.name)
                    .put("name", c.name)
                    .put("description", c.description)
                    .put("response", c.response)
                    .put("ephemeral", c.ephemeral)
            )
        }
        prefs.edit().putString("commands", arr.toString()).apply()
    }

    private fun defaultCommands() = listOf(
        BotCommand(
            type = CommandType.SLASH,
            name = "ping",
            description = "Vérifie que le bot répond",
            response = "🏓 Pong ! Salut {user}.",
        ),
        BotCommand(
            type = CommandType.SLASH,
            name = "serveur",
            description = "Infos sur le serveur",
            response = "Tu es sur **{server}** dans {channel}.",
        ),
    )
}
