package cc.bridgit.app.core.spending

/**
 * Moves of budget room between executors (`transfer/1`; spending authority G5.1, revised 5.3).
 *
 * - **Debit first.** [give] lowers this install's own portion, headroom balance or reclaim right in a
 *   journal entry (`transfer_out`) that commits with the head, and only then signs. A device whose debit did
 *   not commit signs nothing; one that crashed between the two signs the same transfer again ([reissue]),
 *   with the same id and no second debit.
 * - **One recipient, taken once.** [take] writes `transfer_in` with the transfer's id in one transaction
 *   with the head. Only the executor in `to` can use it; a copy that comes back changes nothing; one for
 *   another recipient, namespace or budget gives nothing (`transfer_not_for_recipient`).
 * - **A sender's chain.** Each sender's transfers carry a counter and the digest of the one before. What
 *   this device sees of a chain ([take], [observe]) is checked against what the sender had: transfers past
 *   that, in counter order, are void, and so are both of two under one counter. The unspent part of a taken
 *   one is dropped, and nothing more is taken from that sender until a document lowers its portion. A new
 *   transfer that by itself passes what this device knows the sender had is only [Took.UNPROVEN]: this device
 *   may simply not have seen what gave the sender the room, and voiding is not undone.
 * - **The reclaim right** is held by one executor per budget and moves only by a transfer of kind
 *   `reclaim`. Only its holder may take an ended share ([claimEnded]).
 *
 * The bytes that are signed are the label `bridgit-spend-transfer-v1`, a line feed, and the transfer as
 * canonical JSON without its signature. Nothing from the cloud's key is taken in this build.
 */
