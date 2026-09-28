package com.bothostinger.app.bot.engine

import com.bothostinger.app.bot.DiscordRest
import com.bothostinger.app.data.str
import com.bothostinger.app.data.strings
import org.json.JSONArray
import org.json.JSONObject

class ModalField(
    val id: String,
    val label: String,
    val paragraph: Boolean = false,
    val required: Boolean = true,
    val value: String? = null,
    val placeholder: String? = null,
    val maxLength: Int = if (paragraph) 4000 else 256,
)

/** Commande slash, clic sur un bouton ou formulaire envoyé, avec des raccourcis pour lire les options et répondre. */
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

    /** Valeurs d'un formulaire (modal) envoyé : champ → texte saisi. */
    val modalValues: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        data.optJSONArray("components")?.let { rows ->
            for (r in 0 until rows.length()) {
                val comps = rows.optJSONObject(r)?.optJSONArray("components") ?: continue
                for (c in 0 until comps.length()) {
                    val comp = comps.getJSONObject(c)
                    out[comp.optString("custom_id")] = comp.optString("value")
                }
            }
        }
        out
    }

    /** Valeurs choisies dans un menu déroulant. */
    val selectedValues: List<String> by lazy { data.optJSONArray("values")?.strings() ?: emptyList() }

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

    /** Ouvre un formulaire (modal) Discord. [fields] : (id, libellé, long texte ?, obligatoire ?, valeur par défaut). */
    fun showModal(customId: String, title: String, fields: List<ModalField>) {
        val rows = JSONArray()
        fields.take(5).forEach { f ->
            val input = JSONObject()
                .put("type", 4)
                .put("custom_id", f.id)
                .put("label", f.label.take(45))
                .put("style", if (f.paragraph) 2 else 1)
                .put("required", f.required)
                .put("max_length", f.maxLength)
            if (!f.placeholder.isNullOrBlank()) input.put("placeholder", f.placeholder.take(100))
            if (!f.value.isNullOrBlank()) input.put("value", f.value.take(f.maxLength))
            rows.put(JSONObject().put("type", 1).put("components", JSONArray().put(input)))
        }
        val data = JSONObject().put("custom_id", customId).put("title", title.take(45)).put("components", rows)
        rest.interactionCallback(id, token, JSONObject().put("type", 9).put("data", data))
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
