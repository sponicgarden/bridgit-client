package cc.bridgit.app.ownerapi

import android.app.NotificationManager
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.applets.AppletPackage
import cc.bridgit.app.core.Agent
import cc.bridgit.app.platform.Commands
import cc.bridgit.app.platform.DeviceTools
import cc.bridgit.app.platform.PlatformStore
import org.json.JSONArray
import org.json.JSONObject

/** Delegated decisions use the native activation paths and never create control-agent authority. */
class ConfigurationApprovals(private val app: BridgeApp, private val caller: Agent) {
    private val access = ConfigurationAccess(app, caller)
    companion object {
        val KINDS = listOf("applet", "schedule", "workflow", "operation", "session")
        fun digest(kind: String, id: String, record: JSONObject): String = PlatformStore.digest(
            JSONObject().put("kind", kind).put("id", id).put("request", record))
        fun matches(kind: String, id: String, record: JSONObject, expected: String) {
            if (!Regex("[a-f0-9]{64}").matches(expected)) throw ErrorCatalog.error("invalid_parameter", "expected_digest must be a SHA-256 digest from approval discovery")
            if (digest(kind, id, record) != expected) throw ErrorCatalog.error("conflict", "Approval changed; inspect it again before deciding")
        }

        fun pendingOrNull(app: BridgeApp, kind: String, id: String): JSONObject? = when (kind) {
            "applet" -> app.applets.pendingGet(id)
            "schedule" -> app.schedules.record(id)?.optJSONObject("pending")
            else -> app.platform.store.find(id, kind = kind)?.takeIf { it.optString("status") == "awaiting_owner" }
        }

        /** The agent or applet a pending request came from, or "" when the owner proposed it. */
        fun requester(kind: String, id: String, record: JSONObject): String = when (kind) {
            "applet" -> record.optString("requested_by").takeIf { !record.isNull("requested_by") }.orEmpty()
            "schedule" -> id
            else -> record.optString("agent_id")
        }

        /** The native activation or decline of one pending request. [check] sees the record right before the effect. */
        suspend fun apply(app: BridgeApp, kind: String, id: String, approve: Boolean, check: (JSONObject) -> Unit): Any? = when (kind) {
            "applet" -> try { app.applets.decideByControl(id, approve, check) }
                catch (e: cc.bridgit.app.applets.AppletInstances.Refused) { throw ErrorCatalog.error(if (runCatching { ErrorCatalog.http(e.code) }.isSuccess) e.code else "conflict", e.message ?: "Applet approval refused") }
                catch (e: AppletPackage.Bad) { throw ErrorCatalog.error("invalid_parameter", e.message ?: "Invalid applet") }
            "schedule" -> {
                app.schedules.decideByControl(id, approve, check)
                JSONObject().put("status", if (approve) "active" else "declined")
            }
            else -> synchronized(app.platform.store) {
                val record = pendingOrNull(app, kind, id) ?: throw ErrorCatalog.error("not_found", "No pending $kind approval $id")
                check(record)
                val target = record.getString("agent_id")
                if (approve) when (kind) {
                    "operation" -> app.platform.approveOperation(id)
                    "workflow" -> app.platform.workflows.activate(id)
                    "session" -> {
                        if (record.optString("credential_hash") != app.platform.getAgent(target).hash)
                            throw ErrorCatalog.error("conflict", "Control session credentials changed; request a new session")
                        app.platform.approveSession(id)
                    }
                } else when (kind) {
                    "operation" -> app.platform.cancelOperation(target, id)
                    else -> {
                        app.platform.store.save(target, kind, record.put("status", "declined"))
                        app.platform.store.emit(target, "$kind.declined", id)
                        app.platform.changed()
                    }
                }
                app.getSystemService(NotificationManager::class.java)?.cancel(id.hashCode())
                JSONObject().put("status", app.platform.store.find(id, kind = kind)?.optString("status"))
            }
        }

        /** Registers what an approver's answer does for each approval kind (purpose `approval.<kind>`). Called once from BridgeApp. */
        fun register(app: BridgeApp) = KINDS.forEach { app.authority.register("approval.$it", OwnerDecision(app)) }
    }

