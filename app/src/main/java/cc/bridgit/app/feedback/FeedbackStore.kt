package cc.bridgit.app.feedback

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cc.bridgit.app.core.Schema
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Thrown when an issue can't be filed or moved; the API maps [code] to its error. */
class FeedbackError(val code: String, message: String) : Exception(message)

/**
 * Issues live on the phone first (section 11): SQLite `feedback.db` plus JPEGs under
 * filesDir/feedback. Separate from log.db so neither schema blocks the other.
 *
 * Storage keeps its pre-rename names: the `reports` and `history` tables, the `report_id` column
 * and the file names back Issues Tracking (renamed from "reports", 8 Nov 2026). Renaming them would
 * change nothing visible and could lose a phone's saved issues on update, so they stay.
 */
class FeedbackStore(private val context: Context, private val clock: () -> Long = System::currentTimeMillis,
                    private val uploadEnabled: () -> Boolean = { true }) {
    private val db = Helper(context).writableDatabase
    private val dir = File(context.filesDir, "feedback").apply { mkdirs() }
    private val _changed = MutableStateFlow(0L)
    private val listeners = mutableListOf<(Issue) -> Unit>()

    /** Bumps on every new issue or status change; long-polls and screens watch it. */
    val changed: StateFlow<Long> get() = _changed

    private val created = mutableListOf<(Issue) -> Unit>()
    private val historyAdded = mutableListOf<() -> Unit>()

    /** Called after a history entry is queued for the Bridgit team (FBK-14 history upload). */
    fun onHistoryQueued(l: () -> Unit) = synchronized(historyAdded) { historyAdded += l }

    /** Called after a new issue is saved (FBK-14 upload). */
    fun onCreated(l: (Issue) -> Unit) = synchronized(created) { created += l }

    /** Called after a status change (for the owner notification). */
    fun onStatusChange(l: (Issue) -> Unit) = synchronized(listeners) { listeners += l }

    /**
     * Files an issue. [images] are already downscaled JPEGs (FBK-07). [captureSource] is
     * "form" (the owner) or "agent". Rate limits per FBK-13 are checked here.
     */
    @Synchronized
    fun create(kind: IssueKind, description: String, noAutofix: Boolean, images: List<ByteArray>,
               log: JSONArray, ctx: JSONObject, captureSource: String, agentId: String? = null, agent: String? = null,
               applet: JSONObject? = null): Issue {
        val text = description.trim()
        val request = AppletRequest.normalize(kind, applet)
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
        val held = noAutofix || kind.alwaysHeld // features and applet requests are always held for review (FBK-04, A0178)
        val written = mutableListOf<File>()
        db.beginTransaction()
        val id: Long
        try {
        id = db.insertOrThrow("reports", null, ContentValues().apply {
            put("kind", kind.id); put("description", text); put("no_autofix", if (held) 1 else 0)
            put("status", IssueStatus.NEW.id); put("created_at", now); put("updated_at", now)
            put("capture_source", captureSource); put("agent_id", agentId); put("agent", agent)
            put("context_json", ctx.toString()); put("log_json", log.toString()); put("images_json", "[]")
            put("last_by", agent ?: "owner"); put("applet_json", request?.toString())
            put("upload_state", if (uploadEnabled()) UploadState.PENDING else UploadState.OFF)
        })
        val names = images.mapIndexed { i, bytes ->
            "$id-${i + 1}.jpg".also { File(dir, it).apply { writeBytes(bytes); written += this } }
        }
        db.update("reports", ContentValues().apply { put("images_json", JSONArray(names).toString()) }, "id = ?", arrayOf(id.toString()))
        // Filing it is not sent as history: the issue upload itself carries it.
        addHistory(id, IssueStatus.NEW, agent ?: "owner", null, now, HistoryKind.STATUS,
            if (captureSource == "agent") Actor.REPORTER else Actor.OWNER, UploadState.SKIPPED)
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            written.forEach { it.delete() } // a failed save leaves no issue and no stray screenshots
            throw FeedbackError("internal", "The report couldn't be saved: ${e.javaClass.simpleName}")
        } finally {
            db.endTransaction()
        }
        _changed.value = _changed.value + 1
        val saved = get(id)!!
        synchronized(created) { created.toList() }.forEach { runCatching { it(saved) } }
        return saved
    }

    fun get(id: Long): Issue? = db.query("reports", null, "id = ?", arrayOf(id.toString()), null, null, null)
        .use { c -> if (c.moveToFirst()) row(c, history(id)) else null }

    /** Newest first. Filters are optional; [sinceTs] means updated after; [agentId] = only issues that agent filed. */
    fun list(status: IssueStatus? = null, kind: IssueKind? = null, sinceTs: Long? = null, limit: Int = 100,
             agentId: String? = null): List<Issue> {
        val where = mutableListOf<String>(); val args = mutableListOf<String>()
        agentId?.let { where += "agent_id = ? AND capture_source = 'agent'"; args += it }
        status?.let { where += "status = ?"; args += it.id }
        kind?.let { where += "kind = ?"; args += it.id }
        sinceTs?.let { where += "updated_at > ?"; args += it.toString() }
        return db.query("reports", null, if (where.isEmpty()) null else where.joinToString(" AND "), args.toTypedArray(),
            null, null, "id DESC", limit.toString()).use { c -> buildList { while (c.moveToNext()) add(row(c, emptyList())) } }
    }

    /** Moves an issue if FBK-11a allows it; returns the updated issue. */
    @Synchronized
    fun move(id: Long, to: IssueStatus, actor: Actor, by: String, note: String?): Issue {
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
     * FBK-12a: the agent that filed issue [id] adds a follow-up: a [note], an [outcome] ("works" or
     * "still_broken", the owner's Confirm / Still broken), or both. Other agents' issues are not_found.
     */
    @Synchronized
    fun followUp(id: Long, agentId: String, by: String, note: String?, outcome: String?): Issue {
        val r = get(id)?.takeIf { it.captureSource == "agent" && it.agentId == agentId }
            ?: throw FeedbackError("not_found", "No report $id filed by you.")
        val text = note?.trim()?.ifEmpty { null }
        if (text != null && text.length > FeedbackLimits.NOTE) throw FeedbackError("invalid_parameter", "note is limited to ${FeedbackLimits.NOTE} characters.")
        val to = outcome?.let { StatusMachine.outcome(it) ?: throw FeedbackError("invalid_parameter", "outcome must be works or still_broken.") }
        if (text == null && to == null) throw FeedbackError("invalid_parameter", "Send a note, an outcome (works or still_broken), or both.")
        val hourAgo = (clock() - 3_600_000L).toString()
        val recent = db.rawQuery("SELECT COUNT(*) FROM history WHERE report_id = ? AND actor = ? AND at > ?",
            arrayOf(id.toString(), Actor.REPORTER.id, hourAgo)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (recent >= FeedbackLimits.FOLLOWUPS_PER_HOUR_PER_ISSUE)
            throw FeedbackError("rate_limited", "At most ${FeedbackLimits.FOLLOWUPS_PER_HOUR_PER_ISSUE} follow-ups per hour on one report.")
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

    /** Whether a new history entry goes to the Bridgit team: never the team's own moves, and only for issues that go too. */
    private fun historyUploadState(r: Issue, actor: Actor): String = when {
        actor == Actor.TEAM -> UploadState.SKIPPED
        !uploadEnabled() -> UploadState.OFF
        r.upload == UploadState.PENDING || r.upload == UploadState.SENT -> UploadState.PENDING
        else -> UploadState.SKIPPED
    }

    private fun historyQueued() = synchronized(historyAdded) { historyAdded.toList() }.forEach { runCatching { it() } }

    /** FBK-14: issues waiting to go to the Bridgit team, oldest first. */
    fun pendingUploads(limit: Int = 20): List<Issue> =
        db.query("reports", null, "upload_state = ?", arrayOf(UploadState.PENDING), null, null, "id ASC", limit.toString())
            .use { c -> buildList { while (c.moveToNext()) add(row(c, emptyList())) } }

    fun markUploaded(id: Long, remoteId: Long) = setUpload(id, ContentValues().apply {
        put("upload_state", UploadState.SENT); put("remote_id", remoteId); put("uploaded_at", clock()); putNull("upload_error")
    })

    /** [permanent] = the backend refused the issue itself; it won't be retried. */
    fun markUploadError(id: Long, error: String, permanent: Boolean) = setUpload(id, ContentValues().apply {
        put("upload_error", error.take(300)); if (permanent) put("upload_state", UploadState.FAILED)
    })

    /** The owner turned uploads off: anything not sent yet stays on the phone. */
    fun stopPendingUploads() {
        val off = ContentValues().apply { put("upload_state", UploadState.OFF) }
        db.update("reports", off, "upload_state = ?", arrayOf(UploadState.PENDING))
        db.update("history", off, "upload_state = ?", arrayOf(UploadState.PENDING))
    }

    /** A history entry waiting to go to the Bridgit team, with where its issue stands there. */
    data class PendingHistory(val issueId: Long, val issueUpload: String, val remoteId: Long?, val item: HistoryItem)

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

    /** Marks history entry [historyId] sent, failed (refused for good) or skipped (its issue never went). */
    fun markHistory(historyId: Long, state: String) {
        db.update("history", ContentValues().apply { put("upload_state", state) }, "id = ?", arrayOf(historyId.toString()))
    }

    private fun setUpload(id: Long, v: ContentValues) {
        db.update("reports", v, "id = ?", arrayOf(id.toString())); _changed.value = _changed.value + 1
    }

    fun imageFile(name: String): File? = File(dir, name).takeIf { it.exists() && it.parentFile == dir }

    /** Exact totals, independent of the issue list's display limit. */
    fun summary(kind: IssueKind): Pair<Int, Int> =
        count("kind = ?", kind.id) to count(
            """kind = ? AND (status = 'fixed' OR (status = 'confirmed' AND
                (SELECT h.status FROM history h WHERE h.report_id = reports.id
                 AND h.kind = 'status' AND h.status != 'confirmed' ORDER BY h.id DESC LIMIT 1) = 'fixed'))""",
            kind.id)

    fun counts(): Map<IssueStatus, Int> = db.rawQuery("SELECT status, COUNT(*) FROM reports GROUP BY status", null).use { c ->
        buildMap { while (c.moveToNext()) IssueStatus.of(c.getString(0))?.let { put(it, c.getInt(1)) } }
    }

    private fun count(where: String, vararg args: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM reports WHERE $where", args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Returns true when the entry is queued for the Bridgit team. */
    private fun addHistory(id: Long, s: IssueStatus, by: String, note: String?, at: Long, kind: String, actor: Actor, upload: String): Boolean {
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
        return HistoryItem(c.getLong(c.getColumnIndexOrThrow("at")), IssueStatus.of(s("status"))!!, s("by") ?: "", s("note"),
            id = c.getLong(c.getColumnIndexOrThrow("id")), kind = s("kind") ?: HistoryKind.STATUS, actor = Actor.of(s("actor")))
    }

    private fun row(c: Cursor, history: List<HistoryItem>): Issue {
        fun s(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getString(it) }
        fun l(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
        val imgs = JSONArray(s("images_json") ?: "[]")
        return Issue(l("id"), IssueKind.of(s("kind"))!!, s("description") ?: "", l("no_autofix") == 1L,
            IssueStatus.of(s("status"))!!, l("created_at"), l("updated_at"), s("capture_source") ?: "form",
            s("agent_id"), s("agent"), JSONObject(s("context_json") ?: "{}"), JSONArray(s("log_json") ?: "[]"),
            (0 until imgs.length()).map { imgs.getString(it) }, s("last_by"), history,
            upload = s("upload_state") ?: UploadState.SKIPPED,
            remoteId = c.getColumnIndexOrThrow("remote_id").let { if (c.isNull(it)) null else c.getLong(it) },
            uploadedAt = c.getColumnIndexOrThrow("uploaded_at").let { if (c.isNull(it)) 0 else c.getLong(it) },
            uploadError = s("upload_error"),
            applet = s("applet_json")?.let { runCatching { JSONObject(it) }.getOrNull() })
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "feedback.db", null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE reports (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, description TEXT NOT NULL,
                no_autofix INTEGER NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                capture_source TEXT NOT NULL, agent_id TEXT, agent TEXT, context_json TEXT, log_json TEXT, images_json TEXT, last_by TEXT)""")
            db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, report_id INTEGER NOT NULL, at INTEGER NOT NULL, status TEXT NOT NULL, by TEXT NOT NULL, note TEXT)")
            db.execSQL("CREATE INDEX reports_status ON reports(status, id)")
            db.execSQL("CREATE INDEX history_report ON history(report_id, id)")
            addUploadColumns(db)
            addHistoryColumns(db)
            addAppletColumn(db)
        }
        /** Upgrades add only what is missing; backfills run only when the columns are new, so a kept newer file (onDowngrade) is not rewritten. */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2 && addUploadColumns(db)) {
                // Issues filed before uploads existed stay on the phone; only new ones are sent.
                db.execSQL("UPDATE reports SET upload_state = '${UploadState.SKIPPED}'")
            }
            if (oldVersion < 3 && addHistoryColumns(db)) {
                // History from before this version stays on the phone too.
                db.execSQL("UPDATE history SET upload_state = '${UploadState.SKIPPED}', kind = '${HistoryKind.STATUS}'")
            }
            if (oldVersion < 4) addAppletColumn(db)
        }
        /** C0018: an older build installed over a newer one keeps the newer file (its columns are additive). Android's default throws and stops the app starting. */
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        /** v3 (FBK-12a, FBK-14 history upload): comments, who moved it, and where each entry stands with the team. True when they were new. */
        private fun addHistoryColumns(db: SQLiteDatabase): Boolean {
            val added = Schema.addColumn(db, "history", "upload_state", "TEXT")
            Schema.addColumn(db, "history", "kind", "TEXT")
            Schema.addColumn(db, "history", "actor", "TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS history_upload ON history(upload_state, id)")
            return added
        }
        /** v4 (A0178): what an applet request asks for, as JSON; null for bugs, features and older issues. */
        private fun addAppletColumn(db: SQLiteDatabase) = Schema.addColumn(db, "reports", "applet_json", "TEXT")
        /** v2 (FBK-14): where each issue stands with the Bridgit team's backend. True when they were new. */
        private fun addUploadColumns(db: SQLiteDatabase): Boolean {
            val added = Schema.addColumn(db, "reports", "upload_state", "TEXT")
            Schema.addColumn(db, "reports", "remote_id", "INTEGER")
            Schema.addColumn(db, "reports", "uploaded_at", "INTEGER")
            Schema.addColumn(db, "reports", "upload_error", "TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS reports_upload ON reports(upload_state, id)")
            return added
        }
    }
}
