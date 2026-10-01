package com.monimage.launcher

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Discussion avec l'IA RedSmile (ouverte en touchant 2 fois la bulle). */
class AiActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var downloadButton: Button
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai)
        status = findViewById(R.id.aiStatus)
        progress = findViewById(R.id.aiProgress)
        downloadButton = findViewById(R.id.btnDownload)
        chat = findViewById(R.id.chat)
        scroll = findViewById(R.id.chatScroll)
        input = findViewById(R.id.aiInput)
        send = findViewById(R.id.btnSend)

        downloadButton.setOnClickListener {
            AiEngine.startDownload(this)
            refreshState()
        }
        send.setOnClickListener { sendMessage() }
        input.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_SEND).also { if (it) sendMessage() }
        }
        findViewById<Button>(R.id.btnNew).setOnClickListener {
            if (busy) return@setOnClickListener
            AiEngine.newConversation()
            chat.removeAllViews()
        }

        AiEngine.messages.forEach { addBubble(it) }
        refreshState()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Affiche l'état : à télécharger, en téléchargement, en chargement ou prêt. */
    private fun refreshState() {
        handler.removeCallbacksAndMessages(null)
        val download = AiEngine.downloadProgress(this)
        when {
            download != null && download >= 0 -> {
                showStatus(getString(R.string.ai_downloading, download), showProgress = true, canDownload = false)
                progress.isIndeterminate = false
                progress.progress = download
                handler.postDelayed({ refreshState() }, 1000)
            }
            !AiEngine.isModelReady(this) -> {
                val msg = if (download == -1) R.string.ai_download_failed else R.string.ai_need_download
                showStatus(getString(msg), showProgress = false, canDownload = true)
            }
            else -> {
                showStatus(getString(R.string.ai_loading), showProgress = true, canDownload = false)
                progress.isIndeterminate = true
                AiEngine.load(this) { error ->
                    runOnUiThread {
                        if (error == null) {
                            status.visibility = View.GONE
                            progress.visibility = View.GONE
                            setInputEnabled(true)
                        } else {
                            showStatus(getString(R.string.ai_load_error, error.message), false, canDownload = true)
                        }
                    }
                }
            }
        }
    }

    private fun showStatus(text: String, showProgress: Boolean, canDownload: Boolean) {
        status.visibility = View.VISIBLE
        status.text = text
        progress.visibility = if (showProgress) View.VISIBLE else View.GONE
        downloadButton.visibility = if (canDownload) View.VISIBLE else View.GONE
        setInputEnabled(false)
    }

    private fun setInputEnabled(enabled: Boolean) {
        input.isEnabled = enabled
        send.isEnabled = enabled && !busy
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy || !input.isEnabled) return
        input.text.clear()
        busy = true
        send.isEnabled = false

        val question = AiEngine.Message(AiEngine.Role.USER, text).also { AiEngine.messages += it }
        addBubble(question)
        val answer = AiEngine.Message(AiEngine.Role.AI, "…").also { AiEngine.messages += it }
        val answerView = addBubble(answer)

        AiEngine.ask(
            text,
            onPartial = { partial ->
                runOnUiThread {
                    answer.text = partial
                    answerView.text = partial
                    scrollToEnd()
                }
            },
            onDone = { error ->
                runOnUiThread {
                    if (error != null) {
                        answer.text = getString(R.string.ai_error)
                        answerView.text = answer.text
                    }
                    busy = false
                    send.isEnabled = input.isEnabled
                }
            },
        )
    }

    private fun addBubble(message: AiEngine.Message): TextView {
        val mine = message.role == AiEngine.Role.USER
        val pad = (12 * resources.displayMetrics.density).toInt()
        val view = TextView(this).apply {
            text = message.text
            textSize = 16f
            setTextIsSelectable(true)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(pad, pad * 2 / 3, pad, pad * 2 / 3)
            setBackgroundResource(if (mine) R.drawable.bubble_me else R.drawable.bubble_ai)
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = pad / 2
            if (mine) leftMargin = pad * 4 else rightMargin = pad * 4
        }
        chat.addView(view, params)
        scrollToEnd()
        return view
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
}