    /**
     * A pending approval decided by an approver's answer (architecture §4.7 flow step 5; SA1.5). The owner
     * decides here, so the check is not the asking agent's scopes: it is the request itself, run again
     * against today's state. The pending record must still wait and be the one that was signed for, and its
     * requester must still be an active agent. The native activation then runs its own checks as it does for
     * any decision.
     */
    class OwnerDecision(private val app: BridgeApp) : cc.bridgit.app.core.authority.AuthorityEffect {
        private class Parts(val kind: String, val id: String, val expected: String)
        private fun parts(r: cc.bridgit.app.core.authority.AuthorityStore.Request) = JSONObject(r.body).let { Parts(it.getString("kind"), it.getString("id"), it.getString("expected_digest")) }

        override fun check(tx: cc.bridgit.app.core.authority.AuthorityStore.Tx, r: cc.bridgit.app.core.authority.AuthorityStore.Request): String? = try {
            val p = parts(r)
            val record = pendingOrNull(app, p.kind, p.id)
            val from = record?.let { requester(p.kind, p.id, it) }
            when {
                record == null -> "no longer pending"
                digest(p.kind, p.id, record) != p.expected -> "the request changed"
                from != r.requester -> "the requester changed"
                from.isNotEmpty() && app.agents.get(from)?.takeUnless { it.suspended } == null -> "the requester is no longer active"
                else -> null
            }
        } catch (e: Exception) { "unreadable request" }

        /**
         * The native activation may prepare for a while first (an applet downloads its media). The decision
         * boundary is the moment right before it activates: there the request's authority is read once more
         * from today's state, with its expiry and the switch, so a requester paused or revoked meanwhile, or
         * approver devices turned off, stops it (§4.7, C4). The store's own changes cannot race: this runs
         * inside its transaction.
         */
        private fun stillAllowed(tx: cc.bridgit.app.core.authority.AuthorityStore.Tx, r: cc.bridgit.app.core.authority.AuthorityStore.Request) {
            val why = when {
                !app.authority.enabled -> "approver devices were turned off"
                app.authority.time.wallMs() >= r.expiresMs -> "the request ran out of time"
                tx.request(r.id)?.approver.orEmpty().let { it.isEmpty() || tx.approver(it) == null } -> "the approver is no longer enrolled"
                else -> check(tx, r)
            } ?: return
            throw ErrorCatalog.error("conflict", "The approval can no longer be applied: $why")
        }

        override fun apply(tx: cc.bridgit.app.core.authority.AuthorityStore.Tx, r: cc.bridgit.app.core.authority.AuthorityStore.Request): JSONObject {
            val p = parts(r)
            val result = kotlinx.coroutines.runBlocking { apply(app, p.kind, p.id, true) { record -> matches(p.kind, p.id, record, p.expected); stillAllowed(tx, r) } }
            app.log.system("audit", "configuration", "An approver approved the ${p.kind} approval ${p.id}")
            return JSONObject().put("kind", p.kind).put("id", p.id).put("approved", true).put("result", result)
        }

