package com.monimage.launcher

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

/**
 * Écran d'accueil (launcher) protégé par mot de passe qui affiche toutes les applis
 * installées avec des icônes RedSmile (ou une image choisie dans la galerie).
 */
class MainActivity : ComponentActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val imageFile by lazy { File(filesDir, "background.jpg") }
    private val passwords by lazy { PasswordStore(prefs) }
    private lateinit var adapter: AppAdapter
    private lateinit var lockScreen: View
    private lateinit var lockTitle: TextView
    private lateinit var passwordField: EditText
    private lateinit var confirmField: EditText
    private lateinit var loading: LoadingScreen
    private val shortcuts by lazy { HomeShortcuts(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var apps: List<AppEntry> = emptyList()

    /** Applis à poser une par une sur l'écran d'accueil du téléphone. */
    private val pinQueue = ArrayDeque<AppEntry>()
    private var pinToken = 0
    private var pinWaiting = false
    private var pausedDuringPin = false

    /** Incrémenté à chaque rafraîchissement pour ignorer les résultats périmés. */
    @Volatile private var generation = 0

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importImage(uri)
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    /** L'écran s'éteint : on reverrouille pour que personne d'autre n'accède aux applis. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = lock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        lockScreen = findViewById(R.id.lockScreen)
        lockTitle = findViewById(R.id.lockTitle)
        passwordField = findViewById(R.id.password)
        confirmField = findViewById(R.id.passwordConfirm)
        findViewById<Button>(R.id.btnUnlock).setOnClickListener { submitPassword() }
        val submitOnDone = TextView.OnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE).also { if (it) submitPassword() }
        }
        passwordField.setOnEditorActionListener(submitOnDone)
        confirmField.setOnEditorActionListener(submitOnDone)
        findViewById<Button>(R.id.btnLock).setOnClickListener { lock() }
        loading = LoadingScreen(this, findViewById(R.id.loadingScreen)) {}

        adapter = AppAdapter(::launch, ::showAppMenu)
        findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(this@MainActivity, COLUMNS)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.btnPin).setOnClickListener { choosePinnedApps() }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            imageFile.delete()
            refresh()
        }
        findViewById<Button>(R.id.btnHome).setOnClickListener {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        bindSwitch(R.id.switchLogo, KEY_LOGO, default = true)
        bindSwitch(R.id.switchMosaic, KEY_MOSAIC, default = false)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, RECEIVER_NOT_EXPORTED)
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }

        if (!unlocked) lock() else lockScreen.visibility = View.GONE
        refresh()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        unregisterReceiver(screenOffReceiver)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun bindSwitch(id: Int, key: String, default: Boolean) {
        findViewById<Switch>(id).apply {
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                refresh()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (pinWaiting) pausedDuringPin = true
    }

    override fun onResume() {
        super.onResume()
        // La fenêtre « Ajouter à l'écran d'accueil » vient de se fermer : on passe à l'appli suivante
        if (pinWaiting && pausedDuringPin) {
            pinWaiting = false
            handler.postDelayed({ pinNext() }, 300)
        }
    }

    override fun onStop() {
        super.onStop()
        loading.stop()
    }

    private fun lock() {
        unlocked = false
        loading.stop()
        val setup = !passwords.isSet
        lockTitle.setText(if (setup) R.string.lock_title_setup else R.string.lock_title)
        confirmField.visibility = if (setup) View.VISIBLE else View.GONE
        passwordField.text.clear()
        confirmField.text.clear()
        lockScreen.visibility = View.VISIBLE
    }

    private fun submitPassword() {
        val password = passwordField.text.toString()
        if (!passwords.isSet) {
            when {
                password.length < 4 -> return toast(R.string.password_too_short)
                password != confirmField.text.toString() -> return toast(R.string.password_mismatch)
                else -> passwords.set(password)
            }
        } else if (!passwords.check(password)) {
            passwordField.text.clear()
            return toast(R.string.wrong_password)
        }
        passwordField.text.clear()
        confirmField.text.clear()
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(passwordField.windowToken, 0)
        lockScreen.visibility = View.GONE
        unlocked = true
        loading.start()
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun importImage(uri: Uri) {
        worker.execute {
            val ok = runCatching { IconStyler.importImage(contentResolver, uri, imageFile) }.getOrDefault(false)
            runOnUiThread {
                if (ok) refresh() else toast(R.string.image_error)
            }
        }
    }

    /** Recharge la liste des applis et régénère toutes les icônes en arrière-plan. */
    private fun refresh() {
        val gen = ++generation
        val showLogo = prefs.getBoolean(KEY_LOGO, true)
        val mosaic = prefs.getBoolean(KEY_MOSAIC, false)
        val size = (resources.displayMetrics.density * 60).toInt().coerceAtLeast(96)

        worker.execute {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = packageManager.queryIntentActivities(launcherIntent, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { Triple(it.loadLabel(packageManager).toString(), it.activityInfo, it) }
                .sortedBy { it.first.lowercase() }

            Wallpaper.applyIfNeeded(this, imageFile)
            val image = IconStyler.loadImage(resources, imageFile)
            val entries = activities.mapIndexed { index, (label, info, resolveInfo) ->
                val icon = resolveInfo.loadIcon(packageManager)
                val src = IconStyler.sourceRect(image, index, activities.size, COLUMNS, mosaic)
                val styled = IconStyler.render(image, src, icon, showLogo, size)
                AppEntry(label, android.content.ComponentName(info.packageName, info.name), icon, styled)
            }
            shortcuts.updatePinned(this, entries, image, showLogo)
            image.recycle()

            runOnUiThread {
                if (gen != generation || isDestroyed) return@runOnUiThread
                apps = entries
                adapter.submit(entries)
            }
        }
    }

    private fun launch(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(intent) }
            .onFailure { toast(R.string.launch_error) }
    }

    private fun showAppMenu(app: AppEntry, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.menu_pin).setOnMenuItemClickListener { startPinning(listOf(app)); true }
            menu.add(R.string.menu_info).setOnMenuItemClickListener { openAppInfo(app); true }
            show()
        }
    }

    private fun choosePinnedApps() {
        if (apps.isEmpty()) return
        val checked = BooleanArray(apps.size)
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_title)
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.pin_selected) { _, _ -> startPinning(apps.filterIndexed { i, _ -> checked[i] }) }
            .setNeutralButton(R.string.pin_all) { _, _ -> startPinning(apps) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startPinning(selection: List<AppEntry>) {
        if (selection.isEmpty()) return
        // Les raccourcis vont à l'appli d'accueil par défaut : ça doit être celle du téléphone, pas RedSmile
        val home = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        if (home?.activityInfo?.packageName == packageName) {
            Toast.makeText(this, R.string.pin_need_samsung_home, Toast.LENGTH_LONG).show()
            return startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        if (!shortcuts.isSupported) return toast(R.string.pin_unsupported)
        pinQueue.clear()
        pinQueue.addAll(selection)
        if (selection.size > 1) {
            Toast.makeText(this, getString(R.string.pin_start, selection.size), Toast.LENGTH_LONG).show()
        }
        pinNext()
    }

    /** Demande au launcher d'ajouter le raccourci suivant de la file. */
    private fun pinNext() {
        val app = pinQueue.removeFirstOrNull()
        if (app == null) {
            pinWaiting = false
            return toast(R.string.pin_done)
        }
        val token = ++pinToken
        pinWaiting = true
        pausedDuringPin = false
        val showLogo = prefs.getBoolean(KEY_LOGO, true)
        worker.execute {
            val image = IconStyler.loadImage(resources, imageFile)
            val ok = shortcuts.requestPin(this, app, image, showLogo)
            image.recycle()
            runOnUiThread {
                if (!ok) {
                    pinWaiting = false
                    pinQueue.clear()
                    return@runOnUiThread toast(R.string.pin_unsupported)
                }
                // Certains launchers ajoutent le raccourci sans ouvrir de fenêtre : on n'attend pas
                handler.postDelayed({
                    if (token == pinToken && pinWaiting && !pausedDuringPin) {
                        pinWaiting = false
                        pinNext()
                    }
                }, 2500)
            }
        }
    }

    private fun openAppInfo(app: AppEntry) {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.component.packageName, null))
        )
    }

    companion object {
        /** Reste vrai jusqu'à l'extinction de l'écran, même si Android recrée l'écran. */
        private var unlocked = false
        private const val COLUMNS = 4
        private const val KEY_LOGO = "show_logo"
        private const val KEY_MOSAIC = "mosaic"
    }
}
