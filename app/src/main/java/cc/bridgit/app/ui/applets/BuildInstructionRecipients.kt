package cc.bridgit.app.ui.applets

import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.interact.EventAccess
import cc.bridgit.app.ui.agents.AgentStats

internal object BuildInstructionRecipients {
    fun available(agents: List<Agent>, now: Long = System.currentTimeMillis()): List<Agent> = agents
        .filter { !it.suspended && it.connection != Connection.APPLET && AgentStats.isActive(it, now) &&
            EventAccess.allowed(it, "applet.create_requested") }
        .sortedByDescending { it.lastUsedAt }
}
