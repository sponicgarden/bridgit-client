package cc.bridgit.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlinesTest {
    private fun mask(w: Int, h: Int, on: (Int, Int) -> Boolean) = BooleanArray(w * h) { on(it % w, it / w) }

    @Test fun rectangleBecomesItsFourCorners() {
        val m = mask(20, 10) { x, y -> x in 5..14 && y in 2..7 }
        val p = Outlines.polygon(m, 20, 10)
        assertEquals(4, p.size)
        val xs = p.map { Math.round(it[0] * 20 - 0.5).toInt() }.toSet(); val ys = p.map { Math.round(it[1] * 10 - 0.5).toInt() }.toSet()
        assertEquals(setOf(5, 14), xs); assertEquals(setOf(2, 7), ys)
    }

    @Test fun keepsOnlyTheLargestBlob() {
        val m = mask(30, 30) { x, y -> (x in 2..4 && y in 2..4) || (x in 10..25 && y in 10..25) }
        val p = Outlines.polygon(m, 30, 30)
        assertTrue(p.all { it[0] > 10.0 / 30 && it[1] > 10.0 / 30 })
    }

    @Test fun circleStaysUnderTheLimitAndRoughlyRound() {
        val m = mask(200, 200) { x, y -> (x - 100) * (x - 100) + (y - 100) * (y - 100) < 80 * 80 }
        val p = Outlines.polygon(m, 200, 200, maxPoints = 32)
        assertTrue(p.size in 8..32)
        for (pt in p) { val r = Math.hypot(pt[0] * 200 - 100, pt[1] * 200 - 100); assertTrue("radius $r", r in 76.0..82.0) }
    }

    @Test fun emptyAndSinglePixel() {
        assertEquals(0, Outlines.polygon(BooleanArray(16), 4, 4).size)
        assertEquals(1, Outlines.polygon(mask(4, 4) { x, y -> x == 1 && y == 2 }, 4, 4).size)
    }

    @Test fun lShapeKeepsItsInnerCorner() {
        val m = mask(20, 20) { x, y -> (x in 2..17 && y in 2..6) || (x in 2..6 && y in 2..17) }
        val p = Outlines.polygon(m, 20, 20)
        assertTrue("${p.size} points", p.size in 6..7) // pixel-centre tracing cuts the inner corner diagonally
    }
}
