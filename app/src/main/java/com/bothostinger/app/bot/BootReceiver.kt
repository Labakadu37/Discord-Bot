package com.bothostinger.app.bot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bothostinger.app.data.SettingsStore

/** Relance le bot au démarrage du téléphone si l'option est activée. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = SettingsStore(context).load()
        if (settings.autoStart && settings.token.isNotBlank()) {
            BotService.send(context, BotService.ACTION_START)
        }
    }
}
