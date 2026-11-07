package cc.bridgit.app.feedback

import android.content.Context
import android.os.Build
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.update.UpdateLogic
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Bridgit names (bridgit.cc/names): this Bridgit's Device Domain Name, registered with the team backend
 * (POST /v1/names, signed as this install). The control agent picks the name on its first call; the first
 * Bridgit to register a name keeps it for good. While visible, a heartbeat says it is on.
 * The local copy only caches the backend's answer for capabilities/health hints; the backend decides.
 */
object DeviceNames {
    /** Same advice the backend gives (feedback/src/names.mjs NAME_ADVICE). */
    const val ADVICE = "Pick a Device Domain Name for this Bridgit: about 10 characters, as cute and friendly to a person as you can make it, like kitchen-tab or porch-cam. " +
        "3 to 30 lowercase letters, digits and hyphens; no slashes. First come, first served, like a domain: if it's taken, pick another."
    const val OPERATION = "POST /v1/owner/configuration/name"
    private const val KEY = "bridgit_name"
    private const val HEARTBEAT_S_DEFAULT = 300
    private val NAME = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val client by lazy { FeedbackUploader.client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }
    @Volatile private var lastHeartbeatAt = 0L

    /** A Device Domain Name as the backend accepts it, lower-cased; null when invalid. Pure. */
    fun parse(text: String): String? = text.trim().lowercase().takeIf { it.length in 3..30 && NAME.matches(it) }

    /** The backend's own view cached on the phone, or null before registration. */
    fun cached(app: BridgeApp): JSONObject? = app.platform.store.setting(KEY).takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun save(app: BridgeApp, own: JSONObject) {
        val keep = JSONObject().put("name", own.getString("name")).put("label", own.opt("label") ?: JSONObject.NULL)
            .put("visible", own.optBoolean("visible")).put("heartbeat_every_s", own.optInt("heartbeat_every_s", HEARTBEAT_S_DEFAULT))
        app.platform.store.setting(KEY, keep.toString(), true)
    }

    /**
     * The hint block for capabilities and health: what the agent should do about the name, without a network call.
     * With [agentId], says when that agent lacks the owner.configuration grant the operation needs.
     */
    fun hint(app: BridgeApp, agentId: String? = null): JSONObject = hint(app).also { h ->
        val agent = agentId?.let { app.agents.get(it) } ?: return@also
        if (h.getString("status") == "unregistered" && !(agent.allows("owner_features") && app.platform.hasGrant(agent.id, "owner.configuration")))
            h.put("blocked", "owner.configuration grant required: ask the owner to allow app configuration")
    }

    fun hint(app: BridgeApp): JSONObject {
        val own = cached(app)
        return if (own == null) JSONObject().put("status", "unregistered").put("advice", ADVICE)
            .put("how", "Register a name first: $OPERATION {\"name\":\"kitchen-tab\"} (control MCP: configuration.set with that operation). Taken? Suggest another.")
            .put("about", cc.bridgit.app.api.Learn.NAMES_URL)
        else JSONObject().put("status", "registered").put("name", own.getString("name")).put("label", own.opt("label") ?: JSONObject.NULL)
            .put("visible", own.optBoolean("visible")).put("how", "Registered. Change label or visible with $OPERATION.").put("about", cc.bridgit.app.api.Learn.NAMES_URL)
    }

    /** GET /v1/names/me: the backend's answer, cached; unregistered when the backend has none. */
    fun refresh(app: BridgeApp): JSONObject {
        val (code, json) = call(app, "GET", "/v1/names/me", null)
        if (code == 404 && json.optString("error") == "no_name") { app.platform.store.setting(KEY, "", true); return hint(app) }
        check(app, code, json)
        save(app, json); return hint(app).put("visible_since", json.opt("visible_since")).put("last_seen_at", json.opt("last_seen_at"))
    }

