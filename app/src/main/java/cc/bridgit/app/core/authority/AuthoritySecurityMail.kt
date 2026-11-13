package cc.bridgit.app.core.authority

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Email delivery of the alert outbox (architecture §4.7: approver alerts also go to the owner's email). Each
 * [AuthorityOutbox.Way.EMAIL] row is sent to bridgit.cc `POST /api/alerts/security`, oldest first, and marked
 * once bridgit.cc gives a final answer.
 *
 * What is sent is built here from three stored values and nothing else: the event id, the kind and the time
 * (plus the clear time of a recovery). The outbox body (who, which approvers, which requests) never leaves.
 *
 * The event id is the row id and the row's time, both stored with the change, so a retry after a restart
 * repeats the same id and bridgit.cc sends one mail.
 *
 * No request is made inside a store transaction: rows are read, sent, then marked.
 */
class AuthoritySecurityMail(
    private val store: AuthorityStore,
    private val time: AuthorityTime,
    private val transport: () -> Transport,
    /** The owner alert email is set and confirmed. While false nothing is sent and rows wait. */
    private val ready: () -> Boolean,
    /** A line for the diagnostic log. Never the request, the token or the outbox body. */
    private val log: (String) -> Unit = {},
) {
    /** One POST of [body] to the security mail endpoint, with this install's token. Throws when bridgit.cc can't be reached. */
    fun interface Transport { fun post(body: ByteArray): Reply }

    class Reply(val status: Int, val body: String = "", val retryAfter: String? = null)

    /** What bridgit.cc's answer means for the row. */
    sealed interface Outcome {
        object Sent : Outcome
        /** Final: this event will never be mailed. */
        data class Rejected(val code: String) : Outcome
        /** The address isn't set or confirmed there (or this install is unknown): the row is kept. */
        data class Hold(val code: String) : Outcome
        /** Try again; after [afterMs] when bridgit.cc named a time. */
        data class Retry(val afterMs: Long?) : Outcome
    }

    enum class Pass { OFF, NOT_READY, BUSY, WAITING, IDLE, DONE, STOPPED }

    private val running = AtomicBoolean(false)
    private var notBeforeMs = 0L
    private var failures = 0
    private var held = false

    /** True while a row waits for a later try (bridgit.cc unreachable, limited, or the address not confirmed there). */
    @Volatile var owing = false
        private set

    /**
     * One pass over the waiting rows. [enabled] is the approvers switch: off sends nothing, except [afterOff],
     * the pass that carries the "approvers turned off" mail out after the switch went off.
     */
    fun run(enabled: Boolean, afterOff: Boolean = false): Pass {
        if (!enabled && !afterOff) return Pass.OFF
        if (!running.compareAndSet(false, true)) return Pass.BUSY
        try {
            if (!ready()) {
                // The owner is setting or confirming the address: the next pass after that tries at once.
                if (held) clearBackoff()
                return Pass.NOT_READY
            }
            val now = time.elapsedMs()
            // A wait longer than any this class sets means the device restarted and its clock began again.
            if (now < notBeforeMs && notBeforeMs - now <= MAX_WAIT_MS) return Pass.WAITING
            val rows = store.undelivered(AuthorityOutbox.Way.EMAIL)
            for (row in rows) {
                val request = request(row)
                if (request == null) { store.notSent(row.id, AuthorityOutbox.Way.EMAIL, AuthorityOutbox.SKIPPED); continue }
                val outcome = try { outcome(transport().post(request.toString().toByteArray())) } catch (e: Exception) { Outcome.Retry(null) }
                when (outcome) {
                    Outcome.Sent -> { store.delivered(row.id, AuthorityOutbox.Way.EMAIL); clearBackoff() }
                    is Outcome.Rejected -> {
                        store.notSent(row.id, AuthorityOutbox.Way.EMAIL, AuthorityOutbox.REJECTED)
                        log("Security mail not sent: ${row.kind} refused (${outcome.code})")
                    }
                    is Outcome.Hold -> { waitFor(HOLD_MS, hold = true); log("Security mail waits: ${outcome.code}"); return Pass.STOPPED }
                    is Outcome.Retry -> {
                        failures++
                        waitFor(outcome.afterMs ?: (FIRST_RETRY_MS shl (failures - 1).coerceAtMost(10)).coerceAtMost(MAX_RETRY_MS), hold = false)
                        return Pass.STOPPED
                    }
                }
            }
            owing = false
            return if (rows.isEmpty()) Pass.IDLE else Pass.DONE
        } finally {
            running.set(false)
        }
    }

    private fun waitFor(ms: Long, hold: Boolean) {
        notBeforeMs = time.elapsedMs() + ms.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
        held = hold
        owing = true
    }

    private fun clearBackoff() { notBeforeMs = 0; failures = 0; held = false }

    companion object {
        /** The kinds bridgit.cc mails. Any other outbox kind is marked not emailed without a request. */
        val KINDS = setOf(
            "approver_enrolled", "approver_added", "approver_removed", "recovery_started", "recovery_daily", "recovery_cancelled",
            "recovery_cleared", "recovery_cancel_late", "switch_off_requested", "approvers_off", "approvers_on",
        )
        private val WITH_CLEAR_TIME = setOf("recovery_started", "recovery_daily")
        private val CODE = Regex("[a-z_]{1,40}")

        const val FIRST_RETRY_MS = 60_000L
        const val MAX_RETRY_MS = 60 * 60_000L
        const val HOLD_MS = 60 * 60_000L
        const val MIN_WAIT_MS = 5_000L
        const val MAX_WAIT_MS = 24 * 60 * 60_000L

        /** The same for a row on every try, before and after a restart: bridgit.cc sends one mail per id. */
        fun eventId(row: AuthorityOutbox.Alert): String = "${row.id}-${row.atMs}"

        /** The whole request for [row], or null when its kind is not mailed. Only these fields; never the outbox body. */
        fun request(row: AuthorityOutbox.Alert): JSONObject? {
            if (row.kind !in KINDS) return null
            val out = JSONObject().put("event_id", eventId(row)).put("kind", row.kind).put("at_ms", row.atMs)
            if (row.kind in WITH_CLEAR_TIME) row.body.optLong("clear_at").takeIf { it > 0 }?.let { out.put("clear_at_ms", it) }
            return out
        }

        fun outcome(r: Reply): Outcome {
            val json = runCatching { JSONObject(r.body) }.getOrNull()
            val code = json?.optString("error").orEmpty().takeIf { CODE.matches(it) } ?: "http_${r.status}"
            return when (r.status) {
                200 -> Outcome.Sent // the contract: 200 is delivered, whatever the body says (sent, duplicate, or nothing)
                400, 413, 415 -> Outcome.Rejected(code)
                401, 409, 410 -> Outcome.Hold(code)
                429 -> Outcome.Retry((json?.optLong("retry_after_s")?.takeIf { it > 0 } ?: r.retryAfter?.trim()?.toLongOrNull()?.takeIf { it > 0 })?.let { it.coerceAtMost(MAX_WAIT_MS / 1000) * 1000 })
                else -> Outcome.Retry(null)
            }
        }
    }
}
