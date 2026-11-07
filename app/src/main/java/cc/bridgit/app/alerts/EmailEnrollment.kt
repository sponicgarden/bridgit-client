package cc.bridgit.app.alerts

import android.util.Patterns
import cc.bridgit.app.core.Settings

/** A valid address belongs to the phone even if delivery of its confirmation link fails. */
class EmailEnrollment(private val settings: Settings, private val send: (String) -> AlertRelay.Subscribe, private val confirmed: () -> Unit) {
    constructor(settings: Settings, send: (String) -> AlertRelay.Subscribe) : this(settings, send, {})
    @Synchronized fun subscribe(address: String, authorize: () -> Unit = {}): AlertRelay.Subscribe {
        authorize()
        val email = address.trim().lowercase()
        if (email.length > 254 || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) return AlertRelay.Subscribe.BAD_EMAIL
        // Save before the network call: timeout, rate limiting, navigation and app restarts must not lose it.
        if (settings.alertEmail != email) settings.alertState = Alerts.STATE_NONE
        settings.alertEmail = email
        val result = runCatching { send(email) }.getOrDefault(AlertRelay.Subscribe.FAILED)
        authorize()
        if (settings.alertEmail != email) return AlertRelay.Subscribe.FAILED
        if (result == AlertRelay.Subscribe.CONFIRMED) confirmed()
        if (result == AlertRelay.Subscribe.PENDING && settings.alertState != Alerts.STATE_CONFIRMED) settings.alertState = Alerts.STATE_PENDING
        return result
    }

    /** Subscription and removal share one lifecycle lock, including backend request ordering. */
    @Synchronized fun remove(unsubscribe: () -> Boolean, authorize: () -> Unit = {}): Boolean {
        authorize()
        val before = settings.alertEmail ?: return true
        if (!unsubscribe()) return false
        authorize()
        if (settings.alertEmail != before) return false
        settings.alertEmail = null
        settings.alertState = Alerts.STATE_NONE
        return true
    }
}
