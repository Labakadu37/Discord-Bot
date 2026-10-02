package com.monimage.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/** Écran du mot de passe. Une fois le bon mot de passe entré, l'appli se ferme et l'accueil RedSmile démarre. */
class MainActivity : ComponentActivity() {

    private val passwords by lazy { PasswordStore(getSharedPreferences("settings", Context.MODE_PRIVATE)) }
    private lateinit var passwordField: EditText
    private lateinit var confirmField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        removeOldAiModel()

        passwordField = findViewById(R.id.password)
        confirmField = findViewById(R.id.passwordConfirm)
        val setup = !passwords.isSet
        findViewById<TextView>(R.id.lockTitle).setText(if (setup) R.string.lock_title_setup else R.string.lock_title)
        confirmField.visibility = if (setup) View.VISIBLE else View.GONE

        findViewById<Button>(R.id.btnUnlock).setOnClickListener { submit() }
        val submitOnDone = TextView.OnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE).also { if (it) submit() }
        }
        passwordField.setOnEditorActionListener(submitOnDone)
        confirmField.setOnEditorActionListener(submitOnDone)
        passwordField.requestFocus()

        // Pour voir la notification « Bienvenue » (la musique marche même sans)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private fun submit() {
        val password = passwordField.text.toString()
        if (!passwords.isSet) {
            when {
                password.length < 4 -> return toast(R.string.password_too_short)
                password != confirmField.text.toString() -> return toast(R.string.password_mismatch)
                else -> {
                    passwords.set(password)
                    confirmField.visibility = View.GONE
                    findViewById<TextView>(R.id.lockTitle).setText(R.string.lock_title)
                }
            }
        } else if (!passwords.check(password)) {
            passwordField.text.clear()
            return toast(R.string.wrong_password)
        }

        // L'overlay par-dessus l'écran d'accueil a besoin d'une autorisation (une seule fois)
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        startForegroundService(Intent(this, WelcomeService::class.java))
        BubbleService.start(this)
        askToStayActive()
        finishAndRemoveTask()
    }

    /** L'ancienne version téléchargeait une IA de 1,6 Go : on libère la place. */
    private fun removeOldAiModel() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val id = prefs.getLong("model_download_id", -1L)
        if (id >= 0) {
            runCatching { getSystemService(android.app.DownloadManager::class.java).remove(id) }
            prefs.edit().remove("model_download_id").apply()
        }
        getExternalFilesDir(null)?.listFiles { f -> f.name.startsWith("redsmile-ai.task") }?.forEach { it.delete() }
    }

    /** Sans ça, l'économie de batterie de Samsung peut endormir RedSmile et elle ne dit plus bonjour. */
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

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
