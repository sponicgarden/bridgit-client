package cc.bridgit.app.alerts

import android.app.Application
import cc.bridgit.app.core.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EmailEnrollmentTest {
    private fun settings() = Settings(RuntimeEnvironment.getApplication())

    @Test fun failedOrRateLimitedDeliveryKeepsTheAddressAcrossRestartWithoutVerifyingIt() {
        for (result in listOf(AlertRelay.Subscribe.FAILED, AlertRelay.Subscribe.RATE_LIMITED)) {
            val st = settings()
            val enrollment = EmailEnrollment(st) { email ->
                assertEquals("owner@example.com", settings().alertEmail) // Already saved during the request.
                assertEquals(email, settings().alertEmail)
                result
            }
            assertEquals(result, enrollment.subscribe(" Owner@Example.com "))
            val restored = settings()
            assertEquals("owner@example.com", restored.alertEmail)
            assertEquals(0L, restored.emailVerifiedAt)
            assertFalse(EmailGate.evaluate(100_000_000L, 1L, false, restored.alertState, restored.alertEmail != null).showReminder)
        }
    }

    @Test fun invalidInputCannotReplaceTheSavedAddressOrSend() {
        val st = settings()
        st.alertEmail = "owner@example.com"
        st.alertState = Alerts.STATE_CONFIRMED
        val enrollment = EmailEnrollment(st) { fail("Invalid input must not be sent"); AlertRelay.Subscribe.FAILED }
        assertEquals(AlertRelay.Subscribe.BAD_EMAIL, enrollment.subscribe("not an email"))
        assertEquals("owner@example.com", st.alertEmail)
        assertEquals(Alerts.STATE_CONFIRMED, st.alertState)
    }

    @Test fun newAddressDoesNotInheritConfirmationOfTheOldOne() {
        val st = settings()
        st.alertEmail = "old@example.com"
        st.alertState = Alerts.STATE_CONFIRMED
        assertEquals(AlertRelay.Subscribe.PENDING, EmailEnrollment(st) { AlertRelay.Subscribe.PENDING }.subscribe("new@example.com"))
        assertEquals("new@example.com", st.alertEmail)
        assertEquals(Alerts.STATE_PENDING, st.alertState)
        assertEquals(0L, st.emailVerifiedAt)
    }

    @Test fun pendingResendCannotDowngradeAnAlreadyConfirmedAddress() {
        val st = settings()
        st.alertEmail = "owner@example.com"
        st.alertState = Alerts.STATE_CONFIRMED
        EmailEnrollment(st) { AlertRelay.Subscribe.PENDING }.subscribe("owner@example.com")
        assertEquals(Alerts.STATE_CONFIRMED, st.alertState)
    }

    @Test fun recoveryChecksSavedNoneAndPendingButDoesNotInheritAnotherAddress() {
        assertTrue(EmailVerificationRules.shouldRefresh("owner@example.com", Alerts.STATE_NONE))
        assertTrue(EmailVerificationRules.shouldRefresh("owner@example.com", Alerts.STATE_PENDING))
        assertFalse(EmailVerificationRules.shouldRefresh(null, Alerts.STATE_NONE))
        assertFalse(EmailVerificationRules.shouldRefresh("owner@example.com", Alerts.STATE_CONFIRMED))
        assertEquals(AlertRelay.State.CONFIRMED, EmailVerificationRules.state("confirmed", "OWNER@example.com", "owner@example.com"))
        assertEquals(AlertRelay.State.UNKNOWN, EmailVerificationRules.state("confirmed", "old@example.com", "new@example.com"))
        assertEquals(AlertRelay.State.UNKNOWN, EmailVerificationRules.state("confirmed", null, "owner@example.com"))
        assertEquals(AlertRelay.State.PENDING, EmailVerificationRules.state("pending", "owner@example.com", "owner@example.com"))
        assertEquals(AlertRelay.State.NONE, EmailVerificationRules.state("none", null, "owner@example.com"))
    }

    @Test fun staleEnrollmentCannotApplyToANewAddress() {
        val st = settings()
        for (result in listOf(AlertRelay.Subscribe.PENDING, AlertRelay.Subscribe.CONFIRMED)) {
            assertEquals(AlertRelay.Subscribe.FAILED, EmailEnrollment(st) {
                st.alertEmail = "new@example.com"
                st.alertState = Alerts.STATE_NONE
                result
            }.subscribe("old@example.com"))
            assertEquals("new@example.com", st.alertEmail)
            assertEquals(Alerts.STATE_NONE, st.alertState)
        }
    }

    @Test fun unreadableStoredIdentityIsPreservedAndReturnsNormalFailure() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("alerts_token", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("iv", "unreadable").putString("data", "preserve-this").commit()
        val relay = AlertRelay(app)
        assertEquals(AlertRelay.Subscribe.FAILED, relay.subscribe("owner@example.com"))
        assertEquals(AlertRelay.State.UNKNOWN, relay.status("owner@example.com"))
        assertFalse(relay.unsubscribe())
        assertEquals("unreadable", prefs.getString("iv", null))
        assertEquals("preserve-this", prefs.getString("data", null))
    }
}
