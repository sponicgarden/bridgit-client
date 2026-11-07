package cc.bridgit.app.interact

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** AGT-12: removing an agent cancels its open interactions, quietly (no event to the removed agent). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemovedAgentInteractionsTest {
    private var now = 1_000_000L
    private lateinit var store: InteractionStore
    private val heard = mutableListOf<Pair<Long, String>>()

    @Before fun setup() {
        RuntimeEnvironment.getApplication().deleteDatabase("interactions.db")
        store = InteractionStore(RuntimeEnvironment.getApplication()) { now }
        store.addListener { i, what -> heard += i.id to what }
    }
    @After fun cleanup() { RuntimeEnvironment.getApplication().deleteDatabase("interactions.db") }

    private fun draft(kind: IKind, expiresInS: Long = 3600) = Draft(kind, "Title", "Text",
        if (kind == IKind.QUESTION) listOf(Control.Button("ok", "OK", "primary")) else emptyList(),
        "normal", false, expiresInS, null, null, if (kind == IKind.HTML) "<p>card</p>" else null)

    @Test fun removingAnAgentCancelsEverythingStillWaitingAndSendsNoEvent() {
        val expired = store.create("a", "Alpha", draft(IKind.QUESTION, expiresInS = 10), null)
        now += 20_000
        store.expireDue()
        assertEquals(IStatus.EXPIRED, store.get(expired.id)!!.status)
        val question = store.create("a", "Alpha", draft(IKind.QUESTION), null)
        val card = store.create("a", "Alpha", draft(IKind.HTML), null).also { store.markSeen(it.id) }
        val notice = store.create("a", "Alpha", draft(IKind.NOTICE), null)
        val other = store.create("b", "Beta", draft(IKind.QUESTION), null)
        assertEquals(4, store.waitingQuestions().size)
        val eventsBefore = store.eventsFor("a", null, emptySet()).size
        heard.clear()

        assertEquals(4, store.closeAllFor("a"))

        for (i in listOf(expired, question, card, notice)) {
            val after = store.get(i.id)!!
            assertEquals(IStatus.CANCELLED, after.status)
            assertNotNull(after.closedAt)
        }
        assertEquals(listOf(other.id), store.waitingQuestions().map { it.id })
        assertEquals(1, store.unreadCount())
        assertEquals(IStatus.PENDING, store.get(other.id)!!.status)
        assertEquals(eventsBefore, store.eventsFor("a", null, emptySet()).size)
        assertEquals(setOf(expired.id, question.id, card.id, notice.id), heard.filter { it.second == "cancelled" }.map { it.first }.toSet())
        assertEquals(4, heard.size)
        assertEquals(setOf("b"), store.agentsWithOpen())
    }

    @Test fun closedOnesStayClosed() {
        val answered = store.create("a", "Alpha", draft(IKind.QUESTION), null)
        store.answer(answered.id, "ok", emptyMap())
        val dismissed = store.create("a", "Alpha", draft(IKind.NOTICE), null).also { store.dismiss(it.id) }
        heard.clear()
        assertEquals(0, store.closeAllFor("a"))
        assertEquals(IStatus.ANSWERED, store.get(answered.id)!!.status)
        assertEquals(IStatus.DISMISSED, store.get(dismissed.id)!!.status)
        assertTrue(heard.isEmpty())
    }
}
