package cc.bridgit.app.log

import java.time.OffsetDateTime

/** A parameter was wrong; the API turns this into `invalid_parameter`. */
class QueryError(message: String) : Exception(message)

/**
 * Parsed `GET /v1/log` filters (LOG-06) and the SQL they turn into. Pure Kotlin:
 * the SQL text and arguments are unit-tested; LogStore only executes them.
 * Values are always bound as arguments, never pasted into the SQL.
 */
data class LogQuery(
    val channels: List<String> = emptyList(),
    val agentIds: List<String>? = null,      // null = any agent; a list may include "" for Bridgit's own entries
    val kinds: List<String> = emptyList(),
    val endpointPrefix: String? = null,
    val result: String? = null,              // ok | error | <code>
    val minLevel: String? = null,
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
        if (channels.isNotEmpty()) { parts += "channel IN (${channels.joinToString(",") { "?" }})"; args += channels }
        agentIds?.let { ids ->
            val real = ids.filter { it.isNotEmpty() }
            val clauses = mutableListOf<String>()
            if (real.isNotEmpty()) { clauses += "agent_id IN (${real.joinToString(",") { "?" }})"; args += real }
            if (ids.any { it.isEmpty() }) clauses += "agent_id IS NULL"
            parts += if (clauses.isEmpty()) "0" else clauses.joinToString(" OR ", "(", ")")
        }
        if (kinds.isNotEmpty()) { parts += "kind IN (${kinds.joinToString(",") { "?" }})"; args += kinds }
        endpointPrefix?.let { parts += "endpoint LIKE ? ESCAPE '\\'"; args += like(it) + "%" }
        when (result) {
            null -> {}
            "ok" -> parts += "result = 'ok'"
            "error" -> parts += "(http >= 400 OR level = 'error')"
            else -> { parts += "result = ?"; args += result }
        }
        minLevel?.let {
            val allowed = LogEntry.LEVELS.drop(LogEntry.levelRank(it))
            parts += "level IN (${allowed.joinToString(",") { "?" }})"; args += allowed
        }
        sinceId?.let { parts += "id > ?"; args += it.toString() }
        sinceTs?.let { parts += "ts >= ?"; args += it.toString() }
        untilId?.let { parts += "id < ?"; args += it.toString() }
        untilTs?.let { parts += "ts <= ?"; args += it.toString() }
        q?.let { parts += "(message LIKE ? ESCAPE '\\' OR endpoint LIKE ? ESCAPE '\\')"; val l = "%" + like(it) + "%"; args += l; args += l }
        cursor?.let { parts += if (ascending) "id > ?" else "id < ?"; args += it.toString() }
        return (if (parts.isEmpty()) "1" else parts.joinToString(" AND ")) to args.toTypedArray()
    }

    val orderBy: String get() = if (ascending) "id ASC" else "id DESC"

    companion object {
        const val MAX_LIMIT = 500

        private fun like(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /**
         * Builds a query from request parameters. [resolveAgent] maps a name or id to an agent id
         * (or null if unknown); "me" is resolved by the caller before this.
         */
        fun parse(p: Map<String, String>, resolveAgent: (String) -> String?, parseIso: (String) -> Long? = ::iso): LogQuery {
            fun list(name: String) = p[name]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val channels = list("channel")
            channels.firstOrNull { it !in LogEntry.CHANNELS }?.let { throw QueryError("channel must be audit, diagnostic or agent") }
            val kinds = list("kind")
            kinds.firstOrNull { it !in LogEntry.KINDS }?.let { throw QueryError("unknown kind \"$it\"") }
            val agents = p["agent"]?.let { a ->
                list("agent").map { n ->
                    if (n.equals("bridgit", true) || n.equals("bridget", true) || n == "system") "" else resolveAgent(n) ?: throw QueryError("no agent called \"$n\"")
                }
            }
            val level = p["level"]?.also { if (it !in LogEntry.LEVELS) throw QueryError("level must be debug, info, warn or error") }
            val limit = p["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..MAX_LIMIT } ?: throw QueryError("limit must be 1 to $MAX_LIMIT") } ?: 100
            val order = p["order"] ?: "desc"
            if (order != "asc" && order != "desc") throw QueryError("order must be asc or desc")
            val (sinceId, sinceTs) = idOrTime(p["since"], "since", parseIso)
            val (untilId, untilTs) = idOrTime(p["until"], "until", parseIso)
            val cursor = p["cursor"]?.let { decodeCursor(it) ?: throw QueryError("cursor is not valid") }
            return LogQuery(channels, agents, kinds, p["endpoint"]?.takeIf { it.isNotEmpty() }, p["result"]?.takeIf { it.isNotEmpty() },
                level, sinceId, sinceTs, untilId, untilTs, p["q"]?.takeIf { it.isNotEmpty() }?.take(200), limit, order == "asc", cursor)
        }

        private fun idOrTime(v: String?, name: String, parseIso: (String) -> Long?): Pair<Long?, Long?> {
            if (v.isNullOrEmpty()) return null to null
            v.toLongOrNull()?.let { if (it < 0) throw QueryError("$name must not be negative"); return it to null }
            val t = parseIso(v) ?: throw QueryError("$name must be a log id or an ISO time")
            return null to t
        }

        fun iso(s: String): Long? = runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

        /** Cursors are opaque to agents (API-08): "c" + base-36 of the last id. */
        fun encodeCursor(id: Long) = "c" + id.toString(36)
        fun decodeCursor(c: String): Long? = if (c.startsWith("c")) c.drop(1).toLongOrNull(36) else null
    }
}

/** Percentiles for LOG-09 stats, computed in memory from the matching rows' durations. */
object Percentile {
    fun of(sorted: List<Long>, p: Double): Long? {
        if (sorted.isEmpty()) return null
        val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}
