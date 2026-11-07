package cc.bridgit.app.alerts

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Agent calls waiting to be emailed, in their own small database so that clearing or trimming
 * the log never loses a digest. At most [MAX_PENDING] rows are kept (oldest go first), so a relay
 * outage or an unconfirmed address can't grow it without limit.
 */
class AlertStore(context: Context) : AlertOutbox {
    private val db = Helper(context).writableDatabase
    private val prefs = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    @Synchronized
    override fun add(e: AlertEvent) {
        db.insert("pending", null, ContentValues().apply {
            put("ts", e.ts); put("agent_id", e.agentId); put("agent", e.agent); put("method", e.method)
            put("endpoint", e.endpoint); put("http", e.http); put("message", e.message)
        })
        db.execSQL("DELETE FROM pending WHERE id <= (SELECT id FROM pending ORDER BY id DESC LIMIT 1 OFFSET $MAX_PENDING)")
    }

    @Synchronized
    override fun pending(): List<AlertEvent> = db.query("pending", null, null, null, null, null, "id").use { c ->
        val out = ArrayList<AlertEvent>(c.count)
        while (c.moveToNext()) out += AlertEvent(
            id = c.getLong(c.getColumnIndexOrThrow("id")), ts = c.getLong(c.getColumnIndexOrThrow("ts")),
            agentId = c.getString(c.getColumnIndexOrThrow("agent_id")), agent = c.getString(c.getColumnIndexOrThrow("agent")),
            method = c.getString(c.getColumnIndexOrThrow("method")), endpoint = c.getString(c.getColumnIndexOrThrow("endpoint")),
            http = c.getInt(c.getColumnIndexOrThrow("http")), message = c.getString(c.getColumnIndexOrThrow("message")) ?: "")
        out
    }

    @Synchronized
    override fun remove(ids: Collection<Long>) {
        db.beginTransaction()
        try { ids.chunked(500).forEach { ch -> db.delete("pending", "id IN (${ch.joinToString(",")})", null) }; db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }

    @Synchronized
    override fun clear() { db.delete("pending", null, null) }

    override var lastSentAt: Long
        get() = prefs.getLong("last_sent_at", 0)
        set(v) { prefs.edit().putLong("last_sent_at", v).apply() }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "alerts.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE pending (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, agent_id TEXT NOT NULL,
                agent TEXT NOT NULL, method TEXT NOT NULL, endpoint TEXT NOT NULL, http INTEGER NOT NULL, message TEXT)""")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    companion object { const val MAX_PENDING = 5_000 }
}
