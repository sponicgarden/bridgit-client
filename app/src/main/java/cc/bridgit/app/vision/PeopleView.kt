package cc.bridgit.app.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.vision.PeopleCropRules.Person
import cc.bridgit.app.vision.PeopleCropRules.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * Finds people on the phone and cuts the people view ([PeopleCropRules]). Offline: ML Kit's pose detector (its base
 * model is in the APK) gives each person's body points, so the box is the whole visible body. It finds one person per
 * look, so it looks again with the people already found greyed out, and once more under each face (ML Kit face
 * detection, from Google Play services) that no body covers. A face whose body still isn't found counts with its
 * head and shoulders estimated.
 */
object PeopleView {
    const val DETECTOR = "mlkit_pose+face"
    /** Long side of the copy the detectors look at; boxes are scaled back to the full frame. */
    private const val LOOK_EDGE = 1280
    private const val LOOKS = 4

    class Result(val bitmap: Bitmap, val crop: PeopleCropRules.Crop, val people: List<Person>)

    /**
     * The people view of [src] (not recycled). Throws no_person_found (with the source size) when nobody is in the
     * frame, so the caller returns and keeps nothing.
     */
    suspend fun cut(src: Bitmap, req: PeopleCropRules.Request): Result = withContext(Dispatchers.Default) {
        val people = try { withTimeout(25_000) { find(src) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw ErrorCatalog.error("capture_timeout", "Finding the people took too long. Retry once, or use view \"full\".")
        }
        if (people.isEmpty()) throw ErrorCatalog.error("no_person_found",
            "No person was found in the frame, so nothing was returned or kept. Retry with view \"full\" to get the whole frame.",
            mapOf("view" to "people", "source_width_px" to src.width, "source_height_px" to src.height, "detector" to DETECTOR))
        val crop = PeopleCropRules.cropRect(people.map { it.box }, src.width, src.height, req.padding, req.aspect)
        Result(Bitmap.createBitmap(src, crop.x, crop.y, crop.width, crop.height), crop, people)
    }

    /** People in [src], boxes in its pixels, largest first. */
    suspend fun find(src: Bitmap): List<Person> {
        val look = Finder.scaled(src, LOOK_EDGE)
        val k = src.width.toDouble() / look.width
        try {
            val detector = PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE).build())
            try {
                val bodies = ArrayList<Rect>()
                val masked = look.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    val canvas = Canvas(masked)
                    val grey = Paint().apply { color = Color.rgb(128, 128, 128) }
                    for (i in 0 until LOOKS) {
                        val b = pose(detector, masked, 0.0, 0.0) ?: break
                        if (PeopleCropRules.duplicate(b, bodies)) break
                        bodies += b
                        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), grey)
                    }
                } finally { masked.recycle() }
                val people = bodies.mapTo(ArrayList()) { Person(it, "pose") }
                val faces = try { Finder.faces(look, PeopleCropRules.MAX_PEOPLE) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
                    .map { Rect(it.box.x0 * look.width, it.box.y0 * look.height, it.box.x1 * look.width, it.box.y1 * look.height) }
                for (f in faces) {
                    if (people.size >= PeopleCropRules.MAX_PEOPLE) break
                    if (PeopleCropRules.covered(f, people.map { it.box })) continue
                    val area = PeopleCropRules.searchAround(f, look.width, look.height)
                    val part = Bitmap.createBitmap(look, area.left.toInt(), area.top.toInt(),
                        maxOf(1, area.width.toInt()), maxOf(1, area.height.toInt()))
                    val body = try { pose(detector, part, area.left, area.top) } finally { part.recycle() }
                    people += if (body != null && PeopleCropRules.covered(f, listOf(body)) && !PeopleCropRules.duplicate(body, people.map { it.box }))
                        Person(body, "pose") else Person(PeopleCropRules.bodyFromFace(f, look.width, look.height), "face")
                }
                return people.map { Person(Rect(it.box.left * k, it.box.top * k, it.box.right * k, it.box.bottom * k).clampTo(src.width, src.height), it.source) }
                    .sortedByDescending { it.box.area }.take(PeopleCropRules.MAX_PEOPLE)
            } finally { detector.close() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: java.util.concurrent.TimeoutException) {
            throw ErrorCatalog.error("capture_timeout", "Finding the people took too long. Retry once, or use view \"full\".")
        } catch (e: Exception) {
            throw ErrorCatalog.error("not_supported", "On-phone person finding failed (${e.message ?: e.javaClass.simpleName}). Retry once, or use view \"full\".")
        } finally { if (look !== src) look.recycle() }
    }

    /** One body in [bmp] (a part of the look copy at [dx], [dy]), in look pixels, or null. Blocking ML Kit call. */
    private fun pose(detector: com.google.mlkit.vision.pose.PoseDetector, bmp: Bitmap, dx: Double, dy: Double): Rect? {
        val pose = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)), 10, TimeUnit.SECONDS)
        val pts = pose.allPoseLandmarks.map { doubleArrayOf(it.landmarkType.toDouble(), it.position.x.toDouble(), it.position.y.toDouble(), it.inFrameLikelihood.toDouble()) }
        val b = PeopleCropRules.bodyBox(pts, bmp.width, bmp.height) ?: return null
        return Rect(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy)
    }
}
