package com.bothostinger.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Réglages de l'app (pas du bot sur Discord).
 *
 * @property status statut Discord : "online", "idle", "dnd" ou "invisible".
 * @property presenceMode "playing" (Joue à BotHostinger) ou "streaming" (Stream BotHostinger).
 * @property disabledModules systèmes désactivés par l'utilisateur (tous actifs par défaut).
 */
data class BotSettings(
    val token: String = "",
    val status: String = "online",
    val presenceMode: String = "playing",
    val autoStart: Boolean = false,
    val disabledModules: Set<String> = emptySet(),
)

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bothostinger", Context.MODE_PRIVATE)

    fun loadStudio(): Studio {
        val raw = prefs.getString("studio", null) ?: return Studio.examples()
        return runCatching { Studio.fromJson(JSONObject(raw)) }.getOrDefault(Studio())
    }

    fun saveStudio(studio: Studio) {
        prefs.edit().putString("studio", studio.toJson().toString()).apply()
    }

    /** Sauvegarde complète (sans le token) pour la restaurer plus tard ou sur un autre téléphone. */
    fun exportBackup(): String = JSONObject()
        .put("app", "BotHostinger")
        .put("version", 1)
        .put("settings", load().let { s ->
            JSONObject()
                .put("status", s.status)
                .put("presenceMode", s.presenceMode)
                .put("autoStart", s.autoStart)
                .put("disabledModules", JSONArray(s.disabledModules.toList()))
        })
        .put("studio", loadStudio().toJson())
        .toString(2)

    /** Restaure une sauvegarde ; renvoie false si le fichier n'est pas valide. */
    fun importBackup(text: String): Boolean = runCatching {
        val o = JSONObject(text)
        require(o.optString("app") == "BotHostinger")
        o.optJSONObject("settings")?.let { st ->
            val current = load()
            save(
                current.copy(
                    status = st.optString("status", current.status),
                    presenceMode = st.optString("presenceMode", current.presenceMode),
                    autoStart = st.optBoolean("autoStart", current.autoStart),
                    disabledModules = st.optJSONArray("disabledModules")?.strings()?.toSet() ?: current.disabledModules,
                )
            )
        }
        o.optJSONObject("studio")?.let { saveStudio(Studio.fromJson(it)) }
        true
    }.getOrDefault(false)

    fun load(): BotSettings = BotSettings(
        token = prefs.getString("token", "") ?: "",
        status = prefs.getString("status", "online") ?: "online",
        presenceMode = prefs.getString("presenceMode", "playing") ?: "playing",
        autoStart = prefs.getBoolean("autoStart", false),
        disabledModules = prefs.getStringSet("disabledModules", emptySet())?.toSet() ?: emptySet(),
    )

    fun save(s: BotSettings) {
        prefs.edit()
            .putString("token", s.token.trim())
            .putString("status", s.status)
            .putString("presenceMode", s.presenceMode)
            .putBoolean("autoStart", s.autoStart)
            .putStringSet("disabledModules", s.disabledModules)
            .apply()
    }
}
