package com.monimage.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Remet la bulle IA après un redémarrage du téléphone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) BubbleService.start(context)
    }
}
