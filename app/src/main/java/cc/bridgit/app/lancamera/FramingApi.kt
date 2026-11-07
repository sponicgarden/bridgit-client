package cc.bridgit.app.lancamera

import cc.bridgit.app.api.*
import cc.bridgit.app.core.Power
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object FramingApi {
    private const val BASE="/lan-cameras/framing"
    val CONFIG=EndpointSpec("GET","$BASE/config","lan_camera","Reference framing defaults, hard limits and retention",example="GET /v1$BASE/config",returns="limits, framing, calibration, hold, retention; no credentials")
    val CONFIGURE=EndpointSpec("POST","$BASE/config","lan_camera","Set bounded reference-framing defaults (Fixer Mode)",power=Power.FIXER,
        body=listOf(Param("max_moves","integer","0..24; default 6"),Param("max_checks","integer","2..48; default 12"),Param("timeout_s","integer","15..300; default 90"),Param("settle_ms","integer","1000..5000; default 1500"),Param("max_step","number",".005.. .05"),Param("max_travel","number",".01.. .60"),Param("min_confidence","number",".6.. .99; default .8"),Param("min_similarity","number",".6.. .99; default .75")),
        example="POST /v1$BASE/config {\"max_moves\":6}",returns="Updated defaults within immutable hard limits")
    val START=EndpointSpec("POST",BASE,"lan_camera","Find a reference-like target, center with bounded PTZ and hold aim; sends images to Jev",idempotent=true,
        privateFields=setOf("reference","description"),body=listOf(Param("reference","object","{mime:image/jpeg|image/png,data_base64}; <=300000 bytes and <=1568px per edge",true),
            Param("cameras","array","Explicit enrolled camera ids; 1..4",true),Param("app","string","Optional native app id; every camera must be assigned to that app"),Param("description","string","Target description, at most 500 characters; not identity proof"),
            Param("framing","string","Whole target is the default",enum=listOf("whole_target","whole_target_context")),Param("calibrate","boolean","Default true: bounded per-axis probes verify camera direction; false permits observation only until a move is needed")) + CONFIGURE.body,
        example="POST /v1$BASE {\"reference\":{\"mime\":\"image/jpeg\",\"data_base64\":\"...\"},\"cameras\":[\"lan:example\"]}",
        returns="Operation id, active, state, limits; poll status. Requires lan_camera and judgment grants. Default 6 moves, 12 model calls, 90s across all cameras. One active operation per phone. Hold makes no further moves or captures.")
    val LIST=EndpointSpec("GET",BASE,"lan_camera","Your retained reference-framing operations",example="GET /v1$BASE",returns="operations; records and private frames expire 15 minutes after completion")
    val STATUS=EndpointSpec("GET","$BASE/{id}","lan_camera","Your operation's camera, confidence, progress, stop result and framing limits",example="GET /v1$BASE/{id}",returns="state, reason, camera, observation, limits, moves, checks, travel, frame retrieval time (not sensor time), photo_url, stop, framing_limited; no reference bytes")
    val CANCEL=EndpointSpec("POST","$BASE/{id}/cancel","lan_camera","Cancel your framing operation and request camera Stop",example="POST /v1$BASE/{id}/cancel {}",returns="cancel_requested and current state. Poll status for Stop confirmation; a failure is not a confirmed stop.")
    val PHOTO=EndpointSpec("GET","$BASE/{id}/photo","lan_camera","Latest uncropped frame of your operation, subject to current camera/app access",media=true,example="GET /v1$BASE/{id}/photo",returns="JPEG bytes; sensor timestamp is unverified. Unclear/limited results are not confirmed centered.")
    val CONTROLS=EndpointSpec("GET","/lan-cameras/{id}/controls","lan_camera","Probe controls for this camera's active ONVIF video profile",capture=true,
        example="GET /v1/lan-cameras/{id}/controls",returns="Relative/absolute PTZ and zoom advertisement, implemented controls, normalized ranges, checked_at. Zoom advertisement does not establish optical zoom. Unknown stays unknown.")
    val ALL=listOf(CONFIG,CONFIGURE,START,LIST,STATUS,CANCEL,PHOTO,CONTROLS)
    fun install(route: Route,api: ApiServer,ready: () -> Unit) { with(api) { with(route) {
        endpoint(CONFIG) { Reply.Json(app.cameraFraming.config()) }
        endpoint(CONFIGURE) { ready(); try { Reply.Json(app.cameraFraming.configure(body)) } catch(_:IllegalArgumentException) { throw ErrorCatalog.error("invalid_parameter","Framing defaults must be finite numbers within the advertised hard limits.") } }
        endpoint(START) {
            ready()
            try { Reply.Json(app.cameraFraming.start(agent!!,source,service,body)) }
            catch(e: kotlinx.coroutines.CancellationException) { throw ErrorCatalog.error("agent_not_allowed","Current camera, judgment or app access does not allow this operation.") }
            catch(_:IllegalArgumentException) { throw ErrorCatalog.error("invalid_parameter","Use enrolled camera ids, a valid small reference image and settings within GET /v1/lan-cameras/framing/config limits.") }
            catch(_:org.json.JSONException) { throw ErrorCatalog.error("invalid_parameter","Malformed framing configuration or reference image.") }
        }
        endpoint(LIST) { Reply.Json(app.cameraFraming.list(agent!!.id)) }
        endpoint(STATUS) { Reply.Json(app.cameraFraming.status(path("id")!!,agent!!.id)) }
        endpoint(CANCEL) { Reply.Json(app.cameraFraming.cancel(path("id")!!,agent!!.id)) }
        endpoint(PHOTO) {
            ready()
            try { Reply.Bytes(app.cameraFraming.photo(path("id")!!,agent!!.id),"image/jpeg") }
            catch(_:kotlinx.coroutines.CancellationException) { throw ErrorCatalog.error("agent_not_allowed","Camera or app access changed; the retained photo is unavailable.") }
        }
        endpoint(CONTROLS) {
            ready()
            val c=app.lanCameras.all().firstOrNull { it.id==path("id") } ?: throw ErrorCatalog.error("not_found","Choose an enrolled camera id.")
            val result=withContext(Dispatchers.IO) { try { val client=Onvif(c,app); client.controls(client.info().profile).json() }
                catch(_:CameraFailure) { throw ErrorCatalog.error("upstream_failed","Could not verify active-profile controls. No capability is inferred from failure.") } }
            Reply.Json(result.put("camera",c.id))
        }
    } } }
}
