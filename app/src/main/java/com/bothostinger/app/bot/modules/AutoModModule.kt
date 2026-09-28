package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Interaction
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.boolOpt
import com.bothostinger.app.bot.engine.displayName
import com.bothostinger.app.bot.engine.field
import com.bothostinger.app.bot.engine.formatDuration
import com.bothostinger.app.bot.engine.intOpt
import com.bothostinger.app.bot.engine.mention
import com.bothostinger.app.bot.engine.message
import com.bothostinger.app.bot.engine.msToIso
import com.bothostinger.app.bot.engine.roleMention
import com.bothostinger.app.bot.engine.roleOpt
import com.bothostinger.app.bot.engine.stringOpt
import com.bothostinger.app.bot.engine.sub
import com.bothostinger.app.data.arr
import com.bothostinger.app.data.obj
import com.bothostinger.app.data.str
import com.bothostinger.app.data.strings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Modération automatique : spam, invitations, liens, majuscules, mentions de masse
 * et mots interdits. Le staff n'est jamais sanctionné.
 */
class AutoModModule : Module(
    id = "automod",
    title = "AutoMod",
    description = "Supprime le spam, les invitations, les liens, les majuscules, les mentions de masse et les mots interdits.",
) {
    override val setup = "Actif dès le démarrage contre le spam et les mentions de masse. " +
        "/automod filtre pour activer les autres filtres, /automod sanction pour choisir la punition. " +
        "Nécessite « MESSAGE CONTENT INTENT ». Le staff (gestion des messages, admin) est ignoré."

    override val needsMessageContent = true

    enum class Filter(val key: String, val label: String, val defaultOn: Boolean) {
        SPAM("spam", "Spam (messages en rafale ou répétés)", true),
        MENTIONS("mentions", "Mentions de masse", true),
        INVITES("invitations", "Invitations Discord", false),
        LINKS("liens", "Liens", false),
        CAPS("majuscules", "Abus de majuscules", false),
        WORDS("mots", "Mots interdits", true),
    }

    /** Messages récents par membre (horodatage + texte) pour détecter le spam. */
    private val recent = ConcurrentHashMap<String, ArrayDeque<Pair<Long, String>>>()

    override val commands = listOf(
        Command(
            "automod", "Configurer la modération automatique", Perm.MANAGE_GUILD,
            listOf(
                sub("statut", "Voir la configuration"),
                sub(
                    "filtre", "Activer ou désactiver un filtre",
                    stringOpt("filtre", "Le filtre", choices = Filter.entries.map { it.label to it.key }),
                    boolOpt("actif", "Activé ?"),
                ),
                sub("mots-ajouter", "Ajouter des mots interdits", stringOpt("mots", "Mots séparés par des virgules", maxLength = 1000)),
                sub("mots-retirer", "Retirer des mots interdits", stringOpt("mots", "Mots séparés par des virgules", maxLength = 1000)),
                sub(
                    "sanction", "Punition en plus de la suppression du message",
                    stringOpt(
                        "type", "Sanction",
                        choices = listOf("Aucune (juste supprimer)" to "none", "Avertissement" to "warn", "Mute temporaire" to "mute"),
                    ),
                    intOpt("minutes", "Durée du mute en minutes (5 par défaut)", required = false, min = 1, max = 1440),
                ),
                sub("ignorer-role", "Ne pas surveiller un rôle", roleOpt("role", "Le rôle")),
                sub("surveiller-role", "Surveiller de nouveau un rôle", roleOpt("role", "Le rôle")),
            ),
        ) { configure(it) },
    )

    private fun config(g: JSONObject): JSONObject = g.obj("config").obj("automod")

    private fun isOn(cfg: JSONObject?, f: Filter): Boolean =
        cfg?.optJSONObject("filters")?.let { if (it.has(f.key)) it.optBoolean(f.key) else null } ?: f.defaultOn

    private fun configure(i: Interaction) {
        val gid = i.guildId!!
        when (i.subcommand) {
            "statut" -> {
                val cfg = db.read(gid) { g -> JSONObject(config(g).toString()) }
                val embed = Embeds.base("🛡️ AutoMod")
                Filter.entries.forEach { f -> embed.field(f.label, if (isOn(cfg, f)) "Activé" else "Désactivé", inline = true) }
                val words = cfg.optJSONArray("words")?.strings().orEmpty()
                embed.field("Mots interdits (${words.size})", if (words.isEmpty()) "Aucun" else "||${words.joinToString(", ").take(900)}||")
                embed.field("Sanction", sanctionLabel(cfg))
                val ignored = cfg.optJSONArray("ignoredRoles")?.strings().orEmpty()
                if (ignored.isNotEmpty()) embed.field("Rôles ignorés", ignored.joinToString(" ") { roleMention(it) })
                i.reply(embed, ephemeral = true)
            }
            "filtre" -> {
                val key = i.string("filtre") ?: return
                val on = i.bool("actif") ?: true
                db.edit(gid) { g -> config(g).obj("filters").put(key, on) }
                val label = Filter.entries.firstOrNull { it.key == key }?.label ?: key
                i.success("$label : ${if (on) "activé" else "désactivé"}.")
            }
            "mots-ajouter", "mots-retirer" -> {
                val words = i.string("mots").orEmpty().split(',').map { normalize(it.trim()) }.filter { it.isNotEmpty() }
                val total = db.edit(gid) { g ->
                    val current = config(g).arr("words").strings().toMutableSet()
                    if (i.subcommand == "mots-ajouter") current += words else current -= words.toSet()
                    config(g).put("words", JSONArray(current.sorted()))
                    current.size
                }
                i.success("Liste mise à jour : **$total** mot(s) interdit(s).", ephemeral = true)
            }
            "sanction" -> {
                val type = i.string("type") ?: "none"
                val minutes = i.long("minutes") ?: 5
                db.edit(gid) { g -> config(g).put("sanction", type).put("muteMinutes", minutes) }
                i.success("Sanction : ${sanctionLabel(JSONObject().put("sanction", type).put("muteMinutes", minutes))}.")
            }
            "ignorer-role", "surveiller-role" -> {
                val role = i.snowflake("role") ?: return
                db.edit(gid) { g ->
                    val set = config(g).arr("ignoredRoles").strings().toMutableSet()
                    if (i.subcommand == "ignorer-role") set += role else set -= role
                    config(g).put("ignoredRoles", JSONArray(set.toList()))
                }
                i.success(if (i.subcommand == "ignorer-role") "${roleMention(role)} n'est plus surveillé." else "${roleMention(role)} est de nouveau surveillé.")
            }
        }
    }

    private fun sanctionLabel(cfg: JSONObject): String = when (cfg.optString("sanction", "none")) {
        "warn" -> "Avertissement"
        "mute" -> "Mute ${formatDuration(cfg.optLong("muteMinutes", 5) * 60_000)}"
        else -> "Suppression seulement"
    }

    // ---------------------------------------------------------------- détection

    override fun onEvent(type: String, d: JSONObject) {
        if (type != "MESSAGE_CREATE") return
        val gid = d.str("guild_id") ?: return
        val author = d.optJSONObject("author") ?: return
        if (author.optBoolean("bot") || d.has("webhook_id")) return
        val uid = author.getString("id")
        val roles = d.optJSONObject("member")?.optJSONArray("roles")?.strings().orEmpty()
        if (ctx.guilds[gid]?.isStaff(uid, roles) == true) return

        val cfg = db.read(gid) { g -> g.optJSONObject("config")?.optJSONObject("automod")?.let { JSONObject(it.toString()) } }
        val ignored = cfg?.optJSONArray("ignoredRoles")?.strings().orEmpty()
        if (roles.any { it in ignored }) return

        val reason = check(gid, uid, d, cfg) ?: return
        punish(gid, d, author, reason, cfg ?: JSONObject())
    }

    /** Renvoie la raison de l'infraction, ou null si le message est correct. */
    fun check(gid: String, uid: String, d: JSONObject, cfg: JSONObject?): String? {
        val content = d.optString("content")
        if (isOn(cfg, Filter.MENTIONS)) {
            val count = (d.optJSONArray("mentions")?.length() ?: 0) + (d.optJSONArray("mention_roles")?.length() ?: 0) +
                (if (d.optBoolean("mention_everyone")) 5 else 0)
            if (count >= MAX_MENTIONS) return "mentions de masse"
        }
        if (isOn(cfg, Filter.INVITES) && INVITE.containsMatchIn(content)) return "invitation Discord"
        if (isOn(cfg, Filter.LINKS) && LINK.containsMatchIn(content)) return "lien interdit"
        if (isOn(cfg, Filter.CAPS)) {
            val letters = content.filter { it.isLetter() }
            if (letters.length >= 12 && letters.count { it.isUpperCase() } >= letters.length * 0.7) return "abus de majuscules"
        }
        if (isOn(cfg, Filter.WORDS)) {
            val words = cfg?.optJSONArray("words")?.strings().orEmpty()
            if (words.isNotEmpty()) {
                val normalized = normalize(content)
                val hit = words.firstOrNull { w -> Regex("(?<![\\p{L}\\p{N}])${Regex.escape(w)}(?![\\p{L}\\p{N}])").containsMatchIn(normalized) }
                if (hit != null) return "mot interdit"
            }
        }
        if (isOn(cfg, Filter.SPAM)) {
            val now = System.currentTimeMillis()
            val queue = recent.getOrPut("$gid:$uid") { ArrayDeque() }
            synchronized(queue) {
                queue.addLast(now to content.trim().lowercase())
                while (queue.isNotEmpty() && now - queue.first().first > SPAM_WINDOW_MS) queue.removeFirst()
                val sameText = content.isNotBlank() && queue.count { it.second == content.trim().lowercase() } >= 3
                if (queue.size >= SPAM_MESSAGES || sameText) {
                    queue.clear()
                    return "spam"
                }
            }
        }
        return null
    }

    private fun punish(gid: String, d: JSONObject, author: JSONObject, reason: String, cfg: JSONObject) {
        val uid = author.getString("id")
        val channel = d.getString("channel_id")
        ctx.stats.automod.incrementAndGet()
        runCatching { rest.deleteMessage(channel, d.getString("id"), "AutoMod : $reason") }

        val sanction = cfg.optString("sanction", "none")
        var extra = ""
        when (sanction) {
            "warn" -> {
                val total = db.edit(gid) { g ->
                    val list = g.obj("warns").arr(uid)
                    list.put(JSONObject().put("reason", "AutoMod : $reason").put("mod", ctx.botId).put("at", System.currentTimeMillis()))
                    list.length()
                }
                extra = " Avertissement n°$total."
            }
            "mute" -> {
                val ms = cfg.optLong("muteMinutes", 5) * 60_000
                runCatching { rest.timeout(gid, uid, msToIso(System.currentTimeMillis() + ms), "AutoMod : $reason") }
                    .onSuccess { extra = " Mute ${formatDuration(ms)}." }
            }
        }
        // Le spam est puni d'un mute court même sans sanction configurée.
        if (reason == "spam" && sanction == "none") {
            runCatching { rest.timeout(gid, uid, msToIso(System.currentTimeMillis() + 60_000), "AutoMod : spam") }
                .onSuccess { extra = " Mute 1 min." }
        }

        runCatching {
            val warning = rest.createMessage(
                channel,
                message(embed = Embeds.base(description = "🛡️ ${mention(uid)}, ton message a été supprimé : **$reason**.$extra", color = Embeds.RED)),
            )
            ctx.scope.launch {
                delay(6_000)
                runCatching { rest.deleteMessage(channel, warning.getString("id")) }
            }
        }
        ctx.modLog(
            gid,
            Embeds.base("🛡️ AutoMod", color = Embeds.RED)
                .field("Membre", "${displayName(author)} (${mention(uid)})", inline = true)
                .field("Raison", reason, inline = true)
                .field("Message", d.optString("content").ifBlank { "—" }.take(1000)),
        )
    }

    companion object {
        private const val MAX_MENTIONS = 5
        private const val SPAM_MESSAGES = 6
        private const val SPAM_WINDOW_MS = 5_000L
        private val INVITE = Regex("(discord\\.gg|discord(app)?\\.com/invite)/[a-z0-9-]+", RegexOption.IGNORE_CASE)
        private val LINK = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

        /** Minuscules sans accents, pour que « ÉNERVÉ » et « enerve » soient égaux. */
        fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    }
}
