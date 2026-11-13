package cc.bridgit.app.clientcore

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiDocs
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.core.Connection
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/**
 * GET /v1/device/profile recorded from the Android code through the real dispatch, scrubbed of what changes per run
 * (times, version, ids), and kept in the pack as profile/android-recorded.json, which the pack validates against
 * schemas/device-profile.schema.json. After an intended change: tools/refresh-learn.sh rewrites it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProfileRecordingTest {
    @Test fun recordedProfileMatchesThePack() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as BridgeApp
        app.settings.listenerEnabled = true
        app.settings.emailVerifiedAt = System.currentTimeMillis()
        val agent = app.agents.add("Fixture agent", Connection.DIRECT).first.let { a -> app.agents.update(a.id) { it.copy(caps = setOf("status", "camera", "log")) }!! }
        val svc = Robolectric.buildService(BridgeService::class.java).create().get()
        val api = ApiServer(svc, emptyList(), 0).also { it.prepareRelay() }
        val out = try { api.dispatch(ApiDocs.find("GET /v1/device/profile")!!, agent, "100.64.0.2", emptyMap(), emptyMap(), JSONObject(), null) }
            finally { api.stop(); svc.onDestroy() }
        assertEquals(200, out.status)
        val p = JSONObject(String(out.bytes)).apply { remove("request_id"); remove("report_problems") }
        p.put("profile_revision", 1).put("observed_at", "2026-11-11T08:00:00Z").put("expires_at", "2026-11-11T08:01:00Z")
        p.getJSONObject("device").put("install_id", JSONObject.NULL).put("relay_device_id", JSONObject.NULL).put("app_version", "1.8.0")
            .put("api_revision", 0).put("os_version", "14")
        val text = p.toString(2) + "\n"
        val pack = listOf(File("../contracts/client-core/profile"), File("contracts/client-core/profile")).firstOrNull { it.isDirectory }
        if (System.getProperty("bridgit.learn.refresh") == "1" && pack != null) { File(pack, "android-recorded.json").writeText(text); return@runBlocking }
        val want = javaClass.getResource("/client-core/profile/android-recorded.json")?.readText()
            ?: error("No recorded profile: run tools/refresh-learn.sh")
        assertEquals("Run tools/refresh-learn.sh after an intended profile change", JSONObject(want).toString(2), JSONObject(text).toString(2))
    }
}
