package cc.bridgit.app.outbound

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * Records every call made through an OkHttp client (team backend, Jev, relay HTTP, the relay socket's upgrade).
 * Add it to every OkHttpClient the app builds. Body bytes are read from a copy; one-shot or very large bodies
 * are measured but not previewed. WebSocket frames don't pass through here: RelayClient records those itself.
 */
object OutboundInterceptor : Interceptor {
    private const val MAX_BUFFER = 2L * 1024 * 1024

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val started = System.currentTimeMillis()
        val headers = LinkedHashMap<String, String>()
        req.headers.forEach { (k, v) -> headers[k] = v }
        val body = req.body
        val type = body?.contentType()?.toString()
        type?.let { headers.putIfAbsent("Content-Type", it) }
        val length = runCatching { body?.contentLength() ?: 0L }.getOrDefault(-1L)
        val bytes: ByteArray? = runCatching {
            if (body == null || body.isOneShot() || body.isDuplex() || length > MAX_BUFFER) null
            else Buffer().also { body.writeTo(it) }.readByteArray()
        }.getOrNull()
        val url = req.url.toString().let { if (req.header("Upgrade").equals("websocket", true)) it.replaceFirst("https://", "wss://") else it }
        val transport = if (url.startsWith("wss://")) "wss" else req.url.scheme
        if (runCatching { Outbound.paused() }.getOrDefault(false)) {
            Outbound.record(url, req.method, transport, headers, bytes, if (bytes == null) length.coerceAtLeast(0) else bytes.size.toLong(), type,
                status = 0, error = "paused: " + Outbound.Paused.MESSAGE, startedAt = started)
            throw Outbound.Paused()
        }
        val actor = Outbound.actor(); val hint = Outbound.hint()
        val res = try { chain.proceed(req) } catch (e: Exception) {
            Outbound.record(url, req.method, transport, headers, bytes, if (bytes == null) length.coerceAtLeast(0) else bytes.size.toLong(), type,
                status = 0, error = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""), startedAt = started, actor = actor, hint = hint)
            throw e
        }
        val rb = res.body
        val cap = Redact.MAX_RESPONSE_PREVIEW.toLong() * 2
        val sample = runCatching { if (rb == null || res.code == 101) null else res.peekBody(cap).bytes() }.getOrNull()
        // A chunked reply has no length up front; when it fit in the sample, the sample is its size.
        val receivedBytes = rb?.contentLength()?.takeIf { it >= 0 } ?: sample?.takeIf { it.size < cap }?.size?.toLong() ?: -1L
        Outbound.record(url, req.method, transport, headers, bytes, if (bytes == null) length.coerceAtLeast(0) else bytes.size.toLong(), type,
            status = res.code, receivedBytes = receivedBytes, received = sample, receivedType = rb?.contentType()?.toString(),
            startedAt = started, actor = actor, hint = hint)
        return res
    }
}
