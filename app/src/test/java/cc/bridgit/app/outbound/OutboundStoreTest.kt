package cc.bridgit.app.outbound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboundStoreTest {
    private fun entry(ts: Long, host: String, agentId: String?, agent: String, cats: List<String>, status: Int = 200) =
        OutboundEntry(ts = ts, host = host, service = host, method = "GET", path = "/", purpose = "p", agentId = agentId, agent = agent,
            categories = cats, bytesSent = 10, bytesReceived = 20, status = status, error = null, durationMs = 3, transport = "https",
            requestPreview = "{\"a\":1}", requestDigest = "d")

    private fun store(r: OutboundStore.Retention = OutboundStore.Retention()) = OutboundStore(RuntimeEnvironment.getApplication(), r, null)

    @Test fun storesAndFiltersByAgentDestinationCategoryAndResult() {
        val s = store()
        val now = System.currentTimeMillis()
        s.append(entry(now, "api.met.no", "a1", "Claude", listOf("location_1km")))
        s.append(entry(now, "x.workers.dev", null, "phone", listOf("install_id")))
        s.append(entry(now, "x.workers.dev", "a2", "Grok", listOf("agent_state", "install_id"), status = 500))
        s.flush()
        assertEquals(3, s.count(OutboundQuery()))
        assertEquals(listOf("a1"), s.query(OutboundQuery(agentIds = listOf("a1"))).map { it.agentId })
        assertEquals(listOf("phone"), s.query(OutboundQuery(actors = listOf("phone"))).map { it.agent })
        assertEquals(2, s.count(OutboundQuery(destination = "workers")))
        assertEquals(2, s.count(OutboundQuery(categories = listOf("install_id"))))
        assertEquals(0, s.count(OutboundQuery(categories = listOf("install"))))  // whole category names only
        assertEquals(1, s.count(OutboundQuery(result = "error")))
        val back = s.query(OutboundQuery(agentIds = listOf("a2"))).single()
        assertEquals(listOf("agent_state", "install_id"), back.categories); assertEquals("{\"a\":1}", back.requestPreview)
        // Scoped to a1, a filter asking for a2 finds nothing.
        assertEquals(0, s.count(OutboundQuery(agentIds = listOf("a2")).scopedTo(listOf("a1"))))
        assertEquals(1, s.count(OutboundQuery().scopedTo(listOf("a1"))))
        // First-seen honours the agent scope: a1 never sent to workers.dev.
        assertEquals(setOf("api.met.no"), s.firstSeen(OutboundQuery().scopedTo(listOf("a1"))).keys)
        assertEquals(setOf("api.met.no", "x.workers.dev"), s.firstSeen(OutboundQuery()).keys)
    }

    @Test fun pagesWithCursor() {
        val s = store()
        repeat(7) { s.append(entry(1000L + it, "h", null, "phone", emptyList())) }
        s.flush()
        val first = s.query(OutboundQuery(limit = 5))
        assertEquals(5, first.size)
        val rest = s.query(OutboundQuery(limit = 5, cursor = first.last().id))
        assertEquals(2, rest.size)
        assertTrue(rest.all { it.id < first.last().id })
        assertEquals(7, s.all(OutboundQuery()).size)
        val light = s.all(OutboundQuery(), light = true)
        assertEquals(7, light.size); assertEquals(null, light[0].requestPreview); assertEquals("d", light[0].requestDigest)
    }

    @Test fun retentionKeepsTheNewestEntriesAndDropsOldDays() {
        val s = store(OutboundStore.Retention(maxEntries = 5, maxDays = 1))
        val now = System.currentTimeMillis()
        s.append(entry(now - 3 * 86_400_000L, "old", null, "phone", emptyList()))
        repeat(8) { s.append(entry(now, "new$it", null, "phone", emptyList())) }
        s.trimNow()
        val left = s.query(OutboundQuery())
        assertEquals(5, left.size)
        assertTrue(left.none { it.host == "old" })
        assertEquals("new7", left.first().host)
    }
}
