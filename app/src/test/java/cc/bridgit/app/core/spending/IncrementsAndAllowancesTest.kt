package cc.bridgit.app.core.spending

import android.app.Application
import cc.bridgit.app.core.spending.AllowanceStore.Why
import cc.bridgit.app.core.spending.Increments.Applied
import cc.bridgit.app.core.spending.SpendRefusal.Code
import cc.bridgit.app.core.spending.SpendRefusal.Reason
import cc.bridgit.app.core.spending.SpendingWorld.Companion.DAY
import cc.bridgit.app.core.spending.SpendingWorld.Companion.editBudget
import cc.bridgit.app.core.spending.SpendingWorld.Companion.withGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Raises as increments, headroom as a balance, and the held allowance (G5.1, G5.2): the device-only fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IncrementsAndAllowancesTest {
    private val world = SpendingWorld(RuntimeEnvironment.getApplication())
    private val a = world.device("install-a")
    private val b = world.device("install-b")
    private val agent = world.agent
    private val owner = world.owner

    @After fun close() = world.close()

    private fun usd(d: Long) = world.usd(d)

    private fun budget(ceiling: Long? = null, headA: Long = 0, headB: Long = 0, kind: String = "standing", caller: Caller = owner) = world.found(
        a,
        mapOf("day" to RuleDocument.budget("account", "acct-1", "day", "USD-6", usd(100), mapOf("install-a" to usd(50), "install-b" to usd(50)), kind = kind, ceiling = ceiling?.let(::usd),
            headroom = mapOf("install-a" to usd(headA), "install-b" to usd(headB)).filterValues { it > 0 })),
        caller,
    )

    private fun raise(d: SpendingWorld.Device, dollars: Long, lasts: String = "standing", by: Caller = agent, capability: Boolean = true) = d.core.increments.raise("day", world.money(dollars), lasts, by, capability)
    private fun json(m: Increments.Made) = (m as Increments.Made.Signed).json
    private fun verdict(m: Increments.Made) = (m as Increments.Made.Refused).verdict

    // ---- increments ----

    @Test fun aRaiseIsUsableAtOnceOnTheSigningDeviceAndAppliedOnceEverywhereElse() {
        budget()
        assertTrue(a.held(usd(50)))
        assertEquals(6, a.refusal(1).rule)
        // An authorised raise of an ungated budget needs nobody, with or without the cloud.
        val inc = json(raise(a, 30))
        assertTrue("usable at once", a.held(usd(30)))
        assertEquals(usd(80), a.portion("day"))
        // It raises one portion by exactly what it adds: the signing device's. B's does not move.
        assertEquals(Applied.APPLIED, b.core.increments.apply(inc).result)
        assertEquals(usd(50), b.portion("day"))
        // The same increment delivered twice changes nothing.
        assertEquals(Applied.ALREADY_APPLIED, b.core.increments.apply(inc).result)
        assertEquals(1, b.journal.entries().count { it.kind == "increment" })
        // Standing: it holds in the next period too.
        world.advance(DAY)
        assertEquals(usd(80), a.portion("day"))
        // The signature is over the label, a line feed and the canonical body; a changed copy is nothing.
        val changed = Tree.parse(inc) + ("amount" to mapOf("unit" to "USD-6", "atoms" to usd(500)))
        assertEquals("signer_not_accepted", world.device("install-c").let { c -> c.core.adopt(a.core.document()!!.json); c.core.increments.apply(Canonical.encode(changed)).why })
        a.restartApp(); b.restartApp()
        assertNull(a.journal.reset); assertNull(b.journal.reset)
        assertEquals(usd(80), a.portion("day"))
    }

    @Test fun twoIncrementsMadeApartAddUpWhenTheDevicesMeet() {
        budget()
        world.unlink("install-a", "install-b")
        val byA = json(raise(a, 20))
        val byB = json(raise(b, 30))
        world.link("install-a", "install-b")
        assertEquals(Applied.APPLIED, a.core.increments.apply(byB).result)
        assertEquals(Applied.APPLIED, b.core.increments.apply(byA).result)
        // Each was authorised by itself and each device spent only its own, so nothing overlaps: 100 + 20 + 30.
        assertEquals(usd(70), a.portion("day")); assertEquals(usd(80), b.portion("day"))
        for (d in listOf(a, b)) assertEquals(usd(150), d.journal.transact { tx -> Portions.total(tx.state, d.core.rules.current(tx), "day", d.standing("day").period!!) })
        // The ids differ even though both are each device's first: the install id is in the id.
        assertFalse(Tree.parse(byA).str("id") == Tree.parse(byB).str("id"))
    }

    @Test fun aTopUpLastsOnePeriodAndThePortionSumStillHolds() {
        budget()
        // A $500 top-up: this period's total and this device's portion for this period only.
        val top = json(raise(a, 500, lasts = "period"))
        assertEquals(usd(550), a.portion("day"))
        assertEquals(Applied.APPLIED, b.core.increments.apply(top).result)
        val today = a.standing("day").period!!
        assertEquals("portions still add up to the total, the top-up included", usd(600), a.journal.transact { tx -> Portions.total(tx.state, a.core.rules.current(tx), "day", today) })
        assertEquals(usd(600), a.portion("day") + b.portion("day"))
        assertTrue(a.held(usd(550)))
        // The next period it is gone, on both devices.
        world.advance(DAY)
        assertEquals(usd(50), a.portion("day"))
        val tomorrow = b.standing("day").period!!
        assertEquals(usd(100), b.journal.transact { tx -> Portions.total(tx.state, b.core.rules.current(tx), "day", tomorrow) })
    }

    @Test fun underAnAgentCeilingHeadroomIsABalance_twoDevicesApartEachRaisingA100BudgetWithA150CeilingBy50() {
        budget(ceiling = 150, headA = 25, headB = 25)
        world.unlink("install-a", "install-b")
        // Each tries $50 apart: larger than its balance, refused. They can never pass the ceiling together, whoever was online.
        for (d in listOf(a, b)) assertEquals(Gates.Verdict.Refused(Gates.Why.OVER_CEILING), verdict(raise(d, 50)))
        // One raises by its whole balance, the other by $25 apart: 100 + 25 + 25 = the ceiling.
        val byA = json(raise(a, 25))
        val byB = json(raise(b, 25))
        assertTrue(Tree.parse(byA).flag("draws_headroom"))
        assertEquals(0L, a.headroom("day")); assertEquals(0L, b.headroom("day"))
        // Two increments of a device's whole headroom: the second is refused.
        assertEquals(Gates.Verdict.Refused(Gates.Why.OVER_CEILING), verdict(raise(a, 25)))
        assertEquals(Gates.Verdict.Refused(Gates.Why.OVER_CEILING), verdict(raise(a, 1, lasts = "period")))
        world.link("install-a", "install-b")
        assertEquals(Applied.APPLIED, a.core.increments.apply(byB).result)
        assertEquals(Applied.APPLIED, b.core.increments.apply(byA).result)
        assertEquals(usd(75), a.portion("day")); assertEquals(usd(75), b.portion("day"))
        // An increment that claims more headroom than its signer had is not applied.
        val over = Tree.parse(byB) - "signature" - "actor" + ("counter" to 2L) + ("id" to Signed.id(Signed.INCREMENT_ID, "install-b", "sandbox", b.journal.head().epoch, "2")) + ("amount" to mapOf("unit" to "USD-6", "atoms" to usd(40)))
        val forged = (over + ("actor" to ActorRecord.over(Signed.INCREMENT, over, "agent", "a1", "http", "install-b").toMap())).let { it + ("signature" to Signed.encode(world.signAs("install-b", Signed.bytes(Signed.INCREMENT, it)))) }
        assertEquals(Gates.Why.OVER_CEILING.id, a.core.increments.apply(Canonical.encode(forged)).why)
        // To raise by more, a device first gets headroom from another: there is none left here.
        assertEquals("over_headroom", (b.core.transfers.give("day", "install-a", "headroom", world.money(1), "standing", true, agent, true) as Transfers.Given.Refused).why)
    }

    @Test fun aTopUpDrawsOnTheHeadroomBalanceForItsPeriodOnly() {
        budget(ceiling = 150, headA = 50)
        assertTrue(raise(a, 30, lasts = "period") is Increments.Made.Signed)
        assertEquals(usd(20), a.headroom("day")); assertEquals(usd(80), a.portion("day"))
        world.advance(DAY)
        assertEquals("the period's total is back under the ceiling, and so is the balance", usd(50), a.headroom("day"))
        assertEquals(usd(50), a.portion("day"))
    }

    @Test fun theOwnersRaiseIsNotBoundByTheCeilingAndDrawsOnNoBalance() {
        // The owner set a $150 ceiling; $30 of the room is A's balance, $20 is in nobody's.
        budget(ceiling = 150, headA = 30)
        assertTrue(a.core.document()!!.budgets.getValue("day").ownerSet)
        val inc = Tree.parse(json(raise(a, 20, by = owner)))
        assertFalse(inc.flag("draws_headroom"))
        assertEquals(usd(30), a.headroom("day")); assertEquals(usd(70), a.portion("day"))
        // The document must stay valid: a raise the unallocated room cannot hold comes with a raise of the ceiling first.
        assertEquals(Gates.Verdict.Refused(Gates.Why.RAISE_THE_CEILING_FIRST), verdict(raise(a, 1, by = owner)))
        assertTrue(world.change(a, owner) { editBudget(it, "day") { x -> withGate(x, ceiling = usd(200)) } } is RuleDocuments.Adopt.Adopted)
        assertTrue(raise(a, 50, by = owner) is Increments.Made.Signed)
        // An agent is still bound by its balance, and an agent without the Budgets capability raises nothing.
        assertEquals(Gates.Verdict.Refused(Gates.Why.OVER_CEILING), verdict(raise(a, 31)))
        assertEquals(Gates.Verdict.Refused(Gates.Why.BUDGETS_CAPABILITY_REQUIRED), verdict(raise(a, 1, capability = false)))
        assertTrue(raise(a, 30) is Increments.Made.Signed)
        // The other device applies an owner's raise that drew on nothing, and an agent's that drew on its signer's balance.
        for (e in a.journal.entries().filter { it.kind == "increment" }) {
            world.share(a.core.document()!!, except = a)
            assertEquals(Applied.APPLIED, b.core.increments.apply(json(a.core.increments.reissue(e.tree.map("increment")!!.str("id")!!))).result)
        }
    }

    @Test fun aBudgetWhoseAgentsMayChangeTheOwnerTurnedOffTakesNoAgentIncrement() {
        budget()
        world.change(a, owner) { editBudget(it, "day") { x -> withGate(x, agentsMayChange = false) } }
        assertEquals(Gates.Verdict.Refused(Gates.Why.AGENTS_MAY_NOT_CHANGE), verdict(raise(a, 1)))
        assertEquals(Gates.Verdict.Refused(Gates.Why.AGENTS_MAY_NOT_CHANGE), verdict(raise(a, 1, lasts = "period")))
        assertTrue("the owner still raises it", raise(a, 1, by = owner) is Increments.Made.Signed)
        // Enrolling an approver changes nothing while the approver path is off: the gate stays on grants.
        world.approverEnrolled = true
        assertTrue(raise(a, 1, by = owner) is Increments.Made.Signed)
        assertEquals(Gates.Verdict.Refused(Gates.Why.AGENTS_MAY_NOT_CHANGE), verdict(raise(a, 1)))
    }

    @Test fun anIncrementMadeAgainstADocumentThatHadSinceGainedAnOwnerGateLosesItsUnspentPart() {
        budget()
        world.unlink("install-a", "install-b")
        // The owner turns agents_may_change off on B. A, apart, has not seen it and an agent raises by $40 there.
        val gated = (world.change(b, owner) { editBudget(it, "day") { x -> withGate(x, agentsMayChange = false) } } as RuleDocuments.Adopt.Adopted).document
        val inc = json(raise(a, 40))
        assertTrue(a.held(usd(60)))
        assertEquals(usd(90), a.portion("day"))
        // The document arrives: the unspent $30 is dropped, the $10 spent stays counted, and spend.overshoot names the cause.
        world.link("install-a", "install-b")
        assertTrue(a.core.adopt(gated.json) is RuleDocuments.Adopt.Adopted)
        assertEquals(usd(60), a.portion("day"))
        assertEquals(6, a.refusal(1).rule)
        val event = a.events.single { it.name == SpendEvent.OVERSHOOT }
        assertEquals("owner_gate", event.detail["cause"]); assertEquals(usd(10).toString(), event.detail["counted"])
        // The next period nothing of it is left, and B never applies it.
        world.advance(DAY)
        assertEquals(usd(50), a.portion("day"))
        assertEquals(Gates.Why.AGENTS_MAY_NOT_CHANGE.id, b.core.increments.apply(inc).why)
        // An increment made before the device last took up documents is not touched by a gate that comes later.
        a.restartApp(); assertNull(a.journal.reset)
    }

    @Test fun anIncrementTheDeviceMadeLongBeforeIsNotUndoneByALaterGate() {
        budget()
        val inc = json(raise(a, 40))
        b.core.increments.apply(inc)
        // Documents come and go; the increment is no longer one the next document was made without knowing.
        val next = (world.change(b, agent) { it + ("zone" to "Europe/Warsaw") } as RuleDocuments.Adopt.Adopted).document
        a.core.adopt(next.json)
        assertTrue(a.held(usd(1)))
        val gated = (world.change(b, owner) { editBudget(it, "day") { x -> withGate(x, agentsMayChange = false) } } as RuleDocuments.Adopt.Adopted).document
        a.core.adopt(gated.json)
        assertEquals(usd(90), a.portion("day"))
        assertTrue(a.events.none { it.name == SpendEvent.OVERSHOOT })
    }

    @Test fun aCutOfThisDevicesHeadroomDropsTheUnspentPartOfWhatDrewOnIt() {
        budget(ceiling = 150, headA = 50)
        world.unlink("install-a", "install-b")
        // On B the owner lowers the ceiling to $120 and A's balance to $20. A, apart, raises by its old $50.
        val cut = (world.change(b, owner) { editBudget(it, "day") { x -> withGate(x, ceiling = usd(120)) + ("headroom" to mapOf("cloud" to 0L, "devices" to mapOf("install-a" to usd(20)))) } } as RuleDocuments.Adopt.Adopted).document
        assertTrue(raise(a, 50) is Increments.Made.Signed)
        assertTrue(a.held(usd(55)))
        world.link("install-a", "install-b")
        a.core.adopt(cut.json)
        // The most that can be counted this way is the balance in the document the device held; here $5 of it was spent.
        assertEquals(usd(55), a.portion("day"))
        assertEquals("headroom_cut", a.events.single { it.name == SpendEvent.OVERSHOOT }.detail["cause"])
        assertEquals(usd(15), a.headroom("day"))
    }

    // ---- the held allowance ----

    private fun slice(seq: Long, dollars: Long, d: SpendingWorld.Device = a, validForS: Long? = 3600, notAfter: Long? = null, period: String? = null, edit: (Map<String, Any?>) -> Map<String, Any?> = { it }): String {
        val doc = d.core.document()!!
        val body = edit(
            mapOf(
                "v" to "allowance/1", "id" to "slice-$seq", "issuer" to "cloud", "key" to "allowance-1", "account" to "acct-1", "device" to d.name, "instrument" to "sandbox", "source" to "key-1",
                "lines" to listOf(mapOf("rule" to "day", "period" to (period ?: PeriodClock.at("day", "UTC", d.clock.wall).id), "amount" to mapOf("unit" to "USD-6", "atoms" to usd(dollars)))),
                "rules_generation" to doc.generation, "rules_digest" to doc.digest, "seq" to seq, "journal_epoch" to d.journal.head().epoch,
                "issued_at" to d.clock.wall, "valid_for_s" to validForS, "not_after" to notAfter,
            ).filterValues { it != null },
        )
        return Canonical.encode(body + ("signature" to Signed.encode(world.signAsCloud(Signed.bytes(Signed.ALLOWANCE, body)))))
    }

    private fun accept(json: String, d: SpendingWorld.Device = a, valueTransfer: Boolean = false) = d.core.allowances.accept(d.journal, json, valueTransfer)
    private fun ignored(why: Why, r: AllowanceStore.Result) = assertEquals(AllowanceStore.Result.Ignored(why), r)

    @Test fun aStandingShareIsItsOwnAllowanceAndTheHeadCarriesItsSequence() {
        budget()
        assertTrue(a.held(usd(10)))
        val first = a.journal.entries().single { it.kind == "allowance" }.tree.map("allowance")!!
        assertEquals("rules", first.str("issuer")); assertEquals(1L, first.long("seq")); assertEquals("install-a", first.str("device")); assertEquals("key-1", first.str("source"))
        assertEquals(a.core.document()!!.digest, first.str("rules_digest"))
        assertEquals(listOf(mapOf("rule" to "day", "period" to "day:2026-11-11", "amount" to mapOf("unit" to "USD-6", "atoms" to usd(50)))), first.list("lines"))
        assertNull("nothing expires before the period does", first["valid_for_s"])
        // Unchanged, it is not written again; a new document, a new line or a new period is a new one with the next seq.
        assertTrue(a.held(usd(1)))
        assertEquals(1, a.journal.entries().count { it.kind == "allowance" })
        raise(a, 5)
        assertTrue(a.held(usd(1)))
        world.advance(DAY)
        assertTrue(a.held(usd(1)))
        assertEquals(listOf(1L, 2L, 3L), a.journal.entries().filter { it.kind == "allowance" }.map { it.tree.map("allowance")!!.long("seq") })
        // Each hold names the allowance it was made under.
        val last = a.journal.entries().last { it.kind == "spend" }.tree.str("allowance")
        assertEquals(a.journal.entries().last { it.kind == "allowance" }.tree.map("allowance")!!.str("id"), last)
        a.restartApp(); assertNull(a.journal.reset)
    }

    @Test fun everyCloudSliceIsRefusedWhileTheShippedKeyIsEmpty() {
        budget(kind = "leased")
        assertEquals(0, CloudAllowanceKey.PUBLIC.size)
        val shipped = AllowanceStore(a.core.rules)
        assertEquals(AllowanceStore.Result.Ignored(Why.NO_CLOUD_KEY), shipped.accept(a.journal, slice(1, 10), false))
        // The public way to build the core has no place for a key at all.
        val asShipped = SpendingCore(a.journal, a.signer, a.book, a.env)
        assertEquals(AllowanceStore.Result.Ignored(Why.NO_CLOUD_KEY), asShipped.allowances.accept(a.journal, slice(1, 10), false))
        assertFalse(CloudAllowanceKey.verify(ByteArray(0), ByteArray(1), ByteArray(64)))
        // So a leased share has no slice and spends nothing.
        assertEquals(SpendRefusal(Code.ALLOWANCE_EXHAUSTED, Reason.NO_ALLOWANCE, 8), a.refusal(1))
    }

    @Test fun aSliceIsCheckedBehindTheKey_signatureDeviceLinesReplayGenerationAndEpoch() {
        // With a test key in place of the shipped one, the checks behind it.
        budget(kind = "leased")
        ignored(Why.BAD_SIGNATURE, accept(Canonical.encode(Tree.parse(slice(1, 10)) + ("seq" to 2L))))
        ignored(Why.MALFORMED, accept("{}"))
        ignored(Why.NOT_THIS_DEVICE, accept(slice(1, 10) { it + ("device" to "install-b") }))
        ignored(Why.VALUE_TRANSFER, accept(slice(1, 10), valueTransfer = true))
        ignored(Why.OTHER_EPOCH, accept(slice(1, 10) { it + ("journal_epoch" to "00".repeat(16)) }))
        ignored(Why.WAITING_FOR_RULES, accept(slice(1, 10) { it + ("rules_generation" to 2L) }))
        // A device accepts a slice line only if it is at or below its portion: two devices each sent a slice for the whole budget spend their own portion and no more.
        ignored(Why.LINE_OVER_PORTION, accept(slice(1, 100)))
        ignored(Why.LINE_OVER_PORTION, accept(slice(1, 100, b), b))
        ignored(Why.LINE_OVER_PORTION, accept(slice(1, 10) { it + ("lines" to listOf(mapOf("rule" to "nope", "period" to "day:2026-11-11", "amount" to mapOf("unit" to "USD-6", "atoms" to 1L)))) }))
        assertEquals(AllowanceStore.Result.Accepted, accept(slice(1, 30)))
        assertTrue(a.held(usd(30)))
        assertEquals(SpendRefusal(Code.ALLOWANCE_EXHAUSTED, null, 8, PeriodClock.at("day", "UTC", world.start).endsMs), a.refusal(1))
        // Replay: a seq not above the one held is ignored, and the highest seq is part of the verified head.
        ignored(Why.REPLAYED, accept(slice(1, 50)))
        a.restartApp(); assertNull(a.journal.reset)
        ignored(Why.REPLAYED, accept(slice(1, 50)))
        // A second slice in the same period after the first is used up: spend under the first counts against it.
        assertEquals(AllowanceStore.Result.Accepted, accept(slice(2, 40)))
        assertTrue(a.held(usd(10)))
        assertEquals(Code.ALLOWANCE_EXHAUSTED, a.refusal(1).code)
        assertEquals(usd(40), a.used("day"))
        // A slice for an older rule generation is ignored.
        world.change(a, agent) { it + ("zone" to "UTC") }
        val old = slice(3, 50) { it + ("rules_generation" to 1L) }
        ignored(Why.OLDER_RULES, accept(old))
        // A slice is never taken for a share the document calls standing.
        world.change(a, owner) { editBudget(it, "day") { x -> x + ("devices" to mapOf("install-a" to mapOf("atoms" to usd(50), "kind" to "standing"), "install-b" to mapOf("atoms" to usd(50), "kind" to "leased"))) } }
        ignored(Why.NOT_A_LEASED_SHARE, accept(slice(3, 10)))
    }

    @Test fun aSliceExpiresByElapsedTimeAndMovingTheClockCanOnlyEndItEarly() {
        budget(kind = "leased")
        assertEquals(AllowanceStore.Result.Accepted, accept(slice(1, 50, validForS = 3600, notAfter = world.start + 2 * 3600_000L)))
        assertTrue(a.held(usd(1)))
        // Setting the wall clock back does not extend it: the running time decides.
        a.clock.wall -= 3 * 3600_000L
        a.clock.elapsed += 3600_000L - 1
        assertTrue(a.held(usd(1)))
        a.clock.elapsed += 1
        assertEquals(SpendRefusal(Code.ALLOWANCE_EXPIRED, null, 8), a.refusal(1))
        // It also stops at not_after on the wall clock, whichever is first.
        a.clock.wall = world.start; a.clock.elapsed = 50_000
        assertEquals(AllowanceStore.Result.Accepted, accept(slice(2, 50, validForS = 10 * 3600, notAfter = world.start + 60_000)))
        assertTrue(a.held(usd(1)))
        a.clock.advance(60_000)
        assertEquals(Code.ALLOWANCE_EXPIRED, a.refusal(1).code)
        // After a reboot the wall-clock gap counts, and a wall clock that went backwards ends the allowance.
        assertEquals(AllowanceStore.Result.Accepted, accept(slice(3, 50, validForS = 3600)))
        a.reboot(wallJump = 1800_000L); a.anchor()
        assertTrue(a.held(usd(1)))
        a.reboot(wallJump = 1800_000L); a.anchor()
        assertEquals(Code.ALLOWANCE_EXPIRED, a.refusal(1).code)
        assertEquals(AllowanceStore.Result.Accepted, accept(slice(4, 50, validForS = 3600)))
        a.reboot(wallJump = -1000L)
        assertEquals(Code.ALLOWANCE_EXPIRED, a.refusal(1).code)
        // A slice of another epoch is void after the journal is replaced.
        val doc = a.core.document()!!
        val before = slice(5, 50)
        a.loseJournalFile(); a.core.adopt(doc.json)
        ignored(Why.OTHER_EPOCH, accept(before))
    }

    /** An increment signed by hand with [by]'s install key, saying whatever it likes about itself. */
    private fun forgedIncrement(by: SpendingWorld.Device, dollars: Long, counter: Long, actorKind: String, draws: Boolean, lasts: String = "standing"): String {
        val doc = by.core.document()!!
        val epoch = by.journal.head().epoch
        val body = LinkedHashMap<String, Any?>()
        body["v"] = "increment/1"; body["id"] = Signed.id(Signed.INCREMENT_ID, by.name, "sandbox", epoch, counter.toString()); body["counter"] = counter
        body["namespace"] = "sandbox"; body["budget"] = "day"; body["parent"] = mapOf("generation" to doc.generation, "digest" to doc.digest)
        body["amount"] = mapOf("unit" to "USD-6", "atoms" to usd(dollars)); body["portion"] = by.name; body["lasts"] = lasts
        body["period"] = PeriodClock.at("day", "UTC", by.clock.wall).id; body["draws_headroom"] = draws; body["journal"] = mapOf("epoch" to epoch)
        body["actor"] = ActorRecord.over(Signed.INCREMENT, body, actorKind, if (actorKind == "owner") "owner" else "a1", "http", by.name).toMap()
        return Canonical.encode(body + ("signature" to Signed.encode(world.signAs(by.name, Signed.bytes(Signed.INCREMENT, body)))))
    }

    @Test fun aReceivedIncrementMeetsTheSameGateAndFundingAsOneMadeHere() {
        // The owner set a $150 ceiling: $25 is B's balance, $25 is in nobody's.
        budget(ceiling = 150, headB = 25)
        fun why(json: String) = a.core.increments.apply(json).let { assertEquals(Applied.REFUSED, it.result); it.why }
        // A linked install labels its raise "owner" and says it drew on nothing. The label is that install's word; the
        // funding is checked here: no more than the room no balance holds.
        assertEquals(Gates.Why.RAISE_THE_CEILING_FIRST.id, why(forgedIncrement(b, 1_000_000, 1, "owner", draws = false)))
        assertEquals(Gates.Why.RAISE_THE_CEILING_FIRST.id, why(forgedIncrement(b, 26, 1, "owner", draws = false)))
        // An agent's raise that says it drew on nothing, or drew more than its signer's balance.
        assertEquals(Gates.Why.OVER_CEILING.id, why(forgedIncrement(b, 10, 1, "agent", draws = false)))
        assertEquals(Gates.Why.OVER_CEILING.id, why(forgedIncrement(b, 26, 1, "agent", draws = true)))
        // An owner's raise that claims to have drawn headroom is not what the check arrives at either.
        assertEquals(Gates.Why.OVER_CEILING.id, why(forgedIncrement(b, 10, 1, "owner", draws = true)))
        assertEquals(0, a.journal.entries().count { it.kind == "increment" })
        // Nothing refused backs a transfer: B's room, as A sees it, is still its $50.
        assertEquals(usd(50), a.journal.transact { tx -> Portions.had(tx.state, a.core.rules.current(tx), "day", "install-b", "portion", null) })
        // What fits is applied: the agent's $25 from B's balance, the owner's $25 from the room no balance holds.
        assertEquals(Applied.APPLIED, a.core.increments.apply(forgedIncrement(b, 25, 1, "agent", draws = true)).result)
        assertEquals(Applied.APPLIED, a.core.increments.apply(forgedIncrement(b, 25, 2, "owner", draws = false)).result)
        assertEquals(Gates.Why.RAISE_THE_CEILING_FIRST.id, why(forgedIncrement(b, 1, 3, "owner", draws = false)))
        // Where the owner turned agents_may_change off, an agent's increment from another install is refused here too.
        world.change(a, owner) { editBudget(it, "day") { x -> withGate(x, agentsMayChange = false) } }
        assertEquals(Gates.Why.AGENTS_MAY_NOT_CHANGE.id, why(forgedIncrement(b, 1, 4, "agent", draws = true)))
    }
}
