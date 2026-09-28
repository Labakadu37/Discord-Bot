package com.bothostinger.app.bot.engine

import com.bothostinger.app.bot.BotRuntime
import com.bothostinger.app.bot.ConnState
import com.bothostinger.app.bot.DiscordGateway
import com.bothostinger.app.bot.DiscordRest
import com.bothostinger.app.bot.GatewayListener
import com.bothostinger.app.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Le cerveau du bot : reçoit les évènements du Gateway, les distribue aux
 * modules actifs et exécute les commandes slash.
 */
class BotEngine(private val ctx: BotContext, allModules: List<Module>) : GatewayListener {

    val modules: List<Module> = allModules.filter { it.alwaysOn || it.id in ctx.enabledModules }
    private val commands: Map<String, Command> = modules.flatMap { it.commands }.associateBy { it.name }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val workers = Dispatchers.IO.limitedParallelism(4)

    init {
        modules.forEach { it.ctx = ctx }
        ctx.modules = modules
    }

    val intents: Int
        get() {
            var i = DiscordGateway.INTENT_GUILDS
            if (modules.any { it.needsMembersIntent }) i = i or DiscordGateway.INTENT_GUILD_MEMBERS
            if (modules.any { it.needsMessages }) i = i or DiscordGateway.INTENT_GUILD_MESSAGES
            return i
        }

    val commandCount: Int get() = commands.size

    fun commandsJson(): JSONArray = JSONArray(commands.values.map { it.toJson() })

    /** Lance la boucle de fond (toutes les 10 s) : giveaways, rappels, sauvegarde. */
    fun start() {
        ctx.scope.launch(workers) {
            while (isActive) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                modules.forEach { m ->
                    runCatching { m.tick(now) }
                        .onFailure { BotRuntime.log("Erreur ${m.title} : ${it.message}", isError = true) }
                }
                runCatching { ctx.db.flush() }
            }
        }
    }

    fun stop() {
        runCatching { ctx.db.flush() }
    }

    // ---------------------------------------------------------------- Gateway

    override fun onReady(d: JSONObject) {
        val user = d.getJSONObject("user")
        val appId = d.getJSONObject("application").getString("id")
        ctx.botId = user.getString("id")
        ctx.botName = user.optString("username")
        ctx.botAvatar = avatarUrl(user)
        val guildCount = d.optJSONArray("guilds")?.length() ?: 0
        BotRuntime.update {
            it.copy(
                conn = ConnState.ONLINE,
                botName = ctx.botName,
                applicationId = appId,
                guildCount = guildCount,
                onlineSince = System.currentTimeMillis(),
                error = null,
            )
        }
        BotRuntime.log("✅ Connecté en tant que ${ctx.botName} sur $guildCount serveur(s).")
        ctx.scope.launch(workers) {
            runCatching { ctx.rest.overwriteGlobalCommands(appId, commandsJson()) }
                .onSuccess { BotRuntime.log("${commands.size} commandes synchronisées avec Discord.") }
                .onFailure { BotRuntime.log("Échec de l'enregistrement des commandes : ${it.message}", isError = true) }
        }
    }

    override fun onLatency(ms: Long) {
        ctx.latencyMs = ms
        BotRuntime.update { it.copy(latencyMs = ms) }
    }

    override fun onDispatch(type: String, d: JSONObject) {
        // Mise à jour du cache tout de suite (dans l'ordre), le reste en parallèle.
        updateCache(type, d)
        ctx.scope.launch(workers) {
            if (type == "INTERACTION_CREATE") {
                handleInteraction(d)
                return@launch
            }
            modules.forEach { m ->
                runCatching { m.onEvent(type, d) }
                    .onFailure { BotRuntime.log("Erreur ${m.title} ($type) : ${it.message}", isError = true) }
            }
        }
    }

    private fun updateCache(type: String, d: JSONObject) {
        when (type) {
            "GUILD_CREATE", "GUILD_UPDATE" -> {
                val id = d.getString("id")
                val existing = ctx.guilds[id]
                ctx.guilds[id] = GuildInfo(
                    id = id,
                    name = d.optString("name"),
                    icon = d.str("icon"),
                    ownerId = d.optString("owner_id"),
                    memberCount = d.optInt("member_count", existing?.memberCount ?: 0),
                    roleCount = d.optJSONArray("roles")?.length() ?: existing?.roleCount ?: 0,
                    channelCount = d.optJSONArray("channels")?.length() ?: existing?.channelCount ?: 0,
                    boosts = d.optInt("premium_subscription_count"),
                    boostTier = d.optInt("premium_tier"),
                )
                BotRuntime.update { it.copy(guildCount = maxOf(it.guildCount, ctx.guilds.size)) }
            }
            "GUILD_DELETE" -> if (!d.optBoolean("unavailable", false)) {
                ctx.guilds.remove(d.getString("id"))
                BotRuntime.update { it.copy(guildCount = ctx.guilds.size) }
            }
            "GUILD_MEMBER_ADD" -> d.str("guild_id")?.let { ctx.guilds[it] }?.let { it.memberCount++ }
            "GUILD_MEMBER_REMOVE" -> d.str("guild_id")?.let { ctx.guilds[it] }?.let { it.memberCount = maxOf(0, it.memberCount - 1) }
        }
    }

    // ---------------------------------------------------------------- interactions

    private fun handleInteraction(d: JSONObject) {
        val i = Interaction(d, ctx.rest)
        try {
            when (i.type) {
                2 -> {
                    val cmd = commands[i.name]
                    if (cmd == null) {
                        i.error("Cette commande est désactivée sur ce bot.")
                        return
                    }
                    if (i.guildId == null) {
                        i.error("Cette commande s'utilise sur un serveur.")
                        return
                    }
                    BotRuntime.log("/${i.name}${i.subcommand?.let { " $it" }.orEmpty()} par ${displayName(i.user)}")
                    cmd.run(i)
                }
                3 -> if (modules.none { it.onComponent(i) }) i.error("Ce bouton n'est plus actif.")
            }
        } catch (e: DiscordRest.ApiException) {
            respondError(i, e.friendly())
        } catch (e: Exception) {
            BotRuntime.log("Erreur sur /${i.name} : ${e.message}", isError = true)
            respondError(i, "Une erreur est survenue : ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun respondError(i: Interaction, text: String) {
        runCatching { i.error(text) }
    }

    companion object {
        const val TICK_MS = 10_000L
    }
}
