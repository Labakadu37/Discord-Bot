package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.BotRuntime
import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Interaction
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Opt
import com.bothostinger.app.bot.engine.Template
import com.bothostinger.app.bot.engine.TemplateScope
import com.bothostinger.app.bot.engine.channelMention
import com.bothostinger.app.bot.engine.displayName
import com.bothostinger.app.bot.engine.mention
import com.bothostinger.app.bot.engine.message
import com.bothostinger.app.bot.engine.roleMention
import com.bothostinger.app.bot.engine.ts
import com.bothostinger.app.data.ActionKind
import com.bothostinger.app.data.AutoResponse
import com.bothostinger.app.data.CustomAction
import com.bothostinger.app.data.CustomCommand
import com.bothostinger.app.data.MatchMode
import com.bothostinger.app.data.OptionKind
import com.bothostinger.app.data.Studio
import com.bothostinger.app.data.obj
import com.bothostinger.app.data.str
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Les créations de l'utilisateur : commandes slash personnalisées
 * (réponse, embed, boutons, actions, conditions) et réponses automatiques.
 */
class StudioModule(private val studio: Studio = Studio()) : Module(
    id = "studio",
    title = "Studio",
    description = "Tes propres commandes et réponses automatiques, créées dans l'app sans coder.",
) {
    override val setup = "Crée tes commandes dans l'onglet Studio de l'app. Les réponses automatiques " +
        "lisent les messages : active « MESSAGE CONTENT INTENT » sur le portail développeur."

    private val activeCommands = studio.commands.filter { it.enabled && Studio.NAME_RULE.matches(it.name) }
    private val activeResponses = studio.responses.filter { it.enabled && it.trigger.isNotBlank() && it.response.isNotBlank() }
    private val cooldowns = ConcurrentHashMap<String, Long>()

    override val needsMessageContent: Boolean = activeResponses.isNotEmpty()

    override val commands: List<Command> = activeCommands.map { custom ->
        Command(
            name = custom.name,
            description = custom.description.ifBlank { "Commande personnalisée" },
            permission = custom.permission.takeIf { it != 0L },
            options = custom.options.filter { Studio.NAME_RULE.matches(it.name) }.map { o ->
                Opt(o.kind.discordType, o.name, o.description.ifBlank { o.name }, o.required)
            },
        ) { run(it, custom) }
    }

    // ---------------------------------------------------------------- commandes

    private fun run(i: Interaction, cmd: CustomCommand) {
        val gid = i.guildId!!
        if (cmd.requiredRole.isNotBlank()) {
            val role = ctx.findRole(gid, cmd.requiredRole)
            if (role == null || role !in i.memberRoles) {
                return i.error("Il te faut le rôle ${role?.let { roleMention(it) } ?: "« ${cmd.requiredRole} »"} pour utiliser cette commande.")
            }
        }
        val cooldownKey = "${cmd.id}:${i.userId}"
        if (cmd.cooldownSeconds > 0) {
            val until = cooldowns[cooldownKey] ?: 0L
            if (System.currentTimeMillis() < until) return i.error("Doucement ! Tu pourras la réutiliser ${ts(until)}.")
        }
        if (cmd.cost > 0) {
            val paid = db.edit(gid) { g ->
                val acc = g.obj("eco").obj(i.userId)
                if (acc.optLong("bal") < cmd.cost) return@edit false
                acc.put("bal", acc.optLong("bal") - cmd.cost).put("name", displayName(i.user))
                true
            }
            if (!paid) return i.error("Cette commande coûte **${cmd.cost}** 🪙. Tu n'as pas assez de pièces.")
        }
        // Le délai ne démarre que si la commande s'exécute vraiment.
        if (cmd.cooldownSeconds > 0) cooldowns[cooldownKey] = System.currentTimeMillis() + cmd.cooldownSeconds * 1000L
        val uses = db.edit(gid) { g ->
            val stats = g.obj("studio").obj("uses")
            (stats.optLong(cmd.id) + 1).also { stats.put(cmd.id, it) }
        }

        val scope = TemplateScope(
            user = i.user,
            guildId = gid,
            channelId = i.channelId,
            options = cmd.options.associate { o -> o.name.lowercase() to optionDisplay(i, o.name, o.kind) },
            ctx = ctx,
            uses = uses,
        )
        i.reply(
            embed = if (cmd.useEmbed) buildEmbed(cmd, scope) else null,
            content = Template.render(cmd.content, scope).ifBlank { null }?.take(2000),
            components = linkButtons(cmd, scope),
            ephemeral = cmd.ephemeral,
        ) { payload ->
            if (!payload.has("content") && !payload.has("embeds")) payload.put("content", "✅")
        }
        cmd.actions.forEach { action ->
            runCatching { execute(action, i, scope) }
                .onFailure { e -> BotRuntime.log("/${cmd.name} : action « ${action.kind.label} » impossible (${e.message})", isError = true) }
        }
    }

    private fun optionDisplay(i: Interaction, name: String, kind: OptionKind): String {
        val raw = i.string(name) ?: return ""
        return when (kind) {
            OptionKind.USER -> mention(raw)
            OptionKind.ROLE -> roleMention(raw)
            OptionKind.CHANNEL -> channelMention(raw)
            else -> raw
        }
    }

    /** Construit l'embed de réponse (null si tout est vide). */
    fun buildEmbed(cmd: CustomCommand, scope: TemplateScope): JSONObject? {
        val title = Template.render(cmd.embedTitle, scope).take(256)
        val description = Template.render(cmd.embedDescription, scope).take(4096)
        val image = Template.render(cmd.embedImage, scope).trim()
        val thumbnail = Template.render(cmd.embedThumbnail, scope).trim()
        if (title.isBlank() && description.isBlank() && image.isBlank()) return null
        val e = JSONObject().put("color", cmd.embedColor and 0xFFFFFF)
        if (title.isNotBlank()) e.put("title", title)
        if (description.isNotBlank()) e.put("description", description)
        if (image.startsWith("http")) e.put("image", JSONObject().put("url", image))
        if (thumbnail.startsWith("http")) e.put("thumbnail", JSONObject().put("url", thumbnail))
        val footer = Template.render(cmd.embedFooter, scope)
        e.put("footer", JSONObject().put("text", (if (footer.isBlank()) Embeds.FOOTER else "$footer · ${Embeds.FOOTER}").take(2048)))
        return e
    }

    private fun linkButtons(cmd: CustomCommand, scope: TemplateScope): JSONArray? {
        val buttons = cmd.buttons.mapNotNull { b ->
            val url = Template.render(b.url, scope).trim()
            if (b.label.isBlank() || !(url.startsWith("https://") || url.startsWith("http://"))) return@mapNotNull null
            JSONObject().put("type", 2).put("style", 5).put("label", Template.render(b.label, scope).take(80)).put("url", url)
        }.take(5)
        if (buttons.isEmpty()) return null
        return JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray(buttons)))
    }

    private fun execute(action: CustomAction, i: Interaction, scope: TemplateScope) {
        val gid = i.guildId!!
        // Vide = celui qui utilise la commande. Sinon le membre doit être reconnu : jamais de repli silencieux.
        val member = if (action.member.isBlank()) i.userId else {
            Template.render(action.member, scope).trim().removePrefix("<@").removePrefix("!").removeSuffix(">")
                .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?: error("membre « ${action.member} » introuvable")
        }
        val target = Template.render(action.target, scope)
        val text = Template.render(action.text, scope)
        val reason = "Commande personnalisée /${i.name}"
        when (action.kind) {
            ActionKind.ADD_ROLE -> rest.addRole(gid, member, role(gid, target), reason)
            ActionKind.REMOVE_ROLE -> rest.removeRole(gid, member, role(gid, target), reason)
            ActionKind.TOGGLE_ROLE -> {
                val role = role(gid, target)
                // Les rôles d'un autre membre ne sont pas dans l'interaction : on bascule selon ceux de l'auteur.
                if (member == i.userId && role in i.memberRoles) rest.removeRole(gid, member, role, reason)
                else rest.addRole(gid, member, role, reason)
            }
            ActionKind.SEND_CHANNEL -> {
                val channel = ctx.findChannel(gid, target) ?: error("salon « $target » introuvable")
                rest.createMessage(channel, message(text.ifBlank { "…" }, allowUserMentionsOnly = true))
            }
            ActionKind.SEND_DM -> rest.sendDm(member, message(text.ifBlank { "…" }))
            ActionKind.ADD_COINS, ActionKind.REMOVE_COINS -> db.edit(gid) { g ->
                val acc = g.obj("eco").obj(member)
                val delta = if (action.kind == ActionKind.ADD_COINS) action.amount else -action.amount
                acc.put("bal", maxOf(0L, acc.optLong("bal") + delta))
            }
            ActionKind.ADD_XP -> db.edit(gid) { g ->
                val u = g.obj("levels").obj(member)
                var xp = u.optInt("xp") + action.amount.toInt()
                var level = u.optInt("level")
                while (xp >= LevelsModule.xpForNext(level)) {
                    xp -= LevelsModule.xpForNext(level)
                    level++
                }
                u.put("xp", xp).put("level", level).put("total", u.optLong("total") + action.amount)
            }
        }
    }

    private fun role(gid: String, ref: String): String = ctx.findRole(gid, ref) ?: error("rôle « $ref » introuvable")

    // ---------------------------------------------------------------- réponses automatiques

    override fun onEvent(type: String, d: JSONObject) {
        if (type != "MESSAGE_CREATE" || activeResponses.isEmpty()) return
        val gid = d.str("guild_id") ?: return
        val author = d.optJSONObject("author") ?: return
        if (author.optBoolean("bot") || d.has("webhook_id")) return
        val content = d.optString("content").trim()
        if (content.isEmpty()) return
        val channel = d.getString("channel_id")
        val match = activeResponses.firstOrNull { matches(it, content) } ?: return

        // Anti-boucle / anti-spam : une réponse identique au plus toutes les 3 s par salon.
        val key = "${match.id}:$channel"
        val now = System.currentTimeMillis()
        if (now - (cooldowns[key] ?: 0L) < 3_000) return
        cooldowns[key] = now

        val args = if (match.mode == MatchMode.STARTS) content.drop(match.trigger.length).trim() else content
        val scope = TemplateScope(user = author, guildId = gid, channelId = channel, args = args, ctx = ctx)
        val text = Template.render(match.response, scope)
        val payload = message(text, allowUserMentionsOnly = true)
        if (match.reply && !match.deleteTrigger) {
            payload.put("message_reference", JSONObject().put("message_id", d.getString("id")).put("fail_if_not_exists", false))
        }
        runCatching { rest.createMessage(channel, payload) }
        if (match.reaction.isNotBlank() && !match.deleteTrigger) runCatching { rest.addReaction(channel, d.getString("id"), match.reaction.trim()) }
        if (match.deleteTrigger) runCatching { rest.deleteMessage(channel, d.getString("id"), "Réponse automatique") }
    }

    private fun matches(r: AutoResponse, content: String): Boolean {
        val trigger = r.trigger.trim()
        return when (r.mode) {
            MatchMode.EXACT -> content.equals(trigger, ignoreCase = true)
            MatchMode.STARTS -> content.startsWith(trigger, ignoreCase = true)
            // Mot entier : pas de lettre ni chiffre collé avant ou après (lettres accentuées comprises).
            MatchMode.CONTAINS -> Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(trigger)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
                .containsMatchIn(content)
        }
    }
}
