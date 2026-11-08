package cc.bridgit.app.portal

import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.core.Connection
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.remoteview.RemoteViewLines
import cc.bridgit.app.relay.RelayStore
import cc.bridgit.app.ui.home.HomeStates
import cc.bridgit.app.ui.home.ListenerCard
import io.ktor.server.routing.Route
import org.json.JSONArray
import org.json.JSONObject

object PortalEndpoints {
    private const val WHO = "For the owner's Bridgit Portal app (iPhone & iPad), over the relay; read-only."
    /** Accepted by every portal endpoint: which device is viewing, shown on the phone's Home. */
    val CLIENT = Param("client", "string", "Optional: the viewing device, e.g. iPhone, iPad or Android (1-24 letters, digits, space . _ -). The phone's Home shows it as the device viewing it")
    /** Sent with snapshot and home: what the viewing device is, for the phone's Linked devices map. */
    val CLIENT_DEVICE = Param("client_device", "string", "Optional: the viewing device's model, system and app version, e.g. iPhone 16 Pro · iOS 26.1 · Portal 1.4 (1-80 characters). Shown on the phone's Linked devices")
    /** Sent with snapshot and home: what kind of device is viewing, for the phone's Linked devices map. */
    val CLIENT_TYPE = Param("client_type", "string", "Optional: the viewing device's kind: phone, tablet, computer, tv, watch or camera. Shown on the phone's Linked devices")
    val SNAPSHOT = EndpointSpec("GET", "/portal/snapshot", "portal", "Home, agents, applets and the newest log entries in one call (saves relay quota). $WHO",
        query = listOf(CLIENT, CLIENT_DEVICE, CLIENT_TYPE, Param("logs", "integer", "Newest log entries to include, 0-50 (default 20)")),
        example = "GET /v1/portal/snapshot?logs=20", returns = "home (as GET /v1/portal/home), agents (as GET /v1/portal/agents), applets (as GET /v1/portal/applets), logs (as GET /v1/portal/logs), updates (null, or the request an agent left for this Portal: {id, prompts?: boolean, prompt_now: boolean, at}; apply it, then POST /v1/portal/updates with ack: id), relay_daily_jobs (relay calls this pairing may make per UTC day: 20000 for a Bridgit Portal pairing; other relay agents share the phone's pool, 12000 by default; absent off the relay)",
        errors = listOf("invalid_parameter"))
    val HOME = EndpointSpec("GET", "/portal/home", "portal", "The phone's Home: device, battery, connections, every Home feature with its state, what's listening, and the APK's navigation. $WHO",
        query = listOf(CLIENT, CLIENT_DEVICE, CLIENT_TYPE),
        example = "GET /v1/portal/home",
        returns = "device{name, manufacturer, model, android, api_level, app_version, type (phone or tablet), device_type (phone, tablet, computer, tv, watch or camera)}, battery{percent, charging}, connections{tailscale, adb, relay, wifi: connected|disconnected}, paused, features[{id, name, title, group, icon, state: on|off|blocked, summary}], listening[ids], agents_count (control agents, not portal viewers), nav[{id, title, icon, section: tab|more}], scene{options, paused, listening, home_status} (what the phone's Home feeds assets/diagnostics/scene.html)")
    val DEVICES = EndpointSpec("GET", "/portal/devices", "portal", "Remembered devices on the phone's current network, the same payload as GET /v1/lan/discovered; no scan, no switch needed (like the phone's Network devices screen). $WHO",
        query = listOf(CLIENT),
        example = "GET /v1/portal/devices", returns = "the GET /v1/lan/discovered payload: total_devices, connected_devices, unconnected_devices, categories, devices; every brand logo_url points at GET /v1/portal/brands/{id}/logo; plus added: the devices added to Bridgit (id, name, host, kind, state, applets, checks; no credentials)",
        errors = listOf("not_supported", "conflict"))
    val APPLET_INVENTORY = EndpointSpec("GET", "/portal/applets/inventory", "portal", "Enrolled devices for one approved applet card on this phone. $WHO",
        query = listOf(Param("template", "string", "Exact registry key", true), Param("revision", "integer", "Approved registry revision", true)),
        example = "GET /v1/portal/applets/inventory?template=external-device-dashboard&revision=1",
        returns = "template, revision, devices[{id,name,adapter,availability,verified_at}]. Listing does not probe devices.",
        errors = listOf("invalid_parameter", "not_found", "agent_not_allowed", "capability_disabled"))
    val APPLET_WATCHES = EndpointSpec("GET", "/portal/applets/watches", "portal", "Watch activity on the selected monitoring phone for one approved registry edition. $WHO",
        query = APPLET_INVENTORY.query, example = "GET /v1/portal/applets/watches?template=room-change-watcher&revision=1",
        returns = "template, revision, watches[{key,state:scheduled|paused|budget_paused|blocked|ended,checks?,matches?,last_check_at?,last_outcome?:error|skipped|match|no_match|checked}]. Declared status sources add recent_checks[{at,matched?|outcome?}]; tally sources add tally{today{checks,watched_minutes,labels},episodes[{start,label,minutes}]}, newest 20 from the last day.",
        errors = listOf("invalid_parameter", "not_found", "agent_not_allowed"))
    val BRAND_LOGO = EndpointSpec("GET", "/portal/brands/{id}/logo", "portal", "A bundled manufacturer logo, as GET /v1/lan/brands/{id}/logo, without the Network discovery switch. $WHO",
        media = true, query = listOf(CLIENT, Param("raw", "integer", "1 = the image bytes")),
        example = "GET /v1/portal/brands/apple/logo", returns = "id, name, logo_url, mime, data_base64 (or the image with raw=1)", errors = listOf("not_found"))
    val LOGS = EndpointSpec("GET", "/portal/logs", "portal", "Agent activity from the phone's log, with the same redaction as remote view: no messages, parameters or results. Oldest first after since; the newest page without it. $WHO",
        query = listOf(CLIENT, Param("since", "integer", "Log id: only entries after it (use next_since from the last page)"), Param("until", "integer", "Log id: an older page, entries before it (use oldest_id); not with since"), Param("limit", "integer", "1-200, default 50")),
        example = "GET /v1/portal/logs?since=48210&limit=50", returns = "entries[{id, ts, agent, kind, method, path, status, caption, plus the Home scene keys channel, level, result, endpoint, http, message, params, data}], next_since (pass back as since; it moves past skipped entries too), has_more, has_older, oldest_id (pass as until for the next older page), latest_id",
        errors = listOf("invalid_parameter"))
    val AGENTS = EndpointSpec("GET", "/portal/agents", "portal", "The phone's agents and what each may use. $WHO",
        query = listOf(CLIENT),
        example = "GET /v1/portal/agents", returns = "agents[{id, name, mode (connector|sealed for relay agents, direct otherwise), transport (direct|userspace|relay), last_used_at (ms, 0 = never), paused, all_access, grants[capability ids], model?}]")
    val APPLETS = EndpointSpec("GET", "/portal/applets", "portal", "The phone's applets (Pet monitor, City tour, live watches). $WHO",
        query = listOf(CLIENT),
        example = "GET /v1/portal/applets", returns = "applets[{id, name, kind (dog|tour|watch), running, last_at (ms, 0 = never), summary?, sensors, actions}]")
    val THEME = EndpointSpec("GET", "/portal/theme", "portal", "The APK's design tokens, read from its own resources and Home scene, so the portal looks the same. $WHO",
        query = listOf(CLIENT),
        example = "GET /v1/portal/theme", returns = "colors{light{name: #RRGGBB}, dark{…}}, scene{light, dark} (Home scene colours), corner_radius{name: dp}, fonts{name: family}, motion{name{duration_ms, easing}}, animations[{id, where, describe, motion}]")

