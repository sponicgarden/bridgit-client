package cc.bridgit.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastTabTest {
    private val tabs = setOf("interactions", "home", "camera", "agents", "more")

    @Test fun plainLaunchReopensTheLastTab() =
        assertEquals("agents", LastTab.restore("agents", plainLaunch = true, current = "interactions", start = "interactions", tabs = tabs))

    @Test fun nothingSavedStaysOnStart() =
        assertNull(LastTab.restore(null, plainLaunch = true, current = "interactions", start = "interactions", tabs = tabs))

    @Test fun notificationOrLinkLaunchWins() =
        assertNull(LastTab.restore("agents", plainLaunch = false, current = "interactions", start = "interactions", tabs = tabs))

    @Test fun setupOrFirstRunIsNeverCovered() {
        assertNull(LastTab.restore("agents", plainLaunch = true, current = "firstRun", start = "interactions", tabs = tabs))
        assertNull(LastTab.restore("agents", plainLaunch = true, current = "addAgent", start = "interactions", tabs = tabs))
    }

    @Test fun onlyTabsAreRestored() {
        assertNull(LastTab.restore("addAgent", plainLaunch = true, current = "interactions", start = "interactions", tabs = tabs))
        assertNull(LastTab.restore("tailscaleSetup", plainLaunch = true, current = "interactions", start = "interactions", tabs = tabs))
    }
}
