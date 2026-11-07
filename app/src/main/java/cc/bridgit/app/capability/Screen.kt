package cc.bridgit.app.capability

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import io.ktor.server.routing.Route
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Reply
import cc.bridgit.app.api.Sizing
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * 8.10 Screenshots (D17, CAP-25/26): an Accessibility service that can only take screenshots.
 * It asks for no events and can't read windows, tap or filter keys (res/xml/accessibility_screenshot.xml).
 * The owner turns it on once in Android's Accessibility settings; after that agents get the screen on request.
 */
class ScreenshotService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** CAP-25 private mode: the frame stays in memory; nothing is saved and there's no shutter flash. Android 11+ (ScreenModule blocks earlier). */
    @RequiresApi(30)
    suspend fun capture(): Bitmap = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = result.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hw.close()
                if (bmp != null) cont.resume(bmp)
                else cont.resumeWithException(ErrorCatalog.error("internal_error", "Android returned a screenshot Bridgit couldn't read."))
            }
            override fun onFailure(errorCode: Int) {
                cont.resumeWithException(when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> ErrorCatalog.error("rate_limited",
                        "Android allows about one screenshot a second. Retry shortly.", mapOf("retry_after_s" to 2))
                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> ErrorCatalog.error("not_supported",
                        "The app on screen blocks screenshots (secure window). Bridgit doesn't work around that.",
                        mapOf("secure_content" to true))
                    else -> ErrorCatalog.error("capture_timeout", "Android couldn't take the screenshot (code $errorCode). Retry once.")
                })
            }
        })
    }

    companion object {
        @Volatile var instance: ScreenshotService? = null
            private set

        /** Whether the owner has turned the service on in Accessibility settings (true even before it connects). */
        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, ScreenshotService::class.java)
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** 8.10 Screenshot on request. Master switch "screen", off by default. */
class ScreenModule(app: BridgeApp) : Capability(app) {
    override val id = "screen"
    override val titleRes = R.string.cap_screen
    override val descriptionRes = R.string.cap_screen_desc
    override val group = "photos"
    override val switchKey = "screen"
    override val endpoints = listOf(Endpoints.SCREEN_CAPTURE)
    override fun blockedReason() = when {
        Build.VERSION.SDK_INT < 30 -> "android_version"
        !ScreenshotService.isEnabled(app) -> "accessibility_off"
        !app.captureNotificationsOn() -> "notifications_off"
        else -> null
    }
    override fun details() = JSONObject().put("accessibility_service", ScreenshotService.isEnabled(app)).put("modes", org.json.JSONArray().put("private"))

    private val lock = Mutex()
    /** CAP-25b: per agent, 1 capture per 2 s and 60 an hour. */
    private val recent = HashMap<String, ArrayDeque<Long>>()

