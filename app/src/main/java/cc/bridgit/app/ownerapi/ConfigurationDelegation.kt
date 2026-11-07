package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.core.Connection
import cc.bridgit.app.platform.Capabilities
import cc.bridgit.app.portal.PortalData
import cc.bridgit.app.relay.RelayDispatcher
import org.json.JSONArray
import org.json.JSONObject

/** Explicit configuration delegation includes this API family, independent of an older route snapshot.
 * No initial grant, key, role, peer or unrelated route is created or widened. Revocation removes the projection.
 */
object ConfigurationDelegation {
    fun effective(app: BridgeApp, config: JSONObject): JSONObject {
        val id = config.optString("id")
        val agent = app.agents.get(id) ?: return config
        val routes = RelayDispatcher.strings(config, "routes")
        val tools = RelayDispatcher.strings(config, "tools")
        // Classify this stored config directly: HomeStates reads RelayStore.agents(), which projects through here.
        val superAccess = agent.superPowers && agent.allows("settings") && app.settings.isOn("settings_api") &&
            !PortalData.isPortalConfig(routes, tools) && !app.hubLinks.isHub(id) && !app.satellites.isReturnAgent(id)
        val delegated = agent.allows("owner_features") && Capabilities.enabled(app, "owner.configuration") && app.platform.hasGrant(id, "owner.configuration")
        if (agent.paused || agent.keyHash != config.optString("generation") || agent.connection == Connection.APPLET || app.applets.forAgent(id) != null ||
            PortalData.isLegacyPortalConfig(routes, tools) || (!superAccess && !delegated)) return config
        val endpoints = if (delegated) ConfigurationEndpoints.ALL else ConfigurationEndpoints.ALL.filter { it.power == cc.bridgit.app.core.Power.SUPER }
        return JSONObject(config.toString())
            .put("routes", JSONArray((routes + endpoints.map { "${it.method} ${it.fullPath}" }).sorted()))
            .put("tools", JSONArray((tools + ConfigurationTools.NAMES).sorted()))
    }
}
