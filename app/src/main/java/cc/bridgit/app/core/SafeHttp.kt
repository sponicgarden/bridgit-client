package cc.bridgit.app.core

import cc.bridgit.app.outbound.Outbound
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Outbound HTTP that can't be turned against the phone's own networks (SPK-02, EVT-05a).
 *
 * Every attempt resolves the host, checks **every** resolved address against a [Policy], and then
 * connects to that exact checked address (no second DNS lookup by a library). HTTPS is layered on
 * the pinned socket with SNI and normal hostname verification against the original host name.
 * One monotonic deadline covers DNS, connect, TLS, redirects and the whole transfer.
 *
 * A deliberately small HTTP/1.1 client: one request per connection (`Connection: close`), identity
 * encoding, Content-Length / chunked / read-to-close bodies. No cookies, no auth, no proxies.
 */
object SafeHttp {
    enum class Policy {
        /** Publicly routable addresses only, HTTPS only. */
        PUBLIC,
        /** Tailscale only: 100.64.0.0/10 and fd7a:115c:a1e0::/48. Plain http allowed. */
        TAILNET,
        /** Owner-enrolled literal private IPv4 cameras, ONVIF SOAP only, fixed port/path. */
        LOCAL_CAMERA,
        /** Fixed adapter paths, literal directly connected LAN targets, private payloads. */
        LOCAL_DEVICE,
        /**
         * An owner-approved connection "on my private network" (docs/applet-reach.md A2): any port; https to a
         * public or private address, plain http only when every resolved address is private (10/8, 172.16/12,
         * 192.168/16, 100.64/10, fc00::/7). Checked again on each send against the pinned address.
         */
        OWNER_NETWORK,
    }

    enum class Reason { PAUSED, BAD_URL, BLOCKED_ADDRESS, DNS, CONNECT, TLS, TIMEOUT, TOO_LARGE, TOO_MANY_REDIRECTS, PROTOCOL }

    class Failure(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause)

    /** [bytes] is null when the body went to the caller's file. [url] is the final URL after redirects. */
    class Result(val status: Int, val headers: Map<String, String>, val bytes: ByteArray?, val length: Long, val url: String) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    const val MAX_REDIRECTS = 3
    private const val MAX_HEADER_BYTES = 32 * 1024

    /** Tests only: lets a loopback test server through the address check. Never set in the app. */
    @Volatile internal var allowLoopbackForTests = false

    /** Tests only: overrides DNS (host -> addresses). */
    @Volatile internal var resolverForTests: ((String) -> List<InetAddress>)? = null

    // ---- address rules (pure, unit-tested) ---------------------------------------------------------

    /** True if [a] may be contacted under [policy]. */
    fun allowed(a: InetAddress, policy: Policy): Boolean {
        if (allowLoopbackForTests && a.isLoopbackAddress) return true
        return when (policy) {
            Policy.PUBLIC -> isPublic(a)
            Policy.TAILNET -> isTailnet(a)
            Policy.LOCAL_CAMERA -> a is Inet4Address && cc.bridgit.app.lancamera.LanCamera.localHost(a.hostAddress.orEmpty())
            Policy.LOCAL_DEVICE -> a is Inet4Address && cc.bridgit.app.lan.LanRules.ipv4(a.hostAddress.orEmpty()) != null
            Policy.OWNER_NETWORK -> isPublic(a) || isPrivate(a)
        }
    }

    /** Tailscale ranges only. */
    fun isTailnet(a: InetAddress): Boolean {
        val b = a.address.map { it.toInt() and 0xFF }
        return when (a) {
            is Inet4Address -> b[0] == 100 && (b[1] and 0xC0) == 64
            is Inet6Address -> b[0] == 0xfd && b[1] == 0x7a && b[2] == 0x11 && b[3] == 0x5c && b[4] == 0xa1 && b[5] == 0xe0
            else -> false
        }
    }

    /** A private-network address an owner may approve: RFC 1918, 100.64/10 (Tailscale) and fc00::/7; never loopback or link-local. */
    fun isPrivate(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return false
        val b = a.address.map { it.toInt() and 0xFF }
        return when (a) {
            is Inet4Address -> b[0] == 10 || (b[0] == 172 && (b[1] and 0xF0) == 16) || (b[0] == 192 && b[1] == 168) || (b[0] == 100 && (b[1] and 0xC0) == 64)
            is Inet6Address -> (b[0] and 0xFE) == 0xFC
            else -> false
        }
    }

