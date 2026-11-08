package cc.bridgit.app.interact

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A0074: `presentation` is stored with the interaction, read back, and old databases upgrade to standard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresentationStoreTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun clean() { context.deleteDatabase("interactions.db") }
    private fun card(p: String?) = JSONObject().put("kind", "html").put("title", "Slides").put("html", "<p>x</p>").apply { p?.let { put("presentation", it) } }

    @Test fun presentationRoundTripsThroughTheStore() {
        val s = InteractionStore(context)
        val full = s.create("a1", "Musey", InteractionRules.parse(card("full_screen")), null)
        val plain = s.create("a1", "Musey", InteractionRules.parse(card(null)), null)
        assertEquals(Presentation.FULL_SCREEN, s.get(full.id)!!.presentation)
        assertEquals(Presentation.STANDARD, s.get(plain.id)!!.presentation)
        assertEquals("full_screen", s.getFor("a1", full.id)!!.toJson { it.toString() }.getString("presentation"))
        assertEquals(listOf("standard", "full_screen"), s.listFor("a1", null, 10).map { it.toJson { t -> t.toString() }.getString("presentation") })
        // a fresh store over the same file still reads it
        assertEquals(Presentation.FULL_SCREEN, InteractionStore(context).get(full.id)!!.presentation)
    }

    @Test fun versionFourDatabaseUpgradesWithStandardPresentation() {
        val path = context.getDatabasePath("interactions.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("""CREATE TABLE interactions (id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id TEXT NOT NULL, agent TEXT, kind TEXT NOT NULL,
                title TEXT NOT NULL, text TEXT, image TEXT, controls_json TEXT, html TEXT, priority TEXT, speak INTEGER, status TEXT NOT NULL,
                answer_json TEXT, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, seen_at INTEGER, answered_at INTEGER, closed_at INTEGER,
                stream_text TEXT, stream_done INTEGER, stack TEXT, stack_index INTEGER, swipe_json TEXT, no_takeover INTEGER NOT NULL DEFAULT 0,
                follow_up INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("CREATE INDEX interactions_stack ON interactions(agent_id, stack)")
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id TEXT NOT NULL, type TEXT NOT NULL, at INTEGER NOT NULL, data TEXT NOT NULL)")
            val now = System.currentTimeMillis()
            db.execSQL("INSERT INTO interactions (agent_id, agent, kind, title, controls_json, html, status, created_at, expires_at) VALUES ('a1','Musey','html','Old','[]','<p>x</p>','pending',$now,${now + 3_600_000})")
            db.version = 4
        }
        val s = InteractionStore(context)
        assertEquals(Presentation.STANDARD, s.listAll().single().presentation)
        assertEquals(Presentation.FULL_SCREEN, s.create("a1", "Musey", InteractionRules.parse(card("full_screen")), null).presentation)
    }
}
