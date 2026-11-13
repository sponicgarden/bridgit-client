package cc.bridgit.app.log

import cc.bridgit.app.core.Keys
import org.json.JSONArray
import org.json.JSONObject

/** One log entry, LOG-01. Everything here has already been redacted (LOG-02). */
data class LogEntry(
    val id: Long = 0,
    val ts: Long,
    val channel: String,             // audit | diagnostic | agent
    val level: String = "info",      // debug | info | warn | error
    val kind: String,                // request | setting | agent | grant | pairing | service | crash | note ...
    val agentId: String? = null,
    val agent: String? = null,
    val sourceIp: String? = null,
    val method: String? = null,
    val endpoint: String? = null,
    val params: JSONObject? = null,
    val http: Int? = null,
    val result: String? = null,
    val bytesIn: Long? = null,
    val bytesOut: Long? = null,
    val durationMs: Long? = null,
    val requestId: String? = null,
    val message: String = "",
    val data: JSONObject? = null,
) {
    /** [agentName] lets the caller show the agent's current name (AGT-02: the log keeps the id). */
    fun toJson(isoTs: (Long) -> String, agentName: String? = agent): JSONObject = JSONObject()
        .put("id", id).put("ts", isoTs(ts)).put("channel", channel).put("level", level).put("kind", kind)
        .put("agent", agentName ?: JSONObject.NULL).put("agent_id", agentId ?: JSONObject.NULL)
        .put("source_ip", sourceIp ?: JSONObject.NULL).put("method", method ?: JSONObject.NULL)
        .put("endpoint", endpoint ?: JSONObject.NULL).put("params", params ?: JSONObject())
        .put("http", http ?: JSONObject.NULL).put("result", result ?: JSONObject.NULL)
        .put("bytes_in", bytesIn ?: JSONObject.NULL).put("bytes_out", bytesOut ?: JSONObject.NULL)
        .put("duration_ms", durationMs ?: JSONObject.NULL).put("request_id", requestId ?: JSONObject.NULL)
        .put("message", message).put("data", data ?: JSONObject())

    val isError: Boolean get() = (http ?: 0) >= 400 || level == "error"

    companion object {
        val CHANNELS = listOf("audit", "diagnostic", "agent")
        val LEVELS = listOf("debug", "info", "warn", "error")
        val KINDS = listOf("request", "configuration", "setting", "agent", "grant", "pairing", "interaction", "share", "file",
            "service", "crash", "note", "feedback", "battery", "wakewords", "sounds", "schedule", "applets", "judgment",
            "watch", "capture_stored", "capture_upload", "capture_storage", "security", "satellite", "link", "people", "vision", "mode",
            "camera", "lancamera", "lan", "authority", "spending")
        fun levelRank(l: String) = LEVELS.indexOf(l).coerceAtLeast(0)
    }
}

/**
 * Makes values safe to log (SEC-14, LOG-02): no keys, secrets, media or long text.
 * Every value that reaches the log store or a log response goes through here.
 */
object Redactor {
    const val MAX_STRING = 200
    const val MAX_DATA_BYTES = 4096
    private val SECRET_NAME = Regex("(?i)(key|token|secret|password|passwd|authorization|bearer|cookie|code|signature)")
    private val MEDIA_NAME = Regex("(?i)(data_base64|base64|bytes|html|body|text|answer|clipboard)")

    fun string(s: String, max: Int = MAX_STRING): String {
        val clean = Keys.scrub(s)
        return if (clean.length <= max) clean else clean.take(max - 1) + "…"
    }

    /** Copies [src] with secrets replaced, media dropped and strings truncated. Nested objects are flattened to 2 levels. */
    fun params(src: JSONObject, depth: Int = 0): JSONObject {
        val out = JSONObject()
        var n = 0
        for (rawKey in src.keys()) {
            if (++n > 40) { out.put("…", "${src.length() - 40} more"); break }
            val v = src.opt(rawKey)
            // Field names are agent input too: scrub and cap them like values.
            val k = string(rawKey, 40)
            out.put(k, when {
                SECRET_NAME.containsMatchIn(k) && k != "idempotency" -> "[redacted]"
                MEDIA_NAME.containsMatchIn(k) && v is String -> "[${v.length} chars]"
                v == null || v == JSONObject.NULL -> JSONObject.NULL
                v is String -> string(v)
                v is Number || v is Boolean -> v
                v is JSONObject -> if (depth >= 1) "[object]" else params(v, depth + 1)
                v is JSONArray -> if (v.length() > 20) "[${v.length()} items]" else JSONArray().apply {
                    for (i in 0 until v.length()) {
                        val e = v.opt(i)
                        put(when (e) { is String -> string(e); is Number, is Boolean -> e; else -> "[item]" })
                    }
                }
                else -> string(v.toString())
            })
        }
        return out
    }

    /** Agent-supplied note data (LOG-11): redacted like params and capped at 4 KB. */
    fun data(src: JSONObject): JSONObject {
        val r = params(src)
        return if (r.toString().toByteArray().size <= MAX_DATA_BYTES) r
        else JSONObject().put("truncated", true).put("size_bytes", r.toString().length)
    }
}
