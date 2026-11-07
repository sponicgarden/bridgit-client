package cc.bridgit.app.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.judgment.Jev
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Find things in a photo and crop to them: "the dog", "each dirty dish", "the face of each person".
 *
 * Two ways to find them by default:
 * - On the phone, free and offline: faces, with ML Kit face detection (its model comes through Google
 *   Play services, so the APK doesn't grow). Used for face queries.
 * - In the cloud, for anything else: the team backend's POST /v1/locate (Gemini 3.1 Flash-Lite on
 *   OpenRouter, ~$0.0003-0.001 a photo), on the same budget as Jev and Clef.
 * Opt-in only, when the caller names them ([OnDeviceEngines]): the on-phone any-word detector (engine
 * on_device_detector) and outlines (outline true). They never take part in the default routing.
 *
 * Boxes are fractions (0-1) of the upright photo (EXIF rotation applied), origin top left.
 */
object FindRules {
    data class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
        fun json(w: Int? = null, h: Int? = null): JSONObject = JSONObject().put("x0", r4(x0)).put("y0", r4(y0)).put("x1", r4(x1)).put("y1", r4(y1))
            .apply { if (w != null && h != null) put("px", JSONObject().put("left", (x0 * w).toInt()).put("top", (y0 * h).toInt()).put("right", (x1 * w).toInt()).put("bottom", (y1 * h).toInt())) }
        val area get() = (x1 - x0) * (y1 - y0)
    }
    data class Item(val label: String, val box: Box, val score: Double? = null, val outline: List<DoubleArray>? = null) {
        fun json(w: Int, h: Int): JSONObject = JSONObject().put("label", label).put("box", box.json(w, h)).apply {
            if (score != null) put("score", r4(score))
            if (outline != null) put("outline", JSONArray(outline.map { JSONArray(listOf(r4(it[0]), r4(it[1]))) }))
        }
    }

    private fun r4(x: Double) = Math.round(x * 10000) / 10000.0

    private val FACE = Regex("\\bfaces?\\b", RegexOption.IGNORE_CASE)
    private val ANIMAL = Regex("\\b(dog|dogs|cat|cats|pet|pets|puppy|kitten|animal|animals|bird|horse|cow|clock|watch|rock|cliff)\\b", RegexOption.IGNORE_CASE)

    /** Whether [query] asks for human faces, which the phone can find offline. Pure. */
    fun isFaceQuery(query: String): Boolean = FACE.containsMatchIn(query) && !ANIMAL.containsMatchIn(query)

    /**
     * The pixel rectangle to crop for [boxes] in a [w] x [h] photo: their union, grown by [pad] (a
     * fraction of the union's size on each side), at least [minSide] px, clamped to the photo. With
     * [aspect] (width / height), it is widened or heightened to that shape around the same middle.
     * Returns [left, top, right, bottom]. Pure, unit-tested.
     */
    fun cropRect(boxes: List<Box>, w: Int, h: Int, pad: Double = 0.1, minSide: Int = 64, aspect: Double? = null): IntArray {
        require(boxes.isNotEmpty() && w > 0 && h > 0)
        var x0 = boxes.minOf { it.x0 } * w; var y0 = boxes.minOf { it.y0 } * h
        var x1 = boxes.maxOf { it.x1 } * w; var y1 = boxes.maxOf { it.y1 } * h
        val px = (x1 - x0) * pad; val py = (y1 - y0) * pad
        x0 -= px; x1 += px; y0 -= py; y1 += py
        if (aspect != null && aspect > 0) {
            val cw = x1 - x0; val ch = y1 - y0
            if (cw / ch < aspect) { val nw = ch * aspect; x0 -= (nw - cw) / 2; x1 += (nw - cw) / 2 }
            else { val nh = cw / aspect; y0 -= (nh - ch) / 2; y1 += (nh - ch) / 2 }
        }
        val side = minOf(minSide, w, h).toDouble()
        if (x1 - x0 < side) { val g = (side - (x1 - x0)) / 2; x0 -= g; x1 += g }
        if (y1 - y0 < side) { val g = (side - (y1 - y0)) / 2; y0 -= g; y1 += g }
        // Slide back inside before clamping, so a box at the edge keeps its size where it can.
        if (x0 < 0) { x1 -= x0; x0 = 0.0 }; if (x1 > w) { x0 -= x1 - w; x1 = w.toDouble() }
        if (y0 < 0) { y1 -= y0; y0 = 0.0 }; if (y1 > h) { y0 -= y1 - h; y1 = h.toDouble() }
        val l = Math.round(x0).toInt().coerceIn(0, w - 1); val t = Math.round(y0).toInt().coerceIn(0, h - 1)
        val r = (l + maxOf(1, Math.round(x1 - x0).toInt())).coerceAtMost(w); val b = (t + maxOf(1, Math.round(y1 - y0).toInt())).coerceAtMost(h)
        return intArrayOf(l, t, r, b)
    }

    /** A box from an agent's {x0, y0, x1, y1} (fractions), or null if malformed. Pure. */
    fun boxOf(o: JSONObject?): Box? {
        o ?: return null
        val v = listOf("x0", "y0", "x1", "y1").map { (o.opt(it) as? Number)?.toDouble() ?: return null }
        if (v.any { !it.isFinite() || it < 0 || it > 1 } || v[0] >= v[2] || v[1] >= v[3]) return null
        return Box(v[0], v[1], v[2], v[3])
    }

    /** "on_device", "cloud" or "auto" for [mode] and [query]: what auto picks. Pure. */
    fun route(mode: String, query: String): String = when (mode) {
        "auto" -> if (isFaceQuery(query)) "on_device" else "cloud"
        else -> mode
    }
}

