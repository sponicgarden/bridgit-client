package cc.bridgit.app.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A0186: the latency ring, its aggregates and the curated record. */
class ApiLatencyTest {
    private var now = 1_000_000L
    private fun call(api: String, ms: Long, http: Int = 200, ts: Long = now, ctx: JSONObject? = null) = ApiLatency.Call(
        ts = ts, api = api, method = "GET", path = "/v1/$api", via = "http", requestId = "r$ms", agentId = "a1", agent = "Agent",
        durationMs = ms, http = http, result = if (http < 400) "ok" else "err", bytesIn = 0, bytesOut = 10, inFlight = 1, context = ctx)

    @Test fun keepsOnlyTheNewestCapacityCalls() {
        val t = ApiLatency(capacity = 3, clock = { now })
        (1L..5L).forEach { t.record(call("getHealth", it)) }
        assertEquals(listOf(3L, 4L, 5L), t.calls().map { it.durationMs })
    }

    @Test fun dropsCallsOlderThanMaxAge() {
        val t = ApiLatency(maxAgeMs = 1000, clock = { now })
        t.record(call("getHealth", 1, ts = now - 2000))
        t.record(call("getHealth", 2, ts = now - 10))
        assertEquals(listOf(2L), t.calls().map { it.durationMs })
    }

    @Test fun aggregatesCountMedianP95MaxErrorsAndSlow() {
        val calls = (1L..20L).map { call("getHealth", it * 100) } + call("getHealth", 5000, http = 500) + call("getLog", 7)
        val a = ApiLatency.aggregate(calls, slowMs = 1500)
        assertEquals(2, a.length())
        val h = a.getJSONObject(0)
        assertEquals("getHealth", h.getString("api"))
        assertEquals(21, h.getInt("count"))
        assertEquals(1, h.getInt("errors"))
        assertEquals(1100L, h.getLong("median_ms"))
        assertEquals(2000L, h.getLong("p95_ms"))
        assertEquals(5000L, h.getLong("max_ms"))
        assertEquals(7, h.getInt("slow")) // 1500..2000 (6) and 5000
        val l = a.getJSONObject(1)
        assertEquals(1, l.getInt("count")); assertEquals(7L, l.getLong("median_ms")); assertEquals(7L, l.getLong("p95_ms")); assertEquals(7L, l.getLong("max_ms"))
    }

    @Test fun medianOfTwoIsTheLowerNearestRank() {
        val a = ApiLatency.aggregate(listOf(call("x", 10), call("x", 30)), 1000).getJSONObject(0)
        assertEquals(10L, a.getLong("median_ms")); assertEquals(30L, a.getLong("p95_ms"))
    }

    @Test fun recordIsCuratedAndCarriesContext() {
        val ctx = JSONObject().put("network", JSONObject().put("type", "wifi").put("metered", false))
        val t = ApiLatency(context = { ctx }, clock = { now })
        t.record(call("getHealth", 1200, http = 503))
        val j = ApiLatency.json(t.calls().single()) { "t$it" }
        assertEquals("error", j.getString("outcome"))
        assertEquals("wifi", j.getJSONObject("context").getJSONObject("network").getString("type"))
        val allowed = setOf("ts", "api", "method", "path", "via", "request_id", "agent", "agent_id", "duration_ms", "outcome", "http",
            "result", "bytes_in", "bytes_out", "in_flight", "ran_on", "context")
        assertEquals(allowed, j.keySet())
        assertFalse(j.has("params") || j.has("source_ip") || j.has("body"))
    }

    @Test fun contextIsSampledAtMostEveryTtl() {
        var n = 0
        val t = ApiLatency(context = { n++; JSONObject().put("n", n) }, contextTtlMs = 10_000, clock = { now })
        val first = t.contextNow()
        now += 5_000
        assertSame(first, t.contextNow())
        now += 6_000
        assertEquals(2, t.contextNow()!!.getInt("n"))
    }

    @Test fun aFailingSamplerNeverBreaksRecording() {
        val t = ApiLatency(context = { throw IllegalStateException("no") }, clock = { now })
        t.record(call("getHealth", 3))
        assertTrue(t.calls().single().context == null)
    }

    @Test fun inFlightCountsBeginAndEndAndNeverGoesNegative() {
        val t = ApiLatency()
        assertEquals(1, t.begin()); assertEquals(2, t.begin())
        t.end(); t.end(); t.end()
        assertEquals(0, t.running)
    }
}
