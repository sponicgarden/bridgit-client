package cc.bridgit.app.core.authority

import android.app.Application
import cc.bridgit.app.core.authority.AuthorityFixture.Companion.ok
import cc.bridgit.app.core.authority.AuthorityFixture.Companion.refused
import cc.bridgit.app.core.authority.AuthorityStore.Refusal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The recovery fixtures of architecture §4.7 ("Recovery: the contract"): restart during the wait; clock
 * moved forward and back; cancel one second before and after the clear; start twice; start with an empty
 * list; start with no verified alert email; an approver added during the wait; a cancel signed by a
 * removed approver; enrolment after a clear waits 24 hours. The waits are contract data and are run at
 * full length on a clock the test moves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecoveryMachineTest {
    private lateinit var f: AuthorityFixture
    private val time get() = f.time
    private val state get() = f.recovery.status().state

    @Before fun open() { f = AuthorityFixture(RuntimeEnvironment.getApplication()) }
    @After fun close() = f.close()

    private fun started(by: String = "agent-owner"): AuthorityStore.Recovery {
        f.enrolFirst("A")
        return ok(f.recovery.start(by, emailVerified = true))
    }

    @Test fun startsPendingWithTheClearTimeAndClearsAfter72Hours() {
        val r = started()
        assertEquals(RecoveryMachine.PENDING, r.state)
        assertEquals("agent-owner", r.startedBy)
        assertEquals(time.wall + 72 * HOUR, r.wait.wallEndMs)
        time.run(72 * HOUR - SECOND)
        assertEquals(RecoveryMachine.PENDING, state)
        assertEquals(listOf("A"), f.ids())
        time.run(SECOND)
        assertEquals(RecoveryMachine.CLEARED, state)
        assertEquals(emptyList<String>(), f.ids())
        assertTrue(f.events.any { it == AuthorityEvent.RecoveryCleared(listOf("A")) })
    }

    @Test fun restartDuringTheWaitResumesFromTheLastCheckpoint() {
        started()
        time.run(30 * HOUR)
        f.store.checkpoint()
        time.run(5 * HOUR) // not checkpointed before the device lost power
        time.off(200 * HOUR) // long past the clear time on the wall clock
        f.restart(reboot = true)
        assertEquals(RecoveryMachine.PENDING, state)
        time.run(42 * HOUR - SECOND) // 30 h counted before + this = one second short
        assertEquals(RecoveryMachine.PENDING, state)
        assertEquals(listOf("A"), f.ids())
        time.run(SECOND)
        assertEquals(RecoveryMachine.CLEARED, state)
    }

    @Test fun anAppRestartWithoutARebootAlsoResumesFromTheLastCheckpoint() {
        started()
        time.run(71 * HOUR)
        f.store.checkpoint()
        time.run(10 * HOUR) // the app was stopped; nothing counted
        f.restart()
        assertEquals(RecoveryMachine.PENDING, state)
        time.run(HOUR)
        assertEquals(RecoveryMachine.CLEARED, state)
    }

    @Test fun movingTheClockForwardShortensNothing() {
        started()
        time.wall += 100 * HOUR
        time.run(HOUR)
        assertEquals(RecoveryMachine.PENDING, state)
        assertEquals(listOf("A"), f.ids())
        time.run(71 * HOUR)
        assertEquals(RecoveryMachine.CLEARED, state)
    }

    @Test fun movingTheClockBackHoldsTheClearUntilTheWallClockIsPastTheClearTime() {
        started()
        time.wall -= 10 * HOUR
        time.run(72 * HOUR) // the running time is done; the wall clock is 10 h short
        assertEquals(RecoveryMachine.PENDING, state)
        time.wall += 10 * HOUR - SECOND
        assertEquals(RecoveryMachine.PENDING, state)
        time.wall += SECOND
        assertEquals(RecoveryMachine.CLEARED, state)
    }

    @Test fun aCancelOneSecondBeforeTheClearWins() {
        started()
        time.run(72 * HOUR - SECOND)
        val r = ok(f.recovery.cancelByApprover("A"))
        assertEquals(RecoveryMachine.CANCELLED, r.state)
        assertEquals("A", r.endedBy)
        time.run(500 * HOUR)
        assertEquals(RecoveryMachine.CANCELLED, state)
        assertEquals(listOf("A"), f.ids())
        assertTrue(f.events.contains(AuthorityEvent.RecoveryCancelled("A")))
    }

    @Test fun aCancelOneSecondAfterTheClearIsRefusedAndRaisesAnAlert() {
        started()
        time.run(72 * HOUR + SECOND) // nothing touched the store in between: the cancel itself must find the clear due
        refused(Refusal.RECOVERY_COMPLETE, f.recovery.cancelByApprover("A"))
        assertEquals("recovery_complete", Refusal.RECOVERY_COMPLETE.code)
        assertEquals(RecoveryMachine.CLEARED, state)
        assertEquals(emptyList<String>(), f.ids())
        assertTrue(f.events.contains(AuthorityEvent.RecoveryCancelLate("A")))
        refused(Refusal.RECOVERY_COMPLETE, f.recovery.withdraw("agent-owner"))
    }

    @Test fun aCancelAtTheVeryMomentOfTheClearLoses() {
        started()
        time.run(72 * HOUR)
        refused(Refusal.RECOVERY_COMPLETE, f.recovery.cancelByApprover("A"))
    }

    @Test fun startingTwiceReturnsTheSameRecordAndDoesNotRestartTheClock() {
        val first = started()
        val generation = f.store.generation()
        time.run(10 * HOUR)
        val second = ok(f.recovery.start("someone-else", emailVerified = true))
        assertEquals(first, second)
        assertEquals(generation, f.store.generation())
        assertEquals(1, f.events.count { it is AuthorityEvent.RecoveryStarted })
        time.run(62 * HOUR)
        assertEquals(RecoveryMachine.CLEARED, state)
    }

    @Test fun startNeedsAListThatIsNotEmpty() {
        refused(Refusal.EMPTY_LIST, f.recovery.start("agent-owner", emailVerified = true))
        assertEquals(RecoveryMachine.NONE, state)
        assertEquals(0, f.store.generation())
    }

    @Test fun startNeedsAVerifiedAlertEmail() {
        f.enrolFirst("A")
        refused(Refusal.OWNER_EMAIL_REQUIRED, f.recovery.start("agent-owner", emailVerified = false))
        assertEquals("owner_email_required", Refusal.OWNER_EMAIL_REQUIRED.code)
        assertEquals(RecoveryMachine.NONE, state)
    }

    @Test fun anApproverAddedDuringTheWaitCancelsItAndWaits24Hours() {
        started()
        time.run(10 * HOUR)
        refused(Refusal.LIST_NOT_EMPTY, f.store.enrolFirst(f.approver("X"), f.transcript("enrol", "X"))) // the first-approver flow stays shut
        f.add("B", by = "A")
        assertEquals(RecoveryMachine.CANCELLED, state)
        assertEquals("A", f.recovery.status().endedBy)
        assertFalse(f.store.canApprove("B"))
        time.run(24 * HOUR - SECOND)
        assertFalse(f.store.canApprove("B"))
        time.run(SECOND)
        assertTrue(f.store.canApprove("B"))
        time.run(100 * HOUR)
        assertEquals(listOf("A", "B"), f.ids())
    }

    @Test fun aCancelSignedByARemovedApproverIsRefused() {
        f.enrolFirst("A")
        f.add("B", by = "A")
        time.run(24 * HOUR)
        ok(f.store.removeApprover("A", by = "B", t = f.transcript("remove", "A")))
        ok(f.recovery.start("agent-owner", emailVerified = true))
        refused(Refusal.UNKNOWN_APPROVER, f.recovery.cancelByApprover("A"))
        assertEquals(RecoveryMachine.PENDING, state)
        time.run(72 * HOUR)
        assertEquals(RecoveryMachine.CLEARED, state)
    }

    @Test fun enrolmentAfterAClearWaits24HoursAndFirstSetUpDoesNot() {
        assertFalse(f.store.enrolledBefore())
        f.enrolFirst("A")
        assertTrue(f.store.canApprove("A")) // first set-up: no wait (D16c)
        assertTrue(f.store.enrolledBefore())
        ok(f.recovery.start("agent-owner", emailVerified = true))
        time.run(72 * HOUR)
        assertEquals(RecoveryMachine.CLEARED, state)
        assertTrue(f.store.enrolledBefore()) // never unset

        f.enrolFirst("C")
        assertEquals(RecoveryMachine.NONE, state)
        assertFalse(f.store.canApprove("C"))
        time.wall += 300 * HOUR // moving the clock forward doesn't shorten this wait either
        time.run(24 * HOUR - SECOND)
        assertFalse(f.store.canApprove("C"))
        f.restart(reboot = true)
        assertFalse(f.store.canApprove("C"))
        time.run(SECOND)
        assertTrue(f.store.canApprove("C"))
    }

    @Test fun theClearRaisesTheGenerationAndCancelsWaitingRequestsInOneStep() {
        started()
        f.request("r1")
        val before = f.store.generation()
        time.run(72 * HOUR)
        refused(Refusal.NOT_OPEN, f.store.execute("r1", "A", check = { _, _ -> null }, effect = { _, _ -> Unit }))
        assertEquals(AuthorityStore.CANCELLED, f.store.request("r1")!!.state)
        assertEquals(before + 1, f.store.generation())
        assertEquals("recovery_clear", f.store.transcripts().last().kind)
    }

    @Test fun theStarterCanWithdrawAndNobodyElseCan() {
        started("agent-owner")
        refused(Refusal.NOT_STARTER, f.recovery.withdraw("agent-other"))
        assertEquals(RecoveryMachine.CANCELLED, ok(f.recovery.withdraw("agent-owner")).state)
        refused(Refusal.NOT_PENDING, f.recovery.withdraw("agent-owner"))
        // cancelled counts as none: a new start runs a full new wait
        time.run(50 * HOUR)
        ok(f.recovery.start("agent-owner", emailVerified = true))
        time.run(72 * HOUR - SECOND)
        assertEquals(RecoveryMachine.PENDING, state)
    }

    @Test fun anyOtherValidApproverSignedMessageCancelsAPendingRecovery() {
        started()
        f.request("r1")
        assertEquals("done", ok(f.store.execute("r1", "A", check = { _, _ -> null }, effect = { _, _ -> "done" })))
        assertEquals(RecoveryMachine.CANCELLED, state)
        assertEquals("A", f.recovery.status().endedBy)
    }

    @Test fun aSignedDeclineJustBeforeTheDeadlineCancelsThePendingRecovery() {
        started()
        f.request("r1")
        time.run(72 * HOUR - SECOND)
        ok(f.store.decline("r1", "A"))
        assertEquals(RecoveryMachine.CANCELLED, state)
        assertEquals("A", f.recovery.status().endedBy)
        time.run(500 * HOUR)
        assertEquals(listOf("A"), f.ids())
    }

    @Test fun aDeclineFromAnApproverStillInItsWaitAlsoCancels() {
        f.enrolFirst("A")
        f.add("B", by = "A")
        ok(f.recovery.start("agent-owner", emailVerified = true))
        f.request("r1")
        time.run(HOUR)
        assertFalse(f.store.canApprove("B"))
        ok(f.store.decline("r1", "B"))
        assertEquals(RecoveryMachine.CANCELLED, state)
        assertEquals("B", f.recovery.status().endedBy)
    }

    @Test fun aDeclineOneSecondAfterTheClearCancelsNothing() {
        started()
        f.request("r1")
        time.run(72 * HOUR + SECOND)
        refused(Refusal.NOT_OPEN, f.store.decline("r1", "A")) // the clear already cancelled the request
        assertEquals(RecoveryMachine.CLEARED, state)
    }

    @Test fun anAgentsDeclineOrAnUnlistedSignerDoesNotCancel() {
        started()
        f.request("r1")
        f.request("r2")
        ok(f.store.decline("r1", null))
        refused(Refusal.UNKNOWN_APPROVER, f.store.decline("r2", "ghost"))
        assertEquals(RecoveryMachine.PENDING, state)
    }

    @Test fun aPendingRecoverySurvivesAReopen() {
        val r = started()
        f.restart()
        assertEquals(r, f.recovery.status())
    }
}
