package cc.bridgit.app.alerts

/**
 * Owner email verification (owner request 2 Nov 2026). Nothing is needed before agents start:
 * the first agent call starts a 24-hour clock. After that, agents can only use the status
 * endpoints, filing feedback and the owner-email endpoints until the owner confirms an address
 * from the email bridgit.cc sends. The confirmed address is the same one email alerts use.
 * Pure, so it can be unit tested.
 */
object EmailGate {
    const val REMINDER_DELAY_MS = 60L * 60 * 1000
    const val GRACE_MS = 24L * 60 * 60 * 1000

    enum class Status { VERIFIED, GRACE, REQUIRED }

    /**
     * [graceEndsAt] is null until the clock has started (no agent call yet).
     * [state] is "none", "pending" or "confirmed", as in Settings.alertState.
     */
    data class View(val status: Status, val state: String, val graceEndsAt: Long?, val remainingMs: Long, val hasEmail: Boolean) {
        val blocked get() = status == Status.REQUIRED
        // The same persisted first-call clock survives app restarts. Never nag during the first hour.
        val showReminder get() = status != Status.VERIFIED && !hasEmail && graceEndsAt != null &&
            remainingMs <= GRACE_MS - REMINDER_DELAY_MS
    }

    /**
     * [verified] stays true once any address was confirmed, even if the owner later stops alerts or removes the
     * address: stopping the summaries never locks Bridgit again.
     */
    fun evaluate(now: Long, graceStartedAt: Long, verified: Boolean, alertState: String, hasEmail: Boolean): View {
        val state = if (!hasEmail) Alerts.STATE_NONE else alertState
        val ends = graceStartedAt.takeIf { it > 0 }?.plus(GRACE_MS)
        return when {
            verified || state == Alerts.STATE_CONFIRMED -> View(Status.VERIFIED, state, ends, 0, hasEmail)
            ends == null -> View(Status.GRACE, state, null, GRACE_MS, hasEmail)
            now < ends -> View(Status.GRACE, state, ends, ends - now, hasEmail)
            else -> View(Status.REQUIRED, state, ends, 0, hasEmail)
        }
    }

    /** Capabilities agents keep while blocked: finding out why, fixing it, reporting problems, and the owner's own read-only iPhone & iPad app. */
    val EXEMPT_CAPABILITIES = setOf("status", "feedback", "portal")

    fun exempt(capability: String) = capability in EXEMPT_CAPABILITIES

    /** v2 platform tools agents keep while blocked (read-only discovery). */
    val EXEMPT_TOOLS = setOf("capabilities", "events", "operation.get", "feedback.create")

    /** The one description of what keeps working after the deadline, built from the two lists above.
     *  Every agent doc that mentions the deadline uses it, so they can't disagree. */
    val DOC_SUMMARY get() = "the " + words(EXEMPT_CAPABILITIES.toList()) +
        " endpoints (such as health, me, owner email, capabilities, guide, demo, samples, openapi, changes, `/v1/issues` and the owner's read-only iPhone & iPad app), " +
        "the control tools " + words(EXEMPT_TOOLS.map { "`$it`" }) + " (`events` still needs `events.read`), " +
        "and the routes that are not tool calls (`/v2/capabilities`, `/v2/tools`, `/v2/guide`, operation cancel)"

    private fun words(xs: List<String>) = if (xs.size < 2) xs.joinToString() else xs.dropLast(1).joinToString(", ") + " and " + xs.last()

    /** The agent-facing message for a blocked call. */
    fun message(v: View): String = when (v.state) {
        Alerts.STATE_PENDING -> "Bridgit's first 24 hours are over and the owner hasn't confirmed their email yet. " +
            "Ask them to tap the link in the email from bridgit.cc (check spam), or send a new one with POST /v1/owner/email."
        else -> "Bridgit's first 24 hours are over and the owner hasn't verified an email address. Ask them for it in your chat (cards are paused too), send it " +
            "with POST /v1/owner/email {\"email\":\"...\"}, then ask them to tap the link in the email from bridgit.cc. " +
            "The owner can also add it on the phone (Home or Settings › Email alerts)."
    }

    /** "w***@gmail.com": enough for an agent to say which address, without handing every agent the full address. */
    fun mask(email: String?): String? {
        if (email.isNullOrBlank()) return null
        val at = email.indexOf('@')
        if (at <= 0) return "***"
        return email.first() + "***" + email.substring(at)
    }
}
