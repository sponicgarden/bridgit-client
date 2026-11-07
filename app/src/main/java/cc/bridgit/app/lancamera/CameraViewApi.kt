package cc.bridgit.app.lancamera

import cc.bridgit.app.api.*
import cc.bridgit.app.applets.AppletEditAccess
import cc.bridgit.app.applets.AppletPackage
import cc.bridgit.app.core.Power
import io.ktor.server.routing.Route
import org.json.JSONObject

/** A caller can bind only a camera and position name the owner approved in this instance. */
internal object CameraViewApi {
    private const val PATH = "/lan-cameras/applet-views/{instance}/{view}"
    private val read = EndpointSpec("GET", PATH, "lan_camera", "Inspect an installed applet's selected camera view",
        example="GET /v1$PATH", returns="view, camera, saved, position and revision when saved")
    private val save = EndpointSpec("POST", PATH, "lan_camera", "Save the current physical position of the reviewed camera",
        power=Power.FIXER, body=listOf(Param("revision","string","Current revision when replacing")),
        example="POST /v1$PATH {}", returns="Saved view and new revision")
    private val clear = EndpointSpec("DELETE", PATH, "lan_camera", "Forget the saved position",
        power=Power.FIXER, query=listOf(Param("revision","string","Current saved position revision",true)), example="DELETE /v1$PATH?revision=...", returns="Cleared view")
    val all = listOf(read,save,clear)

    fun install(route: Route, api: ApiServer, usable: () -> Unit) { with(api) { with(route) {
        for (spec in all) endpoint(spec) {
            val instance = path("instance")!!
            val name = path("view")!!
            if (!CameraViews.NAME.matches(name)) throw ErrorCatalog.error("invalid_parameter","Invalid position name.")
            val original = agent!!
            fun binding(): Pair<String,String> {
                usable()
                val live = app.agents.get(original.id)
                val record = app.applets.get(instance)
                if (live == null || live.paused || live.keyHash != original.keyHash || !live.allows("lan_camera") ||
                    !live.allows("applet_install") || (spec != read && !live.fixer) || record == null ||
                    !AppletEditAccess(app).canEdit(record,original.id))
                    throw ErrorCatalog.error("agent_not_allowed","This operation needs the applet's authorized editor and current camera access.")
                val principal = record.optString("agent_id")
                if (app.agents.get(principal)?.allows("lan_camera") != true)
                    throw ErrorCatalog.error("agent_not_allowed","Review camera access for this applet first.")
                val camera = AppletPackage.cameraViewBindings(record)[name]
                    ?: throw ErrorCatalog.error("invalid_parameter","Choose a view bound to one enrolled camera in this edition.")
                return principal to camera
            }
            val selected = binding()
            val (principal,camera) = selected
            val expected = arg("revision") as? String
            if (spec == save) app.cameraViews.savePosition(app,principal,name,camera,expected) {
                if (binding() != selected) throw ErrorCatalog.error("conflict","The applet or its camera selection changed.")
            }
            if (spec == clear) {
                if (expected == null) throw ErrorCatalog.error("invalid_parameter","Supply the revision returned by GET.")
                app.cameraViews.replace(principal,name,expected,null)
            }
            Reply.Json(app.cameraViews.status(principal,name).put("selected_camera",camera))
        }
    } } }
}
