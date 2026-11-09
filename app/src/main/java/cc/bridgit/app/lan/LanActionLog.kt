package cc.bridgit.app.lan

import cc.bridgit.app.log.LogEntry
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.log.Redactor
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The runtime log of the outlet flow (A0100): every enroll, action and removal an agent asks of a LAN device
 * (smart outlets and relays, speakers, routers) is written to the activity log as it happens, in order. Each
 * operation writes a `started` entry before anything is sent to the device, any intermediate steps (a device pack
 * finding a moved device again), and one final entry with the outcome: `ok`, `failed` or `unknown_outcome`, with the
 * error code and message. All entries of one operation share an `op_id`.
 *
 * Entries are audit-channel, kind `lan`, attributed to the agent, so they follow the owner's log retention and
 * clearing, show on the phone's Activity screen and in GET /v1/log?kind=lan, and GET /v1/lan/devices/log returns an
 * agent's own entries. Credentials are never passed in; parameter values go through [Redactor] and URLs lose their
 * user info, query and fragment. Pure Kotlin, unit-tested.
 */
object LanActionLog {
    const val KIND = "lan"
    const val MAX_SCAN_PAGES = 20
    private const val PAGE = 500

    /** What started the operation: which agent, over which transport (http, mcp, relay...), which request. */
    data class Trigger(val agentId: String, val agentName: String, val via: String, val requestId: String, val method: String, val endpoint: String) {
        fun json() = JSONObject().put("agent_id", agentId).put("agent", agentName).put("via", via)
            .put("request_id", requestId).put("method", method).put("endpoint", endpoint)
    }

    /** Read-only actions; anything else may have changed the device when it fails part-way. */
    private val READS = setOf("status", "dhcp_leases")

    class Op(
        private val sink: (LogEntry) -> Unit,
        val trigger: Trigger,
        /** enroll, action or remove. */
        val op: String,
        val action: String?,
        val channel: Int?,
        params: JSONObject?,
        private val now: () -> Long = System::currentTimeMillis,
        val id: String = "lanop_" + UUID.randomUUID().toString().replace("-", "").take(16),
    ) {
        private val startedAt = now()
        private val params: JSONObject? = params?.let(::safeParams)
        private var device = JSONObject()
        private var finished = false

        /** Names the device once it's known: id, name, adapter and LAN address, never its credentials. */
        fun device(id: String, name: String? = null, adapter: String? = null, host: String? = null): Op {
            device = JSONObject().put("id", Redactor.string(id, 80))
                .put("name", name?.let { Redactor.string(it, 60) } ?: JSONObject.NULL)
                .put("adapter", adapter?.let { Redactor.string(it, 40) } ?: JSONObject.NULL)
                .put("host", host?.let { Redactor.string(it, 40) } ?: JSONObject.NULL)
            return this
        }
        fun device(d: LanDevice) = device(d.id, d.name, d.adapter, d.host)

        private val what get() = when (op) {
            "action" -> listOfNotNull(action, channel?.takeIf { device.optString("adapter") == "shelly_gen1" || it != 0 }?.let { "channel $it" }).joinToString(", ")
            "enroll" -> "enroll"
            else -> "remove"
        }
        private val label get() = device.optString("name").takeIf { device.opt("name") != JSONObject.NULL && it.isNotEmpty() }
            ?: device.optString("id").ifEmpty { "a device" }

        fun started() = write("started", "info", "${trigger.agentName} asked $label: $what")

        /** An intermediate step the flow took on its own, e.g. finding a device that moved and retrying once. */
        fun step(phase: String, message: String) = write(phase, "info", "$label: $message")

        fun ok(result: JSONObject?) {
            if (finished) return
            finished = true
            write("ok", "info", "$label: $what done${summary(result)}", result = result, final = true)
        }

        fun failed(code: String, message: String, http: Int) {
            if (finished) return
            finished = true
            val unknown = code in setOf("upstream_failed", "timed_out") && (op != "action" || action !in READS)
            val outcome = if (unknown) "unknown_outcome" else "failed"
            write(outcome, if (unknown) "warn" else "error",
                "$label: $what ${if (unknown) "outcome unknown" else "failed"} ($code): ${Redactor.string(message, 300)}",
                error = code, errorMessage = message, http = http, final = true)
        }

        private fun summary(result: JSONObject?): String {
            val r = result?.optJSONObject("result") ?: result ?: return ""
            return when {
                r.has("on") && r.opt("on") is Boolean -> " (now ${if (r.getBoolean("on")) "on" else "off"})"
                r.has("relays") -> r.optJSONArray("relays")?.let { a ->
                    " (" + (0 until a.length()).joinToString(", ") { i -> a.optJSONObject(i)?.let { "relay ${it.opt("channel")} ${when (it.opt("on")) { true -> "on"; false -> "off"; else -> "unknown" }}" } ?: "?" } + ")"
                } ?: ""
                else -> ""
            }
        }

        private fun write(phase: String, level: String, message: String, result: JSONObject? = null, error: String? = null,
                          errorMessage: String? = null, http: Int? = null, final: Boolean = false) {
            val at = now()
            val data = JSONObject().put("op_id", id).put("op", op).put("phase", phase)
                .put("action", action ?: JSONObject.NULL).put("channel", channel ?: JSONObject.NULL)
                .put("device", JSONObject(device.toString())).put("trigger", trigger.json())
            if (final) data.put("outcome", phase)
            if (error != null) data.put("error", error).put("error_message", Redactor.string(errorMessage.orEmpty(), 300))
            if (result != null) data.put("result", Redactor.params(flatRelays(result)))
            sink(LogEntry(ts = at, channel = "audit", level = level, kind = KIND, agentId = trigger.agentId, agent = trigger.agentName,
                method = trigger.method, endpoint = trigger.endpoint, params = params, http = http ?: if (final) 200 else null,
                result = if (final) error ?: "ok" else null, durationMs = if (final) at - startedAt else null,
                requestId = trigger.requestId, message = Redactor.string(message, 500), data = data))
        }
    }

