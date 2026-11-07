package cc.bridgit.app.planned

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.ktor.server.routing.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.*
import org.json.JSONObject
import java.util.UUID
import androidx.core.content.IntentCompat

class ScreenGrants(private val app: BridgeApp) {
    private val requests = linkedMapOf<String, JSONObject>()
    @Synchronized fun create(agentId: String, seconds: Int): JSONObject {
        if (seconds !in listOf(0, 3600, 57600)) throw ErrorCatalog.error("invalid_parameter", "duration_seconds must be 0 (once), 3600 or 57600.")
        requests.entries.removeAll { it.value.optLong("requested_at") < System.currentTimeMillis() - 300_000 }
        requests.values.firstOrNull { it.getString("agent_id") == agentId && it.optString("status") == "pending" }?.let {
            if (it.getInt("duration_seconds") != seconds) throw ErrorCatalog.error("conflict", "A pending screen request has a different duration; wait for it to close first.")
            return JSONObject(it.toString())
        }
        if (requests.size >= 16) throw ErrorCatalog.error("too_many_open", "Too many screen requests.")
        val o = JSONObject().put("id", UUID.randomUUID().toString()).put("agent_id", agentId).put("duration_seconds", seconds)
            .put("requested_at", System.currentTimeMillis()).put("status", "pending")
        requests[o.getString("id")] = o
        return JSONObject(o.toString())
    }
    @Synchronized fun all(): List<JSONObject> = requests.values.map { o ->
        if (o.optString("status") == "pending" && o.getLong("requested_at") < System.currentTimeMillis() - 300_000) o.put("status", "expired")
        JSONObject(o.toString())
    }
    @Synchronized fun decide(id: String, status: String) { requests[id]?.put("status", status) }
    fun activeFor(agentId: String) = ProjectionService.instance?.takeIf { it.agentId == agentId && it.valid() }
}
class ScreenShareModule(app: BridgeApp) : Capability(app) {
    override val id = "screen_share"
    override val titleRes = R.string.cap_screen_share
    override val descriptionRes = R.string.cap_screen_share_desc
    override val group = "photos"
    override val switchKey = "screen_share"
    override val endpoints = listOf(PlannedEndpoints.SCREEN_GRANT, PlannedEndpoints.SCREEN_GRANTS, PlannedEndpoints.SHARE_CAPTURE, PlannedEndpoints.SHARE_STOP)
    override fun blockedReason() = if (!app.captureNotificationsOn()) "notifications_off" else null
    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(PlannedEndpoints.SCREEN_GRANT) {
            requireUsable()
            val o = app.screenGrants.create(agent!!.id, Rules.integer(body.opt("duration_seconds"), "duration_seconds", 0, 57600, 0))
            OwnerToolsActivity.notify(app, "$agentName requests screen access", "Tap to review and approve Android screen sharing", 706)
            Reply.Json(JSONObject(o.toString()).put("pending", true).put("code", "consent_pending").put("poll_url", "/v1/screen/grants").put("retry_after_s", 1), 202)
        }
        endpoint(PlannedEndpoints.SCREEN_GRANTS) {
            requireUsable()
            val active = app.screenGrants.activeFor(agent!!.id)
            Reply.Json(JSONObject().put("requests", org.json.JSONArray(app.screenGrants.all().filter { it.optString("agent_id") == agent!!.id }))
                .put("active", active != null).put("expires_at", active?.expiresAt ?: JSONObject.NULL))
        }
        endpoint(PlannedEndpoints.SHARE_CAPTURE) {
            requireUsable()
            if (app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) throw ErrorCatalog.error("device_locked", "Unlock the phone first.")
            val projection = app.screenGrants.activeFor(agent!!.id) ?: throw ErrorCatalog.error("consent_expired", "No active screen-sharing grant for you. Request one and wait for owner approval.")
            val bytes = projection.capture()
            Reply.Bytes(bytes, "image/jpeg")
        }
        endpoint(PlannedEndpoints.SHARE_STOP) { requireUsable(); app.screenGrants.activeFor(agent!!.id)?.stopSelf(); Reply.Json(JSONObject().put("stopped", true)) }
    }
}
/** One MediaProjection per owner grant and one VirtualDisplay per Android consent token (API 34+). */
class ProjectionService : Service() {
    private val app get() = application as BridgeApp
    var agentId = ""; private set
    var expiresAt = 0L; private set
    private var once = false
    private var grantId = ""
    private var started = false
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    private val settingsChanged: () -> Unit = { if (!valid()) handler.post { stopSelf() } }
    private val expiry = Runnable { stopSelf() }
    private val lock = kotlinx.coroutines.sync.Mutex()
    override fun onBind(i: Intent?): IBinder? = null
    fun valid() = app.settings.listenerEnabled && app.settings.isOn("screen_share") && System.currentTimeMillis() < expiresAt && app.agents.get(agentId)?.allows("screen_share") == true && projection != null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || intent == null) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) { intent.getStringExtra("grant_id")?.let { app.screenGrants.decide(it, "failed") }; return START_NOT_STICKY }
        agentId = intent.getStringExtra("agent_id") ?: ""
        grantId = intent.getStringExtra("grant_id") ?: ""
        val open = PendingIntent.getActivity(this, 702, Intent(this, OwnerToolsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 703, Intent(this, ProjectionService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        ServiceCompat.startForeground(this, 702, NotificationCompat.Builder(this, BridgeApp.CHANNEL_CAPTURE).setSmallIcon(R.drawable.ic_nav_more)
            .setContentTitle("Screen sharing with ${app.agents.get(agentId)?.name}").setContentText("Visible screen access until stopped or grant expires")
            .setContentIntent(open).setOngoing(true).addAction(0, "Stop sharing", stop).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val request = app.screenGrants.all().firstOrNull { it.optString("id") == grantId && it.optString("agent_id") == agentId && it.optString("status") == "pending" }
        if (request == null || !app.settings.isOn("screen_share") || !app.settings.listenerEnabled || app.agents.get(agentId)?.allows("screen_share") != true) { stopSelf(); return START_NOT_STICKY }
        val seconds = request.getInt("duration_seconds")
        once = seconds == 0
        expiresAt = System.currentTimeMillis() + (if (once) 300 else seconds) * 1000L
        try {
            val data = IntentCompat.getParcelableExtra(intent, "data", Intent::class.java) ?: error("No projection consent")
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, data)
            projection!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, handler)
            val w = intent.getIntExtra("width", 1080).coerceIn(1, 4096); val h = intent.getIntExtra("height", 2400).coerceIn(1, 4096)
            reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
            display = projection!!.createVirtualDisplay("Bridgit owner-approved screen", w, h, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
            started = true
            instance = this
            app.screenGrants.decide(grantId, "granted")
            app.log.system("audit", "grant", "Owner approved screen sharing with ${app.agents.get(agentId)?.name} for ${if (once) "one capture" else "$seconds seconds"}")
            app.settings.addListener(settingsChanged); app.agents.addListener(settingsChanged)
            handler.postDelayed(expiry, expiresAt - System.currentTimeMillis())
        } catch (e: Exception) { app.screenGrants.decide(grantId, "failed"); stopSelf() }
        return START_NOT_STICKY
    }
    suspend fun capture(): ByteArray {
        if (!lock.tryLock()) throw ErrorCatalog.error("sensor_busy", "Screen capture already running.")
        try {
            if (!valid()) throw ErrorCatalog.error("consent_expired", "Screen grant expired.")
            val image = withTimeoutOrNull(5000) { var found = runCatching { reader?.acquireLatestImage() }.getOrNull(); while (found == null) { delay(50); if (!valid()) return@withTimeoutOrNull null; found = runCatching { reader?.acquireLatestImage() }.getOrNull() }; found }
                ?: throw ErrorCatalog.error("capture_timeout", "Android has not supplied a screen frame yet.")
            val bytes = image.use {
                val plane = it.planes[0]; val padded = it.width + (plane.rowStride - plane.pixelStride * it.width) / plane.pixelStride
                val bmp = Bitmap.createBitmap(padded, it.height, Bitmap.Config.ARGB_8888)
                try {
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, it.width, it.height)
                    try { java.io.ByteArrayOutputStream().use { out -> cropped.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() } }
                    finally { if (cropped !== bmp) cropped.recycle() }
                } finally { bmp.recycle() }
            }
            if (!valid()) throw ErrorCatalog.error("consent_expired", "Screen grant revoked during capture.")
            if (once) { expiresAt = 0; handler.post { stopSelf() } }
            return bytes
        } finally { lock.unlock() }
    }
    override fun onDestroy() {
        handler.removeCallbacks(expiry)
        app.settings.removeListener(settingsChanged); app.agents.removeListener(settingsChanged)
        if (instance === this) instance = null
        display?.release(); display = null; reader?.close(); reader = null
        projection?.stop(); projection = null
        if (started) { app.log.system("audit", "grant", "Screen sharing ended for ${app.agents.get(agentId)?.name ?: "removed agent"}"); app.screenGrants.decide(grantId, "ended"); app.interactions.event(agentId, "grant.ended", JSONObject().put("id", grantId)) }
        super.onDestroy()
    }
    companion object { @Volatile var instance: ProjectionService? = null; private set }
}
