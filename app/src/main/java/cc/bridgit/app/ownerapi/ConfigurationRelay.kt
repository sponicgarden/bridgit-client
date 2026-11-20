package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.Agent
import cc.bridgit.app.relay.RelaySetup
import cc.bridgit.app.relay.RelayStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Adds a transport for an already paired owner control agent; never creates initial authority. */
class ConfigurationRelay(private val app: BridgeApp, private val caller: Agent) {
    private val access = ConfigurationAccess(app, caller)
    private val store get() = RelayStore(app)
    private fun require() { access.require(); SuperAgentAccess(app, caller).require() }
    fun status(): JSONObject {
        require()
        return JSONObject().put("enabled", store.enabled).put("enrolled", store.enrolled)
            .put("base_url", store.base.ifEmpty { store.defaultBase() })
            .put("credential_configured", store.agents().any { it.optString("id") == caller.id })
            .put("signed_only", app.settings.securityRelaySignedOnly)
            .put("status", BridgeService.running?.relay?.status ?: "Off")
    }
    suspend fun update(body: JSONObject): JSONObject {
        require(); ConfigurationAccess.only(body, "enabled", "token")
        val enabled = if (body.has("enabled")) ConfigurationAccess.bool(body, "enabled") else null
        val token = if (body.has("token")) ConfigurationAccess.text(body, "token", 200) else null
        if (enabled == null && token == null) throw ErrorCatalog.error("invalid_parameter", "No relay change supplied")
        if (token != null && !Regex("rly_[A-Za-z0-9_-]{40,196}").matches(token))
            throw ErrorCatalog.error("invalid_parameter", "Supply a receiver-generated rly_ credential with at least 40 random characters")
        if (token != null && app.settings.securityRelaySignedOnly)
            throw ErrorCatalog.error("forbidden", "The owner requires signed relay requests")
        if (token != null && app.agents.get(caller.id)?.caps == null)
            throw ErrorCatalog.error("invalid_parameter", "Configure explicit capabilities for the existing control agent first")
        if (enabled == true) withContext(Dispatchers.IO) { require(); RelaySetup.turnOn(app, store, authorized = { require() }) }
        require()
        if (token != null) {
            // Maximum-control and delegated configuration projections remain native dispatcher decisions.
            store.addAgentHash(caller.id, setOf("GET /v1/applets/installed", "GET /v1/owner/configuration/listening", "POST /v1/owner/configuration/listening"), RelayStore.hash(token))
            BridgeService.running?.relay?.wake()
        }
        if (enabled == false) {
            BridgeService.running?.relay?.disable() ?: run { store.enabled = false; store.agents().forEach { app.platform.invalidate(it.getString("id")) } }
        }
        access.audit("relay transport")
        return status()
    }
    fun clear(): JSONObject {
        require(); store.remove(caller.id); BridgeService.running?.relay?.wake(); access.audit("relay credential removal")
        return status()
    }
}
