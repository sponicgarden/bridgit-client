package cc.bridgit.app.applets

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InstalledAppletStoreTest {
    @Test fun importsOnceAndMaintainsIndexedIdentityAcrossReplacementAndRemoval() {
        val context = RuntimeEnvironment.getApplication()
        val legacy = context.getSharedPreferences("applet_instances",0)
        val journal = "家".repeat(750000)
        val record = JSONObject().put("id","first").put("installed_at",1).put("revision",37)
            .put("native","dog").put("package_id","dog-monitor").put("agent_id","old").put("journal",journal)
        val newer = JSONObject().put("id","second").put("installed_at",2).put("package_id","dog-monitor")
        legacy.edit().putString("first",record.toString()).putString("second",newer.toString()).commit()
        InstalledAppletStore(context).use { store ->
            store.open()
            assertTrue(legacy.all.isEmpty())
            assertEquals(journal,store.byBudget("dog")!!.getString("journal"))
            val first = store.byPackage("dog-monitor")!!
            assertEquals(37,first.getInt("revision"))
            first.remove("native")
            store.save(first.put("agent_id","replaced").put("converted_from_dog",true))
            assertNull(store.forAgent("old"))
        }
        // Simulate a process dying between the database commit and cleanup of the old preferences.
        legacy.edit().putString("first",record.toString()).commit()
        InstalledAppletStore(context).use { store ->
            store.open()
            assertEquals(38,store.forAgent("replaced")!!.getInt("revision"))
            assertEquals("first",store.byBudget("dog")!!.getString("id"))
            assertFalse(legacy.contains("first"))
            store.remove("first")
            assertNull(store.byBudget("dog"))
            assertEquals("second",store.byPackage("dog-monitor")!!.getString("id"))
        }
    }
}
