package cc.bridgit.app.planned

import android.content.Context
import io.ktor.server.routing.Route
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.*
import org.json.JSONObject
import java.util.UUID

class ClipboardRequests(private val app: BridgeApp) {
    private val prefs = app.getSharedPreferences("clipboard_requests", Context.MODE_PRIVATE)
    @Synchronized fun create(agentId: String, text: String): JSONObject {
        if (text.isBlank() || text.length > 10000) throw ErrorCatalog.error("invalid_parameter", "text must be 1–10000 characters.")
        if (all().count { it.optString("status") == "pending" } >= 20) throw ErrorCatalog.error("too_many_open", "At most 20 clipboard requests.")
        val o = JSONObject().put("id", UUID.randomUUID().toString()).put("agent_id", agentId).put("text", text)
            .put("status", "pending").put("expires_at", System.currentTimeMillis() + 300_000)
        prefs.edit().putString(o.getString("id"), o.toString()).commit()
        return o
    }
    @Synchronized fun all(): List<JSONObject> {
        prefs.all.forEach { (id, value) ->
            val old = runCatching { JSONObject(value as String) }.getOrNull()
            if (old == null || old.optLong("expires_at") < System.currentTimeMillis() - 86400_000) prefs.edit().remove(id).commit()
        }
        return prefs.all.values.mapNotNull { runCatching { JSONObject(it as String) }.getOrNull() }.map { o ->
        if (o.getString("status") == "pending" && o.getLong("expires_at") < System.currentTimeMillis()) { o.put("status", "expired").remove("text"); prefs.edit().putString(o.getString("id"), o.toString()).commit() }; o
        }
    }
    @Synchronized fun decide(id: String, approve: Boolean): JSONObject {
        val o = all().firstOrNull { it.getString("id") == id } ?: throw ErrorCatalog.error("not_found", "No clipboard request.")
        if (o.getString("status") != "pending") throw ErrorCatalog.error("conflict", "This request has already closed.")
        val a = app.agents.get(o.getString("agent_id"))
        if (approve && (!app.settings.isOn("clipboard") || !app.settings.listenerEnabled || a == null || !a.allows("clipboard"))) throw ErrorCatalog.error("agent_not_allowed", "Clipboard access was disabled or revoked.")
        if (approve) {
            val clip = android.content.ClipData.newPlainText("Agent text", o.getString("text"))
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
            app.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
        }
        o.put("status", if (approve) "copied" else "denied").remove("text")
        prefs.edit().putString(id, o.toString()).commit()
        app.log.system("audit", "setting", "Owner ${if (approve) "approved" else "denied"} a clipboard request from ${a?.name ?: "removed agent"}")
        app.interactions.event(o.getString("agent_id"), "clipboard.completed", JSONObject().put("id", id).put("status", o.getString("status")))
        return o
    }
}
class ClipboardModule(app: BridgeApp) : Capability(app) {
    override val id = "clipboard"
    override val titleRes = R.string.cap_clipboard
    override val descriptionRes = R.string.cap_clipboard_desc
    override val group = "talk"
    override val switchKey = "clipboard"
    override val endpoints = listOf(PlannedEndpoints.CLIPBOARD, PlannedEndpoints.CLIPBOARD_GET)
    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(PlannedEndpoints.CLIPBOARD) {
            requireUsable()
            val o = app.clipboardRequests.create(agent!!.id, body.optString("text"))
            OwnerToolsActivity.notify(app, "$agentName asks to copy text", "Tap to review and approve on the phone")
            Reply.Json(JSONObject(o.toString()).apply { remove("text") }.put("poll_url", "/v1/clipboard/${o.getString("id")}"), 202)
        }
        endpoint(PlannedEndpoints.CLIPBOARD_GET) {
            requireUsable()
            val o = app.clipboardRequests.all().firstOrNull { it.getString("id") == path("id") && it.getString("agent_id") == agent!!.id }
                ?: throw ErrorCatalog.error("not_found", "No clipboard request of yours.")
            Reply.Json(JSONObject(o.toString()).apply { remove("text") })
        }
    }
}
