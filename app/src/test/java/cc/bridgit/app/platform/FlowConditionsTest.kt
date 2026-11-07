package cc.bridgit.app.platform

import cc.bridgit.app.ApiError
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** only_if sources, all/any and their words (docs/applet-reach.md D1). Pure: a fixed clock and zone. */
class FlowConditionsTest {
    private fun o(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun a(vararg v: Any?) = JSONArray().apply { v.forEach { put(it) } }
    private val warsaw = ZoneId.of("Europe/Warsaw")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int, zone: ZoneId = warsaw) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone)
    private fun bad(m: String): Nothing = throw ApiError(422, "invalid_workflow", m)
    private fun check(c: JSONObject, flow: Boolean = false) = FlowConditions.check(c, "steps[0]", flow, { _, _, _ -> }, ::bad)
    private fun rejects(part: String, block: () -> Unit) {
        try { block(); fail("Expected invalid_workflow") } catch (e: ApiError) { assertTrue("'${e.message}' should mention $part", e.message!!.contains(part)) }
    }
    private fun holds(c: JSONObject, facts: FlowConditions.Facts) = FlowConditions.holds(c, facts) { _, _ -> null }
    private fun time(vararg between: String, days: List<String>? = null) = o("time" to o().apply { if (between.isNotEmpty()) put("between", a(*between)); days?.let { put("days", JSONArray(it)) } })

    @Test fun timeBetweenCrossesMidnight() {
        val night = time("22:00", "07:00")
        assertTrue(holds(night, FlowConditions.Clock(at(2026, 11, 6, 23, 30))))
        assertTrue(holds(night, FlowConditions.Clock(at(2026, 11, 7, 0, 0))))
        assertTrue(holds(night, FlowConditions.Clock(at(2026, 11, 7, 6, 59))))
        assertFalse(holds(night, FlowConditions.Clock(at(2026, 11, 7, 7, 0))))
        assertFalse(holds(night, FlowConditions.Clock(at(2026, 11, 7, 21, 59))))
        assertTrue(holds(night, FlowConditions.Clock(at(2026, 11, 7, 22, 0))))
        val day = time("09:00", "17:00")
        assertTrue(holds(day, FlowConditions.Clock(at(2026, 11, 6, 9, 0))))
        assertFalse(holds(day, FlowConditions.Clock(at(2026, 11, 6, 17, 0))))
        assertFalse(holds(day, FlowConditions.Clock(at(2026, 11, 6, 3, 0))))
    }

    @Test fun daysOfANightBelongToTheDayItBegan() {
        // Friday night 22:00-07:00: Saturday 01:00 is still Friday's night; Saturday 23:00 is not.
        val fridayNight = time("22:00", "07:00", days = listOf("fri"))
        assertTrue(holds(fridayNight, FlowConditions.Clock(at(2026, 11, 6, 22, 30)))) // Fri
        assertTrue(holds(fridayNight, FlowConditions.Clock(at(2026, 11, 7, 1, 0))))   // Sat early
        assertFalse(holds(fridayNight, FlowConditions.Clock(at(2026, 11, 7, 23, 0)))) // Sat night
        assertFalse(holds(fridayNight, FlowConditions.Clock(at(2026, 11, 6, 1, 0))))  // Fri early = Thursday's night
        val weekend = time(days = listOf("sat", "sun"))
        assertTrue(holds(weekend, FlowConditions.Clock(at(2026, 11, 8, 12, 0))))
        assertFalse(holds(weekend, FlowConditions.Clock(at(2026, 11, 9, 12, 0))))
    }

    @Test fun timeFollowsTheLocalClockAcrossDaylightSaving() {
        val early = time("01:00", "03:30")
        // 29 Mar 2026: Warsaw jumps 02:00 → 03:00. 03:15 local is inside, two hours of wall time after 01:15.
        val springForward = at(2026, 3, 29, 1, 15)
        assertTrue(holds(early, FlowConditions.Clock(springForward)))
        assertTrue(holds(early, FlowConditions.Clock(springForward.plusHours(1)))) // 03:15 CEST
        assertFalse(holds(early, FlowConditions.Clock(springForward.plusHours(2)))) // 04:15
        // 25 Oct 2026: 03:00 → 02:00. 02:30 happens twice; both are inside, and 03:30 is not.
        val fallBack = at(2026, 10, 25, 2, 30).withEarlierOffsetAtOverlap()
        assertTrue(holds(early, FlowConditions.Clock(fallBack)))
        assertTrue(holds(early, FlowConditions.Clock(fallBack.plusHours(1)))) // 02:30 again (CET)
        assertFalse(holds(early, FlowConditions.Clock(fallBack.plusHours(2)))) // 03:30
        // "today" starts at local midnight on a 23-hour and a 25-hour day.
        val today = o("since" to "today")
        assertEquals(at(2026, 3, 29, 0, 0).toInstant().toEpochMilli(), FlowConditions.sinceMs(today, at(2026, 3, 29, 12, 0)))
        assertEquals(at(2026, 10, 25, 0, 0).toInstant().toEpochMilli(), FlowConditions.sinceMs(today, at(2026, 10, 25, 23, 0)))
        // this_week starts on Monday.
        assertEquals(at(2026, 11, 2, 0, 0).toInstant().toEpochMilli(), FlowConditions.sinceMs(o("since" to "this_week"), at(2026, 11, 8, 20, 0)))
        assertEquals(at(2026, 11, 8, 14, 0).toInstant().toEpochMilli(), FlowConditions.sinceMs(o("hours" to 6), at(2026, 11, 8, 20, 0)))
        assertNull(FlowConditions.sinceMs(o("since" to "all"), at(2026, 11, 8, 20, 0)))
    }

    @Test fun sunTestsUseTodaysSunriseAndSunset() {
        // Warsaw, 5 Nov 2026: sunrise about 06:40, sunset about 16:10 local.
        val pos = 52.2 to 21.0
        val facts = { t: ZonedDateTime -> object : FlowConditions.Facts { override val now = t; override fun sunPosition() = pos } }
        val afterSunset = o("sun" to o("after" to "sunset"))
        assertFalse(holds(afterSunset, facts(at(2026, 11, 5, 15, 30))))
        assertTrue(holds(afterSunset, facts(at(2026, 11, 5, 17, 0))))
        assertTrue(holds(afterSunset, facts(at(2026, 11, 5, 23, 50))))
        assertFalse("after sunset ends at midnight", holds(afterSunset, facts(at(2026, 11, 6, 2, 0))))
        val beforeSunrise = o("sun" to o("before" to "sunrise"))
        assertTrue(holds(beforeSunrise, facts(at(2026, 11, 6, 2, 0))))
        assertFalse(holds(beforeSunrise, facts(at(2026, 11, 6, 8, 0))))
        val night = o("any" to a(afterSunset, beforeSunrise))
        assertTrue(holds(night, facts(at(2026, 11, 6, 2, 0))))
        assertFalse(holds(night, facts(at(2026, 11, 6, 12, 0))))
        // An offset moves the point: one hour before sunset is about 15:10.
        assertTrue(holds(o("sun" to o("after" to "sunset", "offset_min" to -60)), facts(at(2026, 11, 5, 15, 30))))
        // The test's own coordinates win; with no position the test doesn't hold.
        assertTrue(holds(o("sun" to o("after" to "sunset", "latitude" to 52.2, "longitude" to 21.0)), FlowConditions.Clock(at(2026, 11, 5, 17, 0))))
        assertFalse(holds(afterSunset, FlowConditions.Clock(at(2026, 11, 5, 17, 0))))
        // Today's event is the local date's, even far from UTC (Auckland, UTC+13).
        val auckland = ZoneId.of("Pacific/Auckland")
        val rise = FlowConditions.todays("sunrise", -36.8, 174.8, at(2026, 11, 5, 12, 0, auckland))!!
        assertEquals(java.time.LocalDate.of(2026, 11, 5), java.time.Instant.ofEpochMilli(rise).atZone(auckland).toLocalDate())
        assertTrue(java.time.Instant.ofEpochMilli(rise).atZone(auckland).hour in 5..7)
    }

    @Test fun phoneModeZoneAndTableTests() {
        val facts = object : FlowConditions.Facts {
            override val now = at(2026, 11, 5, 12, 0)
            override fun mode() = "away"
            override fun phone(reading: String): Any? = mapOf("charging" to false, "battery" to 18, "network" to "mobile")[reading]
            override fun location() = 52.2297 to 21.0122
            override fun count(table: String, sinceMs: Long?, where: JSONObject?) = if (table == "visits") 2 else null
            override fun sum(table: String, column: String, sinceMs: Long?, where: JSONObject?) = 42.5
        }
        assertTrue(holds(o("mode" to true, "is" to "away"), facts))
        assertTrue(holds(o("mode" to true, "is" to "AWAY"), facts))
        assertFalse(holds(o("mode" to true, "is_not" to "away"), facts))
        assertTrue(holds(o("mode" to true, "in" to a("night", "away")), facts))
        assertTrue(holds(o("phone" to "charging", "is" to false), facts))
        assertTrue(holds(o("phone" to "battery", "below" to 20), facts))
        assertFalse(holds(o("phone" to "battery", "at_least" to 20), facts))
        assertTrue(holds(o("phone" to "network", "in" to a("wifi", "mobile")), facts))
        assertFalse(holds(o("phone" to "network", "is" to "wifi"), facts))
        assertTrue(holds(o("zone" to o("latitude" to 52.2300, "longitude" to 21.0120, "radius_m" to 200), "is" to true), facts))
        assertTrue(holds(o("zone" to o("latitude" to 50.06, "longitude" to 19.94), "is" to false), facts))
        assertTrue(holds(o("count" to o("table" to "visits", "since" to "today"), "below" to 3), facts))
        assertFalse(holds(o("count" to o("table" to "visits", "since" to "today"), "at_least" to 3), facts))
        assertFalse("an unknown table holds nothing", holds(o("count" to o("table" to "gone"), "below" to 3), facts))
        assertTrue(holds(o("sum" to o("table" to "spend", "column" to "amount", "since" to "this_week"), "at_least" to 40), facts))
        // all and any.
        val both = o("all" to a(o("mode" to true, "is" to "away"), o("count" to o("table" to "visits", "since" to "today"), "below" to 3)))
        assertTrue(holds(both, facts))
        assertFalse(holds(o("all" to a(o("mode" to true, "is" to "away"), o("phone" to "charging", "is" to true))), facts))
        assertTrue(holds(o("any" to a(o("mode" to true, "is" to "home"), o("phone" to "charging", "is" to false))), facts))
        assertFalse(holds(o("any" to a(o("mode" to true, "is" to "home"), o("phone" to "charging", "is" to true))), facts))
        // step / trigger tests read values through the caller, as before.
        assertTrue(FlowConditions.holds(o("trigger" to "to", "is" to "away"), facts) { s, p -> if (s == "trigger" && p == "to") "away" else null })
        assertTrue(FlowConditions.holds(o("step" to "look.count", "exists" to false), facts) { _, _ -> null })
        assertFalse("a missing value is in no list", FlowConditions.holds(o("step" to "look.x", "in" to a(JSONObject.NULL, "a")), facts) { _, _ -> null })
    }

    @Test fun checksRefuseBadShapes() {
        assertFalse(check(time("22:00", "07:00")).tables)
        assertTrue(check(o("count" to o("table" to "visits", "since" to "today"), "below" to 3)).tables)
        check(o("all" to a(time("22:00", "07:00"), o("mode" to true, "is" to "away"))))
        assertTrue(check(o("sun" to o("after" to "sunset"))).location)
        assertFalse(check(o("sun" to o("after" to "sunset", "latitude" to 1.0, "longitude" to 2.0))).location)
        assertTrue(check(o("zone" to o("latitude" to 1.0, "longitude" to 2.0), "is" to true)).zone)
        rejects("2-8 tests") { check(o("all" to a(time("22:00", "07:00")))) }
        rejects("2-8 tests") { check(o("any" to JSONArray((1..9).map { time("22:00", "07:00") }))) }
        rejects("don't nest") { check(o("all" to a(time("22:00", "07:00"), o("any" to a(time("01:00", "02:00"), time("03:00", "04:00")))))) }
        rejects("alone") { check(o("all" to a(time("22:00", "07:00"), time("01:00", "02:00")), "mode" to true)) }
        rejects("exactly one of") { check(o("mode" to true, "time" to o("days" to a("mon")), "is" to "away")) }
        rejects("HH:MM") { check(time("25:00", "07:00")) }
        rejects("must differ") { check(time("07:00", "07:00")) }
        rejects("days") { check(time(days = listOf("monday"))) }
        rejects("test on its own") { check(o("time" to o("between" to a("22:00", "07:00")), "is" to true)) }
        rejects("sunrise or sunset") { check(o("sun" to o("after" to "noon"))) }
        rejects("offset_min") { check(o("sun" to o("after" to "sunset", "offset_min" to 400))) }
        rejects("phone must be") { check(o("phone" to "volume", "is" to 3)) }
        rejects("percentage") { check(o("phone" to "battery", "below" to 120)) }
        rejects("exactly one test") { check(o("phone" to "battery", "is" to 20)) }
        rejects("wifi, mobile, none") { check(o("phone" to "network", "is" to "5g")) }
        rejects("mode") { check(o("mode" to "is", "value" to "away")) }
        rejects("a mode") { check(o("mode" to true, "is" to "Not A Mode")) }
        rejects("since or hours") { check(o("count" to o("table" to "visits", "since" to "today", "hours" to 3), "below" to 3)) }
        rejects("hours") { check(o("count" to o("table" to "visits", "hours" to 0), "below" to 3)) }
        rejects("where") { check(o("count" to o("table" to "visits", "where" to o("column" to "taken")), "below" to 3)) }
        rejects("sum.column") { check(o("sum" to o("table" to "spend"), "below" to 3)) }
        rejects("radius_m") { check(o("zone" to o("latitude" to 1.0, "longitude" to 2.0, "radius_m" to 5), "is" to true)) }
        rejects("can't read a step") { check(o("step" to "look.x", "is" to 1), flow = true) }
    }

    @Test fun wordsReadAsTheReviewSentence() {
        val c = o("all" to a(time("22:00", "07:00"), o("mode" to true, "is" to "away"), o("count" to o("table" to "visits", "since" to "today"), "below" to 3)))
        assertEquals("between 22:00 and 07:00, when the mode is away and when there are fewer than 3 rows in \"visits\" today", FlowConditions.words(c) { "?" })
        assertEquals("after 30 minutes before sunset", FlowConditions.words(o("sun" to o("after" to "sunset", "offset_min" to -30))) { "?" })
        assertEquals("on weekdays", FlowConditions.words(time(days = listOf("mon", "tue", "wed", "thu", "fri"))) { "?" })
        assertEquals("when the battery is below 20% or when the phone is offline",
            FlowConditions.words(o("any" to a(o("phone" to "battery", "below" to 20), o("phone" to "network", "is" to "none")))) { "?" })
        assertEquals("when the total of amount in \"spend\" where paid is false this week is at least 50",
            FlowConditions.words(o("sum" to o("table" to "spend", "column" to "amount", "where" to o("column" to "paid", "is" to false), "since" to "this_week"), "at_least" to 50)) { "?" })
    }

    @Test fun flowSentenceHasTheFlowCondition() {
        val def = o("name" to "Night visits", "trigger" to o("type" to "mode", "to" to "away"),
            "only_if" to o("all" to a(time("22:00", "07:00"), o("mode" to true, "is" to "away"))),
            "steps" to a(o("type" to "set_mode", "mode" to "night", "only_if" to o("phone" to "charging", "is" to true)),
                o("type" to "lookup", "id" to "seen", "table" to "visits", "since" to "today", "limit" to 3),
                o("type" to "notice", "title" to "Visits", "only_if" to o("step" to "seen.count", "at_least" to 1))))
        val checked = FlowSteps.check(def.getJSONArray("steps"))
        assertTrue(checked.scopes.contains("modes.set")); assertTrue(checked.needs.tables)
        assertEquals("When the phone's mode changes to away, only between 22:00 and 07:00 and when the mode is away: only when the phone is charging set the phone's mode to night, " +
            "look up the newest 3 rows of \"visits\" today, if seen's count is at least 1 show you \"Visits\".", FlowSteps.summary(def))
        rejects("limit") { FlowSteps.check(a(o("type" to "lookup", "table" to "visits", "limit" to 11))) }
        rejects("mode must be") { FlowSteps.check(a(o("type" to "set_mode", "mode" to "Away!"))) }
        // set_mode runs on its own too (do.step), as POST /v1/mode.
        assertEquals("modes.set", FlowSteps.checkInline(o("type" to "set_mode", "mode" to "away")))
        assertEquals("/v1/mode", FlowSteps.inlineCall(o("type" to "set_mode", "mode" to "away"), JSONObject()).getString("path"))
        assertEquals("POST /v1/mode", FlowSteps.inlineEndpoint("set_mode"))
        assertEquals("sets the phone's mode to away", FlowSteps.inlineWords(o("type" to "set_mode", "mode" to "away")))
    }
}
