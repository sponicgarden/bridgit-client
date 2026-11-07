package cc.bridgit.app.remoteview

import cc.bridgit.app.watch.WatchTally
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteViewPackageTest {
    private val questions = JSONObject("""{visible:{type:'noul',instructions:'Kitchen visible?'},
        state:{type:'choice',instructions:'How tidy?',criteria:{clean:'a',messy:'b',dirty:'c',unclear:'d'}}}""")
    private val tally = WatchTally.parse(JSONObject("""{track:'state',labels:['clean','messy','dirty'],min_confidence:0.2,gap_s:1800,keep_days:30,seen:{answer:'visible',min:0.6}}"""),
        org.json.JSONArray("""[{id:'dirty-twice',when:{at_least:2,of:'dirty',within_s:3600},do:{method:'POST',path:'/v1/interactions',body:{kind:'notice',title:'Kitchen needs a clean-up',text:'x'}}}]"""),
        questions, 600, { _, _ -> })!!

    private fun check(state: String, conf: Double, visible: Double) = JSONObject().put("at", "2026-11-04T18:30:00+01:00").put("model", "clef")
        .put("answers", JSONObject().put("state", JSONObject().put("type", "choice").put("choice", state).put("confidence", conf))
            .put("visible", JSONObject().put("type", "noul").put("noul", visible))
            .put("note", JSONObject().put("type", "text").put("text", "free words never leave")))

    @Test fun latestCarriesOnlyFixedWordsAndWhetherItCounted() {
        val l = RemoteViewPackage.latest(tally, check("messy", 0.24, 0.9))!!
        assertEquals("messy", l.getString("label")); assertEquals(24, l.getInt("confidence")); assertTrue(l.getBoolean("counted"))
        assertEquals("yes", l.getJSONObject("answers").getJSONObject("visible").getString("value"))
        assertFalse(l.getJSONObject("answers").has("note"))
        assertEquals(java.time.OffsetDateTime.parse("2026-11-04T18:30:00+01:00").toInstant().toEpochMilli(), l.getLong("at"))
        // Kitchen not in view: shown, but not counted.
        assertFalse(RemoteViewPackage.latest(tally, check("dirty", 0.9, 0.2))!!.getBoolean("counted"))
        // "unclear" isn't a counted label: no label, still the answers.
        assertTrue(RemoteViewPackage.latest(tally, check("unclear", 0.9, 0.9))!!.isNull("label"))
        assertEquals(null, RemoteViewPackage.latest(tally, JSONObject().put("at", "2026-11-04T18:30:00+01:00")))
    }

    @Test fun summaryKeepsCountsRuleTitlesAndFiredTimes() {
        val ts = JSONObject("""{today:{date:'2026-11-04',checks:5,watched_minutes:50,labels:{clean:{episodes:1,minutes:10,readings:1},dirty:{episodes:2,minutes:20,readings:3},unclear:{episodes:1}}},
            rules:[{id:'dirty-twice',last_fired_at:'2026-11-04T19:00:00+01:00'}]}""")
        val s = RemoteViewPackage.summary("Kitchen cleanliness monitor", tally, ts, check("dirty", 0.8, 0.9), running = true, paused = false,
            photoOn = true, intervalS = 600, rulesFired = 1, lastCheckAt = 5, appVersion = "1.8.1")
        assertEquals("state", s.getString("track"))
        assertEquals(3, s.getJSONObject("today").getJSONObject("labels").getJSONObject("dirty").getInt("readings"))
        assertFalse(s.getJSONObject("today").getJSONObject("labels").has("unclear"))
        val rule = s.getJSONArray("rules").getJSONObject(0)
        assertEquals("Kitchen needs a clean-up", rule.getString("title"))
        assertEquals(java.time.OffsetDateTime.parse("2026-11-04T19:00:00+01:00").toInstant().toEpochMilli(), rule.getLong("last_fired_at"))
        assertEquals("pkg.kitchen-cleanliness", RemoteViewPackage.appletId("kitchen-cleanliness"))
    }

    @Test fun sendsWhenChangedAtMostOnceAMinuteAndRefreshesEveryFive() {
        assertTrue(RemoteViewPackage.due("a", null, 0, 1_000_000))
        assertFalse(RemoteViewPackage.due("b", "a", 1_000_000, 1_030_000))
        assertTrue(RemoteViewPackage.due("b", "a", 1_000_000, 1_060_000))
        assertFalse(RemoteViewPackage.due("a", "a", 1_000_000, 1_200_000))
        assertTrue(RemoteViewPackage.due("a", "a", 1_000_000, 1_300_000))
    }

    @Test fun recordsForTheTimelineAndLeftOutFinds() {
        val r = RemoteViewPackage.stateRecord(tally, check("dirty", 0.8, 0.9))!!
        assertEquals("state", r.getString("kind")); assertEquals("dirty", r.getString("label")); assertTrue(r.getBoolean("counted"))
        assertEquals("frying_pan", RemoteViewPackage.itemLabel("Frying pan!"))
        assertEquals(null, RemoteViewPackage.itemLabel("  ??  "))
        val lo = RemoteViewPackage.leftOut(listOf(Triple("Knife", listOf(0.1, 0.2, 0.3, 1.2), 0.876), Triple("???", listOf(0.0, 0.0, 1.0, 1.0), null)), 42L)
        assertEquals("left_out", lo.getString("kind")); assertEquals(1, lo.getJSONArray("items").length())
        val item = lo.getJSONArray("items").getJSONObject(0)
        assertEquals("knife", item.getString("label")); assertEquals(1.0, item.getJSONArray("box").getDouble(3), 0.0); assertEquals(88, item.getInt("confidence"))
    }
}
