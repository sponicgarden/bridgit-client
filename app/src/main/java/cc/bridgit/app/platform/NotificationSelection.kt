package cc.bridgit.app.platform

import android.content.Intent
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import org.json.JSONArray
import org.json.JSONObject

/** Native/API parity for per-agent application selection; this does not grant notification access. */
class NotificationSelection(private val app: BridgeApp) {
    fun selected(agent: String): List<String> = app.platform.store.setting("notification_apps:$agent").split(',').filter { it.isNotBlank() }
    fun available(): List<String> = app.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName }.distinct().sorted()
    fun status(agent: String): JSONObject {
        if (app.agents.get(agent) == null) throw ErrorCatalog.error("not_found", "Unknown agent")
        return JSONObject().put("agent_id", agent).put("packages", JSONArray(selected(agent))).put("available", JSONArray(available()))
            .put("android_access_granted", NotificationBridge.instance != null)
    }
    fun set(agent: String, packages: List<String>) {
        if (app.agents.get(agent) == null) throw ErrorCatalog.error("not_found", "Unknown agent")
        if (packages.size > 20 || packages.distinct().size != packages.size || packages.any { !Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(it) })
            throw ErrorCatalog.error("invalid_parameter", "Select at most twenty unique application package names")
        val known = available().toSet() + selected(agent)
        if (packages.any { it !in known }) throw ErrorCatalog.error("invalid_parameter", "Select installed launcher applications from available")
        val runtime = app.platform
        synchronized(runtime.store) {
            runtime.store.setting("notification_apps:$agent", packages.joinToString(","), true)
            runtime.store.list(agent, "workflow").filter {
                val trigger = it.optJSONObject("definition")?.optJSONObject("trigger")
                trigger?.optString("transport") == "notification" && trigger.optString("package") !in packages
            }.forEach { runtime.workflows.pause(agent, it.getString("id")) }
        }
    }
}
