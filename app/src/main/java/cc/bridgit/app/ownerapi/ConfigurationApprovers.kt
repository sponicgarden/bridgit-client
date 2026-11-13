package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.authority.ApproverReadiness
import cc.bridgit.app.core.authority.Authority
import cc.bridgit.app.core.authority.AuthorityLimits
import cc.bridgit.app.core.authority.AuthorityStore
import cc.bridgit.app.core.authority.AuthorityStore.Outcome
import cc.bridgit.app.core.authority.AuthorityStore.Refusal
import cc.bridgit.app.core.authority.RecoveryMachine
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Approver devices, owner configuration (architecture §3.5, §4.7; spending authority G10.2 SA1.2, SA1.4b,
 * SA1.6). Part of ConfigurationEndpoints.ALL, so both MCP servers' configuration.get/set list these.
 *
 * Everything is behind the owner setting `security.approvers_enabled` (POST /v1/owner/configuration/settings),
 * off by default: reads then answer `enabled: false` without opening the authority store, writes are refused.
 *
 * An agent with owner.configuration may start an enrolment or a recovery (principle 9). What decides an
 * enrolment is the owner's check on the approver, never the caller. Keys are never returned.
 */
object ConfigurationApprovers {
    private const val P = "/owner/configuration/approvers"
    private val ERRORS = listOf("invalid_parameter", "forbidden", "agent_not_allowed", "capability_disabled", "conflict", "rate_limited", "owner_email_required",
        "no_screen_lock", "platform_unsupported", "no_approver", "recovery_complete", "upstream_failed")
    private const val RECOVERY_FIELDS = "state (none|pending|cancelled|cleared), started_by, started_at, clear_at, time_left_ms, ended_by, ended_at, can_start, blocked_reason (not_available|owner_email_required|no_approver)"

    private fun read(path: String, what: String, returns: String) = EndpointSpec("GET", "$P$path", "owner_features", what,
        example = "GET /v1$P$path", returns = returns, controlScope = "owner.configuration")
    private fun write(method: String, path: String, what: String, body: List<Param>, example: String, returns: String) = EndpointSpec(method, "$P$path", "owner_features",
        "$what; explicit owner.configuration grant required", body = body, example = "$method /v1$P$path $example".trim(), returns = returns, errors = ERRORS,
        idempotent = true, idempotencyRequired = true, controlScope = "owner.configuration", privateFields = setOf("*"), maxBody = 4096)

    val LIST = read("", "Read this device's approver devices (never keys), whether it can enrol itself as its own approver and why not, what its key store offers, and the recovery state. Off unless security.approvers_enabled is on; can_enable says whether this build can turn it on",
        "enabled, setting, can_enable, enable_blocked_reason (not_available_in_this_build: the published build can't turn approver devices on yet) or null, approvers[{id, name, self, added_at, last_used_at, can_approve, wait_ends_at}], can_enrol, blocked_reason (approvers_disabled|owner_email_required|no_screen_lock|platform_unsupported|recovery_pending|approver_listed), email_verified, " +
            "capability{api_level, secure_lock_screen, strongbox, strong_biometric, per_use_credential_keys, readiness}, key_state (absent|ready|needs_setup), generation, enrolment{id, status, detail} or null, " +
            "requests[{id, kind, purpose, requester, expires_at, check}], recovery{$RECOVERY_FIELDS}, remaining_human_step{step, where, status} or null, waits{recovery_ms, approver_ms, request_ms}")
    val ENROL = write("POST", "", "Start enrolling this device as its own approver while the list is empty: makes the approver key and asks for the owner's screen-lock check on this device, which is the one human step. Needs a verified alert email and a screen lock",
        listOf(Param("self", "boolean", "true: this device becomes its own approver. Another device is enrolled over a link (not built yet)", true)),
        "{\"self\":true}", "enrolment{id, status waiting_owner|done|cancelled|failed, detail}, remaining_human_step{step owner_check, where, status}; read GET /v1$P until status is done")
    val RECOVERY = read("/recovery", "Read the approver recovery: its state, who started it, when the approver list is cleared and the time left", RECOVERY_FIELDS)
    val RECOVERY_START = write("POST", "/recovery", "Start an approver recovery when the last approver is lost: the approver list is cleared after 72 hours of running time unless a listed approver cancels. Not available yet: refused with platform_unsupported and remaining_steps until alert email delivery and a cancel action on the approver device exist",
        emptyList(), "", RECOVERY_FIELDS)
    val RECOVERY_CANCEL = write("DELETE", "/recovery", "Cancel a pending approver recovery: the agent that started it withdraws it at once; anyone else's cancel asks a listed approver for the owner's check",
        emptyList(), "", "$RECOVERY_FIELDS, remaining_human_step when a listed approver has to confirm")

    val ALL = listOf(LIST, ENROL, RECOVERY, RECOVERY_START, RECOVERY_CANCEL)

