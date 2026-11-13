package cc.bridgit.app.core.spending

import java.util.TreeMap

/**
 * The owner rule document (`rules/1`; spending authority G6.1): one per account and namespace, with a
 * generation. It holds the switches, the per-spend caps and the owner budgets with their portions, headroom
 * balances and change gates. [map] is the document as JSON values; its digest is over its canonical form
 * without the signature (`contracts/client-core/schemas/spend-rules.schema.json`).
 *
 * What it does **not** hold: who may take an ended share (the reclaim right is fixed when a budget is made
 * and moves only by a transfer, G5.1 item 3), and any raise. A successor never restates an amount, a portion
 * or a headroom balance upward ([Successor]): those rise only by `increment/1` and `transfer/1`.
 */
class RuleDocument internal constructor(val map: Map<String, Any?>) {
    val account: String get() = map.str("account")!!
    val namespace: String get() = map.str("namespace")!!
    val generation: Long get() = map.long("generation")!!
    val parents: List<String> get() = map.list("parents").map { it as String }
    val zone: String get() = map.str("zone")!!
    val merged: Boolean get() = map.flag("merged")

    /** The device that took the change (absent on a merged document). The budgets this document makes get it as first holder of their reclaim right. */
    val executor: String? get() = map.str("executor")
    val actor: ActorRecord? get() = map.map("actor")?.let(ActorRecord::of)
    val signerKind: String? get() = map.map("signed_by")?.str("kind")
    val signerId: String? get() = map.map("signed_by")?.str("id")
    val digest: String by lazy { Signed.digest(Signed.RULES, map) }
    val json: String by lazy { Canonical.encode(map) }

    val paused: Boolean get() = map.map("switches")!!.flag("paused")
    fun classOn(instrumentClass: String): Boolean = map.map("switches")!!.map("classes")!![instrumentClass] != false
    fun sourceOn(source: String): Boolean = map.map("switches")!!.map("sources")!![source] != false
    fun cap(group: String, id: String): Money? = map.map("caps")!!.map(group)!!.map(id)?.let { Money(it.str("unit")!!, it.long("atoms")!!) }

    val budgets: Map<String, Budget> by lazy { map.map("budgets")!!.mapValues { (id, v) -> @Suppress("UNCHECKED_CAST") Budget(id, v as Map<String, Any?>) } }

    class Budget internal constructor(val id: String, val map: Map<String, Any?>) {
        val scopeKind: String get() = map.map("scope")!!.str("kind")!!
        val scopeId: String get() = map.map("scope")!!.str("id")!!
        val period: String get() = map.str("period")!!
        val unit: String get() = map.str("unit")!!
        val amount: Long get() = map.long("amount")!!
        val cloud: Long get() = map.long("cloud")!!
        val rebalance: Boolean get() = map.flag("rebalance")
        val agentsMayChange: Boolean get() = map.map("gate")!!.flag("agents_may_change")
        val ceiling: Long? get() = map.map("gate")!!.long("ceiling")
        val setBy: Map<String, Any?>? get() = map.map("gate")!!.map("set_by")

        /** A gate is the owner's when the owner set it, in the app or with an owner authorization (G6.2). An agent's gate is its own restraint. */
        val ownerSet: Boolean get() = setBy?.let { it.str("kind") == "owner" || it.flag("authorized") } ?: false
        val gated: Boolean get() = !agentsMayChange || ceiling != null
        val headroomCloud: Long get() = map.map("headroom")!!.long("cloud")!!

        /** Every device that is an executor of this budget, with its portion atoms. */
        val devices: Map<String, Long> by lazy { shares().mapValues { (_, s) -> s.long("atoms")!! } }
        fun kind(install: String): String? = shares()[install]?.str("kind")
        fun headroom(install: String): Long = map.map("headroom")!!.map("devices")!!.long(install) ?: 0
        val headroomDevices: Map<String, Long> get() = map.map("headroom")!!.map("devices")!!.mapValues { (it.value as Number).toLong() }

        @Suppress("UNCHECKED_CAST")
        private fun shares(): Map<String, Map<String, Any?>> = map.map("devices")!! as Map<String, Map<String, Any?>>

        /** Whether [install] is the only executor: no other device, and nothing for the cloud. */
        fun soleExecutor(install: String): Boolean = devices.keys == setOf(install) && cloud == 0L && headroomCloud == 0L

