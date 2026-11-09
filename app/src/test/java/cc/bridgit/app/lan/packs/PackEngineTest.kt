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

    /** Report A00102: the replies a WiiM Sound Lite (Linkplay firmware) actually gave on 8 Nov 2026 (track metadata shortened). */
    @Test fun enrollsTheRealWiimSoundReplies() {
        val description = requireNotNull(javaClass.getResourceAsStream("/upnp/wiim-sound-description.xml")).readBytes()
        val envelope = { action: String, ns: String, fields: String -> PackEngine.Reply(200, ("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>\r\n<u:${action}Response xmlns:u=\"$ns\">\r\n$fields</u:${action}Response>\r\n</s:Body> </s:Envelope>").toByteArray()) }
        val net = FakeNet("http://192.168.1.64:49152/description.xml") { url, action, _ -> when {
            url.endsWith("/description.xml") -> PackEngine.Reply(200, description)
            action.endsWith("#GetTransportInfo\"") -> envelope("GetTransportInfo", av,
                "<CurrentTransportState>STOPPED</CurrentTransportState>\r\n<CurrentTransportStatus>OK</CurrentTransportStatus>\r\n<CurrentSpeed>1</CurrentSpeed>\r\n")
            action.endsWith("#GetVolume\"") -> envelope("GetVolume", rc, "<CurrentVolume>41</CurrentVolume>\r\n")
            action.endsWith("#GetMute\"") -> envelope("GetMute", rc, "<CurrentMute>0</CurrentMute>\r\n")
            action.endsWith("#GetPositionInfo\"") -> envelope("GetPositionInfo", av, "<Track>8</Track>\r\n<TrackDuration>00:05:40</TrackDuration>\r\n" +
                "<TrackMetaData>&lt;?xml version=&quot;1.0&quot;?&gt;\r\n&lt;DIDL-Lite xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot;&gt;&lt;/DIDL-Lite&gt;\r\n</TrackMetaData>\r\n" +
                "<RelTime>00:05:40</RelTime>\r\n<AbsTime>NOT_IMPLEMENTED</AbsTime>\r\n<RelCount>2147483647</RelCount>\r\n<AbsCount>2147483647</AbsCount>\r\n")
            else -> PackEngine.Reply(500, ByteArray(0))
        } }
        val bound = PackEngine.bind(pack, "192.168.1.64", net)
        assertEquals("uuid:FF98F52B-8609-CA40-8B6F-FFD1FF98F52B", bound.identity)
        assertEquals("WiiM Sound Speaker", bound.model); assertEquals("Wiim Sponica", bound.name)
        assertEquals("http://192.168.1.64:49152/upnp/control/rendertransport1", bound.controls["av"])
        assertEquals("http://192.168.1.64:49152/upnp/control/rendercontrol1", bound.controls["rc"])
        val out = PackEngine.run(pack, bound, "status", JSONObject(), net)
        assertEquals("STOPPED", out.getString("state")); assertEquals(41L, out.getLong("volume")); assertFalse(out.getBoolean("muted"))
        assertEquals("00:05:40", out.getString("position")); assertEquals("00:05:40", out.getString("duration"))
    }

    @Test fun reportsUpnpFaults() {
        val net = FakeNet(null) { _, _, _ -> PackEngine.Reply(500, """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>701</errorCode><errorDescription>Transition not available</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>""".toByteArray()) }
        val e = assertThrows(PackEngine.PackError::class.java) { PackEngine.run(pack, bound, "pause", JSONObject(), net) }
        assertTrue(e.device); assertTrue(e.message!!, e.message!!.contains("701") && e.message!!.contains("Transition not available"))
    }

    /** Report A0212: parameters sent beside device and action (the guide's names) used to be dropped. */
    @Test fun takesParamsAtTheTopLevelOrNested() {
        fun gather(action: String, json: String) = PackEngine.gather(pack.action(action), JSONObject(json))
        assertEquals(25, gather("volume_set", """{"device":"net:x","action":"volume_set","level":25}""").getInt("level"))
        assertTrue(gather("mute_set", """{"device":"net:x","action":"mute_set","muted":true}""").getBoolean("muted"))
        assertEquals("https://example.com/a.mp3", gather("play", """{"device":"net:x","action":"play","url":"https://example.com/a.mp3"}""").getString("url"))
        assertEquals(30, gather("volume_set", """{"device":"net:x","action":"volume_set","params":{"level":30}}""").getInt("level"))
        // Agreeing duplicates are fine; disagreeing ones are refused, not guessed.
        assertEquals(30, gather("volume_set", """{"action":"volume_set","level":30,"params":{"level":30}}""").getInt("level"))
        assertThrows(PackEngine.PackError::class.java) { gather("volume_set", """{"action":"volume_set","level":30,"params":{"level":40}}""") }
        assertThrows(PackEngine.PackError::class.java) { gather("volume_set", """{"action":"volume_set","params":[1]}""") }
        // Only the action's own parameters come from the top level; device, action, channel and others stay out.
        val g = gather("volume_set", """{"device":"net:x","action":"volume_set","channel":0,"level":20,"note":"x"}""")
        assertEquals(setOf("level"), g.keys().asSequence().toSet())
        assertEquals(0, gather("stop", """{"device":"net:x","action":"stop","level":20}""").length())
        assertEquals(0, PackEngine.gather(null, JSONObject("""{"device":"net:x","action":"relay_on","level":3}""")).length())
        assertEquals(0, gather("status", """{"device":"net:x","action":"status"}""").length())
        // The gathered values reach the device.
        val ok = FakeNet(null) { _, a, _ -> soapReply(a.substringAfter('#').trim('"'), "") }
        PackEngine.run(pack, bound, "volume_set", gather("volume_set", """{"device":"net:x","action":"volume_set","level":25}"""), ok)
        assertTrue(ok.calls.single().third.contains("<DesiredVolume>25</DesiredVolume>"))
    }

    /** Report A0212: GET /v1/lan/devices lists each action's parameters and an example body that runs. */
    @Test fun inventoryDocumentsEachActionsParams() {
        val d = cc.bridgit.app.lan.LanDevice("net:x", "Kitchen", "192.168.1.64", "upnp_av", "agent-a", "", "", false, 1,
            extra = JSONObject().put("pack_version", pack.version))
        val actions = d.publicJson(pack).getJSONArray("actions")
        val byName = (0 until actions.length()).associate { actions.getJSONObject(it).let { a -> a.getString("action") to a } }
        assertEquals(pack.actions.map { it.name }.toSet(), byName.keys)
        val level = byName.getValue("volume_set").getJSONArray("params").getJSONObject(0)
        assertEquals("level", level.getString("name")); assertEquals("integer", level.getString("type")); assertTrue(level.getBoolean("required"))
        assertEquals(0, level.getInt("min")); assertEquals(100, level.getInt("max"))
        assertEquals(0, byName.getValue("status").getJSONArray("params").length())
        assertFalse(byName.getValue("status").getJSONObject("body").has("params"))
        assertEquals("boolean", byName.getValue("mute_set").getJSONArray("params").getJSONObject(0).getString("type"))
        // Every advertised body passes the engine's own validation.
        val net = FakeNet(null) { _, a, _ -> soapReply(a.substringAfter('#').trim('"'), "") }
        for ((name, a) in byName) if (name != "status") PackEngine.run(pack, bound, name, PackEngine.gather(pack.action(name), a.getJSONObject("body")), net)
    }
}
