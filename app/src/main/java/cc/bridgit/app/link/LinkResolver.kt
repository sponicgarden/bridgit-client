package cc.bridgit.app.link

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.Net
import cc.bridgit.app.feedback.DeviceNames
import cc.bridgit.app.satellite.LinkRelayClient
import org.json.JSONObject

/**
 * Name to address (LNK-19, docs/spec-linked-bridgits.md §4.1). The target offers {code_hash, expires_at, reach} to the
 * names backend; the claiming device resolves the name with a proof of the code and gets reach back. Both calls are
 * signed by the install, as the other /v1/names calls. A name says which device; it never grants anything.
 */
object LinkResolver {
    /** Test hook: unit tests never call the names backend. */
    @Volatile internal var enabled = true
    /** Test hook: a fake names backend (method, path, body) -> (status, reply), used even when [enabled] is off. */
    @Volatile internal var names: ((String, String, JSONObject?) -> Pair<Int, JSONObject>)? = null

    /** Where a claim goes: the target's Tailscale address, or its relay base (https://…/d/{device}). */
    sealed class Reach {
        data class Tailscale(val host: String, val port: Int) : Reach()
        data class Relay(val base: String) : Reach()
    }

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

    /**
     * Blocking. Where to claim [name], given a [proof] of its code. Errors keep the backend's code. Tailscale when the
     * target offers it and this device is on Tailscale too; otherwise the relay (§4.1). Chosen before anything is
     * sent: the code works once, so a claim is never retried on the other transport.
     */
    fun resolve(app: BridgeApp, name: String, proof: String): Reach {
        val fake = names
        if (!enabled && fake == null) throw ErrorCatalog.error("team_unavailable", "Names backend off (test)")
        val body = JSONObject().put("code_proof", proof)
        val (code, o) = fake?.invoke("POST", "/v1/names/$name/resolve", body) ?: DeviceNames.call(app, "POST", "/v1/names/$name/resolve", body)
        if (code !in 200..299) throw mapped(code, o)
        // This device's own Tailscale addresses, whether or not its listener is bound right now.
        val onTailnet = cc.bridgit.app.BridgeService.tailscaleAddresses().isNotEmpty() || app.listener.value.boundTo.any { Net.isTailnet(Net.normalise(it)) }
        return choose(o.optJSONObject("reach"), name, onTailnet)
    }

    /** Picks the transport from the backend's reach. Pure apart from MagicDNS lookups, so tests pin it down. */
    fun choose(reach: JSONObject?, name: String, onTailnet: Boolean): Reach {
        val relay = reach?.optJSONObject("relay")?.let { r -> LinkRelayClient.base(r.optString("origin"), r.optString("device")) }
        val ts = reach?.optJSONObject("tailscale")
        if (ts != null && (onTailnet || relay == null)) {
            val port = ts.optInt("port").takeIf { it in 1..65535 }
            val host = runCatching { tailnetHost(ts.optString("host"), name) }
            if (port != null && host.isSuccess) return Reach.Tailscale(host.getOrThrow(), port)
            if (relay == null) {
                if (port == null) throw ErrorCatalog.error("upstream_failed", "The names backend sent no port.")
                throw host.exceptionOrNull()!!
            }
        }
        if (relay != null) return Reach.Relay(relay)
        if (reach?.has("relay") == true) throw ErrorCatalog.error("upstream_failed", "The names backend gave a relay address this device can't use for $name.")
        throw ErrorCatalog.error("target_offline", "$name gave no address. Check that Bridgit is running there, then make a new code.")
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
