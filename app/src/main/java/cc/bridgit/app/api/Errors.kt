package cc.bridgit.app.api

import org.json.JSONObject

/** A structured API error (API-05). [code] must be in [ErrorCatalog]. */
class ApiError(val http: Int, val code: String, message: String, val extra: Map<String, Any?> = emptyMap()) :
    Exception(message) {
    fun toJson(requestId: String): JSONObject {
        val o = JSONObject().put("error", code).put("message", message).put("request_id", requestId)
        extra.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        // Every error says where to report problems, including when the phone itself is the problem.
        if (!o.has("report_problems")) o.put("report_problems", cc.bridgit.app.feedback.TeamKeys.noPhoneReport())
        return o
    }
}

/**
 * Appendix C. The guide and openapi.json are generated from this list, which is generated from the
 * Client Core error catalog; a unit test checks every code here is in the spec table and vice versa.
 * [built] = Bridgit can return it in this release.
 */
object ErrorCatalog {
    data class Entry(val http: Int, val code: String, val meaning: String, val agentShould: String, val built: Boolean = true)

    /** Generated from contracts/client-core/errors.json (tools/client-core/gen.mjs); edit the pack, not this list. */
    val all: List<Entry> = cc.bridgit.app.core.contract.ErrorCatalogData.ENTRIES

    private val byCode = all.associateBy { it.code }

    fun http(code: String): Int = byCode[code]?.http ?: error("Unknown error code $code")

    /** Builds an error whose HTTP status always matches the catalog. */
    fun error(code: String, message: String, extra: Map<String, Any?> = emptyMap()) = ApiError(http(code), code, message, extra)
}

/** API-12: "accepted, not done" (202). No P1a endpoint produces it yet; relay jobs and screen-share consent will. */
object Pending {
    fun body(code: String, id: String, pollUrl: String, requestId: String, retryAfterS: Int = 1): JSONObject {
        require(code == "consent_pending" || code == "job_queued")
        return JSONObject().put("pending", true).put("code", code).put("id", id).put("poll_url", pollUrl)
            .put("retry_after_s", retryAfterS).put("request_id", requestId)
    }
}