        /** Whether a spend with these marks falls in this budget's scope (G6 rule 6). */
        fun covers(install: String, instrument: String, service: String?, applet: String?, agent: String?): Boolean = when (scopeKind) {
            "account" -> true
            "device" -> scopeId == install
            "instrument" -> scopeId == instrument
            "service" -> scopeId == service
            "applet" -> scopeId == applet
            "agent" -> scopeId == agent
            else -> false
        }
    }

    /** Null when the document is well formed and its sums hold; otherwise what is wrong. A document that fails is invalid everywhere. */
    fun problem(): String? = runCatching {
        if (map.str("v") != VERSION) return "version"
        if (generation < 1 || (generation == 1L) != parents.isEmpty()) return "generation"
        if (merged) { if (parents.size != 2 || map.containsKey("signature") || map.containsKey("actor")) return "merged_shape" } else if (parents.size > 1) return "parents"
        if (Namespace.claimed(namespace) == null) return "namespace"
        // No field this build does not know: a name for who may take an ended share, for one, is not a rule field (G5.1 item 3, SA-R5).
        if (!TOP.containsAll(map.keys) || !map.map("switches")!!.keys.let { SWITCHES.containsAll(it) } || map.map("caps")!!.keys != CAPS) return "unknown_field"
        for ((id, b) in budgets) {
            val gate = b.map.map("gate")!!
            if (!BUDGET.containsAll(b.map.keys) || gate.keys != GATE || b.map.map("headroom")!!.keys != HEADROOM || b.map.map("scope")!!.keys != SCOPE) return "unknown_field:$id"
        }
        java.time.ZoneId.of(zone)
        listOf(account, zone).forEach(Journal::text)
        for (group in listOf("instruments", "sources")) for ((id, _) in map.map("caps")!!.map(group)!!) { Journal.text(id); cap(group, id)!!.requireAmount() }
        for ((id, b) in budgets) {
            Journal.text(id); Journal.text(b.scopeId)
            if (b.scopeKind !in SCOPES || b.period !in PeriodClock.KINDS || Units.of(b.unit) == null) return "budget_shape:$id"
            val atoms = listOf(b.amount, b.cloud, b.headroomCloud) + b.devices.values + b.headroomDevices.values + listOfNotNull(b.ceiling)
            if (atoms.any { it < 0 }) return "negative:$id"
            if (b.devices.keys.any { b.kind(it) !in KINDS } || !b.devices.keys.containsAll(b.headroomDevices.keys)) return "share_shape:$id"
            // Portions add up to at most the budget (G5.1), without wrapping.
            if (sum(listOf(b.cloud) + b.devices.values) > b.amount) return "portions_over_budget:$id"
            val room = b.ceiling?.let { if (it < b.amount) return "ceiling_below_budget:$id"; it - b.amount } ?: 0
            if (sum(listOf(b.headroomCloud) + b.headroomDevices.values) > room) return "headroom_over_ceiling:$id"
            b.setBy?.let { if (it.str("kind") !in ActorRecord.KINDS) return "set_by:$id" }
        }
        null
    }.getOrElse { "malformed" }

    companion object {
        const val VERSION = "rules/1"
        val SCOPES = setOf("account", "device", "instrument", "service", "applet", "agent")
        val KINDS = setOf("standing", "leased")
        private val TOP = setOf("v", "account", "namespace", "generation", "parents", "zone", "switches", "caps", "budgets", "executor", "actor", "signed_by", "signature", "merged")
        private val SWITCHES = setOf("paused", "classes", "sources")
        private val CAPS = setOf("instruments", "sources")
        private val BUDGET = setOf("scope", "period", "unit", "amount", "cloud", "devices", "rebalance", "gate", "headroom")
        private val GATE = setOf("agents_may_change", "ceiling", "set_by")
        private val HEADROOM = setOf("cloud", "devices")
        private val SCOPE = setOf("kind", "id")

        fun parse(json: String): RuleDocument = RuleDocument(Tree.parse(json))

        private fun sum(xs: List<Long>): Long = xs.fold(0L) { a, b -> Math.addExact(a, b) }

        /** A budget as JSON values. [devices] gives each executor's portion atoms; every share is [kind]. */
        fun budget(
            scopeKind: String, scopeId: String, period: String, unit: String, amount: Long, devices: Map<String, Long>, cloud: Long = 0,
            kind: String = "standing", rebalance: Boolean = true, agentsMayChange: Boolean = true, ceiling: Long? = null,
            headroom: Map<String, Long> = emptyMap(), headroomCloud: Long = 0, setBy: Map<String, Any?>? = null,
        ): Map<String, Any?> = mapOf(
            "scope" to mapOf("kind" to scopeKind, "id" to scopeId), "period" to period, "unit" to unit, "amount" to amount, "cloud" to cloud,
            "devices" to devices.mapValues { mapOf("atoms" to it.value, "kind" to kind) }, "rebalance" to rebalance,
            "gate" to mapOf("agents_may_change" to agentsMayChange, "ceiling" to ceiling, "set_by" to setBy),
            "headroom" to mapOf("cloud" to headroomCloud, "devices" to headroom),
        )

        /** The content of a document: everything a change can change. */
        fun content(
            zone: String = "UTC", budgets: Map<String, Map<String, Any?>> = emptyMap(), paused: Boolean = false,
            classes: Map<String, Boolean> = emptyMap(), sources: Map<String, Boolean> = emptyMap(),
            instrumentCaps: Map<String, Money> = emptyMap(), sourceCaps: Map<String, Money> = emptyMap(),
        ): Map<String, Any?> = mapOf(
            "zone" to zone,
            "switches" to mapOf("paused" to paused, "classes" to classes, "sources" to sources),
            "caps" to mapOf("instruments" to instrumentCaps.mapValues { Journal.moneyMap(it.value) }, "sources" to sourceCaps.mapValues { Journal.moneyMap(it.value) }),
            "budgets" to budgets,
        )

        internal val CONTENT = listOf("zone", "switches", "caps", "budgets")
    }
}

