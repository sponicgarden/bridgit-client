package cc.bridgit.app.core.spending

/**
 * The rules a spend passes before it is held (spending authority G6), for the device-held column of G7. One
 * rule set for every instrument; a spend passes every rule that applies to it, **in this order, and the
 * first failure decides**:
 *
 * 1. Switches: the pause of outside calls, spending off for the instrument class or the source, payments off.
 * 2. Who may spend: any agent or applet the device already lets act; a value transfer needs `payments.request`.
 * 3. Allow-lists: the source is linked; the model or route is allowed.
 * 4. The per-spend cap.
 * 5. Bridgit-funded caps, as mirrored from the cloud (Bridgit-funded sources only).
 * 6. Owner budgets: every budget whose scope the spend falls in, against this device's portion for the period.
 * 7. Rate limits: own-key calls per hour, from the journal.
 * 8. The held allowance: the period is open, the share is not frozen or expired, and every line has room.
 *
 * **The check and the hold are one storage transaction** ([Journal.transact]): the periods that may open are
 * opened, the rules run on the head as it stands, each line's limit is computed from the rule document and
 * the journal's own record, and the hold entry commits with all of it or nothing does.
 *
 * Rules 9 and 10 (the no-approval threshold and the owner authorization) are for value transfer only and are
 * not built: a value-transfer spend that passes rules 1 to 8 is refused here, closed. So is a spend whose
 * source the cloud holds: that hold is the cloud's.
 *
 * A request the journal already knows gets the same answer as before, whatever the rules say now.
 */
class RuleEngine(private val journal: Journal, private val rules: RuleDocuments, private val allowances: AllowanceStore, private val env: Environment) {
    /** What the engine asks the rest of the device. Every answer is local; nothing here reaches a network. */
    interface Environment {
        /** False only when the device knows it reaches nothing (G7.2): paid work is refused at once, and not as a budget refusal. */
        fun online(): Boolean

        /** The device's global pause of outside calls. */
        fun outsideCallsPaused(): Boolean

        /** Payments on or off (§9). */
        fun paymentsOn(): Boolean

        /** A linked source, or null. */
        fun source(id: String): Source?

        /** Whether the device lets this principal act at all. No new grant for metered spend. */
        fun mayAct(principalKind: String, principalId: String): Boolean
        fun grants(principalKind: String, principalId: String): Set<String>

        /** Whether [route] (a model or route id; null when the spend names none) is allowed on [source]. */
        fun routeAllowed(source: Source, route: String?): Boolean

        /** What is left under Bridgit's own caps for [source], as the cloud last said. Null when nothing was mirrored. */
        fun bridgitRoom(source: Source): Room?

        /** Own-key calls per hour for [source], or null for no limit. Contract data when it gets a value. */
        fun callsPerHour(source: Source): Int?
    }

    /** A source as linked on this device, in the words of `vocab/instruments.json`. */
    data class Source(val id: String, val instrument: String, val instrumentClass: String, val funder: String, val custody: String, val cap: String, val valueTransfer: Boolean = false)

    data class Room(val remaining: Money, val resetsAt: Long?)

    data class Request(
        val spendId: String, val source: String, val amount: Money, val principalKind: String, val principalId: String,
        val attempt: Int = 0, val applet: String? = null, val action: String? = null, val service: String? = null, val route: String? = null,
        val claimedNamespace: String? = null, val pricesVersion: String? = null,
    )

    /** Runs the rules and, when they pass, holds [request]'s amount: one transaction. */
    fun spend(request: Request): Journal.HoldResult = journal.transact { tx -> decide(tx, request) }

    /** Offers the journal a provider's time for [source] (G5.2). Periods that may open then open at the next spend. */
    fun anchor(source: String, timeSource: String, time: InstrumentAdapter.ProviderTime): TimeAnchor.Result = journal.transact { tx ->
        val s = env.source(source) ?: return@transact TimeAnchor.Result.Ignored(TimeAnchor.Why.SOURCE_NOT_ALLOWED)
        TimeAnchor.offer(tx, s.instrument, s.valueTransfer, s.custody, timeSource, time.epochMs, time.verified)
    }

