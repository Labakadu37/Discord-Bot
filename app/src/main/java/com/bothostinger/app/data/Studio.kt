package com.bothostinger.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Type d'une option de commande personnalisée (valeur = type Discord). */
enum class OptionKind(val discordType: Int, val label: String) {
    TEXT(3, "Texte"),
    NUMBER(10, "Nombre"),
    USER(6, "Membre"),
    ROLE(8, "Rôle"),
    CHANNEL(7, "Salon"),
}

data class CustomOption(
    val name: String = "",
    val description: String = "",
    val kind: OptionKind = OptionKind.TEXT,
    val required: Boolean = true,
)

enum class ActionKind(val label: String, val needsTarget: Boolean, val needsText: Boolean, val needsAmount: Boolean) {
    ADD_ROLE("Donner un rôle", true, false, false),
    REMOVE_ROLE("Retirer un rôle", true, false, false),
    TOGGLE_ROLE("Donner ou retirer un rôle", true, false, false),
    SEND_CHANNEL("Envoyer un message dans un salon", true, true, false),
    SEND_DM("Envoyer un message privé", false, true, false),
    ADD_COINS("Donner des pièces", false, false, true),
    REMOVE_COINS("Retirer des pièces", false, false, true),
    ADD_XP("Donner de l'XP", false, false, true),
}

/**
 * Action exécutée après la réponse.
 * @property target rôle ou salon : nom, ID, mention ou variable (ex. `{option:role}`).
 * @property member membre visé : vide = celui qui utilise la commande, sinon variable (ex. `{option:membre}`).
 */
data class CustomAction(
    val kind: ActionKind = ActionKind.ADD_ROLE,
    val target: String = "",
    val text: String = "",
    val amount: Long = 0,
    val member: String = "",
)

data class LinkButton(val label: String = "", val url: String = "")

data class CustomCommand(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val options: List<CustomOption> = emptyList(),
    val content: String = "",
    val useEmbed: Boolean = true,
    val embedTitle: String = "",
    val embedDescription: String = "",
    val embedColor: Int = 0x3D6BFF,
    val embedImage: String = "",
    val embedThumbnail: String = "",
    val embedFooter: String = "",
    val ephemeral: Boolean = false,
    val buttons: List<LinkButton> = emptyList(),
    val actions: List<CustomAction> = emptyList(),
    /** Rôle requis (nom ou ID) ; vide = tout le monde. */
    val requiredRole: String = "",
    /** Permission Discord requise (bits) ; 0 = aucune. */
    val permission: Long = 0,
    val cooldownSeconds: Int = 0,
    /** Prix en pièces (système Économie). */
    val cost: Long = 0,
)

enum class MatchMode(val label: String) {
    EXACT("Message exact"),
    CONTAINS("Contient"),
    STARTS("Commence par"),
}

data class AutoResponse(
    val id: String = UUID.randomUUID().toString(),
    val trigger: String = "",
    val mode: MatchMode = MatchMode.CONTAINS,
    val response: String = "",
    val reply: Boolean = true,
    val reaction: String = "",
    val deleteTrigger: Boolean = false,
    val enabled: Boolean = true,
)

