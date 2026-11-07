package cc.bridgit.app.outbound

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Stores [OutboundEntry]s in their own SQLite file (outbound.db), written on one background thread so a
 * network call never waits for the log. Keeps at most [Retention.maxEntries] entries and [Retention.maxDays] days.
 */
class OutboundStore(context: Context, private val retention: Retention = Retention(), dbName: String? = "outbound.db") {
    data class Retention(val maxEntries: Int = 10_000, val maxDays: Int = 30)

    private val db = Helper(context, dbName).writableDatabase
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "bridgit-outbound-writer").apply { isDaemon = true } }
    private var writesSinceTrim = 0

    fun append(e: OutboundEntry) {
        writer.execute {
            runCatching {
                db.insert("outbound", null, values(e))
                if (++writesSinceTrim >= 100) { writesSinceTrim = 0; trim() }
            }
        }
    }

    /** Waits until every append so far is stored (tests, and before reading back right after a call). */
    fun flush() { writer.submit {}.get() }

    /** [light] leaves out previews, headers and query (the summary never reads them). */
    fun query(q: OutboundQuery, light: Boolean = false): List<OutboundEntry> {
        val (where, args) = q.where()
        return db.query("outbound", if (light) LIGHT else null, where, args, null, null, q.orderBy, q.limit.toString()).use { rows(it) }
    }

    fun count(q: OutboundQuery): Int {
        val (where, args) = q.where()
        return db.rawQuery("SELECT COUNT(*) FROM outbound WHERE $where", args).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** Every matching entry (oldest first), up to [max], for the summary. */
    fun all(q: OutboundQuery, max: Int = 50_000, light: Boolean = false): List<OutboundEntry> {
        val out = ArrayList<OutboundEntry>()
        var cursor: Long? = null
        while (out.size < max) {
            val page = query(q.copy(ascending = true, limit = 500, cursor = cursor), light)
            out += page
            if (page.size < 500) break
            cursor = page.last().id
        }
        return out
    }

    /** Each host's first entry time across the whole kept log, limited to [q]'s agent scope (other filters ignored). */
    fun firstSeen(q: OutboundQuery): Map<String, Long> {
        val (where, args) = OutboundQuery(agentIds = q.agentIds, actors = q.actors).where()
        return db.rawQuery("SELECT host, MIN(ts) FROM outbound WHERE $where GROUP BY host", args).use { c ->
            val m = HashMap<String, Long>(); while (c.moveToNext()) m[c.getString(0)] = c.getLong(1); m }
    }

    fun get(id: Long): OutboundEntry? =
        db.query("outbound", null, "id = ?", arrayOf(id.toString()), null, null, null).use { rows(it).firstOrNull() }

    fun latestId(): Long = db.rawQuery("SELECT MAX(id) FROM outbound", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L }

    /** Runs the retention limits now (tests call this; normally every 100 writes). */
    fun trimNow() { writer.submit { trim() }.get() }

    private fun trim() {
        val cutoff = System.currentTimeMillis() - retention.maxDays * 86_400_000L
        db.delete("outbound", "ts < ?", arrayOf(cutoff.toString()))
        db.execSQL("DELETE FROM outbound WHERE id <= (SELECT id FROM outbound ORDER BY id DESC LIMIT 1 OFFSET ${retention.maxEntries})")
    }

    private fun values(e: OutboundEntry) = ContentValues().apply {
        put("ts", e.ts); put("host", e.host); put("service", e.service); put("method", e.method); put("path", e.path)
        put("purpose", e.purpose); put("agent_id", e.agentId); put("agent", e.agent)
        put("categories", if (e.categories.isEmpty()) "," else e.categories.joinToString(",", ",", ","))
        put("bytes_sent", e.bytesSent); put("bytes_received", e.bytesReceived); put("status", e.status); put("error", e.error)
        put("duration_ms", e.durationMs); put("transport", e.transport); put("query", e.query?.toString()); put("headers", e.headers?.toString())
        put("request_digest", e.requestDigest); put("request_type", e.requestType); put("request_preview", e.requestPreview)
        put("response_type", e.responseType); put("response_preview", e.responsePreview)
    }

    private fun rows(c: Cursor): List<OutboundEntry> {
        val out = ArrayList<OutboundEntry>(c.count)
        fun s(n: String) = c.getColumnIndex(n).let { if (it < 0 || c.isNull(it)) null else c.getString(it) }
        fun l(n: String) = c.getColumnIndex(n).let { if (it < 0 || c.isNull(it)) null else c.getLong(it) }
        fun obj(n: String) = s(n)?.let { runCatching { JSONObject(it) }.getOrNull() }
        while (c.moveToNext()) {
            out += OutboundEntry(
                id = l("id")!!, ts = l("ts")!!, host = s("host")!!, service = s("service")!!, method = s("method")!!, path = s("path")!!,
                purpose = s("purpose")!!, agentId = s("agent_id"), agent = s("agent")!!,
                categories = s("categories")!!.split(',').filter { it.isNotEmpty() },
                bytesSent = l("bytes_sent")!!, bytesReceived = l("bytes_received")!!, status = l("status")!!.toInt(), error = s("error"),
                durationMs = l("duration_ms")!!, transport = s("transport")!!, query = obj("query"), headers = obj("headers"),
                requestDigest = s("request_digest"), requestType = s("request_type"), requestPreview = s("request_preview"),
                responseType = s("response_type"), responsePreview = s("response_preview"))
        }
        return out
    }

    private companion object {
        val LIGHT = arrayOf("id", "ts", "host", "service", "method", "path", "purpose", "agent_id", "agent", "categories",
            "bytes_sent", "bytes_received", "status", "error", "duration_ms", "transport", "request_digest", "request_type", "response_type")
    }

    private class Helper(ctx: Context, name: String?) : SQLiteOpenHelper(ctx, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE outbound (
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, host TEXT NOT NULL, service TEXT NOT NULL,
                method TEXT NOT NULL, path TEXT NOT NULL, purpose TEXT NOT NULL, agent_id TEXT, agent TEXT NOT NULL,
                categories TEXT NOT NULL, bytes_sent INTEGER NOT NULL, bytes_received INTEGER NOT NULL, status INTEGER NOT NULL,
                error TEXT, duration_ms INTEGER NOT NULL, transport TEXT NOT NULL, query TEXT, headers TEXT,
                request_digest TEXT, request_type TEXT, request_preview TEXT, response_type TEXT, response_preview TEXT)""")
            db.execSQL("CREATE INDEX outbound_ts ON outbound(ts)")
            db.execSQL("CREATE INDEX outbound_agent ON outbound(agent_id, id)")
            db.execSQL("CREATE INDEX outbound_host ON outbound(host, id)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
    }
}
