package cc.bridgit.app.watch

import cc.bridgit.app.judgment.Jev
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId

class SituationsTest {
    private val zone = ZoneId.of("Europe/Warsaw")

    private fun bad(body: String, contains: String) {
        try { WatchRules.parse(JSONObject(body)); fail("expected refusal for $body") }
        catch (e: WatchRules.Bad) { assertTrue("${e.message} should mention $contains", e.message!!.contains(contains)) }
    }

    private fun noul(id: String, p: Double, vararg more: Pair<String, Double>): Jev.Answer {
        val answers = JSONObject().put(id, JSONObject().put("type", "noul").put("noul", p))
        more.forEach { (k, v) -> answers.put(k, JSONObject().put("type", "noul").put("noul", v)) }
        return Jev.Answer("clef", answers, 100, 0.0, 300)
    }

    @Test fun libraryIdsAreValidQuestionIds() {
        val ids = Situations.LIBRARY.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (s in Situations.LIBRARY) {
            assertTrue(s.id, s.id.matches(Regex("^[a-z0-9_]{1,40}$")))
            // Every library entry is a watch the rules accept.
            val spec = WatchRules.parse(JSONObject().put("situation", s.id))
            assertEquals(s.id, spec.situation)
            assertEquals(listOf("camera"), spec.data)
        }
    }

    @Test fun potBoilingOverIsACameraTallyWithAnOwnerNotice() {
        val s = WatchRules.parse(JSONObject("""{"situation":"pot_boiling_over"}"""))
        assertEquals("Pot boiling over", s.label)
        assertEquals(listOf("camera"), s.data); assertEquals("rear", s.camera); assertEquals("clef", s.model)
        assertEquals(20, s.intervalS); assertTrue(s.local.enabled)
        val t = s.tally!!
        assertEquals("pot_boiling_over", t.track); assertEquals("noul", t.kind); assertEquals(0.7, t.minProbability, 0.0)
        val rule = t.rules.single()
        assertEquals("POST", rule.call!!.getString("method")); assertEquals("/v1/interactions", rule.call!!.getString("path"))
        assertEquals("urgent", rule.call!!.getJSONObject("body").getString("priority"))
        assertTrue(s.questions.getJSONObject("pot_boiling_over").getString("instructions").contains("boiling over"))
        assertTrue(s.conditions.isEmpty())
        assertEquals("pot_boiling_over", s.json().getString("situation"))
    }

    @Test fun modelAndIntervalStayTheAgentsChoice() {
        val s = WatchRules.parse(JSONObject("""{"situation":"sunlight_in_room","model":"jev","interval_s":600,"camera":"front","label":"Sun"}"""))
        assertEquals("jev", s.model); assertEquals(600, s.intervalS); assertEquals("front", s.camera); assertEquals("Sun", s.label)
        assertTrue(s.tally!!.rules.isEmpty()) // sunlight is counted, not alerted
        assertEquals(300, Situations.byId("sunlight_in_room")!!.intervalS)
    }

    @Test fun dogLickingCountsOnlyWhileADogIsVisible() {
        val s = WatchRules.parse(JSONObject("""{"situation":"dog_licking_itself"}"""))
        val t = s.tally!!
        assertEquals(Situations.SUBJECT_ID, t.seen!!.answer)
        assertNotNull(s.questions.optJSONObject(Situations.SUBJECT_ID))
        val st = WatchTally.State(0)
        val t0 = 1_762_250_000_000L
        WatchTally.record(t, st, t0, noul("dog_licking_itself", 0.9, Situations.SUBJECT_ID to 0.95), zone)
        WatchTally.record(t, st, t0 + 20_000, noul("dog_licking_itself", 0.85, Situations.SUBJECT_ID to 0.95), zone)
        // No dog in view: not counted, and not an episode.
        WatchTally.record(t, st, t0 + 40_000, noul("dog_licking_itself", 0.9, Situations.SUBJECT_ID to 0.1), zone)
        val eps = WatchTally.episodesOf(t, st, "dog_licking_itself")
        assertEquals(1, eps.size); assertEquals(2, eps.single().readings)
    }

    @Test fun alertFiresWhenAnEpisodeStarts() {
        val s = WatchRules.parse(JSONObject("""{"situation":"pot_boiling_over"}"""))
        val t = s.tally!!
        val st = WatchTally.State(0)
        val t0 = 1_762_250_000_000L
        WatchTally.record(t, st, t0, noul("pot_boiling_over", 0.1), zone)
        assertTrue(WatchTally.due(t, st, t0, zone, s.intervalS).isEmpty())
        WatchTally.record(t, st, t0 + 20_000, noul("pot_boiling_over", 0.9), zone)
        assertEquals("alert", WatchTally.due(t, st, t0 + 20_000, zone, s.intervalS).single().rule.id)
        // Same episode a moment later: cooled down, no second notice.
        WatchTally.record(t, st, t0 + 40_000, noul("pot_boiling_over", 0.9), zone)
        assertTrue(WatchTally.due(t, st, t0 + 40_000, zone, s.intervalS).isEmpty())
        // The owner's line uses the title, not the id.
        val line = WatchTally.todayLine(t, st, t0 + 40_000, zone, s.intervalS, mapOf(t.track to s.label))
        assertTrue(line, line.startsWith("Pot boiling over once"))
    }

    @Test fun ownWordsBecomeAPhotoQuestion() {
        val s = WatchRules.parse(JSONObject("""{"situation":"the cat is on the kitchen counter."}"""))
        assertEquals(Situations.CUSTOM_ID, s.situation); assertEquals("The cat is on the kitchen counter", s.label)
        assertTrue(s.questions.getJSONObject("situation").getString("instructions").startsWith("Does the photo show this: the cat is on the kitchen counter?"))
        val a = WatchRules.parse(JSONObject("""{"situation":{"describe":"a delivery box at the door","alert":true,"min":0.8}}"""))
        assertEquals(0.8, a.tally!!.minProbability, 0.0); assertEquals(1, a.tally!!.rules.size)
    }

    @Test fun restartReparsesTheStoredRequestTheSameWay() {
        val request = JSONObject("""{"situation":"pot_boiling_over","max_paid_per_hour":30}""")
        val first = WatchRules.parse(JSONObject(request.toString()))
        val again = WatchRules.parse(JSONObject(request.toString()))
        assertEquals(first.json().toString(), again.json().toString())
        assertTrue(request.has("situation")) // parse doesn't change the stored request
    }

    @Test fun refusals() {
        bad("""{"situation":"pot_boilng_over"}""", "isn't a situation in the library")
        bad("""{"situation":"pot_boiling_over","question":"Is it?"}""", "leave question out")
        bad("""{"situation":"pot_boiling_over","tally":{"track":"x"}}""", "leave tally out")
        bad("""{"situation":"pot_boiling_over","data":["location"]}""", "seen on a camera")
        bad("""{"situation":{"id":"pot_boiling_over","describe":"x"}}""", "not both")
        bad("""{"situation":{"id":"pot_boiling_over","loud":true}}""", "isn't a situation option")
        bad("""{"situation":{"describe":"x","min":2}}""", "0-1")
        bad("""{"situation":"pot_boiling_over","model":"none"}""", "model none")
    }

    @Test fun noSituationLeavesTheBodyAlone() {
        val b = JSONObject("""{"question":"Is it raining?"}""")
        val e = Situations.expand(b)
        assertNull(e.id); assertEquals(b.toString(), e.body.toString())
        assertNull(WatchRules.parse(b).situation)
    }
}
