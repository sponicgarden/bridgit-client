package cc.bridgit.app.clientcore

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiDocs
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.relay.RelayDispatcher
import cc.bridgit.app.relay.RelayStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Client Core door fixtures (contracts/client-core/fixtures/, copied here by tools/client-core/gen.mjs).
 * Each case seeds a fresh app from the state-seeding vocabulary (schemas/seed.schema.json), sends the
 * request through the door's real entry point and compares the answer. A seed key this runner can't
 * apply fails the case: a fixture marked "pass" for Android never passes by being skipped.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
// No Conscrypt: its native library ships only for macOS in this build, and the CI runner is Linux.
@ConscryptMode(ConscryptMode.Mode.OFF)
class DoorFixturesTest(private val path: String) {
    companion object {
        private fun resource(p: String) = DoorFixturesTest::class.java.getResource("/client-core/$p")!!.readText()

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val index = JSONArray(resource("fixtures/index.json"))
            return (0 until index.length()).map { arrayOf<Any>(index.getString(it)) }
        }

        // --- w2 (mcp grant/idempotency refusals, MCP wire driver) ---
        private val W2_SEED = setOf("control_grants", "control_features", "idempotency")
        private val W2_SETTINGS = setOf("email_grace_expired")
        private val W2_AGENT = setOf("rotated_after_snapshot")
        private val W2_EXPECT = setOf("mcp")
        // --- end w2 ---
        // ---- relay_job / http_v2 additions (A0299 1.1, worker w3); declared first, the sets below use them ----
        private val W3_SEED = setOf("link_codes")
        private val W3_RELAY_CONFIG = setOf("claim_until", "portal_tier")
        // w1 (A0299 1.1): links, the owner's link pause, failed sign-ins, location access, rotated keys (control grants are w2's).
        private val W1_SEED = setOf("links", "link_pause", "failed_sign_ins", "location_access")
        private val W1_AGENT = setOf("rotated")
        private val W1_LINK = setOf("agent", "tier", "state", "services", "role", "address")

