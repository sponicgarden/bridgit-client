package cc.bridgit.app.watch

import cc.bridgit.app.judgment.Jev
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId

class WatchRulesTest {
    private fun bad(body: String, contains: String) {
        try { WatchRules.parse(JSONObject(body)); fail("expected refusal for $body") }
        catch (e: WatchRules.Bad) { assertTrue("${e.message} should mention $contains", e.message!!.contains(contains)) }
    }

    private fun answer(json: String) = Jev.Answer("jev-1.14.0", JSONObject(json), 100, 0.0, 300)

    @Test fun rotationUsesIndependentChangeReferencesAndOneSharedLimit() {
        val request = JSONObject("""{"question":"Pet visible?","data":["camera"],"camera":"rear","additional_cameras":["front","lan:Desk"],"max_paid_per_hour":3}""")
        val spec = WatchRules.parse(request)
        val watch = Watches.Watch("w","agent","test",request,spec,0,10000)
        assertEquals(listOf("rear","front","lan:Desk","rear"), (1..4).map { watch.nextCamera() })
        assertEquals(3,spec.paidPerHour)
        val firstGate = watch.gate
        watch.reference = intArrayOf(1)
        watch.sampleCamera = "front"
        assertNull(watch.reference)
        assertTrue(firstGate !== watch.gate)
        watch.sampleCamera = "rear"
        assertEquals(1,watch.reference!!.single())
        assertTrue(firstGate === watch.gate)
        for (invalid in listOf("""["rear"]""","""["lan:Desk","lan:Desk"]""")) {
            bad(JSONObject(request.toString()).put("additional_cameras",JSONArray(invalid)).toString(),"only once")
        }
    }

    @Test fun simpleQuestionGetsDefaults() {
        val s = WatchRules.parse(JSONObject("""{"question":"Is the phone on a bicycle?"}"""))
        assertEquals("Is the phone on a bicycle?", s.label)
        assertEquals(listOf("location", "motion"), s.data)
        assertEquals(60, s.intervalS); assertEquals(5, s.window); assertEquals(3, s.minReadings)
        assertEquals(7200, s.expiresS); assertFalse(s.repeat); assertNull(s.callbackUrl); assertEquals("jev", s.model)
        val q = s.questions.getJSONObject("match")
        assertEquals("noul", q.getString("type"))
        assertTrue(q.getString("instructions").startsWith("Is the phone on a bicycle?"))
        assertEquals(listOf(WatchRules.Condition("match", null, 0.0, 0.8, null)), s.conditions)
        assertEquals(60, s.checksPerHour)
    }

    @Test fun simpleQuestionTakesMinConfidenceAndOptions() {
        val s = WatchRules.parse(JSONObject("""{"question":"Looking for parking?","min_probability":0.9,"data":["location","device","location"],
            "interval_s":30,"window":10,"min_readings":4,"repeat":true,"cooldown_s":900,"callback_url":" http://100.64.1.2:9000/x ",
            "context":"Owner drives a car in Warsaw","label":"parking","expires_in_s":3600}"""))
        assertEquals(0.9, s.conditions.single().min!!, 0.0)
        assertEquals(listOf("location", "device"), s.data)
        assertEquals(30, s.intervalS); assertEquals(10, s.window); assertEquals(4, s.minReadings)
        assertTrue(s.repeat); assertEquals(900, s.cooldownS); assertEquals("http://100.64.1.2:9000/x", s.callbackUrl)
        assertEquals("parking", s.label); assertEquals(120, s.checksPerHour)
        assertEquals("/v1/jev", WatchRules.route(s.model))
        val c = WatchRules.parse(JSONObject("""{"question":"x","model":" Clef "}"""))
        assertEquals("clef", c.model); assertEquals("/v1/clef", WatchRules.route(c.model))
        assertEquals(setOf("location"), WatchRules.grantsFor(s.data))
    }

    @Test fun refusesBadBodies() {
        bad("""{}""", "question")
        bad("""{"question":"a","questions":{}}""", "just one")
        bad("""{"question":""}""", "question must be text")
        bad("""{"question":"a","data":["microphone"]}""", "microphone isn't")
        bad("""{"question":"a","data":[]}""", "at least one")
        bad("""{"question":"a","interval_s":10}""", "interval_s")
        bad("""{"question":"a","interval_s":60.5}""", "interval_s")
        bad("""{"question":"a","window":21}""", "window")
        bad("""{"question":"a","window":2,"min_readings":3}""", "min_readings")
        bad("""{"question":"a","min_probability":1.5}""", "min_probability")
        bad("""{"question":"a","min_confidence":0.5}""", "min_confidence isn't a watch field")
        bad("""{"question":"a","cooldown_s":600}""", "repeat")
        bad("""{"question":"a","model":"gpt"}""", "model")
        bad("""{"question":"a","callback_when":{"answer":"match","choice":"yes"}}""", "callback_when goes with questions")
        bad("""{"question":"a","colour":"red"}""", "colour isn't a watch field")
        bad("""{"question":"a","expires_in_s":90000}""", "expires_in_s")
    }