    val PHOTO_TARGETS = EndpointSpec("GET", "/portal/photo/targets", "portal", "Who checks a photo sent with POST /v1/portal/photo: each agent (usually an installed applet) with a watch that checks photos, and the owner's last pick on the phone's Camera tab. $WHO",
        query = listOf(CLIENT),
        example = "GET /v1/portal/photo/targets",
        returns = "targets[{id (agent id), name, watches[{watch_id, label, model, kind: check|people|change}]}], chosen[ids] (empty = all)")
    val PHOTO = EndpointSpec("POST", "/portal/photo", "portal", "A photo the owner took with the Portal's camera: every photo watch of the chosen targets checks it right away, as if it had taken it (the Camera tab's path). Answers that take longer than about 25 s finish on the phone and show in the applet. The owner's portal pairing only; this is the portal's one write.",
        body = listOf(Param("data_base64", "string", "The photo, JPEG (about 1.4 MB at most after base64; send about 1280 px)", true),
            Param("camera", "string", "Which camera took it: rear (default) or front", enum = listOf("rear", "front")),
            Param("targets", "array", "Agent ids from GET /v1/portal/photo/targets; omitted or empty = all")),
        example = "POST /v1/portal/photo {\"camera\":\"rear\",\"data_base64\":\"/9j/...\"}",
        returns = "camera, targets[{id, name, checks[{watch_id, label, kind: check|people|change, status: answered|people_checked|skipped|checking|error, summary (one line for the owner), answers?}]}]",
        errors = listOf("invalid_parameter", "not_found"), privateFields = setOf("data_base64"))

