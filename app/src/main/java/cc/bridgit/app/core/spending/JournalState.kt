package cc.bridgit.app.core.spending

import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap
import java.util.TreeSet

/** JSON as plain values: sorted maps, lists, strings, whole numbers as Long, booleans and null. A fraction is refused. */
internal object Tree {
    fun of(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> TreeMap<String, Any?>().also { m -> for (k in v.keys()) m[k] = of(v.get(k)) }
        is JSONArray -> ArrayList<Any?>().also { l -> for (i in 0 until v.length()) l.add(of(v.get(i))) }
        is Int -> v.toLong()
        is Long, is String, is Boolean -> v
        else -> throw IllegalArgumentException("Not a whole-number JSON value")
    }

    @Suppress("UNCHECKED_CAST")
    fun parse(json: String): Map<String, Any?> = of(JSONObject(json)) as Map<String, Any?>
}

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.map(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as? List<Any?> ?: emptyList()
internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String
internal fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
internal fun Map<String, Any?>.flag(key: String): Boolean = this[key] == true

internal class PeriodState(
    val period: String, val starts: Long, val ends: Long, var full: Boolean, val unit: String?, var atoms: Long, var overflow: Boolean = false,
    /** Why it was opened, and the monotonic clock then: what an unanchored roll-over is measured from (G5.2). */
    val basis: String = "", val openedElapsed: Long = 0, val openedBoot: Long = 0,
)

/**
 * The time this journal can reason from (G5.2): [t] was the time when the monotonic clock read [elapsed] in
 * boot [boot]. It stands only while the device has not rebooted. [anchored] is false for the wall-clock time
 * a first portion was opened at, which names a period and can roll one over only after a full period of
 * running time. [floor] is the latest anchor accepted: an earlier one changes nothing (rule 4).
 */
internal data class TimeBase(val t: Long, val elapsed: Long, val boot: Long, val anchored: Boolean, val floor: Long?) {
    fun toMap(): Map<String, Any?> = mapOf("t" to t, "elapsed" to elapsed, "boot" to boot, "anchored" to anchored, "floor" to floor)
}

/**
 * One change to what this install may spend or raise beyond the rule document: a transfer it signed
 * ([dir] `out`), one it took (`in`), or an increment (`inc`; [peer] is its signer). Amounts are standing or
 * for one period ([lasts]); [from] is the first period it counts in. A voided move keeps only what had
 * already been spent on it, in the period it was voided in.
 */
internal class Move(
    val id: String, val dir: String, val kind: String, val budget: String, val unit: String?, val atoms: Long,
    val from: String?, val lasts: String, val peer: String, val agent: Boolean, val debit: String?,
    var voided: Boolean = false, var kept: Long = 0, var keptPeriod: String? = null,
    /** For an increment: its actor was the owner. [agent] says it was drawn from the signer's headroom balance. */
    val owner: Boolean = false,
    /**
     * For a transfer taken: what stood behind it in the rule document it was made against: its sender's share
     * ([base]) and the whole it was a share of ([total]: the budget's amount, or for headroom the room under
     * the ceiling). A later document that lowers both lowers what the transfer conveys ([conveyed]).
     */
    val base: Long? = null, val total: Long? = null,
) {
    fun toMap(): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>("id" to id, "dir" to dir, "kind" to kind, "budget" to budget, "atoms" to atoms, "lasts" to lasts, "peer" to peer)
        unit?.let { m["unit"] = it }
        from?.let { m["from"] = it }
        debit?.let { m["debit"] = it }
        if (agent) m["agent"] = true
        if (owner) m["owner"] = true
        base?.let { m["base"] = it }
        total?.let { m["total"] = it }
        if (voided) { m["void"] = true; m["kept"] = kept; keptPeriod?.let { m["kept_period"] = it } }
        return m
    }

    /** What it adds in [period]: its amount while it runs there, or the kept part of a voided one. */
    fun amountIn(period: String): Long = when {
        voided -> if (keptPeriod == period) kept else 0
        lasts == "standing" -> if (from == null || from <= period) atoms else 0
        else -> if (from == period) atoms else 0
    }

    /**
     * What a transfer taken still conveys in [period] when its sender's share is now [share] and the whole is
     * now [whole]. A reduction of the budget that also cut the sender's share below what it had given away
     * would otherwise leave the room standing twice: once gone from the sender, who cannot go below zero, and
     * once with the recipient. So the transfer loses the lesser of the two cuts. That is the most the
     * sender's shortfall can be, whatever else it gave away, so portions never add up to more than the
     * reduced budget; a document that lowers a share and leaves the budget alone takes nothing from it.
     */
    fun conveyed(period: String, share: Long, whole: Long): Long {
        val amount = amountIn(period)
        if (voided || base == null || total == null) return amount
        val cut = minOf(maxOf(base - share, 0), maxOf(total - whole, 0))
        return maxOf(amount - cut, 0)
    }

    /** What it adds for good: nothing for a move of one period, or a void one. */
    fun standing(): Long = if (voided || lasts != "standing") 0 else atoms

    /** What an outgoing move takes away in [period]: from the period of the debit on, whatever period the taker may use it in. */
    fun debitIn(period: String): Long = when {
        debit != null && period < debit -> 0
        lasts == "standing" -> atoms
        else -> if (from == null || period <= from) atoms else 0
    }

    companion object {
        fun of(m: Map<String, Any?>) = Move(
            m.str("id")!!, m.str("dir")!!, m.str("kind")!!, m.str("budget")!!, m.str("unit"), m.long("atoms")!!, m.str("from"), m.str("lasts")!!,
            m.str("peer")!!, m.flag("agent"), m.str("debit"), m.flag("void"), m.long("kept") ?: 0, m.str("kept_period"), m.flag("owner"), m.long("base"), m.long("total"),
        )

        fun ofTransfer(t: Map<String, Any?>, dir: String, debit: String?, backing: Map<String, Any?>? = null): Move {
            val amount = t.map("amount")
            val effective = t.map("effective")!!
            return Move(
                t.str("id")!!, dir, t.str("kind")!!, t.str("budget")!!, amount?.str("unit"), amount?.long("atoms") ?: 0,
                effective.str("from_period"), effective.str("lasts")!!, if (dir == "out") t.str("to")!! else t.str("from")!!, false, debit,
                base = backing?.long("base"), total = backing?.long("total"),
            )
        }

        fun ofIncrement(i: Map<String, Any?>): Move {
            val amount = i.map("amount")!!
            return Move(
                i.str("id")!!, "inc", "portion", i.str("budget")!!, amount.str("unit"), amount.long("atoms")!!, i.str("period"), i.str("lasts")!!,
                i.str("portion")!!, i.flag("draws_headroom"), null, owner = i.map("actor")?.str("kind") == "owner",
            )
        }
    }
}

