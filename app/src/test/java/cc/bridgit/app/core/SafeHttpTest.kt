package cc.bridgit.app.core

import cc.bridgit.app.core.SafeHttp.Policy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Ways outbound fetches (SPK-02, EVT-05a) can be turned against the phone: a private, loopback, link-local,
 * CGNAT/Tailscale, ULA, multicast or reserved address treated as public; IPv6 forms that embed a private IPv4
 * (mapped, 6to4, NAT64); tailnet callbacks reaching outside the tailnet; http or credentials or odd ports
 * accepted; a slow-trickling server holding a fetch past its total deadline; bodies past the byte limit;
 * redirects to blocked addresses followed.
 */
class SafeHttpTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @After fun reset() { SafeHttp.allowLoopbackForTests = false; SafeHttp.resolverForTests = null }

    @Test fun publicAddresses() {
        for (yes in listOf("93.184.216.34", "8.8.8.8", "1.1.1.1", "100.63.255.255", "100.128.0.1", "172.15.0.1", "172.32.0.1",
            "2606:2800:220:1:248:1893:25c8:1946", "2a00:1450:4001:80b::200e", "2002:5db8:d822::1", "64:ff9b::808:808"))
            assertTrue(yes, SafeHttp.isPublic(ip(yes)))
        for (no in listOf(
            "0.0.0.0", "0.1.2.3", "127.0.0.1", "127.255.0.1", "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.32",
            "169.254.169.254", "100.64.0.1", "100.101.102.103", "100.127.255.255", "192.0.0.8", "192.0.2.1", "198.18.0.1",
            "198.19.255.1", "198.51.100.7", "203.0.113.9", "224.0.0.1", "239.255.255.250", "240.0.0.1", "255.255.255.255",
            "::", "::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:192.168.0.1", "::127.0.0.1",
            "fc00::1", "fd00::1", "fd7a:115c:a1e0::1", "fe80::1", "febf::1", "fec0::1", "ff02::1", "ff0e::1",
            "2002:0a00:0001::1", "2002:c0a8:0101::1", "2002:7f00:0001::1", "64:ff9b::a00:1", "64:ff9b::7f00:1", "64:ff9b:1::1",
            "2001::1", "2001:db8::1", "100::1", "4000::1"))
            assertFalse(no, SafeHttp.isPublic(ip(no)))
    }

    @Test fun mappedV4IsFoldedToV4() {
        assertTrue(ip("::ffff:10.0.0.1") is java.net.Inet4Address)
        assertFalse(SafeHttp.allowed(ip("::ffff:100.100.1.1"), Policy.PUBLIC))
    }

    @Test fun tailnetAddresses() {
        for (yes in listOf("100.64.0.1", "100.101.102.103", "100.127.255.254", "fd7a:115c:a1e0::1", "fd7a:115c:a1e0:ab12::42"))
            assertTrue(yes, SafeHttp.allowed(ip(yes), Policy.TAILNET))
        for (no in listOf("100.63.255.255", "100.128.0.1", "10.0.0.1", "192.168.1.1", "127.0.0.1", "8.8.8.8", "::1",
            "fd7a:115c:a1e1::1", "fd7a:115c:a0e0::1", "fd00::1", "fe80::1", "2606:4700::1111"))
            assertFalse(no, SafeHttp.allowed(ip(no), Policy.TAILNET))
    }

    @Test fun urlRules() {
        assertNull(SafeHttp.urlProblem("https://cdn.example.com/a.mp3", Policy.PUBLIC))
        assertNull(SafeHttp.urlProblem("https://cdn.example.com:443/a.mp3", Policy.PUBLIC))
        assertNull(SafeHttp.urlProblem("http://agent.tail1234.ts.net/hook", Policy.TAILNET))
        assertNull(SafeHttp.urlProblem("http://100.94.1.2:8787/hook", Policy.TAILNET, extraPort = 8787))
        for (no in listOf("http://cdn.example.com/a.mp3", "https://u:p@cdn.example.com/a.mp3", "https://cdn.example.com:8443/a.mp3",
            "file:///sdcard/a.mp3", "ftp://a.com/a", "https:///a.mp3", "not a url", "gopher://x/"))
            assertNotNull(no, SafeHttp.urlProblem(no, Policy.PUBLIC))
        assertNotNull(SafeHttp.urlProblem("http://100.94.1.2:9999/hook", Policy.TAILNET, extraPort = 8787))
    }

    @Test fun blockedBeforeConnecting() {
        for (u in listOf("https://127.0.0.1/a", "https://192.168.1.1/a", "https://[::1]/a", "https://169.254.169.254/latest")) {
            try { SafeHttp.get(u, Policy.PUBLIC, 5_000, 1024); fail(u) }
            catch (e: SafeHttp.Failure) { assertEquals(u, SafeHttp.Reason.BLOCKED_ADDRESS, e.reason) }
        }
        // A name resolving to one public and one private address is refused as a whole.
        SafeHttp.resolverForTests = { listOf(ip("93.184.216.34"), ip("10.0.0.7")) }
        try { SafeHttp.get("https://rebind.example/a", Policy.PUBLIC, 5_000, 1024); fail() }
        catch (e: SafeHttp.Failure) { assertEquals(SafeHttp.Reason.BLOCKED_ADDRESS, e.reason) }
    }

    /** A one-shot loopback server; [handler] writes the raw response. */
    private fun server(handler: (java.io.OutputStream) -> Unit): ServerSocket {
        val ss = ServerSocket(0, 5, ip("127.0.0.1"))
        thread(isDaemon = true) {
            runCatching {
                while (!ss.isClosed) {
                    val s = ss.accept()
                    thread(isDaemon = true) {
                        runCatching {
                            s.use { c ->
                                val r = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                                while (r.readLine()?.isNotEmpty() == true) {}
                                handler(c.getOutputStream())
                            }
                        }
                    }
                }
            }
        }
        return ss
    }

    @Test fun getContentLengthChunkedAndFile() {
        SafeHttp.allowLoopbackForTests = true
        server { it.write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Type: text/plain\r\n\r\nhello".toByteArray()) }.use { ss ->
            val r = SafeHttp.get("http://127.0.0.1:${ss.localPort}/x", Policy.TAILNET, 5_000, 100, extraPort = ss.localPort)
            assertEquals(200, r.status); assertEquals("text/plain", r.header("Content-Type")); assertArrayEquals("hello".toByteArray(), r.bytes)
        }
        server { it.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n4\r\ndefg\r\n0\r\n\r\n".toByteArray()) }.use { ss ->
            val f = File.createTempFile("safehttp", ".bin")
            val r = SafeHttp.get("http://127.0.0.1:${ss.localPort}/x", Policy.TAILNET, 5_000, 100, extraPort = ss.localPort, out = f)
            assertNull(r.bytes); assertEquals(7L, r.length); assertEquals("abcdefg", f.readText()); f.delete()
        }
    }

    @Test fun maxBytes() {
        SafeHttp.allowLoopbackForTests = true
        server { it.write(("HTTP/1.1 200 OK\r\n\r\n" + "x".repeat(5000)).toByteArray()) }.use { ss ->
            val f = File.createTempFile("safehttp", ".bin")
            try { SafeHttp.get("http://127.0.0.1:${ss.localPort}/", Policy.TAILNET, 5_000, 1000, extraPort = ss.localPort, out = f); fail() }
            catch (e: SafeHttp.Failure) { assertEquals(SafeHttp.Reason.TOO_LARGE, e.reason) }
            assertFalse("partial file left behind", f.exists())
        }
        server { it.write("HTTP/1.1 200 OK\r\nContent-Length: 999999\r\n\r\n".toByteArray()) }.use { ss ->
            try { SafeHttp.get("http://127.0.0.1:${ss.localPort}/", Policy.TAILNET, 5_000, 1000, extraPort = ss.localPort); fail() }
            catch (e: SafeHttp.Failure) { assertEquals(SafeHttp.Reason.TOO_LARGE, e.reason) }
        }
    }

    @Test fun totalDeadlineBeatsTrickle() {
        SafeHttp.allowLoopbackForTests = true
        // One byte every 300 ms never trips a per-read timeout; the total deadline must still end it.
        server { o ->
            o.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n".toByteArray()); o.flush()
            repeat(1000) { o.write('x'.code); o.flush(); Thread.sleep(300) }
        }.use { ss ->
            val t0 = System.nanoTime()
            try { SafeHttp.get("http://127.0.0.1:${ss.localPort}/", Policy.TAILNET, 1_500, 10_000, extraPort = ss.localPort); fail() }
            catch (e: SafeHttp.Failure) { assertEquals(SafeHttp.Reason.TIMEOUT, e.reason) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("took $ms ms", ms < 4_000)
        }
    }

    @Test fun redirectsRecheckedAndCapped() {
        SafeHttp.allowLoopbackForTests = true
        server { it.write("HTTP/1.1 302 Found\r\nLocation: http://10.0.0.1/secret\r\nContent-Length: 0\r\n\r\n".toByteArray()) }.use { ss ->
            // Not followed by default.
            assertEquals(302, SafeHttp.get("http://127.0.0.1:${ss.localPort}/", Policy.TAILNET, 5_000, 100, extraPort = ss.localPort).status)
            try { SafeHttp.get("http://127.0.0.1:${ss.localPort}/", Policy.TAILNET, 5_000, 100, extraPort = ss.localPort, maxRedirects = 3); fail() }
            catch (e: SafeHttp.Failure) { assertEquals(SafeHttp.Reason.BLOCKED_ADDRESS, e.reason) }
        }
        lateinit var ss: ServerSocket
        ss = server { it.write("HTTP/1.1 301 Moved\r\nLocation: /again\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
        ss.use {
            try { SafeHttp.get("http://127.0.0.1:${ss.localPort}/", Policy.TAILNET, 5_000, 100, extraPort = ss.localPort, maxRedirects = 3); fail() }
            catch (e: SafeHttp.Failure) { assertEquals(SafeHttp.Reason.TOO_MANY_REDIRECTS, e.reason) }
        }
    }

    @Test fun postReturnsStatus() {
        SafeHttp.allowLoopbackForTests = true
        server { it.write("HTTP/1.1 204 No Content\r\n\r\n".toByteArray()) }.use { ss ->
            assertEquals(204, SafeHttp.post("http://127.0.0.1:${ss.localPort}/hook", Policy.TAILNET,
                mapOf("X-Bridgit-Event-Id" to "1"), "{}".toByteArray(), 5_000, extraPort = ss.localPort))
        }
        // Header injection is refused.
        try { SafeHttp.post("http://127.0.0.1:1/hook", Policy.TAILNET, mapOf("X" to "a\r\nEvil: 1"), ByteArray(0), 1_000, extraPort = 1); fail() }
        catch (e: SafeHttp.Failure) { assertEquals(SafeHttp.Reason.BAD_URL, e.reason) }
    }
}
