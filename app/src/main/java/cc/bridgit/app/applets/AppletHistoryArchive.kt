package cc.bridgit.app.applets

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Owner-private snapshots. Package workflows and control-agent data exports cannot read this database. */
internal class AppletHistoryArchive(context: Context) : SQLiteOpenHelper(context, "applet_history.sqlite", null, 1) {
    override fun onCreate(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE archives(instance TEXT PRIMARY KEY, digest TEXT NOT NULL, document BLOB NOT NULL)"
    )
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unknown archive schema")

    class Prepared(val bytes: ByteArray, val history: JSONObject, val digest: String)

    fun prepareDog(input: InputStream): Prepared {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            require(output.size() + count <= 8 * 1024 * 1024) { "History archive exceeds 8 MiB." }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        val document = JSONObject(bytes.toString(Charsets.UTF_8))
        require(document.optString("format") == "bridgit-dog-monitor-history/1" &&
            document.optString("package_id") == "dog-monitor" && document.getString("instance_id").isNotBlank() &&
            document.getString("package_sha256").matches(Regex("[0-9a-f]{64}"))) { "Choose an exported Dog Monitor history file." }
        val history = document.getJSONObject("tracker")
        val episodes = history.getJSONArray("episodes")
        require(episodes.length() <= 2000 && history.getJSONArray("readings").length() <= 60 &&
            history.getJSONArray("spans").length() <= 1000) { "Invalid Dog Monitor history counts." }
        for (i in 0 until episodes.length()) {
            val e = episodes.getJSONObject(i)
            require(e.getString("kind") in setOf("sleeping","resting","drinking","eating") &&
                e.getLong("start") > 0 && e.getLong("last_seen") >= e.getLong("start") && e.getInt("frames") > 0) { "Invalid saved episode." }
        }
        document.getJSONObject("settings")
        document.getJSONObject("sound_episodes")
        document.getJSONArray("sound_readings")
        return Prepared(bytes, history, AppletPackage.sha256(bytes))
    }

    fun save(instance: String, archive: Prepared) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = db.rawQuery("SELECT digest FROM archives WHERE instance=?", arrayOf(instance)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            require(old == null || old == archive.digest) { "This instance already has another history archive. Its original history was kept." }
            if (old == null) db.insertOrThrow("archives", null, ContentValues().apply {
                put("instance", instance); put("digest", archive.digest); put("document", archive.bytes)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun read(instance: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT length(document) FROM archives WHERE instance=?", arrayOf(instance)
    ).use { size ->
        if (!size.moveToFirst()) return@use null
        // CursorWindow can be smaller than an archive containing a JPEG.
        val length = size.getInt(0)
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            readableDatabase.rawQuery("SELECT substr(document,?,65536) FROM archives WHERE instance=?",
                arrayOf((offset + 1).toString(),instance)).use { cursor ->
                check(cursor.moveToFirst())
                val chunk = cursor.getBlob(0)
                check(chunk.isNotEmpty())
                chunk.copyInto(bytes, offset)
                offset += chunk.size
            }
        }
        JSONObject(bytes.toString(Charsets.UTF_8))
    }

    fun remove(instance: String) { writableDatabase.delete("archives","instance=?",arrayOf(instance)) }
}
