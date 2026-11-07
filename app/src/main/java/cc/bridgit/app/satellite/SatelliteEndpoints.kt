package cc.bridgit.app.satellite

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiChanges
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import io.ktor.server.routing.Route
import org.json.JSONArray
import org.json.JSONObject

/** The satellite endpoints (docs/spec-satellites.md). Part of the status module: no switch of their own. */
object SatelliteEndpoints {
    private const val HOW = "Any call takes header X-Bridgit-Target: <satellite id> (or query target=<id>) to run on that satellite instead of this phone; leave it out to use this phone."

    val SATELLITES = EndpointSpec("GET", "/satellites", "status",
        "The satellites (other Bridgit phones linked to this one) you may use, with what each offers. $HOW",
        example = "GET /v1/satellites",
        returns = "satellites[{id (sat:…), name, status (online|offline|unlinked|not_checked), last_seen (ms, 0 = never), battery{percent, charging}, model, device_type (phone, tablet, computer, tv, watch or camera; null from an older satellite), app_version, cameras[{id, target_camera}], capabilities[{id, enabled, ready, blocked_reason}], endpoints[{method, path}], how_to_call}], how_to_call. Only satellites the owner lets you use are listed.")
    val CLAIM = EndpointSpec("POST", "/satellite/claim", "status",
        "Phone to phone: a hub trades a satellite pairing link's one-time code for its key. Not for agents.",
        auth = false, privateFields = setOf("token"),
        body = listOf(Param("token", "string", "The claim=sct_… value from the satellite's pairing link", required = true),
            Param("hub_name", "string", "The hub's name, shown on the satellite")),
        example = "POST /v1/satellite/claim {\"token\":\"sct_…\",\"hub_name\":\"Kitchen tablet\"}",
        returns = "key (the hub's own agent key, locked to the calling Tailscale address), name (this phone's name), agent (the hub link's name)",
        errors = listOf("pairing_closed", "unauthorized", "rate_limited"))
    val MANIFEST = EndpointSpec("GET", "/satellite/manifest", "status",
        "Phone to phone: what this satellite offers its hub (only for a hub link's key). Agents use GET /v1/satellites on the hub.",
        example = "GET /v1/satellite/manifest",
        returns = "name, model, device_type (phone, tablet, computer, tv, watch or camera), android, app_version, api_revision, install, battery{percent, charging}, cameras[ids], capabilities[{id, enabled, ready, blocked_reason}], endpoints[{method, path, capability}]")

    val HUB_INFO = EndpointSpec("POST", "/satellite/hub-info", "status",
        "Phone to phone: a hub tells its satellite which photo applets and portals it has, and how to call back (only for a hub link's key). Not for agents.",
        privateFields = setOf("key"),
        body = listOf(Param("name", "string", "The hub's name"),
            Param("device_type", "string", "The hub's kind: phone, tablet, computer, tv, watch or camera"), Param("port", "integer", "The hub's API port", required = true),
            Param("key", "string", "The satellite's return key on the hub", required = true),
            Param("targets", "array", "The hub's photo applets [{id, name}]"), Param("portals", "array", "The hub's portals [{id, name, url}]")),
        example = "POST /v1/satellite/hub-info {\"name\":\"Kitchen tablet\",\"port\":18471,\"key\":\"<return key>\",\"targets\":[],\"portals\":[]}",
        returns = "ok")
    val HUB = EndpointSpec("GET", "/satellite/hub", "status",
        "Phone to phone: a satellite reads its hub's photo applets and portals (only for a satellite's return key). Not for agents.",
        example = "GET /v1/satellite/hub", returns = "name, device_type (phone, tablet, computer, tv, watch or camera), targets[{id, name}], portals[{id, name, url}]")
    val HUB_PHOTO = EndpointSpec("POST", "/satellite/hub/photo", "status",
        "Phone to phone: a photo the owner took on a satellite's Camera tab, checked by this hub's photo applets (only for a satellite's return key). Not for agents.",
        maxBody = 6 * 1024 * 1024, privateFields = setOf("data_base64"),
        body = listOf(Param("data_base64", "string", "The photo, JPEG", required = true),
            Param("camera", "string", "rear (default) or front", enum = listOf("rear", "front")),
            Param("targets", "array", "Applet ids from GET /v1/satellite/hub; omitted or empty = all")),
        example = "POST /v1/satellite/hub/photo {\"camera\":\"rear\",\"data_base64\":\"/9j/...\"}",
        returns = "camera, targets[{id, name, checks[{watch_id, label, kind, status, summary, answers?}]}] (as POST /v1/portal/photo)",
        errors = listOf("invalid_parameter", "not_found"))
    val HUB_PORTAL_LOGIN = EndpointSpec("POST", "/satellite/hub/portal-login", "status",
        "Phone to phone: a one-use bridgit.cc sign-in code for the hub's account, so a satellite's Portals tab opens the hub's portals signed in (only for a satellite's return key). Not for agents.",
        example = "POST /v1/satellite/hub/portal-login", returns = "code (null when this phone isn't linked to an account)")

