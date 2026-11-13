package cc.bridgit.app.core.spending

/**
 * The allowance a source spends under (`allowance/1`; spending authority G5.2). One device, one source; one
 * line per rule the source draws on. **A line adds no room beyond the device's portion** for that rule and
 * period: the device counts its whole period against its portion, however many allowances it was sent.
 *
 * Two issuers:
 * - `rules`: a **standing share**. The portion in the rule document is the allowance; nothing is sent and
 *   nothing expires before the period does. [standing] writes it down, so the head carries the source's
 *   sequence and each hold names the allowance it was made under.
 * - `cloud`: a **leased share**, a slice signed with the cloud's allowance key. It is verified against the
 *   key shipped in the app ([CloudAllowanceKey]), which is empty: **every cloud slice is refused in this
 *   build.** The checks behind the key are here and tested with a test key: replay by `seq`, the rule
 *   generation, the journal epoch, each line at or below the portion, never for a value-transfer source.
 *
 * **Expiry is by elapsed time.** The journal entry that took the allowance holds the monotonic clock then;
 * the allowance stops after `valid_for_s` of running time, or at `not_after` on the wall clock, whichever
 * is first. After a reboot the wall-clock gap counts, and a wall clock that went backwards ends it. Moving
 * the clock can only end an allowance early.
 */
class AllowanceStore internal constructor(private val rules: RuleDocuments, private val cloudKey: ByteArray) {
    /** With the key shipped in the app. Only a test can put another in its place. */
    constructor(rules: RuleDocuments) : this(rules, CloudAllowanceKey.PUBLIC)

    enum class Why { MALFORMED, NO_CLOUD_KEY, BAD_SIGNATURE, NOT_THIS_DEVICE, VALUE_TRANSFER, REPLAYED, OLDER_RULES, WAITING_FOR_RULES, OTHER_EPOCH, NOT_A_LEASED_SHARE, LINE_OVER_PORTION }

    sealed class Result {
        object Accepted : Result()
        data class Ignored(val why: Why) : Result()
    }

    /** Takes a cloud slice for [instrument]. [valueTransfer] is the instrument's: a cloud slice is never used for one (G5.1, E20). */
    fun accept(journal: Journal, json: String, valueTransfer: Boolean): Result = journal.transact { tx -> accept(tx, json, valueTransfer) }

    internal fun accept(tx: Journal.Tx, json: String, valueTransfer: Boolean): Result {
        val a = runCatching { Tree.parse(json) }.getOrNull()?.takeIf(::wellFormed) ?: return Result.Ignored(Why.MALFORMED)
        if (a.str("issuer") != "cloud") return Result.Ignored(Why.MALFORMED)
        if (cloudKey.isEmpty()) return Result.Ignored(Why.NO_CLOUD_KEY)
        val signature = Signed.decode(a.str("signature")) ?: return Result.Ignored(Why.BAD_SIGNATURE)
        if (!CloudAllowanceKey.verify(cloudKey, Signed.bytes(Signed.ALLOWANCE, a), signature)) return Result.Ignored(Why.BAD_SIGNATURE)
        if (a.str("device") != tx.install || Namespace.of(a.str("instrument")!!) != tx.namespace) return Result.Ignored(Why.NOT_THIS_DEVICE)
        if (valueTransfer) return Result.Ignored(Why.VALUE_TRANSFER)
        val source = a.str("source")!!
        // The highest seq accepted is part of the journal head and commits with it (SA-G5).
        if (a.long("seq")!! <= (tx.state.allowances[source]?.long("seq") ?: 0)) return Result.Ignored(Why.REPLAYED)
        if (a.str("journal_epoch") != tx.state.epoch) return Result.Ignored(Why.OTHER_EPOCH)
        val doc = rules.current(tx) ?: return Result.Ignored(Why.WAITING_FOR_RULES)
        val generation = a.long("rules_generation")!!
        if (generation > doc.generation) return Result.Ignored(Why.WAITING_FOR_RULES)
        if (generation < doc.generation || a.str("rules_digest") != doc.digest) return Result.Ignored(Why.OLDER_RULES)
        for (l in a.list("lines")) {
            @Suppress("UNCHECKED_CAST") val line = l as Map<String, Any?>
            val b = doc.budgets[line.str("rule")] ?: return Result.Ignored(Why.LINE_OVER_PORTION)
            if (b.kind(tx.install) != "leased") return Result.Ignored(Why.NOT_A_LEASED_SHARE)
            val amount = line.map("amount")!!
            // The issuer paces; it cannot enlarge.
            if (amount.str("unit") != b.unit || amount.long("atoms")!! > Portions.portion(tx.state, doc, b.id, tx.install, line.str("period")!!)) return Result.Ignored(Why.LINE_OVER_PORTION)
        }
        tx.append(mapOf("kind" to "allowance", "source" to source, "allowance" to (a - "signature")))
        return Result.Accepted
    }

