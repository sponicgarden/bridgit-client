package cc.bridgit.app.capability

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Base64
import io.ktor.server.routing.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.Address
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Guide
import cc.bridgit.app.api.OpenApi
import cc.bridgit.app.api.Reply
import cc.bridgit.app.api.Req
import cc.bridgit.app.api.Sizing
import cc.bridgit.app.log.LogEntry
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.log.Percentile
import cc.bridgit.app.log.QueryError
import cc.bridgit.app.log.Redactor
import org.json.JSONArray
import org.json.JSONObject

/** The phone's address as agents should use it. */
fun BridgeApp.address(): Address {
    val s = listener.value
    return Address(s.magicDns, s.boundTo.firstOrNull { !it.contains(':') } ?: s.boundTo.firstOrNull(),
        if (s.port > 0) s.port else settings.port)
}

fun BridgeApp.appVersion(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

/** Base64 body with the 12 MB cap (API-11). */
internal fun Req.envelope(img: Encoded, at: String, atKey: String, photoId: String?): JSONObject {
    val b64 = Base64.encodeToString(img.bytes, Base64.NO_WRAP)
    if (b64.length > ApiServer.MAX_PAYLOAD) throw ErrorCatalog.error("payload_too_large",
        "The result is over 12 MB as base64. Ask for a smaller size, or add raw=1.",
        if (photoId != null) mapOf("photo_id" to photoId) else emptyMap())
    bytesOut = img.bytes.size.toLong()
    val fmt = when (img.mime) { "image/jpeg" -> "jpeg"; "image/png" -> "png"; "image/heic", "image/heif" -> "heic"; else -> img.mime.substringAfter('/') }
    return JSONObject().put(atKey, at).put("width_px", img.width).put("height_px", img.height)
        .put("format", fmt).put("mime", img.mime).put("size_bytes", img.bytes.size).put("data_base64", b64)
}

/** X-Bridgit-Capture-Id and X-Bridgit-Stored for a raw reply whose capture was kept (SEC-13, D33). */
internal fun keptHeaders(kept: JSONObject?): Map<String, String> = if (kept == null) emptyMap() else mapOf(
    "X-Bridgit-Capture-Id" to kept.getString("capture_id"),
    "X-Bridgit-Stored" to kept.getJSONArray("stored").let { a -> (0 until a.length()).joinToString(",") { a.getString(it) } })

internal fun Req.wantsRaw(): Boolean = arg("raw").let { it == "1" || it == "true" || it == 1 || it == true }

internal fun Req.rawReply(img: Encoded, headers: Map<String, String>): Reply {
    if (img.bytes.size > ApiServer.MAX_RAW) throw ErrorCatalog.error("payload_too_large", "The image is over 40 MB. Ask for a smaller size.")
    bytesOut = img.bytes.size.toLong()
    return Reply.Bytes(img.bytes, img.mime, headers + mapOf("X-Bridgit-Width" to img.width.toString(), "X-Bridgit-Height" to img.height.toString()))
}

// ---------------------------------------------------------------------------------------------

/** 8.1: health, capabilities, guide, openapi.json, the helper download and pairing by code. Always on. */
class StatusModule(app: BridgeApp) : Capability(app) {
    override val id = "status"
    override val titleRes = R.string.cap_status
    override val descriptionRes = R.string.cap_status_desc
    override val group = "status"
    override val endpoints = listOf(Endpoints.SERVICES, Endpoints.SPEECH_MODELS, Endpoints.HEALTH, Endpoints.DEVICE_DISPLAY, Endpoints.DEVICES, Endpoints.CAPABILITIES, Endpoints.LIMITS, Endpoints.DISCOVER, Endpoints.DISCOVERY_REPORT, Endpoints.GUIDE, Endpoints.START, Endpoints.DEMO, Endpoints.SAMPLES, Endpoints.OPENAPI, Endpoints.REGISTRY, Endpoints.CHANGES, Endpoints.HELPER, Endpoints.HELPER_LEGACY, Endpoints.PAIR, Endpoints.MCP, Endpoints.ME, Endpoints.OWNER_EMAIL, Endpoints.OWNER_EMAIL_SET) + cc.bridgit.app.satellite.SatelliteEndpoints.ALL

    /** GET /v1/owner/email and the health block: where the owner's email verification stands. */
    private fun ownerEmailJson(): JSONObject {
        val v = app.alerts.gate()
        val ends = v.graceEndsAt
        return JSONObject().put("status", v.status.name.lowercase()).put("state", v.state)
            .put("email", cc.bridgit.app.alerts.EmailGate.mask(app.settings.alertEmail) ?: JSONObject.NULL)
            .put("grace_ends_at", ends?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
            .put("hours_left", if (v.status == cc.bridgit.app.alerts.EmailGate.Status.VERIFIED) JSONObject.NULL else Math.ceil(v.remainingMs / 3_600_000.0).toInt())
            .put("required_after_hours", cc.bridgit.app.alerts.EmailGate.GRACE_MS / 3_600_000)
            .put("how", when (v.status) {
                cc.bridgit.app.alerts.EmailGate.Status.VERIFIED -> "Verified. Nothing to do."
                else -> if (v.state == "pending") "Ask the owner to tap the link in the email from bridgit.cc. To send a new one, POST /v1/owner/email again."
                    else "Ask the owner for their email address and send it with POST /v1/owner/email {\"email\":\"...\"}."
            })
    }

    /** Reading the guide, openapi.json or the changes means the agent now knows the current API (API-14). */
    private fun markKnown(r: Req) {
        val a = r.agent ?: return
        if (a.knownApiRevision < cc.bridgit.app.api.ApiChanges.CURRENT)
            app.agents.update(a.id) { it.copy(knownApiRevision = cc.bridgit.app.api.ApiChanges.CURRENT) }
    }

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.SPEECH_MODELS) { Reply.Json(cc.bridgit.app.speech.SpeechEngine.readiness(app)) }
        endpoint(Endpoints.DEVICES) {
            val allowed = agent?.allows("lan_camera") == true
            val inventory = cc.bridgit.app.lancamera.DeviceInventory.json(android.os.Build.MODEL,
                if (allowed) app.lanCameras.all() else emptyList(), allowed, app.settings.isOn("lan_camera"),
                agent?.allows("lan_camera_audio")==true && app.settings.isOn("lan_camera_audio"),
                agent?.allows("lan_camera_speaker")==true && app.settings.isOn("lan_camera_speaker"))
            run {
                val d = agent?.allows("lan_discovery") == true; val e = agent?.allows("lan_enrollment") == true; val c = agent?.allows("lan_control") == true
                val n = if (c && app.settings.isOn("lan_control")) app.lanDevices.all().count { it.agentId == agent!!.id } else 0
                inventory.put("lan_path", cc.bridgit.app.lancamera.DeviceInventory.lanPath(d, d && app.settings.isOn("lan_discovery"), e, e && app.settings.isOn("lan_enrollment"), c, c && app.settings.isOn("lan_control"), n))
            }
            if (agent?.allows("lan_control") == true && app.settings.isOn("lan_control")) {
                app.lanDevices.all().filter { it.agentId == agent!!.id }.forEach { inventory.getJSONArray("devices").put(it.publicJson()) }
            }
            if (agent?.allows("ssh") == true && app.settings.isOn("ssh")) {
                app.sshProfiles.all().filter { it.agentId == agent!!.id }.forEach { inventory.getJSONArray("devices").put(it.publicJson().put("type", "ssh")) }
            }
            // Satellites this agent may use (docs/spec-satellites.md, SAT-20).
            agent?.let { ag -> app.satellites.all().filter { ag.id in it.agents }.forEach {
                inventory.getJSONArray("devices").put(cc.bridgit.app.satellite.SatelliteEndpoints.publicJson(it).put("connection", "satellite")) } }
            Reply.Json(inventory)
        }
        endpoint(Endpoints.DEVICE_DISPLAY) {
            val g = cc.bridgit.app.interact.DisplayInfo.read(app)
            Reply.Json(cc.bridgit.app.interact.CardViewport.displayJson(g, cc.bridgit.app.interact.DisplayInfo.standard(g)))
        }
        endpoint(Endpoints.SERVICES) {
            Reply.Json(cc.bridgit.app.judgment.ServiceCatalog.json(cc.bridgit.app.feedback.FeedbackUploader.configured, app.settings.externalCallsPaused))
        }
        endpoint(Endpoints.HEALTH) {
            val bat = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bat?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0) l * 100 / s else null }
            val s = app.listener.value
            Reply.Json(JSONObject().put("status", "ok").put("app", "Bridgit").put("app_version", app.appVersion())
                // A0093: which build answers (several can share a phone), the same name Android lists it under.
                .put("build_type", cc.bridgit.app.BuildConfig.BUILD_TYPE).put("distribution", cc.bridgit.app.BuildConfig.FLAVOR)
                .put("build_tag", cc.bridgit.app.BuildConfig.BUILD_TAG.ifEmpty { null } ?: JSONObject.NULL)
                .put("package", app.packageName).put("app_label", app.getString(cc.bridgit.app.R.string.app_name)).put("mcp","/v1/mcp").put("agent_control",JSONObject().put("about","Second MCP server and /v2 routes for the agent control tools (using apps on screen, workflows, OCR and more): ${cc.bridgit.app.api.Learn.CONTROL_URL}").put("capabilities","/v2/capabilities").put("tools","/v2/tools").put("mcp","/mcp").put("mode",if(cc.bridgit.app.BuildConfig.PRIVATE_CONTROL) "private" else "limited"))
                .put("agent", agentName).put("agent_id", agent?.id)
                .put("model", agent?.let { app.agents.get(it.id) }?.model ?: JSONObject.NULL)
                .put("listener_uptime_seconds", (System.currentTimeMillis() - s.startedAt) / 1000)
                .put("battery_level", level ?: JSONObject.NULL)
                .put("battery_runtime", cc.bridgit.app.power.Battery.runtimeReport(app, bat))
                .put("charging", (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
                .put("bind_address", s.boundTo.firstOrNull() ?: JSONObject.NULL).put("port", api.port)
                .put("base_url", app.address().baseUrl)
                .put("bridgit_name", cc.bridgit.app.feedback.DeviceNames.hint(app, agent?.id))
                .put("owner_email", ownerEmailJson())
                .put("api_revision", cc.bridgit.app.api.ApiChanges.CURRENT)
                .put("whats_new", cc.bridgit.app.api.ApiChanges.whatsNew(agent?.let { app.agents.get(it.id) }?.knownApiRevision ?: 0))
                .put("links", cc.bridgit.app.api.ApiChanges.links())
                .put("display", runCatching { cc.bridgit.app.interact.CardViewport.healthJson(cc.bridgit.app.interact.DisplayInfo.read(app)) }.getOrDefault(JSONObject.NULL)))
        }
        endpoint(Endpoints.ME) {
            val model = body.optString("model", "").trim()
            val harness = body.optString("harness", "").trim().ifEmpty { null }
            if (!cc.bridgit.app.core.validModel(model))
                throw ErrorCatalog.error("invalid_parameter", "model must be 1-60 characters, e.g. claude-opus-5-5.")
            if (harness != null && !cc.bridgit.app.core.validHarness(harness))
                throw ErrorCatalog.error("invalid_parameter", "harness must be 1-40 characters, e.g. Claude Code.")
            val a = app.agents.update(agent!!.id) { it.copy(model = model, harness = harness, modelReportedAt = System.currentTimeMillis()) }!!
            if (agent!!.model != model || agent!!.harness != harness)
                app.log.system("audit", "agent", "${a.name} says it runs on ${a.modelLabel}")
            Reply.Json(JSONObject().put("agent", a.name).put("model", model).put("harness", harness ?: JSONObject.NULL))
        }
        endpoint(Endpoints.OWNER_EMAIL) { Reply.Json(ownerEmailJson()) }
        endpoint(Endpoints.OWNER_EMAIL_SET) {
            val email = body.optString("email", "").trim().lowercase()
            if (email.isEmpty() || email.length > 254 || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches())
                throw ErrorCatalog.error("invalid_parameter", "email must be an email address, e.g. owner@example.com.")
            if (app.alerts.gate().status == cc.bridgit.app.alerts.EmailGate.Status.VERIFIED)
                throw ErrorCatalog.error("conflict", "The owner already verified an email address. Only the owner can change it, on the phone in Settings › Email alerts.")
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.alerts.subscribe(email, agentName) }
            when (r) {
                cc.bridgit.app.alerts.AlertRelay.Subscribe.PENDING, cc.bridgit.app.alerts.AlertRelay.Subscribe.CONFIRMED -> {}
                cc.bridgit.app.alerts.AlertRelay.Subscribe.BAD_EMAIL -> throw ErrorCatalog.error("invalid_parameter", "bridgit.cc didn't accept that address. Check it with the owner.")
                cc.bridgit.app.alerts.AlertRelay.Subscribe.RATE_LIMITED -> {
                    // bridgit.cc names the limit and the real wait; a fixed 300 s sent agents retrying against a daily cap (A0028).
                    val l = app.alerts.relay.lastRateLimit
                    throw ErrorCatalog.error("rate_limited", l?.message ?: "Confirmation-email requests are rate limited (seven per address per 24 hours, five per network per hour, and at least five minutes apart). No new email was sent.",
                        mapOf("retry_after_s" to (l?.retryAfterS ?: cc.bridgit.app.alerts.EmailVerificationRules.DEFAULT_RETRY_AFTER_S)) + (l?.limit?.let { mapOf("limit" to it) } ?: emptyMap()))
                }
                cc.bridgit.app.alerts.AlertRelay.Subscribe.FAILED -> throw ErrorCatalog.error("upstream_failed", "bridgit.cc couldn't send the confirmation email. Retry in a few minutes.", mapOf("service" to "bridgit.cc"))
            }
            Reply.Json(ownerEmailJson().put("next", if (r == cc.bridgit.app.alerts.AlertRelay.Subscribe.CONFIRMED) "This device already verified that address. No email was sent; nothing to do." else "Tell the owner: an email from bridgit.cc is on its way to ${cc.bridgit.app.alerts.EmailGate.mask(email)}. Tap the link in it and press Confirm (check spam). The link works for 30 days."))
        }
        endpoint(Endpoints.DISCOVER, admit = { _, a -> cc.bridgit.app.api.CapabilityCatalog.admit(app.requests.limiter, a!!.id, false) }) {
            val started = android.os.SystemClock.elapsedRealtime()
            val q = query("q") ?: ""
            val limit = query("limit")?.toIntOrNull() ?: if (query("limit") == null) 5 else 0
            val detail = query("detail") ?: "summary"
            if (q.length > 256 || limit !in 1..20 || detail !in setOf("summary", "full"))
                throw ErrorCatalog.error("invalid_parameter", "q <=256 characters, limit 1..20, detail summary or full required.")
            val states = app.registry.modules.associate { m ->
                val reason = m.blockedReason()
                val st = cc.bridgit.app.api.CapInfo(m.id, m.title, m.switchKey, m.enabled, reason == null, reason)
                val granted = agent?.allows(m.id) == true
                "v1.${m.id}" to cc.bridgit.app.api.CapabilityCatalog.moduleState(st, granted)
            }
            val out = cc.bridgit.app.api.CapabilityCatalog.search(q, query("id"), limit, detail == "full", cc.bridgit.app.api.ApiChanges.CURRENT, states)
            val exact = query("id")?.let { id -> cc.bridgit.app.api.CapabilityCatalog.entries.find { it.id == id } }
            val surface = if (query("id") == null) "catalog" else if (exact?.kind == "recipe") "recipes" else "capability"
            app.usage.count("discovery:v1:request:$surface")
            if (out.getJSONArray("results").length() == 0) app.usage.count("discovery:v1:zero:$surface")
            val ms = android.os.SystemClock.elapsedRealtime() - started
            val bucket = when { ms <= 100 -> "le100"; ms <= 500 -> "le500"; ms <= 2000 -> "le2000"; else -> "gt2000" }
            app.usage.count("discovery:v1:latency:$surface:$bucket")
            Reply.Json(out)
        }
        endpoint(Endpoints.DISCOVERY_REPORT, admit = { _, a -> cc.bridgit.app.api.CapabilityCatalog.admit(app.requests.limiter, a!!.id, true) }) {
            val keys = try { cc.bridgit.app.api.CapabilityCatalog.reportKeys(body) }
                catch (_: IllegalArgumentException) { throw ErrorCatalog.error("invalid_parameter", "Use a documented discovery event, exact catalog ID and fixed enum value; no extra fields.") }
            keys.forEach { app.usage.count(it) }
            Reply.Json(JSONObject().put("accepted", true).put("measurement", "self_report")
                .put("counting_enabled", app.usage.enabled()).put("verified", false))
        }
        endpoint(Endpoints.CAPABILITIES) {
            val out = JSONObject()
            app.registry.modules.filter { it.switchKey != null }.forEach { m ->
                val st = m.status()
                out.put(m.id, JSONObject().put("title", st.title).put("enabled", st.enabled).put("ready", st.ready)
                    .put("blocked_reason", if (agent?.allows(m.id) == false) "agent_not_allowed" else st.blockedReason ?: JSONObject.NULL).put("agent_allowed", agent?.allows(m.id) ?: false).put("details", st.details))
            }
            // Features with no on/off switch but with options and limits to learn (watches: paid vs local, captures).
            app.registry.modules.filter { it.switchKey == null }.forEach { m ->
                val st = m.status()
                if (st.details.length() == 0) return@forEach
                out.put(m.id, JSONObject().put("title", st.title).put("enabled", true).put("ready", st.ready).put("switch", false)
                    .put("blocked_reason", if (agent?.allows(m.id) == false) "agent_not_allowed" else st.blockedReason ?: JSONObject.NULL).put("agent_allowed", agent?.allows(m.id) ?: false).put("details", st.details))
            }
            // EVT-05: whether the caller has a callback, and its host only (never the URL or secret).
            val cb = agent?.let { app.callbacks.get(it.id) }
            Reply.Json(JSONObject().put("services_url", "/v1/services").put("capabilities", out).put("notifications", app.captureNotificationsOn())
                .put("callback", JSONObject().put("set", cb != null).put("host", cb?.host ?: JSONObject.NULL))
                .put("listener", JSONObject().put("camera_ready", app.listener.value.cameraReady).put("mic_ready", app.listener.value.micReady)))
        }
        endpoint(Endpoints.LIMITS) {
            val trust = runCatching { cc.bridgit.app.feedback.InstallIdentity.status(app)?.trust }.getOrNull()
            val account = cc.bridgit.app.api.AccountLimits.account(app, refresh = query("refresh") == "true")
            Reply.Json(JSONObject().put("account", account).put("phone", cc.bridgit.app.api.AccountLimits.phone(trust))
                .put("watch_paid_checks_per_hour_in_effect", cc.bridgit.app.api.AccountLimits.watchPaidChecksPerHour(trust))
                .put("relay", "Over Bridgit's relay, the phone's agents share a daily job pool set by the owner's plan: read the X-Relay-Quota-Limit, -Remaining and -Reset headers on each /jobs reply, or GET <relay address>/quota (uses no job). Tailscale has no daily job pool."))
        }
        endpoint(Endpoints.REGISTRY) {
            val titles = app.registry.modules.associate { it.id to it.title }
            val specs = Endpoints.all.filterNot { cc.bridgit.app.BuildConfig.PLAY_STORE && it.capability in Registry.PLAY_EXCLUDED }
            Reply.Json(cc.bridgit.app.api.ApiAccess.registry(cc.bridgit.app.api.ApiAccess.groups(specs, titles, cc.bridgit.app.platform.Commands.all), agent))
        }
        endpoint(Endpoints.CHANGES) {
            val since = query("since")?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: throw ErrorCatalog.error("invalid_parameter", "since must be an API revision number, e.g. 2.") } ?: 0
            markKnown(this)
            Reply.Json(cc.bridgit.app.api.ApiChanges.json(since, app.appVersion()))
        }
        endpoint(Endpoints.GUIDE) {
            markKnown(this)
            Reply.Text(Guide.build(agentName, app.appVersion(), app.address(), app.registry.caps(), app.settings.logVisibility,
                agent?.superPowers == true) { agent?.allows(it) ?: false }, "text/markdown; charset=utf-8")
        }
        endpoint(Endpoints.START) { Reply.Text(cc.bridgit.app.api.CapabilityCatalog.start(), "text/markdown; charset=utf-8") }
        endpoint(Endpoints.DEMO) {
            val tour = runCatching { app.assets.open(cc.bridgit.app.api.Demos.TOUR_ASSET).bufferedReader().use { it.readText() } }.getOrNull()
            Reply.Text(cc.bridgit.app.api.Demos.build(agentName, app.registry.caps(), tour) { agent?.allows(it) ?: false },
                "text/markdown; charset=utf-8")
        }
        endpoint(Endpoints.SAMPLES) {
            val file = runCatching { app.assets.open(cc.bridgit.app.api.Samples.ASSET).bufferedReader().use { it.readText() } }.getOrNull()
                ?: throw ErrorCatalog.error("not_found", "This build has no samples; see ${cc.bridgit.app.api.Samples.URL}.")
            Reply.Json(cc.bridgit.app.api.Samples.select(file, query("id"), query("kind")))
        }
        endpoint(Endpoints.OPENAPI) { markKnown(this); Reply.Json(OpenApi.build(app.appVersion(), app.address())) }
        // API-15: JSON-RPC in, each tools/call dispatched in-process to the endpoint's own handler.
        endpoint(Endpoints.MCP) { api.mcp.handle(this) }
        with(api.mcp) { installExtras() }
        val helper: suspend Req.() -> Reply = {
            val src = app.assets.open("bridgit-helper.py").use { it.readBytes() }
            Reply.Bytes(src, "text/x-python; charset=utf-8", mapOf("Content-Disposition" to "attachment; filename=\"bridgit\""))
        }
        endpoint(Endpoints.HELPER, block = helper)
        endpoint(Endpoints.HELPER_LEGACY, block = helper)
        cc.bridgit.app.satellite.SatelliteEndpoints.install(this@install, api, app)
        endpoint(Endpoints.PAIR) {
            if (!app.pairing.isOpen()) throw ErrorCatalog.error("pairing_closed", "No pairing window is open. Ask the owner to open one (Add agent, then Pair with code).")
            val w = app.pairing.redeem(body.optString("code", ""))
                ?: run { api.recordAuthFailure(source); throw ErrorCatalog.error("unauthorized", "Wrong pairing code.") }
            message = "${w.agentName} fetched its key with a pairing code"
            data = JSONObject().put("agent_id", w.agentId)
            Reply.Json(JSONObject().put("key", w.key).put("agent", w.agentName).put("base_url", app.address().baseUrl))
        }
    }
}

