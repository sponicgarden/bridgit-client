package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.Agent
import cc.bridgit.app.judgment.OwnKeys
import org.json.JSONArray
import org.json.JSONObject

/** Credentials go directly to their native store; never through durable operation inputs or replies. */
class ConfigurationCredentials(private val app: BridgeApp, caller: Agent) {
    private val access = ConfigurationAccess(app, caller)
    private val keys = OwnKeys(app)
    private fun provider(id: String) = OwnKeys.PROVIDERS.firstOrNull { it.id == id }
        ?: throw ErrorCatalog.error("not_found", "Unknown model-key provider")

    fun status(): JSONObject {
        access.require()
        return JSONObject().put("providers", JSONArray(OwnKeys.PROVIDERS.map { p ->
            JSONObject().put("id", p.id).put("name", app.getString(p.label)).put("configured", keys.has(p.id))
                .put("write_only", true).put("fields", JSONArray(p.fields.map { f ->
                    JSONObject().put("label", app.getString(f.hint)).put("pattern", f.pattern.pattern).put("secret", f.secret)
                })).put("write_url", "/v1/owner/configuration/keys/${p.id}")
        }))
    }

    fun set(id: String, body: JSONObject): JSONObject {
        access.require()
        ConfigurationAccess.only(body, "credentials")
        val p = provider(id)
        val fields = body.optJSONArray("credentials") ?: throw ErrorCatalog.error("invalid_parameter", "credentials must be an array")
        if (fields.length() != p.fields.size) throw ErrorCatalog.error("invalid_parameter", "Provide each provider field in discovery order")
        val values = p.fields.mapIndexed { i, spec ->
            val value = fields.opt(i) as? String ?: throw ErrorCatalog.error("invalid_parameter", "Each credential field must be text")
            value
        }
        val secret = OwnKeys.secretOf(p, values) ?: throw ErrorCatalog.error("invalid_parameter", "Credential fields do not match the provider format")
        access.require()
        keys.set(id, secret) { access.require() }
        access.audit("model key for $id")
        return JSONObject().put("provider", id).put("configured", true).put("write_only", true)
    }

    fun remove(id: String): JSONObject {
        access.require(); provider(id)
        keys.clear(id)
        access.audit("model key for $id")
        return JSONObject().put("provider", id).put("configured", false).put("write_only", true)
    }
}
