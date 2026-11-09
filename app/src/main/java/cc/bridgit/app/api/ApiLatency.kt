package cc.bridgit.app.api

import cc.bridgit.app.log.Percentile
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * A0186: how long every API call took, from receiving the request to sending the response, with a
 * small curated device context (network, power, thermal, app build, screen, load). Performance
 * telemetry only: no parameters, bodies, source addresses, location or sensor contents, only sizes.
 *
 * Kept in memory, newest [capacity] calls and at most [maxAgeMs] old; a restart starts empty. Recording
 * is one append under a lock, so it never slows the call it measures. Pure Kotlin (org.json), unit-tested.
 */
class ApiLatency(
    /** The curated context to attach; called at most every [contextTtlMs]. Must return a fresh object it never changes later. */
    private val context: () -> JSONObject = { JSONObject() },
    val capacity: Int = 5000,
    val maxAgeMs: Long = 7 * 86_400_000L,
    private val contextTtlMs: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One measured call. [api] is the operation name (e.g. getHealth); [via] is http, mcp, relay, card, schedule or control. */
    data class Call(
        val ts: Long, val api: String, val method: String, val path: String, val via: String, val requestId: String,
        val agentId: String?, val agent: String?, val durationMs: Long, val http: Int, val result: String,
        val bytesIn: Long, val bytesOut: Long, val inFlight: Int, val ranOn: String? = null, val context: JSONObject? = null,
    ) {
        val ok get() = http < 400
    }

    private val ring = ArrayDeque<Call>()
    private val lock = Any()
    private val inFlight = AtomicInteger()
    @Volatile private var cached: Pair<Long, JSONObject>? = null
    val startedAt: Long = clock()

    /** Calls running now (every transport). */
    val running: Int get() = inFlight.get()

    /** A call was received; returns how many were running then, including this one. Pair with [end]. */
    fun begin(): Int = inFlight.incrementAndGet()
    fun end() { inFlight.updateAndGet { if (it > 0) it - 1 else 0 } }

    /** The current context, sampled at most every [contextTtlMs]; null when sampling failed. */
    fun contextNow(): JSONObject? {
        val now = clock()
        cached?.let { (at, c) -> if (now - at < contextTtlMs) return c }
        val c = runCatching { context() }.getOrNull() ?: return null
        cached = now to c
        return c
    }

    /** Stores [call] with the current context attached (unless it already has one). Never throws. */
    fun record(call: Call) {
        runCatching {
            val c = if (call.context != null) call else call.copy(context = contextNow())
            synchronized(lock) {
                ring.addLast(c)
                while (ring.size > capacity) ring.removeFirst()
            }
        }
    }

    /** Calls still kept, oldest first. */
    fun calls(): List<Call> {
        val cutoff = clock() - maxAgeMs
        return synchronized(lock) {
            while (ring.isNotEmpty() && ring.first().ts < cutoff) ring.removeFirst()
            ring.toList()
        }
    }

    companion object {
        const val DEFAULT_SLOW_MS = 1000L

        /** Per API: count, errors, median, p95 and max time, and how many were at least [slowMs]; slowest p95 first. */
        fun aggregate(calls: List<Call>, slowMs: Long): JSONArray {
            class Group(val api: String, val list: List<Call>) {
                val sorted = list.map { it.durationMs }.sorted()
                val p95 = Percentile.of(sorted, 95.0) ?: 0L
            }
            val groups = calls.groupBy { it.api }.map { (api, list) -> Group(api, list) }
                .sortedWith(compareByDescending<Group> { it.p95 }.thenBy { it.api })
            return JSONArray(groups.map { g ->
                val last = g.list.last()
                JSONObject().put("api", g.api).put("method", last.method).put("path", last.path)
                    .put("count", g.list.size).put("errors", g.list.count { !it.ok })
                    .put("median_ms", Percentile.of(g.sorted, 50.0)).put("p95_ms", g.p95).put("max_ms", g.sorted.last())
                    .put("slow", g.sorted.count { it >= slowMs })
            })
        }

        /** The curated record of one call, as agents read it. */
        fun json(c: Call, isoTs: (Long) -> String): JSONObject = JSONObject()
            .put("ts", isoTs(c.ts)).put("api", c.api).put("method", c.method).put("path", c.path).put("via", c.via)
            .put("request_id", c.requestId).put("agent", c.agent ?: JSONObject.NULL).put("agent_id", c.agentId ?: JSONObject.NULL)
            .put("duration_ms", c.durationMs).put("outcome", if (c.ok) "success" else "error").put("http", c.http).put("result", c.result)
            .put("bytes_in", c.bytesIn).put("bytes_out", c.bytesOut).put("in_flight", c.inFlight)
            .put("ran_on", c.ranOn ?: JSONObject.NULL).put("context", c.context ?: JSONObject.NULL)
    }
}
