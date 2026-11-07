package cc.bridgit.app.lancamera

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.Capability
import io.ktor.server.routing.Route
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Existing auth, owner switch, per-agent grant and capture rate limits apply to LAN cameras too. */
class LanCameraModule(app: BridgeApp) : Capability(app) {
    override val id = "lan_camera"
    override val titleRes = R.string.lan_title
    override val descriptionRes = R.string.lan_cap_desc
    override val group = "capture"
    override val switchKey = "lan_camera"
    override val endpoints = ALL
    override fun blockedReason(): String? = if (!app.captureNotificationsOn()) "notifications_off" else null
    override fun details() = JSONObject()
        .put("reference_framing", app.cameraFraming.config())
        .put("setup", "Cameras tab. Owner enrolment only; credentials are never returned.")
        .put("live", "On the phone: Local network cameras → Live video and pan / tilt. Video only.")
    override fun Route.install(api: ApiServer) {
        CameraViewApi.install(this,api) { requireUsable() }
        FramingApi.install(this,api) { requireUsable() }
        with(api) {
        endpoint(LIST) { requireUsable(); Reply.Json(details().put("cameras", JSONArray(app.lanCameras.all().map { it.publicJson() }))) }
        endpoint(PROBE) {
            requireUsable();val c=camera(body);val req=this;val ctx=kotlinx.coroutines.currentCoroutineContext()
            val authorized={
                ctx.ensureActive();requireUsable()
                val live=app.agents.get(req.agent!!.id)
                if(live==null || live.paused || live.keyHash!=req.agent!!.keyHash || !live.allows("lan_camera"))throw ErrorCatalog.error("agent_not_allowed","Camera grant revoked.")
                val current=app.lanCameras.all().firstOrNull{it.id==c.id}
                if(current==null || current.host!=c.host || current.username!=c.username || current.password!=c.password || current.talkPassword!=c.talkPassword)throw ErrorCatalog.error("conflict","Camera enrollment changed.")
            }
            val updated=withContext(Dispatchers.IO) {
                try { val info=Onvif(c,app,authorized=authorized).info(); c.verified(info).assessed(CameraProbe.inspect(app,c,info,authorized)) }
                catch(e:kotlinx.coroutines.CancellationException){throw e}
                catch(e:ApiError){throw e}
                catch(_:Exception){throw ErrorCatalog.error("upstream_failed","Could not refresh camera capabilities; check the local connection and authorized account.")}
            }
            authorized()
            if(!app.lanCameras.replace(c,updated))throw ErrorCatalog.error("conflict","Camera changed during discovery; nothing was overwritten.")
            Reply.Json(updated.publicJson())
        }
        endpoint(SNAPSHOT) {
            requireUsable()
            val camera = camera(body)
            cc.bridgit.app.capture.CapturesModule.checkStore(app, this, "lan_camera")
            val captureToken = service.captureStarted("$agentName is taking a local camera photo")
            val bytes = try { RtspFrame.snapshot(app, camera) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { throw ErrorCatalog.error("upstream_failed", "Could not decode a fresh camera frame. Check its local connection and stream slots.") }
                finally { service.captureEnded(captureToken) }
            bytesOut = bytes.size.toLong()
            val kept = cc.bridgit.app.capture.CapturesModule.keep(app, this, "lan_camera", bytes, "image/jpeg", System.currentTimeMillis(),
                JSONObject().put("camera", camera.id).put("width_px", 640).put("height_px", 360))
            if (arg("raw").let { it == 1 || it == "1" || it == true || it == "true" }) Reply.Bytes(bytes, "image/jpeg", cc.bridgit.app.capability.keptHeaders(kept))
            else Reply.Json(JSONObject().put("camera", camera.id).put("mime", "image/jpeg").put("width_px", 640).put("height_px", 360)
                .put("captured_at", java.time.Instant.now().toString()).put("data_base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).apply { if (kept != null) put("capture", kept) })
        }
        endpoint(MOVE) {
            requireUsable()
            val camera = camera(body)
            val pan = number(body, "pan"); val tilt = number(body, "tilt")
            val stop = body.opt("stop") == true
            if (stop) app.cameraFraming.cancelCamera(camera.id)
            withContext(Dispatchers.IO) {
                try {
                    val client = Onvif(camera, app); val info = client.info()
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!info.ptz) throw ErrorCatalog.error("invalid_parameter", "This camera does not advertise relative pan / tilt.")
                    if (stop) client.stop(info.profile) else client.move(info.profile, pan, tilt)
                } catch (e: CameraFailure) { throw ErrorCatalog.error("upstream_failed", e.message ?: "Camera control failed.") }
            }
            Reply.Json(JSONObject().put("camera", camera.id).put("status", if (stop) "stopped" else "moved"))
        }
    }
    }
    private fun camera(body: JSONObject): LanCamera = app.lanCameras.all().firstOrNull { it.id == body.optString("camera") }
        ?: throw ErrorCatalog.error("invalid_parameter", "Choose a configured camera id from GET /v1/lan-cameras.")
    private fun number(body: JSONObject, key: String): Double {
        if (!body.has(key)) return 0.0
        return (body.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it in -.1.. .1 }
            ?: throw ErrorCatalog.error("invalid_parameter", "$key must be a number from -0.1 to 0.1.")
    }
    companion object {
        val LIST = EndpointSpec("GET", "/lan-cameras", "lan_camera", "Owner-configured local cameras (no credentials)",
            example = "GET /v1/lan-cameras", returns = "cameras: id, name, host; setup and live viewing instructions")
        val SNAPSHOT = EndpointSpec("POST", "/lan-cameras/snapshot", "lan_camera", "Decode a fresh frame directly from a local camera",
            capture = true, media = true, body = listOf(Param("camera", "string", "Configured camera id", true), Param("raw", "integer", "1 returns JPEG bytes"), Param("store", "array", "memory, files, upload; subject to owner policy")),
            example = "POST /v1/lan-cameras/snapshot {\"camera\":\"lan:example\",\"raw\":1}", returns = "640×360 JPEG; raw bytes or data_base64, mime, width_px, height_px, captured_at",
            errors = listOf("invalid_parameter", "upstream_failed"))
        val MOVE = EndpointSpec("POST", "/lan-cameras/move", "lan_camera", "Small relative pan / tilt, only if advertised by the camera",
            capture = true, body = listOf(Param("camera", "string", "Configured camera id", true), Param("pan", "number", "-0.1 to 0.1, camera-native sign; verify with fresh frames"),
                Param("tilt", "number", "-0.1 to 0.1, camera-native sign; verify with fresh frames"), Param("stop", "boolean", "true sends ONVIF Stop")),
            example = "POST /v1/lan-cameras/move {\"camera\":\"lan:example\",\"pan\":0.03}", returns = "camera, status",
            errors = listOf("invalid_parameter", "upstream_failed"))
        val PROBE=EndpointSpec("POST","/lan-cameras/probe","lan_camera","Refresh enrolled camera capabilities without recording audio, playing a sound or moving motors",capture=true,
            body=listOf(Param("camera","string","Enrolled camera id",true)),example="POST /v1/lan-cameras/probe {\"camera\":\"lan:example\"}",
            returns="Safe per-device capabilities: advertised vs implemented vs tested, timestamp, audio codecs/directions, ONVIF feature inventory and Tapo talk credential state. Legacy cameras remain not_checked until probed.")
        val ALL = listOf(LIST, SNAPSHOT, MOVE, PROBE) + FramingApi.ALL + CameraViewApi.all
    }
}
