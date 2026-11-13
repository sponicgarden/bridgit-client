package cc.bridgit.app.core.spending

/**
 * What an executor may spend and raise, for one budget and one period (spending authority G5.1).
 *
 * **An executor's portion for a period is its portion in the rule document, plus increments, plus transfers
 * taken, less transfers signed.** Portions add up to at most the budget because the document's do, an
 * increment raises the budget and one portion by the same amount, and a transfer leaves one executor before
 * it reaches another. The headroom balance under an agent ceiling is counted the same way, less what the
 * install's own agent increments drew.
 *
 * **After a reset everything standing is frozen** (SA-R6): in a budget with other executors the document's
 * portion and headroom balance count as 0 and the install holds no reclaim right, until its record is
 * rebuilt from the other executors' lists ([Transfers.rebuild]). What reaches it in the new epoch (a
 * transfer taken, an increment that draws on no headroom) still counts. A new period restores nothing.
 */
internal object Portions {
    fun frozen(state: SpendState, budget: RuleDocument.Budget, install: String): Boolean =
        state.origin == Journal.Origin.RESET && budget.id !in state.rebuilt && !budget.soleExecutor(install)

    /** This install's portion of [budgetId] in [period], in atoms. 0 for a budget the document no longer has, or names no share of. */
    fun portion(state: SpendState, doc: RuleDocument?, budgetId: String, install: String, period: String): Long {
        val b = doc?.budgets?.get(budgetId) ?: return 0
        val share = b.devices[install] ?: return 0
        var sum = if (frozen(state, b, install)) 0 else share
        for (m in state.moves) if (m.budget == budgetId && m.kind == "portion") when (m.dir) {
            "in" -> sum = Math.addExact(sum, m.conveyed(period, b.devices[m.peer] ?: 0, b.amount))
            "inc" -> if (m.peer == install) sum = Math.addExact(sum, m.amountIn(period))
            "out" -> sum = Math.subtractExact(sum, m.debitIn(period))
        }
        return maxOf(sum, 0)
    }

    /** This install's headroom balance under the agent ceiling of [budgetId] in [period]. 0 where there is no ceiling. */
    fun headroom(state: SpendState, doc: RuleDocument?, budgetId: String, install: String, period: String): Long {
        val b = doc?.budgets?.get(budgetId) ?: return 0
        if (b.ceiling == null || install !in b.devices) return 0
        var sum = if (frozen(state, b, install)) 0 else b.headroom(install)
        for (m in state.moves) if (m.budget == budgetId) when {
            m.kind == "headroom" && m.dir == "in" -> sum = Math.addExact(sum, m.conveyed(period, b.headroom(m.peer), b.ceiling!! - b.amount))
            m.kind == "headroom" && m.dir == "out" -> sum = Math.subtractExact(sum, m.debitIn(period))
            m.dir == "inc" && m.peer == install && m.agent -> sum = Math.subtractExact(sum, m.amountIn(period))
        }
        return maxOf(sum, 0)
    }

    /**
     * Whether this install can give [atoms] away **for good** from [period] on: in every later period where
     * something of one period still runs, and for good, what it has is at least that. Room that lasts one
     * period (a top-up, a transfer for one period) backs nothing beyond its period, so a standing transfer
     * cannot be made out of it (the room would stay with the recipient after it had gone from the sender).
     */
    fun backedForGood(state: SpendState, doc: RuleDocument?, budgetId: String, install: String, period: String, atoms: Long): Boolean {
        val b = doc?.budgets?.get(budgetId) ?: return false
        val share = b.devices[install] ?: return false
        val mine = state.moves.filter { it.budget == budgetId && it.kind == "portion" && !(it.dir == "inc" && it.peer != install) }
        // null stands for "for good": only what is standing counts there.
        val whens = listOf<String?>(null) + mine.filter { it.lasts != "standing" && it.from != null && it.from > period }.map { it.from }
        return whens.all { at ->
            fun runs(m: Move) = if (m.lasts == "standing") (at == null || m.from == null || m.from <= at) else (at != null && m.from == at)
            var sum = if (frozen(state, b, install)) 0 else share
            for (m in mine) if (runs(m) && !(m.voided && m.dir != "out")) when (m.dir) {
                "in" -> sum = Math.addExact(sum, m.conveyed(m.from ?: period, b.devices[m.peer] ?: 0, b.amount))
                "inc" -> sum = Math.addExact(sum, m.atoms)
                "out" -> sum = Math.subtractExact(sum, m.atoms)
            }
            sum >= atoms
        }
    }

    /** The budget's whole amount in [period]: the document's, plus every increment this device has applied. */
    fun total(state: SpendState, doc: RuleDocument?, budgetId: String, period: String): Long {
        val b = doc?.budgets?.get(budgetId) ?: return 0
        return state.moves.filter { it.budget == budgetId && it.dir == "inc" }.fold(b.amount) { sum, m -> Math.addExact(sum, m.amountIn(period)) }
    }

    /** The room under the ceiling that no headroom balance holds: what an owner's raise may use without raising the ceiling. */
    fun unallocated(state: SpendState, doc: RuleDocument?, budgetId: String, period: String): Long {
        val b = doc?.budgets?.get(budgetId) ?: return 0
        val ceiling = b.ceiling ?: return Long.MAX_VALUE
        val balances = b.headroomDevices.values.fold(b.headroomCloud) { a, x -> Math.addExact(a, x) }
        val ownerRaises = state.moves.filter { it.budget == budgetId && it.dir == "inc" && !it.agent }.fold(0L) { sum, m -> Math.addExact(sum, m.amountIn(period)) }
        return ceiling - b.amount - balances - ownerRaises
    }

    fun holdsReclaim(state: SpendState, doc: RuleDocument?, budgetId: String, install: String): Boolean {
        val b = doc?.budgets?.get(budgetId) ?: return false
        return install in b.devices && !frozen(state, b, install) && state.reclaim[budgetId] == install
    }

    /**
     * What another executor had of [kind] (`portion` or `headroom`) in [budgetId], as far as this device has
     * seen: its share in the document, what was moved to it and, for a portion, its own increments. Its
     * chain of transfers is checked against this (G5.1 "A sender that signs twice"). [period] says when: in
     * that period, room that lasts only that period counts; with null, only what it has for good, which is
     * all a standing transfer can be backed by.
     */
    fun had(state: SpendState, doc: RuleDocument?, budgetId: String, sender: String, kind: String, period: String?): Long {
        val b = doc?.budgets?.get(budgetId) ?: return 0
        fun at(lasts: String, from: String?, atoms: Long) = if (lasts == "standing") (if (period == null || from == null || from <= period) atoms else 0) else (if (period != null && from == period) atoms else 0)
        var sum = if (kind == "portion") b.devices[sender] ?: 0 else b.headroom(sender)
        for (peer in state.peers.values) for (s in peer.seen) if (s.budget == budgetId && s.kind == kind && s.to == sender && !s.voided) sum = Math.addExact(sum, at(s.lasts, s.from, s.atoms))
        for (m in state.moves) if (m.budget == budgetId) when {
            m.dir == "out" && m.kind == kind && m.peer == sender -> sum = Math.addExact(sum, at(m.lasts, m.from, m.atoms))
            m.dir == "inc" && m.peer == sender && !m.voided -> if (kind == "portion") sum = Math.addExact(sum, at(m.lasts, m.from, m.atoms)) else if (m.agent) sum = Math.subtractExact(sum, at(m.lasts, m.from, m.atoms))
        }
        return maxOf(sum, 0)
    }
}
