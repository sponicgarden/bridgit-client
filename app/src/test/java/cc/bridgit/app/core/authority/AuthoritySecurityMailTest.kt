package cc.bridgit.app.core.authority

import cc.bridgit.app.core.authority.AuthoritySecurityMail.Pass
import cc.bridgit.app.core.authority.AuthoritySecurityMail.Reply
import org.json.JSONObject
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
import java.io.IOException

/** Email delivery of the alert outbox: what is sent, what each answer of bridgit.cc does to the row, and the daily recovery reminder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthoritySecurityMailTest {
    private lateinit var f: AuthorityFixture
    private val sent = ArrayList<JSONObject>()
    private var replies = ArrayDeque<Any>()
    private var ready = true
    private val lines = ArrayList<String>()
    private val ok = Reply(200, """{"sent":true}""")

    /** Answers from [replies] in order (a Reply, or an exception to throw); when empty, 200 sent. */
    private val transport = AuthoritySecurityMail.Transport { body ->
        sent += JSONObject(String(body))
        when (val next = replies.removeFirstOrNull() ?: ok) {
            is Reply -> next
            is Exception -> throw next
            else -> error("bad reply")
        }
    }

    private fun mail() = AuthoritySecurityMail(f.store, f.time, { transport }, { ready }) { lines += it }
    private fun waiting() = f.store.undelivered(AuthorityOutbox.Way.EMAIL).map { it.kind }
    private fun mark(id: Long): Long = f.helper.readableDatabase.rawQuery("SELECT email_ms FROM outbox WHERE id = $id", null).use { it.moveToFirst(); it.getLong(0) }
    private fun daily() = f.store.tx { RecoveryMachine.daily(it) }

    @Before fun setup() { f = AuthorityFixture(RuntimeEnvironment.getApplication(), "authority-mail-test.db") }
    @After fun done() { f.close() }

    @Test fun aRowIsMailedOnceAndMarkedDelivered() {
        f.enrolFirst("phone")
        val m = mail()
        replies.add(Reply(200, """{"sent":true,"duplicate":true}"""))
        assertEquals(Pass.DONE, m.run(enabled = true))
        assertEquals(1, sent.size)
        assertTrue(waiting().isEmpty())
        assertEquals(f.time.wall, mark(1))
        assertEquals(Pass.IDLE, m.run(enabled = true))
        assertEquals(1, sent.size)
        // The other ways are untouched.
        assertEquals(listOf("approver_enrolled"), f.store.undelivered(AuthorityOutbox.Way.APPROVERS).map { it.kind })
    }

    @Test fun theRequestCarriesOnlyTheAllowedFieldsAndNeverTheOutboxBody() {
        f.enrolFirst("phone-secret-id")
        f.add("tablet-secret-id", "phone-secret-id")
        AuthorityFixture.ok(f.store.removeApprover("tablet-secret-id", "phone-secret-id", f.transcript("remove", "tablet-secret-id")))
        f.store.tx { it.emit(AuthorityEvent.SwitchOffRequested("agent-secret-id")); it.emit(AuthorityEvent.ApproversOff("agent-secret-id")); it.emit(AuthorityEvent.ApproversOn("agent-secret-id")) }
        AuthorityFixture.ok(f.recovery.start("agent-secret-id", emailVerified = true))
        f.time.run(RecoveryMachine.DAILY_MS)
        assertTrue(daily())
        AuthorityFixture.ok(f.recovery.withdraw("agent-secret-id"))
        AuthorityFixture.ok(f.recovery.start("agent-secret-id", emailVerified = true))
        f.time.run(73 * HOUR)
        AuthorityFixture.refused(AuthorityStore.Refusal.RECOVERY_COMPLETE, f.recovery.withdraw("agent-secret-id"))
        val rows = f.store.undelivered(AuthorityOutbox.Way.EMAIL)
        assertEquals(Pass.DONE, mail().run(enabled = true))
        assertEquals(AuthoritySecurityMail.KINDS, sent.map { it.getString("kind") }.toSet())
        assertEquals(rows.size, sent.size)
        for ((row, body) in rows.zip(sent)) {
            val clear = row.kind == "recovery_started" || row.kind == "recovery_daily"
            assertEquals(row.kind, if (clear) setOf("event_id", "kind", "at_ms", "clear_at_ms") else setOf("event_id", "kind", "at_ms"), body.keys().asSequence().toSet())
            assertTrue(Regex("[A-Za-z0-9_-]{1,64}").matches(body.getString("event_id")))
            assertEquals("${row.id}-${row.atMs}", body.getString("event_id"))
            assertEquals(row.atMs, body.getLong("at_ms"))
            if (clear) assertEquals(row.body.getLong("clear_at"), body.getLong("clear_at_ms"))
            assertFalse(body.toString().contains("secret-id"))
        }
    }

    @Test fun aKindThatIsNotMailedIsMarkedWithoutARequest() {
        f.enrolFirst("phone")
        f.request("r1")
        f.store.cancelRequests("test")
        assertEquals(listOf("approver_enrolled", "requests_cancelled"), waiting())
        assertEquals(Pass.DONE, mail().run(enabled = true))
        assertEquals(listOf("approver_enrolled"), sent.map { it.getString("kind") })
        assertTrue(waiting().isEmpty())
        assertEquals(AuthorityOutbox.SKIPPED, mark(2))
        assertEquals(null, AuthoritySecurityMail.request(AuthorityOutbox.Alert(9, "something_new", JSONObject(), 5)))
    }

    @Test fun aFinalRefusalStopsThatRowAndTheNextOneStillGoes() {
        val refusals = listOf(
            Reply(400, """{"error":"unknown_field"}"""), Reply(400, """{"error":"unknown_kind"}"""), Reply(400, """{"error":"bad_event"}"""),
            Reply(400, """{"error":"stale_event"}"""), Reply(400, """{"error":"event_id_reused"}"""), Reply(413), Reply(415, "not json"),
        )
        f.enrolFirst("phone")
        for (i in 1 until refusals.size) f.add("a$i", "phone")
        f.add("last", "phone")
        replies.addAll(refusals)
        assertEquals(Pass.DONE, mail().run(enabled = true))
        assertEquals(refusals.size + 1, sent.size)
        assertTrue(waiting().isEmpty())
        for (id in 1L..refusals.size) assertEquals(AuthorityOutbox.REJECTED, mark(id))
        assertEquals(f.time.wall, mark(refusals.size + 1L))
        assertTrue(lines.any { it.contains("stale_event") } && lines.any { it.contains("http_413") })
        // Nothing of what the server said beyond its code reaches the log.
        assertTrue(lines.none { it.contains("not json") })
    }

    @Test fun whileTheAddressIsNotConfirmedThereTheRowIsKeptAndTriedLater() {
        for ((status, body) in listOf(401 to "", 409 to """{"error":"not_confirmed"}""", 410 to """{"error":"not_subscribed"}""")) {
            f.close()
            f = AuthorityFixture(RuntimeEnvironment.getApplication(), "authority-mail-test.db")
            sent.clear(); replies.clear()
            f.enrolFirst("phone"); f.add("tablet", "phone")
            val m = mail()
            replies.add(Reply(status, body))
            assertEquals(Pass.STOPPED, m.run(enabled = true))
            assertEquals("the pass stops at the first row", 1, sent.size)
            assertEquals(listOf("approver_enrolled", "approver_added"), waiting())
            assertTrue(m.owing)
            f.time.run(AuthoritySecurityMail.HOLD_MS - SECOND)
            assertEquals(Pass.WAITING, m.run(enabled = true))
            assertEquals(1, sent.size)
            f.time.run(SECOND)
            assertEquals(Pass.DONE, m.run(enabled = true))
            assertEquals(3, sent.size)
            assertEquals("the same event id on the second try", sent[0].getString("event_id"), sent[1].getString("event_id"))
            assertTrue(waiting().isEmpty())
            assertFalse(m.owing)
        }
    }

    @Test fun confirmingTheAddressEndsTheHoldAtOnce() {
        f.enrolFirst("phone")
        val m = mail()
        replies.add(Reply(409, """{"error":"not_confirmed"}"""))
        assertEquals(Pass.STOPPED, m.run(enabled = true))
        ready = false // the app learns the address is not confirmed
        assertEquals(Pass.NOT_READY, m.run(enabled = true))
        ready = true // the owner opened the link
        assertEquals(Pass.DONE, m.run(enabled = true))
        assertEquals(2, sent.size)
    }

    @Test fun nothingIsSentWithoutAConfirmedAddress() {
        f.enrolFirst("phone")
        ready = false
        assertEquals(Pass.NOT_READY, mail().run(enabled = true))
        assertTrue(sent.isEmpty())
        assertEquals(listOf("approver_enrolled"), waiting())
    }

    @Test fun aRateLimitWaitsAsLongAsBridgitSaid() {
        f.enrolFirst("phone")
        val m = mail()
        replies.add(Reply(429, """{"error":"rate_limited","retry_after_s":90}""", retryAfter = "7"))
        assertEquals(Pass.STOPPED, m.run(enabled = true))
        f.time.run(89 * SECOND)
        assertEquals(Pass.WAITING, m.run(enabled = true))
        f.time.run(SECOND)
        replies.add(Reply(429, "", retryAfter = "30")) // no body: the header
        assertEquals(Pass.STOPPED, m.run(enabled = true))
        f.time.run(29 * SECOND)
        assertEquals(Pass.WAITING, m.run(enabled = true))
        f.time.run(SECOND)
        assertEquals(Pass.DONE, m.run(enabled = true))
        assertEquals(3, sent.size)
        assertEquals(1, sent.map { it.getString("event_id") }.toSet().size)
    }

    @Test fun anOutageBacksOffLongerEachTimeAndClearsOnSuccess() {
        f.enrolFirst("phone")
        val m = mail()
        replies.addAll(listOf(Reply(503), IOException("offline"), Reply(500), Reply(302)))
        var wait = AuthoritySecurityMail.FIRST_RETRY_MS
        repeat(4) {
            assertEquals(Pass.STOPPED, m.run(enabled = true))
            assertEquals(listOf("approver_enrolled"), waiting())
            f.time.run(wait - SECOND)
            assertEquals(Pass.WAITING, m.run(enabled = true))
            f.time.run(SECOND)
            wait *= 2
        }
        assertEquals(4, sent.size)
        assertEquals(Pass.DONE, m.run(enabled = true))
        f.add("tablet", "phone")
        replies.add(Reply(503))
        assertEquals(Pass.STOPPED, m.run(enabled = true))
        f.time.run(AuthoritySecurityMail.FIRST_RETRY_MS)
        assertEquals("after a success the wait starts short again", Pass.DONE, m.run(enabled = true))
        assertEquals(Pass.IDLE, m.run(enabled = true))
    }

    @Test fun aRestartOfTheDeviceDoesNotLeaveTheSenderWaitingForever() {
        f.enrolFirst("phone")
        val m = mail()
        replies.add(Reply(503))
        f.time.elapsed = 40 * 24 * HOUR
        assertEquals(Pass.STOPPED, m.run(enabled = true))
        f.time.elapsed = 1_000 // time since boot starts over
        assertEquals(Pass.DONE, m.run(enabled = true))
    }

    @Test fun withTheSwitchOffNothingIsSentExceptThePassAfterTurningItOff() {
        f.enrolFirst("phone")
        f.store.tx { it.emit(AuthorityEvent.ApproversOff("agent-1")) }
        val m = mail()
        assertEquals(Pass.OFF, m.run(enabled = false))
        assertTrue(sent.isEmpty())
        assertEquals(Pass.DONE, m.run(enabled = false, afterOff = true))
        assertEquals(listOf("approver_enrolled", "approvers_off"), sent.map { it.getString("kind") })
    }

    @Test fun theEventIdIsTheSameAfterARestart() {
        f.enrolFirst("phone")
        AuthorityFixture.ok(f.recovery.start("agent-1", emailVerified = true))
        replies.add(IOException("offline"))
        assertEquals(Pass.STOPPED, mail().run(enabled = true))
        val first = sent.single()
        f.time.run(5 * HOUR)
        f.restart(reboot = true)
        assertEquals(Pass.DONE, mail().run(enabled = true))
        assertEquals(first.toString(), sent[1].toString())
        assertEquals(listOf("approver_enrolled", "approver_enrolled", "recovery_started"), sent.map { it.getString("kind") })
        assertEquals(2, sent.map { it.getString("event_id") }.toSet().size)
    }

    @Test fun aWaitingRecoveryIsAnnouncedAgainOnceADay() {
        f.enrolFirst("phone")
        assertFalse("no recovery, no reminder", daily())
        val r = AuthorityFixture.ok(f.recovery.start("agent-1", emailVerified = true))
        f.time.run(RecoveryMachine.DAILY_MS - SECOND)
        assertFalse(daily())
        f.time.run(SECOND)
        assertTrue(daily())
        assertFalse("one a day", daily())
        f.restart()
        assertFalse("a restart does not repeat it", daily())
        f.time.run(RecoveryMachine.DAILY_MS - SECOND)
        assertFalse(daily())
        f.restart(reboot = true)
        f.time.run(SECOND)
        assertTrue(daily())
        val dailies = f.store.undelivered(AuthorityOutbox.Way.EMAIL).filter { it.kind == "recovery_daily" }
        assertEquals(2, dailies.size)
        assertTrue(dailies.all { it.body.getLong("clear_at") == r.wait.wallEndMs })
        // Past the 72 hours the list is cleared before the reminder is looked at.
        f.time.run(25 * HOUR)
        assertFalse(daily())
        assertEquals(RecoveryMachine.CLEARED, f.recovery.status().state)
        assertEquals(2, f.store.undelivered(AuthorityOutbox.Way.EMAIL).count { it.kind == "recovery_daily" })
    }

    @Test fun aDeviceThatWasOffForDaysSendsOneReminderAndACancelledRecoveryNone() {
        f.enrolFirst("phone")
        AuthorityFixture.ok(f.recovery.start("agent-1", emailVerified = true))
        f.time.off(60 * HOUR) // off: the 72 hours of running time are not over
        assertTrue(daily())
        assertFalse(daily())
        AuthorityFixture.ok(f.recovery.withdraw("agent-1"))
        f.time.run(30 * HOUR)
        assertFalse(daily())
        // A new recovery counts from its own start, not from the old reminder.
        AuthorityFixture.ok(f.recovery.start("agent-1", emailVerified = true))
        f.time.run(RecoveryMachine.DAILY_MS - SECOND)
        assertFalse(daily())
        f.time.run(SECOND)
        assertTrue(daily())
    }

    @Test fun theAnswersOfBridgitMapToWhatTheRowDoesNext() {
        fun o(status: Int, body: String = "", retryAfter: String? = null) = AuthoritySecurityMail.outcome(Reply(status, body, retryAfter))
        assertEquals(AuthoritySecurityMail.Outcome.Sent, o(200, """{"sent":true}"""))
        // 200 is delivered whatever the body: empty, a duplicate acknowledgement, not JSON.
        for (body in listOf("", "{}", """{"duplicate":true}""", """{"sent":true,"duplicate":true}""", "<html>")) assertEquals(AuthoritySecurityMail.Outcome.Sent, o(200, body))
        assertEquals(AuthoritySecurityMail.Outcome.Rejected("unknown_field"), o(400, """{"error":"unknown_field"}"""))
        assertEquals(AuthoritySecurityMail.Outcome.Rejected("http_400"), o(400, """{"error":"Not A Code <script>"}"""))
        assertEquals(AuthoritySecurityMail.Outcome.Hold("not_confirmed"), o(409, """{"error":"not_confirmed"}"""))
        assertEquals(AuthoritySecurityMail.Outcome.Hold("http_401"), o(401))
        assertEquals(AuthoritySecurityMail.Outcome.Retry(12_000), o(429, """{"retry_after_s":12}"""))
        assertEquals(AuthoritySecurityMail.Outcome.Retry(null), o(429, "", "soon"))
        assertEquals(AuthoritySecurityMail.Outcome.Retry(AuthoritySecurityMail.MAX_WAIT_MS), o(429, """{"retry_after_s":99999999}"""))
        for (s in listOf(500, 502, 503, 504, 302, 404)) assertEquals(AuthoritySecurityMail.Outcome.Retry(null), o(s))
    }

    @Test fun anEmptyOrDuplicateOnlyAnswerDeliversTheRowAndTheNextOneGoes() {
        f.enrolFirst("phone"); f.add("tablet", "phone"); f.add("watch", "phone")
        replies.addAll(listOf(Reply(200, ""), Reply(200, """{"duplicate":true}""")))
        assertEquals(Pass.DONE, mail().run(enabled = true))
        assertEquals(listOf("approver_enrolled", "approver_added", "approver_added"), sent.map { it.getString("kind") })
        assertTrue(waiting().isEmpty())
        for (id in 1L..3L) assertEquals(f.time.wall, mark(id))
    }
}