/** Tout ce que l'utilisateur a créé dans le Studio. */
data class Studio(
    val commands: List<CustomCommand> = emptyList(),
    val responses: List<AutoResponse> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("commands", JSONArray(commands.map { it.toJson() }))
        .put("responses", JSONArray(responses.map { it.toJson() }))

    companion object {
        val NAME_RULE = Regex("^[-_a-z0-9]{1,32}$")

        fun fromJson(o: JSONObject?): Studio {
            if (o == null) return Studio()
            return Studio(
                commands = o.optJSONArray("commands")?.objects()?.map { commandFromJson(it) }.orEmpty(),
                responses = o.optJSONArray("responses")?.objects()?.map { responseFromJson(it) }.orEmpty(),
            )
        }

        /** Exemples proposés au premier lancement du Studio. */
        fun examples() = Studio(
            commands = listOf(
                CustomCommand(
                    name = "salut",
                    description = "Le bot te dit bonjour",
                    embedTitle = "Salut {user} !",
                    embedDescription = "Bienvenue sur **{server}**, nous sommes {server.members} membres.\nTon nombre porte-bonheur : **{random:1-100}**",
                    embedThumbnail = "{user.avatar}",
                ),
                CustomCommand(
                    name = "calin",
                    description = "Faire un câlin à quelqu'un",
                    options = listOf(CustomOption("membre", "Qui reçoit le câlin", OptionKind.USER)),
                    useEmbed = false,
                    content = "{user.mention} fait un gros câlin à {option:membre} !",
                ),
            ),
            responses = listOf(
                AutoResponse(trigger = "bonjour", mode = MatchMode.STARTS, response = "Bonjour {user.mention} !"),
            ),
        )

        private fun commandFromJson(o: JSONObject) = CustomCommand(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            description = o.optString("description"),
            enabled = o.optBoolean("enabled", true),
            options = o.optJSONArray("options")?.objects()?.map {
                CustomOption(
                    it.optString("name"),
                    it.optString("description"),
                    runCatching { OptionKind.valueOf(it.optString("kind")) }.getOrDefault(OptionKind.TEXT),
                    it.optBoolean("required", true),
                )
            }.orEmpty(),
            content = o.optString("content"),
            useEmbed = o.optBoolean("useEmbed", true),
            embedTitle = o.optString("embedTitle"),
            embedDescription = o.optString("embedDescription"),
            embedColor = o.optInt("embedColor", 0x3D6BFF),
            embedImage = o.optString("embedImage"),
            embedThumbnail = o.optString("embedThumbnail"),
            embedFooter = o.optString("embedFooter"),
            ephemeral = o.optBoolean("ephemeral"),
            buttons = o.optJSONArray("buttons")?.objects()?.map { LinkButton(it.optString("label"), it.optString("url")) }.orEmpty(),
            actions = o.optJSONArray("actions")?.objects()?.map {
                CustomAction(
                    runCatching { ActionKind.valueOf(it.optString("kind")) }.getOrDefault(ActionKind.ADD_ROLE),
                    it.optString("target"),
                    it.optString("text"),
                    it.optLong("amount"),
                    it.optString("member"),
                )
            }.orEmpty(),
            requiredRole = o.optString("requiredRole"),
            permission = o.optLong("permission"),
            cooldownSeconds = o.optInt("cooldownSeconds"),
            cost = o.optLong("cost"),
        )

        private fun responseFromJson(o: JSONObject) = AutoResponse(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            trigger = o.optString("trigger"),
            mode = runCatching { MatchMode.valueOf(o.optString("mode")) }.getOrDefault(MatchMode.CONTAINS),
            response = o.optString("response"),
            reply = o.optBoolean("reply", true),
            reaction = o.optString("reaction"),
            deleteTrigger = o.optBoolean("deleteTrigger"),
            enabled = o.optBoolean("enabled", true),
        )
    }
}

fun CustomCommand.toJson(): JSONObject = JSONObject()
    .put("id", id).put("name", name).put("description", description).put("enabled", enabled)
    .put("options", JSONArray(options.map { JSONObject().put("name", it.name).put("description", it.description).put("kind", it.kind.name).put("required", it.required) }))
    .put("content", content).put("useEmbed", useEmbed)
    .put("embedTitle", embedTitle).put("embedDescription", embedDescription).put("embedColor", embedColor)
    .put("embedImage", embedImage).put("embedThumbnail", embedThumbnail).put("embedFooter", embedFooter)
    .put("ephemeral", ephemeral)
    .put("buttons", JSONArray(buttons.map { JSONObject().put("label", it.label).put("url", it.url) }))
    .put("actions", JSONArray(actions.map { JSONObject().put("kind", it.kind.name).put("target", it.target).put("text", it.text).put("amount", it.amount).put("member", it.member) }))
    .put("requiredRole", requiredRole).put("permission", permission)
    .put("cooldownSeconds", cooldownSeconds).put("cost", cost)

fun AutoResponse.toJson(): JSONObject = JSONObject()
    .put("id", id).put("trigger", trigger).put("mode", mode.name).put("response", response)
    .put("reply", reply).put("reaction", reaction).put("deleteTrigger", deleteTrigger).put("enabled", enabled)
