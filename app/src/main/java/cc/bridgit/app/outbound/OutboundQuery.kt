package cc.bridgit.app.outbound

import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.log.QueryError
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * `GET /v1/outbound` filters and the SQL they turn into. Pure Kotlin, unit-tested; values are always
 * bound as arguments. [agentIds] null = any agent; [actors] are the non-agent causes ("phone", "owner").
 * When both are given an entry matches either.
 */
data class OutboundQuery(
    val agentIds: List<String>? = null,
    val actors: List<String> = emptyList(),
    val destination: String? = null,
    val categories: List<String> = emptyList(),
    val method: String? = null,
    val result: String? = null,           // ok | error
    val sinceId: Long? = null,
    val sinceTs: Long? = null,
    val untilId: Long? = null,
    val untilTs: Long? = null,
    val q: String? = null,
    val limit: Int = 100,
    val ascending: Boolean = false,
    val cursor: Long? = null,
) {
    fun where(): Pair<String, Array<String>> {
        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (agentIds != null || actors.isNotEmpty()) {
            val clauses = mutableListOf<String>()
            agentIds?.let { ids -> if (ids.isNotEmpty()) { clauses += "agent_id IN (${ids.joinToString(",") { "?" }})"; args += ids } }
            if (actors.isNotEmpty()) { clauses += "(agent_id IS NULL AND agent IN (${actors.joinToString(",") { "?" }}))"; args += actors }
            parts += if (clauses.isEmpty()) "0" else clauses.joinToString(" OR ", "(", ")")
        }
        destination?.let { parts += "(host LIKE ? ESCAPE '\\' OR service LIKE ? ESCAPE '\\')"; val l = "%" + like(it) + "%"; args += l; args += l }
        if (categories.isNotEmpty()) {
            parts += categories.joinToString(" OR ", "(", ")") { "categories LIKE ? ESCAPE '\\'" }
            categories.forEach { args += "%," + like(it) + ",%" }
        }
        method?.let { parts += "method = ?"; args += it.uppercase() }
        when (result) {
            null -> {}
            "ok" -> parts += "((status BETWEEN 100 AND 399 OR status = -1) AND error IS NULL)"
            "error" -> parts += "((status < 100 AND status != -1) OR status >= 400 OR error IS NOT NULL)"
        }
        sinceId?.let { parts += "id > ?"; args += it.toString() }
        sinceTs?.let { parts += "ts >= ?"; args += it.toString() }
        untilId?.let { parts += "id < ?"; args += it.toString() }
        untilTs?.let { parts += "ts <= ?"; args += it.toString() }
        q?.let { parts += "(purpose LIKE ? ESCAPE '\\' OR path LIKE ? ESCAPE '\\' OR request_preview LIKE ? ESCAPE '\\')"; val l = "%" + like(it) + "%"; args += l; args += l; args += l }
        cursor?.let { parts += if (ascending) "id > ?" else "id < ?"; args += it.toString() }
        return (if (parts.isEmpty()) "1" else parts.joinToString(" AND ")) to args.toTypedArray()
    }

    val orderBy: String get() = if (ascending) "id ASC" else "id DESC"

    /** Keeps only what a caller may see: [visible] null = everything, else only those agents' entries. */
    fun scopedTo(visible: List<String>?): OutboundQuery {
        if (visible == null) return this
        // Asking only for phone/owner calls finds nothing for an agent that may only see its own.
        val ids = (agentIds ?: if (actors.isNotEmpty()) emptyList() else visible).filter { it in visible }
        return copy(agentIds = ids, actors = emptyList())
    }

    companion object {
        const val MAX_LIMIT = 500
        val ACTORS = setOf("phone", "owner")

        private fun like(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /** [resolveAgent] maps a name or id to an agent id (null if unknown); "me" is resolved by the caller first. */
        fun parse(p: Map<String, String>, resolveAgent: (String) -> String?, parseIso: (String) -> Long? = LogQuery::iso): OutboundQuery {
            fun list(name: String) = p[name]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            var agentIds: MutableList<String>? = null
            val actors = mutableListOf<String>()
            if (p["agent"] != null) for (n in list("agent")) {
                val low = n.lowercase()
                when {
                    low in ACTORS -> actors += low
                    low == "bridgit" || low == "system" -> actors += "phone"
                    else -> (agentIds ?: mutableListOf<String>().also { agentIds = it }) += resolveAgent(n) ?: throw QueryError("no agent called \"$n\"")
                }
            }
            if (p["agent"] != null && agentIds == null && actors.isEmpty()) agentIds = mutableListOf()
            val limit = p["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..MAX_LIMIT } ?: throw QueryError("limit must be 1 to $MAX_LIMIT") } ?: 100
            val order = p["order"] ?: "desc"
            if (order != "asc" && order != "desc") throw QueryError("order must be asc or desc")
            val result = p["result"]?.takeIf { it.isNotEmpty() }?.also { if (it != "ok" && it != "error") throw QueryError("result must be ok or error") }
            val (sinceId, sinceTs) = idOrTime(p["since"], "since", parseIso)
            val (untilId, untilTs) = idOrTime(p["until"], "until", parseIso)
            val cursor = p["cursor"]?.let { LogQuery.decodeCursor(it) ?: throw QueryError("cursor is not valid") }
            val method = p["method"]?.takeIf { it.isNotEmpty() }?.also { if (!it.matches(Regex("[A-Za-z]{3,7}"))) throw QueryError("method must be like GET or POST") }
            return OutboundQuery(agentIds, actors, p["destination"]?.takeIf { it.isNotEmpty() }?.take(200),
                list("category").map { it.lowercase() }, method, result, sinceId, sinceTs, untilId, untilTs,
                p["q"]?.takeIf { it.isNotEmpty() }?.take(200), limit, order == "asc", cursor)
        }

        private fun idOrTime(v: String?, name: String, parseIso: (String) -> Long?): Pair<Long?, Long?> {
            if (v.isNullOrEmpty()) return null to null
            v.toLongOrNull()?.let { if (it < 0) throw QueryError("$name must not be negative"); return it to null }
            val t = parseIso(v) ?: throw QueryError("$name must be an outbound id or an ISO time")
            return null to t
        }
    }
}

/**
 * The analysis behind `GET /v1/outbound/summary`, computed in memory from the matching entries: totals, trends over
 * time, the biggest destinations, agents and calls, destinations first seen in the window, failures, and for each
 * destination the data categories, purposes and agents. [headline] answers "what left this phone, and to whom?".
 */
object OutboundSummary {
    private class Tally {
        var calls = 0; var failed = 0; var sent = 0L; var received = 0L
        var first = Long.MAX_VALUE; var last = 0L
        val cats = java.util.TreeSet<String>(); val purposes = LinkedHashMap<String, LongArray>(); val agents = LinkedHashMap<String, LongArray>()
        val services = LinkedHashMap<String, LongArray>()
        fun add(e: OutboundEntry) {
            calls++; if (!e.ok) failed++; sent += e.bytesSent; if (e.bytesReceived > 0) received += e.bytesReceived
            first = minOf(first, e.ts); last = maxOf(last, e.ts); cats += e.categories
            for ((m, k) in listOf(purposes to e.purpose, agents to e.agent, services to e.service)) m.getOrPut(k) { LongArray(2) }.let { it[0]++; it[1] += e.bytesSent }
        }
        fun json(iso: (Long) -> String, detail: Boolean = true) = JSONObject().put("calls", calls).put("failed", failed)
            .put("bytes_sent", sent).put("bytes_received", received)
            .apply {
                if (detail) {
                    put("categories", JSONArray(cats.toList()))
                    put("first_at", if (calls > 0) iso(first) else JSONObject.NULL).put("last_at", if (calls > 0) iso(last) else JSONObject.NULL)
                }
            }
    }

    /** [name]: {calls, bytes_sent} as a list, biggest sender first. */
    private fun counts(m: Map<String, LongArray>, name: String) = JSONArray(m.entries
        .sortedWith(compareByDescending<Map.Entry<String, LongArray>> { it.value[1] }.thenByDescending { it.value[0] })
        .map { JSONObject().put(name, it.key).put("calls", it.value[0]).put("bytes_sent", it.value[1]) })
    private fun tally(list: List<OutboundEntry>, key: (OutboundEntry) -> String): Map<String, LongArray> =
        LinkedHashMap<String, LongArray>().also { m -> list.forEach { e -> m.getOrPut(key(e)) { LongArray(2) }.let { it[0]++; it[1] += e.bytesSent } } }

    /**
     * [firstSeen] is each host's first entry in the whole kept log (same visibility); a host whose first entry
     * falls inside the window is "new". [windowStart] null = the start of the matching entries. [bucket] is
     * "hour" or "day"; null picks hour for windows up to 48 h.
     */
    fun of(entries: List<OutboundEntry>, zone: ZoneId = ZoneId.systemDefault(), firstSeen: Map<String, Long> = emptyMap(),
           windowStart: Long? = null, windowEnd: Long = System.currentTimeMillis(), bucket: String? = null,
           iso: (Long) -> String = { Instant.ofEpochMilli(it).atZone(zone).toOffsetDateTime().toString() }): JSONObject {
        val total = Tally()
        val byDest = LinkedHashMap<String, Tally>(); val destNames = HashMap<String, Pair<String, String>>()
        val byCat = LinkedHashMap<String, Tally>()
        val byAgent = LinkedHashMap<String, Tally>(); val agentIds = HashMap<String, String?>()
        val byPurpose = LinkedHashMap<String, Tally>()
        val byDay = java.util.TreeMap<String, Tally>()
        val start = windowStart ?: entries.minOfOrNull { it.ts } ?: windowEnd
        val step = bucket ?: if (windowEnd - start <= 48 * 3_600_000L) "hour" else "day"
        val byTime = java.util.TreeMap<String, Tally>()
        fun timeKey(ts: Long): String {
            val z = Instant.ofEpochMilli(ts).atZone(zone)
            return if (step == "hour") z.truncatedTo(java.time.temporal.ChronoUnit.HOURS).toOffsetDateTime().toString()
                else z.toLocalDate().toString()
        }
        for (e in entries) {
            total.add(e)
            val dk = "${e.service}\u0000${e.host}"
            byDest.getOrPut(dk) { Tally() }.add(e); destNames[dk] = e.service to e.host
            for (c in e.categories) byCat.getOrPut(c) { Tally() }.add(e)
            byAgent.getOrPut(e.agent) { Tally() }.add(e); agentIds[e.agent] = e.agentId
            byPurpose.getOrPut(e.purpose) { Tally() }.add(e)
            byDay.getOrPut(Instant.ofEpochMilli(e.ts).atZone(zone).toLocalDate().toString()) { Tally() }.add(e)
            byTime.getOrPut(timeKey(e.ts)) { Tally() }.add(e)
        }
        fun sorted(m: Map<String, Tally>) = m.entries.sortedWith(compareByDescending<Map.Entry<String, Tally>> { it.value.sent }.thenByDescending { it.value.calls })
        fun dest(k: String, t: Tally) = t.json(iso).put("service", destNames[k]!!.first).put("host", destNames[k]!!.second)
            .put("purposes", counts(t.purposes, "purpose")).put("agents", counts(t.agents, "agent"))
            .put("new", firstSeen[destNames[k]!!.second]?.let { it >= start } ?: false)
        val destinations = sorted(byDest).map { (k, t) -> dest(k, t) }
        val agents = sorted(byAgent).map { (k, t) -> t.json(iso).put("agent", k).put("agent_id", agentIds[k] ?: JSONObject.NULL)
            .put("destinations", counts(t.services, "service")) }

        // Hosts first seen inside the window (one per host, though several services may share it).
        val newHosts = entries.groupBy { it.host }.filter { (h, _) -> firstSeen[h]?.let { it >= start } ?: false }
            .map { (h, list) -> JSONObject().put("host", h).put("service", list.first().service).put("first_at", iso(firstSeen[h]!!))
                .put("calls", list.size).put("bytes_sent", list.sumOf { it.bytesSent })
                .put("categories", JSONArray(list.flatMap { it.categories }.toSortedSet().toList()))
                .put("agents", JSONArray(list.map { it.agent }.distinct())) }
            .sortedBy { it.getString("first_at") }

        val failed = entries.filter { !it.ok }
        val failures = JSONObject().put("count", failed.size)
            .put("by_destination", counts(tally(failed) { it.service }, "service"))
            .put("by_error", counts(tally(failed) { it.error?.substringBefore(':') ?: "HTTP ${it.status}" }, "error"))
            .put("recent", JSONArray(failed.sortedByDescending { it.ts }.take(20).map { e -> JSONObject().put("id", e.id).put("at", iso(e.ts))
                .put("service", e.service).put("host", e.host).put("path", e.path).put("agent", e.agent)
                .put("status", e.status).put("error", e.error ?: JSONObject.NULL) }))

        val largest = entries.sortedByDescending { it.bytesSent }.take(10).map { e -> JSONObject().put("id", e.id).put("at", iso(e.ts))
            .put("service", e.service).put("host", e.host).put("agent", e.agent).put("purpose", e.purpose)
            .put("bytes_sent", e.bytesSent).put("categories", JSONArray(e.categories)) }

        return JSONObject()
            .put("headline", headline(total, destinations, newHosts, start, windowEnd, zone))
            .put("window", JSONObject().put("since", iso(start)).put("until", iso(windowEnd)).put("bucket", step))
            .put("totals", total.json(iso).put("destinations", byDest.size).put("agents", byAgent.size))
            .put("first_at_ms", entries.minOfOrNull { it.ts } ?: JSONObject.NULL)
            .put("last_at_ms", entries.maxOfOrNull { it.ts } ?: JSONObject.NULL)
            .put("by_destination", JSONArray(destinations))
            .put("by_category", JSONArray(sorted(byCat).map { (k, t) -> t.json(iso, false).put("category", k)
                .put("destinations", counts(t.services, "service")).put("agents", counts(t.agents, "agent")) }))
            .put("by_agent", JSONArray(agents))
            .put("by_purpose", JSONArray(sorted(byPurpose).map { (k, t) -> t.json(iso, false).put("purpose", k) }))
            .put("by_day", JSONArray(byDay.map { (k, t) -> t.json(iso, false).put("day", k) }))
            .put("by_time", JSONArray(byTime.map { (k, t) -> t.json(iso, false).put("start", k) }))
            .put("biggest", JSONObject().put("destinations", JSONArray(destinations.take(5).map { JSONObject().put("service", it.getString("service"))
                .put("host", it.getString("host")).put("bytes_sent", it.getLong("bytes_sent")).put("calls", it.getInt("calls")) }))
                .put("agents", JSONArray(agents.take(5).map { JSONObject().put("agent", it.getString("agent")).put("bytes_sent", it.getLong("bytes_sent")).put("calls", it.getInt("calls")) }))
                .put("calls", JSONArray(largest)))
            .put("new_destinations", JSONArray(newHosts))
            .put("failures", failures)
    }

    fun bytes(n: Long): String = when {
        n >= 1_048_576 -> String.format(java.util.Locale.ROOT, "%.1f MB", n / 1_048_576.0)
        n >= 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", n / 1024.0)
        else -> "$n bytes"
    }

    private fun headline(t: Tally, dests: List<JSONObject>, newHosts: List<JSONObject>, start: Long, end: Long, zone: ZoneId): String {
        val from = Instant.ofEpochMilli(start).atZone(zone)
        val today = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
        val since = if (from.toLocalDate() == today) "Since ${from.toLocalTime().withSecond(0).withNano(0)} today"
            else "Since ${from.toLocalDate()} ${from.toLocalTime().withSecond(0).withNano(0)}"
        if (t.calls == 0) return "$since: nothing was sent off the phone."
        val to = dests.take(5).joinToString(", ") { "${it.getString("service")} (${bytes(it.getLong("bytes_sent"))}, ${it.getInt("calls")} call${if (it.getInt("calls") == 1) "" else "s"})" } +
            if (dests.size > 5) " and ${dests.size - 5} more" else ""
        val sb = StringBuilder("$since: ${t.calls} call${if (t.calls == 1) "" else "s"} sent ${bytes(t.sent)} to ${dests.size} destination${if (dests.size == 1) "" else "s"}: $to.")
        if (t.cats.isNotEmpty()) sb.append(" Data sent: ${t.cats.joinToString(", ")}.")
        sb.append(if (t.failed == 0) " None failed." else " ${t.failed} failed.")
        if (newHosts.isNotEmpty()) sb.append(" New destinations: ${newHosts.joinToString(", ") { it.getString("host") }}.")
        return sb.toString()
    }
}
