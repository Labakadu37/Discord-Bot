package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Interaction
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.avatarUrl
import com.bothostinger.app.bot.engine.displayName
import com.bothostinger.app.bot.engine.field
import com.bothostinger.app.bot.engine.formatDuration
import com.bothostinger.app.bot.engine.fr
import com.bothostinger.app.bot.engine.guildIconUrl
import com.bothostinger.app.bot.engine.image
import com.bothostinger.app.bot.engine.isoToMs
import com.bothostinger.app.bot.engine.mention
import com.bothostinger.app.bot.engine.roleMention
import com.bothostinger.app.bot.engine.snowflakeTime
import com.bothostinger.app.bot.engine.thumbnail
import com.bothostinger.app.bot.engine.ts
import com.bothostinger.app.bot.engine.userOpt
import com.bothostinger.app.data.strings

class GeneralModule : Module(
    id = "general",
    title = "Général",
    description = "Aide, infos du serveur, des membres et du bot.",
    alwaysOn = true,
) {
    override val commands = listOf(
        Command("aide", "Liste de toutes les commandes du bot") { help(it) },
        Command("ping", "Latence du bot") { i ->
            val ms = ctx.latencyMs
            i.reply(Embeds.base("🏓 Pong !", "Latence : **${if (ms >= 0) "$ms ms" else "mesure en cours…"}**"))
        },
        Command("botinfo", "Informations sur le bot") { botInfo(it) },
        Command("serveur", "Informations sur le serveur") { serverInfo(it) },
        Command("utilisateur", "Informations sur un membre", options = listOf(userOpt("membre", "Le membre", required = false))) {
            userInfo(it)
        },
        Command("avatar", "Affiche l'avatar d'un membre", options = listOf(userOpt("membre", "Le membre", required = false))) { i ->
            val user = i.user("membre") ?: i.user
            i.reply(Embeds.base("Avatar de ${displayName(user)}").image(avatarUrl(user).replace("size=512", "size=1024")))
        },
    )

    private fun help(i: Interaction) {
        val embed = Embeds.base(
            "📖 Commandes de ${ctx.botName}",
            "Bot hébergé avec **BotHostinger**. Tape `/` puis le nom d'une commande.",
        )
        ctx.modules.forEach { m ->
            if (m.commands.isEmpty()) return@forEach
            val list = m.commands.joinToString(" ") { c ->
                if (c.subcommands.isEmpty()) "`/${c.name}`" else c.subcommands.joinToString(" ") { "`/${c.name} ${it.name}`" }
            }
            embed.field(m.title, list)
        }
        i.reply(embed, ephemeral = true)
    }

    private fun botInfo(i: Interaction) {
        val uptime = System.currentTimeMillis() - ctx.startedAt
        val commandCount = ctx.modules.sumOf { it.commands.size }
        i.reply(
            Embeds.base("🤖 ${ctx.botName}", "Bot Discord hébergé sur mobile avec **BotHostinger**.")
                .field("Serveurs", ctx.guilds.size.fr(), inline = true)
                .field("Membres", ctx.guilds.values.sumOf { it.memberCount }.fr(), inline = true)
                .field("Commandes", commandCount.toString(), inline = true)
                .field("Latence", if (ctx.latencyMs >= 0) "${ctx.latencyMs} ms" else "—", inline = true)
                .field("En ligne depuis", formatDuration(uptime), inline = true)
                .field("Systèmes", ctx.modules.size.toString(), inline = true)
                .thumbnail(ctx.botAvatar)
        )
    }

    private fun serverInfo(i: Interaction) {
        val gid = i.guildId ?: return
        val g = ctx.guilds[gid]
        if (g == null) {
            i.error("Infos du serveur pas encore reçues, réessaie dans quelques secondes.")
            return
        }
        i.reply(
            Embeds.base("🏠 ${g.name}")
                .thumbnail(guildIconUrl(gid, g.icon))
                .field("Propriétaire", mention(g.ownerId), inline = true)
                .field("Membres", g.memberCount.fr(), inline = true)
                .field("Créé", ts(snowflakeTime(gid)), inline = true)
                .field("Salons", g.channelCount.toString(), inline = true)
                .field("Rôles", g.roleCount.toString(), inline = true)
                .field("Boosts", "${g.boosts} (niveau ${g.boostTier})", inline = true)
                .field("ID", "`$gid`")
        )
    }

    private fun userInfo(i: Interaction) {
        val user = i.user("membre") ?: i.user
        val member = if (i.snowflake("membre") != null) i.resolvedMember("membre") else i.member
        val uid = user.optString("id")
        val roles = member?.optJSONArray("roles")?.strings().orEmpty()
        val embed = Embeds.base("👤 ${displayName(user)}")
            .thumbnail(avatarUrl(user))
            .field("Nom d'utilisateur", "@${user.optString("username")}", inline = true)
            .field("ID", "`$uid`", inline = true)
            .field("Bot", if (user.optBoolean("bot")) "Oui" else "Non", inline = true)
            .field("Compte créé", "${ts(snowflakeTime(uid), "D")} (${ts(snowflakeTime(uid))})")
        isoToMs(member?.optString("joined_at"))?.let { embed.field("A rejoint le serveur", "${ts(it, "D")} (${ts(it)})") }
        if (roles.isNotEmpty()) embed.field("Rôles (${roles.size})", roles.take(20).joinToString(" ") { roleMention(it) })
        i.reply(embed)
    }
}
