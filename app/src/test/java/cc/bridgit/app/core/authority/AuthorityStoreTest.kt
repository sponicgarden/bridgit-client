package cc.bridgit.app.core.authority

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import cc.bridgit.app.core.authority.AuthorityFixture.Companion.ok
import cc.bridgit.app.core.authority.AuthorityFixture.Companion.refused
import cc.bridgit.app.core.authority.AuthorityGeneration.Reason
import cc.bridgit.app.core.authority.AuthorityStore.Refusal
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The approver store, the authority generation and the execution-time check as one transaction (architecture §4.7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuthorityStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var f: AuthorityFixture
    private val store get() = f.store
    private val allow: (AuthorityStore.Tx, AuthorityStore.Request) -> String? = { _, _ -> null }

    @Before fun open() { f = AuthorityFixture(app) }
    @After fun close() = f.close()

    // Generation

    @Test fun theGenerationRisesByOneOnEveryKindOfChange() {
        assertEquals(0, store.generation())
        Reason.values().forEachIndexed { i, reason -> assertEquals(i + 1L, store.raise(reason, "test")) }
        assertEquals(Reason.values().map { it.id }, store.generation.log().map { it.second })
        assertEquals(setOf("grant", "pause", "revoke", "rekey", "master_switch", "approver_list", "recovery_start"), Reason.values().map { it.id }.toSet())
    }

    @Test fun approverListChangesAndARecoveryStartEachRaiseIt() {
        var g = store.generation()
        fun rose(what: String) { assertEquals(what, g + 1, store.generation()); g = store.generation() }
        f.enrolFirst("A"); rose("enrol")
        f.add("B", by = "A"); rose("add")
        f.time.run(24 * HOUR)
        ok(store.removeApprover("A", by = "B", t = f.transcript("remove", "A"))); rose("remove")
        ok(f.recovery.start("agent-owner", emailVerified = true)); rose("recovery start")
        ok(f.recovery.cancelByApprover("B"))
        assertEquals("a cancel changes nobody's authority", g, store.generation())
        ok(f.recovery.start("agent-owner", emailVerified = true)); rose("second start")
        f.time.run(72 * HOUR); rose("clear")
        assertEquals(listOf("approver_list", "approver_list", "approver_list", "recovery_start", "recovery_start", "approver_list"), store.generation.log().map { it.second })
    }

    @Test fun aRefusedChangeDoesNotRaiseIt() {
        f.enrolFirst("A")
        val g = store.generation()
        refused(Refusal.LIST_NOT_EMPTY, store.enrolFirst(f.approver("B"), f.transcript("enrol", "B")))
        refused(Refusal.UNKNOWN_APPROVER, store.addApprover(f.approver("B"), "ghost", f.transcript("add", "B")))
        refused(Refusal.SELF_REMOVE, store.removeApprover("A", "A", f.transcript("remove", "A")))
        refused(Refusal.DUPLICATE_APPROVER, store.addApprover(f.approver("A"), "A", f.transcript("add", "A")))
        assertEquals(g, store.generation())
        assertEquals(listOf("A"), f.ids())
    }

    @Test fun aRaiseInsideAFailedTransactionIsNotKept() {
        try {
            store.tx { it.raise(Reason.GRANT); error("the grant failed") }
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertEquals(0, store.generation())
        }
    }

    // Approvers

    @Test fun anApproverStillInItsWaitCannotAuthorizeAListChange() {
        f.enrolFirst("A")
        f.add("B", by = "A")
        refused(Refusal.APPROVER_WAITING, store.addApprover(f.approver("C"), "B", f.transcript("add", "C")))
        refused(Refusal.APPROVER_WAITING, store.removeApprover("A", "B", f.transcript("remove", "A")))
        assertEquals(listOf("A", "B"), f.ids())
    }

    @Test fun theOwnerAuthStateIsReadFromTheStore() {
        f.enrolFirst("A")
        f.add("B", by = "A")
        f.request("r1")
        ok(store.execute("r1", "A", check = allow, effect = { _, _ -> Unit }))
        val state = store.tx { it.ownerAuthState("this-device") }
        assertEquals(listOf("A" to true, "B" to false), state.approvers.map { it.id to it.pastWait })
        assertArrayEquals(f.bytes("A", 1), state.approvers[0].approverKeySpki)
        assertArrayEquals(f.bytes("A", 2), state.approvers[0].installKeySpki)
        assertEquals(f.time.wall, state.nowMs)
        assertTrue(state.nonceUsed("r1"))
        assertFalse(state.nonceUsed("r2"))
    }

    // Survives a reopen

    @Test fun everythingSurvivesAReopen() {
        f.enrolFirst("A")
        f.add("B", by = "A")
        f.request("spent")
        f.request("waiting")
        ok(store.execute("spent", "A", check = allow, effect = { _, _ -> Unit }))
        store.putProposal(AuthorityStore.Proposal("p1", "waiting", "setting", "{\"x\":1}", "screen_lock", "open"))
        f.time.run(3 * HOUR)
        ok(f.recovery.start("agent-owner", emailVerified = true))
        val generation = store.generation()
        val counted = store.checkpoint()

        f.restart(reboot = true)

        assertEquals(generation, store.generation())
        assertEquals(counted, store.checkpoint())
        assertTrue(store.enrolledBefore())
        val a = store.approvers().first { it.id == "B" }
        assertEquals("Name B", a.name)
        assertArrayEquals(f.bytes("B", 1), a.approverKey)
        assertArrayEquals(f.bytes("B", 2), a.installKey)
        assertArrayEquals(f.bytes("B", 3), a.sealingKey)
        assertArrayEquals(f.bytes("B", 4), a.messageKey)
        assertTrue(a.addedMs > 0)
        assertTrue(a.wait.countedEndMs >= AuthorityLimits.APPROVER_WAIT_MS)
        assertTrue(store.approvers().first { it.id == "A" }.lastUsedMs > 0)
        assertEquals(listOf("enrol", "add"), store.transcripts().map { it.kind })
        assertArrayEquals("body B".toByteArray(), store.transcripts()[1].body)
        assertEquals(AuthorityStore.APPLIED, store.request("spent")!!.state)
        val waiting = store.request("waiting")!!
        assertEquals(AuthorityStore.OPEN, waiting.state)
        assertEquals("approver_remove", waiting.purpose)
        assertEquals(2, waiting.generation)
        assertEquals(32, waiting.record().digest.size)
        assertEquals(RecoveryMachine.PENDING, f.recovery.status().state)
        assertEquals(listOf("screen_lock"), store.proposals("open").map { it.remainingHumanStep })
        // the spent one-use id is still spent
        refused(Refusal.NONCE_REUSED, store.execute("spent", "A", check = allow, effect = { _, _ -> Unit }))
        refused(Refusal.NONCE_REUSED, store.createRequest(AuthorityStore.NewRequest("spent", ByteArray(32), "setting", "approver_remove", "agent-1", f.time.wall + HOUR)))
    }

    @Test fun aFileFromANewerBuildIsKept() {
        f.enrolFirst("A")
        f.close()
        SQLiteDatabase.openDatabase(app.getDatabasePath(AuthorityDb.NAME).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }
        AuthorityDb(app).use { newer -> assertEquals(listOf("A"), AuthorityStore(newer, f.time).approvers().map { it.id }) }
    }

    // The execution-time check and the apply, one transaction

    @Test fun aOneUseIdIsSpentInTheSameTransactionAsTheApply() {
        f.enrolFirst("A")
        f.request("r1")
        var seenInside: Boolean? = null
        val applied = store.execute("r1", "A", check = { tx, r ->
            assertFalse(tx.nonceUsed(r.id))
            null
        }, effect = { tx, r ->
            seenInside = tx.nonceUsed(r.id) && tx.ownerAuthState("this-device").nonceUsed(r.id) && tx.request(r.id)!!.state == AuthorityStore.APPLIED
            tx.raise(Reason.GRANT, "applied ${r.id}")
        })
        assertEquals(2L, ok(applied))
        assertEquals(true, seenInside)
        assertEquals("A", store.request("r1")!!.approver)

        var ranAgain = false
        refused(Refusal.NONCE_REUSED, store.execute("r1", "A", check = { _, _ -> ranAgain = true; null }, effect = { _, _ -> ranAgain = true }))
        assertFalse(ranAgain)
        assertEquals(2, store.generation())
    }

    @Test fun ifTheApplyFailsTheOneUseIdIsNotSpent() {
        f.enrolFirst("A")
        f.request("r1")
        try {
            store.execute<Unit>("r1", "A", check = allow, effect = { tx, _ -> tx.raise(Reason.GRANT); error("apply failed") })
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(AuthorityStore.OPEN, store.request("r1")!!.state)
        assertFalse(store.tx { it.nonceUsed("r1") })
        assertEquals(1, store.generation())
        assertEquals("ok", ok(store.execute("r1", "A", check = allow, effect = { _, _ -> "ok" })))
    }

    @Test fun aRequestCannotBeMadeWithAOneUseIdSeenBefore() {
        f.request("r1")
        refused(Refusal.NONCE_REUSED, store.createRequest(AuthorityStore.NewRequest("r1", ByteArray(32), "setting", "approver_remove", "agent-2", f.time.wall + HOUR)))
        refused(Refusal.BAD_REQUEST, store.createRequest(AuthorityStore.NewRequest("r2", ByteArray(31), "setting", "approver_remove", "agent-2", f.time.wall + HOUR)))
        refused(Refusal.BAD_REQUEST, store.createRequest(AuthorityStore.NewRequest("r2", ByteArray(32), "setting", "no_such_purpose", "agent-2", f.time.wall + HOUR)))
    }

    @Test fun theCheckReadsTodaysStateNotTheGenerationNumber() {
        f.enrolFirst("A")
        f.request("r1")
        // A relevant change made without the counter rising still refuses: the check reads the state.
        var requesterGranted = false
        val g = store.generation()
        refused(Refusal.CHECK_FAILED, store.execute("r1", "A", check = { _, r -> if (requesterGranted) null else "${r.requester} is no longer granted" }, effect = { _, _ -> Unit }))
        assertEquals("conflict", Refusal.CHECK_FAILED.code)
        assertEquals(g, store.generation())
        assertEquals(AuthorityStore.OPEN, store.request("r1")!!.state)
        // An unrelated change raised the counter since the request was made: that alone refuses nothing.
        store.raise(Reason.PAUSE, "another agent")
        requesterGranted = true
        ok(store.execute("r1", "A", check = { _, r -> if (requesterGranted) null else "no" }, effect = { _, _ -> Unit }))
    }

    @Test fun aSettingChangeMadeAgainstAnOlderGenerationIsRefused() {
        f.enrolFirst("A")
        f.request("r1")
        f.request("r2")
        ok(store.execute("r1", "A", settingChange = true, check = allow, effect = { tx, _ -> tx.raise(Reason.MASTER_SWITCH) }))
        refused(Refusal.STALE_GENERATION, store.execute("r2", "A", settingChange = true, check = allow, effect = { _, _ -> Unit }))
        assertEquals(AuthorityStore.OPEN, store.request("r2")!!.state)
    }

    @Test fun anAnswerFromARemovedApproverOrOneStillWaitingIsRefused() {
        f.enrolFirst("A")
        f.add("B", by = "A")
        f.request("r1")
        refused(Refusal.APPROVER_WAITING, store.execute("r1", "B", check = allow, effect = { _, _ -> Unit }))
        f.time.run(24 * HOUR)
        ok(store.removeApprover("A", by = "B", t = f.transcript("remove", "A")))
        refused(Refusal.UNKNOWN_APPROVER, store.execute("r1", "A", check = allow, effect = { _, _ -> Unit }))
        ok(store.execute("r1", "B", check = allow, effect = { _, _ -> Unit }))
    }

    @Test fun anExpiredRequestIsRefusedAndClosed() {
        f.enrolFirst("A")
        f.request("r1", expiresInMs = HOUR)
        f.time.run(HOUR)
        refused(Refusal.EXPIRED, store.execute("r1", "A", check = allow, effect = { _, _ -> Unit }))
        assertEquals(AuthorityStore.EXPIRED, store.request("r1")!!.state)
        refused(Refusal.NOT_OPEN, store.execute("r1", "A", check = allow, effect = { _, _ -> Unit }))
        refused(Refusal.UNKNOWN_REQUEST, store.execute("nope", "A", check = allow, effect = { _, _ -> Unit }))
    }

    @Test fun turningAFeatureOffOrRevokingARequesterCancelsItsWaitingRequests() {
        f.enrolFirst("A")
        f.request("r1", requester = "agent-1")
        f.request("r2", requester = "agent-2")
        assertEquals(listOf("r1"), store.cancelRequests("revoked", requester = "agent-1"))
        refused(Refusal.NOT_OPEN, store.execute("r1", "A", check = allow, effect = { _, _ -> Unit }))
        ok(store.execute("r2", "A", check = allow, effect = { _, _ -> Unit }))
        assertTrue(f.events.contains(AuthorityEvent.RequestsCancelled(listOf("r1"), "revoked")))
    }

    @Test fun aDeclineClosesTheRequestForGood() {
        f.enrolFirst("A")
        f.request("r1")
        refused(Refusal.UNKNOWN_APPROVER, store.decline("r1", "ghost"))
        ok(store.decline("r1", "A"))
        assertEquals(AuthorityStore.DECLINED, store.request("r1")!!.state)
        refused(Refusal.NONCE_REUSED, store.execute("r1", "A", check = allow, effect = { _, _ -> Unit }))
        refused(Refusal.NONCE_REUSED, store.decline("r1", null))
    }

    @Test fun aCommitRetryReplaysTheFirstAnswerAndOnlyOneApproverHoldsIt() {
        f.enrolFirst("A")
        f.add("B", by = "A")
        f.time.run(24 * HOUR)
        f.request("r1")
        refused(Refusal.CHECK_FAILED, store.commit("r1", "A") { _, _ -> "over the limit" })
        val first = ok(store.commit("r1", "A", allow))
        assertEquals(2, first.generation)
        assertEquals(AuthorityStore.HELD, first.state)

        // The answer was lost; authority changes; the approver asks again. It gets the first answer, unchanged, and no new check.
        f.time.run(HOUR)
        store.raise(Reason.PAUSE, "after the commit")
        val again = ok(store.commit("r1", "A") { _, _ -> "would refuse now" })
        assertEquals(first, again)
        assertEquals(3, store.generation())

        refused(Refusal.COMMITTED_ELSEWHERE, store.commit("r1", "B", allow))
        refused(Refusal.NOT_OPEN, store.execute("r1", "B", check = allow, effect = { _, _ -> Unit }))
        f.restart()
        assertEquals(first, ok(store.commit("r1", "A", allow)))
    }

    @Test fun aGiveBackNamesItsCommitAndIsAcceptedOnce() {
        f.enrolFirst("A")
        f.add("B", by = "A")
        f.add("C", by = "A")
        f.time.run(24 * HOUR)
        f.request("r1")
        val a = ok(store.commit("r1", "A", allow))
        assertFalse("only the holder can give it back", store.voidCommit(a.id, "B"))
        assertFalse(store.voidCommit("no-such-commit", "A"))
        assertTrue(store.voidCommit(a.id, "A"))
        assertEquals(AuthorityStore.OPEN, store.request("r1")!!.state)

        val b = ok(store.commit("r1", "B", allow))
        assertTrue(a.id != b.id)
        // A's give-back arrives again, late: B's commit must stand.
        assertFalse(store.voidCommit(a.id, "A"))
        assertFalse(store.finishCommit(a.id, "A"))
        assertEquals(AuthorityStore.COMMITTED, store.request("r1")!!.state)
        refused(Refusal.COMMITTED_ELSEWHERE, store.commit("r1", "C", allow))
        assertEquals(b, ok(store.commit("r1", "B", allow)))

        assertFalse("only the holder can finish it", store.finishCommit(b.id, "A"))
        assertTrue(store.finishCommit(b.id, "B"))
        assertFalse(store.finishCommit(b.id, "B"))
        assertFalse(store.voidCommit(b.id, "B"))
        assertEquals(AuthorityStore.APPLIED, store.request("r1")!!.state)
        assertEquals(AuthorityStore.FINISHED, store.tx { it.commitment(b.id) }!!.state)
        refused(Refusal.NONCE_REUSED, store.commit("r1", "C", allow))
    }

    // Nested entry

    @Test fun aStoreCallInsideATransactionIsRefusedAndNoEventEscapes() {
        try {
            store.tx { tx ->
                tx.enrolFirst(f.approver("A"), f.transcript("enrol", "A"))
                store.enrolFirst(f.approver("B"), f.transcript("enrol", "B")) // must use the Tx
            }
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            // expected
        }
        try {
            store.tx { f.recovery.status() }
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertTrue(f.events.isEmpty())
        assertEquals(emptyList<String>(), f.ids())
        assertEquals(0, store.generation())
        f.enrolFirst("A") // the store still works after a refused nested call
    }

    @Test fun composedWorkInOneTransactionRollsBackTogetherWithItsEvents() {
        f.enrolFirst("A")
        f.request("r1")
        f.events.clear()
        try {
            store.tx { tx ->
                ok(RecoveryMachine.start(tx, "agent-owner", emailVerified = true))
                ok(tx.execute("r1", "A", check = allow, effect = { t, _ -> t.raise(Reason.GRANT) }))
                ok(tx.commit("r1", "A", allow)) // refused: already applied
            }
            fail("should have thrown")
        } catch (e: AssertionError) {
            // expected: the last step failed, so the whole transaction is dropped
        }
        assertTrue(f.events.isEmpty())
        assertEquals(RecoveryMachine.NONE, f.recovery.status().state)
        assertEquals(AuthorityStore.OPEN, store.request("r1")!!.state)
        assertEquals(1, store.generation())

        store.tx { tx ->
            ok(RecoveryMachine.start(tx, "agent-owner", emailVerified = true))
            ok(RecoveryMachine.withdraw(tx, "agent-owner"))
        }
        assertEquals(listOf("RecoveryStarted", "RecoveryCancelled"), f.events.map { it::class.simpleName })
    }

    // Two stores on one file

    @Test fun twoStoresOnOneFileShareTheRunningClockSoAWallJumpShortensNothing() {
        val a = store
        f.enrolFirst("A")
        f.time.run(10 * HOUR) // a has stored nothing since hour 0
        val b = f.secondStore() // made at hour 10
        assertEquals(10 * HOUR, a.checkpoint())
        ok(RecoveryMachine(b).start("agent-owner", emailVerified = true)) // must end at counted hour 82, not 72
        assertEquals(82 * HOUR, f.recovery.status().wait.countedEndMs)
        f.time.wall += 500 * HOUR // the wall clock is no longer in the way
        f.time.run(62 * HOUR) // counted hour 72: only 62 h of recovery
        assertEquals(RecoveryMachine.PENDING, f.recovery.status().state)
        assertEquals(RecoveryMachine.PENDING, RecoveryMachine(b).status().state)
        assertEquals(listOf("A"), b.approvers().map { it.id })
        f.time.run(10 * HOUR - SECOND)
        assertEquals(RecoveryMachine.PENDING, RecoveryMachine(b).status().state)
        f.time.run(SECOND)
        assertEquals(RecoveryMachine.CLEARED, f.recovery.status().state)
        assertEquals(emptyList<String>(), b.approvers().map { it.id })
    }

    @Test fun theApproverWaitIsAlsoMeasuredOnTheSharedClock() {
        f.enrolFirst("A")
        f.time.run(10 * HOUR)
        val b = f.secondStore()
        store.checkpoint()
        ok(b.addApprover(f.approver("B"), "A", f.transcript("add", "B")))
        f.time.wall += 500 * HOUR
        f.time.run(14 * HOUR) // counted hour 24, but B was added at hour 10
        assertFalse(store.canApprove("B"))
        assertFalse(b.canApprove("B"))
        f.time.run(10 * HOUR)
        assertTrue(store.canApprove("B"))
    }

    @Test fun proposalsKeepTheirFirstCreationTime() {
        val first = store.putProposal(AuthorityStore.Proposal("p1", "", "approver_enrol", "{}", "owner_check_on_approver", "open"))
        f.time.run(HOUR)
        val second = store.putProposal(first.copy(state = "done"))
        assertEquals(first.createdMs, second.createdMs)
        assertEquals(first.createdMs + HOUR, second.updatedMs)
        assertEquals(emptyList<AuthorityStore.Proposal>(), store.proposals("open"))
        assertNull(store.tx { it.proposal("nope") })
    }

    @Test fun eventsAreSentOnlyAfterTheChangeIsStored() {
        try {
            store.tx { it.enrolFirst(f.approver("A"), f.transcript("enrol", "A")); error("fails after") }
        } catch (e: IllegalStateException) {
            // expected
        }
        assertTrue(f.events.isEmpty())
        f.enrolFirst("A")
        assertEquals(listOf<AuthorityEvent>(AuthorityEvent.ApproverEnrolled("A", waits = false)), f.events)
    }
}