    val UNLINK = EndpointSpec("POST", "/satellite/unlink", "status",
        "Phone to phone: the hub removed this satellite, so the satellite drops its hub link too (only for a hub link's key). Not for agents.",
        example = "POST /v1/satellite/unlink", returns = "ok")
    val HUB_UNLINK = EndpointSpec("POST", "/satellite/hub/unlink", "status",
        "Phone to phone: a satellite unlinked this hub, so the hub removes it too (only for a satellite's return key). Not for agents.",
        example = "POST /v1/satellite/hub/unlink", returns = "ok")

    val HUB_AGENTS = EndpointSpec("GET", "/satellite/hub/agents", "status",
        "Phone to phone: the hub's control agents, so a satellite switched to its hub can see and pause them (only for a satellite's return key, while the hub hasn't paused that satellite). Not for agents.",
        example = "GET /v1/satellite/hub/agents", returns = "agents[{id, name, model, paused, last_used_at (ms, 0 = never)}]")
    val HUB_AGENT_PAUSE = EndpointSpec("POST", "/satellite/hub/agents/{id}/pause", "status",
        "Phone to phone: a satellite pauses or resumes one of its hub's control agents (only for a satellite's return key). Not for agents.",
        body = listOf(Param("paused", "boolean", "true pauses, false resumes", required = true)),
        example = "POST /v1/satellite/hub/agents/agt_123/pause {\"paused\":true}", returns = "id, name, paused",
        errors = listOf("not_found", "invalid_parameter"))

    val ALL = listOf(SATELLITES, CLAIM, MANIFEST, HUB_INFO, HUB, HUB_PHOTO, HUB_PORTAL_LOGIN, UNLINK, HUB_UNLINK, HUB_AGENTS, HUB_AGENT_PAUSE)

