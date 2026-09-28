package com.bothostinger.app.bot.engine

import com.bothostinger.app.data.str
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/** Permissions Discord (bits). */
object Perm {
    const val KICK = 1L shl 1
    const val BAN = 1L shl 2
    const val ADMIN = 1L shl 3
    const val MANAGE_CHANNELS = 1L shl 4
    const val MANAGE_GUILD = 1L shl 5
    const val VIEW = 1L shl 10
    const val SEND = 1L shl 11
    const val MANAGE_MESSAGES = 1L shl 13
    const val EMBED = 1L shl 14
    const val ATTACH = 1L shl 15
    const val HISTORY = 1L shl 16
    const val MANAGE_ROLES = 1L shl 28
    const val MODERATE = 1L shl 40
}

/** Construction des embeds, tous à la couleur de BotHostinger. */
object Embeds {
    const val ORANGE = 0xFF6A00
    const val RED = 0xED4245
    const val GREEN = 0x3BA55D
    const val FOOTER = "⚡ BotHostinger"

    fun base(title: String? = null, description: String? = null, color: Int = ORANGE): JSONObject {
        val e = JSONObject().put("color", color).put("footer", JSONObject().put("text", FOOTER))
        if (title != null) e.put("title", title.take(256))
        if (description != null) e.put("description", description.take(4096))
        return e
    }

    fun error(text: String) = base(description = "❌ $text", color = RED)
    fun success(text: String) = base(description = "✅ $text", color = GREEN)
}

fun JSONObject.field(name: String, value: String, inline: Boolean = false): JSONObject {
    val fields = optJSONArray("fields") ?: JSONArray().also { put("fields", it) }
    fields.put(JSONObject().put("name", name.take(256)).put("value", value.ifBlank { "—" }.take(1024)).put("inline", inline))
    return this
}

fun JSONObject.thumbnail(url: String?): JSONObject = if (url == null) this else put("thumbnail", JSONObject().put("url", url))
fun JSONObject.image(url: String?): JSONObject = if (url == null) this else put("image", JSONObject().put("url", url))
fun JSONObject.author(name: String, icon: String? = null): JSONObject =
    put("author", JSONObject().put("name", name.take(256)).apply { if (icon != null) put("icon_url", icon) })
fun JSONObject.footer(text: String): JSONObject = put("footer", JSONObject().put("text", "$text · ${Embeds.FOOTER}"))
fun JSONObject.timestampNow(): JSONObject = put("timestamp", Instant.now().toString())

/** Corps d'un message (contenu, embeds, composants). */
fun message(
    content: String? = null,
    embed: JSONObject? = null,
    components: JSONArray? = null,
    allowUserMentionsOnly: Boolean = false,
): JSONObject {
    val m = JSONObject()
    if (content != null) m.put("content", content.take(2000))
    if (embed != null) m.put("embeds", JSONArray().put(embed))
    if (components != null) m.put("components", components)
    if (allowUserMentionsOnly) m.put("allowed_mentions", JSONObject().put("parse", JSONArray().put("users")))
    return m
}

// ------------------------------------------------------------ composants

/** Styles : 1 primaire, 2 gris, 3 vert, 4 rouge. */
fun button(customId: String, label: String, style: Int = 1, emoji: String? = null, disabled: Boolean = false): JSONObject {
    val b = JSONObject().put("type", 2).put("style", style).put("custom_id", customId).put("label", label.take(80))
    if (emoji != null) b.put("emoji", JSONObject().put("name", emoji))
    if (disabled) b.put("disabled", true)
    return b
}

fun rows(buttons: List<JSONObject>): JSONArray {
    val arr = JSONArray()
    buttons.chunked(5).forEach { chunk -> arr.put(JSONObject().put("type", 1).put("components", JSONArray(chunk))) }
    return arr
}

fun row(vararg buttons: JSONObject): JSONArray = rows(buttons.toList())

// ------------------------------------------------------------ utilisateurs et formats

fun mention(userId: String) = "<@$userId>"
fun channelMention(channelId: String) = "<#$channelId>"
fun roleMention(roleId: String) = "<@&$roleId>"

fun displayName(user: JSONObject?): String =
    user?.str("global_name") ?: user?.str("username") ?: "Inconnu"

fun avatarUrl(user: JSONObject?): String {
    val id = user?.str("id") ?: "0"
    val hash = user?.str("avatar")
    return if (hash != null) {
        val ext = if (hash.startsWith("a_")) "gif" else "png"
        "https://cdn.discordapp.com/avatars/$id/$hash.$ext?size=512"
    } else {
        val index = ((id.toLongOrNull() ?: 0L) shr 22) % 6
        "https://cdn.discordapp.com/embed/avatars/$index.png"
    }
}

fun guildIconUrl(guildId: String, hash: String?): String? =
    hash?.let { "https://cdn.discordapp.com/icons/$guildId/$it.${if (it.startsWith("a_")) "gif" else "png"}?size=512" }

/** Date de création d'un identifiant Discord (en millisecondes). */
fun snowflakeTime(id: String): Long = ((id.toLongOrNull() ?: 0L) shr 22) + 1420070400000L

/** Horodatage Discord qui s'affiche dans le fuseau de chacun (R = « il y a 3 jours », F = date complète). */
fun ts(ms: Long, style: String = "R") = "<t:${ms / 1000}:$style>"

fun isoToMs(iso: String?): Long? = iso?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

fun msToIso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

fun Long.fr(): String = String.format(Locale.FRANCE, "%,d", this)
fun Int.fr(): String = toLong().fr()

fun progressBar(fraction: Double, length: Int = 12): String {
    val filled = (fraction.coerceIn(0.0, 1.0) * length).toInt()
    return "▰".repeat(filled) + "▱".repeat(length - filled)
}

/** « 1h30m », « 10m », « 2j », « 45s », « 1w » → millisecondes. */
fun parseDuration(input: String): Long? {
    val clean = input.replace(" ", "").lowercase()
    if (clean.isEmpty()) return null
    val regex = Regex("(\\d+)(sec|sem|min|s|m|h|j|d|w)")
    var total = 0L
    var matchedLength = 0
    regex.findAll(clean).forEach { m ->
        val n = m.groupValues[1].toLongOrNull() ?: return null
        total += n * when (m.groupValues[2]) {
            "s", "sec" -> 1_000L
            "m", "min" -> 60_000L
            "h" -> 3_600_000L
            "j", "d" -> 86_400_000L
            else -> 604_800_000L
        }
        matchedLength += m.value.length
    }
    if (matchedLength != clean.length) return null
    return total.takeIf { it > 0 }
}

fun formatDuration(ms: Long): String {
    var s = ms / 1000
    val d = s / 86_400; s %= 86_400
    val h = s / 3_600; s %= 3_600
    val m = s / 60; s %= 60
    return buildList {
        if (d > 0) add("${d} j")
        if (h > 0) add("${h} h")
        if (m > 0) add("${m} min")
        if (s > 0 && d == 0L) add("${s} s")
    }.ifEmpty { listOf("0 s") }.joinToString(" ")
}
