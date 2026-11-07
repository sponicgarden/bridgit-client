package cc.bridgit.app.interact

import cc.bridgit.app.api.EndpointSpec
import org.json.JSONObject

/**
 * Feature #0007, the pure half of Bridgit.act: a card calls one of its agent's API endpoints directly on
 * the phone. Parsing the page's request, matching it to an endpoint and shaping the result for the page.
 * No Android types, so it is unit-tested. The dispatcher ([CardActionRunner]) runs the call through
 * ApiServer.dispatch as the agent, so the agent's grants, switches and limits apply as over HTTP.
 * Design: docs/spec-card-actions.md.
 */
object CardActions {
    /** Calls one card may have running at once. */
    const val MAX_IN_FLIGHT = 8
    /** Biggest JSON reply handed back to the page; a bigger one comes back ok: false, error reply_too_big (media: {content_type, bytes}). */
    const val PAGE_BODY_MAX_BYTES = 64 * 1024

    /** One parsed Bridgit.act request. [path] always starts with /v1/. */
    data class Call(val method: String, val path: String, val query: Map<String, String>, val body: JSONObject)

    /** A request the page sent that can't run; [code] is the error the page and the agent see. */
    class Refused(val code: String, message: String) : Exception(message)

    private fun refuse(code: String, msg: String): Nothing = throw Refused(code, msg)

    /** Parses the JSON string the shim hands to the native bridge. */
    fun parse(json: String?): Call {
        // Too big for the bridge: say so, with the fix (docs/applet-dev-experience.md B1), not "needs an object".
        val size = json?.toByteArray(Charsets.UTF_8)?.size ?: 0
        if (size > CardRules.RESPOND_MAX_BYTES) refuse("request_too_big",
            "A card's request to the phone is at most ${CardRules.RESPOND_MAX_BYTES / 1024} KB; this one is ${(size + 1023) / 1024} KB. " +
                "Don't send photo bytes (data_base64): take the photo with store: [\"memory\"] and send its capture_id, or send a gallery_id.")
        val o = CardRules.parseRespond(json) as? JSONObject
            ?: refuse("invalid_parameter", "Bridgit.act needs an object like {method, path, body}.")
        val raw = o.optString("path", "").trim()
        if (raw.isEmpty()) refuse("invalid_parameter", "Bridgit.act needs a path, e.g. \"/v1/flashlight\".")
        if (raw.contains('?') || raw.contains('%') || raw.contains("..") || raw.contains('#'))
            refuse("invalid_parameter", "path must be a plain endpoint path; put query values in query.")
        val path = when {
            raw.startsWith("/v1/") -> raw
            raw.startsWith("v1/") -> "/$raw"
            raw.startsWith("/") -> "/v1$raw"
            else -> "/v1/$raw"
        }.trimEnd('/')
        val body = when (val b = o.opt("body")) {
            null, JSONObject.NULL -> JSONObject()
            is JSONObject -> b
            else -> refuse("invalid_parameter", "body must be an object.")
        }
        val method = o.optString("method", "").trim().uppercase().ifEmpty { if (o.has("body")) "POST" else "GET" }
        if (method != "GET" && method != "POST" && method != "DELETE" && method != "PUT" && method != "PATCH")
            refuse("invalid_parameter", "method must be GET, POST, PUT, PATCH or DELETE.")
        val query = LinkedHashMap<String, String>()
        when (val q = o.opt("query")) {
            null, JSONObject.NULL -> {}
            is JSONObject -> q.keys().forEach { k -> query[k] = q.get(k).let { v -> if (v == JSONObject.NULL) "" else v.toString() } }
            else -> refuse("invalid_parameter", "query must be an object.")
        }
        return Call(method, path, query, body)
    }

