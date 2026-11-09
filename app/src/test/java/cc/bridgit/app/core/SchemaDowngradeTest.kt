package cc.bridgit.app.core

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import cc.bridgit.app.feedback.FeedbackStore
import cc.bridgit.app.interact.InteractionRules
import cc.bridgit.app.interact.InteractionStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * C0018: an older build installed over a newer one found a newer database version on disk, Android's default
 * onDowngrade threw, and BridgeApp.onCreate crashed on every start. The stores now keep the newer file, and a
 * later upgrade adds only what is missing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SchemaDowngradeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun clean() { app.deleteDatabase("interactions.db"); app.deleteDatabase("feedback.db") }

    private fun stamp(name: String, version: Int) =
        SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = version }
    private fun version(name: String) =
        SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
    private fun card(title: String) = InteractionRules.parse(JSONObject().put("kind", "html").put("title", title).put("html", "<p>x</p>"))

    @Test fun interactionsWrittenByANewerBuildOpenAndKeepTheirRows() {
        val id = InteractionStore(app).create("a1", "Musey", card("Kept"), null).id
        stamp("interactions.db", 99)
        val s = InteractionStore(app) // threw "Can't downgrade database from version 99 to 5" before
        assertEquals("Kept", s.get(id)!!.title)
        assertEquals("Next", s.get(s.create("a1", "Musey", card("Next"), null).id)!!.title)
    }

    @Test fun aKeptNewerInteractionsFileUpgradesAgainWithoutDuplicateColumns() {
        val id = InteractionStore(app).create("a1", "Musey", card("Kept"), null).id
        // What an older (version 1) build leaves after keeping the file: every column, version 1.
        stamp("interactions.db", 1)
        val s = InteractionStore(app)
        assertEquals("Kept", s.get(id)!!.title)
        assertEquals(5, version("interactions.db"))
    }

    @Test fun issuesWrittenByANewerBuildOpenAndUploadsAreNotReset() {
        val store = FeedbackStore(app, uploadEnabled = { true })
        val issue = store.create(cc.bridgit.app.feedback.IssueKind.BUG, "Kept", false, emptyList(), JSONArray(), JSONObject(), "form")
        stamp("feedback.db", 99)
        assertEquals("Kept", FeedbackStore(app, uploadEnabled = { true }).get(issue.id)!!.description)
        // Kept by an older build, then upgraded again: the one-time "skip old uploads" backfill must not run.
        stamp("feedback.db", 1)
        assertEquals("Kept", FeedbackStore(app, uploadEnabled = { true }).get(issue.id)!!.description)
        val state = SQLiteDatabase.openDatabase(app.getDatabasePath("feedback.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT upload_state FROM reports WHERE id = ${issue.id}", null).use { c -> c.moveToFirst(); c.getString(0) }
        }
        assertEquals("pending", state)
        assertEquals(3, version("feedback.db"))
    }

    /** Any store past version 1 must keep a newer file instead of crashing startup. */
    @Test fun everyVersionedHelperHandlesDowngrade() {
        val helper = Regex("""SQLiteOpenHelper\([^)]*?,\s*null\s*,\s*(\d+)\s*\)""")
        val offenders = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.mapNotNull { f ->
            val text = f.readText()
            val versions = helper.findAll(text).map { it.groupValues[1].toInt() }.toList()
            f.name.takeIf { versions.any { it > 1 } && !text.contains("override fun onDowngrade") }
        }.toList()
        assertTrue("Stores without onDowngrade: $offenders", offenders.isEmpty())
    }
}
