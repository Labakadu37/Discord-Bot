package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Interaction
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.avatarUrl
import com.bothostinger.app.bot.engine.boolOpt
import com.bothostinger.app.bot.engine.channelMention
import com.bothostinger.app.bot.engine.displayName
import com.bothostinger.app.bot.engine.field
import com.bothostinger.app.bot.engine.fr
import com.bothostinger.app.bot.engine.intOpt
import com.bothostinger.app.bot.engine.mention
import com.bothostinger.app.bot.engine.message
import com.bothostinger.app.bot.engine.progressBar
import com.bothostinger.app.bot.engine.roleMention
import com.bothostinger.app.bot.engine.roleOpt
import com.bothostinger.app.bot.engine.sub
import com.bothostinger.app.bot.engine.textChannelOpt
import com.bothostinger.app.bot.engine.thumbnail
import com.bothostinger.app.bot.engine.userOpt
import com.bothostinger.app.data.entries
import com.bothostinger.app.data.obj
import com.bothostinger.app.data.str
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class LevelsModule : Module(
    id = "niveaux",
    title = "Niveaux",
    description = "Les membres gagnent de l'XP en discutant, montent de niveau et débloquent des rôles.",
) {
    override val setup = "Actif dès le démarrage : 15 à 25 XP par message (une fois par minute). " +
        "/niveaux salon pour choisir où annoncer les passages de niveau, /niveaux recompense pour donner un rôle à un niveau."

    override val needsMessages = true

    private val cooldowns = ConcurrentHashMap<String, Long>()

    override val commands = listOf(
        Command("rank", "Ton niveau et ton XP", options = listOf(userOpt("membre", "Voir le niveau d'un autre membre", required = false))) {
            rank(it)
        },
        Command("classement", "Top 10 des membres les plus actifs") { leaderboard(it) },
        Command(
            "niveaux", "Configurer le système de niveaux", Perm.MANAGE_GUILD,
            listOf(
                sub("salon", "Salon des annonces de niveau (vide = salon du message)", textChannelOpt("salon", "Le salon", required = false)),
                sub("annonces", "Activer ou non les annonces de niveau", boolOpt("actif", "Annoncer les passages de niveau")),
                sub("recompense", "Donner un rôle à un niveau", intOpt("niveau", "Niveau", min = 1, max = 500), roleOpt("role", "Rôle à donner")),
                sub("retirer-recompense", "Retirer la récompense d'un niveau", intOpt("niveau", "Niveau", min = 1, max = 500)),
                sub("recompenses", "Voir les récompenses"),
                sub("reset", "Remettre un membre à zéro", userOpt("membre", "Le membre")),
            ),
        ) { config(it) },
    )

    override fun onEvent(type: String, d: JSONObject) {
        if (type != "MESSAGE_CREATE") return
        val gid = d.str("guild_id") ?: return
        val author = d.optJSONObject("author") ?: return
        if (author.optBoolean("bot") || d.has("webhook_id")) return
        val uid = author.getString("id")
        val now = System.currentTimeMillis()
        val key = "$gid:$uid"
        if (now - (cooldowns[key] ?: 0L) < COOLDOWN_MS) return
        cooldowns[key] = now

        val gain = Random.nextInt(15, 26)
        var newLevel: Int? = null
        var reward: String? = null
        var announce = true
        var announceChannel: String? = null
        db.edit(gid) { g ->
            val u = g.obj("levels").obj(uid)
            u.put("name", displayName(author))
            var xp = u.optInt("xp") + gain
            var level = u.optInt("level")
            while (xp >= xpForNext(level)) {
                xp -= xpForNext(level)
                level++
                newLevel = level
            }
            u.put("xp", xp).put("level", level).put("total", u.optLong("total") + gain)
            val cfg = g.obj("config").obj("levels")
            announce = cfg.optBoolean("announce", true)
            announceChannel = cfg.str("channel")
            newLevel?.let { reward = cfg.optJSONObject("rewards")?.str(it.toString()) }
        }
        val level = newLevel ?: return
        reward?.let { role -> runCatching { rest.addRole(gid, uid, role, "Récompense du niveau $level") } }
        if (announce) {
            val text = "${mention(uid)} passe au **niveau $level** ! 🎉" + (reward?.let { "\nRécompense débloquée : ${roleMention(it)}" } ?: "")
            runCatching {
                rest.createMessage(
                    announceChannel ?: d.getString("channel_id"),
                    message(embed = Embeds.base("⬆️ Niveau supérieur !", text).thumbnail(avatarUrl(author))),
                )
            }
        }
    }

    private fun ranking(g: JSONObject): List<Pair<String, JSONObject>> =
        (g.optJSONObject("levels") ?: JSONObject()).entries().sortedByDescending { it.second.optLong("total") }

    private fun rank(i: Interaction) {
        val user = i.user("membre") ?: i.user
        val uid = user.getString("id")
        val (data, position) = db.read(i.guildId!!) { g ->
            val sorted = ranking(g)
            val index = sorted.indexOfFirst { it.first == uid }
            (sorted.getOrNull(index)?.second?.let { JSONObject(it.toString()) }) to index + 1
        }
        if (data == null) {
            i.reply(Embeds.base("📊 ${displayName(user)}", "Pas encore d'XP. Il faut discuter pour en gagner !"))
            return
        }
        val level = data.optInt("level")
        val xp = data.optInt("xp")
        val needed = xpForNext(level)
        i.reply(
            Embeds.base("📊 Niveau de ${displayName(user)}")
                .thumbnail(avatarUrl(user))
                .field("Niveau", "**$level**", inline = true)
                .field("Rang", "#$position", inline = true)
                .field("XP total", data.optLong("total").fr(), inline = true)
                .field("Progression", "${progressBar(xp.toDouble() / needed)}  ${xp.fr()} / ${needed.fr()} XP")
        )
    }

    private fun leaderboard(i: Interaction) {
        val top = db.read(i.guildId!!) { g -> ranking(g).take(10).map { it.first to JSONObject(it.second.toString()) } }
        if (top.isEmpty()) {
            i.reply(Embeds.base("🏆 Classement", "Personne n'a encore d'XP."))
            return
        }
        val medals = listOf("🥇", "🥈", "🥉")
        val text = top.mapIndexed { n, (uid, u) ->
            "${medals.getOrElse(n) { "**${n + 1}.**" }} ${mention(uid)} — niveau **${u.optInt("level")}** · ${u.optLong("total").fr()} XP"
        }.joinToString("\n")
        i.reply(Embeds.base("🏆 Classement de ${ctx.guildName(i.guildId)}", text))
    }

    private fun config(i: Interaction) {
        val gid = i.guildId!!
        when (i.subcommand) {
            "salon" -> {
                val channel = i.snowflake("salon")
                db.edit(gid) { g -> g.obj("config").obj("levels").apply { if (channel == null) remove("channel") else put("channel", channel) } }
                i.success(if (channel == null) "Les niveaux seront annoncés dans le salon du message." else "Annonces de niveau dans ${channelMention(channel)}.")
            }
            "annonces" -> {
                val on = i.bool("actif") ?: true
                db.edit(gid) { g -> g.obj("config").obj("levels").put("announce", on) }
                i.success(if (on) "Annonces de niveau activées." else "Annonces de niveau désactivées.")
            }
            "recompense" -> {
                val level = i.long("niveau") ?: return
                val role = i.snowflake("role") ?: return
                if (i.role("role")?.optBoolean("managed") == true || role == gid) {
                    i.error("Ce rôle ne peut pas être donné par un bot.")
                    return
                }
                db.edit(gid) { g -> g.obj("config").obj("levels").obj("rewards").put(level.toString(), role) }
                i.success("Les membres qui atteignent le niveau **$level** recevront ${roleMention(role)}.")
            }
            "retirer-recompense" -> {
                val level = i.long("niveau") ?: return
                db.edit(gid) { g -> g.obj("config").obj("levels").obj("rewards").remove(level.toString()) }
                i.success("Récompense du niveau **$level** retirée.")
            }
            "recompenses" -> {
                val rewards = db.read(gid) { g ->
                    g.optJSONObject("config")?.optJSONObject("levels")?.optJSONObject("rewards")?.let { r ->
                        r.keys().asSequence().map { it.toInt() to r.getString(it) }.sortedBy { it.first }.toList()
                    }.orEmpty()
                }
                val text = if (rewards.isEmpty()) "Aucune récompense. Ajoute-en avec `/niveaux recompense`." else
                    rewards.joinToString("\n") { (lvl, role) -> "Niveau **$lvl** → ${roleMention(role)}" }
                i.reply(Embeds.base("🎁 Récompenses de niveau", text))
            }
            "reset" -> {
                val uid = i.snowflake("membre") ?: return
                db.edit(gid) { g -> g.obj("levels").remove(uid) }
                i.success("Niveau de ${mention(uid)} remis à zéro.")
            }
        }
    }

    companion object {
        private const val COOLDOWN_MS = 60_000L

        /** XP à gagner pour passer du niveau [level] au suivant. */
        fun xpForNext(level: Int): Int = 5 * level * level + 50 * level + 100
    }
}
