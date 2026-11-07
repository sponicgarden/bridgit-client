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
 * Appendix C, as code. The guide and openapi.json are generated from this list,
 * and a unit test checks every code here is in the spec table and vice versa.
 * [built] = Bridgit can return it in this release.
 */
object ErrorCatalog {
    data class Entry(val http: Int, val code: String, val meaning: String, val agentShould: String, val built: Boolean = true)

    val all = listOf(
        Entry(503, "model_unavailable", "The selected registered model is unavailable or incompatible", "Read the effective model preference; clear the override to inherit a valid default"),
        Entry(403, "account_required", "A verified linked account is required", "Link this phone to the owner account"),
        Entry(429, "image_quota_exceeded", "The account used its 40 monthly images", "Wait until quota.resets_at"),
        Entry(409, "image_in_progress", "Image request is pending or its outcome is unknown", "Retry only with the same Idempotency-Key"),
        Entry(409, "image_refused", "Azure refused the request without using an image slot", "Correct the request before starting a new generation"),
        Entry(409, "idempotency_conflict", "This image key was used for different parameters", "Use identical parameters for retries"),
        Entry(502, "image_provider_failed", "Azure could not generate the image", "Inspect provider_status; never retry an unknown outcome with a new key"),
        Entry(502, "image_outcome_unknown", "Image generation outcome could not be confirmed", "Retry only with the same Idempotency-Key"),
        Entry(503, "images_unavailable", "The configured Azure image task is unavailable", "Check the configured model task and Azure deployment"),
        Entry(422, "speech_no_match", "No speech was recognized during this bounded turn", "Start a new turn when ready to speak"),
        Entry(503, "speech_unavailable", "Selected speech engine or backend is unavailable", "Check GET /v1/speech/models; no implicit fallback"),
        Entry(403, "speech_cloud_consent_required", "Selected speech model needs explicit cloud permission", "Ask the owner before allow_cloud=true"),
        Entry(409, "speech_model_changed", "Registry selection changed during this turn", "Resolve again before a new turn"),
        Entry(400, "bad_request", "Malformed HTTP or JSON", "Fix the request"),
        Entry(401, "unauthorized", "Key missing or wrong; `reason` = `key_rotated` or `agent_removed` when Bridgit knows", "Stop; tell the owner the key needs replacing"),
        Entry(403, "forbidden_source", "Caller is not on the tailnet", "Check Tailscale"),
        Entry(403, "claim_required", "This Bridgit Portal pairing link expired before it was claimed (relay)", "Update Bridgit Portal and pair again with a new link"),
        Entry(403, "signed_requests_required", "The owner allows only sealed (signed) relay requests for agents that can control the phone (relay)", "Switch to sealed mode, or ask the owner"),
        Entry(403, "wrong_device", "This agent's key is locked to another Tailscale device (device lock)", "Call from the device you were set up on, or ask the owner to allow this device; don't retry"),
        Entry(403, "capability_disabled", "Master switch off", "Tell the owner which switch (`setting` field)"),
        Entry(403, "permission_denied", "Android permission not granted (`permission` field)", "Tell the owner; More > Hardware access fixes it"),
        Entry(403, "access_needed", "Android special access off (`setting` field)", "Tell the owner; Bridgit also notifies them"),
        Entry(403, "pairing_closed", "No pairing window open", "Ask the owner to open one"),
        Entry(403, "screen_denied", "The owner denied screen access", "Don't ask again soon", built = false),
        Entry(403, "consent_expired", "No active screen-sharing grant", "Ask for a new owner-approved grant"),
        Entry(403, "setting_locked", "Agents can't change this setting (`key`, `reason`: gate, disconnects, owner_only, android or status)", "Ask the owner to change it on the phone"),
        Entry(403, "email_required", "The first 24 hours are over and the owner hasn't verified an email (`state`: none or pending)", "Send the owner's address with POST /v1/owner/email, then ask them to tap the link in the email"),
        Entry(403, "agent_not_allowed", "This agent lacks a capability grant", "Ask the owner to change agent permissions"),
        Entry(404, "not_found", "Unknown endpoint or id", "Fix the request"),
        Entry(404, "row_not_found", "That row of the applet's table isn't there (deleted, or dropped past max_rows or keep_days)", "Read the rows again; don't retry unchanged"),
        Entry(404, "photo_not_found", "Gallery item gone or outside access", "Refresh the list"),
        Entry(409, "sensor_busy", "Sensor in use or capture running", "Retry once after a few seconds"),
        Entry(409, "device_locked", "Needs the phone unlocked", "Ask the owner to unlock"),
        Entry(409, "share_unavailable", "Android's share sheet couldn't open (Bridgit isn't on screen)", "Ask the owner to open Bridgit and tap the button again"),
        Entry(413, "payload_too_large", "Over 12 MB base64 (40 MB raw)", "Ask for a smaller size (`photo_id` given)"),
        Entry(413, "export_too_large", "An export exceeds its row, photo or 20 MB size limit", "Use paginated applet rows and individual saved photos, or filter a share report; don't retry unchanged"),        Entry(422, "invalid_parameter", "Bad value", "Fix; never retry unchanged"),
        Entry(422, "not_supported", "Android doesn't allow this (for example Wi-Fi toggle)", "Don't retry"),
        Entry(429, "rate_limited", "Over a limit (`retry_after_s`)", "Wait"),
        Entry(429, "budget_reached", "An installed applet's own spending cap for cloud model calls is used up (day or month)", "Wait for the next day or month, or ask the owner to raise the applet's limit in Installed applets"),
        Entry(429, "applet_budget", "An installed applet's budget is used up for this period on all the owner's phones (`applet`, `limit`, `resets_at`)", "Don't retry before resets_at; an agent with Budgets can top it up or raise the limit (PUT /v1/applets/{id}/budget)"),
        Entry(403, "agents_may_not_change", "The owner turned off agent changes to this applet's budget", "Ask the owner; don't retry"),
        Entry(403, "over_ceiling", "The new limit (or limit plus top-ups) would pass the owner's ceiling for agents (`ceiling`)", "Stay within the ceiling, or ask the owner to raise it"),
        Entry(400, "out_of_range", "every_s is outside the action's min_s..max_s", "Pick a value within min_s..max_s (GET /v1/applets/{id}/budget)"),
        Entry(429, "too_many_open", "Too many open questions/notices", "Wait for answers or cancel some"),
        Entry(403, "notifications_required", "Bridgit's notifications are off, so captures are refused", "Tell the owner to turn notifications on"),
        Entry(403, "forbidden", "Not allowed for this agent (`reason`, for example `fixer_mode_off` or `super_powers_off`: the owner hasn't given you Bridgit Fixer Mode or Agent Super Powers; `agent_paused`: the owner paused you)", "Don't retry; for `agent_paused`, wait until the owner resumes you"),
        Entry(409, "in_progress", "Same `Idempotency-Key` still running", "Wait, then retry with the same key"),
        Entry(409, "conflict", "The interaction isn't in a state for this (for example a text panel that's closed or finished)", "Read it with GET /v1/interactions/{id}; start a new one if needed"),
        Entry(409, "name_taken", "That Device Domain Name belongs to another Bridgit (`lookup`)", "Suggest another name; don't retry unchanged"),
        Entry(409, "already_named", "This Bridgit already has its Device Domain Name for good (`name`)", "Use that name; change only label or visible"),
        Entry(409, "idempotency_expired", "Same `Idempotency-Key` already done, but its media result is no longer replayed (15 min)", "Don't repeat; use a new key only if you really want a new capture"),
        Entry(429, "quota_exceeded", "Daily data, file storage or relay job quota used up (`limit`)", "Remove old transfers or wait for retention"),
        Entry(429, "too_many_waits", "Too many open `wait` requests", "Close some waits"),
        Entry(502, "upstream_failed", "A service Bridgit asks on your behalf (MET Norway for weather) failed and nothing was cached (`service`, `upstream_status` or `reason`)", "Retry in a few minutes"),
        Entry(502, "callback_failed", "Your callback URL didn't accept the test event (`http_status` or `reason` field)", "Check the receiver; the owner sees the host in Activity"),
        Entry(500, "internal_error", "Bridgit bug", "Report directly to Bridgit servers: https://bridgit.cc/learn/bugs.md"),
        Entry(503, "service_start_blocked", "Android hasn't given the service this sensor", "Ask the owner to open Bridgit once"),
        Entry(503, "model_not_ready", "An on-phone engine you named isn't downloaded yet (`engine`, `state`: downloading, waiting_for_wifi or failed, `size_mb`, `retry_after_s`); the request started the download", "Retry after retry_after_s; GET /v1/images/engines shows progress"),
        Entry(503, "team_unavailable", "The Bridgit team backend can't be reached", "Retry later, or register at the backend directly"),
        Entry(409, "capture_cancelled", "The owner cancelled, deleted or closed the capture review", "Wait for a new deliberate capture; do not retry automatically"),
        Entry(409, "viewfinder_required", "The visible camera viewfinder is required but cannot be shown for this action", "Open Bridgit and use the visible capture/review flow; do not fall back to hidden capture"),
        Entry(504, "capture_timeout", "Sensor didn't answer in time", "Retry once"),
        Entry(404, "unknown_target", "No satellite with that id (`target`); GET /v1/satellites lists them", "Fix the target, or leave it out to use this phone"),
        Entry(403, "target_not_granted", "The owner hasn't let this agent use that satellite", "Ask the owner (Devices > the satellite > Agents)"),
        Entry(403, "not_on_satellite", "This endpoint can't run on a satellite (yet), or the satellite doesn't offer it", "Leave out the target, or use another endpoint"),
        Entry(403, "not_shared", "The applet's package doesn't share that table with you (share_with_installer)", "Read the applet's card on the phone instead, or ship a revision that shares it"),
        Entry(503, "satellite_offline", "The satellite didn't answer (`last_seen`, `retry_after_s`)", "Retry later; the owner may need to check the satellite's Tailscale"),
        Entry(504, "phone_unreachable", "(Relay) phone didn't pick up the job", "Retry later", built = false),
    )

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
