package com.bothostinger.app.bot.engine

import com.bothostinger.app.data.str
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Ce qu'une variable peut connaître au moment où le bot répond. */
class TemplateScope(
    val user: JSONObject? = null,
    val guildId: String? = null,
    val channelId: String? = null,
    /** Options de la commande, déjà mises en forme (un membre devient une mention…). */
    val options: Map<String, String> = emptyMap(),
    /** Texte après le déclencheur (réponses automatiques). */
    val args: String = "",
    val ctx: BotContext? = null,
    val uses: Long = 0,
)

/**
 * Langage de variables du Studio : `{nom}` ou `{nom:paramètre}`.
 * Une variable inconnue est laissée telle quelle ; rien n'est jamais exécuté.
 */
object Template {
    private val pattern = Regex("\\{([a-zA-Z_.]+)(?::([^{}]*))?}")

    /** Documentation affichée dans l'app (variable → explication). */
    val reference: List<Pair<String, String>> = listOf(
        "{user}" to "Nom affiché du membre",
        "{user.mention}" to "Mention du membre (@membre)",
        "{user.name}" to "Nom d'utilisateur",
        "{user.id}" to "Identifiant du membre",
        "{user.avatar}" to "Lien de l'avatar",
        "{user.created}" to "Date de création du compte",
        "{server}" to "Nom du serveur",
        "{server.members}" to "Nombre de membres",
        "{server.icon}" to "Lien de l'icône du serveur",
        "{channel}" to "Mention du salon",
        "{option:nom}" to "Valeur d'une option de la commande",
        "{args}" to "Texte écrit après le déclencheur",
        "{random:1-100}" to "Nombre au hasard",
        "{choose:a|b|c}" to "Un choix au hasard",
        "{coins}" to "Pièces du membre",
        "{level}" to "Niveau du membre",
        "{date}" to "Date du jour",
        "{time}" to "Heure actuelle",
        "{timestamp}" to "Date et heure (affichée dans le fuseau de chacun)",
        "{uses}" to "Nombre d'utilisations de la commande",
        "{bot}" to "Nom du bot",
    )

    fun render(template: String, s: TemplateScope): String =
        pattern.replace(template) { m -> resolve(m.groupValues[1].lowercase(), m.groupValues[2], s) ?: m.value }

    private fun resolve(name: String, param: String, s: TemplateScope): String? {
        val uid = s.user?.str("id")
        val guild = s.guildId?.let { s.ctx?.guilds?.get(it) }
        return when (name) {
            "user" -> displayName(s.user)
            "user.name", "username" -> s.user?.str("username")
            "user.id" -> uid
            "user.mention", "mention" -> uid?.let { mention(it) }
            "user.avatar" -> avatarUrl(s.user)
            "user.created" -> uid?.let { ts(snowflakeTime(it), "D") }
            "server" -> guild?.name ?: "ce serveur"
            "server.id" -> s.guildId
            "server.members", "count" -> guild?.memberCount?.toString()
            "server.icon" -> guild?.let { guildIconUrl(it.id, it.icon) }.orEmpty()
            "channel" -> s.channelId?.let { channelMention(it) }
            "channel.id" -> s.channelId
            "option" -> s.options[param.trim().lowercase()].orEmpty()
            "args" -> s.args
            "random" -> randomBetween(param)
            "choose" -> param.split('|').map { it.trim() }.filter { it.isNotEmpty() }.randomOrNull().orEmpty()
            "date" -> SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date())
            "time" -> SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date())
            "timestamp" -> ts(System.currentTimeMillis(), "F")
            "uses" -> s.uses.toString()
            "bot" -> s.ctx?.botName
            "coins" -> userData(s, "eco", "bal")?.toString() ?: "0"
            "level" -> userData(s, "levels", "level")?.toString() ?: "0"
            "xp" -> userData(s, "levels", "total")?.toString() ?: "0"
            else -> null
        }
    }

    private fun randomBetween(param: String): String {
        val parts = param.split('-', ',').mapNotNull { it.trim().toLongOrNull() }
        val (a, b) = when (parts.size) {
            0 -> 1L to 100L
            1 -> 1L to parts[0]
            else -> minOf(parts[0], parts[1]) to maxOf(parts[0], parts[1])
        }
        return if (a >= b) a.toString() else Random.nextLong(a, b + 1).toString()
    }

    private fun userData(s: TemplateScope, section: String, field: String): Long? {
        val ctx = s.ctx ?: return null
        val gid = s.guildId ?: return null
        val uid = s.user?.str("id") ?: return null
        return ctx.db.read(gid) { g -> g.optJSONObject(section)?.optJSONObject(uid)?.optLong(field) }
    }
}