/** The content of a rule document as leaves: a path and a value. What a reduction check and a merge compare, field by field. */
internal object Leaves {
    private const val SEP = "\u001f"
    private val ABSENT = Any()

    enum class Type { UNORDERED, TIGHT_TRUE, TIGHT_FALSE, AMOUNT, CAP, CEILING, SETTER }

    fun of(doc: RuleDocument): TreeMap<String, Any?> {
        val out = TreeMap<String, Any?>()
        fun walk(path: List<String>, v: Any?) {
            val whole = path.size >= 2 && (path[0] == "caps" && path.size == 3 || path.takeLast(2) == listOf("gate", "set_by"))
            if (v is Map<*, *> && !whole) { @Suppress("UNCHECKED_CAST") for ((k, x) in v as Map<String, Any?>) walk(path + k, x) } else out[path.joinToString(SEP)] = v
        }
        for (k in RuleDocument.CONTENT) walk(listOf(k), doc.map[k])
        return out
    }

    fun path(key: String): List<String> = key.split(SEP)
    fun show(key: String): String = path(key).joinToString("/")
    fun budgetOf(key: String): String? = path(key).takeIf { it[0] == "budgets" && it.size > 1 }?.get(1)

    fun type(key: String): Type {
        val p = path(key)
        return when {
            p[0] == "switches" -> if (p[1] == "paused") Type.TIGHT_TRUE else Type.TIGHT_FALSE
            p[0] == "caps" -> Type.CAP
            p[0] == "budgets" && p.size > 2 -> when {
                p[2] == "amount" || p[2] == "cloud" || p[2] == "headroom" || (p[2] == "devices" && p.last() == "atoms") -> Type.AMOUNT
                p[2] == "rebalance" -> Type.TIGHT_FALSE
                p[2] == "gate" -> when (p[3]) { "agents_may_change" -> Type.TIGHT_FALSE; "ceiling" -> Type.CEILING; else -> Type.SETTER }
                else -> Type.UNORDERED
            }
            else -> Type.UNORDERED
        }
    }

    /**
     * -1 when [x] is tighter than [y], 1 when it is looser, 0 when they are the same, null when the field has
     * no order (a scope, a period, a zone, a share kind). A missing leaf is `absent`: no switch entry is on,
     * no cap is no cap, no share is a removed share. Tighter is the lower amount, the gate on, the switch
     * off, the share removed (G5.1).
     */
    fun order(type: Type, x: Any?, xAbsent: Boolean, y: Any?, yAbsent: Boolean): Int? {
        fun same() = xAbsent == yAbsent && Canonical.encode(x) == Canonical.encode(y)
        return when (type) {
            Type.UNORDERED -> if (same()) 0 else null
            Type.TIGHT_TRUE -> rank(x == true, y == true)
            Type.TIGHT_FALSE -> rank(!(xAbsent || x == true), !(yAbsent || y == true))
            Type.AMOUNT -> (if (xAbsent) 0L else (x as Number).toLong()).compareTo(if (yAbsent) 0L else (y as Number).toLong())
            Type.CEILING -> (x as? Number)?.toLong().let { a -> (y as? Number)?.toLong().let { b -> if (a == null) (if (b == null) 0 else 1) else if (b == null) -1 else a.compareTo(b) } }
            Type.CAP -> {
                @Suppress("UNCHECKED_CAST") val a = x as? Map<String, Any?>
                @Suppress("UNCHECKED_CAST") val b = y as? Map<String, Any?>
                when {
                    a == null && b == null -> 0
                    a == null -> 1
                    b == null -> -1
                    a.str("unit") != b.str("unit") -> null
                    else -> a.long("atoms")!!.compareTo(b.long("atoms")!!)
                }
            }
            // The owner's gate is the tighter one; between two of the same standing the order is only there to be the same everywhere.
            Type.SETTER -> if (same()) 0 else rank(owner(x), owner(y)).takeIf { it != 0 } ?: Canonical.encode(x).compareTo(Canonical.encode(y)).coerceIn(-1, 1)
        }
    }

