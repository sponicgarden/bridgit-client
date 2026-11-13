package cc.bridgit.app.core.spending

/**
 * Raises (`increment/1`; spending authority G5.1 "Raises are increments"). Every raise of a budget, made
 * online or apart, and every top-up of one period, is the same signed thing.
 *
 * - **It raises one portion by exactly what it adds**: the signing device's. A standing increment adds its
 *   amount to the budget and to that portion; a top-up adds it for one period only.
 * - **Applied once.** Every device keeps the ids it has applied; a copy that comes back changes nothing.
 * - **Usable at once** on the signing device, with or without the cloud.
 * - **Under an agent ceiling, headroom is a balance.** An agent's increment is taken from the signing
 *   device's balance, online as well as apart; one larger than the balance is refused ([Gates.increment]).
 * - **A reduction the device had not seen.** When a document arrives that set an owner gate or cut this
 *   device's headroom, the unspent part of the increments made without knowing it is dropped, what was spent
 *   stays counted, and `spend.overshoot` names the cause ([reconcile]).
 *
 * Two things the design's `increment/1` block does not list are in the signed bytes here, because another
 * device cannot apply the increment without them: `period` (the period it was made in, which is the one a
 * top-up is for) and `draws_headroom`. Its id is SHA-256 over the signer's install id and counter **with
 * the namespace and the journal epoch**, as a transfer's is: a journal that was reset starts its counter
 * again, and without the epoch its new increments would repeat old ids and be dropped as copies.
 */
