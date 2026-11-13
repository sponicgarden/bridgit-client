package cc.bridgit.app.core.spending

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The spend journal's storage: one SQLite file per accounting namespace, in the app's private database
 * folder (left out of backups: the app sets `allowBackup="false"`). Live and sandbox never share a file, a
 * chain, an epoch or a total (spending authority G10.3 X10).
 *
 * `entries` only grows; rows are keyed by epoch and sequence, so a new epoch never replaces an old one's
 * rows. `head` is one row. [Journal] is the only writer and commits an entry and the head in one transaction.
 * The file name can be given only inside the module, so tests can keep several installs side by side.
 */
class SpendingDb internal constructor(context: Context, val namespace: Namespace, file: String) :
    SQLiteOpenHelper(context.applicationContext, file, null, VERSION) {

    constructor(context: Context, namespace: Namespace) : this(context, namespace, fileName(namespace))

    init {
        // Rollback journal with full sync (Android's default without WAL): an entry is on disk before its adapter runs.
        setWriteAheadLoggingEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE entries (epoch TEXT NOT NULL, seq INTEGER NOT NULL, spend_key TEXT, body TEXT NOT NULL, " +
                "hash TEXT NOT NULL, mac TEXT NOT NULL, PRIMARY KEY (epoch, seq))",
        )
        db.execSQL("CREATE INDEX entries_spend ON entries (epoch, spend_key, seq)")
        db.execSQL("CREATE TABLE head (id INTEGER PRIMARY KEY CHECK (id = 1), body TEXT NOT NULL, mac TEXT NOT NULL)")
        documents(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 3 had the requests table without what was asked (test builds only); its rows are a replay memory and nothing else.
        if (oldVersion == 3) db.execSQL("DROP TABLE IF EXISTS requests")
        if (oldVersion < 4) documents(db)
    }

    /** A file written by a newer build is kept as it is (C0018); the journal then decides whether it can read it. */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /**
     * Rule documents by digest, and signed things that wait for a rule document this device does not hold
     * yet. Neither table is under the journal's MAC: a document is read back only by a digest the head or
     * another verified document names and must hash to it, and a waiting item is checked in full again when
     * it is taken up. Removing a row can only make the device wait or refuse.
     *
     * `requests` remembers what a caller's idempotency key asked for and what it made (an increment's id, or
     * a word for a rule change), written in the transaction that made it. A retry, after a crash or a restart
     * as well, gets that outcome again and changes nothing more. Removing a row can only let a retry act
     * again, which meets the same gate as any change.
     */
    private fun documents(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS documents (digest TEXT PRIMARY KEY, generation INTEGER NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS waiting (id TEXT PRIMARY KEY, kind TEXT NOT NULL, parent TEXT NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS requests (key TEXT PRIMARY KEY, epoch TEXT NOT NULL, id TEXT NOT NULL, payload TEXT)")
    }

    companion object {
        const val VERSION = 4
        fun fileName(namespace: Namespace) = "spending-${namespace.id}.db"
    }
}
