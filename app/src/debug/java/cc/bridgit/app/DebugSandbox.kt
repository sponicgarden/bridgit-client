package cc.bridgit.app

import android.content.Intent
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.core.spending.Caller
import cc.bridgit.app.core.spending.Money
import cc.bridgit.app.core.spending.SpendRunner
import cc.bridgit.app.core.spending.Spending
import cc.bridgit.app.core.spending.TimeAnchor
import cc.bridgit.app.core.spending.sandbox.SandboxAdapter.Detail
import cc.bridgit.app.ownerapi.ConfigurationSpending
import cc.bridgit.app.ownerapi.SpendWire
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug builds only (debug and test_claude packages; this file is not in a release build): drives the
 * sandbox instrument of the spending core over ADB, for the SA3.1 checks (spending authority G10.1 row 1).
 * No money moves: the sandbox provider is a test double in its own accounting namespace.
 *
 * Extras of `cc.bridgit.app.debug.SETUP`, each a list of `key=value` joined by commas. The answer is the
 * broadcast's result data and a line `sandbox {...}` under the log tag BridgitDebug.
 *
 * - `sandbox_spend`: one spend, quote to settle. `cost` (atoms of USD-6, required), `source` (default: the
 *   first linked), `request` (the request id; the same one is the same spend), `agent` (an agent's name; the
 *   owner spends when left out), `applet`, `service`, `route`, `action`, `attempt`, `namespace` (what the
 *   spend block claims), and what the provider should do: `lose_answer=true`, `bill_past_quote=<atoms>`,
 *   `charge_late=true`, `delay_ms=<ms>`, `ignore_spend_id=true`.
 * - `sandbox_host`: the provider's own controls for `source`: `clock_ms=<shift>` (its time, ahead or behind),
 *   `limit=<atoms>|none` (a cap the provider enforces), `settle_pending=true`, `account_wide=true|false`
 *   (answer usage reads with one figure for the whole test account).
 * - `sandbox_reconcile=<source>`: compare the journal with what the provider shows.
 * - `sandbox_anchor=<source>`: read the provider's time and offer it to the journal.
 * - `sandbox_online=false|true|device`: what the core is told about the network (`device`: the real state).
 * - `sandbox_calls_per_hour=<n>|none`: the rate limit of rule 7, which has no contract value yet.
 * - `sandbox_gate`: a budget's gate set as the owner, which no screen does yet: `budget=<id>`,
 *   `agents_may_change=true|false`, `ceiling=<atoms>|none`.
 */
object DebugSandbox {
    val EXTRAS = listOf("sandbox_online", "sandbox_calls_per_hour", "sandbox_host", "sandbox_gate", "sandbox_spend", "sandbox_reconcile", "sandbox_anchor")

    /** `a=1,b=two` as a map; a part with no `=` or no key is refused. */
    fun spec(text: String): Map<String, String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate { part ->
        val at = part.indexOf('=')
        require(at > 0) { "Expected key=value, got $part" }
        part.substring(0, at).trim() to part.substring(at + 1).trim()
    }

    private fun only(spec: Map<String, String>, vararg keys: String) = require(spec.keys.all { it in keys }) { "Unknown key ${spec.keys.first { it !in keys }}; use ${keys.joinToString(", ")}" }
    private fun Map<String, String>.long(key: String): Long? = this[key]?.let { requireNotNull(it.toLongOrNull()) { "$key must be a whole number" } }
    private fun Map<String, String>.flag(key: String): Boolean? = this[key]?.let { require(it == "true" || it == "false") { "$key must be true or false" }; it == "true" }

    private fun source(s: Spending.Space, named: String?): Spending.Source =
        requireNotNull(if (named == null) s.sources().firstOrNull() else s.source(named)) { "No such linked sandbox source; link one with POST /v1/owner/configuration/spending/sources" }

    /** Everything [intent] asks for, in a fixed order (controls first, then the spend), as one object keyed by extra. */
    fun handle(app: BridgeApp, intent: Intent): JSONObject {
        val out = JSONObject()
        for (name in EXTRAS) intent.getStringExtra(name)?.let { text ->
            out.put(name, try { run(app, name, text) } catch (e: ApiError) {
                e.toJson("debug").also { it.remove("report_problems") }.put("http", e.http)
            } catch (e: Exception) {
                JSONObject().put("error", "invalid_parameter").put("message", e.message ?: e.javaClass.simpleName)
            })
        }
        return out
    }

    fun run(app: BridgeApp, name: String, text: String): JSONObject = when (name) {
        "sandbox_online" -> {
            when (text) { "device" -> app.spending.useDeviceNetwork(); "true" -> app.spending.online = { true }; "false" -> app.spending.online = { false }; else -> throw IllegalArgumentException("sandbox_online is true, false or device") }
            JSONObject().put("online", app.spending.online())
        }
        "sandbox_calls_per_hour" -> {
            app.spending.callsPerHourForTests = if (text == "none") null else requireNotNull(text.toIntOrNull()?.takeIf { it >= 0 }) { "sandbox_calls_per_hour is a whole number or none" }
            JSONObject().put("calls_per_hour", app.spending.callsPerHourForTests ?: JSONObject.NULL)
        }
        "sandbox_host" -> app.spending.guarded { host(app.spending.sandbox(), spec(text)) }
        "sandbox_gate" -> gate(app, spec(text))
        "sandbox_spend" -> app.spending.guarded { spend(app, app.spending.sandbox(), spec(text)) }
        "sandbox_reconcile" -> app.spending.guarded { reconcile(app.spending.sandbox(), text) }
        "sandbox_anchor" -> app.spending.guarded { app.spending.sandbox().let { s -> JSONObject().put("anchor", anchor(s.runner.anchor(source(s, text.ifEmpty { null }).id))) } }
        else -> throw IllegalArgumentException("Unknown extra $name")
    }

