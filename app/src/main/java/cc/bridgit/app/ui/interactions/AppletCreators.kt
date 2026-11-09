package cc.bridgit.app.ui.interactions

import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection

/** Only offer apps belonging to configured agents, never merely installed apps or applet principals. */
object AppletCreators {
    data class Creator(val label: String, val packageName: String, val names: List<String>)

    private val creators = listOf(
        Creator("ChatGPT", "com.openai.chatgpt", listOf("chatgpt", "chat gpt")),
        Creator("Claude", "com.anthropic.claude", listOf("claude", "anthropic")),
        Creator("Grok Bot", "ai.x.grok.bot", listOf("grokbot", "grok bot")),
        Creator("Grok", "ai.x.grok", listOf("grok")),
    )

    fun configured(agents: List<Agent>): List<Creator> = agents
        .filter { !it.suspended && it.connection != Connection.APPLET && it.lastUsedAt > 0L && it.lastUsedAt >= it.keyCreatedAt }
        .sortedByDescending { it.lastUsedAt }
        .mapNotNull { agent ->
            // A model name does not identify a receiving app; a known harness overrides a guessed name.
            sequenceOf(agent.harness?.takeIf { it.isNotBlank() } ?: agent.name).mapNotNull { value ->
                val normalized = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
                creators.firstOrNull { creator -> creator.names.any { name ->
                    Regex("(?:^| )${Regex.escape(name)}(?: |$)").containsMatchIn(normalized)
                } }
            }.firstOrNull()
        }.distinctBy { it.packageName }
}