    val UPDATES = EndpointSpec("POST", "/portal/updates", "portal", "The Portal's own update state (A0050): whether it prompts for new TestFlight builds, its build and the newest one, and which request it applied. Agents read it in GET /v1/owner/settings → portal_updates and change it with owner configuration actions portal.update_prompts and portal.update_now. The owner's portal pairing only.",
        body = listOf(Param("prompts_enabled", "boolean", "The Portal's Update prompts switch", true),
            Param("supported", "boolean", "Whether this install can be updated from TestFlight (false for test builds)", true),
            Param("installed_build", "string", "Installed build number (yymmddHHMM)"), Param("latest_build", "string", "Newest published build number"),
            Param("last_result", "string", "What the last check did", enum = PortalUpdatesLogic.RESULTS.sorted()),
            Param("ack", "string", "The id of the request from GET /v1/portal/snapshot → updates that was applied")),
        example = "POST /v1/portal/updates {\"prompts_enabled\":true,\"supported\":true,\"installed_build\":\"2611071200\",\"latest_build\":\"2611072030\",\"last_result\":\"prompted\",\"ack\":\"pur_1794071633482\"}",
        returns = "saved, pending (a request still waiting, or null)", errors = listOf("invalid_parameter"))

    val ALL = listOf(SNAPSHOT, HOME, DEVICES, APPLET_INVENTORY, APPLET_WATCHES, BRAND_LOGO, LOGS, AGENTS, APPLETS, THEME, PHOTO_TARGETS, PHOTO, UPDATES)
}

/**
 * Bridgit Portal (owner request 4 Nov 2026): read-only data for the owner's iPhone/iPad viewer, which reaches
 * the phone through the relay as a connector agent paired on More › Linked devices › Pair a device ([PairDeviceActivity]).
 * Always on: an agent needs the explicit `portal` grant (relay agents always have explicit grants), and every
 * call goes through the normal grant, rate-limit and audit pipeline. Read-only, with one exception (5 Nov 2026):
 * POST /v1/portal/photo hands a photo the owner took on the portal device to the phone's photo watches; the
 * phone's own camera is never used from here.
 */
class PortalModule(app: BridgeApp) : Capability(app) {
    override val id = "portal"
    override val titleRes = R.string.cap_portal
    override val descriptionRes = R.string.cap_portal_desc
    override val group = "status"
    override val endpoints = PortalEndpoints.ALL

    private val sceneHtml: String by lazy { app.assets.open("diagnostics/scene.html").bufferedReader().use { it.readText() } }
    private val nodes by lazy { PortalData.sceneNodes(sceneHtml) }
    /** Every scene node with its capabilities, "agent" included: what DiagnosticsScene reads from BridgitScene.nodes. */
    private val sceneNodeCaps by lazy { PortalData.sceneNodes(sceneHtml, includeAgent = true).map { it.id to it.caps } }