    private fun decide(tx: Journal.Tx, r: Request): Journal.HoldResult {
        fun no(rule: Int?, reason: SpendRefusal.Reason?, code: SpendRefusal.Code? = null, resetsAt: Long? = null) = Journal.HoldResult.Refused(SpendRefusal(code, reason, rule, resetsAt))
        tx.earlier(r.spendId, r.attempt)?.let { return it }
        if (!env.online()) return no(null, null, SpendRefusal.Code.OFFLINE)
        val source = env.source(r.source)
        // The instrument fixes the namespace (X10); a spend block can only disagree with it.
        if (source != null && (Namespace.of(source.instrument) != tx.namespace || (r.claimedNamespace != null && Namespace.claimed(r.claimedNamespace) != tx.namespace))) {
            return no(null, null, SpendRefusal.Code.NAMESPACE_MISMATCH)
        }
        val doc = rules.current(tx)
        val me = tx.install

        // 1. Switches.
        if (env.outsideCallsPaused() || doc?.paused == true) return no(1, SpendRefusal.Reason.PAUSED)
        if (source != null) {
            if (doc != null && (!doc.classOn(source.instrumentClass) || !doc.sourceOn(source.id))) return no(1, SpendRefusal.Reason.SWITCHED_OFF)
            if (source.valueTransfer && !env.paymentsOn()) return no(1, SpendRefusal.Reason.PAYMENTS_OFF)
        }

        // 2. Who may spend.
        if (!env.mayAct(r.principalKind, r.principalId)) return no(2, SpendRefusal.Reason.MAY_NOT_ACT)
        if (source?.valueTransfer == true && "payments.request" !in env.grants(r.principalKind, r.principalId)) return no(2, SpendRefusal.Reason.PAYMENTS_REQUEST_REQUIRED)

        // 3. Allow-lists.
        if (source == null) return no(3, SpendRefusal.Reason.SOURCE_NOT_LINKED)
        if (!env.routeAllowed(source, r.route)) return no(3, SpendRefusal.Reason.ROUTE_NOT_ALLOWED)

        // 4. Per-spend cap: the source's, else the instrument's.
        r.amount.requireAmount()
        (doc?.cap("sources", source.id) ?: doc?.cap("instruments", source.instrument))?.let { cap ->
            if (cap.unit != r.amount.unit) return no(4, SpendRefusal.Reason.UNIT_MISMATCH)
            if (r.amount > cap) return no(4, SpendRefusal.Reason.PER_SPEND_CAP)
        }

        // 5. Bridgit-funded caps. The cloud is the real cap; the device refuses early from its mirror.
        if (source.funder == "bridgit") env.bridgitRoom(source)?.let { room ->
            if (room.remaining.unit != r.amount.unit) return no(5, SpendRefusal.Reason.UNIT_MISMATCH)
            if (r.amount > room.remaining) return no(5, SpendRefusal.Reason.BRIDGIT_CAP, resetsAt = room.resetsAt)
        }
        if (source.custody == "cloud") return no(null, SpendRefusal.Reason.NOT_BUILT, SpendRefusal.Code.CLOUD_UNREACHABLE)

        // 6. Owner budgets. A spend counts against every budget whose scope it falls in.
        val agent = r.principalId.takeIf { r.principalKind == "agent" }
        val budgets = doc?.budgets?.values?.filter { it.covers(me, source.instrument, r.service, r.applet, agent) } ?: emptyList()
        val standing = budgets.associate { it.id to PeriodClock.roll(tx, it.id, it.period, doc!!.zone) }
        for (b in budgets) {
            val p = tx.state.periods[b.id] ?: continue
            // Where the device has no time to reason from, or the period is not open, rule 8 answers: it has the reasons for that.
            if (standing[b.id] != PeriodClock.Standing.CURRENT || p.full || p.overflow || p.unit != r.amount.unit || b.unit != r.amount.unit) continue
            if (Portions.frozen(tx.state, b, me)) continue
            if (over(p.atoms, r.amount.atoms, Portions.portion(tx.state, doc, b.id, me, p.period))) return no(6, SpendRefusal.Reason.BUDGET_REACHED, resetsAt = p.ends)
        }

        // 7. Rate limits, from the journal.
        env.callsPerHour(source)?.let { if (tx.holdsSince(source.id, HOUR_MS) >= it) return no(7, SpendRefusal.Reason.RATE_LIMITED) }

        // 8. The held allowance.
        fun exhausted(reason: SpendRefusal.Reason?, resetsAt: Long? = null) = no(8, reason, SpendRefusal.Code.ALLOWANCE_EXHAUSTED, resetsAt)
        if (doc == null || budgets.isEmpty()) return exhausted(SpendRefusal.Reason.NO_ALLOWANCE)
        if (tx.historyBlocked()) return exhausted(SpendRefusal.Reason.HISTORY_UNREADABLE)
        val slice = allowances.held(tx, source.id)?.takeIf { it.str("issuer") == "cloud" }
        val leased = budgets.any { it.kind(me) == "leased" }
        if (leased) {
            if (slice == null) return exhausted(SpendRefusal.Reason.NO_ALLOWANCE)
            if (allowances.expired(tx, slice)) return no(8, null, SpendRefusal.Code.ALLOWANCE_EXPIRED)
        }
        val lines = ArrayList<Journal.Line>()
        for (b in budgets) {
            val p = tx.state.periods[b.id]
            if (p == null || p.full) {
                val reason = when {
                    tx.state.origin == Journal.Origin.RESET -> SpendRefusal.Reason.JOURNAL_UNVERIFIED
                    tx.now() == null && tx.state.time != null -> SpendRefusal.Reason.TIME_UNANCHORED
                    else -> SpendRefusal.Reason.PERIOD_NOT_OPEN
                }
                return exhausted(reason)
            }
            if (p.overflow) return exhausted(SpendRefusal.Reason.COUNT_OVERFLOW)
            if (p.unit != r.amount.unit || b.unit != r.amount.unit) return exhausted(SpendRefusal.Reason.UNIT_MISMATCH)
            var limit = Portions.portion(tx.state, doc, b.id, me, p.period)
            if (b.kind(me) == "leased") {
                @Suppress("UNCHECKED_CAST")
                val line = slice!!.list("lines").map { it as Map<String, Any?> }.firstOrNull { it.str("rule") == b.id && it.str("period") == p.period } ?: return exhausted(SpendRefusal.Reason.NO_ALLOWANCE)
                limit = minOf(limit, line.map("amount")!!.long("atoms")!!)
            }
            val anchored = standing[b.id] == PeriodClock.Standing.CURRENT
            if (over(p.atoms, r.amount.atoms, limit)) {
                // A frozen share still spends what reached it since the reset; past that, the reason is the freeze (SA-R6).
                val reason = if (Portions.frozen(tx.state, b, me)) SpendRefusal.Reason.FROZEN_AFTER_RESET else if (anchored) null else SpendRefusal.Reason.TIME_UNANCHORED
                return exhausted(reason, p.ends.takeIf { anchored && reason == null })
            }
            lines += Journal.Line(b.id, p.period, Money(b.unit, limit))
        }
        if (source.valueTransfer) return no(9, SpendRefusal.Reason.NOT_BUILT)
        val allowance = if (leased) slice!!.str("id")!! else allowances.standing(tx, doc, source.id, source.instrument, lines)
        return tx.hold(
            Journal.HoldRequest(
                spendId = r.spendId, instrument = source.instrument, source = source.id, held = r.amount, lines = lines, principalKind = r.principalKind,
                principalId = r.principalId, attempt = r.attempt, claimedNamespace = r.claimedNamespace, applet = r.applet, action = r.action,
                allowance = allowance, pricesVersion = r.pricesVersion,
            ),
        )
    }

