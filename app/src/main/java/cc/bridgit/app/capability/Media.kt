package cc.bridgit.app.capability

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.Sizing
import cc.bridgit.app.core.Compat

class Encoded(val bytes: ByteArray, val width: Int, val height: Int, val mime: String)

/**
 * Permission names as this Android version knows them. Below Android 13 photos and videos fall under
 * READ_EXTERNAL_STORAGE and notifications need no runtime grant; "Select photos" exists from Android 14.
 * Null means the permission doesn't exist here, so there is nothing to ask for.
 */
object Perms {
    fun platform(p: String): String? = when {
        p == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33 -> null
        p == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && Build.VERSION.SDK_INT < 34 -> null
        (p == Manifest.permission.READ_MEDIA_IMAGES || p == Manifest.permission.READ_MEDIA_VIDEO) && Build.VERSION.SDK_INT < 33 ->
            Manifest.permission.READ_EXTERNAL_STORAGE
        else -> p
    }

    /** What to pass to a permission request on this Android version. */
    fun forRequest(perms: Array<out String>): Array<String> = perms.mapNotNull(::platform).distinct().toTypedArray()
}

/** Whether [p] is held, by its meaning on this Android version: notifications count as granted before Android 13. */
fun Context.granted(p: String): Boolean {
    val real = Perms.platform(p) ?: return p == Manifest.permission.POST_NOTIFICATIONS
    return ContextCompat.checkSelfPermission(this, real) == PackageManager.PERMISSION_GRANTED
}

fun isoAt(epochMs: Long): String =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
        .truncatedTo(ChronoUnit.SECONDS).toString()

object ImageUtil {
    /**
     * Applies EXIF rotation and resizes per [spec] (API-10). Returns the original
     * bytes untouched when nothing needs to change and [forceJpeg] is false.
     */
    fun prepare(original: ByteArray, mime: String, spec: Sizing.Spec, forceJpeg: Boolean = false): Encoded {
        val exif = runCatching { ExifInterface(original.inputStream()) }.getOrNull()
        val rotation = exif?.rotationDegrees ?: 0
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0) throw ApiError(500, "internal_error", "Could not decode image")
        val (w, h) = if (rotation % 180 != 0) rawH to rawW else rawW to rawH
        val (tw, th) = Sizing.target(w, h, spec)
        val needsJpeg = forceJpeg && mime != "image/jpeg"
        if (spec.original || (tw == w && rotation == 0 && !needsJpeg)) return Encoded(original, w, h, mime)

        val sample = Sizing.sampleSize(maxOf(w, h), maxOf(tw, th))
        val bmp = BitmapFactory.decodeByteArray(original, 0, original.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw ApiError(500, "internal_error", "Could not decode image")
        val m = Matrix()
        if (rotation != 0) m.postRotate(rotation.toFloat())
        val rotatedW = if (rotation % 180 != 0) bmp.height else bmp.width
        val scale = tw.toFloat() / rotatedW
        if (scale < 1f) m.postScale(scale, scale)
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, spec.quality, bos)
        val res = Encoded(bos.toByteArray(), out.width, out.height, "image/jpeg")
        if (out !== bmp) out.recycle()
        bmp.recycle()
        return res
    }
}

/** A0064: CameraX camera-state errors that mean the photo can't happen soon, as the agent's error. Null: keep waiting. */
object CameraBlock {
    const val GRACE_MS = 3_000L

    fun error(code: Int?, rear: Boolean): ApiError? {
        val which = if (rear) "rear" else "front"
        return when (code) {
            androidx.camera.core.CameraState.ERROR_CAMERA_IN_USE, androidx.camera.core.CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                ApiError(409, "sensor_busy", "Another app on the phone is using the $which camera (for example the Camera app " +
                    "or a video call). Ask the owner to close it, then retry.", mapOf("reason" to "camera_in_use"))
            androidx.camera.core.CameraState.ERROR_CAMERA_DISABLED ->
                ApiError(409, "sensor_busy", "Android has the camera turned off (device policy or the camera privacy switch).",
                    mapOf("reason" to "camera_disabled"))
            else -> null
        }
    }

