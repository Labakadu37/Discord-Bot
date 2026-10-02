package com.monimage.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/** Lancement direct : plus de mot de passe, on démarre tout de suite. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        removeOldAiModel()
        showLastCrash()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        proceed()
    }

    private fun proceed() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            return
        }
        val error = runCatching {
            startForegroundService(Intent(this, WelcomeService::class.java))
            startForegroundService(Intent(this, PopupService::class.java))
            BubbleService.start(this)
        }.exceptionOrNull()
        if (error != null) {
            showMessage("Erreur au démarrage", error.stackTraceToString())
            return
        }
        askToStayActive()
        finishAndRemoveTask()
    }

    private fun showLastCrash() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val crash = prefs.getString(RedSmileApp.KEY_CRASH, null) ?: return
        prefs.edit().remove(RedSmileApp.KEY_CRASH).apply()
        showMessage("RedSmile a planté", crash)
    }

    private fun showMessage(title: String, body: String) {
        val text = TextView(this).apply {
            setText(body)
            setTextIsSelectable(true)
            setPadding(48, 32, 48, 32)
            textSize = 12f
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            proceed()
        }
    }

    private fun removeOldAiModel() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val id = prefs.getLong("model_download_id", -1L)
        if (id >= 0) {
            runCatching { getSystemService(android.app.DownloadManager::class.java).remove(id) }
            prefs.edit().remove("model_download_id").apply()
        }
        getExternalFilesDir(null)?.listFiles { f -> f.name.startsWith("redsmile-ai.task") }?.forEach { it.delete() }
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun askToStayActive() {
        val power = getSystemService(android.os.PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
