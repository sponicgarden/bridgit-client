package cc.bridgit.app.ui.interactions

import cc.bridgit.app.core.Agent
import org.junit.Assert.*
import org.junit.Test

class AppletCreatorsTest {
    private fun agent(name: String, harness: String? = null, model: String? = null, at: Long = 1) =
        Agent(name, name, "", "", "", 0, lastUsedAt = at, harness = harness, model = model)

    @Test fun noConfiguredLlmMeansSetup() {
        assertTrue(AppletCreators.configured(emptyList()).isEmpty())
        assertTrue(AppletCreators.configured(listOf(agent("Kitchen timer"))).isEmpty())
    }

    @Test fun recognizesAllFourAppsAndPrefersHarnessOverModel() {
        val result = AppletCreators.configured(listOf(
            agent("My helper", "Grok Bot", "Claude"), agent("Claude"),
            agent("ChatGPT"), agent("Grok Commie")))
        assertEquals(listOf("ai.x.grok.bot", "com.anthropic.claude", "com.openai.chatgpt", "ai.x.grok"),
            result.map { it.packageName })
    }

    @Test fun offersOneEntryPerAppWithMostRecentlyUsedFirst() {
        val result = AppletCreators.configured(listOf(
            agent("Claude old", at = 1), agent("ChatGPT", at = 2), agent("Claude new", at = 3)))
        assertEquals(listOf("Claude", "ChatGPT"), result.map { it.label })
    }

    @Test fun unprovisionedRotatedPausedAndAppletKeysAreNotCreators() {
        val paired = agent("Grok Bot", at = 5)
        assertTrue(AppletCreators.configured(listOf(paired.copy(lastUsedAt = 0))).isEmpty())
        assertTrue(AppletCreators.configured(listOf(paired.copy(keyCreatedAt = 6))).isEmpty())
        assertTrue(AppletCreators.configured(listOf(paired.copy(paused = true))).isEmpty())
        assertTrue(AppletCreators.configured(listOf(paired.copy(connection = cc.bridgit.app.core.Connection.APPLET))).isEmpty())
        assertEquals("ai.x.grok.bot", AppletCreators.configured(listOf(paired)).single().packageName)
    }

    @Test fun modelOrUnrelatedHarnessDoesNotOpenTheWrongChatApp() {
        assertTrue(AppletCreators.configured(listOf(agent("My helper", model = "Claude"))).isEmpty())
        assertTrue(AppletCreators.configured(listOf(agent("ChatGPT", harness = "Codex", model = "GPT"))).isEmpty())
    }

    @Test fun doesNotMatchProviderNamesInsideUnrelatedWords() {
        assertTrue(AppletCreators.configured(listOf(agent("Grokking recipes"))).isEmpty())
    }
}
