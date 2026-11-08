package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.portal.PortalPairing
import cc.bridgit.app.relay.RelayStore
import org.json.JSONObject

/** One explicitly owner-granted scope, rechecked immediately before every configuration effect. */
class ConfigurationAccess(private val app: BridgeApp, private val caller: Agent) {
    fun require(allowListenerPaused: Boolean = false): Agent {
        val live = app.agents.get(caller.id)?.takeUnless { it.paused }
            ?: throw ErrorCatalog.error("unauthorized", "Control agent is no longer active")
        if (live.keyHash != caller.keyHash) throw ErrorCatalog.error("unauthorized", "Control key changed")
        // Link agents never configure: each Bridgit's own agents change its links (LNK-48).
        if (live.connection == Connection.APPLET || app.applets.forAgent(live.id) != null || app.links.isLinkAgent(live.id) ||
            PortalPairing.granted(app, RelayStore(app)).any { it.id == live.id })
            throw ErrorCatalog.error("forbidden", "Configuration authority belongs to control agents")
        if (!live.allows("owner_features")) throw ErrorCatalog.error("agent_not_allowed", "Owner feature access is required")
        if (!allowListenerPaused) app.platform.authorize(live.id, "owner.configuration")
        else {
            if (!app.platform.hasGrant(live.id, "owner.configuration")) throw ErrorCatalog.error("agent_not_allowed", "Configuration grant was revoked")
            val scope = cc.bridgit.app.platform.Capabilities.all.first { it.id == "owner.configuration" }
            if (cc.bridgit.app.platform.Capabilities.blockers(app, scope).isNotEmpty()) throw ErrorCatalog.error("capability_disabled", "Configuration authority is off")
        }
        return live
    }

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
