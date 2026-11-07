package cc.bridgit.app.ui.addagent

import cc.bridgit.app.core.AgentRegistry
import cc.bridgit.app.relay.ConnectorClient
import org.junit.Assert.*
import org.junit.Test

class AgentSuggestionsTest {
    @Test fun existingAgentsGetANewNameWithoutHidingTheirBrand() {
        assertEquals("Claude", AgentSuggestions.availableName("Claude", emptyList()))
        assertEquals("Claude 4", AgentSuggestions.availableName("Claude", listOf("claude", "Claude 2", " CLAUDE 3 ")))
        assertEquals("Cursor 2", AgentSuggestions.availableName("Cursor", listOf("Cursor")))
        AgentSuggestions.all.forEach {
            assertTrue(AgentRegistry.NAME_RE.matches(AgentSuggestions.availableName(it.name, listOf(it.name))))
        }
    }

    @Test fun pairedLabelsUseValidDistinctNames() {
        val all = AgentSuggestions.all
        assertTrue(all.map { it.label }.containsAll(listOf("Grok bot / Grok", "Claude", "Claude Duet", "ChatGPT", "ChatGPT Dots")))
        assertEquals(all.size, all.map { it.name.lowercase() }.toSet().size)
        all.forEach { assertTrue("Invalid suggested name ${it.name}", AgentRegistry.NAME_RE.matches(it.name)); assertTrue(it.icon != 0) }
    }

    @Test fun onlyHostedChatAppsStartOnTheirConnectorSetup() {
        assertEquals(mapOf("Claude Duet" to ConnectorClient.CLAUDE, "ChatGPT Dots" to ConnectorClient.CHATGPT),
            AgentSuggestions.all.filter { it.connector != null }.associate { it.name to it.connector })
    }
}
