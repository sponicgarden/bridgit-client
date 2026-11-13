package cc.bridgit.app.satellite

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Link calls to a linked Bridgit reached only over the relay (docs/spec-linked-bridgits.md §4.1), as the Apple
 * Portal makes them: POST {relay}/d/{device}/jobs with an rly_ token. A link's base is that https URL; Tailscale bases
 * are http://….tailnet.bridgit.invalid, so the scheme says which transport a stored base uses.
 * Failures are [SatelliteClient.Failure]s with the same codes, so link_revoked and "key gone" handling is shared.
 */
object LinkRelayClient {
    private val DEVICE = Regex("[A-Za-z0-9_-]{8,128}")
    private val TOKEN = Regex("rly_[A-Za-z0-9_-]{16,200}")

    /** Test hook: (url, bearer, JSON body or null for GET) -> (HTTP status, body). */
    @Volatile internal var transport: ((String, String, String?) -> Pair<Int, String>)? = null

    fun isRelay(base: String): Boolean = base.startsWith("https://")

    /** {origin}/d/{device}, accepting only https with no user, query or fragment (the claim token is sent there). Null otherwise. */
    fun base(origin: String, device: String): String? {
        if (!DEVICE.matches(device)) return null
        val u = runCatching { java.net.URI(origin.trim()) }.getOrNull() ?: return null
        if (u.scheme != "https" || u.host.isNullOrEmpty() || u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null) return null
        var path = u.rawPath.orEmpty().trimEnd('/')
        if (!path.endsWith("/d/$device")) path += "/d/$device"
        return "https://${u.rawAuthority}$path"
    }

    fun validToken(t: String) = TOKEN.matches(t)

    /** A new relay credential this device makes for a link; the other device stores only its hash. */
    fun newToken(): String = "rly_" + ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Blocking. A JSON call to [path] (with /v1) on the linked Bridgit at [base]. Throws [SatelliteClient.Failure] unless it answers 2xx. */
    fun call(base: String, token: String, method: String, path: String, body: JSONObject?, readS: Long = 15): JSONObject {
        val (status, o) = job(base, token, method, path, body, readS)
        // The other device answered that this link's relay agent is gone (as a removed key over Tailscale answers 401).
        // A 403 agent_revoked ("relay authority changed") is not that, and never drops a link.
        if (status == 401 && o.optString("error") in setOf("agent_revoked", "unauthorized"))
            throw SatelliteClient.Failure("unauthorized", "The other Bridgit no longer knows this link.", 401)
        if (status !in 200..299) throw SatelliteClient.Failure(o.optString("error", "http_$status"), o.optString("message").ifEmpty { "HTTP $status" },
            status, o.optString("scope").takeIf { it.isNotEmpty() })
        return o
    }

    /**
     * Blocking. Sends one job and waits for the answer: (the other device's HTTP status, its JSON reply). Relay-level
     * refusals come back as the same shape: 401 unauthorized = the relay no longer knows [token] (the other device
     * removed it); an unreachable device throws satellite_offline, never "gone".
     */
    fun job(base: String, token: String, method: String, path: String, body: JSONObject?, readS: Long = 15): Pair<Int, JSONObject> {
        require(isRelay(base) && path.startsWith("/v1/")) { "Not a relay link call" }
        val wait = readS.coerceIn(5, 25)
        val req = JSONObject().put("method", method).put("path", path)
        if (body != null) req.put("body", body)
        val job = JSONObject().put("deadline", System.currentTimeMillis() + (wait + 10) * 1000).put("request", req).toString()
        var (code, text) = send("$base/jobs?wait=$wait", token, job)
        var polls = 0
        while (code == 202 && polls++ < 5) {
            val id = runCatching { JSONObject(text).getString("job_id") }.getOrNull()?.takeIf { Regex("[A-Za-z0-9_-]{1,128}").matches(it) } ?: break
            if (transport == null) Thread.sleep(2000)
            val r = send("$base/jobs/$id", token, null)
            code = r.first; text = r.second
        }
        return decode(code, text)
    }

    /** The relay's answer as (status, the other device's JSON), or a [SatelliteClient.Failure]. Pure, so tests pin it down. */
    fun decode(code: Int, text: String): Pair<Int, JSONObject> {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
        o.optJSONObject("response")?.let { resp ->
            val bytes = when {
                resp.has("body_base64") -> java.util.Base64.getMimeDecoder().decode(resp.getString("body_base64"))
                resp.has("body") -> resp.get("body").toString().toByteArray()
                else -> ByteArray(0)
            }
            return resp.optInt("status", code) to (runCatching { JSONObject(String(bytes)) }.getOrNull() ?: JSONObject())
        }
        val err = o.optString("error")
        throw when {
            code == 401 -> SatelliteClient.Failure("unauthorized", "The other Bridgit no longer knows this link.", 401)
            code == 503 || code == 504 || code == 202 || err == "phone_unreachable" ->
                SatelliteClient.Failure("satellite_offline", "The other Bridgit isn't connected to the relay right now.", code)
            code == 429 -> SatelliteClient.Failure("rate_limited", "The relay is busy for this link. Try again shortly.", 429)
            else -> SatelliteClient.Failure(err.ifEmpty { "http_$code" }, o.optString("message").ifEmpty { "The relay answered HTTP $code." }, code)
        }
    }

    private val http by lazy {
        OkHttpClient.Builder().addInterceptor(cc.bridgit.app.outbound.OutboundInterceptor)
            .followRedirects(false).followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS)
            // Longer than the longest relay wait (25 s), so a slow but successful answer is never cut off (a claim is single-use).
            .readTimeout(40, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    }
    private val JSON = "application/json".toMediaType()

    private fun send(url: String, token: String, body: String?): Pair<Int, String> {
        transport?.let { return it(url, token, body) }
        val b = Request.Builder().url(url).header("Authorization", "Bearer $token")
        if (body != null) b.post(body.toRequestBody(JSON)) else b.get()
        return try {
            cc.bridgit.app.outbound.Outbound.on(cc.bridgit.app.outbound.Outbound.Actor.PHONE) {
                http.newCall(b.build()).execute().use { r -> r.code to (r.body?.string().orEmpty()) }
            }
        } catch (e: java.io.IOException) {
            throw SatelliteClient.Failure("satellite_offline", "The relay couldn't be reached. Check this device's internet connection.")
        }
    }
}
