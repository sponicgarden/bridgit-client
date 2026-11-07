package cc.bridgit.app.outbound

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.api.Req
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.log.QueryError
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * GET /v1/outbound: what this phone sent off the device, for agents to read and analyse.
 * Always on and allowed to every agent. An agent sees the calls it caused; agents with Agent Super Powers, or every agent
 * while the owner's log visibility is "all", see every call, the phone's and the owner's included.
 */
class OutboundModule(app: BridgeApp) : Capability(app) {
    override val id = "outbound"
    override val titleRes = R.string.cap_outbound
    override val descriptionRes = R.string.cap_outbound_desc
    override val group = "status"
    override val endpoints = ALL

    /** Agent ids this caller may read, or null for everything. Same rule as /v1/log. */
    private fun visible(r: Req): List<String>? {
        val current = r.agent?.let { app.agents.get(it.id) } ?: return emptyList()
        return if (current.superPowers || app.settings.logVisibility == "all") null else listOf(current.id)
    }

    private fun parse(r: Req, extra: Map<String, String> = emptyMap()): OutboundQuery {
        val p = r.queries.toMutableMap()
        p.putAll(extra)
        p["agent"]?.let { a -> p["agent"] = a.split(",").joinToString(",") { if (it.trim() == "me") r.agent!!.id else it.trim() } }
        val q = try {
            OutboundQuery.parse(p - "bodies", { n -> app.agents.get(n)?.id ?: app.agents.byName(n)?.id })
        } catch (e: QueryError) { throw ErrorCatalog.error("invalid_parameter", e.message ?: "Bad outbound query.") }
        return q.scopedTo(visible(r))
    }

    /** Whether new calls are being recorded, and since when, so a gap in the log is explained rather than silent. */
    private fun recording(): JSONObject {
        val on = app.settings.outboundLog
        val changed = app.settings.outboundLogChanged
        return JSONObject().put("on", on).put("setting", "privacy.outbound_log")
            .put("changed_at", changed?.let { isoAt(it.first) } ?: JSONObject.NULL).put("changed_by", changed?.second ?: JSONObject.NULL)
            .put("note", if (on) "Every call off the phone is being recorded." else
                "The outbound log is off${changed?.let { " since ${isoAt(it.first)} (turned off by ${it.second})" } ?: ""}: calls made since then are NOT recorded. Entries from before stay readable until retention removes them.")
    }

    /** since/until from period=today|24h|7d|30d (an explicit since wins). */
    private fun periodStart(r: Req): Long? {
        val now = System.currentTimeMillis()
        return when (val p = r.query("period")) {
            null, "" -> null
            "today" -> java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            "24h" -> now - 86_400_000L
            "7d" -> now - 7 * 86_400_000L
            "30d" -> now - 30 * 86_400_000L
            else -> throw ErrorCatalog.error("invalid_parameter", "period must be today, 24h, 7d or 30d (got $p).")
        }
    }

