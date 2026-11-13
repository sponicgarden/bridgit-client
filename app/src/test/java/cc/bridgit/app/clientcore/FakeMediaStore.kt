package cc.bridgit.app.clientcore

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import org.robolectric.Robolectric

/**
 * A stand-in for MediaStore's "media" provider in Robolectric tests: images and videos as rows, newest first.
 * Like the real one it hides pending rows unless the query asks for MATCH_INCLUDE (or the include-pending URI),
 * rejects columns it doesn't know with IllegalArgumentException, honours `_id=?` and the SQL limit, and ignores
 * other selections. [missingColumns] makes it act like an older device that lacks those columns (one or several);
 * [rejectSortArgs] like one that rejects the SQL sort/limit query arguments. A row with [Row.nullName] has a null
 * _display_name (a null [Row.name] otherwise means a generated one). date_modified is [Row.modifiedAt] (epoch seconds),
 * or the row's added time when null, so an unseeded row is long settled.
 */
class FakeMediaStore : ContentProvider() {
    data class Row(val id: Long, val video: Boolean, val name: String? = null, val size: Long = 1024, val addedAt: Long = 1_762_800_000,
                   val ownerPackage: String? = null, val relativePath: String? = null, val pending: Boolean = false,
                   val nullName: Boolean = false, val modifiedAt: Long? = null)

    companion object {
        val rows = mutableListOf<Row>()
        val missingColumns = mutableSetOf<String>()
        var rejectSortArgs = false
        private val KNOWN = setOf("_id", "_display_name", "duration", "_size", "mime_type", "date_added", "datetaken", "width", "height",
            "orientation", "bucket_display_name", "relative_path", "owner_package_name", "is_pending", "date_modified")

        /** Registers a fresh provider for this test (Robolectric resets providers between tests). */
        fun install(seed: List<Row>, missing: Set<String> = emptySet(), rejectSort: Boolean = false) {
            rows.clear(); rows += seed
            missingColumns.clear(); missingColumns += missing
            rejectSortArgs = rejectSort
            Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
        }
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        query(uri, projection, Bundle().apply {
            selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
            selectionArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
        }, null)

    override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor {
        val cols = projection ?: arrayOf("_id")
        cols.firstOrNull { it !in KNOWN || it in missingColumns }?.let { throw IllegalArgumentException("Invalid column $it") }
        if (rejectSortArgs && (queryArgs?.containsKey(ContentResolver.QUERY_ARG_SQL_SORT_ORDER) == true ||
                queryArgs?.containsKey(ContentResolver.QUERY_ARG_SQL_LIMIT) == true)) throw IllegalArgumentException("Unsupported query arguments")
        val video = uri.pathSegments.contains("video")
        val includePending = queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_DEFAULT) == MediaStore.MATCH_INCLUDE ||
            uri.getQueryParameter("includePending") == "1"
        val byId = queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)?.takeIf { it.replace(" ", "") == "_id=?" }
            ?.let { queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)?.firstOrNull()?.toLongOrNull() }
        val limit = queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_LIMIT)?.toIntOrNull() ?: Int.MAX_VALUE
        val c = MatrixCursor(cols)
        rows.filter { it.video == video && (includePending || !it.pending) && (byId == null || it.id == byId) }
            .sortedWith(compareByDescending<Row> { it.addedAt }.thenByDescending { it.id }).take(limit)
            .forEach { r -> c.addRow(cols.map { value(r, it) }) }
        return c
    }

    private fun value(r: Row, column: String): Any? = when (column) {
        "_id" -> r.id
        "_display_name" -> if (r.nullName) null else r.name ?: if (r.video) "video-${r.id}.mp4" else "photo-${r.id}.jpg"
        "duration" -> if (r.video) 5_000L else null
        "_size" -> r.size
        "mime_type" -> if (r.video) "video/mp4" else "image/jpeg"
        "date_added" -> r.addedAt
        "datetaken" -> r.addedAt * 1000
        "width" -> 1200
        "height" -> 900
        "orientation" -> 0
        "bucket_display_name" -> r.relativePath?.trimEnd('/')?.substringAfterLast('/')
        "relative_path" -> r.relativePath
        "owner_package_name" -> r.ownerPackage
        "is_pending" -> if (r.pending) 1 else 0
        "date_modified" -> r.modifiedAt ?: r.addedAt
        else -> null
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
