package com.botdl.telegram;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.regex.Pattern;

/** Réglages partagés entre l'écran et le service du bot. */
final class BotPrefs {
    static final Pattern TOKEN_PATTERN = Pattern.compile("^\\d{5,}:[\\w-]{30,}$");

    private static final String NAME = "bot";
    private static final String TOKEN = "token";
    private static final String ADMIN_IDS = "admin_ids";
    private static final String AUTOSTART = "autostart";
    private static final String WANTED_RUNNING = "wanted_running";

    private BotPrefs() {}

    private static SharedPreferences prefs(Context context) {
        // MODE_MULTI_PROCESS est déprécié mais le service vit dans un autre processus :
        // on relit le fichier à chaque fois pour avoir les dernières valeurs.
        return context.getSharedPreferences(NAME, Context.MODE_MULTI_PROCESS);
    }

    static String token(Context c) { return prefs(c).getString(TOKEN, ""); }
    static String adminIds(Context c) { return prefs(c).getString(ADMIN_IDS, ""); }
    static boolean autostart(Context c) { return prefs(c).getBoolean(AUTOSTART, false); }
    static boolean wantedRunning(Context c) { return prefs(c).getBoolean(WANTED_RUNNING, false); }

    static void save(Context c, String token, String adminIds, boolean autostart) {
        prefs(c).edit()
                .putString(TOKEN, token)
                .putString(ADMIN_IDS, adminIds)
                .putBoolean(AUTOSTART, autostart)
                .commit();
    }

    static void setWantedRunning(Context c, boolean running) {
        prefs(c).edit().putBoolean(WANTED_RUNNING, running).commit();
    }

    static File logFile(Context c) {
        return new File(c.getFilesDir(), "bot.log");
    }
}
