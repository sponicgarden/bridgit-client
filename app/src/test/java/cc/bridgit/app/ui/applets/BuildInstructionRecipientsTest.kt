package cc.bridgit.app.ui.applets

import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.ui.agents.AgentStats
import org.junit.Assert.*
import org.junit.Test

class BuildInstructionRecipientsTest {
    private val now = 2_000_000L
    private val agent = Agent("agent", "My agent", "", "", "", 1, lastUsedAt = now)

    @Test fun excludesUnpairedStalePausedAppletAndNoEventAccess() {
        val rejected = listOf(agent.copy(lastUsedAt = 0),
            agent.copy(lastUsedAt = now - AgentStats.ACTIVE_MS - 1),
            agent.copy(paused = true), agent.copy(connection = Connection.APPLET),
            agent.copy(caps = setOf("status")))
        assertTrue(BuildInstructionRecipients.available(rejected, now).isEmpty())
    }

    @Test fun acceptsConfiguredAgentsRegardlessOfHarnessAndOrdersByContact() {
        val older = agent.copy(id = "old", lastUsedAt = now - 1, caps = setOf("interactions"))
        assertEquals(listOf(agent, older), BuildInstructionRecipients.available(listOf(older, agent), now))
    }
}
