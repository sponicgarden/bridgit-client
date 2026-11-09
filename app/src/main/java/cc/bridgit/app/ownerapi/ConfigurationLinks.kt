package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.core.Agent
import cc.bridgit.app.link.Granter
import cc.bridgit.app.link.LinkModel
import cc.bridgit.app.link.LinkTier
import cc.bridgit.app.link.LinkTierPolicy
import cc.bridgit.app.satellite.LinkPause
import io.ktor.server.routing.Route
import org.json.JSONArray
import org.json.JSONObject

/**
 * Linked Bridgits, owner configuration (docs/spec-linked-bridgits.md §10, LNK-45). Giving or widening needs the
 * owner.configuration grant ([CONFIG], part of ConfigurationEndpoints.ALL, so both MCP servers' configuration.get/set
 * list them). Taking access away needs less (LNK-29): any control agent of this device may disconnect or pause
 * ([TAKE_AWAY]). Link agents, applets and portals can do neither: each device's own agents change its links.
 */
object ConfigurationLinks {
    private const val P = "/owner/configuration/links"
    private val ERRORS = listOf("invalid_parameter", "forbidden", "agent_not_allowed", "not_found", "conflict", "upstream_failed",
        "name_required", "code_invalid", "code_expired", "code_used_up", "link_revoked", "link_waiting", "target_offline", "team_unavailable")

    private fun read(path: String, what: String, returns: String, query: List<Param> = emptyList()) = EndpointSpec("GET", "$P$path", "owner_features", what,
        query = query, example = "GET /v1$P$path", returns = returns, controlScope = "owner.configuration")
    private fun write(path: String, what: String, body: List<Param>, example: String, returns: String, method: String = "POST") = EndpointSpec(method,
        "$P$path", "owner_features", "$what; explicit owner.configuration grant required; no per-change phone approval",
        body = body, example = "$method /v1$P$path $example", returns = returns, errors = ERRORS,
        idempotent = true, idempotencyRequired = true, controlScope = "owner.configuration", privateFields = setOf("*"), maxBody = 32768)
    private fun takeAway(path: String, what: String, body: List<Param>, example: String, returns: String) = EndpointSpec("POST", "$P$path", "status",
        "$what; any control agent of this device (taking access away needs no owner.configuration grant)",
        body = body, example = "POST /v1$P$path $example", returns = returns, errors = listOf("invalid_parameter", "forbidden", "not_found", "conflict"),
        idempotent = true, idempotencyRequired = true, privateFields = setOf("*"), maxBody = 4096)

    private const val LINK = "id, name, bridgit_name, target, sat_id, role, tier (own|associate|public: the narrower of tier_here and tier_there), tier_here, tier_there, tier_about, associate, associate_email, expires_at, review_at, registration{id, tier, role host|peer, state, error} (the team backend's record), in{state, services, granter, paused, revoked, other_side}, out{state, agents[{id,name}], granter, paused, revoked, services, other_side}, disconnecting{direction, undo_until}"