    private fun step(text: String): Any = if (text.isEmpty()) JSONObject.NULL else JSONObject(text)

    private fun refuse(r: Outcome.Refused, why: String): Nothing = throw ErrorCatalog.error(r.reason.code, why)

    private fun recoveryJson(app: BridgeApp, tx: AuthorityStore.Tx): JSONObject {
        val r = tx.recovery()
        val pending = r.state == RecoveryMachine.PENDING
        val blocked = when {
            pending -> null
            !app.authority.recoveryStartBuilt -> "not_available"
            !app.authority.emailVerified() -> "owner_email_required"
            tx.approvers().isEmpty() -> "no_approver"
            else -> null
        }
        return JSONObject().put("state", r.state).put("started_by", r.startedBy.ifEmpty { null } ?: JSONObject.NULL)
            .put("started_at", if (r.startedMs > 0) r.startedMs else JSONObject.NULL)
            .put("clear_at", if (pending) r.wait.wallEndMs else JSONObject.NULL)
            // Both clocks must pass: the wall clock and counted running time (architecture §4.7 "Clocks").
            .put("time_left_ms", if (pending) maxOf(r.wait.wallEndMs - tx.nowMs, r.wait.countedEndMs - tx.countedMs, 0) else JSONObject.NULL)
            .put("ended_by", r.endedBy.ifEmpty { null } ?: JSONObject.NULL).put("ended_at", if (r.endedMs > 0) r.endedMs else JSONObject.NULL)
            .put("can_start", !pending && blocked == null).put("blocked_reason", blocked ?: JSONObject.NULL)
    }

    private fun off() = JSONObject().put("enabled", false).put("setting", Authority.SETTING)

    private fun JSONObject.canEnable(a: Authority) = a.enableBlocker().let { put("can_enable", it == null).put("enable_blocked_reason", it ?: JSONObject.NULL) }

    private fun enrolment(p: AuthorityStore.Proposal?): Any = if (p == null) JSONObject.NULL else JSONObject().put("id", p.id)
        .put("status", p.state).put("detail", JSONObject(p.body).optString("detail").ifEmpty { null } ?: JSONObject.NULL)

    fun list(app: BridgeApp): JSONObject {
        val a = app.authority
        val c = a.capabilities()
        val capability = JSONObject().put("api_level", c.apiLevel).put("secure_lock_screen", c.secureLockScreen).put("strongbox", c.strongBox)
            .put("strong_biometric", c.strongBiometric).put("per_use_credential_keys", c.perUseCredentialKeys).put("readiness", a.readiness().id)
        val waits = JSONObject().put("recovery_ms", AuthorityLimits.RECOVERY_WAIT_MS).put("approver_ms", AuthorityLimits.APPROVER_WAIT_MS).put("request_ms", AuthorityLimits.REQUEST_LIFETIME_MS)
        if (!a.enabled) return off().canEnable(a).put("approvers", JSONArray()).put("can_enrol", false).put("blocked_reason", "approvers_disabled")
            .put("email_verified", a.emailVerified()).put("capability", capability).put("waits", waits)
        val self = a.selfId()
        val blocker = a.enrolBlocker()
        val keyState = runCatching { a.keys.state().id }.getOrDefault("absent")
        return a.store.tx { tx ->
            val proposals = tx.proposals()
            val waiting = proposals.firstOrNull { a.waits(tx, it) }
            JSONObject().put("enabled", true).put("setting", Authority.SETTING).canEnable(a)
                .put("approvers", JSONArray(tx.approvers().map { p ->
                    JSONObject().put("id", p.id).put("name", p.name).put("self", p.id == self).put("added_at", p.addedMs)
                        .put("last_used_at", if (p.lastUsedMs > 0) p.lastUsedMs else JSONObject.NULL).put("can_approve", tx.pastWait(p))
                        .put("wait_ends_at", if (tx.pastWait(p)) JSONObject.NULL else p.wait.wallEndMs)
                }))
                .put("can_enrol", blocker == null).put("blocked_reason", blocker ?: JSONObject.NULL).put("email_verified", a.emailVerified())
                .put("capability", capability).put("key_state", keyState).put("generation", tx.generation())
                .put("enrolment", enrolment(tx.latestProposal(Authority.KIND_SELF_ENROL)))
                .put("requests", JSONArray(tx.openRequests().filter { tx.nowMs < it.expiresMs }.map { r ->
                    val check = proposals.lastOrNull { it.request == r.id }
                    JSONObject().put("id", r.id).put("kind", r.kind).put("purpose", r.purpose).put("requester", r.requester).put("expires_at", r.expiresMs)
                        .put("check", check?.state ?: JSONObject.NULL)
                }))
                .put("recovery", recoveryJson(app, tx)).put("remaining_human_step", step(waiting?.remainingHumanStep.orEmpty())).put("waits", waits)
        }
    }