    @Test fun typedQuestionsNeedValidConditions() {
        val q = """{"mode":{"type":"choice","instructions":"How is the owner travelling?","criteria":{"walk":"on foot","bike":"cycling","car":"driving","still":"not moving"}},
            "parking":{"type":"noul","instructions":"Is the owner looking for a parking spot?"}}"""
        val s = WatchRules.parse(JSONObject("""{"questions":$q,"callback_when":[{"answer":"mode","choice":"car","min_confidence":0.7},{"answer":"parking","min":0.75}]}"""))
        assertNull(s.question)
        assertEquals(2, s.conditions.size)
        assertEquals(0.75, s.conditions[1].min!!, 0.0)
        assertEquals("How is the owner travelling?", s.label)
        bad("""{"questions":$q}""", "callback_when")
        bad("""{"questions":$q,"callback_when":{"answer":"nope","choice":"car"}}""", "name one of your questions")
        bad("""{"questions":$q,"callback_when":{"answer":"mode","choice":"plane"}}""", "isn't one of mode's options")
        bad("""{"questions":$q,"callback_when":{"answer":"parking","min":0.9,"max":0.1}}""", "min is above max")
        bad("""{"questions":$q,"callback_when":{"answer":"parking"}}""", "needs choice")
        bad("""{"questions":$q,"callback_when":{"answer":"parking","min_confidence":0.5}}""", "needs a choice")
        bad("""{"questions":{"bad id":{"type":"choice","instructions":"x"}},"callback_when":{"answer":"bad id","min":0.5}}""", "Question id")
        bad("""{"questions":{"a":{"type":"choice"}},"callback_when":{"answer":"a","min":0.5}}""", "instructions")
        bad("""{"questions":$q,"callback_when":{"answer":"mode","choice":"car"},"min_probability":0.5}""", "min_probability goes with question")
    }

    @Test fun matchingFollowsConditions() {
        val c = WatchRules.parse(JSONObject("""{"question":"Bike?"}""")).conditions
        assertTrue(WatchRules.matches(c, answer("""{"match":{"type":"noul","noul":0.85}}""")))
        assertFalse(WatchRules.matches(c, answer("""{"match":{"type":"noul","noul":0.6}}""")))
        assertFalse(WatchRules.matches(c, answer("""{"match":{"type":"choice","choice":"yes","confidence":0.99}}""")))
        assertFalse(WatchRules.matches(c, answer("""{}""")))
        val ch = listOf(WatchRules.Condition("m", "yes", 0.8, null, null))
        assertTrue(WatchRules.matches(ch, answer("""{"m":{"type":"choice","choice":"yes","confidence":0.85}}""")))
        assertFalse(WatchRules.matches(ch, answer("""{"m":{"type":"choice","choice":"yes","confidence":0.6}}""")))
        assertFalse(WatchRules.matches(ch, answer("""{"m":{"type":"choice","choice":"no","confidence":0.99}}""")))
        val p = listOf(WatchRules.Condition("p", null, 0.0, 0.7, null), WatchRules.Condition("q", null, 0.0, null, 0.2))
        assertTrue(WatchRules.matches(p, answer("""{"p":{"type":"noul","noul":0.8},"q":{"type":"noul","noul":0.1}}""")))
        assertFalse(WatchRules.matches(p, answer("""{"p":{"type":"noul","noul":0.8},"q":{"type":"noul","noul":0.3}}""")))
        assertFalse(WatchRules.matches(emptyList(), answer("""{"p":{"type":"noul","noul":0.8}}""")))
    }

    @Test fun asksOnceEnoughReadingsAndAfterCooldown() {
        val s = WatchRules.parse(JSONObject("""{"question":"x","repeat":true,"cooldown_s":600}"""))
        assertFalse(WatchRules.shouldAsk(s, 2, 0, 1_000_000))
        assertTrue(WatchRules.shouldAsk(s, 3, 0, 1_000_000))
        assertFalse(WatchRules.shouldAsk(s, 5, 1_000_000, 1_000_000 + 599_000))
        assertTrue(WatchRules.shouldAsk(s, 5, 1_000_000, 1_000_000 + 600_000))
        assertFalse(WatchRules.shouldAsk(s, 5, 0, 1_000_000, pausedUntil = 1_000_001))
        assertTrue(WatchRules.shouldAsk(s, 5, 0, 1_000_000, pausedUntil = 1_000_000))
    }

