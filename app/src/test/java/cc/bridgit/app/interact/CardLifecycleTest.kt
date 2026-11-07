package cc.bridgit.app.interact

import org.junit.Assert.*
import org.junit.Test

class CardLifecycleTest {
    @Test fun queuedNotificationsCannotReopenCompletedCards() {
        for (status in listOf(IStatus.ANSWERED, IStatus.DISMISSED, IStatus.CANCELLED))
            assertFalse(CardLifecycle.mayOpenFromNotification(status))
        for (status in listOf(IStatus.PENDING, IStatus.SEEN, IStatus.EXPIRED))
            assertTrue(CardLifecycle.mayOpenFromNotification(status))
    }

    @Test fun completedCardLeavesStackAfterFocusMovesAndStaysOutOnRefresh() {
        assertTrue(CardLifecycle.includeInStack(false, true, false)) // brief receipt
        repeat(3) { assertFalse(CardLifecycle.includeInStack(false, false, false)) }
        assertTrue(CardLifecycle.includeInStack(true, false, false)) // agent's new request
    }

    @Test fun historyShowsOnlyTheSelectedReceipt() {
        assertTrue(CardLifecycle.includeInStack(false, true, true))
        assertFalse(CardLifecycle.includeInStack(false, false, true))
        assertFalse(CardLifecycle.includeInStack(true, false, true))
    }
}
