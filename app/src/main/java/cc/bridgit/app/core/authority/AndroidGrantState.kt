package cc.bridgit.app.core.authority

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.platform.Capabilities

/** [Grants.State] read from this Android app: agents, links, applets, portal pairings, v2 grants and switches. */
class AndroidGrantState(private val app: BridgeApp) : Grants.State {
    override fun live(id: String): Agent? = app.agents.get(id)
    override fun isLinkAgent(id: String): Boolean = app.links.isLinkAgent(id)
    override fun isConfigurationExcluded(live: Agent): Boolean =
        live.connection == Connection.APPLET || app.applets.forAgent(live.id) != null || app.links.isLinkAgent(live.id) ||
            cc.bridgit.app.portal.PortalPairing.granted(app, cc.bridgit.app.relay.RelayStore(app)).any { it.id == live.id }
    override fun listenerEnabled(): Boolean = app.settings.listenerEnabled
    override fun scopeKnown(scope: String): Boolean = Capabilities.all.any { it.id == scope }
    override fun scopeLabel(scope: String): String = Capabilities.all.first { it.id == scope }.label
    override fun hasGrant(agentId: String, scope: String): Boolean = app.platform.hasGrant(agentId, scope)
    override fun scopeBlockers(scope: String): List<String> = Capabilities.blockers(app, Capabilities.all.first { it.id == scope })
    override fun superConfiguration(agentId: String): Boolean = app.agents.get(agentId)?.let { live ->
        live.superPowers && live.allows("settings") && app.settings.isOn("settings_api") &&
            cc.bridgit.app.ui.home.HomeStates.controlAgents(app).any { it.id == agentId }
    } == true
    override fun emailGate(exempt: Boolean) = app.alerts.checkAgentCall(exempt)
}
