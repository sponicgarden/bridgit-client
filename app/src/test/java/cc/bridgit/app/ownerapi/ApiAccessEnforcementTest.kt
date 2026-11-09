package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.platform.Capabilities
import cc.bridgit.app.relay.RelayDispatcher
import cc.bridgit.app.relay.RelayStore
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

/**
 * A0119/A0120 proving case: API access is readable and writable through the API by a properly granted caller
 * (an existing Super agent), the change is logged as that agent, and an agent without API access is refused on
 * every surface (direct and MCP dispatch, relay v1 and v2, control grants) while it stays paired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiAccessEnforcementTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private lateinit var caller: Agent
    private val access get() = SuperAgentAccess(app, caller)

    @Before fun setup() {
        app.settings.listenerEnabled = true
        app.settings.setOn("settings_api", true)
        app.settings.emailVerifiedAt = System.currentTimeMillis()
        caller = app.agents.add("Super caller", Connection.DIRECT).first
        caller = app.agents.update(caller.id) { it.copy(superPowers = true, caps = setOf("settings", "owner_features", "camera")) }!!
        Capabilities.setEnabled(app, "owner.configuration", true)
    }

    private fun body(agent: Agent) = JSONObject().put("expected_generation", cc.bridgit.app.platform.PlatformStore.digest(
        JSONObject().put("id", agent.id).put("credential", agent.keyHash).put("salt", agent.keySalt).put("created", agent.keyCreatedAt)))

    private fun viewOf(id: String): JSONObject {
        val agents = access.get().getJSONArray("agents")
        return (0 until agents.length()).map(agents::getJSONObject).single { it.getString("id") == id }
    }

    @Test fun superAgentReadsAndWritesApiAccessAndTheChangeIsLoggedAsThatAgent() {
        val target = app.agents.add("Target", Connection.DIRECT).first
        assertTrue(viewOf(target.id).getBoolean("api_access"))
        val out = access.agent(target.id, body(target).put("api_access", false))
        assertFalse(out.getBoolean("api_access"))
        assertFalse(viewOf(target.id).getBoolean("api_access"))
        assertNotNull("still paired", app.agents.get(target.id))
        // Retrying the same change is harmless.
        access.agent(target.id, body(target).put("api_access", false))
        assertFalse(app.agents.get(target.id)!!.apiAccess)

        val deadline = System.currentTimeMillis() + 5_000
        var logged = emptyList<cc.bridgit.app.log.LogEntry>()
        while (logged.isEmpty() && System.currentTimeMillis() < deadline) {
            logged = app.log.query(LogQuery(channels = listOf("audit"), agentIds = listOf(caller.id), kinds = listOf("configuration")))
                .filter { it.data?.optString("target_agent_id") == target.id }
            if (logged.isEmpty()) Thread.sleep(50)
        }
        assertTrue("audit entry attributed to the calling agent", logged.isNotEmpty())
        assertEquals("Super caller", logged.first().agent)
        assertEquals(false, logged.first().data!!.getBoolean("api_access"))

        access.agent(target.id, body(target).put("api_access", true))
        assertTrue(app.agents.get(target.id)!!.apiAccess)
    }

    @Test fun invalidValuesChangeNothing() {
        val target = app.agents.add("Target", Connection.DIRECT).first
        try { access.agent(target.id, body(target).put("api_access", "no")); fail("must reject") } catch (e: ApiError) { assertEquals("invalid_parameter", e.code) }
        assertTrue(app.agents.get(target.id)!!.apiAccess)
    }

    @Test fun agentsScreenFieldsAreReadableAndRenameAndNotesFollowTheScreensRules() {
        val target = app.agents.add("Target", Connection.DIRECT).first
        val view = viewOf(target.id)
        for (k in listOf("connection", "created_at", "last_used_at", "request_count", "model", "harness", "device_lock", "notes"))
            assertTrue(k, view.has(k))
        assertFalse("no key material", view.toString().contains(target.keyHash) || view.toString().contains(target.keyPrefix))
        access.agent(target.id, body(target).put("name", "Renamed agent").put("notes", "runs nightly"))
        assertEquals("Renamed agent", app.agents.get(target.id)!!.name)
        assertEquals("runs nightly", viewOf(target.id).getString("notes"))
        for (bad in listOf(JSONObject().put("name", "Super caller"), JSONObject().put("name", "bad/name"), JSONObject().put("name", ""),
            JSONObject().put("notes", "x".repeat(201)), JSONObject().put("notes", 5))) {
            val b = body(target); bad.keys().forEach { b.put(it, bad.get(it)) }
            try { access.agent(target.id, b); fail("must reject $bad") } catch (e: ApiError) { assertEquals("invalid_parameter", e.code) }
        }
        assertEquals("Renamed agent", app.agents.get(target.id)!!.name)
        access.agent(target.id, body(target).put("notes", ""))
        assertEquals("", app.agents.get(target.id)!!.notes)
    }

    @Test fun anAgentWithoutApiAccessCannotChangeAccessEvenWithSuperPowers() {
        app.agents.update(caller.id) { it.copy(apiAccess = false) }
        val target = app.agents.add("Target", Connection.DIRECT).first
        try { access.agent(target.id, body(target).put("paused", true)); fail("must refuse") } catch (e: ApiError) { assertEquals("unauthorized", e.code) }
        assertFalse(app.agents.get(target.id)!!.paused)
    }

    @Test fun dispatchRefusesEveryCallIncludingStatusAndRestoresWhenTurnedBackOn() = runBlocking {
        val svc = Robolectric.buildService(BridgeService::class.java).create().get()
        val api = ApiServer(svc, emptyList(), 0).also { it.prepareRelay() }
        val target = app.agents.add("Target", Connection.DIRECT).first
        assertEquals(200, api.dispatch(Endpoints.HEALTH, target, "100.64.0.2", emptyMap(), emptyMap(), JSONObject(), null).status)
        assertEquals(200, api.dispatch(Endpoints.REGISTRY, target, "100.64.0.2", emptyMap(), emptyMap(), JSONObject(), null).status)
        app.agents.update(target.id) { it.copy(apiAccess = false) }
        for (spec in listOf(Endpoints.HEALTH, Endpoints.REGISTRY, Endpoints.FEEDBACK_LIST)) {
            val out = api.dispatch(spec, target, "100.64.0.2", emptyMap(), emptyMap(), JSONObject(), null)
            assertEquals(spec.path, 403, out.status)
            assertEquals("api_access_off", out.error!!.extra["reason"])
        }
        assertFalse(app.platform.hasGrant(target.id, "diagnostics"))
        app.agents.update(target.id) { it.copy(apiAccess = true) }
        val back = api.dispatch(Endpoints.REGISTRY, target, "100.64.0.2", emptyMap(), emptyMap(), JSONObject(), null)
        assertEquals(200, back.status)
        val json = JSONObject(String(back.bytes))
        assertTrue(json.getJSONObject("permission").getBoolean("you"))
        assertTrue(json.getInt("count") > 40)
        api.stop(); svc.onDestroy()
    }

    @Test fun relayV1AndV2AreRefusedWithoutApiAccess() = runBlocking {
        val store = RelayStore(app); store.enabled = true
        val (a, _) = app.agents.add("RelayTarget", Connection.RELAY)
        app.agents.update(a.id) { it.copy(caps = setOf("status")) }
        store.addAgent(a.id, "connector", null, setOf("GET /v1/health"), setOf("phone.status"))
        val c = store.agents().single { it.getString("id") == a.id }
        val svc = Robolectric.buildService(BridgeService::class.java).create().get()
        val api = ApiServer(svc, emptyList(), 0).also { it.prepareRelay() }
        val d = RelayDispatcher(app, api); val now = System.currentTimeMillis()
        assertEquals(200, d.dispatch(c, JSONObject().put("method", "GET").put("path", "/v1/health"), now + 10_000, now, "job").getInt("status"))
        app.agents.update(a.id) { it.copy(apiAccess = false) }
        for (req in listOf(JSONObject().put("method", "GET").put("path", "/v1/health"), JSONObject().put("method", "GET").put("path", "/v2/tools"),
            JSONObject().put("method", "POST").put("path", "/relay/mcp").put("body", JSONObject().put("method", "tools/list")))) {
            try { d.dispatch(c, req, now + 10_000, now, "job"); fail("${req.getString("path")} must be refused") }
            catch (e: ApiError) { assertEquals("api_access_off", e.extra["reason"]) }
        }
        api.stop(); svc.onDestroy()
    }
}
