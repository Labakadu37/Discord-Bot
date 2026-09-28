package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Interaction
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.author
import com.bothostinger.app.bot.engine.avatarUrl
import com.bothostinger.app.bot.engine.button
import com.bothostinger.app.bot.engine.channelMention
import com.bothostinger.app.bot.engine.displayName
import com.bothostinger.app.bot.engine.field
import com.bothostinger.app.bot.engine.mention
import com.bothostinger.app.bot.engine.message
import com.bothostinger.app.bot.engine.row
import com.bothostinger.app.bot.engine.stringOpt
import com.bothostinger.app.bot.engine.textChannelOpt
import com.bothostinger.app.data.obj
import com.bothostinger.app.data.objects
import com.bothostinger.app.data.str
import org.json.JSONArray
import org.json.JSONObject

class SuggestionModule : Module(
    id = "suggestions",
    title = "Suggestions",
    description = "Les membres proposent des idées, votent, et le staff accepte ou refuse.",
) {
    override val setup = "/suggestions-salon pour choisir le salon, puis les membres utilisent /suggestion."

    override val commands = listOf(
        Command("suggestions-salon", "Choisir le salon des suggestions", Perm.MANAGE_GUILD, listOf(textChannelOpt("salon", "Le salon"))) { i ->
            val channel = i.snowflake("salon") ?: return@Command
            db.edit(i.guildId!!) { g -> g.obj("config").obj("suggestions").put("channel", channel) }
            i.success("Les suggestions iront dans ${channelMention(channel)}.")
        },
        Command("suggestion", "Proposer une idée pour le serveur", options = listOf(stringOpt("idee", "Ton idée", maxLength = 1500))) { suggest(it) },
    )

    private fun suggest(i: Interaction) {
        val gid = i.guildId!!
        val (channel, number) = db.edit(gid) { g ->
            val cfg = g.obj("config").obj("suggestions")
            val n = cfg.optInt("counter") + 1
            if (cfg.str("channel") != null) cfg.put("counter", n)
            cfg.str("channel") to n
        }
        if (channel == null) return i.error("Aucun salon de suggestions. Un admin doit utiliser `/suggestions-salon`.")
        val embed = Embeds.base("💡 Suggestion #$number", i.string("idee").orEmpty())
            .author(displayName(i.user), avatarUrl(i.user))
            .field("Statut", "⏳ En attente")
        val staff = row(button("sg:ok", "Accepter", 3, "✅"), button("sg:no", "Refuser", 4, "✖️"))
        val msg = rest.createMessage(channel, message(embed = embed, components = staff))
        runCatching {
            rest.addReaction(channel, msg.getString("id"), "👍")
            rest.addReaction(channel, msg.getString("id"), "👎")
        }
        i.success("Suggestion envoyée dans ${channelMention(channel)} !", ephemeral = true)
    }

    override fun onComponent(i: Interaction): Boolean {
        val accepted = when (i.customId) {
            "sg:ok" -> true
            "sg:no" -> false
            else -> return false
        }
        if (!i.hasPermission(Perm.MANAGE_GUILD)) {
            i.error("Seul le staff peut accepter ou refuser une suggestion.")
            return true
        }
        val embed = i.message?.optJSONArray("embeds")?.optJSONObject(0)?.let { JSONObject(it.toString()) } ?: Embeds.base()
        val fields = embed.optJSONArray("fields")?.objects()?.filter { it.optString("name") != "Statut" }.orEmpty()
        embed.put("fields", JSONArray(fields))
        embed.put("color", if (accepted) Embeds.GREEN else Embeds.RED)
        embed.field("Statut", if (accepted) "✅ Acceptée par ${mention(i.userId)}" else "✖️ Refusée par ${mention(i.userId)}")
        i.updateMessage(embed, JSONArray())
        return true
    }
}