    @Test fun movementFromGpsOrBetweenFixes() {
        // About 111 m north per 0.001 degree; 60 s apart = 6.7 km/h.
        val r = WatchRules.withMovement(listOf(
            JSONObject().put("at_ms", 0L).put("location", JSONObject().put("latitude", 52.0).put("longitude", 21.0).put("fix_age_s", 0)),
            JSONObject().put("at_ms", 60_000L).put("location", JSONObject().put("latitude", 52.001).put("longitude", 21.0).put("fix_age_s", 0)),
            JSONObject().put("at_ms", 120_000L).put("location", JSONObject().put("latitude", 52.002).put("longitude", 21.0).put("speed_mps", 5.0).put("fix_age_s", 0)),
            JSONObject().put("at_ms", 180_000L).put("motion", JSONObject().put("accel_mean", 1.2)),
        ))
        val l0 = r[0].getJSONObject("location"); val l1 = r[1].getJSONObject("location"); val l2 = r[2].getJSONObject("location")
        assertFalse(l0.has("moved_m")); assertFalse(l0.has("speed_kmh"))
        assertEquals(111, l1.getInt("moved_m"))
        assertEquals(6.7, l1.getDouble("speed_kmh"), 0.05); assertEquals("between_fixes", l1.getString("speed_source"))
        assertEquals(18.0, l2.getDouble("speed_kmh"), 0.0); assertEquals("gps", l2.getString("speed_source"))
        assertFalse(r[3].has("location"))
    }

    @Test fun motionStats() {
        assertNull(WatchRules.motionStats(emptyList(), 2.0))
        val m = WatchRules.motionStats(listOf(1.0, 3.0), 2.04)!!
        assertEquals(2.0, m.getDouble("accel_mean"), 0.0); assertEquals(1.0, m.getDouble("accel_sd"), 0.0)
        assertEquals(3.0, m.getDouble("accel_max"), 0.0); assertEquals(2, m.getInt("samples")); assertEquals(2.0, m.getDouble("seconds"), 0.0)
    }

    @Test fun stateListsReadingsOldestFirstWithAge() {
        val s = WatchRules.parse(JSONObject("""{"question":"x","context":"Owner cycles to work","data":["battery"]}"""))
        val now = 1_798_000_000_000L
        val st = WatchRules.state(s, listOf(
            JSONObject().put("at_ms", now - 120_000).put("battery", JSONObject().put("percent", 50)),
            JSONObject().put("at_ms", now).put("battery", JSONObject().put("percent", 49)),
        ), now, ZoneId.of("Europe/Warsaw"))
        assertEquals("Owner cycles to work", st.getString("context"))
        assertEquals(60, st.getInt("interval_s"))
        assertEquals(JSONArray().put("battery").toString(), st.getJSONArray("data").toString())
        val rs = st.getJSONArray("readings")
        assertEquals(120, rs.getJSONObject(0).getInt("seconds_ago"))
        assertEquals(0, rs.getJSONObject(1).getInt("seconds_ago"))
        assertFalse(rs.getJSONObject(0).has("at_ms"))
        assertTrue(rs.getJSONObject(1).getString("at").endsWith("+01:00"))
        assertTrue(st.getString("weekday").isNotEmpty())
    }

    @Test fun budgetMath() {
        assertEquals(120, WatchRules.checksPerHour(30))
        assertEquals(2, WatchRules.checksPerHour(1800))
        assertEquals(1, WatchRules.checksPerHour(3600))
        assertEquals(46, WatchRules.checksPerHour(79))
        assertEquals(240, WatchRules.maxChecksPerHour("attested"))
        assertEquals(60, WatchRules.maxChecksPerHour("unverified"))
        assertEquals(60, WatchRules.maxChecksPerHour(null))
    }

    @Test fun withoutCameraNothingChangesForOldWatches() {
        val s = WatchRules.parse(JSONObject("""{"question":"Cycling?"}"""))
        assertEquals("jev", s.model); assertNull(s.camera); assertFalse(s.local.enabled); assertNull(s.maxPaidPerHour)
        assertEquals(s.checksPerHour, s.paidPerHour)
        assertEquals(JSONObject.NULL, s.json().get("local"))
        bad("""{"question":"a","local":{"change_check":true}}""", "add camera to data")
        bad("""{"question":"a","camera":"front"}""", "add camera to data")
        bad("""{"question":"a","model":"none"}""", "needs camera")
    }

