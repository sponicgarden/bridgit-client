package cc.bridgit.app.lan

import cc.bridgit.app.core.SafeHttp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Adapters get fixed operations, not an arbitrary URL, RPC, shell, port or credential search. */
interface LanAdapter {
    val id: String
    fun execute(device: LanDevice, action: String, channel: Int): JSONObject
}
fun interface LanTransport { fun request(path: String, body: JSONObject?, basicAuth: Boolean): JSONObject }

object LanAdapters {
    fun guarded(authorized: () -> Unit, delegate: LanTransport) = LanTransport { path, body, basic ->
        authorized(); delegate.request(path, body, basic)
    }
    fun transport(lan: HostLan, d: LanDevice, authorized: () -> Unit): LanTransport = guarded(authorized, LanTransport { path, body, basic ->
        lan.check(d.host)
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (basic && d.username.isNotEmpty()) headers["Authorization"] = "Basic " + Base64.getEncoder().encodeToString("${d.username}:${d.password}".toByteArray())
        val r = SafeHttp.localDevice(d.host, path, !d.http, headers, body?.toString()?.toByteArray(), lan.network.socketFactory)
        require(r.status == 200) { "Device refused the operation; check authorized credentials and adapter support." }
        JSONObject(String(requireNotNull(r.bytes), Charsets.UTF_8))
    })
    fun adapter(id: String, transport: LanTransport): LanAdapter = when (id) {
        "shelly_gen1" -> ShellyAdapter(transport)
        "openwrt_ubus" -> OpenWrtAdapter(transport)
        else -> throw IllegalArgumentException("Unsupported adapter.")
    }
}

class ShellyAdapter(private val transport: LanTransport) : LanAdapter {
    override val id = "shelly_gen1"
    override fun execute(device: LanDevice, action: String, channel: Int): JSONObject {
        LanRules.validateAction(id, action, channel)
        val info = transport.request("/shelly", null, true)
        val mac = info.optString("mac")
        require(mac.matches(Regex("[0-9A-Fa-f]{12}")) && info.optString("type").startsWith("SH") && !info.has("gen")) { "Not a supported Shelly Gen1 device." }
        require(device.identity.isEmpty() || device.identity.equals(mac, true)) { "Device identity changed; enrollment must be renewed." }
        val status = transport.request("/status", null, true)
        val relays = status.optJSONArray("relays") ?: throw IllegalArgumentException("This Shelly has no supported relays.")
        require(relays.length() in 1..4)
        val out = JSONObject().put("identity", mac).put("model", info.optString("type").take(60))
        if (action != "status") {
            require(channel < relays.length()) { "Relay channel is not advertised by this device." }
            val on = action == "relay_on"
            val reply = transport.request("/relay/$channel?turn=${if (on) "on" else "off"}", null, true)
            require(reply.opt("ison") == on) { "Device did not confirm the requested relay state; outcome unknown." }
            out.put("channel", channel).put("on", on)
        } else out.put("relays", JSONArray((0 until relays.length()).map { i ->
            JSONObject().put("channel", i).put("on", relays.getJSONObject(i).opt("ison") as? Boolean ?: JSONObject.NULL)
        }))
        return out
    }
}

/** Read-only ubus ACLs are sufficient. Router config, reboot, firewall and arbitrary RPC are absent. */
class OpenWrtAdapter(private val transport: LanTransport) : LanAdapter {
    override val id = "openwrt_ubus"
    private fun call(session: String, obj: String, method: String, args: JSONObject): JSONObject {
        val response = transport.request("/ubus", JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "call")
            .put("params", JSONArray().put(session).put(obj).put(method).put(args)), false)
        val r = response.optJSONArray("result") ?: throw IllegalArgumentException("Router did not return a ubus result.")
        require(r.optInt(0, -1) == 0) { "Router refused authentication or this read-only ACL." }
        return r.optJSONObject(1) ?: JSONObject()
    }
    override fun execute(device: LanDevice, action: String, channel: Int): JSONObject {
        LanRules.validateAction(id, action, channel)
        require(device.username.isNotBlank() && device.password.isNotEmpty()) { "Authorized router credentials are required." }
        val session = call("0".repeat(32), "session", "login", JSONObject().put("username", device.username).put("password", device.password).put("timeout", 30))
            .optString("ubus_rpc_session")
        require(session.matches(Regex("[a-fA-F0-9]{32}"))) { "Router authentication failed." }
        try {
            return if (action == "status") {
                val board = call(session, "system", "board", JSONObject())
                require(board.has("model") && board.has("release")) { "Not an OpenWrt board response." }
                JSONObject().put("model", board.optString("model").take(120)).put("availability", "online")
            } else {
                val leases = call(session, "dhcp", "ipv4leases", JSONObject()).optJSONArray("dhcp_leases") ?: JSONArray()
                JSONObject().put("leases", JSONArray((0 until minOf(leases.length(), 254)).mapNotNull { i ->
                    val l = leases.optJSONObject(i) ?: return@mapNotNull null
                    val host = l.optString("ipaddr")
                    if (LanRules.ipv4(host) == null) return@mapNotNull null
                    JSONObject().put("host", host).put("hostname", l.optString("hostname").filter { it >= ' ' }.take(120))
                        .put("mac", l.optString("macaddr").take(32)).put("source", "router_dhcp").put("availability", "not_checked")
                }))
            }
        } finally { runCatching { call(session, "session", "destroy", JSONObject()) } }
    }
}
