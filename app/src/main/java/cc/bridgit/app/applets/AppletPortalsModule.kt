package cc.bridgit.app.applets

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

/**
 * Applet portals (docs/spec-portals-and-cards.md, API revision 100): a controlling agent opens any installed applet's
 * portal on the screen, reads what it shows, calls the functions its page exposes, and closes it. The person at the
 * device can use the same screen by touch at any time. Cards (/v1/interactions) stay the agent's own way to put
 * something on the screen; portals are the applets'.
 */
object AppletPortalEndpoints {
    const val CAP = AppletPortalsModule.ID
    private const val ID_EX = "ai0123456789abcd"
    val LIST = EndpointSpec("GET", "/applets/portals", CAP,
        "Every installed applet that has a portal (its page), and whether it is open on the screen now",
        example = "GET /v1/applets/portals", returns = "portals[{id, name, package_id, status, kind (html|pieces), open, on_screen}]")
    val OPEN = EndpointSpec("POST", "/applets/{id}/portal/open", CAP,
        "Bring an installed applet's portal to the front of the screen (any applet), as if the owner tapped Open. Waits for its page to load and expose its functions",
        body = listOf(Param("wait_s", "integer", "0-15 seconds to wait for the page (default 5)")),
        example = "POST /v1/applets/$ID_EX/portal/open {\"wait_s\":5}",
        returns = "as GET …/portal, plus note when the portal didn't come to the front. portal.opened, portal.functions, portal.state and portal.closed follow in /v1/events",
        errors = listOf("not_found", "not_supported", "invalid_parameter", "service_start_blocked"))
    val GET = EndpointSpec("GET", "/applets/{id}/portal", CAP,
        "What an applet's portal shows now: the functions its page exposes, the state it published, its visible words, and who used it last (the owner by touch, or an agent)",
        example = "GET /v1/applets/$ID_EX/portal",
        returns = "id, name, kind, open, on_screen, loaded, functions[{name, description, params}], state (the page's own JSON or null), state_at, text (visible words, ≤ 8 KB, null when closed), last_input{by: owner|agent, agent?, function?, at}",
        errors = listOf("not_found", "not_supported"))
    val CALL = EndpointSpec("POST", "/applets/{id}/portal/call", CAP,
        "Call one function an open portal's page exposes (GET …/portal lists them) and wait for its answer. The page decides what it does and what the screen shows",
        body = listOf(Param("function", "string", "The function's name", required = true), Param("args", "object", "Its arguments (≤ 16 KB)"),
            Param("timeout_s", "integer", "1-30 seconds to wait for the answer (default 10)")),
        example = "POST /v1/applets/$ID_EX/portal/call {\"function\":\"show_day\",\"args\":{\"date\":\"2026-11-05\"}}",
        returns = "ok true with result, or ok false with error (timed_out true when the page didn't answer in time)",
        errors = listOf("not_found", "not_supported", "conflict", "invalid_parameter", "rate_limited"))
    val CLOSE = EndpointSpec("POST", "/applets/{id}/portal/close", CAP, "Close an applet's portal screen",
        example = "POST /v1/applets/$ID_EX/portal/close", returns = "id, closed (false when it wasn't open)", errors = listOf("not_found", "not_supported"))
    val ALL = listOf(LIST, OPEN, GET, CALL, CLOSE)
}

class AppletPortalsModule(app: BridgeApp) : Capability(app) {
    override val id = ID
    override val titleRes = R.string.cap_applet_portals
    override val descriptionRes = R.string.cap_applet_portals_desc
    override val group = "apps"
    override val switchKey = ID
    override val endpoints = AppletPortalEndpoints.ALL

    private fun <T> checked(f: () -> T): T = try { f() } catch (e: PortalRules.Bad) { throw ErrorCatalog.error("invalid_parameter", e.message ?: "Invalid.") }

    override fun Route.install(api: ApiServer) = with(api) {
        val portals = app.appletPortals
        endpoint(AppletPortalEndpoints.LIST) { Reply.Json(JSONObject().put("portals", portals.list())) }
        endpoint(AppletPortalEndpoints.OPEN) {
            val a = agent!!
            val id = path("id").orEmpty()
            val out = portals.open(id, a, checked { PortalRules.openWaitMs(body) })
            message = "${a.name} opened the portal of ${out.optString("name")}"
            Reply.Json(out)
        }
        endpoint(AppletPortalEndpoints.GET) { Reply.Json(portals.snapshot(path("id").orEmpty())) }
        endpoint(AppletPortalEndpoints.CALL) {
            val a = agent!!
            val out = portals.call(path("id").orEmpty(), a, body)
            message = "${a.name} called ${body.optString("function")} on an applet portal"
            Reply.Json(out)
        }
        endpoint(AppletPortalEndpoints.CLOSE) { Reply.Json(portals.close(path("id").orEmpty())) }
    }

    companion object { const val ID = "applet_portals" }
}