    /** Relay states as "0:on" strings, so they survive the log's two-level flattening. */
    private fun flatRelays(r: JSONObject): JSONObject {
        val a = r.optJSONArray("relays") ?: return r
        return JSONObject(r.toString()).put("relays", JSONArray((0 until a.length()).map { i ->
            a.optJSONObject(i)?.let { "${it.opt("channel")}:${when (it.opt("on")) { true -> "on"; false -> "off"; else -> "unknown" }}" } ?: "?"
        }))
    }

    /** Request parameters as logged: redacted like every log entry, and URLs without user info, query or fragment. */
    fun safeParams(p: JSONObject): JSONObject {
        fun clean(v: Any?): Any? = when (v) {
            is String -> stripUrl(v)
            is JSONObject -> JSONObject().also { o -> v.keys().forEach { k -> o.put(k, clean(v.opt(k))) } }
            is JSONArray -> JSONArray().also { a -> for (i in 0 until v.length()) a.put(clean(v.opt(i))) }
            else -> v
        }
        return Redactor.params(clean(p) as JSONObject)
    }

    private val URL = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)(?:[^/@?#]*@)?([^?#]*)([?#].*)?$")
    fun stripUrl(s: String): String {
        val m = URL.matchEntire(s.trim()) ?: return s
        return m.groupValues[1] + m.groupValues[2] + if (m.groupValues[3].isNotEmpty()) "?…" else ""
    }

    /** One entry as GET /v1/lan/devices/log returns it. */
    fun view(e: LogEntry, iso: (Long) -> String): JSONObject {
        val d = e.data ?: JSONObject()
        return JSONObject().put("id", e.id).put("ts", iso(e.ts)).put("op_id", d.opt("op_id") ?: JSONObject.NULL)
            .put("op", d.opt("op") ?: JSONObject.NULL).put("phase", d.opt("phase") ?: JSONObject.NULL)
            .put("outcome", d.opt("outcome") ?: JSONObject.NULL).put("action", d.opt("action") ?: JSONObject.NULL)
            .put("channel", d.opt("channel") ?: JSONObject.NULL).put("device", d.optJSONObject("device") ?: JSONObject())
            .put("trigger", d.optJSONObject("trigger") ?: JSONObject()).put("params", e.params ?: JSONObject())
            .put("result", d.opt("result") ?: JSONObject.NULL).put("error", d.opt("error") ?: JSONObject.NULL)
            .put("error_message", d.opt("error_message") ?: JSONObject.NULL).put("duration_ms", e.durationMs ?: JSONObject.NULL)
            .put("level", e.level).put("message", e.message)
    }

    /**
     * An agent's own LAN entries, optionally for one device or one operation. Device and operation filters are applied
     * while paging through the log, at most [MAX_SCAN_PAGES] pages per call; next_cursor continues where the scan stopped.
     */
    fun read(query: (LogQuery) -> List<LogEntry>, agentId: String, device: String?, opId: String?, sinceId: Long?,
             limit: Int, ascending: Boolean, cursor: Long?, iso: (Long) -> String): JSONObject {
        val base = LogQuery(channels = listOf("audit"), agentIds = listOf(agentId), kinds = listOf(KIND), sinceId = sinceId,
            ascending = ascending, limit = PAGE)
        val out = JSONArray()
        var c = cursor
        var next: Long? = null
        var pages = 0
        scan@ while (true) {
            val page = query(base.copy(cursor = c))
            pages++
            for (e in page) {
                val d = e.data ?: JSONObject()
                if ((device == null || d.optJSONObject("device")?.optString("id") == device) && (opId == null || d.optString("op_id") == opId)) {
                    out.put(view(e, iso))
                    if (out.length() >= limit) { next = e.id; break@scan }
                }
            }
            if (page.size < PAGE) break
            c = page.last().id
            if (pages >= MAX_SCAN_PAGES) { next = c; break }
        }
        // A full page that ended exactly at the last entry still gets a cursor; the next call returns an empty page.
        return JSONObject().put("entries", out).put("count", out.length())
            .put("next_cursor", next?.let { LogQuery.encodeCursor(it) } ?: JSONObject.NULL)
            .put("order", if (ascending) "asc" else "desc")
            .put("retention", "Kept with the activity log: the owner's log retention and Clear log apply. Also in GET /v1/log?kind=lan with the log grant.")
    }
}
