package cc.bridgit.app.interact

import org.junit.Assert.*
import org.junit.Test

class AnswerResubmissionTest {
    @Test fun ordinarySubmissionsAcceptOpenAndExpiredInteractions() {
        IStatus.values().forEach { status ->
            assertEquals(status.name, status.open || status == IStatus.EXPIRED, status.acceptsAnswer())
        }
    }

    @Test fun expiredStillAwaitsTheOwnerButIsNotOpen() {
        assertTrue(IStatus.EXPIRED.awaitsOwner)
        assertFalse(IStatus.EXPIRED.open)
        listOf(IStatus.ANSWERED, IStatus.CANCELLED, IStatus.DISMISSED).forEach { assertFalse(it.name, it.awaitsOwner) }
    }

    @Test fun explicitResubmissionAllowsAnsweredButNeverCancelledOrDismissed() {
        assertTrue(IStatus.ANSWERED.acceptsAnswer(resubmission = true))
        listOf(IStatus.CANCELLED, IStatus.DISMISSED).forEach { status ->
            assertFalse(status.name, status.acceptsAnswer(resubmission = true))
        }
        assertTrue(IStatus.EXPIRED.acceptsAnswer(resubmission = true))
        assertTrue(IStatus.PENDING.acceptsAnswer(resubmission = true))
        assertTrue(IStatus.SEEN.acceptsAnswer(resubmission = true))
    }
}
