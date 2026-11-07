package cc.bridgit.app.lan

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiscoveryHistoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun device(host: String) = NetworkDevices.Device(host, "Example speaker", listOf("_linkplay._tcp."), false)
    @Test fun missingDevicesRemainAndReappearWithoutDuplicates() {
        val history = DiscoveryHistory(context)
        val time = 1_000_000L
        history.update("test-lan", listOf(device("192.168.1.20"), device("192.168.1.21")), true, time)
        val next = history.update("test-lan", listOf(device("192.168.1.21")), true, time+1000)
        assertEquals(2, next.size)
        assertFalse(next.single { it.host.endsWith("20") }.connected)
        assertEquals(time, next.single { it.host.endsWith("20") }.lastSeen)
        assertTrue(DiscoveryHistory(context).read("test-lan",time+2000).single { it.host.endsWith("21") }.connected)
        val back = history.update("test-lan", listOf(device("192.168.1.20")), true, time+3000)
        assertEquals(2, back.size)
        assertTrue(back.single { it.host.endsWith("20") }.connected)
        assertTrue(history.read("another-network",time+4000).isEmpty())
    }
    @Test fun failedCoveragePreservesRecentStateButFreshnessExpires() {
        val history = DiscoveryHistory(context)
        history.update("failed-lan",listOf(device("192.168.1.20")),true,1000)
        assertTrue(history.update("failed-lan",emptyList(),false,2000).single().connected)
        assertFalse(history.read("failed-lan",1000+DiscoveryHistory.FRESH_MILLIS+1).single().connected)
    }
    @Test fun singleDeviceRecheckPreservesOtherDevicesAndPersistsTimestamp() {
        val history = DiscoveryHistory(context)
        history.update("recheck", listOf(device("192.168.1.20"), device("192.168.1.21")), true, 1000)
        history.update("recheck", listOf(device("192.168.1.20")), false, 301000)
        val saved = DiscoveryHistory(context).read("recheck", 301001)
        assertEquals(301000L, saved.single { it.host.endsWith("20") }.lastSeen)
        assertEquals(1000L, saved.single { it.host.endsWith("21") }.lastSeen)
        assertTrue(saved.single { it.host.endsWith("20") }.connected)
        assertFalse(saved.single { it.host.endsWith("21") }.connected)
        val response = DiscoveryResult.enrich(context,
            org.json.JSONObject().put("observations", org.json.JSONArray()), saved)
        val rows = response.getJSONArray("devices")
        val checked = (0 until rows.length()).map(rows::getJSONObject)
            .single { it.getString("host") == "192.168.1.20" }
        assertEquals(301000L, checked.getLong("last_seen"))
    }
    @Test fun onlyRecognizedIntegrationGetsInterfacedLabel() {
        val tapo = device("192.168.1.20").copy(name="Tapo C210",services=listOf("tapo_camera"),cameraCandidate=true)
        assertEquals("tapo_onvif",DeviceInterfaces.available(tapo)!!.adapter)
        assertNull(DeviceInterfaces.available(tapo.copy(services=listOf("tplink_smart_device"),cameraCandidate=false)))
        assertNull(DeviceInterfaces.available(tapo.copy(services=listOf("onvif_candidate"))))
        val result=DiscoveryResult.enrich(context,org.json.JSONObject().put("observations",org.json.JSONArray()),listOf(tapo))
        val row=result.getJSONArray("devices").getJSONObject(0)
        assertTrue(row.getBoolean("bridgit_interfaced"))
        assertTrue(row.getJSONObject("interface").getBoolean("setup_required"))
        assertFalse(row.getBoolean("enrolled"))
    }
    @Test fun connectedDevicesSortBeforeRememberedDevicesWithinCategory() {
        val offline = device("192.168.1.20").copy(name="A speaker",connected=false)
        val online = device("192.168.1.21").copy(name="Z speaker")
        assertEquals(online,NetworkDevicePresentation.groups(listOf(offline,online)).single().devices.first())
        val result=DiscoveryResult.enrich(context,org.json.JSONObject().put("observations",org.json.JSONArray()),listOf(offline,online))
        assertEquals(1,result.getInt("connected_devices"))
        assertEquals(1,result.getJSONArray("categories").getJSONObject(0).getInt("unconnected_count"))
    }
}
