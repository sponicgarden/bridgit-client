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
    }
    private fun kind(value: String): String = value.takeIf { it in KINDS }
        ?: throw ErrorCatalog.error("invalid_parameter", "Choose an approval kind from discovery")
    private fun pending(kind: String, id: String): JSONObject {
        val record = when (kind) {
            "applet" -> app.applets.pendingGet(id)
            "schedule" -> app.schedules.record(id)?.optJSONObject("pending")
            else -> app.platform.store.find(id, kind = kind)?.takeIf { it.optString("status") == "awaiting_owner" }
        }
        return record ?: throw ErrorCatalog.error("not_found", "No pending $kind approval $id")
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
    suspend fun decide(kind: String, id: String, body: JSONObject): JSONObject {
        access.require(); kind(kind)
        ConfigurationAccess.only(body, "approve", "expected_digest")
        val approve = ConfigurationAccess.bool(body, "approve")
        val expected = ConfigurationAccess.text(body, "expected_digest", 64)
        fun check(record: JSONObject) { matches(kind, id, record, expected); authorize(kind, id, record, approve) }
        val result = when (kind) {
            "applet" -> try { app.applets.decideByControl(id, approve, ::check) }
                catch (e: cc.bridgit.app.applets.AppletInstances.Refused) { throw ErrorCatalog.error(if (runCatching { ErrorCatalog.http(e.code) }.isSuccess) e.code else "conflict", e.message ?: "Applet approval refused") }
                catch (e: AppletPackage.Bad) { throw ErrorCatalog.error("invalid_parameter", e.message ?: "Invalid applet") }
            "schedule" -> {
                app.schedules.decideByControl(id, approve, ::check)
                JSONObject().put("status", if (approve) "active" else "declined")
            }
            else -> synchronized(app.platform.store) {
                val record = pending(kind, id)
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
        access.audit("$kind approval $id (${if (approve) "approved" else "declined"})")
        return JSONObject().put("kind", kind).put("id", id).put("approved", approve).put("result", result)
    }
}
