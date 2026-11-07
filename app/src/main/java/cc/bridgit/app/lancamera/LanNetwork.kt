package cc.bridgit.app.lancamera

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress
import javax.net.SocketFactory

/** Bind camera traffic to its directly connected Wi-Fi, leaving the default VPN and mobile data alone. */
object LanNetwork {
    internal fun sameSubnet(network: ByteArray, address: ByteArray, prefix: Int): Boolean {
        if (network.size != address.size || prefix !in 0..network.size * 8) return false
        return network.indices.all { i ->
            val bits = (prefix - i * 8).coerceIn(0, 8)
            val mask = (0xff shl (8 - bits)) and 0xff
            (network[i].toInt() and mask) == (address[i].toInt() and mask)
        }
    }
    fun sockets(context: Context, host: String): SocketFactory {
        require(LanCamera.localHost(host))
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val address = InetAddress.getByName(host) // validated literal; never resolves a host name
        val network = cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                cm.getLinkProperties(n)?.linkAddresses?.any { link ->
                    sameSubnet(link.address.address, address.address, link.prefixLength)
                } == true
        } ?: throw CameraFailure("Connect to the camera's Wi-Fi network. The camera must be on the same local subnet.")
        val active = cm.activeNetwork
        if (active != null && cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            val route = cm.getLinkProperties(active)?.routes?.filter { it.matches(address) }?.maxByOrNull { it.destination.prefixLength }
            if (route != null && (android.os.Build.VERSION.SDK_INT < 33 || route.type != android.net.RouteInfo.RTN_THROW))
                throw CameraFailure("Your VPN routes this camera address to another network. In Tailscale: Settings → Subnet routing → turn off Use Tailscale subnets while using this camera. Tailscale can stay connected.")
            // Android VPNs can forbid explicit network binding even for destinations outside their routes.
            return SocketFactory.getDefault()
        }
        return network.socketFactory
    }
}