    fun install(route: Route, api: ApiServer, app: BridgeApp) = with(api) {
        with(route) {
            endpoint(SATELLITES) {
                val a = agent!!
                val list = app.satellites.all().filter { a.id in it.agents }.map { publicJson(it) }
                Reply.Json(JSONObject().put("satellites", JSONArray(list)).put("how_to_call", HOW))
            }
            endpoint(CLAIM) {
                if (LinkPause.hubsPaused) throw ErrorCatalog.error("forbidden", LinkPause.HUBS_MESSAGE, mapOf("reason" to "hubs_paused"))
                if (!app.hubLinks.pending()) throw ErrorCatalog.error("pairing_closed", "This phone has no satellite link waiting. On it, open Settings › Satellite and make a new link.")
                val c = app.hubLinks.claim(body.optString("token", ""), source, body.optString("hub_name", "").ifBlank { null })
                    ?: run { api.recordAuthFailure(source); throw ErrorCatalog.error("unauthorized", "Wrong or expired satellite pairing code.") }
                message = "A hub claimed this phone as a satellite (${c.agentName})"
                data = JSONObject().put("agent_id", c.agentId)
                Reply.Json(JSONObject().put("key", c.key).put("name", app.hubLinks.name()).put("agent", c.agentName))
            }
            endpoint(MANIFEST) {
                val a = agent!!
                if (!app.hubLinks.isHub(a.id)) throw ErrorCatalog.error("agent_not_allowed", "Only this phone's hub may read its satellite manifest.")
                app.hubLinks.noteHubName(a.id, header(SatellitePolicy.HUB_NAME_HEADER))
                Reply.Json(manifest(app, a))
            }
            endpoint(HUB_INFO) {
                val a = agent!!
                if (!app.hubLinks.isHub(a.id)) throw ErrorCatalog.error("agent_not_allowed", "Only this phone's hub may send it hub info.")
                app.hubLinks.noteHubName(a.id, header(SatellitePolicy.HUB_NAME_HEADER))
                val key = body.optString("key")
                val port = body.optInt("port", 0)
                if (!key.startsWith("brg_") || port !in 1..65535) throw ErrorCatalog.error("invalid_parameter", "key and port are required.")
                app.hubLinks.saveHubInfo(a.id, HubLinks.HubInfo.fromBody(a.id, source, port, key, body))
                app.hubLinks.dropOlderLinksTo(a.id, source, port)
                Reply.Json(JSONObject().put("ok", true))
            }
            endpoint(UNLINK) {
                val a = agent!!
                if (!app.hubLinks.isHub(a.id)) throw ErrorCatalog.error("agent_not_allowed", "Only this phone's hub may unlink it.")
                message = "The hub removed this phone, so its hub link was removed too"
                afterReply { app.hubLinks.unlink(a.id, tellHub = false) }
                Reply.Json(JSONObject().put("ok", true))
            }
            endpoint(HUB_UNLINK) {
                val s = returnLink(app, agent!!.id)
                message = "Satellite ${s.name} unlinked this phone, so it was removed here too"
                afterReply { SatelliteHub.remove(app, s.id, tellSatellite = false) }
                Reply.Json(JSONObject().put("ok", true))
            }
            endpoint(HUB) {
                returnLink(app, agent!!.id)
                Reply.Json(hubInfo(app, cc.bridgit.app.platform.DeviceName.forLinks(app)))
            }
            endpoint(HUB_PHOTO) {
                val s = returnLink(app, agent!!.id)
                val camera = body.optString("camera", "rear").ifBlank { "rear" }
                if (camera !in setOf("rear", "front")) throw ErrorCatalog.error("invalid_parameter", "camera must be rear or front.")
                val bytes = runCatching { android.util.Base64.decode(body.optString("data_base64"), android.util.Base64.DEFAULT) }.getOrNull()
                    ?.takeIf { it.size > 100 } ?: throw ErrorCatalog.error("invalid_parameter", "data_base64 must be a JPEG photo.")
                val ids = body.optJSONArray("targets")?.let { t -> (0 until t.length()).map { t.optString(it) }.filter { it.isNotBlank() } }
                try {
                    Reply.Json(app.ownerPhotos.submit(bytes, camera, ids, "satellite ${s.name}", waitMs = 25_000))
                } catch (e: cc.bridgit.app.watch.OwnerPhotos.NoTargets) {
                    throw ErrorCatalog.error("not_found", e.message ?: "No applet checks photos.")
                }
            }
            endpoint(HUB_AGENTS) {
                returnLink(app, agent!!.id)
                Reply.Json(JSONObject().put("agents", JSONArray(managedAgents(app).map { a ->
                    JSONObject().put("id", a.id).put("name", a.name).put("model", a.modelLabel ?: JSONObject.NULL)
                        .put("paused", a.paused).put("last_used_at", a.lastUsedAt)
                })))
            }
            endpoint(HUB_AGENT_PAUSE) {
                val s = returnLink(app, agent!!.id)
                val id = path("id").orEmpty()
                val target = managedAgents(app).firstOrNull { it.id == id } ?: throw ErrorCatalog.error("not_found", "No such agent on this phone.")
                if (!body.has("paused")) throw ErrorCatalog.error("invalid_parameter", "paused is required.")
                val paused = body.optBoolean("paused")
                app.agents.update(id) { it.copy(paused = paused) }
                app.log.system("audit", "agent", "Satellite ${s.name} ${if (paused) "paused" else "resumed"} ${target.name}")
                Reply.Json(JSONObject().put("id", id).put("name", target.name).put("paused", paused))
            }
            endpoint(HUB_PORTAL_LOGIN) {
                returnLink(app, agent!!.id)
                Reply.Json(JSONObject().put("code", app.remoteView.portalLoginCode() ?: JSONObject.NULL))
            }
        }
    }

    /**
     * Removes the calling link just after this reply: the server re-checks the caller's key once the handler
     * returns, so removing it inside the handler would answer 401 to a request that worked.
     */
    private fun afterReply(f: () -> Unit) { kotlin.concurrent.thread(isDaemon = true, name = "satellite-unlink-after") { Thread.sleep(1500); runCatching(f) } }

    /** The hub's control agents a satellite may see and pause: not phone links (hub and return links), applets or portals. */
    private fun managedAgents(app: BridgeApp): List<cc.bridgit.app.core.Agent> {
        val portals = runCatching { cc.bridgit.app.portal.PortalPairing.paired(app, cc.bridgit.app.relay.RelayStore(app)).map { it.id }.toSet() }.getOrDefault(emptySet())
        return app.agents.all().filter { a -> !app.hubLinks.isHub(a.id) && !app.satellites.isReturnAgent(a.id) &&
            a.connection != cc.bridgit.app.core.Connection.APPLET && a.id !in portals }
    }

    private fun returnLink(app: BridgeApp, agentId: String): SatelliteStore.Satellite =
        app.satellites.byReturnAgent(agentId) ?: throw ErrorCatalog.error("agent_not_allowed", "Only a satellite of this phone may call this.")

