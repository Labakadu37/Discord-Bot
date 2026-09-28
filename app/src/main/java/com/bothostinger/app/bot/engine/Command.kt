package com.bothostinger.app.bot.engine

import org.json.JSONArray
import org.json.JSONObject

/** Option d'une commande slash (ou sous-commande quand [type] = 1). */
class Opt(
    val type: Int,
    val name: String,
    val description: String,
    val required: Boolean = true,
    val choices: List<Pair<String, Any>> = emptyList(),
    val min: Number? = null,
    val max: Number? = null,
    val maxLength: Int? = null,
    val channelTypes: List<Int>? = null,
    val options: List<Opt> = emptyList(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("type", type).put("name", name).put("description", description.take(100))
        if (type != SUB) o.put("required", required)
        if (choices.isNotEmpty()) {
            o.put("choices", JSONArray(choices.map { (n, v) -> JSONObject().put("name", n).put("value", v) }))
        }
        min?.let { o.put("min_value", it) }
        max?.let { o.put("max_value", it) }
        maxLength?.let { o.put("max_length", it) }
        channelTypes?.let { o.put("channel_types", JSONArray(it)) }
        if (options.isNotEmpty()) o.put("options", JSONArray(options.sortedByDescending { it.required }.map { it.toJson() }))
        return o
    }

    companion object {
        const val SUB = 1
        const val STRING = 3
        const val INTEGER = 4
        const val BOOLEAN = 5
        const val USER = 6
        const val CHANNEL = 7
        const val ROLE = 8
    }
}

fun sub(name: String, description: String, vararg options: Opt) =
    Opt(Opt.SUB, name, description, required = false, options = options.toList())

fun stringOpt(name: String, description: String, required: Boolean = true, maxLength: Int? = null, choices: List<Pair<String, Any>> = emptyList()) =
    Opt(Opt.STRING, name, description, required, choices = choices, maxLength = maxLength)

fun intOpt(name: String, description: String, required: Boolean = true, min: Long? = null, max: Long? = null) =
    Opt(Opt.INTEGER, name, description, required, min = min, max = max)

fun boolOpt(name: String, description: String, required: Boolean = true) = Opt(Opt.BOOLEAN, name, description, required)
fun userOpt(name: String, description: String, required: Boolean = true) = Opt(Opt.USER, name, description, required)
fun roleOpt(name: String, description: String, required: Boolean = true) = Opt(Opt.ROLE, name, description, required)

/** Salon textuel (0) ou d'annonces (5). */
fun textChannelOpt(name: String, description: String, required: Boolean = true) =
    Opt(Opt.CHANNEL, name, description, required, channelTypes = listOf(0, 5))

fun categoryOpt(name: String, description: String, required: Boolean = false) =
    Opt(Opt.CHANNEL, name, description, required, channelTypes = listOf(4))

/**
 * Commande slash intégrée au bot.
 *
 * @param permission permission Discord requise par défaut (les admins du serveur peuvent l'ajuster).
 */
class Command(
    val name: String,
    val description: String,
    val permission: Long? = null,
    val options: List<Opt> = emptyList(),
    val run: (Interaction) -> Unit,
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("type", 1)
            .put("name", name)
            .put("description", description.take(100))
            .put("contexts", JSONArray().put(0)) // serveurs uniquement
        if (options.isNotEmpty()) o.put("options", JSONArray(options.sortedByDescending { it.required }.map { it.toJson() }))
        if (permission != null) o.put("default_member_permissions", permission.toString())
        return o
    }

    /** Libellé affiché dans l'app, ex. « Bannir des membres ». */
    val permissionLabel: String?
        get() = when (permission) {
            null -> null
            Perm.BAN -> "Bannir"
            Perm.KICK -> "Expulser"
            Perm.MODERATE -> "Modérer"
            Perm.MANAGE_MESSAGES -> "Gérer messages"
            Perm.MANAGE_CHANNELS -> "Gérer salons"
            Perm.MANAGE_ROLES -> "Gérer rôles"
            Perm.MANAGE_GUILD -> "Gérer serveur"
            else -> "Staff"
        }

    val subcommands: List<Opt> get() = options.filter { it.type == Opt.SUB }
}
