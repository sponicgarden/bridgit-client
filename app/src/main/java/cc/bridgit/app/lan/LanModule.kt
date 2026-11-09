package cc.bridgit.app.lan

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.lancamera.LanCamera
import cc.bridgit.app.lancamera.Onvif
import cc.bridgit.app.lan.packs.DevicePackStore
import cc.bridgit.app.lan.packs.LanPackNet
import cc.bridgit.app.lan.packs.PackEngine
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
        .put("adapters", JSONArray(LanRules.adapters + DevicePackStore.known.map { it.id })).put("device_packs", PACKS.fullPath).put("device_grant", "Enrollment belongs to the enrolling agent; other agents cannot list, use or remove it.")
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
                catch (e: PackEngine.PackError) { throw ErrorCatalog.error(if (e.device) "upstream_failed" else "invalid_parameter", e.message ?: "Device pack refused the operation.") }
                catch (e: LanUnreachable) { throw ErrorCatalog.error("upstream_failed", e.message ?: "The device can't be reached on this LAN.") }
                catch (e: cc.bridgit.app.core.SafeHttp.Failure) {
                    throw ErrorCatalog.error("upstream_failed", "The device didn't answer: ${e.message} The action's outcome may be unknown; check the device and network before retrying.")
                }
                catch (_: IllegalArgumentException) { throw ErrorCatalog.error("invalid_parameter", "Invalid LAN target, adapter, credentials, action or device response. Check the guide and direct LAN connection. No enrollment was saved on failed verification.") }
                catch (_: Exception) { throw ErrorCatalog.error("upstream_failed", "LAN device unavailable or verification failed; action outcome may be unknown. Check the network and authorized credentials before retrying.") }
            } }
        } finally { operationLock.unlock() }
    }
    /** A0100: the outlet flow's runtime log. One operation per enroll, action or removal; see [LanActionLog]. */
    private fun lanOp(req: Req, op: String, action: String?, channel: Int?, params: JSONObject?) = LanActionLog.Op(
        { app.log.append(it) },
        LanActionLog.Trigger(req.agent?.id ?: "", req.agentName, req.invocation, req.requestId, req.spec.method, req.spec.fullPath),
        op, action, channel, params)

    /** Writes [op]'s started entry, runs [block], then its outcome; the error (or timeout) still reaches the caller. */
    private suspend fun logged(op: LanActionLog.Op, result: (JSONObject) -> JSONObject? = { it.optJSONObject("result") }, block: suspend () -> Reply): Reply {
        op.started()
        try {
            val reply = block()
            op.ok((reply as? Reply.Json)?.body?.let(result))
            return reply
        } catch (e: TimeoutCancellationException) {
            op.failed("timed_out", "The LAN operation reached its 25 s limit.", 504); throw e
        } catch (e: CancellationException) {
            op.failed("cancelled", "The request was cancelled.", 499); throw e
        } catch (e: ApiError) {
            op.failed(e.code, e.message ?: e.code, e.http); throw e
        } catch (e: Exception) {
            op.failed("internal", e.javaClass.simpleName, 500); throw e
        }
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
            val packs = DevicePackStore.get(app)
            Reply.Json(JSONObject().put("devices", JSONArray(app.lanDevices.all().filter { it.agentId == agent!!.id }.map { d ->
                if (d.adapter in LanRules.adapters) d.publicJson() else d.publicJson(packs.installed(d.adapter)) }))
                .put("adapters", adapterSchemas(packs)).put("device_packs", PACKS.fullPath).put("camera_inventory", "/v1/lan-cameras"))
        }
        lanEndpoint(ACTION_LOG) {
            authorize(this)
            fun text(name: String, max: Int) = query(name)?.takeIf { it.isNotEmpty() }?.also {
                if (it.length > max) throw ErrorCatalog.error("invalid_parameter", "$name is too long.")
            }
            val limit = query("limit")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..500 } ?: throw ErrorCatalog.error("invalid_parameter", "limit must be 1 to 500.") } ?: 100
            val order = query("order") ?: "desc"
            if (order != "asc" && order != "desc") throw ErrorCatalog.error("invalid_parameter", "order must be asc or desc.")
            val since = query("since")?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw ErrorCatalog.error("invalid_parameter", "since must be a log entry id.") }
            val cursor = query("cursor")?.let { cc.bridgit.app.log.LogQuery.decodeCursor(it) ?: throw ErrorCatalog.error("invalid_parameter", "cursor is not valid.") }
            val out = withContext(Dispatchers.IO) {
                LanActionLog.read({ app.log.query(it) }, agent!!.id, text("device", 80), text("op_id", 40), since, limit, order == "asc", cursor, ::isoAt)
            }
            params.put("returned", out.getInt("count"))
            Reply.Json(out)
        }
        lanEndpoint(PACKS) {
            authorize(this)
            Reply.Json(withContext(Dispatchers.IO) { DevicePackStore.get(app).status() })
        }
        lanEndpoint(PACKS_CHECK) {
            authorize(this)
            Reply.Json(withContext(Dispatchers.IO) { withTimeout(40_000) { DevicePackStore.get(app).check() } })
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
            val op = lanOp(req, "enroll", null, null, JSONObject().put("host", body.optString("host")).put("adapter", body.optString("adapter"))
                .put("name", body.optString("name")).put("allow_http", body.opt("allow_http") == true))
                .device("new", body.optString("name").ifBlank { null }, body.optString("adapter").ifBlank { null }, body.optString("host").ifBlank { null })
            logged(op, { it.optJSONObject("verification") }) { operation(req) { lan ->
                val host = body.getString("host"); lan.check(host)
                val adapter = body.getString("adapter")
                val isPack = adapter !in LanRules.adapters
                val name = body.getString("name"); require(name.isNotBlank() && name.length <= 60)
                val user = body.optString("username"); val pass = body.optString("password")
                require(user.length <= 64 && pass.length <= 128 && !user.contains(':') && user.none { it < ' ' })
                // Explicit acknowledgement is required before credentials are sent over cleartext LAN HTTP.
                val http = body.opt("allow_http") == true
                if (adapter == "tapo_onvif") require(http) { "This ONVIF adapter uses local HTTP; allow_http is required." }
                if (isPack && (user.isNotEmpty() || pass.isNotEmpty())) throw PackEngine.PackError("Device packs don't take credentials; leave username and password out.", false)
                val draft = LanDevice("net:${UUID.randomUUID()}", name, host, adapter, agent!!.id, user, pass, http, System.currentTimeMillis())
                val devices = app.lanDevices.all()
                require(devices.size < 64 && devices.count { it.agentId == agent!!.id } < 32)
                app.appDevices // Complete legacy migration before adding any new camera.
                var camera: LanCamera? = null
                var extra = JSONObject()
                val result = if (isPack) {
                    val pack = DevicePackStore.get(app).pack(adapter)
                    val net = LanPackNet(lan) { authorize(req) }
                    val bound = PackEngine.bind(pack, host, net)
                    val status = PackEngine.run(pack, bound, "status", JSONObject(), net)
                    extra = JSONObject().put("pack_version", pack.version).put("bound", bound.json())
                    JSONObject().put("identity", bound.identity).put("model", bound.model).put("advertised_name", bound.name)
                        .put("pack", pack.id).put("pack_version", pack.version).put("status", status)
                } else if (adapter == "tapo_onvif") {
                    cameraAuthorized(req)
                    camera = LanCamera("lan:${UUID.randomUUID()}", name, host, user, pass, talkPassword=body.optString("talk_cloud_password"))
                    val info = Onvif(camera!!, app) { cameraAuthorized(req) }.info()
                    val report=cc.bridgit.app.lancamera.CameraProbe.inspect(app,camera!!,info){cameraAuthorized(req)}
                    require(camera!!.talkPassword.isEmpty() || report.getJSONObject("tapo_talk").optString("state")=="authenticated")
                    camera = camera!!.verified(info).assessed(report)
                    JSONObject().put("camera", camera!!.id).put("model", info.model).put("capabilities",report)
                } else LanAdapters.adapter(adapter, LanAdapters.transport(lan, draft) { authorize(req) }).execute(draft, "status", 0)
                authorize(req); currentCoroutineContext().ensureActive()
                val device = LanDevice(draft.id, name, host, adapter, agent!!.id, user, pass, http, System.currentTimeMillis(), result.optString("identity"), extra)
                if (camera != null) {
                    cameraAuthorized(req)
                    app.lanCameras.save(camera!!)
                }
                try { app.lanDevices.save(device) }
                catch (e: Exception) { camera?.let { app.lanCameras.remove(it.id) }; throw e }
                op.device(device)
                device.publicJson().put("availability", "online").put("verification", result)
            } }
        }
        lanEndpoint(REMOVE) {
            val op = lanOp(this, "remove", null, null, JSONObject().put("device", body.optString("device")))
            knownDevice(op, body.optString("device"), agent?.id)
            logged(op, { null }) {
                authorize(this)
                val d = owned(body.getString("device"), agent!!.id)
                app.lanDevices.remove(d.id)
                app.appDevices.remove(d.id)
                Reply.Json(JSONObject().put("removed", d.id).put("camera_note", "Existing Devices-tab entries are managed separately by the owner."))
            }
        }
        lanEndpoint(ACTION) {
            val req = this
            val op = lanOp(req, "action", body.optString("action").ifEmpty { null }, (body.opt("channel") as? Number)?.toInt() ?: 0,
                JSONObject().put("device", body.optString("device")).put("action", body.optString("action"))
                    .put("channel", body.opt("channel") ?: JSONObject.NULL).put("params", loggedParams(body)))
            knownDevice(op, body.optString("device"), agent?.id)
            logged(op) { operation(req) { lan ->
                val d = owned(body.getString("device"), agent!!.id); lan.check(d.host)
                val action = body.getString("action")
                val channel = if (!body.has("channel")) 0 else {
                    val n = body.get("channel") as? Number ?: throw IllegalArgumentException()
                    require(n.toDouble() == n.toInt().toDouble()); n.toInt()
                }
                val isPack = d.adapter !in LanRules.adapters
                if (!isPack) LanRules.validateAction(d.adapter, action, channel)
                val params = PackEngine.gather(null, body)
                if (!isPack && params.length() > 0) throw PackEngine.PackError("This adapter's actions take no params.", false)
                val result = if (isPack) runPack(req, lan, d, action, body, op)
                else if (d.adapter == "tapo_onvif") {
                    val c = LanCamera("lan:probe", d.name, d.host, d.username, d.password)
                    cameraAuthorized(req)
                    val info = Onvif(c, app) { owned(d.id, agent!!.id); cameraAuthorized(req) }.info(); JSONObject().put("model", info.model).put("relative_ptz", info.ptz)
                } else LanAdapters.adapter(d.adapter, LanAdapters.transport(lan, d) { owned(d.id, agent!!.id); authorize(req) }).execute(d, action, channel)
                JSONObject().put("device", d.id).put("action", action).put("availability", "online").put("checked_at", System.currentTimeMillis()).put("result", result)
            } }
        }
    }
    /**
     * A device-pack action. The enrollment's stored UPnP addresses are used; if the device refuses the connection
     * (it moved port after a restart) Bridgit finds it again by SSDP, checks it is the same device (UDN), saves the
     * new addresses and retries once. A timeout is not retried: the outcome is unknown.
     */
    private fun runPack(req: Req, lan: HostLan, d: LanDevice, action: String, body: JSONObject, op: LanActionLog.Op): JSONObject {
        val packs = DevicePackStore.get(app)
        val pack = packs.pack(d.adapter)
        val params = PackEngine.gather(pack.action(action), body)
        val net = LanPackNet(lan) { owned(d.id, req.agent!!.id); authorize(req) }
        fun rebind(): PackEngine.Bound {
            val b = PackEngine.bind(pack, d.host, net)
            if (d.identity.isNotEmpty() && b.identity != d.identity) throw PackEngine.PackError("A different device now answers at ${d.host}; remove it and enroll it again.", true)
            app.lanDevices.save(d.with(JSONObject(d.extra.toString()).put("bound", b.json()).put("pack_version", pack.version)))
            return b
        }
        val stored = PackEngine.Bound.read(d.extra.optJSONObject("bound"))
        val bound = if (stored == null || pack.describe.services.keys.any { it !in stored.controls }) {
            op.step("bind", "looked up the device's control addresses (${pack.id} ${pack.version})"); rebind()
        } else stored
        val out = try { PackEngine.run(pack, bound, action, params, net) }
            catch (e: cc.bridgit.app.core.SafeHttp.Failure) {
                if (e.reason != cc.bridgit.app.core.SafeHttp.Reason.CONNECT) throw e
                op.step("retry", "the device refused the connection; finding it again and retrying once")
                PackEngine.run(pack, rebind(), action, params, net)
            }
        if (d.extra.optString("pack_version") != pack.version && bound === stored)
            app.lanDevices.save(d.with(JSONObject(d.extra.toString()).put("pack_version", pack.version)))
        return out.put("pack", pack.id).put("pack_version", pack.version)
    }
    /** The action's parameters for the outlet log: params plus any top-level ones (A0212: both are accepted). */
    private fun loggedParams(body: JSONObject): Any {
        val flat = body.keys().asSequence().filter { it !in PackEngine.RESERVED_BODY }.toList()
        if (flat.isEmpty()) return body.opt("params") ?: JSONObject.NULL
        val out = (body.opt("params") as? JSONObject)?.let { JSONObject(it.toString()) } ?: JSONObject()
        flat.forEach { if (!out.has(it)) out.put(it, body.get(it)) }
        return out
    }
    /** Names the device in the log before the operation starts, when it is this agent's enrollment. */
    private fun knownDevice(op: LanActionLog.Op, id: String, agentId: String?) {
        val d = runCatching { app.lanDevices.all().firstOrNull { it.id == id && it.agentId == agentId } }.getOrNull()
        if (d != null) op.device(d) else if (id.isNotEmpty()) op.device(id)
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
        val DISCOVER = EndpointSpec("POST", "/lan/discover", "lan_discovery", "Network Diagnostics: bounded discovery on the Android host's direct local network", capture = true,
            body = listOf(Param("hosts", "array", "Optional literal private IPv4 targets, at most 254; no other networks"), Param("subnet", "boolean", "Probe the direct /24.. /30 subnet on TCP 80,443,554,2020; default false")),
            example = "POST /v1/lan/discover {\"hosts\":[\"192.168.1.1\"]}", returns = "network, total_devices, total_categories, categories[id,title,count,device_ids], devices[id,name,type,category,model,identifier,services,brand,verified=false,enrolled=false], observations, methods, limits, complete=false; untrusted partial observations, never a complete online inventory")
        val INVENTORY = EndpointSpec("GET", "/lan/devices", "lan_control", "This agent's enrolled devices and adapter/action schemas",
            example = "GET /v1/lan/devices", returns = "devices[id,name,host,adapter,enrolled,verified_at,availability=not_checked,actions[action,method,path,body,params]], adapters; device-pack actions list params[name,type,required,min,max|max_length] (empty when none) and their body carries example params; no credentials, no probe")
        val ENROLL = EndpointSpec("POST", "/lan/devices/enroll", "lan_enrollment", "Verify a supported device with authorized credentials and enroll it for this agent", capture = true, idempotent = true,
            body = listOf(Param("host", "string", "Direct LAN literal IPv4", true), Param("adapter", "string", "A built-in adapter (${LanRules.adapters.joinToString()}) or a device pack id from GET /v1/lan/packs, e.g. upnp_av for speakers; packs download on first use", true), Param("name", "string", "Owner's device label, 1..60 chars", true),
                Param("username", "string", "Authorized local device/router account"), Param("password", "string", "Authorized local password, never returned"), Param("talk_cloud_password", "string", "Optional separate authorized Tapo cloud password, encrypted privately; passive authentication only, no speaker playback"), Param("allow_http", "boolean", "Explicitly permit unencrypted LAN HTTP, including supplied credentials. Default false (HTTPS with normal certificate validation). Required for Tapo ONVIF.")),
            privateFields = setOf("username", "password", "talk_cloud_password"), example = "POST /v1/lan/devices/enroll {\"host\":\"192.168.1.20\",\"adapter\":\"shelly_gen1\",\"name\":\"Desk lamp\",\"allow_http\":true}",
            returns = "Verified enrolled device and safe verification; saved only after successful protocol check. Tapo camera also appears in Devices tab, requiring existing lan_camera permission.")
        val REMOVE = EndpointSpec("POST", "/lan/devices/remove", "lan_enrollment", "Forget this agent's enrollment and stored credentials", idempotent = true,
            body = listOf(Param("device", "string", "Enrolled device id", true)), example = "POST /v1/lan/devices/remove {\"device\":\"net:example\"}", returns = "removed; Devices-tab entries remain owner-managed")
        val ACTION = EndpointSpec("POST", "/lan/devices/action", "lan_control", "Run a fixed supported action on this agent's enrolled device", capture = true, idempotent = true,
            body = listOf(Param("device", "string", "Enrolled device id", true), Param("action", "string", "Use this device's advertised actions (built-in: status, relay_on, relay_off, dhcp_leases; packs list theirs in GET /v1/lan/devices adapters)", true), Param("channel", "integer", "Shelly relay channel 0..3; must be advertised by device; default 0"),
                Param("params", "object", "Device-pack action parameters, e.g. {\"url\":\"https://example.com/chime.mp3\"} for play, {\"level\":30} for volume_set or {\"muted\":true} for mute_set. They may also be sent beside device and action ({\"level\":30}); the same name in both places must agree. Each action's parameters (name, type, required, bounds) are listed in GET /v1/lan/devices devices[].actions[].params")),
            example = "POST /v1/lan/devices/action {\"device\":\"net:example\",\"action\":\"volume_set\",\"params\":{\"level\":25}}", returns = "device, action, availability=online, checked_at, result; failures do not prove offline. Its own rate limit: ${cc.bridgit.app.api.RateLimiter.DEVICE_ACTIONS_PER_AGENT} actions a minute per agent, separate from captures. Camera snapshots/move use existing lan_camera endpoints.")
        val ACTION_LOG = EndpointSpec("GET", "/lan/devices/log", "lan_control", "The runtime log of this agent's device operations (outlet flow): every enroll, action and removal, in order, with trigger, device and outcome",
            query = listOf(Param("device", "string", "Only this enrolled device id"), Param("op_id", "string", "Only the entries of one operation"),
                Param("since", "integer", "Only entries after this log id"), Param("order", "string", "desc (newest first, default) or asc"),
                Param("limit", "integer", "1..500, default 100"), Param("cursor", "string", "next_cursor from the previous page; keep the same filters")),
            example = "GET /v1/lan/devices/log?device=net:example&order=asc",
            returns = "entries[id,ts,op_id,op(enroll|action|remove),phase(started|bind|retry|ok|failed|unknown_outcome),outcome,action,channel,device{id,name,adapter,host},trigger{agent_id,agent,via,request_id,method,endpoint},params,result,error,error_message,duration_ms,level,message], count, next_cursor, order, retention. Each operation writes started before anything reaches the device and one final entry; unknown_outcome means the device may have changed. Only your own entries; no credentials; URLs keep no query. Owner log retention applies; the same entries are in GET /v1/log?kind=lan")
        val PACKS = EndpointSpec("GET", "/lan/packs", "lan_discovery", "Device packs: downloadable device-family support, its versions and update state; no network scan",
            example = "GET /v1/lan/packs", returns = "engine_version, channel, index{serial,published_at,checked_at,last_error}, auto_update, packs[id,title,latest_version,installed_version,state,reason,match_services,actions]")
        val PACKS_CHECK = EndpointSpec("POST", "/lan/packs/check", "lan_discovery", "Check for device-pack updates now (at most once a minute) and update installed packs", idempotent = true,
            example = "POST /v1/lan/packs/check {}", returns = "Same as GET /v1/lan/packs; a failed check keeps the last good list and installed packs")
        val ALL = listOf(REMEMBERED, CATALOG, BRAND_LOGO, DISCOVER, INVENTORY, ENROLL, REMOVE, ACTION, ACTION_LOG, PACKS, PACKS_CHECK)
        fun adapterSchemas(packs: DevicePackStore) = JSONArray(LanRules.adapters.map { id -> JSONObject().put("id", id).put("actions", JSONArray(LanRules.actions(id)))
            .put("implemented", true).put("enrollment", ENROLL.fullPath)
            .put("limitations", when (id) { "shelly_gen1" -> "Gen1 HTTP Basic or no auth; relay devices only, 1..4 channels; MAC checked before control"
                "openwrt_ubus" -> "rpcd ubus session login; system.board and dhcp.ipv4leases ACLs; read-only, no router settings changes"
                else -> "Tapo-style fixed ONVIF port 2020 and RTSP stream1/2; not arbitrary ONVIF cameras; capture and PTZ require lan_camera" }) } +
            DevicePackStore.known.map { e -> (packs.installed(e.id)?.schema() ?: JSONObject().put("id", e.id).put("version", e.version).put("title", e.title)
                .put("actions", JSONObject.NULL).put("note", "Downloads on first enroll; actions are listed after that"))
                .put("device_pack", true).put("latest_version", e.version).put("match_services", JSONArray(e.servicesAny))
                .put("implemented", true).put("enrollment", ENROLL.fullPath).put("credentials", "none") })
    }
}