    val LIST = read("", "Read this Bridgit's links with the owner's other Bridgits: both directions, their states, service lists, allowed agents and who granted each",
        "links[$LINK], portals[{id (portal:… a device viewing this one, viewing:… one this device views), kind, name, tier, expires_at, review_at, associate}], codes[{id, services, directions, expires_at, tries_left, state, granter, tier, access_ms, associate}], tiers[{tier, label, services, about}], services (vocabulary), suggested_services, paused_all{satellites, hubs}, name (this Bridgit's Device Domain Name or null), operations")
    val CODES = write("/codes", "Make a one-time link code for this Bridgit as the target (8 characters, 10 minutes, 5 wrong tries); needs a Device Domain Name",
        listOf(Param("services", "array", "What the claiming Bridgit may use here: ${LinkModel.SERVICES.sorted().joinToString()} (suggested: ${LinkModel.SUGGESTED.joinToString()}); required for in"),
            Param("directions", "array", "[\"in\"] (default: the claiming Bridgit uses this one), [\"out\"] (this one uses it) or both"),
            Param("agents", "array", "For out: this Bridgit's control agent ids that may use the claiming Bridgit"),
            Param("tier", "string", "own (default: the owner's own device), associate (a named person's device: services ${LinkTierPolicy.ASSOCIATE_SERVICES.sorted().joinToString()}, ends or is reviewed) or public (a device in the wild: one of ${LinkTierPolicy.PUBLIC_SERVICES.sorted().joinToString()}, in only, at most 24 h, the owner allows each change)", enum = LinkTier.ORDER.map { it.wire }),
            Param("access_ms", "integer", "associate and public: how long the claimed link's access lasts (associate 1 min-90 days, default 30 days; public 1 min-24 h, default 1 h)"),
            Param("associate", "string", "associate: the person's name"),
            Param("associate_email", "string", "associate: the person's email (required; their Bridgit confirms the link from that account)")),
        "{\"services\":[\"camera\",\"mic\",\"speaker\",\"interactions\",\"view\"]}",
        "code_id, code (K7QX-3MPA: give it with the name), name, expires_at, tier, tier_about, access_ms, associate, services, directions, link (bridgit://link#… for a QR code), name_lookup, how")
    val CODE_DELETE = write("/codes/{id}", "Cancel a link code that hasn't been claimed", emptyList(), "", "code_id, state=cancelled", method = "DELETE")
    val CLAIMS = write("/claims", "Claim another Bridgit's link code: this Bridgit then uses it (its user opt-in), and may offer the other direction too",
        listOf(Param("name", "string", "The other Bridgit's Device Domain Name (required unless link is given)"), Param("code", "string", "Its link code, K7QX-3MPA (required unless link is given)"),
            Param("link", "string", "Its bridgit://link#… link (holds name, code and its Tailscale address)"),
            Param("host", "string", "Its Tailscale address, when the names backend can't resolve the name"), Param("port", "integer", "Its Bridgit port, with host"),
            Param("agents", "array", "This Bridgit's control agent ids that may use it; default: the calling agent"),
            Param("reverse_services", "array", "What the other Bridgit may use here in return (it opts in on its side); default none. Limited by the tier"),
            Param("tier", "string", "This Bridgit's tier for the link (default: the one the code was made with); the narrower of the two applies", enum = LinkTier.ORDER.map { it.wire }),
            Param("associate", "string", "associate: the other person's name"),
            Param("associate_email", "string", "associate: the other person's email")),
        "{\"name\":\"kitchen-tab\",\"code\":\"K7QX-3MPA\"}", "the new link ($LINK)")
    val DIRECTION = write("/{id}/directions/{direction}", "Change this Bridgit's half of one direction: in = the other Bridgit uses this one (services), out = this one uses it (agents); paused on either",
        listOf(Param("services", "array", "in only: the service list (what the other Bridgit may use here); [] leaves only the link itself"),
            Param("agents", "array", "out only: this Bridgit's control agent ids that may use the other one; setting it is this Bridgit's opt-in"),
            Param("paused", "boolean", "Pause or resume this direction on this Bridgit")),
        "{\"agents\":[\"agt_…\"]}", "the link ($LINK)")
    val UNDO = write("/{id}/undo", "Undo a disconnect within 10 seconds; after that linking again needs a new code", emptyList(), "", "the link ($LINK)")
    val PAUSE_ALL = write("/pause-all", "Pause or resume links by role, as the Linked devices pills do; each pauses both directions of those links",
        listOf(Param("satellites", "boolean", "true pauses every link where the other Bridgit is this one's satellite (Satellites pill)"),
            Param("hubs", "boolean", "true pauses every link where the other Bridgit is this one's hub (Hubs pill)")),
        "{\"hubs\":true}", "paused_all{satellites, hubs}")
    val TIER = write("/{id}/tier", "Set a link's trust tier on this Bridgit: own (Your Other Devices), associate (Devices of Associates) or public (Devices in the Wild). Narrowing applies at once and removes services the tier doesn't allow; widening a link record needs the other Bridgit to widen too. Also renews an associate's or public link's access. id: a link id, portal:<agent id> or viewing:<device id>",
        listOf(Param("tier", "string", "own, associate or public", enum = LinkTier.ORDER.map { it.wire }, required = true),
            Param("access_ms", "integer", "associate and public: access from now (renews it)"),
            Param("review_at", "integer", "associate: a review reminder (ms) before the end date; a Portal pairing may have only a review date"),
            Param("associate", "string", "associate: the person's name"),
            Param("associate_email", "string", "associate: the person's email (required unless already set)")),
        "{\"tier\":\"associate\",\"associate\":\"Sam\",\"associate_email\":\"sam@example.com\",\"access_ms\":2592000000}", "the link ($LINK, or the portal entry), tier_before")
    val LOG = read("/log", "Read the grants log: every link event on this Bridgit and the ones the other Bridgits reported, newest first, with who did each",
        "events[{id, at, link_id, direction, event, before, after, granter{kind person|agent|rule|migrated, agent_id, agent_name, device}, device, code_id, reported_by, late}], next_before",
        listOf(Param("link", "string", "Only this link id"), Param("direction", "string", "in, out or both", enum = listOf("in", "out", "both")),
            Param("granter", "string", "person, agent, rule, migrated, or an agent id or name"), Param("before", "integer", "Older than this time (ms), for paging"),
            Param("limit", "integer", "1-200, default 50")))

