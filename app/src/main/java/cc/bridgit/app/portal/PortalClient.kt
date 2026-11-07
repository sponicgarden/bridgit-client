package cc.bridgit.app.portal

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reads another Bridgit phone through the relay, as the iOS Bridgit Portal app does (docs/ios-portal-api.md):
 * POST {relay}/d/{device}/jobs with the pairing's rly_ token. Read-only by construction: it can only send GET to
 * /v1/portal/... paths. Calls appear in this phone's outbound log.
 */
object PortalClient {
    sealed class Failure(message: String) : Exception(message) {
        /** The other phone disconnected this one, or the link is wrong: pair again. */
        class Unpaired(message: String) : Failure(message)
        /** The other phone isn't connected to the relay right now. */
        class Offline(message: String) : Failure(message)
        /** Too many calls (relay quota or backpressure): wait. */
        class Busy(message: String) : Failure(message)
        /** The other phone answered with an error (code from its API). */
        class Refused(val code: String, message: String) : Failure(message)
    }

    private val http by lazy {
        OkHttpClient.Builder().addInterceptor(cc.bridgit.app.outbound.OutboundInterceptor)
            .followRedirects(false).followSslRedirects(false).callTimeout(70, TimeUnit.SECONDS).build()
    }
    private val JSON_TYPE = "application/json".toMediaType()
    private val PATH = Regex("/v1/portal/[a-z]{3,12}")

    /** Blocking: call off the main thread. Returns the other phone's JSON reply. */
    fun get(link: PortalLinks.Link, path: String, query: Map<String, String> = emptyMap()): JSONObject {
        require(PATH.matches(path)) { "Only portal reads" }
        val q = JSONObject().put("client", "Android").apply { runCatching { put("client_type", cc.bridgit.app.platform.DeviceType.of(cc.bridgit.app.BridgeApp.instance).wire) } }
        query.forEach { (k, v) -> q.put(k, v) }
        val body = JSONObject().put("deadline", System.currentTimeMillis() + 60_000)
            .put("request", JSONObject().put("method", "GET").put("path", path).put("query", q)).toString()
        val base = "${link.relay}/d/${link.device}"
        var (code, text) = send(Request.Builder().url("$base/jobs?wait=25").header("Authorization", "Bearer ${link.token}")
            .post(body.toRequestBody(JSON_TYPE)).build())
        var polls = 0
        while (code == 202 && polls++ < 10) {
            val id = runCatching { JSONObject(text).getString("job_id") }.getOrNull() ?: break
            Thread.sleep(2000)
            val r = send(Request.Builder().url("$base/jobs/$id").header("Authorization", "Bearer ${link.token}").get().build())
            code = r.first; text = r.second
        }
        return decode(code, text)
    }

    /** A token for this phone to claim a single-use link with: same shape as the phone's own relay tokens. */
    fun newToken(): String = "rly_" + java.util.UUID.randomUUID() + java.util.UUID.randomUUID()

    /**
     * Blocking. Claims a single-use pairing link (claim=1): sends the SHA-256 of [newToken] with the link's token;
     * from then on only [newToken] works and the link is dead. Throws a [Failure] with a plain message otherwise.
     */
    fun claim(link: PortalLinks.Link, newToken: String) {
        val body = JSONObject().put("deadline", System.currentTimeMillis() + 30_000)
            .put("request", JSONObject().put("method", "POST").put("path", PortalData.CLAIM_PATH)
                .put("body", JSONObject().put("token_hash", cc.bridgit.app.relay.RelayStore.hash(newToken)))).toString()
        val base = "${link.relay}/d/${link.device}"
        var (code, text) = send(Request.Builder().url("$base/jobs?wait=20").header("Authorization", "Bearer ${link.token}")
            .post(body.toRequestBody(JSON_TYPE)).build())
        var polls = 0
        while (code == 202 && polls++ < 10) {
            val id = runCatching { JSONObject(text).getString("job_id") }.getOrNull() ?: break
            Thread.sleep(2000)
            val r = send(Request.Builder().url("$base/jobs/$id").header("Authorization", "Bearer ${link.token}").get().build())
            code = r.first; text = r.second
        }
        val reply = try { decode(code, text) } catch (e: Failure.Refused) {
            throw when (e.code) {
                "already_claimed" -> Failure.Refused(e.code, "This pairing link was already used. On the other phone, make a new link.")
                "link_expired", "claim_required" -> Failure.Refused(e.code, "This pairing link expired. On the other phone, make a new link.")
                else -> e
            }
        }
        if (!reply.optBoolean("claimed")) throw Failure.Refused("not_claimed", "The other phone didn't confirm the pairing.")
    }

    private fun send(req: Request): Pair<Int, String> = cc.bridgit.app.outbound.Outbound.on(cc.bridgit.app.outbound.Outbound.Actor.PHONE) {
        http.newCall(req).execute().use { r -> r.code to (r.body?.string().orEmpty()) }
    }

    /** The relay's answer as the other phone's JSON reply, or the matching [Failure]. Pure, so tests pin it down. */
    fun decode(code: Int, text: String): JSONObject {
        val o = runCatching { JSONObject(text) }.getOrNull()
        val err = o?.optString("error").orEmpty()
        val resp = o?.optJSONObject("response")
        if (resp != null) {
            val bytes = when {
                resp.has("body_base64") -> Base64.decode(resp.getString("body_base64"), Base64.DEFAULT)
                resp.has("body") -> resp.get("body").toString().toByteArray()
                else -> ByteArray(0)
            }
            val reply = runCatching { JSONObject(String(bytes)) }.getOrDefault(JSONObject())
            val status = resp.optInt("status", code)
            if (status in 200..299) return reply
            val c = reply.optString("error").ifEmpty { "http_$status" }
            if (c == "agent_revoked" || c == "unauthorized") throw Failure.Unpaired("That phone disconnected this one.")
            throw Failure.Refused(c, reply.optString("message").ifEmpty { "That phone said $c." })
        }
        throw when {
            code == 401 || err == "agent_revoked" || err == "unauthorized" -> Failure.Unpaired("That phone disconnected this one, or the link is no longer valid.")
            code == 503 || code == 504 || err == "phone_unreachable" -> Failure.Offline("That phone isn't reachable right now.")
            code == 429 -> Failure.Busy(if (err == "quota_exceeded") "That phone's daily allowance for this viewer is used up." else "That phone is busy. Trying again shortly.")
            code == 202 -> Failure.Busy("That phone is taking a while to answer.")
            else -> Failure.Refused(err.ifEmpty { "http_$code" }, "The relay said ${err.ifEmpty { "HTTP $code" }}.")
        }
    }
}
