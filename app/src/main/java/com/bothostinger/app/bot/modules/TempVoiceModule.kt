package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.BotRuntime
import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Opt
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.categoryOpt
import com.bothostinger.app.bot.engine.channelMention
import com.bothostinger.app.bot.engine.displayName
import com.bothostinger.app.bot.engine.intOpt
import com.bothostinger.app.bot.engine.stringOpt
import com.bothostinger.app.bot.engine.sub
import com.bothostinger.app.data.obj
import com.bothostinger.app.data.objects
import com.bothostinger.app.data.str
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * « Rejoindre pour créer » : rejoindre le salon vocal central crée un salon vocal
 * perso, supprimé automatiquement quand il se vide.
 */
class TempVoiceModule : Module(
    id = "vocal",
    title = "Vocaux temporaires",
    description = "Rejoindre un salon vocal crée un salon perso, supprimé quand il se vide.",
) {
    override val setup = "/vocal configurer avec le salon vocal « Rejoindre pour créer ». " +
        "Le bot a besoin de Gérer les salons et Déplacer des membres."
    override val needsVoice = true

    /** Occupants de chaque salon vocal : salon → membres. */
    private val occupants = ConcurrentHashMap<String, MutableSet<String>>()
    /** Salon vocal actuel de chaque membre : « serveur:membre » → salon. */
    private val where = ConcurrentHashMap<String, String>()

    override val commands = listOf(
        Command(
            "vocal", "Salons vocaux temporaires",
            options = listOf(
                sub(
                    "configurer", "Choisir le salon « Rejoindre pour créer » (staff)",
                    Opt(Opt.CHANNEL, "salon", "Salon vocal central", channelTypes = listOf(2)),
                    categoryOpt("categorie", "Catégorie des salons créés"),
                ),
                sub("desactiver", "Désactiver (staff)"),
                sub("renommer", "Renommer ton salon", stringOpt("nom", "Nouveau nom", maxLength = 90)),
                sub("limite", "Nombre maximum de personnes dans ton salon", intOpt("places", "0 = illimité", min = 0, max = 99)),
                sub("verrouiller", "Plus personne ne peut rejoindre ton salon"),
                sub("deverrouiller", "Tout le monde peut de nouveau rejoindre"),
            ),
        ) { i ->
            val gid = i.guildId!!
            when (i.subcommand) {
                "configurer", "desactiver" -> {
                    if (!i.hasPermission(Perm.MANAGE_CHANNELS)) return@Command i.error("Réservé au staff (Gérer les salons).")
                    if (i.subcommand == "desactiver") {
                        db.edit(gid) { g -> g.obj("config").remove("tempvoice") }
                        return@Command i.success("Vocaux temporaires désactivés.")
                    }
                    val hub = i.snowflake("salon") ?: return@Command
                    val cfg = JSONObject().put("hub", hub)
                    i.snowflake("categorie")?.let { cfg.put("category", it) }
                    db.edit(gid) { g -> g.obj("config").put("tempvoice", cfg) }
                    i.success("Rejoindre ${channelMention(hub)} créera un salon vocal perso.")
                }
                else -> {
                    val mine = db.read(gid) { g ->
                        g.optJSONObject("tempvoice")?.let { t -> t.keys().asSequence().firstOrNull { t.optJSONObject(it)?.optString("owner") == i.userId } }
                    } ?: return@Command i.error("Tu n'as pas de salon vocal temporaire. Rejoins le salon « Rejoindre pour créer ».")
                    when (i.subcommand) {
                        "renommer" -> rest.modifyChannel(mine, JSONObject().put("name", i.string("nom").orEmpty().ifBlank { "Salon" }))
                        "limite" -> rest.modifyChannel(mine, JSONObject().put("user_limit", i.long("places") ?: 0))
                        "verrouiller" -> rest.editPermission(mine, gid, 0, 0, CONNECT)
                        "deverrouiller" -> rest.editPermission(mine, gid, 0, 0, 0)
                    }
                    i.success("C'est fait pour ${channelMention(mine)}.", ephemeral = true)
                }
            }
        },
    )

    override fun onEvent(type: String, d: JSONObject) {
        when (type) {
            // État initial : qui est déjà en vocal au démarrage du bot.
            "GUILD_CREATE" -> d.optJSONArray("voice_states")?.objects()?.forEach { vs ->
                val channel = vs.str("channel_id") ?: return@forEach
                track(d.getString("id"), vs.getString("user_id"), channel)
            }
            "VOICE_STATE_UPDATE" -> onVoice(d)
        }
    }

    private fun onVoice(d: JSONObject) {
        val gid = d.str("guild_id") ?: return
        val uid = d.optString("user_id")
        val newChannel = d.str("channel_id")
        val old = track(gid, uid, newChannel)

        val hub = db.read(gid) { g -> g.optJSONObject("config")?.optJSONObject("tempvoice")?.let { JSONObject(it.toString()) } }
        // Salon temporaire quitté et vide → suppression.
        if (old != null && old != newChannel && occupants[old].isNullOrEmpty()) {
            val isTemp = db.read(gid) { g -> g.optJSONObject("tempvoice")?.has(old) == true }
            if (isTemp) {
                runCatching { rest.deleteChannel(old, "Salon vocal temporaire vide") }
                db.edit(gid) { g -> g.optJSONObject("tempvoice")?.remove(old) }
            }
        }
        if (hub == null || newChannel != hub.optString("hub")) return

        val member = d.optJSONObject("member")?.optJSONObject("user")
        val name = "Salon de ${displayName(member)}".take(90)
        val overwrites = JSONArray().put(
            JSONObject().put("id", uid).put("type", 1)
                .put("allow", (CONNECT or Perm.MANAGE_CHANNELS or MOVE_MEMBERS).toString()).put("deny", "0")
        )
        val payload = JSONObject().put("name", name).put("type", 2).put("permission_overwrites", overwrites)
        hub.str("category")?.let { payload.put("parent_id", it) }
        runCatching {
            val created = rest.createChannel(gid, payload, "Salon vocal temporaire de ${displayName(member)}").getString("id")
            db.edit(gid) { g -> g.obj("tempvoice").put(created, JSONObject().put("owner", uid).put("at", System.currentTimeMillis())) }
            rest.moveMember(gid, uid, created, "Salon vocal temporaire")
        }.onFailure { BotRuntime.log("Vocal temporaire impossible : ${it.message}", isError = true) }
    }

    /** Met à jour les occupants ; renvoie l'ancien salon du membre. */
    private fun track(gid: String, uid: String, channel: String?): String? {
        val key = "$gid:$uid"
        val old = if (channel == null) where.remove(key) else where.put(key, channel)
        if (old != null) occupants[old]?.remove(uid)
        if (channel != null) occupants.getOrPut(channel) { ConcurrentHashMap.newKeySet() }.add(uid)
        return old
    }

    override fun tick(now: Long) {
        // Filet de sécurité : supprime les salons temporaires restés vides (ex. bot redémarré).
        db.guildIds().forEach { gid ->
            // Un salon tout juste créé est vide le temps que le membre y soit déplacé : on attend 30 s.
            val temps = db.read(gid) { g ->
                g.optJSONObject("tempvoice")?.let { t ->
                    t.keys().asSequence().filter { now - (t.optJSONObject(it)?.optLong("at") ?: 0L) > 30_000 }.toList()
                }.orEmpty()
            }
            temps.filter { occupants[it].isNullOrEmpty() && ctx.guilds[gid] != null }.forEach { channel ->
                if (ctx.guilds[gid]?.channels?.containsKey(channel) == false) {
                    db.edit(gid) { g -> g.optJSONObject("tempvoice")?.remove(channel) }
                } else if (now - ctx.startedAt > 60_000) {
                    runCatching { rest.deleteChannel(channel, "Salon vocal temporaire vide") }
                    db.edit(gid) { g -> g.optJSONObject("tempvoice")?.remove(channel) }
                }
            }
        }
    }

    companion object {
        private const val CONNECT = 1L shl 20
        private const val MOVE_MEMBERS = 1L shl 24
    }
}
