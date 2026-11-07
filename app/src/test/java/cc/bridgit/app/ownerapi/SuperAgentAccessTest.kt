package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.granted
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SuperAgentAccessTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private lateinit var caller: Agent
    private val access get() = SuperAgentAccess(app, caller)
    @Before fun setup() {
        app.settings.listenerEnabled = true
        app.settings.setOn("settings_api", true)
        app.settings.emailVerifiedAt = System.currentTimeMillis()
        caller = app.agents.add("Existing Super agent", Connection.DIRECT).first
        caller = app.agents.update(caller.id) { it.copy(superPowers = true, caps = setOf("settings", "owner_features", "camera")) }!!
        Capabilities.setEnabled(app, "owner.configuration", true)
    }
    private fun body(agent: Agent) = JSONObject().put("expected_generation", cc.bridgit.app.platform.PlatformStore.digest(
        JSONObject().put("id", agent.id).put("credential", agent.keyHash).put("salt", agent.keySalt).put("created", agent.keyCreatedAt)))
    private fun rejected(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: ApiError) { assertEquals(e.message, code, e.code) }
    }
    @Test fun existingSuperAuthorityCanBootstrapConfigurationWithoutAnotherOwnerTap() {
        assertFalse(app.platform.hasGrant(caller.id, "owner.configuration"))
        val state = access.get()
        assertFalse(state.getBoolean("approval_required"))
        val out = access.agent(caller.id, body(caller).put("control_scopes", JSONArray(listOf("owner.configuration"))))
        assertFalse(out.getBoolean("approval_required"))
        assertEquals(caller.id, ConfigurationAccess(app, caller).require().id)
        assertFalse(state.toString().contains(caller.keyHash))
        assertFalse(out.toString().contains(caller.keyHash))
    }
    @Test fun configurationGrantWithoutSuperPowersCannotPromoteItself() {
        app.agents.update(caller.id) { it.copy(superPowers = false) }
        app.platform.grant(caller.id, "owner.configuration", Long.MAX_VALUE)
        rejected("forbidden") { access.agent(caller.id, body(caller).put("super_powers", true)) }
        assertFalse(app.agents.get(caller.id)!!.superPowers)
    }
    @Test fun pairedControlAgentGrantsCanBeReplacedRevokedAndCleared() {
        val target = app.agents.add("Delegated agent", Connection.DIRECT).first
        access.agent(target.id, body(target).put("capabilities", JSONArray(listOf("settings", "owner_features")))
            .put("control_scopes", JSONArray(listOf("owner.configuration"))).put("super_powers", true))
        assertTrue(app.platform.hasGrant(target.id, "owner.configuration"))
        assertTrue(app.agents.get(target.id)!!.superPowers)
        access.agent(target.id, body(target).put("control_scopes", JSONArray()).put("super_powers", false).put("paused", true))
        assertFalse(app.platform.hasGrant(target.id, "owner.configuration"))
        assertFalse(app.agents.get(target.id)!!.superPowers)
        assertTrue(app.agents.get(target.id)!!.paused)
    }
    @Test fun nativeMaximumGrantDoesNotGrantAndroidAccessAndNarrowingExitsMaximum() {
        val androidBefore = app.granted(android.Manifest.permission.CAMERA)
        val target = app.agents.add("Maximum delegate", Connection.DIRECT).first
        access.agent(target.id, body(target).put("maximum_control", true))
        assertTrue(app.agents.get(target.id)!!.maximumControl)
        assertFalse(app.agents.get(target.id)!!.superPowers)
        access.agent(target.id, body(target).put("capabilities", JSONArray(listOf("settings"))))
        assertFalse(app.agents.get(target.id)!!.maximumControl)
        assertEquals(androidBefore, app.granted(android.Manifest.permission.CAMERA))
    }
    @Test fun appletPrincipalsAndDevicePeersCannotReceiveOrExerciseSuperDelegation() {
        val applet = app.agents.add("Applet", Connection.APPLET).first
        rejected("not_found") { access.agent(applet.id, body(applet).put("super_powers", true)) }
        app.agents.update(caller.id) { it.copy(connection = Connection.APPLET) }
        rejected("forbidden") { access.get() }
    }
    @Test fun pauseRevocationKeyRotationAndStaleTargetGenerationsStopWrites() {
        val target = app.agents.add("Target", Connection.DIRECT).first
        rejected("conflict") { access.agent(target.id, body(target).put("expected_generation", "stale").put("paused", true)) }
        assertFalse(app.agents.get(target.id)!!.paused)
        app.agents.update(caller.id) { it.copy(paused = true) }
        rejected("unauthorized") { access.get() }
        app.agents.update(caller.id) { it.copy(paused = false, superPowers = false) }
        rejected("forbidden") { access.get() }
        app.agents.update(caller.id) { it.copy(superPowers = true, keyHash = "rotated") }
        rejected("unauthorized") { access.get() }
    }
    @Test fun invalidFeatureOrRoleValuesAreRejectedBeforeAnyMutation() {
        val before = Capabilities.enabled(app, "owner.configuration")
        rejected("invalid_parameter") { access.features(JSONObject().put("changes", JSONObject().put("owner.configuration", !before).put("unknown", true))) }
        assertEquals(before, Capabilities.enabled(app, "owner.configuration"))
        rejected("invalid_parameter") { access.agent(caller.id, body(caller).put("super_powers", "yes")) }
        access.features(JSONObject().put("changes", JSONObject().put("owner.configuration", false)))
        assertFalse(Capabilities.enabled(app, "owner.configuration"))
        access.features(JSONObject().put("changes", JSONObject().put("owner.configuration", true)))
        assertTrue(Capabilities.enabled(app, "owner.configuration"))
    }
    @Test fun controlMcpAndRelayProjectOnlyBootstrapUntilConfigurationIsGranted() = runBlocking {
        val original = JSONObject().put("id", caller.id).put("generation", caller.keyHash).put("routes", JSONArray(listOf("GET /v1/status"))).put("tools", JSONArray(listOf("phone.status")))
        val projected = ConfigurationDelegation.effective(app, original)
        assertTrue(cc.bridgit.app.relay.RelayDispatcher.strings(projected, "routes").contains("GET /v1/owner/configuration/access"))
        assertFalse(cc.bridgit.app.relay.RelayDispatcher.strings(projected, "routes").contains("GET /v1/owner/configuration/keys"))
        val server = ApiServer(Robolectric.buildService(BridgeService::class.java).create().get(), emptyList(), 0).also { it.prepareRelay() }
        val args = JSONObject().put("operation", "POST /v1/owner/configuration/access/agents/{agent}")
            .put("parameters", JSONObject().put("agent", caller.id)).put("body", body(caller).put("control_scopes", JSONArray(listOf("owner.configuration"))))
        val response = ConfigurationTools.call(app, server, caller.id, caller.keyHash, "configuration.set", args, UUID.randomUUID().toString())
        assertFalse(response.getBoolean("approval_required"))
        assertTrue(cc.bridgit.app.relay.RelayDispatcher.strings(ConfigurationDelegation.effective(app, original), "routes").contains("GET /v1/owner/configuration/keys"))
    }
    @Test fun storedSuperRelayAgentCanBeEnumeratedWithoutReenteringControlDiscovery() {
        val original = JSONObject().put("id", caller.id).put("generation", caller.keyHash)
            .put("routes", JSONArray(listOf("GET /v1/status"))).put("tools", JSONArray(listOf("phone.status")))
        app.getSharedPreferences("relay_v1", android.content.Context.MODE_PRIVATE).edit()
            .putString("agents", JSONArray().put(original).toString()).commit()
        val projected = cc.bridgit.app.relay.RelayStore(app).agents().single { it.getString("id") == caller.id }
        assertTrue(cc.bridgit.app.relay.RelayDispatcher.strings(projected, "routes").contains("GET /v1/owner/configuration/access"))
        assertTrue(cc.bridgit.app.ui.home.HomeStates.controlAgents(app).any { it.id == caller.id })
        val portal = JSONObject(original.toString()).put("routes", JSONArray(cc.bridgit.app.portal.PortalData.ROUTES.toList()))
            .put("tools", JSONArray())
        assertEquals(portal.toString(), ConfigurationDelegation.effective(app, portal).toString())
    }
    @Test fun concurrentRotationCannotReceiveChangesIntendedForOldCredential() {
        val target = app.agents.add("Rotating target", Connection.DIRECT).first
        val request = body(target).put("maximum_control", true)
        access.get() // Initialize native stores before exercising the authority lock.
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val started = java.util.concurrent.CountDownLatch(1)
        val thread = Thread {
            started.countDown()
            try { access.agent(target.id, request) } catch (e: Throwable) { failure.set(e) }
        }
        synchronized(app.agents) {
            thread.start(); assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(Thread.State.BLOCKED, thread.state)
            app.agents.rotate(target.id)
        }
        thread.join(3000)
        assertFalse(thread.isAlive)
        assertEquals("conflict", (failure.get() as ApiError).code)
        assertFalse(app.agents.get(target.id)!!.superPowers)
        assertFalse(app.agents.get(target.id)!!.maximumControl)
        assertEquals(0, app.platform.store.grants(target.id).length())
    }

    @Test fun concurrentGrantCannotHideARevocationFromNativeInvalidation() {
        val target = app.agents.add("Changing target", Connection.DIRECT).first
        val request = body(target).put("super_powers", false)
        access.get()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val thread = Thread { try { access.agent(target.id, request) } catch (e: Throwable) { failure.set(e) } }
        var operation = ""
        synchronized(app.agents) {
            thread.start()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(Thread.State.BLOCKED, thread.state)
            app.agents.update(target.id) { it.copy(superPowers = true) }
            operation = app.platform.store.save(target.id, "operation", JSONObject().put("status", "awaiting_owner")).getString("id")
        }
        thread.join(3000)
        assertFalse(thread.isAlive)
        assertNull(failure.get())
        assertFalse(app.agents.get(target.id)!!.superPowers)
        assertEquals("cancelled", app.platform.store.find(operation, kind = "operation")!!.getString("status"))
    }

    @Test fun pausingPreservesInspectableConfiguredGrantsAndResumeRestoresThem() {
        val target = app.agents.add("Paused delegate", Connection.DIRECT).first
        access.agent(target.id, body(target).put("control_scopes", JSONArray(listOf("owner.configuration"))))
        val paused = access.agent(target.id, body(target).put("paused", true))
        assertEquals("owner.configuration", paused.getJSONArray("control_scopes").getString(0))
        assertEquals(0, paused.getJSONArray("effective_control_scopes").length())
        val resumed = access.agent(target.id, body(target).put("paused", false))
        assertEquals("owner.configuration", resumed.getJSONArray("effective_control_scopes").getString(0))
    }

    @Test fun existingSuperAgentIsGuidedToBootstrapInsteadOfAnotherPhoneApproval() {
        try { app.platform.authorize(caller.id, "owner.configuration"); fail("Missing scope must remain explicit") }
        catch (e: ApiError) {
            assertEquals("agent_not_allowed", e.code)
            assertEquals(false, e.extra["user_action_required"])
            assertEquals("/v1/owner/configuration/access", e.extra["configuration_access_url"])
        }
        assertFalse(app.platform.hasGrant(caller.id, "owner.configuration"))
        access.agent(caller.id, body(caller).put("control_scopes", JSONArray(listOf("owner.configuration"))))
        access.features(JSONObject().put("changes", JSONObject().put("owner.configuration", false)))
        try { app.platform.authorize(caller.id, "owner.configuration"); fail("Feature remains explicit") }
        catch (e: ApiError) { assertEquals(false, e.extra["user_action_required"]); assertEquals("feature_disabled", e.code) }
    }

}
