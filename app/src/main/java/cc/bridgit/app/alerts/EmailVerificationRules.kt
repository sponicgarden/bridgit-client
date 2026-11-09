package cc.bridgit.app.alerts

/** Verification belongs to one install and its current address, never just to an email string. */
object EmailVerificationRules {
    fun shouldRefresh(email: String?, state: String) = !email.isNullOrBlank() && state != Alerts.STATE_CONFIRMED

    fun state(raw: String, email: String?, expectedEmail: String?): AlertRelay.State {
        if (raw == "none") return AlertRelay.State.NONE
        if (expectedEmail != null && !expectedEmail.equals(email, ignoreCase = true)) return AlertRelay.State.UNKNOWN
        return when (raw) {
            "confirmed" -> AlertRelay.State.CONFIRMED
            "pending" -> AlertRelay.State.PENDING
            else -> AlertRelay.State.UNKNOWN
        }
    }

    /** A refused confirmation email: [limit] and [message] come from bridgit.cc, [retryAfterS] is the real wait. */
    data class RateLimit(val limit: String?, val retryAfterS: Int, val message: String?)

    const val DEFAULT_RETRY_AFTER_S = 300

    /** Reads bridgit.cc's 429 body (`limit`, `retry_after_s`, `error`), falling back to Retry-After, then five minutes. */
    fun rateLimit(body: String, retryAfterHeader: String?): RateLimit {
        val json = runCatching { org.json.JSONObject(body) }.getOrNull()
        val wait = json?.optInt("retry_after_s", 0)?.takeIf { it > 0 }
            ?: retryAfterHeader?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_RETRY_AFTER_S
        val limit = json?.optString("limit", "")?.takeIf { Regex("[a-z_]{1,40}").matches(it) }
        val message = json?.optString("error", "")?.trim()?.takeIf { it.isNotEmpty() }?.take(400)
        return RateLimit(limit, wait.coerceAtMost(2 * 86_400), message)
    }
}
