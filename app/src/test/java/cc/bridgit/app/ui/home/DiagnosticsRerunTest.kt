package cc.bridgit.app.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRerunTest {
    @Test fun eachRequestPlaysOnceAndOldRequestsExpire() {
        val t0 = 1_900_000_000_000L
        DiagnosticsRerun.take(t0) // clear anything left by another test
        assertFalse(DiagnosticsRerun.take(t0))
        DiagnosticsRerun.request(t0)
        assertTrue(DiagnosticsRerun.take(t0 + 1_000))
        assertFalse("the same request plays once", DiagnosticsRerun.take(t0 + 2_000))
        DiagnosticsRerun.request(t0 + 10_000)
        DiagnosticsRerun.request(t0 + 10_000) // two requests in the same millisecond are still new
        assertTrue(DiagnosticsRerun.take(t0 + 11_000))
        DiagnosticsRerun.request(t0 + 20_000)
        assertFalse("an unseen request expires", DiagnosticsRerun.take(t0 + 20_001 + DiagnosticsRerun.TTL_MS))
    }
}
