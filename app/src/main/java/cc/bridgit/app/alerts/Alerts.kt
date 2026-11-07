package cc.bridgit.app.alerts

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.log.LogEntry
import java.util.concurrent.TimeUnit

/**
 * Email alerts (Settings › Email alerts): what's pending, the relay, and the WorkManager jobs
 * that send. A one-time job is queued for the exact moment something becomes due (the end of a
 * 10-minute burst, or the next digest boundary) when a call arrives. A 15-minute periodic job is
 * the safety net: it catches anything a one-time job missed (a call that arrived while a job was
 * running, a reboot, Doze). Both just run [AlertDispatcher.run], which is idempotent.
 */
class Alerts(private val app: BridgeApp) {
    val store = AlertStore(app)
    val relay = AlertRelay(app)
    val dispatcher = AlertDispatcher(store, relay,
        frequency = { AlertFrequency.of(app.settings.alertFrequency) },
        confirmed = { app.settings.alertState == STATE_CONFIRMED },
        onGone = {
            app.settings.alertState = STATE_NONE
            app.log.system("audit", "setting", "Email alerts stopped: bridgit.cc says the address is unsubscribed or unconfirmed")
        },
        phone = { android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "your phone" })

    /** Set while a job is queued, so recording a call doesn't touch WorkManager every time. */
    @Volatile private var queued = false

    /** Hooked to the log: every agent API call (HTTP or MCP). Runs on the log writer thread. */
    fun onLog(e: LogEntry) {
        if (e.channel != "audit" || e.kind != "request" || e.agentId == null) return
        val kept = dispatcher.record(AlertEvent(ts = e.ts, agentId = e.agentId, agent = e.agent ?: e.agentId,
            method = e.method ?: "", endpoint = e.endpoint ?: "", http = e.http ?: 0, message = e.message))
        if (kept && !queued) dispatcher.nextCheck()?.let { schedule(it, replace = false) }
    }

    // ---- Owner email verification (EmailGate) ----

    /** Where the owner stands now. Doesn't start the clock. */
    fun gate(now: Long = System.currentTimeMillis()): EmailGate.View {
        // Installs that confirmed alerts before verification existed count as verified.
        if (app.settings.emailVerifiedAt <= 0L && app.settings.alertState == STATE_CONFIRMED && app.settings.alertEmail != null)
            app.settings.emailVerifiedAt = now
        return EmailGate.evaluate(now, app.settings.emailGraceStartedAt, app.settings.emailVerifiedAt > 0L,
            app.settings.alertState, app.settings.alertEmail != null)
    }

    /**
     * Every agent call goes through here (ApiServer.requireGrant, AgentPlatform.read/mutate). The first one
     * starts the 24-hour clock. After it, only [exempt] calls go through until the owner confirms an address.
     */
    fun checkAgentCall(exempt: Boolean) {
        if (app.settings.emailGraceStartedAt <= 0L) app.settings.emailGraceStartedAt = System.currentTimeMillis()
        val v = gate()
        if (!v.blocked) return
        if (v.hasEmail) refreshVerificationSoon()
        if (exempt) return
        throw cc.bridgit.app.api.ErrorCatalog.error("email_required", EmailGate.message(v),
            mapOf("state" to v.state, "fix" to "POST /v1/owner/email", "status" to "GET /v1/owner/email"))
    }

    @Volatile private var lastRefresh = 0L

    /** At most once a minute, in the background: ask bridgit.cc whether the link was clicked. */
    fun refreshVerificationSoon() {
        val now = System.currentTimeMillis()
        if (now - lastRefresh < 60_000) return
        lastRefresh = now
        Thread({ runCatching { refreshVerification() } }, "bridgit-email-check").start()
    }

    /** Asks bridgit.cc now (network; call off the main thread). Returns the state afterwards. */
    fun refreshVerification(): String {
        val email = app.settings.alertEmail
        if (!EmailVerificationRules.shouldRefresh(email, app.settings.alertState)) return app.settings.alertState
        val state = relay.status(email)
        // An address changed while the network request was in flight cannot inherit the old result.
        if (app.settings.alertEmail != email) return app.settings.alertState
        when (state) {
            AlertRelay.State.CONFIRMED -> markConfirmed()
            AlertRelay.State.NONE -> app.settings.alertState = STATE_NONE
            AlertRelay.State.PENDING -> app.settings.alertState = STATE_PENDING
            else -> {}
        }
        return app.settings.alertState
    }

