package com.bothostinger.app.data

import android.content.Context

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
