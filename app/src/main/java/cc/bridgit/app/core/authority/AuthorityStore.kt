package cc.bridgit.app.core.authority

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** Something the owner must hear about (architecture §4.7: alerts go to every approver, the operating device and by email). Sent only after its change is stored. */
sealed interface AuthorityEvent {
    data class ApproverEnrolled(val approver: String, val waits: Boolean) : AuthorityEvent
    data class ApproverAdded(val approver: String, val by: String) : AuthorityEvent
    data class ApproverRemoved(val approver: String, val by: String) : AuthorityEvent
    data class RecoveryStarted(val by: String, val clearWallMs: Long, val approvers: List<String>) : AuthorityEvent
    data class RecoveryCancelled(val by: String) : AuthorityEvent
    /** The list was emptied. [approvers] is who was on it, so they can still be told. */
    data class RecoveryCleared(val approvers: List<String>) : AuthorityEvent
    /** A cancel came after the clear: refused, and worth an alert. */
    data class RecoveryCancelLate(val by: String) : AuthorityEvent
    data class RequestsCancelled(val requests: List<String>, val why: String) : AuthorityEvent
    /** A recovery is still waiting, a day after it started or after the last of these. */
    data class RecoveryDaily(val clearWallMs: Long) : AuthorityEvent
    /** The owner was asked, on an approver device, to turn approver devices off. */
    data class SwitchOffRequested(val by: String) : AuthorityEvent
    /** [transition] names the switch change that stored this event, so a change interrupted half-way is finished once. */
    data class ApproversOff(val by: String, val transition: String = "") : AuthorityEvent
    data class ApproversOn(val by: String, val transition: String = "") : AuthorityEvent
}

/**
 * The operating device's approver state (architecture §4.7): the approver list, enrolment transcripts,
 * requests and their one-use ids, proposals, the recovery record and the authority generation.
 *
 * Everything runs inside [tx]: one database transaction that first counts the running clock and settles a
 * due recovery clear, so no caller ever acts on a list that should already be empty. [execute] is the
 * execution-time check and the apply as one such transaction; the one-use id is spent in it.
 *
 * This class checks no signature. Callers verify with [OwnerAuth] (inside the same [tx], from
 * [Tx.ownerAuthState]) and tell the store which listed approver signed.
 */