        override fun declined(r: cc.bridgit.app.core.authority.AuthorityStore.Request) {
            val p = parts(r)
            kotlinx.coroutines.runBlocking { apply(app, p.kind, p.id, false) { record -> matches(p.kind, p.id, record, p.expected) } }
            app.log.system("audit", "configuration", "An approver declined the ${p.kind} approval ${p.id}")
        }
    }
    private fun kind(value: String): String = value.takeIf { it in KINDS }
        ?: throw ErrorCatalog.error("invalid_parameter", "Choose an approval kind from discovery")
    private fun pending(kind: String, id: String): JSONObject {
        return pendingOrNull(app, kind, id) ?: throw ErrorCatalog.error("not_found", "No pending $kind approval $id")
    }
    private fun cap(live: Agent, id: String) {
        if (!live.allows(id)) throw ErrorCatalog.error("agent_not_allowed", "Approval requires your existing $id authority")
    }
    private fun scope(live: Agent, id: String) {
        if (!app.platform.hasGrant(live.id, id)) throw ErrorCatalog.error("agent_not_allowed", "Approval requires your existing $id authority")
    }
    private fun authorize(kind: String, id: String, record: JSONObject, approving: Boolean = true) {
        val live = access.require()
        if (!approving) return // Declining a pending request creates no action authority.
        when (kind) {
            "applet" -> {
                cap(live, "applet_install")
                // Installing an applet does not widen the controlling agent's own grants.
                val definition = AppletPackage.check(record.getJSONObject("definition"))
                val review = app.applets.review(definition, record.getJSONObject("config"), record.optString("replaces").takeIf { it.isNotEmpty() && !record.isNull("replaces") })
                val permissions = review.getJSONArray("permissions")
                for (i in 0 until permissions.length()) {
                    val id = permissions.getJSONObject(i).getString("id")
                    if (app.registry.byId(id) != null) cap(live, id)
                    else scope(live, id)
                }
            }
            "schedule" -> {
                cap(live, "schedule")
                val manifest = try { app.schedules.parse(live, record.getJSONObject("manifest")) }
                    catch (e: cc.bridgit.app.schedule.ScheduleRules.Invalid) { throw ErrorCatalog.error("agent_not_allowed", e.message ?: "Scheduled action authority unavailable") }
                manifest.actions.forEach { action ->
                    when (action.action) {
                        is cc.bridgit.app.schedule.ScheduleRules.Do.Play -> cap(live, "speaker")
                        is cc.bridgit.app.schedule.ScheduleRules.Do.Show -> cap(live, "interactions")
                        else -> Unit
                    }
                    (action.action as? cc.bridgit.app.schedule.ScheduleRules.Do.Flow)?.let { flow ->
                    val definition = app.platform.workflows.resolve(id, flow.ref) ?: throw ErrorCatalog.error("not_found", "Scheduled workflow was removed")
                    val scopes = definition.getJSONArray("required_scopes")
                    for (i in 0 until scopes.length()) scope(live, scopes.getString(i))
                } }
            }
            "workflow" -> record.getJSONArray("required_scopes").let { scopes ->
                for (i in 0 until scopes.length()) scope(live, scopes.getString(i))
            }
            "session" -> scope(live, "screen.control")
            "operation" -> {
                val command = record.getString("command")
                val input = record.getJSONObject("input")
                val scope = if (command == "device.action") DeviceTools.scopeFor(input.optString("type")) else Commands.scope(command)
                scope(live, scope)
                if (command == "owner.configure") app.ownerFeatures.review(live.id, input)
            }
        }
    }
    private fun redact(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { out -> value.keys().forEach { key ->
            out.put(key, if (Regex("(?i).*(password|secret|token|credential|authorization|cookie|api.?key).*", RegexOption.IGNORE_CASE).matches(key) || key.equals("headers", true)) "[redacted]" else redact(value.opt(key)))
        } }
        is JSONArray -> JSONArray((0 until value.length()).map { redact(value.opt(it)) })
        else -> value
    }
    private fun descriptor(kind: String, id: String, record: JSONObject, detail: Boolean): JSONObject {
        val out = JSONObject().put("kind", kind).put("id", id).put("expected_digest", digest(kind, id, record))
            .put("review_url", "/v1/owner/configuration/approvals/$kind/$id")
        val problem = runCatching { authorize(kind, id, record) }.exceptionOrNull()
        out.put("can_decide", problem == null).put("can_decline", true)
        if (problem != null) out.put("blocked_reason", problem.message ?: "Authority unavailable")
        // Once the owner can be asked on an approver (not offered yet): an agent that can't decide can still ask.
        if (app.authority.enabled && app.authority.ownerReviewBuilt) out.put("can_ask_owner", true)
        if (detail) out.put("review", when (kind) {
            "applet" -> app.applets.review(AppletPackage.check(record.getJSONObject("definition")), record.getJSONObject("config"), record.optString("replaces").takeIf { it.isNotEmpty() && !record.isNull("replaces") })
            "schedule" -> JSONObject().put("manifest", record.getJSONObject("manifest"))
            "workflow" -> JSONObject().put("definition", record.getJSONObject("definition")).put("required_scopes", record.getJSONArray("required_scopes"))
            "session" -> JSONObject().put("agent_id", record.getString("agent_id")).put("apps", record.getJSONArray("apps")).put("purpose", record.optString("purpose")).put("duration_seconds", record.optInt("duration_seconds"))
            else -> app.platform.publicOperation(record).apply { remove("result"); put("input", record.getJSONObject("input")) }
        })
        return redact(out) as JSONObject
    }
    fun list(): JSONObject {
        access.require()
        val records = mutableListOf<JSONObject>()
        app.applets.pending().forEach { records += descriptor("applet", it.getString("id"), it, false) }
        app.schedules.all().forEach { (id, value) -> value.optJSONObject("pending")?.let { records += descriptor("schedule", id, it, false) } }
        for (kind in listOf("workflow", "operation", "session")) app.platform.store.list(kind = kind, limit = 1000)
            .filter { it.optString("status") == "awaiting_owner" }.forEach { records += descriptor(kind, it.getString("id"), it, false) }
        return JSONObject().put("approvals", JSONArray(records)).put("kinds", JSONArray(KINDS))
            .put("human_authority", JSONArray(listOf("first Super Powers authority and initial key pairing", "Android OS consent", "account sign-in/verification", "biometric/security credentials")))
    }
    fun get(kind: String, id: String): JSONObject {
        access.require(); kind(kind)
        return descriptor(kind, id, pending(kind, id), true)
    }
    /**
     * Asks the owner to decide on an approver device (only with approver devices on). The asking agent needs
     * no authority over what is approved: the approver's signed answer is the decision, and it is applied by
     * [OwnerDecision] after the execution-time check.
     */
    private suspend fun askOwner(kind: String, id: String, body: JSONObject): JSONObject {
        ConfigurationAccess.only(body, "approve", "expected_digest", "owner_authorization")
        if (body.opt("owner_authorization") != true) throw ErrorCatalog.error("invalid_parameter", "owner_authorization must be true")
        if (!ConfigurationAccess.bool(body, "approve")) throw ErrorCatalog.error("invalid_parameter", "Decline directly with approve=false; owner_authorization asks the owner to approve")
        val expected = ConfigurationAccess.text(body, "expected_digest", 64)
        val record = pending(kind, id)
        matches(kind, id, record, expected)
        val from = requester(kind, id, record)
        val what = listOf(kind, app.agents.get(from)?.name.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
        val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            access.require()
            app.authority.requestOwner("approval.$kind", "approval.$kind", from, JSONObject().put("kind", kind).put("id", id).put("expected_digest", expected),
                JSONObject().put("kind", kind).put("id", id).put("expected_digest", expected).put("asked_by", caller.id), what, caller.id)
        }
        access.audit("$kind approval $id (asked the owner on an approver device)")
        return JSONObject().put("kind", kind).put("id", id).put("approved", false).put("status", "awaiting_owner_authorization").put("request_id", p.request)
            .put("remaining_human_step", JSONObject(p.remainingHumanStep))
    }

    suspend fun decide(kind: String, id: String, body: JSONObject): JSONObject {
        access.require(); kind(kind)
        // Not offered yet: it waits for a native review screen that shows what the owner signs. Until then, and
        // with approver devices off, this field is unknown, exactly as before.
        if (app.authority.enabled && app.authority.ownerReviewBuilt && body.has("owner_authorization")) return askOwner(kind, id, body)
        ConfigurationAccess.only(body, "approve", "expected_digest")
        val approve = ConfigurationAccess.bool(body, "approve")
        val expected = ConfigurationAccess.text(body, "expected_digest", 64)
        fun check(record: JSONObject) { matches(kind, id, record, expected); authorize(kind, id, record, approve) }
        val result = apply(app, kind, id, approve, ::check)
        access.audit("$kind approval $id (${if (approve) "approved" else "declined"})")
        return JSONObject().put("kind", kind).put("id", id).put("approved", approve).put("result", result)
    }
}