object Finder {
    /** What was found, where, and what it cost. */
    data class Found(val items: List<FindRules.Item>, val source: String, val model: String, val costUsd: Double = 0.0, val note: String? = null) {
        fun json(w: Int, h: Int): JSONObject = JSONObject().put("items", JSONArray(items.map { it.json(w, h) })).put("count", items.size)
            .put("source", source).put("model", model).put("cost_usd", costUsd).put("width_px", w).put("height_px", h)
            .put("box_format", "x0, y0, x1, y1 as fractions (0-1) of the upright photo; px in pixels of it")
            .apply { if (note != null) put("note", note) }
    }

    class Unavailable(message: String, val reason: String) : Exception(message)

    /** The upright photo, no larger than [maxEdge] px on its long side. */
    fun decode(bytes: ByteArray, maxEdge: Int = 4096): Bitmap? {
        val rotation = runCatching { ExifInterface(bytes.inputStream()).rotationDegrees }.getOrDefault(0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val long = maxOf(bmp.width, bmp.height)
        val scale = if (long > maxEdge) maxEdge.toFloat() / long else 1f
        if (rotation == 0 && scale == 1f) return bmp
        val m = Matrix().apply { if (rotation != 0) postRotate(rotation.toFloat()); if (scale < 1f) postScale(scale, scale) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    fun png(bmp: Bitmap): ByteArray = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }

    /**
     * [cut] (the [rect] part of a [w] x [h] photo) with everything outside [outlines] (photo fractions) transparent.
     * With no outline it is returned unchanged.
     */
    fun cutOut(cut: Bitmap, rect: IntArray, w: Int, h: Int, outlines: List<List<DoubleArray>>): Bitmap {
        val polys = outlines.filter { it.size >= 3 }
        if (polys.isEmpty()) return cut
        val out = Bitmap.createBitmap(cut.width, cut.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val path = android.graphics.Path().apply {
            for (p in polys) {
                p.forEachIndexed { i, pt -> val x = (pt[0] * w - rect[0]).toFloat(); val y = (pt[1] * h - rect[1]).toFloat(); if (i == 0) moveTo(x, y) else lineTo(x, y) }
                close()
            }
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        c.drawPath(path, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(cut, 0f, 0f, paint)
        return out
    }

    fun jpeg(bmp: Bitmap, quality: Int = 88): ByteArray = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it); it.toByteArray() }

    /** [bmp] scaled so its long side is at most [edge] px (a new bitmap, or [bmp] itself). */
    fun scaled(bmp: Bitmap, edge: Int): Bitmap {
        val long = maxOf(bmp.width, bmp.height)
        if (long <= edge) return bmp
        val s = edge.toFloat() / long
        return Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * s).toInt()), maxOf(1, (bmp.height * s).toInt()), true)
    }