/** A transfer another install signed, as this device saw it: one link of that sender's chain (G5.1 "A sender that signs twice"). */
internal class Seen(
    val key: String, val id: String, val digest: String, val budget: String, val kind: String, val atoms: Long,
    val from: String?, val lasts: String, val to: String, var voided: Boolean = false,
) {
    fun toMap(): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>("key" to key, "id" to id, "digest" to digest, "budget" to budget, "kind" to kind, "atoms" to atoms, "lasts" to lasts, "to" to to)
        from?.let { m["from"] = it }
        if (voided) m["void"] = true
        return m
    }

    companion object {
        fun of(m: Map<String, Any?>) = Seen(m.str("key")!!, m.str("id")!!, m.str("digest")!!, m.str("budget")!!, m.str("kind")!!, m.long("atoms")!!, m.str("from"), m.str("lasts")!!, m.str("to")!!, m.flag("void"))

        fun ofTransfer(t: Map<String, Any?>, digest: String): Seen {
            // An ended share taken by the holder of the reclaim right has no counter: there is only one such transfer.
            val key = t.long("counter")?.let { "${t.map("journal")!!.str("epoch")}:$it" } ?: "claim:${t.str("id")}"
            val effective = t.map("effective")!!
            return Seen(
                key, t.str("id")!!, digest, t.str("budget")!!, t.str("kind")!!,
                t.map("amount")?.long("atoms") ?: 0, effective.str("from_period"), effective.str("lasts")!!, t.str("to")!!,
            )
        }
    }
}

internal class Peer {
    val seen = ArrayList<Seen>()

    /** Budgets nothing more is taken in from this sender, each with the most its portion may be in a document that lifts the bar. */
    val barred = TreeMap<String, Long>()

    fun toMap(): Map<String, Any?> = mapOf("seen" to seen.map { it.toMap() }, "barred" to barred.toMap())
}

/**
 * Everything the head says beyond the chain position: what the entries of this epoch add up to. It is
 * produced only by [apply], entry by entry, so a full check of the chain rebuilds it and compares it with the
 * stored head (G5.3 rule 2). Nothing here is checked for sense: the code that writes an entry has done that
 * inside the same transaction, and the entry is under the MAC.
 */
