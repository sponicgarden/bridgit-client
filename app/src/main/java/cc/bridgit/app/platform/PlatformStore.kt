package cc.bridgit.app.platform

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cc.bridgit.app.ApiError
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Durable, bounded records; callers never receive another agent's rows. */
class PlatformStore(context: Context, name: String = "agent-platform.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY, agent TEXT NOT NULL, kind TEXT NOT NULL, value TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX record_owner ON records(agent,kind,created)")
        db.execSQL("CREATE TABLE events(seq INTEGER PRIMARY KEY AUTOINCREMENT, agent TEXT NOT NULL, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE grants(agent TEXT NOT NULL, scope TEXT NOT NULL, expires INTEGER NOT NULL, PRIMARY KEY(agent,scope))")
        db.execSQL("CREATE TABLE requests(agent TEXT NOT NULL, key TEXT NOT NULL, digest TEXT NOT NULL, operation TEXT NOT NULL, PRIMARY KEY(agent,key))")
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun setting(key: String, fallback: String = ""): String = readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else fallback }
    @Synchronized fun setting(key: String, value: String, write: Boolean) { require(write); writableDatabase.execSQL("INSERT OR REPLACE INTO settings VALUES(?,?)", arrayOf(key, value)) }
    val deviceId: String get() = synchronized(this) { setting("device_id").ifEmpty { UUID.randomUUID().toString().also { setting("device_id", it, true) } } }
    /** Told after a grant is stored (true) or taken away (false): the authority generation follows (architecture §4.7). Must not block. */
    @Volatile var onGrantChange: ((agent: String, scope: String?, granted: Boolean) -> Unit)? = null
    @Synchronized fun grant(agent: String, scope: String, expires: Long) { writableDatabase.execSQL("INSERT OR REPLACE INTO grants VALUES(?,?,?)", arrayOf(agent, scope, expires)); onGrantChange?.invoke(agent, scope, true) }
    @Synchronized fun revoke(agent: String, scope: String? = null) { if (scope == null) writableDatabase.delete("grants", "agent=?", arrayOf(agent)) else writableDatabase.delete("grants", "agent=? AND scope=?", arrayOf(agent,scope)); onGrantChange?.invoke(agent, scope, false) }
    @Synchronized fun allowed(agent: String, scope: String, now: Long = System.currentTimeMillis()): Boolean = readableDatabase.rawQuery("SELECT expires FROM grants WHERE agent=? AND scope=?", arrayOf(agent, scope)).use { it.moveToFirst() && it.getLong(0) > now }
    @Synchronized fun grants(agent: String): JSONObject = JSONObject().also { result -> readableDatabase.rawQuery("SELECT scope,expires FROM grants WHERE agent=?", arrayOf(agent)).use { c -> while(c.moveToNext()) result.put(c.getString(0),c.getLong(1)) } }
    @Synchronized fun save(agent: String, kind: String, value: JSONObject): JSONObject {
        val id = value.optString("id").ifEmpty { UUID.randomUUID().toString() }
        val previous = find(id)
        if (previous != null && (previous.optString("agent_id") != agent || previous.optString("kind") != kind)) throw ApiError(409,"resource_conflict","Resource identity cannot change")
        value.put("id", id).put("agent_id", agent).put("kind", kind)
        val created = previous?.optLong("created_at") ?: System.currentTimeMillis()
        value.put("created_at", created)
        writableDatabase.execSQL("INSERT OR REPLACE INTO records VALUES(?,?,?,?,?)", arrayOf(id, agent, kind, value.toString(), created))
        return copy(value)
    }
    @Synchronized fun find(id: String, agent: String? = null, kind: String? = null): JSONObject? = readableDatabase.rawQuery("SELECT value FROM records WHERE id=?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else JSONObject(c.getString(0)).takeIf { (agent == null || it.optString("agent_id") == agent) && (kind == null || it.optString("kind") == kind) }
    }
    fun require(id: String, agent: String, kind: String? = null) = find(id, agent, kind) ?: throw ApiError(404,"resource_not_found","No such resource for this agent")
    @Synchronized fun list(agent: String? = null, kind: String? = null, limit: Int = 100): List<JSONObject> {
        val args = mutableListOf<String>(); val predicates = mutableListOf<String>()
        agent?.let { predicates.add("agent=?"); args.add(it) }; kind?.let { predicates.add("kind=?"); args.add(it) }
        val where = if (predicates.isEmpty()) "" else " WHERE " + predicates.joinToString(" AND ")
        return readableDatabase.rawQuery("SELECT value FROM records$where ORDER BY created DESC LIMIT ${limit.coerceIn(1,1000)}", args.toTypedArray()).use { c -> buildList { while(c.moveToNext()) add(JSONObject(c.getString(0))) } }
    }
    /** Keeps only the newest [keep] finished records of [kind] for [agent], so run history can't fill the store. */
    @Synchronized fun trimFinished(agent: String, kind: String, keep: Int, finished: Set<String>) {
        val old = readableDatabase.rawQuery("SELECT id, value FROM records WHERE agent=? AND kind=? ORDER BY created DESC LIMIT -1 OFFSET $keep", arrayOf(agent, kind)).use { c ->
            buildList { while (c.moveToNext()) if (JSONObject(c.getString(1)).optString("status") in finished) add(c.getString(0)) }
        }
        old.forEach { writableDatabase.delete("records", "id=?", arrayOf(it)) }
    }
    /** [agent]'s records of [kind] whose id starts with [idPrefix], in id order after [afterId], [limit] at most. */
    @Synchronized fun keyed(agent: String, kind: String, idPrefix: String, afterId: String, limit: Int): List<JSONObject> =
        readableDatabase.rawQuery("SELECT value FROM records WHERE agent=? AND kind=? AND substr(id,1,?)=? AND id>? ORDER BY id LIMIT ${limit.coerceIn(1, 1000)}",
            arrayOf(agent, kind, idPrefix.length.toString(), idPrefix, afterId)).use { c -> buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) } }
    /** How many records of [kind] [agent] keeps and their size in characters. */
    @Synchronized fun usage(agent: String, kind: String): Pair<Int, Long> =
        readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(LENGTH(value)),0) FROM records WHERE agent=? AND kind=?", arrayOf(agent, kind)).use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }
    /** Every record [agent] has, of any kind (applet removal). */
    @Synchronized fun deleteAll(agent: String) { writableDatabase.delete("records", "agent=?", arrayOf(agent)) }
    @Synchronized fun delete(id: String, agent: String) { writableDatabase.delete("records", "id=? AND agent=?", arrayOf(id,agent)) }
    @Synchronized fun emit(agent: String, type: String, resource: String = "", payload: JSONObject? = null): Long {
        val data = JSONObject().put("type",type).put("resource_id",resource).put("at",System.currentTimeMillis())
        payload?.let { data.put("data", it) }
        writableDatabase.execSQL("INSERT INTO events(agent,value) VALUES(?,?)", arrayOf(agent,data.toString()))
        val seq = readableDatabase.rawQuery("SELECT last_insert_rowid()",null).use { it.moveToFirst(); it.getLong(0) }
        writableDatabase.execSQL("DELETE FROM events WHERE seq < (SELECT COALESCE(MAX(seq),0)-2000 FROM events)")
        return seq
    }
    @Synchronized fun latestCursor():Long = readableDatabase.rawQuery("SELECT COALESCE(MAX(seq),0) FROM events",null).use { it.moveToFirst(); it.getLong(0) }
    @Synchronized fun events(agent: String, after: Long): JSONObject {
        val bounds = readableDatabase.rawQuery("SELECT COALESCE(MIN(seq),0),COALESCE(MAX(seq),0) FROM events",null).use { it.moveToFirst(); it.getLong(0) to it.getLong(1) }
        val rows = JSONArray(); var cursor = maxOf(after,bounds.second)
        readableDatabase.rawQuery("SELECT seq,value FROM events WHERE agent=? AND seq>? ORDER BY seq LIMIT 100",arrayOf(agent,after.toString())).use { c ->
            while(c.moveToNext()) { cursor = c.getLong(0); rows.put(JSONObject(c.getString(1)).put("cursor",cursor)) }
        }
        // With a full page, advance only through the page, never skip remaining rows.
        return JSONObject().put("events",rows).put("cursor",cursor).put("gap",after > 0 && bounds.first > after + 1)
    }
    /** Reservation and operation creation are one transaction; a crashed request is never re-dispatched. */
    @Synchronized fun reserve(agent: String, key: String, command: String, body: JSONObject): Pair<JSONObject,Boolean> {
        if (!key.matches(Regex("[A-Za-z0-9_-]{8,128}"))) throw ApiError(422,"idempotency_required","Use an 8–128 character Idempotency-Key")
        val digest = digest(JSONObject().put("command",command).put("body",body))
        val db = writableDatabase; db.beginTransaction()
        try {
            val existing = db.rawQuery("SELECT digest,operation FROM requests WHERE agent=? AND key=?",arrayOf(agent,key)).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
            if (existing != null) {
                if (existing.first != digest) throw ApiError(409,"idempotency_conflict","This key was used for different input")
                val record = find(existing.second,agent,"operation") ?: JSONObject().put("id",existing.second).put("status","history_removed").put("agent_id",agent)
                db.setTransactionSuccessful(); return record to false
            }
            val used=readableDatabase.rawQuery("PRAGMA page_count",null).use { it.moveToFirst(); it.getLong(0) }-readableDatabase.rawQuery("PRAGMA freelist_count",null).use { it.moveToFirst(); it.getLong(0) }
            val page=readableDatabase.rawQuery("PRAGMA page_size",null).use { it.moveToFirst(); it.getLong(0) }
            if(used*page>50*1024*1024) throw ApiError(429,"storage_quota","Review platform history and remove old data on the phone")
            if (list(agent,"operation",1000).size >= 1000) throw ApiError(429,"operation_limit","Review and remove completed operations before creating more")
            val op = save(agent,"operation",JSONObject().put("command",command).put("input",body).put("digest",digest).put("status","accepted"))
            db.execSQL("INSERT INTO requests VALUES(?,?,?,?)",arrayOf(agent,key,digest,op.getString("id")))
            db.setTransactionSuccessful(); return op to true
        } finally { db.endTransaction() }
    }
    companion object {
        fun copy(value: JSONObject) = JSONObject(value.toString())
        fun digest(value: JSONObject) = CanonicalJson.digest(value)
        fun canonical(value: Any?) = CanonicalJson.canonical(value)
    }
}

