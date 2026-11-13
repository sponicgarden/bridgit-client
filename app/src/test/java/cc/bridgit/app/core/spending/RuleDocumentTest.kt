package cc.bridgit.app.core.spending

import android.app.Application
import cc.bridgit.app.core.spending.RuleDocuments.Adopt
import cc.bridgit.app.core.spending.SpendingWorld.Companion.editBudget
import cc.bridgit.app.core.spending.SpendingWorld.Companion.withGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The owner rule document: what is valid, who signs, the pure-reduction check, the three-way merge and the change gates (G6.1, G6.2, G5.1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RuleDocumentTest {
    private val world = SpendingWorld(RuntimeEnvironment.getApplication())
    private val a = world.device("install-a")
    private val b = world.device("install-b")
    private val agent = world.agent
    private val owner = world.owner

    @After fun close() = world.close()

    private fun usd(d: Long) = world.usd(d)
    private fun budget(amount: Long = 100, shareA: Long = 60, shareB: Long = 40, ceiling: Long? = null, headA: Long = 0, headB: Long = 0, agentsMayChange: Boolean = true) =
        RuleDocument.budget("account", "acct-1", "day", "USD-6", usd(amount), mapOf("install-a" to usd(shareA), "install-b" to usd(shareB)), ceiling = ceiling?.let(::usd),
            headroom = mapOf("install-a" to usd(headA), "install-b" to usd(headB)).filterValues { it > 0 }, agentsMayChange = agentsMayChange)

    private fun found(by: SpendingWorld.Device = a, caller: Caller = owner, b: Map<String, Any?> = budget()) = world.found(by, mapOf("day" to b), caller)
    private fun refused(r: Adopt) = (r as Adopt.Refused)
    private fun adopted(r: Adopt) = (r as Adopt.Adopted)

    /** A document signed by hand: [by]'s install key, or an approver's, over whatever [body] says. */
    private fun signed(parent: RuleDocument?, content: Map<String, Any?>, by: String, actorKind: String = "agent", approver: String? = null, generation: Long? = null, signedBy: Pair<String, String>? = null): String {
        val body = HashMap<String, Any?>(content.filterKeys { it in RuleDocument.CONTENT })
        body["v"] = "rules/1"; body["account"] = "acct-1"; body["namespace"] = "sandbox"
        body["generation"] = generation ?: ((parent?.generation ?: 0) + 1); body["parents"] = listOfNotNull(parent?.digest)
        body["executor"] = by
        body["signed_by"] = signedBy?.let { mapOf("kind" to it.first, "id" to it.second) } ?: if (approver != null) mapOf("kind" to "approver", "id" to approver) else mapOf("kind" to "install", "id" to by)
        body["actor"] = ActorRecord.over(Signed.RULES, body, actorKind, if (actorKind == "owner") "owner" else "a1", "http", by).toMap()
        val sig = if (signedBy != null) world.signAsCloud(Signed.bytes(Signed.RULES, body)) else if (approver != null) world.signAsApprover(approver, Signed.bytes(Signed.RULES, body)) else world.signAs(by, Signed.bytes(Signed.RULES, body))
        return Canonical.encode(body + ("signature" to Signed.encode(sig)))
    }

    private fun content(doc: RuleDocument) = doc.map.filterKeys { it in RuleDocument.CONTENT }

    // ---- what a document is ----

    @Test fun theFirstDocumentIsSignedByTheInstallThatTookItWithASignedActor() {
        found()
        val doc = a.core.document()!!
        assertEquals(1L, doc.generation); assertTrue(doc.parents.isEmpty()); assertNull(doc.problem())
        assertEquals("install-a", doc.executor); assertEquals("install", doc.signerKind); assertEquals("install-a", doc.signerId)
        val actor = doc.actor!!
        assertEquals(ActorRecord("owner", "owner", "app", "install-a", actor.change), actor)
        assertTrue("the actor record names this very change", ActorRecord.matches(Signed.RULES, doc.map))
        assertFalse(ActorRecord.matches(Signed.RULES, doc.map + ("zone" to "Europe/Warsaw")))
        assertEquals("the other device holds the same document", doc.digest, b.core.document()!!.digest)
        assertEquals("the digest is over the canonical form without the signature", Signed.digest(Signed.RULES, doc.map - "signature"), doc.digest)
        assertEquals(doc.digest, RuleDocument.parse(doc.json).digest)
        // The head names it, and a restart finds it again.
        b.restartApp()
        assertNull(b.journal.reset)
        assertEquals(doc.digest, b.core.document()!!.digest)
        assertEquals(usd(40), b.portion("day"))
    }

    @Test fun portionsAddUpToAtMostTheBudgetAndHeadroomToTheRoomUnderTheCeiling() {
        fun problem(b: Map<String, Any?>) = refused(a.core.change(owner, true, null, "acct-1") { it + ("budgets" to mapOf("day" to b)) }).why
        assertEquals("portions_over_budget:day", problem(budget(100, 60, 41)))
        assertEquals("portions_over_budget:day", problem(RuleDocument.budget("account", "acct-1", "day", "USD-6", usd(100), mapOf("install-a" to usd(60)), cloud = usd(41))))
        assertEquals("ceiling_below_budget:day", problem(budget(ceiling = 99)))
        assertEquals("headroom_over_ceiling:day", problem(budget(ceiling = 150, headA = 30, headB = 21)))
        assertEquals("headroom_over_ceiling:day", problem(budget(headA = 1)))
        assertEquals("budget_shape:day", problem(budget() + ("period" to "fortnight")))
        assertEquals("negative:day", problem(budget() + ("amount" to -1L)))
        assertEquals("unknown_field:day", problem(budget() + ("reverts_to" to "install-b")))
        assertNull(a.core.document())
        // At the sums exactly, it is valid.
        assertTrue(a.core.change(owner, true, null, "acct-1") { it + ("budgets" to mapOf("day" to budget(100, 60, 40, ceiling = 150, headA = 25, headB = 25))) } is Adopt.Adopted)
    }

    @Test fun aChangeNamesTheGenerationItReplaces() {
        val first = found()
        val second = adopted(world.change(a, agent) { it + ("zone" to "Europe/Warsaw") })
        assertEquals(2L, second.document.generation); assertEquals(listOf(first), second.document.parents)
        // A change against the older generation is refused with conflict.
        assertEquals("conflict", refused(a.core.change(agent, true, first, "acct-1") { it + ("zone" to "UTC") }).why)
        assertEquals("conflict", refused(a.core.change(agent, true, null, "acct-1") { it }).why)
        // Taking the same document again changes nothing.
        assertEquals(Adopt.Known, a.core.adopt(second.document.json))
        assertTrue(b.core.adopt(second.document.json) is Adopt.Adopted)
        assertEquals(Adopt.Known, b.core.adopt(second.document.json))
    }

    @Test fun aSuccessorNeverRestatesAnAmountAPortionOrAHeadroomBalanceUpward() {
        found(b = budget(100, 60, 30, ceiling = 150, headA = 20, headB = 20))
        fun why(f: (Map<String, Any?>) -> Map<String, Any?>) = refused(world.change(a, owner) { editBudget(it, "day", f) }).why
        fun devices(x: Map<String, Any?>, install: String, atoms: Long) = x + ("devices" to (x.map("devices")!! + (install to mapOf("atoms" to atoms, "kind" to "standing"))))
        fun headroom(x: Map<String, Any?>, install: String, atoms: Long) = x + ("headroom" to mapOf("cloud" to 0L, "devices" to (x.map("headroom")!!.map("devices")!! + (install to atoms))))
        assertEquals("amount_restated_up:day", why { it + ("amount" to usd(101)) })
        assertEquals("portion_restated_up:day", why { devices(it, "install-b", usd(31)) })
        assertEquals("portion_restated_up:day", why { it + ("cloud" to usd(10)) })
        assertEquals("portion_restated_up:day", why { devices(it, "install-c", usd(1)) })
        assertEquals("headroom_restated_up:day", why { headroom(it, "install-a", usd(21)) })
        // A share moved in a document, 60/30 to 0/90, is the same thing: it moves by a transfer or not at all.
        assertEquals("portion_restated_up:day", why { devices(devices(it, "install-a", 0), "install-b", usd(90)) })
        // Lowering, and a new share that starts at 0, are fine. The owner can raise the ceiling: that is a gate, not an amount.
        assertTrue(world.change(a, owner) { editBudget(it, "day") { x -> withGate(devices(devices(x, "install-b", usd(10)), "install-c", 0), ceiling = usd(200)) + ("amount" to usd(90)) } } is Adopt.Adopted)
        val now = a.core.document()!!.budgets.getValue("day")
        assertEquals(usd(90), now.amount); assertEquals(usd(10), now.devices["install-b"]); assertEquals(0L, now.devices["install-c"]); assertEquals(usd(200), now.ceiling)
    }

    // ---- which signers a device accepts ----

    @Test fun aDocumentIsTakenOnlyFromItsOwnKeyAnApproverKeyOrAnOwnTierLink() {
        found()
        val doc = a.core.document()!!
        val next = content(doc) + ("zone" to "Europe/Warsaw")
        // An own-tier link's install key.
        assertTrue(b.core.adopt(signed(doc, next, "install-a")) is Adopt.Adopted)
        // An install with no such link (an associate or view-only link signs nothing here): ignored and logged.
        val stranger = world.device("install-x", linked = false)
        val fromX = signed(b.core.document(), content(b.core.document()!!) + ("zone" to "UTC"), "install-x")
        assertEquals("signer_not_accepted", refused(b.core.adopt(fromX)).why)
        assertTrue(b.events.any { it.name == SpendEvent.SECURITY && it.detail["signer"] == "install-x" })
        assertEquals("the stranger is another device", "install-x", stranger.name)
        // Signed by one install in another's name, a changed body, a missing or foreign actor record.
        val cur = b.core.document()!!
        val honest = signed(cur, content(cur) + ("zone" to "UTC"), "install-a")
        val inAnothersName = Tree.parse(honest) + ("executor" to "install-b")
        assertEquals("the executor is under the actor record", "unsigned", refused(b.core.adopt(Canonical.encode(inAnothersName))).why)
        assertEquals("unsigned", refused(b.core.adopt(Canonical.encode(Tree.parse(honest) + ("zone" to "Asia/Tokyo")))).why)
        assertEquals("unsigned", refused(b.core.adopt(Canonical.encode(Tree.parse(honest) - "actor"))).why)
        assertEquals("signer_not_accepted", refused(b.core.adopt(Canonical.encode(Tree.parse(honest) + ("signature" to "AAAA")))).why)
        // An approver key the device holds from a verified enrolment.
        assertTrue(b.core.adopt(signed(cur, content(cur) + ("zone" to "UTC"), "install-a", actorKind = "owner", approver = "approver-1")) is Adopt.Adopted)
        val now = b.core.document()!!
        assertEquals("signer_not_accepted", refused(b.core.adopt(signed(now, content(now) + ("zone" to "Asia/Tokyo"), "install-a", signedBy = "approver" to "nobody"))).why)
        // The cloud's key signs nothing a device takes in this build: a document under it is from no accepted signer, even a pure reduction.
        assertEquals("signer_not_accepted", refused(b.core.adopt(signed(now, content(now) + ("budgets" to emptyMap<String, Any?>()), "install-a", actorKind = "portal", signedBy = "cloud" to "allowance-1"))).why)
        assertEquals("signer_not_accepted", refused(b.core.adopt(signed(now, content(now) + ("zone" to "Asia/Tokyo"), "install-a", actorKind = "cloud", signedBy = "cloud" to "allowance-1"))).why)
        // A merged document is computed, never received.
        assertEquals("a_merge_is_computed_not_received", refused(b.core.adopt(Canonical.encode(Tree.parse(honest) - "signature" - "actor" + ("merged" to true) + ("parents" to listOf(cur.digest, doc.digest))))).why)
        // Another namespace's document, and a second first document.
        assertEquals("namespace", refused(b.core.adopt(Canonical.encode(Tree.parse(honest) + ("namespace" to "live")))).why)
        assertEquals("another_first_document", refused(b.core.adopt(signed(null, content(cur) + ("zone" to "Asia/Tokyo"), "install-a", actorKind = "owner"))).why)
    }

    @Test fun aDocumentWhoseParentIsNotHeldWaitsAndIsTakenUpWhenItArrives() {
        found()
        val second = adopted(a.core.change(agent, true, a.core.document()!!.digest, "acct-1") { it + ("zone" to "Europe/Warsaw") }).document
        val third = adopted(a.core.change(agent, true, second.digest, "acct-1") { editBudget(it, "day") { x -> x + ("rebalance" to false) } }).document
        assertEquals(Adopt.Waiting, b.core.adopt(third.json))
        assertEquals(1L, b.core.document()!!.generation)
        assertTrue(b.core.adopt(second.json) is Adopt.Adopted)
        assertEquals("what waited was taken up in the same transaction", third.digest, b.core.document()!!.digest)
        assertEquals(listOf("rules", "rules", "rules"), b.journal.entries().filter { it.kind == "rules" }.map { it.kind })
    }

    // ---- the pure-reduction check ----

    @Test fun aPureReductionIsCheckedFieldByFieldAgainstItsParent() {
        found(b = budget(100, 60, 40, ceiling = 150, headA = 25, headB = 25))
        val parent = a.core.document()!!
        fun child(f: (Map<String, Any?>) -> Map<String, Any?>) = RuleDocument(parent.map + f(content(parent)))
        fun raises(f: (Map<String, Any?>) -> Map<String, Any?>) = Reduction.raises(parent, child(f))
        fun inBudget(f: (Map<String, Any?>) -> Map<String, Any?>) = raises { editBudget(it, "day", f) }
        // A lower amount, a removed share, a switch to off, a gate turned on: only reduces.
        assertEquals(emptyList<String>(), inBudget { it + ("amount" to usd(90)) })
        assertEquals(emptyList<String>(), inBudget { it + ("devices" to (it.map("devices")!! - "install-b")) + ("headroom" to mapOf("cloud" to 0L, "devices" to mapOf("install-a" to usd(25)))) })
        assertEquals(emptyList<String>(), inBudget { withGate(it, agentsMayChange = false, ceiling = usd(120)) + ("rebalance" to false) })
        assertEquals(emptyList<String>(), raises { it + ("switches" to mapOf("paused" to true, "classes" to mapOf("metered" to false), "sources" to mapOf("key-1" to false))) })
        assertEquals(emptyList<String>(), raises { it + ("caps" to mapOf("instruments" to mapOf("sandbox" to mapOf("unit" to "USD-6", "atoms" to 5L)), "sources" to emptyMap<String, Any?>())) })
        assertEquals(emptyList<String>(), raises { it + ("budgets" to emptyMap<String, Any?>()) })
        // Anything that raises, moves or loosens is named.
        assertEquals(listOf("budgets/day/amount"), inBudget { it + ("amount" to usd(101)) })
        assertEquals(listOf("budgets/day/gate/ceiling"), inBudget { withGate(it, ceiling = usd(151)) })
        assertEquals(listOf("budgets/day/gate/ceiling"), inBudget { withGate(it, noCeiling = true) })
        assertEquals(listOf("budgets/day/devices/install-a/kind"), inBudget { it + ("devices" to (it.map("devices")!! + ("install-a" to mapOf("atoms" to usd(60), "kind" to "leased")))) })
        assertEquals(listOf("budgets/day/scope/id"), inBudget { it + ("scope" to mapOf("kind" to "account", "id" to "other")) })
        assertEquals(listOf("budgets/new"), raises { it + ("budgets" to (it.map("budgets")!! + ("new" to budget(1, 1, 0)))) })
        assertEquals(listOf("zone"), raises { it + ("zone" to "Europe/Warsaw") })
        val off = child { it + ("switches" to mapOf("paused" to true, "classes" to mapOf("metered" to false), "sources" to emptyMap<String, Any?>())) }
        val on = RuleDocument(off.map + ("switches" to mapOf("paused" to false, "classes" to emptyMap<String, Any?>(), "sources" to emptyMap<String, Any?>())))
        assertEquals(listOf("switches/classes/metered", "switches/paused"), Reduction.raises(off, on))
        val capped = child { it + ("caps" to mapOf("instruments" to mapOf("sandbox" to mapOf("unit" to "USD-6", "atoms" to 5L)), "sources" to emptyMap<String, Any?>())) }
        assertEquals(listOf("caps/instruments/sandbox"), Reduction.raises(capped, parent))
    }

    // ---- two successors of one parent ----

    private fun fork(left: (Map<String, Any?>) -> Map<String, Any?>, right: (Map<String, Any?>) -> Map<String, Any?>, leftBy: Caller = agent, rightBy: Caller = agent): Pair<Adopt.Adopted, Adopt.Adopted> {
        world.unlink("install-a", "install-b")
        val byA = adopted(world.change(a, leftBy, edit = left)).document
        val byB = adopted(world.change(b, rightBy, edit = right)).document
        world.link("install-a", "install-b")
        return adopted(a.core.adopt(byB.json)) to adopted(b.core.adopt(byA.json))
    }

    @Test fun twoSuccessorsThatChangedDifferentFieldsMergeWithNoPersonAndNoSignature() {
        found()
        val (atA, atB) = fork({ it + ("zone" to "Europe/Warsaw") }, { editBudget(it, "day") { x -> x + ("amount" to usd(90)) + ("devices" to (x.map("devices")!! + ("install-b" to mapOf("atoms" to usd(30), "kind" to "standing")))) } })
        val merged = atA.current
        assertEquals("byte for byte the same on both devices, whichever branch each held first", merged.json, atB.current.json)
        assertTrue(merged.merged); assertEquals(3L, merged.generation); assertEquals(2, merged.parents.size); assertEquals(merged.parents.sorted(), merged.parents)
        assertNull(merged.map["signature"]); assertNull(merged.map["actor"]); assertNull(merged.problem())
        assertEquals("Europe/Warsaw", merged.zone); assertEquals(usd(90), merged.budgets.getValue("day").amount); assertEquals(usd(30), merged.budgets.getValue("day").devices["install-b"])
        assertEquals(emptyList<String>(), atA.forked)
        assertTrue(a.events.none { it.name == SpendEvent.RULE_FORK })
        // A later change names the merged document and every device that holds it accepts.
        val later = adopted(world.change(a, agent) { it + ("zone" to "UTC") })
        assertEquals(listOf(merged.digest), later.document.parents)
        assertTrue(b.core.adopt(later.document.json) is Adopt.Adopted)
        for (d in listOf(a, b)) { d.restartApp(); assertNull(d.journal.reset); assertEquals(later.document.digest, d.core.document()!!.digest) }
    }

    @Test fun theSameFieldChangedOnBothBranchesTakesTheTighterValueAndRaisesAFork() {
        found()
        val (atA, atB) = fork(
            { editBudget(it + ("zone" to "Europe/Warsaw"), "day") { x -> withGate(x, ceiling = usd(150)) + ("amount" to usd(90)) + ("devices" to (x.map("devices")!! + ("install-a" to mapOf("atoms" to usd(50), "kind" to "standing")))) } },
            { editBudget(it + ("zone" to "Asia/Tokyo"), "day") { x -> withGate(x, ceiling = usd(120), agentsMayChange = false) + ("amount" to usd(95)) + ("rebalance" to false) + ("devices" to (x.map("devices")!! + ("install-b" to mapOf("atoms" to usd(35), "kind" to "standing")))) } },
        )
        val day = atA.current.budgets.getValue("day")
        assertEquals(atA.current.json, atB.current.json)
        assertEquals("the lower amount", usd(90), day.amount)
        assertEquals("the lower ceiling", usd(120), day.ceiling)
        assertFalse("the gate on", day.agentsMayChange)
        assertFalse("the switch off, changed on one branch only", day.rebalance)
        assertEquals(usd(50), day.devices["install-a"]); assertEquals(usd(35), day.devices["install-b"])
        assertNull(atA.current.problem())
        assertEquals("a field with no order falls back to the parent's value", "UTC", atA.current.zone)
        assertEquals(setOf("zone", "budgets/day/amount", "budgets/day/gate/ceiling", "budgets/day/gate/set_by"), atA.forked.toSet())
        assertTrue(a.events.any { it.name == SpendEvent.RULE_FORK }); assertTrue(b.events.any { it.name == SpendEvent.RULE_FORK })
        // Any authorised agent can make the change again on the merged document.
        assertTrue(world.change(a, agent) { it + ("zone" to "Europe/Warsaw") } is Adopt.Adopted)
    }

    @Test fun aBudgetEitherBranchRemovedIsRemovedAndWhatWasSpentStaysCounted() {
        found()
        assertTrue(b.held(usd(5)))
        val (atA, atB) = fork({ it + ("budgets" to emptyMap<String, Any?>()) }, { editBudget(it, "day") { x -> x + ("amount" to usd(100)) + ("rebalance" to false) } })
        assertEquals(atA.current.json, atB.current.json)
        assertTrue(atA.current.budgets.isEmpty())
        assertEquals(SpendRefusal.Reason.NO_ALLOWANCE, b.refusal(1).reason)
        assertEquals(usd(5), (b.journal.usage("day", PeriodClock.at("day", "UTC", world.start).id) as Journal.Usage.Counted).amount.atoms)
    }

    @Test fun aGateLiftedWithAnOwnerAuthorizationOnOneBranchAndTightenedByAnAgentOnTheOther() {
        // The merge is a function of the documents alone, so this is checked on the function: one branch is signed by an approver key.
        found(caller = owner, b = budget(ceiling = 150, agentsMayChange = false))
        val parent = a.core.document()!!
        val gate = parent.budgets.getValue("day")
        assertTrue(gate.ownerSet)
        val lifted = RuleDocument.parse(signed(parent, editBudget(content(parent), "day") { x -> x + ("gate" to mapOf("agents_may_change" to true, "ceiling" to null, "set_by" to null)) }, "install-a", actorKind = "owner", approver = "approver-1"))
        val tightened = RuleDocument.parse(signed(parent, editBudget(content(parent), "day") { x -> x + ("gate" to mapOf("agents_may_change" to false, "ceiling" to usd(120), "set_by" to gate.setBy)) }, "install-b"))
        val authorized = { branch: RuleDocument, _: String -> branch.signerKind == "approver" }
        val merged = Merge.of(parent, lifted, tightened, authorized)
        assertEquals("the value that carries an owner authorization wins where exactly one does", null, merged.document.budgets.getValue("day").ceiling)
        assertTrue(merged.document.budgets.getValue("day").agentsMayChange)
        assertFalse(merged.document.budgets.getValue("day").gated)
        assertEquals(emptyList<String>(), merged.forked)
        assertEquals("the same either way round", merged.document.json, Merge.of(parent, tightened, lifted, authorized).document.json)
        // With no authorization on either, the tighter gate stands.
        val none = Merge.of(parent, lifted, tightened) { _, _ -> false }
        assertEquals(usd(120), none.document.budgets.getValue("day").ceiling)
        assertTrue(none.document.budgets.getValue("day").ownerSet)
        assertNotEquals(merged.document.digest, none.document.digest)
    }

    // ---- gates ----

    @Test fun agentsChangeBudgetsWithTheBudgetsCapabilityAndNoPerson() {
        // An agent makes the account's first budget, lowers it, removes a share and sets its own gate: all delegable.
        found(caller = agent)
        assertTrue(world.change(a, agent) { editBudget(it, "day") { x -> withGate(x, ceiling = usd(150)) + ("amount" to usd(100)) } } is Adopt.Adopted)
        val gated = a.core.document()!!.budgets.getValue("day")
        assertFalse("an agent's gate is its own restraint", gated.ownerSet)
        assertEquals("agent", Gates.gateStrength(gated, Gates.Env(false, true)))
        assertTrue("so any agent with the capability lifts it", world.change(a, Caller("agent", "a2", "mcp")) { editBudget(it, "day") { x -> withGate(x, noCeiling = true) } } is Adopt.Adopted)
        // Without the capability an agent changes no budget and raises no cap, but may still lower a cap or turn spending off.
        val r = refused(world.change(a, agent, capability = false) { editBudget(it, "day") { x -> x + ("rebalance" to false) } })
        assertEquals(Gates.Verdict.Refused(Gates.Why.BUDGETS_CAPABILITY_REQUIRED, "budgets/day"), r.verdict)
        assertTrue(world.change(a, agent, capability = false) { it + ("caps" to mapOf("instruments" to mapOf("sandbox" to mapOf("unit" to "USD-6", "atoms" to 5L)), "sources" to emptyMap<String, Any?>())) } is Adopt.Adopted)
        assertEquals(Gates.Why.BUDGETS_CAPABILITY_REQUIRED, (refused(world.change(a, agent, capability = false) { it + ("caps" to mapOf("instruments" to emptyMap<String, Any?>(), "sources" to emptyMap<String, Any?>())) }).verdict as Gates.Verdict.Refused).why)
        assertTrue(world.change(a, agent, capability = false) { it + ("switches" to mapOf("paused" to true, "classes" to emptyMap<String, Any?>(), "sources" to emptyMap<String, Any?>())) } is Adopt.Adopted)
    }

    @Test fun theOwnersGateBindsAgentsAndOnlyTheOwnerLiftsIt_onGrantsWithNoApprover() {
        found()
        // The owner sets the gate in the app.
        assertTrue(world.change(a, owner) { editBudget(it, "day") { x -> withGate(x, agentsMayChange = false, ceiling = usd(150)) } } is Adopt.Adopted)
        val day = a.core.document()!!.budgets.getValue("day")
        assertTrue(day.ownerSet); assertEquals("owner", day.setBy!!.str("kind"))
        assertEquals("nobody takes it for a key-enforced gate", "grants", Gates.gateStrength(day, Gates.Env(false, true)))
        // An agent cannot lift it, and with no approver there is nobody to ask but the owner: no_approver with the remaining human step.
        val lift = refused(world.change(a, agent) { editBudget(it, "day") { x -> withGate(x, agentsMayChange = true) } }).verdict
        assertTrue(lift is Gates.Verdict.NoApprover && lift.remainingHumanStep.contains("owner"))
        assertTrue(refused(world.change(a, agent) { editBudget(it, "day") { x -> withGate(x, ceiling = usd(160)) } }).verdict is Gates.Verdict.NoApprover)
        // With agents_may_change off an agent changes nothing else about the budget either...
        assertEquals(Gates.Verdict.Refused(Gates.Why.AGENTS_MAY_NOT_CHANGE, "budgets/day"), refused(world.change(a, agent) { editBudget(it, "day") { x -> x + ("rebalance" to false) } }).verdict)
        assertTrue("removing the budget would go around the gate", refused(world.change(a, agent) { it + ("budgets" to emptyMap<String, Any?>()) }).verdict is Gates.Verdict.NoApprover)
        // ...but anyone may tighten the gate, and tightening the owner's gate leaves it the owner's.
        assertTrue(world.change(a, agent) { editBudget(it, "day") { x -> withGate(x, ceiling = usd(120)) } } is Adopt.Adopted)
        val tighter = a.core.document()!!.budgets.getValue("day")
        assertEquals(usd(120), tighter.ceiling); assertTrue(tighter.ownerSet); assertEquals(day.setBy, tighter.setBy)
        // Nobody can write another name into set_by.
        val cur = a.core.document()!!
        val renamed = editBudget(content(cur), "day") { x -> x + ("gate" to mapOf("agents_may_change" to false, "ceiling" to usd(120), "set_by" to mapOf("kind" to "agent", "id" to "a1", "door" to "http", "install" to "install-a", "authorized" to false))) }
        assertEquals("set_by:day", refused(a.core.adopt(signed(cur, renamed, "install-a"))).why)
        // The owner lifts it in the app.
        assertTrue(world.change(a, owner) { editBudget(it, "day") { x -> withGate(x, agentsMayChange = true, noCeiling = true) } } is Adopt.Adopted)
        assertNull(a.core.document()!!.budgets.getValue("day").setBy)
        assertTrue(world.change(a, agent) { editBudget(it, "day") { x -> x + ("rebalance" to false) } } is Adopt.Adopted)
    }

    @Test fun nothingGoesAroundTheOwnersCeiling() {
        // The owner set a ceiling and left agents_may_change on: agents may still lower the budget and tighten the gate.
        found(caller = owner, b = budget(ceiling = 150, headA = 25, headB = 25))
        assertTrue(a.core.document()!!.budgets.getValue("day").ownerSet)
        fun verdict(f: (Map<String, Any?>) -> Map<String, Any?>) = refused(world.change(a, agent, edit = f)).verdict
        // Removing the budget (so the same id could come back ungated in the next document),
        assertTrue(verdict { it + ("budgets" to emptyMap<String, Any?>()) } is Gates.Verdict.NoApprover)
        // changing its scope so that it covers nothing, or its period from a day to an hour,
        assertTrue(verdict { editBudget(it, "day") { x -> x + ("scope" to mapOf("kind" to "applet", "id" to "nothing")) } } is Gates.Verdict.NoApprover)
        assertTrue(verdict { editBudget(it, "day") { x -> x + ("period" to "hour") } } is Gates.Verdict.NoApprover)
        // or turning a standing share into a leased one, is refused like loosening the gate itself.
        assertTrue(verdict { editBudget(it, "day") { x -> x + ("devices" to (x.map("devices")!! + ("install-a" to mapOf("atoms" to usd(60), "kind" to "leased")))) } } is Gates.Verdict.NoApprover)
        assertEquals(1L, a.core.document()!!.generation)
        // What only reduces is still any agent's to do, and the owner does any of it in the app.
        assertTrue(world.change(a, agent) { editBudget(it, "day") { x -> withGate(x, agentsMayChange = false) + ("rebalance" to false) } } is Adopt.Adopted)
        assertTrue(a.core.document()!!.budgets.getValue("day").ownerSet)
        assertTrue(world.change(a, owner) { editBudget(it, "day") { x -> x + ("period" to "hour") } } is Adopt.Adopted)
        assertTrue(world.change(a, owner) { it + ("budgets" to emptyMap<String, Any?>()) } is Adopt.Adopted)
        // Enrolling an approver changes none of this while the approver path is off: an agent is still stopped, the owner is not.
        world.change(a, owner) { it + ("budgets" to mapOf("day" to budget(ceiling = 150))) }
        world.approverEnrolled = true
        assertTrue(refused(world.change(a, agent) { it + ("budgets" to emptyMap<String, Any?>()) }).verdict is Gates.Verdict.NoApprover)
        assertTrue(world.change(a, owner) { it + ("budgets" to emptyMap<String, Any?>()) } is Adopt.Adopted)
    }

    @Test fun whileTheApproverPathIsOffEnrollingAnApproverChangesNothing_theGateStaysOnGrants() {
        found()
        world.change(a, owner) { editBudget(it, "day") { x -> withGate(x, agentsMayChange = false) } }
        world.share(a.core.document()!!, except = a)
        assertFalse("off until the owner answers Q3; its stated fallback is grants on every device", Gates.SPEND_SETTING_APPROVER_PATH)
        world.approverEnrolled = true
        // The read says grants, with or without an approver enrolled.
        val gated = a.core.document()!!.budgets.getValue("day")
        assertEquals("grants", Gates.gateStrength(gated, Gates.Env(true, true)))
        assertEquals("grants", Gates.gateStrength(gated, Gates.Env(false, true)))
        assertNull(Gates.gateStrength(RuleDocument.Budget("x", budget()), Gates.Env(true, true)))
        // An agent still cannot lift the owner's gate: no_approver, with the remaining human step.
        val agents = refused(world.change(a, agent) { editBudget(it, "day") { x -> withGate(x, agentsMayChange = true) } }).verdict
        assertTrue(agents is Gates.Verdict.NoApprover)
        // The owner still lifts it in the app, exactly as before the approver was enrolled,
        val lifted = adopted(world.change(a, owner) { editBudget(it, "day") { x -> withGate(x, agentsMayChange = true) } })
        // and the other device takes that document from its own-tier link.
        assertTrue(b.core.adopt(lifted.document.json) is Adopt.Adopted)
        assertTrue(b.core.document()!!.budgets.getValue("day").agentsMayChange)
        // An approver's signature is an accepted signer and no more: it is not an owner authorization while the path is off.
        world.change(a, owner) { editBudget(it, "day") { x -> withGate(x, agentsMayChange = false) } }
        val cur = a.core.document()!!
        val byApprover = editBudget(content(cur), "day") { x -> x + ("gate" to mapOf("agents_may_change" to true, "ceiling" to null, "set_by" to null)) }
        assertTrue(refused(a.core.adopt(signed(cur, byApprover, "install-a", actorKind = "agent", approver = "approver-1"))).verdict is Gates.Verdict.NoApprover)
        assertTrue(a.core.adopt(signed(cur, byApprover, "install-a", actorKind = "owner", approver = "approver-1")) is Adopt.Adopted)
    }
}
