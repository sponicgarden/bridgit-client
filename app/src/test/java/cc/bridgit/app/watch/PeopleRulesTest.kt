package cc.bridgit.app.watch

import cc.bridgit.app.applets.AppletPackage
import cc.bridgit.app.vision.FaceModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PeopleRulesTest {
    private val base = """"data":["camera"],"model":"none""""

    private fun bad(body: String, contains: String) {
        try { WatchRules.parse(JSONObject(body)); fail("expected refusal for $body") }
        catch (e: WatchRules.Bad) { assertTrue("${e.message} should mention $contains", e.message!!.contains(contains)) }
    }

    @Test fun peopleDefaultsAndEcho() {
        val s = WatchRules.parse(JSONObject("""{$base,"people":{}}"""))
        assertEquals(PeopleRules.Options(), s.people)
        assertTrue("a people watch keeps going", s.repeat)
        val echo = s.json().getJSONObject("people")
        assertEquals(3, echo.getInt("burst")); assertEquals(800, echo.getInt("burst_gap_ms")); assertEquals(50, echo.getInt("battery_floor"))
        assertEquals(20, echo.getInt("max_bursts_per_hour")); assertEquals(0.01, echo.getDouble("min_face_share"), 0.0)
        assertNull(WatchRules.parse(JSONObject("""{$base}""")).people)
        assertFalse(WatchRules.parse(JSONObject("""{$base}""")).json().has("people"))
    }

    @Test fun peopleOptionsAreChecked() {
        val s = WatchRules.parse(JSONObject("""{$base,"people":{"burst":5,"burst_gap_ms":300,"battery_floor":0,"max_bursts_per_hour":60,"min_face_share":0.5}}"""))
        assertEquals(PeopleRules.Options(5, 300, 0, 60, 0.5), s.people)
        bad("""{$base,"people":{"burst":6}}""", "people.burst")
        bad("""{$base,"people":{"burst":0}}""", "people.burst")
        bad("""{$base,"people":{"burst_gap_ms":3001}}""", "people.burst_gap_ms")
        bad("""{$base,"people":{"battery_floor":101}}""", "people.battery_floor")
        bad("""{$base,"people":{"max_bursts_per_hour":0}}""", "people.max_bursts_per_hour")
        bad("""{$base,"people":{"min_face_share":0.001}}""", "people.min_face_share")
        bad("""{$base,"people":{"min_face_share":"big"}}""", "people.min_face_share")
        bad("""{$base,"people":{"faces":2}}""", "isn't a people option")
        bad("""{$base,"people":3}""", "people must be an object")
        bad("""{$base,"people":{},"repeat":false}""", "repeat")
    }

    @Test fun peopleOnlyOnModelNoneCamera() {
        bad("""{"data":["camera"],"model":"clef","question":"Messy?","people":{}}""", "model none")
        bad("""{"data":["location"],"question":"Here?","people":{}}""", "model none")
    }

    @Test fun batteryGate() {
        assertEquals(PeopleRules.ON, PeopleRules.gate(false, null, 62, false, 50, 0, 20))
        assertEquals(PeopleRules.ON, PeopleRules.gate(false, null, 50, false, 50, 0, 20))
        assertEquals(PeopleRules.BATTERY_LOW, PeopleRules.gate(false, null, 49, false, 50, 0, 20))
        assertEquals(PeopleRules.ON, PeopleRules.gate(false, null, 10, true, 50, 0, 20))
        assertEquals(PeopleRules.BATTERY_LOW, PeopleRules.gate(false, null, null, false, 50, 0, 20))
        assertEquals(PeopleRules.ON, PeopleRules.gate(false, null, null, false, 0, 0, 20))
        assertEquals(PeopleRules.CAP_REACHED, PeopleRules.gate(false, null, 90, true, 50, 20, 20))
        assertEquals(PeopleRules.OFF, PeopleRules.gate(true, null, 90, true, 50, 0, 20))
        assertEquals("paused — battery below 50% and not charging", PeopleRules.stateWords(PeopleRules.BATTERY_LOW, 50))
        // The portal push speaks on | battery_low | paused | off.
        assertEquals("on", PeopleRules.pushState(false, null, PeopleRules.CAP_REACHED))
        assertEquals("battery_low", PeopleRules.pushState(false, null, PeopleRules.BATTERY_LOW))
        assertEquals("paused", PeopleRules.pushState(true, null, PeopleRules.ON))
        assertEquals("off", PeopleRules.pushState(false, PeopleRules.PHOTO_SHARING_OFF, PeopleRules.OFF))
        // No portal, no photo sharing: off, with a reason the card can show, and no photos at all.
        assertEquals(PeopleRules.OFF, PeopleRules.gate(false, PeopleRules.PORTAL_OFF, 90, true, 50, 0, 20))
        assertEquals(PeopleRules.PORTAL_OFF, PeopleRules.reason(false, PeopleRules.PORTAL_OFF, PeopleRules.OFF))
        assertEquals("off — photo sharing is off", PeopleRules.stateWords(PeopleRules.OFF, 50, PeopleRules.PHOTO_SHARING_OFF))
        assertEquals(null, PeopleRules.reason(false, null, PeopleRules.ON))
        assertFalse(PeopleRules.takesPhotos(PeopleRules.OFF)); assertFalse(PeopleRules.takesPhotos(PeopleRules.BATTERY_LOW))
        assertTrue(PeopleRules.takesPhotos(PeopleRules.ON)); assertTrue(PeopleRules.takesPhotos(PeopleRules.CAP_REACHED))
    }

    @Test fun qualityPrefersBigFrontalFaces() {
        assertEquals(1.0, PeopleRules.quality(0.05, 0f, 0f, 5), 1e-9)
        assertTrue(PeopleRules.quality(0.05, 40f, 0f, 5) < PeopleRules.quality(0.05, 5f, 0f, 5))
        assertTrue(PeopleRules.quality(0.01, 0f, 0f, 5) < PeopleRules.quality(0.04, 0f, 0f, 5))
        assertTrue(PeopleRules.quality(0.05, 0f, 0f, 3) < 1.0)
        assertEquals(0.0, PeopleRules.quality(0.0, 90f, 60f, 0), 1e-9)
    }

    @Test fun similarityOfTheTemplateIsIdentity() {
        val t = PeopleRules.similarity(PeopleRules.ARCFACE_112, PeopleRules.ARCFACE_112)
        assertEquals(1.0, t[0], 1e-9); assertEquals(0.0, t[1], 1e-9); assertEquals(0.0, t[2], 1e-9); assertEquals(0.0, t[3], 1e-9)
    }

    @Test fun similarityRecoversRotationScaleAndShift() {
        // A face in the photo: the template turned 20°, 3x bigger, moved to (400, 250). Its inverse maps it back.
        val ang = Math.toRadians(20.0); val k = 3.0
        val fwd = doubleArrayOf(k * cos(ang), k * sin(ang), 400.0, 250.0)
        val photo = PeopleRules.ARCFACE_112.map { PeopleRules.apply(fwd, it) }
        val t = PeopleRules.similarity(photo, PeopleRules.ARCFACE_112)
        for ((p, q) in photo.zip(PeopleRules.ARCFACE_112)) {
            val m = PeopleRules.apply(t, p)
            assertEquals(q.x, m.x, 1e-6); assertEquals(q.y, m.y, 1e-6)
        }
        assertEquals(1.0 / k, Math.hypot(t[0], t[1]), 1e-9)
        val mat = PeopleRules.matrix(t)
        assertEquals(t[0].toFloat(), mat[0]); assertEquals((-t[1]).toFloat(), mat[1]); assertEquals(t[1].toFloat(), mat[3]); assertEquals(1f, mat[8])
    }

    @Test fun landmarksAreOrderedByImageSide() {
        val p = PeopleRules::Pt
        // Named from the subject's side: their left eye is on the image's right.
        val o = PeopleRules.ordered(eyeA = p(70.0, 50.0), eyeB = p(40.0, 50.0), nose = p(55.0, 70.0), mouthA = p(68.0, 90.0), mouthB = p(42.0, 90.0))
        assertEquals(listOf(p(40.0, 50.0), p(70.0, 50.0), p(55.0, 70.0), p(42.0, 90.0), p(68.0, 90.0)), o)
    }

    @Test fun faceModelHelpers() {
        assertTrue(FaceModel.pinned())
        assertFalse(FaceModel.pinned("TODO"))
        assertEquals(-1f, FaceModel.norm(0), 1e-6f); assertEquals(1f, FaceModel.norm(255), 1e-6f)
        val v = FaceModel.l2(floatArrayOf(3f, 4f))
        assertEquals(0.6f, v[0], 1e-6f); assertEquals(0.8f, v[1], 1e-6f)
        assertEquals(0f, FaceModel.l2(floatArrayOf(0f, 0f))[0], 0f)
        assertEquals(60_000L, FaceModel.backoffMs(1)); assertEquals(120_000L, FaceModel.backoffMs(2)); assertEquals(6 * 3600_000L, FaceModel.backoffMs(30))
    }

    @Test fun messWindowsPairEachMessWithTheCleanBeforeIt() {
        val eps = listOf(
            PeopleRules.Ep("clean", 1_000, 2_000),
            PeopleRules.Ep("messy", 3_000, 4_000),
            PeopleRules.Ep("dirty", 4_500, 5_000),
            PeopleRules.Ep("clean", 6_000, 7_000),
            PeopleRules.Ep("unclear", 7_500, 7_600),
            PeopleRules.Ep("dirty", 8_000, 9_000),
        )
        val all = PeopleRules.messEvents(eps, after = 0)
        assertEquals(listOf(PeopleRules.MessEvent(3_000, "messy", 2_000), PeopleRules.MessEvent(4_500, "dirty", 2_000), PeopleRules.MessEvent(8_000, "dirty", 7_000)), all)
        assertEquals(listOf(PeopleRules.MessEvent(8_000, "dirty", 7_000)), PeopleRules.messEvents(eps, after = 4_500))
        assertEquals(listOf(PeopleRules.MessEvent(3_000, "messy", null)), PeopleRules.messEvents(eps.drop(1), after = 0, max = 1))
        val j = all[0].json()
        assertEquals(3_000L, j.getLong("messy_at")); assertEquals("messy", j.getString("state")); assertEquals(2_000L, j.getLong("clean_at"))
        assertTrue(PeopleRules.MessEvent(1, "dirty", null).json().isNull("clean_at"))
    }

    @Test fun cleanAtIsTheLastCleanReadingEvenWhileCleanIsStillOpen() {
        // Clean 08:00-18:00 every 10 min, one messy reading at 12:00, clean again from 12:05: the clean episode never
        // closes, so only the reading stamped when the messy episode opened knows the last clean check (11:50).
        val zone = java.time.ZoneId.of("Europe/Warsaw")
        fun at(s: String) = java.time.LocalDateTime.parse("2026-11-04T$s").atZone(zone).toInstant().toEpochMilli()
        val spec = WatchRules.parse(JSONObject("""{"data":["camera"],"model":"clef","interval_s":600,
            "questions":{"state":{"type":"choice","instructions":"Tidy?","criteria":{"clean":"c","messy":"m","dirty":"d"}}},
            "tally":{"track":"state","min_episode_readings":1}}""")).tally!!
        fun ans(l: String) = cc.bridgit.app.judgment.Jev.Answer("clef", JSONObject().put("state", JSONObject().put("type", "choice").put("choice", l).put("confidence", 0.9)), 0, 0.0, 0)
        val st = WatchTally.State(at("07:00:00"))
        var t = at("08:00:00")
        while (t < at("12:00:00")) { WatchTally.record(spec, st, t, ans("clean"), zone); t += 600_000 }
        WatchTally.record(spec, st, at("12:00:00"), ans("messy"), zone)
        t = at("12:05:00")
        while (t <= at("18:00:00")) { WatchTally.record(spec, st, t, ans("clean"), zone); t += 600_000 }
        // Saved and loaded again, as the phone does.
        val back = WatchTally.State.from(st.json())
        val eps = WatchTally.episodesOf(spec, back).map { PeopleRules.Ep(it.label, it.start, it.last, it.before) }
        assertEquals(listOf(PeopleRules.MessEvent(at("12:00:00"), "messy", at("11:50:00"))), PeopleRules.messEvents(eps, after = 0))
        // Episodes saved before the stamp existed fall back to closed clean episodes (none here).
        assertEquals(listOf(PeopleRules.MessEvent(at("12:00:00"), "messy", null)), PeopleRules.messEvents(eps.map { it.copy(before = null) }, after = 0))
    }

    private fun sighting(id: String, at: Long, q: Double = 0.5) = PeopleRules.Sighting(id, at, "b", "Zm9v", null, null, 0.02, q, PeopleRules.Box(0.1, 0.2, 0.3, 0.4))
    private fun burst(id: String, at: Long, n: Int, agent: String = "a1") = PeopleRules.Burst(id, agent, "w1", at, n, (1..n).map { sighting("$id-$it", at) })

    @Test fun queueKeepsAtMost48AndSixHours() {
        val q = PeopleRules.Queue()
        val t0 = 1_000_000_000L
        for (i in 0 until 13) q.add(burst("b$i", t0 + i * 1000L, 4), t0 + i * 1000L)
        assertEquals(48, q.sightings(null, t0 + 13_000))
        assertEquals("b1", q.next("a1", t0 + 13_000)!!.id) // the oldest burst went first
        // A burst of more than 4 faces keeps 4.
        q.add(burst("big", t0 + 20_000, 6, agent = "a2"), t0 + 20_000)
        assertEquals(4, q.sightings("a2", t0 + 20_000))
        // 6 hours later all of the first ones are gone.
        assertEquals(0, q.sightings("a1", t0 + 13_000 + PeopleRules.QUEUE_MAX_AGE_MS))
        assertNull(q.next("a1", t0 + 13_000 + PeopleRules.QUEUE_MAX_AGE_MS))
        q.drop("a2"); assertEquals(0, q.sightings(null, t0 + 20_000))
        q.add(burst("x", t0, 0), t0); assertEquals(0, q.sightings(null, t0))
    }

    @Test fun bestKeepsFourBestAndSightingJson() {
        val all = (1..6).map { sighting("s$it", 1, it / 10.0) }
        assertEquals(listOf("s6", "s5", "s4", "s3"), PeopleRules.best(all).map { it.id })
        val s = PeopleRules.Sighting("id1", 5, "b1", "Zm9v", floatArrayOf(0.6f, 0.8f), FaceModel.ID, 0.031234, 0.7, PeopleRules.Box(0.1, 0.2, 0.3, 0.4))
        val j = s.json()
        assertEquals("id1", j.getString("id")); assertEquals("b1", j.getString("burst")); assertEquals("auraface-v1", j.getString("model"))
        assertEquals(2, j.getJSONArray("embedding").length()); assertEquals(0.0312, j.getDouble("face_share"), 1e-9)
        assertFalse(j.has("box"))
        assertTrue(sighting("n", 1).json().isNull("embedding")); assertTrue(sighting("n", 1).json().isNull("model"))
    }

    @Test fun personSeenRecordHasBoxesButNoIds() {
        val r = burst("b9", 1234, 2).record()
        assertEquals("person_seen", r.getString("kind")); assertEquals(1234L, r.getLong("at")); assertEquals("face", r.getString("label"))
        assertEquals(2, r.getInt("count"))
        val item = r.getJSONArray("items").getJSONObject(0)
        assertEquals("face", item.getString("label")); assertEquals(50, item.getInt("confidence"))
        assertEquals(4, item.getJSONArray("box").length()); assertEquals(0.1, item.getJSONArray("box").getDouble(0), 0.0)
        assertFalse(r.toString().contains("b9"))
    }

    @Test fun peopleWatchDerivesPeopleSeen() {
        val plan = AppletPackage.Plan(listOf("people" to JSONObject("""{$base,"people":{}}"""), "main" to JSONObject("""{"data":["camera"],"question":"x"}""")),
            null, emptyList(), null, null, JSONObject())
        val d = AppletPackage.derived(plan, { data, _ -> WatchRules.grantsFor(data) }, { null }) { emptySet() }
        assertTrue("people_seen" in d)
        val plain = AppletPackage.Plan(listOf("main" to JSONObject("""{"data":["camera"],"question":"x"}""")), null, emptyList(), null, null, JSONObject())
        assertFalse("people_seen" in AppletPackage.derived(plain, { data, _ -> WatchRules.grantsFor(data) }, { null }) { emptySet() })
        assertEquals(setOf("people_seen"), AppletPackage.added(setOf("camera", "watches"), setOf("camera", "watches", "people_seen")))
    }
}
