package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.ErrorCatalog
import org.json.JSONObject

/** Control MCP uses the same redacting handlers; credential bodies never enter durable operations. */
object ConfigurationTools {
    val NAMES = setOf("configuration.get", "configuration.set")
    /** Every configuration operation, plus the link disconnects any control agent may make (LNK-29). */
    private val OPERATIONS get() = ConfigurationEndpoints.ALL + ConfigurationLinks.TAKE_AWAY
    fun operations(read: Boolean) = OPERATIONS.filter { (it.method == "GET") == read }.map { "${it.method} ${it.fullPath}" }
    suspend fun call(app: BridgeApp, api: ApiServer, agent: String, generation: String, name: String, args: JSONObject, key: String?): JSONObject {
        val caller = app.agents.get(agent) ?: throw ErrorCatalog.error("unauthorized", "Control agent was removed")
        if (caller.keyHash != generation) throw ErrorCatalog.error("unauthorized", "Control key changed")
        ConfigurationAccess.only(args, "operation", "parameters", "body")
        val operation = if (name == "configuration.get" && !args.has("operation")) "GET /v1/owner/configuration"
            else ConfigurationAccess.text(args, "operation", 256)
        val spec = OPERATIONS.firstOrNull { "${it.method} ${it.fullPath}" == operation }
            ?: throw ErrorCatalog.error("invalid_parameter", "Choose a configuration operation from discovery")
        if (spec.power == cc.bridgit.app.core.Power.SUPER) SuperAgentAccess(app, caller).require()
        else if (spec in ConfigurationLinks.TAKE_AWAY) ConfigurationLinks.requireControl(app, caller)
        else ConfigurationAccess(app, caller).require()
        if ((spec.method == "GET") != (name == "configuration.get")) throw ErrorCatalog.error("invalid_parameter", "Use configuration.get for reads and configuration.set for changes")
        val params = if (args.has("parameters")) args.optJSONObject("parameters") ?: throw ErrorCatalog.error("invalid_parameter", "parameters must be an object") else JSONObject()
        val required = cc.bridgit.app.api.McpTools.pathParams(spec).toSet()
        val optional = spec.query.map { it.name }.toSet()
        val given = params.keys().asSequence().toSet()
        if (!given.containsAll(required) || !(required + optional).containsAll(given)) throw ErrorCatalog.error("invalid_parameter", "Provide exactly the operation's path parameters (and only its documented query parameters)")
        val path = required.associateWith { ConfigurationAccess.text(params, it, 128) }
        val query = (given - required).associateWith { k -> params.opt(k)?.toString()?.takeIf { it.length <= 128 } ?: throw ErrorCatalog.error("invalid_parameter", "$k is too long") }
        val body = if (args.has("body")) args.optJSONObject("body") ?: throw ErrorCatalog.error("invalid_parameter", "body must be an object") else JSONObject()
        if ((spec.method == "GET" || spec.body.isEmpty()) && body.length() > 0) throw ErrorCatalog.error("invalid_parameter", "This operation takes no body")
        val result = api.dispatch(spec, caller, "control:${caller.id}", query, path, body, key, "control-configuration")
        val json = JSONObject(String(result.bytes))
        if (result.status !in 200..299) throw cc.bridgit.app.api.ApiError(result.status, json.optString("error", "configuration_failed"), json.optString("message", "Configuration failed"))
        return json
    }
}
