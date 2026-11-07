package cc.bridgit.app.remoteview

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteViewLinkTest {
    @Test fun retriesUntilClaimThenStopsWithoutWaitingForHeartbeat() = runBlocking {
        var time = 0L; var checks = 0
        RemoteViewLink.awaitClaim(600_000, { true }, { ++checks == 2 }, { time }, { time += it })
        assertEquals(2, checks)
        assertEquals(6_000L, time)
    }

    @Test fun expiredCodeCannotKeepPolling() = runBlocking {
        var time = 0L; var checks = 0
        RemoteViewLink.awaitClaim(1_000, { true }, { checks++; false }, { time }, { time += it })
        assertEquals(0, checks)
        assertEquals(1_000L, time)
    }

    @Test fun optingOutWhileBrowserIsOpenStopsBeforeAnotherRequest() = runBlocking {
        var enabled = true; var checks = 0
        RemoteViewLink.awaitClaim(600_000, { enabled }, { checks++; false }, { 0L }, { enabled = false })
        assertEquals(0, checks)
    }

    @Test fun alreadyLinkedOrDisabledNeverStartsPolling() = runBlocking {
        var checks = 0; var pauses = 0
        RemoteViewLink.awaitClaim(600_000, { false }, { checks++; false }, { 0L }, { pauses++ })
        assertEquals(0, checks)
        assertEquals(0, pauses)
    }
}
