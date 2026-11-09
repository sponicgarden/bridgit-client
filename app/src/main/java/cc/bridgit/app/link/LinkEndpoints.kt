package cc.bridgit.app.link

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.satellite.LinkPause
import io.ktor.server.routing.Route
import org.json.JSONArray
import org.json.JSONObject

/**
 * Linked Bridgits for agents (GET /v1/links, LNK-46) and device to device (LNK-47: /v1/link/…, link agents only).
 * Owner configuration is in ownerapi/ConfigurationLinks.kt. Part of the status module: no switch of its own.
 */
object LinkEndpoints {
    const val HOW = "Any call takes header X-Bridgit-Target: <name> (the linked Bridgit's Device Domain Name, or its sat: id) or query target=<name> to run on that Bridgit instead of this one; leave it out to use this one. Calls are passed on once: a linked Bridgit never passes them further."

    val LINKS = EndpointSpec("GET", "/links", "status",
        "This Bridgit's links to the owner's other Bridgits, from either end (hub or satellite), with which you may use through this one and what each lets you do. $HOW",
        example = "GET /v1/links",
        returns = "links[{id (sat:… or name), target, link_id, name, bridgit_name, kind, role (hub: the other is this one's satellite | satellite: the other is this one's hub), reachable (you may target it), platform, device_type, status (online|offline|unlinked|not_checked), state (on|waiting_for_target|waiting_for_user|paused|off), services[], directions{in{state, services, about}, out{state, services, about}}, last_seen, battery, cameras, capabilities, endpoints, how_to_call}], how_to_call. battery, cameras, capabilities and endpoints only when reachable; an unreachable entry's how_to_call says how the owner lets you use it. GET /v1/satellites is the same list.")

    val CLAIM = EndpointSpec("POST", "/link/claim", "status",
        "Device to device: a Bridgit claims this one's link code with the claim token derived from it (Authorization: Bearer rly_…). Not for agents: agents use POST /v1/owner/configuration/links/claims on their own Bridgit.",
        auth = false, privateFields = setOf("*"),
        body = listOf(Param("name", "string", "The claiming Bridgit's device name"), Param("bridgit_name", "string", "Its Device Domain Name"),
            Param("install", "string", "Its install id (bgi_…)"), Param("granter", "object", "Who claimed: {kind: person|agent, agent_id, agent_name}")),
        example = "POST /v1/link/claim {\"name\":\"Kitchen tablet\",\"bridgit_name\":\"kitchen-tab\"}",
        returns = "link_id, key (the claiming Bridgit's link agent key here, locked to its address), agent, name, bridgit_name, install, services, directions, events",
        errors = listOf("code_invalid", "code_expired", "code_used_up", "conflict", "forbidden", "rate_limited"))

    val EVENTS = EndpointSpec("POST", "/link/events", "status",
        "Device to device: the other Bridgit of a link sends its grants-log events and its halves of both directions (link agents only). Not for agents.",
        body = listOf(Param("link_id", "string", "The link", required = true), Param("events", "array", "Its log entries since the last contact"),
            Param("state", "object", "{in:{opted_in, paused, services}, out:{opted_in, paused}} from its side"), Param("bridgit_name", "string", "Its Device Domain Name")),
        example = "POST /v1/link/events {\"link_id\":\"lnk_…\",\"events\":[],\"state\":{}}", returns = "ok, link_id, state (this device's halves)")

    val PEERS = EndpointSpec("GET", "/link/peers", "status",
        "Device to device: this Bridgit's other links, names and directions only, so the other device can draw them (link agents only). Not for agents.",
        example = "GET /v1/link/peers", returns = "peers[{name, in, out}]")

    val DISCONNECT = EndpointSpec("POST", "/link/disconnect", "status",
        "Device to device: the other Bridgit disconnected a direction or the whole link, so this one marks it off too (link agents only). Not for agents.",
        body = listOf(Param("direction", "string", "From the sender's side: in, out or both", enum = listOf("in", "out", "both")),
            Param("granter", "object", "Who disconnected it")),
        example = "POST /v1/link/disconnect {\"direction\":\"both\"}", returns = "ok")

    /** The owner-configuration disconnects are here too: taking access away works even with owner features off (LNK-29). */
    val ALL = listOf(LINKS, CLAIM, EVENTS, PEERS, DISCONNECT) + cc.bridgit.app.ownerapi.ConfigurationLinks.TAKE_AWAY

    fun install(route: Route, api: ApiServer, app: BridgeApp) = with(api) {
        cc.bridgit.app.ownerapi.ConfigurationLinks.installTakeAway(route, api, app)
        with(route) {
            endpoint(LINKS) {
                val a = agent!!
                Reply.Json(JSONObject().put("links", app.links.agentList(a)).put("how_to_call", HOW))
            }
            endpoint(CLAIM) {
                if (LinkPause.hubsPaused) throw ErrorCatalog.error("forbidden", LinkPause.HUBS_MESSAGE, mapOf("reason" to "hubs_paused"))
                val token = header("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
                val code = try { app.links.redeem(token) } catch (e: ApiError) { api.recordAuthFailure(source); throw e }
                val r = app.links.acceptClaim(code, source, body)
                message = "Another Bridgit claimed a link code (${r.optString("agent")})"
                data = JSONObject().put("link_id", r.optString("link_id"))
                Reply.Json(r)
            }
            endpoint(EVENTS) {
                val r = app.links.byInAgent(agent!!.id) ?: throw ErrorCatalog.error("agent_not_allowed", "Only a linked Bridgit may send link events.")
                Reply.Json(app.links.receive(r, body))
            }
            endpoint(PEERS) {
                val mine = app.links.byInAgent(agent!!.id) ?: throw ErrorCatalog.error("agent_not_allowed", "Only a linked Bridgit may read this device's links.")
                Reply.Json(JSONObject().put("peers", JSONArray(app.links.all().filter { it.id != mine.id }.map { r ->
                    JSONObject().put("name", app.links.peerLabel(r)).put("in", app.links.inState(r).wire).put("out", app.links.outState(r).wire) })))
            }
            endpoint(DISCONNECT) {
                val r = app.links.byInAgent(agent!!.id) ?: throw ErrorCatalog.error("agent_not_allowed", "Only a linked Bridgit may disconnect its link here.")
                val dir = body.optString("direction", "both").takeIf { it in setOf("in", "out", "both") } ?: throw ErrorCatalog.error("invalid_parameter", "direction must be in, out or both.")
                val e = JSONObject().put("id", LinkLog.newId()).put("at", System.currentTimeMillis()).put("link_id", r.id).put("direction", dir)
                    .put("event", "disconnected").put("granter", body.optJSONObject("granter") ?: JSONObject.NULL).put("device", app.links.peerLabel(r))
                Reply.Json(app.links.receive(r, JSONObject().put("link_id", r.id).put("events", JSONArray().put(e))))
            }
        }
    }
}
