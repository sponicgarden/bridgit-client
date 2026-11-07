package cc.bridgit.app.relay

import cc.bridgit.app.R

/**
 * Hosted chat apps that add this phone's relay `/mcp` as a custom connector (MCP server) and sign in with OAuth:
 * the owner pastes the agent's relay key on the relay's own page once.
 */
enum class ConnectorClient(val product: String, val steps: List<Int>, val openUrl: String) {
    /** Claude Duet and claude.ai. */
    CLAUDE("Claude", listOf(R.string.relay_claude_step_open, R.string.relay_claude_step_url, R.string.relay_claude_step_key),
        "https://claude.ai/customize/connectors?modal=add-custom-connector"),
    /** ChatGPT Dots (and ChatGPT itself). */
    CHATGPT("ChatGPT", listOf(R.string.relay_chatgpt_step_open, R.string.relay_chatgpt_step_url, R.string.relay_chatgpt_step_key),
        "https://chatgpt.com/plugins"),
}
