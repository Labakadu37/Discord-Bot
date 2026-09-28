package com.bothostinger.app.bot.engine

import com.bothostinger.app.bot.BotRuntime
import com.bothostinger.app.bot.BotStats
import com.bothostinger.app.bot.DiscordRest
import com.bothostinger.app.data.BotDatabase
import com.bothostinger.app.data.str
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
) {
    /** Rôles : ID → nom. */
    val roles = ConcurrentHashMap<String, String>()
    /** Rôles : ID → permissions. */
    val rolePermissions = ConcurrentHashMap<String, Long>()

    /** Le membre (avec ces rôles) fait-il partie du staff (admin ou gestion des messages) ? */
    fun isStaff(userId: String, memberRoles: List<String>): Boolean {
        if (userId == ownerId) return true
        val perms = (memberRoles + id).fold(0L) { acc, r -> acc or (rolePermissions[r] ?: 0L) }
        return perms and (Perm.ADMIN or Perm.MANAGE_MESSAGES or Perm.MANAGE_GUILD) != 0L
    }
    /** Salons : ID → infos. */
    val channels = ConcurrentHashMap<String, ChannelInfo>()
}

/** [type] : 0 texte, 2 vocal, 4 catégorie, 5 annonces… */
data class ChannelInfo(val name: String, val type: Int)

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

    /** Compteurs affichés dans l'écran Statistiques de l'app. */
    val stats = Stats()

    fun module(id: String): Module? = modules.firstOrNull { it.id == id }

    fun guildName(guildId: String?): String = guildId?.let { guilds[it]?.name } ?: "ce serveur"

    /** Trouve un rôle par mention, ID ou nom (sans tenir compte des majuscules). */
    fun findRole(guildId: String, ref: String): String? {
        val clean = ref.trim().removePrefix("<@&").removeSuffix(">").removePrefix("@")
        if (clean.isEmpty()) return null
        val roles = guilds[guildId]?.roles ?: return clean.takeIf { it.all(Char::isDigit) }
        if (roles.containsKey(clean)) return clean
        return roles.entries.firstOrNull { it.value.equals(clean, ignoreCase = true) }?.key
            ?: clean.takeIf { it.all(Char::isDigit) }
    }

    /** Trouve un salon par mention, ID ou nom (avec ou sans #). */
    fun findChannel(guildId: String, ref: String): String? {
        val clean = ref.trim().removePrefix("<#").removeSuffix(">").removePrefix("#")
        if (clean.isEmpty()) return null
        val channels = guilds[guildId]?.channels ?: return clean.takeIf { it.all(Char::isDigit) }
        if (channels.containsKey(clean)) return clean
        return channels.entries.firstOrNull { it.value.name.equals(clean, ignoreCase = true) }?.key
            ?: clean.takeIf { it.all(Char::isDigit) }
    }

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
    /** Lit le texte des messages (intent privilégié « MESSAGE CONTENT »). */
    open val needsMessageContent: Boolean = false
    open val needsReactions: Boolean = false
    open val needsVoice: Boolean = false

    open fun onEvent(type: String, d: JSONObject) {}

    /** Gère un clic sur bouton ou un menu. Renvoie true si le composant appartient au module. */
    open fun onComponent(i: Interaction): Boolean = false

    /** Gère un formulaire envoyé. Renvoie true s'il appartient au module. */
    open fun onModal(i: Interaction): Boolean = false

    /** Appelé toutes les 10 secondes (fins de giveaways, rappels…). */
    open fun tick(now: Long) {}
}

/** Compteurs d'activité (depuis le démarrage du bot), publiés vers l'interface. */
class Stats {
    private val commands = ConcurrentHashMap<String, AtomicLong>()
    val messages = AtomicLong()
    val joins = AtomicLong()
    val automod = AtomicLong()

    fun command(name: String) {
        commands.getOrPut(name) { AtomicLong() }.incrementAndGet()
        publish()
    }

    fun publish() {
        BotRuntime.updateStats(
            BotStats(
                commands = commands.mapValues { it.value.get() },
                messages = messages.get(),
                joins = joins.get(),
                automod = automod.get(),
            )
        )
    }
}
