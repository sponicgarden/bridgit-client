package cc.bridgit.app.portal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The pairing link, the Home node table and design tokens read from the scene, and the log entry shape. */
class PortalDataTest {
    @Test fun `inventory requires exact approved registry pin grant and card source`() {
        val record = JSONObject("""{"agent_id":"applet-agent","status":"active","declared":["lan_control"],
            "source":{"kind":"template","template":"dashboard","revision":3},
            "definition":{"ui":{"card":{"sources":{"inventory":{"call":{"method":"GET","path":"/v1/lan/devices"}}}}}}}""")
        fun match() = PortalData.inventoryAgent(record, "dashboard", 3)
        assertEquals("applet-agent", match())
        assertNull(PortalData.inventoryAgent(record, "fork", 3))
        assertNull(PortalData.inventoryAgent(record, "dashboard", 4))
        record.put("status", "paused")
        assertNull(match())
        record.put("status", "active").getJSONObject("source").put("kind", "agent_inline")
        assertNull(match())
        record.getJSONObject("source").put("kind", "template")
        record.getJSONArray("declared").put(0, "portal")
        assertNull(match())
        record.getJSONArray("declared").put(0, "lan_control")
        record.getJSONObject("definition").getJSONObject("ui").getJSONObject("card").getJSONObject("sources")
            .getJSONObject("inventory").getJSONObject("call").put("path", "/v1/portal/devices")
        assertNull(match())
    }

    @Test fun `tally card projects latest twenty episodes and reports absence separately from zero`() {
        assertNull(PortalData.tallyForCard(JSONObject()))
        val tally = JSONObject("""{"today":{"checks":2,"watched_minutes":3.5,"labels":{"resting":{"episodes":1,"minutes":3.5,"debug":"hidden"}}},"episodes":[]}""")
        for (i in 1..23) tally.getJSONArray("episodes").put(JSONObject().put("start",i).put("label","resting")
            .put("minutes",2).put("unrelated","hidden"))
        val view = requireNotNull(PortalData.tallyForCard(tally))
        assertEquals(2,view.getJSONObject("today").getInt("checks"))
        val episodes = view.getJSONArray("episodes")
        assertEquals(20,episodes.length())
        assertEquals(4,episodes.getJSONObject(0).getInt("start"))
        assertFalse(view.toString().contains("hidden"))
    }

    private val scene = File("src/main/assets/diagnostics/scene.html").readText()

    @Test fun `pairing link has the documented shape and round-trips`() {
        val token = "rly_0f2b4c8e-1a2b-4c3d-9e8f-001122334455aa00bb11-cc22-dd33-ee44-ff5566778899"
        val link = PortalData.pairLink("https://bridgit-relay.bridgit-app.workers.dev", "dev_ABC-123_x", token, "Galaxy Tab A11 & co")
        assertEquals("bridgitportal://pair#v=1&relay=https%3A%2F%2Fbridgit-relay.bridgit-app.workers.dev&device=dev_ABC-123_x" +
            "&token=$token&name=Galaxy%20Tab%20A11%20%26%20co&portal=1", link)
        val withInstall = PortalData.pairLink("https://r.example", "d1234567", token, "A9", install = "bgi_abc", portal = true)
        assertTrue(withInstall.endsWith("&name=A9&install=bgi_abc&portal=1"))
        assertEquals("bgi_abc", PortalData.parsePairLink(withInstall)!!["install"])
        assertFalse(link.contains('+'))
        val f = PortalData.parsePairLink(link)!!
        assertEquals("1", f["v"]); assertEquals("https://bridgit-relay.bridgit-app.workers.dev", f["relay"])
        assertEquals("dev_ABC-123_x", f["device"]); assertEquals(token, f["token"]); assertEquals("Galaxy Tab A11 & co", f["name"])
        assertNull(PortalData.parsePairLink("https://example.com/#token=x"))
    }