    override fun Route.install(api: ApiServer) = with(api) {
        fun Route.portal(spec: EndpointSpec, block: suspend cc.bridgit.app.api.Req.() -> Reply) = endpoint(spec) {
            agent?.let { noteClient(it.id, query("client")); PortalClients.note(app, it.id, query("client"), query("client_device"), query("client_type")) }
            block()
        }
        portal(PortalEndpoints.SNAPSHOT) {
            val n = query("logs")?.let { v -> v.toIntOrNull()?.takeIf { it in 0..50 } ?: throw ErrorCatalog.error("invalid_parameter", "logs must be 0 to 50.") } ?: 20
            Reply.Json(JSONObject().put("home", home()).put("agents", agents()).put("applets", applets()).put("logs", logs(null, n))
                .put("updates", agent?.let { PortalUpdates.pending(app, it.id)?.json() } ?: JSONObject.NULL)
                .apply { relayDailyJobs(agent)?.let { put("relay_daily_jobs", it) } })
        }
        portal(PortalEndpoints.HOME) { Reply.Json(home()) }
        portal(PortalEndpoints.UPDATES) {
            val id = agent?.id ?: throw ErrorCatalog.error("invalid_parameter", "Only a paired Portal reports its updates.")
            Reply.Json(PortalUpdates.receive(app, id, body))
        }
        portal(PortalEndpoints.DEVICES) {
            val added = runCatching { PortalData.addedDevices(app) }.getOrDefault(JSONArray())
            Reply.Json((PortalData.portalLogoUrls(cc.bridgit.app.lan.LanModule.rememberedForOwner(app)) as JSONObject).put("added", added))
        }
        portal(PortalEndpoints.APPLET_INVENTORY) {
            val (template, revision) = pairedPin(this)
            val agentId = app.applets.all().mapNotNull { PortalData.inventoryAgent(it, template, revision) }.singleOrNull()
                ?: throw ErrorCatalog.error("not_found", "Install and enable this exact dashboard edition on the selected phone.")
            val devices = cc.bridgit.app.lan.LanModule(app, "lan_control").portalInventory(agentId)
            Reply.Json(JSONObject().put("template", template).put("revision", revision).put("devices", devices))
        }
        portal(PortalEndpoints.APPLET_WATCHES) {
            val (template, revision) = pairedPin(this)
            val record = app.applets.all().singleOrNull { PortalData.matchesPin(it, template, revision) }
                ?: throw ErrorCatalog.error("not_found", "Install this exact edition on the monitoring phone.")
            val owner = app.agents.get(record.optString("agent_id"))
            if (owner == null || owner.paused || !owner.allows("watches") || record.optString("status") !in setOf("active", "paused"))
                throw ErrorCatalog.error("agent_not_allowed", "The installed applet needs an active monitoring agent with the watches grant.")
            val bindings = record.optJSONObject("watches") ?: JSONObject()
            val rows = bindings.keys().asSequence().sorted().map { key ->
                val watch = app.watches.get(owner.id, bindings.getJSONObject(key).getString("watch_id"))
                val row = JSONObject().put("key", key)
                if (watch == null) row.put("state", "ended") else {
                    row.put("state", when {
                        watch.userPaused || record.optString("status") == "paused" -> "paused"
                        app.watches.accessProblem(owner.id, watch.spec) != null -> "blocked"
                        watch.budgetPaused() -> "budget_paused"
                        else -> "scheduled"
                    }).put("checks", watch.checks).put("matches", watch.matches)
                    val reads = PortalData.watchReads(record, key)
                    val recent = synchronized(watch.recent) { watch.recent.toList() }
                    if ("status" in reads) row.put("recent_checks", JSONArray(recent.map(PortalData::checkForCard)))
                    if ("tally" in reads) app.watches.tallyOf(owner.id, watch.id, 1, null)?.let(PortalData::tallyForCard)?.let { row.put("tally",it) }
                    recent.lastOrNull()?.let { check ->
                        row.put("last_check_at", check.optString("at")).put("last_outcome", when {
                            check.has("error") -> "error"
                            check.has("skipped") -> "skipped"
                            check.optBoolean("matched") -> "match"
                            check.has("matched") -> "no_match"
                            else -> "checked"
                        })
                    }
                }
                row
            }.toList()
            Reply.Json(JSONObject().put("template", template).put("revision", revision).put("watches", JSONArray(rows)))
        }
        portal(PortalEndpoints.BRAND_LOGO) {
            cc.bridgit.app.lan.LanModule.brandLogo(app, path("id").orEmpty(), arg("raw") in listOf(1, "1", true, "true"))
        }
        portal(PortalEndpoints.LOGS) {
            val since = query("since")?.let { v -> v.toLongOrNull()?.takeIf { it >= 0 } ?: throw ErrorCatalog.error("invalid_parameter", "since must be a log id (0 or more).") }
            val limit = query("limit")?.let { v -> v.toIntOrNull()?.takeIf { it in 1..200 } ?: throw ErrorCatalog.error("invalid_parameter", "limit must be 1 to 200.") } ?: 50
            val until = query("until")?.let { v -> v.toLongOrNull()?.takeIf { it > 0 } ?: throw ErrorCatalog.error("invalid_parameter", "until must be a log id.") }
            if (since != null && until != null) throw ErrorCatalog.error("invalid_parameter", "Send since (newer entries) or until (an older page), not both.")
            Reply.Json(logs(since, limit, until))
        }
        portal(PortalEndpoints.AGENTS) { Reply.Json(agents()) }
        portal(PortalEndpoints.APPLETS) { Reply.Json(applets()) }
        portal(PortalEndpoints.THEME) { Reply.Json(theme()) }
        portal(PortalEndpoints.PHOTO_TARGETS) { Reply.Json(app.ownerPhotos.targetsJson()) }
        portal(PortalEndpoints.PHOTO) {
            val camera = body.optString("camera", "rear").ifBlank { "rear" }
            if (camera !in setOf("rear", "front")) throw ErrorCatalog.error("invalid_parameter", "camera must be rear or front.")
            val bytes = runCatching { android.util.Base64.decode(body.optString("data_base64"), android.util.Base64.DEFAULT) }.getOrNull()
                ?.takeIf { it.size > 100 } ?: throw ErrorCatalog.error("invalid_parameter", "data_base64 must be a JPEG photo.")
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ErrorCatalog.error("invalid_parameter", "data_base64 isn't a photo the phone can read.")
            val ids = body.optJSONArray("targets")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
            try {
                Reply.Json(app.ownerPhotos.submit(bytes, camera, ids, "portal", waitMs = 25_000))
            } catch (e: cc.bridgit.app.watch.OwnerPhotos.NoTargets) {
                throw ErrorCatalog.error("not_found", e.message ?: "No applet checks photos.")
            }
        }
    }