    /** Publicly routable: never loopback, private, link-local, CGNAT, ULA, multicast, reserved or documentation ranges. */
    fun isPublic(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        return when (a) {
            is Inet4Address -> publicV4(a.address.map { it.toInt() and 0xFF })
            is Inet6Address -> publicV6(a.address.map { it.toInt() and 0xFF })
            else -> false
        }
    }

    private fun publicV4(b: List<Int>): Boolean {
        val (x, y, z) = Triple(b[0], b[1], b[2])
        return when {
            x == 0 -> false                                   // 0.0.0.0/8 "this network"
            x == 10 -> false                                  // private
            x == 127 -> false                                 // loopback
            x == 100 && (y and 0xC0) == 64 -> false           // 100.64.0.0/10 CGNAT (Tailscale)
            x == 169 && y == 254 -> false                     // link-local
            x == 172 && (y and 0xF0) == 16 -> false           // 172.16.0.0/12
            x == 192 && y == 168 -> false                     // private
            x == 192 && y == 0 && z == 0 -> false             // 192.0.0.0/24 IETF protocol assignments
            x == 192 && y == 0 && z == 2 -> false             // TEST-NET-1
            x == 198 && (y and 0xFE) == 18 -> false           // 198.18.0.0/15 benchmarking
            x == 198 && y == 51 && z == 100 -> false          // TEST-NET-2
            x == 203 && y == 0 && z == 113 -> false           // TEST-NET-3
            x >= 224 -> false                                 // multicast, 240/4 reserved, broadcast
            else -> true
        }
    }

    private fun publicV6(b: List<Int>): Boolean {
        val first16 = (b[0] shl 8) or b[1]
        if ((b[0] and 0xFE) == 0xFC) return false             // fc00::/7 ULA (includes Tailscale fd7a:115c:a1e0::/48)
        if (b[0] == 0xFE && (b[1] and 0xC0) == 0x80) return false // fe80::/10 link-local
        if (b[0] == 0xFE && (b[1] and 0xC0) == 0xC0) return false // fec0::/10 old site-local
        if (b[0] == 0xFF) return false                        // multicast
        if (b.subList(0, 12).all { it == 0 }) return false    // ::/96 (unspecified, loopback, IPv4-compatible)
        if (first16 == 0x0064 && b[2] == 0xFF && b[3] == 0x9B) return publicV4(b.subList(12, 16)) && b.subList(4, 12).all { it == 0 } // 64:ff9b::/96 NAT64
        if (first16 == 0x2002) return publicV4(b.subList(2, 6))        // 6to4 embeds an IPv4 address
        if (first16 == 0x2001 && b[2] == 0 && b[3] == 0) return false  // 2001::/32 Teredo
        if (first16 == 0x2001 && b[2] == 0x0D && b[3] == 0xB8) return false // 2001:db8::/32 documentation
        if (first16 == 0x0100 && b.subList(2, 8).all { it == 0 }) return false // 100::/64 discard
        return (b[0] and 0xE0) == 0x20                        // only 2000::/3 is global unicast
    }

