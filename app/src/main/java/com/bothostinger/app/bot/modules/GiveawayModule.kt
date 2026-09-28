package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.BotRuntime
import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Interaction
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.button
import com.bothostinger.app.bot.engine.channelMention
import com.bothostinger.app.bot.engine.field
import com.bothostinger.app.bot.engine.formatDuration
import com.bothostinger.app.bot.engine.intOpt
import com.bothostinger.app.bot.engine.mention
import com.bothostinger.app.bot.engine.message
import com.bothostinger.app.bot.engine.parseDuration
import com.bothostinger.app.bot.engine.row
import com.bothostinger.app.bot.engine.stringOpt
import com.bothostinger.app.bot.engine.sub
import com.bothostinger.app.bot.engine.textChannelOpt
import com.bothostinger.app.bot.engine.ts
import com.bothostinger.app.data.arr
import com.bothostinger.app.data.entries
import com.bothostinger.app.data.obj
import com.bothostinger.app.data.strings
import org.json.JSONArray
import org.json.JSONObject

class GiveawayModule : Module(
    id = "giveaways",
    title = "Giveaways",
    description = "Concours avec bouton de participation, tirage automatique et relance.",
) {
    override val setup = "/giveaway lancer, puis les membres cliquent sur « Participer ». " +
        "Le tirage se fait tout seul à la fin, même après un redémarrage du bot."

    private val messageIdOpt = stringOpt("message_id", "ID du message du giveaway")

    override val commands = listOf(
        Command(
            "giveaway", "Gérer les giveaways", Perm.MANAGE_GUILD,
            listOf(
                sub(
                    "lancer", "Lancer un giveaway",
                    stringOpt("duree", "Durée : 30m, 2h, 7j…"),
                    stringOpt("lot", "Ce qu'on gagne", maxLength = 200),
                    intOpt("gagnants", "Nombre de gagnants (1 par défaut)", required = false, min = 1, max = 20),
                    textChannelOpt("salon", "Salon (celui-ci par défaut)", required = false),
                ),
                sub("terminer", "Terminer un giveaway maintenant", messageIdOpt),
                sub("relancer", "Tirer de nouveaux gagnants", messageIdOpt),
            ),
        ) { i ->
            when (i.subcommand) {
                "lancer" -> start(i)
                "terminer" -> finish(i, reroll = false)
                "relancer" -> finish(i, reroll = true)
            }
        },
    )

    private fun embed(gw: JSONObject, ended: Boolean, winners: List<String> = emptyList()): JSONObject {
        val prize = gw.optString("prize")
        return if (!ended) {
            Embeds.base(
                "🎉 GIVEAWAY 🎉",
                "**$prize**\n\nClique sur le bouton pour participer !\n\n" +
                    "Fin : ${ts(gw.optLong("endsAt"))}\nGagnant(s) : **${gw.optInt("winners", 1)}**\nOrganisé par ${mention(gw.optString("host"))}",
            )
        } else {
            Embeds.base(
                "🎉 GIVEAWAY TERMINÉ",
                "**$prize**\n\nGagnant(s) : ${if (winners.isEmpty()) "aucun participant" else winners.joinToString(" ") { mention(it) }}\n" +
                    "Organisé par ${mention(gw.optString("host"))}",
                Embeds.GREEN,
            ).field("Participants", gw.optJSONArray("entrants")?.length()?.toString() ?: "0")
        }
    }

    private fun joinButton(count: Int, ended: Boolean = false) =
        row(button("gw:join", if (ended) "Terminé" else "Participer ($count)", if (ended) 2 else 1, "🎉", disabled = ended))

    private fun start(i: Interaction) {
        val duration = parseDuration(i.string("duree").orEmpty())
        if (duration == null || duration < 10_000 || duration > 60L * 86_400_000) {
            return i.error("Durée invalide (entre 10 secondes et 60 jours). Exemples : `30m`, `2h`, `7j`.")
        }
        val channel = i.snowflake("salon") ?: i.channelId
        val gw = JSONObject()
            .put("prize", i.string("lot").orEmpty())
            .put("winners", i.long("gagnants") ?: 1)
            .put("host", i.userId)
            .put("channel", channel)
            .put("endsAt", System.currentTimeMillis() + duration)
            .put("entrants", JSONArray())
            .put("ended", false)
        val msg = rest.createMessage(channel, message(embed = embed(gw, ended = false), components = joinButton(0)))
        db.edit(i.guildId!!) { g -> g.obj("giveaways").put(msg.getString("id"), gw) }
        i.success("Giveaway lancé dans ${channelMention(channel)} pour ${formatDuration(duration)} !", ephemeral = true)
    }

    override fun onComponent(i: Interaction): Boolean {
        if (i.customId != "gw:join") return false
        val gid = i.guildId ?: return true
        val msgId = i.message?.optString("id") ?: return true
        val result: Toggle? = db.edit(gid) { g ->
            val gw = g.optJSONObject("giveaways")?.optJSONObject(msgId) ?: return@edit null
            if (gw.optBoolean("ended")) return@edit Toggle.ENDED
            val entrants = gw.arr("entrants")
            val index = entrants.strings().indexOf(i.userId)
            if (index >= 0) entrants.remove(index) else entrants.put(i.userId)
            Toggle(joined = index < 0, count = entrants.length())
        }
        when {
            result == null -> i.error("Ce giveaway n'existe plus.")
            result === Toggle.ENDED -> i.error("Ce giveaway est terminé.")
            result.joined -> {
                i.success("Tu participes au giveaway ! Bonne chance 🍀 (clique à nouveau pour te retirer)", ephemeral = true)
                refreshCount(i, result.count)
            }
            else -> {
                i.success("Tu ne participes plus au giveaway.", ephemeral = true)
                refreshCount(i, result.count)
            }
        }
        return true
    }

    private class Toggle(val joined: Boolean, val count: Int) {
        companion object {
            val ENDED = Toggle(false, -1)
        }
    }

    private fun refreshCount(i: Interaction, count: Int) {
        runCatching {
            rest.editMessage(i.channelId, i.message!!.getString("id"), JSONObject().put("components", joinButton(count)))
        }
    }

    private fun finish(i: Interaction, reroll: Boolean) {
        val msgId = i.string("message_id")?.trim().orEmpty()
        val exists = db.read(i.guildId!!) { g -> g.optJSONObject("giveaways")?.optJSONObject(msgId) }
        if (exists == null) return i.error("Giveaway introuvable. Copie l'ID du message du giveaway (mode développeur).")
        if (!reroll && exists.optBoolean("ended")) return i.error("Ce giveaway est déjà terminé. Utilise `/giveaway relancer`.")
        if (reroll && !exists.optBoolean("ended")) return i.error("Ce giveaway n'est pas encore terminé.")
        i.defer(ephemeral = true)
        end(i.guildId!!, msgId, reroll)
        i.reply(Embeds.success(if (reroll) "Nouveaux gagnants tirés !" else "Giveaway terminé !"))
    }

    override fun tick(now: Long) {
        db.guildIds().forEach { gid ->
            val due = db.read(gid) { g ->
                (g.optJSONObject("giveaways") ?: JSONObject()).entries()
                    .filter { (_, gw) -> !gw.optBoolean("ended") && gw.optLong("endsAt") <= now }
                    .map { it.first }
            }
            due.forEach { msgId ->
                runCatching { end(gid, msgId, reroll = false) }
                    .onFailure { BotRuntime.log("Fin du giveaway impossible : ${it.message}", isError = true) }
            }
        }
        // Nettoyage : on garde les giveaways terminés 7 jours pour pouvoir relancer.
        db.guildIds().forEach { gid ->
            db.edit(gid) { g ->
                val all = g.optJSONObject("giveaways") ?: return@edit
                all.entries().filter { (_, gw) -> gw.optBoolean("ended") && now - gw.optLong("endsAt") > 7 * 86_400_000L }
                    .forEach { all.remove(it.first) }
            }
        }
    }

    private fun end(guildId: String, msgId: String, reroll: Boolean) {
        val gw = db.edit(guildId) { g ->
            g.optJSONObject("giveaways")?.optJSONObject(msgId)?.let { it.put("ended", true); JSONObject(it.toString()) }
        } ?: return
        val channel = gw.getString("channel")
        val winners = gw.optJSONArray("entrants")?.strings().orEmpty().shuffled().take(gw.optInt("winners", 1))
        if (!reroll) {
            rest.editMessage(
                channel, msgId,
                message(embed = embed(gw, ended = true, winners = winners), components = joinButton(0, ended = true)),
            )
        }
        val prize = gw.optString("prize")
        val text = if (winners.isEmpty()) {
            "Le giveaway **$prize** est terminé, mais personne n'a participé 😢"
        } else {
            "🎉 Félicitations ${winners.joinToString(" ") { mention(it) }} ! Tu remportes **$prize** !"
        }
        rest.createMessage(
            channel,
            message(text).put("message_reference", JSONObject().put("message_id", msgId).put("fail_if_not_exists", false)),
        )
    }
}