    suspend fun enrol(app: BridgeApp, caller: Agent, access: ConfigurationAccess, body: JSONObject): JSONObject {
        ConfigurationAccess.only(body, "self")
        if (!ConfigurationAccess.bool(body, "self")) throw ErrorCatalog.error("invalid_parameter", "self must be true: this device enrols itself; another device is enrolled over a link")
        app.authority.requireEnabled()
        val p = withContext(Dispatchers.IO) {
            access.require()
            app.authority.startSelfEnrol(caller.name, caller.id)
        }
        access.audit("approver enrolment (waiting for the owner's check)")
        return JSONObject().put("enrolment", enrolment(p)).put("remaining_human_step", step(p.remainingHumanStep))
    }

    fun recovery(app: BridgeApp): JSONObject =
        if (!app.authority.enabled) off().put("state", RecoveryMachine.NONE).put("can_start", false).put("blocked_reason", "approvers_disabled")
        else app.authority.store.tx { recoveryJson(app, it) }

    fun startRecovery(app: BridgeApp, caller: Agent, access: ConfigurationAccess, body: JSONObject): JSONObject {
        ConfigurationAccess.only(body)
        val a = app.authority
        a.requireEnabled()
        // Held back until its two backstops exist (A0300 review): the alert has to reach the owner by email, and the owner needs a cancel action on the approver device.
        if (!a.recoveryStartBuilt) throw ErrorCatalog.error("platform_unsupported", "Approver recovery can't be started on this build yet: it waits for email delivery of its alert and a cancel action on the approver device",
            mapOf("remaining_steps" to JSONArray(listOf("email delivery of approver alerts", "a cancel action on the approver device"))))
        // The refusal is returned from the transaction, not thrown in it, so nothing the store did is undone.
        val (out, json) = a.store.tx { tx -> RecoveryMachine.start(tx, caller.id, a.emailVerified()).let { it to recoveryJson(app, tx) } }
        when (out) {
            is Outcome.Refused -> refuse(out, when (out.reason) {
                Refusal.OWNER_EMAIL_REQUIRED -> "Set and verify the owner alert email first: POST /v1/owner/configuration/email"
                Refusal.EMPTY_LIST -> "No approver is enrolled, so there is nothing to recover"
                else -> "The recovery can't start now"
            })
            is Outcome.Ok -> Unit
        }
        access.audit("approver recovery (started)")
        return json
    }

    fun cancelRecovery(app: BridgeApp, caller: Agent, access: ConfigurationAccess): JSONObject {
        val a = app.authority
        a.requireEnabled()
        val (out, json) = a.store.tx { tx -> RecoveryMachine.withdraw(tx, caller.id).let { it to recoveryJson(app, tx) } }
        when (out) {
            is Outcome.Ok -> { access.audit("approver recovery (withdrawn)"); return json }
            is Outcome.Refused -> when (out.reason) {
                Refusal.RECOVERY_COMPLETE -> refuse(out, "The approver list was already cleared; enrol a first approver again")
                Refusal.NOT_STARTER -> {
                    // Not the starter: only a listed approver can stop it (purpose recovery_cancel), after the owner's check.
                    val started = json.optLong("started_at")
                    val p = try {
                        a.requestOwner("recovery_cancel", "recovery_cancel", caller.id, JSONObject().put("started_at", started).put("started_by", json.optString("started_by")),
                            JSONObject().put("started_at", started), "Cancel approver recovery", caller.id)
                    } catch (e: cc.bridgit.app.api.ApiError) {
                        throw if (e.code == "no_approver") ErrorCatalog.error("conflict", "Only the agent that started the recovery, or a listed approver, can cancel it") else e
                    }
                    access.audit("approver recovery (cancel waiting for the owner's check)")
                    return json.put("remaining_human_step", step(p.remainingHumanStep))
                }
                else -> refuse(out, "No approver recovery is pending")
            }
        }
    }

    fun install(route: Route, api: ApiServer, app: BridgeApp) = with(api) {
        with(route) {
            endpoint(LIST) { ConfigurationAccess(app, agent!!).require(); Reply.Json(list(app)) }
            endpoint(ENROL) { val access = ConfigurationAccess(app, agent!!); access.require(); Reply.Json(enrol(app, agent!!, access, body)) }
            endpoint(RECOVERY) { ConfigurationAccess(app, agent!!).require(); Reply.Json(recovery(app)) }
            endpoint(RECOVERY_START) { val access = ConfigurationAccess(app, agent!!); access.require(); Reply.Json(startRecovery(app, agent!!, access, body)) }
            endpoint(RECOVERY_CANCEL) { val access = ConfigurationAccess(app, agent!!); access.require(); Reply.Json(cancelRecovery(app, agent!!, access)) }
        }
    }
}
