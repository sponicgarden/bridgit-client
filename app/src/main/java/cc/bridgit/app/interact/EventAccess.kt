package cc.bridgit.app.interact

import cc.bridgit.app.core.Agent

/** Event payloads require the same grants over pull and push delivery. */
object EventAccess {
    fun allowed(agent: Agent?, type: String): Boolean {
        if (agent == null || !agent.allows("interactions")) return false
        val capability = when (type.substringBefore('.')) {
            "tour" -> "tour"
            "file" -> "files"
            "share" -> "shares"
            "clipboard" -> "clipboard"
            "grant" -> "screen_share"
            "schedule" -> "schedule"
            "listen" -> "wakewords"
            "sound" -> "sounds"
            "watch" -> "watches"
            else -> null
        }
        return capability == null || agent.allows(capability) && (capability != "tour" || agent.allows("location"))
    }
}
