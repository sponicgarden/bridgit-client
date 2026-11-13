package cc.bridgit.app.core.spending

import java.util.TreeMap

/**
 * The rule documents this device holds (spending authority G6.1), and how one becomes the current one.
 *
 * - **Signers.** A document is taken only when it is signed by this install's key, an approver key or the
 *   install key of an `own`-tier link ([SignerBook]). Nothing under the cloud's key is taken in this build.
 *   Anything else is ignored and raised as a security event.
 * - **Whole ancestry.** A document is taken only when this device holds its parent, back to the first
 *   document of the account. One whose parent is missing waits. That is what lets every device know who
 *   made each budget, and so who holds its reclaim right; a device never has to guess.
 * - **Never upward.** A successor that restates an amount, a portion or a headroom balance upward is
 *   invalid everywhere ([Successor]). The gates of G6.2 are checked against the signed actor ([Gates]).
 * - **Forks merge.** Two valid successors of one parent merge by [Merge]; the merged document is computed
 *   here and never taken off the wire.
 * - **The current document is in the journal head**, by generation and digest; a document row is believed
 *   only if it hashes to the digest that names it.
 */
class RuleDocuments(private val signer: SpendSigner, private val book: SignerBook, private val events: (SpendEvent) -> Unit = {}) {
    sealed class Adopt {
        /** [current] is the document now in force: [document] itself, or the merge of it with what was held. */
        data class Adopted(val document: RuleDocument, val current: RuleDocument, val forked: List<String>) : Adopt()

        /** Already held; nothing changed. */
        object Known : Adopt()

        /** Its parent is not held yet. It is kept and taken up when the parent arrives. */
        object Waiting : Adopt()
        data class Refused(val why: String, val verdict: Gates.Verdict? = null) : Adopt()
    }

    /** Runs after a document became current, in the same transaction: what waits for it, and what it makes void. */
    internal var afterAdopt: ((Journal.Tx, RuleDocument) -> Unit)? = null

    internal fun current(tx: Journal.Tx): RuleDocument? = tx.state.rulesDigest?.let { get(tx, it) ?: throw JournalNotWhole("rule_document_missing") }

    internal fun get(tx: Journal.Tx, digest: String): RuleDocument? =
        tx.document(digest)?.let { runCatching { RuleDocument.parse(it) }.getOrNull() }?.takeIf { it.digest == digest }

    /** Takes a signed document from another device, or from this one. [capability]: whether the calling agent holds Budgets, when the change is made here. */
    internal fun adopt(tx: Journal.Tx, json: String, capability: Boolean = true): Adopt {
        val doc = runCatching { RuleDocument.parse(json) }.getOrNull() ?: return Adopt.Refused("malformed")
        doc.problem()?.let { return Adopt.Refused(it) }
        if (doc.merged) return Adopt.Refused("a_merge_is_computed_not_received")
        if (doc.namespace != tx.namespace.id) return Adopt.Refused("namespace")
        val kind = doc.signerKind
        val id = doc.signerId
        val actor = runCatching { doc.actor }.getOrNull()
        if (kind == null || id == null || actor == null || doc.executor == null || !ActorRecord.matches(Signed.RULES, doc.map)) return Adopt.Refused("unsigned")
        val standing = book.standing(kind, id)
        val accepted = standing != SignerBook.Standing.UNKNOWN && (kind == "approver") == (standing == SignerBook.Standing.APPROVER) &&
            (kind == "approver" || id == doc.executor) && actor.install == doc.executor && Signed.verified(Signed.RULES, doc.map, book, kind, id)
        if (!accepted) {
            events(SpendEvent(SpendEvent.SECURITY, mapOf("what" to "rule_document_signer", "signer" to id)))
            return Adopt.Refused("signer_not_accepted")
        }
        val held = get(tx, doc.digest) != null
        val parent = doc.parents.firstOrNull()?.let { get(tx, it) ?: run { tx.park(doc.digest, "rules", it, json); return Adopt.Waiting } }
        if (!held) {
            if (parent == null) {
                // The first document of the account. A second, different one has no common ancestor to merge on.
                root(tx)?.let { if (it != doc.digest) return Adopt.Refused("another_first_document") }
            } else {
                Successor.problem(parent, doc)?.let { return Adopt.Refused(it) }
            }
            setterProblem(parent, doc)?.let { return Adopt.Refused(it) }
            val env = Gates.Env(book.approverEnrolled(), capability)
            val verdict = Gates.change(parent, doc, actor.kind, kind, env)
            if (verdict !is Gates.Verdict.Allowed) return Adopt.Refused("gate", verdict)
            tx.putDocument(doc.digest, doc.generation, doc.json)
        }
        val before = tx.state.rulesDigest
        val (now, forked) = advance(tx, doc)
        if (held && tx.state.rulesDigest == before) return Adopt.Known
        return Adopt.Adopted(doc, now, forked)
    }

