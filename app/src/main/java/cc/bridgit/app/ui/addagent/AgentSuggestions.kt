package cc.bridgit.app.ui.addagent

import cc.bridgit.app.R
import cc.bridgit.app.relay.ConnectorClient

/** Display labels may pair the chat product and its agent; saved names follow the name field's rules. */
internal object AgentSuggestions {
    /** [connector]: starts on that chat app's custom connector setup over the relay (it can't reach Tailscale). */
    data class Item(val label: String, val name: String, val icon: Int, val color: Int? = null, val connector: ConnectorClient? = null)
    fun availableName(base: String, usedNames: Collection<String>): String {
        val used = usedNames.map { it.trim().lowercase() }.toSet()
        if (base.lowercase() !in used) return base
        var suffix = 2
        while ("$base $suffix".lowercase() in used) suffix++
        return "$base $suffix"
    }

    val all = listOf(
        Item("Grok bot / Grok", "Grok Bot", R.drawable.agent_brand_grok, 0xFF101010.toInt()),
        Item("Claude", "Claude", R.drawable.agent_brand_claude, 0xFFD97757.toInt()),
        Item("Claude Duet", "Claude Duet", R.drawable.agent_brand_claude, 0xFFD97757.toInt(), connector = ConnectorClient.CLAUDE),
        Item("ChatGPT", "ChatGPT", R.drawable.agent_brand_openai, 0xFF101010.toInt()),
        Item("ChatGPT Dots", "ChatGPT Dots", R.drawable.agent_brand_openai, 0xFF101010.toInt(), connector = ConnectorClient.CHATGPT),
        Item("Muse", "Muse", R.drawable.agent_suggestion_muse),
        Item("Google Gemini", "Gemini", R.drawable.agent_suggestion_gemini),
        Item("OpenCode", "OpenCode", R.drawable.agent_suggestion_opencode),
        Item("Instinct", "Instinct", R.drawable.agent_suggestion_instinct),
        Item("OpenRouter.ai", "OpenRouter", R.drawable.agent_suggestion_openrouter),
        Item("Cursor", "Cursor", R.drawable.agent_brand_cursor, 0xFF101010.toInt()),
    )
}
