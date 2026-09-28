package com.bothostinger.app.bot.engine

import com.bothostinger.app.bot.BotRuntime
import com.bothostinger.app.bot.DiscordRest
import com.bothostinger.app.data.BotDatabase
import com.bothostinger.app.data.str
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Infos d'un serveur gardées en mémoire (reçues via GUILD_CREATE). */
class GuildInfo(
    val id: String,
    @Volatile var name: String,
    @Volatile var icon: String?,
    @Volatile var ownerId: String,
    @Volatile var memberCount: Int,
    @Volatile var roleCount: Int,
    @Volatile var channelCount: Int,
    @Volatile var boosts: Int,
    @Volatile var boostTier: Int,
)

/** Tout ce dont les modules ont besoin pour travailler. */
class BotContext(
    val rest: DiscordRest,
    val db: BotDatabase,
    val scope: CoroutineScope,
    val enabledModules: Set<String>,
) {
    val guilds = ConcurrentHashMap<String, GuildInfo>()
    @Volatile var botId: String = ""
    @Volatile var botName: String = ""
    @Volatile var botAvatar: String? = null
    @Volatile var latencyMs: Long = -1
    val startedAt: Long = System.currentTimeMillis()
    var modules: List<Module> = emptyList()
        internal set

    fun guildName(guildId: String?): String = guildId?.let { guilds[it]?.name } ?: "ce serveur"

    /** Envoie un embed dans le salon de logs du serveur (si le module Logs est actif et configuré). */
    fun modLog(guildId: String, embed: JSONObject) {
        if ("logs" !in enabledModules) return
        val channel = db.read(guildId) { it.optJSONObject("config")?.optJSONObject("logs")?.str("channel") } ?: return
        runCatching { rest.createMessage(channel, message(embed = embed.timestampNow())) }
            .onFailure { BotRuntime.log("Logs impossibles : ${it.message}", isError = true) }
    }
}

/**
 * Un « système » du bot (modération, niveaux, économie…) : ses commandes
 * slash, ses réactions aux évènements Discord et ses boutons.
 */
abstract class Module(
    val id: String,
    val title: String,
    val description: String,
    /** Toujours actif (ne peut pas être désactivé dans l'app). */
    val alwaysOn: Boolean = false,
) {
    lateinit var ctx: BotContext
    protected val db: BotDatabase get() = ctx.db
    protected val rest: DiscordRest get() = ctx.rest

    abstract val commands: List<Command>

    /** Explication affichée dans l'app pour configurer le système. */
    open val setup: String = ""

    open val needsMembersIntent: Boolean = false
    open val needsMessages: Boolean = false

    open fun onEvent(type: String, d: JSONObject) {}

    /** Gère un clic sur bouton. Renvoie true si le bouton appartient au module. */
    open fun onComponent(i: Interaction): Boolean = false

    /** Appelé toutes les 10 secondes (fins de giveaways, rappels…). */
    open fun tick(now: Long) {}
}
