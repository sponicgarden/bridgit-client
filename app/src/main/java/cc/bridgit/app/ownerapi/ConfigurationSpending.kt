package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.api.Req
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.contract.ContractVocab
import cc.bridgit.app.core.spending.Caller
import cc.bridgit.app.core.spending.Canonical
import cc.bridgit.app.core.spending.Increments
import cc.bridgit.app.core.spending.JournalRefused
import cc.bridgit.app.core.spending.Money
import cc.bridgit.app.core.spending.MoneyException
import cc.bridgit.app.core.spending.Namespace
import cc.bridgit.app.core.spending.PeriodClock
import cc.bridgit.app.core.spending.RuleDocument
import cc.bridgit.app.core.spending.RuleDocuments
import cc.bridgit.app.core.spending.Spending
import cc.bridgit.app.core.spending.SpendingLimits
import cc.bridgit.app.core.spending.allowanceViews
import cc.bridgit.app.core.spending.budgetView
import cc.bridgit.app.core.spending.budgetViews
import cc.bridgit.app.core.spending.journalView
import cc.bridgit.app.core.spending.rulesView
import cc.bridgit.app.core.spending.sandbox.SandboxAdapter
import cc.bridgit.app.core.spending.spendSummary
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The spending authority's configuration API on the device (spending authority G11), for the sandbox
 * namespace. Part of ConfigurationEndpoints.ALL, so both MCP servers' configuration.get/set list these.
 *
 * Everything is behind the owner setting `spending.core_enabled` (POST /v1/owner/configuration/settings),
 * off by default: reads then answer `enabled: false` and writes are refused, without opening anything.
 *
 * **Namespace.** Reads take `?namespace=` and making writes carry `namespace`; both default to `live`
 * (G10.3 X10), and the live namespace is not in this build: it is refused with `platform_unsupported`.
 * Callers send `sandbox`. A write on a source needs none: its instrument fixes it.
 *
 * **Who may change what.** The caller is an agent holding owner.configuration and acts with the owner's
 * authority. Budgets, raises, top-ups and gates also need the Budgets capability (G6.2). A gate an agent set
 * is its own restraint and any such agent lifts it; a gate the owner set is lifted by the owner. Every write
 * takes an Idempotency-Key and is audit-logged; each rule change is signed with the install key and carries
 * the calling agent as its actor.
 */
object ConfigurationSpending {
    private const val P = "/owner/configuration/spending"
    private val ERRORS = listOf("invalid_parameter", "forbidden", "agent_not_allowed", "capability_disabled", "platform_unsupported", "conflict", "not_found",
        "namespace_mismatch", "agents_may_not_change", "over_ceiling", "raise_the_ceiling_first", "no_approver", "owner_authorization_required", "team_unavailable")
    private val NS = Param("namespace", "string", "live (the default) or sandbox. Only sandbox is available on this build; live is refused with platform_unsupported", enum = listOf("live", "sandbox"))
    private val NS_BODY = NS.copy(description = "live (the default) or sandbox. Only sandbox is available on this build")
    private val GENERATION = Param("generation", "integer", "The rule document generation this change replaces (from a read); a change against an older one is refused with conflict. Left out, the current one is used")
    private const val MONEY = "{unit, atoms} (a whole number of the unit's smallest part) or {unit, amount} (plain decimal text); USD-6 is millionths of a dollar"
    private const val BUDGET = "id, scope{kind,id}, period, unit, amount (what stands for good), base, total (with this period's top-ups), rebalance, gate{agents_may_change, ceiling, set_by, gate_strength agent|grants|approver}, " +
        "portions{cloud, devices{install:{portion, kind, headroom}}}, this_device{portion, headroom, used, remaining, period_id, resets_at, standing current|unanchored|not_open, frozen, holds_reclaim}, increments[], in_transit[], taken[]"

    private fun read(path: String, what: String, returns: String, query: List<Param> = listOf(NS)) = EndpointSpec("GET", "$P$path", "owner_features", what,
        query = query, example = "GET /v1$P$path?namespace=sandbox", returns = returns, errors = listOf("forbidden", "agent_not_allowed", "platform_unsupported", "invalid_parameter"), controlScope = "owner.configuration")
    private fun write(method: String, path: String, what: String, body: List<Param>, example: String, returns: String) = EndpointSpec(method, "$P$path", "owner_features",
        "$what; explicit owner.configuration grant required", body = body, example = "$method /v1$P$path $example".trim(), returns = returns, errors = ERRORS,
        idempotent = true, idempotencyRequired = true, controlScope = "owner.configuration", privateFields = setOf("*"), maxBody = 16 * 1024)

