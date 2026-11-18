package cc.bridgit.app.core.camera

import cc.bridgit.app.api.ErrorCatalog
import org.json.JSONArray
import org.json.JSONObject

/**
 * What an applet's watch asks the camera layer for (A0455 phase 1, docs/plans/a0455-camera-service-watch-registration.md):
 * a camera, the detector classes it cares about, an optional zone and the shortest interval between frames.
 * Pure: parsing, merging and zone geometry for the relevance gate. Nothing is wired to the device yet.
 *
 * ```json
 * {"camera": "rear", "classes": ["person", "cup"], "zone": {"polygon": [[0.1,0.4],[0.9,0.4],[0.9,1.0],[0.1,1.0]]},
 *  "min_interval_ms": 1000}
 * ```
 */
data class WatchRegistration(val camera: String, val classes: Set<String>, val zone: Zone?, val minIntervalMs: Long) {

    /** A normalized point: 0..1 from the frame's left and top edges. */
    data class Point(val x: Double, val y: Double)

    /** A normalized box (a detector's box), left/top to right/bottom in 0..1. */
    data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double)

    /** A simple (not self-intersecting) normalized polygon of [MIN_POINTS]-[MAX_POINTS] points. */
    data class Zone(val polygon: List<Point>) {
        /** Inside or on the edge (even-odd rule). */
        fun contains(x: Double, y: Double): Boolean {
            val p = Point(x, y)
            if (edges().any { (a, b) -> onSegment(a, b, p) }) return true
            var inside = false
            for ((a, b) in edges()) if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
            return inside
        }

        /** Whether [box] and the zone share any area or edge. */
        fun overlaps(box: Box): Boolean {
            val corners = listOf(Point(box.left, box.top), Point(box.right, box.top), Point(box.right, box.bottom), Point(box.left, box.bottom))
            if (corners.any { contains(it.x, it.y) }) return true
            if (polygon.any { it.x in box.left..box.right && it.y in box.top..box.bottom }) return true
            val sides = corners.indices.map { corners[it] to corners[(it + 1) % 4] }
            return edges().any { (a, b) -> sides.any { (c, d) -> segmentsIntersect(a, b, c, d) } }
        }

        internal fun edges(): List<Pair<Point, Point>> = polygon.indices.map { polygon[it] to polygon[(it + 1) % polygon.size] }
    }

    /** What one camera's capture pipeline and gate need for every registration on it. */
    data class CameraPipeline(val camera: String, val minIntervalMs: Long, val classes: Set<String>, val zones: List<Zone>, val wholeFrame: Boolean)

    /** The merged registrations, per camera. */
    data class Pipeline(val cameras: Map<String, CameraPipeline>)

    companion object {
        const val MIN_POINTS = 3
        const val MAX_POINTS = 16
        const val MIN_INTERVAL_MS = 250L
        const val MAX_INTERVAL_MS = 3_600_000L
        const val DEFAULT_INTERVAL_MS = 1000L
        const val MAX_CLASSES = 32
        private val KEYS = setOf("camera", "classes", "zone", "min_interval_ms")
        private val CAMERA = Regex("^(rear|front|lan:[A-Za-z0-9_-]{1,64})$")
        private const val EPS = 1e-12

        /** Parses a registration; [vocabulary] is the detector's class labels. Throws `invalid_parameter`. */
        fun parse(json: JSONObject, vocabulary: Set<String>): WatchRegistration {
            json.keys().forEach { if (it !in KEYS) bad("$it isn't part of a watch registration (use ${KEYS.joinToString()}).") }
            val camera = when (val c = json.opt("camera")) {
                null -> "rear"
                is String -> c.takeIf { CAMERA.matches(it) } ?: bad("camera must be rear, front or lan:<id>.")
                else -> bad("camera must be rear, front or lan:<id>.")
            }
            val raw = json.opt("classes") as? JSONArray ?: bad("classes must be a list of detector classes.")
            if (raw.length() !in 1..MAX_CLASSES) bad("classes must list 1-$MAX_CLASSES detector classes.")
            val classes = LinkedHashSet<String>()
            for (i in 0 until raw.length()) {
                val c = raw.opt(i) as? String ?: bad("classes[$i] must be text.")
                if (c !in vocabulary) bad("classes[$i]: $c isn't a detector class.")
                classes += c
            }
            val zone = json.opt("zone")?.takeIf { it != JSONObject.NULL }?.let(::zone)
            val interval = when (val v = json.opt("min_interval_ms")) {
                null -> DEFAULT_INTERVAL_MS
                is Int -> v.toLong()
                is Long -> v
                is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong() else bad("min_interval_ms must be a whole number.")
                else -> bad("min_interval_ms must be a whole number.")
            }
            if (interval < MIN_INTERVAL_MS) bad("min_interval_ms must be at least $MIN_INTERVAL_MS.")
            if (interval > MAX_INTERVAL_MS) bad("min_interval_ms must be at most $MAX_INTERVAL_MS.")
            return WatchRegistration(camera, classes, zone, interval)
        }

        private fun zone(v: Any): Zone {
            val z = v as? JSONObject ?: bad("zone must be {polygon}.")
            z.keys().forEach { if (it != "polygon") bad("zone.$it isn't part of a zone (use polygon).") }
            val poly = z.opt("polygon") as? JSONArray ?: bad("zone.polygon must be a list of [x, y] points.")
            if (poly.length() !in MIN_POINTS..MAX_POINTS) bad("zone.polygon must have $MIN_POINTS-$MAX_POINTS points.")
            val points = (0 until poly.length()).map { i ->
                val p = poly.opt(i) as? JSONArray
                val x = (p?.opt(0) as? Number)?.toDouble(); val y = (p?.opt(1) as? Number)?.toDouble()
                if (p == null || p.length() != 2 || x == null || y == null) bad("zone.polygon[$i] must be [x, y].")
                if (x.isNaN() || y.isNaN() || x !in 0.0..1.0 || y !in 0.0..1.0) bad("zone.polygon[$i] must be inside the frame (0 to 1).")
                Point(x, y)
            }
            val zone = Zone(points)
            if (Math.abs(area(points)) < EPS) bad("zone.polygon has no area.")
            if (selfIntersecting(zone)) bad("zone.polygon crosses itself.")
            return zone
        }

        /** Per camera: the smallest interval, the union of classes and every zone (none = the whole frame). */
        fun merge(regs: List<WatchRegistration>): Pipeline = Pipeline(regs.groupBy { it.camera }.mapValues { (camera, rs) ->
            CameraPipeline(camera, rs.minOf { it.minIntervalMs }, rs.flatMapTo(LinkedHashSet()) { it.classes },
                rs.mapNotNull { it.zone }, rs.any { it.zone == null })
        })

        /** Whether two edges that aren't neighbours touch, or neighbours fold back onto each other. */
        internal fun selfIntersecting(zone: Zone): Boolean {
            val e = zone.edges(); val n = e.size
            for (i in 0 until n) for (j in i + 1 until n) {
                val adjacent = j == i + 1 || (i == 0 && j == n - 1)
                val (a, b) = e[i]; val (c, d) = e[j]
                if (!adjacent) { if (segmentsIntersect(a, b, c, d)) return true; continue }
                // Neighbours share one end; they cross only by overlapping along a line (a spike back).
                val shared = if (j == i + 1) b else a
                val p = if (j == i + 1) a else b
                val q = if (j == i + 1) d else c
                if (Math.abs(cross(shared, p, q)) < EPS && (p.x - shared.x) * (q.x - shared.x) + (p.y - shared.y) * (q.y - shared.y) > 0) return true
            }
            return false
        }

        private fun area(p: List<Point>): Double = p.indices.sumOf { val a = p[it]; val b = p[(it + 1) % p.size]; a.x * b.y - b.x * a.y } / 2

        private fun cross(o: Point, a: Point, b: Point) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

        internal fun onSegment(a: Point, b: Point, p: Point): Boolean =
            Math.abs(cross(a, b, p)) < EPS && p.x in minOf(a.x, b.x) - EPS..maxOf(a.x, b.x) + EPS && p.y in minOf(a.y, b.y) - EPS..maxOf(a.y, b.y) + EPS

        internal fun segmentsIntersect(a: Point, b: Point, c: Point, d: Point): Boolean {
            val d1 = cross(c, d, a); val d2 = cross(c, d, b); val d3 = cross(a, b, c); val d4 = cross(a, b, d)
            if (((d1 > EPS && d2 < -EPS) || (d1 < -EPS && d2 > EPS)) && ((d3 > EPS && d4 < -EPS) || (d3 < -EPS && d4 > EPS))) return true
            return onSegment(c, d, a) || onSegment(c, d, b) || onSegment(a, b, c) || onSegment(a, b, d)
        }

        private fun bad(message: String): Nothing = throw ErrorCatalog.error("invalid_parameter", message)
    }
}
