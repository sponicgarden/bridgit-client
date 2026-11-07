package cc.bridgit.app.lancamera

import kotlin.math.abs

/**
 * Tapo C210 pan/tilt geometry in ONVIF's normalized PositionGenericSpace (-1..1 on both axes).
 *
 * Measured on 4 Nov 2026 on both Bridgit test cameras ("Tapo Cam Cat" and "Tapo Cam Dog", C210 hardware 3.0,
 * firmware 1.2.7 Build 260818), 2304×1296 main stream. Each move was an AbsoluteMove; the image shift was
 * found by template-matching the frame centre (the overlay is fixed, so whole-frame correlation reads zero):
 *  - Pan: about 4,400 px per unit for steps of 0.1 and more, so one frame width is about [FRAME_PAN] units.
 *    Steps of 0.02 moved only about half as far (gear slack), so moves smaller than [MIN_STEP] are skipped.
 *  - Tilt: about 1,000 px per unit at every step size, so one frame height is about [FRAME_TILT] units.
 *  - Directions: +pan shifts the picture right (the camera turns left); +tilt shifts the picture down (it looks up).
 *  - Range: pan -1 and +1 show nearly the same view, about 25° apart, so the full pan is about 335°
 *    (TP-Link's spec says 360°), about [PAN_DEGREES_PER_UNIT]° per unit. Tilt +1 is the ceiling, -1 the floor
 *    below the camera; TP-Link's spec is 114°, so about [TILT_DEGREES_PER_UNIT]° per unit.
 *  - No zoom: GetNodes and GetConfigurationOptions list no zoom space at all, so the lens is fixed. A close-up can
 *    only be a crop of the photo (digital zoom).
 * The degree values are for people; the code only uses the frame fractions, which were measured directly.
 */
object TapoPtz {
    const val FRAME_PAN = .52
    const val FRAME_TILT = 1.30
    const val PAN_DEGREES_PER_UNIT = 168.0
    const val TILT_DEGREES_PER_UNIT = 57.0
    /** Ring views one step apart: four views cover the whole pan range with a small overlap. */
    const val RING_STEP = .5
    const val MIN_STEP = .03
}

/** The search ring: four views around the camera's current aim, keeping its tilt. */
object PtzRing {
    val VIEWS = listOf("center", "right", "behind", "left")
    const val CENTERED = "centered"
    /** Pan wraps from +1 to -1, which is (almost) the same direction on these cameras. */
    fun wrap(x: Double): Double { var v = x; while (v > 1.0) v -= 2.0; while (v < -1.0) v += 2.0; return v }
    fun target(origin: PtzPoint, view: String): PtzPoint = when (view) {
        "center" -> origin
        "right" -> PtzPoint(wrap(origin.x - TapoPtz.RING_STEP), origin.y)
        "behind" -> PtzPoint(wrap(origin.x + 2 * TapoPtz.RING_STEP), origin.y)
        "left" -> PtzPoint(wrap(origin.x + TapoPtz.RING_STEP), origin.y)
        else -> throw IllegalArgumentException("Unknown ring view")
    }
}

/** A Jev box: edges as fractions of the photo, top-left origin. */
data class PetBox(val left: Double, val top: Double, val right: Double, val bottom: Double, val confidence: Double) {
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2
    fun json(): org.json.JSONObject = org.json.JSONObject().put("left", left).put("top", top).put("right", right).put("bottom", bottom).put("confidence", confidence)
    companion object {
        fun of(l: Double?, t: Double?, r: Double?, b: Double?, c: Double?): PetBox? {
            val e = listOf(l, t, r, b, c)
            if (e.any { it == null || !it.isFinite() || it < 0 || it > 1 }) return null
            if (l!! >= r!! || t!! >= b!!) return null
            return PetBox(l, t, r, b, c!!)
        }
    }
}

/**
 * Turns a pet's box into the next absolute aim. The pet counts as centred when its middle is within
 * [TOLERANCE] of the frame middle on both axes. If a move made an axis worse, that axis's direction
 * is flipped for the next move (a camera mounted upside down).
 */
class PtzCentering(private val space: PtzSpace, private val damping: Double = .85, private val tolerance: Double = TOLERANCE) {
    companion object { const val TOLERANCE = .10; const val MAX_MOVES = 3 }
    var panSign = 1.0; private set
    var tiltSign = 1.0; private set
    var limited = false; private set
    private var last: Pair<Double, Double>? = null
    private var lastMoved: Pair<Boolean, Boolean>? = null

    fun centered(box: PetBox) = abs(box.cx - .5) <= tolerance && abs(box.cy - .5) <= tolerance

