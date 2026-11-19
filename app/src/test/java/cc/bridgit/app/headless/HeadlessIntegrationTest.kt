package cc.bridgit.app.headless

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.capability.SettingsController
import cc.bridgit.app.core.Connection
import cc.bridgit.app.core.SettingsCatalog
import cc.bridgit.app.core.request.CoreRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class HeadlessIntegrationTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp

    @Test fun freshHeadlessLaunchDoesNotInflateFirstRunOrBootstrapAuthority() {
        HeadlessMode.set(app, "headless")
        val activity = Robolectric.buildActivity(cc.bridgit.app.ui.MainActivity::class.java).create().start().resume().get()
        assertTrue(activity.isFinishing)
        assertEquals(0, app.agents.count)
        assertFalse(app.settings.listenerEnabled)
        val receiver = app.packageManager.getReceiverInfo(android.content.ComponentName(app, HeadlessAdminReceiver::class.java), 0)
        assertEquals("android.permission.DUMP", receiver.permission)
        assertTrue(receiver.exported)
    }

    @Test fun presentationRoundTripsAndRejectsUnknownValues() {
        val controller = SettingsController(app)
        val def = controller.def("device.presentation")!!
        assertEquals("auto", controller.read(def.key, null))
        for (mode in HeadlessPolicy.MODES) {
            assertTrue(SettingsCatalog.parse(def, mode) is SettingsCatalog.Parsed.Ok)
            controller.apply(def.key, mode)
            assertEquals(mode, controller.read(def.key, null))
        }
        for (bad in listOf<Any>(true, 1, "gui", "", "HEADLESS"))
            assertTrue(SettingsCatalog.parse(def, bad) is SettingsCatalog.Parsed.Bad)
        assertEquals(0, app.agents.count) // Selecting headless never grants initial authority.
        assertFalse(app.settings.listenerEnabled)
    }

    @Test fun loopbackStillRequiresAValidUnrevokedKeyAndRejectsLan() = runBlocking {
        HeadlessMode.set(app, "headless")
        app.settings.listenerEnabled = true
        val (agent, key) = app.agents.add("USB owner", Connection.DIRECT)
        val service = Robolectric.buildService(BridgeService::class.java).create().get()
        val api = ApiServer(service, emptyList(), 0).also { it.prepareRelay() }
        suspend fun admit(token: String?, source: String = "127.0.0.1") = api.admitHttp(
            CoreRequest("http_v1", "GET", Endpoints.CAPABILITIES.fullPath, source) {
                if (it == "Authorization") token?.let { k -> "Bearer $k" } else null
            }, Endpoints.CAPABILITIES)
        suspend fun denied(code: String, token: String?, source: String = "127.0.0.1") {
            try { admit(token, source); fail("Expected $code") } catch (e: ApiError) { assertEquals(code, e.code) }
        }
        denied("unauthorized", null)
        denied("unauthorized", "wrong-key")
        denied("forbidden_source", key, "192.168.1.20")
        assertEquals(agent.id, admit(key).agent!!.id)
        app.agents.remove(agent.id)
        denied("unauthorized", key)
        api.stop()
    }

    @Test fun visualRequestsFailBeforeTheyCreateAPendingInteraction() = runBlocking {
        HeadlessMode.set(app, "headless")
        app.settings.listenerEnabled = true
        app.settings.setOn("interactions", true)
        val agent = app.agents.add("Visual test", Connection.DIRECT).first
        val service = Robolectric.buildService(BridgeService::class.java).create().get()
        val api = ApiServer(service, emptyList(), 0).also { it.prepareRelay() }
        val output = api.dispatch(Endpoints.INTERACTION_CREATE, agent, "127.0.0.1", emptyMap(), emptyMap(),
            JSONObject().put("kind", "html").put("html", "<p>Test</p>"), "headless-visual-test", via = "http")
        assertEquals("not_supported", JSONObject(String(output.bytes)).getString("error"))
        assertEquals("display_unavailable", JSONObject(String(output.bytes)).getString("reason"))
        assertTrue(app.interactions.listFor(agent.id, null, 10).isEmpty())
        assertEquals("display_unavailable", app.registry.modules.first { it.id == "interactions" }.blockedReason())
        api.stop()
    }
}
