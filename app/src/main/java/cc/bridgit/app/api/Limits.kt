package cc.bridgit.app.api

import org.json.JSONObject

/**
 * Sliding one-minute windows (SEC-06, SEC-07, LOG-11). Keys are free-form
 * ("ip:100.1.2.3", "agent:a1b2c3"). Pure Kotlin with an injectable clock.
 */
class RateLimiter(private val clock: () -> Long = System::currentTimeMillis, private val windowMs: Long = 60_000) {
    private val hits = HashMap<String, ArrayDeque<Long>>()

    /** Records a hit and returns true if it's within [limit] for the window; otherwise records nothing. */
    @Synchronized
    fun tryAcquire(key: String, limit: Int): Boolean {
        val q = prune(key)
        if (q.size >= limit) return false
        q.addLast(clock()); return true
    }

    @Synchronized
    fun record(key: String) { prune(key).addLast(clock()) }

    @Synchronized
    fun count(key: String): Int = prune(key).size

    /** Seconds until the oldest hit in the window expires (for `retry_after_s`). */
    @Synchronized
    fun retryAfterS(key: String): Int {
        val q = prune(key)
        if (q.isEmpty()) return 0
        return (((q.first() + windowMs) - clock() + 999) / 1000).toInt().coerceAtLeast(1)
    }

    private fun prune(key: String): ArrayDeque<Long> {
        val q = hits.getOrPut(key) { ArrayDeque() }
        val cutoff = clock() - windowMs
        while (q.isNotEmpty() && q.first() <= cutoff) q.removeFirst()
        return q
    }

    companion object {
        const val AUTH_FAILS_PER_IP = 5          // SEC-06
        const val CAPTURES_PER_AGENT = 10        // SEC-07
        const val CAPTURES_PER_IP = 30           // SEC-07
        const val DEVICE_ACTIONS_PER_AGENT = 30  // A0212: POST /v1/lan/devices/action has its own bucket
        const val DEVICE_ACTIONS_PER_IP = 60     // A0212

        /** One SEC-07 bucket: key prefix, per-agent and per-address limits, and what the rate_limited message calls a hit. */
        data class Bucket(val key: String, val perAgent: Int, val perIp: Int, val noun: String)
        val CAPTURES = Bucket("cap", CAPTURES_PER_AGENT, CAPTURES_PER_IP, "captures")
        val DEVICE_ACTIONS = Bucket("lan", DEVICE_ACTIONS_PER_AGENT, DEVICE_ACTIONS_PER_IP, "LAN device actions")
        /** A0212: LAN device control doesn't share the captures bucket or its wording. */
        fun bucket(capability: String) = if (capability == "lan_control") DEVICE_ACTIONS else CAPTURES
        const val NOTES_PER_AGENT = 60           // LOG-11
        const val SATELLITE_CALLS_PER_AGENT = 60 // SAT-37: forwarded calls per minute, per hub agent
    }
}

/** Counts open `wait` requests (SEC-08a): 4 per agent, 32 in total. */
class WaitGate(private val perAgent: Int = 4, private val total: Int = 32) {
    private val open = HashMap<String, Int>()
    private var sum = 0

    @Synchronized
    fun enter(agentId: String): Boolean {
        val n = open[agentId] ?: 0
        if (n >= perAgent || sum >= total) return false
        open[agentId] = n + 1; sum++; return true
    }

    @Synchronized
    fun exit(agentId: String) {
        val n = open[agentId] ?: return
        if (n <= 1) open.remove(agentId) else open[agentId] = n - 1
        sum--
    }
}

/**
 * Idempotency-Key handling (API-13), kept in memory and scoped per agent.
 * Only successful (2xx) results are remembered: a failed request did nothing,
 * so a retry should really retry. A repeat of a success within 24 h returns the
 * stored response. Media bodies are kept at most [mediaKeepMs] (15 min; longer keeping is capture storage, SEC-13)
 * and within a total byte budget; once a body is dropped, a small "done" marker
 * stays for the full 24 h, so a repeat gets `idempotency_expired` and never
 * captures twice. Markers are never evicted early; when [maxEntries] is reached,
 * new keys are refused instead. Everything is lost if the process restarts.
 * A key belongs to the endpoint it was first used on ([route]); the same key on another
 * endpoint is [Claim.Mismatch], so a result is never replayed to the wrong request.
 */