class AuthorityStore(
    private val helper: AuthorityDb,
    private val time: AuthorityTime = AuthorityTime.Device,
    private val events: (AuthorityEvent) -> Unit = {},
    clockSession: Long = RunningClock.PROCESS,
) {
    val generation = AuthorityGeneration(helper)
    private val clock = RunningClock(time, helper, clockSession)
    private var inTx = false

    class NewApprover(val id: String, val name: String, val approverKey: ByteArray, val installKey: ByteArray, val sealingKey: ByteArray, val messageKey: ByteArray)

    /** An enrolled approver device. Keys are public keys (SPKI). [wait] is over when it can approve. */
    class Approver(
        val id: String, val name: String, val approverKey: ByteArray, val installKey: ByteArray, val sealingKey: ByteArray,
        val messageKey: ByteArray, val addedMs: Long, val lastUsedMs: Long, val wait: RunningClock.Wait,
    )

    /** What was hashed and signed when the list changed: kind is `enrol`, `add`, `remove` or `recovery_clear`. */
    class Transcript(val kind: String, val approver: String, val hash: ByteArray, val body: ByteArray, val generation: Long = 0, val atMs: Long = 0)

    class NewRequest(val id: String, val digest: ByteArray, val kind: String, val purpose: String, val requester: String, val expiresMs: Long, val body: String = "")

    /** A stored request. [id] is its one-use id, the nonce of the signed bytes. */
    class Request(
        val id: String, val digest: ByteArray, val kind: String, val purpose: String, val requester: String, val body: String,
        val generation: Long, val expiresMs: Long, val state: String, val approver: String, val createdMs: Long, val decidedMs: Long,
    ) {
        fun record() = OwnerAuth.Record(purpose, digest, id, generation, expiresMs)
    }

    data class Proposal(
        val id: String, val request: String, val kind: String, val body: String, val remainingHumanStep: String, val state: String,
        val generation: Long = 0, val createdMs: Long = 0, val updatedMs: Long = 0,
    )

    /**
     * One commit of a request to an approver that will carry the work out itself. [id] names this commit and
     * no other; [generation] and [atMs] are the answer first given, replayed unchanged on every retry.
     */
    data class Commitment(val id: String, val request: String, val approver: String, val generation: Long, val atMs: Long, val state: String)

    data class Recovery(val state: String, val startedBy: String, val startedMs: Long, val wait: RunningClock.Wait, val endedBy: String, val endedMs: Long)

    /** Why the store said no. [code] is the error catalogue code a caller answers with. */
    enum class Refusal(val code: String) {
        UNKNOWN_REQUEST("not_found"), NOT_OPEN("conflict"), EXPIRED("conflict"), NONCE_REUSED("conflict"),
        UNKNOWN_APPROVER("conflict"), APPROVER_WAITING("conflict"), CHECK_FAILED("conflict"), STALE_GENERATION("conflict"),
        COMMITTED_ELSEWHERE("conflict"), LIST_NOT_EMPTY("conflict"), DUPLICATE_APPROVER("conflict"), SELF_REMOVE("conflict"),
        BAD_REQUEST("bad_request"), OWNER_EMAIL_REQUIRED("owner_email_required"), EMPTY_LIST("no_approver"),
        NOT_PENDING("conflict"), NOT_STARTER("conflict"), RECOVERY_COMPLETE("recovery_complete"),
    }

    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        data class Refused(val reason: Refusal, val detail: String = "") : Outcome<Nothing>
    }

    /**
     * One transaction. The clock is counted and a due recovery clear is done before [block] sees anything.
     * If [block] throws, nothing it did is kept and no event is sent.
     *
     * Work that must be atomic with a store change uses the [Tx] it is given: every operation of the store
     * and of [RecoveryMachine] exists on it. Calling the store again from inside [block] is refused, since
     * that inner call's events would be sent before this transaction is known to be kept.
     */
    @Synchronized
    fun <T> tx(block: (Tx) -> T): T {
        check(!inTx) { "AuthorityStore call inside AuthorityStore.tx: use the Tx" }
        val db = helper.writableDatabase
        val out = ArrayList<AuthorityEvent>()
        inTx = true
        val result = try {
            db.beginTransaction()
            try {
                val tx = Tx(db, time.wallMs(), clock.checkpoint(), out)
                RecoveryMachine.settle(tx)
                block(tx).also {
                    out.forEach { e -> AuthorityOutbox.write(db, e, tx.nowMs) }
                    db.setTransactionSuccessful()
                }
            } finally {
                db.endTransaction()
            }
        } finally {
            inTx = false
        }
        out.forEach(events)
        return result
    }

    /** Counts running time to storage and does a due clear. Call on start and on a timer; every other call does it too. */
    fun checkpoint(): Long = tx { it.countedMs }

    fun generation(): Long = tx { it.generation() }
    /** Raises the authority generation for a change made elsewhere (grant, pause, revoke, re-key, master switch). */
    fun raise(reason: AuthorityGeneration.Reason, detail: String = ""): Long = tx { it.raise(reason, detail) }
    fun approvers(): List<Approver> = tx { it.approvers() }
    fun canApprove(id: String): Boolean = tx { tx -> tx.approver(id)?.let(tx::pastWait) ?: false }
    fun enrolledBefore(): Boolean = tx { it.enrolledBefore() }
    fun enrolFirst(a: NewApprover, t: Transcript) = tx { it.enrolFirst(a, t) }
    fun addApprover(a: NewApprover, by: String, t: Transcript) = tx { it.addApprover(a, by, t) }
    fun removeApprover(id: String, by: String, t: Transcript) = tx { it.removeApprover(id, by, t) }
    fun transcripts(): List<Transcript> = tx { it.transcripts() }
    fun createRequest(r: NewRequest) = tx { it.createRequest(r) }
    fun request(id: String): Request? = tx { it.request(id) }
    fun cancelRequests(why: String, requester: String? = null, kind: String? = null) = tx { it.cancelRequests(why, requester, kind) }
    fun decline(id: String, approver: String?) = tx { it.decline(id, approver) }
    fun putProposal(p: Proposal) = tx { it.putProposal(p) }
    fun proposals(state: String? = null): List<Proposal> = tx { it.proposals(state) }
    fun <T> execute(id: String, approver: String?, settingChange: Boolean = false, check: (Tx, Request) -> String?, effect: (Tx, Request) -> T): Outcome<T> =
        tx { it.execute(id, approver, settingChange, check, effect) }
    fun commit(id: String, approver: String, check: (Tx, Request) -> String?): Outcome<Commitment> = tx { it.commit(id, approver, check) }
    fun voidCommit(commit: String, approver: String): Boolean = tx { it.voidCommit(commit, approver) }
    fun proposal(id: String): Proposal? = tx { it.proposal(id) }
    fun openRequests(): List<Request> = tx { it.openRequests() }
    /** Alerts stored and not yet delivered by [way]; see [AuthorityOutbox]. */
    fun undelivered(way: AuthorityOutbox.Way): List<AuthorityOutbox.Alert> = tx { AuthorityOutbox.undelivered(it.db, way) }
    fun delivered(id: Long, way: AuthorityOutbox.Way) = tx { AuthorityOutbox.delivered(it.db, id, way, it.nowMs) }
    /** Row [id] will never go out by [way]: [mark] is [AuthorityOutbox.SKIPPED] or [AuthorityOutbox.REJECTED]. */
    fun notSent(id: Long, way: AuthorityOutbox.Way, mark: Long) = tx { AuthorityOutbox.notSent(it.db, id, way, mark) }
    fun finishCommit(commit: String, approver: String): Boolean = tx { it.finishCommit(commit, approver) }

    inner class Tx internal constructor(
        internal val db: SQLiteDatabase,
        /** The wall clock when the transaction began. */
        val nowMs: Long,
        /** Running time counted when the transaction began. */
        val countedMs: Long,
        private val out: MutableList<AuthorityEvent>,
    ) {
        internal fun emit(e: AuthorityEvent) { out += e }

        fun generation(): Long = generation.current(db)
        fun raise(reason: AuthorityGeneration.Reason, detail: String = ""): Long = generation.raise(db, reason, detail, nowMs)
        fun enrolledBefore(): Boolean = helper.meta(AuthorityDb.ENROLLED_BEFORE, db) != 0L
        fun pastWait(a: Approver): Boolean = a.wait.over(countedMs, nowMs)

        fun approvers(): List<Approver> = db.rawQuery("SELECT * FROM approvers ORDER BY added_ms, id", null).use { c -> c.all(::approverOf) }
        fun approver(id: String): Approver? = db.rawQuery("SELECT * FROM approvers WHERE id = ?", arrayOf(id)).use { c -> c.all(::approverOf).firstOrNull() }

        /** The state [OwnerAuth.verify] needs, read in this transaction, with the one-use id check wired to the store. */
        fun ownerAuthState(self: String, enabledProposed: Set<String> = emptySet()) = OwnerAuth.State(
            self = self,
            approvers = approvers().map { OwnerAuth.Approver(it.id, it.approverKey, it.installKey, pastWait(it)) },
            nowMs = nowMs,
            enabledProposed = enabledProposed,
            nonceUsed = ::nonceUsed,
        )

        // Approver list

        /** The first approver, or a device as its own approver: only while the list is empty. After a recovery (the marker is set) it waits 24 hours; at first set-up it does not (D16c). */
        fun enrolFirst(a: NewApprover, t: Transcript): Outcome<Approver> {
            if (approvers().isNotEmpty()) return Outcome.Refused(Refusal.LIST_NOT_EMPTY)
            val wait = if (enrolledBefore()) approverWait() else RunningClock.Wait.NONE
            insert(a, wait)
            helper.setMeta(AuthorityDb.ENROLLED_BEFORE, 1, db)
            val gen = raise(AuthorityGeneration.Reason.APPROVER_LIST, "enrol ${a.id}")
            keep(t, gen)
            if (recovery().state == RecoveryMachine.CLEARED) setRecovery(RecoveryMachine.NONE)
            emit(AuthorityEvent.ApproverEnrolled(a.id, wait != RunningClock.Wait.NONE))
            return Outcome.Ok(approver(a.id)!!)
        }

        /** A further approver, authorized by [by] (purpose `approver_add`, verified by the caller). It always waits 24 hours. Shows [by] is not lost, so it cancels a pending recovery. */
        fun addApprover(a: NewApprover, by: String, t: Transcript): Outcome<Approver> {
            signer(by)?.let { return it }
            if (approver(a.id) != null) return Outcome.Refused(Refusal.DUPLICATE_APPROVER)
            insert(a, approverWait())
            keep(t, raise(AuthorityGeneration.Reason.APPROVER_LIST, "add ${a.id}"))
            approverSigned(by)
            emit(AuthorityEvent.ApproverAdded(a.id, by))
            return Outcome.Ok(approver(a.id)!!)
        }

        /** Removal, authorized by another listed approver (purpose `approver_remove`). Never by the approver itself or the operating device alone. */
        fun removeApprover(id: String, by: String, t: Transcript): Outcome<Long> {
            signer(by)?.let { return it }
            if (by == id) return Outcome.Refused(Refusal.SELF_REMOVE)
            if (db.delete("approvers", "id = ?", arrayOf(id)) != 1) return Outcome.Refused(Refusal.UNKNOWN_APPROVER)
            val gen = raise(AuthorityGeneration.Reason.APPROVER_LIST, "remove $id")
            keep(t, gen)
            approverSigned(by)
            emit(AuthorityEvent.ApproverRemoved(id, by))
            return Outcome.Ok(gen)
        }

        /**
         * A valid approver-signed message from [id] was just accepted: note its use, and cancel a pending
         * recovery, since it shows an approver is not lost. False when [id] is not listed.
         */
        fun approverSigned(id: String): Boolean {
            val set = ContentValues().apply { put("last_used_ms", nowMs) }
            if (db.update("approvers", set, "id = ?", arrayOf(id)) != 1) return false
            RecoveryMachine.cancelPending(this, id)
            return true
        }

        fun transcripts(): List<Transcript> = db.rawQuery("SELECT kind, approver, hash, body, generation, at_ms FROM transcripts ORDER BY id", null).use { c ->
            c.all { Transcript(it.getString(0), it.getString(1), it.getBlob(2), it.getBlob(3), it.getLong(4), it.getLong(5)) }
        }

        internal fun keep(t: Transcript, gen: Long) {
            db.insertOrThrow("transcripts", null, ContentValues().apply {
                put("kind", t.kind); put("approver", t.approver); put("hash", t.hash); put("body", t.body); put("generation", gen); put("at_ms", nowMs)
            })
        }

        private fun approverWait() = RunningClock.Wait.starting(countedMs, nowMs, AuthorityLimits.APPROVER_WAIT_MS)

        /** Null when [by] is listed and past its wait, so its signature counts; else the refusal. */
        private fun signer(by: String): Outcome.Refused? {
            val a = approver(by) ?: return Outcome.Refused(Refusal.UNKNOWN_APPROVER)
            return if (pastWait(a)) null else Outcome.Refused(Refusal.APPROVER_WAITING)
        }

        private fun insert(a: NewApprover, wait: RunningClock.Wait) {
            db.insertOrThrow("approvers", null, ContentValues().apply {
                put("id", a.id); put("name", a.name); put("approver_key", a.approverKey); put("install_key", a.installKey)
                put("sealing_key", a.sealingKey); put("message_key", a.messageKey); put("added_ms", nowMs)
                put("wait_counted_end", wait.countedEndMs); put("wait_wall_end", wait.wallEndMs)
            })
        }

        // Requests

        fun nonceUsed(nonce: String): Boolean = db.rawQuery("SELECT 1 FROM nonces WHERE nonce = ?", arrayOf(nonce)).use { it.moveToFirst() }
        fun request(id: String): Request? = db.rawQuery("SELECT * FROM requests WHERE id = ?", arrayOf(id)).use { c -> c.all(::requestOf).firstOrNull() }
        fun openRequests(): List<Request> = db.rawQuery("SELECT * FROM requests WHERE state = ? ORDER BY created_ms, id", arrayOf(OPEN)).use { c -> c.all(::requestOf) }

        /** Stores a request under the generation of this moment. A one-use id seen before, spent or not, is refused. */
        fun createRequest(r: NewRequest): Outcome<Request> {
            if (r.id.isEmpty() || r.digest.size != 32 || !OwnerAuth.isPurpose(r.purpose)) return Outcome.Refused(Refusal.BAD_REQUEST)
            if (nonceUsed(r.id) || request(r.id) != null) return Outcome.Refused(Refusal.NONCE_REUSED)
            db.insertOrThrow("requests", null, ContentValues().apply {
                put("id", r.id); put("digest", r.digest); put("kind", r.kind); put("purpose", r.purpose); put("requester", r.requester)
                put("body", r.body); put("generation", generation()); put("expires_ms", r.expiresMs); put("state", OPEN); put("created_ms", nowMs)
            })
            return Outcome.Ok(request(r.id)!!)
        }

        /** Cancels waiting requests: all of them, or those of one requester or kind (a feature turned off, a requester revoked, a recovery clear). */
        fun cancelRequests(why: String, requester: String? = null, kind: String? = null): List<String> {
            val ids = openRequests().filter { (requester == null || it.requester == requester) && (kind == null || it.kind == kind) }.map { it.id }
            ids.forEach { move(it, OPEN, CANCELLED, null) }
            if (ids.isNotEmpty()) emit(AuthorityEvent.RequestsCancelled(ids, why))
            return ids
        }

        /**
         * A decline: by a listed approver (install-key signature verified by the caller; its 24-hour wait does
         * not matter), or with null by an agent or a person here. The one-use id is spent. An approver's
         * decline is a valid approver-signed message, so it cancels a pending recovery.
         */
        fun decline(id: String, approver: String?): Outcome<Unit> {
            val r = request(id) ?: return Outcome.Refused(Refusal.UNKNOWN_REQUEST)
            if (r.state != OPEN) return Outcome.Refused(if (nonceUsed(id)) Refusal.NONCE_REUSED else Refusal.NOT_OPEN)
            if (approver != null && approver(approver) == null) return Outcome.Refused(Refusal.UNKNOWN_APPROVER)
            if (!spend(id) || !move(id, OPEN, DECLINED, approver)) return Outcome.Refused(Refusal.NONCE_REUSED)
            if (approver != null) approverSigned(approver) // a signed decline shows an approver is not lost, in its wait or not
            return Outcome.Ok(Unit)
        }

        /**
         * The execution-time check and the apply, as one atomic step (§4.7, review 3 C4). In order: the
         * request is open, unexpired and unused; [approver] (the listed approver whose signature the caller
         * verified, or null for a decision made on this device for a delegable kind) is still listed and past
         * its wait; a [settingChange] was made against the current generation; [check] runs the request's
         * whole authorisation again against today's state and returns null, or why not. Then the one-use id
         * is spent and [effect] applies the change. If [effect] throws, the id is not spent and the request
         * stays open. Only what [effect] does through this [Tx] is part of the atomic step.
         *
         * An old generation alone refuses nothing unless [settingChange] is set: the check reads the state.
         */
        fun <T> execute(id: String, approver: String?, settingChange: Boolean = false, check: (Tx, Request) -> String?, effect: (Tx, Request) -> T): Outcome<T> =
            when (val ready = ready(id, approver, settingChange, check)) {
                is Outcome.Refused -> ready
                is Outcome.Ok -> {
                    val r = ready.value
                    if (!spend(id)) Outcome.Refused(Refusal.NONCE_REUSED)
                    else if (!move(id, OPEN, APPLIED, approver)) Outcome.Refused(Refusal.NOT_OPEN)
                    else {
                        val value = effect(this, r)
                        if (approver != null) approverSigned(approver)
                        Outcome.Ok(value)
                    }
                }
            }

        /**
         * For work an approver carries out itself (an overlay; payments): the same check, then the request
         * is recorded as committed to [approver] under a new commit id. The commit is the boundary: asking
         * again for the same approver replays the first answer unchanged (same commit id, same generation,
         * same time) and runs no check, whatever changed since. Another approver is refused.
         */
        fun commit(id: String, approver: String, check: (Tx, Request) -> String?): Outcome<Commitment> {
            heldCommit(id)?.let { return if (it.approver == approver) Outcome.Ok(it) else Outcome.Refused(Refusal.COMMITTED_ELSEWHERE) }
            return when (val ready = ready(id, approver, false, check)) {
                is Outcome.Refused -> ready
                is Outcome.Ok -> {
                    if (!move(id, OPEN, COMMITTED, approver)) return Outcome.Refused(Refusal.NOT_OPEN)
                    val commit = java.util.UUID.randomUUID().toString()
                    db.insertOrThrow("commits", null, ContentValues().apply {
                        put("id", commit); put("request", id); put("approver", approver); put("generation", generation()); put("state", HELD); put("at_ms", nowMs)
                    })
                    approverSigned(approver)
                    Outcome.Ok(commitment(commit)!!)
                }
            }
        }

        /**
         * A commit given back under the overlay's own rule: the request is open again. It must name the
         * commit and come from the approver that holds it, and is accepted once. A late or repeated give-back
         * of an older commit changes nothing, whoever holds the request now.
         */
        fun voidCommit(commit: String, approver: String): Boolean {
            val c = endCommit(commit, approver, GIVEN_BACK) ?: return false
            check(move(c.request, COMMITTED, OPEN, "")) { "A held commit without a committed request" }
            return true
        }

        /** The approver that holds [commit] reports the work carried out: the request is applied and its one-use id spent. Accepted once. */
        fun finishCommit(commit: String, approver: String): Boolean {
            val c = endCommit(commit, approver, FINISHED) ?: return false
            check(move(c.request, COMMITTED, APPLIED, null) && spend(c.request)) { "A held commit without a committed, unspent request" }
            return true
        }

        fun commitment(commit: String): Commitment? =
            db.rawQuery("SELECT id, request, approver, generation, at_ms, state FROM commits WHERE id = ?", arrayOf(commit)).use { c -> c.all(::commitOf).firstOrNull() }

        private fun heldCommit(request: String): Commitment? =
            db.rawQuery("SELECT id, request, approver, generation, at_ms, state FROM commits WHERE request = ? AND state = ?", arrayOf(request, HELD)).use { c -> c.all(::commitOf).firstOrNull() }

        /** Ends [commit] only if it is held, and held by [approver]. */
        private fun endCommit(commit: String, approver: String, to: String): Commitment? {
            val c = commitment(commit) ?: return null
            val set = ContentValues().apply { put("state", to); put("ended_ms", nowMs) }
            return if (db.update("commits", set, "id = ? AND approver = ? AND state = ?", arrayOf(commit, approver, HELD)) == 1) c else null
        }

        /** Everything [execute] checks before it spends the one-use id. */
        internal fun ready(id: String, approver: String?, settingChange: Boolean, check: (Tx, Request) -> String?): Outcome<Request> {
            val r = request(id) ?: return Outcome.Refused(Refusal.UNKNOWN_REQUEST)
            if (nonceUsed(id)) return Outcome.Refused(Refusal.NONCE_REUSED)
            if (r.state != OPEN) return Outcome.Refused(Refusal.NOT_OPEN, r.state)
            if (nowMs >= r.expiresMs) {
                move(id, OPEN, EXPIRED, null)
                return Outcome.Refused(Refusal.EXPIRED)
            }
            if (approver != null) {
                val a = approver(approver) ?: return Outcome.Refused(Refusal.UNKNOWN_APPROVER)
                if (!pastWait(a)) return Outcome.Refused(Refusal.APPROVER_WAITING)
            }
            if (settingChange && r.generation != generation()) return Outcome.Refused(Refusal.STALE_GENERATION)
            check(this, r)?.let { return Outcome.Refused(Refusal.CHECK_FAILED, it) }
            return Outcome.Ok(r)
        }

        /** Spends a one-use id. False if it was already spent. */
        internal fun spend(nonce: String): Boolean {
            val row = ContentValues().apply { put("nonce", nonce); put("used_ms", nowMs) }
            return db.insertWithOnConflict("nonces", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        }

        /** Moves a request between states only if it is in [from]. [approver] null leaves the stored approver as it is. */
        internal fun move(id: String, from: String, to: String, approver: String?): Boolean {
            val set = ContentValues().apply {
                put("state", to); put("decided_ms", if (to == OPEN) 0 else nowMs)
                if (approver != null) put("approver", approver)
            }
            return db.update("requests", set, "id = ? AND state = ?", arrayOf(id, from)) == 1
        }

        // Proposals

        fun putProposal(p: Proposal): Proposal {
            val created = proposal(p.id)?.createdMs ?: nowMs
            db.insertWithOnConflict("proposals", null, ContentValues().apply {
                put("id", p.id); put("request", p.request); put("kind", p.kind); put("body", p.body); put("remaining_human_step", p.remainingHumanStep)
                put("state", p.state); put("generation", generation()); put("created_ms", created); put("updated_ms", nowMs)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            return proposal(p.id)!!
        }

        fun proposal(id: String): Proposal? = db.rawQuery("SELECT * FROM proposals WHERE id = ?", arrayOf(id)).use { c -> c.all(::proposalOf).firstOrNull() }
        /** The proposal of [kind] written last (a proposal is written again on every change of its state). */
        fun latestProposal(kind: String): Proposal? =
            db.rawQuery("SELECT * FROM proposals WHERE kind = ? ORDER BY rowid DESC LIMIT 1", arrayOf(kind)).use { c -> c.all(::proposalOf).firstOrNull() }
        fun proposals(state: String? = null): List<Proposal> =
            db.rawQuery("SELECT * FROM proposals ORDER BY created_ms, id", null).use { c -> c.all(::proposalOf) }.filter { state == null || it.state == state }

        // Recovery record (the rules are in RecoveryMachine)

        fun recovery(): Recovery = db.rawQuery("SELECT state, started_by, started_ms, clear_counted, clear_wall_ms, ended_by, ended_ms FROM recovery WHERE id = 1", null).use { c ->
            c.moveToFirst()
            Recovery(c.getString(0), c.getString(1), c.getLong(2), RunningClock.Wait(c.getLong(3), c.getLong(4)), c.getString(5), c.getLong(6))
        }

        internal fun setRecovery(state: String, startedBy: String? = null, wait: RunningClock.Wait? = null, endedBy: String? = null) {
            db.update("recovery", ContentValues().apply {
                put("state", state)
                if (startedBy != null) { put("started_by", startedBy); put("started_ms", nowMs); put("ended_by", ""); put("ended_ms", 0) }
                if (wait != null) { put("clear_counted", wait.countedEndMs); put("clear_wall_ms", wait.wallEndMs) }
                if (endedBy != null) { put("ended_by", endedBy); put("ended_ms", nowMs) }
            }, "id = 1", null)
        }

        internal fun clearApprovers(): List<String> = approvers().map { it.id }.also { db.delete("approvers", null, null) }
    }

    private fun <T> Cursor.all(row: (Cursor) -> T): List<T> = generateSequence { if (moveToNext()) row(this) else null }.toList()
    private fun Cursor.text(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.blob(name: String) = getBlob(getColumnIndexOrThrow(name))

    private fun approverOf(c: Cursor) = Approver(
        c.text("id"), c.text("name"), c.blob("approver_key"), c.blob("install_key"), c.blob("sealing_key"), c.blob("message_key"),
        c.long("added_ms"), c.long("last_used_ms"), RunningClock.Wait(c.long("wait_counted_end"), c.long("wait_wall_end")),
    )

    private fun requestOf(c: Cursor) = Request(
        c.text("id"), c.blob("digest"), c.text("kind"), c.text("purpose"), c.text("requester"), c.text("body"), c.long("generation"),
        c.long("expires_ms"), c.text("state"), c.text("approver"), c.long("created_ms"), c.long("decided_ms"),
    )

    private fun commitOf(c: Cursor) = Commitment(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getString(5))

    private fun proposalOf(c: Cursor) = Proposal(
        c.text("id"), c.text("request"), c.text("kind"), c.text("body"), c.text("remaining_human_step"), c.text("state"),
        c.long("generation"), c.long("created_ms"), c.long("updated_ms"),
    )

    companion object {
        const val OPEN = "open"
        const val COMMITTED = "committed"
        const val APPLIED = "applied"
        const val DECLINED = "declined"
        const val CANCELLED = "cancelled"
        const val EXPIRED = "expired"
        const val HELD = "held"
        const val GIVEN_BACK = "given_back"
        const val FINISHED = "finished"
    }
}
