package cc.bridgit.app.core.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore

/**
 * MediaStore access for the library scope: the extra provenance columns, rows still arriving, and the completeness
 * probe extraction runs before reading an item. A device that rejects a column or query argument still answers,
 * never a 500 (the callers fall back step by step).
 */
object MediaLibrary {
    const val OWNER_PACKAGE = "owner_package_name"
    const val RELATIVE_PATH = "relative_path"
    const val IS_PENDING = "is_pending"
    const val DATE_MODIFIED = "date_modified"

    /** Makes a query see rows still being written too; MediaStore hides other apps' pending rows by default. */
    @Suppress("DEPRECATION")
    fun includePending(uri: Uri, args: Bundle): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE); uri }
        else MediaStore.setIncludePending(uri)

    fun string(c: Cursor, column: String): String? = c.getColumnIndex(column).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getString(it) }
    fun long(c: Cursor, column: String): Long? = c.getColumnIndex(column).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
    /** is_pending as true/false, or null when the device didn't return the column. */
    fun pending(c: Cursor): Boolean? = long(c, IS_PENDING)?.let { it != 0L }

    /**
     * Whether item [id] in [collection] has fully arrived, by [MediaSources.arrived], the rule its list uses: with
     * [checkFile] (videos) the name and size count too. False while incomplete, true when complete, null when the
     * device can't say (no such row, or no is_pending and nothing else shows it incomplete). Callers pass the result
     * to [MediaScopes.requireComplete].
     */
    fun complete(context: Context, collection: Uri, id: Long, checkFile: Boolean = false): Boolean? {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "_id=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(id.toString()))
        }
        val uri = includePending(collection, args)
        val file = arrayOf("_id", MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
        // The source kind and date_modified feed the sync stability window; a device rejecting them falls back to the
        // older projections, where the window isn't applied.
        val stability = arrayOf(IS_PENDING, RELATIVE_PATH, DATE_MODIFIED)
        for (cols in listOf(file + stability + OWNER_PACKAGE, file + stability, file + IS_PENDING, file)) {
            val cursor = try { context.contentResolver.query(uri, cols, args, null) } catch (e: RuntimeException) { null } ?: continue
            return try {
                cursor.use { c ->
                    if (!c.moveToFirst()) return null
                    val pending = pending(c)
                    val kind = MediaSources.classify(string(c, OWNER_PACKAGE), string(c, RELATIVE_PATH), pending, null, 0).source.kind
                    MediaSources.arrived(pending, string(c, MediaStore.MediaColumns.DISPLAY_NAME), long(c, MediaStore.MediaColumns.SIZE), checkFile,
                        kind, long(c, DATE_MODIFIED), nowSeconds())
                }
            } catch (e: RuntimeException) { null }
        }
        return null
    }

    /** The clock the completeness rule is checked against, in MediaStore's date_modified unit (epoch seconds). */
    fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}
