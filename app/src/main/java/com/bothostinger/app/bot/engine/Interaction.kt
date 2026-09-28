package com.bothostinger.app.bot.engine

import com.bothostinger.app.bot.DiscordRest
import com.bothostinger.app.data.str
import com.bothostinger.app.data.strings
import org.json.JSONArray
import org.json.JSONObject

/** Commande slash ou clic sur un bouton, avec des raccourcis pour lire les options et répondre. */
class Interaction(val raw: JSONObject, private val rest: DiscordRest) {
    val id: String = raw.getString("id")
    val token: String = raw.getString("token")
    val type: Int = raw.optInt("type")
    private val appId: String = raw.optString("application_id")
    val guildId: String? = raw.str("guild_id")
    val channelId: String = raw.optString("channel_id")
    val member: JSONObject? = raw.optJSONObject("member")
    val user: JSONObject = member?.optJSONObject("user") ?: raw.optJSONObject("user") ?: JSONObject()
    val userId: String = user.optString("id")
    val data: JSONObject = raw.optJSONObject("data") ?: JSONObject()
    val name: String = data.optString("name")
    val customId: String = data.optString("custom_id")
    val message: JSONObject? = raw.optJSONObject("message")

    /** Sous-commande utilisée (ex. « activer » pour /bienvenue activer). */
    val subcommand: String?
    private val options = HashMap<String, Any>()

    init {
        var opts = data.optJSONArray("options")
        val first = opts?.optJSONObject(0)
        if (first != null && first.optInt("type") == Opt.SUB) {
            subcommand = first.getString("name")
            opts = first.optJSONArray("options")
        } else {
            subcommand = null
        }
        if (opts != null) {
            for (k in 0 until opts.length()) {
                val o = opts.getJSONObject(k)
                if (o.has("value")) options[o.getString("name")] = o.get("value")
            }
        }
    }

    // ------------------------------------------------------------ options

    fun string(name: String): String? = options[name]?.toString()
    fun long(name: String): Long? = (options[name] as? Number)?.toLong() ?: options[name]?.toString()?.toLongOrNull()
    fun bool(name: String): Boolean? = options[name] as? Boolean

    /** Identifiant d'un utilisateur, rôle ou salon passé en option. */
    fun snowflake(name: String): String? = options[name]?.toString()

    private fun resolved(kind: String, id: String?): JSONObject? =
        id?.let { data.optJSONObject("resolved")?.optJSONObject(kind)?.optJSONObject(it) }

    fun user(name: String): JSONObject? = resolved("users", snowflake(name))
    fun resolvedMember(name: String): JSONObject? = resolved("members", snowflake(name))
    fun role(name: String): JSONObject? = resolved("roles", snowflake(name))
    fun channel(name: String): JSONObject? = resolved("channels", snowflake(name))

    // ------------------------------------------------------------ membre qui interagit

    val permissions: Long = member?.optString("permissions")?.toLongOrNull() ?: 0L
    fun hasPermission(bit: Long): Boolean = permissions and Perm.ADMIN != 0L || permissions and bit != 0L
    val memberRoles: List<String> = member?.optJSONArray("roles")?.strings() ?: emptyList()

    // ------------------------------------------------------------ réponses

    @Volatile private var state = NONE
    val replied: Boolean get() = state != NONE

    /** Réponse (ou modification de la réponse différée). */
    fun reply(
        embed: JSONObject? = null,
        content: String? = null,
        components: JSONArray? = null,
        ephemeral: Boolean = false,
        extra: (JSONObject) -> Unit = {},
    ) {
        val payload = message(content, embed, components).also(extra)
        when (state) {
            DEFERRED -> rest.editOriginal(appId, token, payload)
            REPLIED -> {
                if (ephemeral) payload.put("flags", EPHEMERAL)
                rest.followUp(appId, token, payload)
            }
            else -> {
                if (ephemeral) payload.put("flags", EPHEMERAL)
                rest.interactionCallback(id, token, JSONObject().put("type", 4).put("data", payload))
            }
        }
        state = REPLIED
    }

    /** « Le bot réfléchit… » : à utiliser avant une action longue. */
    fun defer(ephemeral: Boolean = false) {
        val data = JSONObject()
        if (ephemeral) data.put("flags", EPHEMERAL)
        rest.interactionCallback(id, token, JSONObject().put("type", 5).put("data", data))
        state = DEFERRED
    }

    /** Remplace le message qui porte le bouton cliqué. */
    fun updateMessage(embed: JSONObject?, components: JSONArray?) {
        val data = message(null, embed, components ?: JSONArray())
        rest.interactionCallback(id, token, JSONObject().put("type", 7).put("data", data))
        state = REPLIED
    }

    fun error(text: String) = reply(Embeds.error(text), ephemeral = true)
    fun success(text: String, ephemeral: Boolean = false) = reply(Embeds.success(text), ephemeral = ephemeral)

    private companion object {
        const val NONE = 0
        const val DEFERRED = 1
        const val REPLIED = 2
        const val EPHEMERAL = 64
    }
}
