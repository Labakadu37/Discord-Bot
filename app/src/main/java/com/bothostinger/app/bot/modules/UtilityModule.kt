package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.boolOpt
import com.bothostinger.app.bot.engine.channelMention
import com.bothostinger.app.bot.engine.formatDuration
import com.bothostinger.app.bot.engine.intOpt
import com.bothostinger.app.bot.engine.mention
import com.bothostinger.app.bot.engine.message
import com.bothostinger.app.bot.engine.parseDuration
import com.bothostinger.app.bot.engine.stringOpt
import com.bothostinger.app.bot.engine.textChannelOpt
import com.bothostinger.app.bot.engine.ts
import com.bothostinger.app.data.arr
import com.bothostinger.app.data.objects
import org.json.JSONArray
import org.json.JSONObject

class UtilityModule : Module(
    id = "utilitaire",
    title = "Utilitaire",
    description = "Sondages, rappels et messages envoyés par le bot.",
) {
    override val commands = listOf(
        Command(
            "sondage", "Créer un sondage Discord",
            options = listOf(
                stringOpt("question", "La question", maxLength = 300),
                stringOpt("reponses", "Réponses séparées par | : Oui | Non | Peut-être"),
                intOpt("duree", "Durée en heures (24 par défaut)", required = false, min = 1, max = 768),
                boolOpt("choix_multiple", "Autoriser plusieurs réponses", required = false),
            ),
        ) { i ->
            val answers = i.string("reponses").orEmpty().split('|', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (answers.size !in 2..10) return@Command i.error("Il faut entre 2 et 10 réponses, séparées par `|`.")
            val poll = JSONObject()
                .put("question", JSONObject().put("text", i.string("question").orEmpty().take(300)))
                .put("answers", JSONArray(answers.map { JSONObject().put("poll_media", JSONObject().put("text", it.take(55))) }))
                .put("duration", i.long("duree") ?: 24)
                .put("allow_multiselect", i.bool("choix_multiple") ?: false)
            i.reply { it.put("poll", poll) }
        },
        Command(
            "rappel", "Le bot te rappelle quelque chose plus tard",
            options = listOf(stringOpt("dans", "Dans combien de temps : 10m, 2h, 1j…"), stringOpt("message", "Quoi te rappeler", maxLength = 500)),
        ) { i ->
            val delay = parseDuration(i.string("dans").orEmpty())
            if (delay == null || delay > 365L * 86_400_000) return@Command i.error("Durée invalide. Exemples : `10m`, `2h30m`, `3j`.")
            val at = System.currentTimeMillis() + delay
            db.edit(i.guildId!!) { g ->
                g.arr("reminders").put(
                    JSONObject().put("user", i.userId).put("channel", i.channelId).put("at", at).put("text", i.string("message").orEmpty())
                )
            }
            i.success("C'est noté ! Je te le rappelle ${ts(at)} (dans ${formatDuration(delay)}).", ephemeral = true)
        },
        Command(
            "dire", "Faire parler le bot", Perm.MANAGE_MESSAGES,
            listOf(
                stringOpt("message", "Le message", maxLength = 2000),
                textChannelOpt("salon", "Salon (celui-ci par défaut)", required = false),
                boolOpt("embed", "Envoyer dans un cadre", required = false),
            ),
        ) { i ->
            val channel = i.snowflake("salon") ?: i.channelId
            val text = i.string("message").orEmpty().replace("\\n", "\n")
            val payload = if (i.bool("embed") == true) message(embed = Embeds.base(description = text)) else message(text, allowUserMentionsOnly = true)
            rest.createMessage(channel, payload)
            i.success("Message envoyé dans ${channelMention(channel)}.", ephemeral = true)
        },
    )

    override fun tick(now: Long) {
        db.guildIds().forEach { gid ->
            val due = db.edit(gid) { g ->
                val list = g.optJSONArray("reminders") ?: return@edit emptyList()
                val all = list.objects()
                val (ready, later) = all.partition { it.optLong("at") <= now }
                if (ready.isNotEmpty()) g.put("reminders", JSONArray(later))
                ready
            }
            due.forEach { r ->
                runCatching {
                    rest.createMessage(
                        r.getString("channel"),
                        message(mention(r.getString("user")), Embeds.base("⏰ Rappel", r.optString("text"))),
                    )
                }
            }
        }
    }
}
