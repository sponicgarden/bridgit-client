package cc.bridgit.app.core.camera

import cc.bridgit.app.api.ApiError
import cc.bridgit.app.core.camera.WatchRegistration.Box
import cc.bridgit.app.core.camera.WatchRegistration.Point
import cc.bridgit.app.core.camera.WatchRegistration.Zone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WatchRegistrationTest {
    private val vocab = setOf("person", "cup", "plate", "dog")
    private fun parse(json: String) = WatchRegistration.parse(JSONObject(json), vocab)
    private fun refused(json: String, contains: String) {
        try { parse(json); fail("expected refusal: $contains") }
        catch (e: ApiError) {
            assertEquals("invalid_parameter", e.code)
            assertEquals(422, e.http)
            assertTrue("${e.message} should mention $contains", e.message!!.contains(contains))
        }
    }
    private fun poly(vararg p: Pair<Double, Double>) = p.joinToString(",", "[", "]") { "[${it.first},${it.second}]" }
    private val square = Zone(listOf(Point(0.2, 0.2), Point(0.6, 0.2), Point(0.6, 0.6), Point(0.2, 0.6)))

    @Test fun parsesTheExample() {
        val r = parse("""{"camera":"rear","classes":["person","cup","plate"],"zone":{"polygon":[[0.1,0.4],[0.9,0.4],[0.9,1.0],[0.1,1.0]]},"min_interval_ms":1000}""")
        assertEquals("rear", r.camera)
        assertEquals(setOf("person", "cup", "plate"), r.classes)
        assertEquals(4, r.zone!!.polygon.size)
        assertEquals(1000L, r.minIntervalMs)
    }

    @Test fun defaults() {
        val r = parse("""{"classes":["dog"]}""")
        assertEquals("rear", r.camera)
        assertNull(r.zone)
        assertEquals(WatchRegistration.DEFAULT_INTERVAL_MS, r.minIntervalMs)
        assertEquals("lan:cam_1", parse("""{"camera":"lan:cam_1","classes":["dog"]}""").camera)
    }

    @Test fun refusals() {
        refused("""{"classes":["dog"],"fps":5}""", "fps isn't part")
        refused("""{"classes":["cat"]}""", "cat isn't a detector class")
        refused("""{"classes":[]}""", "1-32")
        refused("""{}""", "classes must be a list")
        refused("""{"camera":"side","classes":["dog"]}""", "camera must be")
        refused("""{"classes":["dog"],"min_interval_ms":249}""", "at least 250")
        refused("""{"classes":["dog"],"min_interval_ms":1.5}""", "whole number")
        refused("""{"classes":["dog"],"zone":{"polygon":${poly(0.1 to 0.1, 0.2 to 0.2)}}}""", "3-16 points")
        refused("""{"classes":["dog"],"zone":{"polygon":${poly(*Array(17) { 0.5 + 0.4 * Math.cos(it * 0.36) to 0.5 + 0.4 * Math.sin(it * 0.36) })}}}""", "3-16 points")
        refused("""{"classes":["dog"],"zone":{"polygon":${poly(0.1 to 0.1, 1.2 to 0.1, 0.5 to 0.9)}}}""", "inside the frame")
        refused("""{"classes":["dog"],"zone":{"polygon":${poly(-0.1 to 0.1, 0.9 to 0.1, 0.5 to 0.9)}}}""", "inside the frame")
        refused("""{"classes":["dog"],"zone":{"polygon":[[0.1,0.1],[0.9],[0.5,0.9]]}}""", "polygon[1] must be [x, y]")
        refused("""{"classes":["dog"],"zone":{"polygon":${poly(0.1 to 0.1, 0.9 to 0.1, 0.5 to 0.9)},"mask":true}}""", "zone.mask")
        // A bow tie crosses itself; three points on a line have no area.
        refused("""{"classes":["dog"],"zone":{"polygon":${poly(0.1 to 0.1, 0.9 to 0.9, 0.9 to 0.2, 0.1 to 0.6)}}}""", "crosses itself")
        refused("""{"classes":["dog"],"zone":{"polygon":${poly(0.1 to 0.1, 0.5 to 0.5, 0.9 to 0.9)}}}""", "no area")
    }

    @Test fun selfIntersection() {
        assertFalse(WatchRegistration.selfIntersecting(square))
        // Concave but simple (an L shape) is fine.
        val l = Zone(listOf(Point(0.1, 0.1), Point(0.5, 0.1), Point(0.5, 0.4), Point(0.9, 0.4), Point(0.9, 0.9), Point(0.1, 0.9)))
        assertFalse(WatchRegistration.selfIntersecting(l))
        // An edge folding back on its neighbour.
        assertTrue(WatchRegistration.selfIntersecting(Zone(listOf(Point(0.1, 0.1), Point(0.9, 0.1), Point(0.5, 0.1), Point(0.5, 0.9)))))
    }

    @Test fun zoneContains() {
        assertTrue(square.contains(0.4, 0.4))
        assertTrue(square.contains(0.2, 0.4)) // on the edge
        assertFalse(square.contains(0.1, 0.4))
        assertFalse(square.contains(0.7, 0.7))
        val l = Zone(listOf(Point(0.1, 0.1), Point(0.5, 0.1), Point(0.5, 0.4), Point(0.9, 0.4), Point(0.9, 0.9), Point(0.1, 0.9)))
        assertFalse(l.contains(0.7, 0.2)) // the notch
        assertTrue(l.contains(0.7, 0.6))
    }

    @Test fun zoneOverlapsBox() {
        assertTrue(square.overlaps(Box(0.3, 0.3, 0.4, 0.4))) // inside
        assertTrue(square.overlaps(Box(0.0, 0.0, 1.0, 1.0))) // contains the zone
        assertTrue(square.overlaps(Box(0.5, 0.5, 0.9, 0.9))) // corner overlap
        assertTrue(square.overlaps(Box(0.3, 0.0, 0.4, 1.0))) // crosses without a corner inside either
        assertFalse(square.overlaps(Box(0.7, 0.7, 0.9, 0.9)))
        assertFalse(square.overlaps(Box(0.0, 0.0, 0.1, 1.0)))
    }

    @Test fun mergePerCamera() {
        val a = parse("""{"camera":"rear","classes":["person"],"zone":{"polygon":${poly(0.1 to 0.1, 0.9 to 0.1, 0.5 to 0.9)}},"min_interval_ms":2000}""")
        val b = parse("""{"camera":"rear","classes":["cup","person"],"zone":{"polygon":${poly(0.2 to 0.2, 0.6 to 0.2, 0.6 to 0.6, 0.2 to 0.6)}},"min_interval_ms":500}""")
        val c = parse("""{"camera":"front","classes":["dog"]}""")
        val p = WatchRegistration.merge(listOf(a, b, c))
        assertEquals(setOf("rear", "front"), p.cameras.keys)
        val rear = p.cameras.getValue("rear")
        assertEquals(500L, rear.minIntervalMs)
        assertEquals(setOf("person", "cup"), rear.classes)
        assertEquals(listOf(a.zone, b.zone), rear.zones)
        assertFalse(rear.wholeFrame)
        val front = p.cameras.getValue("front")
        assertTrue(front.wholeFrame)
        assertEquals(emptyList<Zone>(), front.zones)
        assertEquals(1000L, front.minIntervalMs)
        assertTrue(WatchRegistration.merge(emptyList()).cameras.isEmpty())
    }
}
