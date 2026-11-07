package cc.bridgit.app.log

import cc.bridgit.app.core.Keys
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ways the log query and redaction logic can fail:
 * Query: user text pasted into SQL (injection); LIKE wildcards in a search not escaped; unknown channel,
 *   kind, level or agent silently ignored (returns everything); "since" treating an id as a time or the
 *   other way round; cursor going the wrong direction for asc/desc; limit not capped; the "Bridgit's own
 *   entries" filter (null agent_id) lost when combined with real agents.
 * Redaction: a key in a value or a nested value survives; a field named key/token/secret/code survives;
 *   base64 media stored; long strings not truncated; data over 4 KB stored.
 * Percentiles: off by one; empty input crashes.
 */
class LogLogicTest {
    private val agents = mapOf("Grok" to "a1", "a1" to "a1", "Claude" to "c2")
    private fun parse(vararg kv: Pair<String, String>) = LogQuery.parse(kv.toMap(), { agents[it] })

    @Test fun valuesAreBoundNeverPasted() {
        val q = parse("q" to "x'; DROP TABLE log; --", "endpoint" to "/v1/cam%", "agent" to "Grok")
        val (where, args) = q.where()
        assertFalse(where.contains("DROP"))
        assertFalse(where.contains("/v1/cam"))
        assertTrue(args.contains("/v1/cam\\%%"))
        assertTrue(args.any { it.contains("DROP TABLE") })
        assertTrue(args.contains("a1"))
    }

    @Test fun badFiltersAreErrors() {
        for (bad in listOf("channel" to "secret", "kind" to "nope", "level" to "loud", "agent" to "Nobody",
            "limit" to "0", "limit" to "501", "order" to "up", "since" to "yesterday", "cursor" to "zzz", "since" to "-1"))
            try { parse(bad); fail("accepted $bad") } catch (_: QueryError) {}
    }

    @Test fun sinceIdVsTime() {
        assertEquals(48213L, parse("since" to "48213").sinceId)
        assertNull(parse("since" to "48213").sinceTs)
        val t = parse("since" to "2026-10-27T00:12:03+01:00")
        assertEquals(1793056323000L, t.sinceTs)
        assertNull(t.sinceId)
    }

    @Test fun cursorDirection() {
        val c = LogQuery.encodeCursor(1234)
        assertEquals(1234L, LogQuery.decodeCursor(c))
        val desc = parse("cursor" to c).where()
        assertTrue(desc.first.contains("id < ?"))
        val asc = parse("cursor" to c, "order" to "asc").where()
        assertTrue(asc.first.contains("id > ?"))
        assertEquals("id ASC", parse("order" to "asc").orderBy)
    }

    @Test fun agentFilterWithBridgitOwn() {
        val (w, a) = parse("agent" to "Grok,bridgit").where()
        assertTrue(w.contains("agent_id IN (?)") && w.contains("agent_id IS NULL"))
        assertTrue(a.contains("a1"))
        assertEquals(w, parse("agent" to "Grok,Bridget").where().first) // the pre-rename spelling still works
        val empty = LogQuery(agentIds = emptyList()).where()
        assertTrue("an empty visibility set matches nothing", empty.first.contains("0"))
    }

    @Test fun levelAndResult() {
        val (w, a) = parse("level" to "warn", "result" to "error").where()
        assertTrue(w.contains("level IN (?,?)") && a.contains("warn") && a.contains("error"))
        assertTrue(w.contains("http >= 400"))
        assertTrue(parse("result" to "capability_disabled").where().second.contains("capability_disabled"))
    }

    @Test fun redaction() {
        val k = Keys.generate()
        val src = JSONObject()
            .put("camera", "rear").put("max_width_px", 1024)
            .put("api_key", "x").put("token", "y").put("code", "123456").put("Authorization", "Bearer $k")
            .put("note", "my key is $k").put("data_base64", "A".repeat(5000))
            .put("long", "z".repeat(500))
            .put("nested", JSONObject().put("secret", "s").put("ok", k).put("deeper", JSONObject().put("a", 1)))
            .put("list", JSONArray().put(k).put(3))
        val r = Redactor.params(src)
        val text = r.toString()
        assertFalse("key value survived: $text", text.contains(k))
        for (f in listOf("api_key", "token", "code", "Authorization")) assertEquals("[redacted]", r.getString(f))
        assertEquals("rear", r.getString("camera"))
        assertEquals(1024, r.getInt("max_width_px"))
        assertEquals("[5000 chars]", r.getString("data_base64"))
        assertEquals(200, r.getString("long").length)
        assertEquals("[redacted]", r.getJSONObject("nested").getString("secret"))
        assertEquals("[object]", r.getJSONObject("nested").getString("deeper"))
        // A key used as a field NAME is scrubbed too.
        val named = Redactor.params(JSONObject().put(k, 1).put("x", 2)).toString()
        assertFalse("key in a field name survived", named.contains(k))
    }

    @Test fun dataCap() {
        val big = JSONObject().apply { repeat(100) { put("k$it", "v".repeat(100)) } }
        val r = Redactor.data(big)
        assertTrue(r.optBoolean("truncated"))
        assertTrue(r.toString().length < 4096)
    }

    @Test fun percentiles() {
        assertNull(Percentile.of(emptyList(), 50.0))
        val d = (1L..100L).toList()
        assertEquals(50L, Percentile.of(d, 50.0))
        assertEquals(95L, Percentile.of(d, 95.0))
        assertEquals(7L, Percentile.of(listOf(7L), 95.0))
    }
}
