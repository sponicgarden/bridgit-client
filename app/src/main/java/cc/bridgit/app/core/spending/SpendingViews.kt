package cc.bridgit.app.core.spending

/**
 * What the configuration reads show of the spending core (spending authority G11): plain values (maps, lists,
 * text, whole numbers), read in one journal transaction each. Nothing here decides anything.
 *
 * A read opens what may open, as a spend would ([PeriodClock.roll]): a budget's first period, or the next
 * one when the journal's own time has reached it. It never opens one early.
 */

/** Whether the journal has a time from outside the device that still stands in this boot (G5.2). */
fun SpendingCore.anchoredNow(): Boolean = journal.transact { tx -> tx.now() != null && tx.state.time?.anchored == true }

internal fun money(unit: String, atoms: Long): Map<String, Any?> = mapOf("unit" to unit, "atoms" to atoms, "amount" to Money(unit, atoms).toDecimal())

/** The journal and its time base: the epoch, whether the chain verifies, and the last time anchor. */
fun SpendingCore.journalView(): Map<String, Any?> {
    val whole = journal.verify()
    return journal.transact { tx ->
        val s = tx.state
        mapOf(
            "epoch" to s.epoch, "origin" to s.origin.id, "seq" to s.seq, "whole" to (whole == null), "not_whole_reason" to whole,
            "reset_reason" to journal.reset?.reason, "history_blocked" to tx.historyBlocked(), "history_acknowledged" to (s.historyAck != null),
            "uploaded_seq" to (s.acked[s.epoch] ?: 0L),
            "time" to mapOf(
                "now" to tx.now(), "anchored" to (s.time?.anchored == true), "last_anchor_at" to s.time?.floor,
                // False after a reboot until a fresh anchor arrives: no new period opens meanwhile.
                "usable" to (tx.now() != null), "wall" to tx.clock.wallMs(), "boot" to tx.clock.bootCount().toLong(),
            ),
            "waiting" to tx.waiting().size.toLong(),
        )
    }
}

/** Every budget of the current rule document as this device holds it, or an empty list when there is none. */
fun SpendingCore.budgetViews(approverEnrolled: Boolean): List<Map<String, Any?>> = journal.transact { tx ->
    val doc = rules.current(tx) ?: return@transact emptyList()
    doc.budgets.values.map { budgetView(tx, doc, it, approverEnrolled) }
}

fun SpendingCore.budgetView(id: String, approverEnrolled: Boolean): Map<String, Any?>? = journal.transact { tx ->
    val doc = rules.current(tx) ?: return@transact null
    doc.budgets[id]?.let { budgetView(tx, doc, it, approverEnrolled) }
}

/** The standing amount of [budget]: the document's, plus every standing increment this device has applied. */
internal fun standingTotal(state: SpendState, budget: RuleDocument.Budget): Long =
    state.moves.filter { it.budget == budget.id && it.dir == "inc" }.fold(budget.amount) { sum, m -> Math.addExact(sum, m.standing()) }

private fun SpendingCore.budgetView(tx: Journal.Tx, doc: RuleDocument, b: RuleDocument.Budget, approverEnrolled: Boolean): Map<String, Any?> {
    val me = tx.install
    val state = tx.state
    val standing = try { PeriodClock.roll(tx, b.id, b.period, doc.zone).name.lowercase() } catch (e: JournalRefused) { e.why }
    val p = state.periods[b.id]
    val period = p?.period
    val portion = period?.let { Portions.portion(state, doc, b.id, me, it) } ?: 0L
    val used = p?.takeIf { !it.full }?.atoms
    val moves = state.moves.filter { it.budget == b.id }
    fun move(m: Move) = mapOf(
        "id" to m.id, "kind" to m.kind, "amount" to money(b.unit, m.atoms), "lasts" to m.lasts, "from_period" to m.from, "peer" to m.peer,
        "void" to m.voided, "kept" to (if (m.voided) money(b.unit, m.kept) else null),
    )
    return mapOf(
        "id" to b.id, "scope" to mapOf("kind" to b.scopeKind, "id" to b.scopeId), "period" to b.period, "unit" to b.unit,
        // amount is what stands for good; total adds this period's top-ups.
        "amount" to money(b.unit, standingTotal(state, b)), "base" to money(b.unit, b.amount),
        "total" to money(b.unit, period?.let { Portions.total(state, doc, b.id, it) } ?: standingTotal(state, b)),
        "rebalance" to b.rebalance,
        "gate" to mapOf(
            "agents_may_change" to b.agentsMayChange, "ceiling" to b.ceiling?.let { money(b.unit, it) }, "set_by" to b.setBy,
            "gate_strength" to Gates.gateStrength(b, Gates.Env(approverEnrolled, true)),
        ),
        "portions" to mapOf(
            "cloud" to money(b.unit, b.cloud),
            "devices" to b.devices.mapValues { (install, atoms) -> mapOf("portion" to money(b.unit, atoms), "kind" to b.kind(install), "headroom" to money(b.unit, b.headroom(install))) },
            "headroom_cloud" to money(b.unit, b.headroomCloud),
        ),
        "this_device" to mapOf(
            "install" to me, "executor" to (me in b.devices), "kind" to b.kind(me),
            "portion" to money(b.unit, portion),
            "headroom" to money(b.unit, period?.let { Portions.headroom(state, doc, b.id, me, it) } ?: 0L),
            // null while the period counts as fully used: no entry has opened it.
            "used" to used?.let { money(b.unit, it) },
            "remaining" to used?.let { money(b.unit, portion - it) },
            "overflow" to (p?.overflow == true),
            "period_id" to period, "period_starts" to p?.starts, "resets_at" to p?.ends,
            "standing" to standing,
            "frozen" to Portions.frozen(state, b, me), "holds_reclaim" to Portions.holdsReclaim(state, doc, b.id, me),
        ),
        "increments" to moves.filter { it.dir == "inc" }.map { move(it) + mapOf("draws_headroom" to it.agent, "by_owner" to it.owner) },
        // Room this device signed away: gone from here whether or not its recipient has taken it yet.
        "in_transit" to moves.filter { it.dir == "out" }.map { move(it) + ("debit_period" to it.debit) },
        "taken" to moves.filter { it.dir == "in" }.map(::move),
        "reclaim_holder" to state.reclaim[b.id],
    )
}

