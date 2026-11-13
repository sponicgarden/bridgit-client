package cc.bridgit.app.core.spending

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import cc.bridgit.app.core.spending.Journal.Basis
import cc.bridgit.app.core.spending.Journal.HoldResult
import cc.bridgit.app.core.spending.Journal.Origin
import cc.bridgit.app.core.spending.Journal.Period
import cc.bridgit.app.core.spending.Journal.State
import cc.bridgit.app.core.spending.Journal.Usage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The spend journal (spending authority G5.3; A0300 SA2.3, journal part). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JournalTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val keys = SoftwareJournalKeys()
    private val clock = FakeClock()
    private val opened = mutableListOf<SpendingDb>()

    private val day1 = Period("day:2026-11-11", 1_793_923_200_000, 1_794_009_600_000)
    private val day2 = Period("day:2026-11-12", 1_794_009_600_000, 1_794_096_000_000)
    private val month = Period("month:2026-11", 1_793_059_200_000, 1_795_651_200_000)
    private val zero = Money.zero("USD-6")

    @Before fun clean() { for (ns in Namespace.values()) app.deleteDatabase(SpendingDb.fileName(ns)) }
    @After fun close() { opened.forEach { it.close() } }

    private fun usd(atoms: Long) = Money("USD-6", atoms)
    private fun db(ns: Namespace = Namespace.SANDBOX) = SpendingDb(app, ns).also { opened += it }
    private fun journal(ns: Namespace = Namespace.SANDBOX, install: String = "install-a") = Journal.open(db(ns), keys, install, clock)
    private fun id(request: String, ns: Namespace = Namespace.SANDBOX) = SpendId.of(ns, "install-a", request)

    private fun request(name: String, atoms: Long, limit: Long = 10_000, lines: List<Journal.Line>? = null, instrument: String = "sandbox", claimed: String? = null) =
        Journal.HoldRequest(
            spendId = id(name, Namespace.of(instrument)), instrument = instrument, source = "key-1", held = usd(atoms),
            lines = lines ?: listOf(Journal.Line("budget:day", day1.id, usd(limit))), principalKind = "agent", principalId = "a1", claimedNamespace = claimed,
        )

    private fun used(j: Journal, rule: String = "budget:day", period: Period = day1) = (j.usage(rule, period.id) as Usage.Counted).amount.atoms
    private fun refusal(r: HoldResult) = (r as HoldResult.Refused).refusal

    /** Opens the file directly, as something other than the journal would. */
    private fun raw(ns: Namespace = Namespace.SANDBOX, f: (SQLiteDatabase) -> Unit) {
        opened.forEach { it.close() }
        SQLiteDatabase.openDatabase(app.getDatabasePath(SpendingDb.fileName(ns)).path, null, SQLiteDatabase.OPEN_READWRITE).use(f)
    }

    private fun ready(): Journal = journal().also { it.openP("budget:day", day1, Basis.FIRST_PORTION, zero) }

    // ---- a new journal ----

    @Test fun aNewJournalCountsEveryPeriodAsFullyUsedUntilAnEntryOpensIt() {
        val j = journal()
        assertNull(j.reset)
        assertEquals(Origin.FIRST, j.head().origin)
        assertEquals(1L, j.head().seq)
        assertEquals(Usage.FullyUsed, j.usage("budget:day", day1.id))
        val r = refusal(j.hold(request("r1", 1)))
        assertEquals(SpendRefusal.Code.ALLOWANCE_EXHAUSTED, r.code)
        assertEquals(SpendRefusal.Reason.PERIOD_NOT_OPEN, r.reason)
        assertEquals("nothing was written for a refused hold", 1L, j.head().seq)

        // The rule document gives this install its first portion after the epoch began (G5.3 rule 4).
        j.openP("budget:day", day1, Basis.FIRST_PORTION, zero)
        assertEquals(0L, used(j))
        assertTrue(j.hold(request("r1", 1)) is HoldResult.Held)
        // Another period id of the same rule is still not open.
        assertEquals(Usage.FullyUsed, j.usage("budget:day", day2.id))
        // Another rule is untouched by it.
        assertEquals(Usage.FullyUsed, j.usage("budget:month", month.id))
    }

    @Test fun holdsAndOutcomesMoveTheCountAndTheJournalOnlyGrows() {
        val j = ready()
        val held = (j.hold(request("r1", 1_500)) as HoldResult.Held).entry
        assertEquals(State.HELD, held.state)
        assertEquals(1_500L, used(j))

        // The same spend id holds nothing more.
        assertTrue(j.hold(request("r1", 1_500)) is HoldResult.Existing)
        assertTrue(j.hold(request("r1", 9_000)) is HoldResult.Existing)
        assertEquals(1_500L, used(j))

        // No room for the whole hold: refused, nothing written.
        val before = j.head().seq
        assertEquals(SpendRefusal(SpendRefusal.Code.ALLOWANCE_EXHAUSTED), refusal(j.hold(request("r2", 8_501))))
        assertEquals(before, j.head().seq)
        assertTrue(j.hold(request("r2", 8_500)) is HoldResult.Held)
        assertEquals(10_000L, used(j))

        assertEquals(usd(1_200), j.settle(id("r1"), usd(1_200), providerRef = "ref-1").settled)
        assertEquals(9_700L, used(j))
        assertEquals("settling twice at the same figure is the same entry", j.latest(id("r1"))!!.seq, j.settle(id("r1"), usd(1_200)).seq)

        j.release(id("r2"), "not_billed")
        assertEquals(1_200L, used(j))
        assertEquals(State.RELEASED, j.latest(id("r2"))!!.state)

        // Four entries for two spends, each its own row; the first hold is still there, unchanged.
        val all = j.entries()
        assertEquals(listOf("epoch", "rules", "period", "spend", "spend", "spend", "spend"), all.map { it.kind })
        assertEquals(held.json, all[3].json)
        assertEquals((1L..7L).toList(), all.map { it.seq })
        assertNull(j.verify())

        // A second attempt of the same request is its own spend with its own hold (G2.2 "Attempts").
        assertTrue(j.hold(request("r1", 100).copy(attempt = 1)) is HoldResult.Held)
        assertEquals(1_300L, used(j))
    }

    @Test fun aSettlementAboveItsHoldIsRecordedAndRefusesEverythingFurther() {
        val j = ready()
        j.hold(request("r1", 9_000))
        j.settle(id("r1"), usd(12_000))
        assertEquals(12_000L, used(j))
        assertEquals(SpendRefusal.Code.ALLOWANCE_EXHAUSTED, refusal(j.hold(request("r2", 1))).code)
    }

    @Test fun refundsPointAtTheSettledEntryAndDoNotReopenThePeriod() {
        val j = ready()
        j.hold(request("r1", 2_000))
        assertThrows(JournalRefused::class.java) { j.refund(id("r1"), usd(1)) }
        val settled = j.settle(id("r1"), usd(2_000))
        assertThrows(JournalRefused::class.java) { j.refund(id("r1"), usd(2_001)) }
        val refund = j.refund(id("r1"), usd(500), providerRef = "rf-1")
        assertEquals(State.REFUNDED, refund.state)
        assertEquals(usd(500), refund.refunded)
        assertEquals(settled.seq, refund.body.getLong("refers"))
        assertEquals(2_000L, used(j))
        assertThrows(JournalRefused::class.java) { j.settle(id("r1"), usd(1)) }
    }

    // ---- unknown outcomes ----

    @Test fun anUnknownOutcomeKeepsItsWholeHold() {
        val j = ready()
        j.hold(request("r1", 4_000))
        j.unknown(id("r1"), "no_answer")
        assertEquals(State.UNKNOWN, j.latest(id("r1"))!!.state)
        assertEquals(4_000L, used(j))

        // Nothing but proof gives it back, and time is not proof.
        clock.advance(400L * 24 * 60 * 60 * 1000)
        assertEquals("unknown_keeps_its_hold", assertThrows(JournalRefused::class.java) { j.release(id("r1"), "timeout") }.why)
        assertEquals("lower_needs_a_qualifying_read", assertThrows(JournalRefused::class.java) { j.settle(id("r1"), usd(100)) }.why)
        assertEquals(4_000L, used(j))
        assertEquals(State.UNKNOWN, j.latest(id("r1"))!!.state)

        // A qualifying provider read settles it at the real figure, lower or not.
        assertEquals(usd(100), j.settle(id("r1"), usd(100), qualifying = true).settled)
        assertEquals(100L, used(j))
    }

    @Test fun anUnknownOutcomeCanBeGivenBackOnlyOnAQualifyingReadAndSettlesUpwardOnAnyRead() {
        val j = ready()
        j.hold(request("r1", 4_000))
        j.unknown(id("r1"), "no_answer")
        j.release(id("r1"), "provider_shows_no_charge", qualifying = true)
        assertEquals(0L, used(j))

        j.hold(request("r2", 1_000))
        j.unknown(id("r2"), "no_answer")
        // The provider's figure wins when it is higher (G5.3 step 6).
        j.settle(id("r2"), usd(1_700))
        assertEquals(1_700L, used(j))
        // A charge found after a give-back is recorded too.
        j.settle(id("r1"), usd(300), reason = "late_charge")
        assertEquals(2_000L, used(j))
    }

    @Test fun atTheEndOfRetentionAnUnknownOutcomeSettlesAtItsHold() {
        val j = ready()
        j.hold(request("old", 4_000))
        j.unknown(id("old"), "no_answer")
        clock.advance(1_000)
        j.hold(request("new", 500))
        j.unknown(id("new"), "no_answer")
        j.hold(request("held", 10))

        assertEquals(1, j.settleUnknownAtHold(beforeWallMs = clock.wall))
        val old = j.latest(id("old"))!!
        assertEquals(State.SETTLED, old.state)
        assertEquals(usd(4_000), old.settled)
        assertEquals("retention", old.reason)
        assertEquals(State.UNKNOWN, j.latest(id("new"))!!.state)
        assertEquals(State.HELD, j.latest(id("held"))!!.state)
        assertEquals(4_510L, used(j))
        assertEquals(0, j.settleUnknownAtHold(beforeWallMs = clock.wall))
    }

    @Test fun theEndOfADayDoesNotReturnAnUnknownHoldToTheMonth() {
        val j = journal()
        j.openP("budget:day", day1, Basis.FIRST_PORTION, zero)
        j.openP("budget:month", month, Basis.FIRST_PORTION, zero)
        val lines = listOf(Journal.Line("budget:day", day1.id, usd(5_000)), Journal.Line("budget:month", month.id, usd(50_000)))
        j.hold(request("r1", 4_000, lines = lines))
        j.unknown(id("r1"), "no_answer")

        // A line with no room refuses the whole hold, whatever the other line has.
        assertEquals(SpendRefusal.Code.ALLOWANCE_EXHAUSTED, refusal(j.hold(request("r2", 1_001, lines = lines))).code)

        j.openP("budget:day", day2, Basis.NEXT_PERIOD, zero)
        assertEquals(0L, used(j, "budget:day", day2))
        assertEquals(4_000L, used(j, "budget:month", month))

        // It settles later, above its hold: the month it was opened in takes the difference; the new day owes nothing.
        j.settle(id("r1"), usd(4_500))
        assertEquals(4_500L, used(j, "budget:month", month))
        assertEquals(0L, used(j, "budget:day", day2))
    }

    // ---- periods ----

    @Test fun aPeriodOpensOnlyAfterTheRecordedOneEnds() {
        val j = ready()
        assertEquals("rule_has_a_period", assertThrows(JournalRefused::class.java) { j.openP("budget:day", day2, Basis.FIRST_PORTION, zero) }.why)
        assertEquals("period_not_later", assertThrows(JournalRefused::class.java) { j.openP("budget:day", day1, Basis.NEXT_PERIOD, zero) }.why)
        val overlapping = Period("day:odd", day1.endsMs - 1, day2.endsMs)
        assertEquals("period_not_later", assertThrows(JournalRefused::class.java) { j.openP("budget:day", overlapping, Basis.NEXT_PERIOD, zero) }.why)
        assertEquals("no_recorded_period", assertThrows(JournalRefused::class.java) { j.openP("budget:other", day2, Basis.NEXT_PERIOD, zero) }.why)
        assertEquals("a_new_period_starts_at_zero", assertThrows(JournalRefused::class.java) { j.openP("budget:day", day2, Basis.NEXT_PERIOD, usd(1)) }.why)
        assertEquals("nothing_to_rebuild", assertThrows(JournalRefused::class.java) { j.openP("budget:day", day1, Basis.PROVIDER_READ, zero) }.why)
        j.openP("budget:day", day2, Basis.NEXT_PERIOD, zero)
        assertEquals(Usage.FullyUsed, j.usage("budget:day", day1.id))
        assertEquals(0L, used(j, period = day2))
    }

    // ---- tampering ----

    @Test fun aChangedEntryBreaksTheChainAndTheNextOpenStartsANewEpochWithEverythingFullyUsed() {
        val j = ready()
        j.hold(request("r1", 4_000))
        j.settle(id("r1"), usd(3_000))
        val oldEpoch = j.head().epoch
        assertNull(j.verify())

        // Someone lowers a settled amount in place.
        raw { it.execSQL("UPDATE entries SET body = replace(body, '\"atoms\":3000', '\"atoms\":1') WHERE seq = 5") }
        assertEquals("chain_broken_at_5", journalWithoutReset().verify())

        val after = journal()
        assertEquals("chain_broken_at_5", after.reset!!.reason)
        assertEquals(oldEpoch, after.reset!!.previousEpoch)
        assertNotEquals(oldEpoch, after.head().epoch)
        assertEquals(Origin.RESET, after.head().origin)
        assertNull(after.verify())

        // Every current period of every rule is fully used, and the reason says why.
        assertEquals(Usage.FullyUsed, after.usage("budget:day", day1.id))
        val r = refusal(after.hold(request("r2", 1)))
        assertEquals(SpendRefusal(SpendRefusal.Code.ALLOWANCE_EXHAUSTED, SpendRefusal.Reason.JOURNAL_UNVERIFIED), r)
        // The old count cannot be claimed back as a first portion.
        assertEquals("not_a_first_journal", assertThrows(JournalRefused::class.java) { after.openP("budget:day", day1, Basis.FIRST_PORTION, zero) }.why)

        // The first anchor only names the current period; the first that opens is the one after it (G5.2 rule 3).
        after.openP("budget:day", day1, Basis.NAMED, zero)
        assertEquals(Usage.FullyUsed, after.usage("budget:day", day1.id))
        assertEquals(SpendRefusal.Reason.JOURNAL_UNVERIFIED, refusal(after.hold(request("r2", 1))).reason)
        after.openP("budget:day", day2, Basis.NEXT_PERIOD, zero)
        assertTrue(after.hold(request("r3", 1, lines = listOf(Journal.Line("budget:day", day2.id, usd(10))))) is HoldResult.Held)

        // The old epoch's rows are kept beside the new one's.
        assertEquals(listOf(oldEpoch, after.head().epoch), after.epochs())
        assertEquals(5, after.entries(oldEpoch).size)
    }

    /** A journal object over the file as it is, without the check that [Journal.open] does first. */
    private fun journalWithoutReset(): Journal {
        val c = Journal::class.java.getDeclaredConstructor(SpendingDb::class.java, JournalKeys::class.java, String::class.java, SpendClock::class.java)
        c.isAccessible = true
        return c.newInstance(db(), keys, "install-a", clock)
    }

    @Test fun everyKindOfDamageIsFound() {
        fun damaged(expect: String, damage: (SQLiteDatabase) -> Unit) {
            opened.forEach { it.close() }
            clean()
            keys.forget(Namespace.SANDBOX)
            val j = ready()
            j.hold(request("r1", 4_000))
            j.unknown(id("r1"), "no_answer")
            raw(f = damage)
            val after = journal()
            assertEquals(expect, after.reset?.reason)
            assertEquals(Usage.FullyUsed, after.usage("budget:day", day1.id))
        }
        damaged("chain_broken_at_5") { it.execSQL("DELETE FROM entries WHERE seq = 4") }
        damaged("head_behind_chain") { it.execSQL("DELETE FROM entries WHERE seq = 5") }
        damaged("chain_broken_at_5") { it.execSQL("UPDATE entries SET mac = (SELECT mac FROM entries WHERE seq = 4) WHERE seq = 5") }
        damaged("chain_broken_at_4") { it.execSQL("UPDATE entries SET hash = '${"0".repeat(64)}' WHERE seq = 4") }
        damaged("head_missing") { it.execSQL("DELETE FROM head") }
        damaged("head_mac") { it.execSQL("UPDATE head SET body = replace(body, '\"atoms\":4000', '\"atoms\":0')") }
        damaged("head_unreadable") { it.execSQL("UPDATE head SET body = '{}'") }
        damaged("head_missing") { it.execSQL("DELETE FROM head"); it.execSQL("DELETE FROM entries") }
    }

    @Test fun aLookupColumnThatDisagreesWithItsEntryIsNeverBelieved() {
        val j = ready()
        j.hold(request("r1", 100))
        j.settle(id("r1"), usd(10))
        assertEquals(10L, used(j))

        // Only the settlement row's lookup column is cleared. The entry's JSON, hash and MAC are untouched.
        raw { it.execSQL("UPDATE entries SET spend_key = NULL WHERE seq = 5") }

        // The object that verified the chain goes by what it verified, sees the row changed, and writes nothing.
        assertEquals("entry_changed_at_5", assertThrows(JournalNotWhole::class.java) { j.settle(id("r1"), usd(10)) }.why)
        // An object that has verified nothing checks the whole chain before it believes a lookup.
        val stale = journalWithoutReset()
        assertEquals("lookup_column_at_5", stale.verify())
        assertEquals("lookup_column_at_5", assertThrows(JournalNotWhole::class.java) { stale.settle(id("r1"), usd(10)) }.why)
        assertEquals("lookup_column_at_5", assertThrows(JournalNotWhole::class.java) { stale.hold(request("r1", 100)) }.why)
        assertEquals("lookup_column_at_5", assertThrows(JournalNotWhole::class.java) { stale.latest(id("r1")) }.why)
        // The count did not move: before the fix the second settlement found the 100 hold and left it at -80.
        assertEquals(10L, used(stale))
        assertEquals(5L, stale.head().seq)

        val after = journal()
        assertEquals("lookup_column_at_5", after.reset!!.reason)
        assertEquals(Usage.FullyUsed, after.usage("budget:day", day1.id))
        assertTrue("and the request is still known", after.hold(request("r1", 100)) is HoldResult.Historical)
    }

    @Test fun aLookupColumnPointedAtAnotherSpendIsNotBelievedEither() {
        val j = ready()
        j.hold(request("r1", 100))
        j.hold(request("r2", 7))
        raw { it.execSQL("UPDATE entries SET spend_key = ? WHERE seq = 5", arrayOf<Any?>(id("r1") + "#0")) }
        assertEquals("lookup_column_at_5", journalWithoutReset().verify())
        assertEquals("lookup_column_at_5", journal().reset!!.reason)
    }

    @Test fun aSettlementTheCountCannotHoldIsRecordedAndClosesThePeriod() {
        val j = ready()
        j.hold(request("r1", 10))
        j.settle(id("r1"), usd(10))
        j.hold(request("r2", 1))

        // The provider reports a figure that fits by itself and overflows the period's count.
        val e = j.settle(id("r2"), usd(Long.MAX_VALUE))
        assertEquals(State.SETTLED, e.state)
        assertEquals(usd(Long.MAX_VALUE), j.latest(id("r2"))!!.settled)
        assertEquals(Usage.Overflowed, j.usage("budget:day", day1.id))

        // The known overshoot refuses everything further, whatever limit the caller brings.
        val closed = SpendRefusal(SpendRefusal.Code.ALLOWANCE_EXHAUSTED, SpendRefusal.Reason.COUNT_OVERFLOW)
        assertEquals(closed, refusal(j.hold(request("r3", 1, limit = Long.MAX_VALUE))))
        assertEquals(closed, refusal(j.hold(request("r4", 0, limit = Long.MAX_VALUE))))

        // It is part of the verified state, survives a reopen, and no later correction reopens the period.
        assertNull(j.verify())
        val again = journal()
        assertNull(again.reset)
        assertEquals(Usage.Overflowed, again.usage("budget:day", day1.id))
        again.settle(id("r2"), usd(1), qualifying = true)
        assertEquals(Usage.Overflowed, again.usage("budget:day", day1.id))
        assertEquals(closed, refusal(again.hold(request("r5", 1))))
        assertNull(again.verify())

        // The next period starts clean.
        again.openP("budget:day", day2, Basis.NEXT_PERIOD, zero)
        assertEquals(0L, used(again, period = day2))
    }

    @Test fun aHoldTheCountCannotHoldIsRefusedBeforeAnythingRuns() {
        val j = ready()
        j.hold(request("r1", 10, limit = Long.MAX_VALUE))
        val seq = j.head().seq
        assertEquals(SpendRefusal(SpendRefusal.Code.ALLOWANCE_EXHAUSTED), refusal(j.hold(request("r2", Long.MAX_VALUE, limit = Long.MAX_VALUE))))
        assertEquals(seq, j.head().seq)
        assertNull(j.latest(id("r2")))
        // Nothing was marked: the period still counts and still has room.
        assertEquals(10L, used(j))
        assertTrue(j.hold(request("r2", Long.MAX_VALUE - 10, limit = Long.MAX_VALUE)) is HoldResult.Held)
    }

    @Test fun anOlderCopyOfTheHeadDoesNotMatchTheChain() {
        val j = ready()
        j.hold(request("r1", 4_000))
        var head: Pair<String, String>? = null
        raw { d -> d.rawQuery("SELECT body, mac FROM head", null).use { c -> c.moveToFirst(); head = c.getString(0) to c.getString(1) } }
        val j2 = journal()
        assertNull(j2.reset)
        j2.hold(request("r2", 5_000))
        // The head is put back to before the second hold: validly MACed, but older than the chain.
        raw { it.execSQL("UPDATE head SET body = ?, mac = ?", arrayOf<Any?>(head!!.first, head!!.second)) }
        val stale = journalWithoutReset()
        assertEquals("head_behind_chain", stale.verify())
        assertEquals("head_behind_chain", assertThrows(JournalNotWhole::class.java) { stale.hold(request("r3", 1)) }.why)
        assertEquals("head_behind_chain", journal().reset!!.reason)
    }

    // ---- atomicity ----

    @Test fun anEntryAndItsHeadCommitTogetherOrNotAtAll() {
        val j = ready()
        j.hold(request("r1", 1_000))
        val head = j.head()
        val rows = j.entries().size

        j.afterEntryWrite = { throw IllegalStateException("power lost") }
        assertThrows(IllegalStateException::class.java) { j.hold(request("r2", 2_000)) }
        assertThrows(IllegalStateException::class.java) { j.settle(id("r1"), usd(900)) }
        assertThrows(IllegalStateException::class.java) { j.openP("budget:month", month, Basis.FIRST_PORTION, zero) }
        j.afterEntryWrite = null

        // Neither the entry nor the head moved, in this object or in a fresh one over the same file.
        for (view in listOf(j, journal())) {
            assertNull(view.reset)
            assertEquals(head, view.head())
            assertEquals(rows, view.entries().size)
            assertNull(view.latest(id("r2")))
            assertEquals(State.HELD, view.latest(id("r1"))!!.state)
            assertEquals(1_000L, used(view))
            assertNull(view.verify())
        }
        // And the journal carries on from where it was.
        val k = journal()
        assertEquals(head.seq + 1, (k.hold(request("r2", 2_000)) as HoldResult.Held).entry.seq)
        assertEquals(3_000L, used(k))
    }

    // ---- epochs ----

    @Test fun theEpochLivesAndDiesWithItsKey() {
        val j = ready()
        j.hold(request("r1", 4_000))
        j.unknown(id("r1"), "no_answer")
        val first = j.head().epoch
        assertEquals(first, keys.epoch(Namespace.SANDBOX))

        // Reopened whole: same epoch, same count, no reset.
        val same = journal()
        assertNull(same.reset)
        assertEquals(first, same.head().epoch)
        assertEquals(4_000L, used(same))

        // The key is gone and the file is not: a new epoch, everything fully used, old rows kept.
        keys.forget(Namespace.SANDBOX)
        val second = journal()
        assertEquals("key_missing", second.reset!!.reason)
        assertEquals(Origin.RESET, second.head().origin)
        assertEquals(Usage.FullyUsed, second.usage("budget:day", day1.id))
        assertEquals("the old epoch's unknown hold is still on record", State.UNKNOWN, second.entries(first).last().state)
        // Its request is still known: it is not held again in the new epoch.
        val again = second.hold(request("r1", 4_000)) as HoldResult.Historical
        assertEquals(first, again.epoch)
        assertEquals(State.UNKNOWN, again.entry!!.state)
        val reset = JSONObject(second.entries().first().json)
        assertEquals("reset", reset.getString("origin"))
        assertEquals("key_missing", reset.getString("reason"))
        assertEquals("install-a", reset.getString("install"))

        // The file is replaced by an empty one and the key is kept (the Apple case): also a reset, not a first journal.
        val secondEpoch = second.head().epoch
        opened.forEach { it.close() }
        clean()
        val third = journal()
        assertEquals("head_missing", third.reset!!.reason)
        assertEquals(Origin.RESET, third.head().origin)
        assertNotEquals(secondEpoch, third.head().epoch)

        // Key and file both gone (Android after "clear data", which also makes a new install id): a first journal.
        opened.forEach { it.close() }
        clean()
        keys.forget(Namespace.SANDBOX)
        val fresh = journal(install = "install-b")
        assertNull(fresh.reset)
        assertEquals(Origin.FIRST, fresh.head().origin)
        assertEquals(Usage.FullyUsed, fresh.usage("budget:day", day1.id))
    }

    @Test fun aRequestOfAnEarlierEpochIsNeverHeldAgain() {
        val j = ready()
        j.hold(request("r1", 4_000))
        j.unknown(id("r1"), "no_answer")
        j.hold(request("r2", 100))
        j.settle(id("r2"), usd(100))
        val first = j.head().epoch

        // The journal resets with the install kept, and the period's count is rebuilt from a provider read.
        keys.forget(Namespace.SANDBOX)
        val k = journal()
        k.openP("budget:day", day1, Basis.NAMED, zero)
        k.openP("budget:day", day1, Basis.PROVIDER_READ, usd(4_100))
        val seq = k.head().seq

        // The old request comes again with the same attempt. It may already have run: no hold, so no execute.
        val again = k.hold(request("r1", 4_000)) as HoldResult.Historical
        assertEquals(first, again.epoch)
        assertEquals(State.UNKNOWN, again.entry!!.state)
        assertEquals(State.SETTLED, (k.hold(request("r2", 100)) as HoldResult.Historical).entry!!.state)
        assertEquals("nothing was written", seq, k.head().seq)
        assertEquals(4_100L, used(k))

        // A retry that is meant to run again is a new attempt, with its own hold. A new request is untouched.
        assertTrue(k.hold(request("r1", 4_000).copy(attempt = 1)) is HoldResult.Held)
        assertEquals(8_100L, used(k))
        assertTrue(k.hold(request("r9", 1)) is HoldResult.Held)

        // A second reset keeps the requests of both earlier epochs.
        val secondEpoch = k.head().epoch
        keys.forget(Namespace.SANDBOX)
        val m = journal()
        assertEquals(first, (m.hold(request("r1", 1)) as HoldResult.Historical).epoch)
        assertEquals(secondEpoch, (m.hold(request("r1", 1).copy(attempt = 1)) as HoldResult.Historical).epoch)
        assertTrue(m.hold(request("r9", 1)) is HoldResult.Historical)
        assertTrue(m.hold(request("r2", 1)) is HoldResult.Historical)

        // An old row that can no longer be read still stands for its request.
        raw { it.execSQL("UPDATE entries SET body = 'x' WHERE epoch = ? AND spend_key = ?", arrayOf<Any?>(first, id("r2") + "#0")) }
        val n = journal()
        assertNull(n.reset)
        assertNull((n.hold(request("r2", 1)) as HoldResult.Historical).entry)

        // An old row that can be tied to no request at all: nothing can be shown to be new, so nothing is held.
        raw { it.execSQL("UPDATE entries SET body = 'x', spend_key = NULL WHERE epoch = ? AND seq = 3", arrayOf<Any?>(first)) }
        val o = journal()
        assertNull(o.reset)
        o.openP("budget:day", day1, Basis.NAMED, zero)
        o.openP("budget:day", day2, Basis.NEXT_PERIOD, zero)
        val line = listOf(Journal.Line("budget:day", day2.id, usd(10)))
        assertEquals(SpendRefusal(SpendRefusal.Code.ALLOWANCE_EXHAUSTED, SpendRefusal.Reason.HISTORY_UNREADABLE), refusal(o.hold(request("brand-new", 1, lines = line))))
        assertTrue("what is still known is still named", o.hold(request("r1", 1, lines = line)) is HoldResult.Historical)
    }

    @Test fun aJournalOfAnotherInstallIsNotWhole() {
        ready().hold(request("r1", 1_000))
        val other = journal(install = "install-z")
        assertEquals("install_changed", other.reset!!.reason)
        assertEquals(Usage.FullyUsed, other.usage("budget:day", day1.id))
    }

    @Test fun aResetPeriodIsRebuiltOnlyByANamedBasisAndThenCounts() {
        ready().hold(request("r1", 4_000))
        keys.forget(Namespace.SANDBOX)
        val j = journal()
        assertEquals("nothing_to_rebuild", assertThrows(JournalRefused::class.java) { j.openP("budget:day", day1, Basis.PROVIDER_READ, usd(4_000)) }.why)
        j.openP("budget:day", day1, Basis.NAMED, zero)
        // A qualifying provider read for every source of the rule gives the count back (the caller proves that it is one).
        j.openP("budget:day", day1, Basis.PROVIDER_READ, usd(4_000))
        assertEquals(4_000L, used(j))
        assertEquals(SpendRefusal.Code.ALLOWANCE_EXHAUSTED, refusal(j.hold(request("r2", 6_001))).code)
        assertTrue(j.hold(request("r2", 6_000)) is HoldResult.Held)
    }

    @Test fun acknowledgementsAreKeptPerEpochAndNeverGoBack() {
        val j = ready()
        val first = j.head().epoch
        j.acknowledge(first, 2)
        j.acknowledge(first, 1)
        assertEquals(2L, j.acknowledged(first))
        assertEquals(0L, j.acknowledged("f".repeat(32)))
        assertNull(j.verify())
        assertEquals(2L, journal().acknowledged(first))
    }

    // ---- namespaces ----

    @Test fun liveAndSandboxNeverShareAFileAChainAnEpochOrATotal() {
        val sandbox = journal(Namespace.SANDBOX)
        val live = journal(Namespace.LIVE)
        assertNotEquals(SpendingDb.fileName(Namespace.LIVE), SpendingDb.fileName(Namespace.SANDBOX))
        assertTrue(File(app.getDatabasePath(SpendingDb.fileName(Namespace.LIVE)).path).exists())
        assertTrue(File(app.getDatabasePath(SpendingDb.fileName(Namespace.SANDBOX)).path).exists())
        assertNotEquals(live.head().epoch, sandbox.head().epoch)

        sandbox.openP("budget:day", day1, Basis.FIRST_PORTION, zero)
        live.openP("budget:day", day1, Basis.FIRST_PORTION, zero)
        assertTrue(sandbox.hold(request("r1", 4_000)) is HoldResult.Held)
        assertEquals(4_000L, used(sandbox))
        assertEquals("sandbox money never meets a live total", 0L, used(live))
        assertNull(live.latest(id("r1")))
        assertEquals(3, live.entries().size)
        assertTrue(sandbox.entries().all { JSONObject(it.json).getString("namespace") == "sandbox" })
        assertTrue(live.entries().all { JSONObject(it.json).getString("namespace") == "live" })

        // The same request has another spend id in each namespace.
        assertNotEquals(id("r1", Namespace.LIVE), id("r1", Namespace.SANDBOX))

        // Losing the sandbox key resets the sandbox journal only.
        keys.forget(Namespace.SANDBOX)
        assertEquals("key_missing", journal(Namespace.SANDBOX).reset!!.reason)
        val liveAgain = journal(Namespace.LIVE)
        assertNull(liveAgain.reset)
        assertEquals(0L, used(liveAgain))
    }

    @Test fun aSpendInAnotherNamespaceThanItsSourceIsRefused() {
        val sandbox = journal(Namespace.SANDBOX).also { it.openP("budget:day", day1, Basis.FIRST_PORTION, zero) }
        val live = journal(Namespace.LIVE).also { it.openP("budget:day", day1, Basis.FIRST_PORTION, zero) }
        val mismatch = SpendRefusal(SpendRefusal.Code.NAMESPACE_MISMATCH)

        // The instrument fixes the namespace: a live instrument cannot be held in the sandbox journal, or the reverse.
        assertEquals(mismatch, refusal(sandbox.hold(request("r1", 1, instrument = "openrouter"))))
        assertEquals(mismatch, refusal(live.hold(request("r1", 1, instrument = "sandbox"))))
        // A spend block that names the other namespace, or something that is not one, is refused; no request chooses it.
        assertEquals(mismatch, refusal(sandbox.hold(request("r1", 1, claimed = "live"))))
        assertEquals(mismatch, refusal(live.hold(request("r1", 1, instrument = "openrouter", claimed = "sandbox"))))
        assertEquals(mismatch, refusal(sandbox.hold(request("r1", 1, claimed = "Sandbox"))))
        assertEquals(0L, used(sandbox))
        assertEquals(0L, used(live))

        assertTrue(sandbox.hold(request("r1", 1, claimed = "sandbox")) is HoldResult.Held)
        assertTrue(sandbox.hold(request("r2", 1)) is HoldResult.Held)
        assertTrue(live.hold(request("r1", 1, instrument = "openrouter", claimed = "live")) is HoldResult.Held)
    }

    // ---- shape ----

    @Test fun everyEntryHasTheFieldsOfTheContractSchema() {
        val j = ready()
        j.hold(request("r1", 4_000).copy(applet = "demo", action = "check", allowance = "al-1", pricesVersion = "p1"))
        j.settle(id("r1"), usd(3_000), providerRef = "ref", qualifying = true, reason = "read")
        j.refund(id("r1"), usd(10))
        j.hold(request("r2", 1))
        j.unknown(id("r2"), "no_answer")
        j.hold(request("r3", 1))
        j.release(id("r3"), "not_billed")

        val file = File("../contracts/client-core/schemas/spend-entry.schema.json").takeIf { it.exists() } ?: File("contracts/client-core/schemas/spend-entry.schema.json")
        val defs = JSONObject(file.readText()).getJSONObject("\$defs")
        val topRequired = JSONObject(file.readText()).getJSONArray("required").let { a -> (0 until a.length()).map(a::getString) }
        for (e in j.entries()) {
            val body = JSONObject(e.json)
            val def = defs.getJSONObject("${e.kind}_entry")
            val allowed = def.getJSONObject("properties").keys().asSequence().toSet()
            val required = def.getJSONArray("required").let { a -> (0 until a.length()).map(a::getString) } + topRequired
            assertEquals("${e.kind} ${e.seq}: fields the schema does not have", emptySet<String>(), body.keys().asSequence().toSet() - allowed)
            assertEquals("${e.kind} ${e.seq}: required fields missing", emptyList<String>(), required.filterNot(body::has))
            assertEquals(Journal.hashOf(e.json), e.hash)
        }
    }
}
