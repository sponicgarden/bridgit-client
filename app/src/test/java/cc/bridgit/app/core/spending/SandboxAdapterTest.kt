package cc.bridgit.app.core.spending

import android.app.Application
import cc.bridgit.app.core.contract.ContractVocab
import cc.bridgit.app.core.spending.InstrumentAdapter.Execution
import cc.bridgit.app.core.spending.InstrumentAdapter.SpendRequest
import cc.bridgit.app.core.spending.Journal.Basis
import cc.bridgit.app.core.spending.Journal.HoldResult
import cc.bridgit.app.core.spending.Journal.Period
import cc.bridgit.app.core.spending.Journal.State
import cc.bridgit.app.core.spending.Journal.Usage
import cc.bridgit.app.core.spending.sandbox.InProcessSandboxHost
import cc.bridgit.app.core.spending.sandbox.SandboxAdapter
import cc.bridgit.app.core.spending.sandbox.SandboxAdapter.Detail
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The sandbox instrument driven through the adapter contract and the journal (spending authority G2.2, G10.1
 * row 1, device-held half): quote, hold, execute, settle, give-back, unknown outcome, reconcile.
 *
 * [spend] is the fixed order of G2.2 with the rule check left out, which is why it is test code: the rule
 * engine is the only thing that may run this order in the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SandboxAdapterTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val clock = FakeClock()
    private val host = InProcessSandboxHost { clock.wall }
    private val key = host.createKey()
    private val day = Period("day:2026-11-11", 1_793_923_200_000, 1_794_009_600_000)
    private val limit = 10_000L
    private lateinit var db: SpendingDb
    private lateinit var journal: Journal

    @Before fun open() {
        for (ns in Namespace.values()) app.deleteDatabase(SpendingDb.fileName(ns))
        db = SpendingDb(app, Namespace.SANDBOX)
        journal = Journal.open(db, SoftwareJournalKeys(), "install-a", clock)
        journal.openP("budget:day", day, Basis.FIRST_PORTION, Money.zero("USD-6"))
    }

    @After fun close() { db.close() }

    private fun usd(atoms: Long) = Money("USD-6", atoms)
    private fun adapter(cap: String = "core_only", timeKey: ByteArray = host.timeKey) =
        SandboxAdapter(host, { if (it == "source-1") key else null }, timeKey, cap, { clock.wall }, quoteLifeMs = 60_000)
    private fun id(request: String) = SpendId.of(Namespace.SANDBOX, "install-a", request)
    private fun used() = (journal.usage("budget:day", day.id) as Usage.Counted).amount.atoms

    /** Whether [read] is a qualifying read for the spend [name]: it must cover the whole time that spend was open. */
    private fun qualifies(read: InstrumentAdapter.Reconciliation, name: String) =
        read.qualifiesFor("source-1", journal.entries().first { it.spendId == id(name) }.wallMs, journal.latest(id(name))!!.wallMs)

    private class Ran(val hold: HoldResult, val execution: Execution?)

    /** Quote, hold, execute, then record what the adapter's answer says. No hold, no call. */
    private fun spend(name: String, cost: Long, detail: Map<String, String> = emptyMap(), a: InstrumentAdapter = adapter(), source: String = "source-1"): Ran {
        val request = SpendRequest(source, usd(cost), detail)
        val quote = a.quote(request)
        val hold = journal.hold(
            Journal.HoldRequest(
                spendId = id(name), instrument = a.describe().instrument, source = source, held = quote.max,
                lines = listOf(Journal.Line("budget:day", day.id, usd(limit))), principalKind = "agent", principalId = "a1",
            ),
        )
        if (hold !is HoldResult.Held) return Ran(hold, null)
        val execution = a.execute(id(name), request, quote)
        when (execution) {
            is Execution.Done -> journal.settle(id(name), a.settle(quote, execution), providerRef = execution.providerRef)
            is Execution.NotBilled -> { assertEquals(quote.max, a.giveBack(quote, execution)); journal.release(id(name), execution.reason) }
            is Execution.Unknown -> { assertNull(a.giveBack(quote, execution)); journal.unknown(id(name), execution.reason) }
        }
        return Ran(hold, execution)
    }

    @Test fun describeSaysWhatTheInstrumentRowSays() {
        val d = adapter(cap = "provider").describe()
        val row = ContractVocab.INSTRUMENTS.single { it.id == "sandbox" }
        assertEquals("sandbox", d.instrument)
        assertEquals("metered", d.instrumentClass)
        assertEquals(Namespace.SANDBOX, d.namespace)
        assertEquals("USD-6", d.unit)
        assertTrue(ContractVocab.InstrumentForm(d.funder, d.custody) in row.forms)
        assertEquals("provider", d.cap)
        assertEquals("provider_signed", d.timeSource)
        assertTrue(d.usageRead.qualifying)
        assertTrue(d.honoursSpendId)
        assertThrows(IllegalArgumentException::class.java) { adapter(cap = "ledger") }
        // No host or time key is named until the hosted piece exists.
        assertNull(row.host)
        assertNull(row.timeKey)
    }

    @Test fun aPlainCallIsHeldRunAndSettledAtItsCost() {
        val ran = spend("r1", 1_000)
        val done = ran.execution as Execution.Done
        assertEquals(usd(1_000), done.cost)
        assertTrue(done.time!!.verified)
        assertEquals(clock.wall, done.time!!.epochMs)
        val e = journal.latest(id("r1"))!!
        assertEquals(State.SETTLED, e.state)
        assertEquals(usd(1_000), e.settled)
        assertEquals(1_000L, used())
        assertEquals(listOf("held", "settled"), journal.entries().filter { it.spendId == id("r1") }.map { it.state!!.id })
        assertEquals(1, host.billedCalls)
        assertNull(journal.verify())
    }

    @Test fun withNoRoomNothingIsHeldAndTheProviderIsNeverCalled() {
        val ran = spend("r1", limit + 1)
        assertEquals(SpendRefusal.Code.ALLOWANCE_EXHAUSTED, (ran.hold as HoldResult.Refused).refusal.code)
        assertNull(ran.execution)
        assertEquals(0, host.billedCalls)
        assertEquals(0L, used())
    }

    @Test fun aLostAnswerKeepsTheHoldUntilReconcileSettlesIt() {
        val a = adapter()
        val ran = spend("r1", 3_000, mapOf(Detail.LOSE_ANSWER to "true"), a)
        assertEquals(Execution.Unknown("no_answer"), ran.execution)
        assertEquals(State.UNKNOWN, journal.latest(id("r1"))!!.state)
        assertEquals(3_000L, used())

        // The provider is asked by spend id; its read is for this key alone, with pending charges, so it qualifies.
        val read = a.reconcile("source-1", listOf(id("r1"), id("never-sent")))
        assertTrue(qualifies(read, "r1"))
        assertEquals("source-1", read.source)
        assertTrue(read.time!!.verified)
        assertEquals(listOf(id("r1")), read.charges.map { it.spendId })
        val charge = read.charges.single()
        journal.settle(id("r1"), charge.cost, providerRef = charge.providerRef, qualifying = qualifies(read, "r1"), reason = "reconcile")
        assertEquals(State.SETTLED, journal.latest(id("r1"))!!.state)
        assertEquals(3_000L, used())
    }

    @Test fun aRetryWithTheSameSpendIdIsNotBilledTwice() {
        val a = adapter()
        spend("r1", 3_000, mapOf(Detail.LOSE_ANSWER to "true"), a)
        // The retry sends the same request with the same id; the journal holds nothing more and the host answers from its record.
        val request = SpendRequest("source-1", usd(3_000))
        val again = a.execute(id("r1"), request, a.quote(request)) as Execution.Done
        assertEquals(usd(3_000), again.cost)
        assertEquals(1, host.billedCalls)
        assertEquals(3_000L, host.usage(key).totalAtoms)
        assertTrue(spend("r1", 3_000, a = a).hold is HoldResult.Existing)
        assertEquals(3_000L, used())
    }

    @Test fun aProviderThatIgnoresTheSpendIdBillsARetryAgainAndReconcileShowsBoth() {
        val a = adapter()
        spend("r1", 1_000, mapOf(Detail.LOSE_ANSWER to "true", Detail.IGNORE_SPEND_ID to "true"), a)
        val request = SpendRequest("source-1", usd(1_000), mapOf(Detail.IGNORE_SPEND_ID to "true"))
        a.execute(id("r1"), request, a.quote(request))
        assertEquals(2, host.billedCalls)
        // What the provider shows for the id is more than the hold; the higher figure wins.
        val total = a.reconcile("source-1", listOf(id("r1"))).charges.map { it.cost }.reduce(Money::plus)
        assertEquals(usd(2_000), total)
        journal.settle(id("r1"), total, reason = "reconcile")
        assertEquals(2_000L, used())
    }

    @Test fun anAccountWideUsageFigureProvesNothingUnspent() {
        val a = adapter()
        spend("r1", 3_000, mapOf(Detail.LOSE_ANSWER to "true"), a)
        host.accountWideUsage = true
        val read = a.reconcile("source-1", listOf(id("r1")))
        assertFalse(qualifies(read, "r1"))
        assertEquals("account", read.coverage!!.scope)
        assertTrue(read.charges.isEmpty())
        // So the unknown hold cannot be given back or lowered on it.
        assertThrows(JournalRefused::class.java) { journal.release(id("r1"), "no_charge_shown", qualifying = qualifies(read, "r1")) }
        assertThrows(JournalRefused::class.java) { journal.settle(id("r1"), usd(0), qualifying = qualifies(read, "r1")) }
        assertEquals(3_000L, used())
    }

    @Test fun todaysEmptyUsageReadDoesNotFreeYesterdaysUnknownHold() {
        val a = adapter()
        spend("r1", 3_000, mapOf(Detail.LOSE_ANSWER to "true"), a)
        assertEquals(3_000L, host.usage(key).totalAtoms)

        // The provider's period rolls over. Its usage read is of the new period only, and shows nothing.
        clock.advance(24L * 60 * 60 * 1000)
        val read = a.reconcile("source-1", listOf(id("r1")))
        assertTrue(read.charges.isEmpty())
        val c = read.coverage!!
        assertEquals("source", c.scope)
        assertTrue(c.complete && c.pendingIncluded)
        assertTrue(c.fromMs <= clock.wall && clock.wall < c.untilMs)

        // For this source, complete, pending included: and still no proof, because it is not the spend's period.
        assertFalse(qualifies(read, "r1"))
        assertThrows(JournalRefused::class.java) { journal.release(id("r1"), "no_charge_shown", qualifying = qualifies(read, "r1")) }
        assertThrows(JournalRefused::class.java) { journal.settle(id("r1"), usd(0), qualifying = qualifies(read, "r1")) }
        assertEquals(State.UNKNOWN, journal.latest(id("r1"))!!.state)
        assertEquals("yesterday's hold is still counted", 3_000L, used())

        // The same read does qualify for a spend that was open inside the period it covers, and only for its own source.
        assertTrue(read.qualifiesFor("source-1", clock.wall - 1_000, clock.wall))
        assertFalse(read.qualifiesFor("source-2", clock.wall - 1_000, clock.wall))
        assertFalse("open since before the period began", read.qualifiesFor("source-1", c.fromMs - 1, clock.wall))
        assertFalse("still open when the period ended", read.qualifiesFor("source-1", clock.wall, c.untilMs))
        assertFalse(read.qualifiesFor("source-1", clock.wall, clock.wall - 1))
        // A provider that could not be read covers nothing.
        assertFalse(adapter().reconcile("source-9", listOf(id("r1"))).qualifiesFor("source-9", clock.wall, clock.wall))
    }

    @Test fun aBillPastTheQuoteSettlesAtTheRealCostAndRefusesEverythingFurther() {
        val ran = spend("r1", 9_000, mapOf(Detail.BILL_PAST_QUOTE_ATOMS to "2500"))
        assertEquals(usd(11_500), (ran.execution as Execution.Done).cost)
        val e = journal.latest(id("r1"))!!
        assertEquals(usd(9_000), e.held)
        assertEquals(usd(11_500), e.settled)
        assertEquals(11_500L, used())
        assertTrue(spend("r2", 1).hold is HoldResult.Refused)
        assertEquals(1, host.billedCalls)
    }

    @Test fun aChargeThatArrivesLateIsFoundByReconcileAndRecorded() {
        val a = adapter()
        val ran = spend("r1", 2_000, mapOf(Detail.CHARGE_LATE to "true"), a)
        assertEquals(usd(0), (ran.execution as Execution.Done).cost)
        assertEquals(0L, used())

        val charge = a.reconcile("source-1", listOf(id("r1"))).charges.single()
        assertTrue(charge.pending)
        assertEquals(usd(2_000), charge.cost)
        // Higher than what was settled: recorded whatever kind of read showed it.
        journal.settle(id("r1"), charge.cost, providerRef = charge.providerRef, reason = "late_charge")
        assertEquals(2_000L, used())
        host.settlePending(key)
        assertFalse(a.reconcile("source-1", listOf(id("r1"))).charges.single().pending)
    }

    @Test fun aCallTheProviderRefusesIsGivenBackWhole() {
        host.limit(key, 1_500)
        val a = adapter(cap = "provider")
        assertEquals(usd(1_500), a.available("source-1"))
        spend("r1", 1_000, a = a)
        assertEquals(usd(500), a.available("source-1"))
        val ran = spend("r2", 600, a = a)
        assertEquals("limit_reached", (ran.execution as Execution.NotBilled).reason)
        assertEquals(State.RELEASED, journal.latest(id("r2"))!!.state)
        assertEquals(1_000L, used())
        assertEquals(1, host.billedCalls)
        assertNull("no cap, no figure", adapter().also { host.limit(key, null) }.available("source-1"))
    }

    @Test fun aSettlementBelowTheHoldGivesBackOnlyTheDifference() {
        val a = adapter()
        val request = SpendRequest("source-1", usd(1_000))
        val quote = a.quote(request)
        assertEquals(usd(400), a.giveBack(quote, Execution.Done(usd(600), "ref", null)))
        assertNull(a.giveBack(quote, Execution.Done(usd(1_000), "ref", null)))
        assertNull(a.giveBack(quote, Execution.Done(usd(1_400), "ref", null)))
        assertEquals("no figure from the provider settles at the hold", usd(1_000), a.settle(quote, Execution.Done(null, null, null)))
        assertNull(a.giveBack(quote, Execution.Unknown("no_answer")))
    }

    @Test fun anUnlinkedSourceOrAnExpiredQuoteNeverReachesTheProvider() {
        val a = adapter()
        val ran = spend("r1", 1_000, a = a, source = "source-9")
        assertEquals("source_not_linked", (ran.execution as Execution.NotBilled).reason)
        assertEquals(State.RELEASED, journal.latest(id("r1"))!!.state)

        val request = SpendRequest("source-1", usd(1_000))
        val quote = a.quote(request)
        clock.advance(60_000)
        assertEquals("quote_expired", (a.execute(id("r2"), request, quote) as Execution.NotBilled).reason)
        assertEquals(0, host.billedCalls)
        assertThrows(MoneyException::class.java) { a.quote(SpendRequest("source-1", usd(-1))) }
    }

    @Test fun theHostTimeFollowsItsClockAndIsOnlyTrustedUnderItsKey() {
        host.clock(key, 24L * 60 * 60 * 1000)
        val ahead = (spend("r1", 1).execution as Execution.Done).time!!
        assertTrue(ahead.verified)
        assertEquals(clock.wall + 24L * 60 * 60 * 1000, ahead.epochMs)

        host.clock(key, 0)
        val slow = (spend("r2", 1, mapOf(Detail.DELAY_MS to "5000")).execution as Execution.Done).time!!
        assertEquals(clock.wall + 5_000, slow.epochMs)

        val otherKey = InProcessSandboxHost { 0L }.timeKey
        val untrusted = (spend("r3", 1, a = adapter(timeKey = otherKey)).execution as Execution.Done).time!!
        assertFalse(untrusted.verified)
    }
}
