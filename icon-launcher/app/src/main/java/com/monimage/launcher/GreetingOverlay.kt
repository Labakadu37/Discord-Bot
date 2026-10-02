package com.monimage.launcher

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import java.util.Locale

/** Carte « RedSmile » qui descend du haut de l'écran, écrit le message et le dit à voix haute. */
class GreetingOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var card: View? = null
    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.FRANCE)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    fun show(message: String, speak: Boolean) {
        dismiss(animated = false)
        val view = LayoutInflater.from(ContextThemeWrapper(context, R.style.Theme_RedSmile))
            .inflate(R.layout.overlay_greeting, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Le reste de l'écran reste utilisable pendant que la carte est affichée
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching { windowManager.addView(view, params) }.onFailure { return }
        card = view
        view.setOnClickListener { dismiss(animated = true) }

        val avatar = view.findViewById<View>(R.id.greetingAvatar)
        avatar.outlineProvider = ViewOutlineProvider.BACKGROUND
        avatar.clipToOutline = true
        ObjectAnimator.ofFloat(avatar, View.ROTATION, -360f, 0f).apply { duration = 900; start() }

        // Glisse depuis le haut
        val panel = view.findViewById<View>(R.id.greetingCard)
        panel.translationY = -400f * context.resources.displayMetrics.density
        panel.animate().translationY(0f).setDuration(650).setInterpolator(OvershootInterpolator(1.2f)).start()

        // Message tapé lettre par lettre
        val text = view.findViewById<TextView>(R.id.greetingText)
        message.indices.forEach { i ->
            handler.postDelayed({ if (card === view) text.text = message.substring(0, i + 1) }, 400L + i * 35L)
        }

        if (speak && ttsReady && canMakeSound()) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "greeting")
        }
        handler.postDelayed({ dismiss(animated = true) }, 400L + message.length * 35L + VISIBLE_MS)
    }

    /** Pas de voix si le téléphone est en silencieux ou en vibreur (en cours par exemple). */
    private fun canMakeSound(): Boolean =
        context.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_NORMAL

    fun dismiss(animated: Boolean) {
        val view = card ?: return
        card = null
        handler.removeCallbacksAndMessages(null)
        val remove = { runCatching { windowManager.removeView(view) } }
        if (!animated) {
            remove()
            return
        }
        view.findViewById<View>(R.id.greetingCard).animate()
            .translationY(-400f * context.resources.displayMetrics.density)
            .setDuration(350)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { remove() }
            .start()
    }

    fun release() {
        dismiss(animated = false)
        tts.shutdown()
    }

    companion object {
        private const val VISIBLE_MS = 5000L
    }
}
