package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.DiscordRest
import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Interaction
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.button
import com.bothostinger.app.bot.engine.categoryOpt
import com.bothostinger.app.bot.engine.channelMention
import com.bothostinger.app.bot.engine.displayName
import com.bothostinger.app.bot.engine.field
import com.bothostinger.app.bot.engine.mention
import com.bothostinger.app.bot.engine.message
import com.bothostinger.app.bot.engine.roleMention
import com.bothostinger.app.bot.engine.roleOpt
import com.bothostinger.app.bot.engine.row
import com.bothostinger.app.bot.engine.stringOpt
import com.bothostinger.app.bot.engine.sub
import com.bothostinger.app.bot.engine.textChannelOpt
import com.bothostinger.app.bot.engine.userOpt
import com.bothostinger.app.data.obj
import com.bothostinger.app.data.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class TicketModule : Module(
    id = "tickets",
    title = "Tickets",
    description = "Support privé : un bouton crée un salon visible seulement par le membre et le staff.",
) {
    override val setup = "/ticket-panel dans le salon de support, avec le rôle du staff. " +
        "Le bot a besoin des permissions Gérer les salons et Gérer les rôles."

    override val commands = listOf(
        Command(
            "ticket-panel", "Publier le panneau d'ouverture de tickets", Perm.MANAGE_GUILD,
            listOf(
                textChannelOpt("salon", "Salon du panneau (celui-ci par défaut)", required = false),
                roleOpt("role_support", "Rôle du staff qui voit les tickets", required = false),
                categoryOpt("categorie", "Catégorie où créer les tickets"),
                stringOpt("titre", "Titre du panneau", required = false, maxLength = 200),
                stringOpt("message", "Texte du panneau", required = false, maxLength = 1500),
            ),
        ) { panel(it) },
        Command(
            "ticket", "Gérer le ticket actuel",
            options = listOf(
                sub("fermer", "Fermer ce ticket"),
                sub("ajouter", "Ajouter un membre au ticket", userOpt("membre", "Le membre")),
                sub("retirer", "Retirer un membre du ticket", userOpt("membre", "Le membre")),
            ),
        ) { i ->
            when (i.subcommand) {
                "fermer" -> close(i)
                "ajouter" -> addOrRemove(i, add = true)
                "retirer" -> addOrRemove(i, add = false)
            }
        },
    )

    private fun panel(i: Interaction) {
        val gid = i.guildId!!
        val channel = i.snowflake("salon") ?: i.channelId
        db.edit(gid) { g ->
            val cfg = g.obj("config").obj("tickets")
            i.snowflake("role_support")?.let { cfg.put("supportRole", it) }
            i.snowflake("categorie")?.let { cfg.put("category", it) }
        }
        val embed = Embeds.base(
            i.string("titre") ?: "🎫 Support",
            i.string("message") ?: "Besoin d'aide ? Clique sur le bouton ci-dessous pour ouvrir un ticket privé avec le staff.",
        )
        rest.createMessage(channel, message(embed = embed, components = row(button("tk:open", "Ouvrir un ticket", 1, "🎫"))))
        i.success("Panneau de tickets publié dans ${channelMention(channel)}.", ephemeral = true)
    }

    override fun onComponent(i: Interaction): Boolean {
        when (i.customId) {
            "tk:open" -> open(i)
            "tk:close" -> close(i)
            else -> return false
        }
        return true
    }

    private fun open(i: Interaction) {
        val gid = i.guildId ?: return
        val uid = i.userId
        val existing = db.read(gid) { g -> g.optJSONObject("tickets")?.optJSONObject("open")?.str(uid) }
        if (existing != null) {
            val stillThere = runCatching { rest.getChannel(existing); true }
                .getOrElse { e -> !(e is DiscordRest.ApiException && e.status == 404) }
            if (stillThere) return i.error("Tu as déjà un ticket ouvert : ${channelMention(existing)}")
            forget(gid, existing)
        }
        i.defer(ephemeral = true)

        val (support, category, number) = db.edit(gid) { g ->
            val cfg = g.obj("config").obj("tickets")
            val n = cfg.optInt("counter") + 1
            cfg.put("counter", n)
            Triple(cfg.str("supportRole"), cfg.str("category"), n)
        }
        val member = Perm.VIEW or Perm.SEND or Perm.HISTORY or Perm.ATTACH or Perm.EMBED
        val overwrites = JSONArray()
            .put(overwrite(gid, 0, allow = 0, deny = Perm.VIEW))
            .put(overwrite(uid, 1, allow = member, deny = 0))
            .put(overwrite(ctx.botId, 1, allow = member or Perm.MANAGE_CHANNELS, deny = 0))
        support?.let { overwrites.put(overwrite(it, 0, allow = member, deny = 0)) }

        val payload = JSONObject()
            .put("name", "ticket-%04d".format(number))
            .put("type", 0)
            .put("topic", "Ticket de ${displayName(i.user)} (${i.userId})")
            .put("permission_overwrites", overwrites)
        category?.let { payload.put("parent_id", it) }
        val channel = rest.createChannel(gid, payload, "Ticket de ${displayName(i.user)}").getString("id")

        db.edit(gid) { g ->
            val t = g.obj("tickets")
            t.obj("open").put(uid, channel)
            t.obj("channels").put(channel, uid)
        }
        rest.createMessage(
            channel,
            message(
                content = mention(uid) + (support?.let { " ${roleMention(it)}" } ?: ""),
                embed = Embeds.base(
                    "🎫 Ticket #%04d".format(number),
                    "Bienvenue ${mention(uid)} ! Explique ton problème, le staff va te répondre.\n" +
                        "Clique sur 🔒 pour fermer le ticket.",
                ),
                components = row(button("tk:close", "Fermer le ticket", 4, "🔒")),
            ),
        )
        i.reply(Embeds.success("Ton ticket est ouvert : ${channelMention(channel)}"))
        ctx.modLog(gid, Embeds.base("🎫 Ticket ouvert").field("Membre", mention(uid), true).field("Salon", channelMention(channel), true))
    }

    private fun close(i: Interaction) {
        val gid = i.guildId ?: return
        val owner = db.read(gid) { g -> g.optJSONObject("tickets")?.optJSONObject("channels")?.str(i.channelId) }
            ?: return i.error("Ce salon n'est pas un ticket.")
        val support = db.read(gid) { g -> g.optJSONObject("config")?.optJSONObject("tickets")?.str("supportRole") }
        val allowed = owner == i.userId || i.hasPermission(Perm.MANAGE_CHANNELS) || (support != null && support in i.memberRoles)
        if (!allowed) return i.error("Seuls l'auteur du ticket et le staff peuvent le fermer.")

        i.reply(Embeds.base("🔒 Fermeture du ticket", "Ticket fermé par ${mention(i.userId)}. Suppression dans 5 secondes…", Embeds.RED))
        forget(gid, i.channelId)
        ctx.modLog(gid, Embeds.base("🔒 Ticket fermé").field("Auteur", mention(owner), true).field("Fermé par", mention(i.userId), true))
        val channel = i.channelId
        ctx.scope.launch {
            delay(5_000)
            runCatching { rest.deleteChannel(channel, "Ticket fermé par ${displayName(i.user)}") }
        }
    }

    private fun addOrRemove(i: Interaction, add: Boolean) {
        val gid = i.guildId!!
        val isTicket = db.read(gid) { g -> g.optJSONObject("tickets")?.optJSONObject("channels")?.has(i.channelId) == true }
        if (!isTicket) return i.error("Utilise cette commande dans un ticket.")
        val support = db.read(gid) { g -> g.optJSONObject("config")?.optJSONObject("tickets")?.str("supportRole") }
        if (!i.hasPermission(Perm.MANAGE_CHANNELS) && (support == null || support !in i.memberRoles)) {
            return i.error("Seul le staff peut gérer les membres d'un ticket.")
        }
        val uid = i.snowflake("membre") ?: return
        val perms = Perm.VIEW or Perm.SEND or Perm.HISTORY or Perm.ATTACH
        rest.editPermission(i.channelId, uid, 1, if (add) perms else 0, if (add) 0 else Perm.VIEW)
        i.success(if (add) "${mention(uid)} a été ajouté au ticket." else "${mention(uid)} a été retiré du ticket.")
    }

    private fun forget(gid: String, channel: String) {
        db.edit(gid) { g ->
            val t = g.obj("tickets")
            val owner = t.obj("channels").str(channel)
            t.obj("channels").remove(channel)
            if (owner != null && t.obj("open").str(owner) == channel) t.obj("open").remove(owner)
        }
    }

    private fun overwrite(id: String, type: Int, allow: Long, deny: Long) =
        JSONObject().put("id", id).put("type", type).put("allow", allow.toString()).put("deny", deny.toString())
}