    private fun anchor(r: TimeAnchor.Result?): Any = when (r) {
        null -> JSONObject.NULL
        is TimeAnchor.Result.Accepted -> "accepted"
        is TimeAnchor.Result.Ignored -> "ignored:${r.why.name.lowercase()}"
    }

    private fun spend(app: BridgeApp, s: Spending.Space, spec: Map<String, String>): JSONObject {
        only(spec, "cost", "source", "request", "agent", "applet", "service", "route", "action", "attempt", "namespace", "lose_answer", "bill_past_quote", "charge_late", "delay_ms", "ignore_spend_id")
        val cost = requireNotNull(spec.long("cost")) { "cost is required: atoms of USD-6 (1000000 is one dollar)" }
        // An unlinked source is asked for by name so rule 3 can answer; with none named, the first linked one is used.
        val sourceId = spec["source"] ?: source(s, null).id
        val agent = spec["agent"]?.let { requireNotNull(app.agents.byName(it)) { "No agent called $it" } }
        val detail = LinkedHashMap<String, String>()
        spec.flag("lose_answer")?.let { detail[Detail.LOSE_ANSWER] = it.toString() }
        spec.long("bill_past_quote")?.let { detail[Detail.BILL_PAST_QUOTE_ATOMS] = it.toString() }
        spec.flag("charge_late")?.let { detail[Detail.CHARGE_LATE] = it.toString() }
        spec.long("delay_ms")?.let { detail[Detail.DELAY_MS] = it.toString() }
        spec.flag("ignore_spend_id")?.let { detail[Detail.IGNORE_SPEND_ID] = it.toString() }
        val request = SpendRunner.Request(
            requestId = spec["request"] ?: "debug-${System.currentTimeMillis()}", source = sourceId, maxCost = Money("USD-6", cost),
            principalKind = if (agent != null) "agent" else "owner", principalId = agent?.id ?: "owner", attempt = spec.long("attempt")?.toInt() ?: 0,
            applet = spec["applet"], action = spec["action"], service = spec["service"], route = spec["route"], claimedNamespace = spec["namespace"], detail = detail,
        )
        return SpendWire.outcome(s.runner.run(s.installId, request), s.namespace).put("request", request.requestId).put("source", sourceId)
    }

    private fun host(s: Spending.Space, spec: Map<String, String>): JSONObject {
        only(spec, "source", "clock_ms", "limit", "settle_pending", "account_wide")
        val src = source(s, spec["source"])
        spec.long("clock_ms")?.let { s.host.clock(src.key, it) }
        spec["limit"]?.let { s.host.limit(src.key, if (it == "none") null else requireNotNull(it.toLongOrNull()?.takeIf { n -> n >= 0 }) { "limit is atoms or none" }) }
        if (spec.flag("settle_pending") == true) s.host.settlePending(src.key)
        spec.flag("account_wide")?.let { s.host.accountWideUsage = it }
        val usage = s.host.usage(src.key)
        val (cap, shift) = s.host.controls(src.key)
        return JSONObject().put("source", src.id).put("clock_ms", shift).put("limit", cap ?: JSONObject.NULL).put("account_wide", s.host.accountWideUsage)
            .put("usage", JSONObject().put("scope", usage.scope).put("period", usage.period).put("total_atoms", usage.totalAtoms).put("host_time", usage.time.epochMs)
                .put("charges", JSONArray(usage.charges.map { JSONObject().put("spend_id", it.spendId).put("atoms", it.costAtoms).put("pending", it.pending) })))
    }

    private fun reconcile(s: Spending.Space, text: String): JSONObject {
        val r = s.runner.reconcile(source(s, text.ifEmpty { null }).id)
        return JSONObject().put("source", r.source).put("read", r.read).put("settled", JSONArray(r.settled)).put("given_back", JSONArray(r.givenBack)).put("raised", JSONArray(r.raised))
            .put("left", JSONArray(r.left)).put("anchor", anchor(r.anchor))
            .put("coverage", r.coverage?.let { JSONObject().put("scope", it.scope).put("period", it.period).put("from", it.fromMs).put("until", it.untilMs).put("complete", it.complete) } ?: JSONObject.NULL)
    }

    private fun gate(app: BridgeApp, spec: Map<String, String>): JSONObject {
        only(spec, "budget", "agents_may_change", "ceiling")
        val id = requireNotNull(spec["budget"]) { "budget is required" }
        val body = JSONObject().put("namespace", "sandbox")
        spec.flag("agents_may_change")?.let { body.put("agents_may_change", it) }
        spec["ceiling"]?.let { body.put("ceiling", if (it == "none") JSONObject.NULL else JSONObject().put("unit", "USD-6").put("atoms", requireNotNull(it.toLongOrNull()) { "ceiling is atoms or none" })) }
        return ConfigurationSpending.setGate(app, Caller("owner", "owner", "app"), true, id, body)
    }
}
