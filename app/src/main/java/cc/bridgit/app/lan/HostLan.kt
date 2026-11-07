package cc.bridgit.app.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.DatagramSocket
import java.net.SocketException

class HostLan(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    val network: Network
    val local: String
    val prefix: Int
    val gateways: List<String>
    init {
        val candidates = cm.allNetworks.mapNotNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return@mapNotNull null
            val lp = cm.getLinkProperties(n) ?: return@mapNotNull null
            val a = lp.linkAddresses.firstOrNull { it.address is Inet4Address && LanRules.ipv4(it.address.hostAddress.orEmpty()) != null } ?: return@mapNotNull null
            Triple(n, lp, a)
        }
        require(candidates.size == 1) { "Connect exactly one private Wi-Fi or Ethernet LAN; VPN/mobile interfaces are not discovery targets." }
        val (n, lp, a) = candidates.single()
        network = n; local = a.address.hostAddress!!; prefix = a.prefixLength
        gateways = lp.routes.mapNotNull { it.gateway?.hostAddress }.filter { LanRules.onLink(it, local, prefix) }.distinct()
    }
    /** Persist across reconnects only when Android exposes a specific Wi-Fi access point. */
    @Suppress("DEPRECATION")
    fun historyScope(context: Context): String {
        val wifi = cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val info = if (wifi) runCatching {
            context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java).connectionInfo
        }.getOrNull() else null
        val bssid = info?.bssid?.lowercase().orEmpty()
        val specific = bssid.matches(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")) && bssid !in listOf("02:00:00:00:00:00", "00:00:00:00:00:00")
        val identity = if (specific) {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bssid.toByteArray()).joinToString("") { "%02x".format(it) }
            "wifi:$hash"
        } else "session:${android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)}:${network.networkHandle}"
        return "$identity:$prefix:${gateways.sorted().joinToString(",")}"
    }
    fun bindDiscoverySocket(socket: DatagramSocket, destination: InetAddress) {
        try { network.bindSocket(socket) }
        catch (e: SocketException) {
            val errno = (e.cause as? android.system.ErrnoException)?.errno
            if (errno !in listOf(android.system.OsConstants.EPERM, android.system.OsConstants.EACCES)) throw e
            val active = cm.activeNetwork ?: throw e
            if (cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true ||
                !DiscoveryRouting.outsideVpn(cm.getLinkProperties(active)?.routes, destination)) throw e
            // Match the existing camera connector: the kernel still enforces VPN policy.
            // The socket is subsequently source-bound to this directly connected LAN.
        }
    }
    fun check(host: String) { require(LanRules.onLink(host, local, prefix)) { "Target must be a literal private IPv4 host on the Android device's directly connected LAN." } }
    fun address(host: String): InetAddress { check(host); return InetAddress.getByName(host) }
    fun json() = JSONObject().put("address", local).put("prefix_length", prefix).put("gateways", JSONArray(gateways)).put("scope", "direct_wifi_or_ethernet_ipv4")
}