    /**
     * A0253: how long the camera may wait to open with no error before the photo gives up. When another app already
     * holds the camera, Android marks it unavailable and CameraX waits in PENDING_OPEN without trying to open it, so no
     * error code ever arrives. Normal opens pass through PENDING_OPEN too (the first state reported, a front/rear
     * switch while the previous camera closes), but for well under a second, so only an unbroken wait this long counts.
     */
    const val HELD_MS = 5_000L

    fun held(rear: Boolean): ApiError = ApiError(409, "sensor_busy",
        "The ${if (rear) "rear" else "front"} camera didn't open: something else on the phone is holding it (for example " +
            "another app's camera or a \"take a picture\" screen). Ask the owner to close it, then retry.",
        mapOf("reason" to "camera_held"))

    /** CameraX state names for logs and the capture_timeout reply. */
    fun name(type: androidx.camera.core.CameraState.Type?): String = type?.name?.lowercase() ?: "unknown"

    /**
     * The camera states seen during one photo, fed by CameraX ([update]) and asked by the photo's watcher ([verdict]).
     * Times are the caller's clock in ms.
     */
    class Watch(private val rear: Boolean) {
        private var err: ApiError? = null
        private var errorSince: Long? = null
        private var pendingSince: Long? = null
        @Volatile var last: androidx.camera.core.CameraState.Type? = null; private set

        @Synchronized fun update(type: androidx.camera.core.CameraState.Type, code: Int?, now: Long) {
            last = type
            val e = error(code, rear)
            if (e == null) { err = null; errorSince = null } else { if (errorSince == null) errorSince = now; err = e }
            val waiting = e == null && type == androidx.camera.core.CameraState.Type.PENDING_OPEN
            if (!waiting) pendingSince = null else if (pendingSince == null) pendingSince = now
        }

        /** The error to end the photo with now, or null to keep waiting. */
        @Synchronized fun verdict(now: Long): ApiError? {
            errorSince?.let { if (now - it >= GRACE_MS) return err }
            pendingSince?.let { if (now - it >= HELD_MS) return held(rear) }
            return null
        }
    }
}

class CameraCapture(private val context: Context, private val owner: LifecycleOwner) {

    /** Zoom range of the last camera used, for /v1/capabilities and error messages. */
    @Volatile var lastZoomRange: Pair<Float, Float>? = null; private set

    /**
     * A bound camera that is already running (8.2 arm). CameraX keeps a small metering stream going while only
     * ImageCapture is bound, so exposure and focus settle before the photo is asked for.
     */
    class Warm internal constructor(val rear: Boolean, internal val provider: ProcessCameraProvider,
                                    internal val camera: Camera, internal val capture: ImageCapture)

    /**
     * Opens and binds the camera without taking a photo. The caller must [release] it. [armed]: an arm, which
     * uses the low-latency capture mode. Exposure has already settled by the time the photo is asked for, so
     * the max-quality mode's own pre-capture exposure search (most of the delay) is skipped.
     * [allowLocked]: an agent's photo, which works while the phone is locked (owner decision 7 Nov 2026, A0049;
     * the caller has already asked [LockedCameraApproval] when the owner wants that). Other callers keep refusing.
     */
    suspend fun warmUp(rear: Boolean, armed: Boolean = false, allowLocked: Boolean = false): Warm {
        val km = context.getSystemService(KeyguardManager::class.java)
        if (!allowLocked && km.isDeviceLocked) throw ApiError(409, "device_locked", "Phone is locked. Unlock it, then retry.")
        val provider = withContext(Dispatchers.Main) { ProcessCameraProvider.getInstance(context).await() }
        val selector = if (rear) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        if (!withContext(Dispatchers.Main) { provider.hasCamera(selector) })
            throw ApiError(422, "invalid_parameter", "This phone has no ${if (rear) "rear" else "front"} camera")
        val capture = ImageCapture.Builder()
            .setCaptureMode(if (armed) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()
            ).build()
        val camera = try {
            withContext(Dispatchers.Main) { provider.bindToLifecycle(owner, selector, capture) }
        } catch (e: Exception) {
            throw ApiError(409, "sensor_busy", "Camera unavailable: ${e.message}")
        }
        val zs = withContext(Dispatchers.Main) { camera.cameraInfo.zoomState.value }
        if (zs != null) lastZoomRange = zs.minZoomRatio to zs.maxZoomRatio
        return Warm(rear, provider, camera, capture)
    }

