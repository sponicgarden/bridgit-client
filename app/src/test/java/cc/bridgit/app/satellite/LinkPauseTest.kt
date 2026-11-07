package cc.bridgit.app.satellite

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkPauseTest {
    @After fun reset() { LinkPause.setSatellites(false); LinkPause.setHubs(false) }

    private fun call(toHub: Boolean) = SatelliteClient.newCall("http://100-89-1-2.${SatelliteClient.SUFFIX}:18471", "brg_x", "GET", "/v1/satellite/manifest",
        emptyList(), emptyList(), null, toHub = toHub)

    @Test fun switchesSurviveARestartAndAreIndependent() {
        LinkPause.attach(RuntimeEnvironment.getApplication())
        LinkPause.setSatellites(true)
        LinkPause.attach(RuntimeEnvironment.getApplication())
        assertTrue(LinkPause.satellites); assertFalse(LinkPause.hubsPaused)
    }

    @Test fun pausedSideMakesNoCallAndTheOtherSideStillDoes() {
        LinkPause.attach(RuntimeEnvironment.getApplication())
        LinkPause.setSatellites(true)
        try { call(toHub = false); fail("a paused satellite must not be called") } catch (e: SatelliteClient.Failure) { assertEquals("paused", e.code) }
        call(toHub = true) // hubs are not paused
        LinkPause.setSatellites(false); LinkPause.setHubs(true)
        call(toHub = false)
        try { call(toHub = true); fail("a paused hub must not be called") } catch (e: SatelliteClient.Failure) { assertEquals("paused", e.code) }
    }
}
