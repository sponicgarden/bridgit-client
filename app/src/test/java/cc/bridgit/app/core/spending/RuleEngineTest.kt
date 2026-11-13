package cc.bridgit.app.core.spending

import android.app.Application
import cc.bridgit.app.core.spending.Journal.HoldResult
import cc.bridgit.app.core.spending.SpendRefusal.Code
import cc.bridgit.app.core.spending.SpendRefusal.Reason
import cc.bridgit.app.core.spending.SpendingWorld.Companion.DAY
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The rules of G6 in order, the hold in the same transaction, and the time-anchor fixtures of G5.2 (A0300 SA2.3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RuleEngineTest {
    private val world = SpendingWorld(RuntimeEnvironment.getApplication())
    private val a = world.device("install-a")

    @After fun close() = world.close()

    private fun day(dollars: Long = 10, extra: Map<String, Map<String, Any?>> = emptyMap(), edit: (Map<String, Any?>) -> Map<String, Any?> = { it }) =
        world.found(a, mapOf("day" to RuleDocument.budget("account", "acct-1", "day", "USD-6", world.usd(dollars), mapOf("install-a" to world.usd(dollars)))) + extra, edit = edit)

    // ---- rule order ----

    @Test fun rulesRunInOrderAndTheFirstFailureDecides_deviceHeld() {
        // Everything is wrong at once: paused, the agent may not act, the source is not linked, the spend is over
        // its cap, over the budget and over the rate limit. Each fix shows the next rule.
        day(10) { it + ("caps" to mapOf("instruments" to mapOf("sandbox" to mapOf("unit" to "USD-6", "atoms" to world.usd(4))), "sources" to emptyMap<String, Any?>())) }
        assertTrue(a.held(world.usd(1)))
        val source = a.env.sources.remove("key-1")!!
        a.env.paused = true; a.env.mayAct = false; a.env.callsPerHour = 1
        fun rule(atoms: Long) = a.refusal(atoms)

        assertEquals(SpendRefusal(null, Reason.PAUSED, 1), rule(world.usd(20)))
        a.env.paused = false
        assertEquals(SpendRefusal(null, Reason.MAY_NOT_ACT, 2), rule(world.usd(20)))
        a.env.mayAct = true
        assertEquals(SpendRefusal(null, Reason.SOURCE_NOT_LINKED, 3), rule(world.usd(20)))
        a.env.sources["key-1"] = source
        a.env.routeAllowed = false
        assertEquals(SpendRefusal(null, Reason.ROUTE_NOT_ALLOWED, 3), rule(world.usd(20)))
        a.env.routeAllowed = true
        assertEquals(SpendRefusal(null, Reason.PER_SPEND_CAP, 4), rule(world.usd(20)))
        // Under the cap but over what is left of the budget: rule 6, with resets_at at the end of the day.
        world.change(a) { it + ("caps" to mapOf("instruments" to emptyMap<String, Any?>(), "sources" to emptyMap<String, Any?>())) }
        val r6 = rule(world.usd(9) + 1)
        assertEquals(6, r6.rule); assertEquals(Reason.BUDGET_REACHED, r6.reason); assertNull(r6.code)
        assertEquals(PeriodClock.at("day", "UTC", world.start).endsMs, r6.resetsAt)
        // Inside the budget: rule 7, one call an hour and one was made.
        assertEquals(SpendRefusal(null, Reason.RATE_LIMITED, 7), rule(world.usd(1)))
        world.advance(60 * 60 * 1000L)
        assertTrue(a.held(world.usd(1)))
        a.env.callsPerHour = null
        assertTrue(a.held(world.usd(8)))
        assertEquals(world.usd(10), a.used("day"))
    }

    @Test fun switchesInTheRuleDocumentAreRuleOneToo() {
        day()
        world.change(a) { it + ("switches" to mapOf("paused" to false, "classes" to mapOf("metered" to false), "sources" to emptyMap<String, Any?>())) }
        assertEquals(SpendRefusal(null, Reason.SWITCHED_OFF, 1), a.refusal(1))
        world.change(a) { it + ("switches" to mapOf("paused" to false, "classes" to emptyMap<String, Any?>(), "sources" to mapOf("key-1" to false))) }
        assertEquals(SpendRefusal(null, Reason.SWITCHED_OFF, 1), a.refusal(1))
        world.change(a) { it + ("switches" to mapOf("paused" to true, "classes" to emptyMap<String, Any?>(), "sources" to emptyMap<String, Any?>())) }
        assertEquals(SpendRefusal(null, Reason.PAUSED, 1), a.refusal(1))
    }

    @Test fun aBridgitFundedSpendMeetsRuleFiveAndIsThenTheCloudsToHold() {
        day()
        a.env.paused = true
        assertEquals(1, a.refusal(1, "cloud-1").rule)
        a.env.paused = false
        a.env.bridgitRoom = RuleEngine.Room(Money("USD-6", 5), world.start + DAY)
        assertEquals(SpendRefusal(null, Reason.BRIDGIT_CAP, 5, world.start + DAY), a.refusal(6, "cloud-1"))
        // Within Bridgit's cap the hold is the cloud's, and that path is not built: closed, and not a budget refusal.
        assertEquals(SpendRefusal(Code.CLOUD_UNREACHABLE, Reason.NOT_BUILT), a.refusal(5, "cloud-1"))
        // The mirror never touches an owner-funded source.
        assertTrue(a.held(world.usd(1)))
    }

    @Test fun aValueTransferNeedsPaymentsOnAndItsGrantAndIsThenRefusedBecauseRulesNineAndTenAreNotBuilt() {
        day()
        a.env.sources["wallet"] = RuleEngine.Source("wallet", "sandbox", "stablecoin", "owner", "approver", "provider", valueTransfer = true)
        a.env.paymentsOn = false
        assertEquals(SpendRefusal(null, Reason.PAYMENTS_OFF, 1), a.refusal(1, "wallet"))
        a.env.paymentsOn = true
        assertEquals(SpendRefusal(null, Reason.PAYMENTS_REQUEST_REQUIRED, 2), a.refusal(1, "wallet"))
        a.env.grants = setOf("payments.request")
        // The budget is still a hard limit for it (rule 6), and inside the budget it stops at rule 9.
        assertEquals(6, a.refusal(world.usd(11), "wallet").rule)
        val seq = a.journal.head().seq
        assertEquals(SpendRefusal(null, Reason.NOT_BUILT, 9), a.refusal(1, "wallet"))
        assertEquals("the periods opened; nothing was held", 0L, a.used("day"))
        assertTrue(a.journal.entries(afterSeq = seq).none { it.kind == "spend" })
    }

    @Test fun offlineAndAnotherNamespaceAreRefusedBeforeAnyRule() {
        day()
        a.env.online = false; a.env.paused = true
        assertEquals(SpendRefusal(Code.OFFLINE), a.refusal(1))
        a.env.online = true; a.env.paused = false
        val r = a.core.engine.spend(RuleEngine.Request(SpendId.of(Namespace.SANDBOX, "install-a", "x"), "key-1", Money("USD-6", 1), "agent", "a1", claimedNamespace = "live"))
        assertEquals(SpendRefusal(Code.NAMESPACE_MISMATCH), (r as HoldResult.Refused).refusal)
    }

    @Test fun aSpendCountsAgainstEveryBudgetItFallsInAndNoBudgetMeansNoAllowance() {
        // No rule document at all: nothing gives this device a share.
        assertEquals(SpendRefusal(Code.ALLOWANCE_EXHAUSTED, Reason.NO_ALLOWANCE, 8), a.refusal(1))
        day(10, mapOf("applet:demo" to RuleDocument.budget("applet", "demo", "day", "USD-6", world.usd(3), mapOf("install-a" to world.usd(3)))))
        assertTrue(a.held(world.usd(2), applet = "demo"))
        assertEquals(world.usd(2), a.used("day")); assertEquals(world.usd(2), a.used("applet:demo"))
        // The applet's budget has 1 left; the account's has 8.
        assertEquals(6, a.refusal(world.usd(2), applet = "demo").rule)
        assertTrue(a.held(world.usd(2)))
        assertEquals(world.usd(4), a.used("day")); assertEquals(world.usd(2), a.used("applet:demo"))
        // A budget that covers the spend and names no share for this device gives it nothing.
        world.device("install-b")
        world.change(a) { it + ("budgets" to (it.map("budgets")!! + ("agent:a1" to RuleDocument.budget("agent", "a1", "day", "USD-6", world.usd(5), mapOf("install-b" to world.usd(5)))))) }
        assertEquals(SpendRefusal(Code.ALLOWANCE_EXHAUSTED, Reason.PERIOD_NOT_OPEN, 8), a.refusal(1))
    }

    @Test fun theChecksThePeriodAndTheHoldAreOneTransaction() {
        day()
        val before = a.journal.head().seq
        a.journal.afterEntryWrite = { throw IllegalStateException("power cut") }
        assertThrows(IllegalStateException::class.java) { a.spend(1) }
        a.journal.afterEntryWrite = null
        assertEquals("the period entry went with the hold that failed", before, a.journal.head().seq)
        assertNull(a.journal.verify())
        // The same request again holds once; asked a third time it is the same hold, whatever the rules say now.
        val held = a.spend(world.usd(1), request = "same") as HoldResult.Held
        assertEquals(listOf("period", "allowance", "spend"), a.journal.entries(afterSeq = before).map { it.kind })
        assertEquals("the hold names the allowance it was made under", a.journal.entries().first { it.kind == "allowance" }.tree.map("allowance")!!.str("id"), held.entry.tree.str("allowance"))
        a.env.paused = true
        assertEquals(held.entry.seq, (a.spend(world.usd(1), request = "same") as HoldResult.Existing).entry.seq)
        assertEquals(1, a.refusal(1).rule)
    }

    // ---- history_unreadable and its exit ----

    @Test fun unreadableHistoryStopsEveryHoldUntilItIsAcknowledgedAndThenANewPeriodOpens() {
        day()
        assertTrue(a.held(world.usd(4)))
        val firstEpoch = a.journal.head().epoch
        assertEquals("nothing_to_acknowledge", assertThrows(JournalRefused::class.java) { a.journal.acknowledgeHistory("agent", "a1") }.why)
        // The journal is reset with its rows kept, and one old row can no longer be tied to any request.
        a.raw { it.execSQL("DELETE FROM head"); it.execSQL("UPDATE entries SET body = 'x', spend_key = NULL WHERE epoch = ? AND spend_key IS NOT NULL", arrayOf<Any?>(firstEpoch)) }
        assertEquals("head_missing", a.journal.reset!!.reason)
        assertTrue(a.journal.historyBlocked())
        assertEquals("the rule document came back from the device's own rows", 1L, a.core.document()!!.generation)

        // A new period opens under the time rules, and still nothing is held: the block is not about the amount.
        assertEquals(TimeAnchor.Result.Accepted, a.anchor())
        assertEquals(Reason.HISTORY_UNREADABLE, a.refusal(1).reason)
        world.advance(DAY)
        assertEquals(SpendRefusal(Code.ALLOWANCE_EXHAUSTED, Reason.HISTORY_UNREADABLE, 8), a.refusal(1))
        assertEquals("the new day did open", 0L, a.used("day"))

        // The agent takes it on for the owner. No person is needed, and nothing is given back.
        val ack = a.journal.acknowledgeHistory("agent", "a1")!!
        assertEquals("history_ack", ack.kind)
        assertNull("a second acknowledgement of the same rows writes nothing", a.journal.acknowledgeHistory("agent", "a1"))
        assertThrows(IllegalArgumentException::class.java) { a.journal.acknowledgeHistory("applet", "demo") }
        assertFalse(a.journal.historyBlocked())
        assertTrue(a.held(world.usd(1)))
        assertNull(a.journal.verify())
        a.restartApp()
        assertNull(a.journal.reset)
        assertFalse("the acknowledgement is part of the verified head", a.journal.historyBlocked())

        // Later damage is not covered by the old acknowledgement.
        val second = a.journal.head().epoch
        a.raw { it.execSQL("DELETE FROM head"); it.execSQL("UPDATE entries SET body = 'y', spend_key = NULL WHERE epoch = ? AND spend_key IS NOT NULL", arrayOf<Any?>(second)) }
        assertTrue(a.journal.historyBlocked())
    }

    @Test fun anAcknowledgementLeavesThePeriodOfTheResetChargedInFull() {
        day()
        assertTrue(a.held(world.usd(4)))
        val firstEpoch = a.journal.head().epoch
        a.raw { it.execSQL("DELETE FROM head"); it.execSQL("UPDATE entries SET body = 'x', spend_key = NULL WHERE epoch = ? AND spend_key IS NOT NULL", arrayOf<Any?>(firstEpoch)) }
        a.anchor()
        a.refusal(1)
        // The period's count is rebuilt from a provider read, as G5.3 rule 3 allows...
        val today = PeriodClock.at("day", "UTC", world.start)
        a.journal.openPeriod("day", today, Journal.Basis.PROVIDER_READ, world.money(4), listOf(mapOf("source" to "key-1", "scope" to "source")))
        assertEquals(Journal.Usage.Counted(world.money(4)), a.journal.usage("day", today.id))
        // ...but a read cannot speak for a request nobody can name. Acknowledging puts the period back to fully used,
        a.journal.acknowledgeHistory("owner", "owner")
        assertEquals(Journal.Usage.FullyUsed, a.journal.usage("day", today.id))
        assertEquals(Reason.JOURNAL_UNVERIFIED, a.refusal(1).reason)
        // no further rebuild is accepted in this epoch,
        assertEquals("nothing_to_rebuild", assertThrows(JournalRefused::class.java) { a.journal.openPeriod("day", PeriodClock.at("day", "UTC", world.start + DAY), Journal.Basis.PROVIDER_READ, world.money(0), listOf(mapOf("source" to "key-1"))) }.why)
        assertEquals("history_acknowledged", assertThrows(JournalRefused::class.java) { a.journal.openPeriod("day", today, Journal.Basis.PROVIDER_READ, world.money(0), listOf(mapOf("source" to "key-1"))) }.why)
        // and spending resumes in the next period.
        world.advance(DAY)
        assertTrue(a.held(world.usd(10)))
    }

    // ---- periods and time anchors (G5.2 fixtures) ----

    private fun usedUp() { day(); assertTrue(a.held(world.usd(10))); assertEquals(6, a.refusal(1).rule) }

    @Test fun anAppRestartWithNoRebootNeedsNoAnchor() {
        usedUp()
        a.anchor()
        a.restartApp()
        assertEquals("the old count stands while the day lasts", 6, a.refusal(1).rule)
        world.advance(DAY)
        a.restartApp()
        assertTrue("the monotonic clock ran on and the boot count is the same", a.held(world.usd(1)))
        assertEquals(PeriodClock.at("day", "UTC", world.start + DAY).id, a.standing("day").period)
    }

    @Test fun theWallClockAloneStartsNothing() {
        usedUp()
        // No anchor was ever taken: the first period was opened by the wall clock. Setting the clock a day ahead opens nothing.
        a.clock.wall += DAY
        assertEquals(6, a.refusal(1).rule)
        // Neither does most of a day of running time; a full period of it does.
        a.clock.wall -= DAY
        world.advance(DAY - 1)
        assertEquals(8, a.refusal(1).rule)
        world.advance(1)
        assertTrue(a.held(world.usd(1)))
    }

    @Test fun aUsedUpShareAndARebootWithTheClockADayAhead() {
        usedUp()
        a.reboot(wallJump = DAY)
        // No anchor: the old period's count stands, and the refusal says why.
        assertEquals(SpendRefusal(Code.ALLOWANCE_EXHAUSTED, Reason.TIME_UNANCHORED, 8), a.refusal(1))
        // The provider's true time is before the boundary and the wall clock contradicts it: it starts nothing.
        assertEquals(TimeAnchor.Result.Ignored(TimeAnchor.Why.CLOCK_DISAGREES), a.anchor(world.start + 60_000))
        assertEquals(Reason.TIME_UNANCHORED, a.refusal(1).reason)
        // The clock is put right. An anchor before the boundary: the count stands, now as a budget refusal with its reset time.
        a.clock.wall -= DAY
        assertEquals(TimeAnchor.Result.Accepted, a.anchor())
        val r = a.refusal(1)
        assertEquals(6, r.rule); assertEquals(PeriodClock.at("day", "UTC", world.start).endsMs, r.resetsAt)
    }

    @Test fun anAnchorAfterTheBoundaryOpensTheNextPeriodAndAnOlderAnchorIsIgnored() {
        usedUp()
        a.reboot(wallJump = DAY)
        assertEquals(Reason.TIME_UNANCHORED, a.refusal(1).reason)
        assertEquals(TimeAnchor.Result.Accepted, a.anchor())
        assertTrue(a.held(world.usd(10)))
        // An anchor older than the last one changes nothing.
        assertEquals(TimeAnchor.Result.Ignored(TimeAnchor.Why.OLDER_THAN_STORED), a.anchor(a.clock.wall - 1))
        // A reboot and an anchor inside the day already open: the count stands.
        a.reboot()
        a.clock.advance(60_000)
        assertEquals(TimeAnchor.Result.Accepted, a.anchor())
        assertEquals(6, a.refusal(1).rule)
        assertEquals(world.usd(10), a.used("day"))
    }

    @Test fun theProvidersTimeADayAheadWithTheDeviceClockRightStartsNothing() {
        usedUp()
        a.reboot()
        assertEquals(TimeAnchor.Result.Ignored(TimeAnchor.Why.CLOCK_DISAGREES), a.anchor(a.clock.wall + DAY))
        assertEquals(TimeAnchor.Result.Ignored(TimeAnchor.Why.CLOCK_DISAGREES), a.anchor(a.clock.wall + SpendingLimits.ANCHOR_SKEW_MS + 1))
        assertEquals(Reason.TIME_UNANCHORED, a.refusal(1).reason)
        assertEquals("inside the allowed skew it counts", TimeAnchor.Result.Accepted, a.anchor(a.clock.wall + SpendingLimits.ANCHOR_SKEW_MS))
    }

    @Test fun providerAndClockBothADayAheadCostOnePeriodAndThenNothingUntilTrueTimeHasPassedIt() {
        usedUp()
        // Both wrong the same way: one extra period opens (the stated remainder).
        a.reboot(wallJump = DAY)
        assertEquals(TimeAnchor.Result.Accepted, a.anchor())
        assertTrue(a.held(world.usd(10)))
        val opened = a.standing("day").period
        // A second reboot, both still a day ahead: no second period.
        a.reboot()
        a.clock.advance(60_000)
        assertEquals(TimeAnchor.Result.Accepted, a.anchor())
        assertEquals(6, a.refusal(1).rule)
        // A reboot at the true time: the stored future anchor opens nothing, and the true time is earlier than it, so it changes nothing.
        a.reboot(wallJump = -DAY)
        assertEquals(TimeAnchor.Result.Ignored(TimeAnchor.Why.OLDER_THAN_STORED), a.anchor())
        assertEquals(Reason.TIME_UNANCHORED, a.refusal(1).reason)
        assertEquals(opened, a.standing("day").period)
        // True time passes the stored anchor, inside the day that was opened early: still that day's count.
        a.clock.advance(DAY)
        assertEquals(TimeAnchor.Result.Accepted, a.anchor())
        assertEquals(6, a.refusal(1).rule)
        // Only when true time passes the end of that day does the next one open.
        a.clock.advance(DAY)
        assertTrue(a.held(world.usd(1)))
    }

    @Test fun onlyAllowedTimeSourcesAnchor() {
        usedUp()
        a.reboot(wallJump = DAY)
        fun ignored(why: TimeAnchor.Why, r: TimeAnchor.Result) = assertEquals(TimeAnchor.Result.Ignored(why), r)
        // A date on a header-path answer is the Worker's, so it is the cloud's word.
        a.env.sources["via-cloud"] = RuleEngine.Source("via-cloud", "sandbox", "metered", "owner", "device_via_cloud", "core_only")
        ignored(TimeAnchor.Why.SOURCE_NOT_ALLOWED, a.anchor(timeSource = "provider_date", source = "via-cloud"))
        // An HTTP date offered for a threshold source, and the cloud's time for one.
        a.env.sources["wallet"] = RuleEngine.Source("wallet", "sandbox", "stablecoin", "owner", "approver", "provider", valueTransfer = true)
        ignored(TimeAnchor.Why.SOURCE_NOT_ALLOWED, a.anchor(timeSource = "provider_date", source = "wallet"))
        ignored(TimeAnchor.Why.SOURCE_NOT_ALLOWED, a.anchor(timeSource = "cloud_signed", source = "wallet"))
        // The cloud's signed time for a metered source: there is no production key, so it is refused in this build.
        ignored(TimeAnchor.Why.SOURCE_NOT_ALLOWED, a.anchor(timeSource = "cloud_signed"))
        ignored(TimeAnchor.Why.SOURCE_NOT_ALLOWED, a.anchor(timeSource = "none"))
        // A time the adapter could not verify.
        ignored(TimeAnchor.Why.NOT_VERIFIED, a.anchor(verified = false))
        assertEquals(Reason.TIME_UNANCHORED, a.refusal(1).reason)
        // The provider's own date on the direct path, a chain's block time for a threshold source, a signed provider time.
        assertTrue(TimeAnchor.allowed(valueTransfer = false, custody = "device", timeSource = "provider_date"))
        assertTrue(TimeAnchor.allowed(valueTransfer = true, custody = "approver", timeSource = "chain_block"))
        assertFalse(TimeAnchor.allowed(valueTransfer = false, custody = "device", timeSource = "chain_block"))
        assertFalse(TimeAnchor.allowed(valueTransfer = false, custody = "device", timeSource = "cloud_signed"))
        assertTrue("with a key in place, which only a test can supply", TimeAnchor.allowed(valueTransfer = false, custody = "device", timeSource = "cloud_signed", cloudKey = ByteArray(32)))
        assertEquals(TimeAnchor.Result.Accepted, a.anchor(timeSource = "provider_date"))
        assertTrue(a.held(world.usd(1)))
    }

    @Test fun afterTheJournalIsReplacedTheFirstProviderTimeOpensNothingUntilTheNextPeriod() {
        usedUp()
        val doc = a.core.document()!!
        a.loseJournalFile()
        assertEquals(Journal.Origin.RESET, a.journal.head().origin)
        a.core.adopt(doc.json)
        assertEquals(SpendRefusal(Code.ALLOWANCE_EXHAUSTED, Reason.JOURNAL_UNVERIFIED, 8), a.refusal(1))
        // The first anchor only names the current period, which stays fully used.
        assertEquals(TimeAnchor.Result.Accepted, a.anchor())
        assertEquals(Reason.JOURNAL_UNVERIFIED, a.refusal(1).reason)
        assertEquals(PeriodClock.at("day", "UTC", world.start).id, a.standing("day").period)
        assertNull(a.used("day"))
        // The first period that opens is the one after it. The device is the only executor, so the count reopens.
        world.advance(DAY)
        assertTrue(a.held(world.usd(10)))
    }

    @Test fun periodsThatPassedWhileTheDeviceWasOffGiveNothing() {
        usedUp()
        a.reboot(wallJump = 5 * DAY)
        a.anchor()
        // One period opens, the one the anchor falls in; the four that passed are not opened.
        assertTrue(a.held(world.usd(10)))
        assertEquals(6, a.refusal(1).rule)
        assertEquals(2, a.journal.entries().count { it.kind == "period" })
    }

    @Test fun periodIdsFollowTheDocumentsFixedZone() {
        val t = world.start // 2026-11-11T10:00Z, a Wednesday
        assertEquals("day:2026-11-11", PeriodClock.at("day", "UTC", t).id)
        assertEquals("hour:2026-11-11T10Z", PeriodClock.at("hour", "UTC", t).id)
        assertEquals("week:2026-11-09", PeriodClock.at("week", "UTC", t).id)
        assertEquals("month:2026-11", PeriodClock.at("month", "UTC", t).id)
        // 10:00 UTC is 23:00 in Auckland (UTC+13 in November): still the 11th there; three hours later it is the 12th.
        assertEquals("day:2026-11-11", PeriodClock.at("day", "Pacific/Auckland", t).id)
        assertEquals("day:2026-11-12", PeriodClock.at("day", "Pacific/Auckland", t + 3 * 60 * 60 * 1000).id)
        val month = PeriodClock.at("month", "Europe/Warsaw", t)
        assertEquals(month.endsMs, PeriodClock.next("month", "Europe/Warsaw", month).startsMs)
        assertEquals("month:2026-12", PeriodClock.next("month", "Europe/Warsaw", month).id)
        // A day with a clock change is one period, 25 hours long (Warsaw leaves summer time on 25 October 2026).
        val long = PeriodClock.at("day", "Europe/Warsaw", java.time.Instant.parse("2026-10-25T12:00:00Z").toEpochMilli())
        assertEquals(25L * 60 * 60 * 1000, long.endsMs - long.startsMs)
        assertTrue("ids of one kind sort in time order", "day:2026-11-09" < "day:2026-11-11" && "hour:2026-11-11T09Z" < "hour:2026-11-11T10Z")
    }
}
