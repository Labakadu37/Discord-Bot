package com.botdl.telegram;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

/** Fait tourner le bot Python en arrière-plan, avec une notification permanente. */
public class BotService extends Service {
    private static final String TAG = "BotService";
    private static final String CHANNEL_ID = "bot";
    private static final int NOTIFICATION_ID = 1;

    private Thread botThread;
    private PowerManager.WakeLock wakeLock;

    static void start(Context context) {
        Intent intent = new Intent(context, BotService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, BotService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        if (botThread != null) {
            return START_STICKY;
        }

        String token = BotPrefs.token(this);
        String adminIds = BotPrefs.adminIds(this);
        if (token.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BotTelegram:bot");
        wakeLock.acquire();

        String dataDir = getFilesDir().getAbsolutePath();
        botThread = new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                Python.getInstance().getModule("android_entry").callAttr("run", token, adminIds, dataDir);
            } catch (Throwable t) {
                Log.e(TAG, "Le bot a planté", t);
            }
            // Le bot s'est arrêté tout seul (token refusé, erreur...) : on ferme le service.
            stopSelf();
        }, "bot");
        botThread.start();
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Bot en marche", NotificationManager.IMPORTANCE_LOW));
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Bot Telegram en marche")
                .setContentText("Touchez pour ouvrir l'appli")
                .setContentIntent(openApp)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        // Python ne peut pas être arrêté proprement depuis Java : on termine le processus
        // du service (l'écran de l'appli vit dans un autre processus et n'est pas touché).
        Process.killProcess(Process.myPid());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
