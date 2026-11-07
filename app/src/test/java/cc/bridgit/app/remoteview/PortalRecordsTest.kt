package cc.bridgit.app.remoteview

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalRecordsTest {
    private fun bad(body: String) = try { PortalRecords.record(JSONObject(body), "workout", 1L); false } catch (e: PortalRecords.Bad) { true }

    @Test fun keepsWordsAndNumbersOnly() {
        val r = PortalRecords.record(JSONObject("""{"label":"moderate","metrics":{"score":72.456,"calories":9.1},
            "items":[{"label":"squats","count":12,"seconds":20}],"answers":{"focus":{"value":"mixed","confidence":70.4}},"extra":"ignored"}"""), "workout", 5L)
        assertEquals("workout", r.getString("kind")); assertEquals(5L, r.getLong("at"))
        assertEquals(72.46, r.getJSONObject("metrics").getDouble("score"), 1e-9)
        assertEquals(12, r.getJSONArray("items").getJSONObject(0).getInt("count"))
        assertEquals(70, r.getJSONObject("answers").getJSONObject("focus").getInt("confidence"))
        assertFalse(r.has("extra"))
    }

    @Test fun refusesFreeTextAndBadShapes() {
        assertTrue(bad("""{"label":"Has spaces"}"""))
        assertTrue(bad("""{"metrics":{"score":"high"}}"""))
        assertTrue(bad("""{"metrics":{"Bad Key":1}}"""))
        assertTrue(bad("""{"metrics":{"x":1e9}}"""))
        assertTrue(bad("""{"items":[{"label":"squats","count":-1}]}"""))
        assertTrue(bad("""{"items":[{"label":"squats","count":2.5}]}"""))
        assertTrue(bad("""{"answers":{"focus":{"value":"a long sentence"}}}"""))
        assertTrue(bad("""{}"""))
    }

    @Test fun declaredKind() {
        assertEquals("workout", PortalRecords.kindOf(JSONObject("""{"ui":{"portal":{"records":{"kind":"workout","title":"Workouts"}}}}""")))
        assertNull(PortalRecords.kindOf(JSONObject("""{"ui":{"portal":{"records":{"kind":"Bad Kind"}}}}""")))
        assertNull(PortalRecords.kindOf(JSONObject("{}")))
        assertNull(PortalRecords.checkDeclaration(JSONObject("""{"portal":{"records":{"kind":"workout","title":"Workouts"}}}""")))
        assertTrue(PortalRecords.checkDeclaration(JSONObject("""{"portal":{"records":{"kind":"workout"}}}""")) != null)
        assertNull(PortalRecords.checkDeclaration(JSONObject("{}")))
    }

    @Test fun queueKeepsTheNewestAndDropsSent() {
        var q = JSONArray()
        for (i in 1..60) q = PortalRecords.queued(q, JSONObject().put("at", i.toLong()))
        assertEquals(PortalRecords.MAX_PENDING, q.length())
        assertEquals(11L, q.getJSONObject(0).getLong("at"))
        val left = PortalRecords.without(q, setOf(11L, 12L))
        assertEquals(PortalRecords.MAX_PENDING - 2, left.length())
    }

    @Test fun recordOnlySummaryPassesTheBackendShape() {
        val s = PortalRecords.summary("Exercise check", "workout", paused = false, appVersion = "1.8.1")
        assertEquals("workout", s.getString("track")); assertEquals(0, s.getJSONArray("labels").length())
        assertFalse(s.getBoolean("photo_on")); assertTrue(s.getBoolean("running"))
    }
}