    /** Unbinds [w]; also when the caller was cancelled (client gone), or the camera stays held. Safe to call twice. */
    suspend fun release(w: Warm) {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { w.provider.unbind(w.capture) }
    }

    /**
     * [zoom] (CAP-05): null = the camera's default (1x). Below 1 uses the ultra-wide lens and
     * above about 5 the telephoto on phones whose camera switches lenses by zoom (Pixel).
     * [warm]: a camera already bound by [warmUp]; this call takes it over and releases it. One for the
     * other camera is released and the photo starts cold. [allowLocked]: see [warmUp].
     */
    suspend fun snapshot(rear: Boolean, flash: Boolean, spec: Sizing.Spec, zoom: Float? = null, warm: Warm? = null,
                         allowLocked: Boolean = false): Encoded {
        val file = File(context.cacheDir, "snap-${System.nanoTime()}.jpg")
        try {
            val w = warm?.takeIf { it.rear == rear } ?: run { warm?.let { release(it) }; warmUp(rear, allowLocked = allowLocked) }
            android.util.Log.i(TAG, "snapshot ${if (rear) "rear" else "front"} warm=${w === warm} locked_ok=$allowLocked")
            try {
                if (!allowLocked && warm != null && w === warm) {
                    val km = context.getSystemService(KeyguardManager::class.java)
                    if (km.isDeviceLocked) throw ApiError(409, "device_locked", "Phone is locked. Unlock it, then retry.")
                }
                withContext(Dispatchers.Main) { w.capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }
                if (zoom != null) {
                    val (min, max) = lastZoomRange ?: (1f to 1f)
                    if (zoom < min - 0.001f || zoom > max + 0.001f) throw ApiError(422, "invalid_parameter",
                        "zoom must be ${"%.1f".format(min)} to ${"%.1f".format(max)} on this ${if (rear) "rear" else "front"} camera",
                        mapOf("min_zoom" to min, "max_zoom" to max))
                    withContext(Dispatchers.Main) { w.camera.cameraControl.setZoomRatio(zoom).await() }
                    delay(400) // let focus and exposure settle at the new zoom
                }
                // take() yields Result: success, or the capture error. null here means timeout.
                val watch = CameraBlock.Watch(rear)
                val outcome = unlessTaken(w.camera, watch) { withTimeoutOrNull(20_000) { take(w.capture, file) } }
                    ?: run {
                        val state = CameraBlock.name(watch.last)
                        android.util.Log.w(TAG, "capture_timeout camera_state=$state")
                        throw ApiError(504, "capture_timeout", "Camera did not produce a photo in time", mapOf("camera_state" to state))
                    }
                outcome.exceptionOrNull()?.let { throw ApiError(409, "sensor_busy", "Capture failed: ${it.message}") }
                android.util.Log.i(TAG, "snapshot saved")
            } finally {
                release(w)
            }
            return ImageUtil.prepare(file.readBytes(), "image/jpeg", spec)
        } finally {
            file.delete()
        }
    }

    private companion object { const val TAG = "BridgitCamera" }