    val DISCONNECT = takeAway("/{id}/disconnect", "Disconnect (or pause) a link at once: calls are refused from now, and it is final after 10 seconds unless undone",
        listOf(Param("direction", "string", "in (the other Bridgit uses this one), out (this one uses it) or both (default)", enum = listOf("in", "out", "both")),
            Param("pause", "boolean", "true pauses instead (resume with directions {paused:false}, which needs owner.configuration)")),
        "{\"direction\":\"both\"}", "the link ($LINK), undo_until")
    val DISCONNECT_ALL = takeAway("/disconnect-all", "Disconnect every link of this Bridgit at once (Undo per link for 10 seconds)", emptyList(), "", "disconnected (count), undo_until")

    val CONFIG = listOf(LIST, CODES, CODE_DELETE, CLAIMS, DIRECTION, TIER, UNDO, PAUSE_ALL, LOG)
    val TAKE_AWAY = listOf(DISCONNECT, DISCONNECT_ALL)

    /** LNK-29: a control agent of this device (not a link agent, applet or portal), live and unpaused. */
    fun requireControl(app: BridgeApp, caller: Agent): Agent {
        val live = app.agents.get(caller.id)?.takeUnless { it.paused } ?: throw ErrorCatalog.error("unauthorized", "Control agent is no longer active")
        if (live.keyHash != caller.keyHash) throw ErrorCatalog.error("unauthorized", "Control key changed")
        if (!app.links.isControlAgent(live)) throw ErrorCatalog.error("forbidden", "Only this Bridgit's own control agents change its links")
        return live
    }

    private fun granter(app: BridgeApp, a: Agent) = Granter.agent(a.id, a.name, app.links.selfName())

    private fun strings(body: JSONObject, key: String): Set<String>? {
        if (!body.has(key) || body.isNull(key)) return null
        val a = body.optJSONArray(key) ?: throw ErrorCatalog.error("invalid_parameter", "$key must be an array")
        if (a.length() > 20) throw ErrorCatalog.error("invalid_parameter", "$key has too many values")
        return (0 until a.length()).map { (a.opt(it) as? String)?.takeIf { s -> s.length in 1..80 } ?: throw ErrorCatalog.error("invalid_parameter", "$key must hold strings") }.toSet()
    }
    private fun services(body: JSONObject, key: String): Set<String>? = strings(body, key)?.let {
        try { LinkModel.parseServices(JSONArray(it.toList())) } catch (e: IllegalArgumentException) { throw ErrorCatalog.error("invalid_parameter", e.message ?: "Unknown service") }
    }

    fun list(app: BridgeApp): JSONObject = JSONObject()
        .put("links", JSONArray(app.links.all().map { app.links.json(it) }))
        .put("portals", JSONArray(app.links.portalTiers().map { it.toJson() }))
        .put("tiers", JSONArray(LinkTier.ORDER.map { t -> JSONObject().put("tier", t.wire).put("label", tierTitle(t))
            .put("services", LinkModel.json(LinkTierPolicy.allowedServices(t))).put("about", LinkTierPolicy.about(t)) }))
        .put("codes", JSONArray(app.links.openCodes().map { it.public() }))
        .put("services", LinkModel.json(LinkModel.SERVICES)).put("suggested_services", JSONArray(LinkModel.SUGGESTED))
        .put("paused_all", pausedAll()).put("name", app.links.registeredName() ?: JSONObject.NULL)
        .put("operations", JSONArray((CONFIG + TAKE_AWAY).map { "${it.method} ${it.fullPath}" }))
        .put("learn", cc.bridgit.app.api.Learn.LINKS_URL)

