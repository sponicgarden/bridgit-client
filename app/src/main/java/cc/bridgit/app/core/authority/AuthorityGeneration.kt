package cc.bridgit.app.core.authority

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * The operating device's authority generation (architecture §4.7): a counter that only rises, raised on
 * every change to who may do what on this device. It tells a signer or a verifier that its view is stale
 * and orders racing setting changes. It is not the condition for the execution-time check, which reads
 * the state itself.
 *
 * [raise] runs on the caller's database handle, so the counter rises in the same transaction as the
 * change that caused it, or not at all. Callers outside this package use [AuthorityStore.raise].
 */
class AuthorityGeneration(private val helper: AuthorityDb) {
    enum class Reason(val id: String) {
        GRANT("grant"), PAUSE("pause"), REVOKE("revoke"), REKEY("rekey"), MASTER_SWITCH("master_switch"),
        APPROVER_LIST("approver_list"), RECOVERY_START("recovery_start"),
    }

    fun current(db: SQLiteDatabase = helper.writableDatabase): Long = helper.meta(AuthorityDb.GENERATION, db)

    /** One higher, logged with why. Returns the new generation. */
    internal fun raise(db: SQLiteDatabase, reason: Reason, detail: String, atMs: Long): Long {
        val next = current(db) + 1
        helper.setMeta(AuthorityDb.GENERATION, next, db)
        db.insertOrThrow("generation_log", null, ContentValues().apply {
            put("generation", next); put("reason", reason.id); put("detail", detail); put("at_ms", atMs)
        })
        return next
    }

    /** The reason each generation was reached, oldest first. */
    fun log(): List<Pair<Long, String>> =
        helper.writableDatabase.rawQuery("SELECT generation, reason FROM generation_log ORDER BY generation", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getLong(0) to c.getString(1) else null }.toList()
        }
}
