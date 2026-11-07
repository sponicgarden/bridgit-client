package cc.bridgit.app.lan

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.lancamera.LanCamera
import cc.bridgit.app.lancamera.Onvif
import io.ktor.server.routing.Route
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LanModule(app: BridgeApp, override val id: String) : Capability(app) {
    override val titleRes = when (id) { "lan_discovery" -> R.string.lan_discovery_title; "lan_enrollment" -> R.string.lan_enrollment_title; else -> R.string.lan_control_title }
    override val descriptionRes = when (id) { "lan_discovery" -> R.string.lan_discovery_desc; "lan_enrollment" -> R.string.lan_enrollment_desc; else -> R.string.lan_control_desc }
    override val group = "capture"
    override val switchKey = id
    override val endpoints = ALL.filter { it.capability == id }
    override fun details() = JSONObject().put("scope", "direct Wi-Fi/Ethernet private IPv4 LAN only")
        .put("adapters", JSONArray(LanRules.adapters)).put("device_grant", "Enrollment belongs to the enrolling agent; other agents cannot list, use or remove it.")
        .put("limitations", "No arbitrary device control, credential guessing, routed subnet scan, router writes, generic HTTP proxy or shell. Existing camera captures require lan_camera.")
    private fun authorize(req: Req) {
        requireUsable()
        val a = req.agent ?: throw ErrorCatalog.error("unauthorized", "Agent required.")
        val live = app.agents.get(a.id)
        if (live == null || live.paused || live.keyHash != a.keyHash || !live.allows(id)) throw ErrorCatalog.error("agent_not_allowed", "LAN permission was revoked.")
    }
    fun portalInventory(agentId: String): JSONArray {
        requireUsable()
        val agent = app.agents.get(agentId)
        if (agent == null || agent.paused || !agent.allows("lan_control"))
            throw ErrorCatalog.error("agent_not_allowed", "The applet needs an active LAN control grant.")
        return JSONArray(app.lanDevices.all().filter { it.agentId == agentId }.map { device ->
            JSONObject().put("id", device.id).put("name", device.name).put("adapter", device.adapter)
                .put("verified_at", device.verifiedAt).put("availability", "not_checked")
        })
    }

    private fun cameraAuthorized(req: Req) {
        authorize(req)
        val live = app.agents.get(req.agent!!.id)
        if (live?.allows("lan_camera") != true || !app.settings.isOn("lan_camera"))
            throw ErrorCatalog.error("agent_not_allowed", "Existing Local network cameras permission and switch are required.")
    }
    private suspend fun operation(req: Req, block: suspend (HostLan) -> JSONObject): Reply {
        authorize(req)
        if (!operationLock.tryLock()) throw ErrorCatalog.error("conflict", "Another LAN operation is running; wait for it to finish.")
        try {
            return withTimeout(25_000) { withContext(Dispatchers.IO) {
                try { Reply.Json(block(HostLan(app))) }
                catch (e: CancellationException) { throw e }
                catch (e: ApiError) { throw e }
                catch (_: IllegalArgumentException) { throw ErrorCatalog.error("invalid_parameter", "Invalid LAN target, adapter, credentials, action or device response. Check the guide and direct LAN connection. No enrollment was saved on failed verification.") }
                catch (_: Exception) { throw ErrorCatalog.error("upstream_failed", "LAN device unavailable or verification failed; action outcome may be unknown. Check the network and authorized credentials before retrying.") }
            } }
        } finally { operationLock.unlock() }
    }
    override fun Route.install(api: ApiServer) = with(api) {
        fun Route.lanEndpoint(spec: EndpointSpec, block: suspend Req.() -> Reply) {
            if (spec.capability == id) endpoint(spec, block = block)
        }
        lanEndpoint(CATALOG) {
            authorize(this)
            Reply.Json(DiscoveryResult.catalog(app))
        }
        lanEndpoint(REMEMBERED) {
            val req = this
            operation(req) { lan -> remembered(app, lan) }
        }
        lanEndpoint(BRAND_LOGO) {
            authorize(this)
            brandLogo(app, path("id").orEmpty(), arg("raw") in listOf(1, "1", true, "true"))
        }
        lanEndpoint(INVENTORY) {
            authorize(this)
            Reply.Json(JSONObject().put("devices", JSONArray(app.lanDevices.all().filter { it.agentId == agent!!.id }.map { it.publicJson() }))
                .put("adapters", adapterSchemas()).put("camera_inventory", "/v1/lan-cameras"))
        }
        lanEndpoint(DISCOVER) {
            val req = this
            operation(req) { lan ->
                val a = body.optJSONArray("hosts") ?: JSONArray()
                require(a.length() <= 254)
                val hosts = (0 until a.length()).map { a.getString(it) }
                LanDiscovery(app, lan) { authorize(req) }.run(hosts, body.opt("subnet") == true)
            }
        }
        lanEndpoint(ENROLL) {
            val req = this
            operation(req) { lan ->
                val host = body.getString("host"); lan.check(host)
                val adapter = body.getString("adapter"); require(adapter in LanRules.adapters)
                val name = body.getString("name"); require(name.isNotBlank() && name.length <= 60)
                val user = body.optString("username"); val pass = body.optString("password")
                require(user.length <= 64 && pass.length <= 128 && !user.contains(':') && user.none { it < ' ' })
                // Explicit acknowledgement is required before credentials are sent over cleartext LAN HTTP.
                val http = body.opt("allow_http") == true
                if (adapter == "tapo_onvif") require(http) { "This ONVIF adapter uses local HTTP; allow_http is required." }
                val draft = LanDevice("net:${UUID.randomUUID()}", name, host, adapter, agent!!.id, user, pass, http, System.currentTimeMillis())
                val devices = app.lanDevices.all()
                require(devices.size < 64 && devices.count { it.agentId == agent!!.id } < 32)
                app.appDevices // Complete legacy migration before adding any new camera.
                var camera: LanCamera? = null
                val result = if (adapter == "tapo_onvif") {
                    cameraAuthorized(req)
                    camera = LanCamera("lan:${UUID.randomUUID()}", name, host, user, pass, talkPassword=body.optString("talk_cloud_password"))
                    val info = Onvif(camera!!, app) { cameraAuthorized(req) }.info()
                    val report=cc.bridgit.app.lancamera.CameraProbe.inspect(app,camera!!,info){cameraAuthorized(req)}
                    require(camera!!.talkPassword.isEmpty() || report.getJSONObject("tapo_talk").optString("state")=="authenticated")
                    camera = camera!!.verified(info).assessed(report)
                    JSONObject().put("camera", camera!!.id).put("model", info.model).put("capabilities",report)
                } else LanAdapters.adapter(adapter, LanAdapters.transport(lan, draft) { authorize(req) }).execute(draft, "status", 0)
                authorize(req); currentCoroutineContext().ensureActive()
                val device = LanDevice(draft.id, name, host, adapter, agent!!.id, user, pass, http, System.currentTimeMillis(), result.optString("identity"))
                if (camera != null) {
                    cameraAuthorized(req)
                    app.lanCameras.save(camera!!)
                }
                try { app.lanDevices.save(device) }
                catch (e: Exception) { camera?.let { app.lanCameras.remove(it.id) }; throw e }
                device.publicJson().put("availability", "online").put("verification", result)
            }
        }
        lanEndpoint(REMOVE) {
            authorize(this)
            val d = owned(body.getString("device"), agent!!.id)
            app.lanDevices.remove(d.id)
            app.appDevices.remove(d.id)
            Reply.Json(JSONObject().put("removed", d.id).put("camera_note", "Existing Devices-tab entries are managed separately by the owner."))
        }
        lanEndpoint(ACTION) {
            val req = this
            operation(req) { lan ->
                val d = owned(body.getString("device"), agent!!.id); lan.check(d.host)
                val action = body.getString("action")
                val channel = if (!body.has("channel")) 0 else {
                    val n = body.get("channel") as? Number ?: throw IllegalArgumentException()
                    require(n.toDouble() == n.toInt().toDouble()); n.toInt()
                }
                LanRules.validateAction(d.adapter, action, channel)
                val result = if (d.adapter == "tapo_onvif") {
                    val c = LanCamera("lan:probe", d.name, d.host, d.username, d.password)
                    cameraAuthorized(req)
                    val info = Onvif(c, app) { owned(d.id, agent!!.id); cameraAuthorized(req) }.info(); JSONObject().put("model", info.model).put("relative_ptz", info.ptz)
                } else LanAdapters.adapter(d.adapter, LanAdapters.transport(lan, d) { owned(d.id, agent!!.id); authorize(req) }).execute(d, action, channel)
                JSONObject().put("device", d.id).put("action", action).put("availability", "online").put("checked_at", System.currentTimeMillis()).put("result", result)
            }
        }
    }
    private fun owned(id: String, agentId: String) = app.lanDevices.all().firstOrNull { it.id == id && it.agentId == agentId }
        ?: throw ErrorCatalog.error("not_found", "No device enrolled for this agent. Use GET /v1/lan/devices.")
    companion object {
        private val operationLock = Mutex()

        /** GET /v1/lan/discovered's payload: remembered devices on the current LAN, no scan. */
        fun remembered(app: BridgeApp, lan: HostLan): JSONObject =
            DiscoveryResult.enrich(app, JSONObject().put("network", lan.json()).put("observations", JSONArray())
                .put("scan_performed", false).put("complete", false), DiscoveryHistory(app).read(lan.historyScope(app)))

        /**
         * The same payload for the owner's Bridgit Portal (GET /v1/portal/devices). Like the phone's own Network
         * devices screen it doesn't need the Network discovery switch; it shares the one-LAN-operation-at-a-time lock.
         */
        suspend fun rememberedForOwner(app: BridgeApp): JSONObject {
            if (!operationLock.tryLock()) throw ErrorCatalog.error("conflict", "Another LAN operation is running; wait for it to finish.")
            try {
                val lan = try { HostLan(app) } catch (e: IllegalArgumentException) {
                    throw ErrorCatalog.error("not_supported", e.message ?: "The phone isn't on one private Wi-Fi or Ethernet network.")
                }
                return withTimeout(25_000) { withContext(Dispatchers.IO) { remembered(app, lan) } }
            } finally { operationLock.unlock() }
        }

        /** A bundled brand logo as GET /v1/lan/brands/{id}/logo returns it (JSON, or bytes with raw). */
        fun brandLogo(app: BridgeApp, id: String, raw: Boolean): Reply {
            val catalog = DeviceBrands(app)
            val brand = catalog.get(id) ?: throw ErrorCatalog.error("not_found", "No logo in the bundled brand catalog.")
            val bytes = catalog.logo(app, brand)
            return if (raw) Reply.Bytes(bytes, brand.mime, mapOf("Cache-Control" to "private, max-age=86400", "X-Content-Type-Options" to "nosniff"))
            else Reply.Json(brand.json().put("data_base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
        }
        val REMEMBERED = EndpointSpec("GET", "/lan/discovered", "lan_discovery", "Remembered devices on the current LAN; no scan",
            example = "GET /v1/lan/discovered", returns = "total_devices, connected_devices, unconnected_devices, categories with counts, devices with connected, connection_state and last_seen (Unix milliseconds); observed within two minutes is not proof of current reachability")
        val CATALOG = EndpointSpec("GET", "/lan/catalog", "lan_discovery", "Bundled discovery coverage, brand logos and control-agent instructions; no network scan",
            example = "GET /v1/lan/catalog", returns = "version, methods, services, brands, agent_guidance, scope, updates; requires owner-enabled discovery and this agent's discovery grant")
        val BRAND_LOGO = EndpointSpec("GET", "/lan/brands/{id}/logo", "lan_discovery", "A passive bundled manufacturer logo; never a remote lookup",
            query = listOf(Param("raw", "boolean", "Return image bytes instead of JSON/base64; default false")),
            example = "GET /v1/lan/brands/tapo/logo", returns = "id,name,mime,source,sha256,data_base64; raw=1 returns passive SVG or PNG bytes; same discovery grant")
        val DISCOVER = EndpointSpec("POST", "/lan/discover", "lan_discovery", "Bounded discovery on the Android host's direct local network", capture = true,
            body = listOf(Param("hosts", "array", "Optional literal private IPv4 targets, at most 254; no other networks"), Param("subnet", "boolean", "Probe the direct /24.. /30 subnet on TCP 80,443,554,2020; default false")),
            example = "POST /v1/lan/discover {\"hosts\":[\"192.168.1.1\"]}", returns = "network, total_devices, total_categories, categories[id,title,count,device_ids], devices[id,name,type,category,model,identifier,services,brand,verified=false,enrolled=false], observations, methods, limits, complete=false; untrusted partial observations, never a complete online inventory")
        val INVENTORY = EndpointSpec("GET", "/lan/devices", "lan_control", "This agent's enrolled devices and adapter/action schemas",
            example = "GET /v1/lan/devices", returns = "devices[id,name,host,adapter,enrolled,verified_at,availability=not_checked,actions], adapters; no credentials, no probe")
        val ENROLL = EndpointSpec("POST", "/lan/devices/enroll", "lan_enrollment", "Verify a supported device with authorized credentials and enroll it for this agent", capture = true, idempotent = true,
            body = listOf(Param("host", "string", "Direct LAN literal IPv4", true), Param("adapter", "string", "Supported adapter", true, LanRules.adapters.toList()), Param("name", "string", "Owner's device label, 1..60 chars", true),
                Param("username", "string", "Authorized local device/router account"), Param("password", "string", "Authorized local password, never returned"), Param("talk_cloud_password", "string", "Optional separate authorized Tapo cloud password, encrypted privately; passive authentication only, no speaker playback"), Param("allow_http", "boolean", "Explicitly permit unencrypted LAN HTTP, including supplied credentials. Default false (HTTPS with normal certificate validation). Required for Tapo ONVIF.")),
            privateFields = setOf("username", "password", "talk_cloud_password"), example = "POST /v1/lan/devices/enroll {\"host\":\"192.168.1.20\",\"adapter\":\"shelly_gen1\",\"name\":\"Desk lamp\",\"allow_http\":true}",
            returns = "Verified enrolled device and safe verification; saved only after successful protocol check. Tapo camera also appears in Devices tab, requiring existing lan_camera permission.")
        val REMOVE = EndpointSpec("POST", "/lan/devices/remove", "lan_enrollment", "Forget this agent's enrollment and stored credentials", idempotent = true,
            body = listOf(Param("device", "string", "Enrolled device id", true)), example = "POST /v1/lan/devices/remove {\"device\":\"net:example\"}", returns = "removed; Devices-tab entries remain owner-managed")
        val ACTION = EndpointSpec("POST", "/lan/devices/action", "lan_control", "Run a fixed supported action on this agent's enrolled device", capture = true, idempotent = true,
            body = listOf(Param("device", "string", "Enrolled device id", true), Param("action", "string", "Use this device's advertised actions", true, listOf("status", "relay_on", "relay_off", "dhcp_leases")), Param("channel", "integer", "Shelly relay channel 0..3; must be advertised by device; default 0")),
            example = "POST /v1/lan/devices/action {\"device\":\"net:example\",\"action\":\"status\"}", returns = "device, action, availability=online, checked_at, result; failures do not prove offline. Camera snapshots/move use existing lan_camera endpoints.")
        val ALL = listOf(REMEMBERED, CATALOG, BRAND_LOGO, DISCOVER, INVENTORY, ENROLL, REMOVE, ACTION)
        fun adapterSchemas() = JSONArray(LanRules.adapters.map { id -> JSONObject().put("id", id).put("actions", JSONArray(LanRules.actions(id)))
            .put("implemented", true).put("enrollment", ENROLL.fullPath)
            .put("limitations", when (id) { "shelly_gen1" -> "Gen1 HTTP Basic or no auth; relay devices only, 1..4 channels; MAC checked before control"
                "openwrt_ubus" -> "rpcd ubus session login; system.board and dhcp.ipv4leases ACLs; read-only, no router settings changes"
                else -> "Tapo-style fixed ONVIF port 2020 and RTSP stream1/2; not arbitrary ONVIF cameras; capture and PTZ require lan_camera" }) })
    }
}
