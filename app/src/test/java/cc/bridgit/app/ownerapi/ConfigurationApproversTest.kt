package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.McpTools
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.core.authority.ApproverKeys
import cc.bridgit.app.core.authority.Authority
import cc.bridgit.app.core.authority.AuthorityLimits
import cc.bridgit.app.core.authority.AuthorityOutbox
import cc.bridgit.app.core.authority.HOUR
import cc.bridgit.app.core.authority.SoftwareApproverKeys
import cc.bridgit.app.platform.Capabilities
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.Base64
import java.util.UUID

/** The approver configuration API (SA1.2, SA1.4b, SA1.6) through the same dispatch an agent's call takes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.LEGACY)
class ConfigurationApproversTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private lateinit var caller: Agent
    private lateinit var kit: ApproverTestKit
    private lateinit var api: ApiServer

    @Before fun setup() {
        TestAndroidKeystore.install()
        app.settings.listenerEnabled = true
        app.settings.emailVerifiedAt = System.currentTimeMillis()
        caller = controller("ConfigOwner")
        Capabilities.setEnabled(app, "owner.configuration", true)
        kit = ApproverTestKit(app, "authority-api-test.db")
        kit.emailVerified()
        api = ApiServer(Robolectric.buildService(BridgeService::class.java).create().get(), emptyList(), 0).also { it.prepareRelay() }
    }
    @After fun cleanup() { TestAndroidKeystore.remove() }

    private fun controller(name: String): Agent {
        val a = app.agents.add(name, Connection.DIRECT).first
        app.agents.update(a.id) { it.copy(caps = setOf("owner_features")) }
        app.platform.grant(a.id, "owner.configuration", Long.MAX_VALUE)
        return app.agents.get(a.id)!!
    }
    private fun call(spec: EndpointSpec, body: JSONObject = JSONObject(), by: Agent = caller) = runBlocking {
        api.dispatch(spec, app.agents.get(by.id)!!, "100.64.0.2", emptyMap(), emptyMap(), body, UUID.randomUUID().toString())
    }
    private fun ok(spec: EndpointSpec, body: JSONObject = JSONObject(), by: Agent = caller): JSONObject {
        val out = call(spec, body, by)
        assertEquals(String(out.bytes), 200, out.status)
        return JSONObject(String(out.bytes))
    }
    private fun refused(code: String, spec: EndpointSpec, body: JSONObject = JSONObject(), by: Agent = caller) {
        val out = call(spec, body, by)
        assertEquals(String(out.bytes), code, JSONObject(String(out.bytes)).optString("error"))
        assertEquals(cc.bridgit.app.api.ErrorCatalog.http(code), out.status)
    }
    private fun o(text: String) = JSONObject(text)
    private val self = o("""{"self":true}""")
    private fun list() = ok(ConfigurationApprovers.LIST)
    private fun enrolled() { kit.enable(); ok(ConfigurationApprovers.ENROL, self); kit.pass() }

    @Test fun offByDefaultNothingRunsReadsSayOffAndWritesAreRefused() {
        assertFalse(app.settings.approversEnabled)
        val l = list()
        assertFalse(l.getBoolean("enabled"))
        assertEquals("security.approvers_enabled", l.getString("setting"))
        assertFalse(l.getBoolean("can_enrol"))
        assertEquals("approvers_disabled", l.getString("blocked_reason"))
        assertEquals(0, l.getJSONArray("approvers").length())
        assertEquals("ready", l.getJSONObject("capability").getString("readiness"))
        assertFalse(ok(ConfigurationApprovers.RECOVERY).getBoolean("enabled"))
        refused("capability_disabled", ConfigurationApprovers.ENROL, self)
        refused("capability_disabled", ConfigurationApprovers.RECOVERY_START)
        refused("capability_disabled", ConfigurationApprovers.RECOVERY_CANCEL)
        // Every hook fires and none of them opens the authority store.
        val other = controller("Other")
        app.agents.update(other.id) { it.copy(paused = true) }
        app.agents.rotate(other.id)
        app.platform.revoke(other.id, "owner.configuration")
        app.agents.remove(other.id)
        app.settings.setOn("camera", false)
        Capabilities.setEnabled(app, "owner.configuration", true)
        app.authority.checkpoint()
        kit.idle()
        assertFalse(app.authority.isOpen)
        assertTrue(kit.shown.isEmpty())
        assertFalse(app.getDatabasePath("authority-api-test.db").exists())
    }

    @Test fun theSwitchIsAnOwnerSettingReadAndWrittenThroughTheConfigurationApi() {
        val index = ok(ConfigurationEndpoints.INDEX)
        assertEquals("/v1/owner/configuration/approvers", index.getString("approvers_url"))
        val row = (0 until index.getJSONArray("settings").length()).map { index.getJSONArray("settings").getJSONObject(it) }.first { it.getString("key") == Authority.SETTING }
        assertEquals(false, row.get("value"))
        assertTrue(row.getBoolean("writable"))
        refused("invalid_parameter", ConfigurationEndpoints.SETTINGS, o("""{"changes":{"security.approvers_enabled":"yes"}}"""))
        ok(ConfigurationEndpoints.SETTINGS, o("""{"changes":{"security.approvers_enabled":true}}"""))
        kit.idle()
        assertTrue(app.settings.approversEnabled)
        assertTrue(list().getBoolean("enabled"))
        assertEquals(listOf("master_switch"), app.authority.store.generation.log().map { it.second })
        // Ordinary agents read it and cannot change it: the settings API keeps it owner-only.
        val def = cc.bridgit.app.capability.SettingsController(app).def(Authority.SETTING)!!
        assertEquals(cc.bridgit.app.core.SettingLock.OWNER, def.lock)
        // Without the configuration grant nothing changes.
        app.platform.revoke(caller.id, "owner.configuration")
        assertEquals(403, call(ConfigurationEndpoints.SETTINGS, o("""{"changes":{"security.approvers_enabled":false}}""")).status)
        assertEquals(403, call(ConfigurationApprovers.LIST).status)
        assertTrue(app.settings.approversEnabled)
    }

    @Test fun thePublishedBuildCannotTurnApproverDevicesOnAndSaysSoInReads() {
        // Unit tests run as the debug build: turning it on is offered.
        assertFalse(app.authority.releaseBuild)
        assertTrue(list().getBoolean("can_enable"))
        assertTrue(list().isNull("enable_blocked_reason"))
        fun row() = ok(ConfigurationEndpoints.INDEX).getJSONArray("settings").let { s -> (0 until s.length()).map { s.getJSONObject(it) }.first { it.getString("key") == Authority.SETTING } }
        assertTrue(row().getBoolean("can_enable"))

        app.authority.releaseBuild = true
        app.settings.capturesOwnerFiles = false
        val out = call(ConfigurationEndpoints.SETTINGS, o("""{"changes":{"captures.owner_files":true,"security.approvers_enabled":true}}"""))
        assertEquals(String(out.bytes), 501, out.status)
        val refusal = JSONObject(String(out.bytes))
        assertEquals("platform_unsupported", refusal.getString("error"))
        assertEquals("not_available_in_this_build", refusal.getString("reason"))
        assertTrue(refusal.getString("remaining_step").contains("design pass"))
        assertFalse(app.settings.approversEnabled)
        assertFalse("nothing else in the refused request was applied", app.settings.capturesOwnerFiles)
        // Nothing woke up: no store, no prompt.
        kit.idle()
        assertFalse(app.authority.isOpen)
        assertTrue(kit.shown.isEmpty())
        // The app's own switch is closed the same way.
        assertEquals("platform_unsupported", runCatching { app.authority.setEnabled(true, "test") }.exceptionOrNull().let { (it as cc.bridgit.app.api.ApiError).code })
        assertFalse(app.settings.approversEnabled)
        // Reads say why.
        val l = list()
        assertFalse(l.getBoolean("enabled"))
        assertFalse(l.getBoolean("can_enable"))
        assertEquals("not_available_in_this_build", l.getString("enable_blocked_reason"))
        assertFalse(row().getBoolean("can_enable"))
        assertEquals("not_available_in_this_build", row().getString("enable_blocked_reason"))
        assertTrue("turning it off is still a write", row().getBoolean("writable"))
        // Off while it is off is still accepted.
        ok(ConfigurationEndpoints.SETTINGS, o("""{"changes":{"security.approvers_enabled":false}}"""))
    }

    @Test fun aPublishedBuildThatHasItOnTurnsItOffAsBefore() {
        // With an approver enrolled, off is still the owner's decision.
        enrolled()
        app.authority.releaseBuild = true
        assertTrue(list().getBoolean("enabled"))
        assertFalse(list().getBoolean("can_enable"))
        ok(ConfigurationEndpoints.SETTINGS, o("""{"changes":{"security.approvers_enabled":true}}""")) // already on: nothing to refuse
        refused("owner_authorization_required", ConfigurationEndpoints.SETTINGS, o("""{"changes":{"security.approvers_enabled":false}}"""))
        assertTrue(app.settings.approversEnabled)
        kit.pass()
        assertFalse(app.settings.approversEnabled)
        // Once off it stays off.
        refused("platform_unsupported", ConfigurationEndpoints.SETTINGS, o("""{"changes":{"security.approvers_enabled":true}}"""))
        assertFalse(app.settings.approversEnabled)
    }

    @Test fun aPublishedBuildWithNoApproverTurnsItOffAtOnce() {
        kit.enable()
        app.authority.releaseBuild = true
        ok(ConfigurationEndpoints.SETTINGS, o("""{"changes":{"security.approvers_enabled":false}}"""))
        kit.idle()
        assertFalse(app.settings.approversEnabled)
    }

    @Test fun theEndpointsAreRegisteredForHttpMcpAndTheGeneratedDocs() {
        for (spec in ConfigurationApprovers.ALL) {
            assertTrue(spec.path, spec in ConfigurationEndpoints.ALL)
            assertTrue(spec.path, spec in cc.bridgit.app.api.Endpoints.all)
            assertEquals("owner.configuration", spec.controlScope)
        }
        assertEquals(listOf("owner_configuration_approvers_list", "owner_configuration_approvers_enrol", "owner_configuration_approvers_recovery_get",
            "owner_configuration_approvers_recovery_start", "owner_configuration_approvers_recovery_cancel"), ConfigurationApprovers.ALL.map { McpTools.name(it) })
    }

    @Test fun bodiesAreValidatedBeforeAnythingHappens() {
        kit.enable()
        refused("invalid_parameter", ConfigurationApprovers.ENROL, JSONObject())
        refused("invalid_parameter", ConfigurationApprovers.ENROL, o("""{"self":false}"""))
        refused("invalid_parameter", ConfigurationApprovers.ENROL, o("""{"self":"true"}"""))
        refused("invalid_parameter", ConfigurationApprovers.ENROL, o("""{"self":true,"name":"x"}"""))
        refused("invalid_parameter", ConfigurationApprovers.RECOVERY_START, o("""{"hours":1}"""))
        kit.idle()
        assertEquals(ApproverKeys.State.ABSENT, kit.keys.state())
        assertTrue(kit.shown.isEmpty())
    }

    @Test fun aDeviceEnrolsItselfOnceTheOwnerPassesTheCheck() {
        kit.enable()
        val before = list()
        assertTrue(before.getBoolean("can_enrol"))
        assertTrue(before.isNull("blocked_reason"))
        assertEquals("absent", before.getString("key_state"))
        val started = ok(ConfigurationApprovers.ENROL, self)
        assertEquals("waiting_owner", started.getJSONObject("enrolment").getString("status"))
        assertEquals("owner_check", started.getJSONObject("remaining_human_step").getString("step"))
        assertEquals("waiting", started.getJSONObject("remaining_human_step").getString("status"))
        assertEquals(1, kit.shown.size)
        // Nothing is enrolled until the check: starting is not deciding.
        val waiting = list()
        assertEquals(0, waiting.getJSONArray("approvers").length())
        assertEquals("waiting_owner", waiting.getJSONObject("enrolment").getString("status"))
        assertEquals("owner_check", waiting.getJSONObject("remaining_human_step").getString("step"))
        // Asking again offers the same check and makes no second key.
        val challenge = kit.keys.challenge!!.copyOf()
        val again = ok(ConfigurationApprovers.ENROL, self)
        assertEquals(started.getJSONObject("enrolment").getString("id"), again.getJSONObject("enrolment").getString("id"))
        assertTrue(challenge.contentEquals(kit.keys.challenge))

        kit.pass()
        val done = list()
        val approver = done.getJSONArray("approvers").getJSONObject(0)
        assertEquals(kit.install.id(), approver.getString("id"))
        assertTrue(approver.getBoolean("self"))
        assertTrue("first set-up has no wait (D16c)", approver.getBoolean("can_approve"))
        assertEquals("done", done.getJSONObject("enrolment").getString("status"))
        assertTrue(done.isNull("remaining_human_step"))
        assertFalse(done.getBoolean("can_enrol"))
        assertEquals("approver_listed", done.getString("blocked_reason"))
        assertEquals("ready", done.getString("key_state"))
        // The store holds the transcript the two keys signed; the API never returns a key.
        val t = app.authority.store.transcripts().single()
        assertEquals("enrol", t.kind)
        assertEquals(32, t.hash.size)
        val approverKey = Base64.getEncoder().encodeToString(kit.keys.approverKeySpki())
        assertFalse(done.toString().contains(approverKey))
        assertFalse(started.toString().contains(approverKey))
        assertTrue(app.authority.store.enrolledBefore())
        // The alert was stored with the change and delivered here; the email and approver deliveries still wait.
        assertTrue(app.authority.store.undelivered(AuthorityOutbox.Way.LOCAL).isEmpty())
        assertEquals(listOf("approvers_on", "approver_enrolled"), app.authority.store.undelivered(AuthorityOutbox.Way.EMAIL).map { it.kind })
        refused("conflict", ConfigurationApprovers.ENROL, self)
    }

    @Test fun enrolmentIsRefusedWithoutAVerifiedEmailAScreenLockOrAKeyStore() {
        kit.enable()
        kit.emailVerified(false)
        assertEquals("owner_email_required", list().getString("blocked_reason"))
        refused("owner_email_required", ConfigurationApprovers.ENROL, self)
        // A pending address is not a verified one.
        app.settings.alertEmail = "owner@example.com"; app.settings.alertState = cc.bridgit.app.alerts.Alerts.STATE_PENDING
        refused("owner_email_required", ConfigurationApprovers.ENROL, self)
        kit.emailVerified()
        kit.capabilities = kit.capabilities.copy(secureLockScreen = false)
        assertEquals("no_screen_lock", list().getString("blocked_reason"))
        refused("no_screen_lock", ConfigurationApprovers.ENROL, self)
        kit.capabilities = kit.capabilities.copy(secureLockScreen = true, apiLevel = 29, perUseCredentialKeys = false, strongBiometric = false)
        assertEquals("platform_unsupported", list().getString("blocked_reason"))
        refused("platform_unsupported", ConfigurationApprovers.ENROL, self)
        kit.capabilities = kit.capabilities.copy(apiLevel = 34, perUseCredentialKeys = true)
        kit.install.registered = false
        refused("upstream_failed", ConfigurationApprovers.ENROL, self)
        assertEquals(ApproverKeys.State.ABSENT, kit.keys.state())
        assertTrue(kit.shown.isEmpty())
        assertEquals(0, list().getJSONArray("approvers").length())
    }

    @Test fun aClosedPromptOrAnotherKeysSignatureEnrolsNobody() {
        kit.enable()
        ok(ConfigurationApprovers.ENROL, self)
        kit.cancel()
        var l = list()
        assertEquals("cancelled", l.getJSONObject("enrolment").getString("status"))
        assertEquals(0, l.getJSONArray("approvers").length())
        assertEquals("the key that guarded nothing is dropped", ApproverKeys.State.ABSENT, kit.keys.state())
        assertTrue(l.getBoolean("can_enrol"))
        // The owner just closed this agent's prompt: it is not raised again at once. Reads go on working.
        val soon = call(ConfigurationApprovers.ENROL, self)
        assertEquals(429, soon.status)
        assertEquals("rate_limited", JSONObject(String(soon.bytes)).getString("error"))
        assertTrue(JSONObject(String(soon.bytes)).getLong("retry_after_s") in 1..Authority.DISMISS_QUIET_MS / 1000)
        assertEquals(1, kit.shown.size)
        assertTrue(list().getBoolean("can_enrol"))
        kit.time.run(Authority.DISMISS_QUIET_MS)
        // The check passed on some other key: the transcript names this device's, so nothing is stored.
        ok(ConfigurationApprovers.ENROL, self)
        val stranger = SoftwareApproverKeys().apply { create(ByteArray(32)) }
        kit.pass(sign = stranger)
        l = list()
        assertEquals("failed", l.getJSONObject("enrolment").getString("status"))
        assertEquals("bad_approver_signature", l.getJSONObject("enrolment").getString("detail"))
        assertEquals(0, l.getJSONArray("approvers").length())
        assertTrue(app.authority.store.transcripts().isEmpty())
        // A third try passes; a late outcome for the check that failed then changes nothing.
        val failed = kit.shown.last()
        ok(ConfigurationApprovers.ENROL, self)
        kit.pass()
        assertEquals(1, list().getJSONArray("approvers").length())
        kit.cancel(failed)
        assertEquals(1, list().getJSONArray("approvers").length())
        assertEquals(ApproverKeys.State.READY, kit.keys.state())
    }

    @Test fun theEmailMustStillBeVerifiedWhenTheOwnersCheckComesBack() {
        kit.enable()
        ok(ConfigurationApprovers.ENROL, self)
        // The address is replaced while the prompt waits: the new one is not verified yet.
        app.settings.alertEmail = "other@example.com"; app.settings.alertState = cc.bridgit.app.alerts.Alerts.STATE_PENDING
        kit.pass()
        val l = list()
        assertEquals(0, l.getJSONArray("approvers").length())
        assertEquals("failed", l.getJSONObject("enrolment").getString("status"))
        assertEquals("owner_email_required", l.getJSONObject("enrolment").getString("detail"))
        assertEquals(ApproverKeys.State.ABSENT, kit.keys.state())
        assertEquals(listOf("approvers_on"), app.authority.store.undelivered(AuthorityOutbox.Way.EMAIL).map { it.kind })
    }

    @Test fun twoStartsAtOnceMakeOneKeyAndOneEnrolment() {
        kit.enable()
        val starts = (1..4).map { java.util.concurrent.CompletableFuture.supplyAsync { app.authority.startSelfEnrol("agent $it").id } }
        val ids = starts.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }.toSet()
        assertEquals(1, ids.size)
        assertEquals(1, app.authority.store.proposals().size)
        kit.pass(ids.single())
        assertEquals(1, list().getJSONArray("approvers").length())
    }

    @Test fun startingARecoveryIsNotOfferedYet() {
        enrolled()
        val out = call(ConfigurationApprovers.RECOVERY_START)
        assertEquals(String(out.bytes), 501, out.status)
        val refusal = JSONObject(String(out.bytes))
        assertEquals("platform_unsupported", refusal.getString("error"))
        assertEquals(2, refusal.getJSONArray("remaining_steps").length())
        val r = ok(ConfigurationApprovers.RECOVERY)
        assertEquals("none", r.getString("state"))
        assertFalse(r.getBoolean("can_start"))
        assertEquals("not_available", r.getString("blocked_reason"))
        refused("conflict", ConfigurationApprovers.RECOVERY_CANCEL)
        assertEquals(1, list().getJSONArray("approvers").length())
        assertEquals(listOf("approvers_on", "approver_enrolled"), app.authority.store.undelivered(AuthorityOutbox.Way.EMAIL).map { it.kind })
    }

    @Test fun onceAnApproverIsEnrolledTurningTheSwitchOffIsTheOwnersDecisionOverHttpAndMcp() {
        enrolled()
        val off = o("""{"changes":{"security.approvers_enabled":false,"captures.owner_files":true}}""")
        app.settings.capturesOwnerFiles = false
        val http = call(ConfigurationEndpoints.SETTINGS, off)
        assertEquals(String(http.bytes), 403, http.status)
        val refusal = JSONObject(String(http.bytes))
        assertEquals("owner_authorization_required", refusal.getString("error"))
        assertEquals("owner_check", refusal.getJSONObject("remaining_human_step").getString("step"))
        val request = refusal.getString("owner_request_id")
        assertEquals(request, app.authority.store.openRequests().single().id)
        assertTrue(app.settings.approversEnabled)
        assertFalse("nothing else in the refused request was applied", app.settings.capturesOwnerFiles)
        assertEquals("setting", list().getJSONArray("requests").getJSONObject(0).getString("purpose"))
        // The same through the MCP configuration tool: the same refusal and the same waiting request.
        val args = o("""{"operation":"POST /v1/owner/configuration/settings","body":{"changes":{"security.approvers_enabled":false}}}""")
        try {
            runBlocking { ConfigurationTools.call(app, api, caller.id, caller.keyHash, "configuration.set", args, UUID.randomUUID().toString()) }
            org.junit.Assert.fail("The MCP tool turned approver devices off without the owner")
        } catch (e: cc.bridgit.app.api.ApiError) {
            assertEquals("owner_authorization_required", e.code)
            assertTrue(e.message, e.message!!.contains(request)) // the tool carries the message, which names the waiting request
        }
        assertTrue(app.settings.approversEnabled)
        assertEquals(1, app.authority.store.openRequests().size)
        // The store itself refuses the direct way.
        assertTrue(runCatching { app.authority.setEnabled(false, "someone") }.isFailure)
        assertTrue(app.settings.approversEnabled)
        // A signature by another key changes nothing.
        kit.pass("check_$request", sign = SoftwareApproverKeys().apply { create(ByteArray(32)) })
        assertTrue(app.settings.approversEnabled)
        // The owner passes the check on the approver: now it is off. The list and the key are kept.
        val again = JSONObject(String(call(ConfigurationEndpoints.SETTINGS, o("""{"changes":{"security.approvers_enabled":false}}"""), controller("Other").also { kit.idle() }).bytes)).getString("owner_request_id")
        kit.pass("check_$again")
        assertFalse(app.settings.approversEnabled)
        assertFalse(list().getBoolean("enabled"))
        assertEquals(1, app.authority.store.approvers().size)
        assertEquals(ApproverKeys.State.READY, kit.keys.state())
        assertEquals("master_switch", app.authority.store.generation.log().last().second)
        // On again needs no check, and the approver is still there.
        ok(ConfigurationEndpoints.SETTINGS, o("""{"changes":{"security.approvers_enabled":true}}"""))
        kit.idle()
        assertEquals(1, list().getJSONArray("approvers").length())
    }

    @Test fun anAgentCannotKeepRaisingTheOwnersPrompt() {
        enrolled() // the caller's first prompt
        val off = o("""{"changes":{"security.approvers_enabled":false}}""")
        assertEquals(403, call(ConfigurationEndpoints.SETTINGS, off).status)
        assertEquals(2, kit.shown.size)
        kit.cancel()
        // Closed by the owner: quiet for a while, whatever is asked. Reads and other writes are unaffected.
        assertEquals(429, call(ConfigurationEndpoints.SETTINGS, off).status)
        assertEquals(2, kit.shown.size)
        assertTrue(list().getBoolean("enabled"))
        ok(ConfigurationEndpoints.SETTINGS, o("""{"changes":{"captures.owner_files":true}}"""))
        // Another agent's ask is its own count.
        val other = controller("Other")
        assertEquals(403, call(ConfigurationEndpoints.SETTINGS, off, other).status)
        assertEquals(3, kit.shown.size)
        kit.time.run(Authority.DISMISS_QUIET_MS)
        assertEquals(403, call(ConfigurationEndpoints.SETTINGS, off).status)
        assertEquals(4, kit.shown.size)
        // Three prompts in ten minutes is the most one agent raises, closed or not.
        val limited = call(ConfigurationEndpoints.SETTINGS, off)
        assertEquals(String(limited.bytes), 429, limited.status)
        assertEquals(4, kit.shown.size)
        kit.time.run(Authority.PROMPT_WINDOW_MS)
        assertEquals(403, call(ConfigurationEndpoints.SETTINGS, off).status)
        assertTrue(app.settings.approversEnabled)
    }

    @Test fun recoveryStartsReadsAndIsWithdrawnByItsStarter() {
        app.authority.recoveryStartBuilt = true
        kit.enable()
        refused("no_approver", ConfigurationApprovers.RECOVERY_START)
        ok(ConfigurationApprovers.ENROL, self); kit.pass()
        kit.emailVerified(false)
        assertEquals("owner_email_required", ok(ConfigurationApprovers.RECOVERY).getString("blocked_reason"))
        refused("owner_email_required", ConfigurationApprovers.RECOVERY_START)
        kit.emailVerified()
        assertTrue(ok(ConfigurationApprovers.RECOVERY).getBoolean("can_start"))
        val started = ok(ConfigurationApprovers.RECOVERY_START)
        assertEquals("pending", started.getString("state"))
        assertEquals(caller.id, started.getString("started_by"))
        assertEquals(AuthorityLimits.RECOVERY_WAIT_MS, started.getLong("time_left_ms"))
        assertEquals(kit.time.wall + AuthorityLimits.RECOVERY_WAIT_MS, started.getLong("clear_at"))
        // Starting again is the same record: the clock does not restart.
        kit.time.run(HOUR)
        val again = ok(ConfigurationApprovers.RECOVERY_START)
        assertEquals(started.getLong("clear_at"), again.getLong("clear_at"))
        assertEquals(AuthorityLimits.RECOVERY_WAIT_MS - HOUR, ok(ConfigurationApprovers.RECOVERY).getLong("time_left_ms"))
        // Moving the wall clock forward shortens nothing: running time is still owed.
        kit.time.wall += 100 * HOUR
        assertEquals(AuthorityLimits.RECOVERY_WAIT_MS - HOUR, ok(ConfigurationApprovers.RECOVERY).getLong("time_left_ms"))
        assertEquals("pending", list().getJSONObject("recovery").getString("state"))
        val withdrawn = ok(ConfigurationApprovers.RECOVERY_CANCEL)
        assertEquals("cancelled", withdrawn.getString("state"))
        assertEquals(caller.id, withdrawn.getString("ended_by"))
        assertEquals(1, list().getJSONArray("approvers").length())
        refused("conflict", ConfigurationApprovers.RECOVERY_CANCEL)
        kit.idle()
        assertEquals(listOf("approvers_on", "approver_enrolled", "recovery_started", "recovery_cancelled"), app.authority.store.undelivered(AuthorityOutbox.Way.EMAIL).map { it.kind })
    }

    @Test fun anotherAgentsCancelNeedsTheOwnersCheckOnAListedApprover() {
        app.authority.recoveryStartBuilt = true
        enrolled()
        val other = controller("Other")
        ok(ConfigurationApprovers.RECOVERY_START, by = other)
        val asked = ok(ConfigurationApprovers.RECOVERY_CANCEL)
        assertEquals("the recovery still runs until the owner's check", "pending", asked.getString("state"))
        assertEquals("owner_check", asked.getJSONObject("remaining_human_step").getString("step"))
        val request = list().getJSONArray("requests").getJSONObject(0)
        assertEquals("recovery_cancel", request.getString("purpose"))
        assertEquals("waiting_owner", request.getString("check"))
        kit.pass()
        val r = ok(ConfigurationApprovers.RECOVERY)
        assertEquals("cancelled", r.getString("state"))
        assertEquals(kit.install.id(), r.getString("ended_by"))
        assertEquals(0, list().getJSONArray("requests").length())
    }

    @Test fun afterTheWaitTheListIsClearedACancelIsTooLateAndANewApproverWaits() {
        app.authority.recoveryStartBuilt = true
        enrolled()
        ok(ConfigurationApprovers.RECOVERY_START)
        kit.time.run(AuthorityLimits.RECOVERY_WAIT_MS - 1000)
        app.authority.checkpoint()
        assertEquals(1, list().getJSONArray("approvers").length())
        kit.time.run(2000)
        app.authority.checkpoint() // the service's timer
        val l = list()
        assertEquals(0, l.getJSONArray("approvers").length())
        assertEquals("cleared", l.getJSONObject("recovery").getString("state"))
        refused("recovery_complete", ConfigurationApprovers.RECOVERY_CANCEL)
        kit.idle()
        assertTrue(app.authority.store.undelivered(AuthorityOutbox.Way.EMAIL).map { it.kind }.containsAll(listOf("recovery_cleared", "recovery_cancel_late")))
        // The first-approver path is open again, and this time the approver waits 24 hours (D16c).
        assertTrue(l.getBoolean("can_enrol"))
        ok(ConfigurationApprovers.ENROL, self); kit.pass()
        val approver = list().getJSONArray("approvers").getJSONObject(0)
        assertFalse(approver.getBoolean("can_approve"))
        assertEquals(kit.time.wall + AuthorityLimits.APPROVER_WAIT_MS, approver.getLong("wait_ends_at"))
        assertEquals("none", list().getJSONObject("recovery").getString("state"))
        assertNotEquals(0, app.authority.store.generation())
    }

    @Test fun turningTheSwitchOffKeepsTheListAndCancelsWhatWaited() {
        kit.enable()
        ok(ConfigurationApprovers.ENROL, self)
        kit.enable(false)
        assertEquals("cancelled", app.authority.store.proposals().single().state)
        assertEquals(ApproverKeys.State.ABSENT, kit.keys.state())
        refused("capability_disabled", ConfigurationApprovers.ENROL, self)
        assertFalse(list().getBoolean("enabled"))
        // A late pass of the old check does nothing.
        assertEquals(null, app.authority.check(kit.shown.last()))
        kit.enable()
        assertEquals(0, list().getJSONArray("approvers").length())
        assertTrue(list().getBoolean("can_enrol"))
    }
}
