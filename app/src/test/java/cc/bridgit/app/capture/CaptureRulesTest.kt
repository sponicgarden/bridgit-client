package cc.bridgit.app.capture

import cc.bridgit.app.capture.CaptureRules.AgentPrefs
import cc.bridgit.app.capture.CaptureRules.Entry
import cc.bridgit.app.capture.CaptureRules.OwnerPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** SEC-13 / D33: where captures go, who decides, and who may fetch them. */
class CaptureRulesTest {
    private val owner = OwnerPolicy()

    @Test fun defaultsKeepTodaysBehaviour() {
        // Clips: memory for 10 minutes. Photos, screenshots, recordings: only returned.
        val clip = CaptureRules.plan(CaptureRules.WAKE_CLIP, owner, null)
        assertTrue(clip.memory); assertFalse(clip.files); assertNull(clip.agentUploadUrl); assertEquals(10 * 60_000L, clip.keepMs)
        assertEquals(listOf("memory"), CaptureRules.plan(CaptureRules.SOUND_CLIP, owner, null).destinations())
        for (k in listOf(CaptureRules.PHOTO, CaptureRules.SCREEN, CaptureRules.MIC)) assertFalse(k, CaptureRules.plan(k, owner, null).kept)
    }

    @Test fun agentsPickAnyCombination() {
        val prefs = AgentPrefs(setOf("memory", "files", "upload"), uploadUrl = "https://example.com/in", keepMinutes = 120)
        val p = CaptureRules.plan(CaptureRules.PHOTO, owner, prefs)
        assertEquals(listOf("memory", "files", "upload"), p.destinations())
        assertEquals("https://example.com/in", p.agentUploadUrl); assertEquals(120 * 60_000L, p.keepMs)
        val filesOnly = CaptureRules.plan(CaptureRules.SCREEN, owner, AgentPrefs(setOf("files")))
        assertEquals(listOf("files"), filesOnly.destinations())
    }

    @Test fun theRequestWinsOverTheSavedChoice() {
        val prefs = AgentPrefs(setOf("files"))
        assertEquals(listOf("memory"), CaptureRules.plan(CaptureRules.PHOTO, owner, prefs, setOf("memory")).destinations())
        assertFalse(CaptureRules.plan(CaptureRules.PHOTO, owner, prefs, emptySet()).kept)
    }

    @Test fun clipsAlwaysStayInMemorySoClipUrlWorks() {
        val p = CaptureRules.plan(CaptureRules.WAKE_CLIP, owner, AgentPrefs(setOf("files")))
        assertEquals(listOf("memory", "files"), p.destinations())
        assertTrue(CaptureRules.plan(CaptureRules.SOUND_CLIP, owner, AgentPrefs(emptySet())).memory)
    }

    @Test fun kindsLimitTheSavedChoice() {
        val prefs = AgentPrefs(setOf("files"), kinds = setOf(CaptureRules.PHOTO))
        assertEquals(listOf("files"), CaptureRules.plan(CaptureRules.PHOTO, owner, prefs).destinations())
        assertFalse(CaptureRules.plan(CaptureRules.SCREEN, owner, prefs).kept)
    }

    @Test fun theOwnerHasTheFinalSay() {
        val strict = OwnerPolicy(agentFiles = false, agentUpload = false, maxKeepMinutes = 30)
        // An explicit request the owner doesn't allow is refused, before anything is captured.
        val e = assertThrows(CaptureRules.Refused::class.java) { CaptureRules.plan(CaptureRules.PHOTO, strict, null, setOf("files")) }
        assertEquals("owner_disallowed", e.reason)
        assertThrows(CaptureRules.Refused::class.java) { CaptureRules.checkAllowed(setOf("upload"), null, strict) }
        assertThrows(CaptureRules.Refused::class.java) { CaptureRules.checkAllowed(setOf("memory"), 31, strict) }
        CaptureRules.checkAllowed(setOf("memory"), 30, strict)
        // A saved choice made before the owner turned something off is quietly narrowed.
        val p = CaptureRules.plan(CaptureRules.PHOTO, strict, AgentPrefs(setOf("memory", "files", "upload"), uploadUrl = "https://x.example/", keepMinutes = 600))
        assertEquals(listOf("memory"), p.destinations()); assertEquals(30 * 60_000L, p.keepMs)
    }

    @Test fun theOwnersOwnCopyGoesEverywhere() {
        val o = OwnerPolicy(ownerFiles = true, ownerUploadUrl = "https://backup.example/in")
        val p = CaptureRules.plan(CaptureRules.MIC, o, null)
        assertTrue(p.files); assertFalse(p.memory); assertEquals("https://backup.example/in", p.ownerUploadUrl)
        assertEquals(listOf("files", "upload"), p.destinations())
        // Even when the owner stops agents from saving files themselves.
        assertTrue(CaptureRules.plan(CaptureRules.PHOTO, o.copy(agentFiles = false), null).files)
    }

