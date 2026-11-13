package cc.bridgit.app.ownerapi

import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.spending.Gates
import cc.bridgit.app.core.spending.Journal
import cc.bridgit.app.core.spending.Money
import cc.bridgit.app.core.spending.Namespace
import cc.bridgit.app.core.spending.Rounding
import cc.bridgit.app.core.spending.SpendRefusal
import cc.bridgit.app.core.spending.SpendRunner
import cc.bridgit.app.core.spending.Units
import org.json.JSONArray
import org.json.JSONObject

/**
 * The spending core's answers as the API gives them (spending authority G11): money, a refusal of a spend,
 * a gate's verdict, and what one spend did.
 *
 * **Every refusal of a spend names its rule** (`rule`, 1 to 8, when a rule of G6 decided), the finer
 * `reason`, the `namespace`, and `resets_at` where the refusal ends by itself (rules 5, 6 and 8, and only when
 * the device has a time it can reason from). The codes for rules 1 to 7 are this door's choice; the design
 * names codes only for rule 8, the namespace, `offline` and the cloud.
 */
object SpendWire {
    fun json(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> for ((k, x) in v) o.put(k as String, json(x)) }
        is List<*> -> JSONArray().also { a -> for (x in v) a.put(json(x)) }
        is Money -> money(v)
        else -> v
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Map<String, Any?>): JSONObject = json(v) as JSONObject

    /** Money on the wire: the unit, a whole number of its smallest part, and the same as plain decimal text for reading. */
    fun money(m: Money): JSONObject = JSONObject().put("unit", m.unit).put("atoms", m.atoms).put("amount", m.toDecimal())

    /**
     * Money a caller sends: `{"unit":"USD-6","atoms":1500000}` or `{"unit":"USD-6","amount":"1.50"}`. Atoms are a
     * whole number and the amount is text: a JSON fraction is refused, and so is an amount the unit cannot
     * hold exactly (it is never rounded for the caller).
     */
    fun money(v: Any?, field: String): Money {
        fun bad(why: String): Nothing = throw ErrorCatalog.error("invalid_parameter", "$field $why", mapOf("field" to field))
        val o = v as? JSONObject ?: bad("must be {unit, atoms} or {unit, amount}")
        if (o.keys().asSequence().any { it !in setOf("unit", "atoms", "amount") }) bad("has an unknown field")
        val unit = o.opt("unit") as? String ?: bad("needs a unit (USD-6 for metered spend)")
        if (Units.of(unit) == null) bad("names an unknown unit")
        if (o.has("atoms") == o.has("amount")) bad("needs either atoms or amount")
        if (o.has("atoms")) {
            val atoms = when (val a = o.get("atoms")) { is Int -> a.toLong(); is Long -> a; else -> bad("atoms must be a whole number") }
            if (atoms < 0) bad("can't be negative")
            return Money(unit, atoms)
        }
        val text = o.get("amount") as? String ?: bad("amount must be text, like \"1.50\"")
        val up = Money.parse(unit, text, Rounding.COST_UP) ?: bad("amount must be plain digits with at most one point")
        if (up != Money.parse(unit, text, Rounding.RETURN_DOWN)) bad("has more decimals than $unit holds")
        return up
    }

    private fun extra(r: SpendRefusal, namespace: Namespace, detail: String?): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        put("rule", r.rule ?: JSONObject.NULL)
        put("reason", r.reason?.id ?: JSONObject.NULL)
        put("resets_at", r.resetsAt ?: JSONObject.NULL)
        put("namespace", namespace.id)
        detail?.let { put("detail", it) }
    }

    /** Why a spend was refused, as an API error. */
    fun refusal(r: SpendRefusal, namespace: Namespace, detail: String? = null): ApiError {
        val x = extra(r, namespace, detail)
        val why = r.reason?.id?.replace('_', ' ')
        return when (r.code) {
            SpendRefusal.Code.OFFLINE -> ErrorCatalog.error("offline", "The device reaches no network, so nothing paid can run now; work that needs no network still runs", x)
            SpendRefusal.Code.CLOUD_UNREACHABLE -> ErrorCatalog.error("cloud_unreachable", "This source is held by the Bridgit cloud, which this build does not spend through", x)
            SpendRefusal.Code.ALLOWANCE_EXHAUSTED -> ErrorCatalog.error("allowance_exhausted", "No room in this device's held allowance for this spend${why?.let { " ($it)" }.orEmpty()}", x)
            SpendRefusal.Code.ALLOWANCE_EXPIRED -> ErrorCatalog.error("allowance_expired", "The held allowance for this source has run out of time", x)
            SpendRefusal.Code.NAMESPACE_MISMATCH -> ErrorCatalog.error("namespace_mismatch", "The spend names another namespace than its source's", x)
            SpendRefusal.Code.TRANSFER_NOT_FOR_RECIPIENT -> ErrorCatalog.error("transfer_not_for_recipient", "That move of budget room names another device", x)
            null -> when (r.rule) {
                1 -> ErrorCatalog.error("spending_off", "Spending is switched off for this spend ($why)", x)
                2 -> ErrorCatalog.error("spend_not_allowed", "This agent or applet may not spend here ($why)", x)
                3 -> ErrorCatalog.error("spend_source_not_allowed", "The source or route is not one this device may spend from ($why)", x)
                4 -> ErrorCatalog.error("spend_over_cap", "The spend is above the per-spend cap ($why)", x)
                5 -> ErrorCatalog.error("spend_bridgit_cap", "A Bridgit-funded cap has no room for this spend", x)
                6 -> ErrorCatalog.error("spend_over_budget", "An owner budget has no room left in this device's portion for the period", x)
                7 -> ErrorCatalog.error("rate_limited", "Too many paid calls from this source in the last hour", x)
                // Rules 9 and 10, the cloud path, an instrument with no adapter: not in this build, closed.
                else -> ErrorCatalog.error("platform_unsupported", "This spend needs a part of the spending authority this build does not have", x + ("reason" to (r.reason?.id ?: "not_built")))
            }
        }
    }

    /** A gate's verdict on a change (G6.2), as an API error. [budget] is the budget asked about, when there is one. */
    fun gate(v: Gates.Verdict?, budget: String? = null): ApiError {
        val x = LinkedHashMap<String, Any?>()
        budget?.let { x["budget"] = it }
        fun step(text: String, step: String) = JSONObject().put("step", step).put("where", "this device").put("status", "waiting").put("detail", text)
        return when (v) {
            is Gates.Verdict.Refused -> {
                x["reason"] = v.why.id
                v.field?.let { x["field"] = it }
                when (v.why) {
                    Gates.Why.BUDGETS_CAPABILITY_REQUIRED -> ErrorCatalog.error("agent_not_allowed", "Changing budgets needs the Budgets capability, which this agent does not hold", x + ("capability" to "budgets"))
                    Gates.Why.AGENTS_MAY_NOT_CHANGE -> ErrorCatalog.error("agents_may_not_change", "Agent changes are turned off for this budget", x)
                    Gates.Why.OVER_CEILING -> ErrorCatalog.error("over_ceiling", "The raise is larger than this device's headroom under the agent ceiling", x)
                    Gates.Why.RAISE_THE_CEILING_FIRST -> ErrorCatalog.error("raise_the_ceiling_first", "The raise does not fit the room under the ceiling that no headroom balance holds; raise the ceiling first", x)
                }
            }
            is Gates.Verdict.NoApprover -> ErrorCatalog.error("no_approver", "The owner set this gate, and only the owner lifts it; nothing was changed",
                x + mapOf("reason" to "owner_gate", "gate_strength" to "grants", "remaining_human_step" to step(v.remainingHumanStep, "owner_lifts_gate")))
            is Gates.Verdict.AwaitingOwner -> ErrorCatalog.error("owner_authorization_required", "The owner set this gate; lifting it needs the owner's check on an approver device",
                x + mapOf("reason" to "owner_gate", "gate_strength" to "approver", "remaining_human_step" to step(v.remainingHumanStep, "owner_check")))
            is Gates.Verdict.Allowed, null -> ErrorCatalog.error("conflict", "The change was not taken", x)
        }
    }

    private fun entry(e: Journal.Entry?): Any = e?.let { JSONObject(it.json) } ?: JSONObject.NULL

    /** What one spend did. A refusal is the same object an API error carries, under `refusal`, with `held: false`. */
    fun outcome(o: SpendRunner.Outcome, namespace: Namespace, requestId: String = "debug"): JSONObject {
        val out = JSONObject().put("spend_id", o.spendId).put("namespace", namespace.id)
        return when (o) {
            is SpendRunner.Outcome.Settled -> out.put("result", "settled").put("held", money(o.held)).put("cost", money(o.cost)).put("overshoot", o.overshoot).put("entry", entry(o.entry))
            is SpendRunner.Outcome.GivenBack -> out.put("result", "given_back").put("reason", o.reason).put("entry", entry(o.entry))
            is SpendRunner.Outcome.Unknown -> out.put("result", "unknown").put("reason", o.reason).put("entry", entry(o.entry))
            is SpendRunner.Outcome.Known -> out.put("result", "known").put("historical", o.historical).put("state", o.entry?.state?.id ?: JSONObject.NULL).put("entry", entry(o.entry))
            is SpendRunner.Outcome.Refused -> refusal(o.refusal, namespace, o.detail).let { e -> out.put("result", "refused").put("http", e.http).put("refusal", e.toJson(requestId).also { it.remove("report_problems") }) }
        }
    }
}
