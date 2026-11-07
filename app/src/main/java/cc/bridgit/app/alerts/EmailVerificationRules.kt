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
}
