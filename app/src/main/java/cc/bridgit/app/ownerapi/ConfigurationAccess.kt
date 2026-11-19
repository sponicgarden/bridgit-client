package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.Agent
import org.json.JSONObject

/** One explicitly owner-granted scope, rechecked immediately before every configuration effect. */
class ConfigurationAccess(private val app: BridgeApp, private val caller: Agent) {
    /** The checks live in core.authority.Grants.requireConfiguration (A0332). */
    fun require(allowListenerPaused: Boolean = false): Agent =
        cc.bridgit.app.core.authority.Grants.requireConfiguration(cc.bridgit.app.core.authority.AndroidGrantState(app), caller, allowListenerPaused)

    fun audit(kind: String) {
        app.log.system("audit", "configuration", "${caller.name} changed $kind")
    }

    companion object {
        fun only(body: JSONObject, vararg allowed: String) {
            if (body.keys().asSequence().any { it !in allowed }) throw ErrorCatalog.error("invalid_parameter", "Unknown configuration field")
        }
        fun text(body: JSONObject, key: String, max: Int, allowEmpty: Boolean = false): String {
            val value = body.opt(key) as? String ?: throw ErrorCatalog.error("invalid_parameter", "$key must be text")
            if (value.length > max || (!allowEmpty && value.isBlank())) throw ErrorCatalog.error("invalid_parameter", "$key has an invalid length")
            return value
        }
        fun bool(body: JSONObject, key: String): Boolean = body.opt(key) as? Boolean
            ?: throw ErrorCatalog.error("invalid_parameter", "$key must be true or false")
    }
}
