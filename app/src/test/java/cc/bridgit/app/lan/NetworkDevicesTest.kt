package cc.bridgit.app.lan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NetworkDevicesTest {
    private fun observation(host: String, service: String, name: String = "", source: String = "mdns") =
        JSONObject().put("host", host).put("service", service).put("name", name).put("source", source)

    @Test fun multipleAdvertisementsBecomeOneDeviceWithoutImplyingControl() {
        val input = JSONArray().put(observation("192.168.1.20", "_googlecast._tcp.", "Living room"))
            .put(observation("192.168.1.20", "_http._tcp.", "Living room"))
            .put(observation("192.168.1.20", "_http._tcp.", "Living room"))
        val devices = NetworkDevices.devices(input)
        assertEquals(1, devices.size)
        assertEquals("Living room", devices.single().name)
        assertEquals(2, devices.single().services.size)
        assertFalse(devices.single().cameraCandidate)
        assertFalse(input.getJSONObject(0).has("enrolled"))
    }
    @Test fun friendlyNamesTakePriorityOverAdvertisedIdentifiers() {
        val input = JSONArray().put(observation("192.168.1.20", "_spotify-connect._tcp.", "ABCDEF0123456789ABCDEF12"))
            .put(observation("192.168.1.20", "_googlecast._tcp.", "Kitchen speaker"))
        assertEquals("Kitchen speaker", NetworkDevices.devices(input).single().name)
        val speaker = JSONArray().put(observation("192.168.1.21", "_spotify-connect._tcp.", "AABBCCDDEEFFDenon Home 350"))
            .put(observation("192.168.1.21", "_airplay._tcp.", "Denon Home 350"))
        assertEquals("Denon Home 350", NetworkDevices.devices(speaker).single().name)
    }
    @Test fun anonymousCameraCanProceedToSeparateSetup() {
        val camera = NetworkDevices.devices(JSONArray().put(observation("10.0.0.20", "onvif_candidate", source = "onvif"))).single()
        assertEquals("10.0.0.20", camera.name)
        assertTrue(camera.cameraCandidate)
        assertTrue(NetworkDevices.devices(JSONArray().put(observation("10.0.0.21", "_rtsp._tcp."))).single().cameraCandidate)
    }
    @Test fun untrustedNamesAreBoundedAndCannotInjectControlOrBidiCharacters() {
        assertEquals("Living room", NetworkDevices.cleanName("Living\n\u202e room\u0000"))
        assertEquals("Kitchen speaker", NetworkDevices.cleanName("A1B2C3D4E5F6@Kitchen speaker"))
        assertEquals("AirPlay", NetworkDevices.serviceLabel("_airplay._tcp."))
        assertEquals(100, NetworkDevices.cleanName("x".repeat(500)).length)
        val input = JSONArray().put(observation("8.8.8.8", "_http._tcp.", "Public"))
            .put(observation("100.83.104.121", "_http._tcp.", "VPN"))
            .put(observation("localhost", "_http._tcp."))
        assertTrue(NetworkDevices.devices(input).isEmpty())
    }
    @Test fun inputAndServicesStayBounded() {
        val input = JSONArray()
        repeat(300) { input.put(observation("10.0.${it / 250}.${it % 250 + 1}", "_http._tcp.")) }
        assertEquals(256, NetworkDevices.devices(input).size)
        assertTrue(LanDiscovery.SERVICE_TYPES.containsAll(listOf("_googlecast._tcp.", "_spotify-connect._tcp.", "_airplay._tcp.", "_rtsp._tcp.")))
    }
}
