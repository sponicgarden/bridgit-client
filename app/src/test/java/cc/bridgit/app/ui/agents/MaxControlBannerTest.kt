package cc.bridgit.app.ui.agents

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaxControlBannerTest {
    @Test fun shownWhileMaxControlIsOff() = assertTrue(MaxControlBanner.visible(maxControlOn = false))
    @Test fun hiddenOnceMaxControlIsOn() = assertFalse(MaxControlBanner.visible(maxControlOn = true))
}
