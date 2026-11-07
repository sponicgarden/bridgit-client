package cc.bridgit.app.interact

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #0005 card stacks: `stack`, `stack_index` and `swipe_answer` validation; a stack counts once against the open-question
 * cap; order, size and swipe answers in the store; and the v1 -> v2 database upgrade keeps existing rows readable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardStackTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context

    @Before fun clean() { context.deleteDatabase("interactions.db") }

    private fun card(stack: String? = "demo", extra: JSONObject.() -> Unit = {}) = JSONObject()
        .put("kind", "html").put("title", "Step").put("html", "<p>hi</p>").apply { stack?.let { put("stack", it) } }.apply(extra)
    private fun bad(b: JSONObject) = try { InteractionRules.parse(b); fail("accepted $b") } catch (_: InteractionError) {}

    @Test fun noTakeoverDefaultsToFalseAndRequiresABoolean() {
        assertFalse(InteractionRules.parse(card()).noTakeover)
        assertTrue(InteractionRules.parse(card { put("no_takeover", true) }).noTakeover)
        assertFalse(InteractionRules.parse(card { put("no_takeover", false) }).noTakeover)
        for (invalid in listOf("true", 1, JSONObject.NULL)) bad(card { put("no_takeover", invalid) })
    }

    @Test fun noTakeoverSurvivesStorageAndNotificationListener() {
        val s = store()
        var delivered: Interaction? = null
        s.addListener { i, what -> if (what == "created") delivered = i }
        val i = post(s, card { put("no_takeover", true) })
        assertTrue(delivered!!.noTakeover)
        assertTrue(s.get(i.id)!!.noTakeover)
        assertTrue(s.getFor("a1", i.id)!!.toJson { it.toString() }.getBoolean("no_takeover"))
        assertFalse(post(s, card()).noTakeover)
    }

    @Test fun followUpParsesStoresAndTellsTheAgentHowLongThePhoneWaits() {
        assertFalse(InteractionRules.parse(card()).followUp)
        for (invalid in listOf("true", 1, JSONObject.NULL)) bad(card { put("follow_up", invalid) })
        bad(JSONObject().put("kind", "text").put("title", "Live").put("follow_up", true))
        val s = store()
        val i = post(s, card(stack = null) { put("follow_up", true) })
        assertTrue(s.get(i.id)!!.followUp)
        assertTrue(s.getFor("a1", i.id)!!.toJson { it.toString() }.getBoolean("follow_up"))
        s.answerCard(i.id, JSONObject().put("ok", true))
        val answered = s.eventsFor("a1", null, setOf("interaction.answered")).last()
        assertEquals(NotifRules.FOLLOW_UP_HOLD_MS / 1000, answered.data.getLong("follow_up_seconds"))
        val j = post(s, card(stack = null) { put("follow_up", true) })
        s.dismiss(j.id)
        assertEquals(NotifRules.FOLLOW_UP_HOLD_MS / 1000, s.eventsFor("a1", null, setOf("interaction.dismissed")).last().data.getLong("follow_up_seconds"))
        // Without follow_up, or with no_takeover (it never holds the screen), the events say nothing about it.
        val k = post(s, card(stack = null))
        s.dismiss(k.id)
        assertFalse(s.eventsFor("a1", null, setOf("interaction.dismissed")).last().data.has("follow_up_seconds"))
        val q = post(s, card(stack = null) { put("follow_up", true).put("no_takeover", true) })
        s.dismiss(q.id)
        assertFalse(s.eventsFor("a1", null, setOf("interaction.dismissed")).last().data.has("follow_up_seconds"))
    }

    @Test fun parsesStackFields() {
        val d = InteractionRules.parse(card { put("stack_index", 2).put("swipe_answer", JSONObject().put("left", "Skip").put("right", "Keep")) })
        assertEquals("demo", d.stack)
        assertEquals(2, d.stackIndex)
        assertEquals(SwipeAnswer("Skip", "Keep"), d.swipe)
        val single = InteractionRules.parse(card(stack = null))
        assertNull(single.stack); assertNull(single.swipe)
    }

    @Test fun refusesBadStackFields() {
        bad(card("has space"))
        bad(card("x".repeat(33)))
        bad(card { put("stack_index", 10) })
        bad(card { put("stack_index", -1) })
        bad(card { put("stack_index", 1.5) })
        bad(card(stack = null) { put("stack_index", 0) })
        bad(card(stack = null) { put("swipe_answer", JSONObject().put("left", "a").put("right", "b")) })
        bad(card { put("swipe_answer", JSONObject().put("left", "a")) })
        bad(card { put("swipe_answer", JSONObject().put("left", "a").put("right", "x".repeat(21))) })
        bad(JSONObject().put("kind", "notice").put("title", "Hi").put("stack", "demo"))
    }

    private fun store() = InteractionStore(context)
    private fun post(s: InteractionStore, b: JSONObject, agent: String = "a1") = s.create(agent, "Musey", InteractionRules.parse(b), null)

    @Test fun stackOrderSizeAndCap() {
        val s = store()
        val c0 = post(s, card())
        val c2 = post(s, card { put("stack_index", 5) })
        val c1 = post(s, card { put("stack_index", 1) })
        assertEquals(listOf(c0.id, c1.id, c2.id), s.stackOf(c0).map { it.id })
        assertEquals(3, s.get(c0.id)!!.stackSize)
        assertEquals("demo", s.get(c1.id)!!.toJson { it.toString() }.getString("stack"))
        // Default index goes after the highest one so far.
        assertEquals(6, post(s, card()).stackIndex)
        // The stack is one open question: four more single cards fit, the fifth doesn't, but the stack can still grow.
        repeat(4) { post(s, card(stack = null)) }
        try { post(s, card(stack = null)); fail("cap not enforced") } catch (e: InteractionError) { assertEquals("too_many_open", e.code) }
        post(s, card())
        // Ten cards per stack id at most.
        repeat(5) { post(s, card()) }
        try { post(s, card()); fail("stack size not enforced") } catch (e: InteractionError) { assertEquals("invalid_parameter", e.code) }
        // The tab badge counts the stack once: 1 stack + 4 single cards.
        assertEquals(5, s.unreadCount())
        // Another agent's same stack id is a different stack.
        assertEquals(1, post(s, card(), agent = "a2").stackSize)
    }

    @Test fun swipeAnswersAndStackDismiss() {
        val s = store()
        val a = post(s, card { put("swipe_answer", JSONObject().put("left", "Skip").put("right", "Keep")) })
        val b = post(s, card())
        val answered = s.answerSwipe(a.id, "right")
        assertEquals(IStatus.ANSWERED, answered.status)
        assertEquals("right", answered.answer!!.getJSONObject("data").getString("swipe"))
        assertEquals("Keep", answered.answer!!.getJSONObject("data").getString("label"))
        val ev = s.eventsFor("a1", null, setOf("interaction.answered")).last()
        assertEquals("demo", ev.data.getString("stack"))
        try { s.answerSwipe(b.id, "left"); fail("swiped a card without swipe_answer") } catch (_: InteractionError) {}
        s.dismissStack(b)
        assertEquals(IStatus.DISMISSED, s.get(b.id)!!.status)
        assertEquals(IStatus.ANSWERED, s.get(a.id)!!.status)
    }

    @Test fun upgradesVersionOneDatabase() = upgradesDatabase(1)

    @Test fun upgradesVersionTwoDatabase() = upgradesDatabase(2)

    private fun upgradesDatabase(version: Int) {
        val path = context.getDatabasePath("interactions.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("""CREATE TABLE interactions (id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id TEXT NOT NULL, agent TEXT, kind TEXT NOT NULL,
                title TEXT NOT NULL, text TEXT, image TEXT, controls_json TEXT, html TEXT, priority TEXT, speak INTEGER, status TEXT NOT NULL,
                answer_json TEXT, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, seen_at INTEGER, answered_at INTEGER, closed_at INTEGER,
                stream_text TEXT, stream_done INTEGER)""")
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id TEXT NOT NULL, type TEXT NOT NULL, at INTEGER NOT NULL, data TEXT NOT NULL)")
            val now = System.currentTimeMillis()
            db.execSQL("INSERT INTO interactions (agent_id, agent, kind, title, controls_json, html, status, created_at, expires_at) VALUES ('a1','Musey','html','Old','[]','<p>x</p>','pending',$now,${now + 3_600_000})")
            if (version == 2) {
                db.execSQL("ALTER TABLE interactions ADD COLUMN stack TEXT")
                db.execSQL("ALTER TABLE interactions ADD COLUMN stack_index INTEGER")
                db.execSQL("ALTER TABLE interactions ADD COLUMN swipe_json TEXT")
                db.execSQL("CREATE INDEX interactions_stack ON interactions(agent_id, stack)")
            }
            db.version = version
        }
        val s = store()
        val old = s.listAll().single()
        assertEquals("Old", old.title)
        assertFalse(old.noTakeover)
        assertNull(old.stack)
        assertEquals(1, old.stackSize)
        assertTrue(post(s, card()).stack == "demo")
    }
}