    /** What this hub offers its satellites' Camera and Portals tabs: its photo applets and its portals. */
    fun hubInfo(app: BridgeApp, hubName: String): JSONObject = JSONObject().put("name", hubName)
        .put(cc.bridgit.app.platform.DeviceType.FIELD, cc.bridgit.app.platform.DeviceType.of(app).wire)
        .put("targets", JSONArray(app.ownerPhotos.targets().map { JSONObject().put("id", it.id).put("name", it.name) }))
        .put("portals", JSONArray(cc.bridgit.app.ui.applets.AppletPortalsFragment.own(app).map {
            JSONObject().put("id", it.id).put("name", it.name).put("url", it.url) }))

    /** What this phone offers the hub link [a]: its caps ∩ this phone's switches (SAT-18, SAT-35). */
    fun manifest(app: BridgeApp, a: cc.bridgit.app.core.Agent): JSONObject {
        val live = app.agents.get(a.id) ?: a
        val caps = JSONArray(); val eps = JSONArray()
        val mods = app.registry.modules.filter { m -> m.endpoints.any { SatellitePolicy.forwardable(it) } }
        mods.filter { it.id in SatellitePolicy.PHASE1_CAPS }.forEach { m ->
            val st = m.status()
            caps.put(JSONObject().put("id", m.id).put("enabled", st.enabled && live.allows(m.id)).put("ready", st.ready)
                .put("blocked_reason", (if (!live.allows(m.id)) "not_shared_with_hub" else st.blockedReason) ?: JSONObject.NULL))
        }
        mods.flatMap { it.endpoints }.filter { SatellitePolicy.forwardable(it) }.distinctBy { it.method + it.path }
            .filter { live.allows(it.capability) && (it.capability == "status" || app.registry.byId(it.capability)?.enabled != false) }
            .forEach { eps.put(JSONObject().put("method", it.method).put("path", it.fullPath).put("capability", it.capability)) }
        return JSONObject().put("name", app.hubLinks.name()).put("model", android.os.Build.MODEL)
            .put(cc.bridgit.app.platform.DeviceType.FIELD, cc.bridgit.app.platform.DeviceType.of(app).wire)
            .put("android", android.os.Build.VERSION.RELEASE).put("app_version", cc.bridgit.app.BuildConfig.VERSION_NAME)
            .put("api_revision", ApiChanges.CURRENT)
            .put("install", runCatching { cc.bridgit.app.feedback.InstallIdentity.status(app)?.installId }.getOrNull() ?: JSONObject.NULL)
            .put("battery", battery(app)).put("cameras", JSONArray(listOf("rear", "front")))
            .put("capabilities", caps).put("endpoints", eps)
    }

    fun battery(app: BridgeApp): JSONObject {
        val i = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return JSONObject().put("percent", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }

    /** One satellite as agents on the hub see it (no host, port or key). */
    fun publicJson(s: SatelliteStore.Satellite, now: Long = System.currentTimeMillis()): JSONObject {
        val m = s.manifest
        val status = when { s.lastError == "unlinked" -> "unlinked"; s.lastSeen == 0L -> "not_checked"; s.online(now) -> "online"; else -> "offline" }
        val cams = JSONArray(); m?.optJSONArray("cameras")?.let { a -> for (i in 0 until a.length()) cams.put(JSONObject()
            .put("id", "${s.id}/${a.getString(i)}").put("target_camera", a.getString(i))) }
        val eps = JSONArray(); m?.optJSONArray("endpoints")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let {
            eps.put(JSONObject().put("method", it.optString("method")).put("path", it.optString("path"))) } }
        return JSONObject().put("id", s.id).put("name", s.name).put("kind", "satellite").put("status", status)
            .put("last_seen", s.lastSeen).put("battery", m?.optJSONObject("battery") ?: JSONObject.NULL)
            .put("model", m?.optString("model") ?: JSONObject.NULL)
            .put(cc.bridgit.app.platform.DeviceType.FIELD, cc.bridgit.app.platform.DeviceType.parse(m?.optString(cc.bridgit.app.platform.DeviceType.FIELD))?.wire ?: JSONObject.NULL).put("app_version", m?.optString("app_version") ?: JSONObject.NULL)
            .put("cameras", cams).put("capabilities", m?.optJSONArray("capabilities") ?: JSONArray()).put("endpoints", eps)
            .put("how_to_call", "Send header X-Bridgit-Target: ${s.id} (or ?target=${s.id}) with any listed endpoint. Example: POST /v1/camera/snapshot?target=${s.id} {\"camera\":\"rear\"}")
    }
}