    private fun rank(xTight: Boolean, yTight: Boolean) = if (xTight == yTight) 0 else if (xTight) -1 else 1

    @Suppress("UNCHECKED_CAST")
    private fun owner(v: Any?) = (v as? Map<String, Any?>)?.let { it.str("kind") == "owner" || it.flag("authorized") } ?: false

    /** Leaves back to the nested content. A share that lost its amount or its kind in a merge is a removed share. */
    fun content(leaves: Map<String, Any?>): Map<String, Any?> {
        val root = TreeMap<String, Any?>()
        for ((key, v) in leaves) {
            val p = path(key)
            var at = root
            for (k in p.dropLast(1)) { @Suppress("UNCHECKED_CAST") at = at.getOrPut(k) { TreeMap<String, Any?>() } as TreeMap<String, Any?> }
            at[p.last()] = v
        }
        @Suppress("UNCHECKED_CAST")
        fun sub(m: MutableMap<String, Any?>, k: String) = m.getOrPut(k) { TreeMap<String, Any?>() } as MutableMap<String, Any?>
        sub(root, "switches").let { sub(it, "classes"); sub(it, "sources") }
        sub(root, "caps").let { sub(it, "instruments"); sub(it, "sources") }
        for ((_, b) in sub(root, "budgets")) {
            @Suppress("UNCHECKED_CAST") val budget = b as MutableMap<String, Any?>
            val devices = sub(budget, "devices")
            devices.entries.removeAll { (_, s) -> (s as Map<*, *>).let { !it.containsKey("atoms") || !it.containsKey("kind") } }
            val headroom = sub(budget, "headroom")
            sub(headroom, "devices").keys.retainAll(devices.keys)
            headroom.putIfAbsent("cloud", 0L)
            budget.putIfAbsent("cloud", 0L)
            sub(budget, "gate").let { g -> g.putIfAbsent("ceiling", null); g.putIfAbsent("set_by", null) }
        }
        return root
    }

    val absent: Any get() = ABSENT
}

/**
 * What a successor may never do, whoever signed it (G5.1, G6.1): restate a budget amount, a portion or a
 * headroom balance upward. A new share starts at 0. A document that does is invalid everywhere.
 */
internal object Successor {
    fun problem(parent: RuleDocument, child: RuleDocument): String? {
        if (child.generation != parent.generation + 1 || child.parents != listOf(parent.digest)) return "not_a_successor"
        if (child.account != parent.account || child.namespace != parent.namespace) return "another_account"
        child.problem()?.let { return it }
        for ((id, b) in child.budgets) {
            val was = parent.budgets[id] ?: continue
            if (b.unit != was.unit) return "unit_changed:$id"
            if (b.amount > was.amount) return "amount_restated_up:$id"
            if (b.cloud > was.cloud || b.devices.any { (i, atoms) -> atoms > (was.devices[i] ?: 0) }) return "portion_restated_up:$id"
            if (b.headroomCloud > was.headroomCloud || b.headroomDevices.any { (i, atoms) -> atoms > was.headroom(i) }) return "headroom_restated_up:$id"
        }
        return null
    }
}

/** The pure-reduction check of G6.1, field by field against the named parent. */
internal object Reduction {
    /** Every field [child] loosens, moves or adds against [parent]. Empty when the change only reduces. */
    fun raises(parent: RuleDocument, child: RuleDocument): List<String> {
        val was = Leaves.of(parent)
        val now = Leaves.of(child)
        val out = ArrayList<String>()
        for (key in was.keys + now.keys) {
            val budget = Leaves.budgetOf(key)
            // A budget gives a device room, so a new one is not a reduction; a removed one is.
            if (budget != null && budget !in parent.budgets) { if (out.none { it == "budgets/$budget" }) out += "budgets/$budget"; continue }
            if (budget != null && budget !in child.budgets) continue
            // A share that appears or goes is judged by its amount; its kind has nothing to compare with.
            if (Leaves.path(key).let { it.size == 5 && it[2] == "devices" && it[4] == "kind" } && (key !in now || key !in was)) continue
            val order = Leaves.order(Leaves.type(key), now[key], key !in now, was[key], key !in was)
            if (order == null || order > 0) out += Leaves.show(key)
        }
        return out
    }
}

