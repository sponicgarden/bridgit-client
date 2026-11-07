package cc.bridgit.app.applets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class InstallProblemTest {
    private val limit = mapOf("checks_per_hour_used" to 0, "checks_per_hour_limit" to 50)

    @Test fun overTheLimitOffersAFittingInterval() {
        val w = InstallProblem.words("rate_limited", "Nothing was installed: The phone's watches could make 120 paid checks an hour; the limit is 50 (GET /v1/limits). Use a longer interval_s", limit, 30, 10, 3600)
        assertEquals("This applet checks too often", w.title)
        assertEquals(75, w.fixInterval) // 3600 / 50 = 72, rounded up to 75
        assertEquals("This phone allows 50 paid checks an hour across all watches. Checking every 75 seconds fits.", w.body)
    }

    @Test fun otherWatchesLeaveLessRoom() {
        val w = InstallProblem.words("rate_limited", null, mapOf("checks_per_hour_used" to 40, "checks_per_hour_limit" to 50), 30, 10, 3600)
        assertEquals(360, w.fixInterval)
        assertEquals("This phone allows 50 paid checks an hour across all watches. Your other watches use 40, leaving 10. Checking every 360 seconds fits.", w.body)
    }

    @Test fun noRoomMeansFreeSomeUp() {
        val w = InstallProblem.words("rate_limited", null, mapOf("checks_per_hour_used" to 50, "checks_per_hour_limit" to 50), 30, 10, 3600)
        assertNull(w.fixInterval)
        assertEquals("No room for more checks", w.title)
    }

    @Test fun withoutAnIntervalChoiceNoFix() {
        val w = InstallProblem.words("rate_limited", null, limit, null, null, null)
        assertNull(w.fixInterval)
        assertEquals("This phone allows 50 paid checks an hour across all watches. Choose a longer time between checks, then try again.", w.body)
    }

    @Test fun plainErrorsKeepTheirReasonWithoutTheRepeatedPrefix() {
        val w = InstallProblem.words("conflict", "Nothing was installed: Front door checker is already installed. Update it instead.", emptyMap(), 30, 10, 3600)
        assertEquals("Couldn't install applet", w.title)
        assertEquals("Front door checker is already installed. Update it instead. Nothing was changed on this phone.", w.body)
    }

    @Test fun technicalErrorsBecomeGeneric() {
        val w = InstallProblem.words("conflict", "Nothing was installed: Watch main: interval_s must be 10-3600", emptyMap(), 30, 10, 3600, update = true)
        assertEquals("Couldn't update applet", w.title)
        assertEquals("Something went wrong. The installed applet keeps running as before. Please try again.", w.body)
        assertFalse(InstallProblem.technical("Camera is off on this phone."))
    }

    @Test fun aSwitchTurnedOffSaysWhich() {
        val w = InstallProblem.words("capability_disabled", "Camera is turned off in Device tools (feature_disabled).", emptyMap(), 30, 10, 3600)
        assertEquals("Camera is turned off in Device tools. Nothing was changed on this phone.", w.body)
    }

    @Test fun fitIntervalRespectsBounds() {
        assertEquals(120, InstallProblem.fitInterval(30, 30, 10, 3600))
        assertEquals(600, InstallProblem.fitInterval(6, 30, 10, 3600))
        assertNull(InstallProblem.fitInterval(1, 30, 10, 600)) // 3600 s doesn't fit under 600
        assertNull(InstallProblem.fitInterval(200, 30, 10, 3600)) // 30 s already fits
    }
}
