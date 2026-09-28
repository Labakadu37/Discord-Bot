package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Interaction
import com.bothostinger.app.bot.engine.ModalField
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

class UtilityModule : Module(
    id = "utilitaire",
    title = "Utilitaire",
    description = "Sondages, rappels et messages envoyés par le bot.",
) {
    override val commands = listOf(
        Command(
            "embed", "Créer un embed avec un formulaire", Perm.MANAGE_MESSAGES,
            listOf(textChannelOpt("salon", "Salon (celui-ci par défaut)", required = false)),
        ) { i ->
            val channel = i.snowflake("salon") ?: i.channelId
            i.showModal(
                "embed:$channel", "Créer un embed",
                listOf(
                    ModalField("title", "Titre", required = false, maxLength = 256),
                    ModalField("description", "Texte", paragraph = true),
                    ModalField("color", "Couleur (ex. #3D6BFF)", required = false, maxLength = 7, placeholder = "#3D6BFF"),
                    ModalField("image", "Lien d'une image", required = false, maxLength = 500, placeholder = "https://…"),
                    ModalField("footer", "Bas de page", required = false, maxLength = 200),
                ),
            )
        },
        Command("calcul", "Calculatrice", options = listOf(stringOpt("expression", "Ex. (12 + 8) * 3 / 2", maxLength = 200))) { i ->
            val expr = i.string("expression").orEmpty()
            val result = runCatching { Calculator.eval(expr) }
            result.onSuccess { v ->
                val shown = if (v == floor(v) && abs(v) < 1e15) v.toLong().toString() else "%.6f".format(Locale.FRANCE, v).trimEnd('0').trimEnd(',')
                i.reply(Embeds.base("🧮 Calcul", "`$expr`\n= **$shown**"))
            }.onFailure { i.error("Calcul impossible : ${it.message}") }
        },
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

    override fun onModal(i: Interaction): Boolean {
        if (!i.customId.startsWith("embed:")) return false
        val channel = i.customId.removePrefix("embed:")
        val v = i.modalValues
        val color = v["color"]?.trim()?.removePrefix("#")?.toIntOrNull(16) ?: Embeds.BLUE
        val embed = Embeds.base(v["title"]?.ifBlank { null }, v["description"]?.ifBlank { null }, color and 0xFFFFFF)
        v["image"]?.trim()?.takeIf { it.startsWith("http") }?.let { embed.put("image", JSONObject().put("url", it)) }
        v["footer"]?.trim()?.takeIf { it.isNotEmpty() }?.let { embed.put("footer", JSONObject().put("text", it)) }
        rest.createMessage(channel, message(embed = embed))
        i.success("Embed publié dans ${channelMention(channel)}.", ephemeral = true)
        return true
    }

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

/** Calculatrice sûre (+ - * / ^ %, parenthèses, virgule décimale) : aucun code n'est exécuté. */
object Calculator {
    fun eval(input: String): Double {
        val p = Parser(input.replace(',', '.').replace('×', '*').replace('÷', '/').replace(" ", ""))
        val v = p.expression()
        require(p.done()) { "caractère inattendu « ${p.rest()} »" }
        require(!v.isNaN() && !v.isInfinite()) { "résultat indéfini" }
        return v
    }

    private class Parser(val s: String) {
        var pos = 0
        fun done() = pos >= s.length
        fun rest() = s.substring(pos).take(10)
        private fun peek(): Char? = s.getOrNull(pos)

        fun expression(): Double {
            var v = term()
            while (true) {
                v = when (peek()) {
                    '+' -> { pos++; v + term() }
                    '-' -> { pos++; v - term() }
                    else -> return v
                }
            }
        }

        fun term(): Double {
            var v = power()
            while (true) {
                v = when (peek()) {
                    '*' -> { pos++; v * power() }
                    '/' -> { pos++; val d = power(); require(d != 0.0) { "division par zéro" }; v / d }
                    '%' -> { pos++; v % power() }
                    else -> return v
                }
            }
        }

        fun power(): Double {
            val base = unary()
            if (peek() == '^') {
                pos++
                return base.pow(power())
            }
            return base
        }

        fun unary(): Double = when (peek()) {
            '-' -> { pos++; -unary() }
            '+' -> { pos++; unary() }
            else -> atom()
        }

        fun atom(): Double {
            if (peek() == '(') {
                pos++
                val v = expression()
                require(peek() == ')') { "parenthèse manquante" }
                pos++
                return v
            }
            val start = pos
            while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
            require(pos > start) { if (done()) "expression incomplète" else "caractère inattendu « ${rest()} »" }
            return s.substring(start, pos).toDoubleOrNull() ?: error("nombre invalide")
        }
    }
}
