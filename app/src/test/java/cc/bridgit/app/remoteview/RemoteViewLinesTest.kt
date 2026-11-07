package cc.bridgit.app.remoteview

import cc.bridgit.app.log.LogEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Remote view sends only a short, content-free line per agent action (bridgit-cloud/feedback/src/watch.mjs checks it again). */
class RemoteViewLinesTest {
    private fun entry(kind: String, endpoint: String? = null, method: String? = null, message: String = "", agentId: String? = "a1",
                      params: JSONObject? = null, data: JSONObject? = null, http: Int? = 200) =
        LogEntry(id = 42, ts = 1_700_000_000_000, channel = "agent", kind = kind, agentId = agentId, agent = "Claude",
            sourceIp = "100.64.1.2", method = method, endpoint = endpoint, params = params, http = http, result = "ok",
            bytesIn = 10, bytesOut = 99_999, requestId = "req_1", message = message, data = data)

    @Test fun `a request keeps only time, agent, method, path and status`() {
        val e = entry("request", "/v1/speaker/say", "POST", message = "Claude: say \"the door code is 4512\"",
            params = JSONObject().put("text", "the door code is 4512"), data = JSONObject().put("source", "api").put("secret", "x"))
        val line = RemoteViewLines.line(e, "Claude (renamed)")!!
        assertEquals(setOf("seq", "ts", "agent", "kind", "method", "endpoint", "http", "caption", "source"), line.keys().asSequence().toSet())
        assertEquals("Claude (renamed)", line.getString("agent"))
        assertEquals("/v1/speaker/say", line.getString("endpoint"))
        assertEquals("api", line.getString("source"))
        assertFalse(line.toString().contains("4512"))
        assertFalse(line.toString().contains("100.64"))
    }

    @Test fun `the log message never leaves, only a fixed caption`() {
        val wake = RemoteViewLines.line(entry("wakewords", message = "Heard \"unlock the front door\" for Claude",
            data = JSONObject().put("transcript", "unlock the front door")), null)!!
        assertEquals("Heard a wake word", wake.getString("caption"))
        assertFalse(wake.toString().contains("front door"))
        val card = RemoteViewLines.line(entry("interaction", message = "Jev showed a card for Claude: \"Your bank PIN\""), null)!!
        assertEquals("Jev showed a card", card.getString("caption"))
        assertFalse(card.toString().contains("PIN"))
        assertEquals("Bridgit played audio", RemoteViewLines.line(entry("interaction", message = "Claude played audio",
            data = JSONObject().put("source", "bridgit")), null)!!.getString("caption"))
        // Other interaction entries ("Read aloud: …", answers) aren't sent at all.
        assertNull(RemoteViewLines.line(entry("interaction", message = "Owner answered: yes"), null))
    }

    @Test fun `ids in paths are taken out`() {
        assertEquals("/v1/gallery/:id/thumb", RemoteViewLines.endpoint("/v1/gallery/12345/thumb?size=small"))
        assertEquals("/v1/interactions/:id", RemoteViewLines.endpoint("/v1/interactions/ix_0123456789abcdefghijklmn"))
        assertEquals("mcp:tools/call", RemoteViewLines.endpoint("mcp:tools/call"))
        assertNull(RemoteViewLines.endpoint("https://example.com/v1/x"))
    }

    @Test fun `polls, status reads, owner screens and settings are not activity`() {
        assertNull(RemoteViewLines.line(entry("request", "/v1/health", "GET"), null))
        assertNull(RemoteViewLines.line(entry("request", "/v1/log", "GET"), null))
        assertNull(RemoteViewLines.line(entry("request", "/v1/interactions/ix_1", "GET", params = JSONObject().put("wait", 30)), null))
        assertNull(RemoteViewLines.line(entry("request", "/v1/camera/snapshot", "POST", agentId = null), null))
        assertNull(RemoteViewLines.line(entry("setting", message = "You turned remote view on"), null))
        assertNull(RemoteViewLines.line(entry("pairing", message = "Paired Claude"), null))
    }

    @Test fun `capability states`() {
        assertEquals("on", RemoteViewLines.capState(enabled = true, ready = true))
        assertEquals("blocked", RemoteViewLines.capState(enabled = true, ready = false))
        assertEquals("off", RemoteViewLines.capState(enabled = false, ready = true))
    }
    @Test fun `legacy and current monitor diagnostics never become generic remote activity`() {
        for (name in listOf("Dog monitor", "DOG MONITOR", "Pet monitor", "PET MONITOR")) {
            val e = entry("judgment", message = "$name: classified a frame")
            assertEquals(true, RemoteViewLines.isAppletDiagnostic(e))
            assertNull(RemoteViewLines.line(e, null))
        }
        val marked = entry("judgment", message = "Classified a frame", data = JSONObject().put("applet", "dog"))
        assertEquals(true, RemoteViewLines.isAppletDiagnostic(marked))
        assertNull(RemoteViewLines.line(marked, null))
        assertFalse(RemoteViewLines.isAppletDiagnostic(entry("judgment", message = "An unrelated decision")))
    }
}
