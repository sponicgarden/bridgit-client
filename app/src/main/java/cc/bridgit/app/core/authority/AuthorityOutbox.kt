package cc.bridgit.app.core.authority

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * The alert outbox (architecture §4.7: alerts go to the operating device, every approver and by email).
 * [AuthorityStore.tx] writes one row per [AuthorityEvent] in the same transaction as the change, so an alert
 * is never lost between the change and its delivery. Each way of delivery marks its own time on the row.
 *
 * Built so far: [Way.LOCAL] (the security notification and the log) and [Way.EMAIL] ([AuthoritySecurityMail]).
 * [Way.APPROVERS] has a column and no sender yet; its rows wait.
 *
 * A way's column is 0 while the row waits, the time once delivered, and [SKIPPED] or [REJECTED] when that
 * way will never carry the row.
 */
object AuthorityOutbox {
    enum class Way(internal val column: String) { LOCAL("local_ms"), EMAIL("email_ms"), APPROVERS("approvers_ms") }

    /** This way does not carry this kind of alert. */
    const val SKIPPED = -1L
    /** The receiver refused the alert for good. */
    const val REJECTED = -2L

    /** One stored alert. [kind] is the event's id; [body] its fields. Never a key or a signature. */
    data class Alert(val id: Long, val kind: String, val body: JSONObject, val atMs: Long)

    fun kind(e: AuthorityEvent): String = when (e) {
        is AuthorityEvent.ApproverEnrolled -> "approver_enrolled"
        is AuthorityEvent.ApproverAdded -> "approver_added"
        is AuthorityEvent.ApproverRemoved -> "approver_removed"
        is AuthorityEvent.RecoveryStarted -> "recovery_started"
        is AuthorityEvent.RecoveryCancelled -> "recovery_cancelled"
        is AuthorityEvent.RecoveryCleared -> "recovery_cleared"
        is AuthorityEvent.RecoveryCancelLate -> "recovery_cancel_late"
        is AuthorityEvent.RequestsCancelled -> "requests_cancelled"
        is AuthorityEvent.RecoveryDaily -> "recovery_daily"
        is AuthorityEvent.SwitchOffRequested -> "switch_off_requested"
        is AuthorityEvent.ApproversOff -> "approvers_off"
        is AuthorityEvent.ApproversOn -> "approvers_on"
    }

    fun body(e: AuthorityEvent): JSONObject = when (e) {
        is AuthorityEvent.ApproverEnrolled -> JSONObject().put("approver", e.approver).put("waits", e.waits)
        is AuthorityEvent.ApproverAdded -> JSONObject().put("approver", e.approver).put("by", e.by)
        is AuthorityEvent.ApproverRemoved -> JSONObject().put("approver", e.approver).put("by", e.by)
        is AuthorityEvent.RecoveryStarted -> JSONObject().put("by", e.by).put("clear_at", e.clearWallMs).put("approvers", JSONArray(e.approvers))
        is AuthorityEvent.RecoveryCancelled -> JSONObject().put("by", e.by)
        is AuthorityEvent.RecoveryCleared -> JSONObject().put("approvers", JSONArray(e.approvers))
        is AuthorityEvent.RecoveryCancelLate -> JSONObject().put("by", e.by)
        is AuthorityEvent.RequestsCancelled -> JSONObject().put("requests", JSONArray(e.requests)).put("why", e.why)
        is AuthorityEvent.RecoveryDaily -> JSONObject().put("clear_at", e.clearWallMs)
        is AuthorityEvent.SwitchOffRequested -> JSONObject().put("by", e.by)
        is AuthorityEvent.ApproversOff -> JSONObject().put("by", e.by).put("transition", e.transition)
        is AuthorityEvent.ApproversOn -> JSONObject().put("by", e.by).put("transition", e.transition)
    }

    internal fun write(db: SQLiteDatabase, e: AuthorityEvent, atMs: Long) {
        db.insertOrThrow("outbox", null, ContentValues().apply { put("kind", kind(e)); put("body", body(e).toString()); put("at_ms", atMs) })
    }

    internal fun undelivered(db: SQLiteDatabase, way: Way): List<Alert> =
        db.rawQuery("SELECT id, kind, body, at_ms FROM outbox WHERE ${way.column} = 0 ORDER BY id", null).use { c ->
            generateSequence { if (c.moveToNext()) Alert(c.getLong(0), c.getString(1), JSONObject(c.getString(2)), c.getLong(3)) else null }.toList()
        }

    /** Ends the wait of row [id] on [way] without a delivery; [mark] is [SKIPPED] or [REJECTED]. */
    internal fun notSent(db: SQLiteDatabase, id: Long, way: Way, mark: Long) {
        require(mark == SKIPPED || mark == REJECTED)
        db.update("outbox", ContentValues().apply { put(way.column, mark) }, "id = ? AND ${way.column} = 0", arrayOf(id.toString()))
    }

    /** The newest switch alert: `approvers_on`, `approvers_off`, or null when the switch was never changed. */
    internal fun lastSwitch(db: SQLiteDatabase): String? =
        db.rawQuery("SELECT kind FROM outbox WHERE kind IN ('approvers_on', 'approvers_off') ORDER BY id DESC LIMIT 1", null).use { c -> if (c.moveToNext()) c.getString(0) else null }

    /** Whether the switch change named [transition] already stored its alert. */
    internal fun hasTransition(db: SQLiteDatabase, transition: String): Boolean =
        db.rawQuery("SELECT body FROM outbox WHERE kind IN ('approvers_on', 'approvers_off') ORDER BY id DESC LIMIT 50", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.any { runCatching { JSONObject(it).optString("transition") }.getOrNull() == transition }
        }

    /** When the newest alert of [kind] was stored, or 0. */
    internal fun lastAt(db: SQLiteDatabase, kind: String): Long =
        db.rawQuery("SELECT at_ms FROM outbox WHERE kind = ? ORDER BY id DESC LIMIT 1", arrayOf(kind)).use { c -> if (c.moveToNext()) c.getLong(0) else 0L }

    internal fun delivered(db: SQLiteDatabase, id: Long, way: Way, atMs: Long) {
        db.update("outbox", ContentValues().apply { put(way.column, atMs.coerceAtLeast(1)) }, "id = ?", arrayOf(id.toString()))
    }
}
