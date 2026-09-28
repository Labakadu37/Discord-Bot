package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Interaction
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.button
import com.bothostinger.app.bot.engine.displayName
import com.bothostinger.app.bot.engine.field
import com.bothostinger.app.bot.engine.fr
import com.bothostinger.app.bot.engine.intOpt
import com.bothostinger.app.bot.engine.mention
import com.bothostinger.app.bot.engine.roleMention
import com.bothostinger.app.bot.engine.roleOpt
import com.bothostinger.app.bot.engine.row
import com.bothostinger.app.bot.engine.stringOpt
import com.bothostinger.app.bot.engine.sub
import com.bothostinger.app.bot.engine.ts
import com.bothostinger.app.bot.engine.userOpt
import com.bothostinger.app.bot.modules.EconomyModule.Companion.COIN
import com.bothostinger.app.data.arr
import com.bothostinger.app.data.obj
import com.bothostinger.app.data.objects
import com.bothostinger.app.data.str
import com.bothostinger.app.data.strings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Jeux d'argent et boutique du serveur (utilise les pièces du système Économie). */
class CasinoModule(private val random: Random = Random.Default) : Module(
    id = "casino",
    title = "Casino & Boutique",
    description = "Machine à sous, blackjack, braquages et boutique de rôles avec les pièces de l'économie.",
) {
    override val setup = "La boutique se remplit avec /boutique ajouter (un article peut donner un rôle). " +
        "Les jeux utilisent les pièces gagnées avec /daily et /travail."

    // ---------------------------------------------------------------- blackjack

    private class Card(val rank: Int, val suit: Char) {
        val value: Int get() = when (rank) { 1 -> 11; in 11..13 -> 10; else -> rank }
        override fun toString(): String = (when (rank) { 1 -> "A"; 11 -> "V"; 12 -> "D"; 13 -> "R"; else -> rank.toString() }) + suit
    }

    private class Game(val guildId: String, val userId: String, val bet: Long, val deck: ArrayDeque<Card>) {
        val player = mutableListOf<Card>()
        val dealer = mutableListOf<Card>()
        val createdAt = System.currentTimeMillis()
    }

    private val games = ConcurrentHashMap<String, Game>()

    private fun score(hand: List<Card>): Int {
        var total = hand.sumOf { it.value }
        var aces = hand.count { it.rank == 1 }
        while (total > 21 && aces > 0) {
            total -= 10
            aces--
        }
        return total
    }

    private fun newDeck(): ArrayDeque<Card> =
        ArrayDeque(listOf('♠', '♥', '♦', '♣').flatMap { s -> (1..13).map { Card(it, s) } }.shuffled(random))

    // ---------------------------------------------------------------- commandes

    override val commands = listOf(
        Command("slots", "Machine à sous", options = listOf(intOpt("mise", "Mise", min = 10, max = 100_000))) { slots(it) },
        Command("blackjack", "Blackjack contre le bot", options = listOf(intOpt("mise", "Mise", min = 10, max = 100_000))) { blackjack(it) },
        Command("braquer", "Tenter de voler des pièces à un membre (risqué !)", options = listOf(userOpt("membre", "La victime"))) { rob(it) },
        Command(
            "boutique", "La boutique du serveur",
            options = listOf(
                sub("voir", "Voir les articles"),
                sub("acheter", "Acheter un article", stringOpt("article", "Nom de l'article", maxLength = 100)),
                sub(
                    "ajouter", "Ajouter un article (staff)",
                    stringOpt("nom", "Nom", maxLength = 100), intOpt("prix", "Prix en pièces", min = 1),
                    roleOpt("role", "Rôle donné à l'achat", required = false),
                    stringOpt("description", "Description", required = false, maxLength = 200),
                ),
                sub("retirer", "Retirer un article (staff)", stringOpt("nom", "Nom de l'article", maxLength = 100)),
            ),
        ) { shop(it) },
        Command("inventaire", "Tes achats", options = listOf(userOpt("membre", "Le membre", required = false))) { inventory(it) },
    )

    /** Retire [amount] pièces si possible ; renvoie false sinon. */
    private fun pay(gid: String, i: Interaction, amount: Long): Boolean = db.edit(gid) { g ->
        val a = g.obj("eco").obj(i.userId)
        if (a.optLong("bal") < amount) return@edit false
        a.put("bal", a.optLong("bal") - amount).put("name", displayName(i.user))
        true
    }

    private fun credit(gid: String, uid: String, amount: Long): Long = db.edit(gid) { g ->
        val a = g.obj("eco").obj(uid)
        (a.optLong("bal") + amount).also { a.put("bal", it) }
    }

    private fun balance(gid: String, uid: String): Long = db.read(gid) { g -> g.optJSONObject("eco")?.optJSONObject(uid)?.optLong("bal") ?: 0L }

    private fun slots(i: Interaction) {
        val gid = i.guildId!!
        val bet = i.long("mise") ?: return
        if (!pay(gid, i, bet)) return i.error("Tu n'as pas assez de pièces (solde : ${balance(gid, i.userId).fr()} $COIN).")
        val reels = List(3) { spin() }
        val multiplier = when {
            reels.distinct().size == 1 -> when (reels[0]) { "💎" -> 10.0; "7️⃣" -> 7.0; "⭐" -> 5.0; else -> 3.0 }
            reels.distinct().size == 2 -> 1.5
            else -> 0.0
        }
        val win = (bet * multiplier).toLong()
        val bal = if (win > 0) credit(gid, i.userId, win) else balance(gid, i.userId)
        val result = if (win > 0) "Tu gagnes **${win.fr()}** $COIN ! (×$multiplier)" else "Perdu… tu perds **${bet.fr()}** $COIN."
        i.reply(
            Embeds.base("🎰 Machine à sous", "**[ ${reels.joinToString(" | ")} ]**\n\n$result", if (win > 0) Embeds.GREEN else Embeds.RED)
                .field("Solde", "${bal.fr()} $COIN")
        )
    }

    private fun spin(): String {
        val symbols = listOf("🍒" to 30, "🍋" to 25, "🔔" to 20, "⭐" to 13, "7️⃣" to 8, "💎" to 4)
        var n = random.nextInt(symbols.sumOf { it.second })
        for ((s, w) in symbols) {
            if (n < w) return s
            n -= w
        }
        return "🍒"
    }

    private fun blackjack(i: Interaction) {
        val gid = i.guildId!!
        val bet = i.long("mise") ?: return
        if (!pay(gid, i, bet)) return i.error("Tu n'as pas assez de pièces (solde : ${balance(gid, i.userId).fr()} $COIN).")
        val game = Game(gid, i.userId, bet, newDeck())
        repeat(2) {
            game.player += game.deck.removeFirst()
            game.dealer += game.deck.removeFirst()
        }
        val id = UUID.randomUUID().toString().take(8)
        if (score(game.player) == 21) {
            val win = (bet * 2.5).toLong()
            credit(gid, i.userId, win)
            return i.reply(table(game, reveal = true, "🃏 **Blackjack !** Tu gagnes **${win.fr()}** $COIN.", Embeds.GREEN))
        }
        games[id] = game
        i.reply(table(game, reveal = false, "Tirer une carte ou rester ?"), components = bjButtons(id))
    }

    private fun bjButtons(id: String) = row(button("bj:hit:$id", "Tirer", 1), button("bj:stand:$id", "Rester", 2))

    private fun table(game: Game, reveal: Boolean, status: String, color: Int = Embeds.BLUE): JSONObject {
        val dealer = if (reveal) "${game.dealer.joinToString(" ")}  (${score(game.dealer)})" else "${game.dealer[0]} 🂠"
        return Embeds.base("🃏 Blackjack — mise ${game.bet.fr()} $COIN", status, color)
            .field("Toi", "${game.player.joinToString(" ")}  (${score(game.player)})", inline = true)
            .field("Croupier", dealer, inline = true)
    }

    override fun onComponent(i: Interaction): Boolean {
        if (!i.customId.startsWith("bj:")) return false
        val (_, action, id) = i.customId.split(':').let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
        val game = games[id]
        if (game == null) {
            i.error("Cette partie est terminée.")
            return true
        }
        if (game.userId != i.userId) {
            i.error("Ce n'est pas ta partie ! Lance la tienne avec /blackjack.")
            return true
        }
        synchronized(game) {
            if (games[id] == null) return true
            if (action == "hit") {
                game.player += game.deck.removeFirst()
                val s = score(game.player)
                if (s > 21) {
                    games.remove(id)
                    i.updateMessage(table(game, reveal = true, "💥 Tu dépasses 21… perdu (**-${game.bet.fr()}** $COIN).", Embeds.RED), JSONArray())
                } else if (s == 21) {
                    finish(i, id, game)
                } else {
                    i.updateMessage(table(game, reveal = false, "Tirer une carte ou rester ?"), bjButtons(id))
                }
            } else {
                finish(i, id, game)
            }
        }
        return true
    }

    private fun finish(i: Interaction, id: String, game: Game) {
        games.remove(id)
        while (score(game.dealer) < 17) game.dealer += game.deck.removeFirst()
        val p = score(game.player)
        val d = score(game.dealer)
        val (text, payout, color) = when {
            d > 21 || p > d -> Triple("🎉 Gagné ! Tu remportes **${(game.bet * 2).fr()}** $COIN.", game.bet * 2, Embeds.GREEN)
            p == d -> Triple("🤝 Égalité, ta mise t'est rendue.", game.bet, Embeds.BLUE)
            else -> Triple("😢 Le croupier gagne (**-${game.bet.fr()}** $COIN).", 0L, Embeds.RED)
        }
        if (payout > 0) credit(game.guildId, game.userId, payout)
        i.updateMessage(table(game, reveal = true, text, color), JSONArray())
    }

    override fun tick(now: Long) {
        // Parties abandonnées depuis 10 min : la mise est perdue.
        games.entries.removeIf { now - it.value.createdAt > 10 * 60_000 }
    }

    // ---------------------------------------------------------------- braquage

    private fun rob(i: Interaction) {
        val gid = i.guildId!!
        val victim = i.user("membre") ?: return i.error("Membre introuvable.")
        val vid = victim.getString("id")
        if (vid == i.userId) return i.error("Te braquer toi-même ? Original, mais non.")
        if (victim.optBoolean("bot")) return i.error("Les bots gardent leur argent dans le cloud ☁️")
        val now = System.currentTimeMillis()
        val outcome = db.edit(gid) { g ->
            val me = g.obj("eco").obj(i.userId)
            val last = me.optLong("rob")
            if (now - last < ROB_COOLDOWN) return@edit "cooldown:${last + ROB_COOLDOWN}"
            val them = g.obj("eco").obj(vid)
            if (them.optLong("bal") < 200) return@edit "poor"
            if (me.optLong("bal") < 100) return@edit "broke"
            me.put("rob", now).put("name", displayName(i.user))
            if (random.nextInt(100) < 40) {
                val stolen = minOf(5_000L, (them.optLong("bal") * random.nextInt(10, 31) / 100))
                them.put("bal", them.optLong("bal") - stolen)
                me.put("bal", me.optLong("bal") + stolen)
                "win:$stolen"
            } else {
                val fine = minOf(me.optLong("bal"), random.nextLong(100, 401))
                me.put("bal", me.optLong("bal") - fine)
                "fail:$fine"
            }
        }
        when {
            outcome.startsWith("cooldown:") -> i.error("La police te surveille… réessaie ${ts(outcome.substringAfter(':').toLong())}.")
            outcome == "poor" -> i.error("${displayName(victim)} n'a presque rien, ça ne vaut pas le coup.")
            outcome == "broke" -> i.error("Il te faut au moins 100 $COIN pour préparer un braquage.")
            outcome.startsWith("win:") -> i.reply(
                Embeds.base("🦹 Braquage réussi !", "${mention(i.userId)} a volé **${outcome.substringAfter(':').toLong().fr()}** $COIN à ${mention(vid)} !", Embeds.GREEN)
            )
            else -> i.reply(
                Embeds.base("🚓 Braquage raté !", "${mention(i.userId)} s'est fait attraper et paie une amende de **${outcome.substringAfter(':').toLong().fr()}** $COIN.", Embeds.RED)
            )
        }
    }

    // ---------------------------------------------------------------- boutique

    private fun items(g: JSONObject): JSONArray = g.obj("config").obj("shop").arr("items")

    private fun shop(i: Interaction) {
        val gid = i.guildId!!
        when (i.subcommand) {
            "voir" -> {
                val list = db.read(gid) { g -> items(g).objects().map { JSONObject(it.toString()) } }
                if (list.isEmpty()) return i.reply(Embeds.base("🛒 Boutique", "La boutique est vide. Le staff peut ajouter des articles avec `/boutique ajouter`."))
                val embed = Embeds.base("🛒 Boutique de ${ctx.guildName(gid)}", "Achète avec `/boutique acheter`.")
                list.take(25).forEach { item ->
                    embed.field(
                        "${item.optString("name")} — ${item.optLong("price").fr()} $COIN",
                        listOfNotNull(item.str("description"), item.str("role")?.let { "Donne le rôle ${roleMention(it)}" }).joinToString("\n").ifBlank { "—" },
                    )
                }
                i.reply(embed)
            }
            "acheter" -> {
                val name = i.string("article").orEmpty().trim()
                val item = db.read(gid) { g -> items(g).objects().firstOrNull { it.optString("name").equals(name, true) }?.let { JSONObject(it.toString()) } }
                    ?: return i.error("Article « $name » introuvable. Regarde `/boutique voir`.")
                val role = item.str("role")
                if (role != null && role in i.memberRoles) return i.error("Tu as déjà ce rôle.")
                val price = item.optLong("price")
                if (!pay(gid, i, price)) return i.error("Il te faut **${price.fr()}** $COIN (tu as ${balance(gid, i.userId).fr()}).")
                if (role != null) {
                    runCatching { rest.addRole(gid, i.userId, role, "Achat en boutique") }.onFailure {
                        credit(gid, i.userId, price)
                        return i.error("Je n'arrive pas à donner ce rôle (mon rôle doit être au-dessus). Tu as été remboursé.")
                    }
                }
                db.edit(gid) { g -> g.obj("inventory").arr(i.userId).put(item.optString("name")) }
                i.reply(Embeds.base("🛍️ Achat réussi", "Tu as acheté **${item.optString("name")}** pour ${price.fr()} $COIN." + (role?.let { "\nRôle obtenu : ${roleMention(it)}" } ?: ""), Embeds.GREEN))
            }
            "ajouter", "retirer" -> {
                if (!i.hasPermission(Perm.MANAGE_GUILD)) return i.error("Réservé au staff (Gérer le serveur).")
                val name = i.string("nom").orEmpty().trim()
                if (i.subcommand == "retirer") {
                    val removed = db.edit(gid) { g ->
                        val list = items(g).objects()
                        val kept = list.filterNot { it.optString("name").equals(name, true) }
                        g.obj("config").obj("shop").put("items", JSONArray(kept))
                        list.size != kept.size
                    }
                    return if (removed) i.success("Article « $name » retiré.") else i.error("Article introuvable.")
                }
                val count = db.edit(gid) { g ->
                    val list = items(g).objects().filterNot { it.optString("name").equals(name, true) } + JSONObject()
                        .put("name", name).put("price", i.long("prix") ?: 0)
                        .apply { i.snowflake("role")?.let { put("role", it) }; i.string("description")?.let { put("description", it) } }
                    g.obj("config").obj("shop").put("items", JSONArray(list))
                    list.size
                }
                i.success("Article « $name » en vente ($count article(s) dans la boutique).")
            }
        }
    }

    private fun inventory(i: Interaction) {
        val user = i.user("membre") ?: i.user
        val owned = db.read(i.guildId!!) { g -> g.optJSONObject("inventory")?.optJSONArray(user.getString("id"))?.strings().orEmpty() }
        val text = if (owned.isEmpty()) "Aucun achat pour l'instant." else
            owned.groupingBy { it }.eachCount().entries.joinToString("\n") { (n, c) -> "• $n${if (c > 1) " ×$c" else ""}" }
        i.reply(Embeds.base("🎒 Inventaire de ${displayName(user)}", text))
    }

    companion object {
        private const val ROB_COOLDOWN = 2 * 3_600_000L
    }
}
