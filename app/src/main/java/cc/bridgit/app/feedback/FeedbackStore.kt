package cc.bridgit.app.feedback

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Thrown when a report can't be filed or moved; the API maps [code] to its error. */
class FeedbackError(val code: String, message: String) : Exception(message)

/**
 * Reports live on the phone first (section 11): SQLite `feedback.db` plus JPEGs under
 * filesDir/feedback. Separate from log.db so neither schema blocks the other.
 */
class FeedbackStore(private val context: Context, private val clock: () -> Long = System::currentTimeMillis,
                    private val uploadEnabled: () -> Boolean = { true }) {
    private val db = Helper(context).writableDatabase
    private val dir = File(context.filesDir, "feedback").apply { mkdirs() }
    private val _changed = MutableStateFlow(0L)
    private val listeners = mutableListOf<(Report) -> Unit>()

    /** Bumps on every new report or status change; long-polls and screens watch it. */
    val changed: StateFlow<Long> get() = _changed

    private val created = mutableListOf<(Report) -> Unit>()
    private val historyAdded = mutableListOf<() -> Unit>()

    /** Called after a history entry is queued for the Bridgit team (FBK-14 history upload). */
    fun onHistoryQueued(l: () -> Unit) = synchronized(historyAdded) { historyAdded += l }

    /** Called after a new report is saved (FBK-14 upload). */
    fun onCreated(l: (Report) -> Unit) = synchronized(created) { created += l }

    /** Called after a status change (for the owner notification). */
    fun onStatusChange(l: (Report) -> Unit) = synchronized(listeners) { listeners += l }

    /**
     * Files a report. [images] are already downscaled JPEGs (FBK-07). [captureSource] is
     * "form" (the owner) or "agent". Rate limits per FBK-13 are checked here.
     */
    @Synchronized
    fun create(kind: ReportKind, description: String, noAutofix: Boolean, images: List<ByteArray>,
               log: JSONArray, ctx: JSONObject, captureSource: String, agentId: String? = null, agent: String? = null): Report {
        val text = description.trim()
        if (text.isEmpty()) throw FeedbackError("invalid_parameter", "Please describe the problem before sending.")
        if (text.length > FeedbackLimits.MAX_DESCRIPTION) throw FeedbackError("invalid_parameter", "The description is limited to 10,000 characters.")
        if (images.size > FeedbackLimits.MAX_IMAGES) throw FeedbackError("invalid_parameter", "Up to 5 screenshots per report.")
        val hourAgo = clock() - 3_600_000L
        if (captureSource == "form") {
            if (count("capture_source = 'form' AND kind = ? AND created_at > ?", kind.id, hourAgo.toString()) >= FeedbackLimits.FORM_PER_HOUR_PER_KIND)
                throw FeedbackError("rate_limited", "That's 10 reports this hour. Try again later.")
        } else if (count("agent_id = ? AND created_at > ?", agentId ?: "", hourAgo.toString()) >= FeedbackLimits.API_PER_HOUR_PER_AGENT) {
            throw FeedbackError("rate_limited", "At most ${FeedbackLimits.API_PER_HOUR_PER_AGENT} reports per hour per agent.")
        }
        val now = clock()
        val held = noAutofix || kind == ReportKind.FEATURE // features are always held for review (FBK-04)
        val written = mutableListOf<File>()
        db.beginTransaction()
        val id: Long
        try {
        id = db.insertOrThrow("reports", null, ContentValues().apply {
            put("kind", kind.id); put("description", text); put("no_autofix", if (held) 1 else 0)
            put("status", ReportStatus.NEW.id); put("created_at", now); put("updated_at", now)
            put("capture_source", captureSource); put("agent_id", agentId); put("agent", agent)
            put("context_json", ctx.toString()); put("log_json", log.toString()); put("images_json", "[]")
            put("last_by", agent ?: "owner")
            put("upload_state", if (uploadEnabled()) UploadState.PENDING else UploadState.OFF)
        })
        val names = images.mapIndexed { i, bytes ->
            "$id-${i + 1}.jpg".also { File(dir, it).apply { writeBytes(bytes); written += this } }
        }
        db.update("reports", ContentValues().apply { put("images_json", JSONArray(names).toString()) }, "id = ?", arrayOf(id.toString()))
        // Filing it is not sent as history: the report upload itself carries it.
        addHistory(id, ReportStatus.NEW, agent ?: "owner", null, now, HistoryKind.STATUS,
            if (captureSource == "agent") Actor.REPORTER else Actor.OWNER, UploadState.SKIPPED)
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            written.forEach { it.delete() } // a failed save leaves no report and no stray screenshots
            throw FeedbackError("internal", "The report couldn't be saved: ${e.javaClass.simpleName}")
        } finally {
            db.endTransaction()
        }
        _changed.value = _changed.value + 1
        val saved = get(id)!!
        synchronized(created) { created.toList() }.forEach { runCatching { it(saved) } }
        return saved
    }

    fun get(id: Long): Report? = db.query("reports", null, "id = ?", arrayOf(id.toString()), null, null, null)
        .use { c -> if (c.moveToFirst()) row(c, history(id)) else null }

    /** Newest first. Filters are optional; [sinceTs] means updated after; [agentId] = only reports that agent filed. */
    fun list(status: ReportStatus? = null, kind: ReportKind? = null, sinceTs: Long? = null, limit: Int = 100,
             agentId: String? = null): List<Report> {
        val where = mutableListOf<String>(); val args = mutableListOf<String>()
        agentId?.let { where += "agent_id = ? AND capture_source = 'agent'"; args += it }
        status?.let { where += "status = ?"; args += it.id }
        kind?.let { where += "kind = ?"; args += it.id }
        sinceTs?.let { where += "updated_at > ?"; args += it.toString() }
        return db.query("reports", null, if (where.isEmpty()) null else where.joinToString(" AND "), args.toTypedArray(),
            null, null, "id DESC", limit.toString()).use { c -> buildList { while (c.moveToNext()) add(row(c, emptyList())) } }
    }

    /** Moves a report if FBK-11a allows it; returns the updated report. */
    @Synchronized
    fun move(id: Long, to: ReportStatus, actor: Actor, by: String, note: String?): Report {
        val r = get(id) ?: throw FeedbackError("not_found", "No report $id.")
        if (r.status == to && actor != Actor.AUTO) return r
        if (!StatusMachine.allowed(r.status, to, actor)) {
            val next = if (actor == Actor.DEVELOPER) StatusMachine.nextForDeveloper(r.status).joinToString { it.id } else ""
            throw FeedbackError("invalid_parameter", "A report that is ${r.status.id} can't move to ${to.id}" +
                (if (next.isNotEmpty()) ". Allowed: $next." else "."))
        }
        val now = clock()
        db.update("reports", ContentValues().apply { put("status", to.id); put("updated_at", now); put("last_by", by) },
            "id = ?", arrayOf(id.toString()))
        val queued = addHistory(id, to, by, note?.trim()?.take(FeedbackLimits.NOTE)?.ifEmpty { null }, now, HistoryKind.STATUS, actor,
            historyUploadState(r, actor))
        _changed.value = _changed.value + 1
        val updated = get(id)!!
        synchronized(listeners) { listeners.toList() }.forEach { runCatching { it(updated) } }
        if (queued) historyQueued()
        return updated
    }

    /**
     * FBK-12a: the agent that filed report [id] adds a follow-up: a [note], an [outcome] ("works" or
     * "still_broken", the owner's Confirm / Still broken), or both. Other agents' reports are not_found.
     */
    @Synchronized
    fun followUp(id: Long, agentId: String, by: String, note: String?, outcome: String?): Report {
        val r = get(id)?.takeIf { it.captureSource == "agent" && it.agentId == agentId }
            ?: throw FeedbackError("not_found", "No report $id filed by you.")
        val text = note?.trim()?.ifEmpty { null }
        if (text != null && text.length > FeedbackLimits.NOTE) throw FeedbackError("invalid_parameter", "note is limited to ${FeedbackLimits.NOTE} characters.")
        val to = outcome?.let { StatusMachine.outcome(it) ?: throw FeedbackError("invalid_parameter", "outcome must be works or still_broken.") }
        if (text == null && to == null) throw FeedbackError("invalid_parameter", "Send a note, an outcome (works or still_broken), or both.")
        val hourAgo = (clock() - 3_600_000L).toString()
        val recent = db.rawQuery("SELECT COUNT(*) FROM history WHERE report_id = ? AND actor = ? AND at > ?",
            arrayOf(id.toString(), Actor.REPORTER.id, hourAgo)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (recent >= FeedbackLimits.FOLLOWUPS_PER_HOUR_PER_REPORT)
            throw FeedbackError("rate_limited", "At most ${FeedbackLimits.FOLLOWUPS_PER_HOUR_PER_REPORT} follow-ups per hour on one report.")
        if (to != null) {
            if (!StatusMachine.allowed(r.status, to, Actor.REPORTER)) throw FeedbackError("invalid_parameter",
                "Report ${r.number} is ${r.status.id}. \"works\" and \"still_broken\" are for a report marked fixed, wont_fix or duplicate; send just a note until then.")
            return move(id, to, Actor.REPORTER, by, text)
        }
        val now = clock()
        db.update("reports", ContentValues().apply { put("updated_at", now); put("last_by", by) }, "id = ?", arrayOf(id.toString()))
        val queued = addHistory(id, r.status, by, text, now, HistoryKind.COMMENT, Actor.REPORTER, historyUploadState(r, Actor.REPORTER))
        _changed.value = _changed.value + 1
        if (queued) historyQueued()
        return get(id)!!
    }

    /** Whether a new history entry goes to the Bridgit team: never the team's own moves, and only for reports that go too. */
    private fun historyUploadState(r: Report, actor: Actor): String = when {
        actor == Actor.TEAM -> UploadState.SKIPPED
        !uploadEnabled() -> UploadState.OFF
        r.upload == UploadState.PENDING || r.upload == UploadState.SENT -> UploadState.PENDING
        else -> UploadState.SKIPPED
    }

    private fun historyQueued() = synchronized(historyAdded) { historyAdded.toList() }.forEach { runCatching { it() } }

    /** FBK-14: reports waiting to go to the Bridgit team, oldest first. */
    fun pendingUploads(limit: Int = 20): List<Report> =
        db.query("reports", null, "upload_state = ?", arrayOf(UploadState.PENDING), null, null, "id ASC", limit.toString())
            .use { c -> buildList { while (c.moveToNext()) add(row(c, emptyList())) } }

    fun markUploaded(id: Long, remoteId: Long) = setUpload(id, ContentValues().apply {
        put("upload_state", UploadState.SENT); put("remote_id", remoteId); put("uploaded_at", clock()); putNull("upload_error")
    })

    /** [permanent] = the backend refused the report itself; it won't be retried. */
    fun markUploadError(id: Long, error: String, permanent: Boolean) = setUpload(id, ContentValues().apply {
        put("upload_error", error.take(300)); if (permanent) put("upload_state", UploadState.FAILED)
    })

    /** The owner turned uploads off: anything not sent yet stays on the phone. */
    fun stopPendingUploads() {
        val off = ContentValues().apply { put("upload_state", UploadState.OFF) }
        db.update("reports", off, "upload_state = ?", arrayOf(UploadState.PENDING))
        db.update("history", off, "upload_state = ?", arrayOf(UploadState.PENDING))
    }

    /** A history entry waiting to go to the Bridgit team, with where its report stands there. */
    data class PendingHistory(val reportId: Long, val reportUpload: String, val remoteId: Long?, val item: HistoryItem)

    /** FBK-14 history upload: entries waiting to go, oldest first. */
    fun pendingHistory(limit: Int = 50): List<PendingHistory> = db.rawQuery(
        """SELECT h.*, r.upload_state AS r_upload, r.remote_id AS r_remote FROM history h JOIN reports r ON r.id = h.report_id
           WHERE h.upload_state = ? ORDER BY h.id ASC LIMIT $limit""", arrayOf(UploadState.PENDING)).use { c ->
        buildList {
            while (c.moveToNext()) {
                val remote = c.getColumnIndexOrThrow("r_remote").let { if (c.isNull(it)) null else c.getLong(it) }
                add(PendingHistory(c.getLong(c.getColumnIndexOrThrow("report_id")),
                    c.getString(c.getColumnIndexOrThrow("r_upload")) ?: UploadState.SKIPPED, remote, historyRow(c)))
            }
        }
    }

    /** Marks history entry [historyId] sent, failed (refused for good) or skipped (its report never went). */
    fun markHistory(historyId: Long, state: String) {
        db.update("history", ContentValues().apply { put("upload_state", state) }, "id = ?", arrayOf(historyId.toString()))
    }

    private fun setUpload(id: Long, v: ContentValues) {
        db.update("reports", v, "id = ?", arrayOf(id.toString())); _changed.value = _changed.value + 1
    }

    fun imageFile(name: String): File? = File(dir, name).takeIf { it.exists() && it.parentFile == dir }

    /** Exact totals, independent of the report list's display limit. */
    fun summary(kind: ReportKind): Pair<Int, Int> =
        count("kind = ?", kind.id) to count(
            """kind = ? AND (status = 'fixed' OR (status = 'confirmed' AND
                (SELECT h.status FROM history h WHERE h.report_id = reports.id
                 AND h.kind = 'status' AND h.status != 'confirmed' ORDER BY h.id DESC LIMIT 1) = 'fixed'))""",
            kind.id)

    fun counts(): Map<ReportStatus, Int> = db.rawQuery("SELECT status, COUNT(*) FROM reports GROUP BY status", null).use { c ->
        buildMap { while (c.moveToNext()) ReportStatus.of(c.getString(0))?.let { put(it, c.getInt(1)) } }
    }

    private fun count(where: String, vararg args: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM reports WHERE $where", args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Returns true when the entry is queued for the Bridgit team. */
    private fun addHistory(id: Long, s: ReportStatus, by: String, note: String?, at: Long, kind: String, actor: Actor, upload: String): Boolean {
        db.insert("history", null, ContentValues().apply {
            put("report_id", id); put("at", at); put("status", s.id); put("by", by); put("note", note)
            put("kind", kind); put("actor", actor.id); put("upload_state", upload)
        })
        return upload == UploadState.PENDING
    }

    private fun history(id: Long): List<HistoryItem> =
        db.query("history", null, "report_id = ?", arrayOf(id.toString()), null, null, "id ASC").use { c ->
            buildList { while (c.moveToNext()) add(historyRow(c)) }
        }

    private fun historyRow(c: Cursor): HistoryItem {
        fun s(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getString(it) }
        return HistoryItem(c.getLong(c.getColumnIndexOrThrow("at")), ReportStatus.of(s("status"))!!, s("by") ?: "", s("note"),
            id = c.getLong(c.getColumnIndexOrThrow("id")), kind = s("kind") ?: HistoryKind.STATUS, actor = Actor.of(s("actor")))
    }

    private fun row(c: Cursor, history: List<HistoryItem>): Report {
        fun s(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getString(it) }
        fun l(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
        val imgs = JSONArray(s("images_json") ?: "[]")
        return Report(l("id"), ReportKind.of(s("kind"))!!, s("description") ?: "", l("no_autofix") == 1L,
            ReportStatus.of(s("status"))!!, l("created_at"), l("updated_at"), s("capture_source") ?: "form",
            s("agent_id"), s("agent"), JSONObject(s("context_json") ?: "{}"), JSONArray(s("log_json") ?: "[]"),
            (0 until imgs.length()).map { imgs.getString(it) }, s("last_by"), history,
            upload = s("upload_state") ?: UploadState.SKIPPED,
            remoteId = c.getColumnIndexOrThrow("remote_id").let { if (c.isNull(it)) null else c.getLong(it) },
            uploadedAt = c.getColumnIndexOrThrow("uploaded_at").let { if (c.isNull(it)) 0 else c.getLong(it) },
            uploadError = s("upload_error"))
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "feedback.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE reports (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, description TEXT NOT NULL,
                no_autofix INTEGER NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                capture_source TEXT NOT NULL, agent_id TEXT, agent TEXT, context_json TEXT, log_json TEXT, images_json TEXT, last_by TEXT)""")
            db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, report_id INTEGER NOT NULL, at INTEGER NOT NULL, status TEXT NOT NULL, by TEXT NOT NULL, note TEXT)")
            db.execSQL("CREATE INDEX reports_status ON reports(status, id)")
            db.execSQL("CREATE INDEX history_report ON history(report_id, id)")
            addUploadColumns(db)
            addHistoryColumns(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                addUploadColumns(db)
                // Reports filed before uploads existed stay on the phone; only new ones are sent.
                db.execSQL("UPDATE reports SET upload_state = '${UploadState.SKIPPED}'")
            }
            if (oldVersion < 3) {
                addHistoryColumns(db)
                // History from before this version stays on the phone too.
                db.execSQL("UPDATE history SET upload_state = '${UploadState.SKIPPED}', kind = '${HistoryKind.STATUS}'")
            }
        }
        /** v3 (FBK-12a, FBK-14 history upload): comments, who moved it, and where each entry stands with the team. */
        private fun addHistoryColumns(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE history ADD COLUMN kind TEXT")
            db.execSQL("ALTER TABLE history ADD COLUMN actor TEXT")
            db.execSQL("ALTER TABLE history ADD COLUMN upload_state TEXT")
            db.execSQL("CREATE INDEX history_upload ON history(upload_state, id)")
        }
        /** v2 (FBK-14): where each report stands with the Bridgit team's backend. */
        private fun addUploadColumns(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE reports ADD COLUMN upload_state TEXT")
            db.execSQL("ALTER TABLE reports ADD COLUMN remote_id INTEGER")
            db.execSQL("ALTER TABLE reports ADD COLUMN uploaded_at INTEGER")
            db.execSQL("ALTER TABLE reports ADD COLUMN upload_error TEXT")
            db.execSQL("CREATE INDEX reports_upload ON reports(upload_state, id)")
        }
    }
}
