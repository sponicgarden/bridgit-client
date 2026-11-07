package cc.bridgit.app.lan

import android.net.RouteInfo
import android.os.Build
import java.net.InetAddress

/** Only use the OS default route when an active VPN explicitly leaves this destination outside it. */
object DiscoveryRouting {
    fun outsideVpn(routes: List<RouteInfo>?, destination: InetAddress): Boolean {
        if (routes == null) return false
        val route = routes.filter { it.matches(destination) }.maxByOrNull { it.destination.prefixLength }
        return route == null || Build.VERSION.SDK_INT >= 33 && route.type == RouteInfo.RTN_THROW
    }
}