class Transfers(
    private val journal: Journal, private val signer: SpendSigner, private val book: SignerBook, private val rules: RuleDocuments,
    private val events: (SpendEvent) -> Unit = {},
) {
    sealed class Given {
        /** The signed transfer, to carry to its recipient. */
        data class Signed(val json: String, val id: String) : Given()
        data class Refused(val why: String, val verdict: Gates.Verdict? = null) : Given()
    }

    enum class Took {
        TAKEN, ALREADY_TAKEN,

        /** Not this executor's: another recipient, namespace or budget. Logged as a security event. */
        NOT_FOR_RECIPIENT,

        /** Made against a rule document this device does not hold yet. Kept, and taken up when it arrives. */
        WAITING,

        /** For one period only, and that period has ended. */
        PERIOD_ENDED,

        /** Void by its sender's chain: nothing was taken, or what was taken lost its unspent part. */
        VOID,

        /** Recorded as a link of its sender's chain; it was not this install's to take. */
        SEEN,

        /**
         * By what this device has seen, its sender did not have this: more room than the sender's share and
         * what was moved to it, or the reclaim right from an executor this device does not know as the
         * holder. Nothing was taken, recorded or voided. **The carrier presents first what gave the sender
         * the room** (the transfers made to it, its own increments, the earlier moves of the right), and
         * then this again. A transfer that stays unproven is simply never used.
         */
        UNPROVEN,
        REFUSED,
    }

    data class Taken(val result: Took, val why: String? = null)

    /**
     * Gives up room and signs the transfer. [kind] is `portion` (needs [amount]), `headroom` (needs [amount])
     * or `reclaim` (no amount). [capped] says every source this device has in the budget's scope is bounded
     * outside the app: only then may the recipient use the room in the current period; otherwise from the
     * next (G5.1 "What a surrender frees, and when"). [floor] is what a mandate keeps under the portion.
     */
    fun give(budget: String, to: String, kind: String, amount: Money?, lasts: String, capped: Boolean, caller: Caller, capability: Boolean, floor: Long = 0): Given {
        val debited = journal.transact { tx -> debit(tx, budget, to, kind, amount, lasts, capped, caller, capability, floor) }
        val body = debited.first ?: return debited.second!!
        // Only now, with the debit committed, does a signature exist.
        return Given.Signed(Canonical.encode(Signed.sign(Signed.TRANSFER, body, signer)), body.str("id")!!)
    }

    /** Signs again a transfer whose debit is already in this epoch's journal: the same bytes, the same id. */
    fun reissue(id: String): Given {
        val body = journal.entries().firstOrNull { it.kind == "transfer_out" && it.tree.map("transfer")!!.str("id") == id }?.tree?.map("transfer") ?: return Given.Refused("no_such_debit")
        return Given.Signed(Canonical.encode(Signed.sign(Signed.TRANSFER, body, signer)), id)
    }

    /** Takes a transfer that names this install. */
    fun take(json: String): Taken = journal.transact { tx -> receive(tx, json, mine = true) }

    /** Records a transfer that names another executor: a link of its sender's chain, a move of the reclaim right, or another executor's claim. */
    fun observe(json: String): Taken = journal.transact { tx -> receive(tx, json, mine = false) }

    /**
     * Takes an ended share (G5.1 item 3): the holder of the budget's reclaim right signs a transfer from the
     * ended executor to itself, with [evidence] of what showed the authority had ended (for a standing
     * share, a provider read that shows its source revoked or replaced). There is only one such transfer
     * per ended executor and budget, and it is taken once. Anyone else's claim is void everywhere.
     *
     * **[evidence] is the caller's word here.** This records it and checks nothing in it, so no door may
     * offer this until the caller is the code that made a verified provider read of the ended source.
     */
    fun claimEnded(budget: String, ended: String, evidence: Map<String, String>, caller: Caller, capability: Boolean): Given = journal.transact { tx ->
        val verdict = Gates.move(caller.kind, Gates.Env(book.approverEnrolled(), capability))
        if (verdict !is Gates.Verdict.Allowed) return@transact Given.Refused("gate", verdict)
        val doc = rules.current(tx) ?: return@transact Given.Refused("no_rule_document")
        val b = doc.budgets[budget] ?: return@transact Given.Refused("no_such_budget")
        val me = tx.install
        if (ended == me || ended !in b.devices) return@transact Given.Refused("not_an_executor")
        if (!Portions.holdsReclaim(tx.state, doc, budget, me)) return@transact Given.Refused("not_the_holder")
        if (evidence.isEmpty()) return@transact Given.Refused("no_evidence")
        val id = Signed.id(Signed.RECLAIM_ID, ended, tx.namespace.id, budget)
        // Used up once, for every holder the right has had: the claims travel with the right.
        if (id in tx.state.taken || tx.state.claimed[budget]?.contains(id) == true) return@transact Given.Refused("already_taken")
        PeriodClock.roll(tx, budget, b.period, doc.zone)
        val period = tx.state.periods[budget]?.period ?: return@transact Given.Refused("period_not_open")
        val left = Portions.had(tx.state, doc, budget, ended, "portion", null) - given(tx.state, ended, budget, "portion")
        if (left <= 0) return@transact Given.Refused("nothing_left")
        val body = LinkedHashMap<String, Any?>()
        body["v"] = VERSION; body["id"] = id; body["namespace"] = tx.namespace.id; body["budget"] = budget
        body["parent"] = mapOf("generation" to doc.generation, "digest" to doc.digest)
        body["kind"] = "portion"; body["amount"] = mapOf("unit" to b.unit, "atoms" to left)
        body["from"] = ended; body["to"] = me
        body["effective"] = mapOf("from_period" to period, "lasts" to "standing")
        body["evidence"] = evidence
        body["actor"] = ActorRecord.over(Signed.TRANSFER, body, caller.kind, caller.id, caller.door, me).toMap()
        val signed = Signed.sign(Signed.TRANSFER, body, signer)
        tx.append(mapOf("kind" to "transfer_in", "transfer" to signed, "digest" to Signed.digest(Signed.TRANSFER, signed), "claim" to true, "evidence" to evidence, "backing" to backing(doc, budget, ended, "portion")))
        Given.Signed(Canonical.encode(signed), id)
    }

    // ---- the sender's side ----

    private fun debit(tx: Journal.Tx, budget: String, to: String, kind: String, amount: Money?, lasts: String, capped: Boolean, caller: Caller, capability: Boolean, floor: Long): Pair<Map<String, Any?>?, Given?> {
        fun no(why: String, verdict: Gates.Verdict? = null) = null to Given.Refused(why, verdict)
        val verdict = Gates.move(caller.kind, Gates.Env(book.approverEnrolled(), capability))
        if (verdict !is Gates.Verdict.Allowed) return no("gate", verdict)
        val doc = rules.current(tx) ?: return no("no_rule_document")
        val b = doc.budgets[budget] ?: return no("no_such_budget")
        val me = tx.install
        if (me !in b.devices) return no("not_an_executor")
        if (to == CLOUD) return no("cloud_not_in_this_build")
        if (to == me || to !in b.devices) return no("recipient_not_an_executor")
        if (lasts != "period" && lasts != "standing") return no("lasts")
        // A device whose journal is not whole signs no surrender (G5.1 item 1): its count for the period is not known.
        if (Portions.frozen(tx.state, b, me)) return no("frozen_after_reset")
        PeriodClock.roll(tx, budget, b.period, doc.zone)
        val p = tx.state.periods[budget]
        if (p == null || p.full || p.overflow) return no("journal_not_whole")
        val from: String
        when (kind) {
            "portion" -> {
                if (amount == null || amount.unit != b.unit || amount.atoms <= 0) return no("amount")
                // The debit leaves no less than what is held, settled and unknown in the period: the whole journal, payments included.
                val left = Portions.portion(tx.state, doc, budget, me, p.period) - amount.atoms
                if (left < p.atoms) return no("below_spent")
                if (left < floor) return no("below_mandate_floor")
                // A standing transfer is backed over its whole life: a top-up or a transfer for one period cannot stand behind it.
                if (lasts == "standing" && !Portions.backedForGood(tx.state, doc, budget, me, p.period, amount.atoms)) return no("not_backed_for_its_lifetime")
                from = if (capped) p.period else PeriodClock.next(b.period, doc.zone, Journal.Period(p.period, p.starts, p.ends)).id
            }
            "headroom" -> {
                if (amount == null || amount.unit != b.unit || amount.atoms <= 0 || b.ceiling == null) return no("amount")
                if (lasts != "standing") return no("lasts")
                if (amount.atoms > Portions.headroom(tx.state, doc, budget, me, p.period)) return no("over_headroom")
                from = p.period
            }
            "reclaim" -> {
                if (amount != null || lasts != "standing") return no("amount")
                if (!Portions.holdsReclaim(tx.state, doc, budget, me)) return no("not_the_holder")
                from = p.period
            }
            else -> return no("kind")
        }
        val counter = tx.state.transferCounter + 1
        val body = LinkedHashMap<String, Any?>()
        body["v"] = VERSION
        body["id"] = Signed.id(Signed.TRANSFER_ID, me, tx.namespace.id, tx.state.epoch, counter.toString())
        body["counter"] = counter; body["prev"] = tx.state.transferLast
        body["namespace"] = tx.namespace.id; body["budget"] = budget
        body["parent"] = mapOf("generation" to doc.generation, "digest" to doc.digest)
        body["kind"] = kind
        amount?.let { body["amount"] = Journal.moneyMap(it) }
        // The right goes with the claims its holders have used up, so the next holder cannot take an ended share again.
        if (kind == "reclaim") body["claimed"] = (tx.state.claimed[budget] ?: emptySet<String>()).toList()
        body["from"] = me; body["to"] = to
        body["effective"] = mapOf("from_period" to from, "lasts" to lasts)
        // The debit is the very next entry of this journal.
        body["journal"] = mapOf("epoch" to tx.state.epoch, "seq" to tx.state.seq + 1)
        body["actor"] = ActorRecord.over(Signed.TRANSFER, body, caller.kind, caller.id, caller.door, me).toMap()
        tx.append(mapOf("kind" to "transfer_out", "transfer" to body, "digest" to Signed.digest(Signed.TRANSFER, body), "debit_period" to p.period))
        return body to null
    }

    // ---- the recipient's side, and everyone who sees a transfer ----

    internal fun receive(tx: Journal.Tx, json: String, mine: Boolean): Taken {
        val t = runCatching { Tree.parse(json) }.getOrNull()?.takeIf(::wellFormed) ?: return Taken(Took.REFUSED, "malformed")
        val me = tx.install
        val id = t.str("id")!!
        val budget = t.str("budget")!!
        val from = t.str("from")!!
        val to = t.str("to")!!
        val claim = t["counter"] == null
        val forMe = to == me && t.str("namespace") == tx.namespace.id
        // A transfer to this install has one way in. Seen first and taken later, a move of the right would apply twice.
        if (!mine && forMe && !claim) return receive(tx, json, mine = true)
        if (mine && (!forMe || claim)) return notForRecipient(id, to)
        if (t.str("namespace") != tx.namespace.id) return Taken(Took.REFUSED, "namespace")
        if (from == CLOUD || to == CLOUD) return Taken(Took.REFUSED, "cloud_not_in_this_build")

        // A claim is signed by its taker; everything else by the executor that gives up the room.
        val signedBy = if (claim) to else from
        val accepted = book.standing("install", signedBy).let { it == SignerBook.Standing.OWN_LINK || it == SignerBook.Standing.OWN } &&
            ActorRecord.matches(Signed.TRANSFER, t) && Signed.verified(Signed.TRANSFER, t, book, "install", signedBy)
        if (!accepted) {
            events(SpendEvent(SpendEvent.SECURITY, mapOf("what" to "transfer_signer", "signer" to signedBy)))
            return Taken(Took.REFUSED, "signer_not_accepted")
        }
        val expected = if (claim) Signed.id(Signed.RECLAIM_ID, from, tx.namespace.id, budget) else Signed.id(Signed.TRANSFER_ID, from, tx.namespace.id, t.map("journal")!!.str("epoch")!!, t.long("counter").toString())
        if (id != expected) return Taken(Took.REFUSED, "id")
        val parent = t.map("parent")!!.str("digest")!!
        val made = rules.get(tx, parent) ?: run { tx.park(id, if (mine) "transfer" else "transfer_seen", parent, json); return Taken(Took.WAITING) }
        val doc = rules.current(tx) ?: return Taken(Took.REFUSED, "no_rule_document")
        val b = doc.budgets[budget]
        if (mine && (b == null || me !in b.devices)) return notForRecipient(id, to)
        if (from !in (made.budgets[budget]?.devices ?: emptyMap()) || (b != null && to !in b.devices)) return Taken(Took.REFUSED, "not_an_executor")
        val digest = Signed.digest(Signed.TRANSFER, t)
        val known = tx.state.peers[from]?.seen?.any { it.id == id && it.digest == digest } == true
        // A copy of what was taken changes nothing. Another transfer under the same id is two under one counter: it goes on to the chain check.
        val sameId = tx.state.peers[from]?.seen?.any { it.id == id } == true
        if (id in tx.state.taken && (known || !sameId)) return Taken(Took.ALREADY_TAKEN)
        // An install that recovered into a new epoch was given this install's list of what it had signed. Anything it
        // signed before that and shows only now is not in the list it rebuilt from, so it is not taken up.
        if (!claim && !known) tx.state.recovered[from]?.let { if (t.map("journal")!!.str("epoch") != it) return Taken(Took.REFUSED, "sender_recovered") }

        if (claim) {
            // Only the holder of the reclaim right may take an ended share. Any other claim is void, whatever document its signer holds.
            if (tx.state.reclaim[budget] != to) {
                events(SpendEvent(SpendEvent.SECURITY, mapOf("what" to "claim_not_by_holder", "signer" to to, "budget" to budget)))
                return Taken(Took.VOID, "claim_not_by_holder")
            }
            if (known) return Taken(Took.SEEN)
            // One claim per ended executor and budget, whoever holds the right now.
            if (tx.state.claimed[budget]?.contains(id) == true) {
                events(SpendEvent(SpendEvent.SECURITY, mapOf("what" to "claim_already_used", "signer" to to, "budget" to budget)))
                return Taken(Took.VOID, "already_claimed")
            }
            // And no more than the ended executor had left, by what this device has seen.
            if (t.map("amount")!!.long("atoms")!! > Portions.had(tx.state, doc, budget, from, "portion", null) - given(tx.state, from, budget, "portion")) return Taken(Took.UNPROVEN, "claim_past_what_it_had")
            if (!known) tx.append(mapOf("kind" to "transfer_seen", "transfer" to t, "digest" to digest))
            return Taken(Took.SEEN)
        }

        // The right moves only from its holder. From anyone else it is either forged or ahead of a move this device has not seen.
        if (t.str("kind") == "reclaim" && tx.state.reclaim[budget] != from) return Taken(Took.UNPROVEN, "not_from_the_holder")
        val conflict = if (known) null else conflict(tx.state, doc, t, digest)
        // Past what the sender had, and nothing this device already holds is displaced by it: unproven, not void.
        if (conflict != null && conflict.second == "past_what_it_had" && conflict.first == listOf(id) && id !in tx.state.taken) return Taken(Took.UNPROVEN, conflict.second)
        if (mine) {
            if (tx.state.peers[from]?.barred?.containsKey(budget) == true) return Taken(Took.REFUSED, "sender_barred")
            if (conflict == null) {
                if (b != null) PeriodClock.roll(tx, budget, b.period, doc.zone)
                val current = tx.state.periods[budget]?.period
                val effective = t.map("effective")!!
                if (effective.str("lasts") == "period" && current != null && effective.str("from_period")!! < current) {
                    if (!known) tx.append(mapOf("kind" to "transfer_seen", "transfer" to t, "digest" to digest))
                    return Taken(Took.PERIOD_ENDED)
                }
                val fields = mutableMapOf<String, Any?>("kind" to "transfer_in", "transfer" to t, "digest" to digest)
                backing(made, budget, from, t.str("kind")!!)?.let { fields["backing"] = it }
                tx.append(fields)
                return Taken(Took.TAKEN)
            }
        }
        if (!known) tx.append(mapOf("kind" to "transfer_seen", "transfer" to t, "digest" to digest))
        if (conflict != null) {
            void(tx, doc, from, budget, conflict.first, conflict.second)
            return Taken(Took.VOID, conflict.second)
        }
        return Taken(Took.SEEN)
    }

    /** What stood behind a transfer in the document it was made against: its sender's share, and the whole that was a share of. */
    private fun backing(made: RuleDocument, budget: String, from: String, kind: String): Map<String, Any?>? {
        val b = made.budgets[budget] ?: return null
        return when (kind) {
            "portion" -> mapOf("base" to (b.devices[from] ?: 0), "total" to b.amount)
            "headroom" -> mapOf("base" to b.headroom(from), "total" to (b.ceiling?.let { it - b.amount } ?: 0))
            else -> null
        }
    }

    private fun notForRecipient(id: String, to: String): Taken {
        events(SpendEvent(SpendEvent.SECURITY, mapOf("what" to "transfer_not_for_recipient", "transfer" to id, "to" to to)))
        return Taken(Took.NOT_FOR_RECIPIENT, SpendRefusal.Code.TRANSFER_NOT_FOR_RECIPIENT.id)
    }

    /** The transfers [t] makes void together with what this device has seen of its sender's chain, and why; null when the chain holds. */
    private fun conflict(state: SpendState, doc: RuleDocument, t: Map<String, Any?>, digest: String): Pair<List<String>, String>? {
        val from = t.str("from")!!
        val now = Seen.ofTransfer(t, digest)
        val seen = state.peers[from]?.seen ?: emptyList<Seen>()
        seen.firstOrNull { it.key == now.key }?.let { return listOf(it.id, now.id) to "two_under_one_counter" }
        val counter = now.key.substringAfter(':').toLong()
        seen.firstOrNull { it.key == "${now.key.substringBefore(':')}:${counter - 1}" }?.let { if (it.digest != t.str("prev")) return listOf(now.id) to "chain_broken" }
        if (now.kind == "reclaim") return null
        // In counter order, whatever passes what the sender had is void: this one, or ones seen before it that come later in the chain.
        val had = { period: String? -> Portions.had(state, doc, now.budget, from, now.kind, period) }
        val chain = (seen.filter { it.budget == now.budget && it.kind == now.kind && !it.voided && !it.key.startsWith("claim:") } + now)
            // Counter order; between two epochs of one sender there is no order to find, so the id decides, the same on every device.
            .sortedWith(compareBy({ it.key.substringAfter(':').toLong() }, { it.id }))
        val kept = ArrayList<Seen>()
        val past = ArrayList<String>()
        for (s in chain) { if (fits(kept + s, had)) kept += s else past += s.id }
        return if (past.isEmpty()) null else past to "past_what_it_had"
    }

    /**
     * Whether [transfers] together stay within what the sender [had] in every period one of them starts in,
     * and for good. A standing one counts from its first period on; one for a single period counts in that
     * period only, so yesterday's does not stand against today's.
     */
    private fun fits(transfers: List<Seen>, had: (String?) -> Long): Boolean {
        val periods = transfers.mapNotNull { it.from }.distinct().all { period ->
            transfers.filter { if (it.lasts == "standing") it.from!! <= period else it.from == period }.fold(0L) { a, s -> Math.addExact(a, s.atoms) } <= had(period)
        }
        // And for good: what stands must be backed by what the sender has for good, not by room that runs out with a period.
        return periods && transfers.filter { it.lasts == "standing" }.fold(0L) { a, s -> Math.addExact(a, s.atoms) } <= had(null)
    }

    /** What [sender] has given of [kind] in [budget], by the transfers this device saw and still holds valid. */
    private fun given(state: SpendState, sender: String, budget: String, kind: String): Long =
        (state.peers[sender]?.seen ?: emptyList<Seen>()).filter { it.budget == budget && it.kind == kind && !it.voided && !it.key.startsWith("claim:") }.fold(0L) { a, s -> Math.addExact(a, s.atoms) }

    /**
     * Voids [ids] of [sender]: the unspent part of any this install took is dropped and what was spent stays
     * counted, `spend.transfer_conflict` is raised, and nothing more is taken from that sender in this
     * budget until a document lowers its portion to what its valid transfers leave.
     */
    private fun void(tx: Journal.Tx, doc: RuleDocument, sender: String, budget: String, ids: List<String>, why: String) {
        val state = tx.state
        val period = state.periods[budget]?.takeIf { !it.full }
        val kept = LinkedHashMap<String, Any?>()
        var portion = period?.let { Portions.portion(state, doc, budget, tx.install, it.period) } ?: 0
        for (m in state.moves) if (m.id in ids && m.dir == "in" && !m.voided && m.kind == "portion" && period != null) {
            val part = m.amountIn(period.period)
            val without = portion - part
            val keep = (period.atoms - without).coerceIn(0, part)
            kept[m.id] = keep
            portion = without + keep
        }
        val left = (doc.budgets[budget]?.devices?.get(sender) ?: 0) - (state.peers[sender]?.seen ?: emptyList<Seen>())
            .filter { it.budget == budget && it.kind == "portion" && !it.voided && it.id !in ids && !it.key.startsWith("claim:") }.fold(0L) { a, s -> Math.addExact(a, s.atoms) }
        val fields = mutableMapOf<String, Any?>("kind" to "transfer_void", "sender" to sender, "budget" to budget, "ids" to ids, "kept" to kept, "bar" to maxOf(left, 0), "why" to why)
        period?.let { fields["period"] = it.period }
        tx.append(fields)
        events(SpendEvent(SpendEvent.TRANSFER_CONFLICT, mapOf("sender" to sender, "budget" to budget, "why" to why)))
    }

    // ---- rebuilding a reset install's standing record ----

    /** What a reset install asks the other executors to answer: its new journal epoch and a challenge made in it. */
    data class Recovery(val epoch: String, val challenge: String)

    /**
     * After a reset: the challenge the other executors of [budget] bind their lists to. It is made once per
     * budget in this epoch and written to the journal, so a list answers this recovery and no other: one
     * saved from before, or made for an earlier epoch, rebuilds nothing.
     */
    fun recover(budget: String): Recovery = journal.transact { tx ->
        val challenge = tx.state.recovery[budget] ?: Hex.of(ByteArray(16).also(java.security.SecureRandom()::nextBytes)).also {
            tx.append(mapOf("kind" to "recovery", "budget" to Journal.text(budget), "challenge" to it))
        }
        Recovery(tx.state.epoch, challenge)
    }

    /**
     * What this install holds about [about] in [budget], signed (`holdings/1`): every transfer that install
     * signed, of every kind; every increment it signed; and every transfer made to it. A reset install
     * rebuilds its record from one such list from each other executor ([rebuild]). The list answers one
     * recovery: it carries [about]'s new journal [epoch] and its [challenge]. **Giving it is a commitment**:
     * it is written to this journal, and from then on nothing [about] signed in an earlier epoch is taken up
     * here, so what the list says stays the whole of what this install will ever hold from before the reset.
     */
    fun holdings(budget: String, about: String, epoch: String, challenge: String): String {
        val body = journal.transact { tx ->
            if (tx.state.recovered[about] != epoch) tx.append(mapOf("kind" to "holdings_given", "about" to Journal.text(about), "budget" to Journal.text(budget), "into" to epoch))
            val transfers = LinkedHashMap<String, Map<String, Any?>>()
            val increments = LinkedHashMap<String, Map<String, Any?>>()
            for (e in journal.entries()) when (e.kind) {
                "transfer_in", "transfer_seen", "transfer_out" -> e.tree.map("transfer")!!.let { t ->
                    if (t.str("budget") == budget && (t.str("from") == about || t.str("to") == about)) transfers[t.str("id")!!] = if (e.kind == "transfer_out") Signed.sign(Signed.TRANSFER, t, signer) else t
                }
                "increment" -> e.tree.map("increment")!!.let { i -> if (i.str("budget") == budget && i.str("portion") == about) increments[i.str("id")!!] = i }
            }
            mapOf(
                "v" to HOLDINGS_VERSION, "namespace" to tx.namespace.id, "budget" to budget, "about" to about, "by" to tx.install,
                "epoch" to epoch, "challenge" to challenge,
                "rules" to tx.state.rulesDigest?.let { mapOf("generation" to tx.state.rulesGeneration, "digest" to it) },
                "transfers" to transfers.values.toList(), "increments" to increments.values.toList(),
            )
        }
        return Canonical.encode(Signed.sign(Signed.HOLDINGS, body, signer))
    }

    enum class Rebuilt { DONE, NOT_RESET, NOT_FROZEN, MISSING_A_LIST, BAD_LIST }

    /**
     * After a reset: restores this install's portion, headroom balance and reclaim right for [budget]
     * together, from the document and the signed lists of **every** other device that is an executor of it,
     * each answering this epoch's challenge ([recover]). One that does not answer keeps everything frozen. A transfer this install signed counts whoever lists
     * it (that can only lower). One made to it counts only as it would if it were taken now: with its sender's
     * signature, id and actor, and no more from one sender than that sender had. An increment of its own
     * counts only with its own signature. An increment of its own that nobody holds is lost with the journal: that loses room, it
     * never doubles it. Lists the cloud holds are not taken in this build.
     */
    fun rebuild(budget: String, lists: List<String>): Rebuilt = journal.transact { tx ->
        val state = tx.state
        val me = tx.install
        if (state.origin != Journal.Origin.RESET) return@transact Rebuilt.NOT_RESET
        val doc = rules.current(tx) ?: return@transact Rebuilt.MISSING_A_LIST
        val b = doc.budgets[budget] ?: return@transact Rebuilt.NOT_FROZEN
        if (!Portions.frozen(state, b, me)) return@transact Rebuilt.NOT_FROZEN
        val challenge = state.recovery[budget] ?: return@transact Rebuilt.MISSING_A_LIST
        val parsed = lists.map { runCatching { Tree.parse(it) }.getOrNull() ?: return@transact Rebuilt.BAD_LIST }
        val by = HashMap<String, Map<String, Any?>>()
        for (l in parsed) {
            val peer = l.str("by") ?: return@transact Rebuilt.BAD_LIST
            val ok = l.str("v") == HOLDINGS_VERSION && l.str("namespace") == tx.namespace.id && l.str("budget") == budget && l.str("about") == me && peer != me &&
                l.str("epoch") == state.epoch && l.str("challenge") == challenge &&
                book.standing("install", peer) == SignerBook.Standing.OWN_LINK && Signed.verified(Signed.HOLDINGS, l, book, "install", peer)
            if (!ok) return@transact Rebuilt.BAD_LIST
            by[peer] = l
        }
        if (!by.keys.containsAll(b.devices.keys - me)) return@transact Rebuilt.MISSING_A_LIST

        val moves = LinkedHashMap<String, Move>()
        val taken = sortedSetOf<String>()
        val applied = sortedSetOf<String>()
        val incoming = LinkedHashMap<String, Map<String, Any?>>()
        val claimed = sortedSetOf<String>()
        var gaveRightTo: String? = null
        var gotRight = false
        for (l in by.values) {
            for (x in l.list("transfers")) {
                @Suppress("UNCHECKED_CAST") val t = x as Map<String, Any?>
                if (!wellFormed(t) || t.str("budget") != budget || t.str("namespace") != tx.namespace.id) return@transact Rebuilt.BAD_LIST
                val id = t.str("id")!!
                if (t.str("from") == me && t["counter"] != null) {
                    moves.putIfAbsent("out:$id", Move.ofTransfer(t, "out", null))
                    if (t.str("kind") == "reclaim") gaveRightTo = t.str("to")
                    t.list("claimed").forEach { claimed += it as String }
                } else if (t.str("to") == me) {
                    incoming.putIfAbsent(id, t)
                }
            }
            for (x in l.list("increments")) {
                @Suppress("UNCHECKED_CAST") val i = x as Map<String, Any?>
                if (i.str("portion") != me || i.str("budget") != budget || !Signed.verified(Signed.INCREMENT, i, book, "install", me)) return@transact Rebuilt.BAD_LIST
                applied += i.str("id")!!
                if (i.str("id") !in state.applied) moves.putIfAbsent("inc:${i.str("id")}", Move.ofIncrement(i))
            }
        }
        // What was made to this install is held to what taking it would be: its own signature, id and actor, a sender that
        // is an executor, and no more from one sender than that sender had. What this install gave a sender counts for it.
        val got = HashMap<String, ArrayList<Seen>>()
        for ((id, t) in incoming.entries.sortedWith(compareBy({ it.value.long("counter") ?: 0 }, { it.key }))) {
            val from = t.str("from")!!
            val claim = t["counter"] == null
            val signedBy = if (claim) me else from
            val expected = if (claim) Signed.id(Signed.RECLAIM_ID, from, tx.namespace.id, budget) else Signed.id(Signed.TRANSFER_ID, from, tx.namespace.id, t.map("journal")!!.str("epoch")!!, t.long("counter").toString())
            val ok = id == expected && from in b.devices && ActorRecord.matches(Signed.TRANSFER, t) && Signed.verified(Signed.TRANSFER, t, book, "install", signedBy) &&
                (claim || book.standing("install", from) == SignerBook.Standing.OWN_LINK)
            if (!ok) return@transact Rebuilt.BAD_LIST
            // Never taken again, whether or not it counts.
            taken += id
            if (claim) claimed += id
            t.list("claimed").forEach { claimed += it as String }
            if (id in state.taken) continue
            val kind = t.str("kind")!!
            // Counted only against the document it was made against, which says what stood behind it.
            val made = rules.get(tx, t.map("parent")!!.str("digest")!!) ?: continue
            if (kind != "reclaim") {
                val gave = { period: String? -> moves.values.filter { it.dir == "out" && it.kind == kind && it.peer == from && (it.lasts == "standing" || (period != null && it.from == period)) }.fold(0L) { a, m -> Math.addExact(a, m.atoms) } }
                val had = { period: String? -> Portions.had(state, doc, budget, from, kind, period) + gave(period) }
                val all = got.getOrPut("$from:$kind") { ArrayList() }
                val seen = Seen.ofTransfer(t, "")
                if (!fits(all + seen, had)) continue
                all += seen
            } else gotRight = true
            moves.putIfAbsent("in:$id", Move.ofTransfer(t, "in", null, backing(made, budget, from, kind)))
        }
        val fields = mutableMapOf<String, Any?>(
            "kind" to "rebuilt", "budget" to budget, "moves" to moves.values.map { it.toMap() }, "taken" to taken.toList(), "applied" to applied.toList(),
            "lists" to by.entries.sortedBy { it.key }.map { mapOf("by" to it.key, "digest" to Signed.digest(Signed.HOLDINGS, it.value)) },
        )
        // The right may have been passed on and come back in an order the lists cannot show: having ever signed it away, the install does not hold it.
        (gaveRightTo ?: me.takeIf { gotRight })?.let { fields["holder"] = it }
        fields["claimed"] = claimed.toList()
        tx.append(fields)
        Rebuilt.DONE
    }

    companion object {
        const val VERSION = "transfer/1"
        const val HOLDINGS_VERSION = "holdings/1"
        const val CLOUD = "cloud"
        private val HASH = Regex("^[0-9a-f]{64}$")

        internal fun wellFormed(t: Map<String, Any?>): Boolean = runCatching {
            val kind = t.str("kind")!!
            val amount = t.map("amount")
            val effective = t.map("effective")!!
            val parent = t.map("parent")!!
            val claim = t["counter"] == null
            t.str("v") == VERSION && HASH.matches(t.str("id")!!) && HASH.matches(parent.str("digest")!!) && parent.long("generation")!! >= 1 &&
                kind in setOf("portion", "headroom", "reclaim") && (kind == "reclaim") == (amount == null) &&
                (amount == null || (Units.of(amount.str("unit")!!) != null && amount.long("atoms")!! > 0)) &&
                effective.str("lasts") in setOf("period", "standing") && effective.str("from_period")!!.isNotEmpty() &&
                (t["claimed"] == null || (kind == "reclaim" && t.list("claimed").all { it is String && HASH.matches(it) })) &&
                listOf(t.str("budget")!!, t.str("from")!!, t.str("to")!!, t.str("namespace")!!).all { Journal.text(it).isNotEmpty() } && t.str("from") != t.str("to") &&
                (if (claim) kind == "portion" && t["prev"] == null && t["journal"] == null
                else t.long("counter")!! >= 1 && (t["prev"] == null || HASH.matches(t.str("prev")!!)) && t.map("journal")!!.let { it.str("epoch")!!.length == 32 && it.long("seq")!! >= 1 })
        }.getOrDefault(false)
    }
}
