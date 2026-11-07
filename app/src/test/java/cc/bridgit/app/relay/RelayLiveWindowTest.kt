package cc.bridgit.app.relay

import cc.bridgit.app.api.ApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regression for live_request_expired on every live call (Nov 2026): the owner's Pixel 10 clock ran ~0.95 s
 * behind the relay, so "now - relay created_at" came out negative and the 0..5000 ms check refused everything.
 */
class RelayLiveWindowTest {
    private val phoneNow=1_794_000_000_000L

    @Test fun phoneClockBehindRelayNoLongerRejectsAFreshJob(){
        val phoneNow=System.currentTimeMillis()
        // Relay stamped created_at 950 ms "in the phone's future"; the job waited 30 ms in the relay.
        val start=RelayDispatcher.liveStart(phoneNow,phoneNow+950,ageMs=30)
        assertEquals(phoneNow-30,start)
        RelayDispatcher.requireLive(start)
    }
    @Test fun phoneClockAheadOfRelayStillTimesFromReceipt(){
        val phoneNow=System.currentTimeMillis()
        val start=RelayDispatcher.liveStart(phoneNow,phoneNow-4_000,ageMs=100)
        assertEquals(phoneNow-100,start);RelayDispatcher.requireLive(start)
    }
    @Test fun aJobThatWaitedTooLongInTheRelayIsStillRefused(){
        assertThrows(ApiError::class.java){RelayDispatcher.requireLive(RelayDispatcher.liveStart(System.currentTimeMillis(),0L,ageMs=6_000))}
    }
    @Test fun olderRelayWithoutAgeToleratesSmallSkewOnly(){
        assertEquals(phoneNow,RelayDispatcher.liveStart(phoneNow,phoneNow+950,ageMs=null))
        assertEquals(phoneNow+10_000,RelayDispatcher.liveStart(phoneNow,phoneNow+10_000,ageMs=null))
        assertEquals(phoneNow-300,RelayDispatcher.liveStart(phoneNow,phoneNow-300,ageMs=null))
    }
    @Test fun sealedJobNeedsTheAgentsAuthenticatedTimeToBeLiveToo(){
        // Agent clock 1.5 s ahead of the phone: allowed.
        assertEquals(phoneNow-20,RelayDispatcher.liveStart(phoneNow,phoneNow,20,agentCreatedAt=phoneNow+1_500))
        // A relay that held a sealed request for a minute and lied about age_ms is caught by the agent's timestamp.
        val held=RelayDispatcher.liveStart(System.currentTimeMillis(),0L,20,agentCreatedAt=System.currentTimeMillis()-60_000)
        assertThrows(ApiError::class.java){RelayDispatcher.requireLive(held)}
    }
}