    /** The Linked devices section names, in display order. */
    fun tierTitle(t: LinkTier) = when (t) { LinkTier.OWN -> "Your Other Devices"; LinkTier.ASSOCIATE -> "Devices of Associates"; LinkTier.PUBLIC -> "Devices in the Wild" }

    private fun tier(body: JSONObject, key: String = "tier"): LinkTier? = if (!body.has(key) || body.isNull(key)) null
        else LinkTier.parse(body.optString(key)) ?: throw ErrorCatalog.error("invalid_parameter", "$key must be own, associate or public")
    private fun accessMs(body: JSONObject): Long? = if (!body.has("access_ms") || body.isNull("access_ms")) null
        else body.optLong("access_ms", -1).takeIf { it > 0 } ?: throw ErrorCatalog.error("invalid_parameter", "access_ms must be a positive number of milliseconds")
    private fun optText(body: JSONObject, key: String, max: Int): String? = if (!body.has(key) || body.isNull(key)) null else ConfigurationAccess.text(body, key, max)

    private fun pausedAll() = JSONObject().put("satellites", LinkPause.satellites).put("hubs", LinkPause.hubsPaused)

    fun install(route: Route, api: ApiServer, app: BridgeApp) = with(api) {
        with(route) {
            endpoint(LIST) { ConfigurationAccess(app, agent!!).require(); Reply.Json(list(app)) }
            endpoint(CODES) {
                val a = ConfigurationAccess(app, agent!!).require()
                ConfigurationAccess.only(body, "services", "directions", "agents", "tier", "access_ms", "associate", "associate_email")
                val dirs = strings(body, "directions") ?: setOf("in")
                Reply.Json(app.links.makeCode(services(body, "services") ?: emptySet(), dirs, strings(body, "agents") ?: emptySet(), granter(app, a),
                    tier = tier(body) ?: LinkTier.OWN, accessMs = accessMs(body), associate = optText(body, "associate", 60), associateEmail = optText(body, "associate_email", 254)))
            }
            endpoint(CODE_DELETE) {
                val a = ConfigurationAccess(app, agent!!).require()
                Reply.Json(app.links.cancelCode(path("id").orEmpty(), granter(app, a)))
            }
            endpoint(CLAIMS) {
                val a = ConfigurationAccess(app, agent!!).require()
                ConfigurationAccess.only(body, "name", "code", "link", "host", "port", "agents", "reverse_services", "tier", "associate", "associate_email")
                val t = if (body.has("link")) app.links.parseLink(ConfigurationAccess.text(body, "link", 1024)).let { l ->
                    if (body.has("name") && cc.bridgit.app.feedback.DeviceNames.parse(body.optString("name")) != cc.bridgit.app.feedback.DeviceNames.parse(l.name.orEmpty()))
                        throw ErrorCatalog.error("invalid_parameter", "name doesn't match the link's name")
                    l
                } else {
                    val host = body.optString("host").takeIf { body.has("host") }?.let { cc.bridgit.app.core.Net.normalise(it) }
                    if (host != null && !cc.bridgit.app.core.Net.isTailnet(host)) throw ErrorCatalog.error("invalid_parameter", "host must be a Tailscale address")
                    val port = if (body.has("port")) body.optInt("port", -1).takeIf { it in 1..65535 } ?: throw ErrorCatalog.error("invalid_parameter", "port must be 1-65535") else null
                    if ((host == null) != (port == null)) throw ErrorCatalog.error("invalid_parameter", "host and port go together")
                    cc.bridgit.app.link.Links.ClaimTarget(ConfigurationAccess.text(body, "name", 40), ConfigurationAccess.text(body, "code", 20), host, port)
                }
                val agents = strings(body, "agents") ?: setOf(a.id)
                Reply.Json(app.links.claim(t, agents, services(body, "reverse_services") ?: emptySet(), granter(app, a), tier(body), optText(body, "associate", 60), optText(body, "associate_email", 254)))
            }
            endpoint(DIRECTION) {
                val a = ConfigurationAccess(app, agent!!).require()
                ConfigurationAccess.only(body, "services", "agents", "paused")
                if (body.length() == 0) throw ErrorCatalog.error("invalid_parameter", "Send services, agents or paused")
                val paused = if (body.has("paused")) ConfigurationAccess.bool(body, "paused") else null
                Reply.Json(app.links.setDirection(path("id").orEmpty(), path("direction").orEmpty(), services(body, "services"), strings(body, "agents"), paused, granter(app, a)))
            }
            endpoint(TIER) {
                val a = ConfigurationAccess(app, agent!!).require()
                ConfigurationAccess.only(body, "tier", "access_ms", "review_at", "associate", "associate_email")
                val t = tier(body) ?: throw ErrorCatalog.error("invalid_parameter", "tier is required: own, associate or public")
                val review = if (body.has("review_at") && !body.isNull("review_at")) body.optLong("review_at", -1).takeIf { it > 0 } ?: throw ErrorCatalog.error("invalid_parameter", "review_at must be a time in ms") else null
                val id = path("id").orEmpty()
                val who = optText(body, "associate", 60); val email = optText(body, "associate_email", 254)
                Reply.Json(if (cc.bridgit.app.link.PortalTiers.keyFromApiId(id) != null) app.links.setPortalTier(id, t, accessMs(body), review, who, granter(app, a), associateEmail = email)
                    else app.links.setTier(id, t, accessMs(body), review, who, granter(app, a), associateEmail = email))
            }
            endpoint(UNDO) {
                val a = ConfigurationAccess(app, agent!!).require()
                Reply.Json(app.links.undo(path("id").orEmpty(), granter(app, a)))
            }
            endpoint(PAUSE_ALL) {
                val a = ConfigurationAccess(app, agent!!).require()
                ConfigurationAccess.only(body, "satellites", "hubs")
                if (body.length() == 0) throw ErrorCatalog.error("invalid_parameter", "Send satellites or hubs")
                if (body.has("satellites")) { val v = ConfigurationAccess.bool(body, "satellites"); if (v != LinkPause.satellites) { LinkPause.setSatellites(v); app.log.system("audit", "link", "${a.name} ${if (v) "paused" else "resumed"} all satellite links") } }
                if (body.has("hubs")) { val v = ConfigurationAccess.bool(body, "hubs"); if (v != LinkPause.hubsPaused) { LinkPause.setHubs(v); app.log.system("audit", "link", "${a.name} ${if (v) "paused" else "resumed"} all hub links") } }
                Reply.Json(JSONObject().put("paused_all", pausedAll()))
            }
            endpoint(LOG) {
                ConfigurationAccess(app, agent!!).require()
                val limit = (query("limit")?.toIntOrNull() ?: 50).coerceIn(1, 200)
                val dir = query("direction")?.takeIf { it in setOf("in", "out", "both") }
                val list = app.links.log.query(query("link"), dir, query("granter"), query("before")?.toLongOrNull(), limit)
                Reply.Json(JSONObject().put("events", JSONArray(list.map { it.toJson() }))
                    .put("next_before", if (list.size == limit) list.last().at else JSONObject.NULL))
            }
        }
    }

    fun installTakeAway(route: Route, api: ApiServer, app: BridgeApp) = with(api) {
        with(route) {
            endpoint(DISCONNECT) {
                val a = requireControl(app, agent!!)
                ConfigurationAccess.only(body, "direction", "pause")
                val dir = body.optString("direction", "both").takeIf { it in setOf("in", "out", "both") } ?: throw ErrorCatalog.error("invalid_parameter", "direction must be in, out or both")
                val pause = if (body.has("pause")) ConfigurationAccess.bool(body, "pause") else false
                Reply.Json(app.links.disconnect(path("id").orEmpty(), dir, granter(app, a), pause))
            }
            endpoint(DISCONNECT_ALL) {
                val a = requireControl(app, agent!!)
                Reply.Json(app.links.disconnectAll(granter(app, a)))
            }
        }
    }
}
