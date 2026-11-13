package cc.bridgit.app.core.authority

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * authority.db: what the operating device holds about its approvers (architecture §4.7). Its own file, so
 * that clearing or trimming any other store never touches the approver list, a spent nonce or a running
 * recovery. Changes are additive (core/Schema.kt); a newer file is kept as it is by an older build.
 *
 * Only public keys are stored here. An approver key never leaves its approver's key store.
 */
class AuthorityDb(context: Context, name: String = NAME) : SQLiteOpenHelper(context, name, null, VERSION), ClockCheckpoints {
    override fun onCreate(db: SQLiteDatabase) {
        // generation, counted_ms + clock_elapsed_ms + clock_session (RunningClock), enrolled_before (the "enrolled before" marker).
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE generation_log (generation INTEGER PRIMARY KEY, reason TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', at_ms INTEGER NOT NULL)")
        db.execSQL(
            """CREATE TABLE approvers (id TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT '', approver_key BLOB NOT NULL,
            install_key BLOB NOT NULL, sealing_key BLOB NOT NULL, message_key BLOB NOT NULL, added_ms INTEGER NOT NULL,
            last_used_ms INTEGER NOT NULL DEFAULT 0, wait_counted_end INTEGER NOT NULL DEFAULT 0, wait_wall_end INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL(
            """CREATE TABLE transcripts (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, approver TEXT NOT NULL,
            hash BLOB NOT NULL, body BLOB NOT NULL, generation INTEGER NOT NULL, at_ms INTEGER NOT NULL)""",
        )
        // id is the request's one-use id (its nonce). state: open, committed, applied, declined, cancelled, expired.
        db.execSQL(
            """CREATE TABLE requests (id TEXT PRIMARY KEY, digest BLOB NOT NULL, kind TEXT NOT NULL, purpose TEXT NOT NULL,
            requester TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '', generation INTEGER NOT NULL, expires_ms INTEGER NOT NULL,
            state TEXT NOT NULL, approver TEXT NOT NULL DEFAULT '', created_ms INTEGER NOT NULL, decided_ms INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL("CREATE INDEX requests_state ON requests (state)")
        // One row per commit of a request to an approver. state: held, given_back, finished. generation is the answer first given.
        db.execSQL(
            """CREATE TABLE commits (id TEXT PRIMARY KEY, request TEXT NOT NULL, approver TEXT NOT NULL, generation INTEGER NOT NULL,
            state TEXT NOT NULL, at_ms INTEGER NOT NULL, ended_ms INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL("CREATE INDEX commits_request ON commits (request, state)")
        // Spent one-use ids, apart from requests so trimming old requests can never make a replay possible.
        db.execSQL("CREATE TABLE nonces (nonce TEXT PRIMARY KEY, used_ms INTEGER NOT NULL)")
        db.execSQL(
            """CREATE TABLE proposals (id TEXT PRIMARY KEY, request TEXT NOT NULL DEFAULT '', kind TEXT NOT NULL, body TEXT NOT NULL DEFAULT '',
            remaining_human_step TEXT NOT NULL DEFAULT '', state TEXT NOT NULL, generation INTEGER NOT NULL, created_ms INTEGER NOT NULL,
            updated_ms INTEGER NOT NULL)""",
        )
        // One row. state: none, pending, cancelled, cleared.
        db.execSQL(
            """CREATE TABLE recovery (id INTEGER PRIMARY KEY CHECK (id = 1), state TEXT NOT NULL, started_by TEXT NOT NULL DEFAULT '',
            started_ms INTEGER NOT NULL DEFAULT 0, clear_counted INTEGER NOT NULL DEFAULT 0, clear_wall_ms INTEGER NOT NULL DEFAULT 0,
            ended_by TEXT NOT NULL DEFAULT '', ended_ms INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL("INSERT INTO recovery (id, state) VALUES (1, 'none')")
        outbox(db)
    }

    /**
     * Alerts not yet delivered (version 2). A row is written in the transaction that made its change, so a
     * stop between the change and the alert loses nothing. One time per way of delivery; 0 is not yet.
     */
    private fun outbox(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, body TEXT NOT NULL DEFAULT '{}',
            at_ms INTEGER NOT NULL, local_ms INTEGER NOT NULL DEFAULT 0, email_ms INTEGER NOT NULL DEFAULT 0, approvers_ms INTEGER NOT NULL DEFAULT 0)""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) outbox(db)
    }

    /** A newer build's file is kept (C0018): its changes are additive, so this build reads it as it is. */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    internal fun meta(key: String, db: SQLiteDatabase = writableDatabase): Long =
        db.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    internal fun setMeta(key: String, value: Long, db: SQLiteDatabase = writableDatabase) {
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("k", key); put("v", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun read() = ClockCheckpoints.State(meta(COUNTED), meta(CLOCK_ELAPSED), meta(CLOCK_SESSION))
    override fun write(state: ClockCheckpoints.State) {
        setMeta(COUNTED, state.countedMs)
        setMeta(CLOCK_ELAPSED, state.elapsedMs)
        setMeta(CLOCK_SESSION, state.session)
    }

    companion object {
        const val NAME = "authority.db"
        const val VERSION = 2
        internal const val COUNTED = "counted_ms"
        internal const val CLOCK_ELAPSED = "clock_elapsed_ms"
        internal const val CLOCK_SESSION = "clock_session"
        internal const val GENERATION = "generation"
        internal const val ENROLLED_BEFORE = "enrolled_before"
    }
}
