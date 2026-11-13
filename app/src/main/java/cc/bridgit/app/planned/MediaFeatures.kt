package cc.bridgit.app.planned

import android.Manifest
import android.app.KeyguardManager
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.ktor.server.routing.Route
import kotlinx.coroutines.*
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import cc.bridgit.app.core.Compat
import cc.bridgit.app.core.media.MediaLibrary
import cc.bridgit.app.core.media.MediaScopes

object AppletVideoSessions {
    private val active = mutableMapOf<String, Recording>()
    private val epochs = mutableMapOf<String, Long>()
    @Synchronized fun begin(agent: String): Long { val n=(epochs[agent] ?: 0L)+1;epochs[agent]=n;return n }
    @Synchronized fun register(agent: String, epoch: Long, recording: Recording) {
        if(epochs[agent]!=epoch) recording.stop() else active[agent]=recording
    }
    @Synchronized fun stop(agent: String) { epochs[agent]=(epochs[agent] ?: 0L)+1;active[agent]?.stop() }
    @Synchronized fun finish(agent: String) { active.remove(agent)?.close() }
    @Synchronized fun current(agent: String, epoch: Long): Boolean = epochs[agent]==epoch
}

class VideoModule(app: BridgeApp) : Capability(app) {
    override val id = "video"
    override val titleRes = R.string.cap_video
    override val descriptionRes = R.string.cap_video_desc
    override val group = "capture"
    override val switchKey = "video"
    override val permissions = listOf(Manifest.permission.CAMERA)
    override val endpoints = listOf(PlannedEndpoints.VIDEO, PlannedEndpoints.VIDEO_STOP, PlannedEndpoints.VIDEO_REVIEW)
    override fun blockedReason() = missingPermission()
        ?: if (!app.captureNotificationsOn()) "notifications_off"
        else if (!app.listener.value.cameraReady) "open_app_once" else null
    override fun Route.install(api: ApiServer) = with(api) {
      endpoint(PlannedEndpoints.VIDEO_STOP) { cc.bridgit.app.capture.VisibleCapture.cancel(agent!!.id); AppletVideoSessions.stop(agent!!.id); Reply.Json(JSONObject().put("stopped",true)) }
      endpoint(PlannedEndpoints.VIDEO_REVIEW) {
        val found=app.captures.get(body.optString("capture_id"),agent!!.id,"camera") ?: throw ApiError(404,"not_found","That video is no longer available")
        if(found.entry.mime!="video/mp4") throw ApiError(422,"invalid_parameter","capture_id must name a recorded video")
        val file=File(app.cacheDir,"review-${System.nanoTime()}.mp4")
        try {
            withContext(Dispatchers.IO) { file.writeBytes(found.data) }
            val result=cc.bridgit.app.capture.VisibleCapture.take(app,agent!!.id,"rear",video=file,reviewOnly=true)
            if(result.deleted) app.captures.delete(found.entry.id,agent!!.id)
            Reply.Json(JSONObject().put("deleted",result.deleted))
        } finally { file.delete() }
      }
      endpoint(PlannedEndpoints.VIDEO) {
        requireUsable()
        if (app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) throw ErrorCatalog.error("device_locked", "Unlock the phone to record video.")
        val seconds = Rules.integer(body.opt("duration_seconds"), "duration_seconds", 1, 30, 10)
        var camera = body.optString("camera", "rear")
        if (camera !in listOf("rear", "front")) throw ErrorCatalog.error("invalid_parameter", "camera must be rear or front.")
        val resolution = body.optString("resolution", "720p")
        if (resolution !in listOf("720p", "1080p")) throw ErrorCatalog.error("invalid_parameter", "resolution must be 720p or 1080p.")
        val frameCount=Rules.integer(body.opt("extract_frames"),"extract_frames",0,8,0)
        val capturePlan=if(frameCount>0) app.captures.plan(agent!!.id,"camera",setOf("memory")) else null
        if(capturePlan != null && !capturePlan.memory) throw ErrorCatalog.error("invalid_parameter","Frame extraction requires enabled in-memory capture storage")
        val audio = Rules.bool(body, "audio", false)
        if (audio && (!app.settings.isOn("mic") || !app.listener.value.micReady || !app.granted(Manifest.permission.RECORD_AUDIO) || !app.agents.get(agent!!.id)!!.allows("mic"))) throw ErrorCatalog.error("permission_denied", "Enable microphone access for this agent and open the app first, or use audio=false.")
        val visible=cc.bridgit.app.capture.CapturePresentationRules.requiresViewfinder(invocation,body.optBoolean("viewfinder"))
        val countdown=Rules.integer(body.opt("countdown_seconds"),"countdown_seconds",0,10,0)
        if (!visible&&!app.registry.cameraLock.tryLock()) throw ErrorCatalog.error("sensor_busy", "Camera in use.")
        val videoEpoch=AppletVideoSessions.begin(agent!!.id)
        var micLocked = false
        val file = File(app.cacheDir, "video-${System.nanoTime()}.mp4")
        var note: Long? = null
        try {
            if (audio&&!visible) { if (!app.registry.micLock.tryLock()) throw ErrorCatalog.error("sensor_busy", "Microphone in use."); micLocked = true; app.wake.awaitMicReleased() }
            note = api.service.captureStarted("$agentName is recording $seconds seconds of video")
            if(visible) { val chosen=cc.bridgit.app.capture.VisibleCapture.take(app,agent!!.id,camera,video=file,seconds=seconds,resolution=resolution,audio=audio,countdown=countdown,instructions=body.optString("instructions").trim().take(240));camera=chosen.camera ?: camera }
            else withContext(Dispatchers.Main) {
                val future = ProcessCameraProvider.getInstance(app)
                val provider = suspendCancellableCoroutine<ProcessCameraProvider> { c -> future.addListener({ if (c.isActive) runCatching { future.get() }.fold({ c.resume(it) }, { c.resumeWithException(it) }) }, app.mainExecutor) }
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(if (resolution == "720p") Quality.HD else Quality.FHD)).build()
                val video = VideoCapture.withOutput(recorder)
                try {
                    val selector = if (camera == "rear") CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    if (!provider.hasCamera(selector)) throw ErrorCatalog.error("not_supported", "This device has no $camera camera.")
                    try { provider.bindToLifecycle(api.service, selector, video) }
                    catch (e: IllegalArgumentException) { throw ErrorCatalog.error("not_supported", "This device does not support the requested video configuration.") }
                    withTimeout(45_000) {
                        suspendCancellableCoroutine<Unit> { c ->
                            var pending = recorder.prepareRecording(app, FileOutputOptions.Builder(file).setFileSizeLimit(if (viaMcp) 6L * 1024 * 1024 else 40L * 1024 * 1024).setDurationLimitMillis(seconds * 1000L).build())
                            if (audio) pending = pending.withAudioEnabled()
                            val recording = pending.start(app.mainExecutor) { event -> if (event is VideoRecordEvent.Finalize && c.isActive) {
                                if (event.hasError() && event.error !in listOf(VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED, VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED)) c.resumeWithException(ErrorCatalog.error("capture_timeout", "Video recording failed (${event.error}).")) else c.resume(Unit)
                            } }
                            AppletVideoSessions.register(agent!!.id,videoEpoch,recording)
                            c.invokeOnCancellation { recording.stop() }
                        }
                    }
                } finally { provider.unbind(video) }
            }
            if(!AppletVideoSessions.current(agent!!.id,videoEpoch)) throw ApiError(409,"capture_stopped","Video capture was stopped")
            if(frameCount>0) {
                val frames=withContext(Dispatchers.IO) {
                    val retriever=MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: throw ErrorCatalog.error("capture_timeout","No recorded video duration")
                        JSONArray((0 until frameCount).map { i ->
                            val at=(duration*1000L*(i+.5)/frameCount).toLong()
                            val bmp=retriever.getScaledFrameAtTime(at,MediaMetadataRetriever.OPTION_CLOSEST,960,960) ?: throw ErrorCatalog.error("capture_timeout","Video frame unavailable")
                            val jpeg=java.io.ByteArrayOutputStream().use { out -> try { bmp.compress(Bitmap.CompressFormat.JPEG,75,out);out.toByteArray() } finally { bmp.recycle() } }
                            app.captures.keep(agent!!.id,"camera",jpeg,"image/jpeg",System.currentTimeMillis(),capturePlan!!,JSONObject().put("video_frame_at_s",at/1e6)) ?: throw ErrorCatalog.error("capture_timeout","Frame was not kept")
                        })
                    } finally { retriever.release() }
                }
                val keptVideo=if(visible) withContext(Dispatchers.IO) { app.captures.keep(agent!!.id,"camera",file.readBytes(),"video/mp4",System.currentTimeMillis(),capturePlan!!,JSONObject().put("camera",camera)) } else null
                return@endpoint Reply.Json(JSONObject().put("frames",frames).put("camera",camera).put("count",frames.length()).put("video_saved",keptVideo!=null).apply { keptVideo?.let { put("video_capture_id",it.getString("capture_id")) } })
            }
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            bytesOut = bytes.size.toLong()
            if (bytes.size > ApiServer.MAX_RAW) throw ErrorCatalog.error("payload_too_large", "Video exceeds 40 MB.")
            Reply.Bytes(bytes, "video/mp4", mapOf("X-Bridgit-Duration-Limit-Seconds" to seconds.toString()))
        } finally { AppletVideoSessions.finish(agent!!.id); file.delete(); note?.let { api.service.captureEnded(it) }; if (micLocked) app.registry.micLock.unlock(); if(!visible) app.registry.cameraLock.unlock() }
    } }
}

