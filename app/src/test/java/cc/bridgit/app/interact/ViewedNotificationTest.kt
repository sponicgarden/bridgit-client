package cc.bridgit.app.interact

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import cc.bridgit.app.R
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewedNotificationTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private lateinit var store: InteractionStore
    private lateinit var notifications: NotificationManager

    @Before fun setup() {
        context.deleteDatabase("interactions.db")
        store = InteractionStore(context)
        notifications = context.getSystemService(NotificationManager::class.java)
        notifications.cancelAll()
        notifications.createNotificationChannel(NotificationChannel("viewed-test", "Test", NotificationManager.IMPORTANCE_DEFAULT))
        store.addListener { i, what ->
            if (what != "created") InteractionNotifier.onChange(context, i, what)
        }
    }

    @Test fun viewingEveryCardKindClearsOnlyItsAlertAndKeepsAnswersAvailable() {
        val untouched = store.create("agent", "Agent", InteractionRules.parse(JSONObject()
            .put("kind", "notice").put("title", "Other")), null)
        alert(untouched.id)
        for (kind in listOf("notice", "question", "html", "pieces", "text")) {
            val body = JSONObject().put("kind", kind).put("title", "Card")
            when (kind) {
                "question" -> body.put("controls", org.json.JSONArray("[{\"type\":\"button\",\"id\":\"ok\",\"label\":\"OK\"}]"))
                "html" -> body.put("html", "<p>Card</p>")
                "pieces" -> body.put("pieces", org.json.JSONArray("[{\"type\":\"text\",\"text\":\"Card\"}]"))
            }
            val i = store.create("agent", "Agent", InteractionRules.parse(body), null)
            alert(i.id)
            assertTrue(active(i.id))
            store.markSeen(i.id)
            assertFalse("Opened $kind alert", active(i.id))
            assertTrue(active(untouched.id))
            assertEquals(IStatus.SEEN, store.get(i.id)!!.status)
            assertTrue(store.get(i.id)!!.status.open)
            store.markSeen(i.id)
            assertFalse(active(i.id))
            if (kind == "html") {
                store.answerCard(i.id, JSONObject().put("ok", true))
                assertEquals(IStatus.ANSWERED, store.get(i.id)!!.status)
            } else if (kind == "question") {
                store.answer(i.id, "ok", emptyMap())
                assertEquals(IStatus.ANSWERED, store.get(i.id)!!.status)
            }
        }
    }

    @Test fun explicitReminderRestoresSeenAlertWithoutChangingTheCard() {
        val i = store.create("agent", "Agent", InteractionRules.parse(JSONObject()
            .put("kind", "html").put("title", "Card").put("html", "<p>Card</p>")), null)
        store.markSeen(i.id)
        val before = store.get(i.id)!!
        store.renotify("agent", i.id)
        assertTrue(active(i.id))
        val after = store.get(i.id)!!
        assertEquals(IStatus.SEEN, after.status)
        assertEquals(before.seenAt, after.seenAt)
        assertEquals(before.expiresAt, after.expiresAt)
        assertNull(after.answer)
        assertEquals("interaction.renotified", store.eventsFor("agent", null, emptySet()).last().type)
        // A second visit clears even a previously seen card's explicit reminder.
        InteractionNotifier.cancel(context, i.id)
        assertFalse(active(i.id))
        store.answerCard(i.id, JSONObject().put("ok", true))
        assertEquals(IStatus.ANSWERED, store.get(i.id)!!.status)
        refused("conflict") { store.renotify("agent", i.id) }
        assertFalse(active(i.id))
    }

    @Test fun reminderIsScopedAndCannotReviveClosedOrExpiredCards() {
        var now = 1_000_000L
        val timed = InteractionStore(context) { now }
        val d = InteractionRules.parse(JSONObject().put("kind", "notice").put("title", "Notice").put("expires_in_seconds", 60))
        val i = timed.create("agent", "Agent", d, null)
        refused("not_found") { timed.renotify("other", i.id) }
        refused("not_found") { timed.renotify("agent", i.id + 1000) }
        timed.renotify("agent", i.id)
        assertEquals(IStatus.PENDING, timed.get(i.id)!!.status)
        now += 61_000
        refused("conflict") { timed.renotify("agent", i.id) }
        assertEquals(IStatus.EXPIRED, timed.get(i.id)!!.status)
        val cancelled = timed.create("agent", "Agent", d, null)
        timed.cancel("agent", cancelled.id)
        refused("conflict") { timed.renotify("agent", cancelled.id) }
        val dismissed = timed.create("agent", "Agent", d, null)
        timed.dismiss(dismissed.id)
        refused("conflict") { timed.renotify("agent", dismissed.id) }
    }

    private fun refused(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") }
        catch (e: InteractionError) { assertEquals(code, e.code) }
    }

    private fun active(id: Long) = notifications.activeNotifications.any { it.id == InteractionNotifier.notifId(id) }
    private fun alert(id: Long) = notifications.notify(InteractionNotifier.notifId(id),
        NotificationCompat.Builder(context, "viewed-test").setSmallIcon(R.drawable.ic_notif).setContentTitle("Card").build())
}
