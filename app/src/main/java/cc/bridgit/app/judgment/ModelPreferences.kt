package cc.bridgit.app.judgment

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ErrorCatalog
import org.json.JSONObject

/** Account overrides are shared across every installed instance of the package. */
object ModelPreferences {
    private val APPLET = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private val KEY = Regex("[a-z0-9][a-z0-9._:-]{0,79}")
    fun validate(applet: String, task: String, model: String?) {
        if (!APPLET.matches(applet) || !KEY.matches(task) || (model != null && !KEY.matches(model)))
            throw ErrorCatalog.error("invalid_parameter", "Use a stable applet package ID, task key and registered model ID; null restores inheritance.")
    }
    fun read(app: BridgeApp, applet: String? = null, task: String? = null): JSONObject {
        if (applet != null && task != null) validate(applet, task, null)
        return call(app, "GET", "/v1/model-preferences" + if (applet != null && task != null) "/$applet/$task" else "", null)
    }
    fun set(app: BridgeApp, applet: String, task: String, model: String?): JSONObject {
        validate(applet, task, model)
        return call(app, if (model == null) "DELETE" else "PUT", "/v1/model-preferences/$applet/$task", model?.let { JSONObject().put("model_id", it) })
    }
    private fun call(app: BridgeApp, method: String, path: String, body: JSONObject?): JSONObject {
        val (status, reply) = app.budgetSync.call(method, path, body)
        if (status !in 200..299) throw ApiError(status, reply.optString("error", "team_unavailable"), reply.optString("message", "Model preferences are unavailable."))
        return reply
    }
}