internal class SpendState(val epoch: String, val origin: Journal.Origin, var seq: Long, var hash: String, private val install: String) {
    val periods = TreeMap<String, PeriodState>()
    val acked = TreeMap<String, Long>()

    /** Budgets a rule document of this epoch named this install an executor of (G5.3 rule 4). */
    val budgets = TreeSet<String>()
    var rulesGeneration: Long? = null
    var rulesDigest: String? = null
    val allowances = TreeMap<String, Map<String, Any?>>()
    var transferCounter = 0L
    var transferLast: String? = null
    val taken = TreeSet<String>()
    var incrementCounter = 0L
    val applied = TreeSet<String>()
    val moves = ArrayList<Move>()

    /** Who holds each budget's reclaim right, as far as this device has seen (G5.1 item 3). */
    val reclaim = TreeMap<String, String>()
    val peers = TreeMap<String, Peer>()
    var time: TimeBase? = null

    /** After a reset: budgets whose standing record was rebuilt from the other executors' lists (SA-R6). */
    val rebuilt = TreeSet<String>()
    var historyAck: String? = null

    /**
     * This install's own increments made since it last took up rule documents: the ones a document that
     * arrives next was made without knowing (G5.1 "A reduction the device had not seen"). Documents taken
     * one after another count as one contact; the first other entry after them starts a new list.
     */
    val fresh = TreeSet<String>()
    var settling = false

    /**
     * Per budget: the ids of the ended-share claims that are used up, as far as this device has seen. A claim
     * is used up for every later holder of the reclaim right too: the list travels with the right (SA-R5).
     */
    val claimed = TreeMap<String, TreeSet<String>>()

    /** After a reset, per budget: the challenge this epoch asked the other executors to answer with their lists. */
    val recovery = TreeMap<String, String>()

    /**
     * Per other install that asked this one for its list: the journal epoch it recovered into. From then on
     * nothing that install signed in an earlier epoch is taken up, so the list it was given stays the whole truth.
     */
    val recovered = TreeMap<String, String>()

    fun stateMap(): Map<String, Any?> = mapOf(
        "periods" to periods.mapValues { (_, p) ->
            val m = mutableMapOf<String, Any?>("period" to p.period, "starts" to p.starts, "ends" to p.ends, "full" to p.full, "basis" to p.basis, "opened" to mapOf("elapsed" to p.openedElapsed, "boot" to p.openedBoot))
            if (!p.full) m["counted"] = mapOf("unit" to p.unit, "atoms" to p.atoms)
            if (p.overflow) m["overflow"] = true
            m
        },
        "budgets" to budgets.toList(),
        "rules" to rulesDigest?.let { mapOf("generation" to rulesGeneration, "digest" to it) },
        "allowances" to allowances.toMap(),
        "allowance_seq" to allowances.mapValues { (_, a) -> a.long("seq") },
        "transfers" to mapOf("counter" to transferCounter, "last" to transferLast, "taken" to taken.toList()),
        "increments" to mapOf("counter" to incrementCounter, "applied" to applied.toList()),
        "moves" to moves.map { it.toMap() },
        "reclaim" to reclaim.toMap(),
        "peers" to peers.mapValues { (_, p) -> p.toMap() },
        "time" to time?.toMap(),
        "rebuilt" to rebuilt.toList(),
        "history_ack" to historyAck,
        "fresh" to mapOf("ids" to fresh.toList(), "settling" to settling),
        "claimed" to claimed.mapValues { it.value.toList() },
        "recovery" to recovery.toMap(),
        "recovered" to recovered.toMap(),
    )

