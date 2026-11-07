package cc.bridgit.app.watch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.vision.FaceEmbedder
import cc.bridgit.app.vision.FaceModel
import cc.bridgit.app.vision.FindRules
import cc.bridgit.app.vision.Finder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

/**
 * The phone side of a people watch ([PeopleRules]): the battery gate and hourly cap on every check, the burst when
 * the picture changed, faces found with ML Kit (landmarks on), display crops and aligned crops for the
 * [FaceEmbedder], and the in-memory queue the applet's portal push drains ([cc.bridgit.app.remoteview.PackagePortals]).
 * Nothing is written to files; one log line per burst, never an image.
 */
class WatchPeople(private val app: BridgeApp, private val sampler: WatchSampler) {
    val queue = PeopleRules.Queue()
    val embedder = FaceEmbedder(app) { level, msg -> app.log.system("diagnostic", "people", msg, level = if (level == "warn") "warn" else "info") }

    /**
     * Why faces can't be captured for an agent's applet (portal off or unlinked, photo sharing off), or null when
     * they can. Set by the applet portals; until then nothing is captured.
     */
    @Volatile var blocker: ((String) -> String?)? = null
    private fun blockerOf(agentId: String): String? = blocker?.let { b -> runCatching { b(agentId) }.getOrDefault(PeopleRules.PORTAL_OFF) } ?: PeopleRules.PORTAL_OFF

    /** Per people watch, memory only. */
    class State {
        val bursts = ArrayDeque<Long>()
        @Volatile var state: String = PeopleRules.OFF
        @Volatile var blocker: String? = PeopleRules.PORTAL_OFF
        @Volatile var lastBurstAt = 0L
        @Volatile var day: String = ""
        @Volatile var facesToday = 0
        @Volatile var batteryPercent: Int? = null
        @Volatile var charging = false
        fun burstsLastHour(now: Long): Int = synchronized(bursts) {
            while (bursts.isNotEmpty() && now - bursts.first() >= 3_600_000L) bursts.removeFirst()
            bursts.size
        }
    }

    private val states = ConcurrentHashMap<String, State>()
    private fun st(w: Watches.Watch) = states.getOrPut(w.id) { State() }

    /** The capture state of [w] now (the watch's own pause counts as off). */
    fun captureState(w: Watches.Watch): String = if (w.userPaused) PeopleRules.OFF else st(w).state

    /** The `people` block of the watch's status (GET /v1/watches/{id}, the applet's card). */
    fun status(w: Watches.Watch): JSONObject {
        val o = w.spec.people ?: return JSONObject()
        val s = st(w)
        val now = System.currentTimeMillis()
        val state = captureState(w)
        val reason = PeopleRules.reason(w.userPaused, s.blocker, state)
        return JSONObject().put("state", state).put("reason", reason ?: JSONObject.NULL)
            .put("words", PeopleRules.stateWords(state, o.batteryFloor, reason)).put("battery_floor", o.batteryFloor)
            .put("last_burst_at", if (s.lastBurstAt > 0) isoAt(s.lastBurstAt) else JSONObject.NULL)
            .put("faces_today", if (s.day == today()) s.facesToday else 0)
            .put("queued", queue.sightingsOfWatch(w.id, now))
            .put("embedder", embedder.state)
    }

    /** The `capture` object the portal push sends. */
    fun capture(w: Watches.Watch?): JSONObject {
        if (w == null) return JSONObject().put("state", PeopleRules.OFF)
        val s = st(w)
        return JSONObject().put("state", PeopleRules.pushState(w.userPaused, s.blocker, s.state))
            .apply { s.batteryPercent?.let { put("battery_percent", it) } }
            .put("charging", s.charging).put("battery_floor", w.spec.people?.batteryFloor ?: PeopleRules.Options().batteryFloor)
    }

    /** The capture state in words, e.g. "off — the applet's web portal is off". */
    fun words(w: Watches.Watch): String {
        val s = st(w)
        return PeopleRules.stateWords(s.state, w.spec.people?.batteryFloor ?: PeopleRules.Options().batteryFloor, PeopleRules.reason(w.userPaused, s.blocker, s.state))
    }

    fun ended(watchId: String) { states.remove(watchId); queue.dropWatch(watchId) }

    private fun today(): String = LocalDate.now(ZoneId.systemDefault()).toString()