fun JSONObject.text(key: String, max: Int = 4096, fallback: String? = null): String {
    val value = if (!has(key) && fallback != null) fallback else opt(key) as? String ?: throw ApiError(422,"invalid_parameter","$key must be text")
    if (value.length > max) throw ApiError(422,"invalid_parameter","$key is too long")
    return value
}
fun JSONObject.whole(key: String, default: Int, min: Int, max: Int): Int {
    if (!has(key)) return default
    val value = opt(key)
    if (value !is Int && value !is Long) throw ApiError(422,"invalid_parameter","$key must be a whole number")
    val n = (value as Number).toLong()
    if (n !in min.toLong()..max.toLong()) throw ApiError(422,"invalid_parameter","$key must be $min–$max")
    return n.toInt()
}
fun JSONObject.only(vararg keys: String) { if (keys().asSequence().any { it !in keys }) throw ApiError(422,"invalid_parameter","Unknown field") }
fun JSONObject.strings(key: String, max: Int = 32): List<String> {
    val array = optJSONArray(key) ?: throw ApiError(422,"invalid_parameter","$key must be a list")
    if (array.length() > max) throw ApiError(422,"invalid_parameter","Too many $key")
    return (0 until array.length()).map { (array.opt(it) as? String)?.takeIf { it.length in 1..256 } ?: throw ApiError(422,"invalid_parameter","Invalid $key value") }.distinct()
}
