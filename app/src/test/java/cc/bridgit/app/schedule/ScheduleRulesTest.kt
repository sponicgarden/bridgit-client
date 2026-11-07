package cc.bridgit.app.schedule

import cc.bridgit.app.schedule.ScheduleRules.Do
import cc.bridgit.app.schedule.ScheduleRules.When
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ScheduleRulesTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val now = ScheduleRules.time("2026-11-02T16:00", zone, "now")
    private fun parse(json: String, routes: (String, String) -> String? = { _, _ -> null }) =
        ScheduleRules.parse(JSONObject(json), zone, now, { it.startsWith("https://") }, routes)
    private fun t(s: String) = ScheduleRules.time(s, zone, "t")
    private fun invalid(json: String): String = try { parse(json); fail("expected invalid: $json"); "" } catch (e: ScheduleRules.Invalid) { e.message!! }

    @Test fun everySevenMinutesFromFourThirtyFourThreeTimes() {
        val m = parse("""{"actions":[{"id":"pics","when":{"start":"2026-11-02T16:34","every_minutes":7,"count":3},
            "do":{"call":"POST /v1/camera/snapshot","query":{"raw":"1"}},"send_to":{"url":"https://example.com/in"}}]}""")
        val w = m.actions.single().whenSpec as When.Every
        val slots = generateSequence(ScheduleRules.nextSlot(w, null, 0)) { ScheduleRules.nextSlot(w, it, 0) }.toList()
        assertEquals(listOf(t("2026-11-02T16:34"), t("2026-11-02T16:41"), t("2026-11-02T16:48")), slots)
        assertEquals("Every 7 minutes from Mon 2 Nov 16:34, 3 times: call POST /v1/camera/snapshot (raw=1), then send it to example.com",
            ScheduleRules.describe(m.actions.single(), zone))
    }

    @Test fun fixedTimesAreSortedAndRunOnce() {
        val w = parse("""{"actions":[{"id":"a","when":{"times":["2026-11-03T09:00","2026-11-02T18:30+01:00"]},"do":{"call":"POST /v1/speaker/say","body":{"text":"hi"}}}]}""")
            .actions.single().whenSpec as When.Times
        assertEquals(listOf(t("2026-11-02T18:30"), t("2026-11-03T09:00")), w.times)
        assertEquals(t("2026-11-03T09:00"), ScheduleRules.nextSlot(w, t("2026-11-02T18:30"), 1))
        assertNull(ScheduleRules.nextSlot(w, t("2026-11-03T09:00"), 2))
    }

    @Test fun dailyHonoursWeekdaysAndDst() {
        // 25 Oct 2026 is the switch back from summer time in Poland.
        val start = "2026-10-23T00:00"
        val w = ScheduleRules.parse(JSONObject("""{"actions":[{"id":"d","when":{"daily":["07:30"],"days":["fri","mon"],"start":"$start"},"do":{"play":"song"}}]}"""),
            zone, now, { true }, { _, _ -> null }).actions.single().whenSpec as When.Daily
        val first = ScheduleRules.nextSlot(w, null, 0)!!
        assertEquals(t("2026-10-23T07:30"), first)
        val second = ScheduleRules.nextSlot(w, first, 1)!!
        assertEquals(t("2026-10-26T07:30"), second) // Monday, wall clock still 07:30 after DST ends
        assertEquals(7 * 24 * 3600_000L - 4 * 24 * 3600_000L + 3600_000L, second - first) // one extra hour that weekend
    }

    @Test fun missedSlotsCollapseToTheLatestWithinGrace() {
        val w = When.Every(t("2026-11-02T10:00"), 60_000L * 7, null, null, 10 * 60_000L)
        // Phone was off from 10:00 to 11:02: the 10:56 slot is within 10 minutes' grace, the earlier ones are skipped.
        val d = ScheduleRules.due(w, null, 0, t("2026-11-02T11:02"))
        assertEquals(t("2026-11-02T10:56"), d.run)
        assertEquals(8, d.skipped)
        assertEquals(t("2026-11-02T11:03"), d.next)
        // Past grace: nothing runs, every slot is reported missed.
        val late = ScheduleRules.due(When.At(t("2026-11-02T10:00"), 5 * 60_000L), null, 0, t("2026-11-02T10:30"))
        assertNull(late.run); assertEquals(1, late.skipped); assertNull(late.next)
    }

    @Test fun countStopsEverySchedule() {
        val w = When.Every(t("2026-11-02T10:00"), 60_000L, 3, null, 3600_000L)
        val d = ScheduleRules.due(w, null, 0, t("2026-11-02T10:30"))
        assertEquals(t("2026-11-02T10:02"), d.run)
        assertNull(d.next)
        assertNull(ScheduleRules.nextSlot(w, t("2026-11-02T10:02"), 3))
    }

    @Test fun downloadThenPlayChain() {
        val m = parse("""{"actions":[
            {"id":"song","when":{"at":"2026-11-03T07:00"},"do":{"download":"https://cdn.example.com/a.mp3","sha256":"${"a".repeat(64)}"}},
            {"id":"wake","when":{"after":"song"},"do":{"play":"song","caption":"Good morning"}}]}""")
        assertEquals("song", m.actions[0].saveAs) // a download keeps its result under its own id by default
        assertTrue(m.actions[1].action is Do.Play)
        assertEquals("After song succeeds: play song", ScheduleRules.describe(m.actions[1], zone))
    }

    @Test fun rejectsBadManifests() {
        assertTrue(invalid("""{"actions":[]}""").contains("1 to 100"))
        assertTrue(invalid("""{"actions":[{"id":"a","when":{"at":"x"},"do":{"play":"s"}}]}""").contains("ISO-8601"))
        assertTrue(invalid("""{"actions":[{"id":"a","when":{"at":"2026-11-03T07:00","every_minutes":5},"do":{"play":"s"}}]}""").contains("exactly one"))
        assertTrue(invalid("""{"actions":[{"id":"a","when":{"every_minutes":0},"do":{"play":"s"}}]}""").contains("1 to 10080"))
        assertTrue(invalid("""{"actions":[{"id":"a","when":{"after":"b"},"do":{"play":"s"}},{"id":"b","when":{"after":"a"},"do":{"play":"s"}}]}""").contains("loop"))
        assertTrue(invalid("""{"actions":[{"id":"a","when":{"after":"zz"},"do":{"play":"s"}}]}""").contains("isn't in the manifest"))
        assertTrue(invalid("""{"actions":[{"id":"a","when":{"at":"2026-11-03T07:00"},"do":{"download":"http://x"}}]}""").contains("https"))
        assertTrue(invalid("""{"actions":[{"id":"a","when":{"at":"2026-11-03T07:00"},"do":{"send":"x"}}]}""").contains("send_to"))
        assertTrue(invalid("""{"actions":[{"id":"a","when":{"at":"2026-11-03T07:00"},"do":{"play":"s"},"bogus":1}]}""").contains("Unknown field"))
        assertTrue(invalid("""{"actions":[{"id":"a","when":{"at":"2026-11-03T07:00"},"do":{"call":"POST /v1/camera/snapshot"},"send_to":{"url":"https://e.com","headers":{"Host":"x"}}}]}""").contains("isn't allowed"))
        assertTrue(invalid("""{"actions":[{"id":"a","when":{"at":"2026-11-03T07:00"},"do":{"play":"s"}},{"id":"a","when":{"at":"2026-11-03T07:00"},"do":{"play":"s"}}]}""").contains("unique"))
        val blocked = try { parse("""{"actions":[{"id":"a","when":{"at":"2026-11-03T07:00"},"do":{"call":"GET /v1/feedback"}}]}""") { _, _ -> "Not schedulable." }; null } catch (e: ScheduleRules.Invalid) { e.message }
        assertTrue(blocked!!.contains("Not schedulable."))
    }

    @Test fun payloadDecodesMediaReplies() {
        val p = ScheduleRules.payload("application/json; charset=utf-8", """{"format":"jpeg","data_base64":"AQID"}""".toByteArray()) { java.util.Base64.getDecoder().decode(it) }
        assertEquals("image/jpeg", p.mime); assertArrayEquals(byteArrayOf(1, 2, 3), p.bytes)
        val raw = ScheduleRules.payload("image/png", byteArrayOf(9)) { error("not called") }
        assertEquals("image/png", raw.mime)
        val json = ScheduleRules.payload("application/json", """{"battery":80}""".toByteArray()) { error("not called") }
        assertEquals("application/json", json.mime)
    }

    @Test fun digestIgnoresKeyOrder() {
        assertEquals(ScheduleRules.digest(JSONObject("""{"a":1,"b":[1,{"c":2,"d":3}]}""")), ScheduleRules.digest(JSONObject("""{"b":[1,{"d":3,"c":2}],"a":1}""")))
    }

    /** The documented example must name real endpoints (it once said /v1/camera/photo). */
    @Test fun documentedExampleParsesAgainstRealRoutes() {
        val routes = cc.bridgit.app.api.Endpoints.all
        fun exists(method: String, path: String): String? {
            val b = path.split('/')
            return if (routes.any { e -> e.method == method && e.fullPath.split('/').let { a -> a.size == b.size && a.indices.all { i -> a[i].startsWith('{') || a[i] == b[i] } } }) null
            else "$method $path isn't a Bridgit endpoint."
        }
        val example = ScheduleEndpoints.SET.example.substringAfter("POST /v1/schedule ")
        val m = ScheduleRules.parse(JSONObject(example).getJSONObject("manifest"), zone, now, { it.startsWith("https://") }, ::exists)
        assertEquals("POST", (m.actions.single().action as Do.Call).method)
        assertNull(exists("POST", "/v1/camera/snapshot"))
        assertNotNull(exists("POST", "/v1/camera/photo"))
    }
}
