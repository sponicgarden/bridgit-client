package cc.bridgit.app.lan

import cc.bridgit.app.lancamera.DevicesScene
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Devices tab (owner, 4 Nov 2026): what is added to Bridgit comes first, then what Bridgit can link to. */
class DeviceLinkOrderTest {
    private fun device(host: String, name: String, services: List<String> = emptyList(), camera: Boolean = false) =
        NetworkDevices.Device(host, name, services, camera)

    @Test fun addedThenLinkableThenTheRest() {
        val plain = device("192.168.1.20", "Tapo C210", listOf("_rtsp._tcp"), camera = true)
        val linkable = device("192.168.1.21", "Tapo C210", listOf("tapo_camera"), camera = true)
        val added = device("192.168.1.22", "Tapo C210", listOf("_rtsp._tcp"), camera = true)
        val group = NetworkDevicePresentation.groups(listOf(plain, linkable, added), setOf(added.host)).single()
        assertEquals(listOf(added.host, linkable.host, plain.host), group.devices.map { it.host })
        assertEquals(0, NetworkDevicePresentation.linkRank(added, setOf(added.host)))
        assertEquals(1, NetworkDevicePresentation.linkRank(linkable, emptySet()))
        assertEquals(2, NetworkDevicePresentation.linkRank(plain, emptySet()))
    }

    @Test fun loggedRequestsFindTheirDeviceOnTheMap() {
        assertEquals(listOf("lan:cam1"), DevicesScene.targets("/v1/lan-cameras/snapshot", JSONObject().put("camera", "lan:cam1")))
        assertEquals(listOf("lan:a", "lan:b"), DevicesScene.targets("/v1/lan-cameras/framing", JSONObject().put("cameras", JSONArray(listOf("lan:a", "lan:b")))))
        assertEquals(listOf("net:x"), DevicesScene.targets("/v1/lan/devices/action", JSONObject().put("device", "net:x")))
        assertEquals(emptyList<String>(), DevicesScene.targets("/v1/camera/snapshot", JSONObject().put("camera", "lan:cam1")))
    }
}
