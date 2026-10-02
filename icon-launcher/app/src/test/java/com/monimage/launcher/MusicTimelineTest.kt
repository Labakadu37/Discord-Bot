package com.monimage.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Vérifie la carte rythmique réelle du morceau (res/raw/zelenuyu_beats.json). */
class MusicTimelineTest {

    // org.json n'est pas disponible dans les tests locaux : on lit le JSON à la main
    private val json = File("src/main/res/raw/zelenuyu_beats.json").readText()
    private fun ints(key: String) = Regex("\"$key\":\\[([^]]*)]").find(json)!!.groupValues[1].split(',').map { it.toInt() }
    private fun int(key: String) = Regex("\"$key\":(\\d+)").find(json)!!.groupValues[1].toInt()

    private val timeline = MusicTimeline(
        durationMs = int("durationMs"),
        beatsMs = ints("beatsMs").toIntArray(),
        strong = ints("strong").map { it == 1 }.toBooleanArray(),
        bassStepMs = int("bassStepMs"),
        bass = ints("bass").map { it / 100f }.toFloatArray(),
    )

    @Test
    fun followsTheSongStructure() {
        assertEquals(MusicTimeline.Phase.INTRO, timeline.phaseAt(2_000))
        assertEquals(MusicTimeline.Phase.DROP, timeline.phaseAt(10_000))
        assertEquals(MusicTimeline.Phase.BREAK, timeline.phaseAt(17_500))
        assertEquals(MusicTimeline.Phase.DROP, timeline.phaseAt(30_000))
        assertEquals(MusicTimeline.Phase.BREAK, timeline.phaseAt(73_000))
        assertEquals(MusicTimeline.Phase.DROP, timeline.phaseAt(82_000))
        assertEquals(MusicTimeline.Phase.OUTRO, timeline.phaseAt(107_000))
    }

    @Test
    fun beatsMatchTheTempo() {
        val gaps = timeline.beatsMs.toList().zipWithNext { a, b -> b - a }
        val average = gaps.average()
        // 103 BPM ≈ un beat toutes les 580 ms
        assertTrue("moyenne $average", average in 550.0..610.0)
        assertTrue(timeline.durationMs in 108_000..110_000)
        assertTrue(timeline.landingStartMs in 104_000..106_000)
    }
}
