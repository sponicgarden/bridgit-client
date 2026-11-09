package cc.bridgit.app.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A0095: a paused accessibility service is reported as not ready instead of ready plus an endless "retry". */
class ScreenStartupTest {
    @Test fun pausedServiceIsBlocked() {
        assertEquals("accessibility_paused", ScreenshotService.startupBlock(enabled = true, connected = false, paused = true))
    }
    @Test fun connectingOrRunningServiceIsNotBlocked() {
        assertNull(ScreenshotService.startupBlock(enabled = true, connected = false, paused = false))
        assertNull(ScreenshotService.startupBlock(enabled = true, connected = true, paused = true))
        assertNull(ScreenshotService.startupBlock(enabled = false, connected = false, paused = true))
    }
}