    @Test fun cameraWatchDefaultsToClefWithTheChangeCheckOn() {
        val s = WatchRules.parse(JSONObject("""{"question":"Is a person at the door?","data":["camera"],"interval_s":30}"""))
        assertEquals("clef", s.model); assertEquals("rear", s.camera)
        assertTrue(s.local.enabled); assertEquals("normal", s.local.sensitivity); assertTrue(s.local.learnNoise)
        assertEquals(300_000L, s.local.maxReuseMs)
        assertEquals(1, s.window); assertEquals(1, s.minReadings) // only the newest photo is sent anyway
        assertEquals(setOf("camera"), WatchRules.grantsFor(s.data))
        // Mixed data keeps the usual history.
        val mixed = WatchRules.parse(JSONObject("""{"question":"x","data":["camera","location"],"model":"jev"}"""))
        assertEquals("jev", mixed.model); assertEquals(5, mixed.window)
        // Parsing what it reports gives the same spec back (watches are re-read from the saved request after a restart).
        assertEquals("clef", s.json().getString("model"))
        assertTrue(s.json().getJSONObject("local").getBoolean("change_check"))
    }

    @Test fun localOptionsAndPaidCap() {
        val s = WatchRules.parse(JSONObject("""{"question":"x","data":["camera"],"camera":"Front","local":{"change_check":true,"sensitivity":"high","learn_noise":false,"max_reuse_s":120},"max_paid_per_hour":20,"interval_s":30}"""))
        assertEquals("front", s.camera); assertEquals("high", s.local.sensitivity); assertFalse(s.local.learnNoise)
        assertEquals(120_000L, s.local.maxReuseMs); assertEquals(20, s.maxPaidPerHour)
        assertEquals(120, s.checksPerHour); assertEquals(20, s.paidPerHour) // the cap is what counts against the phone's limit
        val off = WatchRules.parse(JSONObject("""{"question":"x","data":["camera"],"local":{"change_check":false}}"""))
        assertFalse(off.local.enabled)
        bad("""{"question":"x","data":["camera"],"local":{"sensitivity":"extreme"}}""", "local.sensitivity")
        bad("""{"question":"x","data":["camera"],"local":{"max_reuse_s":5}}""", "local.max_reuse_s")
        bad("""{"question":"x","data":["camera"],"local":{"colour":1}}""", "local.colour")
        bad("""{"question":"x","data":["camera"],"local":true}""", "local must be an object")
        bad("""{"question":"x","data":["camera"],"max_paid_per_hour":0}""", "max_paid_per_hour")
        bad("""{"question":"x","data":["camera"],"camera":"side"}""", "rear or front")
    }

    @Test fun modelNoneIsLocalOnly() {
        val s = WatchRules.parse(JSONObject("""{"data":["camera"],"model":"none","repeat":true,"local":{"sensitivity":"low"}}"""))
        assertEquals("none", s.model); assertFalse(s.paid); assertEquals(0, s.paidPerHour)
        assertTrue(s.conditions.isEmpty()); assertEquals("Picture changes", s.label); assertEquals("low", s.local.sensitivity)
        bad("""{"data":["camera"],"model":"none","question":"a"}""", "takes no question")
        bad("""{"data":["camera"],"model":"none","local":{"change_check":false}}""", "can't be false")
        bad("""{"data":["camera"],"model":"none","max_paid_per_hour":5}""", "model none makes none")
        bad("""{"data":["camera"],"model":"clef"}""", "question")
    }

    @Test fun sameAnswerComparesPicksAndProbabilities() {
        val a = answer("""{"match":{"type":"noul","noul":0.10},"who":{"type":"choice","choice":"nobody","confidence":0.9}}""")
        val b = answer("""{"match":{"type":"noul","noul":0.20},"who":{"type":"choice","choice":"nobody","confidence":0.6}}""")
        val c = answer("""{"match":{"type":"noul","noul":0.60},"who":{"type":"choice","choice":"nobody","confidence":0.9}}""")
        val d = answer("""{"match":{"type":"noul","noul":0.10},"who":{"type":"choice","choice":"courier","confidence":0.9}}""")
        assertTrue(WatchRules.sameAnswer(a, b)); assertFalse(WatchRules.sameAnswer(a, c)); assertFalse(WatchRules.sameAnswer(a, d))
        assertFalse(WatchRules.sameAnswer(a, answer("""{"match":{"type":"noul","noul":0.10}}""")))
    }

