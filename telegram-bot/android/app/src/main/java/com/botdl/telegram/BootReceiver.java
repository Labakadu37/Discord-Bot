package com.botdl.telegram;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Relance le bot au démarrage du téléphone si l'option est cochée. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && BotPrefs.autostart(context)
                && BotPrefs.wantedRunning(context)
                && !BotPrefs.token(context).isEmpty()) {
            BotService.start(context);
        }
    }
}
