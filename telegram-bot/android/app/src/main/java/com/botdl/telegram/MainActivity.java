package com.botdl.telegram;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MainActivity extends Activity {
    private static final long REFRESH_MS = 2000;
    private static final int LOG_TAIL_BYTES = 6000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView logView;
    private EditText tokenInput;
    private EditText adminInput;
    private CheckBox autostart;
    private Button startButton;
    private Button stopButton;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        logView = findViewById(R.id.log);
        tokenInput = findViewById(R.id.token);
        adminInput = findViewById(R.id.admin);
        autostart = findViewById(R.id.autostart);
        startButton = findViewById(R.id.start);
        stopButton = findViewById(R.id.stop);

        tokenInput.setText(BotPrefs.token(this));
        adminInput.setText(BotPrefs.adminIds(this));
        autostart.setChecked(BotPrefs.autostart(this));

        startButton.setOnClickListener(v -> startBot());
        stopButton.setOnClickListener(v -> stopBot());
        findViewById(R.id.battery).setOnClickListener(v -> askIgnoreBatteryOptimizations());
        autostart.setOnCheckedChangeListener((b, checked) -> saveSettings());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private void saveSettings() {
        BotPrefs.save(this, tokenInput.getText().toString().trim(),
                adminInput.getText().toString().trim(), autostart.isChecked());
    }

    private void startBot() {
        String token = tokenInput.getText().toString().trim();
        if (!BotPrefs.TOKEN_PATTERN.matcher(token).matches()) {
            tokenInput.setError("Token invalide : copie-le depuis @BotFather");
            return;
        }
        saveSettings();
        BotPrefs.setWantedRunning(this, true);
        BotService.start(this);
        Toast.makeText(this, "Démarrage du bot…", Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::refresh, 500);
    }

    private void stopBot() {
        BotPrefs.setWantedRunning(this, false);
        BotService.stop(this);
        handler.postDelayed(this::refresh, 500);
    }

    private void askIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "C'est déjà autorisé ✅", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private boolean isBotRunning() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null) {
            return false;
        }
        String botProcess = getPackageName() + ":bot";
        for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (botProcess.equals(p.processName)) {
                return true;
            }
        }
        return false;
    }

    private void refresh() {
        boolean running = isBotRunning();
        status.setText(running ? "● Le bot est en marche" : "○ Le bot est arrêté");
        status.setTextColor(running ? Color.rgb(0, 140, 60) : Color.GRAY);
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        logView.setText(readLogTail(BotPrefs.logFile(this)));
    }

    private static String readLogTail(File file) {
        if (!file.exists()) {
            return "(rien pour l'instant)";
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, raf.length() - LOG_TAIL_BYTES);
            byte[] bytes = new byte[(int) (raf.length() - start)];
            raf.seek(start);
            raf.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (start > 0) {
                int firstLine = text.indexOf('\n');
                text = firstLine >= 0 ? text.substring(firstLine + 1) : text;
            }
            return text;
        } catch (Exception e) {
            return "Impossible de lire le journal : " + e.getMessage();
        }
    }
}
