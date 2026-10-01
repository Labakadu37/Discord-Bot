package com.monimage.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.media.MediaPlayer
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.animation.doOnEnd

/** Écran de chargement : le RedSmile tourne en rond pendant que la musique joue. */
class LoadingScreen(
    private val context: Context,
    private val root: View,
    private val onFinished: () -> Unit,
) {
    private val logo: ImageView = root.findViewById(R.id.spinningLogo)
    private val bar: ProgressBar = root.findViewById(R.id.loadingBar)
    private val text: TextView = root.findViewById(R.id.loadingText)
    private var spin: ObjectAnimator? = null
    private var progress: ValueAnimator? = null
    private var player: MediaPlayer? = null
    private var running = false

    init {
        logo.outlineProvider = ViewOutlineProvider.BACKGROUND
        logo.clipToOutline = true
        root.setOnClickListener { finish() }
    }

    fun start() {
        stop()
        running = true
        root.alpha = 1f
        root.visibility = View.VISIBLE

        spin = ObjectAnimator.ofFloat(logo, View.ROTATION, 0f, 360f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        player = MediaPlayer.create(context, R.raw.zelenuyu)?.apply { start() }

        progress = ValueAnimator.ofInt(0, 100).apply {
            duration = DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val percent = it.animatedValue as Int
                bar.progress = percent
                text.text = context.getString(R.string.loading, percent)
            }
            doOnEnd { finish() }
            start()
        }
    }

    /** Fin du chargement : la musique baisse doucement et l'écran disparaît. */
    fun finish() {
        if (!running) return
        running = false
        progress?.cancel()
        val fadingPlayer = player
        player = null
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = FADE_MS
            addUpdateListener {
                val v = it.animatedValue as Float
                runCatching { fadingPlayer?.setVolume(v, v) }
                root.alpha = v
            }
            doOnEnd {
                fadingPlayer?.release()
                hide()
                onFinished()
            }
            start()
        }
    }

    /** Arrêt immédiat (écran éteint, appli quittée…). */
    fun stop() {
        running = false
        progress?.cancel()
        player?.release()
        player = null
        hide()
    }

    private fun hide() {
        spin?.cancel()
        spin = null
        logo.rotation = 0f
        root.visibility = View.GONE
    }

    companion object {
        private const val DURATION_MS = 7000L
        private const val FADE_MS = 700L
    }
}
