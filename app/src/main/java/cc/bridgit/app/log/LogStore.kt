package cc.bridgit.app.log

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The log (section 10): SQLite table `log`, written on one background thread so a
 * request never waits for a log write (LOG-03, NFR-09). Readers see an entry once
 * [latestId] includes it.
 */
class LogStore(context: Context, private val retention: () -> Retention) {
    data class Retention(val maxEntries: Int = 0, val maxDays: Int = 0, val maxDiagnostic: Int = 0)

    private val db = Helper(context).writableDatabase
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "bridgit-log-writer").apply { isDaemon = true } }
    private val _latest = MutableStateFlow(queryLatestId())
    private var writesSinceTrim = 0

    /** Called on the writer thread after each entry is stored (email alerts listen here). */
    @Volatile var onAppended: ((LogEntry) -> Unit)? = null

    /** Id of the newest stored entry. Collect it to follow the log live. */
    val latestId: StateFlow<Long> get() = _latest

    fun append(e: LogEntry) {
        writer.execute {
            runCatching {
                val id = db.insert("log", null, values(e))
                if (id > 0) _latest.value = id
                if (id > 0) onAppended?.let { cb -> runCatching { cb(e) } }
                if (++writesSinceTrim >= 200) { writesSinceTrim = 0; trim() }
            }
        }
    }

    /** Convenience for Bridgit's own entries (agent = null). */
    fun system(channel: String, kind: String, message: String, level: String = "info", data: JSONObject? = null) =
        append(LogEntry(ts = System.currentTimeMillis(), channel = channel, kind = kind, level = level,
            message = Redactor.string(message, 500), data = data))

    /** An audit entry for a change an agent made through the API (A0119): attributed to it, like its requests. */
    fun byAgent(agentId: String, agentName: String, kind: String, message: String, data: JSONObject? = null) =
        append(LogEntry(ts = System.currentTimeMillis(), channel = "audit", kind = kind, agentId = agentId, agent = agentName,
            message = Redactor.string(message, 500), data = data))

    fun query(q: LogQuery): List<LogEntry> {
        val (where, args) = q.where()
        return db.query("log", null, where, args, null, null, q.orderBy, q.limit.toString()).use { c -> rows(c) }
    }

    fun count(q: LogQuery): Int {
        val (where, args) = q.where()
        return db.rawQuery("SELECT COUNT(*) FROM log WHERE $where", args).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun get(id: Long): LogEntry? =
        db.query("log", null, "id = ?", arrayOf(id.toString()), null, null, null).use { rows(it).firstOrNull() }

    /** Streams every matching row (oldest first) in pages, for export (LOG-10). */
    fun forEach(q: LogQuery, block: (LogEntry) -> Unit) {
        // A finite snapshot: concurrent writes (including this export's audit) belong to the next export.
        val upper = minOf(q.untilId ?: Long.MAX_VALUE, latestId.value + 1)
        var cursor: Long? = q.cursor
        while (true) {
            val page = query(q.copy(ascending = true, limit = 500, cursor = cursor, untilId = upper))
            page.forEach(block)
            if (page.size < 500) return
            cursor = page.last().id
        }
    }

    /**
     * Tail mode (LOG-07): waits up to [waitMs] for the first entry newer than [afterId]
     * that matches [q]. Returns immediately when one exists already.
     */
    suspend fun waitFor(q: LogQuery, afterId: Long, waitMs: Long): List<LogEntry> {
        val ask = q.copy(sinceId = afterId, ascending = true, cursor = null)
        // Read the watermark before querying, so an entry written in between is never missed.
        var seen = _latest.value
        query(ask).let { if (it.isNotEmpty()) return it }
        withTimeoutOrNull(waitMs) {
            while (true) {
                _latest.first { it > seen }
                seen = _latest.value
                val found = query(ask)
                if (found.isNotEmpty()) return@withTimeoutOrNull
            }
        }
        return query(ask)
    }

    /** LOG-05: deletes audit and agent entries, keeps diagnostics, and records the clear. Returns how many went. */
    fun clear(by: String): Int {
        val n = db.delete("log", "channel IN ('audit','agent')", null)
        append(LogEntry(ts = System.currentTimeMillis(), channel = "audit", kind = "setting",
            message = "$by cleared the log ($n entries)", data = JSONObject().put("cleared", n)))
        return n
    }

    fun flush() { writer.submit {}.get() }

    private fun trim() {
        val r = retention()
        val cutoff = System.currentTimeMillis() - r.maxDays * 86_400_000L
        if (r.maxDays > 0) db.delete("log", "channel IN ('audit','agent') AND ts < ?", arrayOf(cutoff.toString()))
        if (r.maxEntries > 0) db.execSQL("DELETE FROM log WHERE channel IN ('audit','agent') AND id <= " +
            "(SELECT id FROM log WHERE channel IN ('audit','agent') ORDER BY id DESC LIMIT 1 OFFSET ${r.maxEntries})")
        if (r.maxDiagnostic > 0) db.execSQL("DELETE FROM log WHERE channel = 'diagnostic' AND id <= " +
            "(SELECT id FROM log WHERE channel = 'diagnostic' ORDER BY id DESC LIMIT 1 OFFSET ${r.maxDiagnostic})")
    }

    private fun queryLatestId(): Long =
        db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name = 'log'", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun values(e: LogEntry) = ContentValues().apply {
        put("ts", e.ts); put("channel", e.channel); put("level", e.level); put("kind", e.kind)
        put("agent_id", e.agentId); put("agent", e.agent); put("source_ip", e.sourceIp); put("method", e.method)
        put("endpoint", e.endpoint); put("params", e.params?.toString()); put("http", e.http); put("result", e.result)
        put("bytes_in", e.bytesIn); put("bytes_out", e.bytesOut); put("duration_ms", e.durationMs)
        put("request_id", e.requestId); put("message", e.message); put("data", e.data?.toString())
    }

    private fun rows(c: Cursor): List<LogEntry> {
        val out = ArrayList<LogEntry>(c.count)
        fun s(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getString(it) }
        fun l(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getLong(it) }
        while (c.moveToNext()) {
            out += LogEntry(
                id = l("id")!!, ts = l("ts")!!, channel = s("channel")!!, level = s("level")!!, kind = s("kind")!!,
                agentId = s("agent_id"), agent = s("agent"), sourceIp = s("source_ip"), method = s("method"),
                endpoint = s("endpoint"), params = s("params")?.let { runCatching { JSONObject(it) }.getOrNull() },
                http = l("http")?.toInt(), result = s("result"), bytesIn = l("bytes_in"), bytesOut = l("bytes_out"),
                durationMs = l("duration_ms"), requestId = s("request_id"), message = s("message") ?: "",
                data = s("data")?.let { runCatching { JSONObject(it) }.getOrNull() })
        }
        return out
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "log.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE log (
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, channel TEXT NOT NULL, level TEXT NOT NULL,
                kind TEXT NOT NULL, agent_id TEXT, agent TEXT, source_ip TEXT, method TEXT, endpoint TEXT, params TEXT,
                http INTEGER, result TEXT, bytes_in INTEGER, bytes_out INTEGER, duration_ms INTEGER, request_id TEXT,
                message TEXT, data TEXT)""")
            db.execSQL("CREATE INDEX log_ts ON log(ts)")
            db.execSQL("CREATE INDEX log_agent ON log(agent_id, id)")
            db.execSQL("CREATE INDEX log_channel ON log(channel, id)")
            db.execSQL("CREATE INDEX log_result ON log(result, id)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
    }
}
