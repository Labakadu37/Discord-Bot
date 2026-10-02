package com.monimage.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Après un redémarrage : remet la bulle et dit bonjour. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) BubbleService.startAfterBoot(context)
    }
}
