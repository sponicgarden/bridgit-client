package cc.bridgit.app.api

import cc.bridgit.app.capability.PoseRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoseRulesTest {
    private fun frames(n: Int) = (0 until n).map { PoseRules.Frame(it, it * 100, FloatArray(PoseRules.POINTS * 3) { k -> k / 1000f }) }

    @Test fun pagesFromAnIndex() {
        val (page, next) = PoseRules.page(frames(10), 3, 4)
        assertEquals(listOf(3, 4, 5, 6), page.map { it.i })
        assertEquals(7, next)
    }

    @Test fun pageAtTheEndIsEmptyAndKeepsTheIndex() {
        val (page, next) = PoseRules.page(frames(5), 5, 4)
        assertEquals(0, page.size)
        assertEquals(5, next)
        assertEquals(0 to 0, PoseRules.page(emptyList(), 0, 4).let { it.first.size to it.second })
    }

    @Test fun frameIsThirtyThreePointsRounded() {
        val f = PoseRules.Frame(2, 250, FloatArray(PoseRules.POINTS * 3) { 0.123456f })
        val j = PoseRules.frameJson(f)
        assertEquals(99, j.getJSONArray("p").length())
        assertEquals(0.123, j.getJSONArray("p").getDouble(0), 1e-9)
        assertEquals(0.12, j.getJSONArray("p").getDouble(2), 1e-9)
        assertEquals(PoseRules.POINTS, PoseRules.NAMES.size)
    }

    @Test fun wholeNumbersInRange() {
        assertEquals(60, PoseRules.int(null, 5, 120, 60))
        assertEquals(45, PoseRules.int(45, 5, 120, 60))
        assertEquals(45, PoseRules.int("45", 5, 120, 60))
        assertNull(PoseRules.int(4, 5, 120, 60))
        assertNull(PoseRules.int(4.5, 1, 120, 60))
        assertNull(PoseRules.int(true, 1, 120, 60))
    }

    @Test fun nearestPicture() {
        assertEquals(-1, PoseRules.nearest(emptyList(), 10))
        assertEquals(2, PoseRules.nearest(listOf(0, 500, 1000, 1500), 1100))
        assertEquals(3, PoseRules.nearest(listOf(0, 500, 1000, 1500), 99_999))
    }
}