    val GET = read("", "Read the spending core: whether it is on (owner setting spending.core_enabled), which namespaces this build has, and for namespace=sandbox its switches, journal state and time anchor. With no namespace it answers for live, which is not available yet",
        "enabled, setting, namespace, available, namespaces[], and for sandbox: switches{paused, device_paused, classes, sources}, payments_on, online, install, rules{generation, digest} or null, " +
            "journal{epoch, origin, seq, whole, reset_reason, history_blocked, time{now, anchored, last_anchor_at, usable}}, read_at")
    val SET = write("POST", "", "Turn spending on or off for the namespace: the pause, per instrument class and per source. Off applies at once; nothing here changes today's live budgets",
        listOf(NS_BODY, Param("paused", "boolean", "true pauses every spend of the namespace"), Param("classes", "object", "Instrument class to true or false, for example {\"metered\": false}"),
            Param("sources", "object", "Source id to true or false"), GENERATION),
        "{\"namespace\":\"sandbox\",\"paused\":true}", "the same as GET, plus changed")
    val SOURCES = read("/sources", "List the linked sources of a namespace: instrument, provider, custody, cap and whether the key is set. A key is never returned",
        "sources[{id, instrument, provider, class, namespace, funder, custody, cap provider|core_only, configured, device, host, on, time_source, usage_read, available, linked_at}], instruments[]")
    val SOURCE_LINK = write("POST", "/sources", "Link a source. This build links the sandbox instrument only: a sandbox key is made at the sandbox provider and stored on this device, write-only. It needs no namespace: the instrument fixes it",
        listOf(Param("instrument", "string", "sandbox", true), Param("id", "string", "A name for the source, 1 to 64 of a-z 0-9 . _ -; made up when left out")),
        "{\"instrument\":\"sandbox\",\"id\":\"sandbox-1\"}", "the source as in the list; conflict when the id is taken or was linked before (reason id_retired)")
    val SOURCE_UNLINK = write("DELETE", "/sources/{id}", "Unlink a source and remove its key from this device. What it spent stays in the journal; an unknown hold of it can no longer be reconciled and keeps its hold. Its id is retired with it: a later link takes another id",
        emptyList(), "", "unlinked, id")
    val RULES = read("/rules", "Read the rule document of a namespace: generation, time zone, switches and per-spend caps, and who signed it",
        "generation, digest, zone, switches, caps{instruments, sources}, executor, signed_by, actor, account; generation 0 when there is none yet")
    val RULES_SET = write("POST", "/rules", "Change the budget time zone or a per-spend cap (per instrument or per source). Lowering is open to any configuration agent; raising or removing a cap needs the Budgets capability. A zone change never resets a period",
        listOf(NS_BODY, Param("zone", "string", "An IANA time zone, for example Europe/Warsaw"),
            Param("caps", "object", "{instruments: {id: money or null}, sources: {id: money or null}}; null removes a cap. Money is $MONEY"), GENERATION),
        "{\"namespace\":\"sandbox\",\"caps\":{\"instruments\":{\"sandbox\":{\"unit\":\"USD-6\",\"atoms\":500000}}}}", "the same as GET /rules, plus changed")
    val BUDGETS = read("/budgets", "List the owner budgets of a namespace with this device's portion, what it has used this period and when that resets, the change gate with who set it, and the raises applied",
        "generation, digest, budgets[{$BUDGET}]")
    val BUDGET_CREATE = write("POST", "/budgets", "Create an owner budget. This device is its executor and holds the whole amount as a standing share. Needs the Budgets capability",
        listOf(NS_BODY, Param("id", "string", "1 to 64 of a-z 0-9 . _ -", true),
            Param("scope", "object", "{kind: account|device|instrument|service|applet|agent, id}. account and device need no id (device is this device); an instrument must be one of this namespace", true),
            Param("period", "string", "hour, day, week or month, in the rule document's time zone", true, PeriodClock.KINDS),
            Param("amount", "object", "The budget per period: $MONEY", true),
            Param("rebalance", "boolean", "Rebalance shares between devices by use (default true)"),
            Param("gate", "object", "{agents_may_change: boolean, ceiling: money or null}. Under a ceiling this device's headroom balance is the whole room between the amount and the ceiling"), GENERATION),
        "{\"namespace\":\"sandbox\",\"id\":\"sandbox-day\",\"scope\":{\"kind\":\"instrument\",\"id\":\"sandbox\"},\"period\":\"day\",\"amount\":{\"unit\":\"USD-6\",\"amount\":\"1.00\"}}",
        "budget{$BUDGET}, generation")
    val BUDGET_UPDATE = write("PATCH", "/budgets/{id}", "Change a budget. A lower amount is a reduction and applies at once. A higher amount is a standing raise: an increment with an id, applied once, drawn from this device's headroom balance under an agent ceiling. " +
        "A new scope or period never opens a period early. Needs the Budgets capability",
        listOf(NS_BODY, Param("amount", "object", "The new standing amount: $MONEY. Send it alone when it is a raise"), Param("rebalance", "boolean", "Rebalance shares by use"),
            Param("scope", "object", "{kind, id} as when creating"), Param("period", "string", "hour, day, week or month", enum = PeriodClock.KINDS), GENERATION),
        "{\"namespace\":\"sandbox\",\"amount\":{\"unit\":\"USD-6\",\"amount\":\"0.50\"}}", "budget{$BUDGET}, generation, increment{id, amount, lasts, replayed} when it was a raise; replayed: true when this Idempotency-Key already did it (nothing is worked out again)")
    val BUDGET_DELETE = write("DELETE", "/budgets/{id}", "Remove a budget. A reduction: it applies at once, unless the owner's gate stands on it. The namespace goes in the query",
        emptyList(), "", "removed, id, generation").let { it.copy(query = listOf(NS, GENERATION), example = "DELETE /v1$P/budgets/sandbox-day?namespace=sandbox") }
    val BUDGET_TOPUP = write("POST", "/budgets/{id}/topups", "Top a budget up for the current period only: an increment with an id that raises this device's portion by its amount, applied once per Idempotency-Key, and under an agent ceiling drawn from this device's headroom balance",
        listOf(NS_BODY, Param("amount", "object", "How much to add for this period: $MONEY", true)),
        "{\"namespace\":\"sandbox\",\"amount\":{\"unit\":\"USD-6\",\"amount\":\"0.25\"}}", "increment{id, amount, lasts period, period, draws_headroom, replayed}, budget{$BUDGET}")
    val BUDGET_GATE = write("POST", "/budgets/{id}/gate", "Set or change a budget's change gate: agents_may_change and the agent ceiling. Setting or tightening is open to any agent with the Budgets capability. Lifting one the owner set is the owner's: no_approver with remaining_human_step. A ceiling added to an existing budget starts with no headroom balance",
        listOf(NS_BODY, Param("agents_may_change", "boolean", "false: agents may only reduce this budget"), Param("ceiling", "object", "The most agents may raise the budget to, as money ($MONEY), or null for none"), GENERATION),
        "{\"namespace\":\"sandbox\",\"ceiling\":{\"unit\":\"USD-6\",\"amount\":\"2.00\"}}", "budget{$BUDGET}, generation")
    val ALLOWANCES = read("/allowances", "Read how each budget is divided and what this device holds: portions and share kinds, headroom balances, the allowance each source last spent under, room in transit, the last time anchor and the journal's epoch",
        "budgets[{id, portions, this_device, increments, in_transit, taken, reclaim_holder, pending_cloud_room}], held{source: {id, issuer rules|cloud, seq, lines, expired}}, journal{epoch, whole, time{anchored, last_anchor_at, usable}}, read_at")
    val JOURNAL = read("/journal", "Read the spend journal of a namespace: entries of the current epoch (or another) in order, as entry/1. It answers from the device, with or without a network",
        "epoch, epochs[], head{seq, hash, origin}, entries[entry/1], next_after, more, read_at",
        listOf(NS, Param("after", "integer", "Entries after this sequence number (default 0)"), Param("limit", "integer", "1 to 200 (default 50)"), Param("epoch", "string", "A journal epoch id; default the current one")))
    val JOURNAL_ACK = write("POST", "/journal", "Acknowledge unreadable history: rows of an earlier journal epoch that can be tied to no request stop every spend until the owner or an agent takes that on. Nothing is given back by it",
        listOf(NS_BODY, Param("acknowledge_history", "boolean", "true", true)), "{\"namespace\":\"sandbox\",\"acknowledge_history\":true}", "acknowledged, journal{...}")
    val SUMMARY = read("/summary", "Read what was spent in a namespace: per budget the portion, what is used and when it resets; per source the counts and amounts by state. It answers from the device's journal, with its age",
        "budgets[{id, unit, portion, used, remaining, resets_at, standing}], sources{id: {state: {count, counted[]}}}, last_entry_at, age_ms, online, read_at")
    val LIMITS = read("/limits", "Read the Bridgit-funded limits as mirrored from the cloud, and the contract limits of the spending core. Nothing is mirrored on this build: no Bridgit-funded source exists on the device-held path",
        "bridgit_funded[], mirrored_at, age_ms, contract{anchor_skew_ms}")