    fun load(j: Map<String, Any?>) {
        for ((rule, v) in j.map("periods")!!) {
            @Suppress("UNCHECKED_CAST") val p = v as Map<String, Any?>
            val counted = p.map("counted")
            val opened = p.map("opened")!!
            periods[rule] = PeriodState(p.str("period")!!, p.long("starts")!!, p.long("ends")!!, p.flag("full"), counted?.str("unit"), counted?.long("atoms") ?: 0, p.flag("overflow"), p.str("basis")!!, opened.long("elapsed")!!, opened.long("boot")!!)
        }
        j.list("budgets").forEach { budgets += it as String }
        j.map("rules")?.let { rulesGeneration = it.long("generation"); rulesDigest = it.str("digest") }
        @Suppress("UNCHECKED_CAST")
        for ((source, a) in j.map("allowances")!!) allowances[source] = a as Map<String, Any?>
        j.map("transfers")!!.let { t -> transferCounter = t.long("counter")!!; transferLast = t.str("last"); t.list("taken").forEach { taken += it as String } }
        j.map("increments")!!.let { i -> incrementCounter = i.long("counter")!!; i.list("applied").forEach { applied += it as String } }
        @Suppress("UNCHECKED_CAST")
        j.list("moves").forEach { moves += Move.of(it as Map<String, Any?>) }
        for ((budget, holder) in j.map("reclaim")!!) reclaim[budget] = holder as String
        for ((sender, v) in j.map("peers")!!) {
            @Suppress("UNCHECKED_CAST") val p = v as Map<String, Any?>
            val peer = Peer()
            @Suppress("UNCHECKED_CAST")
            p.list("seen").forEach { peer.seen += Seen.of(it as Map<String, Any?>) }
            for ((budget, limit) in p.map("barred")!!) peer.barred[budget] = (limit as Number).toLong()
            peers[sender] = peer
        }
        j.map("time")?.let { time = TimeBase(it.long("t")!!, it.long("elapsed")!!, it.long("boot")!!, it.flag("anchored"), it.long("floor")) }
        j.list("rebuilt").forEach { rebuilt += it as String }
        historyAck = j.str("history_ack")
        j.map("fresh")!!.let { f -> f.list("ids").forEach { fresh += it as String }; settling = f.flag("settling") }
        @Suppress("UNCHECKED_CAST")
        for ((budget, ids) in j.map("claimed")!!) claimed[budget] = TreeSet((ids as List<String>))
        for ((budget, c) in j.map("recovery")!!) recovery[budget] = c as String
        for ((about, e) in j.map("recovered")!!) recovered[about] = e as String
        for ((e, s) in j.map("acked")!!) acked[e] = (s as Number).toLong()
    }