    @Test fun eachAgentFetchesOnlyItsOwnWhileItHasTheCapability() {
        val all: (String) -> Boolean = { true }
        assertTrue(CaptureRules.canFetch("a", "camera", "a", all, false))
        assertFalse(CaptureRules.canFetch("a", "camera", "b", all, false))
        assertFalse(CaptureRules.canFetch("a", "camera", "a", { it != "camera" }, false))
        // The owner's share switch opens it to other agents, still only with the capability.
        assertTrue(CaptureRules.canFetch("a", "camera", "b", all, true))
        assertFalse(CaptureRules.canFetch("a", "camera", "b", { it != "camera" }, true))
    }

    @Test fun storeAndKindsParse() {
        assertNull(CaptureRules.parseStore(null)); assertNull(CaptureRules.parseStore(JSONObject.NULL))
        assertEquals(setOf("memory", "files"), CaptureRules.parseStore(JSONArray().put("memory").put("files")))
        assertEquals(setOf("upload", "memory"), CaptureRules.parseStore("upload, memory"))
        assertEquals(emptySet<String>(), CaptureRules.parseStore("none"))
        assertEquals(emptySet<String>(), CaptureRules.parseStore(JSONArray()))
        assertThrows(CaptureRules.Refused::class.java) { CaptureRules.parseStore(JSONArray().put("gallery")) }
        assertThrows(CaptureRules.Refused::class.java) { CaptureRules.parseStore(5) }
        assertEquals(setOf("camera"), CaptureRules.parseKinds("camera"))
        assertThrows(CaptureRules.Refused::class.java) { CaptureRules.parseKinds(JSONArray().put("video")) }
    }

    @Test fun uploadHeadersAreChecked() {
        assertEquals(mapOf("Authorization" to "Bearer x"), CaptureRules.checkHeaders(mapOf(" Authorization " to "Bearer x")))
        for (bad in listOf(mapOf("Host" to "x"), mapOf("Content-Type" to "x"), mapOf("X-Bridgit-Kind" to "x"),
            mapOf("Bad Name" to "x"), mapOf("A" to "line\r\nInjected: 1"))) {
            assertThrows(bad.toString(), CaptureRules.Refused::class.java) { CaptureRules.checkHeaders(bad) }
        }
        assertThrows(CaptureRules.Refused::class.java) { CaptureRules.checkHeaders((1..9).associate { "H$it" to "v" }) }
    }

    @Test fun prefsRoundTripAndNeverShowHeaderValues() {
        val p = AgentPrefs(setOf("files", "upload"), setOf("camera"), "https://x.example/in", mapOf("Authorization" to "Bearer secret"), 45)
        assertEquals(p, AgentPrefs.fromJson(JSONObject(p.toJson().toString())))
        val shown = p.publicJson().toString()
        assertFalse(shown.contains("secret")); assertTrue(shown.contains("Authorization"))
        assertEquals(AgentPrefs(), AgentPrefs.fromJson(JSONObject(AgentPrefs().toJson().toString())))
    }

    @Test fun memoryDropsExpiredThenOldest() {
        fun e(id: String, size: Int, until: Long, mem: Boolean = true) = Entry(id, "a", "camera", "image/jpeg", size, 0, until, mem)
        val list = listOf(e("old", 10, 50), e("a", 10, 500), e("b", 10, 500), e("c", 10, 500), e("file", 10, 0, mem = false))
        assertEquals(setOf("old"), CaptureRules.memoryToDrop(list, 100))
        assertEquals(setOf("old", "a"), CaptureRules.memoryToDrop(list, 100, maxItems = 2))
        assertEquals(setOf("old", "a", "b"), CaptureRules.memoryToDrop(list, 100, budget = 10))
        assertEquals(setOf("old", "a", "b", "c"), CaptureRules.memoryToDrop(list, 600))
    }

    @Test fun entriesRoundTripAndListOnlyWhatIsStillSomewhere() {
        val e = Entry("c1", "a", "screen", "image/png", 3, 1000, 2000, true, "content://x/1", "Download/Bridgit/Claude/f.png",
            mapOf("agent" to "sent"), JSONObject().put("width_px", 10))
        val back = Entry.fromJson(JSONObject(e.toJson().toString()))
        assertEquals("content://x/1", back.fileUri); assertEquals(mapOf("agent" to "sent"), back.upload); assertFalse(back.memory)
        assertTrue(CaptureRules.listed(back))
        assertFalse(CaptureRules.listed(Entry("c2", "a", "mic", "audio/mp4", 1, 0, 0, false)))
    }

    @Test fun filesGoToAFolderPerAgent() {
        assertEquals("Download/Bridgit/Claude/", CaptureRules.folder("Claude"))
        assertEquals("Download/Bridgit/agent/", CaptureRules.folder("../"))
        assertEquals("2026-11-02_14-03-22_camera_abcdef.jpg",
            CaptureRules.fileName("camera", 1_793_628_202_000L, "c0123abcdef", "image/jpeg", ZoneOffset.UTC))
        assertEquals("wav", CaptureRules.extension("audio/wav")); assertEquals("m4a", CaptureRules.extension("audio/mp4"))
    }
}
