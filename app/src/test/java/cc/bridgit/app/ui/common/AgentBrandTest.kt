package cc.bridgit.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentBrandTest {
    @Test fun claudeFromModel() = assertEquals(AgentBrand.CLAUDE, AgentBrand.of("Claude Opus 5.5", "Claude Code", "laptop"))
    @Test fun claudeFromModelId() = assertEquals(AgentBrand.CLAUDE, AgentBrand.of("claude-opus-5-5", null, null))
    @Test fun openAiFromGptAndCodex() {
        assertEquals(AgentBrand.OPENAI, AgentBrand.of("gpt-6", "Codex desktop", "ChatGPT"))
        assertEquals(AgentBrand.OPENAI, AgentBrand.of("o3-mini", null, null))
        assertEquals(AgentBrand.OPENAI, AgentBrand.of(null, null, "ChatGPT"))
    }
    @Test fun modelBeatsName() = assertEquals(AgentBrand.CLAUDE, AgentBrand.of("Claude Sonnet 5.5", null, "My ChatGPT helper"))
    @Test fun othersAndWholeWords() {
        assertEquals(AgentBrand.GEMINI, AgentBrand.of("gemini-3-pro", null, null))
        assertEquals(AgentBrand.GROK, AgentBrand.of(null, null, "Grok"))
        assertEquals(AgentBrand.CURSOR, AgentBrand.of(null, "Cursor", null))
        assertNull(AgentBrand.of(null, null, "Claudette"))
        assertNull(AgentBrand.of(null, null, "claude test 11-04-26".replace("claude", "clade")))
        assertNull(AgentBrand.of(null, null, null))
    }
}
