package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.channelMention
import com.bothostinger.app.bot.engine.field
import com.bothostinger.app.bot.engine.mention
import com.bothostinger.app.bot.engine.message
import com.bothostinger.app.bot.engine.sub
import com.bothostinger.app.bot.engine.textChannelOpt
import com.bothostinger.app.data.obj
import com.bothostinger.app.data.str
import org.json.JSONObject

/** Jeu de comptage : chacun son tour, 1, 2, 3… Une erreur et on repart de zéro. */
class CountingModule : Module(
    id = "comptage",
    title = "Comptage",
    description = "Salon où les membres comptent ensemble, chacun son tour. Une erreur remet tout à zéro.",
) {
    override val setup = "/comptage salon pour choisir le salon. Nécessite « MESSAGE CONTENT INTENT »."
    override val needsMessageContent = true

    override val commands = listOf(
        Command(
            "comptage", "Jeu de comptage",
            options = listOf(
                sub("salon", "Choisir le salon de comptage (staff)", textChannelOpt("salon", "Le salon")),
                sub("statut", "Nombre actuel et record"),
            ),
        ) { i ->
            val gid = i.guildId!!
            when (i.subcommand) {
                "salon" -> {
                    if (!i.hasPermission(Perm.MANAGE_GUILD)) return@Command i.error("Réservé au staff (Gérer le serveur).")
                    val channel = i.snowflake("salon") ?: return@Command
                    db.edit(gid) { g -> g.put("counting", JSONObject().put("channel", channel).put("current", 0).put("record", 0)) }
                    rest.createMessage(channel, message(embed = Embeds.base("🔢 Jeu de comptage", "Comptez chacun votre tour à partir de **1** ! Pas deux fois de suite.")))
                    i.success("Salon de comptage : ${channelMention(channel)}.")
                }
                else -> {
                    val c = db.read(gid) { g -> g.optJSONObject("counting")?.let { JSONObject(it.toString()) } }
                        ?: return@Command i.error("Pas de salon de comptage. Un admin doit utiliser `/comptage salon`.")
                    i.reply(
                        Embeds.base("🔢 Comptage")
                            .field("Nombre actuel", c.optInt("current").toString(), inline = true)
                            .field("Record", c.optInt("record").toString(), inline = true)
                            .field("Salon", channelMention(c.optString("channel")), inline = true)
                    )
                }
            }
        },
    )

    override fun onEvent(type: String, d: JSONObject) {
        if (type != "MESSAGE_CREATE") return
        val gid = d.str("guild_id") ?: return
        val author = d.optJSONObject("author") ?: return
        if (author.optBoolean("bot")) return
        val channel = d.getString("channel_id")
        val number = d.optString("content").trim().split(' ').first().toIntOrNull() ?: return
        val uid = author.getString("id")

        // Résultat calculé dans la transaction pour éviter deux « 5 » acceptés en même temps.
        val result = db.edit(gid) { g ->
            val c = g.optJSONObject("counting") ?: return@edit null
            if (c.optString("channel") != channel) return@edit null
            val current = c.optInt("current")
            when {
                c.optString("last") == uid -> {
                    c.put("current", 0).remove("last")
                    Outcome.Broken(current, "a compté deux fois de suite")
                }
                number != current + 1 -> {
                    c.put("current", 0).remove("last")
                    Outcome.Broken(current, "a écrit $number au lieu de ${current + 1}")
                }
                else -> {
                    c.put("current", number).put("last", uid)
                    val record = number > c.optInt("record")
                    if (record) c.put("record", number)
                    Outcome.Ok(record)
                }
            }
        } ?: return

        val msgId = d.getString("id")
        when (result) {
            is Outcome.Ok -> runCatching { rest.addReaction(channel, msgId, if (result.record) "🏆" else "✅") }
            is Outcome.Broken -> {
                runCatching { rest.addReaction(channel, msgId, "❌") }
                runCatching {
                    rest.createMessage(
                        channel,
                        message(embed = Embeds.base(description = "${mention(uid)} ${result.why} ! La série s'arrête à **${result.reached}**. On repart de **1**.", color = Embeds.RED)),
                    )
                }
            }
        }
    }

    private sealed class Outcome {
        class Ok(val record: Boolean) : Outcome()
        class Broken(val reached: Int, val why: String) : Outcome()
    }
}