    /**
     * Before each check of a people watch: the portal, the battery and the hourly cap. Returns whether the watch
     * takes its photo at all: no camera use while the portal can't take faces or the battery is low.
     */
    fun beforeCheck(w: Watches.Watch): Boolean {
        val o = w.spec.people ?: return true
        val s = st(w)
        val b = runCatching { sampler.battery() }.getOrNull()
        s.batteryPercent = b?.opt("percent")?.let { it as? Number }?.toInt()
        s.charging = b?.optBoolean("charging") == true
        s.blocker = blockerOf(w.agentId)
        val before = s.state
        s.state = PeopleRules.gate(w.userPaused, s.blocker, s.batteryPercent, s.charging, o.batteryFloor, s.burstsLastHour(System.currentTimeMillis()), o.maxBurstsPerHour)
        if (s.state != before) app.log.system("diagnostic", "people", "Watch ${w.id}: people spotting ${PeopleRules.stateWords(s.state, o.batteryFloor, PeopleRules.reason(w.userPaused, s.blocker, s.state))}" +
            " (battery ${s.batteryPercent ?: "?"}%, ${if (s.charging) "charging" else "not charging"})")
        // The face model is fetched (and checked) only while faces could really be captured.
        if (s.state == PeopleRules.ON) embedder.ensure()
        return PeopleRules.takesPhotos(s.state)
    }

    /** After a check that took a photo: when the picture [changed] and the gate is on, a burst starting with [photo]. */
    suspend fun afterCheck(w: Watches.Watch, photo: ByteArray?, shotAt: Long, changed: Boolean) {
        val o = w.spec.people ?: return
        val s = st(w)
        if (!changed || photo == null) return
        if (s.state != PeopleRules.ON) {
            app.log.system("diagnostic", "people", "Watch ${w.id}: the picture changed, no burst (${PeopleRules.stateWords(s.state, o.batteryFloor, PeopleRules.reason(w.userPaused, s.blocker, s.state))})")
            return
        }
        burst(w, photo, shotAt, force = false)
    }

    /** A burst: [first] plus burst-1 more photos burst_gap_ms apart (skipped quietly while the camera is busy). */
    suspend fun burst(w: Watches.Watch, first: ByteArray, firstAt: Long, force: Boolean, size: Int = w.spec.people?.burst ?: 1) {
        val o = w.spec.people ?: return
        val s = st(w)
        val started = System.currentTimeMillis()
        synchronized(s.bursts) { s.bursts.addLast(started) }
        s.lastBurstAt = started
        val photos = mutableListOf(first to firstAt)
        var busy = 0
        repeat(size - 1) {
            delay(o.burstGapMs.toLong())
            val more = runCatching { sampler.read(listOf("camera"), null, w.spec.camera, "People: ${w.spec.label}", w.agentId, cameraWaitMs = 0) }.getOrNull()
            val bytes = more?.photo
            if (bytes != null) photos += bytes to System.currentTimeMillis() else busy++
        }
        val r = analyse(photos, o, w.agentId, w.id, UUID.randomUUID().toString())
        if (r.burst != null) {
            queue.add(r.burst, System.currentTimeMillis())
            val d = today()
            if (s.day != d) { s.day = d; s.facesToday = 0 }
            s.facesToday += r.burst.sightings.size
        }
        app.log.system("diagnostic", "people", "Watch ${w.id}: burst of ${photos.size} photo${if (photos.size == 1) "" else "s"}" +
            (if (busy > 0) " ($busy skipped, camera busy)" else "") + ", ${r.found} face${if (r.found == 1) "" else "s"} found, ${r.burst?.sightings?.size ?: 0} kept, " +
            "${r.embedded} embedded" + (if (r.embedded == 0 && (r.burst?.sightings?.size ?: 0) > 0) " (face model ${embedder.state}${embedder.reason?.let { ": $it" } ?: ""})" else "") +
            "; battery ${s.batteryPercent ?: "?"}%, ${if (s.charging) "charging" else "not charging"}${if (force) ", gate bypassed (debug)" else ""}")
    }

    class Result(val burst: PeopleRules.Burst?, val found: Int, val embedded: Int)