class IdempotencyCache(private val clock: () -> Long = System::currentTimeMillis,
                       private val keepMs: Long = 24 * 3600_000L, private val mediaKeepMs: Long = 15 * 60_000L,
                       private val maxEntries: Int = 20_000, private val maxBodyBytes: Long = 48L * 1024 * 1024) {
    class Stored(val status: Int, val contentType: String, val body: ByteArray?, val headers: Map<String, String>,
                 val doneAt: Long, val isMedia: Boolean, val route: String = "")

    sealed class Claim {
        object New : Claim()
        object Running : Claim()
        class Done(val stored: Stored) : Claim()
        object Expired : Claim()
        object Full : Claim()
        object Mismatch : Claim()
    }

    private val running = HashMap<String, String>()
    private val done = LinkedHashMap<String, Stored>()
    private var bodyBytes = 0L

    /** Atomically reserves (agent, key) before the work starts. */
    @Synchronized
    fun claim(agentId: String, key: String, route: String = ""): Claim {
        prune()
        val k = "$agentId\u0000$key"
        done[k]?.let { return if (it.route != route) Claim.Mismatch else if (it.body == null) Claim.Expired else Claim.Done(it) }
        running[k]?.let { return if (it != route) Claim.Mismatch else Claim.Running }
        if (done.size + running.size >= maxEntries) return Claim.Full
        running[k] = route
        return Claim.New
    }

    /** True if (agent, key) already finished, so [claim] will answer Done or Expired without running anything. */
    @Synchronized
    fun hasResult(agentId: String, key: String, route: String = ""): Boolean { prune(); return done["$agentId\u0000$key"]?.route == route }

    /** Stores a 2xx outcome; anything else just releases the claim. */
    @Synchronized
    fun finish(agentId: String, key: String, status: Int, contentType: String, body: ByteArray,
               headers: Map<String, String>, isMedia: Boolean) {
        val k = "$agentId\u0000$key"
        val route = running.remove(k) ?: ""
        if (status !in 200..299) return
        prune()
        // Keep the body only while it fits the budget; the done marker is kept either way.
        val keepBody = bodyBytes + body.size <= maxBodyBytes
        if (keepBody) bodyBytes += body.size
        done[k] = Stored(status, contentType, if (keepBody) body else null, if (keepBody) headers else emptyMap(), clock(), isMedia, route)
    }

    @Synchronized
    fun abandon(agentId: String, key: String) { running.remove("$agentId\u0000$key") }

    /** Drops expired entries and media bodies. Also called from a timer so idle memory is freed. */
    @Synchronized
    fun prune() {
        val now = clock()
        val it = done.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val v = e.value
            if (now - v.doneAt > keepMs) { it.remove(); bodyBytes -= v.body?.size ?: 0 }
            else if (v.isMedia && v.body != null && now - v.doneAt > mediaKeepMs) {
                bodyBytes -= v.body.size
                e.setValue(Stored(v.status, v.contentType, null, emptyMap(), v.doneAt, true, v.route))
            }
        }
    }

    companion object {
        val KEY_RE = Regex("^[A-Za-z0-9_-]{8,128}$")
    }
}

/** Image sizing presets (API-10). Pure maths, unit-tested. */
object Sizing {
    data class Spec(val maxEdge: Int?, val maxWidth: Int?, val quality: Int, val original: Boolean = false)

    val PRESETS = mapOf(
        "agent" to Spec(1568, null, 85),
        "thumb" to Spec(512, null, 75),
        "large" to Spec(2560, null, 90),
        "original" to Spec(null, null, 100, original = true),
    )

    /**
     * Resolves `size`, `max_edge_px`, `max_width_px` and `quality` from query or body values.
     * Explicit values win over the preset. Throws [ApiError] invalid_parameter on bad input.
     */
    fun resolve(get: (String) -> Any?, default: String): Spec {
        val size = (get("size") as? String) ?: default
        val base = PRESETS[size] ?: throw ErrorCatalog.error("invalid_parameter", "size must be agent, thumb, large or original")
        fun int(name: String, min: Int, max: Int): Int? {
            val v = get(name) ?: return null
            if (v == JSONObject.NULL) return null
            val n = when (v) { is Int -> v; is Long -> v.takeIf { it in min..max }?.toInt(); is String -> v.toIntOrNull(); else -> null }
            if (n == null || n < min || n > max) throw ErrorCatalog.error("invalid_parameter", "$name must be $min to $max")
            return n
        }
        val edge = int("max_edge_px", 64, 20_000)
        val width = int("max_width_px", 64, 20_000)
        val q = int("quality", 40, 100)
        if (edge == null && width == null && q == null) return base
        return Spec(edge ?: if (width != null) null else base.maxEdge, width, q ?: if (base.original) 90 else base.quality)
    }

    /** Target (width, height) for an image of [w]x[h] under [spec]; never upscales. */
    fun target(w: Int, h: Int, spec: Spec): Pair<Int, Int> {
        if (spec.original || w <= 0 || h <= 0) return w to h
        var scale = 1.0
        spec.maxEdge?.let { e -> val long = maxOf(w, h); if (long > e) scale = minOf(scale, e.toDouble() / long) }
        spec.maxWidth?.let { mw -> if (w > mw) scale = minOf(scale, mw.toDouble() / w) }
        if (scale >= 1.0) return w to h
        return maxOf(1, Math.round(w * scale).toInt()) to maxOf(1, Math.round(h * scale).toInt())
    }

    /** Largest power-of-two decode sample that still leaves the decoded image at least the target size. */
    fun sampleSize(srcLong: Int, targetLong: Int): Int {
        var s = 1
        while (targetLong > 0 && srcLong / (s * 2) >= targetLong) s *= 2
        return s
    }
}
