package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Interaction
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.channelMention
import com.bothostinger.app.bot.engine.displayName
import com.bothostinger.app.bot.engine.field
import com.bothostinger.app.bot.engine.formatDuration
import com.bothostinger.app.bot.engine.intOpt
import com.bothostinger.app.bot.engine.mention
import com.bothostinger.app.bot.engine.message
import com.bothostinger.app.bot.engine.msToIso
import com.bothostinger.app.bot.engine.parseDuration
import com.bothostinger.app.bot.engine.snowflakeTime
import com.bothostinger.app.bot.engine.stringOpt
import com.bothostinger.app.bot.engine.textChannelOpt
import com.bothostinger.app.bot.engine.ts
import com.bothostinger.app.bot.engine.userOpt
import com.bothostinger.app.data.arr
import com.bothostinger.app.data.obj
import com.bothostinger.app.data.objects
import org.json.JSONObject

class ModerationModule : Module(
    id = "moderation",
    title = "Modération",
    description = "Ban, expulsion, mute, avertissements, nettoyage et verrouillage des salons.",
) {
    override val setup = "Donne au bot un rôle placé au-dessus des membres à sanctionner, avec les permissions " +
        "Bannir, Expulser, Exclure temporairement, Gérer les messages et Gérer les salons. " +
        "Seuls les membres qui ont ces permissions voient les commandes."

    private val reason = stringOpt("raison", "Raison de la sanction", required = false, maxLength = 400)

    override val commands = listOf(
        Command(
            "ban", "Bannir un membre", Perm.BAN,
            listOf(
                userOpt("membre", "Le membre à bannir"), reason,
                intOpt("supprimer_messages", "Supprimer ses messages des X derniers jours (0-7)", required = false, min = 0, max = 7),
            ),
        ) { ban(it) },
        Command("unban", "Débannir un utilisateur", Perm.BAN, listOf(stringOpt("id", "ID de l'utilisateur"), reason)) { unban(it) },
        Command("kick", "Expulser un membre", Perm.KICK, listOf(userOpt("membre", "Le membre à expulser"), reason)) { kick(it) },
        Command(
            "mute", "Rendre muet un membre (exclusion temporaire)", Perm.MODERATE,
            listOf(userOpt("membre", "Le membre"), stringOpt("duree", "Durée : 10m, 1h, 2j… (max 28j)"), reason),
        ) { mute(it) },
        Command("unmute", "Rendre la parole à un membre", Perm.MODERATE, listOf(userOpt("membre", "Le membre"))) { unmute(it) },
        Command(
            "clear", "Supprimer des messages", Perm.MANAGE_MESSAGES,
            listOf(intOpt("nombre", "Nombre de messages (1-100)", min = 1, max = 100), userOpt("membre", "Seulement ce membre", required = false)),
        ) { clear(it) },
        Command("warn", "Avertir un membre", Perm.MODERATE, listOf(userOpt("membre", "Le membre"), stringOpt("raison", "Raison", maxLength = 400))) {
            warn(it)
        },
        Command("warns", "Voir les avertissements d'un membre", Perm.MODERATE, listOf(userOpt("membre", "Le membre"))) { warns(it) },
        Command("resetwarns", "Effacer les avertissements d'un membre", Perm.MODERATE, listOf(userOpt("membre", "Le membre"))) {
            resetWarns(it)
        },
        Command(
            "slowmode", "Mode lent du salon", Perm.MANAGE_CHANNELS,
            listOf(intOpt("secondes", "Délai entre deux messages (0 = désactivé, max 21600)", min = 0, max = 21600)),
        ) { slowmode(it) },
        Command("lock", "Verrouiller un salon", Perm.MANAGE_CHANNELS, listOf(textChannelOpt("salon", "Le salon", required = false), reason)) {
            lock(it, true)
        },
        Command("unlock", "Déverrouiller un salon", Perm.MANAGE_CHANNELS, listOf(textChannelOpt("salon", "Le salon", required = false))) {
            lock(it, false)
        },
    )

    /** Vérifie la cible ; renvoie null (et répond) si elle n'est pas valide. */
    private fun target(i: Interaction): JSONObject? {
        val user = i.user("membre")
        when {
            user == null -> i.error("Membre introuvable.")
            user.optString("id") == i.userId -> i.error("Tu ne peux pas te sanctionner toi-même.")
            user.optString("id") == ctx.botId -> i.error("Je ne vais pas me sanctionner moi-même 😅")
            ctx.guilds[i.guildId]?.ownerId == user.optString("id") -> i.error("Impossible de sanctionner le propriétaire du serveur.")
            else -> return user
        }
        return null
    }

    private fun auditReason(i: Interaction) = "${displayName(i.user)} : ${i.string("raison") ?: "Aucune raison"}"

    private fun logAction(i: Interaction, action: String, target: String, extra: String? = null) {
        val embed = Embeds.base("🛡️ $action")
            .field("Membre", target, inline = true)
            .field("Modérateur", mention(i.userId), inline = true)
            .field("Raison", i.string("raison") ?: "Aucune raison")
        if (extra != null) embed.field("Détails", extra)
        ctx.modLog(i.guildId!!, embed)
    }

    private fun ban(i: Interaction) {
        val user = target(i) ?: return
        val uid = user.getString("id")
        val days = i.long("supprimer_messages")?.toInt() ?: 0
        dm(uid, "🔨 Tu as été banni de **${ctx.guildName(i.guildId)}**.", i.string("raison"))
        rest.ban(i.guildId!!, uid, days * 86_400, auditReason(i))
        i.reply(Embeds.base("🔨 Membre banni", "**${displayName(user)}** a été banni.").field("Raison", i.string("raison") ?: "Aucune raison"))
        logAction(i, "Bannissement", "${displayName(user)} (${mention(uid)})")
    }

    private fun unban(i: Interaction) {
        val uid = i.string("id")?.trim()?.removePrefix("<@")?.removeSuffix(">")?.takeIf { it.all(Char::isDigit) }
        if (uid == null) {
            i.error("ID invalide. Active le mode développeur de Discord et copie l'ID de l'utilisateur.")
            return
        }
        rest.unban(i.guildId!!, uid, auditReason(i))
        i.success("${mention(uid)} a été débanni.")
        logAction(i, "Débannissement", mention(uid))
    }

    private fun kick(i: Interaction) {
        val user = target(i) ?: return
        val uid = user.getString("id")
        dm(uid, "👢 Tu as été expulsé de **${ctx.guildName(i.guildId)}**.", i.string("raison"))
        rest.kick(i.guildId!!, uid, auditReason(i))
        i.reply(Embeds.base("👢 Membre expulsé", "**${displayName(user)}** a été expulsé.").field("Raison", i.string("raison") ?: "Aucune raison"))
        logAction(i, "Expulsion", "${displayName(user)} (${mention(uid)})")
    }

    private fun mute(i: Interaction) {
        val user = target(i) ?: return
        val duration = parseDuration(i.string("duree").orEmpty())
        if (duration == null || duration > 28L * 86_400_000) {
            i.error("Durée invalide. Exemples : `10m`, `1h30m`, `2j` (28 jours maximum).")
            return
        }
        val until = System.currentTimeMillis() + duration
        rest.timeout(i.guildId!!, user.getString("id"), msToIso(until), auditReason(i))
        i.reply(
            Embeds.base("🔇 Membre rendu muet", "${mention(user.getString("id"))} ne peut plus parler pendant **${formatDuration(duration)}**.")
                .field("Fin", ts(until))
                .field("Raison", i.string("raison") ?: "Aucune raison")
        )
        logAction(i, "Mute", mention(user.getString("id")), "Durée : ${formatDuration(duration)}")
    }

    private fun unmute(i: Interaction) {
        val user = target(i) ?: return
        rest.timeout(i.guildId!!, user.getString("id"), null, auditReason(i))
        i.success("${mention(user.getString("id"))} peut de nouveau parler.")
        logAction(i, "Unmute", mention(user.getString("id")))
    }

    private fun clear(i: Interaction) {
        val count = i.long("nombre")?.toInt() ?: return
        val onlyUser = i.snowflake("membre")
        i.defer(ephemeral = true)
        val cutoff = System.currentTimeMillis() - 14L * 86_400_000 + 60_000
        val ids = rest.getMessages(i.channelId, 100).objects()
            .filter { onlyUser == null || it.optJSONObject("author")?.optString("id") == onlyUser }
            .filter { snowflakeTime(it.getString("id")) > cutoff }
            .take(count)
            .map { it.getString("id") }
        when (ids.size) {
            0 -> {
                i.reply(Embeds.error("Aucun message à supprimer (les messages de plus de 14 jours ne peuvent pas être supprimés)."))
                return
            }
            1 -> rest.deleteMessage(i.channelId, ids[0], "Clear par ${displayName(i.user)}")
            else -> rest.bulkDelete(i.channelId, ids, "Clear par ${displayName(i.user)}")
        }
        i.reply(Embeds.success("🧹 **${ids.size}** message(s) supprimé(s)."))
        logAction(i, "Nettoyage", channelMention(i.channelId), "${ids.size} message(s)")
    }

    private fun warn(i: Interaction) {
        val user = target(i) ?: return
        val uid = user.getString("id")
        val reasonText = i.string("raison") ?: "Aucune raison"
        val total = db.edit(i.guildId!!) { g ->
            val list = g.obj("warns").arr(uid)
            list.put(JSONObject().put("reason", reasonText).put("mod", i.userId).put("at", System.currentTimeMillis()))
            list.length()
        }
        dm(uid, "⚠️ Tu as reçu un avertissement sur **${ctx.guildName(i.guildId)}**.", reasonText)
        i.reply(
            Embeds.base("⚠️ Avertissement", "${mention(uid)} a reçu un avertissement.")
                .field("Raison", reasonText)
                .field("Total", "$total avertissement(s)")
        )
        logAction(i, "Avertissement", mention(uid), "Total : $total")
    }

    private fun warns(i: Interaction) {
        val user = i.user("membre") ?: return i.error("Membre introuvable.")
        val uid = user.getString("id")
        val list = db.read(i.guildId!!) { g -> g.optJSONObject("warns")?.optJSONArray(uid)?.objects().orEmpty() }
        if (list.isEmpty()) {
            i.reply(Embeds.base("📋 Avertissements", "**${displayName(user)}** n'a aucun avertissement. 😇"))
            return
        }
        val text = list.takeLast(15).mapIndexed { n, w ->
            "**${n + 1}.** ${w.optString("reason")} — par ${mention(w.optString("mod"))} ${ts(w.optLong("at"))}"
        }.joinToString("\n")
        i.reply(Embeds.base("📋 Avertissements de ${displayName(user)} (${list.size})", text))
    }

    private fun resetWarns(i: Interaction) {
        val user = i.user("membre") ?: return i.error("Membre introuvable.")
        db.edit(i.guildId!!) { g -> g.obj("warns").remove(user.getString("id")) }
        i.success("Avertissements de **${displayName(user)}** effacés.")
        logAction(i, "Avertissements effacés", mention(user.getString("id")))
    }

    private fun slowmode(i: Interaction) {
        val seconds = i.long("secondes")?.toInt() ?: 0
        rest.modifyChannel(i.channelId, JSONObject().put("rate_limit_per_user", seconds), "Slowmode par ${displayName(i.user)}")
        i.success(if (seconds == 0) "Mode lent désactivé." else "Mode lent : **${formatDuration(seconds * 1000L)}** entre deux messages.")
    }

    private fun lock(i: Interaction, locked: Boolean) {
        val gid = i.guildId!!
        val channelId = i.snowflake("salon") ?: i.channelId
        val channel = rest.getChannel(channelId)
        val everyone = channel.optJSONArray("permission_overwrites")?.objects()?.firstOrNull { it.optString("id") == gid }
        var allow = everyone?.optString("allow")?.toLongOrNull() ?: 0L
        var deny = everyone?.optString("deny")?.toLongOrNull() ?: 0L
        if (locked) {
            deny = deny or Perm.SEND
            allow = allow and Perm.SEND.inv()
        } else {
            deny = deny and Perm.SEND.inv()
        }
        rest.editPermission(channelId, gid, 0, allow, deny, auditReason(i))
        if (locked) {
            i.reply(Embeds.base("🔒 Salon verrouillé", "${channelMention(channelId)} est verrouillé. Seul le staff peut écrire."))
        } else {
            i.reply(Embeds.base("🔓 Salon déverrouillé", "${channelMention(channelId)} est de nouveau ouvert."))
        }
        logAction(i, if (locked) "Salon verrouillé" else "Salon déverrouillé", channelMention(channelId))
    }

    /** Message privé à la personne sanctionnée (ignoré si ses MP sont fermés). */
    private fun dm(userId: String, text: String, reason: String?) {
        runCatching {
            rest.sendDm(userId, message(embed = Embeds.base(description = text).field("Raison", reason ?: "Aucune raison")))
        }
    }
}