    /** Human faces on the phone. Throws [Unavailable] while Play services is still fetching the model. */
    suspend fun faces(bmp: Bitmap, max: Int): List<FindRules.Item> = withTimeout(20_000) {
        suspendCancellableCoroutine { c ->
            val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).setMinFaceSize(0.02f).build())
            c.invokeOnCancellation { detector.close() }
            detector.process(InputImage.fromBitmap(bmp, 0)).addOnCompleteListener { task ->
                detector.close()
                if (!c.isActive) return@addOnCompleteListener
                if (!task.isSuccessful) {
                    c.resumeWith(Result.failure(Unavailable("On-device face finding isn't ready (${task.exception?.message ?: "no detail"}); Google Play services may still be downloading it.", "on_device_unavailable")))
                    return@addOnCompleteListener
                }
                val w = bmp.width.toDouble(); val h = bmp.height.toDouble()
                val items = task.result.mapNotNull { f ->
                    val r = f.boundingBox
                    val b = FindRules.Box((r.left / w).coerceIn(0.0, 1.0), (r.top / h).coerceIn(0.0, 1.0), (r.right / w).coerceIn(0.0, 1.0), (r.bottom / h).coerceIn(0.0, 1.0))
                    b.takeIf { it.x1 > it.x0 && it.y1 > it.y0 }?.let { FindRules.Item("face", it) }
                }.sortedByDescending { it.box.area }.take(max)
                c.resume(items)
            }
        }
    }

    /** The cloud: a ~1024 px copy goes to the team backend. Blocking network inside; throws [Jev.Failure]. */
    suspend fun cloud(app: BridgeApp, bmp: Bitmap, query: String, max: Int, applet: String? = null): Found = withContext(Dispatchers.IO) {
        val small = scaled(bmp, 1024)
        val bytes = try { jpeg(small, 85) } finally { if (small !== bmp) small.recycle() }
        val r = Jev.locate(app, bytes, query, max, applet)
        Found(r.items.map { FindRules.Item(it.label, FindRules.Box(it.x0, it.y0, it.x1, it.y1)) }, "cloud", r.model, r.costUsd)
    }

    /**
     * Finds [query] in [bmp]. [mode]: "auto" (faces on the phone, the rest in the cloud), "on_device"
     * (faces only, never uploads) or "cloud". In auto, a face query falls back to the cloud if the
     * on-device model isn't ready. [engine] (opt-in, e.g. on_device_detector) overrides the routing and never
     * falls back: it throws [OnDeviceVision.NotReady] while its model downloads.
     */
    suspend fun find(app: BridgeApp, bmp: Bitmap, query: String, mode: String = "auto", max: Int = 20, applet: String? = null, engine: String? = null): Found {
        if (engine != null)
            return Found(app.onDeviceVision.detect(bmp, query, max, engine), "on_device", app.onDeviceVision.resolve(engine).first.model)
        val route = FindRules.route(mode, query)
        if (route == "on_device") {
            if (!FindRules.isFaceQuery(query))
                throw Unavailable("On the phone Bridgit can only find faces. Use mode auto or cloud for \"$query\".", "on_device_faces_only")
            try {
                return Found(faces(bmp, max), "on_device", "mlkit-face-detection")
            } catch (e: Unavailable) {
                if (mode != "auto") throw e
                return cloud(app, bmp, query, max, applet).copy(note = "Answered in the cloud: ${e.message}")
            }
        }
        return cloud(app, bmp, query, max, applet)
    }

    /** [found] with an outline on each item (opt-in engine on_device_outline); throws [OnDeviceVision.NotReady]. */
    suspend fun outlined(app: BridgeApp, bmp: Bitmap, found: Found): Found {
        if (found.items.isEmpty()) return found
        val polys = app.onDeviceVision.outline(bmp, found.items.map { it.box })
        return found.copy(items = found.items.zip(polys).map { (i, p) -> i.copy(outline = p) },
            model = found.model + " + " + app.onDeviceVision.resolve(OnDeviceEngines.OUTLINE).first.model)
    }
}
