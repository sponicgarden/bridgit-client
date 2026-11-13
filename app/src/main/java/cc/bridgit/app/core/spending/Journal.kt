package cc.bridgit.app.core.spending

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.security.MessageDigest

/** The journal cannot do what was asked: an outcome that is not allowed from the spend's state, or a period that may not open. */
class JournalRefused(val why: String) : IllegalStateException(why)

/** The head or the key no longer verifies. Nothing was written; opening the journal again starts a new epoch. */
class JournalNotWhole(val why: String) : IllegalStateException(why)

/**
 * The device's durable record of its holds and outcomes (spending authority G5.3), for one namespace.
 *
 * - **It only grows.** Every entry is `entry/1` (`contracts/client-core/schemas/spend-entry.schema.json`),
 *   hash-chained to the one before it and MACed with the epoch's key. A settlement, a give-back, an unknown
 *   outcome or a refund is a new entry for the same spend id; nothing is rewritten.
 * - **Head and entry commit together.** The head (epoch, last sequence and hash, period totals) is written
 *   in the same storage transaction as each entry, under the MAC. No hold without a head that verifies and
 *   matches the last entry.
 * - **A journal that is not whole spends nothing.** [open] checks the key, the head and the whole chain. If
 *   any is missing or wrong it starts a new epoch ([reset] says why) and keeps the old rows.
 * - **Every period counts as fully used until an entry opens it** ([openPeriod]). That holds for a new
 *   journal and after a reset alike (G5.3 rules 3 and 4), so there is nothing to "treat as full": a period
 *   with no opening entry has no count to spend against.
 * - **Unknown keeps its hold.** Time never releases it; [settleUnknownAtHold] settles it at its hold when
 *   the retention the caller names has passed (G2.2, SA-G6).
 * - **Only verified records decide.** Which entry is a spend's latest comes from the chain as [open] replayed
 *   it, never from a storage column on its own; a row that no longer matches what was verified stops every
 *   write ([JournalNotWhole]).
 * - **A request is remembered across epochs.** A spend id and attempt found in an earlier epoch's rows is
 *   never held again ([HoldResult.Historical]): it may already have run. A deliberate retry is a new attempt.
 *
 * - **A period opens only on evidence the journal itself holds** ([Tx.openPeriod]): a rule document of this
 *   epoch that named the install for a first portion, and for every basis a time this journal can reason
 *   from ([TimeBase]) that falls inside the period.
 * - **One transaction for a decision and its entry.** [transact] gives the rule engine, the allowance store
 *   and the transfer and increment steps a [Tx]: what they read and what they write commit together, or not
 *   at all. Holding and opening a period are not public: only code in this module can reach them, and the
 *   rule engine computes every line's limit inside the transaction.
 *
 * What is **not** here: the rules of G6 ([RuleEngine]), what a transfer, an increment, an allowance or a
 * rule document must satisfy (their own files), and the upload. The journal stores what they decided and
 * replays it: every head field comes from the entries ([SpendState.apply]).
 */
