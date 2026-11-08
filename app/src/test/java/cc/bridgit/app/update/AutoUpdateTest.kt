package cc.bridgit.app.update

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.ownerapi.ConfigurationResources
import cc.bridgit.app.ownerapi.OwnerActions
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoUpdateTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private fun o(s: String) = JSONObject(s)

    @Test fun decisions() {
        assertTrue(AutoUpdateLogic.supported(debug = false, play = false))
        assertFalse(AutoUpdateLogic.supported(debug = true, play = false))
        assertFalse(AutoUpdateLogic.supported(debug = false, play = true))
        assertTrue(AutoUpdateLogic.canInstallSilently(adb = true, sessionApi = false, installsAllowed = false))
        assertTrue(AutoUpdateLogic.canInstallSilently(adb = false, sessionApi = true, installsAllowed = true))
        assertFalse(AutoUpdateLogic.canInstallSilently(adb = false, sessionApi = true, installsAllowed = false))
        assertFalse(AutoUpdateLogic.canInstallSilently(adb = false, sessionApi = false, installsAllowed = true))
        assertEquals(AutoUpdate.Result.UNSUPPORTED, AutoUpdateLogic.gate(supported = false, on = true, silent = true))
        assertEquals(AutoUpdate.Result.OFF, AutoUpdateLogic.gate(supported = true, on = false, silent = true))
        assertEquals(AutoUpdate.Result.NEEDS_OWNER, AutoUpdateLogic.gate(supported = true, on = true, silent = false))
        assertNull(AutoUpdateLogic.gate(supported = true, on = true, silent = true))
    }

    @Test fun onByDefaultAndReadableInOwnerSettings() {
        app.getSharedPreferences("update", 0).edit().clear().commit()
        assertTrue(AutoUpdate.enabled(app))
        val s = app.ownerFeatures.settingsStatus().getJSONObject("auto_update")
        assertTrue(s.getBoolean("enabled"))
        assertTrue(s.isNull("last_result"))
        AutoUpdate.setEnabled(app, false)
        assertFalse(app.ownerFeatures.settingsStatus().getJSONObject("auto_update").getBoolean("enabled"))
        AutoUpdate.setEnabled(app, true)
    }

    @Test fun debugBuildPassStopsBeforeNetwork() = runBlocking {
        // Unit tests run the debug build, which never self-updates.
        assertEquals(AutoUpdate.Result.UNSUPPORTED, AutoUpdate.run(app, "test", force = true))
        assertEquals(AutoUpdate.Result.UNSUPPORTED, AutoUpdate.status(app).getString("last_result"))
    }

    @Test fun validation() {
        OwnerActions.validate(o("""{"action":"updates.auto","options":{"enabled":false}}"""))
        OwnerActions.validate(o("""{"action":"updates.update_now","options":{}}"""))
        for (bad in listOf("""{"action":"updates.auto","options":{}}""", """{"action":"updates.auto","options":{"enabled":"no"}}""",
                """{"action":"updates.auto","options":{"enabled":true,"url":"x"}}""", """{"action":"updates.update_now","options":{"url":"https://x"}}""")) {
            try { OwnerActions.validate(o(bad)); fail(bad) } catch (e: ApiError) { assertEquals("invalid_parameter", e.code) }
        }
        assertTrue("updates.auto" in ConfigurationResources.ACTIONS)
        // Installing now keeps the owner's approval.
        assertFalse("updates.update_now" in ConfigurationResources.ACTIONS)
    }
}
