package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Interaction
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.avatarUrl
import com.bothostinger.app.bot.engine.displayName
import com.bothostinger.app.bot.engine.field
import com.bothostinger.app.bot.engine.fr
import com.bothostinger.app.bot.engine.intOpt
import com.bothostinger.app.bot.engine.mention
import com.bothostinger.app.bot.engine.sub
import com.bothostinger.app.bot.engine.thumbnail
import com.bothostinger.app.bot.engine.ts
import com.bothostinger.app.bot.engine.userOpt
import com.bothostinger.app.data.entries
import com.bothostinger.app.data.obj
import org.json.JSONObject
import kotlin.random.Random

class EconomyModule : Module(
    id = "economie",
    title = "Économie",
    description = "Monnaie du serveur : récompense quotidienne, travail, paris, virements et classement.",
) {
    override val setup = "Aucune configuration : les membres utilisent /daily et /travail pour gagner des pièces. " +
        "Le staff peut en donner ou en retirer avec /eco-admin."

    private val jobs = listOf(
        "Tu as livré des pizzas", "Tu as codé un bot Discord", "Tu as réparé un ordinateur", "Tu as fait du baby-sitting",
        "Tu as vendu des cookies", "Tu as streamé 4 heures", "Tu as lavé des voitures", "Tu as promené des chiens",
        "Tu as monté un PC gamer", "Tu as tondu la pelouse du voisin", "Tu as donné un cours de maths", "Tu as fait le DJ à une soirée",
    )

    override val commands = listOf(
        Command("solde", "Ton argent (ou celui d'un membre)", options = listOf(userOpt("membre", "Le membre", required = false))) { balance(it) },
        Command("daily", "Récompense quotidienne") { daily(it) },
        Command("travail", "Travailler pour gagner des pièces (toutes les heures)") { work(it) },
        Command("payer", "Donner des pièces à un membre", options = listOf(userOpt("membre", "Le membre"), intOpt("montant", "Montant", min = 1))) {
            pay(it)
        },
        Command("parier", "Pile ou face : double ou perds ta mise", options = listOf(intOpt("montant", "Mise", min = 10))) { bet(it) },
        Command("richesse", "Top 10 des plus riches") { top(it) },
        Command(
            "eco-admin", "Gérer l'argent des membres", Perm.MANAGE_GUILD,
            listOf(
                sub("donner", "Donner des pièces", userOpt("membre", "Le membre"), intOpt("montant", "Montant", min = 1)),
                sub("retirer", "Retirer des pièces", userOpt("membre", "Le membre"), intOpt("montant", "Montant", min = 1)),
                sub("reset", "Remettre un membre à zéro", userOpt("membre", "Le membre")),
            ),
        ) { admin(it) },
    )

    private fun account(g: JSONObject, uid: String, user: JSONObject?): JSONObject =
        g.obj("eco").obj(uid).also { if (user != null) it.put("name", displayName(user)) }

    private fun balance(i: Interaction) {
        val user = i.user("membre") ?: i.user
        val bal = db.read(i.guildId!!) { g -> g.optJSONObject("eco")?.optJSONObject(user.getString("id"))?.optLong("bal") ?: 0L }
        i.reply(Embeds.base("💰 Solde de ${displayName(user)}", "**${bal.fr()}** $COIN").thumbnail(avatarUrl(user)))
    }

    private fun daily(i: Interaction) {
        val now = System.currentTimeMillis()
        val result = db.edit(i.guildId!!) { g ->
            val a = account(g, i.userId, i.user)
            val last = a.optLong("daily")
            if (now - last < DAY) return@edit Result.failure<Pair<Long, Int>>(Cooldown(last + DAY))
            val streak = if (now - last < 2 * DAY) a.optInt("streak") + 1 else 1
            val gain = 200L + minOf(streak, 10) * 30L
            a.put("daily", now).put("streak", streak).put("bal", a.optLong("bal") + gain)
            Result.success(gain to streak)
        }
        result.onSuccess { (gain, streak) ->
            i.reply(Embeds.base("🎁 Récompense quotidienne", "Tu gagnes **${gain.fr()}** $COIN !\nSérie : **$streak** jour(s) 🔥"))
        }.onFailure { e ->
            i.error("Tu as déjà récupéré ta récompense. Reviens ${ts((e as Cooldown).until)}.")
        }
    }

    private fun work(i: Interaction) {
        val now = System.currentTimeMillis()
        val result = db.edit(i.guildId!!) { g ->
            val a = account(g, i.userId, i.user)
            val last = a.optLong("work")
            if (now - last < HOUR) return@edit Result.failure<Long>(Cooldown(last + HOUR))
            val gain = Random.nextLong(80, 201)
            a.put("work", now).put("bal", a.optLong("bal") + gain)
            Result.success(gain)
        }
        result.onSuccess { gain ->
            i.reply(Embeds.base("💼 Travail", "${jobs.random()} et tu gagnes **${gain.fr()}** $COIN."))
        }.onFailure { e ->
            i.error("Tu es fatigué ! Tu pourras retravailler ${ts((e as Cooldown).until)}.")
        }
    }

    private fun pay(i: Interaction) {
        val target = i.user("membre") ?: return i.error("Membre introuvable.")
        val amount = i.long("montant") ?: return
        val tid = target.getString("id")
        if (tid == i.userId) return i.error("Tu ne peux pas te payer toi-même.")
        if (target.optBoolean("bot")) return i.error("Les bots n'ont pas besoin d'argent 🤖")
        val ok = db.edit(i.guildId!!) { g ->
            val from = account(g, i.userId, i.user)
            if (from.optLong("bal") < amount) return@edit false
            val to = account(g, tid, target)
            from.put("bal", from.optLong("bal") - amount)
            to.put("bal", to.optLong("bal") + amount)
            true
        }
        if (!ok) return i.error("Tu n'as pas assez de pièces.")
        i.reply(Embeds.base("💸 Virement", "${mention(i.userId)} a envoyé **${amount.fr()}** $COIN à ${mention(tid)}."))
    }

    private fun bet(i: Interaction) {
        val amount = i.long("montant") ?: return
        val win = Random.nextInt(100) < 48
        val newBal = db.edit(i.guildId!!) { g ->
            val a = account(g, i.userId, i.user)
            val bal = a.optLong("bal")
            if (bal < amount) return@edit null
            (if (win) bal + amount else bal - amount).also { a.put("bal", it) }
        } ?: return i.error("Tu n'as pas assez de pièces pour miser ça.")
        val embed = if (win) {
            Embeds.base("🪙 Pile ou face", "C'est gagné ! Tu remportes **${amount.fr()}** $COIN 🎉", Embeds.GREEN)
        } else {
            Embeds.base("🪙 Pile ou face", "Perdu… tu perds **${amount.fr()}** $COIN 😢", Embeds.RED)
        }
        i.reply(embed.field("Nouveau solde", "${newBal.fr()} $COIN"))
    }

    private fun top(i: Interaction) {
        val list = db.read(i.guildId!!) { g ->
            (g.optJSONObject("eco") ?: JSONObject()).entries()
                .map { it.first to it.second.optLong("bal") }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(10)
        }
        if (list.isEmpty()) return i.reply(Embeds.base("🏦 Les plus riches", "Personne n'a encore de pièces. Tape `/daily` !"))
        val medals = listOf("🥇", "🥈", "🥉")
        val text = list.mapIndexed { n, (uid, bal) -> "${medals.getOrElse(n) { "**${n + 1}.**" }} ${mention(uid)} — **${bal.fr()}** $COIN" }
        i.reply(Embeds.base("🏦 Les plus riches de ${ctx.guildName(i.guildId)}", text.joinToString("\n")))
    }

    private fun admin(i: Interaction) {
        val target = i.user("membre") ?: return i.error("Membre introuvable.")
        val tid = target.getString("id")
        val amount = i.long("montant") ?: 0L
        val newBal = db.edit(i.guildId!!) { g ->
            val a = account(g, tid, target)
            val bal = when (i.subcommand) {
                "donner" -> a.optLong("bal") + amount
                "retirer" -> maxOf(0L, a.optLong("bal") - amount)
                else -> 0L
            }
            a.put("bal", bal)
            bal
        }
        i.success("Nouveau solde de ${mention(tid)} : **${newBal.fr()}** $COIN")
    }

    private class Cooldown(val until: Long) : Exception()

    companion object {
        const val COIN = "🪙"
        private const val HOUR = 3_600_000L
        private const val DAY = 86_400_000L
    }
}
