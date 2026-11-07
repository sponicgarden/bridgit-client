package cc.bridgit.app.lancamera

import org.junit.Assert.*
import org.junit.Test

class DeviceInventoryTest {
    private val camera = LanCamera("lan:dog", "Dog", "192.168.1.85", "secret-user", "secret-password", relativePtz = true)
    @Test fun listsHostAndExternalActionsWithoutClaimingAnOfflineProbeOrLeakingSecrets() {
        val out = DeviceInventory.json("Android host", listOf(camera), true, true)
        val devices = out.getJSONArray("devices")
        assertEquals(2, devices.length())
        assertEquals("host", devices.getJSONObject(0).getString("id"))
        val external = devices.getJSONObject(1)
        assertEquals("not_checked", external.getString("availability"))
        val move = external.getJSONObject("actions").getJSONObject("pan_tilt")
        assertEquals("/v1/lan-cameras/move", move.getString("path"))
        assertEquals(camera.id, move.getJSONObject("body").getString("camera"))
        assertFalse(out.toString().contains("secret-user")); assertFalse(out.toString().contains("secret-password"))
        assertTrue(external.getJSONObject("actions").getJSONObject("listen").getBoolean("implemented"))
        assertFalse(external.getJSONObject("actions").getJSONObject("listen").getBoolean("enabled"))
    }
    @Test fun respectsCameraInventoryGrantAndOwnerSwitch() {
        assertEquals(1, DeviceInventory.json("Host", listOf(camera), false, true).getJSONArray("devices").length())
        val c = DeviceInventory.json("Host", listOf(camera), true, false).getJSONArray("devices").getJSONObject(1)
        assertFalse(c.getBoolean("enabled"))
        assertFalse(c.getJSONObject("actions").getJSONObject("snapshot").getBoolean("enabled"))
    }
}