// ---------------------------------------------------------------------------------------------

/** Readiness shared by the capture modules: permission, notifications (PRD-05), service type (ARC-03). */
private fun Capability.captureBlocked(app: BridgeApp, permission: String, ready: Boolean): String? = when {
    !app.granted(permission) -> "permission:" + permission.substringAfterLast('.')
    !app.captureNotificationsOn() -> "notifications_off"
    !app.listener.value.running -> "listener_off"
    !ready -> "open_app_once"
    else -> null
}

private suspend fun <T> ApiServer.withSensor(lock: Mutex, note: String, block: suspend () -> T): T {
    if (!lock.tryLock()) throw ErrorCatalog.error("sensor_busy", "A capture is already running. Retry in a few seconds.")
    val token = service.captureStarted(note)
    try {
        return block()
    } finally {
        service.captureEnded(token)
        lock.unlock()
    }
}

/** 8.2 Camera photo. */
class CameraModule(app: BridgeApp, private val registry: Registry) : Capability(app) {
    override val id = "camera"
    override val titleRes = R.string.cap_camera
    override val descriptionRes = R.string.cap_camera_desc
    override val group = "capture"
    override val switchKey = "camera"
    override val permissions = listOf(Manifest.permission.CAMERA)
    override val endpoints = listOf(Endpoints.SNAPSHOT, Endpoints.CAMERA_ARM, Endpoints.POSE_START, Endpoints.POSE_READ, Endpoints.POSE_FRAME, Endpoints.POSE_STOP)
    override fun blockedReason() = captureBlocked(app, Manifest.permission.CAMERA, app.listener.value.cameraReady)
    override fun details() = JSONObject().apply {
        put("cameras", org.json.JSONArray().put("rear").put("front"))
        // Zoom range of the camera used last; known after the first photo.
        cc.bridgit.app.BridgeService.running?.camera?.lastZoomRange?.let { (min, max) -> put("zoom_min", min.toDouble()).put("zoom_max", max.toDouble()) }
    }

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.SNAPSHOT) {
            val returnImage=if(body.has("return_image")) body.opt("return_image") as? Boolean ?: throw ErrorCatalog.error("invalid_parameter","return_image must be boolean") else true
            var which = body.optString("camera", "rear")
            if (which != "rear" && which != "front") throw ErrorCatalog.error("invalid_parameter", "camera must be \"rear\" or \"front\".")
            val flash = if (body.has("flash")) body.opt("flash") as? Boolean
                ?: throw ErrorCatalog.error("invalid_parameter", "flash must be true or false.") else false
            val spec = snapshotSpec(this)
            val zoom = if (!body.has("zoom") || body.isNull("zoom")) null else (body.opt("zoom") as? Number)?.toFloat()
                ?.takeIf { it in 0.1f..100f } ?: throw ErrorCatalog.error("invalid_parameter", "zoom must be a number such as 0.5, 1, 2 or 5.")
            requireUsable()
            cc.bridgit.app.capture.CapturesModule.checkStore(app, this, "camera")
            // A camera this agent armed is already running: take it over (it is released after the photo).
            val visible=cc.bridgit.app.capture.CapturePresentationRules.requiresViewfinder(invocation,body.optBoolean("viewfinder"))
            // A0049: an agent's photo works while the phone is locked; with captures.locked_camera_approval on, the
            // owner Allows each locked photo first (before the camera is taken, so other captures aren't held up).
            // lockedOk false (approval on, phone unlocked now): the camera still refuses if the phone locks meanwhile.
            val lockedOk = if(visible) false else try { LockedCameraApproval.require(app, agentName) } catch (e: cc.bridgit.app.api.ApiError) {
                // Declined: don't leave this agent's armed camera running until its timer ends.
                agent?.let { a -> registry.cameraArm.takeFor(a.id)?.let { registry.cameraArm.finish(api.service, it) } }
                throw e
            }
            if(visible) registry.cameraArm.takeFor(agent!!.id)?.let { registry.cameraArm.finish(api.service,it) }
            val armed = if(visible)null else agent?.let { registry.cameraArm.takeFor(it.id) }
            val img = if(visible) {
                val countdown=cc.bridgit.app.planned.Rules.integer(body.opt("countdown_seconds"),"countdown_seconds",0,10,0)
                val result=cc.bridgit.app.capture.VisibleCapture.take(app,agent!!.id,which,groupPhoto=body.optBoolean("group_photo"),countdown=countdown,flash=flash,instructions=body.optString("instructions").trim().take(240),zoom=zoom)
                which=result.camera ?: which
                cc.bridgit.app.capability.ImageUtil.prepare(result.photo ?: throw cc.bridgit.app.api.ApiError(409,"capture_cancelled","No photo accepted"),"image/jpeg",spec,forceJpeg=true)
            } else if (armed != null) {
                try {
                    if (armed.rear == (which == "rear")) delay(ArmSlot.settleWaitMs(armed, System.currentTimeMillis()))
                    api.service.camera.snapshot(which == "rear", flash, spec, zoom, warm = armed.binding, allowLocked = lockedOk)
                }
                finally { registry.cameraArm.finish(api.service, armed) }
            } else api.withSensor(registry.cameraLock, app.getString(R.string.notif_taking_photo, agentName)) {
                api.service.camera.snapshot(which == "rear", flash, spec, zoom, allowLocked = lockedOk)
            }
            val warm = armed != null && armed.rear == (which == "rear")
            data = JSONObject().put("camera", which).put("width_px", img.width).put("height_px", img.height).put("zoom", zoom ?: 1f).put("warm", warm)
            val now = System.currentTimeMillis()
            val at = isoAt(now)
            val kept = cc.bridgit.app.capture.CapturesModule.keep(app, this, "camera", img.bytes, img.mime, now,
                JSONObject().put("camera", which).put("width_px", img.width).put("height_px", img.height))
            if(!returnImage) {
                if(kept == null) throw ErrorCatalog.error("invalid_parameter","return_image:false requires a kept capture; send store:[memory]")
                Reply.Json(JSONObject().put("capture",kept).put("camera",which).put("captured_at",at))
            }
            else if (wantsRaw()) rawReply(img, mapOf("X-Bridgit-Captured-At" to at, "X-Bridgit-Camera" to which) + keptHeaders(kept))
            else Reply.Json(envelope(img, at, "captured_at", null).put("source", "camera").put("camera", which).put("zoom", zoom ?: 1.0).put("warm", warm)
                .apply { if (kept != null) put("capture", kept) })
        }
        endpoint(Endpoints.CAMERA_ARM) {
            val which = body.optString("camera", "rear")
            if (which != "rear" && which != "front") throw ErrorCatalog.error("invalid_parameter", "camera must be \"rear\" or \"front\".")
            val seconds = ArmSlot.seconds(body.opt("seconds"))
                ?: throw ErrorCatalog.error("invalid_parameter", "seconds must be a whole number from 1 to ${ArmSlot.MAX_SECONDS}.")
            requireUsable()
            val a = agent ?: throw ErrorCatalog.error("unauthorized", "Arming the camera needs an agent key.")
            registry.cameraArm.arm(api.service, a.id, app.getString(R.string.notif_camera_armed, agentName), which == "rear", seconds) {
                val live = app.agents.get(a.id)
                this@CameraModule.enabled && this@CameraModule.blockedReason() == null && live != null && live.keyHash == a.keyHash && live.allows("camera")
            }
            data = JSONObject().put("camera", which).put("seconds", seconds)
            Reply.Json(JSONObject().put("camera", which).put("armed", true).put("seconds", seconds)
                .put("armed_until", isoAt(System.currentTimeMillis() + seconds * 1000L))
                .put("settle_ms", ArmSlot.SETTLE_MS))
        }
        endpoint(Endpoints.POSE_START) {
            val which = body.optString("camera", "front")
            if (which != "rear" && which != "front") throw ErrorCatalog.error("invalid_parameter", "camera must be \"front\" or \"rear\".")
            val seconds = PoseRules.int(body.opt("seconds"), PoseRules.MIN_SECONDS, PoseRules.MAX_SECONDS, PoseRules.DEFAULT_SECONDS)
                ?: throw ErrorCatalog.error("invalid_parameter", "seconds must be a whole number from ${PoseRules.MIN_SECONDS} to ${PoseRules.MAX_SECONDS}.")
            val every = PoseRules.int(body.opt("preview_every_ms"), 150, 2000, 400)
                ?: throw ErrorCatalog.error("invalid_parameter", "preview_every_ms must be a whole number from 150 to 2000.")
            val edge = PoseRules.int(body.opt("preview_edge_px"), 120, 480, 320)
                ?: throw ErrorCatalog.error("invalid_parameter", "preview_edge_px must be a whole number from 120 to 480.")
            requireUsable()
            val a = agent ?: throw ErrorCatalog.error("unauthorized", "Body tracking needs an agent key.")
            val s = registry.pose.start(api.service, a.id, app.getString(R.string.notif_pose_tracking, agentName), which == "rear", seconds, every, edge)
            data = JSONObject().put("camera", which).put("seconds", seconds)
            Reply.Json(JSONObject().put("session_id", s.id).put("state", s.state).put("camera", which).put("mirror", which == "front")
                .put("max_ms", s.maxMs).put("started_at", isoAt(s.startedAt)))
        }
        endpoint(Endpoints.POSE_READ) {
            val s = poseSession(this)
            val since = PoseRules.int(query("since"), 0, Int.MAX_VALUE, 0) ?: throw ErrorCatalog.error("invalid_parameter", "since must be a frame index (0 or more).")
            val limit = PoseRules.int(query("limit"), 1, PoseRules.MAX_LIMIT, PoseRules.DEFAULT_LIMIT) ?: throw ErrorCatalog.error("invalid_parameter", "limit must be 1-${PoseRules.MAX_LIMIT}.")
            val after = query("preview_after")?.let { it.toIntOrNull() ?: throw ErrorCatalog.error("invalid_parameter", "preview_after must be a number of ms.") }
            val wait = PoseRules.int(query("wait_ms"), 0, 1000, 0) ?: throw ErrorCatalog.error("invalid_parameter", "wait_ms must be 0-1000.")
            s.lastReadAt = System.currentTimeMillis()
            val until = System.currentTimeMillis() + wait
            while (s.running && System.currentTimeMillis() < until && synchronized(s.frames) { s.frames.lastOrNull()?.i ?: -1 } < since) delay(40)
            Reply.Json(s.json(System.currentTimeMillis(), since, limit, after))
        }
        endpoint(Endpoints.POSE_FRAME) {
            val s = poseSession(this)
            val t = PoseRules.int(query("t"), 0, Int.MAX_VALUE, -1)?.takeIf { it >= 0 } ?: throw ErrorCatalog.error("invalid_parameter", "t (ms into the session) is required.")
            s.lastReadAt = System.currentTimeMillis()
            val pic = synchronized(s.thumbs) { s.thumbs.getOrNull(PoseRules.nearest(s.thumbs.map { it.t }, t)) }
                ?: throw ErrorCatalog.error("not_found", "This session has no pictures yet.")
            Reply.Json(registry.pose.picJson(pic))
        }
        endpoint(Endpoints.POSE_STOP) {
            val s = poseSession(this)
            registry.pose.stop(s)
            Reply.Json(s.json(System.currentTimeMillis(), Int.MAX_VALUE, 1, null).apply { remove("frames") })
        }
    }

    private fun poseSession(r: Req): PoseSessions.Session {
        val id = r.path("id").orEmpty()
        val a = r.agent ?: throw ErrorCatalog.error("unauthorized", "Body tracking needs an agent key.")
        return registry.pose.get(id, a.id) ?: throw ErrorCatalog.error("not_found", "No body tracking session $id for this agent (they are kept 15 minutes after they end).")
    }

    /** CAP-04: default 2048 px wide at q90 (1.2 behaviour); null max_width_px = full size; API-10 presets otherwise. */
    private fun snapshotSpec(r: Req): Sizing.Spec {
        val b = r.body
        if (b.has("max_width_px") && b.isNull("max_width_px")) return Sizing.PRESETS.getValue("original")
        val explicit = listOf("size", "max_edge_px", "max_width_px", "quality").any { b.has(it) || r.query(it) != null }
        if (!explicit) return Sizing.Spec(null, 2048, 90)
        return Sizing.resolve({ r.arg(it) }, "agent")
    }
}

