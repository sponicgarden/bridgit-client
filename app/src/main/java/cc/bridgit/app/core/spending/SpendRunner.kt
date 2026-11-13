package cc.bridgit.app.core.spending

import cc.bridgit.app.core.spending.InstrumentAdapter.Execution

/**
 * One spend, in the fixed order of G2.2: quote, then the rules and the hold in one journal transaction
 * ([RuleEngine.spend]), then the adapter's call, then what its answer says is recorded. This is the only
 * code in the app that calls [InstrumentAdapter.execute].
 *
 * - **No hold, no call.** Anything but a fresh hold ends here: a refusal, and a request the journal already
 *   knows ([Outcome.Known]). An unknown outcome is settled by [reconcile], never by running the call again.
 * - **The provider's time is offered to the journal** whenever an answer carries one (G5.2). A spend refused
 *   because the journal has no time to open a period with gets one free read of the provider's time first.
 * - **Unknown keeps its hold.** [reconcile] gives a hold back, or settles one below its hold, only on a read
 *   that qualifies for the whole time that spend was open (G5.3 step 4). A charge that is shown always counts.
 *
 * A malformed request (a unit the instrument is not priced in, a negative cost) throws
 * [IllegalArgumentException] or [MoneyException] before anything is written.
 */
class SpendRunner(
    private val core: SpendingCore,
    private val sourceOf: (String) -> RuleEngine.Source?,
    private val adapterOf: (RuleEngine.Source) -> InstrumentAdapter?,
    private val events: (SpendEvent) -> Unit = {},
) {
    private val journal get() = core.journal

    /** One paid request. [requestId] is the caller's; the spend id is made from it, this install and the namespace. */
    data class Request(
        val requestId: String, val source: String, val maxCost: Money, val principalKind: String, val principalId: String,
        val attempt: Int = 0, val applet: String? = null, val action: String? = null, val service: String? = null, val route: String? = null,
        val claimedNamespace: String? = null, val detail: Map<String, String> = emptyMap(),
    )

    sealed class Outcome {
        abstract val spendId: String

        /** The provider answered. [overshoot]: it billed more than was held (G5.4). */
        data class Settled(override val spendId: String, val entry: Journal.Entry, val held: Money, val cost: Money, val overshoot: Boolean) : Outcome()

        /** The provider answered that it did not run the call; the whole hold is back. */
        data class GivenBack(override val spendId: String, val entry: Journal.Entry, val reason: String) : Outcome()

        /** No clear answer. The hold stays whole until a reconcile settles it. */
        data class Unknown(override val spendId: String, val entry: Journal.Entry, val reason: String) : Outcome()

        /** Nothing was held and nothing ran. [detail] is the journal's own word where it has one. */
        data class Refused(override val spendId: String, val refusal: SpendRefusal, val detail: String? = null) : Outcome()

        /**
         * The journal already holds this request: nothing more was held and nothing ran. [entry] is its latest
         * entry; [historical] says it is from an earlier epoch, where [entry] can be null (the row is unreadable).
         */
        data class Known(override val spendId: String, val entry: Journal.Entry?, val historical: Boolean) : Outcome()
    }

    /** Requests in flight (spend id and attempt, with how many callers are in [run] for it), so [reconcile] leaves their holds alone. */
    private val running = HashMap<String, Int>()

    /** Test seam: runs right after a hold committed and before the provider is called. */
    @Volatile internal var afterHold: (() -> Unit)? = null

    fun spendId(installId: String, requestId: String): String = SpendId.of(journal.namespace, installId, requestId)

    fun run(installId: String, r: Request): Outcome {
        val spendId = spendId(installId, r.requestId)
        val source = sourceOf(r.source)
        val adapter = source?.let(adapterOf)
        // A linked source this build has no adapter for can be held against and never spent from: closed.
        if (source != null && adapter == null) return Outcome.Refused(spendId, SpendRefusal(null, SpendRefusal.Reason.NOT_BUILT))
        val call = InstrumentAdapter.SpendRequest(r.source, r.maxCost, r.detail)
        val quote = adapter?.quote(call)
        val asked = RuleEngine.Request(
            spendId, r.source, quote?.max ?: r.maxCost.requireAmount(), r.principalKind, r.principalId, r.attempt, r.applet, r.action, r.service, r.route, r.claimedNamespace,
        )
        // The request is in flight from before its hold can exist until its outcome is in the journal, so a
        // reconcile never finds a hold of it that it may take for one cut off (it would give the hold back
        // before the call ran).
        val key = "$spendId#${r.attempt}"
        synchronized(running) { running[key] = (running[key] ?: 0) + 1 }
        try {
            var hold = decide(asked)
            if (source != null && adapter != null && (hold.first as? Journal.HoldResult.Refused)?.refusal?.reason in NEEDS_TIME && !core.anchoredNow() && anchor(source, adapter) is TimeAnchor.Result.Accepted) hold = decide(asked)
            return when (val h = hold.first) {
                is Journal.HoldResult.Refused -> Outcome.Refused(spendId, h.refusal, hold.second)
                is Journal.HoldResult.Existing -> Outcome.Known(spendId, h.entry, false)
                is Journal.HoldResult.Historical -> Outcome.Known(spendId, h.entry, true)
                is Journal.HoldResult.Held -> {
                    afterHold?.invoke()
                    execute(spendId, r, source!!, adapter!!, call, quote!!)
                }
            }
        } finally {
            synchronized(running) { val n = (running[key] ?: 1) - 1; if (n <= 0) running.remove(key) else running[key] = n }
        }
    }

    private fun decide(asked: RuleEngine.Request): Pair<Journal.HoldResult, String?> = try {
        core.engine.spend(asked) to null
    } catch (e: JournalRefused) {
        // A period that may not open (a zone change that moved the boundary back, for one): rule 8, and the old count stands.
        Journal.HoldResult.Refused(SpendRefusal(SpendRefusal.Code.ALLOWANCE_EXHAUSTED, SpendRefusal.Reason.PERIOD_NOT_OPEN, 8)) to e.why
    }

    private fun execute(spendId: String, r: Request, source: RuleEngine.Source, adapter: InstrumentAdapter, call: InstrumentAdapter.SpendRequest, quote: InstrumentAdapter.Quote): Outcome {
        val execution = try { adapter.execute(spendId, call, quote) } catch (e: Exception) { Execution.Unknown("adapter_failed") }
        return when (execution) {
            is Execution.Done -> {
                val cost = adapter.settle(quote, execution)
                val entry = journal.settle(spendId, cost, providerRef = execution.providerRef, attempt = r.attempt)
                offer(source, adapter, execution.time)
                val over = cost > quote.max
                if (over) events(SpendEvent(SpendEvent.OVERSHOOT, mapOf("cause" to "bill_past_quote", "source" to source.id, "spend" to spendId, "held" to quote.max.atoms.toString(), "counted" to cost.atoms.toString())))
                Outcome.Settled(spendId, entry, quote.max, cost, over)
            }
            is Execution.NotBilled -> {
                val entry = journal.release(spendId, Journal.text(execution.reason), attempt = r.attempt)
                offer(source, adapter, execution.time)
                Outcome.GivenBack(spendId, entry, execution.reason)
            }
            is Execution.Unknown -> Outcome.Unknown(spendId, journal.unknown(spendId, Journal.text(execution.reason), attempt = r.attempt), execution.reason)
        }
    }

    /** Reads the provider's time for [sourceId] (a call that costs nothing) and offers it to the journal. Null when there is no time to offer. */
    fun anchor(sourceId: String): TimeAnchor.Result? {
        val source = sourceOf(sourceId) ?: return null
        return anchor(source, adapterOf(source) ?: return null)
    }

    private fun anchor(source: RuleEngine.Source, adapter: InstrumentAdapter): TimeAnchor.Result? = offer(source, adapter, adapter.reconcile(source.id, emptyList()).time)

    private fun offer(source: RuleEngine.Source, adapter: InstrumentAdapter, time: InstrumentAdapter.ProviderTime?): TimeAnchor.Result? =
        time?.let { runCatching { core.engine.anchor(source.id, adapter.describe().timeSource, it) }.getOrNull() }

    /**
     * What one [reconcile] did. [read] is false when the provider could not be read. [settled]: unknown holds
     * settled at what the provider shows. [givenBack]: unknown holds a qualifying read shows no charge for.
     * [raised]: settled spends the provider now shows a higher charge for. [left]: unknown holds this read
     * proves nothing about; they stay whole.
     */
    data class Reconciled(
        val source: String, val read: Boolean, val coverage: InstrumentAdapter.Coverage? = null, val settled: List<String> = emptyList(), val givenBack: List<String> = emptyList(),
        val raised: List<String> = emptyList(), val left: List<String> = emptyList(), val anchor: TimeAnchor.Result? = null,
    )

    /** Step 8: compares this epoch's spends of [sourceId] with what the provider shows, by spend id. */
    fun reconcile(sourceId: String): Reconciled {
        val source = sourceOf(sourceId) ?: return Reconciled(sourceId, false)
        val adapter = adapterOf(source) ?: return Reconciled(sourceId, false)
        val spends = LinkedHashMap<String, MutableList<Journal.Entry>>()
        for (e in journal.entries()) if (e.kind == "spend" && e.source == sourceId) spends.getOrPut("${e.spendId}#${e.body.getInt("attempt")}") { ArrayList() } += e
        // The provider knows a spend by its id alone. Where a request has more than one attempt, a charge cannot be tied to one of them here.
        val single = spends.filter { (_, entries) -> spends.keys.count { it.substringBefore('#') == entries[0].spendId } == 1 }
        // A hold whose call is not running was cut off before its answer was recorded: its outcome is unknown.
        // Decided and written under the lock a run registers under, on the spend's state as it is now: a run that is
        // not registered has finished, so a hold it left is one no answer will come for.
        for ((key, entries) in single) if (entries.last().state == Journal.State.HELD) synchronized(running) {
            val id = entries[0].spendId!!
            val attempt = entries[0].body.getInt("attempt")
            val now = journal.latest(id, attempt)
            if (key !in running && now?.state == Journal.State.HELD) entries += journal.unknown(id, "interrupted", attempt = attempt)
            else if (now != null && now.seq != entries.last().seq) entries += now
        }
        val open = single.filterValues { it.last().state == Journal.State.UNKNOWN }
        val done = single.filterValues { it.last().state == Journal.State.SETTLED }
        val asked = (open.values + done.values.toList().takeLast(MAX_ASKED)).map { it[0].spendId!! }
        val read = adapter.reconcile(sourceId, asked)
        val anchor = offer(source, adapter, read.time)
        if (read.coverage == null) return Reconciled(sourceId, false, anchor = anchor, left = open.values.map { it[0].spendId!! })
        val shown = read.charges.groupBy { it.spendId }
        val settled = ArrayList<String>(); val back = ArrayList<String>(); val raised = ArrayList<String>(); val left = ArrayList<String>()
        for (entries in open.values) {
            val id = entries[0].spendId!!
            val attempt = entries[0].body.getInt("attempt")
            val qualifying = read.qualifiesFor(sourceId, entries.first().wallMs, entries.last().wallMs)
            val charges = shown[id].orEmpty()
            try {
                if (charges.isNotEmpty()) {
                    journal.settle(id, charges.map { it.cost }.reduce(Money::plus), providerRef = charges.last().providerRef, qualifying = qualifying, reason = "reconcile", attempt = attempt)
                    settled += id
                } else if (qualifying) {
                    journal.release(id, "no_charge_shown", qualifying = true, attempt = attempt)
                    back += id
                } else left += id
            } catch (e: JournalRefused) {
                left += id
            }
        }
        for (entries in done.values) {
            val id = entries[0].spendId!!
            val charges = shown[id].orEmpty()
            if (charges.isEmpty()) continue
            val total = charges.map { it.cost }.reduce(Money::plus)
            // A higher figure is recorded whatever kind of read showed it; a lower one is left as it is.
            if (total > entries.last().settled!!) {
                journal.settle(id, total, providerRef = charges.last().providerRef, reason = "late_charge", attempt = entries[0].body.getInt("attempt"))
                raised += id
            }
        }
        return Reconciled(sourceId, true, read.coverage, settled, back, raised, left, anchor)
    }

    private companion object {
        const val MAX_ASKED = 500

        /** Refusals an accepted time can end: the journal had none to open or name the period with. */
        val NEEDS_TIME = setOf(SpendRefusal.Reason.TIME_UNANCHORED, SpendRefusal.Reason.PERIOD_NOT_OPEN, SpendRefusal.Reason.JOURNAL_UNVERIFIED)
    }
}