class GalleryVideoModule(app: BridgeApp) : Capability(app) {
    override val id = "gallery_video"
    override val titleRes = R.string.cap_gallery_video
    override val descriptionRes = R.string.cap_gallery_video_desc
    override val group = "photos"
    override val switchKey = "gallery_video"
    override val permissions = listOf(Manifest.permission.READ_MEDIA_VIDEO)
    override val endpoints = listOf(PlannedEndpoints.VIDEOS, PlannedEndpoints.GALLERY_VIDEO)
    override fun blockedReason() = if (app.granted(Manifest.permission.READ_MEDIA_VIDEO) || app.granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) null else "permission:READ_MEDIA_VIDEO"
    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(PlannedEndpoints.VIDEOS) {
            requireUsable()
            val limit = Rules.integer(query("limit"), "limit", 1, 100, 20)
            val includePending = Rules.flag(query("include_pending"), "include_pending")
            val showSource = MediaScopes.showsSource(app.settings.isOn(MediaScopes.PROVENANCE_GRANT)) { agent!!.allows(it) }
            val out = JSONArray()
            // Rows still arriving (is_pending=1) are skipped, never an error (A0256), unless include_pending=true lists them
            // with complete:false (A0270). Some Android builds reject the SQL sort/limit args or the is_pending,
            // owner_package_name or relative_path columns (IllegalArgumentException), so fall back step by step, finally
            // to the bare query sorted here; the listing never answers 500 because of how MediaStore was asked.
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            // MediaStore's real names are _display_name and _size; display_name and size are rejected (checked on a device), which emptied this list.
            val base = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.DATE_ADDED)
            fun args(sorted: Boolean): Pair<android.net.Uri, android.os.Bundle?> {
                val b = android.os.Bundle()
                if (sorted) { b.putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "date_added DESC, _id DESC"); b.putString(android.content.ContentResolver.QUERY_ARG_SQL_LIMIT, (limit + 5).toString()) }
                val uri = if (includePending) MediaLibrary.includePending(collection, b) else collection
                return uri to b.takeIf { !it.isEmpty }
            }
            val provenance = arrayOf(MediaLibrary.IS_PENDING, MediaLibrary.OWNER_PACKAGE, MediaLibrary.RELATIVE_PATH)
            // date_modified feeds the sync stability window (6.3); a device rejecting it gets the older rungs, window not applied.
            val stable = provenance + MediaLibrary.DATE_MODIFIED
            // A device that rejects only the sort/limit args still gets the pending and provenance columns unsorted.
            val attempts: List<Pair<Array<String>, Boolean>> = listOf(base + stable to true, base + provenance to true, base + MediaLibrary.IS_PENDING to true, base to true,
                base + stable to false, base + provenance to false, base + MediaLibrary.IS_PENDING to false, base to false)
            val now = MediaLibrary.nowSeconds()
            val items = ArrayList<JSONObject>()
            for ((cols, sorted) in attempts) {
                val (uri, queryArgs) = args(sorted)
                val cursor = (try { app.contentResolver.query(uri, cols, queryArgs, null) } catch (e: RuntimeException) { null }) ?: continue
                items.clear()
                try {
                    cursor.use { c ->
                        while (c.moveToNext()) {
                            val item = runCatching {
                                Rules.galleryVideoItem(MediaLibrary.pending(c), c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4), c.getLong(5), ::isoAt,
                                    MediaLibrary.string(c, MediaLibrary.OWNER_PACKAGE), MediaLibrary.string(c, MediaLibrary.RELATIVE_PATH), includePending, showSource,
                                    MediaLibrary.long(c, MediaLibrary.DATE_MODIFIED), now)
                            }.getOrNull()
                            if (item != null) items.add(item)
                            if (sorted && items.size >= limit) break
                        }
                    }
                } catch (e: RuntimeException) { continue }
                break
            }
            Rules.newestVideosFirst(items).take(limit).forEach { out.put(it) }
            Reply.Json(JSONObject().put("items", out).put("access_mode", if (app.granted(Manifest.permission.READ_MEDIA_VIDEO)) "full" else "selected"))
        }
        endpoint(PlannedEndpoints.GALLERY_VIDEO) {
            requireUsable()
            val id = path("id")?.toLongOrNull()?.takeIf { it > 0 } ?: throw ErrorCatalog.error("invalid_parameter", "Use a video ID from the video list.")
            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            try {
                val frame = query("frame_at_s")
                if (frame != null) {
                    val at = frame.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it < 86400 } ?: throw ErrorCatalog.error("invalid_parameter", "frame_at_s must be 0–86399 seconds.")
                    MediaScopes.requireComplete(MediaLibrary.complete(app, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id, checkFile = true))
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(app, uri)
                        val bmp = retriever.getScaledFrameAtTime((at * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1568, 1568)
                            ?: throw ErrorCatalog.error("not_found", "No frame at this time.")
                        val bytes = java.io.ByteArrayOutputStream().use { out -> try { bmp.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() } finally { bmp.recycle() } }
                        Reply.Bytes(bytes, "image/jpeg")
                    } finally { retriever.release() }
                } else {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { Compat.readAtMost(it, ApiServer.MAX_RAW + 1) } ?: throw ErrorCatalog.error("not_found", "Video unavailable.")
                    if (bytes.size > ApiServer.MAX_RAW) throw ErrorCatalog.error("payload_too_large", "Video exceeds 40 MB. Request a frame instead.")
                    Reply.Bytes(bytes, app.contentResolver.getType(uri) ?: "video/mp4")
                }
            } catch (e: SecurityException) { throw ErrorCatalog.error("permission_denied", "This video is outside your selected media grant.") }
        }
    }
}

