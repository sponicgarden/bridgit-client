package cc.bridgit.app.core.spending

/**
 * What every instrument adapter implements (spending authority G2.2). An adapter is shipped in the app,
 * never downloaded, and has a row in `vocab/instruments.json`.
 *
 * The order is fixed by the core, not by the adapter: [quote], then the core's authorisation and its hold
 * in the journal (steps 2 and 3, not adapter operations), then [execute], then the core records what
 * [settle] or [giveBack] say. Nothing here writes the journal, and [execute] must never be called before
 * the hold is durable. Storing and removing a source's secret (`link`, `unlink`) is not part of this
 * interface yet.
 */
interface InstrumentAdapter {
    /** Static facts. The same answer every time, with no network. */
    fun describe(): Description

    /** What the provider says can still be spent from [source]: balance, remaining cap or credit. Null when it does not say. */
    fun available(source: String): Money?

    /** Step 1: the **most** this spend can cost, with an expiry. */
    fun quote(request: SpendRequest): Quote

    /**
     * Step 4: does the paid thing under [spendId], which goes to the provider as its idempotency key. A retry
     * sends the same request with the same id. Anything short of a clear answer is [Execution.Unknown].
     */
    fun execute(spendId: String, request: SpendRequest, quote: Quote): Execution

    /** Step 5: what the core records as spent for an answer: the provider's reported cost, else the hold. */
    fun settle(quote: Quote, done: Execution.Done): Money

    /**
     * Step 6: the part of the hold **proven unspent** by this answer, or null when nothing is proven. An
     * unknown outcome proves nothing, so it never gives anything back (review P2).
     */
    fun giveBack(quote: Quote, execution: Execution): Money?

    /** Step 8: what the provider shows for [source], by spend id, with exactly what the read covers. */
    fun reconcile(source: String, spendIds: Collection<String>): Reconciliation

    /** [funder], [custody], [cap] and the rest use the words of `vocab/instruments.json`. */
    data class Description(
        val instrument: String,
        val instrumentClass: String,
        val funder: String,
        val custody: String,
        val namespace: Namespace,
        val unit: String,
        /** What bounds a source outside the app: `provider` or `core_only`. */
        val cap: String,
        /** The provider honours the spend id as an idempotency key, so a retry cannot bill twice. */
        val honoursSpendId: Boolean,
        /** A hold can be released at the provider (a card authorisation can be voided). */
        val holdReleasable: Boolean,
        val refunds: Boolean,
        /** What time its provider gives (G5.2 rule 1). */
        val timeSource: String,
        val usageRead: UsageFacts,
    )

    /**
     * What a usage read covers (G5.3 step 4). Only a read for this source alone, for one period, with pending
     * charges included can give money back or settle an unknown hold downward.
     */
    data class UsageFacts(val scope: String, val perPeriod: Boolean, val pendingIncluded: Boolean) {
        val qualifying get() = scope == "source" && perPeriod && pendingIncluded
    }

    /** One paid request. [maxCost] is the most the caller's price list says it can cost. */
    data class SpendRequest(val source: String, val maxCost: Money, val detail: Map<String, String> = emptyMap())

    data class Quote(val max: Money, val expiresAtMs: Long)

    /** A provider's own word for the time, with whatever proves it. The time-anchor rules (G5.2) decide whether it counts. */
    data class ProviderTime(val epochMs: Long, val verified: Boolean)

    sealed class Execution {
        /** The provider answered. [cost] is what it says it billed; null when it gave no figure. */
        data class Done(val cost: Money?, val providerRef: String?, val time: ProviderTime?) : Execution()

        /** The provider answered that it did not run the call and cannot bill it. */
        data class NotBilled(val reason: String, val time: ProviderTime?) : Execution()

        /** No clear answer. The hold stays whole until [reconcile] settles it (G2.2). */
        data class Unknown(val reason: String) : Execution()
    }

    /** One charge the provider shows. [pending] charges are not final. */
    data class Charge(val spendId: String, val cost: Money, val providerRef: String?, val pending: Boolean)

    /**
     * What one provider read covers: whose charges ([scope] `source` for [source] alone, or `account`), which
     * provider period ([period], from [fromMs] up to but not including [untilMs], in the provider's time),
     * whether charges not yet final are in it, and whether it is the whole of that period ([complete]) and
     * not a page or a sample of it.
     */
    data class Coverage(val source: String, val scope: String, val period: String, val fromMs: Long, val untilMs: Long, val pendingIncluded: Boolean, val complete: Boolean)

    /**
     * [charges] are those of the asked spend ids the provider shows in what [coverage] covers. A charge that
     * is shown always counts. A spend id that is **not** shown proves nothing by itself: see [qualifiesFor].
     * [coverage] is null when the provider could not be read.
     */
    data class Reconciliation(val source: String, val charges: List<Charge>, val coverage: Coverage?, val time: ProviderTime?) {
        /**
         * Whether this read is a qualifying one (G5.3 step 4) **for a spend of [source] that was open from
         * [firstMs] to [lastMs]** (its hold and the last time its call could have been running): for that
         * source alone, pending charges included, complete, and covering that whole stretch. Only then may
         * an absent spend id be given back or a lower figure settle an unknown hold. A read of another
         * period, today's usage for yesterday's call, never qualifies, however empty it is (SA-G6).
         * The two clocks are compared as given; the skew to allow is the caller's.
         */
        fun qualifiesFor(source: String, firstMs: Long, lastMs: Long): Boolean {
            val c = coverage ?: return false
            return this.source == source && c.source == source && c.scope == "source" && c.pendingIncluded && c.complete &&
                firstMs <= lastMs && c.fromMs <= firstMs && lastMs < c.untilMs
        }
    }
}