    /**
     * Makes a change on this device: [edit] turns the current content into the new one. Refused with
     * `conflict` when [against] is not the current document's digest (a change names the generation it
     * replaces). The gate's `set_by` is filled in here; a caller cannot choose it.
     */
    internal fun change(tx: Journal.Tx, caller: Caller, capability: Boolean, against: String?, account: String, edit: (Map<String, Any?>) -> Map<String, Any?>): Adopt {
        val cur = current(tx)
        if (cur?.digest != against) return Adopt.Refused("conflict")
        val content = edit(cur?.map?.filterKeys { it in RuleDocument.CONTENT } ?: RuleDocument.content())
        val body = TreeMap<String, Any?>(content.filterKeys { it in RuleDocument.CONTENT })
        body["v"] = RuleDocument.VERSION
        body["account"] = cur?.account ?: account
        body["namespace"] = tx.namespace.id
        body["generation"] = (cur?.generation ?: 0) + 1
        body["parents"] = listOfNotNull(cur?.digest)
        body["executor"] = signer.installId
        body["signed_by"] = mapOf("kind" to "install", "id" to signer.installId)
        body["budgets"] = stamp(cur, content.map("budgets") ?: emptyMap(), caller)
        body["actor"] = ActorRecord.over(Signed.RULES, body, caller.kind, caller.id, caller.door, signer.installId).toMap()
        return adopt(tx, Canonical.encode(Signed.sign(Signed.RULES, body, signer)), capability)
    }

