package cc.bridgit.app.satellite

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The hub's calls to its satellites, over Tailscale (docs/spec-satellites.md §6). Every call goes through the outbound
 * log (keys are redacted there). A satellite that can't be reached fails within [CONNECT_S] seconds (SAT-25);
 * reads may run long, as a microphone clip can be two minutes.
 */
object SatelliteClient {
    const val CONNECT_S = 5L
    /**
     * Plain HTTP is off app-wide (network_security_config.xml) except for names under [SUFFIX], which only this
     * client resolves, and only to a Tailscale address: 100-89-1-2.tailnet.bridgit.invalid → 100.89.1.2.
     */
    const val SUFFIX = "tailnet.bridgit.invalid"

    fun base(host: String, port: Int): String = "http://${hostName(host)}:$port"

    /** 100.89.1.2 → 100-89-1-2.tailnet.bridgit.invalid; fd7a:115c:a1e0::1 → v6-fd7a-115c-a1e0--1.tailnet.bridgit.invalid */
    fun hostName(ip: String): String {
        val n = cc.bridgit.app.core.Net.normalise(ip)
        require(cc.bridgit.app.core.Net.isTailnet(n)) { "Not a Tailscale address" }
        return (if (':' in n) "v6-" + n.replace(':', '-') else n.replace('.', '-')) + "." + SUFFIX
    }

    /** The reverse of [hostName], or null for any other name. */
    fun ipOf(name: String): String? {
        val label = name.lowercase().removeSuffix(".$SUFFIX").takeIf { it != name.lowercase() && '.' !in it } ?: return null
        val ip = if (label.startsWith("v6-")) label.removePrefix("v6-").replace('-', ':') else label.replace('-', '.')
        return ip.takeIf { cc.bridgit.app.core.Net.isTailnet(it) }
    }

