package cc.bridgit.app.feedback

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Issues Tracking rename (8 Nov 2026): a phone updating from a build that called them "reports" keeps
 * its My issues history. The store still reads feedback.db as that build wrote it (tables `reports`
 * and `history`, schema version 3), so nothing needs moving.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class IssueStorageTest {
    @Test fun issuesSavedBeforeTheRenameStillShowWithTheirHistory() {
        val app = RuntimeEnvironment.getApplication()
        val file = app.getDatabasePath("feedback.db").apply { parentFile?.mkdirs() }
        // Exactly what the pre-rename build left on the phone.
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("""CREATE TABLE reports (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, description TEXT NOT NULL,
                no_autofix INTEGER NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                capture_source TEXT NOT NULL, agent_id TEXT, agent TEXT, context_json TEXT, log_json TEXT, images_json TEXT, last_by TEXT,
                upload_state TEXT, remote_id INTEGER, uploaded_at INTEGER, upload_error TEXT)""")
            db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, report_id INTEGER NOT NULL, at INTEGER NOT NULL, status TEXT NOT NULL, by TEXT NOT NULL, note TEXT, kind TEXT, actor TEXT, upload_state TEXT)")
            db.execSQL("""INSERT INTO reports (kind, description, no_autofix, status, created_at, updated_at, capture_source, context_json,
                log_json, images_json, last_by, upload_state, remote_id, uploaded_at)
                VALUES ('bug', 'Camera froze', 0, 'fixed', 1000, 3000, 'form', '{}', '[]', '[]', 'Bridgit team', 'sent', 23, 1500)""")
            db.execSQL("""INSERT INTO reports (kind, description, no_autofix, status, created_at, updated_at, capture_source, context_json,
                log_json, images_json, last_by, upload_state) VALUES ('feature', 'Dark map', 1, 'new', 2000, 2000, 'agent', '{}', '[]', '[]', 'Claude', 'pending')""")
            db.execSQL("INSERT INTO history (report_id, at, status, by, note, kind, actor, upload_state) VALUES (1, 1000, 'new', 'owner', NULL, 'status', 'owner', 'skipped')")
            db.execSQL("INSERT INTO history (report_id, at, status, by, note, kind, actor, upload_state) VALUES (1, 3000, 'fixed', 'Bridgit team', 'In 1.8.0', 'status', 'team', 'skipped')")
            db.version = 3
        }

        val store = FeedbackStore(app, uploadEnabled = { false })
        val all = store.list()
        assertEquals(listOf("Dark map", "Camera froze"), all.map { it.description })
        val bug = store.get(all.last().id)!!
        assertEquals(IssueStatus.FIXED, bug.status)
        assertEquals("M0023", bug.ref)
        assertEquals(listOf(IssueStatus.NEW, IssueStatus.FIXED), bug.history.map { it.status })
        assertEquals("In 1.8.0", bug.teamNote)
        assertEquals(1 to 0, store.summary(IssueKind.FEATURE))
        // New issues go into the same tables, after the old ones.
        val next = store.create(IssueKind.BUG, "After the update", false, emptyList(), org.json.JSONArray(), org.json.JSONObject(), "form")
        assertEquals(3L, next.id)
        assertEquals(3, store.list().size)
    }
}
