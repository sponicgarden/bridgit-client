package cc.bridgit.app.watch

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * "Who was in the kitchen" (watch option `people`, 4 Nov 2026): a model-none camera watch whose picture changed
 * takes a short burst of photos, finds faces on the phone, crops them and (when the model is there) embeds them,
 * and keeps the results in memory only until the applet's web portal takes them. The people seen are not the
 * owner, so every limit here is a requirement: battery floor, bursts an hour, at most 4 faces a burst, 48 queued,
 * nothing older than 6 hours, nothing written to files.
 *
 * Also the mess windows the portal pairs with the sightings: each messy or dirty episode of the applet's tally
 * watch with the last clean reading before it. Pure: no Android, unit-tested.
 */
object PeopleRules {
    const val MAX_SIGHTINGS_PER_BURST = 4
    const val QUEUE_CAP = 48
    const val QUEUE_MAX_AGE_MS = 6 * 3600_000L
    /** Sightings per push to the portal (one burst at most). */
    const val MAX_SIGHTINGS_PER_PUSH = 4
    const val MAX_MESS_EVENTS_PER_PUSH = 20
    /** The display crop: square, the face box grown this much on each side, at most this many px and bytes. */
    const val CROP_PAD = 0.35
    const val CROP_EDGE = 256
    const val CROP_MAX_BYTES = 40 * 1024
    const val CROP_QUALITY = 80
    val MESSY_LABELS = setOf("messy", "dirty")
    const val CLEAN_LABEL = "clean"

    /** Capture states, as the portal and the card show them. */
    const val ON = "on"
    const val BATTERY_LOW = "battery_low"
    const val CAP_REACHED = "cap_reached"
    const val OFF = "off"

    data class Options(
        val burst: Int = 3,
        val burstGapMs: Int = 800,
        val batteryFloor: Int = 50,
        val maxBurstsPerHour: Int = 20,
        val minFaceShare: Double = 0.01,
    ) {
        fun json(): JSONObject = JSONObject().put("burst", burst).put("burst_gap_ms", burstGapMs).put("battery_floor", batteryFloor)
            .put("max_bursts_per_hour", maxBurstsPerHour).put("min_face_share", minFaceShare)
    }

    const val ABOUT = "With model none and camera in data: when the picture changes, take a short burst of photos, find faces on the phone, " +
        "crop them (and embed them with an on-phone face model once it has downloaded) and hand them to the applet's private web portal. " +
        "{burst: 1-5 (default 3; the changed photo counts), burst_gap_ms: 300-3000 (default 800), battery_floor: 0-100 (default 50; " +
        "only while charging or at least this full), max_bursts_per_hour: 1-60 (default 20), min_face_share: 0.005-0.5 (default 0.01; " +
        "smaller faces are ignored)}. Crops stay in memory only (at most 48, 6 hours). Needs repeat: true. Only in an installed applet's own plan with the people_seen permission the owner approved (refused here and from card actions), and it captures only while that applet's web portal is on with photo sharing"

    /** Reads `people`. Throws [WatchRules.Bad]. */
    fun parse(raw: Any?): Options {
        val o = raw as? JSONObject ?: throw WatchRules.Bad("people must be an object, e.g. {\"burst\": 3, \"battery_floor\": 50}.")
        val keys = setOf("burst", "burst_gap_ms", "battery_floor", "max_bursts_per_hour", "min_face_share")
        o.keys().asSequence().firstOrNull { it !in keys }?.let { throw WatchRules.Bad("people.$it isn't a people option (${keys.joinToString()}).") }
        fun int(k: String, d: Int, lo: Int, hi: Int): Int {
            if (!o.has(k)) return d
            val v = (o.opt(k) as? Number)?.toDouble()
            if (v == null || v != Math.floor(v) || v < lo || v > hi) throw WatchRules.Bad("people.$k must be a whole number from $lo to $hi.")
            return v.toInt()
        }
        val share = if (!o.has("min_face_share")) 0.01 else (o.opt("min_face_share") as? Number)?.toDouble()
            ?.takeIf { !it.isNaN() && it >= 0.005 && it <= 0.5 } ?: throw WatchRules.Bad("people.min_face_share must be a number from 0.005 to 0.5.")
        return Options(int("burst", 3, 1, 5), int("burst_gap_ms", 800, 300, 3000), int("battery_floor", 50, 0, 100),
            int("max_bursts_per_hour", 20, 1, 60), share)
    }

