package cc.bridgit.app.remoteview

import cc.bridgit.app.log.LogEntry
import org.json.JSONObject

/**
 * What remote view (bridgit.cc/watch) may see of one log entry. Pure, so the unit test pins it down.
 *
 * Only: a sequence number (the log id), the time, the agent's name, the kind, method, path (ids taken out),
 * HTTP status, a fixed caption for the few entries without a path, and where speech came from. Never the log
 * message (it holds wake-word transcripts and card titles), parameters, data, results, sizes or addresses.
 * The backend (bridgit-cloud/feedback/src/watch.mjs) checks the same shape again.
 */
object RemoteViewLines {
    /** Entry kinds that are something an agent (or Bridgit for an agent) did on the phone. */
    val KINDS = setOf("request", "wakewords", "sounds", "interaction", "judgment", "feedback")

    /** Polling, setup and status reads: not actions, never sent (the scene ignores the same paths). */
    private val IGNORE = listOf(Regex("^/v1/health$"), Regex("^/v1/capabilities"), Regex("^/v1/log"), Regex("^/v1/events"),
        Regex("^/v1/guide"), Regex("^/v1/openapi"), Regex("^/v1/camera/arm"), Regex("^/v1/screen/grant"),
        Regex("^/v1/adb/(setup|status)"), Regex("^/v1/listen"), Regex("^/v1/sounds$"), Regex("^/v1/outbound"), Regex("^/v1/me$"), Regex("^/v1/portal"))
    private val ENDPOINT = Regex("^(/v[12](/[A-Za-z0-9_.:-]{1,40}){0,6}|mcp:[a-z_/]{1,40})$")
    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")

    /** A path with ids taken out: a segment with a digit, or over 24 characters, becomes ":id". Null if it isn't an API path. */
    fun endpoint(raw: String?): String? {
        val path = raw?.substringBefore('?')?.takeIf { it.isNotEmpty() } ?: return null
        if (!ENDPOINT.matches(path)) return null
        if (path.startsWith("mcp:")) return path
        return path.split('/').mapIndexed { i, s -> if (i < 2 || (s.none(Char::isDigit) && s.length <= 24)) s else ":id" }.joinToString("/")
    }

    /** A fixed phrase for entries without a path; the matching rules in the scene look for these words. */
    fun caption(e: LogEntry): String? = when (e.kind) {
        "wakewords" -> if (e.message.startsWith("Heard ")) "Heard a wake word" else null
        "sounds" -> if (e.message.startsWith("Heard a sound")) "Heard a sound" else null
        "interaction" -> when {
            e.message.endsWith("played audio") -> "Bridgit played audio"
            e.message.startsWith("Jev showed a card") -> "Jev showed a card"
            else -> null
        }
        "judgment" -> "Asked Jev"
        "feedback" -> "Filed a bug report"
        else -> null
    }

    /** Preserve legacy-name suppression for logs retained across the display-name upgrade. */
    fun isAppletDiagnostic(e: LogEntry): Boolean =
        listOf("Pet monitor", "Dog monitor").any { e.message.contains(it, ignoreCase = true) } || e.data?.optString("applet") == "dog"

    /** The line for [e], or null when it isn't sent. [agentName] is the agent's current name. */
    fun line(e: LogEntry, agentName: String?): JSONObject? {
        if (e.kind !in KINDS || e.id <= 0) return null
        // Applet diagnostics stay local; an instance backend sends its own bounded data.
        if (isAppletDiagnostic(e)) return null
        val path = endpoint(e.endpoint)
        val method = e.method?.uppercase()?.takeIf { it in METHODS }
        if (e.kind == "request") {
            if (e.agentId == null || path == null) return null // only agents' calls, not the owner's own screens
            if (IGNORE.any { it.containsMatchIn(path) }) return null
            if (method == "GET" && e.params?.has("wait") == true) return null // a long poll, not an action
        }
        val caption = if (e.kind == "request") null else caption(e)
        if (e.kind != "request" && caption == null) return null // only the entries the scene shows
        val source = e.data?.optString("source")?.takeIf { it in setOf("api", "bridgit", "jev") }
        return JSONObject().put("seq", e.id).put("ts", e.ts)
            .put("agent", (agentName ?: e.agent)?.take(60) ?: JSONObject.NULL)
            .put("kind", e.kind)
            .put("method", method ?: JSONObject.NULL)
            .put("endpoint", path ?: JSONObject.NULL)
            .put("http", e.http ?: JSONObject.NULL)
            .put("caption", caption ?: JSONObject.NULL)
            .put("source", source ?: JSONObject.NULL)
    }

    /** on = switched on and ready; off = switched off; blocked = on but missing a permission or hardware. */
    fun capState(enabled: Boolean, ready: Boolean): String = when {
        !enabled -> "off"
        !ready -> "blocked"
        else -> "on"
    }
}