    @Test fun `shared pairing message has a subject, the landing page and the link last`() {
        assertEquals("https://bridgit.cc/portalpairing/", PortalData.PAIRING_PAGE)
        val res = File("src/main/res/values/strings_portal.xml").readText()
        val text = Regex("<string name=\"portal_share_text\">(.*?)</string>").find(res)!!.groupValues[1].removeSurrounding("\"")
        assertTrue(text.contains("%1\$s") && text.contains("%2\$s"))
        // The link stays alone on the last line, so it's easy to copy and paste into either app.
        assertTrue(text.endsWith("\\n\\n%3\$s"))
        val subject = Regex("<string name=\"portal_share_subject\">([^<]*)</string>").find(res)!!.groupValues[1]
        assertTrue(subject.contains("%1\$s"))
    }

    @Test fun `relay is the origin without the device path`() {
        assertEquals("https://relay.example", PortalData.relayOrigin("https://relay.example/d/abcdefgh12", "abcdefgh12"))
    }

    @Test fun `portal allowlist is read-only and recognised`() {
        // Read-only, except the Network Diagnostics scan (owner's discovery switch, 20 s bound), the Portal camera's photos and the Portal's update reports.
        assertEquals(listOf("POST /v1/lan/discover", "POST /v1/portal/photo", "POST /v1/portal/updates"), PortalData.ROUTES.filterNot { it.startsWith("GET ") })
        // Pairings from 1.8.1731/1742 (portal GETs only) are recognised for the in-place upgrade.
        val old = PortalData.ROUTES.filter { it.startsWith("GET ") }.toSet()
        assertTrue(PortalData.isLegacyPortalConfig(old, emptySet()))
        assertFalse(PortalData.isLegacyPortalConfig(old + "POST /v1/camera/snapshot", emptySet()))
        assertTrue(PortalData.isPortalConfig(PortalData.ROUTES.toSet(), emptySet()))
        assertFalse(PortalData.isPortalConfig(PortalData.ROUTES.toSet() + "POST /v1/camera/snapshot", emptySet()))
        assertFalse(PortalData.isPortalConfig(PortalData.ROUTES.toSet(), setOf("tap")))
        assertFalse("camera" in PortalData.GRANTS || "mic" in PortalData.GRANTS || "adb" in PortalData.GRANTS)
    }

    @Test fun `Home nodes come from the real scene with its groups and wording`() {
        assertTrue(PortalData.sceneNodes(scene, includeAgent = true).any { it.id == "agent" })
        val nodes = PortalData.sceneNodes(scene)
        val ids = nodes.map { it.id }
        assertEquals(listOf("voicerec", "mic", "camera", "video", "screen", "photos", "sounds", "battery", "location", "notifs",
            "motion", "touch", "speaker", "settings", "ring", "torch", "ask", "bugs", "tailscale", "jev", "clef", "weather"), ids)
        val camera = nodes.first { it.id == "camera" }
        assertEquals("Senses", camera.group); assertEquals("photo_camera", camera.icon); assertEquals("Camera", camera.label)
        assertEquals("Camera, front and back", camera.name); assertEquals(listOf("camera"), camera.caps)
        assertTrue(camera.why.isNotEmpty())
        assertEquals("Voice Recognition", nodes.first { it.id == "voicerec" }.label)
        assertEquals(listOf("wakewords", "mic"), nodes.first { it.id == "voicerec" }.caps)
        assertEquals(setOf("Senses", "Awareness", "Controls", "Talks With You", "Network", "Intelligence", "Information"), nodes.map { it.group }.toSet())
        assertTrue(nodes.all { it.icon.matches(Regex("[a-z_]+")) })
    }

    @Test fun `scene tokens give light and dark colours and fonts`() {
        val t = PortalData.sceneTokens(scene)
        assertEquals("#070b14", t["dark"]!!["void"]); assertEquals("#f8f9ff", t["light"]!!["void"])
        assertEquals("#5cc8ff", t["dark"]!!["senses"]); assertTrue(t["light"]!!.containsKey("senses"))
        assertTrue(t["dark"]!!["mono"]!!.contains("IBM Plex Mono"))
    }

    @Test fun `hex keeps alpha only when not opaque`() {
        assertEquals("#1F5BD6", PortalData.hex(0xFF1F5BD6.toInt()))
        assertEquals("#2B183420", PortalData.hex(0x202B1834))
    }