/** 8.4 Microphone clip. */
class MicModule(app: BridgeApp, private val registry: Registry) : Capability(app) {
    override val id = "mic"
    override val titleRes = R.string.cap_mic
    override val descriptionRes = R.string.cap_mic_desc
    override val group = "capture"
    override val switchKey = "mic"
    override val permissions = listOf(Manifest.permission.RECORD_AUDIO)
    override val endpoints = listOf(Endpoints.MIC, Endpoints.SPEECH_HEAR)
    override fun blockedReason() = captureBlocked(app, Manifest.permission.RECORD_AUDIO, app.listener.value.micReady)
    override fun details() = JSONObject().put("max_seconds", 120).put("on_device_transcription", cc.bridgit.app.planned.Transcriber.available(app))

    override fun Route.install(api: ApiServer) = with(api) {
        for (spec in listOf(Endpoints.MIC, Endpoints.SPEECH_HEAR)) endpoint(spec) {
            val d = body.opt("duration_seconds")
            val secs = (d as? Int) ?: (d as? Long)?.takeIf { it in 1..120 }?.toInt()
            if (secs == null || secs < 1 || secs > 120) throw ErrorCatalog.error("invalid_parameter", "duration_seconds must be a whole number from 1 to 120.")
            requireUsable()
            val speechTurn = spec == Endpoints.SPEECH_HEAR
            if (speechTurn && secs > cc.bridgit.app.speech.SpeechEngine.MAX_SECONDS) throw ErrorCatalog.error("invalid_parameter", "Hearing turns are limited to 30 seconds.")
            val preparation = if (speechTurn) app.speaker.beginPreparation(agent!!.id) else null
            try {
            val selected = if (speechTurn) cc.bridgit.app.speech.SpeechEngine.select(app, "hear", body.optBoolean("allow_cloud", false)) else null
            val transcribe = speechTurn || cc.bridgit.app.planned.Rules.bool(body, "transcribe", false)
            val returnAudio = cc.bridgit.app.planned.Rules.bool(body, "return_audio", !speechTurn)
            if (!returnAudio && !transcribe) throw ErrorCatalog.error("invalid_parameter", "return_audio=false requires transcribe=true.")
            if (transcribe && selected == null && !cc.bridgit.app.planned.Transcriber.available(app)) throw ErrorCatalog.error("not_supported", "No on-device speech recognizer available; nothing was recorded.")
            cc.bridgit.app.capture.CapturesModule.checkStore(app, this, "mic")
            val started = System.currentTimeMillis()
            if (transcribe) app.speaker.beginHearing()
            var speech: JSONObject? = null
            try {
            if (selected != null && !selected.cloud) {
                if (returnAudio) throw ErrorCatalog.error("invalid_parameter", "Device hearing does not return recorded audio; use /v1/mic/record for a clip.")
                val text = api.withSensor(registry.micLock, app.getString(R.string.notif_recording, agentName, secs)) {
                    app.wake.awaitMicReleased()
                    cc.bridgit.app.speech.NativeHearing.listen(app, secs, body.optString("language").ifBlank { null })
                }
                Reply.Json(JSONObject().put("transcript", text).put("on_device", true).put("recorded_at", isoAt(started))
                    .put("speech", JSONObject().put("model", selected.model).put("processing", "device").put("cost_usd", 0).put("elapsed_ms", System.currentTimeMillis()-started)))
            } else {
            val clip = api.withSensor(registry.micLock, app.getString(R.string.notif_recording, agentName, secs)) {
                app.wake.awaitMicReleased()
                api.service.mic.record(secs)
            }
            val transcript = if (transcribe) cc.bridgit.app.planned.transcribeKeepingAudio(returnAudio) {
                if (selected != null) {
                    speech = cc.bridgit.app.speech.SpeechEngine.hear(app, selected, clip.bytes, body.optString("language").ifBlank { null }, secs)
                    speech!!.getString("transcript")
                } else cc.bridgit.app.planned.Transcriber.transcribe(app, clip.bytes, body.optString("language").ifBlank { null })
            } else null
            val b64 = if (returnAudio) Base64.encodeToString(clip.bytes, Base64.NO_WRAP) else ""
            if (b64.length > ApiServer.MAX_PAYLOAD) throw ErrorCatalog.error("payload_too_large", "The clip is over 12 MB as base64.")
            bytesOut = clip.bytes.size.toLong()
            data = JSONObject().put("duration_seconds", secs)
            val kept = cc.bridgit.app.capture.CapturesModule.keep(app, this, "mic", clip.bytes, "audio/mp4", started,
                JSONObject().put("duration_seconds", clip.durationSeconds).apply { if (transcript?.text != null) put("transcript", transcript.text) })
            Reply.Json(JSONObject().put("recorded_at", isoAt(started)).put("duration_seconds", clip.durationSeconds)
                .put("format", "m4a").put("sample_rate_hz", clip.sampleRate).put("size_bytes", clip.bytes.size)
                .apply { if (returnAudio) put("data_base64", b64); if (transcript != null) {
                    put("transcript", transcript.text ?: JSONObject.NULL).put("on_device", selected?.cloud != true)
                    if (speech != null) put("speech", JSONObject(speech.toString()).apply { remove("transcript") })
                    transcript.error?.let { put("transcript_error", it) }
                }; if (kept != null) put("capture", kept) })
            }
            } finally { if (transcribe) app.speaker.endHearing() }
            } finally { preparation?.let { app.speaker.endPreparation(it) } }
        }
    }
}