    /**
     * A0064: while another app (the Camera app, a video call) holds the camera, Android refuses Bridgit's open and
     * CameraX keeps retrying quietly, so the photo used to wait the full 20 s and end in capture_timeout. Runs [block]
     * but fails with [CameraBlock]'s error once the camera has reported it for [CameraBlock.GRACE_MS] (a brief
     * hand-over between apps still gets its photo). A0253: also fails once the camera has waited to open, with no
     * error, for [CameraBlock.HELD_MS] (another app opened it first, so Android never lets Bridgit try).
     */
    private suspend fun <T> unlessTaken(camera: Camera, watch: CameraBlock.Watch, block: suspend () -> T): T {
        val live = camera.cameraInfo.cameraState
        val obs = androidx.lifecycle.Observer<androidx.camera.core.CameraState> { st ->
            watch.update(st.type, st.error?.code, android.os.SystemClock.elapsedRealtime())
        }
        withContext(Dispatchers.Main) { live.observeForever(obs) }
        try {
            return kotlinx.coroutines.coroutineScope {
                val watcher = launch {
                    while (true) {
                        delay(250)
                        watch.verdict(android.os.SystemClock.elapsedRealtime())?.let {
                            android.util.Log.w(TAG, "camera unavailable: ${it.extra["reason"]} state=${CameraBlock.name(watch.last)}")
                            throw it
                        }
                    }
                }
                try { block() } finally { watcher.cancel() }
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { live.removeObserver(obs) }
        }
    }

    private suspend fun take(capture: ImageCapture, file: File): Result<Unit> =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) { if (cont.isActive) cont.resume(Result.success(Unit)) }
                    override fun onError(e: ImageCaptureException) { if (cont.isActive) cont.resume(Result.failure(e)) }
                })
        }

    private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addListener({ if(cont.isActive) runCatching { get() }.fold({ cont.resume(it) }, { cont.cancel(it) }) },
                ContextCompat.getMainExecutor(context))
        }
}

class MicCapture(private val context: Context) {
    /** [peak]: the loudest moment (0–32,767, MediaRecorder's max amplitude), or null when it couldn't be read. */
    class Clip(val bytes: ByteArray, val durationSeconds: Int, val sampleRate: Int, val peak: Int? = null)

    /**
     * [rate] and [bitRate] default to music quality; the web portal's listen clips ask for 16 kHz at 32 kbps to stay small.
     * [shared] records even while another app holds the microphone: Android gives the input to the newest recorder,
     * and if it silences this one for more than half the clip the recording fails as busy rather than returning a
     * mostly silent clip. (Another listener that restarts mid-clip takes the input back for the rest of it.)
     */
    suspend fun record(seconds: Int, builtIn: Boolean = false, rate: Int = 44100, bitRate: Int = 96_000, shared: Boolean = false): Clip {
        val am = context.getSystemService(AudioManager::class.java)
        if (!shared && am.activeRecordingConfigurations.isNotEmpty())
            throw ApiError(409, "sensor_busy", "Another app is using the microphone")
        val file = File(context.cacheDir, "clip-${System.nanoTime()}.m4a")
        val rec = Compat.mediaRecorder(context)
        try {
            try {
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioSamplingRate(rate)
                rec.setAudioChannels(1)
                rec.setAudioEncodingBitRate(bitRate)
                if (builtIn) {
                    val device = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
                        ?: throw ApiError(409, "sensor_busy", "Built-in microphone unavailable")
                    if (!rec.setPreferredDevice(device)) throw ApiError(409, "sensor_busy", "Built-in microphone routing unavailable")
                }
                rec.setOutputFile(file)
                rec.prepare()
                rec.start()
            } catch (e: Exception) {
                throw ApiError(409, "sensor_busy", "Microphone unavailable: ${e.message}")
            }
            // Poll the loudest moment while recording (each read returns the peak since the last one).
            var peak: Int? = null
            var polls = 0; var silenced = 0
            runCatching { rec.maxAmplitude }
            val end = System.currentTimeMillis() + seconds * 1000L
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                delay(minOf(100L, left))
                runCatching { rec.maxAmplitude }.getOrNull()?.takeIf { it >= 0 }?.let { peak = maxOf(peak ?: 0, it) }
                if (shared) { polls++; if (rec.activeRecordingConfiguration?.isClientSilenced == true) silenced++ }
            }
            if (shared && silenced * 2 > polls) throw ApiError(409, "sensor_busy", "Another app is using the microphone")
            try { rec.stop() } catch (e: Exception) {
                throw ApiError(504, "capture_timeout", "Recording did not finish cleanly")
            }
            return Clip(file.readBytes(), seconds, rate, peak)
        } finally {
            rec.release()
            file.delete()
        }
    }
}

