package cc.bridgit.app.capability

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiError
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The limits and wire format of a pose session (POST /v1/camera/pose/start). Pure Kotlin, unit-tested.
 * A frame is the 33 ML Kit body points as one flat list [x0, y0, v0, x1, y1, v1, ...]: x and y are fractions
 * of the upright camera picture (not mirrored), v is how sure the phone is that the point is in view (0-1).
 */
object PoseRules {
    const val POINTS = 33
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 120
    const val DEFAULT_SECONDS = 60
    /** A session nobody has read for this long stops, so a closed card never keeps the camera. */
    const val IDLE_STOP_MS = 10_000L
    /** Finished sessions stay readable this long (the card's replay and scoring read them back). */
    const val KEEP_MS = 15 * 60_000L
    const val MAX_KEPT = 3
    const val THUMB_EVERY_MS = 500L
    const val THUMB_EDGE = 240
    const val MAX_THUMBS = 2 * MAX_SECONDS
    const val DEFAULT_LIMIT = 40
    const val MAX_LIMIT = 80

    /** ML Kit's names for the 33 points, in frame order. */
    val NAMES = listOf("nose", "left_eye_inner", "left_eye", "left_eye_outer", "right_eye_inner", "right_eye", "right_eye_outer",
        "left_ear", "right_ear", "mouth_left", "mouth_right", "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
        "left_wrist", "right_wrist", "left_pinky", "right_pinky", "left_index", "right_index", "left_thumb", "right_thumb",
        "left_hip", "right_hip", "left_knee", "right_knee", "left_ankle", "right_ankle", "left_heel", "right_heel",
        "left_foot_index", "right_foot_index")

    class Frame(val i: Int, val t: Int, val p: FloatArray)

    /** A whole number from [min] to [max], [default] when absent; null = invalid. */
    fun int(raw: Any?, min: Int, max: Int, default: Int): Int? = when (raw) {
        null, JSONObject.NULL -> default
        is Int -> raw.takeIf { it in min..max }
        is Long -> raw.takeIf { it in min..max }?.toInt()
        is Double -> raw.takeIf { it == Math.floor(it) && it >= min && it <= max }?.toInt()
        is String -> raw.toIntOrNull()?.takeIf { it in min..max }
        else -> null
    }

    /** One point list, rounded so a frame is about 500 bytes of JSON. */
    fun frameJson(f: Frame): JSONObject {
        val a = JSONArray()
        for (k in f.p.indices) a.put(if (k % 3 == 2) round(f.p[k], 100) else round(f.p[k], 1000))
        return JSONObject().put("i", f.i).put("t", f.t).put("p", a)
    }

    private fun round(v: Float, by: Int): Double = Math.round(v.toDouble() * by) / by.toDouble()

    /** Frames from index [since], at most [limit]: the page and the index to ask from next. */
    fun page(frames: List<Frame>, since: Int, limit: Int): Pair<List<Frame>, Int> {
        val from = frames.indexOfFirst { it.i >= since }.takeIf { it >= 0 } ?: return emptyList<Frame>() to maxOf(since, (frames.lastOrNull()?.i ?: -1) + 1)
        val out = frames.subList(from, minOf(frames.size, from + limit)).toList()
        return out to (out.last().i + 1)
    }

    /** The index of the kept picture nearest [t] in [times] (ascending), or -1 when there are none. */
    fun nearest(times: List<Int>, t: Int): Int {
        if (times.isEmpty()) return -1
        var best = 0
        for (k in times.indices) if (Math.abs(times[k] - t) < Math.abs(times[best] - t)) best = k
        return best
    }
}

/**
 * On-phone body tracking for POST /v1/camera/pose/start: the camera streams small frames, ML Kit's pose
 * detector (bundled model, offline, free) finds 33 body points in each, and the session keeps every frame's
 * points, a fresh preview picture and a small picture every half second for a replay. Holds the camera lock
 * and the capture notification while it runs. Nothing leaves the phone.
 */