    /** Whether the battery allows a burst: charging, or at least [floor] percent (unknown counts as low unless the floor is 0). */
    fun batteryOk(percent: Int?, charging: Boolean, floor: Int): Boolean = charging || floor <= 0 || (percent != null && percent >= floor)

    /** Why faces can't be captured at all for this applet: its portal is off or unlinked, or photo sharing is off. */
    const val PORTAL_OFF = "portal_off"
    const val PORTAL_NOT_LINKED = "portal_not_linked"
    const val PHOTO_SHARING_OFF = "photo_sharing_off"
    const val NOT_APPLET = "not_applet"

    /**
     * The capture state right now. [paused]: the watch or applet is paused; [blocker]: why the portal can't take
     * faces (null = it can). Off and battery_low take no photo at all; cap_reached still runs the change check.
     */
    fun gate(paused: Boolean, blocker: String?, percent: Int?, charging: Boolean, floor: Int, burstsLastHour: Int, maxPerHour: Int): String = when {
        paused || blocker != null -> OFF
        !batteryOk(percent, charging, floor) -> BATTERY_LOW
        burstsLastHour >= maxPerHour -> CAP_REACHED
        else -> ON
    }

    /** Whether the people watch takes its own photo in [state]. */
    fun takesPhotos(state: String): Boolean = state == ON || state == CAP_REACHED

    /** Why the state isn't on, for the card: paused, portal_off, portal_not_linked, photo_sharing_off, battery_low, cap_reached; null when on. */
    fun reason(paused: Boolean, blocker: String?, state: String): String? = when {
        paused -> "paused"
        blocker != null -> blocker
        state == ON -> null
        else -> state
    }

    /**
     * The capture state as the portal push contract has it (on | battery_low | paused | off): a paused watch is
     * "paused", a portal that can't take faces is "off", the hourly cap is only a throttle so it stays "on".
     */
    fun pushState(paused: Boolean, blocker: String?, state: String): String = when {
        paused -> "paused"
        blocker != null || state == OFF -> OFF
        state == BATTERY_LOW -> BATTERY_LOW
        else -> ON
    }

    /** One line for the card or the log. */
    fun stateWords(state: String, floor: Int, reason: String? = null): String = when {
        state == ON -> "on"
        state == BATTERY_LOW -> "paused — battery below $floor% and not charging"
        state == CAP_REACHED -> "paused — enough photos this hour"
        reason == PORTAL_OFF || reason == PORTAL_NOT_LINKED -> "off — the applet's web portal is off"
        reason == PHOTO_SHARING_OFF -> "off — photo sharing is off"
        reason == "paused" -> "paused"
        else -> "off"
    }

    /**
     * How good a face crop is for telling people apart, 0-1: big enough ([faceShare] of the picture, full marks
     * from 4 %), facing the camera (head turned [eulerY] / tilted [eulerZ] degrees), and how many of the 5
     * landmarks were found.
     */
    fun quality(faceShare: Double, eulerY: Float, eulerZ: Float, landmarks: Int): Double {
        val size = (faceShare / 0.04).coerceIn(0.0, 1.0)
        val pose = (1.0 - maxOf(abs(eulerY) / 45.0, abs(eulerZ) / 30.0)).coerceIn(0.0, 1.0)
        val marks = (landmarks.coerceIn(0, 5) / 5.0)
        return Math.round((0.4 * size + 0.4 * pose + 0.2 * marks) * 1000) / 1000.0
    }

    // ---- alignment ------------------------------------------------------------------------------------------------

    data class Pt(val x: Double, val y: Double)