    /** The next aim from [from], or null when centred or no useful move is left. */
    fun next(from: PtzPoint, box: PetBox): PtzPoint? {
        val ex = box.cx - .5; val ey = box.cy - .5
        val prev = last; val moved = lastMoved
        if (prev != null && moved != null) {
            if (moved.first && ex * prev.first > 0 && abs(ex) > abs(prev.first) + .05) panSign = -panSign
            if (moved.second && ey * prev.second > 0 && abs(ey) > abs(prev.second) + .05) tiltSign = -tiltSign
        }
        last = ex to ey
        if (centered(box)) { lastMoved = false to false; return null }
        // +pan shifts the picture right, so a pet right of centre (ex>0) needs -pan; likewise for tilt.
        var dx = if (abs(ex) > tolerance) -ex * TapoPtz.FRAME_PAN * damping * panSign else 0.0
        var dy = if (abs(ey) > tolerance) -ey * TapoPtz.FRAME_TILT * damping * tiltSign else 0.0
        if (abs(dx) < TapoPtz.MIN_STEP) dx = 0.0
        if (abs(dy) < TapoPtz.MIN_STEP) dy = 0.0
        val x = (from.x + dx).coerceIn(space.xMin, space.xMax)
        val y = (from.y + dy).coerceIn(space.yMin, space.yMax)
        if (x != from.x + dx || y != from.y + dy) limited = true
        val movedX = abs(x - from.x) >= TapoPtz.MIN_STEP / 2; val movedY = abs(y - from.y) >= TapoPtz.MIN_STEP / 2
        lastMoved = movedX to movedY
        if (!movedX && !movedY) { limited = true; return null }
        return PtzPoint(x, y)
    }
}

/**
 * Portal PTZ search, version 4: photograph the ring, then aim at the most likely pet and re-shoot until it
 * sits near the middle (at most [PtzCentering.MAX_MOVES] moves). Every pose is verified before its photo.
 * The camera stays on the pet when one was found; otherwise it goes back to where it started. If the pet is
 * lost during centring, the camera goes back to the last pose whose photo showed it (7 Nov 2026: it used to
 * stay where the pet had just been missed, pointing the monitor away from it).
 */
class PtzCenterSearch(private val space: PtzSpace, private val driver: Driver) {
    /** One photo and Jev's answer about it; [payload] is the driver's own (photo and reading). */
    data class Shot(val dogP: Double, val box: PetBox?, val payload: Any? = null)
    data class Centering(val moves: Int, val centered: Boolean, val from: String?, val limited: Boolean) {
        fun json(): org.json.JSONObject = org.json.JSONObject().put("moves", moves).put("centered", centered).put("from", from ?: org.json.JSONObject.NULL).put("limited", limited)
    }
    interface Driver {
        suspend fun authorize()
        suspend fun position(): PtzPoint
        suspend fun move(point: PtzPoint)
        suspend fun settle(ms: Long)
        suspend fun progress(view: String, step: String, index: Int?, of: Int?, move: Int? = null, moves: Int? = null)
        /** Takes one photo and asks Jev about it; null if the photo or the answer failed. */
        suspend fun shoot(view: String): Shot?
        /** Sends [shot] as [view]'s result; null [shot] reports a failed photo. */
        suspend fun deliver(view: String, shot: Shot?, centering: Centering? = null)
        /** The centring step found no pet in any view. */
        suspend fun notFound()
        suspend fun stop()
    }
    companion object { const val PET_MIN = .6 } // the same bar as a "dog" reading (DogJev.DOG_MIN)
    private suspend fun go(point: PtzPoint) {
        driver.authorize(); driver.move(point)
        repeat(80) {
            driver.authorize()
            if (space.near(driver.position(), point)) { driver.settle(500); driver.authorize(); if (space.near(driver.position(), point)) return }
            driver.settle(250)
        }
        throw PtzSearch.PositionUnavailable()
    }

    /** [views] are ring views, optionally followed by [PtzRing.CENTERED]. */
    suspend fun run(views: List<String>) {
        val ring = views.filter { it != PtzRing.CENTERED }
        require(ring.isNotEmpty() && ring.distinct().size == ring.size && ring.all { it in PtzRing.VIEWS })
        val center = PtzRing.CENTERED in views
        driver.authorize()
        val original = driver.position()
        if (!space.contains(original)) throw PtzSearch.PositionUnavailable()
        var keep = false
        try {
            var best: Triple<String, PtzPoint, Shot>? = null
            ring.forEachIndexed { i, view ->
                driver.progress(view, "moving", i + 1, ring.size)
                val point = PtzRing.target(original, view); go(point)
                driver.progress(view, "photo", i + 1, ring.size)
                val shot = driver.shoot(view)
                driver.deliver(view, shot)
                val b = best
                if (shot != null && shot.dogP >= PET_MIN && shot.box != null && (b == null || shot.dogP > b.third.dogP)) best = Triple(view, point, shot)
            }
            if (!center) return
            val found = best
            if (found == null) { driver.notFound(); return }
            val (from, start, first) = found
            val logic = PtzCentering(space)
            var at = start; var seenAt = start; var shot = first; var moves = 0
            driver.progress(PtzRing.CENTERED, "centering", null, null, 0, PtzCentering.MAX_MOVES)
            go(start); keep = true
            while (moves < PtzCentering.MAX_MOVES) {
                val next = logic.next(at, shot.box!!) ?: break
                moves++
                driver.progress(PtzRing.CENTERED, "centering", null, null, moves, PtzCentering.MAX_MOVES)
                go(next); at = next
                val again = driver.shoot(PtzRing.CENTERED)
                // The pet walked off, Jev lost it or the photo failed: keep the last photo that showed it, and
                // aim where that photo was taken so the monitor keeps watching the pet.
                if (again == null || again.dogP < PET_MIN || again.box == null) { go(seenAt); at = seenAt; break }
                shot = again; seenAt = next
            }
            driver.deliver(PtzRing.CENTERED, shot, Centering(moves, logic.centered(shot.box!!), from, logic.limited))
        } finally {
            try { if (!keep) { driver.authorize(); driver.progress("center", "returning", null, null); go(original) } } finally { driver.stop() }
        }
    }
}