    @Test fun stateNamesOnlyTheNewestPhoto() {
        val spec = WatchRules.parse(JSONObject("""{"question":"x","data":["camera","battery"],"window":3}"""))
        val now = 1_800_000_000_000L
        val r = { t: Long -> JSONObject().put("at_ms", t).put("camera", JSONObject().put("taken", true)).put("battery", JSONObject().put("percent", 50)) }
        val st = WatchRules.state(spec, listOf(r(now - 60_000), r(now - 30_000), r(now)), now, ZoneId.of("UTC"), photo = "photo-ref-1")
        val rs = st.getJSONArray("readings")
        assertEquals("photo-ref-1", rs.getJSONObject(2).getJSONObject("camera").getString("photo"))
        assertTrue(rs.getJSONObject(0).getJSONObject("camera").getString("photo").startsWith("not sent"))
        assertTrue(st.getString("about").contains("camera.photo"))
        // Without a photo (a reused answer), no reading claims one was sent.
        val none = WatchRules.state(spec, listOf(r(now)), now, ZoneId.of("UTC"))
        assertTrue(none.getJSONArray("readings").getJSONObject(0).getJSONObject("camera").getString("photo").startsWith("not sent"))
    }
    @Test fun enrolledCameraIdsPreserveCaseAndUseOnlyTheirRequiredSourceGrant() {
        val spec=WatchRules.parse(JSONObject("""{"question":"dog?","data":["camera"],"camera":"lan:Camera-One"}"""))
        assertEquals("lan:Camera-One",spec.camera)
        assertEquals(setOf("lan_camera"),WatchRules.grantsFor(spec.data,spec.camera))
        assertEquals("lan:Camera-One",spec.json().getString("camera"))
        bad("""{"question":"dog?","data":["camera"],"camera":"rtsp://user:secret@camera"}""", "rear or front")
    }

    @Test fun soundMicrophoneSelectionIsBoundedAndExplicit() {
        fun options(vararg ids:String) = JSONObject().put("sources",org.json.JSONArray(ids.toList()))
        assertEquals(listOf("builtin"), WatchRules.soundSources(null))
        assertTrue(WatchRules.soundSources(options()).isEmpty())
        assertEquals(listOf("lan:bowl"),WatchRules.soundSources(options("lan:bowl")))
        for (bad in listOf(options("rear"),options("lan:x","lan:x"),options("lan:a","lan:b","lan:c","lan:d","lan:e"))) {
            try { WatchRules.soundSources(bad); org.junit.Assert.fail("Accepted invalid sound sources") }
            catch (_:WatchRules.Bad) { }
        }
    }

    @Test fun soundWatch() {
        val s = WatchRules.parse(JSONObject("""{"data":["sound"],"question":"Is a baby crying?","repeat":true}"""))
        assertEquals("clef", s.model); assertEquals(30, s.intervalS); assertEquals(1, s.window); assertEquals(1, s.minReadings)
        assertEquals("normal", s.sound!!.sensitivity); assertEquals(300, s.sound!!.reuseS)
        assertEquals(6, s.maxPaidPerHour); assertEquals(6, s.paidPerHour)
        assertEquals(setOf("sounds"), WatchRules.grantsFor(s.data))
        val t = WatchRules.parse(JSONObject("""{"data":["sound"],"sound":{"sensitivity":"quiet","reuse_s":0},"max_paid_per_hour":12,
            "questions":{"sound":{"type":"choice","instructions":"What is it?","criteria":{"alarm":"A smoke alarm","other":"Anything else"}}},
            "tally":{"track":"sound","labels":["alarm"]}}"""))
        assertEquals("quiet", t.sound!!.sensitivity); assertEquals(0, t.sound!!.reuseS); assertEquals(12, t.paidPerHour)
        assertEquals(30, t.tally!!.gapS) // reuse 0: episodes as close as the detector's own gap
        assertEquals(300, WatchRules.parse(JSONObject("""{"data":["sound"],"questions":{"s":{"type":"noul","instructions":"Barking?"}},"tally":{"track":"s"}}""")).tally!!.gapS)
        bad("""{"data":["sound","location"],"question":"x?"}""", "alone")
        bad("""{"data":["sound"],"question":"x?","interval_s":60}""", "no interval_s")
        bad("""{"data":["sound"],"question":"x?","model":"jev"}""", "clef")
        bad("""{"data":["sound"],"question":"x?","sound":{"sensitivity":"huge"}}""", "sensitivity")
        bad("""{"data":["sound"],"question":"x?","sound":{"volume":1}}""", "sound.volume")
        bad("""{"question":"x?","sound":{}}""", "data [\"sound\"]")
    }
}