    /** Faces in [photos] → at most 4 sightings (best first) and the most faces any one photo showed. */
    suspend fun analyse(photos: List<Pair<ByteArray, Long>>, o: PeopleRules.Options, agentId: String, watchId: String, burstId: String): Result =
        withContext(Dispatchers.Default) {
            class Cand(val bmp: Bitmap, val at: Long, val face: Face, val box: PeopleRules.Box, val share: Double, val quality: Double, val marks: List<PeopleRules.Pt>?)
            val cands = ArrayList<Cand>()
            val bitmaps = ArrayList<Bitmap>()
            var most = 0; var found = 0
            try {
                for ((bytes, at) in photos) {
                    val bmp = Finder.decode(bytes, 1280) ?: continue
                    bitmaps += bmp
                    val faces = runCatching { detect(bmp, (sqrt(o.minFaceShare) * 0.7).toFloat().coerceIn(0.02f, 0.9f)) }.getOrElse { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        app.log.system("diagnostic", "people", "Face finding failed: ${e.message ?: e.javaClass.simpleName}", level = "warn"); emptyList()
                    }
                    val w = bmp.width.toDouble(); val h = bmp.height.toDouble()
                    var here = 0
                    for (f in faces) {
                        val r = f.boundingBox
                        val box = PeopleRules.Box((r.left / w).coerceIn(0.0, 1.0), (r.top / h).coerceIn(0.0, 1.0), (r.right / w).coerceIn(0.0, 1.0), (r.bottom / h).coerceIn(0.0, 1.0))
                        val share = (box.x1 - box.x0).coerceAtLeast(0.0) * (box.y1 - box.y0).coerceAtLeast(0.0)
                        if (share < o.minFaceShare) continue
                        here++
                        val pts = listOf(FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE, FaceLandmark.NOSE_BASE, FaceLandmark.MOUTH_LEFT, FaceLandmark.MOUTH_RIGHT)
                            .map { f.getLandmark(it)?.position?.let { p -> PeopleRules.Pt(p.x.toDouble(), p.y.toDouble()) } }
                        val marks = if (pts.all { it != null }) PeopleRules.ordered(pts[0]!!, pts[1]!!, pts[2]!!, pts[3]!!, pts[4]!!) else null
                        cands += Cand(bmp, at, f, box, share, PeopleRules.quality(share, f.headEulerAngleY, f.headEulerAngleZ, pts.count { it != null }), marks)
                    }
                    most = maxOf(most, here); found += here
                }
                var embedded = 0
                val kept = cands.sortedByDescending { it.quality }.take(PeopleRules.MAX_SIGHTINGS_PER_BURST).mapNotNull { c ->
                    val crop = displayCrop(c.bmp, c.box) ?: return@mapNotNull null
                    val emb = c.marks?.let { m -> runCatching { aligned(c.bmp, m) }.getOrNull()?.let { a -> try { embedder.embed(a) } finally { a.recycle() } } }
                    if (emb != null) embedded++
                    PeopleRules.Sighting(UUID.randomUUID().toString(), c.at, burstId, android.util.Base64.encodeToString(crop, android.util.Base64.NO_WRAP),
                        emb, if (emb != null) FaceModel.ID else null, c.share, c.quality, c.box)
                }
                Result(if (kept.isEmpty()) null else PeopleRules.Burst(burstId, agentId, watchId, photos.first().second, most, kept), found, embedded)
            } finally { bitmaps.forEach { it.recycle() } }
        }

    /** The face for the portal: square, padded, ≤ 256 px, JPEG, ≤ 40 KB (smaller and softer until it fits). */
    private fun displayCrop(bmp: Bitmap, box: PeopleRules.Box): ByteArray? {
        val r = FindRules.cropRect(listOf(FindRules.Box(box.x0, box.y0, box.x1, box.y1)), bmp.width, bmp.height, pad = PeopleRules.CROP_PAD, minSide = 32, aspect = 1.0)
        val cut = Bitmap.createBitmap(bmp, r[0], r[1], r[2] - r[0], r[3] - r[1])
        try {
            for (edge in listOf(PeopleRules.CROP_EDGE, 192, 128)) {
                val small = Finder.scaled(cut, edge)
                try {
                    for (q in listOf(PeopleRules.CROP_QUALITY, 70, 60)) {
                        val jpeg = Finder.jpeg(small, q)
                        if (jpeg.size <= PeopleRules.CROP_MAX_BYTES) return jpeg
                    }
                } finally { if (small !== cut) small.recycle() }
            }
            return null
        } finally { if (cut !== bmp) cut.recycle() }
    }

    /** The 112 x 112 face the embedder wants: the 5 points moved onto the ArcFace template. */
    private fun aligned(bmp: Bitmap, marks: List<PeopleRules.Pt>): Bitmap {
        val t = PeopleRules.similarity(marks, PeopleRules.ARCFACE_112)
        val out = Bitmap.createBitmap(FaceModel.INPUT, FaceModel.INPUT, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, Matrix().apply { setValues(PeopleRules.matrix(t)) }, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** ML Kit faces with all landmarks (head angles come with every face). */
    private suspend fun detect(bmp: Bitmap, minFaceSize: Float): List<Face> = withTimeout(20_000) {
        suspendCancellableCoroutine { c ->
            val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(minFaceSize).build())
            c.invokeOnCancellation { detector.close() }
            detector.process(InputImage.fromBitmap(bmp, 0)).addOnCompleteListener { task ->
                detector.close()
                if (!c.isActive) return@addOnCompleteListener
                if (task.isSuccessful) c.resume(task.result)
                else c.resumeWithException(Finder.Unavailable("On-device face finding isn't ready (${task.exception?.message ?: "no detail"}).", "on_device_unavailable"))
            }
        }
    }

    /**
     * Debug builds: the face pipeline on a JPEG file, as a burst of one photo of [w] (gate bypassed), queued like a
     * real one. Returns a one-line summary.
     */
    suspend fun runFile(w: Watches.Watch, bytes: ByteArray): String {
        embedder.ensure()
        burst(w, bytes, System.currentTimeMillis(), force = true, size = 1)
        return "queued ${queue.sightingsOfWatch(w.id, System.currentTimeMillis())} sightings for ${w.id}; embedder ${embedder.state}${embedder.reason?.let { " ($it)" } ?: ""}"
    }
}