    /** The address was confirmed. If the owner never chose how often, alerts start as a daily digest. */
    fun markConfirmed() {
        if (app.settings.emailVerifiedAt <= 0L) app.settings.emailVerifiedAt = System.currentTimeMillis()
        if (app.settings.alertState == STATE_CONFIRMED) return
        app.settings.alertState = STATE_CONFIRMED
        app.log.system("audit", "setting", "The owner's email address is verified")
        if (!app.settings.alertFrequencyChosen) {
            app.settings.alertFrequency = AlertFrequency.DAILY.id
            app.log.system("audit", "setting", "Email alerts start as a daily summary (change in Settings › Email alerts)")
        }
        runCatching { poke() }
    }

    /**
     * Sends the confirmation email (network; call off the main thread). Used by the owner's screens and by
     * agents (POST /v1/owner/email). Never logs the address: agents can read the log.
     */
    private val enrollment by lazy { EmailEnrollment(app.settings, relay::subscribe) { markConfirmed() } }

    fun removeAddress(authorize: () -> Unit = {}): Boolean = enrollment.remove(relay::unsubscribe, authorize)

    fun subscribe(email: String, by: String, authorize: () -> Unit = {}): AlertRelay.Subscribe {
        val r = enrollment.subscribe(email, authorize)
        authorize()
        if (app.settings.alertEmail != email.trim().lowercase()) return AlertRelay.Subscribe.FAILED
        if (r == AlertRelay.Subscribe.PENDING) {
            app.log.system("audit", "setting", "$by set the owner's email address (waiting for confirmation)")
        }
        if (r != AlertRelay.Subscribe.BAD_EMAIL) runCatching { poke() }
        return r
    }

    /** At startup and after every settings change: keep the safety net in step and look again now. */
    fun poke() {
        val wm = WorkManager.getInstance(app)
        if (EmailVerificationRules.shouldRefresh(app.settings.alertEmail, app.settings.alertState) &&
            AlertFrequency.of(app.settings.alertFrequency) == AlertFrequency.NONE) {
            // No alerts yet, but keep checking every 15 minutes whether the owner clicked the link.
            wm.cancelUniqueWork(WORK)
            queued = false
            store.clear()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES).setConstraints(network()).build())
            return
        }
        if (AlertFrequency.of(app.settings.alertFrequency) == AlertFrequency.NONE) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(WORK)
            queued = false
            store.clear()
            return
        } else {
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES).setConstraints(network()).build())
        }
        schedule(System.currentTimeMillis(), replace = true)
    }

    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun schedule(at: Long, replace: Boolean) {
        queued = true
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<AlertWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(network())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    /** One dispatcher pass. Afterwards the next arriving call queues a fresh one-time job. */
    internal fun runOnce(): AlertDispatcher.Outcome {
        queued = false
        // The owner may confirm from the email without reopening Settings, so the job checks too.
        // Checked even with alerts off: the same confirmation verifies the owner's email (EmailGate).
        if (EmailVerificationRules.shouldRefresh(app.settings.alertEmail, app.settings.alertState)) refreshVerification()
        if (!EmailVerificationRules.shouldRefresh(app.settings.alertEmail, app.settings.alertState) && AlertFrequency.of(app.settings.alertFrequency) == AlertFrequency.NONE)
            WorkManager.getInstance(app).cancelUniqueWork(PERIODIC)
        return dispatcher.run()
    }

    companion object {
        const val WORK = "bridgit-email-alerts"
        const val PERIODIC = "bridgit-email-alerts-periodic"
        const val STATE_NONE = "none"
        const val STATE_PENDING = "pending"
        const val STATE_CONFIRMED = "confirmed"
    }
}

class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        when ((applicationContext as BridgeApp).alerts.runOnce()) {
            AlertDispatcher.Outcome.Retry -> Result.retry()
            else -> Result.success()
        }
    }
}
