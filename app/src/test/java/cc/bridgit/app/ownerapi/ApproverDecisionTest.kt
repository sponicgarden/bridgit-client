package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.applets.AppletPackage
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.core.authority.Authority
import cc.bridgit.app.core.authority.AuthorityLimits
import cc.bridgit.app.core.authority.AuthorityStore
import cc.bridgit.app.core.authority.AuthorityStore.Outcome
import cc.bridgit.app.core.authority.AuthorityStore.Refusal
import cc.bridgit.app.core.authority.OwnerAuth
import cc.bridgit.app.core.authority.SoftwareApproverKeys
import cc.bridgit.app.platform.Capabilities
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
import java.io.File

/**
 * SA1.5: a delegable approval decided by an approver's answer. With approver devices off, deciding is
 * exactly what it was; with them on, an agent's own decision still is, and the owner can be asked instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApproverDecisionTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private lateinit var caller: Agent
    private lateinit var kit: ApproverTestKit
    private val api get() = ConfigurationApprovals(app, caller)

    @Before fun setup() {
        app.settings.listenerEnabled = true
        app.settings.emailVerifiedAt = System.currentTimeMillis()
        app.settings.setOn("interactions", true)
        // A controller that may configure but holds none of the authority the applet needs.
        caller = app.agents.add("Asking controller", Connection.DIRECT).first
        caller = app.agents.update(caller.id) { it.copy(caps = setOf("owner_features")) }!!
        Capabilities.setEnabled(app, "owner.configuration", true)
        app.platform.grant(caller.id, "owner.configuration", Long.MAX_VALUE)
        kit = ApproverTestKit(app, "authority-decision-test.db")
        kit.emailVerified()
    }

    private fun proposal(by: Agent? = null, edit: (JSONObject) -> Unit = {}): String {
        val d = JSONObject(File("../site-content/public/recipes/room-change-watcher/applet.json").readText())
        d.put("id", "approval-test").put("name", "Approval test").put("processing", JSONObject())
            .put("config_schema", JSONObject("""{"type":"object","properties":{},"required":[],"additionalProperties":false}""")).put("defaults", JSONObject())
        d.put("permissions", JSONObject().put("required", JSONArray(listOf("interactions"))).put("optional", JSONArray()).put("automatic_grants", false))
        d.put("ui", JSONObject().put("card", JSONObject("""{"title":"Approval test","pieces":[{"type":"header","text":"Approval test"}]}""")))
        edit(d)
        return app.applets.propose(AppletPackage.check(d), null, JSONObject().put("kind", "template").put("template", "approval-test").put("revision", 1), by, notify = false).getString("id")
    }
    private fun digest(id: String) = api.get("applet", id).getString("expected_digest")
    private fun ask(id: String) = runBlocking { api.decide("applet", id, JSONObject().put("approve", true).put("expected_digest", digest(id)).put("owner_authorization", true)) }
    private fun rejected(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: ApiError) { assertEquals(e.message, code, e.code) }
    }
    /** An enrolled self-approver, with the owner path turned on as it will be once its review screen exists. */
    private fun enrolled() {
        app.authority.ownerReviewBuilt = true
        kit.enable()
        app.authority.startSelfEnrol("test")
        kit.pass()
        assertEquals(1, app.authority.store.approvers().size)
    }
    private fun check(request: String) = app.authority.store.proposal("check_$request")!!
    private fun signedApproval(request: String, keys: SoftwareApproverKeys = kit.keys): OwnerAuth.Answer {
        val r = app.authority.store.request(request)!!
        val self = kit.install.id()!!
        val bytes = OwnerAuth.bytes(r.purpose, OwnerAuth.APPROVE, self, self, r.digest, r.id, r.generation, r.expiresMs)
        return OwnerAuth.Answer(OwnerAuth.LABEL, r.purpose, OwnerAuth.APPROVE, self, self, keys.beginSign(bytes).finish())
    }
    private fun refusedWith(reason: Refusal, out: Outcome<*>) = assertEquals(out.toString(), reason, (out as? Outcome.Refused)?.reason)

    @Test fun withTheSwitchOffDecidingIsExactlyWhatItWas() = runBlocking {
        val id = proposal()
        val review = api.get("applet", id)
        assertFalse(review.has("can_ask_owner"))
        assertFalse(review.getBoolean("can_decide"))
        // The new field is an unknown field, as any other.
        rejected("invalid_parameter") { runBlocking { api.decide("applet", id, JSONObject().put("approve", true).put("expected_digest", digest(id)).put("owner_authorization", true)) } }
        rejected("agent_not_allowed") { runBlocking { api.decide("applet", id, JSONObject().put("approve", true).put("expected_digest", digest(id))) } }
        // An agent that holds the authority decides as before, and nothing of the authority store is touched.
        caller = app.agents.update(caller.id) { it.copy(caps = setOf("owner_features", "applet_install", "interactions")) }!!
        val result = api.decide("applet", id, JSONObject().put("approve", true).put("expected_digest", digest(id))).getJSONObject("result")
        assertNotNull(app.applets.get(result.getString("id")))
        kit.idle()
        assertFalse(app.authority.isOpen)
    }

    @Test fun askingTheOwnerIsNotOfferedYet() = runBlocking {
        enrolled()
        app.authority.ownerReviewBuilt = false // as shipped
        val id = proposal()
        assertFalse(api.get("applet", id).has("can_ask_owner"))
        rejected("invalid_parameter") { runBlocking { api.decide("applet", id, JSONObject().put("approve", true).put("expected_digest", digest(id)).put("owner_authorization", true)) } }
        assertTrue(app.authority.store.openRequests().isEmpty())
        assertEquals(1, kit.shown.size)
        assertFalse(ConfigurationEndpoints.APPROVAL_DECIDE.body.any { it.name == "owner_authorization" })
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun aRequesterPausedWhileTheAppletPreparesIsNotActivated() {
        enrolled()
        val requester = app.agents.add("Builder", Connection.DIRECT).first
        val bytes = File("../site-content/public/samples/sample-photo.jpg").readBytes()
        val id = proposal(requester) { d -> d.put("assets", JSONObject().put("review.jpg", JSONObject()
            .put("storage", "revision").put("media_type", "image/jpeg").put("size", bytes.size).put("sha256", AppletPackage.sha256(bytes)))) }
        var prepared = 0
        // The media is fetched after the first check passed; the requester is paused right then.
        app.applets.packageMedia = cc.bridgit.app.applets.AppletMedia(File(app.cacheDir, "paused-approval-media")) { _, _, out ->
            out.writeBytes(bytes)
            prepared++
            app.agents.update(requester.id) { it.copy(paused = true) }
        }
        val request = ask(id).getString("request_id")
        val out = app.authority.answer(request, signedApproval(request))
        assertEquals(1, prepared)
        refusedWith(Refusal.CHECK_FAILED, out)
        assertTrue(app.applets.all().isEmpty())
        assertNotNull(app.applets.pendingGet(id))
        assertEquals("nothing was spent", AuthorityStore.OPEN, app.authority.store.request(request)!!.state)
        // Resumed, the same signature activates it.
        app.agents.update(requester.id) { it.copy(paused = false) }
        app.applets.packageMedia = cc.bridgit.app.applets.AppletMedia(File(app.cacheDir, "paused-approval-media")) { _, _, o -> o.writeBytes(bytes) }
        assertTrue(app.authority.answer(request, signedApproval(request)) is Outcome.Ok)
        assertEquals(1, app.applets.all().size)
    }

    @Test fun withTheSwitchOnAnAgentsOwnDecisionStillWorksAsToday() = runBlocking {
        enrolled()
        caller = app.agents.update(caller.id) { it.copy(caps = setOf("owner_features", "applet_install", "interactions")) }!!
        val id = proposal()
        assertTrue(api.get("applet", id).getBoolean("can_ask_owner"))
        val result = api.decide("applet", id, JSONObject().put("approve", true).put("expected_digest", digest(id))).getJSONObject("result")
        assertNotNull(app.applets.get(result.getString("id")))
        assertTrue(kit.authority.store.openRequests().isEmpty())
        val declined = proposal()
        api.decide("applet", declined, JSONObject().put("approve", false).put("expected_digest", digest(declined)))
        assertNull(app.applets.pendingGet(declined))
    }

    @Test fun theOwnerDecidesOnTheApproverWhatTheAgentCouldNot() {
        enrolled()
        val id = proposal()
        rejected("agent_not_allowed") { runBlocking { api.decide("applet", id, JSONObject().put("approve", true).put("expected_digest", digest(id))) } }
        val asked = ask(id)
        assertEquals("awaiting_owner_authorization", asked.getString("status"))
        assertFalse(asked.getBoolean("approved"))
        assertEquals("owner_check", asked.getJSONObject("remaining_human_step").getString("step"))
        val request = asked.getString("request_id")
        assertEquals("approval.applet", app.authority.store.request(request)!!.purpose)
        assertNotNull("nothing is applied before the check", app.applets.pendingGet(id))
        // Asking again offers the same request.
        assertEquals(request, ask(id).getString("request_id"))
        assertEquals(1, app.authority.store.openRequests().size)

        kit.pass("check_$request")
        assertNull(app.applets.pendingGet(id))
        assertEquals(1, app.applets.all().size)
        assertEquals(AuthorityStore.APPLIED, app.authority.store.request(request)!!.state)
        assertEquals(kit.install.id(), app.authority.store.request(request)!!.approver)
        assertEquals(Authority.DONE, check(request).state)
        assertTrue(JSONObject(check(request).body).getJSONObject("result").getBoolean("approved"))
        // The same signature again is a replay: the one-use id is spent.
        refusedWith(Refusal.NONCE_REUSED, app.authority.answer(request, signedApproval(request)))
        assertEquals(1, app.applets.all().size)
    }

    @Test fun aWorkflowIsActivatedByTheOwnersAnswerThroughItsNativePath() {
        enrolled()
        val builder = app.agents.add("Builder", Connection.DIRECT).first
        Capabilities.setEnabled(app, "interactions", true)
        Capabilities.setEnabled(app, "workflows", true)
        app.platform.grant(builder.id, "interactions", Long.MAX_VALUE)
        app.platform.grant(builder.id, "workflows", Long.MAX_VALUE)
        val flow = JSONObject("""{"name":"Approval test","trigger":{"type":"called"},"steps":[{"id":"note","type":"notice","title":"Test","text":"Test"}]}""")
        val id = app.platform.workflows.propose(builder.id, flow).getString("id")
        fun digest() = api.get("workflow", id).getString("expected_digest")
        // The asking controller holds none of the workflow's scopes, so it cannot decide; the owner can.
        rejected("agent_not_allowed") { runBlocking { api.decide("workflow", id, JSONObject().put("approve", true).put("expected_digest", digest())) } }
        val asked = runBlocking { api.decide("workflow", id, JSONObject().put("approve", true).put("expected_digest", digest()).put("owner_authorization", true)) }
        val request = asked.getString("request_id")
        assertEquals("approval.workflow", app.authority.store.request(request)!!.purpose)
        assertEquals(builder.id, app.authority.store.request(request)!!.requester)
        // The requester lost a scope the workflow needs while the owner was deciding: the native activation refuses, nothing is spent.
        app.platform.revoke(builder.id, "interactions")
        val refused = app.authority.answer(request, signedApproval(request))
        refusedWith(Refusal.CHECK_FAILED, refused)
        assertEquals("agent_not_allowed", (refused as Outcome.Refused).detail)
        assertEquals("awaiting_owner", app.platform.store.find(id, kind = "workflow")!!.getString("status"))
        assertEquals(AuthorityStore.OPEN, app.authority.store.request(request)!!.state)
        app.platform.grant(builder.id, "interactions", Long.MAX_VALUE)
        kit.idle()
        kit.pass("check_$request")
        assertEquals("active", app.platform.store.find(id, kind = "workflow")!!.getString("status"))
        assertEquals(AuthorityStore.APPLIED, app.authority.store.request(request)!!.state)
        assertEquals(Authority.DONE, check(request).state)
    }

    @Test fun asksAreValidatedLikeDecisions() {
        enrolled()
        val id = proposal()
        rejected("invalid_parameter") { runBlocking { api.decide("applet", id, JSONObject().put("approve", true).put("expected_digest", digest(id)).put("owner_authorization", "yes")) } }
        rejected("invalid_parameter") { runBlocking { api.decide("applet", id, JSONObject().put("approve", false).put("expected_digest", digest(id)).put("owner_authorization", true)) } }
        rejected("invalid_parameter") { runBlocking { api.decide("applet", id, JSONObject().put("approve", true).put("owner_authorization", true)) } }
        rejected("conflict") { runBlocking { api.decide("applet", id, JSONObject().put("approve", true).put("expected_digest", "0".repeat(64)).put("owner_authorization", true)) } }
        rejected("not_found") { runBlocking { api.decide("applet", "nope", JSONObject().put("approve", true).put("expected_digest", "0".repeat(64)).put("owner_authorization", true)) } }
        rejected("invalid_parameter") { runBlocking { api.decide("payment", id, JSONObject().put("approve", true).put("expected_digest", digest(id)).put("owner_authorization", true)) } }
        app.platform.revoke(caller.id, "owner.configuration")
        rejected("agent_not_allowed") { ask(id) }
        assertTrue(app.authority.store.openRequests().isEmpty())
        assertEquals(1, kit.shown.size) // only the enrolment's check was ever shown
    }

    @Test fun withoutAnApproverThatCanDecideTheAskIsRefused() {
        app.authority.ownerReviewBuilt = true
        kit.enable()
        val id = proposal()
        rejected("no_approver") { ask(id) }
        assertTrue(app.authority.store.openRequests().isEmpty())
    }

    @Test fun aSignatureByAnotherKeyOrForAnotherRequestIsRefusedAndTheRequestStaysOpen() {
        enrolled()
        val id = proposal()
        val request = ask(id).getString("request_id")
        val stranger = SoftwareApproverKeys().apply { create(ByteArray(32)) }
        refusedWith(Refusal.CHECK_FAILED, app.authority.answer(request, signedApproval(request, stranger)))
        // A decline's install-key signature is never an approval, and an approval is never under the install key.
        val good = signedApproval(request)
        refusedWith(Refusal.CHECK_FAILED, app.authority.answer(request, OwnerAuth.Answer(good.label, good.purpose, OwnerAuth.DECLINE, good.target, good.approver, good.signatureP1363)))
        refusedWith(Refusal.CHECK_FAILED, app.authority.answer(request, OwnerAuth.Answer(good.label, "approval.schedule", good.decision, good.target, good.approver, good.signatureP1363)))
        refusedWith(Refusal.UNKNOWN_APPROVER, app.authority.answer(request, OwnerAuth.Answer(good.label, good.purpose, good.decision, good.target, "bgi_someone", good.signatureP1363)))
        refusedWith(Refusal.UNKNOWN_REQUEST, app.authority.answer("no-such-request", good))
        assertNotNull(app.applets.pendingGet(id))
        assertEquals(AuthorityStore.OPEN, app.authority.store.request(request)!!.state)
        // The real one still works afterwards.
        assertTrue(app.authority.answer(request, good) is Outcome.Ok)
        assertNull(app.applets.pendingGet(id))
    }

    @Test fun theRequestIsCheckedAgainAgainstTodaysStateWhenItIsApplied() {
        enrolled()
        val requester = app.agents.add("Builder", Connection.DIRECT).first
        // The request changed after the owner was asked: the signature is valid and the request is still refused.
        val changedId = proposal()
        val changed = ask(changedId).getString("request_id")
        runBlocking { app.applets.decline(changedId) }
        val out = app.authority.answer(changed, signedApproval(changed))
        refusedWith(Refusal.CHECK_FAILED, out)
        assertEquals("no longer pending", (out as Outcome.Refused).detail)
        assertTrue(app.applets.all().isEmpty())
        // The requester was paused while the request waited (no generation rise is needed for the refusal).
        val pausedId = proposal(requester)
        val paused = ask(pausedId).getString("request_id")
        app.agents.update(requester.id) { it.copy(paused = true) }
        val refused = app.authority.answer(paused, signedApproval(paused))
        refusedWith(Refusal.CHECK_FAILED, refused)
        assertEquals("the requester is no longer active", (refused as Outcome.Refused).detail)
        assertNotNull(app.applets.pendingGet(pausedId))
        // Resumed, the same request and signature are accepted: the refusal spent nothing.
        app.agents.update(requester.id) { it.copy(paused = false) }
        assertTrue(app.authority.answer(paused, signedApproval(paused)) is Outcome.Ok)
        assertNull(app.applets.pendingGet(pausedId))
    }

    @Test fun anAnswerAfterTheLifetimeOrAfterTheSwitchWentOffIsRefused() {
        enrolled()
        val id = proposal()
        val request = ask(id).getString("request_id")
        val answer = signedApproval(request)
        kit.time.run(AuthorityLimits.REQUEST_LIFETIME_MS + 1)
        assertNull("an expired request has no check left to show", app.authority.check("check_$request"))
        assertEquals(Authority.EXPIRED, check(request).state)
        refusedWith(Refusal.EXPIRED, app.authority.answer(request, answer))
        assertNotNull(app.applets.pendingGet(id))
        // Asking again makes a new request; turning the feature off cancels it at once.
        val second = ask(id).getString("request_id")
        assertTrue(second != request)
        val late = signedApproval(second)
        val off = app.authority.requestOff("another-agent")
        kit.pass("check_${off.request}")
        assertFalse(app.settings.approversEnabled)
        assertEquals(AuthorityStore.CANCELLED, app.authority.store.request(second)!!.state)
        refusedWith(Refusal.NOT_OPEN, app.authority.answer(second, late))
        assertNotNull(app.applets.pendingGet(id))
    }

    @Test fun aClosedPromptLeavesTheRequestOpenAndItCanBeAskedAgain() {
        enrolled()
        val id = proposal()
        val request = ask(id).getString("request_id")
        kit.cancel("check_$request")
        assertEquals(Authority.DISMISSED, check(request).state)
        assertEquals(AuthorityStore.OPEN, app.authority.store.request(request)!!.state)
        rejected("rate_limited") { ask(id) }
        assertEquals(Authority.DISMISSED, check(request).state)
        kit.time.run(Authority.DISMISS_QUIET_MS)
        assertEquals(request, ask(id).getString("request_id"))
        assertEquals(Authority.WAITING, check(request).state)
        kit.pass("check_$request")
        assertNull(app.applets.pendingGet(id))
    }
}