    /** Scheme, credentials and port rules for one URL under [policy]. Returns null when fine, else why not. */
    fun urlProblem(url: String, policy: Policy, extraPort: Int? = null): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return "not a URL"
        val scheme = u.scheme?.lowercase() ?: return "no scheme"
        if (policy == Policy.LOCAL_DEVICE) return if (scheme in listOf("http", "https") &&
            effectivePort(u) in listOf(80, 443) && u.rawUserInfo == null && u.rawFragment == null &&
            cc.bridgit.app.lan.LanRules.ipv4(u.host.orEmpty()) != null &&
            (u.rawPath in listOf("/shelly", "/status", "/ubus") && u.rawQuery == null ||
             u.rawPath.matches(Regex("/relay/[0-3]")) && u.rawQuery in listOf("turn=on", "turn=off"))) null
            else "only fixed LAN adapter operations are allowed"
        if (policy == Policy.LOCAL_CAMERA) return if (scheme == "http" && u.port == 2020 &&
            u.rawPath == "/onvif/service" && u.rawQuery == null && u.rawFragment == null && u.rawUserInfo == null &&
            cc.bridgit.app.lancamera.LanCamera.localHost(u.host.orEmpty())) null else "only an enrolled camera ONVIF address is allowed"
        if (policy == Policy.OWNER_NETWORK) return when {
            scheme != "https" && scheme != "http" -> "scheme $scheme isn't allowed"
            u.rawUserInfo != null -> "credentials in the URL aren't allowed"
            u.host.isNullOrEmpty() -> "no host"
            u.rawFragment != null -> "a #fragment isn't allowed"
            else -> null
        }
        if (scheme != "https" && !(scheme == "http" && policy == Policy.TAILNET)) return "scheme $scheme isn't allowed"
        if (u.rawUserInfo != null) return "credentials in the URL aren't allowed"
        if (u.host.isNullOrEmpty()) return "no host"
        val port = effectivePort(u)
        if (port != 80 && port != 443 && port != extraPort) return "port $port isn't allowed"
        return null
    }

    private fun effectivePort(u: URI) = if (u.port != -1) u.port else if (u.scheme.equals("https", true)) 443 else 80

    // ---- requests ----------------------------------------------------------------------------------

    /**
     * GET [url]. Follows at most [maxRedirects] redirects (default none), re-checking every hop.
     * With [out] the body is streamed into that file (deleted again on failure); otherwise it is returned as bytes.
     * Throws [Failure]. Non-2xx statuses are returned, not thrown.
     */
    fun get(url: String, policy: Policy, deadlineMs: Long, maxBytes: Long, extraPort: Int? = null,
            headers: Map<String, String> = emptyMap(), maxRedirects: Int = 0, out: File? = null): Result {
        val deadline = Deadline(deadlineMs)
        var current = url
        var hop = 0
        while (true) {
            val r = try {
                exchange(current, policy, "GET", headers, null, deadline, maxBytes, extraPort, out)
            } catch (e: Throwable) {
                out?.delete(); throw e
            }
            val loc = r.header("location")
            if (r.status in 300..399 && r.status != 304 && loc != null && maxRedirects > 0) {
                if (hop >= maxRedirects) { out?.delete(); throw Failure(Reason.TOO_MANY_REDIRECTS, "More than $maxRedirects redirects.") }
                hop++
                current = runCatching { URI(current).resolve(loc.trim()).toString() }.getOrElse {
                    out?.delete(); throw Failure(Reason.BAD_URL, "Bad redirect target.")
                }
                continue
            }
            return r
        }
    }

    /** ONVIF response body, with pinned private address and a single bounded request. SOAP credentials are not logged. */
    fun cameraSoap(host: String, contentType: String, body: ByteArray, sockets: javax.net.SocketFactory): Result =
        exchange("http://$host:2020/onvif/service", Policy.LOCAL_CAMERA, "POST", mapOf("Content-Type" to contentType),
            body, Deadline(6000), 128 * 1024, 2020, null, sockets)

    /** Caller validates on-link scope; no redirects, no payload/header logging, one pinned socket. */
    fun localDevice(host: String, path: String, https: Boolean, headers: Map<String, String>, body: ByteArray?, sockets: javax.net.SocketFactory): Result =
        exchange("${if (https) "https" else "http"}://$host$path", Policy.LOCAL_DEVICE,
            if (body == null) "GET" else "POST", headers, body, Deadline(4000), 128 * 1024, null, null, sockets)

    /** POST [body] to [url]; never follows redirects. Returns the HTTP status. Throws [Failure]. */
    fun post(url: String, policy: Policy, headers: Map<String, String>, body: ByteArray, deadlineMs: Long, extraPort: Int? = null): Int =
        exchange(url, policy, "POST", headers, body, Deadline(deadlineMs), 64 * 1024, extraPort, null).status

    /**
     * One request to an owner-approved connection ([Policy.PUBLIC] or [Policy.OWNER_NETWORK]); never follows
     * redirects. The reply body is kept up to [maxBytes]. [logUrl] is what the outbound log records instead of
     * [url], because webhook services keep their secret in the path. Throws [Failure].
     */
    fun request(url: String, policy: Policy, method: String, headers: Map<String, String>, body: ByteArray?, deadlineMs: Long,
                maxBytes: Long, logUrl: String, beforeWrite: (() -> Unit)? = null): Result =
        exchange(url, policy, method, headers, body, Deadline(deadlineMs), maxBytes, null, null, logUrl = logUrl, beforeWrite = beforeWrite)

    /** DELETE [url]; never follows redirects. Returns the HTTP status. Throws [Failure]. */
    fun delete(url: String, policy: Policy, headers: Map<String, String>, deadlineMs: Long): Int =
        exchange(url, policy, "DELETE", headers, null, Deadline(deadlineMs), 64 * 1024, null, null).status

    private class Deadline(ms: Long) {
        private val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        fun remainingMs(): Long = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())
        fun check(): Int {
            val r = remainingMs()
            if (r <= 0) throw Failure(Reason.TIMEOUT, "Took too long.")
            return r.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    private fun resolve(host: String, deadline: Deadline): List<InetAddress> {
        resolverForTests?.let { return it(host) }
        val task = FutureTask { InetAddress.getAllByName(host).toList() }
        Thread(task, "safehttp-dns").apply { isDaemon = true }.start()
        return try {
            task.get(deadline.check().toLong(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true); throw Failure(Reason.TIMEOUT, "Looking up $host took too long.")
        } catch (e: ExecutionException) {
            throw Failure(Reason.DNS, "Can't resolve $host.", e.cause)
        }
    }

    /** Every exchange is recorded in the outbound log, refused ones included (they never left, status 0). */
    private fun exchange(url: String, policy: Policy, method: String, headers: Map<String, String>, body: ByteArray?,
                         deadline: Deadline, maxBytes: Long, extraPort: Int?, out: File?, sockets: javax.net.SocketFactory? = null,
                         logUrl: String = url, beforeWrite: (() -> Unit)? = null): Result {
        // Paused external calls: only servers off the owner's network; tailnet callbacks to their own machines still go.
        if (policy == Policy.PUBLIC || (policy == Policy.OWNER_NETWORK && url.startsWith("https:"))) try { Outbound.check(logUrl, method, if (url.startsWith("http:")) "http" else "https") }
        catch (e: Outbound.Paused) { throw Failure(Reason.PAUSED, Outbound.Paused.MESSAGE, e) }
        val started = System.currentTimeMillis()
        val r = try { exchangeRaw(url, policy, method, headers, body, deadline, maxBytes, extraPort, out, sockets, beforeWrite) } catch (e: Failure) {
            Outbound.record(logUrl, method, if (url.startsWith("http:")) "http" else "https", if (policy == Policy.LOCAL_DEVICE) emptyMap() else headers, if (policy == Policy.LOCAL_CAMERA || policy == Policy.LOCAL_DEVICE) null else body, sentBytes = body?.size?.toLong() ?: 0L, status = 0,
                error = "${e.reason}: ${e.message}", startedAt = started)
            throw e
        }
        Outbound.record(logUrl, method, if (url.startsWith("http:")) "http" else "https", if (policy == Policy.LOCAL_DEVICE) emptyMap() else headers, if (policy == Policy.LOCAL_CAMERA || policy == Policy.LOCAL_DEVICE) null else body, sentBytes = body?.size?.toLong() ?: 0L, status = r.status,
            receivedBytes = r.length, received = (if (policy == Policy.LOCAL_CAMERA || policy == Policy.LOCAL_DEVICE) null else r.bytes)?.let { if (it.size > 4096) it.copyOf(4096) else it },
            receivedType = r.header("content-type"), startedAt = started)
        return r
    }

    private fun exchangeRaw(url: String, policy: Policy, method: String, headers: Map<String, String>, body: ByteArray?,
                         deadline: Deadline, maxBytes: Long, extraPort: Int?, out: File?, sockets: javax.net.SocketFactory? = null, beforeWrite: (() -> Unit)? = null): Result {
        urlProblem(url, policy, extraPort)?.let { throw Failure(Reason.BAD_URL, "Not allowed: $it.") }
        headers.forEach { (k, v) ->
            if (k.isEmpty() || k.any { it == '\r' || it == '\n' || it == ':' } || v.any { it == '\r' || it == '\n' })
                throw Failure(Reason.BAD_URL, "Bad header $k.")
        }
        val u = URI(url)
        val host = u.host.removePrefix("[").removeSuffix("]")
        val port = effectivePort(u)
        val https = u.scheme.equals("https", true)

        val addrs = resolve(host, deadline)
        if (addrs.isEmpty()) throw Failure(Reason.DNS, "Can't resolve $host.")
        if (addrs.any { !allowed(it, policy) }) throw Failure(Reason.BLOCKED_ADDRESS,
            when (policy) { Policy.PUBLIC -> "$host isn't a public address."; Policy.OWNER_NETWORK -> "$host isn't a public or private-network address."; else -> "$host isn't on the tailnet." })
        // Plain http only inside the owner's network: every address (and so the pinned one) must be private.
        if (policy == Policy.OWNER_NETWORK && !https && addrs.any { !(isPrivate(it) || (allowLoopbackForTests && it.isLoopbackAddress)) })
            throw Failure(Reason.BLOCKED_ADDRESS, "$host isn't on your private network, so plain http isn't allowed.")
        val pinned = addrs.first()

        val raw = sockets?.createSocket() ?: Socket()
        var sock: Socket = raw
        // Closing the socket from a watchdog makes a slow-trickling server fail at the deadline even mid-read.
        val watchdog = Thread {
            try { Thread.sleep(deadline.remainingMs().coerceAtLeast(1)) ; runCatching { raw.close() } } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; name = "safehttp-deadline" }
        watchdog.start()
        try {
            try {
                raw.connect(InetSocketAddress(pinned, port), deadline.check())
            } catch (e: SocketTimeoutException) { throw Failure(Reason.TIMEOUT, "Connecting to $host took too long.", e)
            } catch (e: Failure) { throw e
            } catch (e: Exception) { throw if (deadline.remainingMs() <= 0) Failure(Reason.TIMEOUT, "Took too long.", e) else Failure(Reason.CONNECT, "Can't connect to $host.", e) }
            raw.soTimeout = deadline.check()
            if (https) sock = tls(raw, host, port, deadline)

            val path = (u.rawPath?.ifEmpty { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
            val hostHeader = (if (host.contains(':')) "[$host]" else host) + (if (u.port != -1) ":${u.port}" else "")
            val req = StringBuilder("$method $path HTTP/1.1\r\nHost: $hostHeader\r\nConnection: close\r\nAccept-Encoding: identity\r\n")
            headers.forEach { (k, v) ->
                if (!k.equals("host", true) && !k.equals("connection", true) && !k.equals("content-length", true)) req.append("$k: $v\r\n")
            }
            if (body != null) req.append("Content-Length: ${body.size}\r\n")
            req.append("\r\n")
            val os = sock.getOutputStream()
            beforeWrite?.invoke()
            os.write(req.toString().toByteArray(Charsets.ISO_8859_1))
            body?.let { os.write(it) }
            os.flush()

            val inp = TimedInput(sock, deadline)
            val (status, hdrs) = readHead(inp)
            val sink: OutputStream = out?.outputStream() ?: ByteArrayOutputStream()
            var total = 0L
            sink.use { s ->
                if (method != "HEAD" && status !in 100..199 && status != 204 && status != 304) {
                    val te = hdrs["transfer-encoding"]?.lowercase()
                    val cl = hdrs["content-length"]?.trim()?.toLongOrNull()
                    if (cl != null && cl > maxBytes) throw Failure(Reason.TOO_LARGE, "Larger than ${maxBytes / 1024 / 1024} MB.")
                    total = when {
                        te != null && "chunked" in te -> readChunked(inp, s, maxBytes)
                        cl != null -> copy(inp, s, cl, maxBytes, exact = true)
                        else -> copy(inp, s, Long.MAX_VALUE, maxBytes, exact = false)
                    }
                }
            }
            return Result(status, hdrs, (sink as? ByteArrayOutputStream)?.toByteArray(), total, url)
        } catch (e: Failure) {
            throw e
        } catch (e: Exception) {
            if (deadline.remainingMs() <= 0 || e is SocketTimeoutException) throw Failure(Reason.TIMEOUT, "Took too long.", e)
            throw Failure(Reason.PROTOCOL, "The connection to $host failed (${e.javaClass.simpleName}).", e)
        } finally {
            watchdog.interrupt()
            runCatching { sock.close() }; runCatching { raw.close() }
        }
    }

    private fun tls(raw: Socket, host: String, port: Int, deadline: Deadline): SSLSocket {
        val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
        val p = s.sslParameters
        // SNI for the real name although the socket is connected to an IP; hostname checked against that name.
        if (InetAddressLiteral.isLiteral(host).not()) p.serverNames = listOf(SNIHostName(host))
        p.endpointIdentificationAlgorithm = "HTTPS"
        s.sslParameters = p
        s.soTimeout = deadline.check()
        try { s.startHandshake() } catch (e: Exception) {
            throw if (deadline.remainingMs() <= 0) Failure(Reason.TIMEOUT, "Took too long.", e) else Failure(Reason.TLS, "TLS with $host failed.", e)
        }
        // Belt and braces: the platform verifier must accept the certificate for the original host.
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) throw Failure(Reason.TLS, "Certificate doesn't match $host.")
        return s
    }

    private object InetAddressLiteral {
        fun isLiteral(h: String) = h.contains(':') || h.all { it.isDigit() || it == '.' }
    }

    /** Reads with the socket timeout tightened to the remaining deadline before every read. */
    private class TimedInput(private val sock: Socket, private val deadline: Deadline) {
        private val inp: InputStream = sock.getInputStream()
        fun read(buf: ByteArray, off: Int, len: Int): Int { sock.soTimeout = deadline.check(); return inp.read(buf, off, len) }
        fun read(): Int { sock.soTimeout = deadline.check(); return inp.read() }
    }

    private fun readLine(inp: TimedInput, limit: IntArray): String {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) { if (sb.isEmpty()) throw Failure(Reason.PROTOCOL, "Connection closed early."); break }
            if (--limit[0] < 0) throw Failure(Reason.PROTOCOL, "Response headers too large.")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readHead(inp: TimedInput): Pair<Int, Map<String, String>> {
        val limit = intArrayOf(MAX_HEADER_BYTES)
        while (true) {
            val statusLine = readLine(inp, limit)
            val parts = statusLine.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw Failure(Reason.PROTOCOL, "Not an HTTP response.")
            val status = parts[1].toIntOrNull() ?: throw Failure(Reason.PROTOCOL, "Bad status line.")
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(inp, limit)
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            if (status == 100) continue // 100 Continue: the real response follows
            return status to headers
        }
    }

    private fun copy(inp: TimedInput, out: OutputStream, length: Long, maxBytes: Long, exact: Boolean): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < length) {
            val n = inp.read(buf, 0, minOf(buf.size.toLong(), length - total).toInt())
            if (n < 0) { if (exact) throw Failure(Reason.PROTOCOL, "The body ended early."); break }
            total += n
            if (total > maxBytes) throw Failure(Reason.TOO_LARGE, "Larger than ${maxBytes / 1024 / 1024} MB.")
            out.write(buf, 0, n)
        }
        return total
    }

    private fun readChunked(inp: TimedInput, out: OutputStream, maxBytes: Long): Long {
        var total = 0L
        val limit = intArrayOf(Int.MAX_VALUE)
        while (true) {
            val sizeLine = readLine(inp, limit).substringBefore(';').trim()
            val size = sizeLine.toLongOrNull(16) ?: throw Failure(Reason.PROTOCOL, "Bad chunk.")
            if (size == 0L) {
                runCatching { while (readLine(inp, intArrayOf(MAX_HEADER_BYTES)).isNotEmpty()) { /* trailers */ } }
                return total
            }
            if (total + size > maxBytes) throw Failure(Reason.TOO_LARGE, "Larger than ${maxBytes / 1024 / 1024} MB.")
            total += copy(inp, out, size, maxBytes, exact = true)
            readLine(inp, intArrayOf(8)) // CRLF after the chunk
        }
    }
}
