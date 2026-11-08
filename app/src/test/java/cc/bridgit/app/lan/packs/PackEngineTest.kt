package cc.bridgit.app.lan.packs

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PackEngineTest {
    private val av = "urn:schemas-upnp-org:service:AVTransport:1"
    private val rc = "urn:schemas-upnp-org:service:RenderingControl:1"

    /** A WiiM-style description: one MediaRenderer root with relative control URLs. */
    private val flat = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Kitchen</friendlyName>
        <modelName>WiiM Sound</modelName><UDN>uuid:aaaa-1111</UDN><serviceList>
        <service><serviceType>$av</serviceType><controlURL>/upnp/control/rendertransport1</controlURL></service>
        <service><serviceType>$rc</serviceType><controlURL>/upnp/control/rendercontrol1</controlURL></service>
        </serviceList></device></root>""".toByteArray()

    /** A Denon-style description: the renderer is a sub-device; the root has other services. */
    private val nested = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
        <deviceType>urn:schemas-denon-com:device:AiosDevice:1</deviceType><UDN>uuid:root-0000</UDN><deviceList>
        <device><deviceType>urn:schemas-denon-com:device:AiosServices:1</deviceType><UDN>uuid:svc-0000</UDN><serviceList>
          <service><serviceType>$av</serviceType><controlURL>/wrong/AVTransport</controlURL></service></serviceList></device>
        <device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Living room</friendlyName>
          <modelName>Denon Home 350</modelName><UDN>uuid:bbbb-2222</UDN><serviceList>
          <service><serviceType>$av</serviceType><controlURL>upnp/control/renderer_dvc/AVTransport</controlURL></service>
          <service><serviceType>$rc</serviceType><controlURL>http://192.168.1.98:60006/upnp/control/renderer_dvc/RenderingControl</controlURL></service>
          </serviceList></device></deviceList></device></root>""".toByteArray()

    private val pack: DevicePack.Pack by lazy {
        val f = File("../tools/device-packs/upnp_av/1.0.0.json")
        assumeTrue("pack source not in this checkout", f.isFile)
        DevicePack.parsePack(f.readText())
    }

    private class FakeNet(val location: String?, val reply: (String, String, String) -> PackEngine.Reply) : PackEngine.Net {
        val calls = mutableListOf<Triple<String, Map<String, String>, String>>()
        override fun locate(host: String, st: String) = location
        override fun http(url: String, headers: Map<String, String>, body: ByteArray?): PackEngine.Reply {
            val b = body?.toString(Charsets.UTF_8).orEmpty(); calls += Triple(url, headers, b)
            return reply(url, headers["SOAPACTION"].orEmpty(), b)
        }
    }

    private fun soapReply(action: String, fields: String) = PackEngine.Reply(200,
        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response xmlns:u="x">$fields</u:${action}Response></s:Body></s:Envelope>""".toByteArray())

    @Test fun describesFlatAndNestedRenderers() {
        val w = PackEngine.describe(pack, "192.168.1.64", "http://192.168.1.64:49152/description.xml", flat)
        assertEquals("http://192.168.1.64:49152/upnp/control/rendertransport1", w.controls["av"])
        assertEquals("http://192.168.1.64:49152/upnp/control/rendercontrol1", w.controls["rc"])
        assertEquals("uuid:aaaa-1111", w.identity); assertEquals("WiiM Sound", w.model)
        val d = PackEngine.describe(pack, "192.168.1.98", "http://192.168.1.98:60006/upnp/desc/aios_device/aios_device.xml", nested)
        assertEquals("http://192.168.1.98:60006/upnp/desc/aios_device/upnp/control/renderer_dvc/AVTransport", d.controls["av"])
        assertEquals("http://192.168.1.98:60006/upnp/control/renderer_dvc/RenderingControl", d.controls["rc"])
        assertEquals("uuid:bbbb-2222", d.identity)
        assertEquals(d, PackEngine.Bound.read(d.json()))
    }

    @Test fun neverTalksToAnythingButTheEnrolledHost() {
        assertThrows(PackEngine.PackError::class.java) { PackEngine.describe(pack, "192.168.1.99", "http://192.168.1.98:60006/d.xml", nested) }
        for (bad in listOf("http://192.168.1.65:49152/x", "https://192.168.1.64:49152/x", "http://192.168.1.64:22/x", "http://u:p@192.168.1.64:8080/x", "http://192.168.1.64:8080"))
            assertThrows(bad, PackEngine.PackError::class.java) { PackEngine.deviceUrl(bad, "192.168.1.64") }
        assertEquals("http://192.168.1.64/x", PackEngine.deviceUrl("http://192.168.1.64/x", "192.168.1.64"))
        val elsewhere = String(flat).replace("/upnp/control/rendercontrol1", "http://203.0.113.9:8080/ctl").toByteArray()
        assertThrows(PackEngine.PackError::class.java) { PackEngine.describe(pack, "192.168.1.64", "http://192.168.1.64:49152/d.xml", elsewhere) }
        val net = FakeNet("http://192.168.1.70:49152/description.xml") { _, _, _ -> error("must not fetch") }
        assertThrows(PackEngine.PackError::class.java) { PackEngine.bind(pack, "192.168.1.64", net) }
        assertTrue(net.calls.isEmpty())
    }

    @Test fun refusesDoctypeAndNonRenderer() {
        val evil = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><root>&x;</root>".toByteArray()
        assertThrows(PackEngine.PackError::class.java) { PackEngine.parse(evil) }
        val notRenderer = String(flat).replace("device:MediaRenderer", "device:MediaServer").toByteArray()
        assertThrows(PackEngine.PackError::class.java) { PackEngine.describe(pack, "192.168.1.64", "http://192.168.1.64:49152/d.xml", notRenderer) }
    }

    private val bound get() = PackEngine.describe(pack, "192.168.1.64", "http://192.168.1.64:49152/description.xml", flat)

    @Test fun statusReadsTypedFieldsAndSkipsOptionalFailures() {
        val net = FakeNet(null) { _, action, _ -> when {
            action.endsWith("#GetTransportInfo\"") -> soapReply("GetTransportInfo", "<CurrentTransportState>PLAYING</CurrentTransportState>")
            action.endsWith("#GetVolume\"") -> soapReply("GetVolume", "<CurrentVolume>18</CurrentVolume>")
            action.endsWith("#GetMute\"") -> soapReply("GetMute", "<CurrentMute>0</CurrentMute>")
            else -> PackEngine.Reply(500, ByteArray(0))
        } }
        val out = PackEngine.run(pack, bound, "status", JSONObject(), net)
        assertEquals("PLAYING", out.getString("state")); assertEquals(18L, out.getLong("volume")); assertFalse(out.getBoolean("muted"))
        assertFalse(out.has("position"))
        assertEquals("\"$av#GetTransportInfo\"", net.calls[0].second["SOAPACTION"])
        assertEquals("http://192.168.1.64:49152/upnp/control/rendercontrol1", net.calls[1].first)
    }

    @Test fun playEscapesTheUrlOnceAndTheMetadataTwice() {
        val net = FakeNet(null) { _, action, _ -> soapReply(action.substringAfter('#').trim('"'), "") }
        PackEngine.run(pack, bound, "play", JSONObject().put("url", "https://example.com/a.mp3?x=1&y=it's").put("title", "Door & bell"), net)
        val set = net.calls[0].third
        assertTrue(set, set.contains("<CurrentURI>https://example.com/a.mp3?x=1&amp;y=it&apos;s</CurrentURI>"))
        assertTrue(set, set.contains("&lt;dc:title&gt;Door &amp;amp; bell&lt;/dc:title&gt;"))
        assertTrue(set, set.contains("a.mp3?x=1&amp;amp;y=it&amp;apos;s&lt;/res&gt;"))
        assertTrue(net.calls[1].second["SOAPACTION"]!!.endsWith("#Play\""))
        assertTrue(net.calls[1].third.contains("<Speed>1</Speed>"))
    }

    @Test fun validatesParamsBeforeAnyRequest() {
        val net = FakeNet(null) { _, _, _ -> error("no request expected") }
        fun bad(action: String, p: JSONObject) = assertThrows(PackEngine.PackError::class.java) { PackEngine.run(pack, bound, action, p, net) }.also { assertFalse(it.device) }
        bad("volume_set", JSONObject().put("level", 101)); bad("volume_set", JSONObject().put("level", 2.5)); bad("volume_set", JSONObject())
        bad("volume_set", JSONObject().put("level", 5).put("channel", "LF"))
        bad("play", JSONObject().put("url", "file:///sdcard/a.mp3")); bad("play", JSONObject().put("url", "ftp://x/a.mp3"))
        bad("play", JSONObject().put("url", "http://user:pw@x/a.mp3")); bad("play", JSONObject().put("url", "http://x/a b.mp3"))
        bad("mute_set", JSONObject().put("muted", "yes")); bad("reboot", JSONObject())
        assertTrue(net.calls.isEmpty())
        val ok = FakeNet(null) { _, a, _ -> soapReply(a.substringAfter('#').trim('"'), "") }
        PackEngine.run(pack, bound, "volume_set", JSONObject().put("level", 30), ok)
        assertTrue(ok.calls.single().third.contains("<Channel>Master</Channel><DesiredVolume>30</DesiredVolume>"))
    }

    @Test fun reportsUpnpFaults() {
        val net = FakeNet(null) { _, _, _ -> PackEngine.Reply(500, """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>701</errorCode><errorDescription>Transition not available</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>""".toByteArray()) }
        val e = assertThrows(PackEngine.PackError::class.java) { PackEngine.run(pack, bound, "pause", JSONObject(), net) }
        assertTrue(e.device); assertTrue(e.message!!, e.message!!.contains("701") && e.message!!.contains("Transition not available"))
    }
}
