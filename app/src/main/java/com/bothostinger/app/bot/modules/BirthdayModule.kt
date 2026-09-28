package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.channelMention
import com.bothostinger.app.bot.engine.intOpt
import com.bothostinger.app.bot.engine.mention
import com.bothostinger.app.bot.engine.message
import com.bothostinger.app.bot.engine.roleMention
import com.bothostinger.app.bot.engine.roleOpt
import com.bothostinger.app.bot.engine.sub
import com.bothostinger.app.bot.engine.textChannelOpt
import com.bothostinger.app.bot.engine.userOpt
import com.bothostinger.app.data.obj
import com.bothostinger.app.data.str
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.Clock
import java.time.temporal.ChronoUnit

/** Anniversaires des membres : annonce le jour J (à partir de 9 h) et rôle du jour. */
class BirthdayModule(private val clock: Clock = Clock.systemDefaultZone()) : Module(
    id = "anniversaires",
    title = "Anniversaires",
    description = "Les membres enregistrent leur anniversaire, le bot le souhaite le jour J avec un rôle spécial.",
) {
    override val setup = "/anniversaire salon pour choisir où souhaiter les anniversaires (et un rôle du jour en option). " +
        "Les membres utilisent /anniversaire definir. Annonce à 9 h (heure du téléphone)."

    private val months = listOf(
        "janvier", "février", "mars", "avril", "mai", "juin",
        "juillet", "août", "septembre", "octobre", "novembre", "décembre",
    )

    override val commands = listOf(
        Command(
            "anniversaire", "Anniversaires des membres",
            options = listOf(
                sub("definir", "Enregistrer ton anniversaire", intOpt("jour", "Jour", min = 1, max = 31), intOpt("mois", "Mois (1-12)", min = 1, max = 12)),
                sub("oublier", "Supprimer ton anniversaire"),
                sub("voir", "Voir l'anniversaire d'un membre", userOpt("membre", "Le membre", required = false)),
                sub("prochains", "Les prochains anniversaires"),
                sub("salon", "Salon des annonces (staff)", textChannelOpt("salon", "Le salon"), roleOpt("role", "Rôle donné le jour J", required = false)),
            ),
        ) { i ->
            val gid = i.guildId!!
            when (i.subcommand) {
                "definir" -> {
                    val day = i.long("jour")!!.toInt()
                    val month = i.long("mois")!!.toInt()
                    if (runCatching { MonthDay.of(month, day) }.isFailure) return@Command i.error("Cette date n'existe pas.")
                    db.edit(gid) { g -> g.obj("birthdays").put(i.userId, JSONObject().put("day", day).put("month", month)) }
                    i.success("Anniversaire enregistré : **$day ${months[month - 1]}** 🎂", ephemeral = true)
                }
                "oublier" -> {
                    db.edit(gid) { g -> g.obj("birthdays").remove(i.userId) }
                    i.success("Anniversaire supprimé.", ephemeral = true)
                }
                "voir" -> {
                    val uid = i.snowflake("membre") ?: i.userId
                    val b = db.read(gid) { g -> g.optJSONObject("birthdays")?.optJSONObject(uid)?.let { JSONObject(it.toString()) } }
                    if (b == null) return@Command i.reply(Embeds.base("🎂 Anniversaire", "${mention(uid)} n'a pas enregistré son anniversaire."))
                    val next = nextDate(b.getInt("month"), b.getInt("day"))
                    val inDays = ChronoUnit.DAYS.between(today(), next)
                    i.reply(
                        Embeds.base(
                            "🎂 Anniversaire",
                            "${mention(uid)} : **${b.getInt("day")} ${months[b.getInt("month") - 1]}**" +
                                if (inDays == 0L) "\nC'est aujourd'hui ! 🎉" else "\nDans **$inDays** jour(s).",
                        )
                    )
                }
                "prochains" -> {
                    val list = db.read(gid) { g ->
                        val all = g.optJSONObject("birthdays") ?: JSONObject()
                        all.keys().asSequence().mapNotNull { uid ->
                            all.optJSONObject(uid)?.let { b -> runCatching { uid to nextDate(b.getInt("month"), b.getInt("day")) }.getOrNull() }
                        }.sortedBy { it.second }.take(10).toList()
                    }
                    if (list.isEmpty()) return@Command i.reply(Embeds.base("🎂 Prochains anniversaires", "Personne n'a encore enregistré son anniversaire. `/anniversaire definir`"))
                    val text = list.joinToString("\n") { (uid, date) -> "**${date.dayOfMonth} ${months[date.monthValue - 1]}** — ${mention(uid)}" }
                    i.reply(Embeds.base("🎂 Prochains anniversaires", text))
                }
                "salon" -> {
                    if (!i.hasPermission(Perm.MANAGE_GUILD)) return@Command i.error("Réservé au staff (Gérer le serveur).")
                    val channel = i.snowflake("salon") ?: return@Command
                    val cfg = JSONObject().put("channel", channel)
                    i.snowflake("role")?.let { cfg.put("role", it) }
                    db.edit(gid) { g -> g.obj("config").put("birthdays", cfg) }
                    i.success("Anniversaires souhaités dans ${channelMention(channel)}" + (i.snowflake("role")?.let { " avec le rôle ${roleMention(it)}" } ?: "") + ".")
                }
            }
        },
    )

    private fun today(): LocalDate = LocalDate.now(clock)

    /** Prochaine occurrence (le 29 février devient le 28 les années non bissextiles). */
    private fun nextDate(month: Int, day: Int): LocalDate {
        val t = today()
        fun at(year: Int): LocalDate = MonthDay.of(month, day).atYear(year)
        val thisYear = at(t.year)
        return if (thisYear.isBefore(t)) at(t.year + 1) else thisYear
    }

    override fun tick(now: Long) {
        val t = today()
        if (LocalTime.now(clock).hour < 9) return
        val key = t.toString()
        db.guildIds().forEach { gid ->
            val cfg = db.read(gid) { g -> g.optJSONObject("config")?.optJSONObject("birthdays")?.let { JSONObject(it.toString()) } } ?: return@forEach
            val channel = cfg.str("channel") ?: return@forEach
            val role = cfg.str("role")

            // Retire le rôle du jour aux anniversaires passés.
            val expired = db.edit(gid) { g ->
                val given = g.obj("birthdayRoles")
                given.keys().asSequence().toList().filter { given.optString(it) != key }.onEach { given.remove(it) }
            }
            if (role != null) expired.forEach { uid -> runCatching { rest.removeRole(gid, uid, role, "Fin de l'anniversaire") } }

            // Les anniversaires du jour pas encore souhaités.
            val todays = db.edit(gid) { g ->
                val all = g.optJSONObject("birthdays") ?: return@edit emptyList()
                all.keys().asSequence().toList().filter { uid ->
                    val b = all.optJSONObject(uid) ?: return@filter false
                    val date = runCatching { MonthDay.of(b.getInt("month"), b.getInt("day")).atYear(t.year) }.getOrNull()
                    date == t && b.optString("wished") != key
                }.onEach { uid -> all.getJSONObject(uid).put("wished", key) }
            }
            todays.forEach { uid ->
                runCatching {
                    rest.createMessage(
                        channel,
                        message(mention(uid), Embeds.base("🎂 Joyeux anniversaire !", "Tout le serveur souhaite un très joyeux anniversaire à ${mention(uid)} ! 🎉")),
                    )
                }
                if (role != null) {
                    runCatching { rest.addRole(gid, uid, role, "Anniversaire") }
                    db.edit(gid) { g -> g.obj("birthdayRoles").put(uid, key) }
                }
            }
        }
    }
}