    val ALL = listOf(GET, SET, SOURCES, SOURCE_LINK, SOURCE_UNLINK, RULES, RULES_SET, BUDGETS, BUDGET_CREATE, BUDGET_UPDATE, BUDGET_DELETE, BUDGET_TOPUP, BUDGET_GATE,
        ALLOWANCES, JOURNAL, JOURNAL_ACK, SUMMARY, LIMITS)

    private const val RULE_CHANGE = "rules"
    private val ID = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private fun bad(message: String, field: String? = null): Nothing = throw ErrorCatalog.error("invalid_parameter", message, field?.let { mapOf("field" to it) } ?: emptyMap())
    private fun off() = JSONObject().put("enabled", false).put("setting", Spending.SETTING)

    /** Stops a rule change from inside its own transaction, which is then rolled back. */
    private class Stop(val error: ApiError?) : RuntimeException()

    // ---- reads ----

    fun get(app: BridgeApp, namespace: String?): JSONObject {
        val sp = app.spending
        val named = namespace ?: ContractVocab.SPEND_DEFAULT_NAMESPACE
        if (Namespace.claimed(named) == null) bad("namespace must be live or sandbox", "namespace")
        val base = (if (sp.enabled) JSONObject().put("enabled", true).put("setting", Spending.SETTING) else off()).put("namespace", named)
            .put("namespaces", JSONArray(ContractVocab.SPEND_NAMESPACES)).put("available_namespaces", JSONArray(listOf(Namespace.SANDBOX.id)))
        // Live is not in this build, and a read with no namespace shows nothing of the sandbox (G10.3 X10).
        if (named != Namespace.SANDBOX.id) return base.put("available", false).put("reason", "not_built")
        if (!sp.enabled) return base.put("available", true)
        return sp.guarded {
            val s = sp.sandbox()
            val doc = s.core.document()
            base.put("available", true).put("install", s.installId).put("online", sp.online()).put("payments_on", false)
                .put("switches", JSONObject().put("paused", doc?.paused ?: false).put("device_paused", app.settings.externalCallsPaused)
                    .put("classes", SpendWire.json(doc?.map?.get("switches")?.let { (it as Map<*, *>)["classes"] } ?: emptyMap<String, Any?>()))
                    .put("sources", SpendWire.json(doc?.map?.get("switches")?.let { (it as Map<*, *>)["sources"] } ?: emptyMap<String, Any?>())))
                .put("rules", doc?.let { JSONObject().put("generation", it.generation).put("digest", it.digest) } ?: JSONObject.NULL)
                .put("journal", SpendWire.obj(s.core.journalView())).put("sandbox_host", s.hostName).put("read_at", app.spending.clock.wallMs())
        }
    }

    private fun source(s: Spending.Space, src: Spending.Source): JSONObject {
        val e = s.engineSource(src)
        val a = s.adapter(e)
        val d = a?.describe()
        return JSONObject().put("id", src.id).put("instrument", src.instrument).put("provider", src.instrument).put("class", e.instrumentClass).put("namespace", s.namespace.id)
            .put("funder", e.funder).put("custody", e.custody).put("cap", e.cap).put("configured", true).put("device", s.installId).put("host", s.hostName)
            .put("on", s.core.document()?.sourceOn(src.id) ?: true).put("time_source", d?.timeSource ?: JSONObject.NULL)
            .put("usage_read", d?.let { JSONObject().put("scope", it.usageRead.scope).put("per_period", it.usageRead.perPeriod).put("pending_included", it.usageRead.pendingIncluded) } ?: JSONObject.NULL)
            .put("available", a?.available(src.id)?.let(SpendWire::money) ?: JSONObject.NULL).put("linked_at", src.linkedMs)
    }

    fun sources(app: BridgeApp, namespace: String?): JSONObject = app.spending.guarded {
        val s = app.spending.space(namespace)
        JSONObject().put("namespace", s.namespace.id).put("sources", JSONArray(s.sources().map { source(s, it) }))
            .put("instruments", JSONArray(ContractVocab.INSTRUMENTS.filter { it.namespace == s.namespace.id }.map { JSONObject().put("id", it.id).put("class", it.instrumentClass).put("unit", it.unit) }))
    }

    private fun rulesJson(s: Spending.Space): JSONObject =
        s.core.rulesView()?.let(SpendWire::obj) ?: JSONObject().put("generation", 0).put("digest", JSONObject.NULL).put("namespace", s.namespace.id)

    fun rules(app: BridgeApp, namespace: String?): JSONObject = app.spending.guarded { rulesJson(app.spending.space(namespace)) }

    private fun generation(s: Spending.Space): Long = s.core.document()?.generation ?: 0

    fun budgets(app: BridgeApp, namespace: String?): JSONObject = app.spending.guarded {
        val s = app.spending.space(namespace)
        val doc = s.core.document()
        JSONObject().put("namespace", s.namespace.id).put("generation", doc?.generation ?: 0).put("digest", doc?.digest ?: JSONObject.NULL)
            .put("budgets", SpendWire.json(s.core.budgetViews(s.approverEnrolled())))
    }

