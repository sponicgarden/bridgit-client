package cc.bridgit.app.watch

import cc.bridgit.app.judgment.Jev
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WatchTallyTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    private val dogQuestions = """{
        "dog":{"type":"noul","instructions":"Is a dog visible?"},
        "activity":{"type":"choice","instructions":"What is the dog doing?","criteria":{"eating":"e","drinking":"d","sleeping":"s","other":"o"}}}"""

    private fun body(extra: String) = JSONObject("""{"questions":$dogQuestions,"data":["camera"]${if ("interval_s" in extra) "" else ",\"interval_s\":60"}$extra}""")
    private fun spec(extra: String = ""","tally":{"track":"activity","seen":{"answer":"dog","min":0.6}}""") = WatchRules.parse(body(extra))

    private fun bad(extra: String, contains: String) {
        try { WatchRules.parse(body(extra)); fail("expected refusal for $extra") }
        catch (e: WatchRules.Bad) { assertTrue("${e.message} should mention $contains", e.message!!.contains(contains)) }
    }

    private fun dog(activity: String?, present: Double = 0.9, conf: Double = 0.9): Jev.Answer {
        val a = JSONObject().put("dog", JSONObject().put("type", "noul").put("noul", present))
        if (activity != null) a.put("activity", JSONObject().put("type", "choice").put("choice", activity).put("confidence", conf))
        return Jev.Answer("clef", a, 0, 0.0, 0)
    }

    @Test fun plainWatchesKeepTheirOldDefaults() {
        val s = WatchRules.parse(JSONObject("""{"question":"Is the phone on a bicycle?"}"""))
        assertNull(s.tally); assertFalse(s.repeat); assertEquals(7200, s.expiresS)
        // A plain repeat watch still doesn't ask during its cooldown.
        val r = WatchRules.parse(JSONObject("""{"question":"x?","repeat":true,"cooldown_s":600,"min_readings":1}"""))
        assertFalse(WatchRules.shouldAsk(r, 3, lastMatchAt = 1_000L, now = 100_000L))
        assertTrue(WatchRules.callbackDue(r, 1_000L, 100_000L))
        bad("", "callback_when")
    }

    @Test fun tallyDefaults() {
        val s = spec()
        val t = s.tally!!
        assertEquals("activity", t.track); assertEquals("choice", t.kind); assertNull(t.labels)
        assertEquals(180, t.gapS); assertEquals(30, t.keepDays)
        assertTrue(s.repeat); assertTrue(s.conditions.isEmpty())
        assertEquals(WatchRules.DEFAULT_EXPIRES_TALLY_S, s.expiresS)
        // A tally keeps asking during the callback cooldown; only the callback waits.
        assertTrue(WatchRules.shouldAsk(s, 1, lastMatchAt = 1_000L, now = 100_000L))
        assertFalse(WatchRules.callbackDue(s, 1_000L, 100_000L))
    }

    @Test fun tallyFastIntervalNeedsCameraAndChangeCheck() {
        assertEquals(10, WatchRules.parse(body(""","interval_s":10,"max_paid_per_hour":60,"tally":{"track":"activity"}""")).intervalS)
        bad(""","interval_s":10,"local":{"change_check":false},"tally":{"track":"activity"}""", "change check")
        try { WatchRules.parse(JSONObject("""{"question":"x?","interval_s":10,"tally":{}}""")); fail() } catch (e: WatchRules.Bad) { assertTrue(e.message!!.contains("interval_s")) }
        assertEquals(90 * 86400, WatchRules.parse(body(""","expires_in_s":7776000,"tally":{"track":"activity"}""")).expiresS)
    }

    @Test fun trackedLabelsCanHaveDifferentEpisodeGapsWithoutExtendingCoverage() {
        val tally = spec(""","tally":{"track":"activity","gap_s":60,"label_gap_s":{"sleeping":120}}""").tally!!
        assertEquals(120, tally.json().getJSONObject("label_gap_s").getInt("sleeping"))
        val start = at("2026-11-03T08:00:00")
        val state = WatchTally.State(start)
        for ((seconds, label) in listOf(0 to "sleeping", 1 to "eating", 120 to "sleeping", 121 to "eating")) {
            WatchTally.record(tally, state, start + seconds * 1000L, dog(label), zone)
        }
        assertEquals(1, WatchTally.episodesOf(tally, state, "sleeping").size)
        assertEquals(2, WatchTally.episodesOf(tally, state, "eating").size)
        assertEquals(2, state.spans.size)
        bad(""","tally":{"track":"activity","labels":["eating"],"label_gap_s":{"sleeping":120}}""", "tracked label")
        bad(""","tally":{"track":"activity","label_gap_s":{"eating":0}}""", "whole number")
        bad(""","tally":{"track":"activity","label_gap_s":{"eating":"60"}}""", "whole number")
    }

    @Test fun continuousGapSkipsUnseenTimeAcrossRestart() {
        val t = spec(""","tally":{"track":"activity","gap_s":60,"label_gap_s":{"drinking":5},"typical_gap_basis":"continuous_seen"}""").tally!!
        val start = at("2026-11-03T08:00:00")
        var state = WatchTally.State(start)
        fun record(sec: Int, label: String?) {
            WatchTally.record(t,state,start + sec*1000L,label?.let { dog(it) },zone)
        }
        record(0,"drinking"); record(10,null)
        state = WatchTally.State.from(state.json())
        record(20,"drinking")
        assertEquals(50_000L, WatchTally.seenMs(state, start, start+60_000, 30))
        assertNull(WatchTally.typicalGapMin(t,state,"drinking",start+20000))
        record(50,"drinking")
        assertEquals(0.5,WatchTally.typicalGapMin(t,state,"drinking",start+50000)!!,0.001)
        assertEquals("continuous_seen",t.json().getString("typical_gap_basis"))
        bad(""","tally":{"track":"activity","typical_gap_basis":"guess"}""","typical_gap_basis")
    }

    @Test fun tallyRefusals() {
        bad(""","tally":{"track":"nope"}""", "isn't one of your questions")
        bad(""","tally":{"track":"activity","labels":["napping"]}""", "napping")
        bad(""","tally":{"track":"activity","min":0.5}""", "min_confidence")
        bad(""","tally":{"track":"activity","bogus":1}""", "bogus")
        bad(""","rules":[]""", "tally")
        bad(""","tally":{"track":"activity"},"repeat":false,"callback_when":{"answer":"dog","min":0.9}""", "repeat")
        bad(""","tally":{"track":"activity"},"rules":[{"when":{"none_for_s":3600,"of":"barking"}}]""", "barking")
        bad(""","tally":{"track":"activity"},"rules":[{"when":{"none_for_s":3600,"at_least":2,"of":"eating"}}]""", "exactly one")
        bad(""","tally":{"track":"activity"},"rules":[{"when":{"fewer_than":1,"of":"eating","by":"25:00"}}]""", "by")
        bad(""","tally":{"track":"activity"},"rules":[{"id":"a","when":{"at_least":1,"of":"eating","within_s":60}},{"id":"a","when":{"at_least":1,"of":"eating","within_s":60}}]""", "twice")
        try { WatchRules.parse(JSONObject("""{"data":["camera"],"model":"none","tally":{}}""")); fail() } catch (e: WatchRules.Bad) { assertTrue(e.message!!.contains("model none")) }
    }

    @Test fun ruleCallIsChecked() {
        var seen: String? = null
        WatchRules.parse(body(""","tally":{"track":"activity"},"rules":[{"when":{"at_least":1,"of":"eating","within_s":60},"do":{"path":"/v1/speaker/say","body":{"text":"{message}"}}}]""")) { _, at -> seen = at }
        assertEquals("rules[0].do", seen)
        try {
            WatchRules.parse(body(""","tally":{"track":"activity"},"rules":[{"when":{"at_least":1,"of":"eating","within_s":60},"do":{"path":"/v1/mcp"}}]""")) { _, at -> throw WatchRules.Bad("$at: no") }
            fail()
        } catch (e: WatchRules.Bad) { assertEquals("rules[0].do: no", e.message) }
    }

    @Test fun episodesMergeAcrossShortGapsAndOtherAnswers() {
        val s = spec(); val t = s.tally!!
        val st = WatchTally.State(at("2026-11-03T08:00:00"))
        // Eating 08:00-08:03, one "other" in the middle, then nothing for 10 min.
        WatchTally.record(t, st, at("2026-11-03T08:00:00"), dog("eating"), zone)
        WatchTally.record(t, st, at("2026-11-03T08:01:00"), dog("other"), zone)
        WatchTally.record(t, st, at("2026-11-03T08:02:00"), dog("eating"), zone)
        WatchTally.record(t, st, at("2026-11-03T08:03:00"), dog("eating"), zone)
        assertEquals(1, st.open.count { it.key == "eating" })
        // Gap of 10 min (> gap_s 180 s): the meal ends; a new one starts.
        val closed = WatchTally.record(t, st, at("2026-11-03T08:13:00"), dog("eating"), zone)
        assertEquals(listOf("eating", "other"), closed.map { it.label }.sorted())
        val eating = WatchTally.episodesOf(t, st, "eating")
        assertEquals(2, eating.size)
        assertEquals(4 * 60_000L, WatchTally.lengthMs(eating[0], s.intervalS))
        val sum = WatchTally.summary(t, st, at("2026-11-03T08:14:00"), zone, s.intervalS)
        val today = sum.getJSONObject("today").getJSONObject("labels").getJSONObject("eating")
        assertEquals(2, today.getInt("episodes")); assertEquals(4, today.getInt("readings")); assertEquals(5.0, today.getDouble("minutes"), 0.01)
        assertTrue(sum.getJSONObject("ongoing").has("eating"))
        assertTrue(WatchTally.todayLine(t, st, at("2026-11-03T08:14:00"), zone, s.intervalS).startsWith("eating 2 times"))
    }

    @Test fun notSeenAndUnsureAreNotCounted() {
        val s = spec(); val t = s.tally!!
        val st = WatchTally.State(at("2026-11-03T08:00:00"))
        WatchTally.record(t, st, at("2026-11-03T08:00:00"), dog("eating", present = 0.2), zone) // no dog: not seen
        WatchTally.record(t, st, at("2026-11-03T08:01:00"), dog("eating", conf = 0.3), zone)    // unsure
        WatchTally.record(t, st, at("2026-11-03T08:02:00"), null, zone)                          // no photo
        assertTrue(WatchTally.episodesOf(t, st).isEmpty())
        val d = st.days["2026-11-03"]!!
        assertEquals(3, d["_checks"]); assertEquals(1, d["_seen"])
    }

    @Test fun dayTotalsSplitAtMidnight() {
        val s = spec(); val t = s.tally!!
        val st = WatchTally.State(at("2026-11-02T23:00:00"))
        WatchTally.record(t, st, at("2026-11-02T23:58:00"), dog("drinking"), zone)
        WatchTally.record(t, st, at("2026-11-03T00:30:00"), dog("drinking"), zone)
        val sum = WatchTally.summary(t, st, at("2026-11-03T00:31:00"), zone, s.intervalS)
        assertEquals("2026-11-03", sum.getJSONObject("today").getString("date"))
        assertEquals(1, sum.getJSONObject("today").getJSONObject("labels").getJSONObject("drinking").getInt("episodes"))
        val yesterday = sum.getJSONArray("days").getJSONObject(1)
        assertEquals("2026-11-02", yesterday.getString("date"))
        assertEquals(1, yesterday.getJSONObject("labels").getJSONObject("drinking").getInt("episodes"))
    }

    @Test fun typicalGapIsTheMedian() {
        val s = spec(); val t = s.tally!!
        val st = WatchTally.State(at("2026-11-03T06:00:00"))
        listOf("07:00", "08:00", "10:00", "10:40").forEach { WatchTally.record(t, st, at("2026-11-03T$it:00"), dog("drinking"), zone) }
        assertEquals(60.0, WatchTally.typicalGapMin(t, st, "drinking", at("2026-11-03T11:00:00"))!!, 0.01)
    }

    @Test fun noneForCountsOnlyWatchedTime() {
        val s = spec(""","tally":{"track":"activity","seen":{"answer":"dog","min":0.6}},"rules":[{"id":"water","when":{"none_for_s":3600,"of":"drinking"}}]""")
        val t = s.tally!!
        val st = WatchTally.State(at("2026-11-03T08:00:00"))
        WatchTally.record(t, st, at("2026-11-03T08:00:00"), dog("drinking"), zone)
        // 30 min of the dog sleeping in view, then 2 h with no dog (phone fell over): only 30 min watched.
        var m = at("2026-11-03T08:01:00")
        while (m <= at("2026-11-03T08:30:00")) { WatchTally.record(t, st, m, dog("sleeping"), zone); m += 60_000 }
        while (m <= at("2026-11-03T10:30:00")) { WatchTally.record(t, st, m, null, zone); m += 60_000 }
        assertTrue(WatchTally.due(t, st, m, zone, s.intervalS).isEmpty())
        // Another 35 min watched without a drink: now it fires, once.
        repeat(35) { WatchTally.record(t, st, m, dog("sleeping"), zone); m += 60_000 }
        val fired = WatchTally.due(t, st, m, zone, s.intervalS)
        assertEquals(listOf("water"), fired.map { it.rule.id })
        assertTrue(fired[0].message, fired[0].message.startsWith("No drinking for 1 h"))
        assertTrue(WatchTally.due(t, st, m + 60_000, zone, s.intervalS).isEmpty())
    }

    @Test fun fewerThanByFiresOnceAfterTheDeadline() {
        val s = spec(""","tally":{"track":"activity"},"rules":[{"id":"breakfast","when":{"fewer_than":1,"of":"eating","by":"10:00"}}]""")
        val t = s.tally!!
        val st = WatchTally.State(at("2026-11-03T07:00:00"))
        WatchTally.record(t, st, at("2026-11-03T09:00:00"), dog("sleeping"), zone)
        assertTrue(WatchTally.due(t, st, at("2026-11-03T09:59:00"), zone, s.intervalS).isEmpty())
        WatchTally.record(t, st, at("2026-11-03T10:01:00"), dog("sleeping"), zone)
        assertEquals(1, WatchTally.due(t, st, at("2026-11-03T10:01:00"), zone, s.intervalS).size)
        assertTrue(WatchTally.due(t, st, at("2026-11-03T11:00:00"), zone, s.intervalS).isEmpty())
        // Started after the deadline: quiet that day.
        val late = WatchTally.State(at("2026-11-03T11:00:00"))
        WatchTally.record(t, late, at("2026-11-03T11:01:00"), dog("sleeping"), zone)
        assertTrue(WatchTally.due(t, late, at("2026-11-03T11:01:00"), zone, s.intervalS).isEmpty())
    }

    @Test fun atLeastWithinRespectsCooldown() {
        val s = spec(""","tally":{"track":"activity"},"rules":[{"id":"snacks","when":{"at_least":2,"of":"eating","within_s":3600},"do":{"method":"POST","path":"/v1/speaker/say","body":{"text":"{label}: {message}"}}}]""")
        val t = s.tally!!
        val st = WatchTally.State(at("2026-11-03T08:00:00"))
        WatchTally.record(t, st, at("2026-11-03T08:00:00"), dog("eating"), zone)
        assertTrue(WatchTally.due(t, st, at("2026-11-03T08:00:00"), zone, s.intervalS).isEmpty())
        WatchTally.record(t, st, at("2026-11-03T08:10:00"), dog("eating"), zone)
        val f = WatchTally.due(t, st, at("2026-11-03T08:10:00"), zone, s.intervalS).single()
        assertEquals("2 eating in the last 1 h.", f.message)
        val filled = WatchTally.fill(f.rule.call!!, f.message, "Dog")
        assertEquals("Dog: 2 eating in the last 1 h.", filled.getJSONObject("body").getString("text"))
        WatchTally.record(t, st, at("2026-11-03T08:20:00"), dog("eating"), zone)
        assertTrue(WatchTally.due(t, st, at("2026-11-03T08:20:00"), zone, s.intervalS).isEmpty())
    }

    @Test fun stateSurvivesJson() {
        val s = spec(); val t = s.tally!!
        val st = WatchTally.State(at("2026-11-03T08:00:00"))
        WatchTally.record(t, st, at("2026-11-03T08:00:00"), dog("eating"), zone)
        WatchTally.record(t, st, at("2026-11-03T08:30:00"), dog("drinking"), zone)
        st.ruleFiredAt["x"] = 5L; st.ruleDay["y"] = "2026-11-03"
        val back = WatchTally.State.from(JSONObject(st.json().toString()))
        assertEquals(st.json().toString(), back.json().toString())
        assertNotNull(back.open["drinking"])
    }

    @Test fun oldHistoryIsDropped() {
        val s = spec(""","tally":{"track":"activity","keep_days":2}"""); val t = s.tally!!
        val st = WatchTally.State(at("2026-11-01T08:00:00"))
        WatchTally.record(t, st, at("2026-11-01T08:00:00"), dog("eating"), zone)
        WatchTally.record(t, st, at("2026-11-04T08:00:00"), dog("eating"), zone)
        assertEquals(1, WatchTally.episodesOf(t, st).size)
        assertFalse(st.days.containsKey("2026-11-01"))
    }

    @Test fun noulTrackCountsItsOwnId() {
        val s = WatchRules.parse(JSONObject("""{"question":"Is someone in the kitchen?","data":["camera"],"tally":{}}"""))
        val t = s.tally!!
        assertEquals("match", t.track); assertEquals("noul", t.kind); assertTrue(s.conditions.isEmpty())
        val st = WatchTally.State(0)
        val yes = Jev.Answer("clef", JSONObject().put("match", JSONObject().put("type", "noul").put("noul", 0.8)), 0, 0.0, 0)
        WatchTally.record(t, st, 60_000, yes, zone)
        assertEquals("match", WatchTally.episodesOf(t, st).single().label)
    }
}
