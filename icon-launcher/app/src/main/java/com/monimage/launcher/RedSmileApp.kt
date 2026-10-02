package com.monimage.launcher

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Enregistre tout plantage (dans l'appli ou dans ses services) pour pouvoir l'afficher à la prochaine
 * ouverture. Ça permet de retrouver la cause d'un problème sans câble ni ordinateur.
 */
class RedSmileApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                prefs.edit().putString(KEY_CRASH, "${Date()}\n[$thread]\n$trace").apply()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val KEY_CRASH = "last_crash"
    }
}
