package cc.bridgit.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleReconnectTest {
    @Test fun firstTryIsImmediateThenBacksOffToCap() {
        val r = TailscaleReconnect(minGapMs = 60_000, maxGapMs = 300_000)
        assertTrue(r.due(0))
        assertFalse(r.due(59_999))
        assertEquals(1, r.waitMs(59_999))
        assertTrue(r.due(60_000))   // gap 1 min
        assertFalse(r.due(179_999))
        assertTrue(r.due(180_000))  // gap 2 min
        assertTrue(r.due(420_000))  // gap 4 min
        assertTrue(r.due(720_000))  // capped at 5 min
        assertTrue(r.due(1_020_000))
        assertEquals(6, r.attempts)
    }

    @Test fun resetStartsOver() {
        val r = TailscaleReconnect(minGapMs = 60_000, maxGapMs = 300_000)
        r.due(0); r.due(60_000)
        r.reset()
        assertEquals(0, r.attempts)
        assertTrue(r.due(60_001))
        assertFalse(r.due(60_002))
    }
}