class Gallery(private val context: Context) {

    /** "full", "selected", or null when no photo permission is held. */
    fun accessMode(): String? = when {
        context.granted(Manifest.permission.READ_MEDIA_IMAGES) -> "full"
        context.granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "selected"
        else -> null
    }

    class Item(val id: Long, val takenAt: Long, val width: Int, val height: Int,
               val size: Long, val mime: String, val album: String?,
               val addedAt: Long = 0, val relativePath: String? = null)

    @SuppressLint("Range", "InlinedApi")
    fun recent(limit: Int, type: String = "any", album: String? = null, since: Long? = null, until: Long? = null): List<Item> {
        val args = Bundle().apply {
            // MediaStore ignores QUERY_ARG_SORT_COLUMNS here; the SQL sort string is honoured.
            // Newest-added first: received images (WhatsApp) often have no DATE_TAKEN.
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, NEWEST_FIRST)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_LIMIT, limit.toString())
        }
        val clauses = mutableListOf<String>(); val values = mutableListOf<String>()
        if (type == "screenshot") { clauses += "LOWER(relative_path) LIKE ?"; values += "%screenshots%" }
        if (type == "photo") { clauses += "LOWER(relative_path) LIKE ?"; values += "dcim/camera/%" }
        if (album != null) { clauses += "bucket_display_name = ?"; values += album }
        if (since != null) { clauses += "date_added >= ?"; values += (since / 1000).toString() }
        if (until != null) { clauses += "date_added <= ?"; values += (until / 1000).toString() }
        if (clauses.isNotEmpty()) { args.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, clauses.joinToString(" AND ")); args.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, values.toTypedArray()) }
        return query(args, limit)
    }

    enum class Kind { ANY, SCREENSHOT, PHOTO }

    private companion object {
        const val NEWEST_FIRST = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
    }

    /** Classifies by folder: Pixel saves screenshots under .../Screenshots/, camera photos under DCIM/Camera/. */
    fun kindOf(relativePath: String?): String = when {
        relativePath == null -> "other"
        relativePath.contains("Screenshots", ignoreCase = true) -> "screenshot"
        relativePath.startsWith("DCIM/Camera", ignoreCase = true) -> "photo"
        else -> "other"
    }

    /** The most recently added image of [kind], or null. */
    @SuppressLint("InlinedApi")
    fun latest(kind: Kind): Pair<Item, String?>? {
        val args = Bundle().apply {
            when (kind) {
                Kind.SCREENSHOT -> {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("%Screenshots%"))
                }
                Kind.PHOTO -> {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("DCIM/Camera%"))
                }
                Kind.ANY -> {}
            }
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, NEWEST_FIRST)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_LIMIT, "1")
        }
        return query(args, 1).firstOrNull()?.let { it to it.relativePath }
    }

    fun find(id: Long): Item? {
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media._ID}=?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(id.toString()))
        }
        return query(args).firstOrNull()
    }

    fun read(id: Long): ByteArray? = runCatching {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    /** Android 10 ignores QUERY_ARG_SQL_LIMIT, so reading stops at [limit] here too. */
    @SuppressLint("InlinedApi")
    private fun query(args: Bundle, limit: Int = Int.MAX_VALUE): List<Item> {
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH
        )
        val out = mutableListOf<Item>()
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, args, null)?.use { c ->
            while (out.size < limit && c.moveToNext()) {
                val taken = c.getLong(1).takeIf { it > 0 } ?: (c.getLong(2) * 1000)
                var w = c.getInt(3); var h = c.getInt(4)
                if (c.getInt(7) % 180 != 0) { val t = w; w = h; h = t }
                out += Item(c.getLong(0), taken, w, h, c.getLong(5), c.getString(6) ?: "image/jpeg", c.getString(8),
                    c.getLong(2) * 1000, c.getString(9))
            }
        }
        return out
    }
}
