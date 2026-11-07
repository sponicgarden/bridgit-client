package cc.bridgit.app.lan

import cc.bridgit.app.core.SafeHttp
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class LanRulesTest {
    @Test fun inventoryNeverExpandsToRoutedVpnPublicOrSelfTargets() {
        for (h in listOf("127.0.0.1", "100.83.104.121", "8.8.8.8", "169.254.1.1", "192.168.1.2:80", "192.168.01.2", "localhost", "::1", "10.1.2.256")) assertNull(h, LanRules.ipv4(h))
        assertFalse(LanRules.onLink("192.168.2.20", "192.168.1.10", 24))
        assertFalse(LanRules.onLink("192.168.1.0", "192.168.1.10", 24))
        assertFalse(LanRules.onLink("192.168.1.255", "192.168.1.10", 24))
        assertFalse(LanRules.onLink("192.168.1.10", "192.168.1.10", 24))
        assertTrue(LanRules.onLink("192.168.1.0", "192.168.0.10", 23))
        assertTrue(SafeHttp.allowed(InetAddress.getByName("192.168.1.0"), SafeHttp.Policy.LOCAL_DEVICE))
        val hosts = LanRules.boundedHosts("192.168.1.10", 24)
        assertEquals(253, hosts.size); assertFalse(hosts.contains("192.168.1.10"))
        assertEquals(1, LanRules.boundedHosts("10.0.0.1", 30).size)
    }
    @Test(expected = IllegalArgumentException::class) fun refusesLargeSubnetSweep() { LanRules.boundedHosts("10.0.0.1", 8) }
    @Test fun adapterHttpIsNotAnArbitraryLocalProxy() {
        val p = SafeHttp.Policy.LOCAL_DEVICE
        for (url in listOf("http://192.168.1.1/ubus", "https://192.168.1.2/status", "http://192.168.1.2/relay/3?turn=off")) assertNull(url, SafeHttp.urlProblem(url, p))
        for (url in listOf("http://8.8.8.8/ubus", "http://localhost/ubus", "http://192.168.1.1:2020/status", "http://user:pass@192.168.1.1/ubus", "http://192.168.1.1/ubus?x=1", "http://192.168.1.1/relay/4?turn=on", "http://192.168.1.1/relay/0?turn=toggle", "http://192.168.1.1/settings", "http://192.168.1.1/../ubus")) assertNotNull(url, SafeHttp.urlProblem(url, p))
        assertNotNull(SafeHttp.urlProblem("http://192.168.1.2/status", SafeHttp.Policy.PUBLIC))
    }
    @Test(expected = IllegalArgumentException::class) fun unknownAdaptersCannotControl() { LanRules.validateAction("unknown", "relay_on", 0) }
    @Test(expected = IllegalArgumentException::class) fun routerHasNoWriteActions() { LanRules.validateAction("openwrt_ubus", "reboot", 0) }
}