/** 8.5 Gallery and 8.6 Last image. */
class GalleryModule(app: BridgeApp) : Capability(app) {
    private val gallery = Gallery(app)
    override val id = "gallery"
    override val titleRes = R.string.cap_gallery
    override val descriptionRes = R.string.cap_gallery_desc
    override val group = "photos"
    override val switchKey = "gallery"
    override val permissions = listOf(Manifest.permission.READ_MEDIA_IMAGES)
    override val endpoints = listOf(Endpoints.GALLERY_RECENT, Endpoints.GALLERY_PHOTO, Endpoints.IMAGE_LAST)
    override fun blockedReason() = if (gallery.accessMode() == null) "permission:READ_MEDIA_IMAGES" else null
    override fun details() = JSONObject().put("access_mode", gallery.accessMode() ?: "none")

    private fun mode(): String { requireUsable(); return gallery.accessMode()!! }

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.GALLERY_RECENT) {
            val m = mode()
            val limit = intArg(this, "limit", 20, 1, 100)
            val arr = JSONArray()
            val type = query("type") ?: "any"
            if (type !in listOf("any", "photo", "screenshot")) throw ErrorCatalog.error("invalid_parameter", "type must be any, photo or screenshot.")
            val since = query("since")?.let { cc.bridgit.app.planned.UsageModule.time(it, 0) }
            val until = query("until")?.let { cc.bridgit.app.planned.UsageModule.time(it, 0) }
            if (since != null && until != null && since > until) throw ErrorCatalog.error("invalid_parameter", "since must precede until.")
            gallery.recent(limit, type, query("album"), since, until).forEach {
                arr.put(JSONObject().put("id", it.id.toString()).put("taken_at", isoAt(it.takenAt))
                    .put("width_px", it.width).put("height_px", it.height)
                    .put("size_bytes", it.size).put("mime", it.mime).put("album", it.album ?: JSONObject.NULL))
            }
            val out = JSONObject().put("access_mode", m).put("photos", arr)
            if (m == "selected") out.put("hint", "Only the photos the owner picked are visible. For new photos and screenshots, " +
                "ask them to set Photos to \"Allow all\" (Bridgit > More > Hardware access > Photos > Change).")
            Reply.Json(out)
        }
        endpoint(Endpoints.GALLERY_PHOTO) {
            val m = mode()
            val idStr = path("id") ?: ""
            val id = idStr.toLongOrNull() ?: throw ErrorCatalog.error("invalid_parameter", "id must be a gallery id from /v1/gallery/recent.")
            val spec = legacyOrPreset(this)
            val item = gallery.find(id) ?: throw notFound(m, "No photo with that id")
            val raw = gallery.read(id) ?: throw ErrorCatalog.error("photo_not_found", "That photo could not be opened.")
            val img = ImageUtil.prepare(raw, item.mime, spec)
            if (wantsRaw()) rawReply(img, mapOf("X-Bridgit-Id" to idStr, "X-Bridgit-Taken-At" to isoAt(item.takenAt)))
            else Reply.Json(envelope(img, isoAt(item.takenAt), "taken_at", idStr).put("source", "gallery").put("id", idStr).put("access_mode", m))
        }
        endpoint(Endpoints.IMAGE_LAST) {
            val m = mode()
            val type = query("type") ?: "any"
            val kind = when (type) {
                "any" -> Gallery.Kind.ANY; "screenshot" -> Gallery.Kind.SCREENSHOT; "photo" -> Gallery.Kind.PHOTO
                else -> throw ErrorCatalog.error("invalid_parameter", "type must be any, screenshot or photo.")
            }
            val spec = Sizing.resolve({ arg(it) }, "agent")
            val since = query("since")?.let { s ->
                s.toLongOrNull()?.let { Since(id = it) } ?: LogQuery.iso(s)?.let { Since(ts = it) }
                    ?: throw ErrorCatalog.error("invalid_parameter", "since must be a gallery id or an ISO time.")
            }
            val wait = intArg(this, "wait", 0, 0, 55)
            if (wait > 0 && since == null) throw ErrorCatalog.error("invalid_parameter", "wait needs since (an ISO time or gallery id).")
            var found = gallery.latest(kind)?.takeIf { since == null || since.isOlderThan(it.first) }
            if (found == null && wait > 0) {
                if (!api.waits.enter(agent!!.id)) throw ErrorCatalog.error("too_many_waits", "At most 4 open waits per agent.")
                try {
                    val until = System.currentTimeMillis() + wait * 1000L
                    while (found == null && System.currentTimeMillis() < until) {
                        delay(1000)
                        mode()
                        found = gallery.latest(kind)?.takeIf { since!!.isOlderThan(it.first) }
                    }
                } finally { api.waits.exit(agent!!.id) }
                if (found == null) return@endpoint Reply.Json(JSONObject().put("new", false).put("type", type)
                    .put("message", "No new $type image in $wait s. Ask again with the same since."))
            }
            mode() // Consent can change while a long poll is suspended.
            val (item, path) = found ?: throw notFound(m,
                "No ${if (type == "any") "" else "$type "}images" + (if (since != null) " newer than since" else ""))
            val bytesIn = gallery.read(item.id) ?: throw ErrorCatalog.error("photo_not_found", "That image could not be opened.")
            val img = ImageUtil.prepare(bytesIn, item.mime, spec, forceJpeg = !spec.original)
            val kindName = gallery.kindOf(path)
            data = JSONObject().put("type", type).put("id", item.id.toString())
            if (wantsRaw()) rawReply(img, mapOf(
                "X-Bridgit-Id" to item.id.toString(), "X-Bridgit-Type" to kindName,
                "X-Bridgit-Taken-At" to isoAt(item.takenAt), "X-Bridgit-Added-At" to isoAt(item.addedAt),
                "X-Bridgit-Album" to (item.album ?: "")))
            else Reply.Json(envelope(img, isoAt(item.takenAt), "taken_at", item.id.toString())
                .put("source", "gallery").put("id", item.id.toString()).put("type", kindName)
                .put("added_at", isoAt(item.addedAt)).put("album", item.album ?: JSONObject.NULL)
                .put("original_width_px", item.width).put("original_height_px", item.height).put("access_mode", m))
        }
    }

    /**
     * photo_not_found. With "Select photos" access Android only shows Bridgit the photos the owner picked, and
     * new screenshots are never in that set, so say how to fix it instead of just "not found".
     */
    private fun notFound(mode: String, what: String) =
        if (mode != "selected") ErrorCatalog.error("photo_not_found", "$what.", mapOf("access_mode" to mode))
        else ErrorCatalog.error("photo_not_found", "$what in the photos the owner picked. Bridgit has \"Select photos\" access only, " +
            "so new photos and screenshots stay hidden. Ask the owner to open Bridgit > More > Hardware access > Photos > Change and choose " +
            "\"Allow all\".", mapOf("access_mode" to mode, "setting" to "photos_allow_all"))

    private class Since(val id: Long? = null, val ts: Long? = null) {
        fun isOlderThan(item: Gallery.Item) = if (id != null) item.id > id else item.addedAt > ts!!
    }

    /** Gallery photos keep 1.2's "unchanged unless asked" default; presets apply when given. */
    private fun legacyOrPreset(r: Req): Sizing.Spec {
        val explicit = listOf("size", "max_edge_px", "max_width_px", "quality").any { r.query(it) != null }
        return if (explicit) Sizing.resolve({ r.arg(it) }, "agent") else Sizing.PRESETS.getValue("original")
    }
}

