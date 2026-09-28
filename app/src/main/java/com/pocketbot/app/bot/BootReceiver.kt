package com.pocketbot.app.bot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pocketbot.app.data.ConfigStore

/** Relance le bot au démarrage du téléphone si l'option est activée. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = ConfigStore(context).loadSettings()
        if (settings.autoStart && settings.token.isNotBlank()) {
            BotService.send(context, BotService.ACTION_START)
        }
    }
}