class Increments(
    private val journal: Journal, private val signer: SpendSigner, private val book: SignerBook, private val rules: RuleDocuments,
    private val events: (SpendEvent) -> Unit = {},
) {
    sealed class Made {
        data class Signed(val json: String, val id: String, val replayed: Boolean = false) : Made()
        data class Refused(val why: String, val verdict: Gates.Verdict? = null) : Made()
    }

    enum class Applied { APPLIED, ALREADY_APPLIED, WAITING, REFUSED }

    data class Result(val result: Applied, val why: String? = null)

    /**
     * Raises [budget] by [amount] on this device. [lasts] is `standing`, or `period` for a top-up of the current period.
     * [requestKey] is the caller's idempotency key, when a door has one. The increment it made is remembered in
     * the transaction that made it, so the same key gets that increment again ([Made.Signed.replayed]) and
     * never a second one, across a crash or a restart as well. The same key for a different raise is refused
     * (`request_key_reused`).
     */
    fun raise(budget: String, amount: Money, lasts: String, caller: Caller, capability: Boolean, requestKey: String? = null, payload: String? = null): Made {
        val made = journal.transact { tx ->
            val earlier = requestKey?.let(tx::remembered)
            if (earlier != null) Triple(null, null, earlier) else {
                val out = make(tx, budget, amount, lasts, caller, capability)
                if (requestKey != null) out.first?.let { tx.remember(requestKey, it.str("id")!!, payload) }
                Triple(out.first, out.second, null)
            }
        }
        made.third?.let { id ->
            val again = reissue(id) as? Made.Signed ?: return Made.Refused("no_such_increment")
            // A key stands for one raise. Used again for another budget, amount or lifetime, it gets neither the old answer nor a new raise.
            val i = Tree.parse(again.json)
            val same = i.str("budget") == budget && i.str("lasts") == lasts && i.map("amount")!!.let { it.str("unit") == amount.unit && it.long("atoms") == amount.atoms }
            return if (same) again.copy(replayed = true) else Made.Refused("request_key_reused")
        }
        val body = made.first ?: return made.second!!
        return Made.Signed(Canonical.encode(Signed.sign(Signed.INCREMENT, body, signer)), body.str("id")!!)
    }

    /** Signs again an increment this epoch's journal already holds: the same bytes, the same id. */
    fun reissue(id: String): Made {
        val body = journal.entries().firstOrNull { it.kind == "increment" && it.tree.map("increment")!!.let { i -> i.str("id") == id && i.str("portion") == signer.installId } }
            ?.tree?.map("increment") ?: return Made.Refused("no_such_increment")
        return Made.Signed(Canonical.encode(Signed.sign(Signed.INCREMENT, body, signer)), id)
    }

    /** Applies another device's increment: once. It changes that device's portion and the budget's total, never this one's portion. */
    fun apply(json: String): Result = journal.transact { tx -> apply(tx, json) }

    private fun make(tx: Journal.Tx, budget: String, amount: Money, lasts: String, caller: Caller, capability: Boolean): Pair<Map<String, Any?>?, Made?> {
        fun no(why: String, verdict: Gates.Verdict? = null) = null to Made.Refused(why, verdict)
        val doc = rules.current(tx) ?: return no("no_rule_document")
        val b = doc.budgets[budget] ?: return no("no_such_budget")
        val me = tx.install
        if (me !in b.devices) return no("not_an_executor")
        if (amount.unit != b.unit || amount.atoms <= 0) return no("amount")
        if (lasts != "period" && lasts != "standing") return no("lasts")
        PeriodClock.roll(tx, budget, b.period, doc.zone)
        val period = tx.state.periods[budget]?.period ?: return no("period_not_known")
        val verdict = Gates.increment(
            b, caller.kind, "install", Gates.Env(book.approverEnrolled(), capability), amount.atoms,
            Portions.headroom(tx.state, doc, budget, me, period), Portions.unallocated(tx.state, doc, budget, period),
        )
        if (verdict !is Gates.Verdict.Allowed) return no("gate", verdict)
        val counter = tx.state.incrementCounter + 1
        val body = LinkedHashMap<String, Any?>()
        body["v"] = VERSION
        body["id"] = Signed.id(Signed.INCREMENT_ID, me, tx.namespace.id, tx.state.epoch, counter.toString())
        body["counter"] = counter; body["namespace"] = tx.namespace.id; body["budget"] = budget
        body["parent"] = mapOf("generation" to doc.generation, "digest" to doc.digest)
        body["amount"] = Journal.moneyMap(amount)
        body["portion"] = me; body["lasts"] = lasts; body["period"] = period
        body["draws_headroom"] = verdict.drawsHeadroom
        body["journal"] = mapOf("epoch" to tx.state.epoch)
        body["actor"] = ActorRecord.over(Signed.INCREMENT, body, caller.kind, caller.id, caller.door, me).toMap()
        tx.append(mapOf("kind" to "increment", "increment" to body))
        return body to null
    }

    internal fun apply(tx: Journal.Tx, json: String): Result {
        val i = runCatching { Tree.parse(json) }.getOrNull()?.takeIf(::wellFormed) ?: return Result(Applied.REFUSED, "malformed")
        val by = i.str("portion")!!
        val id = i.str("id")!!
        if (i.str("namespace") != tx.namespace.id) return Result(Applied.REFUSED, "namespace")
        if (id in tx.state.applied) return Result(Applied.ALREADY_APPLIED)
        val accepted = by != tx.install && book.standing("install", by) == SignerBook.Standing.OWN_LINK && ActorRecord.matches(Signed.INCREMENT, i) &&
            Signed.verified(Signed.INCREMENT, i, book, "install", by)
        if (!accepted) {
            events(SpendEvent(SpendEvent.SECURITY, mapOf("what" to "increment_signer", "signer" to by)))
            return Result(Applied.REFUSED, "signer_not_accepted")
        }
        if (id != Signed.id(Signed.INCREMENT_ID, by, tx.namespace.id, i.map("journal")!!.str("epoch")!!, i.long("counter").toString())) return Result(Applied.REFUSED, "id")
        val parent = i.map("parent")!!.str("digest")!!
        rules.get(tx, parent) ?: run { tx.park(id, "increment", parent, json); return Result(Applied.WAITING) }
        val doc = rules.current(tx) ?: return Result(Applied.REFUSED, "no_rule_document")
        // Made against a budget a merge removed: dropped. What its signer spent stays counted in its own journal.
        val b = doc.budgets[i.str("budget")] ?: return Result(Applied.REFUSED, "no_such_budget")
        if (by !in b.devices || i.map("amount")!!.str("unit") != b.unit) return Result(Applied.REFUSED, "not_an_executor")
        tx.state.recovered[by]?.let { if (i.map("journal")!!.str("epoch") != it) return Result(Applied.REFUSED, "sender_recovered") }
        // The same gate and the same funding as a raise made here, by what this device holds: its signer's headroom
        // balance for a raise under the ceiling, the room no balance holds for the owner's. What the increment says
        // about itself (who made it, what it drew on) has to be what this check arrives at.
        val period = i.str("period")!!
        val actor = ActorRecord.of(i.map("actor")!!)
        if (actor.install != by) return Result(Applied.REFUSED, "signer_not_accepted")
        val verdict = Gates.increment(
            b, actor.kind, "install", Gates.Env(book.approverEnrolled(), true), i.map("amount")!!.long("atoms")!!,
            Portions.had(tx.state, doc, b.id, by, "headroom", period), Portions.unallocated(tx.state, doc, b.id, period),
        )
        if (verdict !is Gates.Verdict.Allowed) return Result(Applied.REFUSED, (verdict as? Gates.Verdict.Refused)?.why?.id ?: "owner_gate")
        if (verdict.drawsHeadroom != i.flag("draws_headroom")) return Result(Applied.REFUSED, Gates.Why.OVER_CEILING.id)
        tx.append(mapOf("kind" to "increment", "increment" to i))
        return Result(Applied.APPLIED)
    }

    /**
     * Runs when [now] became the current document. Of this install's own increments:
     * - those made since it last took up documents, by an agent, in a budget where [now] has the owner's
     *   `agents_may_change` off and the document they were made against did not: void;
     * - while this install's headroom balance under [now] is below zero (the ceiling was lowered or its
     *   balance cut): the newest that drew on it, until it is not.
     * A void increment keeps what was already spent on it in the current period and loses the rest.
     */
    internal fun reconcile(tx: Journal.Tx, now: RuleDocument) {
        val me = tx.install
        fun own() = tx.state.moves.filter { it.dir == "inc" && it.peer == me && !it.voided }
        for (m in own().filter { it.id in tx.state.fresh && !it.owner }) {
            val b = now.budgets[m.budget] ?: continue
            if (b.agentsMayChange || !b.ownerSet) continue
            val against = parentOf(tx, m.id)?.budgets?.get(m.budget)
            if (against == null || against.agentsMayChange) void(tx, now, m, "owner_gate")
        }
        for (b in now.budgets.values) {
            if (b.ceiling == null || me !in b.devices) continue
            val period = tx.state.periods[b.id]?.period ?: continue
            while (raw(tx.state, b, me, period) < 0) void(tx, now, own().lastOrNull { it.budget == b.id && it.agent } ?: break, "headroom_cut")
        }
    }

    private fun raw(state: SpendState, b: RuleDocument.Budget, me: String, period: String): Long {
        var sum = if (Portions.frozen(state, b, me)) 0 else b.headroom(me)
        for (m in state.moves) if (m.budget == b.id) when {
            m.kind == "headroom" && m.dir == "in" -> sum += m.amountIn(period)
            m.kind == "headroom" && m.dir == "out" -> sum -= m.debitIn(period)
            m.dir == "inc" && m.peer == me && m.agent -> sum -= m.amountIn(period)
        }
        return sum
    }

    private fun parentOf(tx: Journal.Tx, id: String): RuleDocument? =
        journal.entries().firstOrNull { it.kind == "increment" && it.tree.map("increment")!!.str("id") == id }?.tree?.map("increment")?.map("parent")?.str("digest")?.let { rules.get(tx, it) }

    private fun void(tx: Journal.Tx, doc: RuleDocument, m: Move, why: String) {
        val p = tx.state.periods[m.budget]?.takeIf { !it.full }
        val part = p?.let { m.amountIn(it.period) } ?: 0
        val kept = if (p == null) 0 else (p.atoms - (Portions.portion(tx.state, doc, m.budget, tx.install, p.period) - part)).coerceIn(0, part)
        val fields = mutableMapOf<String, Any?>("kind" to "increment_void", "id" to m.id, "kept" to kept, "why" to why)
        p?.let { fields["period"] = it.period }
        tx.append(fields)
        events(SpendEvent(SpendEvent.OVERSHOOT, mapOf("cause" to why, "budget" to m.budget, "increment" to m.id, "counted" to kept.toString())))
    }

    companion object {
        const val VERSION = "increment/1"
        private val HASH = Regex("^[0-9a-f]{64}$")

        internal fun wellFormed(i: Map<String, Any?>): Boolean = runCatching {
            val amount = i.map("amount")!!
            val parent = i.map("parent")!!
            i.str("v") == VERSION && HASH.matches(i.str("id")!!) && i.long("counter")!! >= 1 && HASH.matches(parent.str("digest")!!) && parent.long("generation")!! >= 1 &&
                Units.of(amount.str("unit")!!) != null && amount.long("atoms")!! > 0 && i.str("lasts") in setOf("period", "standing") &&
                listOf(i.str("budget")!!, i.str("portion")!!, i.str("period")!!, i.str("namespace")!!).all { Journal.text(it).isNotEmpty() } &&
                i["draws_headroom"] is Boolean && i.map("journal")!!.str("epoch")!!.length == 32 && i.map("actor") != null
        }.getOrDefault(false)
    }
}
