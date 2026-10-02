package com.monimage.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlin.concurrent.thread

/** Boîte à outils RedSmile (toucher 2 fois la bulle) : infos du téléphone et terminal. */
class ToolsActivity : ComponentActivity() {

    private lateinit var infoPage: View
    private lateinit var terminalPage: View
    private lateinit var infoList: LinearLayout
    private lateinit var output: TextView
    private lateinit var outputScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var detectionButton: Button
    private val captureConsent = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            DetectionService.start(this, result.resultCode, data)
            detectionButton.postDelayed({ showDetectionState() }, 500)
            moveTaskToBack(true) // on laisse l'écran libre pour voir les cadres
        } else {
            Toast.makeText(this, R.string.detect_refused, Toast.LENGTH_LONG).show()
        }
    }
    private var shell: Shell? = null
    private var linuxMode = false
    private var installing = false
    private val log = SpannableStringBuilder()
    private val history = mutableListOf<String>()
    private var historyIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools)
        infoPage = findViewById(R.id.infoPage)
        terminalPage = findViewById(R.id.terminalPage)
        infoList = findViewById(R.id.infoList)
        output = findViewById(R.id.terminalOutput)
        outputScroll = findViewById(R.id.terminalScroll)
        input = findViewById(R.id.terminalInput)

        findViewById<Button>(R.id.tabInfo).setOnClickListener { showTab(info = true) }
        findViewById<Button>(R.id.tabTerminal).setOnClickListener { showTab(info = false) }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { loadInfo() }
        findViewById<Button>(R.id.btnTermux).setOnClickListener { openTermux() }
        detectionButton = findViewById(R.id.btnDetection)
        detectionButton.setOnClickListener { toggleDetection() }
        findViewById<Button>(R.id.btnGreetingTest).setOnClickListener { BubbleService.testGreeting(this) }
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val voice = findViewById<Button>(R.id.btnVoice)
        val showVoice = {
            voice.setText(
                if (prefs.getBoolean(BubbleService.KEY_VOICE, true)) R.string.greeting_voice_on else R.string.greeting_voice_off
            )
        }
        showVoice()
        voice.setOnClickListener {
            prefs.edit().putBoolean(BubbleService.KEY_VOICE, !prefs.getBoolean(BubbleService.KEY_VOICE, true)).apply()
            showVoice()
        }

        findViewById<Button>(R.id.modeAndroid).setOnClickListener { switchMode(linux = false) }
        findViewById<Button>(R.id.modeLinux).setOnClickListener { switchMode(linux = true) }
        switchMode(linux = LinuxEnv.isInstalled(this))

        findViewById<Button>(R.id.btnRun).setOnClickListener { runInput() }
        input.setOnEditorActionListener { _, action, event ->
            val enter = action == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            enter.also { if (it) runInput() }
        }
        findViewById<Button>(R.id.btnUp).setOnClickListener { browseHistory(-1) }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            shell?.start()
            append(getString(R.string.terminal_stopped), ACCENT)
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            log.clear()
            output.text = ""
        }

        showTab(info = true)
        loadInfo()
    }

    override fun onResume() {
        super.onResume()
        showDetectionState()
    }

    override fun onDestroy() {
        shell?.stop()
        super.onDestroy()
    }

    /** Détection à l'écran : Android demande l'accord pour la capture à chaque démarrage. */
    private fun toggleDetection() {
        if (DetectionService.running) {
            DetectionService.stop(this)
            detectionButton.postDelayed({ showDetectionState() }, 300)
        } else {
            val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            captureConsent.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun showDetectionState() {
        detectionButton.setText(if (DetectionService.running) R.string.detect_stop_button else R.string.detect_start)
    }

    /** Terminal Android (commandes du téléphone) ou Linux intégré (Alpine : python, pip, git…). */
    private fun switchMode(linux: Boolean) {
        linuxMode = linux
        findViewById<Button>(R.id.modeAndroid).alpha = if (linux) 0.5f else 1f
        findViewById<Button>(R.id.modeLinux).alpha = if (linux) 1f else 0.5f
        shell?.stop()
        shell = null
        log.clear()
        output.text = ""
        when {
            !linux -> {
                startShell(Shell(filesDir, onOutput = ::onShellOutput))
                append(getString(R.string.terminal_welcome), ACCENT)
                setQuickCommands(ANDROID_COMMANDS)
            }
            LinuxEnv.isInstalled(this) -> startLinux()
            else -> {
                append(getString(R.string.linux_intro), ACCENT)
                setQuickCommands(emptyList())
                addQuickButton(getString(R.string.linux_install)) { installLinux() }
            }
        }
    }

    private fun startLinux() {
        startShell(Shell(filesDir, LinuxEnv.shellCommand(this), LinuxEnv.shellEnv(this), ::onShellOutput))
        setQuickCommands(LINUX_COMMANDS)
    }

    private fun startShell(newShell: Shell) {
        shell = newShell
        newShell.start()
    }

    private fun onShellOutput(text: String) = runOnUiThread { append(text, OUTPUT_COLOR) }

    private fun installLinux() {
        if (installing) return
        installing = true
        setQuickCommands(emptyList())
        thread {
            val error = runCatching {
                LinuxEnv.install(this) { step -> runOnUiThread { append("$step\n", ACCENT) } }
            }.exceptionOrNull()
            runOnUiThread {
                installing = false
                if (isDestroyed || !linuxMode) return@runOnUiThread
                if (error == null) {
                    append(getString(R.string.linux_ready), ACCENT)
                    startLinux()
                } else {
                    LinuxEnv.uninstall(this)
                    append(getString(R.string.linux_failed, error.message ?: error.javaClass.simpleName), ACCENT)
                    addQuickButton(getString(R.string.linux_install)) { installLinux() }
                }
            }
        }
    }

    private fun setQuickCommands(commands: List<Pair<String, String>>) {
        findViewById<LinearLayout>(R.id.quickCommands).removeAllViews()
        commands.forEach { (label, command) -> addQuickButton(label) { execute(command) } }
    }

    private fun addQuickButton(label: String, action: () -> Unit) {
        val button = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            setTextColor(ACCENT)
            setOnClickListener { action() }
        }
        findViewById<LinearLayout>(R.id.quickCommands).addView(button)
    }

    /** Ouvre Termux (vrai Linux avec Python…), ou sa page F-Droid s'il n'est pas installé. */
    private fun openTermux() {
        val launch = packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
        if (launch != null) {
            startActivity(launch)
            return
        }
        Toast.makeText(this, R.string.termux_missing, Toast.LENGTH_LONG).show()
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_DOWNLOAD))) }
    }

    private fun showTab(info: Boolean) {
        infoPage.visibility = if (info) View.VISIBLE else View.GONE
        terminalPage.visibility = if (info) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.tabInfo).alpha = if (info) 1f else 0.5f
        findViewById<Button>(R.id.tabTerminal).alpha = if (info) 0.5f else 1f
        if (!info) input.requestFocus()
    }

    // --- Infos ---

    private fun loadInfo() {
        infoList.removeAllViews()
        val publicIp = addInfoRow(DeviceInfo.Row("IP publique", "…"))
        DeviceInfo.collect(this).forEach { addInfoRow(it) }
        thread {
            val ip = DeviceInfo.publicIp()
            runOnUiThread { publicIp.text = ip }
        }
    }

    /** Ajoute une ligne ; toucher la valeur la copie. Renvoie la vue de la valeur. */
    private fun addInfoRow(row: DeviceInfo.Row): TextView {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val title = TextView(this).apply {
            text = row.title
            setTextColor(ACCENT)
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }
        val value = TextView(this).apply {
            text = row.value
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setPadding(0, pad / 4, 0, pad / 2)
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(row.title, text))
                Toast.makeText(this@ToolsActivity, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
        infoList.addView(title)
        infoList.addView(value)
        return value
    }

    // --- Terminal ---

    private fun runInput() {
        val command = input.text.toString()
        if (command.isBlank()) return
        input.text.clear()
        execute(command)
    }

    private fun execute(command: String) {
        if (history.lastOrNull() != command) history += command
        historyIndex = history.size
        if (command.trim() == "clear") {
            log.clear()
            output.text = ""
            return
        }
        val current = shell ?: return
        append("\n$ $command\n", PROMPT_COLOR)
        current.run(command)
    }

    private fun browseHistory(step: Int) {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + step).coerceIn(0, history.size - 1)
        input.setText(history[historyIndex])
        input.setSelection(input.text.length)
    }

    private fun append(text: String, color: Int) {
        val start = log.length
        log.append(text)
        log.setSpan(ForegroundColorSpan(color), start, log.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // On garde seulement la fin pour rester fluide
        if (log.length > MAX_LOG) log.delete(0, log.length - MAX_LOG)
        output.text = log
        outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val MAX_LOG = 60_000
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_DOWNLOAD = "https://f-droid.org/packages/com.termux/"
        private const val ACCENT = 0xFFE53935.toInt()
        private const val PROMPT_COLOR = 0xFFFF8A80.toInt()
        private const val OUTPUT_COLOR = 0xFFE0E0E0.toInt()

        private val LINUX_COMMANDS = listOf(
            "installer python" to "apk add python3 py3-pip && python3 --version",
            "python" to "python3",
            "pip list" to "pip list",
            "git" to "apk add git && git --version",
            "mise à jour" to "apk update && apk upgrade",
            "système" to "head -2 /etc/os-release; uname -m",
            "ls" to "ls -la",
        )

        private val ANDROID_COMMANDS = listOf(
            "ip" to "ip addr | grep inet",
            "ping" to "ping -c 4 8.8.8.8",
            "dns" to "getprop | grep dns",
            "modèle" to "getprop ro.product.model; getprop ro.build.version.release",
            "batterie" to "cat /sys/class/power_supply/battery/capacity 2>/dev/null || echo 'non accessible'",
            "stockage" to "df -h /data /sdcard 2>/dev/null",
            "processus" to "ps -A 2>/dev/null | head -20 || ps | head -20",
            "uptime" to "uptime",
            "ls" to "ls -la",
        )
    }
}