private fun intArg(r: Req, name: String, def: Int, min: Int, max: Int): Int {
    val s = r.query(name) ?: return def
    val n = s.toIntOrNull()
    if (n == null || n < min || n > max) throw ErrorCatalog.error("invalid_parameter", "$name must be $min to $max.")
    return n
}

// ---------------------------------------------------------------------------------------------

/** Section 10: the log API. Always on; what an agent may read follows the visibility setting. */
class LogModule(app: BridgeApp) : Capability(app) {
    override val id = "log"
    override val titleRes = R.string.cap_log
    override val descriptionRes = R.string.cap_log_desc
    override val group = "status"
    override val endpoints = listOf(Endpoints.LOG, Endpoints.LOG_ENTRY, Endpoints.LOG_STATS, Endpoints.LOG_EXPORT, Endpoints.DEVICE_LOG, Endpoints.DEVICE_EXITS, Endpoints.LOG_NOTE, Endpoints.AUDIT)

    private val noteLimiter = cc.bridgit.app.api.RateLimiter()

    /** Agent ids this caller may read, or null for everything (agents with Super Powers always see all). */
    private fun allowed(r: Req): List<String>? {
        val current = r.agent?.let { app.agents.get(it.id) } ?: return emptyList()
        return if (current.superPowers || app.settings.logVisibility == "all") null else listOf(current.id)
    }

