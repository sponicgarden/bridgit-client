package cc.bridgit.app.link

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.core.Connection
import cc.bridgit.app.feedback.InstallIdentity
import cc.bridgit.app.satellite.LinkRelayClient
import cc.bridgit.app.satellite.SatelliteClient
import cc.bridgit.app.satellite.SatelliteLink
import cc.bridgit.app.satellite.SatelliteStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator

/** Claiming a link code over the relay (docs/spec-linked-bridgits.md §4.1), with the names backend and the relay faked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkRelayClaimTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp

    private val origin = "https://relay.example"
    private val device = "dev_target_0001"
    private val base = "$origin/d/$device"

    /** One relay job as the fake relay saw it. */
    data class Sent(val url: String, val bearer: String, val body: JSONObject?) {
        val path get() = body?.getJSONObject("request")?.getString("path")
    }
    private val sent = CopyOnWriteArrayList<Sent>()
    /** The other device's answer per path: (status, reply). Null = the relay answers with that HTTP status itself. */
    private val answers = HashMap<String, Pair<Int, JSONObject>>()
    @Volatile private var relayStatus: Int? = null

    @Before fun setUp() {
        SatelliteStore.keyOverride = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        InstallIdentity.testStatus = InstallIdentity.Status("bgi_cccccccccccccccccccccccc", "unverified", null)
        LinkResolver.enabled = false
        LinkResolver.names = { _, path, _ ->
            assertEquals("/v1/names/kitchen-tab/resolve", path)
            200 to JSONObject().put("name", "kitchen-tab").put("reach", JSONObject().put("relay", JSONObject().put("origin", origin).put("device", device)))
        }
        LinkRelayClient.transport = { url, bearer, body ->
            val j = body?.let { JSONObject(it) }
            sent += Sent(url, bearer, j)
            val path = j?.getJSONObject("request")?.getString("path")
            relayStatus?.let { s -> s to JSONObject().put("error", if (s == 401) "unauthorized" else "phone_unreachable").toString() }
                ?: (answers[path] ?: (200 to JSONObject().put("ok", true))).let { (status, reply) ->
                    200 to JSONObject().put("response", JSONObject().put("status", status).put("content_type", "application/json")
                        .put("body_base64", Base64.getEncoder().encodeToString(reply.toString().toByteArray()))).toString()
                }
        }
        app.platform.store.setting("bridgit_name", JSONObject().put("name", "pocket-phone").put("visible", true).toString(), true)
    }

    @After fun tearDown() {
        LinkRelayClient.transport = null; LinkResolver.names = null; LinkResolver.enabled = true
        InstallIdentity.testStatus = null; SatelliteStore.keyOverride = null
    }

    private fun claimReply(linkId: String = "lnk_" + "a".repeat(20)) = JSONObject().put("link_id", linkId).put("key", JSONObject.NULL)
        .put("agent", "Link pocket-phone").put("name", "Kitchen tablet").put("bridgit_name", "kitchen-tab").put("install", "bgi_dddddddddddddddddddddddd")
        .put("services", JSONArray(listOf("camera"))).put("directions", JSONArray(listOf("in"))).put("tier", "own").put("expires_at", JSONObject.NULL)
        .put("events", JSONArray())

    private fun claim(): JSONObject {
        val me = app.agents.all().firstOrNull { it.name == "Claimer" } ?: app.agents.add("Claimer", Connection.DIRECT).first
        return app.links.claim(Links.ClaimTarget("kitchen-tab", "K7QX-3MPA"), setOf(me.id), emptySet(), Granter.person("pocket-phone"))
    }

    private fun err(code: String, f: () -> Unit) {
        try { f(); fail("expected $code") } catch (e: ApiError) { assertEquals(e.message, code, e.code) }
    }

    // ---- Choosing the transport -----------------------------------------------------------------------

    @Test fun tailscaleWhenBothDevicesHaveIt_relayOtherwise() {
        val relay = JSONObject().put("origin", origin).put("device", device)
        val ts = JSONObject().put("host", "100.64.0.9").put("port", 8765)
        assertEquals(LinkResolver.Reach.Relay(base), LinkResolver.choose(JSONObject().put("relay", relay), "kitchen-tab", onTailnet = true))
        assertEquals(LinkResolver.Reach.Tailscale("100.64.0.9", 8765), LinkResolver.choose(JSONObject().put("relay", relay).put("tailscale", ts), "kitchen-tab", onTailnet = true))
        assertEquals("this device isn't on Tailscale", LinkResolver.Reach.Relay(base), LinkResolver.choose(JSONObject().put("relay", relay).put("tailscale", ts), "kitchen-tab", onTailnet = false))
        assertEquals("Tailscale-only keeps today's behaviour", LinkResolver.Reach.Tailscale("100.64.0.9", 8765), LinkResolver.choose(JSONObject().put("tailscale", ts), "kitchen-tab", onTailnet = false))
        val offTailnet = JSONObject().put("host", "192.168.1.5").put("port", 8765)
        assertEquals("an address off the tailnet falls to the relay", LinkResolver.Reach.Relay(base), LinkResolver.choose(JSONObject().put("relay", relay).put("tailscale", offTailnet), "kitchen-tab", onTailnet = true))
        err("target_offline") { LinkResolver.choose(JSONObject().put("tailscale", offTailnet), "kitchen-tab", onTailnet = true) }
        err("upstream_failed") { LinkResolver.choose(JSONObject().put("relay", JSONObject().put("origin", "http://relay.example").put("device", device)), "kitchen-tab", true) }
        err("target_offline") { LinkResolver.choose(JSONObject(), "kitchen-tab", true) }
    }

    @Test fun relayBasesAreHttpsOnly() {
        assertEquals(base, LinkRelayClient.base(origin, device))
        assertEquals(base, LinkRelayClient.base("$origin/", device))
        assertEquals(base, LinkRelayClient.base(base, device))
        assertNull(LinkRelayClient.base("http://relay.example", device))
        assertNull(LinkRelayClient.base("https://user:pw@relay.example", device))
        assertNull(LinkRelayClient.base("https://relay.example?x=1", device))
        assertNull(LinkRelayClient.base(origin, "../x"))
        assertTrue(LinkRelayClient.isRelay(base)); assertFalse(LinkRelayClient.isRelay(SatelliteClient.base("100.64.0.9", 8765)))
        val t = LinkRelayClient.newToken(); assertTrue(LinkRelayClient.validToken(t)); assertTrue(LinkCode.CLAIM_TOKEN_RE.matches(t))
    }

    // ---- The claim ------------------------------------------------------------------------------------

    @Test fun claimOverTheRelayStoresATokenMadeHere() {
        answers["/v1/link/claim"] = 200 to claimReply()
        answers["/v1/satellite/manifest"] = 200 to JSONObject().put("name", "Kitchen tablet").put("cameras", JSONArray(listOf("rear")))
        val out = claim()
        val claimToken = LinkCode.keys("K7QX3MPA", "kitchen-tab").claimToken

        val c = sent.first()
        assertEquals("$base/jobs?wait=20", c.url)
        assertEquals("the code's claim token is the bearer for the claim job", claimToken, c.bearer)
        assertEquals("POST", c.body!!.getJSONObject("request").getString("method"))
        val body = c.body.getJSONObject("request").getJSONObject("body")
        assertEquals("pocket-phone", body.getString("bridgit_name"))
        assertEquals("android", body.getString("platform"))

        val s = app.satellites.all().single()
        assertEquals(base, s.relay); assertEquals(base, s.base); assertEquals("", s.host)
        assertTrue(LinkRelayClient.validToken(s.key)); assertNotEquals(claimToken, s.key)
        assertEquals("the target gets only the hash of the token stored here", cc.bridgit.app.relay.RelayStore.hash(s.key), body.getString("token_hash"))
        assertFalse("the token itself never travels", c.body.toString().contains(s.key))
        assertEquals("the manifest is read with the stored token", s.key, sent.first { it.path == "/v1/satellite/manifest" }.bearer)
        assertEquals("rear", s.manifest!!.getJSONArray("cameras").getString(0))
        assertNull("no return agent over the relay", s.returnAgent)

        val r = app.links.get("lnk_" + "a".repeat(20))!!
        assertEquals(Links.Role.HUB, r.role); assertEquals(s.id, r.ref); assertEquals("kitchen-tab", r.peerName)
        assertTrue(app.links.overRelay(r))
        assertEquals(r.id, out.getString("id"))
    }

    @Test fun anOldTargetWithoutRelayManifestsStillLinks() {
        answers["/v1/link/claim"] = 200 to claimReply()
        answers["/v1/satellite/manifest"] = 403 to JSONObject().put("error", "relay_route_excluded")
        claim()
        val s = app.satellites.all().single()
        assertNull(s.manifest)
        val after = cc.bridgit.app.satellite.SatelliteHub.refresh(app, s.id)!!
        assertNull("answered, so not offline", after.lastError); assertTrue(after.lastSeen > 0)
    }

    @Test fun claimErrorsKeepTheirCodes() {
        answers["/v1/link/claim"] = 410 to JSONObject().put("error", "code_used_up").put("message", "That link code was already used.")
        err("code_used_up") { claim() }
        answers["/v1/link/claim"] = 403 to JSONObject().put("error", "relay_route_excluded")
        err("upstream_failed") { claim() }
        relayStatus = 401
        err("code_used_up") { claim() }
        relayStatus = 503
        err("target_offline") { claim() }
        assertTrue("nothing stored", app.satellites.all().isEmpty())
    }

    @Test fun aSecondRelayLinkDoesNotReplaceTheFirst() {
        val p = SatelliteLink.Parsed("", 0, "", "One", null)
        val a = app.satellites.add(p, LinkRelayClient.newToken(), null, relay = "$origin/d/device_one_1")
        val b = app.satellites.add(p.copy(name = "Two"), LinkRelayClient.newToken(), null, relay = "$origin/d/device_two_2")
        assertNotEquals(a.id, b.id)
        assertEquals(2, app.satellites.all().size)
        val again = app.satellites.add(p, LinkRelayClient.newToken(), null, relay = "$origin/d/device_one_1")
        assertEquals("the same relay device keeps its record", a.id, again.id)
    }

    // ---- After the claim --------------------------------------------------------------------------------

    @Test fun linkPlumbingGoesOverTheRelay() {
        answers["/v1/link/claim"] = 200 to claimReply()
        claim()
        val s = app.satellites.all().single()
        answers["/v1/link/events"] = 200 to JSONObject().put("ok", true).put("state", JSONObject().put("in", JSONObject().put("opted_in", true).put("paused", true).put("services", JSONArray(listOf("camera")))))
        sent.clear()
        assertTrue(app.links.flush("lnk_" + "a".repeat(20)))
        val e = sent.last { it.path == "/v1/link/events" }
        assertEquals(s.key, e.bearer)
        assertTrue(e.url.startsWith("$base/jobs"))
        // The agent-gone answer (the relay no longer knows the token) and an offline device stay distinct.
        relayStatus = 503
        try { SatelliteClient.call(s.base, s.key, "GET", "/v1/link/peers", null, toHub = false); fail() } catch (f: SatelliteClient.Failure) { assertFalse(SatelliteClient.isGone(f)) }
        relayStatus = 401
        try { SatelliteClient.call(s.base, s.key, "GET", "/v1/link/peers", null, toHub = false); fail() } catch (f: SatelliteClient.Failure) { assertTrue(SatelliteClient.isGone(f)) }
        relayStatus = null
        answers["/v1/link/peers"] = 403 to JSONObject().put("error", "link_revoked").put("scope", "link")
        try { SatelliteClient.call(s.base, s.key, "GET", "/v1/link/peers", null, toHub = false); fail() } catch (f: SatelliteClient.Failure) {
            assertEquals("link_revoked", f.code); assertEquals("link", f.scope); assertEquals(403, f.status) }
    }

    @Test fun forwardedCallsDontUseTheRelayYet() {
        try { SatelliteClient.newCall(base, "rly_" + "a".repeat(64), "GET", "/v1/phone/status", emptyList(), emptyList(), null); fail() }
        catch (e: IllegalArgumentException) {}
    }

    @Test fun theTargetLetsARelayLinkReadItsManifest() {
        val routes = app.links.relayRoutes(setOf("camera"))
        assertTrue("GET /v1/satellite/manifest" in routes); assertTrue("POST /v1/satellite/unlink" in routes)
        assertFalse("POST /v1/satellite/hub-info" in routes)
        // A link agent's routes are never mistaken for a Portal viewer's (that would change its relay rules).
        for (services in listOf(setOf("view"), setOf("view", "camera"), LinkModel.SUGGESTED.toSet())) {
            val r = app.links.relayRoutes(services)
            assertFalse(cc.bridgit.app.portal.PortalData.isPortalConfig(r, emptySet()))
            assertFalse(cc.bridgit.app.portal.PortalData.isLegacyPortalConfig(r, emptySet()))
        }
    }

    @Test fun reverseServicesAreRefusedBeforeTheCodeIsSpent() {
        answers["/v1/link/claim"] = 200 to claimReply()
        val me = app.agents.add("Claimer", Connection.DIRECT).first
        err("invalid_parameter") { app.links.claim(Links.ClaimTarget("kitchen-tab", "K7QX-3MPA"), setOf(me.id), setOf("camera"), Granter.person("pocket-phone")) }
        assertTrue("no claim job sent", sent.isEmpty())
    }

    @Test fun onlyAGoneAgentDropsALink() {
        val token = "rly_" + "b".repeat(64)
        fun failure(): SatelliteClient.Failure = try { SatelliteClient.call(base, token, "POST", "/v1/link/events", JSONObject(), toHub = false); throw AssertionError() }
            catch (f: SatelliteClient.Failure) { f }
        answers["/v1/link/events"] = 401 to JSONObject().put("error", "agent_revoked")
        assertTrue(SatelliteClient.isGone(failure()))
        answers["/v1/link/events"] = 403 to JSONObject().put("error", "agent_revoked").put("message", "Relay authority changed")
        assertFalse(SatelliteClient.isGone(failure()))
        relayStatus = 504
        assertEquals("satellite_offline", failure().code); assertFalse(SatelliteClient.isGone(failure()))
    }

    @Test fun anEventsSyncCountsAsSeen() {
        answers["/v1/link/claim"] = 200 to claimReply()
        answers["/v1/satellite/manifest"] = 403 to JSONObject().put("error", "relay_route_excluded")
        claim()
        val id = app.satellites.all().single().id
        app.satellites.update(id) { it.copy(lastSeen = 0, lastError = "offline") }
        assertTrue(app.links.flush("lnk_" + "a".repeat(20)))
        val s = app.satellites.get(id)!!
        assertNull(s.lastError); assertTrue(s.lastSeen > 0)
    }
}
