package cc.bridgit.app.log

import org.json.JSONObject

/**
 * Structured fields for activity-log entries that used to say what happened only in the message
 * text. The live Bridgit animation reads these to pulse the right part of the phone. Pure, unit-tested.
 */
object LogFacts {
    /** Android `input` sub-commands and the action they stand for. */
    private val INPUT_ACTIONS = mapOf(
        "tap" to "tap", "swipe" to "swipe", "draganddrop" to "swipe", "motionevent" to "tap",
        "text" to "text", "keyevent" to "keyevent", "keycombination" to "keyevent", "press" to "keyevent",
    )
    /** Input sources `input` accepts before the sub-command (`input touchscreen tap 1 2`). */
    private val INPUT_SOURCES = setOf("keyboard", "mouse", "joystick", "touchnavigation", "touchpad", "trackball",
        "stylus", "dpad", "gamepad", "touchscreen", "rotaryencoder")

    /**
     * What an ADB shell command does: `tap`, `swipe`, `text` or `keyevent` for an `input` command,
     * `shell` for anything else (including several commands chained together).
     */
    fun adbAction(command: String): String {
        val c = command.trim()
        if (c.isEmpty() || Regex("[;&|\\n`]|\\$\\(").containsMatchIn(c)) return "shell"
        val words = c.split(Regex("\\s+")).toMutableList()
        if (words.firstOrNull() == "adb" && words.getOrNull(1) == "shell") repeat(2) { words.removeAt(0) }
        if (words.firstOrNull() != "input") return "shell"
        var i = 1
        if (words.getOrNull(i) == "-d") i += 2
        if (words.getOrNull(i)?.lowercase() in INPUT_SOURCES) i++
        if (words.getOrNull(i) == "-d") i += 2
        return INPUT_ACTIONS[words.getOrNull(i)?.lowercase()] ?: "shell"
    }

    /**
     * Data for the "<agent> played audio" entry, written when sound really starts. `action` is `speak`
     * for text-to-speech, `play` for an audio file; `source` is `api` when a /v1/speaker request asked
     * for it (that request has its own entry) or `bridgit` for a card read aloud or a Jev `speak` action.
     * Never the words themselves, only how many characters.
     */
    fun played(agentId: String, spoken: Boolean, fromApi: Boolean, caption: String?, tour: Boolean): JSONObject =
        JSONObject().put("agent_id", agentId).put("action", if (spoken) "speak" else "play")
            .put("source", if (fromApi) "api" else "bridgit").put("tour", tour)
            .apply { if (spoken && caption != null) put("chars", caption.length) }

    /** Data for a card a Jev `notice` action showed. */
    fun jevNotice(agentId: String, actionId: String, reason: String, interactionId: Long?): JSONObject =
        JSONObject().put("agent_id", agentId).put("action", "notice").put("source", "jev").put("action_id", actionId)
            .put("reason", Redactor.string(reason)).put("interaction_id", interactionId ?: JSONObject.NULL)

    fun jevNoticeMessage(agent: String, title: String) = "Jev showed a card for $agent: \"${Redactor.string(title, 80)}\""

    /** Data for a wake-word hit: the transcript (already shortened for the log) as its own field. */
    fun wakeHeard(agentId: String, endedBy: String, clip: Boolean, transcript: String, recogniser: String): JSONObject =
        JSONObject().put("agent_id", agentId).put("ended_by", endedBy).put("clip", clip)
            .put("has_transcript", transcript.isNotBlank()).put("transcript", transcript).put("recogniser", recogniser)
}
