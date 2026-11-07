package cc.bridgit.app.applets

import android.content.Intent
import cc.bridgit.app.BridgeApp
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppletJsonExportTest {
    // Robolectric changes cacheDir for each test; FileProvider caches roots across its sandboxes.
    @org.junit.Before @org.junit.After fun clearProviderRoots() {
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @Test fun exportsEveryTableAndOwnRecordsWithJsonShareGrant() {
        val app = RuntimeEnvironment.getApplication() as BridgeApp
        val definition = JSONObject("""{"processing":{"tables":[{"id":"items","columns":{"label":{"type":"text"}}},{"id":"extra","columns":{"n":{"type":"integer"}}}]}}""")
        val tables = AppletTables.parse(definition.getJSONObject("processing"))
        val store = AppletTableStore.of(app)
        store.add("export-agent", tables[0], JSONObject().put("label", "Chair"), JSONObject())
        store.add("export-agent", tables[1], JSONObject().put("n", 42), JSONObject())
        app.platform.store.save("export-agent", AppletData.KIND, JSONObject().put("id", AppletData.id("export-agent", "notes")).put("key", "notes").put("value", JSONObject().put("text", "All my data")))
        app.platform.store.save("other-agent", AppletData.KIND, JSONObject().put("id", AppletData.id("other-agent", "private")).put("key", "private").put("value", JSONObject().put("secret", true)))
        val ref = store.keepPhoto("export-agent", "camera", byteArrayOf(1, 2, 3))
        val portal = JSONObject().put("collections", JSONObject().put("records", org.json.JSONArray().put(JSONObject().put("kind", "workout"))))
        val built = AppletJsonExport.export(app, JSONObject().put("agent_id", "export-agent").put("name", "My applet").put("package_id", "sample").put("definition", definition), portal)
        val json = JSONObject(built.file.readText())
        assertEquals("Chair", json.getJSONObject("tables").getJSONArray("items").getJSONObject(0).getString("label"))
        assertEquals(42, json.getJSONObject("tables").getJSONArray("extra").getJSONObject(0).getInt("n"))
        assertEquals(1, json.getJSONArray("records").length())
        assertEquals("workout", json.getJSONObject("portal").getJSONObject("collections").getJSONArray("records").getJSONObject(0).getString("kind"))
        assertEquals(ref, json.getJSONArray("photos").getJSONObject(0).getString("ref"))
        assertEquals("AQID", json.getJSONArray("photos").getJSONObject(0).getString("base64"))
        assertFalse(built.file.readText().contains("other-agent"))
        val chooser = AppletExport.intent(app, built)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("application/json", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(send.clipData)
    }
}