    @Test fun `theme colour list covers every colour token`() {
        val names = PortalModule.COLORS.map { it.first }.toSet()
        listOf("colors.xml", "colors_design.xml", "network_colors.xml").forEach { f ->
            Regex("<color name=\"([a-z0-9_]+)\"").findAll(File("src/main/res/values/$f").readText()).map { it.groupValues[1] }
                .filter { it != "qr_dark" && it != "qr_light" }.forEach { assertTrue("$it missing from the portal theme", it in names) }
        }
    }

    @Test fun `log entry renames remote-view fields and keeps nothing else`() {
        val line = JSONObject().put("seq", 7).put("ts", 1L).put("agent", "Claude").put("kind", "request").put("method", "POST")
            .put("endpoint", "/v1/camera/snapshot").put("http", 200).put("caption", JSONObject.NULL).put("source", "api")
        val e = PortalData.logEntry(line)
        assertEquals(setOf("id", "ts", "agent", "kind", "method", "path", "status", "caption",
            "channel", "level", "result", "endpoint", "http", "message", "params", "data"), e.keys().asSequence().toSet())
        assertEquals("/v1/camera/snapshot", e.getString("endpoint")); assertEquals(200, e.getInt("http")); assertEquals("", e.getString("message"))
        assertEquals(0, e.getJSONObject("params").length()); assertEquals(0, e.getJSONObject("data").length())
        assertEquals(7, e.getLong("id")); assertEquals("/v1/camera/snapshot", e.getString("path")); assertEquals(200, e.getInt("status"))
    }

    @Test fun `devices payload logo urls point at the portal route`() {
        val o = JSONObject().put("categories", org.json.JSONArray().put(JSONObject().put("brands", org.json.JSONArray()
            .put(JSONObject().put("id", "hikvision").put("logo_url", "/v1/lan/brands/hikvision/logo?raw=1")))))
        PortalData.portalLogoUrls(o)
        assertEquals("/v1/portal/brands/hikvision/logo?raw=1", o.getJSONArray("categories").getJSONObject(0).getJSONArray("brands").getJSONObject(0).getString("logo_url"))
    }

    @Test fun `Google-approve requests are parsed strictly and Allow hands back exactly the link's fields`() {
        val id = "ptr_" + "a".repeat(32)
        val reqs = PortalApprovals.parse(org.json.JSONArray()
            .put(JSONObject().put("request", id).put("email", "owner@example.com").put("device_name", "Rahul's iPad").put("expires_at", 5L))
            .put(JSONObject().put("request", "nope").put("email", "x")))
        assertEquals(1, reqs.size)
        assertEquals(PortalApprovals.Request(id, "owner@example.com", "Rahul's iPad", 5L), reqs.single())
        val link = PortalData.pairLink("https://bridgit-relay.bridgit-app.workers.dev", "a4e5f1c5-dd15", "rly_abc", "Galaxy Tab A9", install = "bgi_x", portal = true)
        val j = PortalApprovals.pairingJson(link)
        assertEquals(setOf("relay", "device", "token", "name", "install", "portal"), j.keys().asSequence().toSet())
        assertEquals("https://bridgit-relay.bridgit-app.workers.dev", j.getString("relay")); assertEquals("rly_abc", j.getString("token"))
        assertEquals("Galaxy Tab A9", j.getString("name")); assertTrue(j.getBoolean("portal"))
        assertFalse(PortalApprovals.pairingJson(PortalData.pairLink("https://r.example", "d1234567", "rly_x", "n", portal = false)).has("install"))
    }

    // The relay lives in the private bridgit-cloud repo, which writes its allowances here.
    @Test fun `the advertised relay allowance matches the relay Worker`() {
        val relay = JSONObject(File("../site-content/content/backend-api.json").readText()).getJSONObject("relay")
        assertEquals(relay.getInt("agent_daily_jobs"), PortalModule.RELAY_DAILY_JOBS)
        assertEquals(relay.getInt("portal_daily_jobs"), PortalModule.RELAY_PORTAL_DAILY_JOBS)
    }
}
