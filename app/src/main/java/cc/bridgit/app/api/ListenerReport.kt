package cc.bridgit.app.api

import cc.bridgit.app.BridgeApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * health's `listener` block (A0092): what the Tailscale listener is bound to, next to the Tailscale
 * addresses the phone has right now, so the connection ladder (Learn.CONNECTION_URL) can tell "the
 * phone is on the tailnet but Bridgit isn't listening there" from a network problem. Bridgit binds only
 * its Tailscale addresses (never the LAN or 0.0.0.0); `binds` says so. Read over the relay, this is
 * how an agent learns that the Tailscale side is down while the app itself is fine.
 */
object ListenerReport {
    const val BINDS = "tailscale"

    /** listening | rebinding | waiting_for_tailscale | bind_failed | stopped. */
    fun state(s: BridgeApp.ListenerState, current: List<String>): String = when {
        !s.running -> "stopped"
        s.bindError != null && s.boundTo.isEmpty() -> "bind_failed"
        s.boundTo.isEmpty() -> "waiting_for_tailscale"
        current.isNotEmpty() && s.boundTo.toSet() != current.toSet() -> "rebinding"
        current.isEmpty() -> "rebinding"
        else -> "listening"
    }

    fun json(s: BridgeApp.ListenerState, current: List<String>, port: Int, tailscaleInstalled: Boolean): JSONObject =
        JSONObject().put("state", state(s, current)).put("binds", BINDS)
            .put("bound_to", JSONArray(s.boundTo)).put("port", port)
            .put("tailscale_addresses", JSONArray(current))
            .put("on_tailnet", current.isNotEmpty())
            .put("tailscale_installed", tailscaleInstalled)
            .put("magic_dns", s.magicDns ?: JSONObject.NULL)
            .put("bind_error", s.bindError ?: JSONObject.NULL)
            .put("ladder", Learn.CONNECTION_URL)
}