    /**
     * The endpoint [call] names, with its path parameters. Refuses unknown paths, endpoints that take no
     * key (pairing) and MCP, which a card has no use for.
     */
    fun match(specs: List<EndpointSpec>, call: Call): Pair<EndpointSpec, Map<String, String>> {
        val b = call.path.split('/')
        for (spec in specs) {
            if (spec.method != call.method) continue
            val a = spec.fullPath.split('/')
            if (a.size != b.size) continue
            val params = LinkedHashMap<String, String>()
            val ok = a.indices.all { i ->
                if (a[i].startsWith('{')) { params[a[i].trim('{', '}')] = b[i]; b[i].isNotEmpty() } else a[i] == b[i]
            }
            if (!ok) continue
            if (!spec.auth || spec.path == "/mcp") refuse("not_available", "${spec.method} ${spec.fullPath} can't be called from a card.")
            return spec to params
        }
        refuse("not_found", "No endpoint ${call.method} ${call.path}. GET /v1/guide lists them.")
    }

    /** What the page gets back: {ok, status, body}. JSON replies are parsed; anything else is summarised. */
    /** [toPage]: the reply goes to a card or chat app page (64 KB at most); false for watch rules, which have no page. */
    fun result(status: Int, contentType: String, bytes: ByteArray, toPage: Boolean = true): JSONObject {
        val ok = status in 200..299
        val json = contentType.startsWith("application/json")
        // A JSON reply too big for the page used to come back ok with only its size: now it says it didn't fit.
        if (toPage && ok && json && bytes.size > PAGE_BODY_MAX_BYTES) return JSONObject().put("ok", false).put("status", status).put("error", "reply_too_big")
            .put("body", JSONObject().put("error", "reply_too_big").put("bytes", bytes.size)
                .put("message", "The reply is ${(bytes.size + 1023) / 1024} KB, over the ${PAGE_BODY_MAX_BYTES / 1024} KB a card can receive, so the call ran but its result can't reach the card.")
                .put("fix", "Ask for less: a limit on lists, max_edge_px of 1024 or less on photos, raw with a small size on captures, or keep the photo on the phone and pass its capture_id."))
        val body: Any = if (json && bytes.size <= PAGE_BODY_MAX_BYTES)
            runCatching { org.json.JSONTokener(String(bytes, Charsets.UTF_8)).nextValue() }.getOrNull() ?: JSONObject.NULL
        else JSONObject().put("content_type", contentType).put("bytes", bytes.size)
        val out = JSONObject().put("ok", ok).put("status", status).put("body", body)
        // API errors are {"error": "<code>", "message", "request_id"} (ApiError.toJson).
        if (!ok) out.put("error", ((body as? JSONObject)?.opt("error") as? String)?.takeIf { it.isNotEmpty() } ?: "http_$status")
        return out
    }

    /** A call refused before it reached an endpoint. */
    fun refused(code: String, message: String, status: Int = 400): JSONObject = JSONObject().put("ok", false).put("status", status)
        .put("error", code).put("body", JSONObject().put("error", code).put("message", message))

    /** The action.executed event the agent gets for every call. */
    fun event(interactionId: Long, call: Call?, result: JSONObject): JSONObject = JSONObject()
        .put("interaction_id", interactionId)
        .put("method", call?.method ?: JSONObject.NULL).put("path", call?.path ?: JSONObject.NULL)
        .put("status", result.optInt("status")).put("ok", result.optBoolean("ok"))
        .put("error", result.optString("error").ifEmpty { null } ?: JSONObject.NULL)

    /** The script that hands [result] for call [ref] back to the page (Promise and bridgit:acted event). */
    fun deliverScript(ref: Int, result: JSONObject): String {
        val payload = JSONObject(result.toString()).put("ref", ref).toString()
        return "(function(){var r=$payload;try{window.__bridgitActed&&window.__bridgitActed(r)}catch(e){}" +
            "try{window.dispatchEvent(new CustomEvent('bridgit:acted',{detail:r}))}catch(e){}})();"
    }
}

/** Hands out a card's call refs and counts its calls in flight ([CardActions.MAX_IN_FLIGHT]). Thread-safe. */
class ActGate(private val max: Int = CardActions.MAX_IN_FLIGHT) {
    private var running = 0
    private var next = 0
    @Synchronized fun nextRef(): Int = ++next
    /** True if another call may start now (then [finish] it). */
    @Synchronized fun start(): Boolean { if (running >= max) return false; running++; return true }
    @Synchronized fun finish() { if (running > 0) running-- }
    val inFlight: Int @Synchronized get() = running
}