    /** Where the 5 points go in the 112 x 112 crop (the standard ArcFace template): eyes, nose tip, mouth corners, image-left first. */
    val ARCFACE_112 = listOf(Pt(38.2946, 51.6963), Pt(73.5318, 51.5014), Pt(56.0252, 71.7366), Pt(41.5493, 92.3655), Pt(70.7299, 92.2041))

    /**
     * The 5 points in template order from two eyes, the nose base and two mouth corners as found. Face finders
     * name eyes from the subject's side, so the pairs are ordered by image x instead: the left one in the picture first.
     */
    fun ordered(eyeA: Pt, eyeB: Pt, nose: Pt, mouthA: Pt, mouthB: Pt): List<Pt> =
        listOf(minOf(eyeA, eyeB, compareBy { it.x }), maxOf(eyeA, eyeB, compareBy { it.x }), nose,
            minOf(mouthA, mouthB, compareBy { it.x }), maxOf(mouthA, mouthB, compareBy { it.x }))

    /**
     * The least-squares similarity transform (rotation, uniform scale, translation; no reflection) taking [src]
     * onto [dst]: x' = a·x − b·y + tx, y' = b·x + a·y + ty. Returns [a, b, tx, ty].
     */
    fun similarity(src: List<Pt>, dst: List<Pt>): DoubleArray {
        require(src.size == dst.size && src.size >= 2)
        val n = src.size
        val mx = src.sumOf { it.x } / n; val my = src.sumOf { it.y } / n
        val nx = dst.sumOf { it.x } / n; val ny = dst.sumOf { it.y } / n
        var den = 0.0; var sa = 0.0; var sb = 0.0
        for (i in 0 until n) {
            val px = src[i].x - mx; val py = src[i].y - my
            val qx = dst[i].x - nx; val qy = dst[i].y - ny
            den += px * px + py * py
            sa += px * qx + py * qy
            sb += px * qy - py * qx
        }
        require(den > 1e-9) { "the points are all in one place" }
        val a = sa / den; val b = sb / den
        return doubleArrayOf(a, b, nx - (a * mx - b * my), ny - (b * mx + a * my))
    }

    fun apply(t: DoubleArray, p: Pt): Pt = Pt(t[0] * p.x - t[1] * p.y + t[2], t[1] * p.x + t[0] * p.y + t[3])

    /** The transform as a row-major 3 x 3 matrix (what android.graphics.Matrix.setValues takes). */
    fun matrix(t: DoubleArray): FloatArray = floatArrayOf(t[0].toFloat(), (-t[1]).toFloat(), t[2].toFloat(), t[1].toFloat(), t[0].toFloat(), t[3].toFloat(), 0f, 0f, 1f)

    // ---- what goes to the portal -------------------------------------------------------------------------------