    private fun scope(r: Req): JSONObject = JSONObject().put("sees", if (visible(r) == null) "all" else "own")
        .put("rule", "Agents see the calls they caused; agents with Agent Super Powers, or all agents while the owner's log visibility is \"all\", see every call.")

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(LIST) {
            val q = parse(this, periodStart(this)?.takeIf { query("since") == null }?.let { mapOf("since" to isoAt(it)) } ?: emptyMap())
            val bodies = query("bodies")?.let { it == "1" || it == "true" } ?: true
            val entries = withContext(Dispatchers.IO) { app.outbound.flush(); app.outbound.query(q) }
            params.put("returned", entries.size)
            Reply.Json(JSONObject().put("entries", JSONArray(entries.map { it.toJson(::isoAt, bodies) }))
                .put("next_cursor", if (entries.size == q.limit) LogQuery.encodeCursor(entries.last().id) else JSONObject.NULL)
                .put("latest_id", app.outbound.latestId()).put("recording", recording()).put("scope", scope(this)).put("coverage", coverage()))
        }
        endpoint(SUMMARY) {
            val now = System.currentTimeMillis()
            val start: Long? = if (query("since") != null) null else (periodStart(this) ?: (now - 30L * 86_400_000L))
            val bucket = query("bucket")?.takeIf { it.isNotEmpty() }?.also {
                if (it != "hour" && it != "day") throw ErrorCatalog.error("invalid_parameter", "bucket must be hour or day.") }
            val q = parse(this, if (start != null) mapOf("since" to isoAt(start)) else emptyMap())
            val (rows, first) = withContext(Dispatchers.IO) { app.outbound.flush(); app.outbound.all(q, light = true) to app.outbound.firstSeen(q) }
            params.put("entries", rows.size)
            val windowStart = start ?: q.sinceTs ?: rows.minOfOrNull { it.ts }
            Reply.Json(OutboundSummary.of(rows, firstSeen = first, windowStart = windowStart, windowEnd = q.untilTs ?: now, bucket = bucket, iso = ::isoAt)
                .put("entries", rows.size).put("recording", recording()).put("scope", scope(this)).put("coverage", coverage()))
        }
        endpoint(ENTRY) {
            val id = path("id")?.toLongOrNull() ?: throw ErrorCatalog.error("invalid_parameter", "id must be an outbound entry id.")
            val e = withContext(Dispatchers.IO) { app.outbound.flush(); app.outbound.get(id) }
            val mine = visible(this)
            if (e == null || (mine != null && e.agentId !in mine)) throw ErrorCatalog.error("not_found", "No outbound entry $id that you can read.")
            Reply.Json(e.toJson(::isoAt, true))
        }
    }

    companion object {
        /** What the log covers and what it can't see, so an agent never mistakes silence for "nothing was sent". */
        fun coverage(): JSONObject = JSONObject()
            .put("recorded", JSONArray(listOf(
                "Bridgit team backend: bug reports, report status sync, install registration, agent team keys",
                "Jev decisions (team backend, forwarded to TypeSafe)",
                "MET Norway weather (location rounded to about 1 km)",
                "bridgit.cc: usage counts and email alerts",
                "The relay: enrollment, socket, agent list, push token and every relayed reply",
                "App update check and download, wake-word model download, ML Kit translation model download",
                "Agent-chosen destinations: interaction callbacks, webhooks, schedule send_to, manifest and downloads, audio URLs",
                "Capture uploads to the agent's or the owner's URL",
                "Sentry crash reports (a summary; the SDK sends them) and Firebase push registration")))
            .put("not_recorded", JSONArray(listOf(
                "Replies to agents over the direct Tailscale API: they are in /v1/log (bytes_out), not here",
                "Traffic made by Google Play services and the Firebase SDK on their own (push delivery, token refresh)",
                "Tailscale's own control traffic, DNS lookups, and other apps on the phone")))
            .put("retention", "Up to 10,000 entries or 30 days; previews capped at 8 KB sent and 2 KB received; keys, tokens, signatures and secret-looking URL path segments never stored")

        private val filters = listOf(
            Param("since", "string", "Outbound id (exclusive) or ISO time"),
            Param("until", "string", "Outbound id or ISO time"),
            Param("destination", "string", "Text in the host or service name, e.g. met.no, jev, relay"),
            Param("agent", "string", "Comma list of agent names or ids; me; phone or owner for calls nobody's agent caused"),
            Param("category", "string", "Comma list of data categories, any of them, e.g. location_1km,image,install_id"),
            Param("method", "string", "GET, POST, SEND (relay frames)…"),
            Param("result", "string", "ok or error", enum = listOf("ok", "error")),
            Param("q", "string", "Text in the purpose, path or sent preview"),
        )

        private val period = Param("period", "string", "today, 24h, 7d or 30d (since overrides it)", enum = listOf("today", "24h", "7d", "30d"))
        val LIST = EndpointSpec("GET", "/outbound", "outbound",
            "Every call this phone made to a server off the device: destination, purpose, which agent caused it, the data categories sent, bytes, status, time, and a redacted preview of what was sent and received",
            query = filters + listOf(
                period,
                Param("limit", "integer", "1-500, default 100"),
                Param("order", "string", "desc (default) or asc", enum = listOf("desc", "asc")),
                Param("cursor", "string", "next_cursor from the previous page"),
                Param("bodies", "boolean", "false leaves out the sent/received previews (default true)"),
            ),
            example = "GET /v1/outbound?destination=met.no&limit=20",
            returns = "entries[id, at, destination{host, service}, method, path, purpose, agent, agent_id, categories[], bytes_sent, bytes_received, status, ok, error, duration_ms, query, headers (secrets redacted), sent_sha256, sent{type, preview}, received{type, preview}], next_cursor, latest_id, recording{on, changed_at, changed_by, note}, scope{sees: own|all}, coverage{recorded[], not_recorded[]}",
            errors = listOf("invalid_parameter"))
        val SUMMARY = EndpointSpec("GET", "/outbound/summary", "outbound",
            "Analysis of what left the phone, in one call: a plain-language headline, totals, trends by hour or day, the biggest destinations, agents and calls, destinations first seen in the window, failures, and for each destination the data categories, purposes and agents it received",
            query = filters + listOf(
                Param("period", "string", "today, 24h, 7d or 30d (default 30d; since overrides it)", enum = listOf("today", "24h", "7d", "30d")),
                Param("bucket", "string", "Trend step: hour or day (default hour for windows up to 48 h)", enum = listOf("hour", "day")),
            ),
            example = "GET /v1/outbound/summary?period=today",
            returns = "headline (\"Since 00:00 today: 7 calls sent 4.9 KB to 3 destinations: ...\"), window{since, until, bucket}, totals{calls, failed, bytes_sent, bytes_received, categories[], destinations, agents}, by_destination[{service, host, calls, failed, bytes, categories[], purposes[], agents[], first_at, last_at, new}], by_category[{category, destinations[], agents[]}], by_agent[{agent, destinations[]}], by_purpose[], by_day[], by_time[], biggest{destinations[], agents[], calls[]}, new_destinations[{host, first_at, categories[]}], failures{count, by_destination[], by_error[], recent[]}, recording{on, changed_at, changed_by}, scope, coverage",
            errors = listOf("invalid_parameter"))
        val ENTRY = EndpointSpec("GET", "/outbound/{id}", "outbound", "One outbound entry with its previews",
            example = "GET /v1/outbound/412", returns = "the entry", errors = listOf("not_found", "invalid_parameter"))
        val ALL = listOf(LIST, SUMMARY, ENTRY)
    }
}
