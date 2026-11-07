package cc.bridgit.app.tour

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ways the City tour's state can go wrong: a tick or question that started before End/Pause/replacement
 * writes its old snapshot back (resurrecting an ended tour or un-pausing it); an agent ends a tour that was
 * replaced by another agent's between its check and the end; an ended tour's stored events still carry
 * location, compass or photo links (TOUR-02).
 */
class TourStateTest {
    private fun tour(id: String = "t1", agent: String = "a") = Tour(id, agent, agent.uppercase(), listOf("history"), 60, 1000L)

    @Test fun updateIsANoOpForAnotherTour() {
        val s = TourState()
        s.begin(tour("t1"))
        assertNull(s.update("t0") { it.copy(ticks = 99) })
        assertEquals(0, s.current()!!.ticks)
        assertEquals(1, s.update("t1") { it.copy(ticks = it.ticks + 1) }!!.ticks)
    }

    @Test fun endedTourIsNotResurrected() {
        val s = TourState()
        s.begin(tour("t1"))
        val snapshot = s.current()!!
        s.take()
        assertNull(s.update(snapshot.id) { it.copy(ticks = snapshot.ticks + 1) })
        assertNull(s.current())
        // Replaced by a new tour: the old tour's work doesn't touch it.
        s.begin(tour("t2", "b"))
        assertNull(s.update("t1") { it.copy(caption = "old") })
        assertEquals("t2", s.current()!!.id); assertNull(s.current()!!.caption)
    }

    @Test fun updateKeepsTheCurrentPausedFlag() {
        val s = TourState()
        s.begin(tour("t1"))
        val stale = s.current()!!      // a tick captured this, then the owner paused
        s.setPaused(true)
        s.update("t1") { c -> c.copy(ticks = stale.ticks + 1) }
        assertTrue(s.current()!!.paused)
        assertEquals(1, s.current()!!.ticks)
        assertNull(s.setPaused(false, "other"))
        assertTrue(s.current()!!.paused)
    }

    @Test(expected = IllegalArgumentException::class)
    fun updateCannotSwapTheTour() {
        val s = TourState(); s.begin(tour("t1"))
        s.update("t1") { tour("t9") }
    }

    @Test fun endIfOwnedChecksAndEndsInOneStep() {
        val s = TourState()
        s.begin(tour("t1", "a"))
        // The owner replaced A's tour with B's before A's DELETE arrived.
        s.begin(tour("t2", "b"))
        assertNull(s.takeIfOwned("a"))
        assertEquals("t2", s.current()!!.id)
        assertNull(s.takeIfOwned("b", "t1"))
        assertEquals("t2", s.takeIfOwned("b", "t2")!!.id)
        assertNull(s.current())
        assertNull(s.takeIfOwned("b"))
    }

    @Test fun takeOnlyTheNamedTour() {
        val s = TourState(); s.begin(tour("t1"))
        assertNull(s.take("t0")); assertEquals("t1", s.current()!!.id)
        assertEquals("t1", s.take("t1")!!.id); assertNull(s.current())
    }

    @Test fun scrubRemovesLocationCompassAndPhotos() {
        val tick = JSONObject().put("tour_id", "t1").put("tick", 3).put("kinds", JSONArray(listOf("history")))
            .put("location", JSONObject().put("latitude", 52.52).put("longitude", 13.40))
            .put("compass", JSONObject().put("azimuth_deg", 90))
            .put("new_photos", JSONArray().put(JSONObject().put("id", "7").put("fetch", "/v1/gallery/photo/7?size=agent")))
            .put("moved_m", 12)
        val out = TourPrivacy.scrub(tick)!!
        for (k in TourPrivacy.SENSITIVE) assertFalse(k, out.has(k))
        assertEquals("t1", out.getString("tour_id")); assertEquals(3, out.getInt("tick")); assertEquals(1, out.getInt("new_photos_count"))
        assertFalse(out.toString().contains("52.52")); assertFalse(out.toString().contains("/v1/gallery/photo"))
        assertTrue(tick.has("location")) // the input isn't modified

        val q = JSONObject().put("tour_id", "t1").put("question", "What's that?").put("location", JSONObject.NULL)
            .put("compass", JSONObject.NULL).put("latest_photo", JSONObject().put("id", "9").put("fetch", "/v1/gallery/photo/9?size=agent"))
        val qo = TourPrivacy.scrub(q)!!
        assertFalse(qo.has("latest_photo")); assertFalse(qo.has("location")); assertEquals("t1", qo.getString("tour_id"))

        assertNull(TourPrivacy.scrub(JSONObject().put("tour_id", "t1").put("reason", "time limit")))
    }

    @Test fun batteryUsedSoFar() {
        assertEquals(7, TourBattery.used(80, 73, charged = false))
        assertEquals(0, TourBattery.used(80, 81, charged = false)) // never negative
        assertNull(TourBattery.used(80, 70, charged = true))       // charging during the tour: unknown
        assertNull(TourBattery.used(null, 70, charged = false))
        assertNull(TourBattery.used(80, null, charged = false))
        val t = tour().copy(batteryStartPct = 90)
        val s = TourState(); s.begin(t)
        assertTrue(s.update("t1") { it.copy(charged = true) }!!.charged)
        assertEquals(90, s.current()!!.batteryStartPct)
    }
}