/** The rule document's own fields: generation, digest, zone, switches, caps and who signed it. Null when there is none. */
fun SpendingCore.rulesView(): Map<String, Any?>? = document()?.let { doc ->
    mapOf(
        "generation" to doc.generation, "digest" to doc.digest, "account" to doc.account, "namespace" to doc.namespace, "zone" to doc.zone,
        "switches" to doc.map.map("switches"), "caps" to doc.map.map("caps")!!.mapValues { (_, group) ->
            @Suppress("UNCHECKED_CAST") (group as Map<String, Map<String, Any?>>).mapValues { (_, m) -> money(m.str("unit")!!, m.long("atoms")!!) }
        },
        "merged" to doc.merged, "executor" to doc.executor, "signed_by" to doc.map.map("signed_by"), "actor" to doc.map.map("actor"),
    )
}

/** The allowance each source last spent under, as the journal head holds it (G5.2). */
fun SpendingCore.allowanceViews(): Map<String, Map<String, Any?>> = journal.transact { tx ->
    tx.state.allowances.mapValues { (_, a) ->
        mapOf(
            "id" to a.str("id"), "issuer" to a.str("issuer"), "seq" to a.long("seq"), "rules_generation" to a.long("rules_generation"),
            "lines" to a.list("lines"), "valid_for_s" to a.long("valid_for_s"), "not_after" to a.long("not_after"),
            // A standing share is its own allowance and ends with its period; only a leased slice runs out of time.
            "expired" to (a.str("issuer") == "cloud" && allowances.expired(tx, a)),
        )
    }
}

/** What this epoch's spends add up to, per source and by state. Read from the journal, so it is as fresh as its last entry. */
fun SpendingCore.spendSummary(): Map<String, Any?> {
    val latest = LinkedHashMap<String, Journal.Entry>()
    var last = 0L
    for (e in journal.entries()) {
        last = e.wallMs
        if (e.kind == "spend") latest["${e.spendId}#${e.body.getInt("attempt")}"] = e
    }
    class Sum { var count = 0L; val atoms = HashMap<String, Long>() }
    val bySource = LinkedHashMap<String, LinkedHashMap<String, Sum>>()
    for (e in latest.values) {
        val state = e.state ?: continue
        // What a spend counts for now: its settlement less any refund, or its whole hold while that is open.
        val counted = when (state) {
            Journal.State.SETTLED -> e.settled
            Journal.State.REFUNDED -> e.settled?.let { s -> e.refunded?.let { s - it } ?: s }
            Journal.State.HELD, Journal.State.UNKNOWN -> e.held
            Journal.State.RELEASED -> null
        }
        val sum = bySource.getOrPut(e.source ?: "") { LinkedHashMap() }.getOrPut(state.id) { Sum() }
        sum.count++
        counted?.let { sum.atoms[it.unit] = Math.addExact(sum.atoms[it.unit] ?: 0L, it.atoms) }
    }
    return mapOf(
        "last_entry_at" to last.takeIf { it > 0 },
        "sources" to bySource.mapValues { (_, states) -> states.mapValues { (_, s) -> mapOf("count" to s.count, "counted" to s.atoms.map { (unit, atoms) -> money(unit, atoms) }) } },
    )
}
