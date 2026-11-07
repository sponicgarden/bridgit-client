package cc.bridgit.app.judgment

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.applets.AppletBudget
import cc.bridgit.app.applets.BudgetModule
import cc.bridgit.app.api.ErrorCatalog
import org.json.JSONArray
import org.json.JSONObject

object ServiceBudgets {
    val NAMES = linkedMapOf("jev" to "Jev", "clef" to "Clef", "glm" to "GLM 5.3 Flash", "locate" to "Find in photo", "appraise" to "Inventory", "chef" to "Chef’s Helper", "speech" to "Cloud speech", "images" to "Azure GPT Image 2.5 Sunburst", "story" to "Fairytale", "places" to "Nearby places", "airquality" to "Air quality", "pollen" to "Pollen", "timezone" to "Time zone", "maps" to "Satellite maps", "streetview" to "Street View", "weathergoogle" to "Google Weather", "openmeteo" to "Open-Meteo")
    fun validate(service: String, limits: JSONArray) {
        if (service !in NAMES) throw ErrorCatalog.error("invalid_parameter", "Unknown paid service.")
        try { if(limits.length() > 0) AppletBudget.parseLimits(limits) } catch (e: cc.bridgit.app.applets.AppletPackage.Bad) { BudgetModule.error(e) }
    }
    fun read(app: BridgeApp, service: String? = null): JSONObject = call(app, "GET", "/v1/budgets/services" + (service?.let { "/$it" } ?: ""), null)
    fun set(app: BridgeApp, service: String, limits: JSONArray): JSONObject {
        validate(service, limits)
        return call(app, "PUT", "/v1/budgets/services/$service", JSONObject().put("limits", if(limits.length() == 0) limits else AppletBudget.json(AppletBudget.parseLimits(limits))))
    }
    private fun call(app: BridgeApp, method: String, path: String, body: JSONObject?): JSONObject {
        val (code, reply) = app.budgetSync.call(method, path, body)
        if (code !in 200..299) throw ErrorCatalog.error("team_unavailable", reply.optString("message", "Service budgets are unavailable."))
        return reply
    }
}
