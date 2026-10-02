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

    /** Vrai une fois le mot de passe validé : on attend juste l'autorisation d'affichage. */
    private var passwordOk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        removeOldAiModel()
        showLastCrash()

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
        passwordOk = true
        proceed()
    }

    /** Vérifie l'autorisation d'affichage, puis démarre tout. Rappelé automatiquement au retour des réglages. */
    private fun proceed() {
        if (!passwordOk) return
        // La bulle, le « Bienvenue » et la détection s'affichent par-dessus les autres applis : autorisation obligatoire
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            return
        }
        // Si le démarrage d'un service échoue, on l'affiche au lieu de fermer l'appli sans rien dire
        val error = runCatching {
            startForegroundService(Intent(this, WelcomeService::class.java))
            BubbleService.start(this)
        }.exceptionOrNull()
        if (error != null) {
            showMessage("Erreur au démarrage", error.stackTraceToString())
            return
        }
        askToStayActive()
        finishAndRemoveTask()
    }

    /** Affiche le dernier plantage enregistré (service compris), pour pouvoir le corriger. */
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
            .setView(android.widget.ScrollView(this).apply { addView(text) })
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (passwordOk && Settings.canDrawOverlays(this)) {
            // Retour depuis l'écran d'autorisation : on continue
            proceed()
        } else if (passwords.isSet && Settings.canDrawOverlays(this)) {
            // L'appli est simplement rouverte et l'autorisation est là : on remet la bulle tout de suite
            BubbleService.start(this)
        }
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
