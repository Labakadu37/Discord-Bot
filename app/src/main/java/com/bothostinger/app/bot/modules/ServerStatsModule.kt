package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.fr
import com.bothostinger.app.bot.engine.sub
import com.bothostinger.app.data.obj
import com.bothostinger.app.data.str
import org.json.JSONArray
import org.json.JSONObject

/** Salons vocaux verrouillés qui affichent le nombre de membres et de boosts, mis à jour tout seuls. */
class ServerStatsModule : Module(
    id = "compteurs",
    title = "Compteurs",
    description = "Salons qui affichent en direct le nombre de membres et de boosts du serveur.",
) {
    override val setup = "/compteurs creer ajoute une catégorie avec les compteurs. Mise à jour toutes les 10 minutes " +
        "(limite imposée par Discord). Le bot a besoin de Gérer les salons."

    override val commands = listOf(
        Command(
            "compteurs", "Compteurs de membres", Perm.MANAGE_CHANNELS,
            listOf(sub("creer", "Créer les salons compteurs"), sub("supprimer", "Supprimer les salons compteurs")),
        ) { i ->
            val gid = i.guildId!!
            i.defer(ephemeral = true)
            val existing = db.read(gid) { g -> g.optJSONObject("config")?.optJSONObject("counters")?.let { JSONObject(it.toString()) } }
            if (i.subcommand == "supprimer") {
                existing?.let { c -> listOf("members", "boosts", "category").mapNotNull { c.str(it) }.forEach { runCatching { rest.deleteChannel(it) } } }
                db.edit(gid) { g -> g.obj("config").remove("counters") }
                return@Command i.reply(Embeds.success("Compteurs supprimés."))
            }
            if (existing != null) return@Command i.reply(Embeds.error("Les compteurs existent déjà. `/compteurs supprimer` d'abord."))

            // Visibles par tous mais impossible de s'y connecter.
            val locked = JSONArray().put(JSONObject().put("id", gid).put("type", 0).put("allow", "0").put("deny", CONNECT.toString()))
            val category = rest.createChannel(gid, JSONObject().put("name", "📊 Statistiques").put("type", 4).put("position", 0)).getString("id")
            val members = rest.createChannel(
                gid, JSONObject().put("name", label("members", gid)).put("type", 2).put("parent_id", category).put("permission_overwrites", locked),
            ).getString("id")
            val boosts = rest.createChannel(
                gid, JSONObject().put("name", label("boosts", gid)).put("type", 2).put("parent_id", category).put("permission_overwrites", locked),
            ).getString("id")
            db.edit(gid) { g ->
                g.obj("config").put("counters", JSONObject().put("category", category).put("members", members).put("boosts", boosts).put("updatedAt", System.currentTimeMillis()))
            }
            i.reply(Embeds.success("Compteurs créés en haut du serveur."))
        },
    )

    private fun label(kind: String, gid: String): String {
        val g = ctx.guilds[gid]
        return when (kind) {
            "members" -> "👥 Membres : ${(g?.memberCount ?: 0).fr()}"
            else -> "🚀 Boosts : ${g?.boosts ?: 0}"
        }
    }

    override fun tick(now: Long) {
        db.guildIds().forEach { gid ->
            val c = db.read(gid) { g -> g.optJSONObject("config")?.optJSONObject("counters")?.let { JSONObject(it.toString()) } } ?: return@forEach
            // Discord limite le renommage d'un salon à 2 fois par 10 minutes.
            if (now - c.optLong("updatedAt") < 10 * 60_000L) return@forEach
            listOf("members", "boosts").forEach { kind ->
                val channel = c.str(kind) ?: return@forEach
                val wanted = label(kind, gid)
                if (ctx.guilds[gid]?.channels?.get(channel)?.name != wanted) {
                    runCatching { rest.modifyChannel(channel, JSONObject().put("name", wanted)) }
                }
            }
            db.edit(gid) { g -> g.obj("config").optJSONObject("counters")?.put("updatedAt", now) }
        }
    }

    companion object {
        private const val CONNECT = 1L shl 20
    }
}
