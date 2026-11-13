package cc.bridgit.app.core.authority

import cc.bridgit.app.core.authority.AuthorityStore.Outcome
import cc.bridgit.app.core.authority.AuthorityStore.Recovery
import cc.bridgit.app.core.authority.AuthorityStore.Refusal

/**
 * Approver recovery, the contract of architecture §4.7 (review 2 N8; D16b): when the last approver is
 * lost, a request to clear the approver list takes effect after 72 hours unless a listed approver cancels.
 *
 *     none -> pending (start) -> cancelled (a listed approver, or the starter) -> none
 *                             -> cleared (the wait ends) -> none (a new approver is enrolled)
 *
 * The wait ends only when 72 hours of running time have been counted AND the wall clock is past the clear
 * time ([RunningClock.Wait]). The clear is done by whichever store call first sees the wait over
 * ([settle], run at the start of every [AuthorityStore.tx]), so a cancel that arrives after that moment
 * finds the list already empty, however long the device sat idle in between.
 *
 * `cancelled` is kept as the last outcome (who stopped it, when) and is treated as `none` by [start].
 * Each operation also exists taking an [AuthorityStore.Tx], to run inside a larger store transaction.
 */
class RecoveryMachine(private val store: AuthorityStore) {
    fun status(): Recovery = store.tx { it.recovery() }
    fun start(by: String, emailVerified: Boolean): Outcome<Recovery> = store.tx { start(it, by, emailVerified) }
    fun cancelByApprover(approver: String): Outcome<Recovery> = store.tx { cancelByApprover(it, approver) }
    fun withdraw(by: String): Outcome<Recovery> = store.tx { withdraw(it, by) }

    companion object {
        const val NONE = "none"
        const val PENDING = "pending"
        const val CANCELLED = "cancelled"
        const val CLEARED = "cleared"

        /** How often a waiting recovery is announced again. */
        const val DAILY_MS = 24L * 60 * 60 * 1000

        /**
         * Starts the 72-hour wait. [by] is who asked (an agent's id, or the app). Needs a verified alert
         * email, a list that is not empty and no recovery running. Starting again while one is pending
         * returns that record: the clock does not restart and the generation does not rise again.
         */
        fun start(tx: AuthorityStore.Tx, by: String, emailVerified: Boolean): Outcome<Recovery> {
            val now = tx.recovery()
            if (now.state == PENDING) return Outcome.Ok(now)
            if (!emailVerified) return Outcome.Refused(Refusal.OWNER_EMAIL_REQUIRED)
            val listed = tx.approvers().map { it.id }
            if (listed.isEmpty()) return Outcome.Refused(Refusal.EMPTY_LIST)
            val wait = RunningClock.Wait.starting(tx.countedMs, tx.nowMs, AuthorityLimits.RECOVERY_WAIT_MS)
            tx.setRecovery(PENDING, startedBy = by, wait = wait)
            tx.raise(AuthorityGeneration.Reason.RECOVERY_START, by)
            tx.emit(AuthorityEvent.RecoveryStarted(by, wait.wallEndMs, listed))
            return Outcome.Ok(tx.recovery())
        }

        /** A `recovery_cancel` whose signature by [approver] the caller verified. The approver must still be listed. */
        fun cancelByApprover(tx: AuthorityStore.Tx, approver: String): Outcome<Recovery> =
            cancel(tx, approver) { if (tx.approver(approver) == null) Refusal.UNKNOWN_APPROVER else null }

        /** The starter withdrawing its own request. */
        fun withdraw(tx: AuthorityStore.Tx, by: String): Outcome<Recovery> = cancel(tx, by) { r -> if (r.startedBy != by) Refusal.NOT_STARTER else null }

        private fun cancel(tx: AuthorityStore.Tx, by: String, allowed: (Recovery) -> Refusal?): Outcome<Recovery> {
            val now = tx.recovery()
            return when (now.state) {
                CLEARED -> {
                    tx.emit(AuthorityEvent.RecoveryCancelLate(by))
                    Outcome.Refused(Refusal.RECOVERY_COMPLETE)
                }
                PENDING -> allowed(now)?.let { Outcome.Refused(it) } ?: run {
                    cancelPending(tx, by)
                    Outcome.Ok(tx.recovery())
                }
                else -> Outcome.Refused(Refusal.NOT_PENDING)
            }
        }

        /** The clear, if it is due: one atomic step that empties the list, raises the generation and cancels waiting requests. */
        internal fun settle(tx: AuthorityStore.Tx) {
            val r = tx.recovery()
            if (r.state != PENDING || !r.wait.over(tx.countedMs, tx.nowMs)) return
            val removed = tx.clearApprovers()
            val gen = tx.raise(AuthorityGeneration.Reason.APPROVER_LIST, "recovery clear")
            tx.keep(AuthorityStore.Transcript("recovery_clear", removed.joinToString(","), ByteArray(0), ByteArray(0)), gen)
            tx.cancelRequests("recovery_clear")
            tx.setRecovery(CLEARED, endedBy = "")
            tx.emit(AuthorityEvent.RecoveryCleared(removed))
        }

        /**
         * The daily reminder while a recovery waits: one alert when a day has passed since the recovery started
         * or since the last reminder, whichever is later. The last reminder is read from the outbox, so a
         * restart neither repeats nor loses one; a device that was off for days sends one, not one per day.
         * Run from the checkpoint, after [settle], so a recovery that has cleared sends none.
         */
        internal fun daily(tx: AuthorityStore.Tx): Boolean {
            val r = tx.recovery()
            if (r.state != PENDING) return false
            val last = maxOf(r.startedMs, AuthorityOutbox.lastAt(tx.db, "recovery_daily"))
            if (tx.nowMs - last < DAILY_MS) return false
            tx.emit(AuthorityEvent.RecoveryDaily(r.wait.wallEndMs))
            return true
        }

        /** Stops a pending recovery; the list is unchanged. Nothing happens in any other state. */
        internal fun cancelPending(tx: AuthorityStore.Tx, by: String) {
            if (tx.recovery().state != PENDING) return
            tx.setRecovery(CANCELLED, endedBy = by)
            tx.emit(AuthorityEvent.RecoveryCancelled(by))
        }
    }
}
