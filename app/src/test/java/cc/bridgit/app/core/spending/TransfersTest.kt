package cc.bridgit.app.core.spending

import android.app.Application
import cc.bridgit.app.core.spending.SpendRefusal.Code
import cc.bridgit.app.core.spending.SpendRefusal.Reason
import cc.bridgit.app.core.spending.SpendingWorld.Companion.DAY
import cc.bridgit.app.core.spending.Transfers.Took
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Moves of budget room between three installs (spending authority G5.1, revision 5.3): the device-only
 * fixtures, with the concurrent-recipients (SA-R1), concurrent-reclamation (SA-R5) and reset-headroom
 * (SA-R6) ones. A $100 daily budget made by A: A 60, B 40, C 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TransfersTest {
    private val world = SpendingWorld(RuntimeEnvironment.getApplication())
    private val a = world.device("install-a")
    private val b = world.device("install-b")
    private val c = world.device("install-c")
    private val agent = world.agent

    @After fun close() = world.close()

    private fun budget(ceiling: Long? = null, headroom: Map<String, Long> = emptyMap(), shares: Map<String, Long> = mapOf("install-a" to 60L, "install-b" to 40L, "install-c" to 0L)) = world.found(
        a, mapOf("day" to RuleDocument.budget("account", "acct-1", "day", "USD-6", world.usd(100), shares.mapValues { world.usd(it.value) }, ceiling = ceiling?.let(world::usd), headroom = headroom.mapValues { world.usd(it.value) })),
    )

    private fun give(from: SpendingWorld.Device, to: SpendingWorld.Device, dollars: Long, kind: String = "portion", lasts: String = "standing", capped: Boolean = true, floor: Long = 0) =
        from.core.transfers.give("day", to.name, kind, if (kind == "reclaim") null else world.money(dollars), lasts, capped, agent, true, floor)

    private fun signed(g: Transfers.Given) = (g as Transfers.Given.Signed).json

    /** [by] answers [about]'s recovery of its record for the budget with its signed list. */
    private fun list(by: SpendingWorld.Device, about: SpendingWorld.Device): String =
        about.core.transfers.recover("day").let { by.core.transfers.holdings("day", about.name, it.epoch, it.challenge) }
    private fun why(g: Transfers.Given) = (g as Transfers.Given.Refused).why
    private fun today() = PeriodClock.at("day", "UTC", world.start).id
    private fun tomorrow() = PeriodClock.at("day", "UTC", world.start + DAY).id

    /** A transfer signed with [from]'s key but never debited anywhere: what a taken-over device can produce. */
    private fun forged(from: SpendingWorld.Device, to: String, dollars: Long, counter: Long, epoch: String, prev: String? = null, kind: String = "portion", budget: String = "day"): String {
        val doc = from.core.document()!!
        val body = LinkedHashMap<String, Any?>()
        body["v"] = "transfer/1"; body["id"] = Signed.id(Signed.TRANSFER_ID, from.name, "sandbox", epoch, counter.toString())
        body["counter"] = counter; body["prev"] = prev; body["namespace"] = "sandbox"; body["budget"] = budget
        body["parent"] = mapOf("generation" to doc.generation, "digest" to doc.digest); body["kind"] = kind
        if (kind != "reclaim") body["amount"] = mapOf("unit" to "USD-6", "atoms" to world.usd(dollars))
        body["from"] = from.name; body["to"] = to; body["effective"] = mapOf("from_period" to today(), "lasts" to "standing")
        body["journal"] = mapOf("epoch" to epoch, "seq" to 99L)
        body["actor"] = ActorRecord.over(Signed.TRANSFER, body, "agent", "a1", "http", from.name).toMap()
        return Canonical.encode(body + ("signature" to Signed.encode(world.signAs(from.name, Signed.bytes(Signed.TRANSFER, body)))))
    }

    // ---- one sender, one recipient, taken once ----

    @Test fun aSurrenderIsDebitedFirstThenTakenOnceByTheRecipientItNames() {
        budget()
        assertEquals(world.usd(60), a.portion("day")); assertEquals(world.usd(40), b.portion("day")); assertEquals(0L, c.portion("day"))
        assertEquals(Reason.BUDGET_REACHED, c.refusal(1).reason)
        val t = signed(give(a, c, 20))
        // From the sender's debit on, the room is the recipient's, taken or not: A cannot spend it.
        assertEquals(world.usd(40), a.portion("day"))
        assertEquals(6, a.refusal(world.usd(40) + 1).rule)
        assertEquals(0L, c.portion("day"))

        assertEquals(Took.TAKEN, c.core.transfers.take(t).result)
        assertEquals(world.usd(20), c.portion("day"))
        assertTrue(c.held(world.usd(20)))
        // A copy that comes back changes nothing.
        assertEquals(Took.ALREADY_TAKEN, c.core.transfers.take(t).result)
        assertEquals(world.usd(20), c.portion("day"))
        // Portions still add up to the budget.
        assertEquals(world.usd(100), a.portion("day") + b.portion("day") + c.portion("day"))
        // It is standing: it holds in the next period too.
        world.advance(DAY)
        assertEquals(world.usd(40), a.portion("day")); assertEquals(world.usd(20), c.portion("day"))
        for (d in listOf(a, b, c)) { d.restartApp(); assertNull(d.journal.reset) }
        assertEquals(world.usd(20), c.portion("day"))
    }

    @Test fun aTransferPresentedByAnotherExecutorGivesItNothing() {
        budget()
        val toB = signed(give(a, b, 10))
        // A's transfer to B presented by C.
        val r = c.core.transfers.take(toB)
        assertEquals(Took.NOT_FOR_RECIPIENT, r.result); assertEquals(Code.TRANSFER_NOT_FOR_RECIPIENT.id, r.why)
        assertEquals(0L, c.portion("day"))
        assertTrue("presenting one is a security event", c.events.any { it.name == SpendEvent.SECURITY && it.detail["what"] == "transfer_not_for_recipient" })
        // C may keep it as a link of A's chain; that gives it nothing either.
        assertEquals(Took.SEEN, c.core.transfers.observe(toB).result)
        assertEquals(0L, c.portion("day"))
        assertEquals(Took.TAKEN, b.core.transfers.take(toB).result)
        assertEquals(world.usd(50), b.portion("day"))
    }

    @Test fun bAndCApartEachHandedTheOneTransferAMadeToB_inTwoSuccessorsOfTheSameParent() {
        budget()
        val toB = signed(give(a, b, 60))
        // B and C are apart and each makes a valid successor of the same parent (5.2 let each cite the surrender in its own).
        world.unlink("install-b", "install-c")
        val byB = world.change(b, agent) { SpendingWorld.editBudget(it, "day") { x -> x + ("rebalance" to false) } } as RuleDocuments.Adopt.Adopted
        val byC = world.change(c, agent) { it + ("zone" to "Europe/Warsaw") } as RuleDocuments.Adopt.Adopted
        assertNotEquals(byB.document.digest, byC.document.digest)
        // Whatever document it holds, only the recipient under the sender's signature takes the room.
        assertEquals(Took.TAKEN, b.core.transfers.take(toB).result)
        assertEquals(Took.NOT_FOR_RECIPIENT, c.core.transfers.take(toB).result)
        assertEquals(world.usd(100), b.portion("day")); assertEquals(0L, c.portion("day")); assertEquals(0L, a.portion("day"))
        // No document can do it either: a successor that restates a portion upward is invalid everywhere.
        val up = world.change(c, agent) { SpendingWorld.editBudget(it, "day") { x -> x + ("devices" to (x.map("devices")!! + ("install-a" to mapOf("atoms" to 0L, "kind" to "standing")) + ("install-c" to mapOf("atoms" to world.usd(60), "kind" to "standing")))) } }
        assertEquals("portion_restated_up:day", (up as RuleDocuments.Adopt.Refused).why)
    }

    @Test fun theSameHoldsForAHeadroomTransfer() {
        budget(ceiling = 150, headroom = mapOf("install-a" to 50L))
        val toB = signed(give(a, b, 50, kind = "headroom"))
        assertEquals(0L, a.headroom("day"))
        assertEquals(Took.NOT_FOR_RECIPIENT, c.core.transfers.take(toB).result)
        assertEquals(0L, c.headroom("day"))
        assertEquals(Took.TAKEN, b.core.transfers.take(toB).result)
        assertEquals(Took.ALREADY_TAKEN, b.core.transfers.take(toB).result)
        assertEquals(world.usd(50), b.headroom("day"))
        // More headroom than the balance, and headroom where there is no ceiling, are refused at the sender.
        assertEquals("over_headroom", why(give(a, b, 1, kind = "headroom")))
    }

    @Test fun aTransferForAnotherNamespaceAnotherBudgetOrAPeriodThatHasEnded() {
        world.found(a, mapOf(
            "day" to RuleDocument.budget("account", "acct-1", "day", "USD-6", world.usd(100), mapOf("install-a" to world.usd(60), "install-b" to world.usd(40), "install-c" to 0L)),
            "applet:x" to RuleDocument.budget("applet", "x", "day", "USD-6", world.usd(10), mapOf("install-a" to world.usd(10), "install-b" to 0L)),
        ))
        // Another budget: C is not an executor of applet:x, so a transfer of it is not C's own.
        val other = forged(a, "install-c", 5, 1, a.journal.head().epoch, budget = "applet:x")
        assertEquals(Took.NOT_FOR_RECIPIENT, c.core.transfers.take(other).result)
        // Another namespace.
        val live = Canonical.encode(Tree.parse(signed(give(a, b, 5))) + ("namespace" to "live"))
        assertEquals(Took.NOT_FOR_RECIPIENT, b.core.transfers.take(live).result)
        // A transfer for one period, taken after that period ended: nothing.
        val forToday = signed(give(a, b, 10, lasts = "period"))
        assertEquals(today(), Tree.parse(forToday).map("effective")!!.str("from_period"))
        world.advance(DAY)
        assertEquals(Took.PERIOD_ENDED, b.core.transfers.take(forToday).result)
        assertEquals(world.usd(40), b.portion("day"))
        // The sender's debit was for that period too; its portion is whole again in the new one.
        assertEquals(world.usd(55), a.portion("day"))
        // Taken in time, a transfer for one period counts in that period only.
        val forThisDay = signed(give(a, b, 10, lasts = "period"))
        assertEquals(Took.TAKEN, b.core.transfers.take(forThisDay).result)
        assertEquals(world.usd(50), b.portion("day"))
        world.advance(DAY)
        assertEquals(world.usd(40), b.portion("day")); assertEquals(world.usd(55), a.portion("day"))
    }

    @Test fun aSenderWhoseDebitDidNotCommitSignsNothing_andOneThatCrashedAfterItSignsTheSameTransferAgain() {
        budget()
        a.standing("day")
        val seq = a.journal.head().seq
        a.journal.afterEntryWrite = { throw IllegalStateException("power cut") }
        assertThrows(IllegalStateException::class.java) { give(a, b, 10) }
        a.journal.afterEntryWrite = null
        assertEquals("no debit, and so no signature", seq, a.journal.head().seq)
        assertEquals(world.usd(60), a.portion("day"))

        // The debit commits; the signature is lost with the process. After a restart the same transfer is signed again.
        val first = give(a, b, 10) as Transfers.Given.Signed
        a.restartApp()
        val again = a.core.transfers.reissue(first.id) as Transfers.Given.Signed
        assertEquals(Tree.parse(first.json) - "signature", Tree.parse(again.json) - "signature")
        assertEquals("no second debit", world.usd(50), a.portion("day"))
        assertEquals(Took.TAKEN, b.core.transfers.take(again.json).result)
        assertEquals(Took.ALREADY_TAKEN, b.core.transfers.take(first.json).result)
        assertEquals(world.usd(50), b.portion("day"))
        assertEquals("no_such_debit", why(a.core.transfers.reissue("0".repeat(64))))
    }

    @Test fun whatASurrenderLeavesAndWhenItFreesRoom() {
        budget()
        // A device that spent $50 (payments included: the whole journal for the period) cannot surrender below it.
        assertTrue(a.held(world.usd(50)))
        assertEquals("below_spent", why(give(a, b, 11)))
        // Nor below a mandate floor.
        assertEquals("below_mandate_floor", why(give(a, b, 6, floor = world.usd(55))))
        // A device with a core_only source frees nothing in the current period: the recipient uses it from the next.
        val t = signed(give(a, b, 10, capped = false))
        assertEquals(tomorrow(), Tree.parse(t).map("effective")!!.str("from_period"))
        assertEquals("the debit is at once", world.usd(50), a.portion("day"))
        assertEquals(Took.TAKEN, b.core.transfers.take(t).result)
        assertEquals("nothing for the recipient today", world.usd(40), b.portion("day"))
        world.advance(DAY)
        assertEquals(world.usd(50), b.portion("day")); assertEquals(world.usd(50), a.portion("day"))
        // To an install that is not an executor of the budget, to itself, or to the cloud (not in this build): refused.
        world.device("install-z")
        assertEquals("recipient_not_an_executor", why(a.core.transfers.give("day", "install-z", "portion", world.money(1), "standing", true, agent, true)))
        assertEquals("recipient_not_an_executor", why(give(a, a, 1)))
        assertEquals("cloud_not_in_this_build", why(a.core.transfers.give("day", "cloud", "portion", world.money(1), "standing", true, agent, true)))
        // An agent without the Budgets capability cannot ask; with it, no person is needed.
        val r = a.core.transfers.give("day", "install-b", "portion", world.money(1), "standing", true, agent, false) as Transfers.Given.Refused
        assertEquals(Gates.Verdict.Refused(Gates.Why.BUDGETS_CAPABILITY_REQUIRED), r.verdict)
    }

    @Test fun aTransferFromASignerThisDeviceDoesNotTakeIsRefusedAndLogged() {
        budget()
        val t = signed(give(a, c, 10))
        // Only an own-tier link's install key counts. Without the link the same transfer is nothing.
        world.unlink("install-a", "install-c")
        assertEquals("signer_not_accepted", c.core.transfers.take(t).why)
        assertTrue(c.events.any { it.name == SpendEvent.SECURITY && it.detail["what"] == "transfer_signer" })
        assertEquals(0L, c.portion("day"))
        // A changed copy does not verify either.
        world.link("install-a", "install-c")
        val more = Tree.parse(t) + ("amount" to mapOf("unit" to "USD-6", "atoms" to world.usd(50)))
        assertEquals("signer_not_accepted", c.core.transfers.take(Canonical.encode(more)).why)
        assertEquals(Took.TAKEN, c.core.transfers.take(t).result)
    }

    @Test fun aTransferWaitsForTheDocumentItWasMadeAgainst() {
        budget()
        // A makes a new document and a transfer against it; the transfer reaches C first.
        val made = a.core.change(world.agent, true, a.core.document()!!.digest, "acct-1") { SpendingWorld.editBudget(it, "day") { x -> x + ("rebalance" to false) } } as RuleDocuments.Adopt.Adopted
        val t = signed(give(a, c, 10))
        assertEquals(Took.WAITING, c.core.transfers.take(t).result)
        assertEquals(0L, c.portion("day"))
        // When the document arrives, what waited for it is taken up in the same transaction.
        assertTrue(c.core.adopt(made.document.json) is RuleDocuments.Adopt.Adopted)
        assertEquals(world.usd(10), c.portion("day"))
        assertEquals(Took.ALREADY_TAKEN, c.core.transfers.take(t).result)
    }

    // ---- a sender that signs twice ----

    @Test fun twoTransfersOfAsWholePortionToBAndToC_metLater() {
        budget()
        // A is taken over: it debits and signs its whole $60 to B, and signs the same $60 again to C from a second journal.
        val toB = signed(give(a, b, 60))
        val toC = forged(a, "install-c", 60, 1, "ab".repeat(16))
        assertEquals(Took.TAKEN, b.core.transfers.take(toB).result)
        assertEquals(Took.TAKEN, c.core.transfers.take(toC).result)
        // Apart, each could spend it: the stated remainder, once per recipient that has not seen another's.
        assertTrue(b.held(world.usd(70)))
        assertTrue(c.held(world.usd(20)))

        // They meet. In counter order one of the two is past what A had, and every device finds the same one: the id decides
        // between two epochs. The device that holds it drops it; the other has seen nothing of its own displaced.
        val atB = b.core.transfers.observe(toC)
        val atC = c.core.transfers.observe(toB)
        assertEquals(setOf(Took.VOID, Took.UNPROVEN), setOf(atB.result, atC.result))
        assertEquals("past_what_it_had", atB.why); assertEquals("past_what_it_had", atC.why)
        val loser = if (atB.result == Took.VOID) b else c
        val winner = if (loser == b) c else b
        val void = loser.journal.entries().single { it.kind == "transfer_void" }.tree
        assertEquals(listOf(Tree.parse(if (loser == b) toB else toC).str("id")), void.list("ids"))
        assertTrue(winner.journal.entries().none { it.kind == "transfer_void" })
        // The one whose transfer is void drops the unspent part and keeps what it spent counted.
        val spent = loser.used("day")!!
        val own = if (loser == b) world.usd(40) else 0L
        assertEquals(maxOf(spent, own), loser.portion("day"))
        assertEquals(6, loser.refusal(1).rule)
        assertEquals(if (winner == b) world.usd(100) else world.usd(60), winner.portion("day"))
        assertTrue(loser.events.any { it.name == SpendEvent.TRANSFER_CONFLICT })

        // Nothing more is taken from A until a signed document lowers A's portion to what its valid transfers leave: 0.
        val more = forged(a, loser.name, 1, 2, "ab".repeat(16))
        assertEquals("sender_barred", loser.core.transfers.take(more).why)
        // That is a pure reduction, so any authorised agent can make it, on any device.
        val lowered = world.change(b, agent) { SpendingWorld.editBudget(it, "day") { x -> x + ("devices" to (x.map("devices")!! + ("install-a" to mapOf("atoms" to 0L, "kind" to "standing")))) } } as RuleDocuments.Adopt.Adopted
        world.share(lowered.document, except = b)
        assertTrue(loser.journal.entries().last { it.kind == "rules" }.tree.list("unbar").isNotEmpty())
        for (d in listOf(b, c)) { d.restartApp(); assertNull(d.journal.reset) }
    }

    @Test fun twoDifferentTransfersUnderOneCounterAreBothVoid() {
        budget()
        val epoch = "cd".repeat(16)
        val one = forged(a, "install-b", 10, 1, epoch)
        val two = forged(a, "install-c", 20, 1, epoch)
        assertEquals(Took.TAKEN, b.core.transfers.take(one).result)
        assertTrue(b.held(world.usd(45)))
        assertEquals(Took.TAKEN, c.core.transfers.take(two).result)
        // B sees C's: the two share a counter, so both are void, B's own included. $45 was spent: $5 of the $10 stays counted.
        val r = b.core.transfers.observe(two)
        assertEquals(Took.VOID, r.result); assertEquals("two_under_one_counter", r.why)
        assertEquals(world.usd(45), b.portion("day"))
        assertEquals(6, b.refusal(1).rule)
        val r2 = c.core.transfers.observe(one)
        assertEquals("two_under_one_counter", r2.why)
        assertEquals(0L, c.portion("day"))
        // The next day the kept part is gone as well.
        world.advance(DAY)
        assertEquals(world.usd(40), b.portion("day"))
        // A link that does not follow the one before it is void by itself.
        val broken = forged(b, "install-c", 1, 2, "ef".repeat(16), prev = "11".repeat(32))
        c.core.transfers.observe(forged(b, "install-a", 1, 1, "ef".repeat(16)))
        assertEquals("chain_broken", c.core.transfers.take(broken).why)
    }

    @Test fun nothingUnderTheCloudsKeyIsTaken() {
        budget()
        // A cloud cut signed to two devices: in the first build a device takes nothing from the cloud at all.
        val cut = Canonical.encode(Tree.parse(forged(a, "install-b", 10, 1, "ab".repeat(16))) + ("from" to "cloud"))
        assertEquals("cloud_not_in_this_build", b.core.transfers.take(cut).why)
        assertEquals("cloud_not_in_this_build", c.core.transfers.observe(cut).why)
        assertEquals(world.usd(40), b.portion("day"))
    }

    @Test fun yesterdaysOnePeriodTransferDoesNotStandAgainstTodaysStandingOne() {
        budget()
        // A gives B $30 for today only. The next day A gives C its whole $60, standing. C has seen both; A is honest.
        val forToday = signed(give(a, b, 30, lasts = "period"))
        assertEquals(Took.SEEN, c.core.transfers.observe(forToday).result)
        world.advance(DAY)
        assertEquals(world.usd(60), a.portion("day"))
        val standing = signed(give(a, c, 60))
        assertEquals(Took.TAKEN, c.core.transfers.take(standing).result)
        assertEquals(world.usd(60), c.portion("day"))
        assertTrue(c.events.none { it.name == SpendEvent.TRANSFER_CONFLICT })
        // In one and the same period the two would be past what A had.
        assertEquals(Took.UNPROVEN, c.core.transfers.observe(forged(a, "install-b", 1, 9, "ab".repeat(16))).result)
    }

    @Test fun roomThatCameThroughAnotherExecutorIsUnprovenUntilItsWayIsSeenAndNeverVoidForThat() {
        budget()
        // A gives B 60; B, now with 100, gives C 100. C gets B's transfer first and has not seen A's.
        val toB = signed(give(a, b, 60))
        assertEquals(Took.TAKEN, b.core.transfers.take(toB).result)
        val toC = signed(give(b, c, 100))
        val early = c.core.transfers.take(toC)
        assertEquals(Took.UNPROVEN, early.result); assertEquals("past_what_it_had", early.why)
        // Nothing was taken, recorded or voided, and B is not barred: C may simply not have seen what gave B the room.
        assertEquals(0L, c.portion("day"))
        assertTrue(c.journal.entries().none { it.kind.startsWith("transfer") })
        assertTrue(c.events.none { it.name == SpendEvent.TRANSFER_CONFLICT })
        // The carrier presents what gave the sender the room, and then the transfer again.
        assertEquals(Took.SEEN, c.core.transfers.observe(toB).result)
        assertEquals(Took.TAKEN, c.core.transfers.take(toC).result)
        assertEquals(world.usd(100), c.portion("day")); assertEquals(0L, b.portion("day"))
        // The same for a sender whose own increment the recipient has not applied yet.
        val inc = (c.core.increments.raise("day", world.money(50), "standing", agent, true) as Increments.Made.Signed).json
        val toA = signed(give(c, a, 150))
        assertEquals(Took.UNPROVEN, a.core.transfers.take(toA).result)
        a.core.transfers.observe(toB); a.core.transfers.observe(toC)
        assertEquals(Took.UNPROVEN, a.core.transfers.take(toA).result)
        assertEquals(Increments.Applied.APPLIED, a.core.increments.apply(inc).result)
        assertEquals(Took.TAKEN, a.core.transfers.take(toA).result)
        assertEquals(world.usd(150), a.portion("day"))
    }

    // ---- the reclaim right ----

    private fun evidence() = mapOf("source" to "key-9", "read" to "revoked", "at" to world.start.toString())

    @Test fun anEndedShareIsTakenOnlyByTheHolderOfTheReclaimRight() {
        // A made the budget, so A holds its reclaim right. C's standing share of $30 has ended (its key was revoked at the provider).
        budget(shares = mapOf("install-a" to 30L, "install-b" to 40L, "install-c" to 30L))
        assertTrue(a.standing("day").holdsReclaim); assertFalse(b.standing("day").holdsReclaim)
        // Claimed by another device: void there, whatever it holds.
        assertEquals("not_the_holder", why(b.core.transfers.claimEnded("day", "install-c", evidence(), agent, true)))
        assertEquals("no_evidence", why(a.core.transfers.claimEnded("day", "install-c", emptyMap(), agent, true)))
        val claim = signed(a.core.transfers.claimEnded("day", "install-c", evidence(), agent, true))
        assertEquals(world.usd(60), a.portion("day"))
        // There is only one such transfer per ended executor and budget: its id has no counter, and it is taken once.
        assertEquals(Signed.id(Signed.RECLAIM_ID, "install-c", "sandbox", "day"), Tree.parse(claim).str("id"))
        assertEquals("already_taken", why(a.core.transfers.claimEnded("day", "install-c", evidence(), agent, true)))
        assertEquals(world.usd(60), a.portion("day"))
        // The others record the holder's claim; a claim signed by anyone else is void everywhere and a security event.
        assertEquals(Took.SEEN, b.core.transfers.observe(claim).result)
        val byB = Tree.parse(claim) - "signature" - "actor" + ("to" to "install-b")
        val forgedClaim = (byB + ("actor" to ActorRecord.over(Signed.TRANSFER, byB, "agent", "a1", "http", "install-b").toMap())).let { it + ("signature" to Signed.encode(world.signAs("install-b", Signed.bytes(Signed.TRANSFER, it)))) }
        val seen = c.core.transfers.observe(Canonical.encode(forgedClaim))
        assertEquals(Took.VOID, seen.result); assertEquals("claim_not_by_holder", seen.why)
        assertTrue(c.events.any { it.detail["what"] == "claim_not_by_holder" })
        // Other executors get that room from the holder by ordinary transfers.
        val toB = signed(give(a, b, 30))
        assertEquals(Took.TAKEN, b.core.transfers.take(toB).result)
        assertEquals(world.usd(70), b.portion("day"))
    }

    @Test fun twoSuccessorsApartCannotNameAnotherReclaimer_andOnlyTheHoldersClaimStandsOnBothBranches() {
        // B holds the reclaim right (it made the budget). A's standing $100 share has ended.
        val made = b.core.change(world.owner, true, null, "acct-1") {
            it + ("budgets" to mapOf("day" to RuleDocument.budget("account", "acct-1", "day", "USD-6", world.usd(100), mapOf("install-a" to world.usd(100), "install-b" to 0L, "install-c" to 0L))))
        } as RuleDocuments.Adopt.Adopted
        world.share(made.document, except = b)
        world.unlink("install-b", "install-c")
        // Two successors apart, each trying to name another reclaimer: there is no such field, so each is invalid where it is made.
        for (d in listOf(b, c)) {
            val named = world.change(d, agent) { SpendingWorld.editBudget(it, "day") { x -> x + ("reverts_to" to "install-c") } } as RuleDocuments.Adopt.Refused
            assertEquals("unknown_field:day", named.why)
        }
        // Each makes an ordinary successor instead, so they are on two branches.
        val byB = world.change(b, agent) { SpendingWorld.editBudget(it, "day") { x -> x + ("rebalance" to false) } } as RuleDocuments.Adopt.Adopted
        val byC = world.change(c, agent) { it + ("zone" to "Europe/Warsaw") } as RuleDocuments.Adopt.Adopted
        // B and C each claim A's ended $100: only B's stands, on both branches.
        val claim = signed(b.core.transfers.claimEnded("day", "install-a", evidence(), agent, true))
        assertEquals("not_the_holder", why(c.core.transfers.claimEnded("day", "install-a", evidence(), agent, true)))
        assertEquals(world.usd(100), b.portion("day")); assertEquals(0L, c.portion("day"))
        // When the branches meet, the merge does not touch the right, and C records B's claim.
        world.link("install-b", "install-c")
        val atC = c.core.adopt(byB.document.json) as RuleDocuments.Adopt.Adopted
        val atB = b.core.adopt(byC.document.json) as RuleDocuments.Adopt.Adopted
        assertTrue(atC.current.merged)
        assertEquals("both arrive at the same merged document", atC.current.digest, atB.current.digest)
        assertEquals(Took.SEEN, c.core.transfers.observe(claim).result)
        assertTrue(b.standing("day").holdsReclaim); assertFalse(c.standing("day").holdsReclaim)
        assertEquals(world.usd(100), b.portion("day")); assertEquals(0L, c.portion("day"))
        assertEquals("not_the_holder", why(c.core.transfers.claimEnded("day", "install-a", evidence(), agent, true)))
    }

    @Test fun theReclaimRightMovesOnlyByATransferFromItsHolder() {
        budget(shares = mapOf("install-a" to 30L, "install-b" to 40L, "install-c" to 30L))
        // Not the holder: cannot move it.
        assertEquals("not_the_holder", why(give(b, c, 0, kind = "reclaim")))
        val right = signed(give(a, b, 0, kind = "reclaim"))
        // Debited before the signature leaves: the old holder cannot use it any more, taken or not.
        assertFalse(a.standing("day").holdsReclaim)
        assertEquals("not_the_holder", why(a.core.transfers.claimEnded("day", "install-c", evidence(), agent, true)))
        assertFalse("not yet taken", b.standing("day").holdsReclaim)
        // An ended share while the holder is gone: nobody takes it, and the room stays unused.
        assertEquals("not_the_holder", why(b.core.transfers.claimEnded("day", "install-c", evidence(), agent, true)))
        // Presented by C: not its own.
        assertEquals(Took.NOT_FOR_RECIPIENT, c.core.transfers.take(right).result)
        assertEquals(Took.TAKEN, b.core.transfers.take(right).result)
        assertEquals(Took.ALREADY_TAKEN, b.core.transfers.take(right).result)
        assertTrue(b.standing("day").holdsReclaim)
        // C learns the move from the transfer; then the old holder's claim is void for it and the new holder's stands.
        assertEquals(Took.SEEN, c.core.transfers.observe(right).result)
        val claim = signed(b.core.transfers.claimEnded("day", "install-c", evidence(), agent, true))
        assertEquals(world.usd(70), b.portion("day"))
        assertEquals(Took.SEEN, a.core.transfers.observe(claim).result)
        // A move of the right from anyone but its holder is forged, or ahead of a move this device has not seen: it moves nothing.
        val notHolders = forged(c, "install-a", 0, 1, c.journal.head().epoch, kind = "reclaim")
        val r = a.core.transfers.take(notHolders)
        assertEquals(Took.UNPROVEN, r.result); assertEquals("not_from_the_holder", r.why)
        assertFalse(a.standing("day").holdsReclaim)
        assertEquals(Took.UNPROVEN, b.core.transfers.observe(notHolders).result)
        assertTrue(b.standing("day").holdsReclaim)
        // The right can be passed on again, by its holder only.
        assertEquals("not_the_holder", why(give(a, c, 0, kind = "reclaim")))
        assertTrue(give(b, a, 0, kind = "reclaim") is Transfers.Given.Signed)
    }

    // ---- an executor whose journal was reset (SA-R6) ----

    @Test fun aResetFreezesPortionHeadroomAndTheReclaimRightUntilTheRecordIsRebuilt_the50DollarCase() {
        // A $100 budget under a $150 agent ceiling. A has the whole $50 of headroom, and gives it to B.
        budget(ceiling = 150, headroom = mapOf("install-a" to 50L), shares = mapOf("install-a" to 50L, "install-b" to 50L))
        val toB = signed(give(a, b, 50, kind = "headroom"))
        assertEquals(Took.TAKEN, b.core.transfers.take(toB).result)
        assertTrue(a.held(world.usd(20)))
        val doc = a.core.document()!!

        // A loses its journal. The rule document it holds still shows $50 of headroom, $50 of portion, and A as the maker.
        a.loseJournalFile()
        a.core.adopt(doc.json)
        a.anchor()
        assertEquals(world.usd(50), a.core.document()!!.budgets.getValue("day").headroom("install-a"))
        assertTrue(a.standing("day").frozen)
        // It signs an increment: refused. The balance counts as 0, so $200 cannot be reached.
        fun raise() = a.core.increments.raise("day", world.money(50), "standing", agent, true)
        assertEquals(Gates.Verdict.Refused(Gates.Why.OVER_CEILING), (raise() as Increments.Made.Refused).verdict)
        assertEquals(0L, a.headroom("day")); assertEquals(0L, a.portion("day")); assertFalse(a.standing("day").holdsReclaim)
        assertEquals("frozen_after_reset", why(give(a, b, 1)))
        assertEquals("frozen_after_reset", why(give(a, b, 0, kind = "reclaim")))

        // Again after a new period opens: still refused. A new period restores nothing standing in a shared budget.
        world.advance(DAY)
        assertEquals(Gates.Verdict.Refused(Gates.Why.OVER_CEILING), (raise() as Increments.Made.Refused).verdict)
        assertEquals(SpendRefusal(Code.ALLOWANCE_EXHAUSTED, Reason.FROZEN_AFTER_RESET, 8), a.refusal(1))
        // B uses the headroom it was given: the budget reaches $150 and no more.
        assertTrue(b.core.increments.raise("day", world.money(50), "standing", agent, true) is Increments.Made.Signed)
        assertEquals(world.usd(100), b.portion("day"))

        // After B's signed list: portion, headroom balance and reclaim right come back together. The balance is 0.
        assertEquals(Transfers.Rebuilt.MISSING_A_LIST, a.core.transfers.rebuild("day", emptyList()))
        val list = list(b, a)
        assertEquals(Transfers.Rebuilt.DONE, a.core.transfers.rebuild("day", listOf(list)))
        assertFalse(a.standing("day").frozen)
        assertEquals(0L, a.headroom("day")); assertEquals(world.usd(50), a.portion("day")); assertTrue(a.standing("day").holdsReclaim)
        assertEquals(Gates.Verdict.Refused(Gates.Why.OVER_CEILING), (raise() as Increments.Made.Refused).verdict)
        assertTrue(a.held(world.usd(50)))
        assertEquals("the transfer it signed before the reset is never taken as a new one", Transfers.Rebuilt.NOT_FROZEN, a.core.transfers.rebuild("day", listOf(list)))
        a.restartApp(); assertNull(a.journal.reset)
        assertEquals(world.usd(50), a.portion("day"))
    }

    @Test fun aResetDeviceWhoseOnlyPeerNeverAnswersStaysFrozenButRoomCanStillReachIt() {
        budget(shares = mapOf("install-a" to 60L, "install-b" to 40L))
        // The sender's journal is reset after it signed a transfer: the transfer stands for its recipient.
        val toB = signed(give(a, b, 10))
        val doc = a.core.document()!!
        a.loseHead()
        assertEquals("the document came back from the device's own rows", doc.digest, a.core.document()!!.digest)
        assertEquals(Took.TAKEN, b.core.transfers.take(toB).result)
        assertEquals(world.usd(50), b.portion("day"))
        assertEquals(0L, a.portion("day"))
        // A list that is not from the peer, or not signed by it, rebuilds nothing.
        val stranger = world.device("install-x", linked = false)
        assertEquals(Transfers.Rebuilt.BAD_LIST, a.core.transfers.rebuild("day", listOf(list(stranger, a))))
        assertEquals(Transfers.Rebuilt.BAD_LIST, a.core.transfers.rebuild("day", listOf(list(a, a))))
        assertTrue(a.standing("day").frozen)
        // Meanwhile another executor can transfer room to it, and that counts once a period is open.
        val back = signed(give(b, a, 20))
        assertEquals(Took.TAKEN, a.core.transfers.take(back).result)
        // The first anchor names today, which stays fully used; the day after is the first that opens.
        a.anchor()
        assertNull(a.used("day"))
        world.advance(DAY)
        assertEquals(world.usd(20), a.portion("day"))
        assertTrue(a.held(world.usd(20)))
        assertEquals(Reason.FROZEN_AFTER_RESET, a.refusal(1).reason)
        // A list is held to what taking would be. The peer adds a transfer to A that it signed and never debited, for far more
        // than it had: that one is not counted (and never taken later), and the rest of the list stands.
        val honest = Tree.parse(list(b, a))
        val inflated = Tree.parse(forged(b, "install-a", 500, 7, b.journal.head().epoch))
        val padded = (honest - "signature" + ("transfers" to (honest.list("transfers") + inflated))).let { it + ("signature" to Signed.encode(world.signAs("install-b", Signed.bytes(Signed.HOLDINGS, it)))) }
        val tampered = honest + ("transfers" to emptyList<Any?>())
        assertEquals("a list changed after it was signed", Transfers.Rebuilt.BAD_LIST, a.core.transfers.rebuild("day", listOf(Canonical.encode(tampered))))
        // When the peer answers, the record is whole: 60 in the document, less the 10 it gave, plus the 20 it took.
        assertEquals(Transfers.Rebuilt.DONE, a.core.transfers.rebuild("day", listOf(Canonical.encode(padded))))
        assertEquals(Took.ALREADY_TAKEN, a.core.transfers.take(Canonical.encode(inflated)).result)
        assertEquals(world.usd(70), a.portion("day"))
        assertEquals(world.usd(30), b.portion("day"))
        assertEquals(Transfers.Rebuilt.NOT_RESET, b.core.transfers.rebuild("day", emptyList()))
    }

    // ---- review findings (Astra, 12 Nov 2026) ----

    @Test fun aStandingTransferIsBackedOverItsWholeLife_roomForOnePeriodCannotStandBehindIt() {
        budget(shares = mapOf("install-a" to 50L, "install-b" to 50L))
        // A tops up by $50 for today only, and tries to give B $100 for good.
        val top = (a.core.increments.raise("day", world.money(50), "period", agent, true) as Increments.Made.Signed).json
        assertEquals(world.usd(100), a.portion("day"))
        assertEquals("not_backed_for_its_lifetime", why(give(a, b, 100)))
        assertEquals("not_backed_for_its_lifetime", why(give(a, b, 51)))
        // For today only it can go, and what A has for good can go for good. (B has applied the top-up, so it can tell.)
        assertEquals(Increments.Applied.APPLIED, b.core.increments.apply(top).result)
        assertEquals(Took.TAKEN, b.core.transfers.take(signed(give(a, b, 30, lasts = "period"))).result)
        assertEquals(Took.TAKEN, b.core.transfers.take(signed(give(a, b, 50))).result)
        // At receipt the same holds: a standing $100 signed anyway is not backed by a top-up B has applied.
        val anyway = forged(a, "install-b", 100, 9, "ab".repeat(16))
        assertEquals(Took.UNPROVEN, b.core.transfers.take(anyway).result)
        // The next period the top-up and the one-period transfer are gone, and the portions still add up to the budget.
        world.advance(DAY)
        assertEquals(0L, a.portion("day")); assertEquals(world.usd(100), b.portion("day"))
    }

    @Test fun theReclaimRightTakesItsUsedUpClaimsWithIt_theSameEndedShareIsNotClaimedAgainByTheNextHolder() {
        budget(shares = mapOf("install-a" to 30L, "install-b" to 40L, "install-c" to 30L))
        // A claims C's ended $30, then passes the right to B. B never saw A's claim.
        val claim = signed(a.core.transfers.claimEnded("day", "install-c", evidence(), agent, true))
        val right = signed(give(a, b, 0, kind = "reclaim"))
        assertEquals(listOf(Tree.parse(claim).str("id")), Tree.parse(right).list("claimed"))
        assertEquals(Took.TAKEN, b.core.transfers.take(right).result)
        assertTrue(b.standing("day").holdsReclaim)
        // The claim came with the right: the same ended share cannot be taken a second time.
        assertEquals("already_taken", why(b.core.transfers.claimEnded("day", "install-c", evidence(), agent, true)))
        assertEquals(world.usd(40), b.portion("day")); assertEquals(world.usd(60), a.portion("day"))
        // A second claim signed anyway by the new holder is void wherever the first is known, seen or carried.
        val body = Tree.parse(claim) - "signature" - "actor" + ("to" to "install-b")
        val second = Canonical.encode((body + ("actor" to ActorRecord.over(Signed.TRANSFER, body, "agent", "a1", "http", "install-b").toMap())).let { it + ("signature" to Signed.encode(world.signAs("install-b", Signed.bytes(Signed.TRANSFER, it)))) })
        assertEquals(Took.SEEN, c.core.transfers.observe(claim).result)
        assertEquals(Took.SEEN, c.core.transfers.observe(right).result)
        val r = c.core.transfers.observe(second)
        assertEquals(Took.VOID, r.result); assertEquals("already_claimed", r.why)
        // And it goes on with the right: C gets it from B and cannot claim either.
        assertEquals(Took.TAKEN, c.core.transfers.take(signed(give(b, c, 0, kind = "reclaim"))).result)
        assertEquals("not_an_executor", why(c.core.transfers.claimEnded("day", "install-c", evidence(), agent, true)))
        assertTrue(c.journal.transact { tx -> tx.state.claimed.getValue("day").contains(Tree.parse(claim).str("id")) })
    }

    @Test fun aListAnswersOneRecovery_anOldListAndALateTransferRebuildNothing() {
        // A $100 budget under a $150 ceiling; A has the whole $50 of headroom.
        budget(ceiling = 150, headroom = mapOf("install-a" to 50L), shares = mapOf("install-a" to 50L, "install-b" to 50L))
        // B's list about A from before anything moved is saved: valid, signed, and empty.
        val old = b.core.transfers.holdings("day", "install-a", a.journal.head().epoch, "00".repeat(16))
        b.restartApp()
        // A gives B its $50 of headroom; B uses it. Then A loses its journal.
        val toB = signed(give(a, b, 50, kind = "headroom"))
        assertEquals(Transfers.Taken(Took.TAKEN), b.core.transfers.take(toB))
        assertTrue(b.core.increments.raise("day", world.money(50), "standing", agent, true) is Increments.Made.Signed)
        val late = signed(give(a, b, 10))
        val doc = a.core.document()!!
        a.loseJournalFile(); a.core.adopt(doc.json); a.anchor()
        // The saved list answers no recovery: with no challenge made, and after one is made, it rebuilds nothing.
        assertEquals(Transfers.Rebuilt.MISSING_A_LIST, a.core.transfers.rebuild("day", listOf(old)))
        val recovery = a.core.transfers.recover("day")
        assertEquals("one challenge per budget and epoch", recovery, a.core.transfers.recover("day"))
        assertEquals(Transfers.Rebuilt.BAD_LIST, a.core.transfers.rebuild("day", listOf(old)))
        // Nor does a list for this epoch with another challenge, or with this challenge for another epoch.
        assertEquals(Transfers.Rebuilt.BAD_LIST, a.core.transfers.rebuild("day", listOf(b.core.transfers.holdings("day", "install-a", recovery.epoch, "11".repeat(16)))))
        assertEquals(Transfers.Rebuilt.BAD_LIST, a.core.transfers.rebuild("day", listOf(b.core.transfers.holdings("day", "install-a", "22".repeat(16), recovery.challenge))))
        assertTrue(a.standing("day").frozen); assertEquals(0L, a.headroom("day"))
        // The fresh list has the headroom transfer: the balance comes back as 0, not the $50 the document shows.
        val fresh = b.core.transfers.holdings("day", "install-a", recovery.epoch, recovery.challenge)
        assertEquals(Transfers.Rebuilt.DONE, a.core.transfers.rebuild("day", listOf(fresh)))
        assertEquals(0L, a.headroom("day")); assertEquals(world.usd(50), a.portion("day"))
        // The list was a snapshot B is held to: a transfer A signed before the reset and shows only now is not taken up,
        // so what A rebuilt from stays the whole of it and the $10 is not counted on both sides.
        val r = b.core.transfers.take(late)
        assertEquals(Took.REFUSED, r.result); assertEquals("sender_recovered", r.why)
        assertEquals(world.usd(100), b.portion("day"))
        // What A signs in its new epoch is taken as ever.
        a.standing("day"); world.advance(DAY)
        assertEquals(Took.TAKEN, b.core.transfers.take(signed(give(a, b, 5))).result)
        b.restartApp(); assertNull(b.journal.reset)
    }

    @Test fun aReductionOfTheBudgetReachesRoomThatWasTransferred_portionsNeverAddUpToMoreThanTheReducedBudget() {
        budget(shares = mapOf("install-a" to 100L, "install-b" to 0L, "install-c" to 0L))
        assertEquals(Took.TAKEN, b.core.transfers.take(signed(give(a, b, 100))).result)
        assertEquals(world.usd(100), b.portion("day")); assertEquals(0L, a.portion("day"))
        // A valid successor halves the budget and A's share. A cannot go below zero, so the cut has to reach what B took.
        val halved = (world.change(a, world.owner) { SpendingWorld.editBudget(it, "day") { x -> x + ("amount" to world.usd(50)) + ("devices" to (x.map("devices")!! + ("install-a" to mapOf("atoms" to world.usd(50), "kind" to "standing")))) } } as RuleDocuments.Adopt.Adopted).document
        world.share(halved, except = a)
        assertEquals(0L, a.portion("day")); assertEquals(world.usd(50), b.portion("day")); assertEquals(0L, c.portion("day"))
        assertTrue(b.held(world.usd(50)))
        assertEquals(6, b.refusal(1).rule)
        // Three executors, where a cap by the budget alone would not do: A 60, B 20, C 20; A gives B 60; then budget 50 with A 10.
        val w = SpendingWorld(RuntimeEnvironment.getApplication(), Namespace.LIVE)
        try {
            val (x, y, z) = listOf(w.device("x"), w.device("y"), w.device("z"))
            w.found(x, mapOf("day" to RuleDocument.budget("account", "acct-1", "day", "USD-6", w.usd(100), mapOf("x" to w.usd(60), "y" to w.usd(20), "z" to w.usd(20)))))
            assertEquals(Took.TAKEN, y.core.transfers.take((x.core.transfers.give("day", "y", "portion", w.money(60), "standing", true, w.agent, true) as Transfers.Given.Signed).json).result)
            val cut = (w.change(x, w.owner) { SpendingWorld.editBudget(it, "day") { b -> b + ("amount" to w.usd(50)) + ("devices" to mapOf("x" to mapOf("atoms" to w.usd(10), "kind" to "standing"), "y" to mapOf("atoms" to w.usd(20), "kind" to "standing"), "z" to mapOf("atoms" to w.usd(20), "kind" to "standing"))) } } as RuleDocuments.Adopt.Adopted).document
            w.share(cut, except = x)
            assertEquals(listOf(0L, w.usd(30), w.usd(20)), listOf(x, y, z).map { it.portion("day") })
            // A document that lowers a share and leaves the budget alone takes nothing from what was transferred.
            val lower = (w.change(x, w.agent) { SpendingWorld.editBudget(it, "day") { b -> b + ("devices" to (b.map("devices")!! + ("x" to mapOf("atoms" to 0L, "kind" to "standing")))) } } as RuleDocuments.Adopt.Adopted).document
            w.share(lower, except = x)
            assertEquals(w.usd(30), y.portion("day"))
        } finally { w.close() }
    }

    @Test fun aMoveOfTheRightAppliesOnce_seenFirstAndTakenLaterDoesNotBringAnOldHolderBack() {
        budget()
        val toB = signed(give(a, b, 0, kind = "reclaim"))
        // B is shown the transfer that names it by the "seen" way in. There is one way in for it: it is taken.
        assertEquals(Took.TAKEN, b.core.transfers.observe(toB).result)
        assertTrue(b.standing("day").holdsReclaim)
        // B passes the right on to C, and then takes A's original transfer again.
        val toC = signed(give(b, c, 0, kind = "reclaim"))
        assertEquals(Took.SEEN, c.core.transfers.observe(toB).result)
        assertEquals(Took.TAKEN, c.core.transfers.take(toC).result)
        assertEquals(Took.ALREADY_TAKEN, b.core.transfers.take(toB).result)
        assertEquals(Took.ALREADY_TAKEN, b.core.transfers.observe(toB).result)
        assertFalse("the old holder did not come back", b.standing("day").holdsReclaim)
        assertTrue(c.standing("day").holdsReclaim)
        assertEquals("install-c", b.journal.transact { tx -> tx.state.reclaim["day"] })
        // The same for room: a portion transfer shown to its recipient as "seen" is taken once and counted once.
        val room = signed(give(a, b, 10))
        assertEquals(Took.TAKEN, b.core.transfers.observe(room).result)
        assertEquals(Took.ALREADY_TAKEN, b.core.transfers.take(room).result)
        assertEquals(world.usd(50), b.portion("day"))
        // A, who gave the right away, is shown C's later move of it back to A out of order with nothing else: taken, once.
        val back = signed(give(c, a, 0, kind = "reclaim"))
        assertEquals(Took.UNPROVEN, a.core.transfers.take(back).result)
        assertEquals(Took.SEEN, a.core.transfers.observe(toC).result)
        assertEquals(Took.TAKEN, a.core.transfers.take(back).result)
        assertEquals(Took.ALREADY_TAKEN, a.core.transfers.take(back).result)
        assertTrue(a.standing("day").holdsReclaim)
    }
}
