package cc.bridgit.app.link

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.core.Connection
import cc.bridgit.app.portal.LinkTierSections
import cc.bridgit.app.portal.LinkedDevicesLayout
import cc.bridgit.app.satellite.SatelliteEndpoints
import cc.bridgit.app.satellite.SatellitePolicy
import cc.bridgit.app.satellite.SatelliteStore
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

/** Link trust tiers (docs/spec-linked-bridgits.md §16): migration, per-tier authorization, expiry and revocation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkTierTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp

    @Before fun setUp() {
        SatelliteStore.keyOverride = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        LinkResolver.enabled = false
        LinkRegistry.Backend.enabled = false
        LinkRegistry.Outbox.clear(app)
    }
    @After fun tearDown() { SatelliteStore.keyOverride = null; LinkResolver.enabled = true; LinkRegistry.Backend.enabled = true; PublicLinkApproval.autoAnswer = null }

    private fun name(n: String) = app.platform.store.setting("bridgit_name", JSONObject().put("name", n).put("visible", true).toString(), true)
    private fun err(code: String, f: () -> Unit) {
        try { f(); fail("expected $code") } catch (e: ApiError) { assertEquals(e.message, code, e.code) }
    }

    // ---- Pure ---------------------------------------------------------------------------------------

    @Test fun tiersParseRestrictivelyAndNarrow() {
        assertEquals(LinkTier.ASSOCIATE, LinkTier.parse("associate"))
        assertNull(LinkTier.parse("admin"))
        assertEquals("unknown values read as the most restrictive", LinkTier.PUBLIC, LinkTier.parseOrPublic("admin"))
        assertEquals(LinkTier.PUBLIC, LinkTier.parseOrPublic(null))
        assertEquals(LinkTier.ASSOCIATE, LinkTier.narrowest(LinkTier.OWN, LinkTier.ASSOCIATE))
        assertEquals(LinkTier.PUBLIC, LinkTier.narrowest(LinkTier.PUBLIC, LinkTier.OWN))
        assertEquals(LinkTier.OWN, LinkTier.narrowest(LinkTier.OWN, null))
        assertEquals(listOf("own", "associate", "public"), LinkTier.ORDER.map { it.wire })
    }

    /** Migration: a record from before tiers is the owner's own link; a garbled tier reads as public. */
    @Test fun storedRecordsMigrate() {
        val old = JSONObject().put("id", "lnk_abcdefghijklmnopqrst").put("role", "hub").put("ref", "sat:x").put("created_at", 1L)
        assertEquals(LinkTier.OWN, Links.Rec.fromJson(old).tier)
        assertEquals(LinkTier.OWN, Links.Rec.fromJson(old).effectiveTier)
        assertEquals(LinkTier.PUBLIC, Links.Rec.fromJson(JSONObject(old.toString()).put("tier", "root")).tier)
        val assoc = Links.Rec.fromJson(JSONObject(old.toString()).put("tier", "associate").put("far_tier", "public").put("expires_at", 5L).put("associate", "Sam"))
        assertEquals(LinkTier.PUBLIC, assoc.effectiveTier)
        assertTrue(assoc.expired(6L)); assertFalse(assoc.expired(4L))
        // Round trip.
        assertEquals(assoc, Links.Rec.fromJson(assoc.toJson()))
    }

    @Test fun servicesPerTier() {
        LinkTierPolicy.checkServices(LinkTier.OWN, setOf("camera", "mic", "location"))
        LinkTierPolicy.checkServices(LinkTier.ASSOCIATE, setOf("view", "interactions", "speaker", "sounds"))
        for (s in listOf("camera", "mic", "location", "gallery"))
            try { LinkTierPolicy.checkServices(LinkTier.ASSOCIATE, setOf(s)); fail(s) } catch (e: IllegalArgumentException) {}
        LinkTierPolicy.checkServices(LinkTier.PUBLIC, setOf("view"))
        try { LinkTierPolicy.checkServices(LinkTier.PUBLIC, setOf("view", "interactions")); fail("one only") } catch (e: IllegalArgumentException) {}
        try { LinkTierPolicy.checkServices(LinkTier.PUBLIC, setOf("speaker")); fail() } catch (e: IllegalArgumentException) {}
        // Whatever the grants say, the tier bounds them.
        assertEquals(setOf("view"), LinkTierPolicy.effectiveServices(LinkTier.ASSOCIATE, setOf("view", "camera")))
        assertEquals(setOf("view"), LinkTierPolicy.effectiveServices(LinkTier.PUBLIC, setOf("view", "interactions", "camera")))
        assertEquals(setOf("interactions"), LinkTierPolicy.effectiveServices(LinkTier.PUBLIC, setOf("interactions", "mic")))
    }

    /** A public link can't hold a lasting key: its access always ends within a day. */
    @Test fun expiryBounds() {
        val now = 1_000_000L
        assertNull(LinkTierPolicy.expiry(LinkTier.OWN, null, now))
        assertEquals(now + LinkTierPolicy.ASSOCIATE_DEFAULT_MS, LinkTierPolicy.expiry(LinkTier.ASSOCIATE, null, now))
        assertEquals(now + LinkTierPolicy.PUBLIC_DEFAULT_MS, LinkTierPolicy.expiry(LinkTier.PUBLIC, null, now))
        try { LinkTierPolicy.expiry(LinkTier.PUBLIC, LinkTierPolicy.DAY_MS + 1, now); fail() } catch (e: IllegalArgumentException) {}
        try { LinkTierPolicy.expiry(LinkTier.ASSOCIATE, 91 * LinkTierPolicy.DAY_MS, now); fail("the backend's limit is 90 days") } catch (e: IllegalArgumentException) {}
        try { LinkTierPolicy.expiry(LinkTier.OWN, 1000L, now); fail() } catch (e: IllegalArgumentException) {}
        assertFalse(LinkTierPolicy.mayHoldLastingKey(LinkTier.PUBLIC))
        assertTrue(LinkTierPolicy.mayHoldLastingKey(LinkTier.ASSOCIATE))
    }

    /** An associate never gets the owner's hub and satellite control; public changes need the owner's Allow. */
    @Test fun whatEachTierMayCall() {
        val view = setOf(LinkModel.VIEW)
        assertTrue(LinkTierPolicy.mayCall(LinkTier.OWN, SatelliteEndpoints.HUB_AGENT_PAUSE, view))
        assertFalse(LinkTierPolicy.mayCall(LinkTier.ASSOCIATE, SatelliteEndpoints.HUB_AGENT_PAUSE, view))
        assertFalse(LinkTierPolicy.mayCall(LinkTier.PUBLIC, SatelliteEndpoints.HUB_AGENT_PAUSE, view))
        assertFalse("satellite plumbing is own only", LinkTierPolicy.mayCall(LinkTier.ASSOCIATE, SatelliteEndpoints.HUB, view))
        assertTrue("its own link's calls work, so either side can revoke", LinkTierPolicy.mayCall(LinkTier.ASSOCIATE, LinkEndpoints.DISCONNECT, emptySet()))
        assertTrue(LinkTierPolicy.mayCall(LinkTier.PUBLIC, LinkEndpoints.EVENTS, emptySet()))
        assertTrue(LinkTierPolicy.mayCall(LinkTier.ASSOCIATE, Endpoints.HEALTH, emptySet()))
        assertFalse("configuration never", LinkTierPolicy.mayCall(LinkTier.OWN, cc.bridgit.app.ownerapi.ConfigurationLinks.TIER, LinkModel.SERVICES))
        assertTrue(LinkTierPolicy.needsApproval(LinkTier.PUBLIC, Endpoints.SNAPSHOT))
        assertFalse("reads don't wait", LinkTierPolicy.needsApproval(LinkTier.PUBLIC, Endpoints.HEALTH))
        assertFalse(LinkTierPolicy.needsApproval(LinkTier.PUBLIC, LinkEndpoints.DISCONNECT))
        assertFalse(LinkTierPolicy.needsApproval(LinkTier.ASSOCIATE, Endpoints.SNAPSHOT))
    }

    /** Every linked device sits in exactly one section, in the owner's order; this phone and network devices sit in none. */
    @Test fun sectionsHoldEveryLinkOnce() {
        val d = LinkedDevicesLayout.build("A11", cc.bridgit.app.platform.DeviceType.TABLET,
            hubsOf = listOf(LinkedDevicesLayout.Peer("agt_hub", "Kitchen", true)), viewing = emptyList(),
            satellites = listOf(LinkedDevicesLayout.Peer("sat:1", "Porch", false)),
            viewers = listOf(LinkedDevicesLayout.Peer("agt_view", "Sam's iPhone", true), LinkedDevicesLayout.Peer("agt_new", "Unknown", true)),
            network = listOf(LinkedDevicesLayout.NetPeer("192.168.1.9", "Cam", "Camera", "CAMERA", true, true)))
        val tiers = mapOf("agt_hub" to LinkTier.OWN, "sat:1" to LinkTier.OWN, "agt_view" to LinkTier.ASSOCIATE)
        val groups = LinkTierSections.group(d.all) { n -> tiers[n.id]?.let { listOf(LinkTierSections.Part(n.id, it)) } ?: emptyList() }
        assertEquals(LinkTier.ORDER, groups.map { it.first })
        val placed = groups.flatMap { g -> g.second.map { it.node.key } }
        assertEquals("no device twice", placed.size, placed.toSet().size)
        assertEquals(4, placed.size)
        assertEquals(listOf("Kitchen", "Porch"), groups[0].second.map { it.node.name })
        assertEquals(listOf("Sam's iPhone"), groups[1].second.map { it.node.name })
        assertEquals("a device with no known link is in the wild", listOf("Unknown"), groups[2].second.map { it.node.name })
        // Two links to one device: the narrower tier.
        assertEquals(LinkTier.ASSOCIATE, LinkTierSections.row(d.hubs.first { !it.thisPhone },
            listOf(LinkTierSections.Part("lnk_a", LinkTier.OWN), LinkTierSections.Part("viewing:x", LinkTier.ASSOCIATE))).tier)
    }

    // ---- Codes, claims and the stored link ------------------------------------------------------------

    private fun claim(tier: LinkTier, services: Set<String>, accessMs: Long? = null, associate: String? = null): Links.Rec {
        name("test-target")
        val made = app.links.makeCode(services, setOf("in"), emptySet(), Granter.person("test-target"), tier = tier, accessMs = accessMs, associate = associate,
            associateEmail = if (tier == LinkTier.ASSOCIATE) "sam@example.com" else null)
        assertEquals(tier.wire, made.getString("tier"))
        val code = app.links.redeem(LinkCode.keys(LinkCode.normalise(made.getString("code"))!!, "test-target").claimToken)
        val r = app.links.acceptClaim(code, "100.64.0.40", JSONObject().put("name", "Sam's tablet").put("bridgit_name", "sams-tab")
            .put("install", "bgi_bbbbbbbbbbbbbbbbbbbbbbbb"))
        assertEquals(tier.wire, r.getString("tier"))
        return app.links.get(r.getString("link_id"))!!
    }

    @Test fun codesCheckTheTier() {
        name("test-target")
        val g = Granter.person("test-target")
        err("invalid_parameter") { app.links.makeCode(setOf("view"), setOf("in"), emptySet(), g, tier = LinkTier.ASSOCIATE, associate = "Sam") } // no email
        err("invalid_parameter") { app.links.makeCode(setOf("view"), setOf("in"), emptySet(), g, tier = LinkTier.ASSOCIATE, associateEmail = "not an email") }
        err("invalid_parameter") { app.links.makeCode(setOf("camera"), setOf("in"), emptySet(), g, tier = LinkTier.ASSOCIATE, associate = "Sam", associateEmail = "sam@example.com") }
        err("invalid_parameter") { app.links.makeCode(setOf("view"), setOf("in"), emptySet(), g, tier = LinkTier.ASSOCIATE, associateEmail = "sam@example.com", accessMs = 91 * LinkTierPolicy.DAY_MS) }
        err("invalid_parameter") { app.links.makeCode(setOf("view", "interactions"), setOf("in"), emptySet(), g, tier = LinkTier.PUBLIC) }
        err("invalid_parameter") { app.links.makeCode(setOf("view"), setOf("in", "out"), emptySet(), g, tier = LinkTier.PUBLIC) }
        err("invalid_parameter") { app.links.makeCode(setOf("view"), setOf("in"), emptySet(), g, tier = LinkTier.PUBLIC, accessMs = 2 * LinkTierPolicy.DAY_MS) }
        err("invalid_parameter") { app.links.makeCode(setOf("camera"), setOf("in"), emptySet(), g, accessMs = 1000L * 3600) } // own never ends
        err("invalid_parameter") { app.links.makeCode(setOf("view"), setOf("in"), emptySet(), g, tier = LinkTier.PUBLIC, associate = "Sam") }
    }

    @Test fun anAssociateClaimGetsOnlyScopedGrantsAndAnEndDate() {
        val before = System.currentTimeMillis()
        val r = claim(LinkTier.ASSOCIATE, setOf("view", "interactions"), associate = "Sam")
        assertEquals(LinkTier.ASSOCIATE, r.effectiveTier)
        assertEquals("Sam", r.associate)
        assertTrue(r.expiresAt!! in before + LinkTierPolicy.ASSOCIATE_DEFAULT_MS..System.currentTimeMillis() + LinkTierPolicy.ASSOCIATE_DEFAULT_MS)
        assertEquals(setOf("portal", "interactions"), app.agents.get(r.ref)!!.caps)
        err("tier_not_allowed") { app.links.setDirection(r.id, "in", setOf("camera"), null, null, Granter.person("test-target")) }
        assertEquals("register", LinkRegistry.Outbox.pending(app).single { it.getString("link_id") == r.id }.getString("op"))
    }

    /** The backend registration waits in the outbox with only what the backend needs; the other side's id gets confirmed. */
    @Test fun registrationsCarryOnlyWhatTheBackendNeeds() {
        val r = claim(LinkTier.ASSOCIATE, setOf("view", "interactions"))
        val o = LinkRegistry.Outbox.pending(app).single { it.getString("link_id") == r.id }
        assertEquals("associate", o.getString("tier"))
        assertEquals("sam@example.com", o.getString("email"))
        assertEquals("linked_bridgit", o.getString("kind"))
        assertEquals(listOf("interactions", "view"), (0 until o.getJSONArray("services").length()).map { o.getJSONArray("services").getString(it) })
        assertFalse("no keys or tokens", o.toString().contains("brg_") || o.toString().contains("rly_"))
        val reg = LinkRegistry.Registration.fromJson(o)!!
        assertEquals(r.expiresAt, reg.expiresAt)
        app.links.applyFar(r.id, JSONObject().put("registration", "dlk_" + "a".repeat(32)))
        assertEquals("dlk_" + "a".repeat(32), LinkRegistry.Backend.peerStatus(app, r.id)!!.getString("id"))
        assertNull("this side's own registration is separate", LinkRegistry.Backend.status(app, r.id))
        app.links.applyFar(r.id, JSONObject().put("registration", "not-an-id"))
        assertEquals("dlk_" + "a".repeat(32), LinkRegistry.Backend.peerStatus(app, r.id)!!.getString("id"))
    }

    /** No persistent control token for a device in the wild: at its end date its link agent is gone. */
    @Test fun aPublicLinkEndsWithinADayAndLosesItsKey() {
        val r = claim(LinkTier.PUBLIC, setOf("view"))
        assertTrue(r.expiresAt!! <= System.currentTimeMillis() + LinkTierPolicy.PUBLIC_MAX_MS)
        assertEquals(setOf("portal"), app.agents.get(r.ref)!!.caps)
        // Widening a public link to a lasting one isn't possible without the other side, and its end date stays.
        app.links.setTier(r.id, LinkTier.PUBLIC, null, null, null, Granter.person("test-target"))
        assertTrue(app.links.get(r.id)!!.expiresAt!! <= System.currentTimeMillis() + LinkTierPolicy.PUBLIC_MAX_MS)
        app.links.expireDue(r.expiresAt!! + 1)
        assertNull("the record is gone", app.links.get(r.id))
        assertNull("and the link agent's key with it", app.agents.get(r.ref))
        assertEquals("revoke", LinkRegistry.Outbox.pending(app).single { it.getString("link_id") == r.id }.getString("op"))
        assertTrue(app.links.log.all().any { it.linkId == r.id && it.event == "disconnected" && it.granter?.rule == "expired" })
    }

    /** Revocation from this side: disconnect makes the associate's key answer link_revoked, as for own links. */
    @Test fun revokingAnAssociate() {
        val r = claim(LinkTier.ASSOCIATE, setOf("view"), associate = "Sam")
        val g = Granter.person("test-target")
        app.links.disconnect(r.id, "both", g)
        app.links.finishDue(System.currentTimeMillis() + Links.UNDO_MS + 1)
        assertNull(app.links.get(r.id)); assertNull(app.agents.get(r.ref))
        assertEquals("revoke", LinkRegistry.Outbox.pending(app).single { it.getString("link_id") == r.id }.getString("op"))
    }

    /** Revocation from the other side: its disconnect event removes the link here too. */
    @Test fun anAssociateCanRevokeFromTheirSide() {
        val r = claim(LinkTier.ASSOCIATE, setOf("view"), associate = "Sam")
        app.links.receive(r, JSONObject().put("link_id", r.id).put("events", org.json.JSONArray().put(JSONObject().put("id", "lev_far1")
            .put("at", System.currentTimeMillis()).put("link_id", r.id).put("direction", "out").put("event", "disconnected"))))
        assertTrue("its out is this side's in: off at once", app.links.get(r.id)!!.inRevoked)
        assertEquals(emptySet<String>(), app.agents.get(r.ref)!!.caps)
    }

    /** Narrowing applies at once; widening needs both sides; the other side's narrower tier narrows this one. */
    @Test fun changingTiers() {
        name("test-target")
        val made = app.links.makeCode(setOf("camera", "view"), setOf("in"), emptySet(), Granter.person("test-target"))
        val code = app.links.redeem(LinkCode.keys(LinkCode.normalise(made.getString("code"))!!, "test-target").claimToken)
        val id = app.links.acceptClaim(code, "100.64.0.41", JSONObject().put("bridgit_name", "own-tab")).getString("link_id")
        val r = app.links.get(id)!!
        assertEquals(LinkTier.OWN, r.effectiveTier); assertNull(r.expiresAt)
        val g = Granter.person("test-target")
        err("invalid_parameter") { app.links.setTier(id, LinkTier.ASSOCIATE, null, null, "Sam", g) } // needs an email
        app.links.setTier(id, LinkTier.ASSOCIATE, null, null, "Sam", g, associateEmail = "Sam@Example.com")
        assertEquals("sam@example.com", app.links.get(id)!!.associateEmail)
        val a = app.links.get(id)!!
        assertEquals(setOf("view"), a.inServices)
        assertEquals("camera dropped from the link agent", setOf("portal"), app.agents.get(r.ref)!!.caps)
        assertTrue(a.expiresAt != null)
        // The other side says public: what applies narrows at once, with an end date within a day.
        app.links.applyFar(id, JSONObject().put("tier", "public"))
        val p = app.links.get(id)!!
        assertEquals(LinkTier.PUBLIC, p.effectiveTier)
        assertTrue(p.expiresAt!! <= System.currentTimeMillis() + LinkTierPolicy.PUBLIC_MAX_MS)
        // Widening here alone changes nothing that applies.
        app.links.setTier(id, LinkTier.OWN, null, null, null, g)
        assertEquals(LinkTier.OWN, app.links.get(id)!!.tier)
        assertEquals(LinkTier.PUBLIC, app.links.get(id)!!.effectiveTier)
        assertEquals("update", LinkRegistry.Outbox.pending(app).single { it.getString("link_id") == id }.getString("op"))
        assertEquals("what applies", "public", app.links.json(app.links.get(id)!!).getString("tier"))
        assertEquals("what this side tells the other", "own", app.links.stateJson(app.links.get(id)!!).getString("tier"))
    }

    /** Existing links migrate to own; Portal pairings (handed over as a one-time link) to associate with a review date only. */
    @Test fun existingLinksAndPortalPairingsMigrate() {
        val (hubAgent, _, _) = app.hubLinks.claimLink("100.64.0.30", "Kitchen tablet", SatellitePolicy.PHASE1_CAPS)
        assertEquals(LinkTier.OWN, app.links.all().single { it.ref == hubAgent }.effectiveTier)
        assertEquals("own", app.links.log.all().single { it.event == "migrated" }.toJson().getJSONObject("after").getString("tier"))
        val t = PortalTiers(app)
        val now = System.currentTimeMillis()
        val e = t.get(PortalTiers.viewer("agt_portal1"), now)
        assertEquals(LinkTier.ASSOCIATE, e.tier)
        assertEquals(now + LinkTierPolicy.MIGRATED_REVIEW_MS, e.reviewAt)
        assertNull("never cut off by the migration", e.expiresAt)
        assertEquals("kept", e, t.get(PortalTiers.viewer("agt_portal1"), now + 5))
        assertEquals(LinkTier.ASSOCIATE, t.get(PortalTiers.viewing("dev1")).tier)
        assertEquals(PortalTiers.viewer("agt_x"), PortalTiers.keyFromApiId("portal:agt_x"))
        assertNull(PortalTiers.keyFromApiId("lnk_abcdefghijklmnopqrst"))
    }

    // ---- Enforcement where link requests are authorized (ApiServer.linkAgentCall) -----------------------

    private fun api() = ApiServer(Robolectric.buildService(BridgeService::class.java).create().get(), emptyList(), 0).also { it.prepareRelay() }
    private suspend fun call(api: ApiServer, agentId: String, spec: EndpointSpec, body: JSONObject = JSONObject()) =
        api.dispatch(spec, app.agents.get(agentId)!!, "100.64.0.40", emptyMap(), emptyMap(), body, null)

    @Test fun anAssociateCantUseWhatItsTierDoesntAllowEvenWithTheGrant() = runBlocking {
        val r = claim(LinkTier.ASSOCIATE, setOf("view"), associate = "Sam")
        // Grants widened behind the link's back: the tier still bounds them.
        app.agents.update(r.ref) { it.copy(caps = setOf("portal", "camera")) }
        val api = api()
        assertEquals("not_on_target", call(api, r.ref, Endpoints.SNAPSHOT).error?.code)
        assertEquals("tier_not_allowed", call(api, r.ref, SatelliteEndpoints.HUB_AGENT_PAUSE).error?.code)
        assertNotEquals("tier_not_allowed", call(api, r.ref, Endpoints.HEALTH).error?.code)
    }

    @Test fun aPublicLinkIsRateLimitedAndAsksBeforeChanges() = runBlocking {
        val r = claim(LinkTier.PUBLIC, setOf("interactions"))
        val api = api()
        PublicLinkApproval.autoAnswer = false
        val create = Endpoints.all.first { it.method == "POST" && it.capability == "interactions" && SatellitePolicy.forwardable(it) }
        assertEquals("link_approval_declined", call(api, r.ref, create, JSONObject().put("kind", "notice").put("title", "hi")).error?.code)
        PublicLinkApproval.autoAnswer = null
        val codes = (1..12).map { call(api, r.ref, Endpoints.HEALTH).error?.code }
        assertTrue(codes.toString(), codes.take(9).none { it == "rate_limited" })
        assertEquals("rate_limited", codes.last())
    }

    @Test fun anOwnLinkKeepsWhatItHad() = runBlocking {
        name("test-target")
        val made = app.links.makeCode(setOf("camera"), setOf("in"), emptySet(), Granter.person("test-target"))
        val code = app.links.redeem(LinkCode.keys(LinkCode.normalise(made.getString("code"))!!, "test-target").claimToken)
        val ref = app.links.get(app.links.acceptClaim(code, "100.64.0.42", JSONObject().put("bridgit_name", "own-two")).getString("link_id"))!!.ref
        val api = api()
        val e = call(api, ref, Endpoints.SNAPSHOT, JSONObject().put("camera", "rear")).error?.code
        assertTrue(e.toString(), e !in setOf("not_on_target", "tier_not_allowed", "link_expired", "rate_limited"))
    }
}