    /** Registers [name] (POST /v1/names) when this Bridgit has none; otherwise changes label/visibility only. */
    fun set(app: BridgeApp, name: String?, label: Any?, hasLabel: Boolean, visible: Boolean?): JSONObject {
        val held = cached(app)?.optString("name")
        if (held == null && name != null) {
            val body = JSONObject().put("name", name).apply { if (hasLabel) put("label", label ?: JSONObject.NULL); visible?.let { put("visible", it) } }
            val (code, json) = call(app, "POST", "/v1/names", body)
            if (code == 409 && json.optString("error") == "already_named") return refresh(app).also { throw ErrorCatalog.error("already_named", json.optString("message"), mapOf("name" to json.opt("name"))) }
            check(app, code, json)
            save(app, json); lastHeartbeatAt = System.currentTimeMillis()
            return hint(app).put("registered", true).put("created", code == 201)
        }
        if (held == null) {
            // The phone may have no cached copy (cleared settings); ask before saying there is no name.
            if (refresh(app).optString("status") != "registered") throw ErrorCatalog.error("invalid_parameter", "name is required: this Bridgit has no name yet. $ADVICE")
            return set(app, name, label, hasLabel, visible)
        }
        if (name != null && name != held) throw ErrorCatalog.error("already_named", "This Bridgit is already $held. A Bridgit keeps its first name.", mapOf("name" to held))
        val changes = JSONObject().apply { if (hasLabel) put("label", label ?: JSONObject.NULL); visible?.let { put("visible", it) } }
        if (changes.length() == 0) return refresh(app)
        val (code, json) = call(app, "PATCH", "/v1/names/me", changes)
        check(app, code, json); save(app, json); return hint(app)
    }

    /** DELETE /v1/names/me: hides the name and clears the label; the name stays this Bridgit's. */
    fun hide(app: BridgeApp): JSONObject {
        val (code, json) = call(app, "DELETE", "/v1/names/me", null)
        if (code == 404 && json.optString("error") == "no_name") { app.platform.store.setting(KEY, "", true); return hint(app) }
        check(app, code, json); save(app, json); return hint(app)
    }

    /** Says "I'm on" every heartbeat_every_s while the name is visible. Called from the service's periodic loop. */
    fun heartbeatIfDue(app: BridgeApp) {
        val own = cached(app) ?: return
        if (!own.optBoolean("visible") || !FeedbackUploader.configured) return
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatAt < own.optInt("heartbeat_every_s", HEARTBEAT_S_DEFAULT) * 1000L - 15_000) return
        lastHeartbeatAt = now
        val (code, json) = runCatching { call(app, "POST", "/v1/names/heartbeat", JSONObject()) }.getOrNull() ?: return
        if (code == 200) save(app, json) else if (code == 404 && json.optString("error") == "no_name") app.platform.store.setting(KEY, "", true)
    }

    private fun check(app: Context, code: Int, json: JSONObject) {
        if (code in 200..299) return
        val message = json.optString("message").ifEmpty { "The team backend answered $code." }
        when (json.optString("error")) {
            "name_taken" -> throw ErrorCatalog.error("name_taken", message, mapOf("lookup" to json.opt("lookup")))
            "already_named" -> throw ErrorCatalog.error("already_named", message, mapOf("name" to json.opt("name")))
            "invalid_parameter" -> throw ErrorCatalog.error("invalid_parameter", message)
            "rate_limited" -> throw ErrorCatalog.error("rate_limited", message, mapOf("retry_after_s" to 3600))
            "unknown_install" -> { InstallIdentity.forget(app); throw ErrorCatalog.error("team_unavailable", "The phone must register with the team backend again. Retry.") }
        }
        throw ErrorCatalog.error("upstream_failed", message, mapOf("service" to "bridgit names", "upstream_status" to code))
    }

    private fun call(app: BridgeApp, method: String, path: String, body: JSONObject?): Pair<Int, JSONObject> {
        if (!FeedbackUploader.configured) throw ErrorCatalog.error("team_unavailable", "This build has no team backend.")
        try { InstallIdentity.ensure(app, TeamKeys.base) } catch (e: Exception) { null }
            ?: throw ErrorCatalog.error("team_unavailable", "The phone couldn't prove who it is to the team backend.")
        val bytes = body?.toString()?.toByteArray() ?: ByteArray(0)
        val req = Request.Builder().url("${TeamKeys.base}$path")
            .method(method, if (body != null) bytes.toRequestBody("application/json".toMediaType()) else if (method == "DELETE") ByteArray(0).toRequestBody(null) else null)
            .apply { InstallIdentity.headers(app, method, path, bytes).forEach { (k, v) -> header(k, v) } }
            .header("User-Agent", UpdateLogic.userAgent(cc.bridgit.app.BuildConfig.VERSION_NAME, Build.VERSION.RELEASE)).build()
        return try {
            client.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                res.code to (runCatching { JSONObject(text) }.getOrNull() ?: JSONObject())
            }
        } catch (e: ApiError) { throw e } catch (e: java.io.IOException) {
            throw ErrorCatalog.error("team_unavailable", "The team backend can't be reached: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
