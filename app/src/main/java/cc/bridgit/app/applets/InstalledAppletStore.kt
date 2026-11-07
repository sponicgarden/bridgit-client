package cc.bridgit.app.applets

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** The device's installed copies and private journals. The remote catalog is paged separately. */
internal class InstalledAppletStore(context: Context) : SQLiteOpenHelper(context, "installed_applets.sqlite", null, 1) {
    private val legacy = context.getSharedPreferences("applet_instances", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE instances (
            id TEXT PRIMARY KEY, package_id TEXT NOT NULL, agent_id TEXT NOT NULL, budget_id TEXT NOT NULL,
            installed_at INTEGER NOT NULL, revision INTEGER NOT NULL, legacy_portal INTEGER NOT NULL, document BLOB NOT NULL)""")
        for (column in listOf("installed_at", "package_id, installed_at", "agent_id, installed_at", "budget_id, installed_at")) {
            db.execSQL("CREATE INDEX instances_by_${column.substringBefore(',')} ON instances($column,id)")
        }
        // SQLiteOpenHelper commits schema creation and every imported row together.
        for ((key, value) in legacy.all) {
            val record = (value as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            if (record.optString("id") == key) write(db, record)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported installed applet schema")

    fun open() {
        val db = writableDatabase
        // Runs after the import transaction committed, including on restart after an interrupted cleanup.
        val keys = legacy.all.keys
        if (keys.isEmpty()) return
        val edit = legacy.edit()
        for (id in keys) {
            db.rawQuery("SELECT 1 FROM instances WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst()) edit.remove(id)
            }
        }
        edit.apply()
    }

    private fun write(db: SQLiteDatabase, record: JSONObject) {
        val values = ContentValues().apply {
            put("id",record.getString("id"))
            put("package_id",record.optString("package_id"))
            put("agent_id",record.optString("agent_id"))
            put("budget_id",AppletBudget.appletOf(record))
            put("installed_at",record.optLong("installed_at"))
            put("revision",record.optLong("revision"))
            val portalRecords = record.optJSONObject("definition")?.optJSONObject("ui")?.optJSONObject("portal")?.optJSONObject("records")
            put("legacy_portal", portalRecords != null && !portalRecords.has("table") && !portalRecords.has("column"))
            put("document",record.toString().toByteArray(Charsets.UTF_8))
        }
        check(db.insertWithOnConflict("instances", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    fun save(record: JSONObject, advanceRevision: Boolean = true) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (advanceRevision) {
                val revision = db.rawQuery("SELECT revision FROM instances WHERE id=?", arrayOf(record.getString("id"))).use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                }
                record.put("revision", revision + 1)
            }
            write(db,record)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun remove(id: String) { writableDatabase.delete("instances","id=?",arrayOf(id)) }

    fun get(id: String) = first("id",id)
    fun byPackage(id: String) = first("package_id",id)
    fun forAgent(id: String) = first("agent_id",id)
    fun byBudget(id: String) = first("budget_id",id)

    private fun first(column: String, value: String): JSONObject? {
        val db = readableDatabase
        // Read large journals from a single snapshot, in chunks below Android's CursorWindow limit.
        db.beginTransactionNonExclusive()
        try {
            val row = db.rawQuery("SELECT id,length(document),substr(document,1,65536) FROM instances WHERE $column=? ORDER BY installed_at,id LIMIT 1", arrayOf(value)).use {
                if (!it.moveToFirst()) return null
                Triple(it.getString(0), it.getInt(1), it.getBlob(2))
            }
            val (id, length, first) = row
            val bytes = if (length == first.size) first else first.copyOf(length)
            var offset = first.size
            while (offset < length) {
                val chunk = db.rawQuery("SELECT substr(document,?,65536) FROM instances WHERE id=?",arrayOf((offset+1).toString(),id)).use {
                    check(it.moveToFirst())
                    it.getBlob(0)
                }
                check(chunk.isNotEmpty())
                chunk.copyInto(bytes,offset)
                offset += chunk.size
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { db.endTransaction() }
    }

    fun all() = listRecords("SELECT id FROM instances ORDER BY installed_at,id")

    fun portalMigrationCandidates() = listRecords("SELECT id FROM instances WHERE package_id='chef-helper' OR legacy_portal=1")

    private fun listRecords(sql: String): List<JSONObject> {
        val ids = readableDatabase.rawQuery(sql,null).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        return ids.mapNotNull(::get)
    }
}