    private object TailnetDns : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> =
            ipOf(hostname)?.let { listOf(java.net.InetAddress.getByName(it)) }
                ?: throw java.net.UnknownHostException("Satellites are reached only at Tailscale addresses")
    }

    private val http by lazy {
        OkHttpClient.Builder().addInterceptor(cc.bridgit.app.outbound.OutboundInterceptor).dns(TailnetDns)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(CONNECT_S, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS).build()
    }
    private val JSON = "application/json".toMediaType()

    /** [scope]: for link_revoked, "link" (the whole link is gone) or "direction". */
    class Failure(val code: String, message: String, val status: Int = 0, val scope: String? = null) : Exception(message)

    /** A call to [path] (with /v1) on [base], with the satellite's [key]. Not started. */
    fun newCall(base: String, key: String, method: String, path: String, query: List<Pair<String, String>>,
                headers: List<Pair<String, String>>, body: RequestBody?, toHub: Boolean = false): Call {
        paused(toHub)
        require(!LinkRelayClient.isRelay(base)) { "Forwarded calls don't go over the relay yet" }
        val url = base.toHttpUrl().newBuilder().encodedPath(path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val b = Request.Builder().url(url).header("Authorization", "Bearer $key")
        headers.forEach { (k, v) -> b.addHeader(k, v) }
        b.method(method, body ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(null) else null)
        return http.newCall(b.build())
    }

    /** The owner's pause switches (More › Linked devices): no call leaves this phone for a paused side. */
    private fun paused(toHub: Boolean) {
        if (toHub && LinkPause.hubsPaused) throw Failure("paused", LinkPause.HUBS_MESSAGE)
        if (!toHub && LinkPause.satellites) throw Failure("paused", LinkPause.SATELLITES_MESSAGE)
    }

    /** Blocking. Trades a link's one-time claim token for this hub's key. Returns (key, satellite name). */
    fun claim(p: SatelliteLink.Parsed, hubName: String): Pair<String, String> {
        paused(false)
        val base = base(p.host, p.port)
        val body = JSONObject().put("token", p.claim).put("hub_name", hubName).toString().toRequestBody(JSON)
        val (code, o) = exec(http.newCall(Request.Builder().url("$base/v1/satellite/claim").post(body).build()))
        if (code !in 200..299) throw Failure(o.optString("error", "http_$code"), when (o.optString("error")) {
            "unauthorized", "pairing_closed" -> "That link was already used or has expired. On the spare phone, make a new link."
            else -> o.optString("message").ifEmpty { "The spare phone said HTTP $code." }
        }, code)
        val key = o.optString("key")
        require(key.startsWith("brg_")) { "The spare phone didn't send a key." }
        return key to o.optString("name").ifBlank { p.name }
    }

    /** Blocking. The satellite's manifest (GET /v1/satellite/manifest). */
    fun manifest(base: String, key: String, hubName: String): JSONObject {
        if (LinkRelayClient.isRelay(base)) { paused(false); return LinkRelayClient.call(base, key, "GET", "/v1/satellite/manifest", null) }
        val (code, o) = exec(newCall(base, key, "GET", "/v1/satellite/manifest", emptyList(),
            listOf(SatellitePolicy.HUB_NAME_HEADER to hubName.filter { it.code in 0x20..0x7e }.take(40)), null))
        if (code !in 200..299) throw Failure(o.optString("error", "http_$code"), o.optString("message").ifEmpty { "HTTP $code" }, code, o.optString("scope").takeIf { it.isNotEmpty() })
        return o
    }

    /** Blocking. Tells the satellite what this hub offers and how to call back (POST /v1/satellite/hub-info). */
    fun hubInfo(base: String, key: String, hubName: String, body: JSONObject) {
        if (LinkRelayClient.isRelay(base)) throw Failure("not_supported", "A link over the relay has no way back yet.")
        val (code, o) = exec(newCall(base, key, "POST", "/v1/satellite/hub-info", emptyList(),
            listOf(SatellitePolicy.HUB_NAME_HEADER to hubName.filter { it.code in 0x20..0x7e }.take(40)), body.toString().toRequestBody(JSON)))
        if (code !in 200..299) throw Failure(o.optString("error", "http_$code"), o.optString("message").ifEmpty { "HTTP $code" }, code, o.optString("scope").takeIf { it.isNotEmpty() })
    }

    /** Blocking. Tells the satellite this hub removed it (POST /v1/satellite/unlink), so it drops its hub link too. */
    fun unlink(base: String, key: String) {
        if (LinkRelayClient.isRelay(base)) { paused(false); LinkRelayClient.call(base, key, "POST", "/v1/satellite/unlink", null); return }
        val (code, o) = exec(newCall(base, key, "POST", "/v1/satellite/unlink", emptyList(), emptyList(), null))
        if (code !in 200..299) throw Failure(o.optString("error", "http_$code"), o.optString("message").ifEmpty { "HTTP $code" }, code, o.optString("scope").takeIf { it.isNotEmpty() })
    }

    /**
     * Blocking. A JSON call over a link (docs/spec-linked-bridgits.md, LNK-47): [toHub] when the key is a return key
     * (this device is the other one's satellite), so the owner's pause pills apply to the right side.
     */
    fun call(base: String, key: String, method: String, path: String, body: JSONObject?, toHub: Boolean, readS: Long = 15): JSONObject {
        if (LinkRelayClient.isRelay(base)) { paused(toHub); return LinkRelayClient.call(base, key, method, path, body, readS) }
        val c = http.newBuilder().readTimeout(readS, TimeUnit.SECONDS).callTimeout(readS + CONNECT_S + 5, TimeUnit.SECONDS).build()
            .newCall(newCall(base, key, method, path, emptyList(), emptyList(), body?.toString()?.toRequestBody(JSON), toHub = toHub).request())
        val (code, o) = exec(c)
        if (code !in 200..299) throw Failure(o.optString("error", "http_$code"), o.optString("message").ifEmpty { "HTTP $code" }, code, o.optString("scope").takeIf { it.isNotEmpty() })
        return o
    }

    /** Blocking. Claims a link code on the target (POST /v1/link/claim, no key). Errors keep the target's own code (code_invalid, …). */
    fun linkClaim(base: String, claimToken: String, body: JSONObject): JSONObject {
        paused(false)
        val (code, o) = exec(http.newCall(Request.Builder().url("$base/v1/link/claim").header("Authorization", "Bearer $claimToken")
            .post(body.toString().toRequestBody(JSON)).build()))
        if (code !in 200..299) {
            val err = o.optString("error", "http_$code")
            throw cc.bridgit.app.api.ApiError(if (code in 400..599) code else 502,
                if (err in LINK_CLAIM_ERRORS) err else "upstream_failed", o.optString("message").ifEmpty { "The other Bridgit said HTTP $code." },
                if (err in LINK_CLAIM_ERRORS) emptyMap() else mapOf("upstream_error" to err))
        }
        return o
    }
    private val LINK_CLAIM_ERRORS = setOf("code_invalid", "code_expired", "code_used_up", "name_required", "conflict", "forbidden", "rate_limited", "invalid_parameter")

    /** A key the other phone no longer knows: it removed this link (or was reset). Only then is a link dropped. */
    fun isGone(e: Throwable): Boolean = e is Failure && e.status == 401 && e.code == "unauthorized"

    /**
     * Blocking. The satellite calling its hub back with the return key (see [SatelliteHub.pushHubInfo]): a JSON call
     * to [path] that throws [Failure] unless it gets 2xx. [readS] caps the wait (a photo check can take ~25 s).
     */
    fun toHub(base: String, key: String, method: String, path: String, body: JSONObject?, readS: Long = 15,
              query: List<Pair<String, String>> = emptyList()): JSONObject {
        val c = http.newBuilder().readTimeout(readS, TimeUnit.SECONDS).callTimeout(readS + CONNECT_S + 5, TimeUnit.SECONDS).build()
            .newCall(newCall(base, key, method, path, query, emptyList(), body?.toString()?.toRequestBody(JSON), toHub = true).request())
        val (code, o) = exec(c)
        if (code !in 200..299) throw Failure(o.optString("error", "http_$code"), o.optString("message").ifEmpty { "HTTP $code" }, code, o.optString("scope").takeIf { it.isNotEmpty() })
        return o
    }

    private fun exec(c: Call): Pair<Int, JSONObject> = try {
        cc.bridgit.app.outbound.Outbound.on(cc.bridgit.app.outbound.Outbound.Actor.PHONE) {
            c.execute().use { r -> r.code to (runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrDefault(JSONObject())) }
        }
    } catch (e: java.io.IOException) {
        throw Failure("satellite_offline", "The spare phone didn't answer. Check that Tailscale is on on both phones and Bridgit is running there.")
    }
}
