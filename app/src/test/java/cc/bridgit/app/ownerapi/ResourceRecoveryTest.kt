package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** No Application.onCreate, network, download or sensor operation is run by these recovery checks. */
@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,sdk=[34])
class ResourceRecoveryTest {
    @Test fun restartRepairsQueuedRunningAndCancellingAndPreservesOwnerIsolation() {
        val app=BridgeApp()
        android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext",android.content.Context::class.java).apply { isAccessible=true }
            .invoke(app,RuntimeEnvironment.getApplication())
        val prefs=app.getSharedPreferences("resource_jobs",0)
        prefs.edit().clear().commit()
        for((index,status) in listOf("queued","running","cancelling","completed").withIndex()) {
            prefs.edit().putString("j$index",JSONObject().put("id","j$index").put("agent_id",if(index==3) "other" else "owner")
                .put("resource","wake-english").put("status",status).put("created_at",index).toString()).commit()
        }
        val resources=ResourceDownloads(app)
        for(i in 0..2) {
            val job=resources.job("owner","j$i")
            assertEquals("interrupted",job.getString("status"))
            assertEquals("process_restarted",job.getString("error"))
        }
        assertEquals("completed",resources.job("other","j3").getString("status"))
        assertEquals(3,resources.jobs("owner").getJSONArray("jobs").length())
        try { resources.job("owner","j3"); fail("Cross-agent job was readable") } catch(e:ApiError) { assertEquals("not_found",e.code) }
    }
}