    private fun pairedPin(req: cc.bridgit.app.api.Req): Pair<String, Int> {
        val viewer = req.agent
        if (viewer?.connection != Connection.RELAY || PortalPairing.granted(app, RelayStore(app)).none { it.id == viewer.id })
            throw ErrorCatalog.error("agent_not_allowed", "Pair an owner portal device to read this applet.")
        val template = req.query("template")?.takeIf { it.isNotBlank() && it.length <= 256 }
        val revision = req.query("revision")?.toIntOrNull()?.takeIf { it > 0 }
        if (template == null || revision == null) throw ErrorCatalog.error("invalid_parameter", "Supply a registry template and positive revision.")
        return template to revision
    }

    /**
     * How many relay jobs a day the calling agent gets: the phone tells the relay which agents are Bridgit Portal
     * pairings (RelayClient's agents frame), and the relay gives each of those 20,000; the phone's other agents share
     * 12,000 by default (a plan can change it; agents read the live figure from the relay's GET /quota). Null off the relay.
     */
    private fun relayDailyJobs(a: cc.bridgit.app.core.Agent?): Int? {
        if (a == null || a.connection != Connection.RELAY) return null
        return if (PortalPairing.paired(app, RelayStore(app)).any { it.id == a.id }) RELAY_PORTAL_DAILY_JOBS else RELAY_DAILY_JOBS
    }

    // ---- Home ----

    /** This phone's own Home summary, as a portal reads it: Linked devices' sheet for this phone shows the same. */
    internal fun ownHome(): JSONObject = home()