        private val SUPPORTED_SEED = setOf("settings", "agents", "relay", "switches", "media_access", "media") + W2_SEED + W3_SEED + W1_SEED
        private val SUPPORTED_SETTINGS = setOf("listener_enabled", "email_verified", "device_lock", "relay_signed_only", "settings_api") + W2_SETTINGS
        private val SUPPORTED_AGENT = setOf("ref", "name", "connection", "caps", "api_access", "paused", "super_powers", "fixer", "maximum_control", "removed", "devices") + W2_AGENT + W1_AGENT
    }

    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private val agents = HashMap<String, Agent>()
    private val keys = HashMap<String, String>()
    /** Relay configs as seeded: a job already on its way keeps the config it was made with, even if the agent is then removed. */
    private val relayConfigs = HashMap<String, JSONObject>()

    private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet()

    private fun seed(s: JSONObject) {
        s.keys().forEach { if (it !in SUPPORTED_SEED) fail("$path: the Android runner can't seed '$it' yet") }
        s.optJSONObject("settings")?.let { set ->
            set.keys().forEach { if (it !in SUPPORTED_SETTINGS) fail("$path: the Android runner can't seed setting '$it'") }
            if (set.has("listener_enabled")) app.settings.listenerEnabled = set.getBoolean("listener_enabled")
            if (set.has("email_verified")) app.settings.emailVerifiedAt = if (set.getBoolean("email_verified")) System.currentTimeMillis() else 0
            if (set.has("device_lock")) app.settings.securityDeviceLock = set.getBoolean("device_lock")
            if (set.has("relay_signed_only")) app.settings.securityRelaySignedOnly = set.getBoolean("relay_signed_only")
            if (set.has("settings_api")) app.settings.setOn("settings_api", set.getBoolean("settings_api"))
            seedW2Settings(set)
        }
        s.optJSONObject("switches")?.let { sw -> sw.keys().forEach { app.settings.setOn(it, sw.getBoolean(it)) } }
        when (s.optString("media_access", "none")) {
            "full" -> shadowOf(app).grantPermissions(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
            "selected" -> shadowOf(app).grantPermissions(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
        s.optJSONArray("media")?.let { list ->
            FakeMediaStore.install((0 until list.length()).map { i ->
                val m = list.getJSONObject(i)
                if (m.getString("scope") != "library") fail("$path: the Android runner can seed only library media")
                fun text(k: String) = if (m.has(k) && !m.isNull(k)) m.getString(k) else null
                FakeMediaStore.Row(m.getLong("id"), m.getString("kind") == "video", text("name"), m.optLong("size", 1024),
                    m.optLong("added_at", 1_762_800_000), text("owner_package"), text("relative_path"), m.optBoolean("pending"),
                    // modified_seconds_ago: date_modified relative to the real clock the app checks against; absent is long settled.
                    modifiedAt = if (m.has("modified_seconds_ago")) System.currentTimeMillis() / 1000 - m.getLong("modified_seconds_ago") else null)
            })
        }
        s.optJSONArray("agents")?.let { list ->
            for (i in 0 until list.length()) {
                val a = list.getJSONObject(i)
                a.keys().forEach { if (it !in SUPPORTED_AGENT) fail("$path: the Android runner can't seed agent field '$it'") }
                val connection = if (a.optString("connection") == "relay") Connection.RELAY else Connection.DIRECT
                val (added, key) = w1LinkAgent(s, a) ?: app.agents.add(a.optString("name", "Fixture ${a.getString("ref")}"), connection)
                keys[a.getString("ref")] = key
                a.optJSONArray("devices")?.let { d -> for (j in 0 until d.length()) app.agents.allowDevice(added.id, d.getString(j)) }
                agents[a.getString("ref")] = app.agents.update(added.id) { cur ->
                    cur.copy(
                        caps = if (!a.has("caps")) cur.caps else if (a.isNull("caps")) null else strings(a.getJSONArray("caps")),
                        apiAccess = a.optBoolean("api_access", cur.apiAccess),
                        paused = a.optBoolean("paused", cur.paused),
                        superPowers = a.optBoolean("super_powers", cur.superPowers),
                        fixer = a.optBoolean("fixer", cur.fixer),
                        maximumControl = a.optBoolean("maximum_control", cur.maximumControl),
                    )
                }!!
            }
        }
        s.optJSONObject("relay")?.let { r ->
            val store = RelayStore(app)
            store.enabled = r.optBoolean("enabled", true)
            val configs = r.optJSONArray("configs") ?: JSONArray()
            for (i in 0 until configs.length()) {
                val c = configs.getJSONObject(i)
                c.keys().forEach { if (it !in setOf("agent", "mode", "routes", "tools") + W3_RELAY_CONFIG) fail("$path: the Android runner can't seed relay config '$it'") }
                val id = agent(c.getString("agent")).id
                store.addAgent(id, c.getString("mode"), null, strings(c.optJSONArray("routes")), strings(c.optJSONArray("tools")))
                relayConfigs[id] = store.agents().single { it.getString("id") == id }
                seedW3RelayConfig(id, c)
            }
        }
        seedW2(s)
        s.optJSONArray("link_codes")?.let { seedLinkCodes(it) }
        w1Seed(s)
        // Removed last, so the agent's relay config exists and the request carries its old record.
        s.optJSONArray("agents")?.let { list -> for (i in 0 until list.length()) list.getJSONObject(i).takeIf { it.optBoolean("removed") }
            ?.let { app.agents.remove(agent(it.getString("ref")).id) } }
    }

    private fun agent(ref: String) = agents[ref] ?: error("$path: no seeded agent '$ref'")

    private class Answer(val status: Int, val body: JSONObject)

    private fun send(door: String, principal: JSONObject, request: JSONObject): Answer = runBlocking {
        val svc = Robolectric.buildService(BridgeService::class.java).create().get()
        val api = ApiServer(svc, emptyList(), 0).also { it.prepareRelay() }
        w1BeforeSend(api)
        try {
            val a = agent(principal.getString("agent"))
            val query = request.optJSONObject("query")?.let { q -> q.keys().asSequence().associateWith { q.getString(it) } } ?: emptyMap()
            val body = request.optJSONObject("body") ?: JSONObject()
            when (door) {
                // The HTTP door: its own checks through the seam the listener uses (ApiServer.admitHttp), then the same handler.
                // satellite_forward (w1): the same HTTP checks, then where a call naming another device goes (ApiServer.forwardTarget).
                "http_v1", "satellite_forward" -> {
                    val key = when (principal.optString("key", "agent")) { "none" -> null; "wrong" -> "brg_fixtureWrongKey0000"; else -> keys[principal.getString("agent")] }
                    val reqHeaders = w1Headers(request) + mapOf("Authorization" to key?.let { "Bearer $it" })
                    val method = request.getString("method")
                    val (spec, params) = endpoint(method, request.getString("path"))
                    val source = principal.optString("source", "100.64.0.2")
                    try {
                        val admitted = api.admitHttp(cc.bridgit.app.core.request.CoreRequest("http_v1", method, spec.fullPath, source) { h -> reqHeaders[h] }, spec)
                        if (door == "satellite_forward") w1Forward(api, spec, admitted.agent, reqHeaders, query)
                        w1Apply("admission")
                        val out = w1Run { api.dispatch(spec, admitted.agent!!, source, query, params, body, request.optString("idempotency_key").ifEmpty { null }, via = "http") }
                        Answer(out.status, JSONObject(String(out.bytes)))
                    } catch (e: ApiError) { Answer(e.http, e.toJson("fixture")) }
                }
                "mcp" -> if (request.optString("wire") == "mcp_rpc") mcpWire(api, principal, request) else {
                    val (spec, params) = endpoint(request.getString("method"), request.getString("path"))
                    w1Apply("admission")
                    val out = w1Run { api.dispatch(spec, a, principal.optString("source", "100.64.0.2"), query, params, body,
                        request.optString("idempotency_key").ifEmpty { null }, via = request.optString("via").ifEmpty { "mcp" }) }
                    Answer(out.status, JSONObject(String(out.bytes)))
                }
                "relay_job" -> relayJob(api, a, principal, request)
                "http_v2" -> httpV2(api, principal, request)
                else -> error("$path: the Android runner has no driver for door '$door' yet")
            }
        } finally { api.stop(); svc.onDestroy() }
    }

    /** The endpoint for a concrete path, with its path parameters ("/v1/gallery/video/9" -> {id: 9}). */
    private fun endpoint(method: String, concrete: String): Pair<EndpointSpec, Map<String, String>> {
        ApiDocs.find("$method $concrete")?.let { return it to emptyMap() }
        val parts = concrete.removePrefix("/v1").split('/')
        for (spec in Endpoints.all.filter { it.method == method }) {
            val tpl = spec.path.split('/')
            if (tpl.size != parts.size) continue
            val params = HashMap<String, String>()
            val fits = tpl.indices.all { i ->
                val param = tpl[i].startsWith("{") && tpl[i].endsWith("}") && parts[i].isNotEmpty()
                if (param) params[tpl[i].trim('{', '}')] = parts[i]
                param || tpl[i] == parts[i]
            }
            if (fits) return spec to params
        }
        error("$path: no endpoint $method $concrete")
    }

    // ---- relay_job / http_v2 drivers and seeds (A0299 1.1, worker w3) ----

    /** fixture.prior_requests: identical requests sent first through the same door instance (rate limits); answers ignored. */
    private var priorRequests = 0
    /** fixture.job: relay job timing, relative to now (deadline_in_ms, default 10 s; age_ms, default 0). */
    private var job: JSONObject? = null

    private fun readW3Fields(f: JSONObject) {
        priorRequests = f.optInt("prior_requests", 0)
        job = f.optJSONObject("job")
        val door = f.getString("door")
        if (f.has("prior_requests") && door !in setOf("relay_job", "http_v2")) fail("$path: the Android runner applies prior_requests only on relay_job and http_v2")
        if (job != null && door != "relay_job") fail("$path: the Android runner applies job only on relay_job")
        job?.keys()?.forEach { if (it !in setOf("deadline_in_ms", "age_ms")) fail("$path: the Android runner can't apply job.$it") }
    }

    private fun seedW3RelayConfig(id: String, c: JSONObject) {
        // claim_until (epoch ms) is part of the stored config the relay job carries; RelayStore only sets it relative to now.
        if (c.has("claim_until")) relayConfigs[id] = JSONObject(relayConfigs.getValue(id).toString()).put("claim_until", c.getLong("claim_until"))
        c.optJSONObject("portal_tier")?.let { t ->
            t.keys().forEach { if (it !in setOf("tier", "expires_at")) fail("$path: the Android runner can't seed portal_tier.$it") }
            val tier = cc.bridgit.app.link.LinkTier.values().firstOrNull { it.wire == t.getString("tier") } ?: error("$path: unknown tier ${t.getString("tier")}")
            cc.bridgit.app.link.PortalTiers(app).set(cc.bridgit.app.link.PortalTiers.viewer(id),
                cc.bridgit.app.link.PortalTiers.Entry(tier, expiresAt = if (t.has("expires_at")) t.getLong("expires_at") else null))
        }
    }

    /** link_codes: a real link code (Links.makeCode) with the relay on, so it gets its claim-only relay agent, seeded as [ref]. */
    private fun seedLinkCodes(list: JSONArray) {
        val store = RelayStore(app)
        if (!store.enabled) fail("$path: link_codes needs relay.enabled")
        if (app.links.registeredName() == null)
            app.platform.store.setting("bridgit_name", JSONObject().put("name", "fixture-target").put("visible", true).toString(), true)
        for (i in 0 until list.length()) {
            val c = list.getJSONObject(i)
            c.keys().forEach { if (it !in setOf("ref", "services")) fail("$path: the Android runner can't seed link_codes.$it") }
            val made = app.links.makeCode(strings(c.optJSONArray("services")).ifEmpty { setOf("camera") }, setOf("in"), emptySet(), cc.bridgit.app.link.Granter.person("fixture-target"))
            val claimAgent = app.links.codes().single { it.id == made.getString("code_id") }.relayAgent ?: error("$path: the link code got no relay agent")
            agents[c.getString("ref")] = app.agents.get(claimAgent)!!
            relayConfigs[claimAgent] = store.agents().single { it.getString("id") == claimAgent }
        }
    }

    /** relay_job: RelayDispatcher.dispatch, the relay worker's entry, with the job's deadline and creation time. */
    private suspend fun relayJob(api: ApiServer, a: Agent, principal: JSONObject, request: JSONObject): Answer {
        val config = relayConfigs[a.id] ?: error("$path: no relay config seeded for ${principal.getString("agent")}")
        val dispatcher = RelayDispatcher(app, api)
        suspend fun once(): Answer {
            val now = System.currentTimeMillis()
            val deadline = now + (job?.optLong("deadline_in_ms", 10_000) ?: 10_000)
            val created = now - (job?.optLong("age_ms", 0) ?: 0)
            return try {
                val out = dispatcher.dispatch(config, JSONObject(request.toString()), deadline, created, "fixture")
                val bytes = out.optString("body_base64").takeIf { it.isNotEmpty() }?.let { java.util.Base64.getDecoder().decode(it) }
                Answer(out.getInt("status"), bytes?.let { JSONObject(String(it)) } ?: out.optJSONObject("body") ?: out.optJSONObject("mcp") ?: JSONObject())
            } catch (e: ApiError) { Answer(e.http, e.toJson("fixture")) }
        }
        repeat(priorRequests) { once() }
        return once()
    }

    /**
     * http_v2: PlatformApi.admit (the checks authenticate runs on every call, with the socket peer and headers), then
     * what the route runs: POST /v2/call through PlatformApi.callTool, the route's own body.
     */
    private suspend fun httpV2(api: ApiServer, principal: JSONObject, request: JSONObject): Answer {
        val platform = cc.bridgit.app.platform.PlatformApi(app, api)
        val key = when (principal.optString("key", "agent")) { "none" -> null; "wrong" -> "brg_fixtureWrongKey0000"; else -> keys[principal.getString("agent")] }
        val headers = HashMap<String, String?>()
        request.optJSONObject("headers")?.let { h -> h.keys().forEach { headers[it] = h.getString(it) } }
        if (!headers.containsKey("Authorization")) headers["Authorization"] = key?.let { "Bearer $it" }
        val source = principal.optString("source", "100.64.0.2")
        val method = request.getString("method"); val route = request.getString("path")
        if (method != "POST" || route != "/v2/call") fail("$path: the Android http_v2 driver runs only POST /v2/call")
        suspend fun once(): Answer = try {
            val a = platform.admit(source) { h -> headers[h] }
            Answer(200, platform.callTool(a, JSONObject((request.optJSONObject("body") ?: JSONObject()).toString()), request.optString("idempotency_key").ifEmpty { null }, JSONObject()))
        } catch (e: ApiError) { Answer(e.http, e.toJson("fixture")) }
        repeat(priorRequests) { once() }
        return once()
    }

    private object Absent

    /** The value at a dotted path (numbers index arrays), or [Absent]. */
    private fun at(body: Any?, dotted: String): Any? = dotted.split('.').fold(body) { cur, k ->
        when (cur) {
            is JSONObject -> if (cur.has(k)) cur.get(k) else Absent
            is JSONArray -> k.toIntOrNull()?.takeIf { it in 0 until cur.length() }?.let { cur.get(it) } ?: Absent
            else -> Absent
        }
    }

    private fun same(want: Any?, have: Any?): Boolean = when (want) {
        is JSONObject -> have is JSONObject && want.length() == have.length() && want.keys().asSequence().all { have.has(it) && same(want.get(it), have.get(it)) }
        is JSONArray -> have is JSONArray && want.length() == have.length() && (0 until want.length()).all { same(want.get(it), have.get(it)) }
        is Number -> have is Number && want.toDouble() == have.toDouble()
        else -> want == have
    }

    @Test fun case() {
        val f = JSONObject(resource("fixtures/$path"))
        val state = f.getJSONObject("platforms").optString("android", "todo")
        if (state != "pass") return  // gap and todo cases are listed, not run; the pack check counts them.
        readW3Fields(f)
        w1During = f.optJSONObject("during")
        seed(f.getJSONObject("seed"))
        val got = send(f.getString("door"), f.getJSONObject("principal"), f.getJSONObject("request"))
        if (w1During != null && !w1Applied) fail("$path: the ${f.getString("door")} driver never applied the fixture's during changes")
        val want = f.getJSONObject("expect")
        // Expectations this runner can't check fail the case rather than pass unchecked.
        want.keys().forEach { if (it !in setOf("status", "error", "reason", "body_has", "body_paths", "body_lacks") + W2_EXPECT) fail("$path: the Android runner can't check expect.$it yet") }
        assertEquals("$path status: ${got.body}", want.getInt("status"), got.status)
        if (want.has("error")) assertEquals("$path error", want.getString("error"), got.body.optString("error"))
        if (want.has("reason")) assertEquals("$path reason", want.getString("reason"), got.body.optString("reason"))
        want.optJSONArray("body_has")?.let { keys -> strings(keys).forEach { assertTrue("$path: reply lacks $it", got.body.has(it)) } }
        want.optJSONObject("body_paths")?.let { paths -> paths.keys().forEach { p ->
            assertTrue("$path: $p is ${at(got.body, p)}, want ${paths.get(p)} in ${got.body}", same(paths.get(p), at(got.body, p)))
        } }
        want.optJSONArray("body_lacks")?.let { paths -> strings(paths).forEach { p -> assertTrue("$path: reply has $p: ${got.body}", at(got.body, p) === Absent) } }
        want.optJSONObject("mcp")?.let { checkMcp(it, got) }
    }

    // --- w2: seeds, the MCP wire driver and its checks ---

    private fun seedW2Settings(set: JSONObject) {
        // The owner's first agent call was more than 24 hours ago (EmailGate.GRACE_MS) and no address is verified.
        if (set.optBoolean("email_grace_expired")) {
            app.settings.emailVerifiedAt = 0
            app.settings.emailGraceStartedAt = System.currentTimeMillis() - cc.bridgit.app.alerts.EmailGate.GRACE_MS - 60_000
        }
    }

    private fun seedW2(s: JSONObject) {
        s.optJSONArray("control_grants")?.let { list -> for (i in 0 until list.length()) {
            val g = list.getJSONObject(i)
            app.platform.grant(agent(g.getString("agent")).id, g.getString("scope"), g.optLong("until", Long.MAX_VALUE))
        } }
        s.optJSONObject("control_features")?.let { f -> f.keys().forEach { cc.bridgit.app.platform.Capabilities.setEnabled(app, it, f.getBoolean(it)) } }
        s.optJSONObject("idempotency")?.let { seedIdempotency(it) }
        // Rotated after the runner kept its snapshot in [agents]: the door then checks a request whose key changed under it.
        s.optJSONArray("agents")?.let { list -> for (i in 0 until list.length()) list.getJSONObject(i).takeIf { it.optBoolean("rotated_after_snapshot") }
            ?.let { app.agents.rotate(agent(it.getString("ref")).id) } }
    }

    /** The shared IdempotencyCache ApiServer uses (app.requests.idempotency), in the state the fixture names. */
    private fun seedIdempotency(o: JSONObject) {
        o.keys().forEach { if (it !in setOf("entries", "full")) fail("$path: the Android runner can't seed idempotency '$it'") }
        val cache = app.requests.idempotency
        val entries = o.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val id = agent(e.getString("agent")).id
            val key = e.getString("key"); val route = e.getString("route")
            assertTrue("$path: idempotency key $key was already claimed", cache.claim(id, key, route) == cc.bridgit.app.api.IdempotencyCache.Claim.New)
            when (val state = e.getString("state")) {
                "running" -> {}
                // A finished result whose body didn't fit the 48 MB budget keeps only its marker, so a repeat is Expired.
                "expired" -> cache.finish(id, key, 200, "application/json", ByteArray(48 * 1024 * 1024 + 1), emptyMap(), true)
                else -> fail("$path: the Android runner can't seed idempotency state '$state'")
            }
        }
        if (o.optBoolean("full")) {
            var n = 0
            while (cache.claim("fixture-filler", "filler-key-${n++}") != cc.bridgit.app.api.IdempotencyCache.Claim.Full)
                if (n > 1_000_000) fail("$path: the idempotency cache never filled")
        }
    }

    /**
     * POST /v1/mcp as an MCP client sends it: the HTTP door's own checks for the MCP route (ApiServer.admitHttp), then
     * McpDispatch.handle with the JSON-RPC envelope in request.body. The answer is the reply's HTTP status and, as body,
     * the envelope's result (tools/list: {tools}, tools/call: {content, isError, structuredContent}) or {"rpc_error": error}.
     */
    private suspend fun mcpWire(api: ApiServer, principal: JSONObject, request: JSONObject): Answer {
        val source = principal.optString("source", "100.64.0.2")
        val key = keys[principal.getString("agent")]
        val envelope = request.getJSONObject("body")
        val admitted = try {
            api.admitHttp(cc.bridgit.app.core.request.CoreRequest("http_v1", "POST", "/v1/mcp", source) { h -> if (h == "Authorization") "Bearer $key" else null }, Endpoints.MCP)
        } catch (e: ApiError) { return Answer(e.http, e.toJson("fixture")) }
        val req = cc.bridgit.app.api.Req(Endpoints.MCP, admitted.agent, "fixture", source, envelope, null, emptyMap(), emptyMap()) { null }
        return when (val reply = api.mcp.handle(req)) {
            is cc.bridgit.app.api.Reply.Text -> {
                val o = if (reply.text.isEmpty()) JSONObject() else JSONObject(reply.text)
                Answer(reply.status, o.optJSONObject("result") ?: o.optJSONObject("error")?.let { JSONObject().put("rpc_error", it) } ?: o)
            }
            else -> error("$path: McpDispatch answered ${reply.javaClass.simpleName}, not JSON text")
        }
    }

    private fun checkMcp(m: JSONObject, got: Answer) {
        m.keys().forEach { if (it !in setOf("tools_include", "tools_exclude")) fail("$path: the Android runner can't check expect.mcp.$it yet") }
        val tools = got.body.optJSONArray("tools")
        if (tools == null) { fail("$path: no tools list in ${got.body}"); return }
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet()
        strings(m.optJSONArray("tools_include")).forEach { assertTrue("$path: tools/list lacks $it", it in names) }
        strings(m.optJSONArray("tools_exclude")).forEach { assertTrue("$path: tools/list has $it", it !in names) }
    }
    // --- end w2 ---

    // ---- w1 (A0299 1.1): links, mid-request changes, failed sign-ins, the satellite_forward door ----------------------
    // Kept in one block so parallel runner changes merge by hand.

    /** The fixture's "during": changes made after admission (at "admission") or once a long wait has started (at "wait"). */
    private var w1During: JSONObject? = null
    private var w1Applied = false
    private var w1FailedSignIns: JSONObject? = null
    private val w1LinkSeeds = HashMap<String, JSONObject>()

    /** Process-wide switches link seeding sets (as LinkTierTest does); reset after every case so none leaks. */
    @After fun w1Reset() {
        cc.bridgit.app.satellite.SatelliteStore.keyOverride = null
        cc.bridgit.app.link.LinkResolver.enabled = true
        cc.bridgit.app.link.LinkRegistry.Backend.enabled = true
        cc.bridgit.app.link.PublicLinkApproval.autoAnswer = null
        cc.bridgit.app.satellite.LinkPause.setHubs(false)
        cc.bridgit.app.satellite.LinkPause.setSatellites(false)
    }

    private fun w1Headers(request: JSONObject): Map<String, String?> =
        request.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } } ?: emptyMap()

    /**
     * A seeded agent named by a links entry is made by a real link claim (code, redeem, accept), as LinkTierTest does,
     * so it is a link agent with the link's grants and its key locked to the link's address.
     */
    private fun w1LinkAgent(s: JSONObject, a: JSONObject): Pair<Agent, String>? {
        val ref = a.getString("ref")
        val list = s.optJSONArray("links") ?: return null
        val l = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.getString("agent") == ref } ?: return null
        l.keys().forEach { if (it !in W1_LINK) fail("$path: the Android runner can't seed link field '$it'") }
        if (a.optString("connection", "direct") != "direct") fail("$path: the Android runner seeds only Tailscale links")
        cc.bridgit.app.satellite.SatelliteStore.keyOverride = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        cc.bridgit.app.link.LinkResolver.enabled = false
        cc.bridgit.app.link.LinkRegistry.Backend.enabled = false
        cc.bridgit.app.link.LinkRegistry.Outbox.clear(app)
        w1LinkSeeds[ref] = l
        val address = l.optString("address", "100.64.0.40")
        val services = strings(l.optJSONArray("services"))
        val tier = cc.bridgit.app.link.LinkTier.parseOrPublic(l.getString("tier"))
        return when (l.optString("role", "hub")) {
            // The other device is this one's hub: its link agent here carries the link's "in" direction.
            "hub" -> {
                val me = "fixture-target"
                app.platform.store.setting("bridgit_name", JSONObject().put("name", me).put("visible", true).toString(), true)
                val g = cc.bridgit.app.link.Granter.person(me)
                val made = app.links.makeCode(services, setOf("in"), emptySet(), g, tier = tier,
                    associateEmail = if (tier == cc.bridgit.app.link.LinkTier.ASSOCIATE) "fixture@example.com" else null)
                val code = app.links.redeem(cc.bridgit.app.link.LinkCode.keys(cc.bridgit.app.link.LinkCode.normalise(made.getString("code"))!!, me).claimToken)
                val r = app.links.acceptClaim(code, address, JSONObject().put("name", a.optString("name", "Fixture link")).put("bridgit_name", "fixture-peer"))
                val id = app.links.get(r.getString("link_id"))!!.ref
                app.agents.get(id)!! to r.getString("key")
            }
            // The other device is this one's satellite: its return agent here (SatelliteHub.pushHubInfo, without the network).
            "satellite" -> {
                if (tier != cc.bridgit.app.link.LinkTier.OWN || services.isNotEmpty()) fail("$path: the Android runner seeds satellite links as own links with the return grant only")
                val p = cc.bridgit.app.satellite.SatelliteLink.Parsed(address, 18471, "", "Fixture satellite", "bgi_" + "c".repeat(24))
                val sat = app.satellites.add(p, "brg_" + "z".repeat(40), JSONObject().put("name", "Fixture satellite"))
                val (added, key) = app.agents.add(a.optString("name", "Satellite Fixture"), Connection.DIRECT, limited = false)
                app.agents.update(added.id) { it.copy(caps = cc.bridgit.app.satellite.SatellitePolicy.RETURN_CAPS, fixer = false, superPowers = false,
                    boundIps = listOf(cc.bridgit.app.core.Net.normalise(address))) }
                app.satellites.update(sat.id) { it.copy(returnAgent = added.id, returnKey = key) }
                app.agents.get(added.id)!! to key
            }
            else -> { fail("$path: unknown link role ${l.optString("role")}"); null }
        }
    }

    private fun w1Seed(s: JSONObject) {
        s.optJSONArray("links")?.let { list -> for (i in 0 until list.length()) {
            val ref = list.getJSONObject(i).getString("agent")
            if (ref !in w1LinkSeeds) fail("$path: link agent '$ref' must also be a seeded agent")
        } }
        w1LinkSeeds.forEach { (ref, l) -> w1LinkState(ref, l.getString("state")) }
        s.optJSONObject("link_pause")?.let { p ->
            p.keys().forEach { if (it !in setOf("hubs", "satellites")) fail("$path: the Android runner can't seed link_pause '$it'") }
            if (p.has("hubs")) cc.bridgit.app.satellite.LinkPause.setHubs(p.getBoolean("hubs"))
            if (p.has("satellites")) cc.bridgit.app.satellite.LinkPause.setSatellites(p.getBoolean("satellites"))
        }
        w1FailedSignIns = s.optJSONObject("failed_sign_ins")
        when (s.optString("location_access", "none")) {
            "precise" -> shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            "approximate" -> shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            "none" -> {}
            else -> fail("$path: unknown location_access")
        }
        // A rotated key: the caller still holds the old one (and, in-process, the old record).
        s.optJSONArray("agents")?.let { list -> for (i in 0 until list.length()) list.getJSONObject(i).takeIf { it.optBoolean("rotated") }
            ?.let { app.agents.rotate(agent(it.getString("ref")).id) } }
    }

    /** Puts a seeded link in [state] through the calls the owner's screens and the other device use. */
    private fun w1LinkState(ref: String, state: String) {
        val l = w1LinkSeeds[ref] ?: error("$path: '$ref' is not a seeded link agent")
        val rec = app.links.byInAgent(agent(ref).id) ?: error("$path: no link record for '$ref'")
        if (l.optString("role", "hub") == "satellite" && state != "active") fail("$path: the Android runner seeds satellite links only active")
        val g = cc.bridgit.app.link.Granter.person("fixture-target")
        when (state) {
            "active" -> {}
            "paused" -> app.links.disconnect(rec.id, "in", g, pause = true)
            "disconnecting" -> app.links.disconnect(rec.id, "in", g)
            // The other device disconnected its side: this side's "in" is off at once.
            "revoked" -> app.links.receive(rec, JSONObject().put("link_id", rec.id).put("events", JSONArray().put(JSONObject().put("id", "lev_fixture1")
                .put("at", System.currentTimeMillis()).put("link_id", rec.id).put("direction", "out").put("event", "disconnected"))))
            // Disconnected here and past Undo: the link agent is removed for the disconnect.
            "disconnected" -> { app.links.disconnect(rec.id, "both", g); app.links.finishDue(System.currentTimeMillis() + cc.bridgit.app.link.Links.UNDO_MS + 1) }
            // Its end date passed a minute ago (before the expiry sweep removed it).
            "expired" -> {
                val prefs = app.getSharedPreferences("links_v1", android.content.Context.MODE_PRIVATE)
                val arr = JSONArray(prefs.getString("links", "[]"))
                for (i in 0 until arr.length()) arr.getJSONObject(i).takeIf { it.getString("id") == rec.id }?.put("expires_at", System.currentTimeMillis() - 60_000)
                check(prefs.edit().putString("links", arr.toString()).commit())
            }
            else -> fail("$path: the Android runner can't seed link state '$state'")
        }
    }

    private fun w1BeforeSend(api: ApiServer) {
        w1FailedSignIns?.let { f -> f.keys().forEach { addr -> repeat(f.getInt(addr)) { api.recordAuthFailure(addr) } } }
    }

    /** satellite_forward: the target logic the listener runs after the HTTP checks. Forwarding itself needs a network hop this runner doesn't drive. */
    private fun w1Forward(api: ApiServer, spec: EndpointSpec, agent: Agent?, headers: Map<String, String?>, query: Map<String, String>) {
        val p = cc.bridgit.app.satellite.SatellitePolicy
        val target = p.target(headers.entries.firstOrNull { it.key.equals(p.TARGET_HEADER, ignoreCase = true) }?.value, query[p.TARGET_QUERY])
        if (target == null || app.links.isSelf(target)) return
        api.forwardTarget(spec, agent, target)
        fail("$path: the call would be forwarded to $target; the Android runner doesn't drive the network hop")
    }

    /** Applies the fixture's "during" changes when their moment comes. */
    private fun w1Apply(at: String) {
        val d = w1During ?: return
        if (d.optString("at", "admission") != at) return
        w1Applied = true
        d.keys().forEach { if (it !in setOf("at", "agents", "links", "switches")) fail("$path: the Android runner can't apply during.$it") }
        d.optJSONArray("agents")?.let { list -> for (i in 0 until list.length()) {
            val c = list.getJSONObject(i)
            c.keys().forEach { if (it !in setOf("ref", "removed", "rotated", "paused")) fail("$path: the Android runner can't change agent field '$it' during a request") }
            val id = agent(c.getString("ref")).id
            if (c.has("paused")) app.agents.update(id) { it.copy(paused = c.getBoolean("paused")) }
            if (c.optBoolean("rotated")) app.agents.rotate(id)
            if (c.optBoolean("removed")) app.agents.remove(id)
        } }
        d.optJSONArray("links")?.let { list -> for (i in 0 until list.length()) list.getJSONObject(i).let { w1LinkState(it.getString("agent"), it.getString("state")) } }
        d.optJSONObject("switches")?.let { sw -> sw.keys().forEach { app.settings.setOn(it, sw.getBoolean(it)) } }
    }

    /** Runs the handler; with a "during" at "wait", makes the change once the request has been waiting for a while. */
    private suspend fun w1Run(block: suspend () -> ApiServer.Outcome): ApiServer.Outcome {
        if (w1During?.optString("at", "admission") != "wait") return block()
        return coroutineScope {
            val job = async { block() }
            delay(1500)
            if (!job.isActive) fail("$path: the request answered before the change during its wait: ${String(job.await().bytes)}")
            w1Apply("wait")
            job.await()
        }
    }
}
