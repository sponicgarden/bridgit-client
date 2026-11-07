package cc.bridgit.app.lancamera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PtzCenteringTest {
    private val space = PtzSpace(-1.0, 1.0, -1.0, 1.0)
    private fun near(a: Double, b: Double) = abs(a - b) < 1e-9

    @Test fun ringCoversTheWholePanRangeAtTheCurrentTilt() {
        val o = PtzPoint(-.068, -.69)
        val xs = PtzRing.VIEWS.map { PtzRing.target(o, it) }
        assertTrue(xs.all { it.y == o.y && space.contains(it) })
        // Picture moves right on +pan, so "right" is -pan; "behind" wraps through ±1.
        assertTrue(near(xs[1].x, -.568)); assertTrue(near(xs[2].x, .932)); assertTrue(near(xs[3].x, .432))
        // Four views one frame apart leave no gap: every pan position is within half a frame of a view.
        for (i in 0..200) {
            val x = -1 + i / 100.0
            assertTrue("gap at $x", xs.any { v -> listOf(-2.0, 0.0, 2.0).any { abs(x - (v.x + it)) <= TapoPtz.FRAME_PAN / 2 + 1e-9 } })
        }
        assertTrue(near(PtzRing.wrap(1.3), -.7)); assertTrue(near(PtzRing.wrap(-1.3), .7))
    }

    @Test fun boxRightAndLowTurnsTheCameraRightAndDown() {
        val c = PtzCentering(space)
        val next = c.next(PtzPoint(0.0, 0.0), PetBox(.7, .7, .9, .9, .9))!!
        assertTrue(near(next.x, -.3 * TapoPtz.FRAME_PAN * .85)); assertTrue(near(next.y, -.3 * TapoPtz.FRAME_TILT * .85))
        assertNull("centred within 10%", PtzCentering(space).next(PtzPoint(0.0, 0.0), PetBox(.4, .45, .58, .6, .9)))
    }

    @Test fun anAxisThatGotWorseFlipsAndEdgesMarkTheResultLimited() {
        val c = PtzCentering(space)
        c.next(PtzPoint(0.0, 0.0), PetBox(.7, .4, .9, .6, .9)) // pet right: pan negative
        val flipped = c.next(PtzPoint(-.09, 0.0), PetBox(.8, .4, 1.0, .6, .9)) // it got further right
        assertEquals(-1.0, c.panSign, 0.0); assertTrue(flipped!!.x > -.09)
        val edge = PtzCentering(space)
        assertNull("pinned against both stops", edge.next(PtzPoint(1.0, -1.0), PetBox(.0, .8, .2, 1.0, .9)))
        assertTrue(edge.limited)
    }

    private class Fake(val pet: PtzPoint?) : PtzCenterSearch.Driver {
        var current = PtzPoint(-.07, -.69); val delivered = mutableListOf<Pair<String, PtzCenterSearch.Centering?>>(); val steps = mutableListOf<String>()
        var stops = 0; var notFound = 0; var shots = 0; var revoked = false; var revokeAfterShot = false
        /** The pet leaves after this many photos (null: it stays). */
        var leavesAfter: Int? = null
        override suspend fun authorize() { if (revoked) throw CancellationException() }
        override suspend fun position() = current
        override suspend fun move(point: PtzPoint) { current = point }
        override suspend fun settle(ms: Long) {}
        override suspend fun progress(view: String, step: String, index: Int?, of: Int?, move: Int?, moves: Int?) { steps.add("$view:$step") }
        override suspend fun shoot(view: String): PtzCenterSearch.Shot? {
            shots++; if (revokeAfterShot) revoked = true
            if (leavesAfter?.let { shots > it } == true) return PtzCenterSearch.Shot(.04, null)
            val p = pet ?: return PtzCenterSearch.Shot(.02, null)
            // Where the pet appears: +pan shifts the picture right, +tilt shifts it down.
            val cx = .5 + (current.x - p.x) / TapoPtz.FRAME_PAN; val cy = .5 + (current.y - p.y) / TapoPtz.FRAME_TILT
            if (cx !in 0.05..0.95 || cy !in 0.05..0.95) return PtzCenterSearch.Shot(.03, null)
            return PtzCenterSearch.Shot(.97, PetBox(cx - .05, cy - .05, cx + .05, cy + .05, .9))
        }
        override suspend fun deliver(view: String, shot: PtzCenterSearch.Shot?, centering: PtzCenterSearch.Centering?) { delivered.add(view to centering) }
        override suspend fun notFound() { notFound++ }
        override suspend fun stop() { stops++ }
    }

    @Test fun findsThePetInARingViewThenCentresItAndStaysThere() = runBlocking {
        val f = Fake(PtzPoint(-.45, -.55))
        PtzCenterSearch(space, f).run(PtzRing.VIEWS + PtzRing.CENTERED)
        assertEquals(PtzRing.VIEWS + PtzRing.CENTERED, f.delivered.map { it.first })
        val centring = f.delivered.last().second!!
        assertEquals("right", centring.from); assertTrue(centring.centered); assertTrue(centring.moves in 1..PtzCentering.MAX_MOVES)
        assertTrue(abs(f.current.x + .45) < .06 && abs(f.current.y + .55) < .14)
        assertEquals(1, f.stops); assertFalse(f.steps.contains("center:returning"))
        assertTrue(f.shots <= PtzRing.VIEWS.size + PtzCentering.MAX_MOVES)
        assertEquals(listOf("center:moving", "center:photo", "right:moving"), f.steps.take(3))
    }

    @Test fun aPetLostWhileCentringLeavesTheCameraWhereItWasLastSeen() = runBlocking {
        // Found in the "right" ring view, off-centre; it leaves before the first centring photo.
        val f = Fake(PtzPoint(-.45, -.55)).apply { leavesAfter = PtzRing.VIEWS.size }
        val original = f.current
        PtzCenterSearch(space, f).run(PtzRing.VIEWS + PtzRing.CENTERED)
        val right = PtzRing.target(original, "right")
        assertEquals("back at the view whose photo showed the pet", right, f.current)
        val centring = f.delivered.last().second!!
        assertEquals("right", centring.from); assertEquals(1, centring.moves); assertFalse(f.steps.contains("center:returning"))
        assertEquals(1, f.stops)
    }

    @Test fun noPetReturnsToTheStartAndSaysSo() = runBlocking {
        val f = Fake(null); val start = f.current
        PtzCenterSearch(space, f).run(PtzRing.VIEWS + PtzRing.CENTERED)
        assertEquals(1, f.notFound); assertEquals(start, f.current); assertTrue(f.steps.contains("center:returning")); assertEquals(1, f.stops)
    }

    @Test fun revokedAccessStopsTheCamera() = runBlocking {
        val g = Fake(PtzPoint(0.0, 0.0)); g.revokeAfterShot = true
        try { PtzCenterSearch(space, g).run(PtzRing.VIEWS); fail("must cancel") } catch (_: CancellationException) {}
        assertEquals(1, g.stops); assertEquals(1, g.shots); assertTrue(g.delivered.size <= 1)
    }
}