    private fun admit(agentId: String) = synchronized(recent) {
        val now = System.currentTimeMillis()
        val q = recent.getOrPut(agentId) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > 3_600_000) q.removeFirst()
        val last = q.lastOrNull()
        if (last != null && now - last < 2_000) throw ErrorCatalog.error("rate_limited",
            "At most one screenshot every 2 seconds.", mapOf("retry_after_s" to 2))
        if (q.size >= 60) throw ErrorCatalog.error("rate_limited", "At most 60 screenshots an hour.",
            mapOf("retry_after_s" to ((3_600_000 - (now - q.first())) / 1000 + 1)))
        q.addLast(now)
    }

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.SCREEN_CAPTURE) {
            val mode = body.optString("mode", "private")
            if (mode != "private") throw ErrorCatalog.error("invalid_parameter", "mode must be \"private\" (system mode isn't built yet).")
            val spec = Sizing.resolve({ arg(it) }, "agent")
            if (!enabled) throw ErrorCatalog.error("capability_disabled",
                "Screenshots are switched off on the phone. Ask the owner to turn on Screenshots in Feature enablement.", mapOf("setting" to switchKey))
            if (Build.VERSION.SDK_INT < 30) throw ErrorCatalog.error("not_supported", "Screenshots need Android 11 or newer.")
            if (!ScreenshotService.isEnabled(app)) throw ErrorCatalog.error("access_needed",
                "Bridgit's screenshot service is off. Ask the owner to open Bridgit > More > Hardware access > Screenshots and turn it on.",
                mapOf("setting" to "accessibility_screenshot"))
            requireUsable()
            val svc = ScreenshotService.instance ?: throw ErrorCatalog.error("service_start_blocked",
                "The screenshot service is on but Android hasn't started it yet. Retry in a few seconds.")
            if (app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true)
                throw ErrorCatalog.error("device_locked", "The phone is locked, so there's nothing to see. Ask the owner to unlock it.")
            cc.bridgit.app.capture.CapturesModule.checkStore(app, this, "screen")
            admit(agent!!.id)
            if (!lock.tryLock()) throw ErrorCatalog.error("sensor_busy", "A screenshot is already being taken. Retry in a few seconds.")
            val token = api.service.captureStarted(app.getString(R.string.notif_screenshot, agentName))
            val img = try {
                val bmp = withTimeout(10_000) { svc.capture() }
                try { encode(bmp, spec) } finally { bmp.recycle() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw ErrorCatalog.error("capture_timeout", "Android didn't return the screenshot in time. Retry once.")
            } finally {
                api.service.captureEnded(token)
                lock.unlock()
            }
            val now = System.currentTimeMillis()
            val at = isoAt(now)
            val kept = cc.bridgit.app.capture.CapturesModule.keep(app, this, "screen", img.bytes, img.mime, now,
                JSONObject().put("width_px", img.width).put("height_px", img.height))
            data = JSONObject().put("mode", mode).put("width_px", img.width).put("height_px", img.height)
            if (img.bytes.size > ApiServer.MAX_RAW) throw ErrorCatalog.error("payload_too_large", "The image is over 40 MB. Ask for a smaller size.")
            bytesOut = img.bytes.size.toLong()
            val raw = arg("raw").let { it == "1" || it == "true" || it == 1 || it == true }
            if (raw) Reply.Bytes(img.bytes, img.mime, mapOf("X-Bridgit-Captured-At" to at, "X-Bridgit-Mode" to mode,
                "X-Bridgit-Width" to img.width.toString(), "X-Bridgit-Height" to img.height.toString()) + cc.bridgit.app.capability.keptHeaders(kept))
            else {
                val b64 = Base64.encodeToString(img.bytes, Base64.NO_WRAP)
                if (b64.length > ApiServer.MAX_PAYLOAD) throw ErrorCatalog.error("payload_too_large",
                    "The result is over 12 MB as base64. Ask for a smaller size, or add raw=1.")
                Reply.Json(JSONObject().put("captured_at", at).put("width_px", img.width).put("height_px", img.height)
                    .put("format", if (img.mime == "image/png") "png" else "jpeg").put("mime", img.mime)
                    .put("size_bytes", img.bytes.size).put("mode", mode).put("source", "screen").put("data_base64", b64)
                    .apply { if (kept != null) put("capture", kept) })
            }
        }
    }

    /** API-10 sizing: long edge per [spec] (agent = 1568 px, q85); original = full-size PNG. */
    private fun encode(bmp: Bitmap, spec: Sizing.Spec): Encoded {
        val bos = ByteArrayOutputStream()
        if (spec.original) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            return Encoded(bos.toByteArray(), bmp.width, bmp.height, "image/png")
        }
        val (tw, th) = Sizing.target(bmp.width, bmp.height, spec)
        val out = if (tw < bmp.width) Bitmap.createScaledBitmap(bmp, tw, th, true) else bmp
        out.compress(Bitmap.CompressFormat.JPEG, spec.quality, bos)
        val res = Encoded(bos.toByteArray(), out.width, out.height, "image/jpeg")
        if (out !== bmp) out.recycle()
        return res
    }
}

