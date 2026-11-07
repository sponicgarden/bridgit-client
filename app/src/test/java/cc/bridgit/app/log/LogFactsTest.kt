package cc.bridgit.app.log

import cc.bridgit.app.PlatformAudit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The structured log fields the live Bridgit animation reads (log audit, 2 Nov 2026). */
class LogFactsTest {
    @Test fun adbInputCommandsNameTheirAction() {
        assertEquals("tap", LogFacts.adbAction("input tap 100 200"))
        assertEquals("tap", LogFacts.adbAction("  input touchscreen tap 1 2"))
        assertEquals("swipe", LogFacts.adbAction("input swipe 1 2 3 4 300"))
        assertEquals("swipe", LogFacts.adbAction("input -d 0 touchscreen swipe 1 2 3 4"))
        assertEquals("text", LogFacts.adbAction("input text 'hello%sworld'"))
        assertEquals("keyevent", LogFacts.adbAction("input keyevent KEYCODE_HOME"))
        assertEquals("keyevent", LogFacts.adbAction("input keyboard keyevent 66"))
        assertEquals("keyevent", LogFacts.adbAction("adb shell input keyevent 3"))
    }

    @Test fun anythingElseIsShell() {
        assertEquals("shell", LogFacts.adbAction("pm list packages"))
        assertEquals("shell", LogFacts.adbAction("input"))
        assertEquals("shell", LogFacts.adbAction("input unknownthing 1"))
        assertEquals("shell", LogFacts.adbAction("inputs tap 1 2"))
        assertEquals("shell", LogFacts.adbAction("input tap 1 2; rm -rf /sdcard/x"))
        assertEquals("shell", LogFacts.adbAction("input text a && reboot"))
        assertEquals("shell", LogFacts.adbAction(""))
    }

    @Test fun playbackSaysSpeechOrAudioAndWhoAsked() {
        val d = LogFacts.played("a1", spoken = true, fromApi = false, caption = "The forecast is rain", tour = false)
        assertEquals("a1", d.getString("agent_id"))
        assertEquals("speak", d.getString("action"))
        assertEquals("bridgit", d.getString("source"))
        assertEquals(20, d.getInt("chars"))
        assertFalse(d.toString().contains("forecast"))
        val f = LogFacts.played("a1", spoken = false, fromApi = true, caption = null, tour = true)
        assertEquals("play", f.getString("action"))
        assertEquals("api", f.getString("source"))
        assertTrue(f.getBoolean("tour"))
        assertFalse(f.has("chars"))
    }

    @Test fun jevNoticeNamesTheCard() {
        val d = LogFacts.jevNotice("a1", "n1", "battery low", 42)
        assertEquals("notice", d.getString("action"))
        assertEquals("jev", d.getString("source"))
        assertEquals("n1", d.getString("action_id"))
        assertEquals(42L, d.getLong("interaction_id"))
        assertEquals("battery low", d.getString("reason"))
        assertEquals("Jev showed a card for Claude: \"Charge me\"", LogFacts.jevNoticeMessage("Claude", "Charge me"))
    }

    @Test fun wakeHitCarriesTranscriptAsAField() {
        val d = LogFacts.wakeHeard("a1", "silence", true, "what's the weather", "android_on_device")
        assertTrue(d.getBoolean("has_transcript"))
        assertEquals("what's the weather", d.getString("transcript"))
        assertEquals("android_on_device", d.getString("recogniser"))
        assertEquals("silence", d.getString("ended_by"))
        assertTrue(d.getBoolean("clip"))
        assertFalse(LogFacts.wakeHeard("a1", "silence", false, " ", "vosk").getBoolean("has_transcript"))
    }

    @Test fun platformCallNamesItsTool() {
        val e = PlatformAudit.entry(JSONObject().put("agent_id", "a1").put("endpoint", "/v2/call").put("result", "ok")
            .put("tool", "ui.action"), 5L)
        assertEquals("/v2/call", e.endpoint)
        assertEquals("ui.action", e.data!!.getString("tool"))
        assertFalse(PlatformAudit.entry(JSONObject().put("endpoint", "/v2/tools"), 5L).data!!.has("tool"))
        assertEquals(null, e.http)
        val refused = PlatformAudit.entry(JSONObject().put("endpoint", "/v2/call").put("result", "unknown_tool").put("http", 404), 5L)
        assertEquals(404, refused.http)
        assertTrue(refused.isError)
    }

    /** Every kind the app writes is a known kind, so log filters can pick it. */
    @Test fun everyWrittenKindIsKnown() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val written = Regex("""system\(\s*"[a-z]+",\s*"([a-z_]+)"""")
        val kinds = root.walk().filter { it.extension == "kt" }
            .flatMap { f -> written.findAll(f.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue(kinds.size > 10)
        assertEquals(emptySet<String>(), kinds - LogEntry.KINDS.toSet())
        for (k in listOf("battery", "wakewords", "sounds", "schedule", "judgment", "capture_stored",
            "capture_upload", "capture_storage")) assertTrue(k, k in LogEntry.KINDS)
    }
}
