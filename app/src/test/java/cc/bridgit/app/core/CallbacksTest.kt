package cc.bridgit.app.core

import cc.bridgit.app.core.CallbackRules.Check
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class CallbacksTest {
    private val domain = "tail1234.ts.net"

    private fun ok(url: String, allow: Boolean = false, d: String? = domain) = CallbackRules.check(url, d, allow) as Check.Ok
    private fun bad(url: String, allow: Boolean = false, d: String? = domain) = CallbackRules.check(url, d, allow) is Check.Bad

    // ---- signature -------------------------------------------------------------------------------

    @Test fun signatureMatchesKnownVector() {
        val secret = "cbs_test-secret-0123456789"
        val body = "{\"event\":{\"id\":\"42\",\"type\":\"interaction.answered\",\"at\":\"2026-10-27T12:00:00Z\",\"data\":{}},\"agent\":\"Grok\"}"
            .toByteArray()
        // Computed independently with Python's hmac module.
        val expected = "sha256=117468517fecab256d0a734748b2c0ce3d1a5a3378f9935301a018885022d6f0"
        assertEquals(expected, CallbackRules.sign(secret, 1761566400L, body))
        // And with javax.crypto directly, over "<timestamp>.<raw body>".
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val hex = mac.doFinal("1761566400.".toByteArray() + body).joinToString("") { "%02x".format(it) }
        assertEquals(expected, "sha256=$hex")
    }

    @Test fun headersSignTheSameTimestampTheySend() {
        val body = CallbackRules.body(JSONObject().put("id", "7").put("type", "callback.test"), "Grok")
        val h = CallbackRules.headers("cbs_x", 1700000000L, "7", body, "1.5.2")
        assertEquals("1700000000", h["X-Bridgit-Timestamp"])
        assertEquals("7", h["X-Bridgit-Event-Id"])
        assertEquals("application/json", h["Content-Type"])
        assertEquals("Bridgit/1.5.2", h["User-Agent"])
        assertEquals(CallbackRules.sign("cbs_x", 1700000000L, body), h["X-Bridgit-Signature"])
        // Receivers written before the rename verify the X-Bridget- spelling: same values.
        for (n in listOf("Timestamp", "Event-Id", "Signature")) assertEquals(h["X-Bridgit-$n"], h["X-Bridget-$n"])
        val parsed = JSONObject(String(body))
        assertEquals("Grok", parsed.getString("agent"))
        assertEquals("callback.test", parsed.getJSONObject("event").getString("type"))
    }

    @Test fun secretShape() {
        val s = CallbackRules.newSecret()
        assertTrue(s.startsWith("cbs_"))
        assertEquals(4 + 43, s.length) // 32 bytes base64url without padding
        assertTrue(s.drop(4).all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    // ---- URL rules -------------------------------------------------------------------------------

    @Test fun tailnetAddressesAllowedWithHttp() {
        assertEquals(SafeHttp.Policy.TAILNET, ok("http://100.101.102.103/hook").policy)
        assertEquals(SafeHttp.Policy.TAILNET, ok("http://[fd7a:115c:a1e0::1]/hook").policy)
        assertEquals(SafeHttp.Policy.TAILNET, ok("https://grok-box.tail1234.ts.net/bridgit").policy)
        assertEquals("grok-box.tail1234.ts.net", ok("http://Grok-Box.tail1234.ts.net/x?y=1").host)
    }

    @Test fun otherTailnetsAndPrivateNetworksRefused() {
        assertTrue(bad("http://box.othertail.ts.net/hook"))
        assertTrue(bad("http://box.tail1234.ts.net/hook", d = null)) // tailnet name unknown
        assertTrue(bad("http://192.168.1.10/hook", allow = true))
        assertTrue(bad("http://10.0.0.1/hook", allow = true))
        assertTrue(bad("https://127.0.0.1/hook", allow = true))
        assertTrue(bad("https://[fd00::1]/hook", allow = true))
        assertTrue(bad("https://localhost/hook", allow = true))
        assertTrue(bad("http://100.200.1.1/hook")) // 100.128+ is outside 100.64.0.0/10 and not public-https
    }

    @Test fun publicNeedsSwitchAndHttps() {
        assertTrue(bad("https://hooks.example.com/bridgit"))
        assertEquals(SafeHttp.Policy.PUBLIC, ok("https://hooks.example.com/bridgit", allow = true).policy)
        assertTrue(bad("http://hooks.example.com/bridgit", allow = true))
        assertEquals(SafeHttp.Policy.PUBLIC, ok("https://8.8.8.8/x", allow = true).policy)
    }

    @Test fun portsAreDefaultsOrTheRegisteredOne() {
        assertNull(ok("http://100.101.102.103/hook").extraPort)
        assertEquals(8443, ok("http://100.101.102.103:8443/hook").extraPort)
        assertEquals(9000, ok("https://hooks.example.com:9000/x", allow = true).extraPort)
        assertNull(SafeHttp.urlProblem("http://100.101.102.103:8443/hook", SafeHttp.Policy.TAILNET, 8443))
        assertTrue(SafeHttp.urlProblem("http://100.101.102.103:8443/hook", SafeHttp.Policy.TAILNET, null) != null)
        assertTrue(bad("http://100.101.102.103:0/hook"))
    }

    @Test fun malformedRefused() {
        assertTrue(bad(""))
        assertTrue(bad("ftp://100.101.102.103/"))
        assertTrue(bad("100.101.102.103/hook"))
        assertTrue(bad("http://user:pw@100.101.102.103/hook"))
        assertTrue(bad("http://100.101.102.103/ho ok"))
        assertTrue(bad("http://100.101.102.103/hook#frag"))
    }

    @Test fun tailnetDomainFromMagicDns() {
        assertEquals("tail1234.ts.net", CallbackRules.tailnetDomain("pixel.tail1234.ts.net"))
        assertEquals("tail1234.ts.net", CallbackRules.tailnetDomain("Pixel.Tail1234.ts.net."))
        assertNull(CallbackRules.tailnetDomain(null))
        assertNull(CallbackRules.tailnetDomain("pixel.example.com"))
        assertNull(CallbackRules.tailnetDomain("tail1234.ts.net"))
    }

    @Test fun hostOnlyForLogs() {
        assertEquals("grok-box.tail1234.ts.net", CallbackRules.hostOf("https://grok-box.tail1234.ts.net/secret/path?token=abc"))
        assertEquals("fd7a:115c:a1e0::1", CallbackRules.hostOf("http://[fd7a:115c:a1e0::1]:8080/x"))
    }

    // ---- retries and queue -----------------------------------------------------------------------

    @Test fun retrySchedule() {
        assertEquals(10_000L, CallbackRules.nextDelayMs(1))
        assertEquals(60_000L, CallbackRules.nextDelayMs(2))
        assertEquals(300_000L, CallbackRules.nextDelayMs(3))
        assertNull(CallbackRules.nextDelayMs(4))
        assertEquals("60 s", CallbackRules.delayWords(60_000))
        assertEquals("5 min", CallbackRules.delayWords(300_000))
        assertEquals("10 s", CallbackRules.delayWords(10_000))
        assertTrue(CallbackRules.success(200) && CallbackRules.success(204) && !CallbackRules.success(301) && !CallbackRules.success(500))
    }

    @Test fun queueDropsOldestPerAgentAndOrdersByDue() {
        val q = CallbackQueue<Int>(cap = 3)
        repeat(3) { assertEquals(0, q.add(CallbackQueue.Item("a", it, 1, 100L + it))) }
        assertEquals(0, q.add(CallbackQueue.Item("b", 99, 1, 50)))
        assertEquals(1, q.add(CallbackQueue.Item("a", 3, 1, 103)))
        assertEquals(3, q.countFor("a"))
        assertNull(q.takeDue(10))
        assertEquals(99, q.takeDue(1000)!!.value)
        assertEquals(1, q.takeDue(1000)!!.value) // 0 was dropped
        q.removeAgent("a")
        assertEquals(0, q.size)
        assertNull(q.nextDueAt())
    }
}
