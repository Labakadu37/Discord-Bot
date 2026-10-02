package com.monimage.launcher

import android.content.res.Resources
import org.json.JSONObject

/**
 * Carte rythmique de la musique (calculée à partir du morceau) : temps de chaque beat,
 * beats forts, et niveau de basse dans le temps pour repérer les drops et les breaks.
 */
class MusicTimeline(
    val durationMs: Int,
    val beatsMs: IntArray,
    val strong: BooleanArray,
    private val bassStepMs: Int,
    private val bass: FloatArray,
) {
    enum class Phase { INTRO, DROP, BREAK, OUTRO }

    /** Niveau de basse 0..1 à l'instant [ms] (interpolé). */
    fun bassAt(ms: Int): Float {
        val pos = ms.toFloat() / bassStepMs
        val i = pos.toInt().coerceIn(0, bass.size - 1)
        val j = (i + 1).coerceAtMost(bass.size - 1)
        val f = (pos - i).coerceIn(0f, 1f)
        return bass[i] * (1 - f) + bass[j] * f
    }

    private val firstDropMs: Int = run {
        var ms = 0
        while (ms < durationMs && bassAt(ms) < DROP_LEVEL) ms += bassStepMs
        ms
    }

    fun phaseAt(ms: Int): Phase = when {
        ms < firstDropMs -> Phase.INTRO
        ms >= durationMs - OUTRO_MS -> Phase.OUTRO
        bassAt(ms) < BREAK_LEVEL -> Phase.BREAK
        else -> Phase.DROP
    }

    /** Début de la fin : le logo part se poser dans le fond d'écran. */
    val landingStartMs: Int get() = durationMs - OUTRO_MS

    companion object {
        private const val DROP_LEVEL = 0.55f
        private const val BREAK_LEVEL = 0.45f
        private const val OUTRO_MS = 4200

        fun load(resources: Resources, rawId: Int): MusicTimeline {
            val json = JSONObject(resources.openRawResource(rawId).bufferedReader().use { it.readText() })
            val beats = json.getJSONArray("beatsMs")
            val strong = json.getJSONArray("strong")
            val bass = json.getJSONArray("bass")
            return MusicTimeline(
                durationMs = json.getInt("durationMs"),
                beatsMs = IntArray(beats.length()) { beats.getInt(it) },
                strong = BooleanArray(strong.length()) { strong.getInt(it) == 1 },
                bassStepMs = json.getInt("bassStepMs"),
                bass = FloatArray(bass.length()) { bass.getInt(it) / 100f },
            )
        }
    }
}