    private fun over(used: Long, more: Long, limit: Long): Boolean = try { Math.addExact(used, more) > limit } catch (e: ArithmeticException) { true }

    private companion object { const val HOUR_MS = 60L * 60 * 1000 }
}

/**
 * The device side of the spending authority's rules, wired together: the rule documents, the allowance
 * store, transfers, increments and the rule engine over one journal. When a document becomes current, what
 * it makes void is voided and what was waiting for it is taken up, in the same transaction.
 *
 * Nothing constructs this yet outside tests: no door, no setting and no screen uses it.
 */
class SpendingCore internal constructor(val journal: Journal, signer: SpendSigner, book: SignerBook, env: RuleEngine.Environment, cloudKey: ByteArray, events: (SpendEvent) -> Unit) {
    /** With the cloud key shipped in the app, which is empty. Only a test can put another in its place. */
    constructor(journal: Journal, signer: SpendSigner, book: SignerBook, env: RuleEngine.Environment, events: (SpendEvent) -> Unit = {}) : this(journal, signer, book, env, CloudAllowanceKey.PUBLIC, events)

    val rules = RuleDocuments(signer, book, events)
    val allowances = AllowanceStore(rules, cloudKey)
    val transfers = Transfers(journal, signer, book, rules, events)
    val increments = Increments(journal, signer, book, rules, events)
    val engine = RuleEngine(journal, rules, allowances, env)
    private var draining = false

