package cc.bridgit.app.lancamera

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The Devices map for another phone (a satellite switched to its hub): its devices, named, read only. */
class RemoteDevicesMapTest {
    private val payload = JSONObject("""{
      "devices":[
        {"id":"192.168.1.5","name":"Living room TV","host":"192.168.1.5","category":"media","type":"tv","connected":true},
        {"id":"192.168.1.9","name":"Old printer","host":"192.168.1.9","category":"printers","connected":false,"last_seen":1000},
        {"id":"192.168.1.20","name":"Dog cam","host":"192.168.1.20","category":"cameras","connected":true},
        {"id":"192.168.1.5","name":"Duplicate","host":"192.168.1.5","category":"media","connected":true}],
      "added":[{"id":"lan:dog","name":"Dog cam","host":"192.168.1.20","kind":"camera","state":"on"},
               {"id":"net:lamp","name":"Desk lamp","host":"192.168.1.30","kind":"smart","state":"blocked"}]}""")

    @Test fun namesTheShownPhoneInTheMiddle() {
        assertEquals("A9 Cream Tab", DevicesScene.remoteOptions(payload, "A9 Cream Tab").getString("core"))
        assertFalse(DevicesScene.remoteOptions(payload, "").has("core"))
    }

    @Test fun addedDevicesComeFirstAndAreNotListedTwice() {
        val devs = DevicesScene.remoteOptions(payload, "Hub").getJSONArray("devices")
        val ids = (0 until devs.length()).map { devs.getJSONObject(it).getString("id") }
        assertEquals(listOf("lan:dog", "net:lamp"), ids.take(2))
        assertEquals(1, ids.count { it == "seen:192.168.1.5" }) // the duplicate id is dropped
        assertFalse("seen:192.168.1.20" in ids) // already added, so not repeated as seen
        assertEquals("blocked", devs.getJSONObject(1).getString("state"))
    }

    @Test fun groupsAndCountsFollowThePayload() {
        val o = DevicesScene.remoteOptions(payload, "Hub")
        assertEquals(4, o.getJSONObject("counts").getInt("total"))
        assertEquals(2, o.getJSONObject("counts").getInt("added"))
        val rows = DevicesScene.remoteListRows(payload).toMap()
        assertTrue(rows.getValue("Cameras").single().startsWith("Dog cam"))
        assertTrue(rows.getValue("Printers").single().startsWith("Old printer"))
    }

    @Test fun anEmptyOrOddPayloadDrawsAnEmptyMap() {
        val o = DevicesScene.remoteOptions(JSONObject(), "Hub")
        assertEquals(0, o.getJSONArray("devices").length())
        assertEquals("Hub", o.getString("core"))
        assertTrue(DevicesScene.remoteListRows(JSONObject("""{"devices":[{"name":"no id"}]}""")).isEmpty())
    }

    @Test fun theReturnLinkMayReadThePhonesDevices() {
        assertTrue("/portal/devices" in cc.bridgit.app.satellite.SatellitePolicy.RETURN_PATHS)
    }
}