    private fun home(): JSONObject {
        val bat = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0) l * 100 / s else null }
        val battery = JSONObject().put("percent", level ?: JSONObject.NULL)
            .put("charging", (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
        val device = JSONObject().put("name", PortalPairing.phoneName(app)).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE).put("api_level", Build.VERSION.SDK_INT).put("app_version", app.appVersion())
            .put("type", app.remoteView.deviceType()).put(cc.bridgit.app.platform.DeviceType.FIELD, cc.bridgit.app.platform.DeviceType.of(app).wire)
        fun on(b: Boolean) = if (b) "connected" else "disconnected"
        val connections = JSONObject().put("tailscale", on(HomeStates.tailscaleAddress(app) != null))
            .put("adb", on(app.adb.connected))
            .put("relay", on(RelayStore(app).enabled && BridgeService.running?.relay?.status == "Connected"))
            .put("wifi", on(wifi()))
        val features = JSONArray(nodes.map { n ->
            val state = when (n.id) {
                "tailscale" -> if (HomeStates.tailscaleAddress(app) != null) "on" else "off"
                "clef" -> HomeStates.capState(app, listOf("watches"))
                else -> HomeStates.capState(app, n.caps)
            }
            JSONObject().put("id", n.id).put("name", n.label).put("title", n.name).put("group", n.group).put("icon", n.icon)
                .put("state", state).apply { if (n.why.isNotEmpty()) put("summary", n.why) }
        })
        return JSONObject().put("device", device).put("battery", battery).put("connections", connections)
            .put("paused", ListenerCard.isPaused(app))
            .put("features", features).put("listening", JSONArray(HomeStates.listening(app)))
            .put("agents_count", HomeStates.controlAgents(app).size).put("nav", nav())
            // Exactly what the phone's Home feeds its scene (assets/diagnostics/scene.html), so a viewer can draw the same Home.
            .put("scene", JSONObject().put("options", HomeStates.options(app, sceneNodeCaps))
                .put("paused", ListenerCard.isPaused(app)).put("listening", JSONArray(HomeStates.listening(app)))
                .put("home_status", HomeStates.homeStatus(app)))
    }

    private fun wifi(): Boolean = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true }
    }.getOrDefault(false)

    /** The APK's bottom navigation, then its More list, in order, with Material symbol names for their icons. */
    private fun nav(): JSONArray {
        val out = JSONArray()
        fun add(id: String, title: Int, icon: String, section: String) =
            out.put(JSONObject().put("id", id).put("title", app.getString(title)).put("icon", icon).put("section", section))
        add("interactions", R.string.nav_interactions, "home", "tab")
        add("home", R.string.nav_home, "smartphone", "tab")
        add("camera", R.string.nav_camera, "photo_camera", "tab")
        add("agents", R.string.nav_agents, "group", "tab")
        add("more", R.string.nav_more, "menu", "tab")
        add("check_updates", R.string.more_check_updates, "download", "more")
        add("cameras", R.string.nav_cameras, "lan", "more")
        add("activity", R.string.more_activity, "show_chart", "more")
        add("features", R.string.more_features, "toggle_on", "more")
        add("access", R.string.more_access, "verified_user", "more")
        add("software_access", R.string.more_software_access, "verified_user", "more")
        add("ssh_connections", R.string.ssh_title, "laptop", "more")
        add("network_devices", R.string.network_devices_title, "laptop", "more")
        add("tailscale_setup", R.string.more_tailscale_setup, "verified_user", "more")
        if (BuildConfig.PRIVATE_CONTROL) add("adb_setup", R.string.more_adb_setup, "settings", "more")
        add("relay", R.string.more_relay, "verified_user", "more")
        add("portal", R.string.more_portal, "laptop", "more")
        add("control_center", R.string.more_control_center, "settings", "more")
        if (cc.bridgit.app.feedback.FeedbackUploader.configured) add("link_web_view", R.string.more_link_web_view, "laptop", "more")
        add("settings", R.string.more_settings, "settings", "more")
        add("report_bug", R.string.more_report_bug, "bug_report", "more")
        add("request_feature", R.string.more_request_feature, "lightbulb", "more")
        add("my_reports", R.string.more_my_reports, "history", "more")
        add("about", R.string.more_about, "info", "more")
        add("what_can", R.string.more_what_can, "lightbulb", "more")
        return out
    }

    // ---- Logs ----

    /**
     * Agent activity after [since] (oldest first), or the newest [limit] entries before [until] (or overall) without it,
     * also oldest first. Rows remote view wouldn't show are skipped; next_since moves past them so the client never
     * stalls on a run of them, and oldest_id is where the next older page starts (pass it as until).
     */
    internal fun logs(since: Long?, limit: Int, until: Long? = null): JSONObject {
        val names = app.agents.all().associate { it.id to it.name }
        val latest = app.log.latestId.value
        fun entry(e: cc.bridgit.app.log.LogEntry): JSONObject? = RemoteViewLines.line(e, e.agentId?.let(names::get))
            ?.let { PortalData.logEntry(it, e.channel, e.level, e.result ?: "ok") }
        val entries = JSONArray()
        var next = since ?: 0L
        var more = false
        var olderFrom: Long? = null
        var hasOlder = false
        if (limit == 0) return JSONObject().put("entries", entries).put("next_since", latest).put("has_more", false)
            .put("has_older", false).put("latest_id", latest)
        if (since != null) {
            var cursor = since
            scan@ for (round in 0 until 5) {
                val rows = app.log.query(LogQuery(sinceId = cursor, ascending = true, limit = SCAN))
                for (e in rows) {
                    if (entries.length() >= limit) { more = true; break@scan }
                    next = e.id
                    entry(e)?.let { entries.put(it) }
                }
                if (rows.size < SCAN) break
                cursor = rows.last().id
                if (round == 4) more = true
            }
        } else {
            // Newest first, page by page: the portal's own polls are logged too and would fill a single page.
            val picked = mutableListOf<JSONObject>()
            var cursor: Long? = until
            next = latest
            scan@ for (round in 0 until 5) {
                val rows = app.log.query(LogQuery(untilId = cursor, limit = SCAN))
                for (e in rows) {
                    if (picked.size >= limit) { hasOlder = true; break@scan }
                    olderFrom = e.id
                    entry(e)?.let { picked += it }
                }
                if (rows.size < SCAN) break
                cursor = rows.last().id
                if (round == 4) hasOlder = true
            }
            picked.asReversed().forEach { entries.put(it) }
        }
        return JSONObject().put("entries", entries).put("next_since", next).put("has_more", more)
            .put("has_older", hasOlder).put("oldest_id", olderFrom ?: JSONObject.NULL).put("latest_id", latest)
    }

    // ---- Agents and applets ----

    private fun agents(): JSONObject {
        val store = RelayStore(app)
        val relay = store.agents().associateBy { it.getString("id") }
        val portalIds = PortalPairing.paired(app, store).map { it.id }.toSet()
        val all = app.registry.modules.map { it.id }.sorted()
        return JSONObject().put("agents", JSONArray(app.agents.all().sortedBy { it.createdAt }.map { a ->
            JSONObject().put("id", a.id).put("name", a.name)
                .put("mode", if (a.connection == Connection.RELAY) relay[a.id]?.optString("mode")?.ifEmpty { null } ?: "connector" else "direct")
                .put("transport", a.connection.id).put("last_used_at", a.lastUsedAt).put("paused", a.paused)
                .put("all_access", a.caps == null).put("portal", a.id in portalIds)
                .put("grants", JSONArray((a.caps?.toList() ?: all).sorted()))
                .apply { a.model?.let { put("model", it) } }
        }))
    }

    private fun applets(): JSONObject = JSONObject().put("applets", JSONArray().also { out ->
        val list = app.remoteView.applets()
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            out.put(JSONObject().put("id", a.getString("id")).put("name", a.getString("name")).put("kind", a.getString("kind"))
                .put("running", a.optBoolean("running")).put("last_at", a.optLong("last_at"))
                .put("sensors", a.optJSONArray("sensors") ?: JSONArray()).put("actions", a.optJSONArray("actions") ?: JSONArray())
                .apply { appletSummary(a)?.let { put("summary", it) } })
        }
    })

    private fun appletSummary(a: JSONObject): String? = when (a.optString("kind")) {
        "dog" -> if (a.optBoolean("running")) "Watching" else null
        "tour" -> if (a.optBoolean("running")) "On tour" else "Paused"
        "watch" -> if (a.optBoolean("running")) "Watching" else "Paused"
        "package" -> if (a.optBoolean("running")) "Watching" else "Paused"
        else -> null
    }

    // ---- Theme ----

    private fun colors(night: Boolean): JSONObject {
        val cfg = Configuration(app.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        val ctx = app.createConfigurationContext(cfg)
        val out = JSONObject()
        COLORS.forEach { (name, res) -> out.put(name, PortalData.hex(ContextCompat.getColor(ctx, res))) }
        return out
    }

    private fun theme(): JSONObject {
        val tokens = PortalData.sceneTokens(sceneHtml)
        fun sceneColors(m: Map<String, String>?) = JSONObject().also { o -> m?.filterValues { it.startsWith("#") }?.forEach { (k, v) -> o.put(k, v.uppercase()) } }
        val dark = tokens["dark"].orEmpty()
        val motion = JSONObject()
        PortalData.MOTION.forEach { (name, ms, easing) -> motion.put(name, JSONObject().put("duration_ms", ms).put("easing", easing)) }
        return JSONObject()
            .put("colors", JSONObject().put("light", colors(false)).put("dark", colors(true)))
            .put("scene", JSONObject().put("light", sceneColors(tokens["light"])).put("dark", sceneColors(dark))
                .put("groups", JSONObject().put("Senses", "senses").put("Awareness", "aware").put("Controls", "controls")
                    .put("Talks With You", "you").put("Intelligence", "intel").put("Information", "intel").put("Network", "ink")))
            .put("corner_radius", JSONObject().put("card", 12).put("hero_card", 24).put("panel", 16).put("bottom_sheet", 28).put("mono_chip", 4).put("unit", "dp"))
            .put("fonts", JSONObject().put("body", "sans-serif (Roboto)").put("heading", "sans-serif-medium (Roboto Medium)")
                .put("mono", "monospace").put("scene_display", dark["display"] ?: "").put("scene_body", dark["body"] ?: "").put("scene_mono", dark["mono"] ?: ""))
            .put("motion", motion)
            .put("animations", PortalData.animations())
    }

    companion object {
        private const val SCAN = 200
        /** The relay Worker's default daily job quotas (bridgit-cloud/relay/src/worker.mjs AGENT_DAILY_JOBS, shared by a phone's agents; PORTAL_DAILY_JOBS, per viewer). */
        const val RELAY_DAILY_JOBS = 12_000
        const val RELAY_PORTAL_DAILY_JOBS = 20_000

        /** The device each portal agent last said it is (query client=…). In memory: it only labels Home's viewer. */
        private val clients = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val CLIENT_RE = Regex("[A-Za-z0-9 ._-]{1,24}")
        fun noteClient(agentId: String, raw: String?) { raw?.trim()?.takeIf { CLIENT_RE.matches(it) }?.let { clients[agentId] = it } }
        fun clientOf(agentId: String): String? = clients[agentId]

        /** The APK's colour resources (values/ and values-night/). PortalDataTest checks every b_* is here. */
        val COLORS: List<Pair<String, Int>> = listOf(
            "b_primary" to R.color.b_primary, "b_on_primary" to R.color.b_on_primary,
            "b_primary_container" to R.color.b_primary_container, "b_on_primary_container" to R.color.b_on_primary_container,
            "b_secondary" to R.color.b_secondary, "b_on_secondary" to R.color.b_on_secondary,
            "b_secondary_container" to R.color.b_secondary_container, "b_on_secondary_container" to R.color.b_on_secondary_container,
            "b_surface" to R.color.b_surface, "b_surface_container" to R.color.b_surface_container,
            "b_surface_container_high" to R.color.b_surface_container_high, "b_surface_container_low" to R.color.b_surface_container_low,
            "b_surface_container_highest" to R.color.b_surface_container_highest, "b_surface_container_lowest" to R.color.b_surface_container_lowest,
            "b_on_surface" to R.color.b_on_surface, "b_on_surface_variant" to R.color.b_on_surface_variant,
            "b_outline" to R.color.b_outline, "b_outline_variant" to R.color.b_outline_variant,
            "b_error" to R.color.b_error, "b_on_error" to R.color.b_on_error,
            "b_error_container" to R.color.b_error_container, "b_on_error_container" to R.color.b_on_error_container,
            "b_warning" to R.color.b_warning, "b_success" to R.color.b_success, "b_success_container" to R.color.b_success_container,
            "b_on_success_container" to R.color.b_on_success_container,
            "brand_primary" to R.color.brand_primary, "beacon_gold" to R.color.beacon_gold,
            "design_info_start" to R.color.design_info_start, "design_info_end" to R.color.design_info_end,
            "design_info_edge" to R.color.design_info_edge, "design_rule_start" to R.color.design_rule_start,
            "design_rule_end" to R.color.design_rule_end, "design_screen_glow" to R.color.design_screen_glow,
            "network_category_start" to R.color.network_category_start, "network_category_end" to R.color.network_category_end,
            "network_category_text" to R.color.network_category_text,
            "avatar_bg_0" to R.color.avatar_bg_0, "avatar_fg_0" to R.color.avatar_fg_0, "avatar_bg_1" to R.color.avatar_bg_1, "avatar_fg_1" to R.color.avatar_fg_1,
            "avatar_bg_2" to R.color.avatar_bg_2, "avatar_fg_2" to R.color.avatar_fg_2, "avatar_bg_3" to R.color.avatar_bg_3, "avatar_fg_3" to R.color.avatar_fg_3,
            "avatar_bg_4" to R.color.avatar_bg_4, "avatar_fg_4" to R.color.avatar_fg_4, "avatar_bg_5" to R.color.avatar_bg_5, "avatar_fg_5" to R.color.avatar_fg_5,
            "avatar_bg_6" to R.color.avatar_bg_6, "avatar_fg_6" to R.color.avatar_fg_6, "avatar_bg_7" to R.color.avatar_bg_7, "avatar_fg_7" to R.color.avatar_fg_7,
        )
    }
}