    private fun parse(r: Req, extra: Map<String, String> = emptyMap()): LogQuery {
        val p = r.queries.toMutableMap()
        p.putAll(extra)
        p["agent"]?.let { a -> p["agent"] = a.split(",").joinToString(",") { if (it.trim() == "me") r.agent!!.id else it.trim() } }
        val q = try {
            LogQuery.parse(p - "wait" - "format", { n -> app.agents.get(n)?.id ?: app.agents.byName(n)?.id })
        } catch (e: QueryError) { throw ErrorCatalog.error("invalid_parameter", e.message ?: "Bad log query.") }
        val mine = allowed(r) ?: return q
        return q.copy(agentIds = q.agentIds?.filter { it in mine } ?: mine)
    }

    private fun json(e: LogEntry): JSONObject =
        e.toJson(::isoAt, e.agentId?.let { app.agents.get(it)?.name } ?: e.agent)

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.LOG) {
            val q = parse(this)
            val wait = intArg(this, "wait", 0, 0, 55)
            val entries = if (wait > 0) {
                if (!q.ascending || q.sinceId == null) throw ErrorCatalog.error("invalid_parameter", "Tail mode needs order=asc and since=<log id>.")
                if (!api.waits.enter(agent!!.id)) throw ErrorCatalog.error("too_many_waits", "At most 4 open waits per agent.")
                val found = try { app.log.waitFor(q, q.sinceId, wait * 1000L) } finally { api.waits.exit(agent!!.id) }
                // The setting or the Developer switch may have changed during the wait: filter again with the current rule.
                val now = allowed(this)
                if (now == null) found else found.filter { it.agentId in now }
            } else app.log.query(q)
            params.put("returned", entries.size)
            Reply.Json(JSONObject().put("entries", JSONArray(entries.map { json(it) }))
                .put("next_cursor", if (entries.size == q.limit) LogQuery.encodeCursor(entries.last().id) else JSONObject.NULL)
                .put("latest_id", app.log.latestId.value))
        }
        endpoint(Endpoints.LOG_STATS) {
            val q = parse(this, buildMap { if (query("since") == null) put("since", isoAt(System.currentTimeMillis() - 86_400_000L)) })
                .copy(channels = listOf("audit"), kinds = listOf("request"), limit = 20_000)
            val rows = app.log.query(q.copy(limit = LogQuery.MAX_LIMIT)).let { first ->
                if (first.size < LogQuery.MAX_LIMIT) first else {
                    val all = ArrayList<LogEntry>(); app.log.forEach(q) { all += it }; all
                }
            }
            fun group(key: (LogEntry) -> String) = JSONArray().apply {
                rows.groupBy(key).forEach { (k, list) ->
                    val d = list.mapNotNull { it.durationMs }.sorted()
                    put(JSONObject().put("key", k).put("count", list.size).put("errors", list.count { it.isError })
                        .put("p50_ms", Percentile.of(d, 50.0) ?: JSONObject.NULL).put("p95_ms", Percentile.of(d, 95.0) ?: JSONObject.NULL)
                        .put("bytes_out", list.sumOf { it.bytesOut ?: 0 }))
                }
            }
            Reply.Json(JSONObject().put("by_agent", group { e -> e.agentId?.let { app.agents.get(it)?.name } ?: e.agent ?: "(no key)" })
                .put("by_endpoint", group { "${it.method} ${it.endpoint}" }).put("entries", rows.size))
        }
        endpoint(Endpoints.LOG_EXPORT) {
            val format = query("format") ?: "jsonl"
            if (format != "jsonl" && format != "csv") throw ErrorCatalog.error("invalid_parameter", "format must be jsonl or csv.")
            val q = parse(this)
            Reply.Stream(if (format == "csv") "text/csv; charset=utf-8" else "application/x-ndjson") { out ->
                if (format == "csv") out.write((CSV_HEADER + "\n").toByteArray())
                var n = 0
                app.log.forEach(q) { e ->
                    val live = agent?.let { app.agents.get(it.id) }
                    if (live == null || live.keyHash != agent?.keyHash || live.suspended || !live.allows("log"))
                        throw ErrorCatalog.error("forbidden", "Log access changed during export.")
                    val mine = allowed(this)
                    if (mine == null || e.agentId in mine) {
                        val line = if (format == "jsonl") json(e).toString() else csv(json(e))
                        val bytes = (line + "\n").toByteArray()
                        out.write(bytes); bytesOut += bytes.size; n++
                    }
                }
                params.put("returned", n)
            }
        }
        endpoint(Endpoints.DEVICE_LOG) {
            Reply.Json(cc.bridgit.app.log.DeviceDiagnostics.appLog(query("buffer") ?: "main", intArg(this, "lines", 1000, 1, 10_000)))
        }
        endpoint(Endpoints.DEVICE_EXITS) {
            val before = query("before")?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 }
                ?: throw ErrorCatalog.error("invalid_parameter", "before must be a non-negative timestamp_ms.") }
            val trace = query("trace")?.let { when(it) { "true", "1" -> true; "false", "0" -> false
                else -> throw ErrorCatalog.error("invalid_parameter", "trace must be true or false.") } } ?: false
            val limit = intArg(this, "limit", 20, 1, 100)
            Reply.Json(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                cc.bridgit.app.log.DeviceDiagnostics.exitPage(app, before, limit, trace)
            })
        }
        endpoint(Endpoints.LOG_NOTE) {
            val a = agent!!
            val msg = body.optString("message", "")
            if (msg.isBlank()) throw ErrorCatalog.error("invalid_parameter", "message is required.")
            if (msg.toByteArray().size > 1024) throw ErrorCatalog.error("invalid_parameter", "message is limited to 1 KB.")
            val level = body.optString("level", "info")
            if (level !in LogEntry.LEVELS) throw ErrorCatalog.error("invalid_parameter", "level must be debug, info, warn or error.")
            val d = body.optJSONObject("data") ?: JSONObject()
            if (d.toString().toByteArray().size > 4096) throw ErrorCatalog.error("invalid_parameter", "data is limited to 4 KB.")
            if (!noteLimiter.tryAcquire(a.id, cc.bridgit.app.api.RateLimiter.NOTES_PER_AGENT))
                throw ErrorCatalog.error("rate_limited", "At most 60 notes per minute.", mapOf("retry_after_s" to noteLimiter.retryAfterS(a.id)))
            app.log.append(LogEntry(ts = System.currentTimeMillis(), channel = "agent", kind = "note", level = level,
                agentId = a.id, agent = a.name, sourceIp = source, requestId = requestId,
                message = Redactor.string(msg, 1024), data = Redactor.data(d)))
            params.put("message", "[${msg.length} chars]")
            message = "${a.name} wrote a note"
            Reply.Json(JSONObject().put("ok", true).put("channel", "agent"), status = 201)
        }
        endpoint(Endpoints.AUDIT) {
            val lim = intArg(this, "limit", 50, 1, LogQuery.MAX_LIMIT)
            val q = parse(this, mapOf("channel" to "audit", "limit" to lim.toString()))
            Reply.Json(JSONObject().put("entries", JSONArray(app.log.query(q).map { json(it) }))
                .put("deprecated", "Use GET /v1/log?channel=audit. /v1/audit is removed in v3."))
        }
        endpoint(Endpoints.LOG_ENTRY) {
            val id = path("id")?.toLongOrNull() ?: throw ErrorCatalog.error("invalid_parameter", "id must be a log entry id.")
            val e = app.log.get(id)
            val mine = allowed(this)
            if (e == null || (mine != null && e.agentId !in mine)) throw ErrorCatalog.error("not_found", "No log entry $id that you can read.")
            Reply.Json(json(e))
        }
    }

    companion object {
        val CSV_COLS = listOf("id", "ts", "channel", "level", "kind", "agent", "agent_id", "source_ip", "method", "endpoint",
            "http", "result", "bytes_in", "bytes_out", "duration_ms", "request_id", "message")
        val CSV_HEADER = CSV_COLS.joinToString(",")
        fun csv(o: JSONObject) = CSV_COLS.joinToString(",") { c ->
            val v = o.opt(c)
            val s = if (v == null || v == JSONObject.NULL) "" else v.toString()
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }
    }
}

