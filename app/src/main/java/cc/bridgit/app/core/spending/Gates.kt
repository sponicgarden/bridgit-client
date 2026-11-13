package cc.bridgit.app.core.spending

/**
 * Who may change what in the rule document (spending authority G6.2). Agents act with the owner's authority
 * by default: setting, raising and lowering a budget, topping one up and moving a share are all delegable to
 * an agent that holds the Budgets capability. The only limits are the gates the owner set on a budget
 * (`agents_may_change` off, an agent ceiling), and those bind agents only while the owner's gate stands.
 *
 * **Two paths for the owner's gate.**
 * - *Grants* (built, and the only path in force): the gate is enforced by this check on the device, and the
 *   owner lifts it in the app. The read says `gate_strength: grants`. An agent's lift is answered
 *   `no_approver` with the remaining human step.
 * - *Approver* (behind [SPEND_SETTING_APPROVER_PATH], off): lifting an owner's gate needs an owner
 *   authorization of purpose `spend_setting` once an approver is enrolled. That is owner question Q3, still
 *   undecided, and its stated fallback is grants on every device. So **while the flag is off, enrolling an
 *   approver changes nothing here**: the restriction comes into force only together with the working
 *   authorization path, when the owner has said yes and the flag is on.
 *
 * **Nothing goes around the owner's gate.** While it stands, whoever is not the owner may only reduce that
 * budget: removing it, or changing its scope or period, is refused the same way as loosening the gate.
 */
object Gates {
    /** A compile-time constant on purpose: a setting would be an editable field and need its own configuration API. */
    const val SPEND_SETTING_APPROVER_PATH = false

    enum class Why(val id: String) {
        /** The agent does not hold the Budgets capability: an ordinary grant, given by any agent with the configuration grant. */
        BUDGETS_CAPABILITY_REQUIRED("budgets_capability_required"),

        /** The owner turned `agents_may_change` off for this budget. */
        AGENTS_MAY_NOT_CHANGE("agents_may_not_change"),

        /** The raise is larger than this device's headroom balance under the agent ceiling. */
        OVER_CEILING("over_ceiling"),

        /** An owner's raise that the room outside the headroom balances cannot hold: the ceiling is raised first, in a document. */
        RAISE_THE_CEILING_FIRST("raise_the_ceiling_first"),

    }

    sealed class Verdict {
        /** [drawsHeadroom]: for an increment, whether it is taken from the signing device's headroom balance. */
        data class Allowed(val drawsHeadroom: Boolean = false) : Verdict()

        /** Stored as a proposal; an approver's signature completes it. */
        data class AwaitingOwner(val remainingHumanStep: String) : Verdict()

        /** The owner's gate stands on grants and only the owner lifts it; an agent's way through is an enrolled approver. */
        data class NoApprover(val remainingHumanStep: String) : Verdict()
        data class Refused(val why: Why, val field: String? = null) : Verdict()
    }

    /** What this device knows when it checks: whether an approver is enrolled, and whether the calling agent holds the Budgets capability. */
    data class Env(val approverEnrolled: Boolean, val budgetsCapability: Boolean)

    /** Whether this change speaks with the owner's own authority, as far as the gate is concerned. */
    fun ownerLevel(actorKind: String, signerKind: String?, env: Env): Boolean =
        if (SPEND_SETTING_APPROVER_PATH) signerKind == "approver" || (actorKind == "owner" && !env.approverEnrolled) else actorKind == "owner"

    /** `agent` for an agent's own restraint; for the owner's gate, what enforces it. */
    fun gateStrength(budget: RuleDocument.Budget, env: Env): String? = when {
        !budget.gated -> null
        !budget.ownerSet -> "agent"
        env.approverEnrolled && SPEND_SETTING_APPROVER_PATH -> "approver"
        // While the approver path is off this is the answer on every device, with or without an approver enrolled.
        else -> "grants"
    }

