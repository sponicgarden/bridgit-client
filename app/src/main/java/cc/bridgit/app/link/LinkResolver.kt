package cc.bridgit.app.link

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.Net
import cc.bridgit.app.feedback.DeviceNames
import org.json.JSONObject

/**
 * Name to address (LNK-19, docs/spec-linked-bridgits.md §4.1). The target offers {code_hash, expires_at, reach} to the
 * names backend; the claiming device resolves the name with a proof of the code and gets reach back. Both calls are
 * signed by the install, as the other /v1/names calls. A name says which device; it never grants anything.
 */
object LinkResolver {
    /** Test hook: unit tests never call the names backend. */
    @Volatile internal var enabled = true

    /** Blocking. Registers an offer for a new code; returns its id. Throws when the backend refuses or can't be reached. */
    fun offer(app: BridgeApp, codeHash: String, expiresAt: Long): String {
        if (!enabled) throw ErrorCatalog.error("team_unavailable", "Names backend off (test)")
        val relay = JSONObject().put("origin", cc.bridgit.app.BuildConfig.RELAY_URL.ifEmpty { "https://bridgit-relay.bridgit-app.workers.dev" })
            .put("device", app.platform.store.deviceId)
        val reach = JSONObject().put("relay", relay)
        val l = app.listener.value
        (l.boundTo.firstOrNull { !Net.isIpv6(it) } ?: l.boundTo.firstOrNull())?.let { reach.put("tailscale", JSONObject().put("host", Net.normalise(it)).put("port", l.port)) }
        val (code, o) = DeviceNames.call(app, "POST", "/v1/names/me/offers", JSONObject().put("code_hash", codeHash).put("expires_at", expiresAt).put("reach", reach))
        if (code !in 200..299) throw mapped(code, o)
        return o.optString("id").takeIf { it.startsWith("off_") } ?: throw ErrorCatalog.error("upstream_failed", "The names backend didn't return an offer id.")
    }

    fun deleteOffer(app: BridgeApp, id: String) {
        if (!enabled) return
        if (!Regex("off_[A-Za-z0-9_-]{1,80}").matches(id)) return
        DeviceNames.call(app, "DELETE", "/v1/names/me/offers/$id", null)
    }

    /** Blocking. The target's Tailscale address and port for [name], given a [proof] of its code. Errors keep the backend's code. */
    fun resolve(app: BridgeApp, name: String, proof: String): Pair<String, Int> {
        if (!enabled) throw ErrorCatalog.error("team_unavailable", "Names backend off (test)")
        val (code, o) = DeviceNames.call(app, "POST", "/v1/names/$name/resolve", JSONObject().put("code_proof", proof))
        if (code !in 200..299) throw mapped(code, o)
        val ts = o.optJSONObject("reach")?.optJSONObject("tailscale")
            ?: throw ErrorCatalog.error("target_offline", "$name can be reached only over the relay, which this version of Bridgit doesn't use for linking yet. Turn Tailscale on on both devices and make a new code.")
        val port = ts.optInt("port").takeIf { it in 1..65535 } ?: throw ErrorCatalog.error("upstream_failed", "The names backend sent no port.")
        return tailnetHost(ts.optString("host"), name) to port
    }

    /** A Tailscale address for [host] (an address, or a MagicDNS name that resolves to one). */
    fun tailnetHost(host: String, name: String): String {
        val direct = Net.normalise(host)
        if (Net.isTailnet(direct)) return direct
        val ip = runCatching { java.net.InetAddress.getAllByName(host).map { Net.normalise(it.hostAddress ?: "") }.firstOrNull { Net.isTailnet(it) } }.getOrNull()
        return ip ?: throw ErrorCatalog.error("target_offline", "$name's address isn't on this device's Tailscale network. Turn Tailscale on on both devices.")
    }

    private fun mapped(code: Int, o: JSONObject): Exception {
        val err = o.optString("error"); val msg = o.optString("message").ifEmpty { "The names backend answered $code." }
        return when (err) {
            "code_invalid", "code_expired", "code_used_up", "name_required", "invalid_parameter" -> ErrorCatalog.error(err, msg)
            "rate_limited" -> ErrorCatalog.error("rate_limited", msg, mapOf("retry_after_s" to 3600))
            else -> ErrorCatalog.error("upstream_failed", msg, mapOf("service" to "bridgit names", "upstream_status" to code, "upstream_error" to err.ifEmpty { null }))
        }
    }
}