/**
 * Two successors of one rule document, made by devices apart (G5.1, revised 5.3). The merge is **computed,
 * not signed**: every device arrives at the same document from the same three, so it is never taken off the
 * wire. A field changed on one branch takes that branch's value. A field changed on both to different values
 * takes the value that carries an owner authorization, if exactly one does; otherwise the tighter one, and
 * where the field has no order, the parent's. A budget either branch removed is removed. The reclaim right
 * is not a field and is not merged.
 */
internal object Merge {
    class Result(val document: RuleDocument, /** Fields decided by "tighter" or by falling back to the parent: `spend.rule_fork`. */ val forked: List<String>)

    /** [authorized] says whether the last change of a field on a branch carried an owner authorization. */
    fun of(parent: RuleDocument, x: RuleDocument, y: RuleDocument, authorized: (branch: RuleDocument, key: String) -> Boolean): Result {
        // The same order on every device, whichever branch it held first.
        val (a, b) = if (x.digest <= y.digest) x to y else y to x
        val p = Leaves.of(parent)
        val la = Leaves.of(a)
        val lb = Leaves.of(b)
        val out = TreeMap<String, Any?>()
        val forked = ArrayList<String>()
        val both = (a.budgets.keys intersect b.budgets.keys).filter { it !in parent.budgets }
        val dropped = both.filter { Canonical.encode(a.budgets.getValue(it).map) != Canonical.encode(b.budgets.getValue(it).map) }.toSet()
        dropped.forEach { forked += "budgets/$it" }
        for (key in p.keys + la.keys + lb.keys) {
            val budget = Leaves.budgetOf(key)
            if (budget != null) {
                if (budget in dropped) continue
                if (budget in parent.budgets && (budget !in a.budgets || budget !in b.budgets)) continue
            }
            val inA = key in la
            val inB = key in lb
            val inP = key in p
            fun same(x: Any?, hasX: Boolean, y: Any?, hasY: Boolean) = hasX == hasY && Canonical.encode(x) == Canonical.encode(y)
            val take: Pair<Any?, Boolean> = when {
                same(la[key], inA, lb[key], inB) -> la[key] to inA
                same(la[key], inA, p[key], inP) -> lb[key] to inB
                same(lb[key], inB, p[key], inP) -> la[key] to inA
                else -> {
                    val authA = authorized(a, key)
                    val authB = authorized(b, key)
                    if (authA != authB) (if (authA) la[key] to inA else lb[key] to inB) else {
                        forked += Leaves.show(key)
                        when (Leaves.order(Leaves.type(key), la[key], !inA, lb[key], !inB)) {
                            null -> p[key] to inP
                            1 -> lb[key] to inB
                            else -> la[key] to inA
                        }
                    }
                }
            }
            if (take.second) out[key] = take.first
        }
        val map = TreeMap<String, Any?>(Leaves.content(out))
        // A gate keeps a name on it. Where the fields that won came from one branch and the name from the other, the
        // gate is the owner's if any of the three documents says the owner set it: the tighter reading.
        @Suppress("UNCHECKED_CAST")
        for ((id, v) in map.map("budgets")!!) {
            val gate = (v as Map<String, Any?>).map("gate") as MutableMap<String, Any?>
            val gated = gate["agents_may_change"] != true || gate["ceiling"] != null
            if (!gated) { gate["set_by"] = null; continue }
            if (gate["set_by"] != null) continue
            val names = listOf(a, b, parent).mapNotNull { it.budgets[id]?.setBy }
            gate["set_by"] = names.filter { it.str("kind") == "owner" || it.flag("authorized") }.ifEmpty { names }.minByOrNull { Canonical.encode(it) }
        }
        map["v"] = RuleDocument.VERSION
        map["account"] = parent.account
        map["namespace"] = parent.namespace
        map["generation"] = maxOf(a.generation, b.generation) + 1
        map["parents"] = listOf(a.digest, b.digest)
        map["merged"] = true
        return Result(RuleDocument(map), forked)
    }
}
