package cc.bridgit.app.lan

import android.net.IpPrefix
import android.net.RouteInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DiscoveryRoutingTest {
    private val destination = InetAddress.getByName("192.168.1.255")
    private fun route(prefix: String, type: Int = RouteInfo.RTN_UNICAST): RouteInfo {
        val network = IpPrefix(InetAddress.getByName(prefix.substringBefore('/')), prefix.substringAfter('/').toInt())
        val constructor = RouteInfo::class.java.getDeclaredConstructor(IpPrefix::class.java, InetAddress::class.java, String::class.java, Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(network, null, "tun0", type)
    }
    @Test fun missingMetadataAndVpnRoutedDestinationsFailClosed() {
        assertFalse(DiscoveryRouting.outsideVpn(null, destination))
        assertFalse(DiscoveryRouting.outsideVpn(listOf(route("0.0.0.0/0")), destination))
        assertFalse(DiscoveryRouting.outsideVpn(listOf(route("192.168.1.0/24")), destination))
    }
    @Test fun onlyUnroutedOrExplicitlyExcludedDestinationsMayUseDefault() {
        assertTrue(DiscoveryRouting.outsideVpn(listOf(route("100.64.0.0/10")), destination))
        assertTrue(DiscoveryRouting.outsideVpn(listOf(route("0.0.0.0/0"), route("192.168.1.0/24", RouteInfo.RTN_THROW)), destination))
        assertFalse(DiscoveryRouting.outsideVpn(listOf(route("192.168.0.0/16", RouteInfo.RTN_THROW), route("192.168.1.0/24")), destination))
    }
}
