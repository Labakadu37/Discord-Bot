package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.BotRuntime
import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Interaction
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.avatarUrl
import com.bothostinger.app.bot.engine.channelMention
import com.bothostinger.app.bot.engine.displayName
import com.bothostinger.app.bot.engine.footer
import com.bothostinger.app.bot.engine.mention
import com.bothostinger.app.bot.engine.message
import com.bothostinger.app.bot.engine.roleMention
import com.bothostinger.app.bot.engine.roleOpt
import com.bothostinger.app.bot.engine.stringOpt
import com.bothostinger.app.bot.engine.sub
import com.bothostinger.app.bot.engine.textChannelOpt
import com.bothostinger.app.bot.engine.thumbnail
import com.bothostinger.app.data.obj
import com.bothostinger.app.data.str
import org.json.JSONObject

class WelcomeModule : Module(
    id = "bienvenue",
    title = "Bienvenue",
    description = "Message de bienvenue, message de départ et rôle automatique pour les nouveaux.",
) {
    override val setup = "Active « SERVER MEMBERS INTENT » sur discord.com/developers → ton application → Bot. " +
        "Puis /bienvenue activer, /aurevoir activer et /autorole definir. " +
        "Variables : {user} {mention} {server} {count}."

    override val needsMembersIntent = true

    override val commands = listOf(
        Command(
            "bienvenue", "Message de bienvenue", Perm.MANAGE_GUILD,
            listOf(
                sub(
                    "activer", "Activer le message de bienvenue",
                    textChannelOpt("salon", "Salon"),
                    stringOpt("message", "Message ({user} {mention} {server} {count})", required = false, maxLength = 1500),
                ),
                sub("desactiver", "Désactiver le message de bienvenue"),
                sub("test", "Tester le message avec toi"),
            ),
        ) { i -> configure(i, "welcome", DEFAULT_WELCOME) },
        Command(
            "aurevoir", "Message quand un membre part", Perm.MANAGE_GUILD,
            listOf(
                sub(
                    "activer", "Activer le message de départ",
                    textChannelOpt("salon", "Salon"),
                    stringOpt("message", "Message ({user} {server} {count})", required = false, maxLength = 1500),
                ),
                sub("desactiver", "Désactiver le message de départ"),
                sub("test", "Tester le message avec toi"),
            ),
        ) { i -> configure(i, "goodbye", DEFAULT_GOODBYE) },
        Command(
            "autorole", "Rôle donné automatiquement aux nouveaux", Perm.MANAGE_ROLES,
            listOf(sub("definir", "Choisir le rôle", roleOpt("role", "Le rôle")), sub("retirer", "Ne plus donner de rôle")),
        ) { autorole(it) },
    )

    private fun configure(i: Interaction, key: String, default: String) {
        val gid = i.guildId!!
        when (i.subcommand) {
            "activer" -> {
                val channel = i.snowflake("salon") ?: return
                val text = i.string("message") ?: default
                db.edit(gid) { g -> g.obj("config").put(key, JSONObject().put("channel", channel).put("message", text)) }
                val warning = if (BotRuntime.state.value.missingMembersIntent) {
                    "\n\n⚠️ « SERVER MEMBERS INTENT » n'est pas activé sur le portail développeur : active-le puis redémarre le bot."
                } else ""
                i.success("Activé dans ${channelMention(channel)}.$warning")
            }
            "desactiver" -> {
                db.edit(gid) { g -> g.obj("config").remove(key) }
                i.success("Désactivé.")
            }
            "test" -> {
                val cfg = db.read(gid) { g -> g.optJSONObject("config")?.optJSONObject(key)?.let { JSONObject(it.toString()) } }
                    ?: return i.error("Ce message n'est pas activé. Utilise d'abord la sous-commande `activer`.")
                send(gid, cfg, i.user, welcome = key == "welcome")
                i.success("Message de test envoyé dans ${channelMention(cfg.getString("channel"))}.", ephemeral = true)
            }
        }
    }

    private fun autorole(i: Interaction) {
        val gid = i.guildId!!
        if (i.subcommand == "retirer") {
            db.edit(gid) { g -> g.obj("config").remove("autorole") }
            return i.success("Plus de rôle automatique.")
        }
        val role = i.snowflake("role") ?: return
        if (role == gid || i.role("role")?.optBoolean("managed") == true) return i.error("Ce rôle ne peut pas être donné par un bot.")
        db.edit(gid) { g -> g.obj("config").put("autorole", role) }
        i.success("Les nouveaux membres recevront ${roleMention(role)}. Mon rôle doit être placé au-dessus de celui-ci.")
    }

    override fun onEvent(type: String, d: JSONObject) {
        if (type != "GUILD_MEMBER_ADD" && type != "GUILD_MEMBER_REMOVE") return
        val gid = d.str("guild_id") ?: return
        val user = d.optJSONObject("user") ?: return
        val join = type == "GUILD_MEMBER_ADD"
        val (cfg, autorole) = db.read(gid) { g ->
            val c = g.optJSONObject("config")
            c?.optJSONObject(if (join) "welcome" else "goodbye")?.let { JSONObject(it.toString()) } to c?.str("autorole")
        }
        if (join && autorole != null && !user.optBoolean("bot")) {
            runCatching { rest.addRole(gid, user.getString("id"), autorole, "Autorôle") }
                .onFailure { BotRuntime.log("Autorôle impossible : ${it.message}", isError = true) }
        }
        if (cfg != null) runCatching { send(gid, cfg, user, join) }
    }

    private fun send(gid: String, cfg: JSONObject, user: JSONObject, welcome: Boolean) {
        val count = ctx.guilds[gid]?.memberCount ?: 0
        val text = cfg.optString("message")
            .replace("{user}", displayName(user))
            .replace("{mention}", mention(user.optString("id")))
            .replace("{server}", ctx.guildName(gid))
            .replace("{count}", count.toString())
        val embed = Embeds.base(if (welcome) "👋 Bienvenue !" else "😢 Au revoir", text, if (welcome) Embeds.ORANGE else Embeds.RED)
            .thumbnail(avatarUrl(user))
            .footer(if (welcome) "Membre n°$count" else "Nous sommes maintenant $count")
        rest.createMessage(cfg.getString("channel"), message(if (welcome) mention(user.optString("id")) else null, embed))
    }

    companion object {
        const val DEFAULT_WELCOME = "Bienvenue {mention} sur **{server}** ! Nous sommes maintenant **{count}** membres 🎉"
        const val DEFAULT_GOODBYE = "**{user}** a quitté **{server}**. À bientôt !"
    }
}
