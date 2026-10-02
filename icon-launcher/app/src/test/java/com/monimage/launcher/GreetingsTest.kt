package com.monimage.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.random.Random

class GreetingsTest {

    /** 2 octobre 2026 = vendredi, 3 octobre = samedi. */
    private fun at(day: Int, hour: Int, minute: Int = 0): Calendar =
        Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, day, hour, minute) }

    @Test
    fun slotsCoverTheDay() {
        assertEquals(Greetings.Slot.NIGHT, Greetings.slotOf(4))
        assertEquals(Greetings.Slot.MORNING, Greetings.slotOf(5))
        assertEquals(Greetings.Slot.MORNING, Greetings.slotOf(10))
        assertEquals(Greetings.Slot.NOON, Greetings.slotOf(11))
        assertEquals(Greetings.Slot.AFTERNOON, Greetings.slotOf(14))
        assertEquals(Greetings.Slot.AFTERNOON, Greetings.slotOf(17))
        assertEquals(Greetings.Slot.EVENING, Greetings.slotOf(18))
        assertEquals(Greetings.Slot.NIGHT, Greetings.slotOf(22))
        assertEquals(Greetings.Slot.NIGHT, Greetings.slotOf(0))
    }

    @Test
    fun greetsOncePerSlotThenOnlyAfterBeingAway() {
        val morning = at(2, 7)
        assertTrue(Greetings.shouldGreet(morning, lastSlotKey = null, awayMs = null))
        val key = Greetings.slotKey(morning)
        // Juste regardé l'heure 2 minutes plus tard : pas de nouveau bonjour
        assertFalse(Greetings.shouldGreet(at(2, 7, 30), key, awayMs = 2 * 60_000L))
        // Parti 45 minutes : on resalue
        assertTrue(Greetings.shouldGreet(at(2, 8, 15), key, awayMs = 45 * 60_000L))
        // Nouveau moment de la journée : bonjour même si l'écran vient de s'éteindre
        assertTrue(Greetings.shouldGreet(at(2, 16), key, awayMs = 60_000L))
        // Même heure mais autre jour
        assertTrue(Greetings.shouldGreet(at(3, 7), key, awayMs = 60_000L))
    }

    @Test
    fun morningAsksAboutTheNight() {
        val all = (0 until 50).map { Greetings.message(at(2, 7), true, null, Random(it)) }.toSet()
        assertTrue(all.any { "nuit" in it || "dormi" in it })
    }

    @Test
    fun schoolDayAfternoonTalksAboutSchool() {
        val all = (0 until 50).map { Greetings.message(at(2, 16), true, null, Random(it)) }
        assertTrue(all.all { "école" in it || "cours" in it || "rentré" in it })
    }

    @Test
    fun weekendAfternoonDoesNotMentionSchool() {
        val all = (0 until 50).map { Greetings.message(at(3, 16), true, null, Random(it)) }
        assertTrue(all.none { "école" in it || "cours" in it })
    }

    @Test
    fun lowBatteryIsMentioned() {
        assertTrue("15 %" in Greetings.message(at(2, 19), true, 15, Random(1)))
        assertFalse("batterie" in Greetings.message(at(2, 19), true, 80, Random(1)))
    }
}
