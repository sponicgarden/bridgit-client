package cc.bridgit.app.ui.addagent

import cc.bridgit.app.api.Address
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PairingEndpointTest {
    private fun live(port: Int = 8734) = PairingEndpoint.Snapshot(true, true,
        Address("pixel-2-xl.tail1234.ts.net", "100.64.0.10", port), port)

    @Test fun usesCurrentPortInsteadOfEarlierPromptPort() = runBlocking {
        var state = live(18529)
        assertEquals(18529, PairingEndpoint.verified({ state }) { true }!!.port)
        state = live(8734)
        val address = PairingEndpoint.verified({ state }) { it.port == 8734 }!!
        assertEquals("http://pixel-2-xl.tail1234.ts.net:8734/v1", address.baseUrl)
        assertEquals("http://100.64.0.10:8734/v1", address.ipBaseUrl)
    }

    @Test fun refusesStoppedPausedUnboundAndPendingRebind() = runBlocking {
        val live = live()
        for (state in listOf(live.copy(enabled = false), live.copy(listening = false),
            live.copy(address = live.address.copy(ip = null)), live.copy(configuredPort = 18529))) {
            assertNull(PairingEndpoint.verified({ state }) { fail("Must not probe an unready listener"); true })
        }
    }

    @Test fun refusesUnreachablePort() = runBlocking {
        assertNull(PairingEndpoint.verified({ live(18529) }) { false })
    }

    @Test fun refusesAddressChangeDuringProbe() = runBlocking {
        var state = live(18529)
        assertNull(PairingEndpoint.verified({ state }) { state = live(8734); true })
        assertEquals(8734, PairingEndpoint.verified({ state }) { true }!!.port)
    }

    @Test fun refusesPauseDuringProbe() = runBlocking {
        var state = live()
        assertNull(PairingEndpoint.verified({ state }) { state = state.copy(enabled = false); true })
    }
}
