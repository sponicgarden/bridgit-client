package cc.bridgit.app.api

import cc.bridgit.app.capability.SpeakerRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Ways the speaker's URL rules (SPK-02) can fail: http or other schemes accepted; credentials in the URL;
 * odd ports to reach internal services; private, loopback, link-local, Tailscale (CGNAT) or IPv6 ULA
 * addresses treated as public, letting an agent make the phone probe the home network or tailnet.
 */
class SpeakerRulesTest {
    @Test fun urls() {
        assertTrue(SpeakerRules.urlAllowed("https://cdn.example.com/a.mp3"))
        for (no in listOf("http://cdn.example.com/a.mp3", "https://u:p@cdn.example.com/a.mp3", "https://cdn.example.com:8443/a.mp3",
            "file:///sdcard/a.mp3", "content://x/y", "ftp://a.com/a.mp3", "https:///a.mp3", "not a url"))
            assertFalse(no, SpeakerRules.urlAllowed(no))
    }

    @Test fun addresses() {
        fun ip(s: String) = InetAddress.getByName(s)
        assertTrue(SpeakerRules.publicAddress(ip("93.184.216.34")))
        assertTrue(SpeakerRules.publicAddress(ip("2606:2800:220:1:248:1893:25c8:1946")))
        for (no in listOf("127.0.0.1", "10.0.0.5", "192.168.1.32", "172.16.0.1", "169.254.1.1", "100.101.102.103", "100.64.0.1",
            "0.0.0.0", "::1", "fd7a:115c:a1e0::1", "fe80::1", "224.0.0.1"))
            assertFalse(no, SpeakerRules.publicAddress(ip(no)))
    }

    /** On silent, vibrate or DND nothing plays, so a say/play reply must not claim "playing" (the agent would wait for speech that never comes). */
    @Test fun replyStatusSaysSilenced() {
        assertEquals("playing", SpeakerRules.replyStatus(0, quiet = false))
        assertEquals("queued", SpeakerRules.replyStatus(2, quiet = false))
        assertEquals("silenced", SpeakerRules.replyStatus(0, quiet = true))
        assertEquals("silenced", SpeakerRules.replyStatus(1, quiet = true))
    }

    /** GET /v1/speaker must not show another agent's caption or play id (it may be private narration). */
    @Test fun statusShowsOnlyYourOwnSound() {
        val a = SpeakerRules.Entry("p1", "agentA", "Grok", "Secret narration for the owner")
        val queued = listOf(SpeakerRules.Entry("p2", "agentA", "Grok", "next"), SpeakerRules.Entry("p3", "agentB", "Claude", "mine"))
        val forB = SpeakerRules.status("agentB", a, queued, quiet = false, paused = false)
        assertTrue(forB.isNull("playing"))
        assertTrue(forB.getBoolean("busy"))
        assertFalse(forB.toString().contains("Secret"))
        assertFalse(forB.toString().contains("p1"))
        assertEquals(1, forB.getJSONArray("queued").length())
        assertEquals("p3", forB.getJSONArray("queued").getJSONObject(0).getString("play_id"))

        val forA = SpeakerRules.status("agentA", a, queued, quiet = true, paused = true)
        assertEquals("p1", forA.getJSONObject("playing").getString("play_id"))
        assertEquals("Secret narration for the owner", forA.getJSONObject("playing").getString("text"))
        assertFalse(forA.getBoolean("busy")); assertTrue(forA.getBoolean("paused")); assertTrue(forA.getBoolean("quiet"))
        assertEquals(1, forA.getJSONArray("queued").length())

        val idle = SpeakerRules.status("agentA", null, emptyList(), quiet = false, paused = false)
        assertTrue(idle.isNull("playing")); assertFalse(idle.getBoolean("busy")); assertFalse(idle.getBoolean("paused"))
    }

    /** Failed downloads/synthesis must not pile up; queued or playing files must never be swept. */
    @Test fun cacheSweep() {
        val now = 10_000_000L
        val min = 60_000L
        val files = listOf(
            SpeakerRules.CacheFile("old-orphan", now - 20 * min, 10),
            SpeakerRules.CacheFile("old-queued", now - 20 * min, 10),
            SpeakerRules.CacheFile("new-orphan", now - 1 * min, 10),
            SpeakerRules.CacheFile("big-orphan", now - 2 * min, 1000),
            SpeakerRules.CacheFile("playing", now - 3 * min, 5000))
        val owned = setOf("old-queued", "playing")
        assertEquals(setOf("old-orphan"), SpeakerRules.sweep(files, owned, now, maxAgeMs = 15 * min, maxBytes = 1_000_000))
        // Over the size cap: oldest unowned go first, owned never.
        assertEquals(setOf("old-orphan", "big-orphan"), SpeakerRules.sweep(files, owned, now, maxAgeMs = 15 * min, maxBytes = 5100))
        assertEquals(setOf("old-orphan", "big-orphan", "new-orphan"), SpeakerRules.sweep(files, owned, now, maxAgeMs = 15 * min, maxBytes = 100))
    }
}