    /** Whether [actorKind] may turn [parent] into [child]. [parent] is null for the first document of an account. */
    fun change(parent: RuleDocument?, child: RuleDocument, actorKind: String, signerKind: String?, env: Env): Verdict {
        val owner = ownerLevel(actorKind, signerKind, env)
        val isOwner = actorKind == "owner"
        if (parent == null) return if (isOwner || env.budgetsCapability || child.budgets.isEmpty()) Verdict.Allowed() else Verdict.Refused(Why.BUDGETS_CAPABILITY_REQUIRED)
        val was = Leaves.of(parent)
        val now = Leaves.of(child)
        val changed = (was.keys + now.keys).filter { (it in was) != (it in now) || Canonical.encode(was[it]) != Canonical.encode(now[it]) }
        val touched = changed.mapNotNull(Leaves::budgetOf).toSet()
        val raises = Reduction.raises(parent, child)
        val raisedCap = raises.firstOrNull { it.startsWith("caps/") }
        if (!isOwner && !env.budgetsCapability) {
            touched.firstOrNull()?.let { return Verdict.Refused(Why.BUDGETS_CAPABILITY_REQUIRED, "budgets/$it") }
            raisedCap?.let { return Verdict.Refused(Why.BUDGETS_CAPABILITY_REQUIRED, it) }
        }
        for (id in touched) {
            val before = parent.budgets[id] ?: continue
            if (!before.ownerSet) continue
            val after = child.budgets[id]
            // The owner's gate is lifted by loosening it, and just as much by going around it: removing the budget (the same
            // id could come back ungated in the next document), or changing its scope, its period or anything else that is
            // not a reduction. Under the owner's gate, whoever is not the owner only reduces.
            val around = raises.firstOrNull { it == "budgets/$id" || it.startsWith("budgets/$id/") }
            if ((after == null || around != null) && !owner) return needsOwner(isOwner, env, if (after == null) "budgets/$id" else around!!)
            if (!before.agentsMayChange && !isOwner) {
                // Setting or tightening a gate is open to anyone; nothing else about this budget is, for an agent.
                val beyondGate = after == null || changed.any { Leaves.budgetOf(it) == id && Leaves.path(it).getOrNull(2) != "gate" }
                if (beyondGate) return Verdict.Refused(Why.AGENTS_MAY_NOT_CHANGE, "budgets/$id")
            }
        }
        return Verdict.Allowed()
    }

    /**
     * Whether [actorKind] may raise [budget] by [amount] atoms on this device (G5.1 "Raises are increments").
     * Under an agent ceiling an agent's raise is taken from [headroom], this device's balance. The owner's
     * raise is not bound by the ceiling and draws on no balance, but must fit [unallocated]: the room under
     * the ceiling that no balance holds.
     */
    fun increment(budget: RuleDocument.Budget, actorKind: String, signerKind: String?, env: Env, amount: Long, headroom: Long, unallocated: Long): Verdict {
        val isOwner = actorKind == "owner"
        if (!isOwner && !env.budgetsCapability) return Verdict.Refused(Why.BUDGETS_CAPABILITY_REQUIRED)
        val owner = ownerLevel(actorKind, signerKind, env) || (isOwner && !budget.ownerSet)
        if (!budget.agentsMayChange && !owner) return if (isOwner) needsOwner(true, env, "budgets/${budget.id}/gate") else Verdict.Refused(Why.AGENTS_MAY_NOT_CHANGE)
        if (budget.ceiling == null) return Verdict.Allowed()
        if (owner) return if (amount <= unallocated) Verdict.Allowed() else Verdict.Refused(Why.RAISE_THE_CEILING_FIRST)
        if (amount <= headroom) return Verdict.Allowed(drawsHeadroom = true)
        return if (isOwner) needsOwner(true, env, "budgets/${budget.id}/gate") else Verdict.Refused(Why.OVER_CEILING)
    }

    /** Moving a share, headroom or the reclaim right: delegable (G6.2). How the old authority ends is the transfer's business, not a limit on who may ask. */
    fun move(actorKind: String, env: Env): Verdict =
        if (actorKind == "owner" || env.budgetsCapability) Verdict.Allowed() else Verdict.Refused(Why.BUDGETS_CAPABILITY_REQUIRED)

    @Suppress("UNUSED_PARAMETER")
    private fun needsOwner(isOwner: Boolean, env: Env, field: String): Verdict = when {
        env.approverEnrolled && SPEND_SETTING_APPROVER_PATH -> Verdict.AwaitingOwner("Approve the change on an approver device")
        isOwner -> Verdict.Allowed()
        else -> Verdict.NoApprover("The owner lifts this gate in the app")
    }
}
