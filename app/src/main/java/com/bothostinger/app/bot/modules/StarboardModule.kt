package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.author
import com.bothostinger.app.bot.engine.avatarUrl
import com.bothostinger.app.bot.engine.channelMention
import com.bothostinger.app.bot.engine.displayName
import com.bothostinger.app.bot.engine.field
import com.bothostinger.app.bot.engine.intOpt
import com.bothostinger.app.bot.engine.message
import com.bothostinger.app.bot.engine.stringOpt
import com.bothostinger.app.bot.engine.sub
import com.bothostinger.app.bot.engine.textChannelOpt
import com.bothostinger.app.data.obj
import com.bothostinger.app.data.objects
import com.bothostinger.app.data.str
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Les messages qui reçoivent assez d'étoiles sont mis en avant dans un salon. */
class StarboardModule : Module(
    id = "starboard",
    title = "Starboard",
    description = "Les meilleurs messages (ceux qui reçoivent assez d'étoiles) sont épinglés dans un salon.",
) {
    override val setup = "/starboard activer avec le salon et le nombre d'étoiles. " +
        "Nécessite « MESSAGE CONTENT INTENT » pour recopier le texte des messages."

    override val needsReactions = true
    override val needsMessageContent = true

    override val commands = listOf(
        Command(
            "starboard", "Configurer le starboard", Perm.MANAGE_GUILD,
            listOf(
                sub(
                    "activer", "Activer le starboard",
                    textChannelOpt("salon", "Salon du starboard"),
                    intOpt("seuil", "Nombre de réactions nécessaires (3 par défaut)", required = false, min = 1, max = 50),
                    stringOpt("emoji", "Emoji à compter (⭐ par défaut)", required = false, maxLength = 64),
                ),
                sub("desactiver", "Désactiver le starboard"),
            ),
        ) { i ->
            val gid = i.guildId!!
            if (i.subcommand == "desactiver") {
                db.edit(gid) { g -> g.obj("config").remove("starboard") }
                return@Command i.success("Starboard désactivé.")
            }
            val channel = i.snowflake("salon") ?: return@Command
            val threshold = i.long("seuil") ?: 3
            val emoji = i.string("emoji")?.trim()?.ifBlank { null } ?: "⭐"
            db.edit(gid) { g ->
                g.obj("config").put("starboard", JSONObject().put("channel", channel).put("threshold", threshold).put("emoji", emoji))
            }
            i.success("Starboard dans ${channelMention(channel)} : **$threshold** × $emoji pour y apparaître.")
        },
    )

    override fun onEvent(type: String, d: JSONObject) {
        if (type != "MESSAGE_REACTION_ADD" && type != "MESSAGE_REACTION_REMOVE") return
        val gid = d.str("guild_id") ?: return
        val cfg = db.read(gid) { g -> g.optJSONObject("config")?.optJSONObject("starboard")?.let { JSONObject(it.toString()) } } ?: return
        val emoji = cfg.optString("emoji", "⭐")
        if (!emojiMatches(d.optJSONObject("emoji"), emoji)) return
        val channel = d.getString("channel_id")
        val board = cfg.getString("channel")
        if (channel == board) return
        val msgId = d.getString("message_id")
        // Deux étoiles quasi simultanées ne doivent pas créer deux publications.
        synchronized(locks.getOrPut(msgId) { Any() }) { update(gid, cfg, channel, board, msgId, emoji) }
    }

    private val locks = ConcurrentHashMap<String, Any>()

    private fun update(gid: String, cfg: JSONObject, channel: String, board: String, msgId: String, emoji: String) {
        val msg = rest.getMessage(channel, msgId)
        val count = msg.optJSONArray("reactions")?.objects()
            ?.firstOrNull { emojiMatches(it.optJSONObject("emoji"), emoji) }?.optInt("count") ?: 0
        val threshold = cfg.optInt("threshold", 3)
        val posted = db.read(gid) { g -> g.optJSONObject("starboard")?.str(msgId) }
        val header = "$emoji **$count** · ${channelMention(channel)}"

        when {
            posted != null -> runCatching { rest.editMessage(board, posted, JSONObject().put("content", header)) }
            count >= threshold -> {
                val author = msg.optJSONObject("author")
                val embed = Embeds.base(description = msg.optString("content").take(3500).ifBlank { null })
                    .author(displayName(author), avatarUrl(author))
                    .field("Message d'origine", "[Aller au message](https://discord.com/channels/$gid/$channel/$msgId)")
                msg.optJSONArray("attachments")?.objects()?.firstOrNull { it.optString("content_type").startsWith("image/") }
                    ?.let { embed.put("image", JSONObject().put("url", it.optString("url"))) }
                val sent = rest.createMessage(board, message(header, embed))
                db.edit(gid) { g -> g.obj("starboard").put(msgId, sent.getString("id")) }
            }
        }
    }

    private fun emojiMatches(e: JSONObject?, wanted: String): Boolean {
        if (e == null) return false
        val name = e.optString("name")
        val id = e.str("id")
        return name == wanted || (id != null && wanted.contains(id)) || name == wanted.removePrefix(":").removeSuffix(":")
    }
}
