package cc.bridgit.app.context

import cc.bridgit.app.platform.FlowConditions
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

/** Bounded local place summaries, not a travel log. A single stay never establishes home/work. */
object ContextPatterns {
    private const val WINDOW = 35 * 86_400_000L
    fun observe(samples: JSONArray, kind: String, lat: Double, lon: Double, now: ZonedDateTime): JSONArray {
        val at = now.toInstant().toEpochMilli()
        val eligible = if (kind == "home") now.hour in 0..5 else now.dayOfWeek.value <= 5 && now.hour in 9..16
        val rows = (0 until samples.length()).map { JSONObject(samples.getJSONObject(it).toString()) }
            .filter { at - it.optLong("last") in 0..WINDOW }.toMutableList()
        if (eligible) {
            val day = now.toLocalDate().toEpochDay()
            val match = rows.firstOrNull { it.optLong("day") == day && near(it, lat, lon) }
            if (match != null) {
                val gap = at - match.getLong("last")
                // Only consecutive observations count as dwell; two brief visits hours apart do not.
                val dwell = match.optLong("dwell") + if (gap in 1..10 * 60_000L) gap else 0L
                match.put("last", at).put("dwell", dwell)
            }
            else rows += JSONObject().put("day", day).put("first", at).put("last", at).put("dwell", 0L).put("latitude", lat).put("longitude", lon)
        }
        return JSONArray(rows.takeLast(120))
    }
    fun candidate(samples: JSONArray, kind: String): JSONObject? {
        val all = (0 until samples.length()).map { samples.getJSONObject(it) }
        return all.asReversed().firstNotNullOfOrNull { p ->
            val cluster = all.filter { near(it, p.getDouble("latitude"), p.getDouble("longitude")) }
                .filter { it.optLong("dwell") >= 2 * 3_600_000L }
            val days = cluster.map { it.getLong("day") }.distinct()
            val needed = if (kind == "home") 14 else 8
            val span = if (kind == "home") 21 else 14
            if (days.size >= needed && days.max() - days.min() >= span) JSONObject(p.toString()) else null
        }
    }
    private fun near(p: JSONObject, lat: Double, lon: Double) =
        FlowConditions.distanceM(p.getDouble("latitude"), p.getDouble("longitude"), lat, lon) < 150
}
