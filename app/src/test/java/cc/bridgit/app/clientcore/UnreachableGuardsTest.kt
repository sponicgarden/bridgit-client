package cc.bridgit.app.clientcore

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.ownerapi.ConfigurationAccess
import cc.bridgit.app.ownerapi.TestAndroidKeystore
import cc.bridgit.app.platform.Capabilities
import cc.bridgit.app.relay.RelayDispatcher
import cc.bridgit.app.relay.RelayStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode

/**
 * Guards no door request can reach (contracts/client-core/doors/authorization.json, refusals marked `untestable`).
 * Each is kept as defence in depth: an earlier step refuses the same request today, but the guard still holds if
 * that step moves or a new caller skips it. No door fixture can reach them, so each is proved here by calling the
 * guard directly with the state it refuses. The contract names each test in its refusal's `untestable.proof`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UnreachableGuardsTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private lateinit var caller: Agent

    @Before fun setup() {
        TestAndroidKeystore.install()
        app.settings.listenerEnabled = true
        app.settings.emailVerifiedAt = System.currentTimeMillis()
        caller = app.agents.add("Guard control", Connection.DIRECT).first
        caller = app.agents.update(caller.id) { it.copy(caps = setOf("owner_features")) }!!
        Capabilities.setEnabled(app, "owner.configuration", true)
        app.platform.grant(caller.id, "owner.configuration", Long.MAX_VALUE)
    }

    @After fun cleanup() { TestAndroidKeystore.remove() }

    private fun refused(http: Int, code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: ApiError) {
            assertEquals(e.message, code, e.code); assertEquals(e.message, http, e.http)
        }
    }

    /** A0327: ConfigurationAccess re-reads the agent and refuses a removed, re-keyed or paused one with 401. */
    @Test fun controlScopeRefusesAStaleAgent() {
        ConfigurationAccess(app, caller).require()
        val snapshot = caller
        app.agents.rotate(caller.id)
        refused(401, "unauthorized") { ConfigurationAccess(app, snapshot).require() }
        val current = app.agents.get(caller.id)!!
        app.agents.update(current.id) { it.copy(paused = true) }
        refused(401, "unauthorized") { ConfigurationAccess(app, current).require() }
        app.agents.remove(current.id)
        refused(401, "unauthorized") { ConfigurationAccess(app, current).require() }
    }

    /** A0337: an agent holding a Portal pairing never gets configuration authority, even if it is a direct agent. */
    @Test fun controlScopeRefusesAPortalPairedAgent() {
        RelayStore(app).addAgent(caller.id, "connector", null, setOf("GET /v1/portal/home"), emptySet())
        refused(403, "forbidden") { ConfigurationAccess(app, app.agents.get(caller.id)!!).require() }
    }

    /**
     * A0328: with owner.configuration turned off, dispatch's path answers the blocker (403 feature_disabled, also
     * proved by the http_v1 and mcp fixtures); the listener-pause re-check after a settings write answers 403
     * capability_disabled.
     */
    @Test fun controlScopeListenerPauseRecheckAnswersCapabilityDisabled() {
        Capabilities.setEnabled(app, "owner.configuration", false)
        refused(403, "feature_disabled") { ConfigurationAccess(app, caller).require() }
        app.settings.listenerEnabled = false
        refused(403, "capability_disabled") { ConfigurationAccess(app, caller).require(allowListenerPaused = true) }
        Capabilities.setEnabled(app, "owner.configuration", true)
        ConfigurationAccess(app, caller).require(allowListenerPaused = true)
    }

    /** A0329: AgentPlatform.authorize refuses a scope Capabilities.all doesn't list with 422 unknown_scope. */
    @Test fun authorizeRefusesAnUnknownScope() {
        refused(422, "unknown_scope") { app.platform.authorize(caller.id, "no.such.scope") }
    }

    /** A0330: the relay door refuses a relay agent whose grant set is the legacy null (everything). */
    @Test fun relayRefusesAnAgentWithoutExplicitGrants() {
        // RelayStore.addAgent refuses null caps, so the config is made with an explicit set that is then dropped.
        val relay = app.agents.update(app.agents.add("Guard relay", Connection.RELAY).first.id) { it.copy(caps = setOf("status")) }!!
        val store = RelayStore(app).also { it.enabled = true }
        store.addAgent(relay.id, "connector", null, setOf("GET /v1/status"), emptySet())
        val config = store.agents().single { it.getString("id") == relay.id }
        val nullCaps = app.agents.update(relay.id) { it.copy(caps = null) }!!
        assertEquals(null, nullCaps.caps)
        val svc = Robolectric.buildService(BridgeService::class.java).create().get()
        val api = ApiServer(svc, emptyList(), 0).also { it.prepareRelay() }
        try {
            val request = JSONObject().put("method", "GET").put("path", "/v1/status").put("headers", JSONObject()).put("query", JSONObject()).put("body", JSONObject())
            refused(403, "explicit_grants_required") {
                runBlocking { RelayDispatcher(app, api).dispatch(config, request, System.currentTimeMillis() + 60_000, System.currentTimeMillis(), "guard-job") }
            }
        } finally { api.stop(); svc.onDestroy() }
    }
}
