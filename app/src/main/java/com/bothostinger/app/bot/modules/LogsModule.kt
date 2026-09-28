package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.avatarUrl
import com.bothostinger.app.bot.engine.channelMention
import com.bothostinger.app.bot.engine.displayName
import com.bothostinger.app.bot.engine.field
import com.bothostinger.app.bot.engine.mention
import com.bothostinger.app.bot.engine.snowflakeTime
import com.bothostinger.app.bot.engine.sub
import com.bothostinger.app.bot.engine.textChannelOpt
import com.bothostinger.app.bot.engine.thumbnail
import com.bothostinger.app.bot.engine.ts
import com.bothostinger.app.data.obj
import com.bothostinger.app.data.str
import org.json.JSONObject

class LogsModule : Module(
    id = "logs",
    title = "Logs",
    description = "Journal du serveur : sanctions, arrivées, départs et tickets dans un salon privé.",
) {
    override val setup = "/logs salon dans un salon réservé au staff. Les arrivées et départs nécessitent « SERVER MEMBERS INTENT »."

    override val needsMembersIntent = true

    override val commands = listOf(
        Command(
            "logs", "Configurer le salon de logs", Perm.MANAGE_GUILD,
            listOf(sub("salon", "Choisir le salon de logs", textChannelOpt("salon", "Le salon")), sub("desactiver", "Désactiver les logs")),
        ) { i ->
            val gid = i.guildId!!
            if (i.subcommand == "desactiver") {
                db.edit(gid) { g -> g.obj("config").remove("logs") }
                return@Command i.success("Logs désactivés.")
            }
            val channel = i.snowflake("salon") ?: return@Command
            db.edit(gid) { g -> g.obj("config").put("logs", JSONObject().put("channel", channel)) }
            i.success("Les logs seront envoyés dans ${channelMention(channel)}.")
        },
    )

    override fun onEvent(type: String, d: JSONObject) {
        val gid = d.str("guild_id") ?: return
        val user = d.optJSONObject("user") ?: return
        val uid = user.optString("id")
        when (type) {
            "GUILD_MEMBER_ADD" -> ctx.modLog(
                gid,
                Embeds.base("📥 Arrivée", "${mention(uid)} (${displayName(user)}) a rejoint le serveur.", Embeds.GREEN)
                    .thumbnail(avatarUrl(user))
                    .field("Compte créé", ts(snowflakeTime(uid)), inline = true)
                    .field("Membres", (ctx.guilds[gid]?.memberCount ?: 0).toString(), inline = true),
            )
            "GUILD_MEMBER_REMOVE" -> ctx.modLog(
                gid,
                Embeds.base("📤 Départ", "${mention(uid)} (${displayName(user)}) a quitté le serveur.", Embeds.RED)
                    .thumbnail(avatarUrl(user)),
            )
        }
    }
}