    /** What one entry does to the state. [charges] holds what each spend counted before this entry, and is updated. */
    fun apply(b: Map<String, Any?>, charges: MutableMap<String, Long>) {
        when (b.str("kind")) {
            "rules" -> settling = true
            "increment_void", "epoch" -> Unit
            else -> if (settling) { fresh.clear(); settling = false }
        }
        when (b.str("kind")) {
            "epoch" -> Unit
            "period" -> {
                val counted = b.map("counted")
                periods[b.str("rule")!!] = PeriodState(
                    b.str("period")!!, b.long("starts")!!, b.long("ends")!!, b.flag("full"), counted?.str("unit"), counted?.long("atoms") ?: 0,
                    false, b.str("basis")!!, b.long("t_elapsed")!!, b.long("boot")!!,
                )
                // A first portion opened with no time to go by leaves the wall clock it was opened at: not an anchor.
                if (time == null && b.str("basis") == Journal.Basis.FIRST_PORTION.id) time = TimeBase(b.long("t_wall")!!, b.long("t_elapsed")!!, b.long("boot")!!, false, null)
            }
            "spend" -> {
                val key = "${b.str("spend_id")}#${b.long("attempt")}"
                val now = charge(b)
                val delta = Math.subtractExact(now, charges[key] ?: 0)
                charges[key] = now
                for (l in b.list("rules")) {
                    @Suppress("UNCHECKED_CAST") val line = l as Map<String, Any?>
                    val p = periods[line.str("id")] ?: continue
                    // A period that has ended, or is still fully used, has no count to move.
                    if (p.period != line.str("period") || p.full || p.overflow) continue
                    // A settlement the count cannot hold is still recorded; the period is marked and refuses everything further (G5.4).
                    try { p.atoms = Math.addExact(p.atoms, delta) } catch (e: ArithmeticException) { p.atoms = Long.MAX_VALUE; p.overflow = true }
                }
            }
            "rules" -> {
                rulesGeneration = b.long("generation")
                rulesDigest = b.str("digest")
                b.list("budgets").forEach { budgets += it as String }
                b.map("made")?.forEach { (budget, by) -> if (budget !in reclaim) reclaim[budget] = by as String }
                for (u in b.list("unbar")) {
                    @Suppress("UNCHECKED_CAST") val m = u as Map<String, Any?>
                    peers[m.str("sender")]?.barred?.remove(m.str("budget"))
                }
            }
            "allowance" -> {
                val a = TreeMap(b.map("allowance")!!)
                a["at"] = mapOf("wall" to b.long("t_wall"), "elapsed" to b.long("t_elapsed"), "boot" to b.long("boot"))
                allowances[b.str("source")!!] = a
            }
            "anchor" -> time = TimeBase(b.long("t")!!, b.long("t_elapsed")!!, b.long("boot")!!, true, b.long("t"))
            "transfer_out" -> {
                val t = b.map("transfer")!!
                transferCounter = t.long("counter")!!
                transferLast = b.str("digest")
                moves += Move.ofTransfer(t, "out", b.str("debit_period"))
                if (t.str("kind") == "reclaim") reclaim[t.str("budget")!!] = t.str("to")!!
            }
            "transfer_in" -> {
                val t = b.map("transfer")!!
                taken += t.str("id")!!
                moves += Move.ofTransfer(t, "in", null, b.map("backing"))
                // The right changes hands once per transfer: only from the executor that holds it.
                if (t.str("kind") == "reclaim" && reclaim[t.str("budget")] == t.str("from")) reclaim[t.str("budget")!!] = t.str("to")!!
                claims(t)
                see(t, b.str("digest")!!)
            }
            "transfer_seen" -> {
                val t = b.map("transfer")!!
                // A transfer to this install is taken, never just seen: seeing one moves nothing to it.
                if (t.str("kind") == "reclaim" && reclaim[t.str("budget")] == t.str("from") && t.str("to") != install) reclaim[t.str("budget")!!] = t.str("to")!!
                claims(t)
                see(t, b.str("digest")!!)
            }
            "recovery" -> recovery[b.str("budget")!!] = b.str("challenge")!!
            "holdings_given" -> recovered[b.str("about")!!] = b.str("into")!!
            "transfer_void" -> {
                val ids = b.list("ids").toSet()
                val kept = b.map("kept") ?: emptyMap()
                val peer = peers.getOrPut(b.str("sender")!!) { Peer() }
                peer.seen.filter { it.id in ids }.forEach { it.voided = true }
                moves.filter { it.id in ids && it.dir == "in" }.forEach { it.voided = true; it.kept = (kept[it.id] as? Number)?.toLong() ?: 0; it.keptPeriod = b.str("period") }
                b.long("bar")?.let { peer.barred[b.str("budget")!!] = it }
            }
            "increment" -> {
                val i = b.map("increment")!!
                applied += i.str("id")!!
                if (i.str("portion") == install && i.map("journal")?.str("epoch") == epoch) { incrementCounter = maxOf(incrementCounter, i.long("counter")!!); fresh += i.str("id")!! }
                moves += Move.ofIncrement(i)
            }
            "increment_void" -> moves.filter { it.id == b.str("id") && it.dir == "inc" }.forEach { it.voided = true; it.kept = b.long("kept") ?: 0; it.keptPeriod = b.str("period") }
            "rebuilt" -> {
                rebuilt += b.str("budget")!!
                @Suppress("UNCHECKED_CAST")
                b.list("moves").forEach { m -> Move.of(m as Map<String, Any?>).let { if (moves.none { x -> x.id == it.id && x.dir == it.dir }) moves += it } }
                b.list("taken").forEach { taken += it as String }
                b.list("applied").forEach { applied += it as String }
                b.str("holder")?.let { reclaim[b.str("budget")!!] = it }
                b.list("claimed").forEach { claimed.getOrPut(b.str("budget")!!) { TreeSet() } += it as String }
            }
            "history_ack" -> {
                historyAck = b.str("digest")
                // The period a hidden request may belong to stays charged in full: a count rebuilt from a read is not kept.
                periods.values.filter { it.basis == Journal.Basis.PROVIDER_READ.id || it.basis == Journal.Basis.CLOUD_SETTLEMENT.id }.forEach { it.full = true }
            }
            else -> error("Unknown entry kind")
        }
    }

    /** An ended-share claim is used up once seen; a move of the right brings the claims its holders used up. */
    private fun claims(t: Map<String, Any?>) {
        val used = claimed.getOrPut(t.str("budget")!!) { TreeSet() }
        if (t["counter"] == null) used += t.str("id")!!
        t.list("claimed").forEach { used += it as String }
    }

    private fun see(t: Map<String, Any?>, digest: String) {
        val seen = Seen.ofTransfer(t, digest)
        val peer = peers.getOrPut(t.str("from")!!) { Peer() }
        if (peer.seen.none { it.id == seen.id && it.digest == seen.digest }) peer.seen += seen
    }

    /** What a spend counts in its periods: its hold while held or unknown, its real cost once settled (a refund does not lower it), nothing once released. */
    private fun charge(b: Map<String, Any?>): Long = when (b.str("state")) {
        Journal.State.HELD.id, Journal.State.UNKNOWN.id -> b.map("held")!!.long("atoms")!!
        Journal.State.SETTLED.id, Journal.State.REFUNDED.id -> b.map("settled")!!.long("atoms")!!
        Journal.State.RELEASED.id -> 0
        else -> error("Unknown state")
    }
}
