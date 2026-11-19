package cc.bridgit.app.relay

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.satellite.SatellitePolicy
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The target of a call forwarded over the relay (A0335) logs the far agent, its model and the device it came through (A0304). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelayLinkForwardTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp

    private fun entries() = app.log.also { it.flush() }.query(LogQuery(channels = listOf("audit"))).filter { it.endpoint == "/v1/health" }

    @Test fun linkJobHeadersNameTheFarAgentOnlyForLinkAgents() = runBlocking {
        app.settings.listenerEnabled = true
        val store = RelayStore(app); store.enabled = true
        val (linkId, _) = app.hubLinks.claimRelayLink("Pixel", setOf("status"), setOf("GET /v1/health"), "a".repeat(64))
        val svc = Robolectric.buildService(BridgeService::class.java).create().get()
        val api = ApiServer(svc, emptyList(), 0); api.prepareRelay()
        val d = RelayDispatcher(app, api); val now = System.currentTimeMillis()
        val headers = JSONObject().put(SatellitePolicy.ON_BEHALF_HEADER, "agt_muse01,Muse")
            .put(SatellitePolicy.ON_BEHALF_RUNTIME_HEADER, SatellitePolicy.onBehalfRuntime("Meta Muse", "Muse Wonder"))
            .put(SatellitePolicy.TARGET_HEADER, "elsewhere")
        val c = store.agents().single { it.getString("id") == linkId }
        val reply = d.dispatch(c, JSONObject().put("method", "GET").put("path", "/v1/health").put("headers", headers), now + 10000, now, "job")
        assertEquals(200, reply.getInt("status"))
        val e = entries().maxBy { it.id!! }
        assertTrue(e.agent, e.agent!!.startsWith("Muse via "))
        assertTrue(e.message, e.message!!.contains("Muse (Meta Muse, Muse Wonder) via "))
        val who = e.data!!.getJSONObject("on_behalf_of")
        assertEquals("agt_muse01", who.getString("id")); assertEquals("Meta Muse", who.getString("model")); assertEquals("Muse Wonder", who.getString("harness"))

        // Without the headers the call still runs, naming only the link.
        d.dispatch(c, JSONObject().put("method", "GET").put("path", "/v1/health"), now + 10000, now, "job")
        assertFalse(entries().maxBy { it.id!! }.data!!.has("on_behalf_of"))

        // An ordinary relay agent's job can't claim to act for someone else; a link agent's keeps only the allowlist.
        val other = app.agents.add("Plain", cc.bridgit.app.core.Connection.RELAY).first
        val job = JSONObject().put("headers", headers)
        assertEquals(emptyMap<String, String>(), d.linkHeaders(other.id, job))
        assertEquals(setOf(SatellitePolicy.ON_BEHALF_HEADER, SatellitePolicy.ON_BEHALF_RUNTIME_HEADER), d.linkHeaders(linkId, job).keys)
        api.stop(); svc.onDestroy()
    }
}