class OcrModule(app: BridgeApp) : Capability(app) {
    override val id = "ocr"
    override val titleRes = R.string.cap_ocr
    override val descriptionRes = R.string.cap_ocr_desc
    override val group = "photos"
    override val switchKey = "ocr"
    override val endpoints = listOf(PlannedEndpoints.OCR)
    override fun Route.install(api: ApiServer) = with(api) { endpoint(PlannedEndpoints.OCR) {
        requireUsable()
        val bytes = if (body.has("gallery_id")) {
            if (!app.settings.isOn("gallery") || !app.agents.get(agent!!.id)!!.allows("gallery")) throw ErrorCatalog.error("capability_disabled", "Enable this agent's gallery access first.")
            val id = body.optString("gallery_id").toLongOrNull()?.takeIf { it > 0 } ?: throw ErrorCatalog.error("invalid_parameter", "gallery_id must be a positive image ID.")
            MediaScopes.requireComplete(MediaLibrary.complete(app, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val raw = try { app.contentResolver.openInputStream(uri)?.use { Compat.readAtMost(it, 16 * 1024 * 1024 + 1) } }
                catch (e: SecurityException) { throw ErrorCatalog.error("permission_denied", "This photo is outside your media grant.") }
                ?: throw ErrorCatalog.error("photo_not_found", "Photo unavailable.")
            if (raw.size > 16 * 1024 * 1024) throw ErrorCatalog.error("payload_too_large", "OCR source images must be under 16 MB.")
            raw
        } else try { Base64.decode(body.getString("data_base64"), Base64.DEFAULT) } catch (e: Exception) { throw ErrorCatalog.error("invalid_parameter", "Provide gallery_id or image data_base64.") }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 100_000_000) throw ErrorCatalog.error("invalid_parameter", "Use a supported image below 100 megapixels.")
        val opts = BitmapFactory.Options().apply { inSampleSize = Rules.imageSampleSize(bounds.outWidth, bounds.outHeight) }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw ErrorCatalog.error("invalid_parameter", "Image could not be decoded.")
        val text = withTimeout(30_000) { suspendCancellableCoroutine<String> { c ->
            val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            c.invokeOnCancellation { client.close() } // ML Kit tasks cannot be cancelled; keep bitmap until task completion.
            client.process(InputImage.fromBitmap(bmp, 0)).addOnCompleteListener { task ->
                bmp.recycle(); client.close()
                if (c.isActive) { if (task.isSuccessful) c.resume(task.result.text) else c.resumeWithException(ErrorCatalog.error("not_supported", "On-device text recognition failed.")) }
            }
        } }
        Reply.Json(JSONObject().put("text", text).put("script", "Latin").put("on_device", true))
    } }
}
