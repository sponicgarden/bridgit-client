package cc.bridgit.app.applets

import android.content.Context
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.lancamera.CameraMotion
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppletDeviceStopsTest {
    @Test fun missingDeviceStaysPendingAndKeepsItsLeaseAcrossRestore() {
        val app = RuntimeEnvironment.getApplication() as BridgeApp
        val token = "unit-pending-stop"
        val camera = "lan:unit-pending-stop"
        val prefs = app.getSharedPreferences("applet_device_stops", Context.MODE_PRIVATE)
        prefs.edit().putString(token, JSONObject().put("owner", "monitor")
            .put("camera", camera).put("profile", "test").put("enrollment", "test").toString()).commit()
        try {
            AppletDeviceStops.restore(app)
            AppletDeviceStops.restore(app)
            AppletDeviceStops.stopOwner(app, "other-applet")
            assertTrue(prefs.contains(token))
            assertThrows(IllegalStateException::class.java) { AppletDeviceStops.stopOwner(app, "monitor") }
            CameraMotion.release(token)
            assertTrue(CameraMotion.owns(camera, token))
            assertFalse(CameraMotion.acquire(listOf(camera), "other-task"))
            assertTrue(prefs.contains(token))
        } finally {
            prefs.edit().remove(token).commit()
            // Only the test-owned fake record/lease is removed; no device operation is sent.
            val field = AppletDeviceStops::class.java.getDeclaredField("retained").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(null) as MutableSet<String>).remove(token)
            CameraMotion.release(token)
        }
    }
}
