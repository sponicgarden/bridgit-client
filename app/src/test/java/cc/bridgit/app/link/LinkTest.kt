package cc.bridgit.app.link

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.core.Connection
import cc.bridgit.app.satellite.SatelliteEndpoints
import cc.bridgit.app.satellite.SatelliteLink
import cc.bridgit.app.satellite.SatellitePolicy
import cc.bridgit.app.satellite.SatelliteStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/** Linked Bridgits (docs/spec-linked-bridgits.md): model, codes, migration of satellite links, directions, disconnect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp

    @Before fun setUp() {
        SatelliteStore.keyOverride = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        LinkResolver.enabled = false
    }
    @After fun tearDown() { SatelliteStore.keyOverride = null; LinkResolver.enabled = true }

    private fun name(n: String) = app.platform.store.setting("bridgit_name", JSONObject().put("name", n).put("visible", true).toString(), true)
    private fun err(code: String, f: () -> Unit) {
        try { f(); fail("expected $code") } catch (e: ApiError) { assertEquals(e.message, code, e.code) }
    }

    // ---- Pure parts --------------------------------------------------------------------------------

    @Test fun codeKeysMatchTheBackendContract() {
        // Computed independently (Python hashlib) from §4.1: PBKDF2-HMAC-SHA256, 100000 rounds, 64 bytes.
        val k = LinkCode.keys("K7QX3MPA", "kitchen-tab")
        assertEquals("83c1e52828c43f9d7205b3bbb3753ad402782e7a323959c755b1335351456fe0", k.codeHash)
        assertEquals("rly_5ebc2056b9789e83d7f47a1820dff822759486f328800082010705b0a7afd46d", k.claimToken)
        assertEquals("22a31311a3135b129eeb9da5cecd005ff6a0e44225a4d88afcebe160f6ba5f62", k.proof("kitchen-tab", "bgi_abcdefghijklmnopqrstuvwx"))
        assertTrue(LinkCode.CLAIM_TOKEN_RE.matches(k.claimToken))
    }

    @Test fun codesAreCrockfordAndForgiving() {
        repeat(50) { val c = LinkCode.generate(); assertEquals(8, c.length); assertEquals(c, LinkCode.normalise(LinkCode.format(c))) }
        assertEquals("K7QX3MPA", LinkCode.normalise("k7qx-3mpa"))
        assertEquals("10000000", LinkCode.normalise("lo000000"))
        assertNull("no U in Crockford", LinkCode.normalise("UUUUUUUU"))
        assertNull(LinkCode.normalise("K7QX3MP"))
        assertEquals("K7QX-3MPA", LinkCode.format("K7QX3MPA"))
    }

    @Test fun directionStates() {
        val on = LinkModel.Half(true); val off = LinkModel.Half(false); val paused = LinkModel.Half(true, paused = true)
        assertEquals(LinkModel.State.ON, LinkModel.state(on, on))
        assertEquals(LinkModel.State.WAITING_FOR_USER, LinkModel.state(on, off))
        assertEquals(LinkModel.State.WAITING_FOR_TARGET, LinkModel.state(off, on))
        assertEquals(LinkModel.State.OFF, LinkModel.state(off, null))
        assertEquals(LinkModel.State.PAUSED, LinkModel.state(paused, on))
        assertEquals(LinkModel.State.PAUSED, LinkModel.state(on, paused))
        assertEquals(LinkModel.State.WAITING_FOR_TARGET, LinkModel.state(null, on))
    }

    @Test fun servicesAreGrants() {
        assertEquals(setOf("camera", "portal"), LinkModel.capsFor(setOf("camera", "view")))
        assertEquals(setOf("camera", "view"), LinkModel.servicesOf(setOf("camera", "portal", "settings")))
        assertEquals(SatellitePolicy.PHASE1_CAPS, LinkModel.servicesOf(null))
        try { LinkModel.parseServices(JSONArray(listOf("camera", "settings"))); fail() } catch (e: IllegalArgumentException) {}
        assertEquals(emptySet<String>(), LinkModel.parseServices(JSONArray()))
    }

    /** LNK-12: what passes is the endpoint shape ∩ the service list; plumbing always; the return paths with view. */
    @Test fun whatALinkAgentMayCall() {
        assertTrue(LinkPolicy.mayCall(Endpoints.SNAPSHOT, setOf("camera")))
        assertFalse(LinkPolicy.mayCall(Endpoints.SNAPSHOT, setOf("mic")))
        assertTrue("status passes", LinkPolicy.mayCall(Endpoints.HEALTH, emptySet()))
        assertFalse("pairing never", LinkPolicy.mayCall(Endpoints.PAIR, SatellitePolicy.PHASE1_CAPS + "view"))
        assertTrue(LinkPolicy.mayCall(LinkEndpoints.EVENTS, emptySet()))
        assertTrue(LinkPolicy.mayCall(SatelliteEndpoints.HUB, setOf("view")))
        assertFalse(LinkPolicy.mayCall(SatelliteEndpoints.HUB, setOf("camera")))
        assertFalse("configuration never", LinkPolicy.mayCall(cc.bridgit.app.ownerapi.ConfigurationLinks.CLAIMS, LinkModel.SERVICES))
        assertFalse("disconnect for agents isn't plumbing", LinkPolicy.mayCall(cc.bridgit.app.ownerapi.ConfigurationLinks.DISCONNECT, LinkModel.SERVICES))
    }

    // ---- Migration (LNK-54) ------------------------------------------------------------------------

    @Test fun existingSatelliteLinksMigrateWithNoRePair() {
        val owner = app.agents.add("Muse", Connection.DIRECT).first
        val p = SatelliteLink.Parsed("100.64.0.20", 18471, "", "Garage phone", "bgi_aaaaaaaaaaaaaaaaaaaaaaaa")
        val s = app.satellites.add(p, "brg_" + "x".repeat(40), JSONObject().put("name", "Garage phone"))
        app.satellites.setAgent(s.id, owner.id, true)
        // A hub link here (this device is someone's satellite), made the old way.
        val (hubAgent, _, _) = app.hubLinks.claimLink("100.64.0.30", "Kitchen tablet", SatellitePolicy.PHASE1_CAPS)

        val links = app.links.all()
        val hub = links.single { it.role == Links.Role.HUB }
        assertEquals(s.id, hub.ref)
        assertEquals(Granter.MIGRATED, hub.outGranter?.kind)
        assertEquals(LinkModel.State.ON, app.links.outState(hub))
        assertEquals(setOf(owner.id), app.links.outAgents(hub))
        assertEquals("sat: id kept as the target", s.id, app.links.targetOf(hub))
        assertEquals(s.id, app.links.reach(s.id, owner).id)
        val sat = links.single { it.role == Links.Role.SAT }
        assertEquals(hubAgent, sat.ref)
        assertTrue(sat.provisional)
        assertEquals(SatellitePolicy.PHASE1_CAPS, sat.inServices)
        assertEquals(LinkModel.State.ON, app.links.inState(sat))
        // Running it again changes nothing, and the log has one migrated entry per link.
        assertEquals(links.map { it.id }, app.links.all().map { it.id })
        assertEquals(2, app.links.log.all().count { it.event == "migrated" })
        // The hub's shared id replaces the provisional one.
        app.links.noteHubInfo(hubAgent, JSONObject().put("link", JSONObject().put("link_id", "lnk_abcdefghijklmnopqrst").put("bridgit_name", "kitchen-tab")))
        val adopted = app.links.all().single { it.role == Links.Role.SAT }
        assertEquals("lnk_abcdefghijklmnopqrst", adopted.id); assertFalse(adopted.provisional); assertEquals("kitchen-tab", adopted.peerName)
        assertEquals(adopted.id, app.links.find("kitchen-tab")?.id)
        // Link agents never take one of the owner's 16 slots.
        assertFalse(app.agents.all().any { it.id == hubAgent && app.links.isControlAgent(it) })
    }

    /** A0152: the hub lists its satellite even for an agent not in the user opt-in, with both directions; no duplicates. */
    @Test fun theHubListsItsSatelliteLinks() {
        val almusca = app.agents.add("Almusca", Connection.DIRECT).first
        val p = SatelliteLink.Parsed("100.64.0.21", 18471, "", "Pixel 10", "bgi_dddddddddddddddddddddddd")
        val manifest = JSONObject().put("name", "Pixel 10").put("cameras", JSONArray(listOf("rear")))
            .put("capabilities", JSONArray().put(JSONObject().put("id", "camera").put("enabled", true)))
        val s = app.satellites.add(p, "brg_" + "y".repeat(40), manifest)
        // A hub-side record only: this device is the hub, no hub link of its own, and Almusca was never granted the satellite.
        assertTrue(app.hubLinks.agents().isEmpty())
        assertTrue(app.links.reachableBy(almusca).isEmpty())

        val list = app.links.agentList(almusca)
        assertEquals(1, list.length())
        val e = list.getJSONObject(0)
        val rec = app.links.all().single()
        assertEquals("hub", e.getString("role")); assertEquals(rec.id, e.getString("link_id")); assertEquals(s.id, e.getString("target"))
        assertFalse(e.getBoolean("reachable"))
        assertEquals("on", e.getJSONObject("directions").getJSONObject("in").getString("state"))
        assertEquals(listOf("view"), (0 until e.getJSONObject("directions").getJSONObject("in").getJSONArray("services").length()).map { e.getJSONObject("directions").getJSONObject("in").getJSONArray("services").getString(it) })
        assertTrue(e.getJSONObject("directions").getJSONObject("out").has("state"))
        assertTrue(e.getJSONObject("directions").getJSONObject("out").getJSONArray("services").toString().contains("camera"))
        for (k in listOf("cameras", "capabilities", "endpoints", "battery")) assertFalse("no $k while not reachable", e.has(k))
        assertTrue(e.getString("how_to_call").contains("/v1/owner/configuration/links/${rec.id}/directions/out"))

        // Once granted, the same single entry is reachable and carries the satellite's manifest.
        app.satellites.setAgent(s.id, almusca.id, true)
        val granted = app.links.agentList(almusca)
        assertEquals(1, granted.length())
        val g = granted.getJSONObject(0)
        assertTrue(g.getBoolean("reachable")); assertEquals("hub", g.getString("role")); assertEquals(rec.id, g.getString("link_id"))
        assertEquals("${s.id}/rear", g.getJSONArray("cameras").getJSONObject(0).getString("id"))
        assertTrue(g.getString("how_to_call").contains("X-Bridgit-Target: ${s.id}"))
        assertTrue(g.has("directions"))
    }

    /** A0152: both ends list a link: the side a claim reached lists it too, for an agent that can't use it. */
    @Test fun aClaimedLinkIsListedOnBothRoles() {
        val (r, _) = claimed()
        val other = app.agents.add("Bystander", Connection.DIRECT).first
        val entries = app.links.agentList(other)
        val ids = (0 until entries.length()).map { entries.getJSONObject(it).getString("link_id") }
        assertEquals("one entry per link", ids.distinct(), ids)
        val e = entries.getJSONObject(ids.indexOf(r.id))
        assertEquals(r.role.wire, e.getString("role"))
        assertFalse(e.getBoolean("reachable"))
        val inbound = e.getJSONObject("directions").getJSONObject("in")
        assertEquals("on", inbound.getString("state"))
        assertTrue(inbound.getJSONArray("services").toString().contains("camera"))
    }

    // ---- Codes (LNK-16) ----------------------------------------------------------------------------

    @Test fun codesNeedANameAndCountWrongTries() {
        val g = Granter.person("test")
        err("name_required") { app.links.makeCode(setOf("camera"), setOf("in"), emptySet(), g) }
        name("test-target")
        err("invalid_parameter") { app.links.makeCode(emptySet(), setOf("in"), emptySet(), g) }
        val made = app.links.makeCode(setOf("camera", "mic"), setOf("in"), emptySet(), g)
        val code = LinkCode.normalise(made.getString("code"))!!
        assertTrue(made.getString("link").startsWith(Links.LINK_PREFIX))
        val token = LinkCode.keys(code, "test-target").claimToken
        repeat(4) { err("code_invalid") { app.links.redeem("rly_" + "0".repeat(64)) } }
        assertEquals(1, app.links.openCodes().single().let { LinkCode.MAX_TRIES - it.tries })
        err("code_invalid") { app.links.redeem("rly_" + "1".repeat(64)) }
        assertTrue("fifth wrong try voids it", app.links.openCodes().isEmpty())
        err("code_used_up") { app.links.redeem(token) }

        val fresh = app.links.makeCode(setOf("camera"), setOf("in"), emptySet(), g)
        val t2 = LinkCode.keys(LinkCode.normalise(fresh.getString("code"))!!, "test-target").claimToken
        assertEquals(fresh.getString("code_id"), app.links.redeem(t2).id)
        err("code_used_up") { app.links.redeem(t2) }

        val late = app.links.makeCode(setOf("camera"), setOf("in"), emptySet(), g)
        val t3 = LinkCode.keys(LinkCode.normalise(late.getString("code"))!!, "test-target").claimToken
        err("code_expired") { app.links.redeem(t3, now = System.currentTimeMillis() + LinkCode.VALID_MS + 1) }
        assertTrue(app.links.log.all().none { it.toJson().toString().contains(code) })
    }

    // ---- A claim, then directions and disconnect -------------------------------------------------

    /** The target's side: a claim makes a link agent with exactly the code's services and records both granters. */
    private fun claimed(): Pair<Links.Rec, String> {
        name("test-target")
        val maker = app.agents.add("Maker", Connection.DIRECT).first
        val made = app.links.makeCode(setOf("camera", "speaker"), setOf("in"), emptySet(), Granter.agent(maker.id, maker.name, "test-target"))
        val code = app.links.redeem(LinkCode.keys(LinkCode.normalise(made.getString("code"))!!, "test-target").claimToken)
        val r = app.links.acceptClaim(code, "100.64.0.40", JSONObject().put("name", "Kitchen tablet").put("bridgit_name", "kitchen-tab")
            .put("install", "bgi_bbbbbbbbbbbbbbbbbbbbbbbb").put("granter", JSONObject().put("kind", "agent").put("agent_id", "agt_x").put("agent_name", "Soly")))
        assertTrue(r.getString("key").startsWith("brg_"))
        return app.links.get(r.getString("link_id"))!! to r.getString("key")
    }

    @Test fun claimMakesALinkAgentWithTheServiceList() {
        val (r, _) = claimed()
        val agent = app.agents.get(r.ref)!!
        assertEquals(setOf("camera", "speaker"), agent.caps)
        assertEquals(listOf("100.64.0.40"), agent.boundIps)
        assertTrue(app.links.isLinkAgent(agent.id)); assertFalse(app.links.isControlAgent(agent))
        assertEquals(LinkModel.State.ON, app.links.inState(r))
        assertEquals("kitchen-tab", r.peerName)
        val log = app.links.log.all().filter { it.linkId == r.id }
        assertTrue("claimer named", log.any { it.event == "code_claimed" && it.granter?.agentName == "Soly" && it.granter?.device == "kitchen-tab" })
        assertTrue("maker named", log.any { it.event == "opt_in" && it.direction == "in" && it.granter?.agentName == "Maker" })
        // The same device claiming again reuses the link id (LNK-10).
        val made = app.links.makeCode(setOf("mic"), setOf("in"), emptySet(), Granter.person("test-target"))
        val again = app.links.acceptClaim(app.links.redeem(LinkCode.keys(LinkCode.normalise(made.getString("code"))!!, "test-target").claimToken),
            "100.64.0.40", JSONObject().put("bridgit_name", "kitchen-tab").put("install", "bgi_bbbbbbbbbbbbbbbbbbbbbbbb"))
        assertEquals(r.id, again.getString("link_id"))
        assertEquals(setOf("mic"), app.agents.get(r.ref)!!.caps)
    }

    @Test fun disconnectCutsAtOnceAndUndoRestores() {
        val (r, _) = claimed()
        val me = app.agents.add("Owner agent", Connection.DIRECT).first
        val g = Granter.agent(me.id, me.name, "test-target")
        val d = app.links.disconnect(r.id, "in", g)
        assertTrue(d.getLong("undo_until") > System.currentTimeMillis())
        assertTrue(app.links.get(r.id)!!.covers("in"))
        assertEquals(LinkModel.State.WAITING_FOR_TARGET, app.links.inState(app.links.get(r.id)!!))
        app.links.undo(r.id, g)
        assertEquals(LinkModel.State.ON, app.links.inState(app.links.get(r.id)!!))
        assertTrue("an undone disconnect is never sent", app.links.log.undelivered(r.id).none { it.event == "disconnected" })

        app.links.disconnect(r.id, "in", g)
        app.links.finishDue(System.currentTimeMillis() + Links.UNDO_MS + 1)
        val after = app.links.get(r.id)!!
        assertTrue(after.inRevoked)
        assertEquals(emptySet<String>(), app.agents.get(r.ref)!!.caps)
        err("conflict") { app.links.undo(r.id, g) }
        err("link_revoked") { app.links.setDirection(r.id, "in", setOf("camera"), null, null, g) }
        assertTrue("the disconnect waits for the other device", app.links.log.undelivered(r.id).any { it.event == "disconnected" && it.granter?.agentName == "Owner agent" })
    }

    @Test fun directionsKeepTheirSides() {
        val (r, _) = claimed()
        val g = Granter.person("test-target")
        err("invalid_parameter") { app.links.setDirection(r.id, "in", null, setOf("x"), null, g) }
        err("invalid_parameter") { app.links.setDirection(r.id, "out", setOf("camera"), null, null, g) }
        app.links.setDirection(r.id, "in", setOf("camera", "view"), null, null, g)
        assertEquals(setOf("camera", "portal"), app.agents.get(r.ref)!!.caps)
        app.links.setDirection(r.id, "in", null, null, true, g)
        assertEquals(LinkModel.State.PAUSED, app.links.inState(app.links.get(r.id)!!))
        // The other side uses this device only once this device opts in to "out" with its agents.
        val me = app.agents.add("Pixel agent", Connection.DIRECT).first
        app.links.applyFar(r.id, JSONObject().put("in", JSONObject().put("opted_in", true).put("services", JSONArray(listOf("camera")))))
        assertEquals(LinkModel.State.WAITING_FOR_USER, app.links.outState(app.links.get(r.id)!!))
        err("target_not_granted") { app.links.reach("kitchen-tab", me) }
        app.links.setDirection(r.id, "out", null, setOf(me.id), null, g)
        assertEquals(LinkModel.State.ON, app.links.outState(app.links.get(r.id)!!))
        // No return key from the other device yet: it says so instead of hanging.
        err("link_waiting") { app.links.reach("kitchen-tab", me) }
        err("unknown_target") { app.links.reach("porch-cam", me) }
        // Removing the agent removes its access (LNK-31).
        app.agents.remove(me.id)
        app.links.forgetAgent(me.id)
        assertEquals(emptySet<String>(), app.links.get(r.id)!!.outAgents)
    }

    @Test fun eventsFromTheOtherDeviceAreKeptAndActedOn() {
        val (r, _) = claimed()
        val e = JSONObject().put("id", "lev_remote1").put("at", System.currentTimeMillis() - 10 * 60_000).put("link_id", r.id).put("direction", "out")
            .put("event", "disconnected").put("granter", JSONObject().put("kind", "agent").put("agent_name", "Soly").put("device", "kitchen-tab"))
        app.links.receive(r, JSONObject().put("link_id", r.id).put("events", JSONArray().put(e)).put("bridgit_name", "kitchen-tab"))
        val got = app.links.log.all().single { it.id == "lev_remote1" }
        assertEquals("kitchen-tab", got.reportedBy)
        assertEquals("its out is this device's in", "in", got.direction)
        assertTrue(app.links.get(r.id)!!.inRevoked)
        // The same event again is not stored twice.
        app.links.receive(r, JSONObject().put("link_id", r.id).put("events", JSONArray().put(e)))
        assertEquals(1, app.links.log.all().count { it.id == "lev_remote1" })
    }

    /** LNK-30: the other device's halves say a direction was disconnected for good; this side marks it off at once. */
    @Test fun aRevokedHalfFromTheOtherDeviceMarksThisSideOff() {
        val (r, _) = claimed()
        val me = app.agents.add("Pixel agent", Connection.DIRECT).first
        app.links.setDirection(r.id, "out", null, setOf(me.id), null, Granter.person("test-target"))
        assertTrue(app.links.applyFar(r.id, JSONObject().put("in", JSONObject().put("opted_in", false).put("revoked", true))
            .put("out", JSONObject().put("opted_in", true))))
        val out = app.links.get(r.id)!!
        assertTrue(out.outRevoked)
        err("link_revoked") { app.links.reach("kitchen-tab", me) }
        assertFalse(app.links.get(r.id)!!.inRevoked)
        // Its "out" revoked: it stopped using this device, so the link agent here loses its grants.
        app.links.applyFar(r.id, JSONObject().put("out", JSONObject().put("opted_in", false).put("revoked", true)))
        assertTrue(app.links.get(r.id)!!.inRevoked)
        assertFalse("camera" in app.agents.get(r.ref)!!.caps.orEmpty())
        assertEquals(2, app.links.log.all().count { it.event == "marked_off" && it.linkId == r.id })
        // The same state again changes nothing and logs nothing.
        assertFalse(app.links.applyFar(r.id, JSONObject().put("out", JSONObject().put("opted_in", false).put("revoked", true))))
        assertEquals(2, app.links.log.all().count { it.event == "marked_off" && it.linkId == r.id })
    }

    /** A Bridgit with no name yet sends `"bridgit_name": null`; that must not become a peer called "null". */
    @Test fun anUnnamedPeerIsNotCalledNull() {
        val (r, _) = claimed()
        val before = app.links.get(r.id)!!.peerName
        app.links.receive(r, JSONObject().put("link_id", r.id).put("bridgit_name", JSONObject.NULL))
        assertEquals(before, app.links.get(r.id)!!.peerName)
        assertTrue(app.links.get(r.id)!!.peerName != "null")
    }

    /** §4.1: an iPhone claims over the relay with the code's claim token; it gets a relay link agent with its own token. */
    @Test fun claimOverTheRelay() {
        name("test-target")
        val store = cc.bridgit.app.relay.RelayStore(app)
        store.enabled = true
        try {
            val made = app.links.makeCode(setOf("camera"), setOf("in"), emptySet(), Granter.person("test-target"))
            val code = app.links.codes().single { it.id == made.getString("code_id") }
            val claimAgent = code.relayAgent!!
            val token = LinkCode.keys(LinkCode.normalise(made.getString("code"))!!, "test-target").claimToken
            assertEquals("the relay knows the claim token's hash", LinkCode.sha256(token), store.agents().single { it.getString("id") == claimAgent }.getString("token_hash"))
            assertTrue(app.links.isClaimAgent(claimAgent))
            val mine = LinkCode.sha256("rly_" + "a".repeat(64))
            val (status, body) = app.links.relayClaim(claimAgent, JSONObject().put("token_hash", mine).put("name", "Rahul's iPhone").put("bridgit_name", "pocket-phone")
                .put("granter", JSONObject().put("kind", "person")))
            assertEquals(body.toString(), 200, status)
            assertTrue("no key over the relay", body.isNull("key"))
            val r = app.links.get(body.getString("link_id"))!!
            val cfg = store.agents().single { it.getString("id") == r.ref }
            assertEquals(mine, cfg.getString("token_hash"))
            val routes = cc.bridgit.app.relay.RelayDispatcher.strings(cfg, "routes")
            assertTrue("POST /v1/link/events" in routes); assertTrue("POST /v1/camera/snapshot" in routes); assertFalse("POST /v1/mic/record" in routes)
            assertEquals(410, app.links.relayClaim(claimAgent, JSONObject().put("token_hash", mine)).first)
            // Narrowing the service list narrows the relay routes too.
            app.links.setDirection(r.id, "in", setOf("mic"), null, null, Granter.person("test-target"))
            val after = cc.bridgit.app.relay.RelayDispatcher.strings(store.agents().single { it.getString("id") == r.ref }, "routes")
            assertFalse("POST /v1/camera/snapshot" in after)
        } finally { store.enabled = false }
    }

    /** Review fixes: a second disconnect widens the first; "both" never re-opens while it becomes final; Undo sends nothing. */
    @Test fun disconnectsMergeAndFinishClosed() {
        val (r, _) = claimed()
        val g = Granter.person("test-target")
        app.links.disconnect(r.id, "in", g)
        app.links.disconnect(r.id, "out", g)
        val p = app.links.get(r.id)!!.pending!!
        assertEquals("both", p.direction)
        assertEquals("one event to send, for the merged disconnect", 1, app.links.log.undelivered(r.id).count { it.event == "disconnected" })
        assertEquals("held back while Undo works", p.eventId, app.links.log.undelivered(r.id).single { it.event == "disconnected" }.id)
        // The other device is still told the earlier halves (it must not mark its side off for good).
        assertTrue(app.links.stateJson(app.links.get(r.id)!!).getJSONObject("in").getBoolean("opted_in"))
        app.links.undo(r.id, g)
        assertTrue(app.links.log.undelivered(r.id).none { it.event == "disconnected" })
        // Now "both", final: the link agent is gone with a key that answers link_revoked, and the record with it.
        app.links.disconnect(r.id, "both", g)
        app.links.finishDue(System.currentTimeMillis() + Links.UNDO_MS + 1)
        assertNull(app.links.get(r.id))
        assertNull(app.agents.get(r.ref))
        // Disconnect-all with nothing left is fine.
        assertEquals(0, app.links.disconnectAll(g).getInt("disconnected"))
    }

    @Test fun eventsInTheReplyCountOnlyOnceAcknowledged() {
        val (r, _) = claimed()
        val reply = app.links.receive(r, JSONObject().put("link_id", r.id))
        val sent = reply.getJSONArray("events")
        assertTrue(sent.length() > 0)
        assertTrue("not delivered until acknowledged", app.links.log.undelivered(r.id).isNotEmpty())
        val ids = JSONArray((0 until sent.length()).map { sent.getJSONObject(it).getString("id") })
        app.links.receive(r, JSONObject().put("link_id", r.id).put("ack", ids))
        assertTrue(app.links.log.undelivered(r.id).isEmpty())
    }

    @Test fun aPeerCantFlushThisLog() {
        val (r, _) = claimed()
        val mineBefore = app.links.log.undelivered(r.id).size
        repeat(60) { batch ->
            app.links.receive(r, JSONObject().put("link_id", r.id).put("events", JSONArray((0 until 50).map { i ->
                JSONObject().put("id", "lev_x${batch}_$i").put("at", 1000L + batch * 50 + i).put("link_id", r.id).put("event", "noise") })))
        }
        assertTrue(app.links.log.all().count { it.reportedBy != null } <= LinkLog.PER_PEER)
        assertEquals(mineBefore, app.links.log.undelivered(r.id).size)
    }

    @Test fun linkParsing() {
        val t = app.links.parseLink("bridgit://link#v=1&name=kitchen-tab&code=K7QX-3MPA&host=100.64.0.9&port=8734&install=bgi_cccccccccccccccccccccccc")
        assertEquals("kitchen-tab", t.name); assertEquals("100.64.0.9", t.host); assertEquals(8734, t.port)
        val noHost = app.links.parseLink("bridgit://link#v=1&name=kitchen-tab&code=K7QX-3MPA&host=192.168.1.4&port=8734")
        assertNull("only Tailscale addresses", noHost.host)
        err("invalid_parameter") { app.links.parseLink("bridgit://link#v=2&name=a&code=b") }
    }
}
