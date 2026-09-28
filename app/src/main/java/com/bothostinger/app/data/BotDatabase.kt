package com.bothostinger.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Base de données du bot : un document JSON par serveur Discord, gardé en
 * mémoire et écrit sur le disque du téléphone par [flush].
 *
 * Toutes les lectures/écritures passent par [read] / [edit], qui sont
 * synchronisées : les modules peuvent l'utiliser depuis plusieurs threads.
 */
class BotDatabase(private val dir: File) {
    private val guilds = HashMap<String, JSONObject>()
    private val dirty = HashSet<String>()

    init {
        dir.mkdirs()
        dir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            runCatching { guilds[file.nameWithoutExtension] = JSONObject(file.readText()) }
        }
    }

    fun <T> read(guildId: String, block: (JSONObject) -> T): T = synchronized(this) {
        block(guilds.getOrPut(guildId) { JSONObject() })
    }

    fun <T> edit(guildId: String, block: (JSONObject) -> T): T = synchronized(this) {
        val result = block(guilds.getOrPut(guildId) { JSONObject() })
        dirty += guildId
        result
    }

    fun guildIds(): List<String> = synchronized(this) { guilds.keys.toList() }

    /** Écrit les serveurs modifiés (écriture atomique via un fichier temporaire). */
    fun flush() {
        val pending = synchronized(this) {
            val snapshot = dirty.map { it to guilds[it].toString() }
            dirty.clear()
            snapshot
        }
        pending.forEach { (id, text) ->
            val tmp = File(dir, "$id.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(dir, "$id.json"))) {
                File(dir, "$id.json").writeText(text)
                tmp.delete()
            }
        }
    }
}

/** Renvoie l'objet enfant [key], en le créant s'il n'existe pas. */
fun JSONObject.obj(key: String): JSONObject =
    optJSONObject(key) ?: JSONObject().also { put(key, it) }

/** Renvoie le tableau enfant [key], en le créant s'il n'existe pas. */
fun JSONObject.arr(key: String): JSONArray =
    optJSONArray(key) ?: JSONArray().also { put(key, it) }

fun JSONObject.entries(): List<Pair<String, JSONObject>> =
    keys().asSequence().mapNotNull { k -> optJSONObject(k)?.let { k to it } }.toList()

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }

/** Chaîne optionnelle : null si absente ou vide (optString renvoie "" par défaut). */
fun JSONObject.str(key: String): String? = optString(key, "").ifBlank { null }?.takeIf { it != "null" }
