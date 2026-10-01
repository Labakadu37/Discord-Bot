package com.monimage.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
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
 * Écran d'accueil (launcher) qui affiche toutes les applis installées
 * avec des icônes générées à partir d'une image de la galerie.
 */
class MainActivity : ComponentActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val imageFile by lazy { File(filesDir, "background.jpg") }
    private lateinit var adapter: AppAdapter
    private lateinit var hint: TextView

    /** Incrémenté à chaque rafraîchissement pour ignorer les résultats périmés. */
    @Volatile private var generation = 0

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importImage(uri)
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hint = findViewById(R.id.hint)
        adapter = AppAdapter(::launch, ::openAppInfo)
        findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(this@MainActivity, COLUMNS)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
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
        } else {
            registerReceiver(packageReceiver, filter)
        }

        refresh()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
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

    private fun importImage(uri: Uri) {
        worker.execute {
            val ok = runCatching { IconStyler.importImage(contentResolver, uri, imageFile) }.getOrDefault(false)
            runOnUiThread {
                if (ok) refresh() else Toast.makeText(this, R.string.image_error, Toast.LENGTH_SHORT).show()
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

            val image = IconStyler.loadImage(imageFile)
            val entries = activities.mapIndexed { index, (label, info, resolveInfo) ->
                val icon = resolveInfo.loadIcon(packageManager)
                val styled = image?.let {
                    val src = IconStyler.sourceRect(it, index, activities.size, COLUMNS, mosaic)
                    IconStyler.render(it, src, icon, showLogo, size)
                }
                AppEntry(label, android.content.ComponentName(info.packageName, info.name), icon, styled)
            }
            image?.recycle()

            runOnUiThread {
                if (gen != generation || isDestroyed) return@runOnUiThread
                hint.visibility = if (image == null) View.VISIBLE else View.GONE
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
            .onFailure { Toast.makeText(this, R.string.launch_error, Toast.LENGTH_SHORT).show() }
    }

    private fun openAppInfo(app: AppEntry) {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.component.packageName, null))
        )
    }

    companion object {
        private const val COLUMNS = 4
        private const val KEY_LOGO = "show_logo"
        private const val KEY_MOSAIC = "mosaic"
    }
}
