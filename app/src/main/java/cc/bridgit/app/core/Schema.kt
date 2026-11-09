package cc.bridgit.app.core

import android.database.sqlite.SQLiteDatabase

/**
 * Helpers for SQLiteOpenHelper schemas that survive an older build being installed over a newer one (C0018).
 *
 * Android's default onDowngrade throws, and the stores open in BridgeApp.onCreate, so the app could not start.
 * Bridgit's schema changes are additive (new columns with defaults, new tables and indexes), so older code can read
 * a newer file as it is: helpers override onDowngrade to keep the data, and write their upgrades with these
 * helpers so a later upgrade adds only what is missing instead of failing on a column that is already there.
 */
internal object Schema {
    fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val name = c.getColumnIndexOrThrow("name")
            generateSequence { if (c.moveToNext()) c.getString(name) else null }.any { it.equals(column, ignoreCase = true) }
        }

    /** Adds [column] to [table] unless it exists. True when it was added, so one-time backfills run only then. */
    fun addColumn(db: SQLiteDatabase, table: String, column: String, definition: String): Boolean {
        if (hasColumn(db, table, column)) return false
        db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        return true
    }
}
