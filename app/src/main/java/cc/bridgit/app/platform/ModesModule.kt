package cc.bridgit.app.platform

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.capability.Capability
import io.ktor.server.routing.Route
import org.json.JSONObject

/** The phone's mode for agents and single steps (docs/applet-reach.md D2): read it, and set it (`do.step` set_mode). */
object ModesApi {
    val GET = EndpointSpec("GET", "/mode", "modes",
        "The phone's mode (home, away, night, vacation or one the owner added), since when, who set it, every mode it can be, and the newest changes",
        example = "GET /v1/mode",
        returns = "mode, since, set_by {type, name}, modes [..], log [{at, from, to, by}] (newest 10)")
    val SET = EndpointSpec("POST", "/mode", "modes",
        "Set the phone's mode. Setting the mode it already is changes nothing; flows with a mode trigger start on a change",
        body = listOf(Param("mode", "string", "One of the phone's modes (GET /v1/mode lists them)", required = true)),
        example = "POST /v1/mode {\"mode\":\"away\"}",
        returns = "mode, from, changed (false when it already was that mode)",
        errors = listOf("invalid_parameter", "rate_limited"))
    val ALL = listOf(GET, SET)
}

class ModesModule(app: BridgeApp) : Capability(app) {
    override val id = "modes"
    override val titleRes = R.string.cap_modes
    override val descriptionRes = R.string.cap_modes_desc
    override val group = "apps"
    override val endpoints = ModesApi.ALL

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(ModesApi.GET) { Reply.Json(app.platform.modes.json()) }
        endpoint(ModesApi.SET) {
            val a = agent!!
            val mode = body.opt("mode") as? String ?: throw ErrorCatalog.error("invalid_parameter", "mode must be one of the phone's modes (GET /v1/mode).")
            val from = app.platform.modes.current()
            val changed = try {
                app.platform.modes.set(mode, Modes.By(if (app.applets.forAgent(a.id) != null) "applet" else "agent", a.name, a.id))
            } catch (e: cc.bridgit.app.api.ApiError) {
                throw when (e.code) {
                    "mode_busy" -> ErrorCatalog.error("rate_limited", e.message ?: "The mode changed too often.", mapOf("retry_after_s" to 600))
                    else -> ErrorCatalog.error("invalid_parameter", e.message ?: "Not one of the phone's modes.")
                }
            }
            this.data = JSONObject().put("mode", mode).put("changed", changed)
            Reply.Json(JSONObject().put("mode", mode).put("from", from).put("changed", changed))
        }
    }
}
