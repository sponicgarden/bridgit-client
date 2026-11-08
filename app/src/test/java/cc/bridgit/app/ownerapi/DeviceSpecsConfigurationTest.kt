package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.McpTools
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.feedback.DeviceSpecs
import cc.bridgit.app.platform.Capabilities
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * A0078: "Share device specs with Bridgit" through the owner configuration API: read, off (DELETE of the stored sheet,
 * retried while the backend is unreachable), on and refresh (PUT), upload only when the sheet changes, validation,
 * and the grant/revocation rules every configuration route has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.LEGACY)
class DeviceSpecsConfigurationTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private lateinit var caller: Agent
    private val calls = mutableListOf<String>()
    private var answer: (String, String) -> Pair<Int, JSONObject> = { _, _ -> 200 to JSONObject() }
    private var version = "1.0"
    private val stored = mutableMapOf<String, JSONObject?>()

    @Before fun setup() {
        TestAndroidKeystore.install()
        app.settings.listenerEnabled = true
        app.settings.emailVerifiedAt = System.currentTimeMillis()
        caller = app.agents.add("SpecsOwner", Connection.DIRECT).first
        app.agents.update(caller.id) { it.copy(caps = setOf("owner_features")) }
        caller = app.agents.get(caller.id)!!
        Capabilities.setEnabled(app, "owner.configuration", true)
        app.platform.grant(caller.id, "owner.configuration", Long.MAX_VALUE)
        app.platform.store.setting("bridgit_name", """{"name":"porch-cam","label":null,"visible":true,"heartbeat_every_s":300}""", true)
        for (k in listOf("device_specs_share", "device_specs_hash", "device_specs_uploaded_at", "device_specs_delete_pending")) app.platform.store.setting(k, "", true)
        DeviceSpecs.lastCheckAt = 0L
        DeviceSpecs.sheet = { JSONObject().put("display", JSONObject().put("width_px", 1344).put("height_px", 2992)).put("os", "android").put("app_version", version) }
        DeviceSpecs.transport = { _, method, path, body ->
            calls += "$method $path"
            when (method) {
                "PUT" -> { stored["me"] = body; 200 to JSONObject().put("name", "porch-cam").put("specs", body).put("specs_updated_at", "2026-11-08T10:00:00Z") }
                "DELETE" -> answer(method, path).also { if (it.first == 200) stored["me"] = null }
                else -> 200 to JSONObject().put("name", "porch-cam").put("specs", stored["me"] ?: JSONObject.NULL).put("specs_updated_at", JSONObject.NULL)
            }
        }
    }
    @After fun cleanup() {
        DeviceSpecs.transport = { a, m, p, b -> cc.bridgit.app.feedback.DeviceNames.call(a, m, p, b) }
        DeviceSpecs.sheet = { a -> DeviceSpecs.build(DeviceSpecs.collect(a)) }
        TestAndroidKeystore.remove()
    }
    private fun service() = ConfigurationResources(app, caller)
    private fun rejected(code: String, block: suspend () -> Unit) = runBlocking {
        try { block(); fail("Expected $code") } catch (e: ApiError) { assertEquals(e.message, code, e.code) }
    }

    @Test fun sharingIsOnByDefaultAndReadsTheStoredCopy() = runBlocking {
        val r = service().specs()
        assertTrue(r.getBoolean("share"))
        assertEquals("porch-cam", r.getString("name"))
        assertEquals(1344, r.getJSONObject("specs").getJSONObject("display").getInt("width_px"))
        assertTrue(r.getString("lookup").endsWith("/v1/names/porch-cam/specs"))
        assertTrue(r.getJSONObject("stored").getBoolean("available"))
        assertEquals(listOf("GET /v1/names/me/specs"), calls)
    }

    @Test fun offDeletesTheStoredSheetAndOnUploadsIt() = runBlocking {
        val on = service().specs(JSONObject().put("share", true))
        assertEquals("uploaded", on.getString("backend"))
        assertEquals("PUT /v1/names/me/specs", calls.last())
        assertTrue(stored["me"] != null)
        val off = service().specs(JSONObject().put("share", false))
        assertEquals("deleted", off.getString("backend"))
        assertFalse(off.getBoolean("share"))
        assertEquals("DELETE /v1/names/me/specs", calls.last())
        assertTrue(stored["me"] == null)
        assertFalse(service().specs().getBoolean("share"))
        // While off the hourly check sends nothing.
        DeviceSpecs.lastCheckAt = 0L; calls.clear()
        DeviceSpecs.syncIfDue(app)
        assertTrue(calls.isEmpty())
        // refresh needs sharing on
        rejected("invalid_parameter") { service().specs(JSONObject().put("refresh", true)) }
        assertEquals("uploaded", service().specs(JSONObject().put("share", true)).getString("backend"))
        assertTrue(service().specs().getBoolean("share"))
    }

    @Test fun aDeletionTheBackendMissedIsRetriedLater() = runBlocking {
        answer = { _, _ -> throw cc.bridgit.app.api.ErrorCatalog.error("team_unavailable", "offline") }
        val off = service().specs(JSONObject().put("share", false))
        assertEquals("pending", off.getString("backend"))
        assertTrue(off.getBoolean("delete_pending"))
        answer = { _, _ -> 200 to JSONObject().put("name", "porch-cam").put("specs", JSONObject.NULL) }
        DeviceSpecs.lastCheckAt = 0L; calls.clear()
        assertEquals("deleted", DeviceSpecs.syncIfDue(app))
        assertEquals(listOf("DELETE /v1/names/me/specs"), calls)
        assertFalse(service().specs().getBoolean("delete_pending"))
    }

    @Test fun uploadsWhenTheSheetChangesAndOnlyThen() {
        DeviceSpecs.lastCheckAt = 0L
        assertEquals("uploaded", DeviceSpecs.syncIfDue(app))
        DeviceSpecs.lastCheckAt = 0L
        assertEquals("unchanged", DeviceSpecs.syncIfDue(app))
        assertEquals(1, calls.count { it.startsWith("PUT") })
        // Not due again within the hour.
        assertEquals(null, DeviceSpecs.syncIfDue(app))
        version = "1.1"
        DeviceSpecs.lastCheckAt = 0L
        assertEquals("uploaded", DeviceSpecs.syncIfDue(app))
        assertEquals(2, calls.count { it.startsWith("PUT") })
        assertEquals("1.1", stored["me"]!!.getString("app_version"))
    }

    @Test fun noNameMeansNothingIsSent() {
        app.platform.store.setting("bridgit_name", "", true)
        DeviceSpecs.lastCheckAt = 0L
        assertEquals("not_named", DeviceSpecs.syncIfDue(app))
        assertTrue(calls.isEmpty())
        // A 404 no_name from the backend is skipped, not stored as uploaded.
        app.platform.store.setting("bridgit_name", """{"name":"porch-cam","visible":true}""", true)
        DeviceSpecs.transport = { _, m, p, _ -> calls += "$m $p"; 404 to JSONObject().put("error", "no_name") }
        DeviceSpecs.lastCheckAt = 0L
        assertEquals("not_named", DeviceSpecs.syncIfDue(app))
        assertEquals("", app.platform.store.setting("device_specs_hash"))
    }

    @Test fun validationMatchesTheSwitch() {
        for (body in listOf("""{}""", """{"share":"no"}""", """{"refresh":false}""", """{"share":false,"refresh":true}""", """{"share":true,"extra":1}""", """{"specs":{}}"""))
            rejected("invalid_parameter") { service().specs(JSONObject(body)) }
        assertTrue(calls.isEmpty())
        assertTrue(ConfigurationEndpoints.ALL.containsAll(listOf(ConfigurationEndpoints.SPECS, ConfigurationEndpoints.SPECS_SET)))
        val props = McpTools.inputSchema(ConfigurationEndpoints.SPECS_SET).getJSONObject("properties")
        assertTrue(props.has("share") && props.has("refresh"))
    }

    @Test fun revokedGrantRefusesBeforeAnyCall() {
        app.platform.revoke(caller.id, "owner.configuration")
        try { runBlocking { service().specs(JSONObject().put("share", false)) }; fail() } catch (e: ApiError) { assertTrue(e.code in setOf("agent_not_allowed", "forbidden", "unauthorized", "permission_denied")) }
        assertTrue(calls.isEmpty())
        assertTrue(DeviceSpecs.shared(app))
        val other = app.agents.add("Applet", Connection.APPLET).first
        try { runBlocking { ConfigurationResources(app, other).specs() }; fail() } catch (e: ApiError) { assertTrue(e.code in setOf("forbidden", "agent_not_allowed")) }
    }

    @Test fun hintTellsAgentsToAskBridgitForStaticSpecs() {
        val h = cc.bridgit.app.feedback.DeviceNames.hint(app).getString("specs")
        assertTrue(h.contains("/v1/names/porch-cam/specs"))
        assertTrue(h.contains("full_screen"))
        app.platform.store.setting("device_specs_share", "0", true)
        assertTrue(cc.bridgit.app.feedback.DeviceNames.hint(app).getString("specs").contains("/v1/device/display"))
    }
}
