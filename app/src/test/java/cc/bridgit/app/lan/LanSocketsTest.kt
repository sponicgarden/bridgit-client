package cc.bridgit.app.lan

import cc.bridgit.app.core.SafeHttp
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketException

/**
 * Report A00102: on a phone running Tailscale (a VPN apps can't bypass) Android refuses to bind device traffic to
 * the Wi-Fi network, and a tailnet subnet route for the same 192.168.1.0/24 at another site sends the speaker's
 * address into the tailnet. Enrollment then failed with the generic "LAN device unavailable" message.
 */
class LanSocketsTest {
    private val host = "192.168.1.64"

    @Test fun bindsToTheLanWhenAndroidAllowsIt() {
        var bound = 0
        LanSockets(host, { bound++; true }, { fail("routes aren't consulted when the bind works"); false }).createSocket().use { s ->
            assertFalse(s.isClosed)
        }
        assertEquals(1, bound)
    }

    @Test fun usesTheDefaultRouteWhenTheVpnRefusesTheBindButLeavesTheHostAlone() {
        LanSockets(host, { false }, { true }).createSocket().use { assertFalse(it.isClosed) }
    }

    @Test fun namesTheTailscaleSettingWhenTheVpnRoutesTheHostElsewhere() {
        val e = assertThrows(LanUnreachable::class.java) { LanSockets(host, { false }, { false }).createSocket() }
        assertTrue(e.message!!, e.message!!.contains(host) && e.message!!.contains("Use Tailscale subnets"))
    }

    @Test fun otherBindFailuresStayFailures() {
        assertThrows(SocketException::class.java) { LanSockets(host, { throw SocketException("ENETUNREACH") }, { true }).createSocket() }
    }

    /** The refusal reaches LanModule unchanged (as LanUnreachable, not a generic network error), before anything is sent. */
    @Test fun safeHttpPassesTheRefusalThroughBeforeConnecting() {
        val e = assertThrows(LanUnreachable::class.java) {
            SafeHttp.localPack("http://$host:49152/description.xml", emptyMap(), null, LanSockets(host, { false }, { false }))
        }
        assertEquals(LanSockets.vpnRoutes(host), e.message)
    }
}