    init {
        rules.afterAdopt = { tx, now ->
            increments.reconcile(tx, now)
            drain(tx)
        }
        // After a reset the head names no document; the ones this device held are taken up again from its own rows.
        if (journal.reset != null) journal.transact { tx -> rules.restore(tx) }
    }

    /** The current rule document, or null. */
    fun document(): RuleDocument? = journal.transact { tx -> rules.current(tx) }

    /** Takes a signed rule document from another device of the account. */
    fun adopt(json: String): RuleDocuments.Adopt = journal.transact { tx -> rules.adopt(tx, json) }

    /**
     * Changes the rule document on this device. [against] is the digest of the document the caller read
     * (null for the account's first); [capability] is whether the calling agent holds the Budgets capability.
     */
    fun change(caller: Caller, capability: Boolean, against: String?, account: String, edit: (Map<String, Any?>) -> Map<String, Any?>): RuleDocuments.Adopt =
        journal.transact { tx -> rules.change(tx, caller, capability, against, account, edit) }

    /** This install's portion and headroom balance for [budget] in its recorded period, and what it has used there. */
    fun standing(budget: String): Standing? = journal.transact { tx ->
        val doc = rules.current(tx) ?: return@transact null
        val b = doc.budgets[budget] ?: return@transact null
        PeriodClock.roll(tx, budget, b.period, doc.zone)
        val p = tx.state.periods[budget]
        val period = p?.period ?: return@transact Standing(null, 0, 0, null, Portions.frozen(tx.state, b, tx.install), Portions.holdsReclaim(tx.state, doc, budget, tx.install))
        Standing(
            period, Portions.portion(tx.state, doc, budget, tx.install, period), Portions.headroom(tx.state, doc, budget, tx.install, period),
            p.atoms.takeIf { !p.full }, Portions.frozen(tx.state, b, tx.install), Portions.holdsReclaim(tx.state, doc, budget, tx.install),
        )
    }

    /** [used] is null while the period counts as fully used. */
    data class Standing(val period: String?, val portion: Long, val headroom: Long, val used: Long?, val frozen: Boolean, val holdsReclaim: Boolean)

    private fun drain(tx: Journal.Tx) {
        if (draining) return
        draining = true
        try {
            var moved = true
            while (moved) {
                moved = false
                for ((id, kind, parent, body) in tx.waiting()) {
                    if (rules.get(tx, parent) == null) continue
                    tx.unpark(id)
                    moved = true
                    when (kind) {
                        "rules" -> rules.adopt(tx, body)
                        "transfer" -> transfers.receive(tx, body, mine = true)
                        "transfer_seen" -> transfers.receive(tx, body, mine = false)
                        "increment" -> increments.apply(tx, body)
                    }
                }
            }
        } finally {
            draining = false
        }
    }
}