class PoseSessions(private val lock: Mutex) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = LinkedHashMap<String, Session>()

    class Pic(val t: Int, val bytes: ByteArray, val width: Int, val height: Int)

    inner class Session(val id: String, val agentId: String, val rear: Boolean, val startedAt: Long, val maxMs: Long,
                        val previewEveryMs: Long, val previewEdge: Int) {
        val frames = ArrayList<PoseRules.Frame>()
        val thumbs = ArrayList<Pic>()
        @Volatile var preview: Pic? = null
        @Volatile var state = "starting"
        @Volatile var error: String? = null
        @Volatile var endedAt = 0L
        @Volatile var lastReadAt = startedAt
        @Volatile var analyzed = 0
        @Volatile var width = 0
        @Volatile var height = 0
        internal var stopRequested = false
        internal var lastPreviewAt = -1_000_000L
        internal var lastThumbAt = -1_000_000L
        internal var release: (suspend () -> Unit)? = null

        val running get() = state == "starting" || state == "running"
        fun elapsedMs(now: Long = System.currentTimeMillis()) = ((if (endedAt > 0) endedAt else now) - startedAt).coerceAtLeast(0)

        fun json(now: Long, since: Int, limit: Int, previewAfter: Int?): JSONObject {
            val (page, next) = synchronized(frames) { PoseRules.page(frames, since, limit) }
            val total = synchronized(frames) { frames.size }
            val el = elapsedMs(now)
            val o = JSONObject().put("session_id", id).put("state", state).put("camera", if (rear) "rear" else "front")
                .put("mirror", !rear).put("width_px", width).put("height_px", height)
                .put("elapsed_ms", el).put("max_ms", maxMs).put("analyzed", analyzed).put("frames_total", total)
                .put("fps", if (el > 0) Math.round(analyzed * 10_000.0 / el) / 10.0 else 0.0)
                .put("frames", JSONArray(page.map(PoseRules::frameJson))).put("next", next)
                .put("thumbs", synchronized(thumbs) { thumbs.size })
            if (since == 0) o.put("points", JSONArray(PoseRules.NAMES))
            error?.let { o.put("error", it) }
            preview?.takeIf { previewAfter != null && it.t > previewAfter }?.let { o.put("preview", picJson(it)) }
            return o
        }
    }

    fun picJson(p: Pic): JSONObject = JSONObject().put("t", p.t).put("width_px", p.width).put("height_px", p.height)
        .put("format", "jpeg").put("data_base64", Base64.encodeToString(p.bytes, Base64.NO_WRAP))

    @Synchronized fun get(id: String, agentId: String): Session? {
        prune(System.currentTimeMillis())
        return sessions[id]?.takeIf { it.agentId == agentId }
    }

    @Synchronized private fun prune(now: Long) {
        sessions.entries.removeAll { (_, s) -> !s.running && now - s.endedAt > PoseRules.KEEP_MS }
        while (sessions.count { !it.value.running } > PoseRules.MAX_KEPT) {
            val oldest = sessions.values.filter { !it.running }.minByOrNull { it.endedAt } ?: break
            sessions.remove(oldest.id)
        }
    }

    /** Starts tracking; the camera lock must be free (sensor_busy otherwise). */
    @OptIn(ExperimentalGetImage::class)
    suspend fun start(service: BridgeService, agentId: String, note: String, rear: Boolean, seconds: Int,
                      previewEveryMs: Int, previewEdge: Int): Session {
        val km = service.getSystemService(KeyguardManager::class.java)
        if (km.isDeviceLocked) throw ApiError(409, "device_locked", "Phone is locked. Unlock it, then retry.")
        if (!lock.tryLock()) throw ApiError(409, "sensor_busy", "The camera is busy with another capture. Retry in a few seconds.")
        val token = service.captureStarted(note)
        val now = System.currentTimeMillis()
        val s = Session("pose_" + java.lang.Long.toString(now, 36) + Integer.toString((Math.random() * 1296).toInt(), 36),
            agentId, rear, now, seconds * 1000L, previewEveryMs.toLong(), previewEdge)
        val executor = Executors.newSingleThreadExecutor()
        var detector: PoseDetector? = null
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var released = false
        val release: suspend () -> Unit = release@{
            synchronized(s) { if (released) return@release; released = true }
            // Non-cancellable as a whole: a caller whose request was cut off must still free the camera.
            withContext(NonCancellable) {
                try {
                    withContext(Dispatchers.Main) {
                        runCatching { analysis?.clearAnalyzer() }
                        runCatching { analysis?.let { a -> provider?.unbind(a) } }
                    }
                    runCatching { detector?.close() }
                    runCatching { executor.shutdown() }
                } finally {
                    s.endedAt = System.currentTimeMillis()
                    if (s.running) s.state = if (s.stopRequested) "stopped" else "done"
                    service.captureEnded(token)
                    lock.unlock()
                }
            }
        }
        s.release = release
        synchronized(this) { prune(now); sessions[s.id] = s }
        try {
            val p = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { c -> ProcessCameraProvider.getInstance(service).let { f -> f.addListener({
                    try { c.resume(f.get()) } catch (e: Throwable) { c.resumeWithException(e.cause ?: e) } }, { it.run() }) } }
            }
            provider = p
            val selector = if (rear) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            if (!withContext(Dispatchers.Main) { p.hasCamera(selector) }) throw ApiError(422, "invalid_parameter", "This phone has no ${if (rear) "rear" else "front"} camera.")
            val d = PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
            detector = d
            val a = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .build()
            analysis = a
            a.setAnalyzer(executor) { proxy ->
                val t = (System.currentTimeMillis() - s.startedAt).toInt()
                val media = proxy.image
                if (media == null || !s.running || s.stopRequested || t > s.maxMs) {
                    proxy.close()
                    if (s.running && (s.stopRequested || t > s.maxMs)) scope.launch { release() }
                    return@setAnalyzer
                }
                val rot = proxy.imageInfo.rotationDegrees
                if (s.width == 0) { s.width = if (rot % 180 == 0) proxy.width else proxy.height; s.height = if (rot % 180 == 0) proxy.height else proxy.width }
                s.state = "running"
                val wantPreview = t - s.lastPreviewAt >= s.previewEveryMs
                val wantThumb = t - s.lastThumbAt >= PoseRules.THUMB_EVERY_MS && synchronized(s.thumbs) { s.thumbs.size } < PoseRules.MAX_THUMBS
                if (wantPreview || wantThumb) runCatching {
                    val bmp = upright(proxy.toBitmap(), rot)
                    if (wantPreview) { s.preview = pic(bmp, t, s.previewEdge, 55); s.lastPreviewAt = t.toLong() }
                    if (wantThumb) { synchronized(s.thumbs) { s.thumbs += pic(bmp, t, PoseRules.THUMB_EDGE, 50) }; s.lastThumbAt = t.toLong() }
                    bmp.recycle()
                }
                val w = s.width.toFloat(); val h = s.height.toFloat()
                val task = try { d.process(InputImage.fromMediaImage(media, rot)) } catch (e: Exception) { s.analyzed++; proxy.close(); return@setAnalyzer }
                task
                    .addOnSuccessListener { pose ->
                        s.analyzed++
                        val marks = pose.allPoseLandmarks
                        if (marks.size >= PoseRules.POINTS) {
                            val pts = FloatArray(PoseRules.POINTS * 3)
                            for (m in marks) {
                                val k = m.landmarkType
                                if (k !in 0 until PoseRules.POINTS) continue
                                pts[k * 3] = m.position.x / w; pts[k * 3 + 1] = m.position.y / h; pts[k * 3 + 2] = m.inFrameLikelihood
                            }
                            synchronized(s.frames) { s.frames += PoseRules.Frame(s.frames.size, t, pts) }
                        }
                    }
                    .addOnFailureListener { s.analyzed++ }
                    .addOnCompleteListener { proxy.close() }
            }
            try { withContext(Dispatchers.Main) { p.bindToLifecycle(service, selector, a) } }
            catch (e: Exception) { throw ApiError(409, "sensor_busy", "Camera unavailable: ${e.message}") }
            if (s.stopRequested) throw ApiError(409, "sensor_busy", "Bridgit stopped the camera while it was starting.")
        } catch (e: Throwable) {
            s.state = "failed"; s.error = e.message
            // Stopped while binding: the camera may have been bound after release ran, so unbind it here too.
            withContext(NonCancellable + Dispatchers.Main) { runCatching { analysis?.clearAnalyzer(); analysis?.let { provider?.unbind(it) } } }
            release()
            synchronized(this) { sessions.remove(s.id) }
            throw e
        }
        // Ends the session at its time limit, or when nobody reads it any more (the card was closed).
        scope.launch {
            while (s.running) {
                delay(500)
                val n = System.currentTimeMillis()
                if (n - s.startedAt > s.maxMs + 1500 || n - s.lastReadAt > PoseRules.IDLE_STOP_MS) {
                    if (n - s.lastReadAt > PoseRules.IDLE_STOP_MS) s.stopRequested = true
                    release()
                }
            }
        }
        return s
    }

    suspend fun stop(s: Session) {
        s.stopRequested = true
        s.release?.invoke()
    }

    /** Stops every running session (the owner paused Bridgit or switched the camera off). */
    fun stopAll() {
        val running = synchronized(this) { sessions.values.filter { it.running } }
        running.forEach { s -> scope.launch { stop(s) } }
    }

    private fun upright(b: Bitmap, rot: Int): Bitmap {
        if (rot == 0) return b
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).also { if (it !== b) b.recycle() }
    }

    private fun pic(b: Bitmap, t: Int, edge: Int, quality: Int): Pic {
        val k = minOf(1f, edge.toFloat() / maxOf(b.width, b.height))
        val s = if (k < 1f) Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true) else b
        val out = ByteArrayOutputStream()
        s.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val pic = Pic(t, out.toByteArray(), s.width, s.height)
        if (s !== b) s.recycle()
        return pic
    }
}
