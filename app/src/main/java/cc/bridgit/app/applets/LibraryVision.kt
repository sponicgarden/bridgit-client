package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import org.json.JSONObject

internal object LibraryVision {
    fun context(instance: JSONObject, workflow: JSONObject, required: Boolean = false): JSONObject? {
        val origin = instance.optJSONObject("source")
        val edition = instance.optJSONObject("edition_source") ?: origin
        if (!required && edition?.optString("kind") != "template" && origin?.optString("kind") !in setOf("template","bundled")) return null
        val action = workflow.optString("package_flow")
        val installed = instance.optJSONObject("flows")?.optJSONObject(action)
        val owner = edition?.optString("library_account").orEmpty()
        if (edition?.optString("kind") != "template" || !Regex("[a-f0-9]{64}").matches(owner) ||
            edition.optInt("revision") <= 0 || edition.optString("template").isBlank() ||
            installed == null || installed.optString("workflow_id") != workflow.getString("id") ||
            installed.optString("digest").isEmpty() || installed.optString("digest") != workflow.optString("approved_digest") ||
            instance.has("problem"))
            throw ApiError(409,"edition_review_required","Link the phone to your account and review the published edition before using its vision service.")
        return JSONObject().put("owner",owner).put("key",edition.getString("template")).put("revision",edition.getInt("revision"))
            .put("applet",instance.getString("package_id")).put("action",action)
    }

    fun verify(app: BridgeApp, agent: String, instance: JSONObject, workflow: JSONObject, pin: JSONObject?) {
        if (pin == null) return
        val current = app.applets.forAgent(agent)
        if (current == null || current.optString("status") != "active" || current.optString("sha256") != instance.optString("sha256") ||
            context(current,workflow)?.let { AppletEvolution.same(it,pin) } != true)
            throw ApiError(409,"edition_changed","The reviewed edition changed during this vision request.")
        app.platform.authorize(agent,"decisions")
    }
}