    /** After a reset the head names no document, but the rows are still there: takes each signed one up again, oldest first. */
    internal fun restore(tx: Journal.Tx) {
        val rows = tx.w.rawQuery("SELECT body FROM documents ORDER BY generation, digest", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        for (json in rows) if (runCatching { !RuleDocument.parse(json).merged }.getOrDefault(false)) adopt(tx, json)
    }

    internal fun isAncestor(tx: Journal.Tx, ancestor: String, of: String): Boolean = ancestor == of || ancestor in ancestors(tx, of)

    // ---- how a held document becomes current ----

    private fun advance(tx: Journal.Tx, doc: RuleDocument): Pair<RuleDocument, List<String>> {
        val cur = current(tx)
        val (now, forked) = when {
            cur == null -> doc to emptyList()
            cur.digest == doc.digest || isAncestor(tx, doc.digest, cur.digest) -> return cur to emptyList()
            isAncestor(tx, cur.digest, doc.digest) -> doc to emptyList()
            else -> {
                val base = commonAncestor(tx, cur, doc) ?: throw JournalRefused("no_common_ancestor")
                val merge = Merge.of(base, cur, doc) { branch, key -> authorized(tx, branch, base, key) }
                merge.document.problem()?.let { throw JournalRefused("merge_invalid:$it") }
                tx.putDocument(merge.document.digest, merge.document.generation, merge.document.json)
                if (merge.forked.isNotEmpty()) events(SpendEvent(SpendEvent.RULE_FORK, mapOf("fields" to merge.forked.joinToString(","), "generation" to merge.document.generation.toString())))
                merge.document to merge.forked
            }
        }
        val mine = now.budgets.filter { tx.install in it.value.devices }.keys.sorted()
        val made = TreeMap<String, Any?>()
        for (id in now.budgets.keys) if (id !in tx.state.reclaim) maker(tx, now, id)?.let { made[id] = it }
        // A sender this install stopped taking from is taken from again once a document lowers its portion to what its valid transfers leave.
        val unbar = tx.state.peers.flatMap { (sender, peer) ->
            peer.barred.filter { (budget, limit) -> (now.budgets[budget]?.devices?.get(sender) ?: 0) <= limit }.map { mapOf("sender" to sender, "budget" to it.key) }
        }
        tx.append(mapOf("kind" to "rules", "generation" to now.generation, "digest" to now.digest, "budgets" to mine, "made" to made, "unbar" to unbar))
        afterAdopt?.invoke(tx, now)
        return now to forked
    }

    private fun parentsOf(tx: Journal.Tx, digest: String): List<String> = get(tx, digest)?.parents ?: emptyList()

    private fun ancestors(tx: Journal.Tx, digest: String): Set<String> {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(parentsOf(tx, digest))
        while (queue.isNotEmpty()) { val d = queue.removeFirst(); if (seen.add(d)) queue.addAll(parentsOf(tx, d)) }
        return seen
    }

    private fun root(tx: Journal.Tx): String? = tx.w.rawQuery("SELECT digest FROM documents WHERE generation = 1 LIMIT 1", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** The newest document both branches descend from. */
    private fun commonAncestor(tx: Journal.Tx, a: RuleDocument, b: RuleDocument): RuleDocument? {
        val ofA = ancestors(tx, a.digest) + a.digest
        return (listOf(b.digest) + ancestors(tx, b.digest)).mapNotNull { d -> d.takeIf { it in ofA }?.let { get(tx, it) } }.maxByOrNull { it.generation }
    }

    /**
     * Whether the last change of [key] on the way from [base] to [branch] carried an owner authorization: an
     * approver's signature. Read from the documents alone, so every device answers the same. A merged
     * document on the way carries none.
     */
    private fun authorized(tx: Journal.Tx, branch: RuleDocument, base: RuleDocument, key: String): Boolean {
        var doc = branch
        while (doc.digest != base.digest && doc.parents.size == 1) {
            val parent = get(tx, doc.parents[0]) ?: return false
            val now = Leaves.of(doc)
            val was = Leaves.of(parent)
            if ((key in now) != (key in was) || Canonical.encode(now[key]) != Canonical.encode(was[key])) return doc.signerKind == "approver"
            doc = parent
        }
        return false
    }

    /** The executor that took the change which made [budget]: walks back to the first document that has it. */
    private fun maker(tx: Journal.Tx, doc: RuleDocument, budget: String): String? {
        var at = doc
        while (true) {
            val parents = at.parents.mapNotNull { get(tx, it) }.filter { budget in it.budgets }
            if (parents.isEmpty()) return at.executor
            at = parents.minByOrNull { it.digest }!!
        }
    }

    /**
     * `set_by` says whose gate it is, so it follows the change that set the gate and nothing else: the
     * owner's gate stays the owner's when an agent tightens it, and no one can write another name into it.
     */
    private fun stamp(parent: RuleDocument?, budgets: Map<String, Any?>, caller: Caller): Map<String, Any?> = budgets.mapValues { (id, v) ->
        @Suppress("UNCHECKED_CAST") val b = TreeMap(v as Map<String, Any?>)
        val gate = TreeMap(b.map("gate")!!)
        gate["set_by"] = setter(parent?.budgets?.get(id), gate.flag("agents_may_change"), gate.long("ceiling"), mapOf("kind" to caller.kind, "id" to caller.id, "door" to caller.door, "install" to signer.installId, "authorized" to false))
        b["gate"] = gate
        b
    }

    private fun setter(before: RuleDocument.Budget?, agentsMayChange: Boolean, ceiling: Long?, by: Map<String, Any?>): Map<String, Any?>? {
        val gated = !agentsMayChange || ceiling != null
        if (!gated) return null
        if (before == null || !before.gated) return by
        if (before.agentsMayChange == agentsMayChange && before.ceiling == ceiling) return before.setBy
        // Tightening the owner's gate leaves it the owner's. Only the owner replaces the name on it.
        return if (before.ownerSet && by.str("kind") != "owner") before.setBy else by
    }

    private fun setterProblem(parent: RuleDocument?, doc: RuleDocument): String? {
        val actor = doc.actor!!
        val by = mapOf("kind" to actor.kind, "id" to actor.id, "door" to actor.door, "install" to actor.install, "authorized" to (Gates.SPEND_SETTING_APPROVER_PATH && doc.signerKind == "approver"))
        for ((id, b) in doc.budgets) {
            val expected = setter(parent?.budgets?.get(id), b.agentsMayChange, b.ceiling, by)
            if (Canonical.encode(expected) != Canonical.encode(b.setBy)) return "set_by:$id"
        }
        return null
    }
}