class Journal private constructor(
    private val db: SpendingDb,
    private val keys: JournalKeys,
    private val installId: String,
    private val clock: SpendClock,
) {
    enum class Origin(val id: String) {
        /** No journal and no key existed: a new install. */
        FIRST("first"),

        /** A journal or its key existed and was not whole. */
        RESET("reset"),
    }

    /** Why a period entry is written. The journal checks the order of periods; whether the evidence is good is the caller's to prove. */
    enum class Basis(val id: String) {
        /** The rule document first gave this install a portion after the epoch began (G5.3 rule 4). Only in a first epoch, only for a rule with no period yet. */
        FIRST_PORTION("first_portion"),

        /** Names the current period without opening it: it stays fully used (G5.2 rule 3, after a new epoch). */
        NAMED("named"),

        /** A later period than the one recorded: it must begin at or after the recorded one's end (G5.2 rule 3). */
        NEXT_PERIOD("next_period"),

        /** Rebuilds a fully used period's count from a qualifying provider read for every source in the rule's scope (G5.3 rule 3). */
        PROVIDER_READ("provider_read"),

        /** Rebuilds it from the cloud's settlement, for a rule whose every source on this device is leased and metered (G5.3 rule 3). */
        CLOUD_SETTLEMENT("cloud_settlement"),
    }

    enum class State(val id: String) { HELD("held"), SETTLED("settled"), RELEASED("released"), UNKNOWN("unknown"), REFUNDED("refunded") }

    data class Period(val id: String, val startsMs: Long, val endsMs: Long)

    /** One line a spend must fit: a rule, the period it is counted in, and the most this device may spend there. */
    data class Line(val rule: String, val period: String, val limit: Money)

    data class HoldRequest(
        val spendId: String,
        val instrument: String,
        val source: String,
        val held: Money,
        val lines: List<Line>,
        val principalKind: String,
        val principalId: String,
        val attempt: Int = 0,
        /** The namespace the signed spend block names, when it names one. Never what decides the namespace. */
        val claimedNamespace: String? = null,
        val applet: String? = null,
        val action: String? = null,
        val allowance: String? = null,
        val pricesVersion: String? = null,
    )

    sealed class HoldResult {
        data class Held(val entry: Entry) : HoldResult()

        /** This spend id and attempt already has an entry; nothing new was held. [entry] is its latest. */
        data class Existing(val entry: Entry) : HoldResult()

        /**
         * This spend id and attempt is in the rows of an earlier epoch ([epoch], [seq]), so it may already have
         * run: nothing was held and it must not be executed again. Reconcile it with the provider; a retry that
         * is meant to run again is a new attempt with its own hold (G2.2 "Attempts"). [entry] is its last
         * readable entry there, or null when that row cannot be read.
         */
        data class Historical(val epoch: String, val seq: Long, val entry: Entry?) : HoldResult()
        data class Refused(val refusal: SpendRefusal) : HoldResult()
    }

    sealed class Usage {
        /** No entry has opened this period of this rule in this epoch. */
        object FullyUsed : Usage()

        /** A settlement took the count past what can be counted. The period refuses everything until the next one opens. */
        object Overflowed : Usage()
        data class Counted(val amount: Money) : Usage()
    }

    class Reset(val reason: String, val previousEpoch: String?)

    class Entry internal constructor(val epoch: String, val seq: Long, val hash: String, val json: String) {
        internal val body: JSONObject by lazy { JSONObject(json) }
        internal val tree: Map<String, Any?> by lazy { Tree.parse(json) }
        val kind: String get() = body.getString("kind")
        val spendId: String? get() = body.optString("spend_id").ifEmpty { null }
        val source: String? get() = body.optString("source").ifEmpty { null }
        val state: State? get() = State.values().firstOrNull { it.id == body.optString("state") }
        val held: Money? get() = money("held")
        val settled: Money? get() = money("settled")
        val refunded: Money? get() = money("refunded")
        val reason: String? get() = body.optString("reason").ifEmpty { null }
        val wallMs: Long get() = body.getLong("t_wall")
        private fun money(name: String) = body.optJSONObject(name)?.let { Money(it.getString("unit"), it.getLong("atoms")) }
    }

    data class Head(val epoch: String, val origin: Origin, val seq: Long, val hash: String)

    val namespace: Namespace get() = db.namespace

    /** Set when [open] had to start a new epoch over an earlier journal; the caller raises `spend.journal_reset`. */
    var reset: Reset? = null
        private set

    /** Test seam: runs inside the transaction after the entry row is written and before the head is. */
    @Volatile internal var afterEntryWrite: (() -> Unit)? = null

    @Synchronized fun head(): Head = read { w -> loadHead(w).let { Head(it.epoch, it.origin, it.seq, it.hash) } }

    /** What this rule has used in this period, or [Usage.FullyUsed] when no entry opened it. */
    @Synchronized fun usage(rule: String, period: String): Usage = read { w ->
        val p = loadHead(w).periods[rule]
        usageOf(p, period)
    }

    /** Whether rows of an earlier epoch that can be tied to no request still stop every hold (see [acknowledgeHistory]). */
    @Synchronized fun historyBlocked(): Boolean = transact { tx -> tx.historyBlocked() }

    /**
     * The exit from `history_unreadable`. A row of an earlier epoch that can be tied to no request means no
     * request can be shown to be new, so every hold is refused. The owner, or an agent acting for the owner,
     * can take that on: this records who did, bound to a digest of exactly the rows that are unreadable now
     * (later damage is not covered and blocks again). Nothing is given back by it. Every period whose count
     * was rebuilt from a provider read goes back to fully used, no such rebuild is accepted afterwards in
     * this epoch, and spending resumes only in a period opened after it under the time rules (G5.2).
     *
     * Why an acknowledgement and not the next period by itself: a new period bounds the amount the hidden
     * request may have cost, but not that it may run a second time under its old request id. Only the caller
     * knows what it had in flight, so the caller says so; no person is needed (the agent is the customer).
     * Returns null when these rows were already acknowledged.
     */
    @Synchronized fun acknowledgeHistory(actorKind: String, actorId: String): Entry? = transact { tx ->
        require(actorKind == "owner" || actorKind == "agent") { "The owner or an agent acknowledges; an applet does not" }
        val digest = sync(tx.w, tx.state).historyDigest ?: throw JournalRefused("nothing_to_acknowledge")
        if (tx.state.historyAck == digest) return@transact null
        tx.append(mapOf("kind" to "history_ack", "digest" to digest, "actor" to mapOf("kind" to actorKind, "id" to text(actorId))))
    }

    /** Runs [f] in one storage transaction with the verified head. Everything it appends commits together with what it read. */
    @Synchronized internal fun <T> transact(f: (Tx) -> T): T = write { w, head -> f(Tx(w, head)) }

    /** What code of this module may do inside one transaction. */
    internal inner class Tx(internal val w: SQLiteDatabase, val state: SpendState) {
        val install: String get() = installId
        val namespace: Namespace get() = this@Journal.namespace
        val clock: SpendClock get() = this@Journal.clock

        /** The time by the journal's own time base, or null when the device has rebooted since it was set (G5.2). */
        fun now(): Long? {
            val t = state.time ?: return null
            if (t.boot != clock.bootCount().toLong() || clock.elapsedMs() < t.elapsed) return null
            return Math.addExact(t.t, clock.elapsedMs() - t.elapsed)
        }

        fun usage(rule: String, period: String): Usage = usageOf(state.periods[rule], period)
        fun historyBlocked(): Boolean = sync(w, state).historyDigest.let { it != null && it != state.historyAck }
        fun append(fields: Map<String, Any?>): Entry = append(w, state, fields, null)
        fun openPeriod(rule: String, period: Period, basis: Basis, counted: Money, evidence: List<Map<String, Any?>> = emptyList()): Entry =
            this@Journal.openPeriod(this, rule, period, basis, counted, evidence)
        fun hold(request: HoldRequest): HoldResult = this@Journal.hold(this, request)

        /** What the journal already holds for this spend id and attempt: its latest entry in this epoch, or its row in an earlier one. Null for a new request. */
        fun earlier(spendId: String, attempt: Int): HoldResult? {
            val key = spendKey(spendId, attempt)
            latest(w, state, key)?.let { return HoldResult.Existing(it) }
            return sync(w, state).history[key]?.let { HoldResult.Historical(it.epoch, it.seq, it.entry) }
        }

        /** Holds of [source] written in the last [windowMs]: by running time inside this boot, by the wall clock before it. */
        fun holdsSince(source: String, windowMs: Long): Int {
            var n = 0
            w.rawQuery("SELECT body FROM entries WHERE epoch = ? AND spend_key IS NOT NULL ORDER BY seq DESC", arrayOf(state.epoch)).use { c ->
                while (c.moveToNext()) {
                    val b = Tree.parse(c.getString(0))
                    val recent = if (b.long("boot") == clock.bootCount().toLong()) clock.elapsedMs() - b.long("t_elapsed")!! < windowMs else clock.wallMs() - b.long("t_wall")!! < windowMs
                    if (!recent) break
                    if (b.str("state") == State.HELD.id && b.str("source") == source) n++
                }
            }
            return n
        }

        fun document(digest: String): String? = w.rawQuery("SELECT body FROM documents WHERE digest = ?", arrayOf(digest)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

        fun putDocument(digest: String, generation: Long, body: String) {
            w.insertWithOnConflict("documents", null, ContentValues().apply { put("digest", digest); put("generation", generation); put("body", body) }, SQLiteDatabase.CONFLICT_IGNORE)
        }

        fun park(id: String, kind: String, parent: String, body: String) {
            w.insertWithOnConflict("waiting", null, ContentValues().apply { put("id", id); put("kind", kind); put("parent", parent); put("body", body) }, SQLiteDatabase.CONFLICT_IGNORE)
        }

        /** Waiting items, oldest first, as (id, kind, parent, body). */
        fun waiting(): List<List<String>> = w.rawQuery("SELECT id, kind, parent, body FROM waiting ORDER BY rowid", null).use { c -> buildList { while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) } }
        fun unpark(id: String) { w.delete("waiting", "id = ?", arrayOf(id)) }

        /** What a caller's idempotency key [key] made in this epoch (an increment's id), or null. */
        fun remembered(key: String): String? =
            w.rawQuery("SELECT id FROM requests WHERE key = ? AND epoch = ?", arrayOf(key, state.epoch)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

        /** What the caller asked under [key], as the door wrote it down, or null when it wrote nothing. */
        fun rememberedPayload(key: String): String? =
            w.rawQuery("SELECT payload FROM requests WHERE key = ? AND epoch = ?", arrayOf(key, state.epoch)).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }

        /** Remembers what [key] made ([id]: an increment's id, or a word for a change with none) and what was asked ([payload]). */
        fun remember(key: String, id: String, payload: String? = null) {
            w.insertWithOnConflict("requests", null, ContentValues().apply { put("key", key); put("epoch", state.epoch); put("id", id); put("payload", payload) }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /**
     * Names or opens one period of one rule. [counted] is the starting count: zero for a period that begins
     * now, the rebuilt figure for [Basis.PROVIDER_READ] and [Basis.CLOUD_SETTLEMENT]; unused for [Basis.NAMED].
     * The journal checks the evidence it holds itself:
     * - a first portion needs a rule document of this epoch that named the install for [rule] (G5.3 rule 4);
     * - the period must contain the journal's own time ([Tx.now]). With no time base at all, a first portion
     *   takes the wall clock, which opens nothing a later anchor could not: it is the first period;
     * - naming a period after a reset needs an **anchored** time;
     * - a next period on an unanchored base opens only after a full period of running time since the last
     *   one opened, with no reboot between (G5.2: the wall clock alone starts nothing);
     * - a rebuild needs [evidence] (what was read, for which source), and is refused once the history was
     *   acknowledged ([acknowledgeHistory]).
     */
    @Synchronized internal fun openPeriod(rule: String, period: Period, basis: Basis, counted: Money, evidence: List<Map<String, Any?>> = emptyList()): Entry =
        transact { tx -> openPeriod(tx, rule, period, basis, counted, evidence) }

    private fun openPeriod(tx: Tx, rule: String, period: Period, basis: Basis, counted: Money, evidence: List<Map<String, Any?>>): Entry {
        val head = tx.state
        text(rule); text(period.id)
        if (period.startsMs < 0 || period.endsMs <= period.startsMs) throw JournalRefused("bad_period")
        counted.requireAmount()
        val current = head.periods[rule]
        fun inPeriod(at: Long?) {
            if (at == null) throw JournalRefused("time_unanchored")
            if (at < period.startsMs || at >= period.endsMs) throw JournalRefused("period_not_current")
        }
        when (basis) {
            Basis.FIRST_PORTION -> {
                if (head.origin != Origin.FIRST) throw JournalRefused("not_a_first_journal")
                if (current != null) throw JournalRefused("rule_has_a_period")
                if (!counted.isZero) throw JournalRefused("a_new_period_starts_at_zero")
                if (rule !in head.budgets) throw JournalRefused("no_portion_received")
                inPeriod(if (head.time == null) clock.wallMs() else tx.now())
            }
            Basis.NAMED -> {
                if (current != null) throw JournalRefused("rule_has_a_period")
                inPeriod(tx.now().takeIf { head.time?.anchored == true })
            }
            Basis.NEXT_PERIOD -> {
                if (current == null) throw JournalRefused("no_recorded_period")
                if (period.startsMs < current.ends) throw JournalRefused("period_not_later")
                if (!counted.isZero) throw JournalRefused("a_new_period_starts_at_zero")
                inPeriod(tx.now())
                if (head.time?.anchored != true) {
                    val ran = clock.elapsedMs() - current.openedElapsed
                    if (current.openedBoot != clock.bootCount().toLong() || ran < current.ends - current.starts) throw JournalRefused("full_period_not_elapsed")
                }
            }
            Basis.PROVIDER_READ, Basis.CLOUD_SETTLEMENT -> {
                if (current == null || !current.full || current.period != period.id) throw JournalRefused("nothing_to_rebuild")
                if (head.historyAck != null) throw JournalRefused("history_acknowledged")
                if (evidence.isEmpty()) throw JournalRefused("no_evidence")
            }
        }
        val full = basis == Basis.NAMED
        val fields = mutableMapOf<String, Any?>(
            "kind" to "period", "rule" to rule, "period" to period.id, "starts" to period.startsMs, "ends" to period.endsMs,
            "basis" to basis.id, "full" to full,
        )
        if (!full) fields["counted"] = moneyMap(counted)
        if (evidence.isNotEmpty()) fields["evidence"] = evidence
        return append(tx.w, head, fields, null)
    }

    /**
     * Sets [request]'s amount aside, or says why not. The check and the entry are one transaction: every
     * line's period must be open and have room for the whole hold. A second call with the same spend id and
     * attempt holds nothing more. Not public: the rule engine is the caller, with limits it computed in the
     * same transaction ([Tx.hold]).
     */
    @Synchronized internal fun hold(request: HoldRequest): HoldResult = transact { tx -> hold(tx, request) }

    private fun hold(tx: Tx, request: HoldRequest): HoldResult {
        val w = tx.w
        val head = tx.state
        require(SpendId.isValid(request.spendId)) { "Not a spend id" }
        require(request.attempt >= 0) { "Negative attempt" }
        require(request.lines.isNotEmpty()) { "A spend counts against at least one rule" }
        require(request.lines.map { it.rule }.toSet().size == request.lines.size) { "A rule is listed twice" }
        require(request.principalKind in PRINCIPALS) { "Unknown principal kind" }
        listOfNotNull(request.instrument, request.source, request.principalId, request.applet, request.action, request.allowance, request.pricesVersion).forEach(::text)
        request.lines.forEach { text(it.rule); text(it.period) }
        request.held.requireAmount()

        // The instrument fixes the namespace (X10). The spend block's word can only disagree with it.
        if (Namespace.of(request.instrument) != namespace || (request.claimedNamespace != null && Namespace.claimed(request.claimedNamespace) != namespace)) {
            return HoldResult.Refused(SpendRefusal(SpendRefusal.Code.NAMESPACE_MISMATCH))
        }
        val key = spendKey(request.spendId, request.attempt)
        latest(w, head, key)?.let { return HoldResult.Existing(it) }
        // Request identity outlives an epoch: what an earlier epoch's rows name may already have run.
        val index = sync(w, head)
        index.history[key]?.let { return HoldResult.Historical(it.epoch, it.seq, it.entry) }
        if (index.historyDigest != null && index.historyDigest != head.historyAck) return refuse(SpendRefusal.Reason.HISTORY_UNREADABLE)

        for (line in request.lines) {
            val p = head.periods[line.rule]
            if (p == null || p.full || p.period != line.period) {
                val unverified = head.origin == Origin.RESET && (p == null || p.full)
                return refuse(if (unverified) SpendRefusal.Reason.JOURNAL_UNVERIFIED else SpendRefusal.Reason.PERIOD_NOT_OPEN)
            }
            if (p.overflow) return refuse(SpendRefusal.Reason.COUNT_OVERFLOW)
            if (p.unit != request.held.unit || line.limit.unit != request.held.unit) return refuse(SpendRefusal.Reason.UNIT_MISMATCH)
            val after = try { Math.addExact(p.atoms, request.held.atoms) } catch (e: ArithmeticException) { return refuse(null) }
            if (after > line.limit.atoms) return refuse(null)
        }
        val fields = mutableMapOf<String, Any?>(
            "kind" to "spend", "spend_id" to request.spendId, "attempt" to request.attempt,
            "instrument" to request.instrument, "source" to request.source,
            "rules" to request.lines.map { mapOf("id" to it.rule, "period" to it.period) },
            "principal" to mapOf("kind" to request.principalKind, "id" to request.principalId),
            "held" to moneyMap(request.held), "state" to State.HELD.id,
        )
        request.applet?.let { fields["applet"] = it }
        request.action?.let { fields["action"] = it }
        request.allowance?.let { fields["allowance"] = it }
        request.pricesVersion?.let { fields["prices_version"] = it }
        return HoldResult.Held(append(w, head, fields, key))
    }

    /**
     * Replaces the hold with the real cost. A cost above the hold is recorded as it is: the period's count
     * passes its limit and everything further is refused (G5.4). From an unknown outcome, or as a correction
     * of an earlier settlement, a **lower** figure counts only when [qualifying] says it came from a
     * qualifying provider read (G5.3 steps 4 and 6). A charge found after a give-back settles it again.
     */
    @Synchronized fun settle(spendId: String, cost: Money, providerRef: String? = null, qualifying: Boolean = false, reason: String? = null, attempt: Int = 0): Entry =
        write { w, head ->
            val prior = prior(w, head, spendId, attempt)
            val held = prior.held!!
            if (cost.unit != held.unit) throw JournalRefused("unit_mismatch")
            cost.requireAmount()
            when (prior.state) {
                State.HELD -> Unit
                State.UNKNOWN -> if (cost < held && !qualifying) throw JournalRefused("lower_needs_a_qualifying_read")
                State.SETTLED -> {
                    if (cost == prior.settled) return@write prior
                    if (cost < prior.settled!! && !qualifying) throw JournalRefused("lower_needs_a_qualifying_read")
                }
                State.RELEASED -> if (cost.isZero) return@write prior
                State.REFUNDED, null -> throw JournalRefused("not_from_${prior.state?.id}")
            }
            append(w, head, next(prior, State.SETTLED, reason) { it["settled"] = moneyMap(cost); providerRef?.let { r -> it["provider_ref"] = text(r) }; if (qualifying) it["qualifying"] = true }, spendKey(spendId, attempt))
        }

    /**
     * Gives the whole hold back. Only for an amount **proven unspent**: from a hold whose adapter answered
     * that nothing was billed, or from an unknown outcome when a qualifying read shows no charge.
     */
    @Synchronized fun release(spendId: String, reason: String, qualifying: Boolean = false, attempt: Int = 0): Entry = write { w, head ->
        val prior = prior(w, head, spendId, attempt)
        when (prior.state) {
            State.HELD -> Unit
            State.UNKNOWN -> if (!qualifying) throw JournalRefused("unknown_keeps_its_hold")
            State.RELEASED -> return@write prior
            else -> throw JournalRefused("not_from_${prior.state?.id}")
        }
        append(w, head, next(prior, State.RELEASED, reason) { if (qualifying) it["qualifying"] = true }, spendKey(spendId, attempt))
    }

    /** Records that the outcome is not known. The hold stays, whole, in every period it was opened in. */
    @Synchronized fun unknown(spendId: String, reason: String, attempt: Int = 0): Entry = write { w, head ->
        val prior = prior(w, head, spendId, attempt)
        when (prior.state) {
            State.HELD -> append(w, head, next(prior, State.UNKNOWN, reason) {}, spendKey(spendId, attempt))
            State.UNKNOWN -> prior
            else -> throw JournalRefused("not_from_${prior.state?.id}")
        }
    }

    /** Money returned after settlement. A new entry that points at the settled one; the period it was spent in is not reopened (G5.4). */
    @Synchronized fun refund(spendId: String, amount: Money, providerRef: String? = null, attempt: Int = 0): Entry = write { w, head ->
        val prior = prior(w, head, spendId, attempt)
        if (prior.state != State.SETTLED) throw JournalRefused("not_from_${prior.state?.id}")
        if (amount.unit != prior.settled!!.unit) throw JournalRefused("unit_mismatch")
        if (amount.requireAmount().isZero || amount > prior.settled!!) throw JournalRefused("refund_out_of_range")
        append(w, head, next(prior, State.REFUNDED, null) { it["refunded"] = moneyMap(amount); it["refers"] = prior.seq; providerRef?.let { r -> it["provider_ref"] = text(r) } }, spendKey(spendId, attempt))
    }

    /**
     * Settles, at its hold, every unknown outcome recorded before [beforeWallMs]: the end of the journal's
     * retention, which is contract data the caller brings (no value is fixed yet). Returns how many.
     */
    @Synchronized fun settleUnknownAtHold(beforeWallMs: Long): Int = write { w, head ->
        val stale = latestOfEach(w, head).filter { it.state == State.UNKNOWN && it.wallMs < beforeWallMs }
        for (e in stale) {
            val key = spendKey(e.spendId!!, e.body.getInt("attempt"))
            append(w, head, next(e, State.SETTLED, "retention") { it["settled"] = moneyMap(e.held!!) }, key)
        }
        stale.size
    }

    /** The latest entry of a spend in this epoch, or null. */
    @Synchronized fun latest(spendId: String, attempt: Int = 0): Entry? = read { w -> latest(w, loadHead(w), spendKey(spendId, attempt)) }

    /** Entries of [epoch] (default: the current one) after [afterSeq], in order. */
    @Synchronized fun entries(epoch: String? = null, afterSeq: Long = 0): List<Entry> = read { w -> rows(w, epoch ?: loadHead(w).epoch, afterSeq) }

    /** Every epoch this file holds rows of, oldest first. */
    @Synchronized fun epochs(): List<String> = read { w ->
        w.rawQuery("SELECT epoch FROM entries GROUP BY epoch ORDER BY MIN(rowid)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }

    /** The cloud has stored [epoch] up to [seq] (G5.3 "Upload": the position is kept per epoch). Never moves backwards. */
    @Synchronized fun acknowledge(epoch: String, seq: Long) = write { w, head ->
        if (seq > (head.acked[epoch] ?: 0)) { head.acked[epoch] = seq; saveHead(w, head) }
    }

    @Synchronized fun acknowledged(epoch: String): Long = read { w -> loadHead(w).acked[epoch] ?: 0 }

    /** Null when the key, the head and the whole chain verify; otherwise what does not. Changes nothing. */
    @Synchronized fun verify(): String? = read { w -> (inspect(w) as? Check.Broken)?.reason }

    // ---- storage ----

    /** A spend's latest entry as the verified chain gave it. */
    private class Latest(val seq: Long, val hash: String, val charge: Long)

    private class Past(val epoch: String, val seq: Long, val entry: Entry?)

    /**
     * What the last full check of the chain established, valid while the head still stands at [seq]: each
     * spend's latest entry in this epoch, and every spend the rows of earlier epochs name.
     */
    private class Index(val epoch: String, var seq: Long, val latest: HashMap<String, Latest>, val history: Map<String, Past>, val historyDigest: String?)

    private var index: Index? = null

    private sealed class Check {
        object Absent : Check()
        object Whole : Check()
        class Broken(val reason: String, val epoch: String?) : Check()
    }

    private fun start() {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            when (val c = inspect(w)) {
                Check.Whole -> Unit
                Check.Absent -> begin(w, Origin.FIRST, null)
                is Check.Broken -> { begin(w, Origin.RESET, c.reason); reset = Reset(c.reason, c.epoch) }
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    private fun begin(w: SQLiteDatabase, origin: Origin, reason: String?) {
        val epoch = keys.create(namespace)
        require(EPOCH.matches(epoch)) { "An epoch is 32 hex digits" }
        val head = SpendState(epoch, origin, 0, ZERO, installId)
        val fields = mutableMapOf<String, Any?>("kind" to "epoch", "install" to text(installId), "origin" to origin.id)
        reason?.let { fields["reason"] = it }
        index = null
        append(w, head, fields, null)
    }

    /** Key, head, then the whole chain of the head's epoch, replayed to the head's totals. */
    private fun inspect(w: SQLiteDatabase): Check {
        index = null
        val row = w.rawQuery("SELECT body, mac FROM head WHERE id = 1", null).use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }
        val keyEpoch = keys.epoch(namespace)
        val anyEntries = w.rawQuery("SELECT 1 FROM entries LIMIT 1", null).use { it.moveToFirst() }
        if (row == null) return if (!anyEntries && keyEpoch == null) Check.Absent else Check.Broken("head_missing", null)
        val head = runCatching { parseHead(row.first) }.getOrNull() ?: return Check.Broken("head_unreadable", null)
        fun broken(reason: String) = Check.Broken(reason, head.epoch)
        if (keyEpoch == null) return broken("key_missing")
        if (keyEpoch != head.epoch) return broken("epoch_mismatch")
        if (!macOk(head.epoch, headBytes(row.first), row.second)) return broken("head_mac")
        val json = JSONObject(row.first)
        if (json.getString("namespace") != namespace.id) return broken("namespace")
        if (json.getString("install") != installId) return broken("install_changed")

        val replay = SpendState(head.epoch, head.origin, 0, ZERO, installId)
        val charges = HashMap<String, Long>()
        val latest = HashMap<String, Latest>()
        for (e in rowsWithMac(w, head.epoch, 0)) {
            val b = runCatching { e.first.body }.getOrNull() ?: return broken("entry_unreadable")
            val ok = e.first.seq == replay.seq + 1 && b.optLong("seq") == e.first.seq && b.optString("prev") == replay.hash &&
                b.optString("epoch") == head.epoch && b.optString("namespace") == namespace.id && b.optString("v") == VERSION &&
                e.first.hash == hashOf(e.first.json) && macOk(head.epoch, Hex.decode(e.first.hash), e.second)
            if (!ok) return broken("chain_broken_at_${e.first.seq}")
            if (e.first.seq == 1L && !(b.optString("kind") == "epoch" && b.optString("install") == installId && b.optString("origin") == head.origin.id)) return broken("first_entry")
            if (e.first.seq > 1L && b.optString("kind") == "epoch") return broken("chain_broken_at_${e.first.seq}")
            runCatching { replay.apply(e.first.tree, charges) }.getOrElse { return broken("entry_unreadable") }
            // The lookup column is outside the hash, so it is held to the authenticated fields here.
            val key = if (b.optString("kind") == "spend") spendKey(b.getString("spend_id"), b.getInt("attempt")) else null
            if (e.third != key) return broken("lookup_column_at_${e.first.seq}")
            if (key != null) latest[key] = Latest(e.first.seq, e.first.hash, charges.getValue(key))
            replay.seq = e.first.seq
            replay.hash = e.first.hash
        }
        if (replay.seq == 0L || replay.seq != head.seq || replay.hash != head.hash) return broken("head_behind_chain")
        if (Canonical.encode(replay.stateMap()) != Canonical.encode(head.stateMap())) return broken("totals")
        val (history, unreadable) = history(w, head.epoch)
        index = Index(head.epoch, head.seq, latest, history, unreadable)
        return Check.Whole
    }

    /** The head as stored, MAC checked, and matching the last entry row. Throws [JournalNotWhole] otherwise. */
    private fun loadHead(w: SQLiteDatabase): SpendState {
        val row = w.rawQuery("SELECT body, mac FROM head WHERE id = 1", null).use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }
            ?: throw JournalNotWhole("head_missing")
        val head = runCatching { parseHead(row.first) }.getOrNull() ?: throw JournalNotWhole("head_unreadable")
        if (!macOk(head.epoch, headBytes(row.first), row.second)) throw JournalNotWhole("head_mac")
        val last = w.rawQuery("SELECT seq, hash FROM entries WHERE epoch = ? ORDER BY seq DESC LIMIT 1", arrayOf(head.epoch)).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null }
        if (last == null || last.first != head.seq || last.second != head.hash) throw JournalNotWhole("head_behind_chain")
        return head
    }

    private fun saveHead(w: SQLiteDatabase, head: SpendState) {
        val body = Canonical.encode(
            mapOf(
                "v" to "head/1", "namespace" to namespace.id, "install" to installId, "epoch" to head.epoch, "origin" to head.origin.id,
                "seq" to head.seq, "hash" to head.hash,
                "acked" to head.acked.toMap(),
                // The monotonic checkpoint of G5.2. Not replayed: what a roll-over is measured from is in the entries.
                "clock" to mapOf("wall" to clock.wallMs(), "elapsed" to clock.elapsedMs(), "boot_count" to clock.bootCount()),
            ) + head.stateMap(),
        )
        val mac = keys.mac(namespace, head.epoch, headBytes(body)) ?: throw JournalNotWhole("key_missing")
        val v = ContentValues().apply { put("id", 1); put("body", body); put("mac", Hex.of(mac)) }
        w.insertWithOnConflict("head", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun parseHead(body: String): SpendState {
        val j = Tree.parse(body)
        check(j.str("v") == "head/1")
        val head = SpendState(j.str("epoch")!!, Origin.values().first { it.id == j.str("origin") }, j.long("seq")!!, j.str("hash")!!, installId)
        head.load(j)
        return head
    }

    /** Writes one entry and the head that follows from it. The caller holds the transaction. */
    private fun append(w: SQLiteDatabase, head: SpendState, fields: Map<String, Any?>, spendKey: String?): Entry {
        val seq = head.seq + 1
        val body = Canonical.encode(
            fields + mapOf(
                "v" to VERSION, "namespace" to namespace.id, "epoch" to head.epoch, "seq" to seq, "prev" to head.hash,
                "t_wall" to clock.wallMs(), "t_elapsed" to clock.elapsedMs(), "boot" to clock.bootCount(),
            ),
        )
        val hash = hashOf(body)
        val mac = keys.mac(namespace, head.epoch, Hex.decode(hash)) ?: throw JournalNotWhole("key_missing")
        val entry = Entry(head.epoch, seq, hash, body)
        val prior = spendKey?.let { sync(w, head).latest[it]?.charge }
        val v = ContentValues().apply { put("epoch", head.epoch); put("seq", seq); put("spend_key", spendKey); put("body", body); put("hash", hash); put("mac", Hex.of(mac)) }
        w.insertOrThrow("entries", null, v)
        afterEntryWrite?.invoke()
        val charges = HashMap<String, Long>()
        if (spendKey != null && prior != null) charges[spendKey] = prior
        head.apply(entry.tree, charges)
        head.seq = seq
        head.hash = hash
        saveHead(w, head)
        index?.takeIf { it.epoch == head.epoch && it.seq == seq - 1 }?.let { i ->
            i.seq = seq
            if (spendKey != null) i.latest[spendKey] = Latest(seq, hash, charges.getValue(spendKey))
        }
        return entry
    }

    /** The fields of a spend's next entry: its identity carried forward, a new state, and what [more] adds. */
    private fun next(prior: Entry, state: State, reason: String?, more: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> {
        val b = prior.body
        val fields = mutableMapOf<String, Any?>(
            "kind" to "spend", "spend_id" to b.getString("spend_id"), "attempt" to b.getInt("attempt"),
            "instrument" to b.getString("instrument"), "source" to b.getString("source"),
            "rules" to b.getJSONArray("rules").let { r -> (0 until r.length()).map { i -> r.getJSONObject(i).let { mapOf("id" to it.getString("id"), "period" to it.getString("period")) } } },
            "principal" to b.getJSONObject("principal").let { mapOf("kind" to it.getString("kind"), "id" to it.getString("id")) },
            "held" to moneyMap(prior.held!!), "state" to state.id,
        )
        for (k in listOf("applet", "action", "allowance", "prices_version", "provider_ref")) if (b.has(k)) fields[k] = b.getString(k)
        prior.settled?.let { fields["settled"] = moneyMap(it) }
        reason?.let { fields["reason"] = text(it) }
        more(fields)
        return fields
    }

    private fun prior(w: SQLiteDatabase, head: SpendState, spendId: String, attempt: Int): Entry =
        latest(w, head, spendKey(spendId, attempt)) ?: throw JournalRefused("no_such_spend")

    /** The verified index for the head as it stands, rebuilt by a full check when the head has moved under it. */
    private fun sync(w: SQLiteDatabase, head: SpendState): Index {
        index?.takeIf { it.epoch == head.epoch && it.seq == head.seq }?.let { return it }
        (inspect(w) as? Check.Broken)?.let { throw JournalNotWhole(it.reason) }
        return index ?: throw JournalNotWhole("head_missing")
    }

    /** A spend's latest entry: the row the verified chain named, and only if it is still that row. */
    private fun latest(w: SQLiteDatabase, head: SpendState, key: String): Entry? {
        val known = sync(w, head).latest[key] ?: return null
        return w.rawQuery("SELECT hash, body, spend_key FROM entries WHERE epoch = ? AND seq = ?", arrayOf(head.epoch, known.seq.toString())).use { c ->
            if (!c.moveToFirst() || c.getString(0) != known.hash || hashOf(c.getString(1)) != known.hash || c.getString(2) != key) throw JournalNotWhole("entry_changed_at_${known.seq}")
            Entry(head.epoch, known.seq, known.hash, c.getString(1))
        }
    }

    private fun latestOfEach(w: SQLiteDatabase, head: SpendState): List<Entry> =
        sync(w, head).latest.entries.sortedBy { it.value.seq }.map { latest(w, head, it.key)!! }

    /**
     * Every spend the rows of other epochs name, by what each row itself says (the column only when the row
     * cannot be read). Those rows cannot be verified any more, and that is no reason to forget them. The
     * digest is of the rows that can be tied to no spend at all (null when there are none): while such rows are
     * not acknowledged, nothing can be said to be new.
     */
    private fun history(w: SQLiteDatabase, current: String): Pair<Map<String, Past>, String?> {
        val out = HashMap<String, Past>()
        var unreadable: MessageDigest? = null
        w.rawQuery("SELECT epoch, seq, hash, body, spend_key FROM entries WHERE epoch != ? ORDER BY rowid", arrayOf(current)).use { c ->
            while (c.moveToNext()) {
                val entry = Entry(c.getString(0), c.getLong(1), c.getString(2), c.getString(3))
                val named = runCatching { entry.body.takeIf { it.getString("kind") == "spend" }?.let { spendKey(it.getString("spend_id"), it.getInt("attempt")) } }
                val column = if (c.isNull(4)) null else c.getString(4)
                when {
                    named.isSuccess -> listOfNotNull(named.getOrNull(), column).forEach { out[it] = Past(entry.epoch, entry.seq, entry.takeIf { _ -> named.getOrNull() != null }) }
                    column != null -> out[column] = Past(entry.epoch, entry.seq, null)
                    else -> {
                        val d = unreadable ?: MessageDigest.getInstance("SHA-256").also { unreadable = it }
                        d.update("${entry.epoch}\n${entry.seq}\n${entry.hash}\n${entry.json}\n".toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
        return out to unreadable?.let { Hex.of(it.digest()) }
    }

    private fun rows(w: SQLiteDatabase, epoch: String, afterSeq: Long): List<Entry> = rowsWithMac(w, epoch, afterSeq).map { it.first }

    private fun rowsWithMac(w: SQLiteDatabase, epoch: String, afterSeq: Long): List<Triple<Entry, String, String?>> =
        w.rawQuery("SELECT seq, hash, body, mac, spend_key FROM entries WHERE epoch = ? AND seq > ? ORDER BY seq", arrayOf(epoch, afterSeq.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(Triple(Entry(epoch, c.getLong(0), c.getString(1), c.getString(2)), c.getString(3), if (c.isNull(4)) null else c.getString(4))) } }

    private fun macOk(epoch: String, data: ByteArray, macHex: String): Boolean {
        val mac = keys.mac(namespace, epoch, data) ?: return false
        return MessageDigest.isEqual(mac, runCatching { Hex.decode(macHex) }.getOrNull() ?: return false)
    }

    private fun refuse(reason: SpendRefusal.Reason?) = HoldResult.Refused(SpendRefusal(SpendRefusal.Code.ALLOWANCE_EXHAUSTED, reason))

    private fun usageOf(p: PeriodState?, period: String): Usage =
        if (p == null || p.period != period || p.full) Usage.FullyUsed else if (p.overflow) Usage.Overflowed else Usage.Counted(Money(p.unit!!, p.atoms))

    private inline fun <T> read(f: (SQLiteDatabase) -> T): T = f(db.readableDatabase)

    private inline fun <T> write(f: (SQLiteDatabase, SpendState) -> T): T {
        val w = db.writableDatabase
        var ok = false
        w.beginTransaction()
        try {
            val out = f(w, loadHead(w))
            w.setTransactionSuccessful()
            ok = true
            return out
        } finally {
            w.endTransaction()
            // What a rolled-back transaction added to the index was never stored.
            if (!ok) index = null
        }
    }

    companion object {
        const val VERSION = "entry/1"
        const val ENTRY_LABEL = "bridgit-spend-entry-v1"
        const val HEAD_LABEL = "bridgit-spend-head-v1"
        private val ZERO = "0".repeat(64)
        private val EPOCH = Regex("^[0-9a-f]{32}$")
        private val PRINCIPALS = setOf("owner", "agent", "applet")
        private const val MAX_TEXT = 200

        /**
         * Opens the journal of [db]'s namespace for [installId], checking it whole. A journal that is missing
         * with no key is begun as a first one; anything else that does not verify begins a new epoch.
         */
        fun open(db: SpendingDb, keys: JournalKeys, installId: String, clock: SpendClock): Journal =
            Journal(db, keys, installId, clock).also { synchronized(it) { it.start() } }

        /** SHA-256 of the entry label, a line feed and the entry's canonical JSON, as hex. */
        fun hashOf(body: String): String = Hex.of(MessageDigest.getInstance("SHA-256").digest("$ENTRY_LABEL\n$body".toByteArray(Charsets.UTF_8)))

        private fun headBytes(body: String) = "$HEAD_LABEL\n$body".toByteArray(Charsets.UTF_8)
        private fun spendKey(spendId: String, attempt: Int) = "$spendId#$attempt"
        internal fun moneyMap(m: Money) = mapOf("unit" to m.unit, "atoms" to m.atoms)

        /** Journal text is ids and short reasons: printable ASCII, 1 to 200 characters. */
        internal fun text(s: String): String {
            require(s.isNotEmpty() && s.length <= MAX_TEXT && s.all { it in ' '..'~' }) { "Journal text must be 1 to $MAX_TEXT printable ASCII characters" }
            return s
        }
    }
}
