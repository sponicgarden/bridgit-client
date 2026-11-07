package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.*
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.judgment.OwnKeys
import cc.bridgit.app.platform.Capabilities
import cc.bridgit.app.platform.Connections
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.Base64
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.LEGACY)
class UnattendedConfigurationTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private lateinit var caller: Agent
    @Before fun setup() {
        TestAndroidKeystore.install()
        app.settings.listenerEnabled = true
        app.settings.emailVerifiedAt = System.currentTimeMillis()
        caller = app.agents.add("ConfigOwner", Connection.DIRECT).first
        app.agents.update(caller.id) { it.copy(caps = setOf("owner_features", "camera", "lan_camera", "lan_camera_audio", "wakewords", "sounds")) }
        caller = app.agents.get(caller.id)!!
        Capabilities.setEnabled(app, "owner.configuration", true)
        app.platform.grant(caller.id, "owner.configuration", Long.MAX_VALUE)
    }
    @After fun cleanup() { TestAndroidKeystore.remove() }
    private fun rejected(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: ApiError) { assertEquals(e.message, code, e.code) }
    }
    private fun api(): ApiServer = ApiServer(Robolectric.buildService(BridgeService::class.java).create().get(), emptyList(), 0).also { it.prepareRelay() }
    private fun specCall(api: ApiServer, spec: EndpointSpec, body: JSONObject = JSONObject(), key: String? = UUID.randomUUID().toString(), path: Map<String,String> = emptyMap()) = runBlocking {
        api.dispatch(spec, caller, "100.64.0.2", emptyMap(), path, body, key)
    }
    private fun o(text: String) = JSONObject(text)

    @Test fun explicitConfigurationGrantProjectsOnlyConfigurationAndRevocationRemovesIt() {
        val original = o("""{"id":"${caller.id}","generation":"${caller.keyHash}","routes":["GET /v1/status"],"tools":["phone.status"]}""")
        val effective = ConfigurationDelegation.effective(app, original)
        assertTrue(effective.getJSONArray("tools").toString().contains("configuration.set"))
        assertTrue(cc.bridgit.app.relay.RelayDispatcher.strings(effective, "routes").contains("POST /v1/owner/configuration/settings"))
        assertFalse(effective.getJSONArray("tools").toString().contains("ui.action"))
        assertFalse(original.toString().contains("configuration.set"))
        app.platform.revoke(caller.id, "owner.configuration")
        assertEquals(original.toString(), ConfigurationDelegation.effective(app, original).toString())
        app.platform.grant(caller.id, "owner.configuration", Long.MAX_VALUE)
        app.agents.update(caller.id) { it.copy(paused = true) }
        assertEquals(original.toString(), ConfigurationDelegation.effective(app, original).toString())
        app.agents.update(caller.id) { it.copy(paused = false, connection = Connection.APPLET) }
        assertEquals(original.toString(), ConfigurationDelegation.effective(app, original).toString())
    }

    @Test fun controlToolsKeepAuthenticatedGenerationAndReturnDirectSecretSafeResults() = runBlocking {
        val api = api()
        val args = o("""{"operation":"POST /v1/owner/configuration/keys/{provider}","parameters":{"provider":"openrouter"},"body":{"credentials":["fixture-openrouter-key-0123456789"]}}""")
        val result = ConfigurationTools.call(app, api, caller.id, caller.keyHash, "configuration.set", args, UUID.randomUUID().toString())
        assertTrue(result.getBoolean("configured"))
        assertFalse(result.toString().contains("fixture-openrouter"))
        assertTrue(app.platform.store.list(kind="operation").isEmpty())
        app.agents.update(caller.id) { it.copy(keyHash = "replacement") }
        try {
            ConfigurationTools.call(app, api, caller.id, caller.keyHash, "configuration.set", args, UUID.randomUUID().toString())
            fail("Old authenticated key must not inherit replacement authority")
        } catch (e: ApiError) { assertEquals("unauthorized", e.code) }
    }

    @Test fun savedFoodViewCanBeInspectedAndClearedWhenCameraFeaturesAreOff() = runBlocking {
        app.settings.setOn("lan_camera", false)
        app.settings.setOn("lan_camera_audio", false)
        val service = ConfigurationResources(app, caller)
        assertFalse(service.feeding().getBoolean("enabled"))
        service.feeding(o("""{"clear":true}"""))
        Unit
    }

    @Test fun controlDispatchEnforcesConfigurationBodyLimitBeforeEffects() {
        val response = specCall(api(), ConfigurationEndpoints.SETTINGS, JSONObject().put("changes", JSONObject().put("device.name", "x".repeat(33000))))
        assertEquals(413, response.status)
    }

    @Test fun nativeCredentialCommitRefusesAuthorityRevokedDuringEncryption() {
        val keys = OwnKeys(app)
        keys.set("openrouter", "fixture-original-key-0123456789")
        var checks = 0
        rejected("unauthorized") {
            keys.set("openrouter", "fixture-replacement-key-0123456789") {
                if (++checks == 2) throw ErrorCatalog.error("unauthorized", "Key revoked")
            }
        }
        assertEquals(2, checks)
        assertEquals("fixture-original-key-0123456789", keys.get("openrouter"))
    }

    @Test fun legacyOwnerConfigureRetainsAuthenticatedGeneration() {
        val api = api()
        app.agents.update(caller.id) { it.copy(keyHash = "replacement") }
        val result = specCall(api, OwnerFeatureEndpoints.CONFIGURE, o("""{"action":"updates.offers","options":{"enabled":false}}"""))
        assertEquals(401, result.status)
    }

    @Test fun settingsValidateBeforeChangingAndPreserveSecurityBoundaries() = runBlocking {
        val service = ConfigurationSettings(app, caller)
        app.settings.capturesShareAll = false
        val bad = o("""{"changes":{"captures.any_agent_can_fetch":true,"listener.port":12}}""")
        try { service.update(bad); fail() } catch (e: ApiError) { assertEquals("invalid_parameter", e.code) }
        assertFalse(app.settings.capturesShareAll)
        service.update(o("""{"changes":{"captures.any_agent_can_fetch":true,"captures.max_keep_minutes":60}}"""))
        assertTrue(app.settings.capturesShareAll)
        assertEquals(60, app.settings.capturesMaxKeepMinutes)
        for (key in listOf("security.biometric", "agent.super_powers", "android.accessibility")) {
            try { service.update(JSONObject().put("changes", JSONObject().put(key, true))); fail() } catch (e: ApiError) { assertEquals("setting_locked", e.code) }
        }
        assertFalse(caller.superPowers) // explicit configuration scope, no need to grant itself Super Powers
    }

    @Test fun replayAfterConfigurationRevocationIsRefusedAndMissingKeyIsRefused() {
        val api = api()
        val body = o("""{"changes":{"device.name":"Kitchen"}}""")
        assertEquals(422, specCall(api, ConfigurationEndpoints.SETTINGS, body, null).status)
        val key = UUID.randomUUID().toString()
        val first = specCall(api, ConfigurationEndpoints.SETTINGS, body, key)
        assertEquals(String(first.bytes), 200, first.status)
        app.platform.revoke(caller.id, "owner.configuration")
        assertEquals(403, specCall(api, ConfigurationEndpoints.SETTINGS, body, key).status)
        assertEquals(403, specCall(api, ConfigurationEndpoints.INDEX).status)
    }

    @Test fun intentionalListenerPauseReturnsSuccessButGrantsAreStillRechecked() {
        val api = api()
        val result = specCall(api, ConfigurationEndpoints.SETTINGS, o("""{"changes":{"listener.enabled":false}}"""))
        assertEquals(String(result.bytes), 200, result.status)
        assertFalse(app.settings.listenerEnabled)
        assertTrue(JSONObject(String(result.bytes)).getJSONObject("listener").getBoolean("reconnect_required"))
        assertEquals(caller.id, ConfigurationAccess(app, caller).require(allowListenerPaused = true).id)
        app.platform.revoke(caller.id, "owner.configuration")
        rejected("agent_not_allowed") { ConfigurationAccess(app, caller).require(allowListenerPaused = true) }
    }

    @Test fun pausedRotatedAndAppletPrincipalsCannotAdminister() {
        app.agents.update(caller.id) { it.copy(paused = true) }
        rejected("unauthorized") { ConfigurationAccess(app, caller).require() }
        app.agents.update(caller.id) { it.copy(paused = false, keyHash = "rotated") }
        rejected("unauthorized") { ConfigurationAccess(app, caller).require() }
        val applet = app.agents.add("Applet", Connection.APPLET).first
        app.platform.grant(applet.id, "owner.configuration", Long.MAX_VALUE)
        rejected("forbidden") { ConfigurationAccess(app, applet).require() }
    }

    @Test fun nativeModelKeyStoreRoundTripUsesTrimmedFieldsAndNeverReturnsValues() {
        val service = ConfigurationCredentials(app, caller)
        val secret = "fixture-openrouter-key-0123456789"
        val response = service.set("openrouter", JSONObject().put("credentials", JSONArray(listOf("  $secret  "))))
        assertTrue(response.getBoolean("configured")); assertFalse(response.toString().contains(secret)); assertFalse(service.status().toString().contains(secret.takeLast(4)))
        assertEquals(secret, OwnKeys(app).get("openrouter"))
        val persisted = app.getSharedPreferences("own_keys", 0).all.toString()
        assertFalse(persisted.contains(secret))
        rejected("invalid_parameter") { service.set("openrouter", o("""{"credentials":["short"]}""")) }
        assertEquals(secret, OwnKeys(app).get("openrouter"))
        service.remove("openrouter"); assertNull(OwnKeys(app).get("openrouter"))
        assertFalse(app.platform.store.list(kind="operation").toString().contains(secret))
    }

    @Test fun connectionsAreWriteOnlyDurablyIdempotentAndRotateWithoutRetryChurn() {
        val service = ConfigurationConnections(app, caller)
        val secret = "whsec_" + Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val key = UUID.randomUUID().toString()
        val body = o("""{"name":"Receiver","url":"https://example.com/private-token","preset":"json","headers":{"Authorization":"Bearer private-header"}}""").put("signing_secret", secret)
        val first = service.create(body, " $key ")
        val id = first.getString("id")
        val stored = app.platform.store.find(id, "owner", "destination")!!
        assertEquals("v1,YII0UYIC2z0QxETiAjAaRSvZUh0Nhim3cUGtUDbFuAA=", Connections.sign(stored.getString("whsec"), "msg_fixture", 1700000000, "{\"text\":\"hello\"}".toByteArray()))
        val repeated = ConfigurationConnections(app, caller).create(body, key)
        assertEquals(id, repeated.getString("id"))
        assertEquals(1, app.platform.store.list("owner", "destination").size)
        val visible = service.list().toString()
        for (value in listOf("private-token", "private-header", secret)) assertFalse(visible.contains(value))
        rejected("conflict") { service.create(JSONObject(body.toString()).put("name", "Changed"), key) }
        val replacement = "whsec_" + Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
        val rotate = JSONObject().put("signing_secret", replacement)
        service.rotate(id, rotate)
        val before = app.platform.store.find(id, "owner", "destination")!!
        service.rotate(id, rotate)
        val after = app.platform.store.find(id, "owner", "destination")!!
        assertEquals(secret, after.getString("whsec_previous"))
        assertEquals(before.getLong("whsec_previous_until"), after.getLong("whsec_previous_until"))
        service.update(id, o("""{"headers":null,"name":"Updated"}"""))
        assertEquals(0, app.platform.store.find(id, "owner", "destination")!!.getJSONObject("headers").length())
        assertEquals(replacement, after.getString("whsec"))
        service.remove(id); assertFalse(service.list().getJSONArray("connections").getJSONObject(0).getBoolean("enabled"))
    }

    @Test fun callbackReceiverSecretIsProvisionedAndPreservedByUrlOnlyUpdate() {
        val service = ConfigurationResources(app, caller)
        val secret = "cbs_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })
        service.callback(caller.id, JSONObject().put("url", "http://100.64.0.5:8080/events").put("secret", secret))
        assertEquals(secret, app.callbacks.secret(caller.id))
        assertEquals("sha256=139273f149a38c7eba8715d53adc3abdd1d3d4b8beb7a75a06a5e50f11bfa74b", cc.bridgit.app.core.CallbackRules.sign(app.callbacks.secret(caller.id)!!, 1700000000, "{\"text\":\"hello\"}".toByteArray()))
        assertFalse(service.callbacks().toString().contains(secret))
        val replacement = "cbs_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 4 })
        service.callback(caller.id, JSONObject().put("url", "http://100.64.0.5:8080/events").put("secret", replacement))
        service.callback(caller.id, JSONObject().put("url", "http://100.64.0.6:8080/events"))
        assertEquals(replacement, app.callbacks.secret(caller.id))
        service.removeCallback(caller.id); assertNull(app.callbacks.secret(caller.id))
    }

    @Test fun completeListeningDefinitionsValidateAtomicallyAndCanBeCleared() = runBlocking {
        val service = ConfigurationResources(app, caller)
        val target = app.agents.add("Recipient", Connection.DIRECT).first.id
        val input = JSONObject().put("wake_words", JSONArray().put(JSONObject().put("phrase", "Hey Helper!").put("agent_id", target).put("send_clip", true)))
            .put("sounds", JSONObject().put("agent_id", target).put("sensitivity", "quiet").put("send_clip", false))
        service.listening(input)
        assertEquals("hey helper", service.listening().getJSONArray("wake_words").getJSONObject(0).getString("phrase"))
        assertEquals(target, service.listening().getJSONObject("sounds").getString("agent_id"))
        val before = service.listening().toString()
        val bad = JSONObject(input.toString()).put("sounds", JSONObject().put("agent_id", target).put("send_clip", "yes"))
        try { service.listening(bad); fail() } catch (e: ApiError) { assertEquals("invalid_parameter", e.code) }
        assertEquals(before, service.listening().toString())
        service.listening(JSONObject().put("wake_words", JSONArray()).put("sounds", JSONObject.NULL))
        assertEquals(0, service.listening().getJSONArray("wake_words").length())
        assertTrue(service.listening().isNull("sounds"))
    }

    @Test fun customModesAndNotificationSelectionsUseDelegatedAuthorityWithoutGrantingMore() = runBlocking {
        val service = ConfigurationResources(app, caller)
        service.modes(o("""{"custom":["Guests"]}"""))
        assertEquals(listOf("guests"), app.platform.modes.custom())
        service.modes(o("""{"custom":[]}"""))
        assertTrue(app.platform.modes.custom().isEmpty())
        app.platform.store.setting("notification_apps:${caller.id}", "com.example.previous", true)
        service.notifications(caller.id, o("""{"packages":[]}"""))
        assertEquals(0, service.notifications(caller.id).getJSONArray("packages").length())
        assertFalse(app.platform.hasGrant(caller.id, "notifications.read"))
        try { service.notifications(caller.id, o("""{"packages":["com.unknown.notinstalled"]}""")); fail() }
        catch (e: ApiError) { assertEquals("invalid_parameter", e.code) }
        assertEquals(0, service.notifications(caller.id).getJSONArray("packages").length())
    }

    @Test fun bridgitNameValidatesLocallyAndKeepsTheFirstName() = runBlocking {
        val service = ConfigurationResources(app, caller)
        assertEquals("kitchen-tab", cc.bridgit.app.feedback.DeviceNames.parse(" Kitchen-Tab "))
        for (bad in listOf("ab", "porch/cam", "-cam", "two--hyphens", "x".repeat(31), "has space")) assertNull(bad, cc.bridgit.app.feedback.DeviceNames.parse(bad))
        assertEquals("unregistered", cc.bridgit.app.feedback.DeviceNames.hint(app).getString("status"))
        assertTrue(cc.bridgit.app.feedback.DeviceNames.hint(app).getString("how").contains(cc.bridgit.app.feedback.DeviceNames.OPERATION))
        for (body in listOf("""{"name":"a/b"}""", """{"name":7}""", """{"nick":"x"}""", """{}""", """{"label":5}""", """{"visible":"yes"}""", """{"label":"${"x".repeat(61)}"}"""))
            try { service.name(o(body)); fail(body) } catch (e: ApiError) { assertEquals(body, "invalid_parameter", e.code) }
        app.platform.store.setting("bridgit_name", """{"name":"porch-cam","label":null,"visible":true,"heartbeat_every_s":300}""", true)
        assertEquals("porch-cam", cc.bridgit.app.feedback.DeviceNames.hint(app).getString("name"))
        try { service.name(o("""{"name":"kitchen-tab"}""")); fail() } catch (e: ApiError) { assertEquals("already_named", e.code); assertEquals("porch-cam", e.extra["name"]) }
        assertTrue(ConfigurationEndpoints.ALL.containsAll(listOf(ConfigurationEndpoints.NAME, ConfigurationEndpoints.NAME_SET, ConfigurationEndpoints.NAME_DELETE)))
        val types = McpTools.inputSchema(ConfigurationEndpoints.NAME_SET).getJSONObject("properties").getJSONObject("label").getJSONArray("type")
        assertTrue((0 until types.length()).map(types::getString).contains("null"))
    }

    @Test fun newSchemasExposeScopeStringArraysAndNullableClears() {
        for (spec in listOf(ConfigurationEndpoints.KEY_SET, ConfigurationEndpoints.NOTIFICATIONS_SET, ConfigurationEndpoints.MODES_SET)) {
            val field = when (spec) { ConfigurationEndpoints.KEY_SET -> "credentials"; ConfigurationEndpoints.NOTIFICATIONS_SET -> "packages"; else -> "custom" }
            assertEquals("string", McpTools.inputSchema(spec).getJSONObject("properties").getJSONObject(field).getJSONObject("items").getString("type"))
        }
        assertTrue(McpTools.description(ConfigurationEndpoints.INDEX).contains("owner.configuration"))
        val types = McpTools.inputSchema(ConfigurationEndpoints.EMAIL_SET).getJSONObject("properties").getJSONObject("email").getJSONArray("type")
        assertTrue((0 until types.length()).map(types::getString).contains("null"))
    }

    @Test fun everyConfigurationRouteDeclaresScopeAndFullRedaction() {
        for (spec in ConfigurationEndpoints.ALL) {
            if (spec.power == cc.bridgit.app.core.Power.SUPER) {
                assertEquals("settings", spec.capability)
                assertNull(spec.controlScope)
            } else assertEquals(spec.path, "owner.configuration", spec.controlScope)
            if (spec.method != "GET") {
                assertTrue(spec.path, spec.idempotencyRequired)
                assertEquals("[redacted]", spec.loggedParam("credentials", "must-not-appear"))
            }
        }
    }
}
