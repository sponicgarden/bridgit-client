package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.applets.AppletPackage
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.platform.Capabilities
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigurationApprovalsTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private lateinit var caller: Agent
    private val api get() = ConfigurationApprovals(app, caller)
    @Before fun setup() {
        app.settings.listenerEnabled = true
        app.settings.emailVerifiedAt = System.currentTimeMillis()
        app.settings.setOn("interactions", true)
        caller = app.agents.add("Approving controller", Connection.DIRECT).first
        caller = app.agents.update(caller.id) { it.copy(caps = setOf("owner_features", "applet_install", "interactions", "schedule")) }!!
        Capabilities.setEnabled(app, "owner.configuration", true)
        app.platform.grant(caller.id, "owner.configuration", Long.MAX_VALUE)
    }
    private fun proposal(edit: (JSONObject) -> Unit = {}): String {
        val d = JSONObject(File("../site-content/public/recipes/room-change-watcher/applet.json").readText())
        d.put("id", "approval-test").put("name", "Approval test").put("processing", JSONObject()).put("config_schema", JSONObject("""{"type":"object","properties":{},"required":[],"additionalProperties":false}""")).put("defaults", JSONObject())
        d.put("permissions", JSONObject().put("required", JSONArray(listOf("interactions"))).put("optional", JSONArray()).put("automatic_grants", false))
        d.put("ui", JSONObject().put("card", JSONObject("""{"title":"Approval test","pieces":[{"type":"header","text":"Approval test"}]}""")))
        edit(d)
        return app.applets.propose(AppletPackage.check(d), null, JSONObject().put("kind", "template").put("template", "approval-test").put("revision", 1), null, notify = false).getString("id")
    }
    private fun decision(kind: String, id: String, approve: Boolean = true) = JSONObject().put("approve", approve)
        .put("expected_digest", api.get(kind, id).getString("expected_digest"))
    private fun rejected(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: ApiError) { assertEquals(e.message, code, e.code) }
    }
    @Test fun controllerApprovesOwnerProposedAppletWithoutAnotherPhoneReview() = runBlocking {
        val id = proposal()
        assertTrue(api.get("applet", id).getBoolean("can_decide"))
        val result = api.decide("applet", id, decision("applet", id)).getJSONObject("result")
        assertNotNull(app.applets.get(result.getString("id")))
        assertNull(app.applets.pendingGet(id))
    }
    @Test fun exactReviewBindsConfigAndRejectsChangedRequestBeforeEffects() = runBlocking {
        val id = proposal(); val body = decision("applet", id)
        // Even non-package pending fields are bound; UI/config/source/deletion/revision changes invalidate inspection.
        val changed = JSONObject(app.applets.pendingGet(id)!!.toString()).put("expected_revision", 42)
        ConfigurationApprovals.matches("applet", id, app.applets.pendingGet(id)!!, body.getString("expected_digest"))
        rejected("conflict") { ConfigurationApprovals.matches("applet", id, changed, body.getString("expected_digest")) }
        body.put("expected_digest", "0".repeat(64))
        rejected("conflict") { runBlocking { api.decide("applet", id, body) } }
        assertNotNull(app.applets.pendingGet(id)); assertTrue(app.applets.all().isEmpty())
    }
    @Test fun revokedPausedRotatedAndAppletPrincipalsCannotDecide() = runBlocking {
        val id = proposal(); val body = decision("applet", id, false)
        app.platform.revoke(caller.id, "owner.configuration")
        rejected("agent_not_allowed") { runBlocking { api.decide("applet", id, body) } }
        app.platform.grant(caller.id, "owner.configuration", Long.MAX_VALUE)
        app.agents.update(caller.id) { it.copy(paused = true) }
        rejected("unauthorized") { api.list() }
        app.agents.update(caller.id) { it.copy(paused = false, connection = Connection.APPLET) }
        rejected("forbidden") { runBlocking { api.decide("applet", id, body) } }
        app.agents.update(caller.id) { it.copy(connection = Connection.DIRECT, keyHash = "changed") }
        rejected("unauthorized") { api.list() }
        assertNotNull(app.applets.pendingGet(id))
    }
    @Test fun insufficientDelegatedActionAuthorityCannotApprove() = runBlocking {
        val id = proposal(); val body = decision("applet", id, true)
        app.agents.update(caller.id) { it.copy(caps = setOf("owner_features")) }
        assertFalse(api.get("applet", id).getBoolean("can_decide"))
        rejected("agent_not_allowed") { runBlocking { api.decide("applet", id, body) } }
        assertNotNull(app.applets.pendingGet(id))
    }
    @Test fun declinedRequestCannotBeAppliedAgain() = runBlocking {
        val id = proposal(); val body = decision("applet", id, false)
        api.decide("applet", id, body)
        rejected("not_found") { runBlocking { api.decide("applet", id, body) } }
        assertTrue(app.applets.all().isEmpty())
    }
    @Test fun allNativeQueuesAreDiscoverableAndPrivateSessionCredentialsNeverReturned() {
        val schedule = JSONObject().put("manifest", JSONObject().put("actions", JSONArray())).put("digest", "pending")
        app.getSharedPreferences("schedules", 0).edit().putString(caller.id, JSONObject().put("pending", schedule).toString()).commit()
        val s = app.platform.store.save(caller.id, "session", JSONObject().put("status", "awaiting_owner").put("credential_hash", "private-credential")
            .put("apps", JSONArray(listOf("example.app"))).put("purpose", "Test").put("duration_seconds", 30))
        val records = api.list().getJSONArray("approvals")
        assertEquals(2, records.length())
        val detail = api.get("session", s.getString("id"))
        assertFalse(detail.toString().contains("private-credential"))
        assertEquals(5, api.list().getJSONArray("kinds").length())
        rejected("invalid_parameter") { api.get("pairing", "anything") }
    }
    @Test fun controllerCannotApproveScheduleWhoseActionsExceedItsOwnAuthority() = runBlocking {
        val pending = JSONObject().put("manifest", JSONObject("""{"actions":[{"id":"shot","when":{"daily":["07:00"]},"do":{"call":"POST /v1/camera/snapshot"}}]}""")).put("digest", "x")
        app.getSharedPreferences("schedules", 0).edit().putString(caller.id, JSONObject().put("pending", pending).toString()).commit()
        assertFalse(api.get("schedule", caller.id).getBoolean("can_decide"))
        rejected("agent_not_allowed") { runBlocking { api.decide("schedule", caller.id, decision("schedule", caller.id)) } }
        assertNotNull(app.schedules.record(caller.id)?.optJSONObject("pending"))
        api.decide("schedule", caller.id, decision("schedule", caller.id, false))
        assertNull(app.schedules.record(caller.id)?.optJSONObject("pending"))
    }
    @Test fun operationReviewShowsExactSafeInputAndRedactsCredentials() {
        val record = app.platform.store.save(caller.id, "operation", JSONObject().put("status", "awaiting_owner").put("command", "device.action")
            .put("input", JSONObject().put("type", "clipboard").put("text", "Inspected text").put("password", "never-return-this")))
        val detail = api.get("operation", record.getString("id"))
        assertEquals("Inspected text", detail.getJSONObject("review").getJSONObject("input").getString("text"))
        assertFalse(detail.toString().contains("never-return-this"))
    }
    @Test fun changedNativeRequestsConflictAndDeclineConsumesEveryQueue() = runBlocking {
        for (kind in listOf("workflow", "operation", "session")) {
            val record = app.platform.store.save(caller.id, kind, JSONObject().put("status", "awaiting_owner")
                .put("command", "device.action").put("input", JSONObject().put("type", "clipboard"))
                .put("definition", JSONObject()).put("required_scopes", JSONArray()).put("apps", JSONArray()).put("duration_seconds", 30))
            val id = record.getString("id"); val body = decision(kind, id, false)
            app.platform.store.save(caller.id, kind, record.put("purpose", "changed after inspection"))
            rejected("conflict") { runBlocking { api.decide(kind, id, body) } }
            assertEquals("awaiting_owner", app.platform.store.find(id, kind = kind)!!.getString("status"))
            api.decide(kind, id, decision(kind, id, false))
            assertTrue(app.platform.store.find(id, kind = kind)!!.getString("status") in setOf("declined", "cancelled"))
        }
    }

    @Test fun scheduleAndWorkflowUseNativeActivationAndRecheckTheirDigests() = runBlocking {
        val manifest = JSONObject("""{"actions":[{"id":"show","when":{"daily":["07:00"]},"do":{"step":{"type":"notice","title":"Test","text":"Test"}}}]}""")
        app.schedules.submit(caller, manifest, null)
        val scheduleDecision = decision("schedule", caller.id)
        api.decide("schedule", caller.id, scheduleDecision)
        assertNotNull(app.schedules.record(caller.id)?.optJSONObject("active"))
        assertNull(app.schedules.record(caller.id)?.optJSONObject("pending"))
        Capabilities.setEnabled(app, "interactions", true)
        app.platform.grant(caller.id, "interactions", Long.MAX_VALUE)
        Capabilities.setEnabled(app, "workflows", true)
        app.platform.grant(caller.id, "workflows", Long.MAX_VALUE)
        val flow = JSONObject("""{"name":"Approval test","trigger":{"type":"called"},"steps":[{"id":"note","type":"notice","title":"Test","text":"Test"}]}""")
        val id = app.platform.workflows.propose(caller.id, flow).getString("id")
        api.decide("workflow", id, decision("workflow", id))
        assertEquals("active", app.platform.store.find(id, kind = "workflow")!!.getString("status"))
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun revocationDuringMediaPreparationCannotActivateAnApplet() = runBlocking {
        val bytes = File("../site-content/public/samples/sample-photo.jpg").readBytes()
        val id = proposal { definition -> definition.put("assets", JSONObject().put("review.jpg", JSONObject()
            .put("storage", "revision").put("media_type", "image/jpeg").put("size", bytes.size).put("sha256", AppletPackage.sha256(bytes)))) }
        app.applets.packageMedia = cc.bridgit.app.applets.AppletMedia(File(app.cacheDir, "revoked-approval-media")) { _, _, out ->
            out.writeBytes(bytes)
            app.platform.revoke(caller.id, "owner.configuration")
        }
        val body = decision("applet", id)
        rejected("agent_not_allowed") { runBlocking { api.decide("applet", id, body) } }
        assertTrue(app.applets.all().isEmpty())
        assertNotNull(app.applets.pendingGet(id))
    }

}