    fun allowances(app: BridgeApp, namespace: String?): JSONObject = app.spending.guarded {
        val s = app.spending.space(namespace)
        val keep = setOf("id", "unit", "portions", "this_device", "increments", "in_transit", "taken", "reclaim_holder")
        // No cloud portion is leased in this build, so no cloud room is ever pending.
        val budgets = s.core.budgetViews(s.approverEnrolled()).map { b -> b.filterKeys { it in keep } + ("pending_cloud_room" to null) }
        JSONObject().put("namespace", s.namespace.id).put("generation", generation(s)).put("budgets", SpendWire.json(budgets))
            .put("held", SpendWire.json(s.core.allowanceViews())).put("journal", SpendWire.obj(s.core.journalView())).put("read_at", app.spending.clock.wallMs())
    }

    fun journal(app: BridgeApp, namespace: String?, after: String?, limit: String?, epoch: String?): JSONObject = app.spending.guarded {
        val s = app.spending.space(namespace)
        val from = after?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: bad("after must be a whole number, 0 or more", "after") } ?: 0
        val max = limit?.let { it.toIntOrNull()?.takeIf { n -> n in 1..200 } ?: bad("limit must be 1 to 200", "limit") } ?: 50
        val epochs = s.journal.epochs()
        val head = s.journal.head()
        val of = epoch?.also { if (it !in epochs) bad("No such journal epoch", "epoch") } ?: head.epoch
        val rows = s.journal.entries(of, from)
        val page = rows.take(max)
        JSONObject().put("namespace", s.namespace.id).put("epoch", of).put("epochs", JSONArray(epochs))
            .put("head", JSONObject().put("epoch", head.epoch).put("seq", head.seq).put("hash", head.hash).put("origin", head.origin.id))
            .put("entries", JSONArray(page.map { JSONObject(it.json) })).put("next_after", page.lastOrNull()?.seq ?: from).put("more", rows.size > page.size)
            .put("read_at", app.spending.clock.wallMs())
    }

    fun summary(app: BridgeApp, namespace: String?): JSONObject = app.spending.guarded {
        val s = app.spending.space(namespace)
        val now = app.spending.clock.wallMs()
        val spends = s.core.spendSummary()
        val budgets = s.core.budgetViews(s.approverEnrolled()).map { b ->
            @Suppress("UNCHECKED_CAST") val mine = b["this_device"] as Map<String, Any?>
            mapOf("id" to b["id"], "unit" to b["unit"], "period" to b["period"], "portion" to mine["portion"], "used" to mine["used"], "remaining" to mine["remaining"],
                "period_id" to mine["period_id"], "resets_at" to mine["resets_at"], "standing" to mine["standing"])
        }
        // The figures are the device's own journal, so they are as old as its last entry and need no network.
        JSONObject().put("namespace", s.namespace.id).put("budgets", SpendWire.json(budgets)).put("sources", SpendWire.json(spends["sources"]))
            .put("last_entry_at", spends["last_entry_at"] ?: JSONObject.NULL).put("age_ms", (spends["last_entry_at"] as? Long)?.let { (now - it).coerceAtLeast(0) } ?: JSONObject.NULL)
            .put("online", app.spending.online()).put("read_at", now)
    }

    fun limits(app: BridgeApp, namespace: String?): JSONObject = app.spending.guarded {
        val s = app.spending.space(namespace)
        JSONObject().put("namespace", s.namespace.id).put("bridgit_funded", JSONArray()).put("mirrored_at", JSONObject.NULL).put("age_ms", JSONObject.NULL)
            .put("contract", JSONObject().put("anchor_skew_ms", SpendingLimits.ANCHOR_SKEW_MS))
    }

    // ---- writes ----

    private fun namespaceOf(body: JSONObject): String? = when (val v = body.opt("namespace")) { null, JSONObject.NULL -> null; is String -> v; else -> bad("namespace must be live or sandbox", "namespace") }

    private fun generationOf(body: JSONObject): Long? = when (val v = body.opt("generation")) {
        null, JSONObject.NULL -> null
        is Int -> v.toLong()
        is Long -> v
        else -> bad("generation must be a whole number", "generation")
    }

    private fun flag(o: JSONObject, key: String): Boolean? = when (val v = o.opt(key)) { null -> null; is Boolean -> v; else -> bad("$key must be true or false", key) }

    private fun conflict(s: Spending.Space) = ErrorCatalog.error("conflict", "The rule document changed; read it again and retry against its generation", mapOf("generation" to generation(s)))

    /**
     * One change to the rule document. [edit] gets the current document (null for the first) and its content,
     * and returns the new content, or the same content for "nothing to change". It runs inside the journal's
     * transaction, so what it reads is what it replaces. Returns whether anything changed.
     */
    private fun change(s: Spending.Space, caller: Caller, capability: Boolean, generation: Long?, budget: String? = null, edit: (RuleDocument?, Map<String, Any?>) -> Map<String, Any?>): Boolean {
        repeat(3) {
            val cur = s.core.document()
            if (generation != null && generation != (cur?.generation ?: 0)) throw conflict(s)
            val out = try {
                s.core.change(caller, capability, cur?.digest, s.account) { content ->
                    // A first document takes the device's own time zone unless the caller names one.
                    val start = if (cur == null) content + ("zone" to java.util.TimeZone.getDefault().id) else content
                    val next = edit(cur, start)
                    if (cur != null && Canonical.encode(next) == Canonical.encode(content)) throw Stop(null)
                    next
                }
            } catch (e: Stop) {
                e.error?.let { throw it }
                return false
            } catch (e: MoneyException) {
                bad(e.message ?: "Bad amount")
            } catch (e: JournalRefused) {
                throw ErrorCatalog.error("conflict", "The spend journal refused the change (${e.why})", mapOf("reason" to e.why))
            } catch (e: IllegalArgumentException) {
                bad(e.message ?: "Bad value")
            }
            when (out) {
                is RuleDocuments.Adopt.Adopted, RuleDocuments.Adopt.Known -> return true
                RuleDocuments.Adopt.Waiting -> throw conflict(s)
                is RuleDocuments.Adopt.Refused -> when {
                    out.why == "conflict" -> if (generation != null) throw conflict(s)
                    out.verdict != null -> throw SpendWire.gate(out.verdict, budget)
                    else -> throw ErrorCatalog.error("invalid_parameter", "The rule document would not be valid (${out.why})", mapOf("reason" to out.why))
                }
            }
        }
        throw conflict(s)
    }

    @Suppress("UNCHECKED_CAST")
    private fun sub(m: Map<String, Any?>, key: String): Map<String, Any?> = m[key] as? Map<String, Any?> ?: emptyMap()

    private fun moneyMap(m: Money): Map<String, Any?> = mapOf("unit" to m.unit, "atoms" to m.atoms)

    fun set(app: BridgeApp, caller: Caller, capability: Boolean, body: JSONObject): JSONObject = app.spending.guarded {
        ConfigurationAccess.only(body, "namespace", "paused", "classes", "sources", "generation")
        val s = app.spending.space(namespaceOf(body))
        val paused = flag(body, "paused")
        fun switches(key: String, known: (String) -> Boolean): Map<String, Boolean> {
            val o = when (val v = body.opt(key)) { null -> return emptyMap(); is JSONObject -> v; else -> bad("$key must be an object of true or false", key) }
            return o.keys().asSequence().associateWith { id ->
                if (!known(id)) bad("$key has an unknown id: $id", key)
                o.opt(id) as? Boolean ?: bad("$key.$id must be true or false", key)
            }
        }
        val classes = switches("classes") { it in ContractVocab.INSTRUMENT_CLASSES }
        val sources = switches("sources") { ID.matches(it) }
        if (paused == null && classes.isEmpty() && sources.isEmpty()) bad("Nothing to change: send paused, classes or sources")
        val changed = change(s, caller, capability, generationOf(body)) { _, content ->
            val sw = sub(content, "switches")
            content + ("switches" to mapOf("paused" to (paused ?: (sw["paused"] == true)), "classes" to (sub(sw, "classes") + classes), "sources" to (sub(sw, "sources") + sources)))
        }
        get(app, s.namespace.id).put("changed", changed)
    }

    fun link(app: BridgeApp, body: JSONObject): JSONObject = app.spending.guarded {
        ConfigurationAccess.only(body, "instrument", "id")
        app.spending.requireEnabled()
        val instrument = body.opt("instrument") as? String ?: bad("instrument is required: sandbox", "instrument")
        // The instrument fixes the namespace (X10); everything but the sandbox instrument is live, which this build does not have.
        if (instrument != SandboxAdapter.ID) throw Spending.notBuilt("Only the sandbox instrument can be linked on this build; today's own keys are unchanged (POST /v1/owner/configuration/keys)", Namespace.of(instrument).id)
        val s = app.spending.sandbox()
        val id = when (val v = body.opt("id")) {
            null, JSONObject.NULL -> generateSequence(1) { it + 1 }.map { "sandbox-$it" }.first { s.free(it) }
            is String -> v.also { if (!ID.matches(it)) bad("id must be 1 to 64 of a-z 0-9 . _ -, starting with a letter or digit", "id") }
            else -> bad("id must be text", "id")
        }
        // An id is one credential for good: the journal names spends by it, so a new key under an old id could be asked about the old key's spends.
        if (s.retired(id)) throw ErrorCatalog.error("conflict", "A source called $id was linked here before; its id stays with its spends in the journal. Link under another id", mapOf("id" to id, "reason" to "id_retired"))
        val linked = s.link(id) ?: throw ErrorCatalog.error("conflict", "A source called $id is already linked", mapOf("id" to id))
        source(s, linked)
    }

    fun unlink(app: BridgeApp, id: String): JSONObject = app.spending.guarded {
        val s = app.spending.sandbox()
        if (!s.unlink(id)) throw ErrorCatalog.error("not_found", "No source called $id is linked")
        JSONObject().put("unlinked", true).put("id", id)
    }

    fun setRules(app: BridgeApp, caller: Caller, capability: Boolean, body: JSONObject): JSONObject = app.spending.guarded {
        ConfigurationAccess.only(body, "namespace", "zone", "caps", "generation")
        val s = app.spending.space(namespaceOf(body))
        val zone = when (val v = body.opt("zone")) {
            null -> null
            is String -> v.also { if (runCatching { java.time.ZoneId.of(it) }.isFailure || !it.all { c -> c in ' '..'~' }) bad("zone must be an IANA time zone, for example Europe/Warsaw", "zone") }
            else -> bad("zone must be text", "zone")
        }
        val caps = when (val v = body.opt("caps")) { null -> null; is JSONObject -> v; else -> bad("caps must be an object", "caps") }
        caps?.let { if (it.keys().asSequence().any { k -> k != "instruments" && k != "sources" }) bad("caps takes instruments and sources", "caps") }
        fun group(name: String): Map<String, Money?> {
            val o = when (val v = caps?.opt(name)) { null -> return emptyMap(); is JSONObject -> v; else -> bad("caps.$name must be an object", "caps") }
            return o.keys().asSequence().associateWith { id ->
                if (!ID.matches(id)) bad("caps.$name has a bad id", "caps")
                // A budget, a cap and a source are all of one namespace: a live instrument has no place in a sandbox document (X10).
                if (name == "instruments" && Namespace.of(id) != s.namespace) throw ErrorCatalog.error("namespace_mismatch", "Instrument $id is not of the ${s.namespace.id} namespace", mapOf("namespace" to s.namespace.id))
                if (o.isNull(id)) null else SpendWire.money(o.get(id), "caps.$name.$id")
            }
        }
        val instruments = group("instruments")
        val sources = group("sources")
        if (zone == null && instruments.isEmpty() && sources.isEmpty()) bad("Nothing to change: send zone or caps")
        val changed = change(s, caller, capability, generationOf(body)) { _, content ->
            val c = sub(content, "caps")
            fun merged(name: String, edits: Map<String, Money?>) = (sub(c, name) + edits.mapValues { it.value?.let(::moneyMap) }).filterValues { it != null }
            content + mapOf("zone" to (zone ?: content["zone"]), "caps" to mapOf("instruments" to merged("instruments", instruments), "sources" to merged("sources", sources)))
        }
        rulesJson(s).put("changed", changed)
    }

    private fun scope(s: Spending.Space, v: Any?): Pair<String, String> {
        val o = v as? JSONObject ?: bad("scope must be {kind, id}", "scope")
        if (o.keys().asSequence().any { it != "kind" && it != "id" }) bad("scope takes kind and id", "scope")
        val kind = o.opt("kind") as? String ?: bad("scope.kind is required", "scope")
        if (kind !in RuleDocument.SCOPES) bad("scope.kind must be one of ${RuleDocument.SCOPES.joinToString(", ")}", "scope")
        val given = when (val i = o.opt("id")) { null, JSONObject.NULL -> null; is String -> i; else -> bad("scope.id must be text", "scope") }
        val id = when (kind) {
            "account" -> s.account
            // Every budget made here has this device as its one executor, so a device budget is this device's.
            "device" -> s.installId.also { if (given != null && given != it) bad("A device budget made on this device is for this device: leave scope.id out", "scope") }
            else -> given ?: bad("scope.id is required for scope.kind $kind", "scope")
        }
        if (id.isEmpty() || id.length > 200 || !id.all { it in ' '..'~' }) bad("scope.id must be 1 to 200 printable ASCII characters", "scope")
        if (kind == "instrument" && Namespace.of(id) != s.namespace) throw ErrorCatalog.error("namespace_mismatch", "Instrument $id is not of the ${s.namespace.id} namespace, so a ${s.namespace.id} budget can't name it", mapOf("namespace" to s.namespace.id))
        return kind to id
    }

    private fun period(v: Any?): String = (v as? String)?.takeIf { it in PeriodClock.KINDS } ?: bad("period must be one of ${PeriodClock.KINDS.joinToString(", ")}", "period")

    private fun budgetJson(s: Spending.Space, id: String): JSONObject =
        JSONObject().put("namespace", s.namespace.id).put("generation", generation(s)).put("budget", s.core.budgetView(id, s.approverEnrolled())?.let(SpendWire::obj) ?: JSONObject.NULL)

    private fun budgetId(id: String?): String = id?.takeIf { ID.matches(it) } ?: bad("A budget id is 1 to 64 of a-z 0-9 . _ -, starting with a letter or digit", "id")

    private fun missing(id: String) = ErrorCatalog.error("not_found", "No budget called $id")

    fun createBudget(app: BridgeApp, caller: Caller, capability: Boolean, body: JSONObject): JSONObject = app.spending.guarded {
        ConfigurationAccess.only(body, "namespace", "id", "scope", "period", "amount", "rebalance", "gate", "generation")
        val s = app.spending.space(namespaceOf(body))
        val id = budgetId(body.opt("id") as? String)
        val (kind, scopeId) = scope(s, body.opt("scope"))
        val period = period(body.opt("period"))
        val amount = SpendWire.money(body.opt("amount"), "amount")
        val rebalance = flag(body, "rebalance") ?: true
        val gate = when (val v = body.opt("gate")) { null, JSONObject.NULL -> JSONObject(); is JSONObject -> v; else -> bad("gate must be an object", "gate") }
        if (gate.keys().asSequence().any { it != "agents_may_change" && it != "ceiling" }) bad("gate takes agents_may_change and ceiling", "gate")
        val agentsMayChange = flag(gate, "agents_may_change") ?: true
        val ceiling = if (!gate.has("ceiling") || gate.isNull("ceiling")) null else SpendWire.money(gate.get("ceiling"), "gate.ceiling")
        if (ceiling != null && (ceiling.unit != amount.unit || ceiling < amount)) bad("gate.ceiling must be in the budget's unit and at least its amount", "gate")
        val me = s.installId
        change(s, caller, capability, generationOf(body), id) { _, content ->
            val budgets = sub(content, "budgets")
            if (id in budgets) throw Stop(ErrorCatalog.error("conflict", "A budget called $id exists; change it with PATCH", mapOf("id" to id)))
            // One device: it holds the whole budget as a standing share, and under a ceiling the whole room is its headroom balance (G5.1).
            val headroom = ceiling?.let { it.atoms - amount.atoms }?.takeIf { it > 0 }?.let { mapOf(me to it) } ?: emptyMap()
            content + ("budgets" to (budgets + (id to RuleDocument.budget(kind, scopeId, period, amount.unit, amount.atoms, mapOf(me to amount.atoms),
                rebalance = rebalance, agentsMayChange = agentsMayChange, ceiling = ceiling?.atoms, headroom = headroom))))
        }
        budgetJson(s, id)
    }

    private fun increment(made: Increments.Made, budget: String): JSONObject = when (made) {
        is Increments.Made.Signed -> JSONObject(made.json).let { i ->
            JSONObject().put("id", made.id).put("amount", i.getJSONObject("amount").let { SpendWire.money(Money(it.getString("unit"), it.getLong("atoms"))) })
                .put("lasts", i.getString("lasts")).put("period", i.getString("period")).put("draws_headroom", i.getBoolean("draws_headroom")).put("replayed", made.replayed)
        }
        is Increments.Made.Refused -> throw when (made.why) {
            "gate" -> SpendWire.gate(made.verdict, budget)
            "no_rule_document", "no_such_budget" -> missing(budget)
            "amount" -> ErrorCatalog.error("invalid_parameter", "The amount must be more than zero, in the budget's unit", mapOf("field" to "amount"))
            "request_key_reused" -> ErrorCatalog.error("invalid_parameter", "This Idempotency-Key already made a different raise. Use a new key for each new request.", mapOf("reason" to made.why))
            else -> ErrorCatalog.error("conflict", "The raise can't be made now (${made.why})", mapOf("reason" to made.why, "budget" to budget))
        }
    }

    private fun raise(s: Spending.Space, id: String, amount: Money, lasts: String, caller: Caller, capability: Boolean, requestKey: String?, payload: String): JSONObject = try {
        increment(s.core.increments.raise(id, amount, lasts, caller, capability, requestKey, payload), id)
    } catch (e: JournalRefused) {
        throw ErrorCatalog.error("conflict", "The budget's period can't open on this device now (${e.why})", mapOf("reason" to e.why, "budget" to id))
    }

    fun updateBudget(app: BridgeApp, caller: Caller, capability: Boolean, idIn: String?, body: JSONObject, requestKey: String?): JSONObject = app.spending.guarded {
        ConfigurationAccess.only(body, "namespace", "amount", "rebalance", "scope", "period", "generation")
        val s = app.spending.space(namespaceOf(body))
        val id = budgetId(idIn)
        val amount = if (body.has("amount")) SpendWire.money(body.get("amount"), "amount") else null
        val rebalance = flag(body, "rebalance")
        val scope = if (body.has("scope")) scope(s, body.get("scope")) else null
        val period = if (body.has("period")) period(body.get("period")) else null
        if (amount == null && rebalance == null && scope == null && period == null) bad("Nothing to change: send amount, rebalance, scope or period")
        // An amount here is absolute, so a retry must never be worked out again against a budget that has moved on:
        // what this key asked and did is looked up first, and answered as it was.
        val payload = "patch|$id|${amount?.unit}|${amount?.atoms}|$rebalance|${scope?.first}|${scope?.second}|$period"
        replayed(s, id, requestKey, payload)?.let { return@guarded it }
        val view = s.core.budgetView(id, s.approverEnrolled()) ?: throw missing(id)
        @Suppress("UNCHECKED_CAST") fun atoms(key: String) = (view[key] as Map<String, Any?>)["atoms"] as Long
        val unit = view["unit"] as String
        if (amount != null && amount.unit != unit) bad("amount must be in the budget's unit, $unit", "amount")
        val standing = atoms("amount")
        val base = atoms("base")
        if (amount != null && amount.atoms > standing) {
            // A raise is an increment (G5.1): its own signed thing with an id, never a field of the rule document.
            if (rebalance != null || scope != null || period != null) bad("Send a higher amount alone: a raise is an increment, and the other fields are a rule change", "amount")
            val made = raise(s, id, Money(unit, amount.atoms - standing), "standing", caller, capability, requestKey, payload)
            return@guarded budgetJson(s, id).put("increment", made)
        }
        // What standing raises added stays; a reduction lowers the document's own amount, and this device's share with it.
        val lowerBy = amount?.let { standing - it.atoms } ?: 0
        if (lowerBy > base) bad("The amount can't go below ${Money(unit, standing - base).toDecimal()} $unit: that much was added by standing raises, which a reduction does not undo", "amount")
        val me = s.installId
        // The change and the memory of the key that made it commit together, or neither does.
        s.journal.transact { tx ->
          if (requestKey != null && tx.remembered(requestKey) != null) return@transact
          change(s, caller, capability, generationOf(body), id) { _, content ->
            val budgets = sub(content, "budgets")
            val b = sub(budgets, id).ifEmpty { throw Stop(missing(id)) }
            if ((b["amount"] as Number).toLong() != base) throw Stop(ErrorCatalog.error("conflict", "The budget changed meanwhile; read it again and retry"))
            val next = HashMap(b)
            if (lowerBy > 0) {
                next["amount"] = base - lowerBy
                val devices = sub(b, "devices")
                next["devices"] = devices.mapValues { (install, share) ->
                    @Suppress("UNCHECKED_CAST") val m = share as Map<String, Any?>
                    if (install == me) m + ("atoms" to maxOf(0, (m["atoms"] as Number).toLong() - lowerBy)) else m
                }
            }
            rebalance?.let { next["rebalance"] = it }
            scope?.let { next["scope"] = mapOf("kind" to it.first, "id" to it.second) }
            period?.let { next["period"] = it }
            content + ("budgets" to (budgets + (id to next)))
          }
          requestKey?.let { tx.remember(it, RULE_CHANGE, payload) }
        }
        budgetJson(s, id)
    }

    /**
     * What [requestKey] did before, answered again without doing anything: the budget as it stands now, marked
     * `replayed`, with the increment when the request was a raise. Null when the key is new. The same key for
     * another request is refused.
     */
    private fun replayed(s: Spending.Space, id: String, requestKey: String?, payload: String): JSONObject? {
        val key = requestKey ?: return null
        val (made, asked) = s.journal.transact { tx -> tx.remembered(key) to tx.rememberedPayload(key) }
        if (made == null) return null
        if (asked != payload) throw ErrorCatalog.error("invalid_parameter", "This Idempotency-Key already made a different change. Use a new key for each new request.", mapOf("reason" to "request_key_reused"))
        val out = budgetJson(s, id).put("replayed", true)
        if (made != RULE_CHANGE) (s.core.increments.reissue(made) as? Increments.Made.Signed)?.let { out.put("increment", increment(it.copy(replayed = true), id)) }
        return out
    }

    fun deleteBudget(app: BridgeApp, caller: Caller, capability: Boolean, idIn: String?, namespace: String?, generation: String?): JSONObject = app.spending.guarded {
        val s = app.spending.space(namespace)
        val id = budgetId(idIn)
        val against = generation?.let { it.toLongOrNull() ?: bad("generation must be a whole number", "generation") }
        change(s, caller, capability, against, id) { cur, content ->
            val budgets = sub(content, "budgets")
            if (cur == null || id !in budgets) throw Stop(missing(id))
            content + ("budgets" to (budgets - id))
        }
        JSONObject().put("removed", true).put("id", id).put("namespace", s.namespace.id).put("generation", generation(s))
    }

    fun topUp(app: BridgeApp, caller: Caller, capability: Boolean, idIn: String?, body: JSONObject, requestKey: String?): JSONObject = app.spending.guarded {
        ConfigurationAccess.only(body, "namespace", "amount")
        val s = app.spending.space(namespaceOf(body))
        val id = budgetId(idIn)
        val amount = SpendWire.money(body.opt("amount"), "amount")
        val made = raise(s, id, amount, "period", caller, capability, requestKey, "topup|$id|${amount.unit}|${amount.atoms}")
        budgetJson(s, id).put("increment", made)
    }

    /**
     * Sets a budget's gate as [caller]. The API's caller is always an agent; the owner as a caller exists for
     * the app, which has no screen for this yet, and for a debug build's test hook.
     */
    fun setGate(app: BridgeApp, caller: Caller, capability: Boolean, idIn: String?, body: JSONObject): JSONObject = app.spending.guarded {
        ConfigurationAccess.only(body, "namespace", "agents_may_change", "ceiling", "generation")
        val s = app.spending.space(namespaceOf(body))
        val id = budgetId(idIn)
        val agentsMayChange = flag(body, "agents_may_change")
        val setCeiling = body.has("ceiling")
        val ceiling = if (!setCeiling || body.isNull("ceiling")) null else SpendWire.money(body.get("ceiling"), "ceiling")
        if (agentsMayChange == null && !setCeiling) bad("Nothing to change: send agents_may_change or ceiling")
        change(s, caller, capability, generationOf(body), id) { _, content ->
            val budgets = sub(content, "budgets")
            val b = sub(budgets, id).ifEmpty { throw Stop(missing(id)) }
            val amount = (b["amount"] as Number).toLong()
            if (ceiling != null && (ceiling.unit != b["unit"] || ceiling.atoms < amount)) throw Stop(ErrorCatalog.error("invalid_parameter", "ceiling must be in the budget's unit and at least its amount", mapOf("field" to "ceiling")))
            val gate = HashMap(sub(b, "gate"))
            agentsMayChange?.let { gate["agents_may_change"] = it }
            val next = HashMap(b)
            if (setCeiling) {
                gate["ceiling"] = ceiling?.atoms
                // Headroom never rises in a document: a lower ceiling trims the balances to the room that is left, and no ceiling leaves none.
                var room = ceiling?.let { it.atoms - amount } ?: 0
                val h = sub(b, "headroom")
                val cloud = minOf((h["cloud"] as Number).toLong(), room).also { room -= it }
                next["headroom"] = mapOf("cloud" to cloud, "devices" to sub(h, "devices").mapValues { (_, v) -> minOf((v as Number).toLong(), room).also { room -= it } }.filterValues { it > 0 })
            }
            next["gate"] = gate
            content + ("budgets" to (budgets + (id to next)))
        }
        budgetJson(s, id)
    }

    fun acknowledge(app: BridgeApp, caller: Caller, body: JSONObject): JSONObject = app.spending.guarded {
        ConfigurationAccess.only(body, "namespace", "acknowledge_history")
        val s = app.spending.space(namespaceOf(body))
        if (flag(body, "acknowledge_history") != true) bad("acknowledge_history must be true", "acknowledge_history")
        val entry = try { s.journal.acknowledgeHistory(caller.kind, caller.id) } catch (e: JournalRefused) {
            throw ErrorCatalog.error("conflict", "No unreadable history is blocking spends", mapOf("reason" to e.why))
        }
        JSONObject().put("acknowledged", entry != null).put("namespace", s.namespace.id).put("journal", SpendWire.obj(s.core.journalView()))
    }

    // ---- routes ----

    private class Call(val access: ConfigurationAccess, val caller: Caller, val capability: Boolean, val key: String?)

    /** The caller as the rule document will record it: the agent, the door it came through, and whether it holds Budgets. */
    private fun Req.call(app: BridgeApp): Call {
        val access = ConfigurationAccess(app, agent!!)
        val live: Agent = access.require()
        val door = if (invocation.isNotEmpty() && invocation.length <= 40 && invocation.all { it in '!'..'~' }) invocation else "http"
        return Call(access, Caller("agent", live.id, door), live.allows("budgets"), header("Idempotency-Key")?.trim()?.takeIf { it.isNotEmpty() }?.let { "${live.id}:$it" })
    }

    fun install(route: Route, api: ApiServer, app: BridgeApp) = with(api) {
        with(route) {
            suspend fun <T> io(f: () -> T): T = withContext(Dispatchers.IO) { f() }
            endpoint(GET) { ConfigurationAccess(app, agent!!).require(); val ns = query("namespace"); Reply.Json(io { get(app, ns) }) }
            endpoint(SOURCES) { ConfigurationAccess(app, agent!!).require(); val ns = query("namespace"); Reply.Json(io { sources(app, ns) }) }
            endpoint(RULES) { ConfigurationAccess(app, agent!!).require(); val ns = query("namespace"); Reply.Json(io { rules(app, ns) }) }
            endpoint(BUDGETS) { ConfigurationAccess(app, agent!!).require(); val ns = query("namespace"); Reply.Json(io { budgets(app, ns) }) }
            endpoint(ALLOWANCES) { ConfigurationAccess(app, agent!!).require(); val ns = query("namespace"); Reply.Json(io { allowances(app, ns) }) }
            endpoint(JOURNAL) {
                ConfigurationAccess(app, agent!!).require()
                val (ns, after, limit) = Triple(query("namespace"), query("after"), query("limit")); val epoch = query("epoch")
                Reply.Json(io { journal(app, ns, after, limit, epoch) })
            }
            endpoint(SUMMARY) { ConfigurationAccess(app, agent!!).require(); val ns = query("namespace"); Reply.Json(io { summary(app, ns) }) }
            endpoint(LIMITS) { ConfigurationAccess(app, agent!!).require(); val ns = query("namespace"); Reply.Json(io { limits(app, ns) }) }
            endpoint(SET) { val c = call(app); Reply.Json(io { set(app, c.caller, c.capability, body) }.also { c.access.audit("spending switches (${it.optString("namespace")})") }) }
            endpoint(SOURCE_LINK) { val c = call(app); Reply.Json(io { link(app, body) }.also { c.access.audit("spending source ${it.optString("id")} (linked)") }) }
            endpoint(SOURCE_UNLINK) { val c = call(app); val id = path("id").orEmpty(); Reply.Json(io { unlink(app, id) }.also { c.access.audit("spending source $id (unlinked)") }) }
            endpoint(RULES_SET) { val c = call(app); Reply.Json(io { setRules(app, c.caller, c.capability, body) }.also { c.access.audit("spending rules (generation ${it.optLong("generation")})") }) }
            endpoint(BUDGET_CREATE) { val c = call(app); Reply.Json(io { createBudget(app, c.caller, c.capability, body) }.also { c.access.audit("spending budget ${body.optString("id")} (created)") }) }
            endpoint(BUDGET_UPDATE) { val c = call(app); val id = path("id"); Reply.Json(io { updateBudget(app, c.caller, c.capability, id, body, c.key) }.also { c.access.audit("spending budget $id (changed)") }) }
            endpoint(BUDGET_DELETE) {
                val c = call(app); val id = path("id"); val ns = query("namespace"); val generation = query("generation")
                Reply.Json(io { deleteBudget(app, c.caller, c.capability, id, ns, generation) }.also { c.access.audit("spending budget $id (removed)") })
            }
            endpoint(BUDGET_TOPUP) { val c = call(app); val id = path("id"); Reply.Json(io { topUp(app, c.caller, c.capability, id, body, c.key) }.also { c.access.audit("spending budget $id (topped up)") }) }
            endpoint(BUDGET_GATE) { val c = call(app); val id = path("id"); Reply.Json(io { setGate(app, c.caller, c.capability, id, body) }.also { c.access.audit("spending budget $id (gate)") }) }
            endpoint(JOURNAL_ACK) { val c = call(app); Reply.Json(io { acknowledge(app, c.caller, body) }.also { c.access.audit("spending journal (history acknowledged)") }) }
        }
    }
}
