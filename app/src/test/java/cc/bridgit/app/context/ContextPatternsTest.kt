package cc.bridgit.app.context

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class ContextPatternsTest {
    private val start = ZonedDateTime.parse("2026-10-01T02:00:00+02:00[Europe/Warsaw]")
    @Test fun aHolidayOrOneNightDoesNotBecomeHomeButRepeatedNightsCan() {
        var samples = JSONArray()
        repeat(14) { day -> repeat(25) { tick -> samples = ContextPatterns.observe(samples, "home", 52.2, 21.0, start.plusDays(day.toLong()).plusMinutes(tick * 5L)) } }
        assertNull(ContextPatterns.candidate(samples, "home"))
        repeat(25) { tick -> samples = ContextPatterns.observe(samples, "home", 52.2, 21.0, start.plusDays(22).plusMinutes(tick * 5L)) }
        assertNotNull(ContextPatterns.candidate(samples, "home"))
        val old = ContextPatterns.observe(samples, "home", 1.0, 1.0, start.plusDays(70))
        assertNull(ContextPatterns.candidate(old, "home"))
        assertEquals(1, old.length())
    }
    @Test fun passingThroughAnOfficeDoesNotEstablishWork() {
        var samples = JSONArray()
        repeat(30) { samples = ContextPatterns.observe(samples, "work", 52.2, 21.0, start.plusDays(it.toLong()).withHour(10)) }
        assertNull(ContextPatterns.candidate(samples, "work"))
        repeat(30) { i ->
            val day = start.plusDays(i.toLong()).withHour(10)
            repeat(25) { tick -> samples = ContextPatterns.observe(samples, "work", 52.2, 21.0, day.plusMinutes(tick * 5L)) }
        }
        assertNotNull(ContextPatterns.candidate(samples, "work"))
    }
}
