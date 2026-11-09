package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.platform.Capabilities
import cc.bridgit.app.platform.DeviceProfile
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
 * A0115-A0118 over the owner configuration API: the permission home read, this device's profile and switches
 * (round trips, the inherited (type, purpose) defaults and a specific choice overriding them, safe retries, invalid
 * values, offline), linked devices capped at their pairing, and who may change what.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.LEGACY)
class PermissionsConfigurationTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private lateinit var caller: Agent
    private val sent = mutableListOf<Pair<String, JSONObject?>>()
    /** What the team backend answers: the profile it holds with the defaults its pair tables give. */
    private var backend: (String, JSONObject?) -> Pair<Int, JSONObject> = { _, body -> 200 to profileReply(body) }

    private fun profileReply(body: JSONObject?): JSONObject {
        val purposes = body?.optJSONArray("purposes") ?: JSONArray()
        val controller = (0 until purposes.length()).any { purposes.getString(it) == "network_controller" }
        val defaults = JSONArray()
        for (id in listOf("lan_discovery", "lan_enrollment", "lan_control"))
            defaults.put(JSONObject().put("id", id).put("default", if (controller) "on" else "off").put("decided_by", if (controller) "pair" else "baseline"))
        return JSONObject().put("changed", true).put("profile", JSONObject().put("generation", sent.size).put("purposes", purposes)
            .put("device_type", body?.optString("device_type")).put("model", body?.optString("model")).put("defaults", defaults))
    }

    @Before fun setup() {
        app.settings.listenerEnabled = true
        app.settings.emailVerifiedAt = System.currentTimeMillis()
        app.getSharedPreferences(DeviceProfile.PREFS, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit()
            .remove("switch_lan_discovery").remove("switch_default_lan_discovery").remove("switch_camera").remove("switch_default_camera").commit()
        caller = app.agents.add("PermsOwner", Connection.DIRECT).first
        caller = app.agents.update(caller.id) { it.copy(caps = setOf("owner_features", "settings")) }!!
        Capabilities.setEnabled(app, "owner.configuration", true)
        app.platform.grant(caller.id, "owner.configuration", Long.MAX_VALUE)
        DeviceProfile.transport = { _, method, _, body -> sent += method to body; backend(method, body) }
    }
    @After fun cleanup() { DeviceProfile.transport = { a, m, p, b -> cc.bridgit.app.feedback.DeviceNames.call(a, m, p, b) } }

    private fun service(who: Agent = caller) = ConfigurationPermissions(app, who)
    private fun rejected(code: String, block: suspend () -> Unit) = runBlocking {
        try { block(); fail("Expected $code") } catch (e: ApiError) { assertEquals(e.message, code, e.code) }
    }
    private fun perm(device: JSONObject, id: String): JSONObject {
        val a = device.getJSONArray("permissions")
        return (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == id }
    }

    @Test fun theReadHasEveryAgentThisDeviceAndTheLayers() {
        val r = service().get()
        assertFalse(r.getBoolean("approval_required"))
        val agents = r.getJSONArray("agents")
        val me = (0 until agents.length()).map { agents.getJSONObject(it) }.first { it.getString("id") == caller.id }
        assertEquals(me.getJSONArray("permissions").length(), me.getInt("total"))
        assertEquals("specific", me.getJSONArray("permissions").getJSONObject(0).getString("layer"))
        assertTrue(me.getString("change_url").endsWith("/access/agents/${caller.id}"))
        val device = r.getJSONObject("device")
        assertEquals("android", device.getJSONObject("profile").getString("platform"))
        assertEquals(app.registry.switchable().size, device.getInt("total"))
        assertTrue(r.getJSONArray("layers").toString().contains("superpowers"))
        assertFalse(r.toString().contains(caller.keyHash))
    }

    @Test fun aSwitchSetHereIsSpecificAndDefaultReturnsItAndRetriesChangeNothing() = runBlocking {
        val on = service().device(JSONObject().put("switches", JSONObject().put("camera", true)))
        assertEquals(listOf("camera"), on.getJSONObject("changed").getJSONArray("switches").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals("specific", perm(on.getJSONObject("device"), "camera").getString("layer"))
        assertTrue(app.settings.isOn("camera"))
        val again = service().device(JSONObject().put("switches", JSONObject().put("camera", true)))
        assertEquals(0, again.getJSONObject("changed").getJSONArray("switches").length())
        val back = service().device(JSONObject().put("switches", JSONObject().put("camera", "default")))
        assertEquals("baseline", perm(back.getJSONObject("device"), "camera").getString("layer"))
        assertFalse(app.settings.isOn("camera"))
    }

    @Test fun purposesGiveTheirPairDefaultsAndASpecificChoiceOverridesThem() = runBlocking {
        // The owner turned network control off on purpose before choosing a purpose.
        app.settings.setOn("lan_control", false)
        val r = service().device(JSONObject().put("profile", JSONObject().put("purposes", JSONArray(listOf("network_controller")))
            .put("device_type", "tablet").put("model", "SM-X238U")))
        assertEquals("synced", r.getString("backend"))
        assertEquals("PATCH", sent.last().first)
        assertEquals("android", sent.last().second!!.getString("platform"))
        val device = r.getJSONObject("device")
        assertEquals("default", perm(device, "lan_discovery").getString("layer"))
        assertEquals("on", perm(device, "lan_discovery").getString("inherited"))
        assertTrue(app.settings.isOn("lan_discovery"))
        assertEquals("specific", perm(device, "lan_control").getString("layer"))
        assertFalse(app.settings.isOn("lan_control"))
        val profile = device.getJSONObject("profile")
        assertEquals("tablet", profile.getString("device_type"))
        assertEquals("SM-X238U", profile.getString("model"))
        assertTrue(profile.getBoolean("synced"))
        // Removing the purpose takes its default away again; the specific choice stays.
        service().device(JSONObject().put("profile", JSONObject().put("purposes", JSONArray())))
        assertFalse(app.settings.isOn("lan_discovery"))
        assertFalse(app.settings.isSpecific("lan_discovery"))
        assertTrue(app.settings.isSpecific("lan_control"))
        // The same profile again changes nothing and sends nothing.
        val calls = sent.size
        val same = service().device(JSONObject().put("profile", JSONObject().put("purposes", JSONArray())))
        assertEquals(0, same.getJSONObject("changed").getJSONArray("profile").length())
        assertEquals("unchanged", same.getString("backend"))
        assertEquals(calls, sent.size)
    }

    @Test fun offlineTheProfileIsKeptHereAndGoesUpLater() = runBlocking {
        backend = { _, _ -> throw cc.bridgit.app.api.ErrorCatalog.error("team_unavailable", "offline") }
        val r = service().device(JSONObject().put("profile", JSONObject().put("purposes", JSONArray(listOf("primary")))))
        assertEquals("pending", r.getString("backend"))
        assertFalse(DeviceProfile(app).get().synced)
        assertEquals(listOf("primary"), DeviceProfile(app).get().purposes)
        backend = { _, body -> 200 to profileReply(body) }
        DeviceProfile(app).sync()
        assertTrue(DeviceProfile(app).get().synced)
    }

    @Test fun invalidValuesAreRefusedBeforeAnythingChanges() {
        val bad = listOf(
            JSONObject(),
            JSONObject().put("other", 1),
            JSONObject().put("profile", JSONObject().put("purposes", JSONArray(listOf("Not An Id")))),
            JSONObject().put("profile", JSONObject().put("purposes", JSONArray((1..9).map { "p$it" }))),
            JSONObject().put("profile", JSONObject().put("device_type", "toaster")),
            JSONObject().put("profile", JSONObject().put("model", "x".repeat(81))),
            JSONObject().put("profile", JSONObject().put("model", "  ")),
            JSONObject().put("profile", JSONObject().put("colour", "red")),
            JSONObject().put("switches", JSONObject().put("nope", true)),
            JSONObject().put("switches", JSONObject().put("camera", "on")),
            JSONObject().put("switches", JSONObject().put("camera", true)).put("profile", JSONObject().put("device_type", "toaster")),
        )
        for (b in bad) rejected("invalid_parameter") { service().device(b) }
        assertFalse(app.settings.isOn("camera"))
        assertTrue(sent.isEmpty())
    }

    @Test fun onlyAnAgentWithConfigurationAuthorityCanChangeIt() {
        val other = app.agents.add("NoGrant", Connection.DIRECT).first
        val plain = app.agents.update(other.id) { it.copy(caps = setOf("owner_features")) }!!
        rejected("agent_not_allowed") { service(plain).device(JSONObject().put("switches", JSONObject().put("camera", true))) }
        rejected("agent_not_allowed") { service(plain).get() }
        app.platform.revoke(caller.id, "owner.configuration")
        rejected("agent_not_allowed") { service().device(JSONObject().put("switches", JSONObject().put("camera", true))) }
        assertFalse(app.settings.isOn("camera"))
    }

    @Test fun aLinkedDeviceIsCappedAtItsPairingAndNeedsSuperpowers() {
        val hub = app.agents.add("Kitchen hub", Connection.DIRECT).first
        app.agents.update(hub.id) { it.copy(caps = setOf("camera", "mic")) }
        app.getSharedPreferences("hub_links_v1", android.content.Context.MODE_PRIVATE).edit().putStringSet("ids", setOf(hub.id)).commit()
        val linked = service().get().getJSONArray("linked_devices")
        val row = (0 until linked.length()).map { linked.getJSONObject(it) }.first { it.getString("id") == hub.id }
        assertEquals("hub", row.getString("kind"))
        assertEquals(2, row.getInt("granted"))
        // Not a control agent: absent from agents.
        assertFalse(service().get().getJSONArray("agents").toString().contains(hub.id))
        val body = JSONObject().put("capabilities", JSONArray(listOf("camera")))
        app.settings.setOn("settings_api", true)
        rejected("forbidden") { service().linked(hub.id, body) }
        app.agents.update(caller.id) { it.copy(superPowers = true) }
        caller = app.agents.get(caller.id)!!
        runBlocking {
            val out = service().linked(hub.id, body)
            assertEquals(1, out.getInt("granted"))
            assertTrue(out.getBoolean("changed"))
            assertFalse(service().linked(hub.id, body).getBoolean("changed"))
            // Back up to what it was paired with: fine. Beyond it: refused.
            service().linked(hub.id, JSONObject().put("capabilities", JSONArray(listOf("camera", "mic"))))
        }
        rejected("forbidden") { service().linked(hub.id, JSONObject().put("capabilities", JSONArray(listOf("owner_features")))) }
        rejected("invalid_parameter") { service().linked(hub.id, JSONObject().put("capabilities", JSONArray(listOf("nope")))) }
        rejected("not_found") { service().linked(caller.id, body) }
        assertEquals(setOf("camera", "mic"), app.agents.get(hub.id)!!.caps)
    }
}
