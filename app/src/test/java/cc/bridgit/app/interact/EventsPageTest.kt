package cc.bridgit.app.interact

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** GET /v1/events paging: without a cursor the newest page, oldest first; with one, what follows it; `limit` caps both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventsPageTest {
    private var now = 1_000_000L
    private lateinit var store: InteractionStore

    @Before fun setup() {
        RuntimeEnvironment.getApplication().deleteDatabase("interactions.db")
        store = InteractionStore(RuntimeEnvironment.getApplication()) { now }
    }
    @After fun cleanup() { RuntimeEnvironment.getApplication().deleteDatabase("interactions.db") }

    @Test fun noCursorGivesTheNewestPageOldestFirst() {
        val ids = (1..60).map { now += 1000; store.event("a", "test.tick", JSONObject().put("n", it)).id }
        store.event("b", "test.tick", JSONObject())
        val page = store.eventsFor("a", null, emptySet()).map { it.id }
        assertEquals(50, page.size)
        assertEquals("the newest 50", ids.takeLast(50), page)
        assertEquals(page.sorted(), page)
        assertEquals("limit takes from the new end", ids.takeLast(2), store.eventsFor("a", null, emptySet(), 2).map { it.id })
    }

    @Test fun cursorGivesWhatFollowsIt() {
        val ids = (1..10).map { now += 1000; store.event("a", "test.tick", JSONObject()).id }
        assertEquals(ids.drop(4), store.eventsFor("a", ids[3], emptySet()).map { it.id })
        assertEquals(ids.drop(4).take(3), store.eventsFor("a", ids[3], emptySet(), 3).map { it.id })
        assertEquals(emptyList<Long>(), store.eventsFor("a", ids.last(), emptySet()).map { it.id })
    }
}
