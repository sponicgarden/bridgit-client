package cc.bridgit.app.satellite

import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.api.IdempotencyCache
import cc.bridgit.app.core.AgentRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SatelliteTest {
    private val claim = SatelliteLink.newClaimToken()

    @Before fun key() { SatelliteStore.keyOverride = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    @After fun clear() { SatelliteStore.keyOverride = null }

    @Test fun linkRoundTrip() {
        val link = SatelliteLink.build("100.64.0.10", 18471, claim, "Garage phone & co", "bgi_abcdefghijklmnopqrstuvwx")
        assertTrue(link.startsWith(SatelliteLink.PREFIX))
        assertFalse("no key in the link", "brg_" in link)
        val p = SatelliteLink.parse(link)
        assertEquals("100.64.0.10", p.host); assertEquals(18471, p.port); assertEquals(claim, p.claim)
        assertEquals("Garage phone & co", p.name); assertEquals("bgi_abcdefghijklmnopqrstuvwx", p.install)
    }

    @Test fun linkRefusesNonTailscaleHostsAndBadCodes() {
        for (bad in listOf(
            SatelliteLink.build("100.64.0.10", 18471, claim, "x", null).replace("100.64.0.10", "192.168.1.5"),
            SatelliteLink.build("100.64.0.10", 18471, claim, "x", null).replace("100.64.0.10", "example.com"),
            SatelliteLink.build("100.64.0.10", 18471, "sct_short", "x", null),
            SatelliteLink.build("100.64.0.10", 18471, claim, "x", null).replace("port=18471", "port=99999"),
            SatelliteLink.build("100.64.0.10", 18471, claim, "x", null).replace("v=1", "v=2"),
            "bridgitportal://pair#v=1",
        )) try { SatelliteLink.parse(bad); fail("accepted $bad") } catch (_: IllegalArgumentException) {}
    }

    @Test fun hubLinkState() {
        val now = 10_000_000_000L
        assertEquals(HubLinks.LinkState.OK, HubLinks.stateOf(true, 0, now))
        assertEquals(HubLinks.LinkState.WAITING, HubLinks.stateOf(false, now - 5 * 60_000L, now))
        // The Pixel 2 XL case: no details from the hub and no check-in for 24 minutes.
        assertEquals(HubLinks.LinkState.STALE, HubLinks.stateOf(false, now - 24 * 60_000L, now))
        assertEquals(HubLinks.LinkState.STALE, HubLinks.stateOf(false, now - 24 * 60_000L, now, answered = false))
        // Bridgit answers at the hub's address but the link doesn't check in: that Bridgit doesn't list this phone
        // (5 Nov 2026: the hub that paired the Pixel 10 was a test copy on the Pixel 2 XL, later uninstalled).
        assertEquals(HubLinks.LinkState.FORGOTTEN, HubLinks.stateOf(false, now - 24 * 60_000L, now, answered = true))
        // A hub that checked in lately, or sent its details, is never called forgotten by the probe.
        assertEquals(HubLinks.LinkState.WAITING, HubLinks.stateOf(false, now - 5 * 60_000L, now, answered = true))
        assertEquals(HubLinks.LinkState.OK, HubLinks.stateOf(true, 0, now, answered = true))
        assertTrue(HubLinks.LinkState.FORGOTTEN.needsPairing && HubLinks.LinkState.STALE.needsPairing)
        assertFalse(HubLinks.LinkState.WAITING.needsPairing || HubLinks.LinkState.OK.needsPairing)
    }

    @Test fun ids() {
        assertEquals("garage-phone", SatelliteLink.slug("Garage Phone!"))
        assertEquals("phone", SatelliteLink.slug("…"))
        assertEquals("sat:garage", SatelliteLink.newId("Garage", emptySet()))
        assertEquals("sat:garage-2", SatelliteLink.newId("Garage", setOf("sat:garage")))
        assertTrue(SatelliteLink.ID_RE.matches(SatelliteLink.newId("A".repeat(80), emptySet())))
    }

    @Test fun whatPassesALink() {
        assertTrue(SatellitePolicy.forwardable(Endpoints.SNAPSHOT))
        assertTrue(SatellitePolicy.forwardable(Endpoints.HEALTH))
        assertFalse("pairing", SatellitePolicy.forwardable(Endpoints.PAIR))
        assertFalse("mcp", SatellitePolicy.forwardable(Endpoints.MCP))
        assertFalse("devices", SatellitePolicy.forwardable(Endpoints.DEVICES))
        assertFalse("claim", SatellitePolicy.forwardable(SatelliteEndpoints.CLAIM))
        assertFalse("satellites list", SatellitePolicy.forwardable(SatelliteEndpoints.SATELLITES))
        for (e in Endpoints.all) if (e.power != null) assertFalse("power endpoint ${e.path}", SatellitePolicy.forwardable(e))
    }

    @Test fun targets() {
        assertNull(SatellitePolicy.target(null, null))
        assertNull(SatellitePolicy.target("hub", null))
        assertNull(SatellitePolicy.target(" ", "HUB"))
        assertEquals("sat:a", SatellitePolicy.target("sat:a", "sat:b"))
        assertEquals("sat:b", SatellitePolicy.target(null, "SAT:B"))
    }

    @Test fun idempotencyKeysStayValidAndApart() {
        val a = SatellitePolicy.forwardedIdempotencyKey("fa93a154394b", "test-key-0001")
        val b = SatellitePolicy.forwardedIdempotencyKey("0b11c2d3e4f5", "test-key-0001")
        assertTrue(IdempotencyCache.KEY_RE.matches(a)); assertTrue(a != b)
        assertTrue(IdempotencyCache.KEY_RE.matches(SatellitePolicy.forwardedIdempotencyKey("fa93a154394b", "k".repeat(128))))
    }

    @Test fun onBehalfOf() {
        assertEquals("abc" to "Muse Opus", SatellitePolicy.parseOnBehalfOf(SatellitePolicy.onBehalfOf("abc", "Muse, Opus")))
        assertNull(SatellitePolicy.parseOnBehalfOf("no comma"))
        assertNull(SatellitePolicy.parseOnBehalfOf("bad id!,Name"))
        assertNull(SatellitePolicy.parseOnBehalfOf("abc,"))
    }

    @Test fun tailnetNames() {
        val n = SatelliteClient.hostName("100.64.0.10")
        assertEquals("100-64-0-10.tailnet.bridgit.invalid", n)
        assertEquals("100.64.0.10", SatelliteClient.ipOf(n))
        val v6 = SatelliteClient.hostName("fd7a:115c:a1e0::1")
        assertEquals(cc.bridgit.app.core.Net.normalise("fd7a:115c:a1e0::1"), cc.bridgit.app.core.Net.normalise(SatelliteClient.ipOf(v6)!!))
        assertNull("not tailnet", SatelliteClient.ipOf("192-168-1-5.tailnet.bridgit.invalid"))
        assertNull("other name", SatelliteClient.ipOf("example.com"))
        assertNull("extra label", SatelliteClient.ipOf("a.100-64-0-10.tailnet.bridgit.invalid"))
        try { SatelliteClient.hostName("8.8.8.8"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun hubLinkNamesFollowAgentNameRules() {
        for (h in listOf("Galaxy Tab A11", "Küche · Tablet", "", null, "A very long hub name that goes on and on", "!!!"))
            for (n in listOf(1, 2, 17)) {
                val name = HubLinks.linkName(h, n)
                assertTrue("'$name' from '$h'", AgentRegistry.NAME_RE.matches(name))
            }
        assertEquals("Hub Galaxy Tab A11", HubLinks.linkName("Galaxy Tab A11", 1))
        assertEquals("Satellite hub 2", HubLinks.linkName(null, 2))
    }

    @Test fun storeKeepsKeysEncryptedAndAccessPerAgent() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = SatelliteStore(ctx)
        val p = SatelliteLink.parse(SatelliteLink.build("100.64.0.10", 18471, claim, "Garage", "bgi_abcdefghijklmnopqrstuvwx"))
        val s = store.add(p, "brg_secretsecretsecretsecret", JSONObject().put("name", "Garage phone"))
        assertEquals("sat:garage-phone", s.id); assertTrue(s.agents.isEmpty())
        val raw = ctx.getSharedPreferences("satellites_v1", 0).getString("satellites", "")!!
        assertFalse("key stored in clear", "brg_secret" in raw)
        assertEquals("brg_secretsecretsecretsecret", store.get(s.id)!!.key)
        store.setAgent(s.id, "agentA", true)
        assertTrue(store.allows(s.id, "agentA")); assertFalse(store.allows(s.id, "agentB"))
        // Pairing the same phone again keeps its id and who may use it.
        val again = store.add(p, "brg_newkeynewkeynewkeynewkey", null)
        assertEquals(s.id, again.id); assertTrue(store.allows(s.id, "agentA")); assertEquals(1, store.count())
        store.forgetAgent("agentA"); assertFalse(store.allows(s.id, "agentA"))
        store.remove(s.id); assertEquals(0, store.count())
    }

    @Test fun returnLinkIsStoredEncryptedAndFoundQuickly() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = SatelliteStore(ctx)
        val p = SatelliteLink.parse(SatelliteLink.build("100.64.0.11", 18471, claim, "Pixel 10", null))
        val s = store.add(p, "brg_satkeysatkeysatkeysatkey", null)
        assertFalse(store.isReturnAgent("ret1"))
        store.update(s.id) { it.copy(returnAgent = "ret1", returnKey = "brg_returnreturnreturnreturn") }
        assertTrue(store.isReturnAgent("ret1")); assertFalse(store.isReturnAgent("other")); assertFalse(store.isReturnAgent(null))
        assertEquals(s.id, store.byReturnAgent("ret1")!!.id)
        val raw = ctx.getSharedPreferences("satellites_v1", 0).getString("satellites", "")!!
        assertFalse("return key stored in clear", "brg_return" in raw)
        assertEquals("brg_returnreturnreturnreturn", store.get(s.id)!!.returnKey)
        // Re-pairing the same phone keeps its return link.
        assertEquals("ret1", store.add(p, "brg_newsatnewsatnewsatnewsat", null).returnAgent)
        store.remove(s.id); assertFalse(store.isReturnAgent("ret1"))
    }

    @Test fun returnLinkMayCallOnlyItsThreePaths() {
        val all = Endpoints.all
        val allowed = all.filter { it.path in SatellitePolicy.RETURN_PATHS }.map { it.method + " " + it.path }.toSet()
        assertEquals(setOf("GET /satellite/hub", "POST /satellite/hub/photo", "POST /satellite/hub/portal-login",
            "GET /portal/home", "GET /portal/logs", "GET /portal/devices", "POST /satellite/hub/unlink",
            "GET /satellite/hub/agents", "POST /satellite/hub/agents/{id}/pause"), allowed)
        // Every return path is reachable with the return link's grant (plus what every agent may use).
        assertTrue(all.filter { it.path in SatellitePolicy.RETURN_PATHS }.all { it.capability in SatellitePolicy.RETURN_CAPS || it.capability == "status" })
        assertTrue(all.none { it.path in SatellitePolicy.RETURN_PATHS && (it.power != null || !it.auth) })
        // A hub link may send hub info, but not use the return paths or anything outside a link.
        assertTrue(SatellitePolicy.hubLinkMay(SatelliteEndpoints.HUB_INFO))
        assertTrue(SatellitePolicy.hubLinkMay(SatelliteEndpoints.MANIFEST))
        assertFalse(SatellitePolicy.hubLinkMay(SatelliteEndpoints.HUB_PHOTO))
        assertFalse(SatellitePolicy.hubLinkMay(SatelliteEndpoints.CLAIM))
    }

    @Test fun testBuildsNameThemselvesApartFromTheRealApp() {
        assertEquals(null, cc.bridgit.app.platform.DeviceName.buildTag("cc.bridgit.app"))
        assertEquals("test applets", cc.bridgit.app.platform.DeviceName.buildTag("cc.bridgit.app.test_claude_applets"))
        assertEquals("debug", cc.bridgit.app.platform.DeviceName.buildTag("cc.bridgit.app.debug"))
        assertEquals("Google Pixel 2 XL", cc.bridgit.app.platform.DeviceName.tagged("Google Pixel 2 XL", "cc.bridgit.app"))
        val t = cc.bridgit.app.platform.DeviceName.tagged("Google Pixel 2 XL", "cc.bridgit.app.test_claude_applets")
        assertEquals("Google Pixel 2 XL (test applets)", t)
        assertEquals(t, cc.bridgit.app.platform.DeviceName.tagged(t, "cc.bridgit.app.test_claude_applets")) // once only
        val long = cc.bridgit.app.platform.DeviceName.tagged("x".repeat(80), "cc.bridgit.app.test_claude_applets")
        assertTrue(long.length <= cc.bridgit.app.platform.DeviceName.MAX && long.endsWith(" (test applets)"))
    }

    @Test fun eachSideCanTellTheOtherALinkIsGone() {
        assertTrue(SatellitePolicy.hubLinkMay(SatelliteEndpoints.UNLINK))
        assertFalse(SatellitePolicy.hubLinkMay(SatelliteEndpoints.HUB_UNLINK))
        assertTrue("/satellite/hub/unlink" in SatellitePolicy.RETURN_PATHS)
        assertFalse("/satellite/unlink" in SatellitePolicy.RETURN_PATHS)
        assertTrue(SatelliteClient.isGone(SatelliteClient.Failure("unauthorized", "x", 401)))
        assertFalse(SatelliteClient.isGone(SatelliteClient.Failure("satellite_offline", "x")))
        assertFalse(SatelliteClient.isGone(SatelliteClient.Failure("wrong_device", "x", 403)))
        assertFalse(SatelliteClient.isGone(SatelliteClient.Failure("rate_limited", "x", 429)))
    }

    @Test fun returnNamesFitTheAgentNameRules() {
        for (n in listOf("Pixel 10", "Ryan's very long phone name, really", "…", ""))
            for (i in listOf(1, 2, 50)) {
                val name = SatelliteHub.returnName(n, i)
                assertTrue("'$name' from '$n'", AgentRegistry.NAME_RE.matches(name))
            }
        assertEquals("Satellite Pixel 10", SatelliteHub.returnName("Pixel 10", 1))
    }

    @Test fun hubInfoKeepsOnlyBridgitPortalsAndSealsTheKey() {
        val body = JSONObject().put("name", "Pixel 2 XL")
            .put("targets", org.json.JSONArray().put(JSONObject().put("id", "a1").put("name", "Kitchen")).put(JSONObject().put("id", "").put("name", "x")))
            .put("portals", org.json.JSONArray()
                .put(JSONObject().put("id", "dog").put("name", "Pet monitor").put("url", "https://bridgit.cc/myapps/?device=bgi_x#dog"))
                .put(JSONObject().put("id", "evil").put("name", "Evil").put("url", "https://example.com/"))
                .put(JSONObject().put("id", "plain").put("name", "Plain").put("url", "http://bridgit.cc/")))
        val h = HubLinks.HubInfo.fromBody("lnk", "100.89.1.2", 18471, "brg_hubkeyhubkeyhubkeyhubkey", body)
        assertEquals(listOf("a1"), h.targets.map { it.id })
        assertEquals(listOf("dog"), h.portals.map { it.id })
        val json = h.toJson()
        assertFalse("return key in clear", "brg_hubkey" in json.toString())
        val back = HubLinks.HubInfo.fromJson("lnk", json)!!
        assertEquals(h.copy(at = back.at), back)
    }

    @Test fun hubInfoCarriesTheHubsDeviceTypeAndOlderHubsReadAsUnknown() {
        val withType = HubLinks.HubInfo.fromBody("lnk", "100.89.1.2", 18471, "brg_hubkeyhubkeyhubkeyhubkey",
            JSONObject().put("name", "A11").put("device_type", "tablet"))
        assertEquals(cc.bridgit.app.platform.DeviceType.TABLET, withType.deviceType)
        assertEquals(withType.copy(at = 0), HubLinks.HubInfo.fromJson("lnk", withType.toJson())!!.copy(at = 0))
        val older = HubLinks.HubInfo.fromBody("lnk", "100.89.1.2", 18471, "brg_hubkeyhubkeyhubkeyhubkey", JSONObject().put("name", "Old"))
        assertEquals(null, older.deviceType)
        assertEquals(null, HubLinks.HubInfo.fromJson("lnk", older.toJson())!!.deviceType)
    }
}
