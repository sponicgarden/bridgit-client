package cc.bridgit.app.portal

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.log.LogEntry
import cc.bridgit.app.relay.RelayDispatcher
import cc.bridgit.app.relay.RelayStore
import cc.bridgit.app.relay.Sealed
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Pair a portal, then call every portal endpoint through the real relay dispatcher (grant, rate limit, audit). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortalEndpointsTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp

    @Test fun `paired monitor reports only the selected editions watch activity`() = runBlocking {
        app.settings.listenerEnabled = true
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA)
        listOf("camera", "interactions", "watches").forEach { app.settings.setOn(it, true) }
        val store = RelayStore(app)
        store.base = "https://relay.example/d/${store.device}"
        store.enabled = true
        val viewer = PortalPairing.create(app, store, singleUse = false).agent
        val service = Robolectric.buildService(BridgeService::class.java).create().get()
        val api = ApiServer(service, emptyList(), 0).apply { prepareRelay() }
        val key = "room-change-watcher"
        suspend fun read(revision: Int): Pair<Int, JSONObject> {
            val now = System.currentTimeMillis()
            val request = JSONObject().put("method","GET").put("path","/v1/portal/applets/watches")
                .put("query",JSONObject().put("template",key).put("revision",revision))
            val result = RelayDispatcher(app,api).dispatch(store.agents().single { it.getString("id") == viewer.id },request,now+10_000,now,"monitor")
            return result.getInt("status") to JSONObject(String(Sealed.un64(result.getString("body_base64"))))
        }
        val pkg = cc.bridgit.app.applets.AppletPackage.parse(java.io.File("../site-content/public/recipes/$key/applet.json").readText())
        val pin = JSONObject().put("kind","template").put("template",key).put("revision",1)
        val proposal = app.applets.propose(pkg,null,pin,null,notify=false)
        val record = app.applets.approve(proposal.getString("id"),null)
        try {
            val owner = record.getString("agent_id")
            val watch = requireNotNull(app.watches.get(owner,record.getJSONObject("watches").getJSONObject("main").getString("watch_id")))
            synchronized(watch.recent) {
                watch.recent.addLast(JSONObject().put("at","2026-11-06T11:00:00Z").put("error","test").put("message","private diagnostic"))
            }
            val (status,snapshot) = read(1)
            assertEquals(snapshot.toString(),200,status)
            val row = snapshot.getJSONArray("watches").getJSONObject(0)
            assertEquals("main",row.getString("key"))
            assertEquals("scheduled",row.getString("state"))
            assertEquals("error",row.getString("last_outcome"))
            val latest = row.getJSONArray("recent_checks").getJSONObject(row.getJSONArray("recent_checks").length()-1)
            assertEquals(setOf("at","outcome"), latest.keys().asSequence().toSet())
            assertEquals("error", latest.getString("outcome"))
            assertFalse(row.has("tally"))
            assertFalse(snapshot.toString().contains("private diagnostic"))
            assertEquals(404,read(2).first)
            app.applets.setPaused(record.getString("id"),true)
            assertEquals("paused",read(1).second.getJSONArray("watches").getJSONObject(0).getString("state"))
            app.agents.update(owner) { it.copy(paused = true) }
            assertEquals(403,read(1).first)
        } finally {
            app.applets.remove(record.getString("id"))
        }
        Unit
    }

    @Test fun `paired portal reads every endpoint over the relay and is cut off when disconnected`() = runBlocking {
        app.platform.store
        app.settings.listenerEnabled = true
        val store = RelayStore(app)
        store.base = "https://relay.example/d/${store.device}"
        store.enabled = true
        val p = PortalPairing.create(app, store)
        assertEquals("Bridgit Portal", p.agent.name)
        assertEquals(PortalData.GRANTS, app.agents.get(p.agent.id)!!.caps)
        val link = PortalData.parsePairLink(p.link)!!
        assertEquals("https://relay.example", link["relay"]); assertEquals(store.device, link["device"])
        assertTrue(link["token"]!!.startsWith("rly_"))
        assertEquals(listOf(p.agent.id), PortalPairing.paired(app, store).map { it.id })
        assertEquals("Bridgit Portal 2", PortalPairing.nextName(app))

        // An agent action the log page should show, and a portal poll it should skip.
        app.log.append(LogEntry(ts = System.currentTimeMillis(), channel = "audit", kind = "request", agentId = p.agent.id, agent = p.agent.name,
            method = "POST", endpoint = "/v1/camera/snapshot", http = 200, result = "ok", message = "secret words"))

        val c = store.agents().single { it.getString("id") == p.agent.id }
        val svc = Robolectric.buildService(BridgeService::class.java).create().get()
        val api = ApiServer(svc, emptyList(), 0); api.prepareRelay()
        val d = RelayDispatcher(app, api)
        suspend fun get(path: String, query: JSONObject = JSONObject()): Pair<Int, JSONObject> {
            val now = System.currentTimeMillis()
            val r = d.dispatch(c, JSONObject().put("method", "GET").put("path", path).put("query", query), now + 10_000, now, "job")
            return r.getInt("status") to JSONObject(String(Sealed.un64(r.getString("body_base64"))))
        }

        assertTrue(PortalPairing.viewers(app, store).isEmpty())
        val (hs, home) = get("/v1/portal/home", JSONObject().put("client", "iPad"))
        // The call makes it a live viewer on Home, labelled with the device it said it is.
        val viewer = PortalPairing.viewers(app, store).single()
        assertEquals("iPad", viewer.kind); assertTrue(viewer.live)
        assertEquals("iPad", PortalPairing.sceneViewer(listOf(viewer))!!.getString("kind"))
        assertEquals("2 viewing", PortalPairing.sceneViewer(listOf(viewer, viewer))!!.getString("sub"))
        assertTrue(app.agents.get(p.agent.id)!!.lastUsedAt > 0)
        assertTrue(PortalPairing.viewers(app, store, System.currentTimeMillis() + PortalPairing.SHOWN_MS + 1000).isEmpty())
        get("/v1/portal/home", JSONObject().put("client", "<script>"))
        assertEquals("iPad", PortalPairing.viewers(app, store).single().kind)
        assertEquals(200, hs)
        listOf("device", "battery", "connections", "features", "listening", "agents_count", "nav").forEach { assertTrue(it, home.has(it)) }
        listOf("name", "manufacturer", "model", "android", "api_level", "app_version").forEach { assertTrue(it, home.getJSONObject("device").has(it)) }
        listOf("tailscale", "adb", "relay", "wifi").forEach { assertTrue(it, home.getJSONObject("connections").getString(it) in setOf("connected", "disconnected")) }
        val f = home.getJSONArray("features")
        assertEquals(22, f.length())
        (0 until f.length()).forEach { assertTrue(f.getJSONObject(it).getString("state") in setOf("on", "off", "blocked")) }
        val nav = home.getJSONArray("nav")
        assertEquals("interactions", nav.getJSONObject(0).getString("id")); assertEquals("Home", nav.getJSONObject(0).getString("title"))
        assertTrue((0 until nav.length()).any { nav.getJSONObject(it).getString("id") == "portal" })
        // The Home scene's own inputs: node states for every scene node, check lines, agents (portal viewers left out), status.
        val scene = home.getJSONObject("scene")
        val opts = scene.getJSONObject("options")
        listOf("externalDevices", "emailReminder", "agent", "called", "agents", "nodes", "checks").forEach { assertTrue(it, opts.has(it)) }
        listOf("camera", "voicerec", "tailscale", "clef", "jev", "weather", "agent").forEach { assertTrue(it, opts.getJSONObject("nodes").has(it)) }
        assertFalse(opts.getJSONArray("agents").toString().contains("Bridgit Portal"))
        assertEquals(0, home.getInt("agents_count"))
        listOf("agents", "applets", "emptyApplets", "position", "rotate").forEach { assertTrue(it, scene.getJSONObject("home_status").has(it)) }
        assertTrue(scene.has("paused")); assertTrue(scene.has("listening"))

        val (ls, logs) = get("/v1/portal/logs", JSONObject().put("since", 0).put("limit", 200))
        assertEquals(200, ls)
        val entries = logs.getJSONArray("entries")
        val paths = (0 until entries.length()).map { entries.getJSONObject(it).optString("path") }
        assertTrue("/v1/camera/snapshot" in paths)
        assertFalse(paths.any { it.startsWith("/v1/portal") })
        assertFalse(logs.toString().contains("secret words"))
        assertTrue(logs.getLong("next_since") >= entries.getJSONObject(entries.length() - 1).getLong("id"))
        // Hundreds of portal polls after the action must not hide it from the newest page.
        repeat(700) { app.log.append(LogEntry(ts = System.currentTimeMillis(), channel = "audit", kind = "request", agentId = p.agent.id,
            agent = p.agent.name, method = "GET", endpoint = "/v1/portal/home", http = 200, result = "ok")) }
        val (_, newest) = get("/v1/portal/logs", JSONObject().put("limit", 5))
        val newestPaths = newest.getJSONArray("entries").let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("path") } }
        assertTrue(newestPaths.toString(), "/v1/camera/snapshot" in newestPaths)
        assertTrue(newest.getLong("next_since") > entries.getJSONObject(entries.length() - 1).getLong("id"))
        assertTrue(newest.getLong("latest_id") in 1..app.log.latestId.value)
        // An older page before the newest one.
        val older = get("/v1/portal/logs", JSONObject().put("until", newest.getLong("oldest_id")).put("limit", 5)).second
        assertTrue(older.has("has_older")); assertTrue(older.getJSONArray("entries").length() <= 5)
        assertEquals("invalid_parameter", get("/v1/portal/logs", JSONObject().put("since", 1).put("until", 5)).second.getString("error"))
        assertEquals("invalid_parameter", get("/v1/portal/logs", JSONObject().put("limit", 201)).second.getString("error"))

        val (as_, agents) = get("/v1/portal/agents")
        assertEquals(200, as_)
        val me = (0 until agents.getJSONArray("agents").length()).map { agents.getJSONArray("agents").getJSONObject(it) }.single { it.getString("id") == p.agent.id }
        assertEquals("connector", me.getString("mode")); assertEquals("relay", me.getString("transport")); assertTrue(me.getBoolean("portal"))
        assertEquals(listOf("lan_discovery", "portal"), (0 until me.getJSONArray("grants").length()).map { me.getJSONArray("grants").getString(it) })

        val (aps, applets) = get("/v1/portal/applets")
        assertEquals(200, aps); assertEquals("dog", applets.getJSONArray("applets").getJSONObject(0).getString("id"))

        val (ts, theme) = get("/v1/portal/theme")
        assertEquals(200, ts)
        assertEquals("#1F5BD6", theme.getJSONObject("colors").getJSONObject("light").getString("b_primary"))
        assertTrue(theme.getJSONObject("colors").getJSONObject("dark").getString("b_surface").matches(Regex("#[0-9A-F]{6}")))
        assertTrue(theme.getJSONObject("motion").getJSONObject("core_breathe").getInt("duration_ms") == 4000)
        assertTrue(theme.getJSONArray("animations").length() >= 3)

        val (ss, snap) = get("/v1/portal/snapshot")
        assertEquals(200, ss); listOf("home", "agents", "applets", "logs").forEach { assertTrue(it, snap.has(it)) }
        assertEquals(20_000, snap.getInt("relay_daily_jobs"))

        // No Wi-Fi under Robolectric: devices answers not_supported rather than failing.
        assertTrue(get("/v1/portal/devices").first in setOf(200, 422))
        val brand = cc.bridgit.app.lan.DeviceBrands(app).all.first().id
        val (bs, logo) = get("/v1/portal/brands/$brand/logo")
        assertEquals(200, bs); assertTrue(logo.getString("data_base64").isNotEmpty())

        // A pairing made before POST /v1/lan/discover was allowed is upgraded in place: same token, new route.
        val legacy = store.agents().single { it.getString("id") == p.agent.id }
        val oldRoutes = org.json.JSONArray(PortalData.ROUTES.filter { it.startsWith("GET ") })
        val prefs = app.getSharedPreferences("relay_v1", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("agents", org.json.JSONArray(store.agents().map { if (it.getString("id") == p.agent.id) JSONObject(it.toString()).put("routes", oldRoutes) else it }).toString()).commit()
        assertEquals(1, store.upgradePortalRoutes())
        val upgraded = store.agents().single { it.getString("id") == p.agent.id }
        assertEquals(legacy.getString("token_hash"), upgraded.getString("token_hash"))
        assertTrue("POST /v1/lan/discover" in RelayDispatcher.strings(upgraded, "routes"))
        assertEquals(0, store.upgradePortalRoutes())

        // The Portal camera: who checks photos (none here), and a photo with nobody to check it.
        assertEquals(0, get("/v1/portal/photo/targets").second.getJSONArray("targets").length())
        suspend fun post(path: String, body: JSONObject): Pair<Int, JSONObject> {
            val now = System.currentTimeMillis()
            val r = d.dispatch(c, JSONObject().put("method", "POST").put("path", path).put("body", body), now + 10_000, now, "job")
            return r.getInt("status") to JSONObject(String(Sealed.un64(r.getString("body_base64"))))
        }
        assertEquals(422, post("/v1/portal/photo", JSONObject().put("data_base64", "bm90IGEgcGhvdG8=")).first)
        val jpeg = java.io.ByteArrayOutputStream().also { android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
            .compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        val (ps, pb) = post("/v1/portal/photo", JSONObject().put("camera", "front").put("data_base64", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)))
        assertEquals(pb.toString(), 404, ps)

        // Only the portal's read-only routes.
        listOf("/v1/health", "/v1/log", "/v1/lan/discovered").forEach { path ->
            try { get(path); fail("$path must be excluded") } catch (e: ApiError) { assertEquals("relay_route_excluded", e.code) }
        }
        try {
            val now = System.currentTimeMillis()
            d.dispatch(c, JSONObject().put("method", "POST").put("path", "/v1/camera/snapshot"), now + 10_000, now, "job")
            fail("capture must be excluded")
        } catch (e: ApiError) { assertEquals("relay_route_excluded", e.code) }

        // Disconnect cuts it off.
        PortalPairing.disconnect(app, store, p.agent.id)
        assertTrue(PortalPairing.paired(app, store).isEmpty())
        assertTrue(store.agents().none { it.getString("id") == p.agent.id })
        try { get("/v1/portal/home"); fail("revoked") } catch (e: ApiError) { assertEquals("agent_revoked", e.code) }
        api.stop(); svc.onDestroy()
    }
}
