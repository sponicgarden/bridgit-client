package cc.bridgit.app.alerts

import cc.bridgit.app.api.ErrorCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailGateTest {
    private val h = 3_600_000L
    private val t0 = 1_800_000_000_000L

    @Test fun nothingNeededBeforeTheFirstAgentCall() {
        val v = EmailGate.evaluate(t0, graceStartedAt = 0, verified = false, alertState = "none", hasEmail = false)
        assertEquals(EmailGate.Status.GRACE, v.status)
        assertNull(v.graceEndsAt)
        assertFalse(v.blocked)
    }

    @Test fun reminderWaitsOneHourFromFirstAgentCall() {
        for (state in listOf("none")) {
            fun at(elapsed: Long) = EmailGate.evaluate(t0 + elapsed, t0, false, state, state == "pending")
            assertFalse(at(0).showReminder)
            assertFalse(at(h - 1).showReminder)
            assertTrue(at(h).showReminder)
            assertTrue(at(24 * h).showReminder)
        }
        assertFalse(EmailGate.evaluate(t0, 0, false, "none", false).showReminder)
        assertFalse(EmailGate.evaluate(t0 + h, t0, true, "none", false).showReminder)
        assertFalse(EmailGate.evaluate(t0 + h, t0, false, "confirmed", true).showReminder)
    }

    @Test fun savedEmailHidesTheAddEmailReminderEvenBeforeConfirmation() {
        for (state in listOf("none", "pending", "confirmed")) {
            for (elapsed in listOf(h, 24 * h, 30 * h)) {
                assertFalse(EmailGate.evaluate(t0 + elapsed, t0, false, state, true).showReminder)
            }
        }
        // Saving the address hides the prompt; confirmation still controls the existing API gate.
        assertTrue(EmailGate.evaluate(t0 + 24 * h, t0, false, "pending", true).blocked)
    }

    @Test fun twentyFourHoursOfGraceThenRequired() {
        val during = EmailGate.evaluate(t0 + 23 * h, t0, false, "none", false)
        assertEquals(EmailGate.Status.GRACE, during.status)
        assertEquals(h, during.remainingMs)
        val after = EmailGate.evaluate(t0 + 24 * h, t0, false, "none", false)
        assertEquals(EmailGate.Status.REQUIRED, after.status)
        assertTrue(after.blocked)
    }

    @Test fun pendingIsStillBlockedAfterGrace() {
        val v = EmailGate.evaluate(t0 + 30 * h, t0, false, "pending", true)
        assertTrue(v.blocked)
        assertEquals("pending", v.state)
        assertTrue(EmailGate.message(v).contains("tap the link"))
    }

    @Test fun confirmedOrOnceVerifiedNeverBlocks() {
        assertEquals(EmailGate.Status.VERIFIED, EmailGate.evaluate(t0 + 99 * h, t0, false, "confirmed", true).status)
        // Stopping alerts (address removed or unsubscribed) after verifying doesn't lock Bridgit again.
        assertEquals(EmailGate.Status.VERIFIED, EmailGate.evaluate(t0 + 99 * h, t0, true, "none", false).status)
    }

    @Test fun stateWithoutAnAddressIsNone() {
        assertEquals("none", EmailGate.evaluate(t0, t0, false, "pending", hasEmail = false).state)
    }

    @Test fun agentsKeepStatusAndFeedbackWhileBlocked() {
        assertTrue(EmailGate.exempt("status"))
        assertTrue(EmailGate.exempt("feedback"))
        assertFalse(EmailGate.exempt("camera"))
        assertFalse(EmailGate.exempt("interactions"))
        assertTrue("capabilities" in EmailGate.EXEMPT_TOOLS)
        assertFalse("ui.action" in EmailGate.EXEMPT_TOOLS)
    }

    @Test fun maskKeepsTheDomainOnly() {
        assertEquals("w***@gmail.com", EmailGate.mask("wingsie@gmail.com"))
        assertNull(EmailGate.mask(null))
        assertEquals("***", EmailGate.mask("nodomain"))
    }

    @Test fun errorCodeIsCatalogued() {
        assertEquals(403, ErrorCatalog.http("email_required"))
    }
}
