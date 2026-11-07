package cc.bridgit.app.core

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.capability.SettingsConfigurationApi
import cc.bridgit.app.capability.SettingsController
import cc.bridgit.app.platform.Modes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsConfigurationTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private fun rejected(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: ApiError) { assertEquals(code, e.code) }
    }

    @Test fun modeDefinitionsRoundTripAndRefuseInvalidReplacementAtomically() {
        val modes = app.platform.modes
        modes.replaceCustom(listOf("Late shift", "guests"))
        assertEquals(listOf("late_shift", "guests"), modes.custom())
        assertEquals("home", modes.current())
        for (bad in listOf(listOf("home"), listOf("Bad!"), listOf("same", "SAME"), (1..9).map { "mode_$it" })) {
            rejected("invalid_parameter") { modes.replaceCustom(bad) }
            assertEquals(listOf("late_shift", "guests"), modes.custom())
        }
        modes.set("guests", Modes.By("owner", "You"))
        rejected("conflict") { modes.replaceCustom(emptyList()) }
        modes.set("home", Modes.By("owner", "You"))
        modes.replaceCustom(emptyList())
        assertEquals(Modes.DEFAULTS, modes.all())
    }

    @Test fun schemaRejectsWrongTypesUnknownFieldsAndMissingLists() {
        for (bad in listOf(JSONObject(), JSONObject("""{"custom":null}"""), JSONObject("""{"custom":[true]}"""), JSONObject("""{"custom":[],"extra":true}"""))) {
            rejected("invalid_parameter") { SettingsConfigurationApi.customModes(bad) }
        }
        assertEquals(emptyList<String>(), SettingsConfigurationApi.customModes(JSONObject().put("custom", JSONArray())))
    }

    @Test fun configurationRequiresCurrentAuthorityAndHonorsRevocation() {
        app.settings.listenerEnabled = true
        app.settings.setOn("settings_api", true)
        val id = app.policy.addAgent("ConfigTest").first.id
        rejected("forbidden") { SettingsConfigurationApi.requireWriter(app, id) }
        app.agents.update(id) { it.copy(superPowers = true, caps = setOf("settings")) }
        assertEquals(id, SettingsConfigurationApi.requireWriter(app, id).id)
        rejected("unauthorized") { SettingsConfigurationApi.requireWriter(app, id, "stale-key-hash") }
        app.agents.update(id) { it.copy(caps = emptySet()) }
        rejected("agent_not_allowed") { SettingsConfigurationApi.requireWriter(app, id) }
        app.agents.update(id) { it.copy(caps = setOf("settings"), paused = true) }
        rejected("unauthorized") { SettingsConfigurationApi.requireWriter(app, id) }
        app.agents.update(id) { it.copy(paused = false) }
        app.settings.setOn("settings_api", false)
        rejected("capability_disabled") { SettingsConfigurationApi.requireWriter(app, id) }
    }

    @Test fun appOwnedCaptureAndCallbackPolicyCanRoundTripWhileAuthorityStaysLocked() {
        val controller = SettingsController(app)
        val changes = mapOf<String, Any>("listener.port" to 9876, "listener.allow_internet_callbacks" to true,
            "privacy.pause_external_calls" to false, "captures.agent_files" to true, "captures.agent_upload" to true,
            "captures.max_keep_minutes" to 45, "captures.any_agent_can_fetch" to true, "captures.owner_files" to true)
        changes.forEach { (key, value) ->
            val def = controller.def(key)!!
            assertNull(key, def.lock)
            assertTrue(key, SettingsCatalog.parse(def,value) is SettingsCatalog.Parsed.Ok)
            controller.apply(key,value)
            assertEquals(key,value,controller.read(key,null))
        }
        assertNotNull(controller.def("features.settings_api")!!.lock)
        assertNotNull(controller.def("security.biometric")!!.lock)
        assertNotNull(controller.def("agent.capabilities")!!.lock)
    }

    @Test fun savedKeyWritesUseUiValidationAndNeverExposeRequestValuesInLogs() {
        val secret = "sk-or-v1-" + "a".repeat(64)
        val valid = JSONObject().put("provider","openrouter").put("fields",JSONArray().put(secret))
        val change = SettingsConfigurationApi.keyChange(valid)
        assertEquals("openrouter",change.first)
        assertEquals(secret,change.second)
        assertEquals("[redacted]",SettingsConfigurationApi.KEYS_SET.loggedParam("fields",valid.get("fields")))
        assertEquals("[redacted]",SettingsConfigurationApi.CAPTURE_UPLOAD.loggedParam("url","https://example.com/secret"))
        assertNull(SettingsConfigurationApi.keyChange(JSONObject().put("provider","openrouter").put("remove",true)).second)
        for (bad in listOf(JSONObject().put("provider","unknown").put("remove",true),
            JSONObject().put("provider","openrouter").put("fields",JSONArray().put("bad")),
            JSONObject(valid.toString()).put("remove",true), JSONObject(valid.toString()).put("extra",secret))) {
            rejected("invalid_parameter") { SettingsConfigurationApi.keyChange(bad) }
        }
        SettingsConfigurationApi.ALL.forEach { endpoint ->
            assertTrue(cc.bridgit.app.api.Endpoints.all.contains(endpoint))
            assertNotNull(cc.bridgit.app.api.McpTools.byName(cc.bridgit.app.api.McpTools.name(endpoint)))
        }
    }

    @Test fun appOwnedConfigurationRunsUnderExistingGrantWithoutAnotherApproval() {
        app.settings.listenerEnabled = true
        val a = app.agents.add("OwnerConfig", Connection.DIRECT).first
        cc.bridgit.app.power.PowerAccess(app).apply { enableUnconfiguredFeatures(); grantAll(a.id) }
        val request = JSONObject().put("action","updates.offers").put("options",JSONObject().put("enabled",false))
        val op = app.platform.mutate(a.id,"owner.configure",request,"owner-config-123")
        assertNotEquals("awaiting_owner",op.getString("status"))
        assertEquals(op.getString("id"),app.platform.mutate(a.id,"owner.configure",request,"owner-config-123").getString("id"))
        app.platform.revoke(a.id,"owner.configuration")
        rejected("agent_not_allowed") { app.platform.mutate(a.id,"owner.configure",request,"owner-config-456") }
    }

    @Test fun connectionConfigurationValidatesBeforeReplacementAndKeepsSecretsOutOfResponses() {
        val manager=app.platform.delivery
        val first=manager.destination("Automation","https://example.com/hook",false,"json")
        val id=first.getString("id")
        val secret=first.getString("whsec")
        val next=manager.destination("Renamed","https://example.com/new-hook",false,"json",id=id)
        assertEquals(id,next.getString("id"))
        assertEquals(secret,next.getString("whsec"))
        assertEquals("Renamed",next.getString("name"))
        rejected("invalid_connection") { manager.destination("Bad","http://public.example/hook",false,"json",id=id) }
        assertEquals("Renamed",app.platform.store.require(id,"owner","destination").getString("name"))
        val public=cc.bridgit.app.platform.Connections.public(next).toString()
        assertFalse(public.contains(secret))
        assertFalse(public.contains("example.com"))
        val rotated=manager.rotate(id)
        assertNotEquals(secret,rotated.getString("whsec"))
        manager.revoke(id)
        assertTrue(manager.connections().none { it.getString("id")==id })
        assertEquals("[redacted]",cc.bridgit.app.platform.ConnectionsApi.CONFIGURE.loggedParam("url","https://example.com/secret"))
    }

    @Test fun appletConfigurationDoesNotInheritUnattendedControlAgentAuthority() {
        app.settings.listenerEnabled = true
        val a=app.agents.add("ScopedApplet",Connection.APPLET).first
        app.agents.update(a.id) { it.copy(caps=setOf("owner_features")) }
        app.platform.grant(a.id,"owner.configuration",Long.MAX_VALUE)
        val op=app.platform.mutate(a.id,"owner.configure",JSONObject().put("action","updates.offers")
            .put("options",JSONObject().put("enabled",false)),"applet-config-123")
        assertEquals("awaiting_owner",op.getString("status"))
    }

    @Test fun deviceNameHasValidatedReadWriteAndClear() {
        val controller = SettingsController(app)
        val def = controller.def("device.name")!!
        assertTrue(SettingsCatalog.parse(def, "x".repeat(61)) is SettingsCatalog.Parsed.Bad)
        assertTrue(SettingsCatalog.parse(def, "name\n") is SettingsCatalog.Parsed.Bad)
        assertTrue(SettingsCatalog.parse(def, 42) is SettingsCatalog.Parsed.Bad)
        controller.apply("device.name", "Kitchen tablet")
        assertEquals("Kitchen tablet", controller.read("device.name", null))
        controller.apply("device.name", "")
        assertEquals("", controller.read("device.name", null))
    }
}