    /** A face box in fractions (0-1) of its photo. */
    data class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
        fun json(): JSONObject = JSONObject().put("x0", r4(x0)).put("y0", r4(y0)).put("x1", r4(x1)).put("y1", r4(y1))
    }

    class Sighting(
        val id: String, val at: Long, val burst: String, val crop: String, val embedding: FloatArray?, val model: String?,
        val faceShare: Double, val quality: Double, val box: Box,
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("at", at).put("burst", burst).put("crop", crop)
            .put("embedding", embedding?.let { e -> JSONArray().apply { e.forEach { put(Math.round(it * 1e6) / 1e6) } } } ?: JSONObject.NULL)
            .put("model", model ?: JSONObject.NULL).put("face_share", r4(faceShare)).put("quality", quality)
    }

    /** One burst that found faces: its kept sightings (best first) and how many faces one photo of it showed at most. */
    class Burst(val id: String, val agentId: String, val watchId: String, val at: Long, val count: Int, val sightings: List<Sighting>) {
        /** The portal's person_seen record for this burst (the push body's records list): boxes only, no ids or crops. */
        fun record(): JSONObject = JSONObject().put("kind", "person_seen").put("at", at).put("label", "face").put("count", count)
            .put("items", JSONArray(sightings.take(20).map { s ->
                JSONObject().put("label", "face").put("confidence", Math.round(s.quality * 100).toInt().coerceIn(0, 100))
                    .put("box", JSONArray(listOf(s.box.x0, s.box.y0, s.box.x1, s.box.y1).map { Math.round(it.coerceIn(0.0, 1.0) * 1000) / 1000.0 }))
            }))
    }

    /**
     * The sightings waiting for the portal, in memory only: at most [cap] sightings (oldest bursts dropped first),
     * none older than [maxAgeMs]. Thread-safe.
     */
    class Queue(private val cap: Int = QUEUE_CAP, private val maxAgeMs: Long = QUEUE_MAX_AGE_MS) {
        private val bursts = ArrayList<Burst>()

        @Synchronized fun add(b: Burst, now: Long) {
            if (b.sightings.isEmpty()) return
            bursts += Burst(b.id, b.agentId, b.watchId, b.at, b.count, b.sightings.take(minOf(cap, MAX_SIGHTINGS_PER_BURST)))
            trim(now)
        }

        @Synchronized private fun trim(now: Long) {
            bursts.removeAll { now - it.at > maxAgeMs }
            bursts.sortBy { it.at }
            while (bursts.sumOf { it.sightings.size } > cap) bursts.removeAt(0)
        }

        /** The oldest waiting burst of [agentId], or null. */
        @Synchronized fun next(agentId: String, now: Long): Burst? { trim(now); return bursts.firstOrNull { it.agentId == agentId } }
        @Synchronized fun remove(id: String) { bursts.removeAll { it.id == id } }
        @Synchronized fun drop(agentId: String) { bursts.removeAll { it.agentId == agentId } }
        @Synchronized fun clear() { bursts.clear() }
        @Synchronized fun dropWatch(watchId: String) { bursts.removeAll { it.watchId == watchId } }
        @Synchronized fun sightings(agentId: String? = null, now: Long): Int { trim(now); return bursts.filter { agentId == null || it.agentId == agentId }.sumOf { it.sightings.size } }
        @Synchronized fun sightingsOfWatch(watchId: String, now: Long): Int { trim(now); return bursts.filter { it.watchId == watchId }.sumOf { it.sightings.size } }
    }

    /** At most [MAX_SIGHTINGS_PER_BURST] faces of a burst, best quality first. */
    fun best(all: List<Sighting>): List<Sighting> = all.sortedByDescending { it.quality }.take(MAX_SIGHTINGS_PER_BURST)

    // ---- mess windows ------------------------------------------------------------------------------------------

    /** [before]: last reading time of each label when it opened (null for episodes saved before that was kept). */
    data class Ep(val label: String, val start: Long, val last: Long, val before: Map<String, Long>? = null)

    data class MessEvent(val messyAt: Long, val state: String, val cleanAt: Long?) {
        fun json(): JSONObject = JSONObject().put("messy_at", messyAt).put("state", state).put("clean_at", cleanAt ?: JSONObject.NULL)
    }

    /**
     * Each messy or dirty episode that started after [after], oldest first (at most [max]), with the last clean
     * reading before it (null when there's none). Clean and messy answers interleave (a clean episode can still be
     * open when a messy one starts), so the time stamped on the episode wins; older episodes fall back to the end
     * of the latest clean episode that ended before it.
     */
    fun messEvents(episodes: List<Ep>, after: Long, max: Int = MAX_MESS_EVENTS_PER_PUSH): List<MessEvent> {
        val cleans = episodes.filter { it.label == CLEAN_LABEL }
        return episodes.filter { it.label in MESSY_LABELS && it.start > after }.sortedBy { it.start }.take(max).map { e ->
            MessEvent(e.start, e.label, if (e.before != null) e.before[CLEAN_LABEL] else cleans.filter { it.last < e.start }.maxOfOrNull { it.last })
        }
    }

    private fun r4(x: Double) = Math.round(x * 10000) / 10000.0
}