    /**
     * The standing share of [source] as an allowance: [lines] are the budgets in its scope with this
     * install's portion in the open period. Written again only when the document or a line changed; the id
     * of the one in force is returned.
     */
    internal fun standing(tx: Journal.Tx, doc: RuleDocument, source: String, instrument: String, lines: List<Journal.Line>): String {
        val held = tx.state.allowances[source]
        val wire = lines.map { mapOf("rule" to it.rule, "period" to it.period, "amount" to Journal.moneyMap(it.limit)) }
        if (held != null && held.str("issuer") == "rules" && held.str("rules_digest") == doc.digest && Canonical.encode(held["lines"]) == Canonical.encode(wire)) return held.str("id")!!
        val seq = (held?.long("seq") ?: 0) + 1
        val id = Signed.id(Signed.ALLOWANCE_ID, doc.digest, tx.install, source, seq.toString())
        tx.append(
            mapOf(
                "kind" to "allowance", "source" to source,
                "allowance" to mapOf(
                    "v" to VERSION, "id" to id, "issuer" to "rules", "key" to (doc.signerId ?: "merged"), "account" to doc.account, "device" to tx.install,
                    "instrument" to instrument, "source" to source, "lines" to wire, "rules_generation" to doc.generation, "rules_digest" to doc.digest, "seq" to seq,
                ),
            ),
        )
        return id
    }

    /** The allowance in force for [source], as the head holds it (with `at`: when it arrived), or null. */
    internal fun held(tx: Journal.Tx, source: String): Map<String, Any?>? = tx.state.allowances[source]

    internal fun expired(tx: Journal.Tx, held: Map<String, Any?>): Boolean {
        val at = held.map("at")!!
        val wall = tx.clock.wallMs()
        held.long("not_after")?.let { if (wall >= it) return true }
        val valid = held.long("valid_for_s")?.let { Math.multiplyExact(it, 1000L) } ?: return false
        if (at.long("boot") == tx.clock.bootCount().toLong()) return tx.clock.elapsedMs() - at.long("elapsed")!! >= valid
        // After a reboot the running time is gone; the wall-clock gap stands in, and a clock that went backwards ends it.
        val gap = wall - at.long("wall")!!
        return gap < 0 || gap >= valid
    }

    companion object {
        const val VERSION = "allowance/1"
        private val HASH = Regex("^[0-9a-f]{64}$")

        internal fun wellFormed(a: Map<String, Any?>): Boolean = runCatching {
            a.str("v") == VERSION && a.str("issuer") in setOf("rules", "cloud") && a.long("seq")!! >= 1 && a.long("rules_generation")!! >= 1 && HASH.matches(a.str("rules_digest")!!) &&
                listOf(a.str("id")!!, a.str("key")!!, a.str("account")!!, a.str("device")!!, a.str("instrument")!!, a.str("source")!!).all { Journal.text(it).isNotEmpty() } &&
                (a["valid_for_s"] == null || a.long("valid_for_s")!! > 0) && (a["not_after"] == null || a.long("not_after")!! > 0) &&
                a.list("lines").isNotEmpty() && a.list("lines").all { l ->
                    @Suppress("UNCHECKED_CAST") val line = l as Map<String, Any?>
                    Journal.text(line.str("rule")!!).isNotEmpty() && Journal.text(line.str("period")!!).isNotEmpty() && Units.of(line.map("amount")!!.str("unit")!!) != null && line.map("amount")!!.long("atoms")!! >= 0
                }
        }.getOrDefault(false)
    }
}
