package cc.bridgit.app.platform

import cc.bridgit.app.power.SunTimes
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * `only_if` conditions (docs/applet-reach.md D1), on a step or on the whole flow. Pure: the phone's readings come in
 * through [Facts], so time, sun and table tests are unit-tested with a fixed clock and zone.
 *
 * Every test has one source and, except `time` and `sun` (which are a test on their own), one test key:
 * - `{"step": "id.path", "is": …}` and `{"trigger": "path", …}`: unchanged (is, is_not, in, at_least, below, exists).
 * - `{"time": {"between": ["22:00", "07:00"], "days": ["fri", "sat"]}}`: the phone's local time; a span may cross
 *   midnight, and then its early hours belong to the day it began (Friday 22:00-07:00 holds at 01:00 on Saturday).
 * - `{"sun": {"after" | "before": "sunrise" | "sunset", "offset_min": -180..180}}`: against today's sunrise or sunset
 *   (local date). "after sunset" holds from sunset to midnight, "before sunrise" from midnight to sunrise; night is
 *   `any` of the two. Optional latitude/longitude, else a rough position (0.1°) saved once.
 * - `{"phone": "charging", "is": true}`, `{"phone": "battery", "below": 20}`, `{"phone": "network", "is": "wifi"}`.
 * - `{"zone": {"latitude", "longitude", "radius_m"}, "is": true}`: whether the phone is within the place (a fix).
 * - `{"mode": true, "is": "away"}` (is, is_not, in).
 * - `{"count": {"table", "since" | "hours", "where"}, "below": 3}` and `{"sum": {…, "column"}, "at_least": 50}`:
 *   rows of the applet's own table.
 * - `{"all": [2-8 tests]}` and `{"any": [...]}`, one level deep.
 */
object FlowConditions {
    val SOURCES = listOf("step", "trigger", "time", "sun", "phone", "zone", "mode", "count", "sum")
    val TESTS = setOf("is", "is_not", "in", "at_least", "below", "exists")
    val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    val NETWORKS = listOf("wifi", "mobile", "none")
    val PHONE = mapOf("charging" to setOf("is", "is_not"), "battery" to setOf("at_least", "below"), "network" to setOf("is", "is_not", "in"))
    val SINCE = listOf("today", "this_week", "all")
    const val MAX_HOURS = 720
    const val MAX_LOOKUP = 10
    private val HHMM = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private val ID = Regex("^[a-z][a-z0-9_]{0,31}$")

    /** What the phone knows when a condition is checked. Null readings make their test false. */
    interface Facts {
        val now: ZonedDateTime
        fun mode(): String? = null
        /** charging (Boolean), battery (percent), network (wifi, mobile or none). */
        fun phone(reading: String): Any? = null
        /** A recent location fix, for `zone`. */
        fun location(): Pair<Double, Double>? = null
        /** The rough position for a sun test without its own. */
        fun sunPosition(): Pair<Double, Double>? = null
        fun count(table: String, sinceMs: Long?, where: JSONObject?): Int? = null
        fun sum(table: String, column: String, sinceMs: Long?, where: JSONObject?): Double? = null
    }

    /** No readings (time only): what tests and a check without the phone see. */
    class Clock(override val now: ZonedDateTime) : Facts

    /** What a condition tree needs: the applet's tables (applet flows only) and a location fix (scope location). */
    class Needs(var tables: Boolean = false, var location: Boolean = false, var sunPosition: Boolean = false, var zone: Boolean = false) {
        fun add(o: Needs) { tables = tables || o.tables; location = location || o.location; sunPosition = sunPosition || o.sunPosition; zone = zone || o.zone }
    }

    // ---- checking --------------------------------------------------------------------------------------------

    /**
     * Checks one `only_if` at [at]. [path] checks a `step` / `trigger` path (the caller knows the earlier steps);
     * [flow] is true for a flow-level only_if (no step results exist yet).
     */
    fun check(v: Any?, at: String, flow: Boolean, path: (source: String, value: Any?, at: String) -> Unit, bad: (String) -> Nothing): Needs {
        val c = v as? JSONObject ?: bad("$at: only_if must be an object, like {\"step\":\"id.path\", \"is\":…}, {\"time\":{\"between\":[\"22:00\",\"07:00\"]}} or {\"all\":[…]}")
        val group = listOf("all", "any").filter { c.has(it) }
        if (group.isNotEmpty()) {
            if (group.size > 1 || c.length() != 1) bad("$at: only_if.${group[0]} must be alone in its object")
            val list = c.opt(group[0]) as? JSONArray ?: bad("$at: only_if.${group[0]} must be a list of 2-8 tests")
            if (list.length() !in 2..8) bad("$at: only_if.${group[0]} takes 2-8 tests")
            val needs = Needs()
            for (i in 0 until list.length()) {
                val one = list.opt(i) as? JSONObject ?: bad("$at: only_if.${group[0]}[$i] must be a test object")
                if (one.has("all") || one.has("any")) bad("$at: only_if.${group[0]}[$i]: all and any don't nest (one level only)")
                needs.add(one(one, "$at.only_if.${group[0]}[$i]", flow, path, bad))
            }
            return needs
        }
        return one(c, "$at.only_if", flow, path, bad)
    }

    private fun one(c: JSONObject, at: String, flow: Boolean, path: (String, Any?, String) -> Unit, bad: (String) -> Nothing): Needs {
        val sources = SOURCES.filter { c.has(it) }
        if (sources.size != 1) bad("$at needs exactly one of ${SOURCES.joinToString()} (or all / any)")
        val source = sources[0]
        val tests = c.keys().asSequence().filter { it != source }.toList()
        fun test(allowed: Set<String>): String {
            if (tests.size != 1 || tests[0] !in allowed) bad("$at needs exactly one test: ${allowed.joinToString()}")
            return tests[0]
        }
        val needs = Needs()
        when (source) {
            "step", "trigger" -> {
                if (flow && source == "step") bad("$at: a flow-level only_if runs before any step, so it can't read a step's result")
                path(source, c.opt(source), at)
                val t = test(TESTS); val value = c.opt(t)
                when (t) {
                    "is", "is_not" -> if (value !is String && value !is Number && value !is Boolean) bad("$at.$t must be text, a number or true/false")
                    "in" -> if (value !is JSONArray || value.length() !in 1..20) bad("$at.in must be a list of 1-20 values")
                    "at_least", "below" -> if (value !is Number) bad("$at.$t must be a number")
                    "exists" -> if (value !is Boolean) bad("$at.exists must be true or false")
                }
            }
            "time" -> {
                if (tests.isNotEmpty()) bad("$at: time is a test on its own: {\"time\":{\"between\":[\"22:00\",\"07:00\"],\"days\":[\"mon\",…]}}")
                val t = c.opt("time") as? JSONObject ?: bad("$at.time must be {\"between\":[\"HH:MM\",\"HH:MM\"], \"days\":[…]}")
                t.keys().asSequence().firstOrNull { it != "between" && it != "days" }?.let { bad("$at.time.$it isn't a time field (between, days)") }
                if (!t.has("between") && !t.has("days")) bad("$at.time needs between, days or both")
                t.opt("between")?.let { b ->
                    val a = b as? JSONArray
                    if (a == null || a.length() != 2 || (0..1).any { !HHMM.matches(a.opt(it) as? String ?: "") }) bad("$at.time.between must be two times [\"HH:MM\", \"HH:MM\"], 24-hour, like [\"22:00\", \"07:00\"]")
                    if (a.getString(0) == a.getString(1)) bad("$at.time.between: the two times must differ")
                }
                t.opt("days")?.let { d ->
                    val a = d as? JSONArray
                    if (a == null || a.length() !in 1..7 || (0 until a.length()).any { a.opt(it) !in DAYS }) bad("$at.time.days must be a list of ${DAYS.joinToString()}")
                }
            }
            "sun" -> {
                if (tests.isNotEmpty()) bad("$at: sun is a test on its own: {\"sun\":{\"after\":\"sunset\", \"offset_min\":0}}")
                val s = c.opt("sun") as? JSONObject ?: bad("$at.sun must be {\"after\" or \"before\": \"sunrise\" or \"sunset\", \"offset_min\"}")
                s.keys().asSequence().firstOrNull { it !in setOf("after", "before", "offset_min", "latitude", "longitude") }?.let { bad("$at.sun.$it isn't a sun field (after, before, offset_min, latitude, longitude)") }
                val sides = listOf("after", "before").filter { s.has(it) }
                if (sides.size != 1 || s.opt(sides[0]) !in SunTimes.ONS) bad("$at.sun needs one of after or before: sunrise or sunset")
                s.opt("offset_min")?.let { if (it !is Int || it !in -SunTimes.MAX_OFFSET_MIN..SunTimes.MAX_OFFSET_MIN) bad("$at.sun.offset_min must be a whole number from -${SunTimes.MAX_OFFSET_MIN} to ${SunTimes.MAX_OFFSET_MIN}") }
                if (s.has("latitude") != s.has("longitude")) bad("$at.sun: give both latitude and longitude, or neither (the phone then saves a rough position once)")
                if (s.has("latitude")) coords(s, "$at.sun", bad) else { needs.location = true; needs.sunPosition = true }
            }
            "phone" -> {
                val reading = c.opt("phone") as? String
                val allowed = PHONE[reading] ?: bad("$at.phone must be ${PHONE.keys.joinToString()}")
                val t = test(allowed); val value = c.opt(t)
                when (reading) {
                    "charging" -> if (value !is Boolean) bad("$at.$t must be true or false")
                    "battery" -> if (value !is Number || value.toDouble() !in 0.0..100.0) bad("$at.$t must be a percentage 0-100")
                    "network" -> {
                        val values = if (t == "in") (value as? JSONArray)?.let { a -> (0 until a.length()).map { a.opt(it) } } else listOf(value)
                        if (values == null || values.isEmpty() || values.any { it !in NETWORKS }) bad("$at.$t must be ${if (t == "in") "a list of " else ""}${NETWORKS.joinToString(", ")}")
                    }
                }
            }
            "zone" -> {
                val z = c.opt("zone") as? JSONObject ?: bad("$at.zone must be a place {latitude, longitude, radius_m}")
                z.keys().asSequence().firstOrNull { it !in setOf("latitude", "longitude", "radius_m") }?.let { bad("$at.zone.$it isn't a zone field (latitude, longitude, radius_m)") }
                if (!z.has("latitude") || !z.has("longitude")) bad("$at.zone needs latitude and longitude")
                coords(z, "$at.zone", bad)
                z.opt("radius_m")?.let { if (it !is Int || it !in 100..10000) bad("$at.zone.radius_m must be a whole number from 100 to 10000") }
                if (test(setOf("is")).let { c.opt(it) } !is Boolean) bad("$at.is must be true (inside) or false (outside)")
                needs.location = true; needs.zone = true
            }
            "mode" -> {
                if (c.opt("mode") != true) bad("$at: a mode test is {\"mode\": true, \"is\": \"away\"} (is, is_not or in)")
                val t = test(setOf("is", "is_not", "in")); val value = c.opt(t)
                val values = if (t == "in") (value as? JSONArray)?.takeIf { it.length() in 1..Modes.MAX_MODES }?.let { a -> (0 until a.length()).map { a.opt(it) } } else listOf(value)
                if (values == null || values.any { it !is String || !Modes.NAME.matches(it) }) bad("$at.$t must be ${if (t == "in") "a list of modes" else "a mode"}, like \"away\" (lowercase letters, digits or _)")
            }
            "count", "sum" -> {
                val o = c.opt(source) as? JSONObject ?: bad("$at.$source must be {\"table\": …, \"since\": \"today\"${if (source == "sum") ", \"column\": …" else ""}}")
                rowFilter(o, "$at.$source", if (source == "sum") setOf("column") else emptySet(), bad)
                if (source == "sum" && !ID.matches(o.opt("column") as? String ?: "")) bad("$at.sum.column must name a number column of the table")
                val t = test(setOf("at_least", "below"))
                if (c.opt(t) !is Number) bad("$at.$t must be a number")
                needs.tables = true
            }
        }
        return needs
    }

    private fun coords(o: JSONObject, at: String, bad: (String) -> Nothing) {
        val lat = o.opt("latitude") as? Number; val lon = o.opt("longitude") as? Number
        if (lat == null || lon == null || !lat.toDouble().isFinite() || !lon.toDouble().isFinite() || lat.toDouble() !in -90.0..90.0 || lon.toDouble() !in -180.0..180.0) bad("$at: invalid coordinates")
    }

    /**
     * The rows a count, sum or lookup reads: `table`, `since` (today, this_week, all) or `hours` (1-720), and `where`
     * {column, is}. [extra] names the other fields the caller takes.
     */
    fun rowFilter(o: JSONObject, at: String, extra: Set<String>, bad: (String) -> Nothing) {
        o.keys().asSequence().firstOrNull { it !in setOf("table", "since", "hours", "where") + extra }?.let { bad("$at.$it isn't a field here (table, since, hours, where${extra.joinToString("") { ", $it" }})") }
        if (!ID.matches(o.opt("table") as? String ?: "")) bad("$at.table must be a table id of the applet")
        if (o.has("since") && o.has("hours")) bad("$at: give since or hours, not both")
        o.opt("since")?.let { if (it !in SINCE) bad("$at.since must be today, this_week or all (or give hours)") }
        o.opt("hours")?.let { if (it !is Int || it !in 1..MAX_HOURS) bad("$at.hours must be a whole number from 1 to $MAX_HOURS") }
        o.opt("where")?.let { w ->
            val wo = w as? JSONObject
            if (wo == null || wo.length() != 2 || !ID.matches(wo.opt("column") as? String ?: "") || !wo.has("is")) bad("$at.where must be {\"column\": \"<column>\", \"is\": <value>}")
            val v = wo.opt("is")
            if (v !is String && v !is Number && v !is Boolean && v != JSONObject.NULL) bad("$at.where.is must be text, a number, true/false or null")
        }
    }

    /** Whether any only_if in [definition] (the flow's own and its steps') reads a table, or needs a location fix. */
    fun needsOf(definition: JSONObject): Needs {
        val n = Needs()
        fun walk(c: Any?) {
            val o = c as? JSONObject ?: return
            listOf("all", "any").forEach { g -> o.optJSONArray(g)?.let { a -> for (i in 0 until a.length()) walk(a.opt(i)) } }
            if (o.has("count") || o.has("sum")) n.tables = true
            if (o.has("zone")) { n.location = true; n.zone = true }
            o.optJSONObject("sun")?.let { if (!it.has("latitude")) { n.location = true; n.sunPosition = true } }
        }
        walk(definition.opt("only_if"))
        definition.optJSONArray("steps")?.let { s -> for (i in 0 until s.length()) walk(s.optJSONObject(i)?.opt("only_if")) }
        return n
    }

    // ---- holding ---------------------------------------------------------------------------------------------

    /** The start of a `since` / `hours` period at [now], in ms; null for all rows. */
    fun sinceMs(o: JSONObject, now: ZonedDateTime): Long? = when {
        o.has("hours") -> now.toInstant().toEpochMilli() - o.optInt("hours") * 3_600_000L
        o.optString("since") == "today" -> now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
        o.optString("since") == "this_week" -> now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(now.zone).toInstant().toEpochMilli()
        else -> null
    }

    /** Whether [c] holds. [value] reads a step / trigger path (null when it has no value). */
    fun holds(c: JSONObject, facts: Facts, value: (source: String, path: String) -> Any?): Boolean {
        c.optJSONArray("all")?.let { a -> return (0 until a.length()).all { holds(a.getJSONObject(it), facts, value) } }
        c.optJSONArray("any")?.let { a -> return (0 until a.length()).any { holds(a.getJSONObject(it), facts, value) } }
        return when {
            c.has("step") -> test(c, value("step", c.getString("step")))
            c.has("trigger") -> test(c, value("trigger", c.getString("trigger")))
            c.has("time") -> inTime(c.getJSONObject("time"), facts.now)
            c.has("sun") -> c.getJSONObject("sun").let { s ->
                val pos = if (s.has("latitude")) s.getDouble("latitude") to s.getDouble("longitude") else facts.sunPosition()
                pos != null && sunHolds(s, pos.first, pos.second, facts.now)
            }
            c.has("phone") -> test(c, facts.phone(c.getString("phone")))
            c.has("zone") -> c.getJSONObject("zone").let { z ->
                val here = facts.location() ?: return false
                val inside = distanceM(here.first, here.second, z.getDouble("latitude"), z.getDouble("longitude")) <= z.optInt("radius_m", 200)
                inside == c.optBoolean("is")
            }
            c.has("mode") -> test(c, facts.mode())
            c.has("count") -> c.getJSONObject("count").let { o -> test(c, facts.count(o.getString("table"), sinceMs(o, facts.now), o.optJSONObject("where"))) }
            c.has("sum") -> c.getJSONObject("sum").let { o -> test(c, facts.sum(o.getString("table"), o.getString("column"), sinceMs(o, facts.now), o.optJSONObject("where"))) }
            else -> false
        }
    }

    /** The one test of [c] on [v] (null: no value, so only `exists: false` holds). */
    fun test(c: JSONObject, v: Any?): Boolean = when {
        c.has("exists") -> (v != null) == c.getBoolean("exists")
        c.has("is") -> same(v, c.opt("is"))
        c.has("is_not") -> v != null && !same(v, c.opt("is_not"))
        c.has("in") -> v != null && c.getJSONArray("in").let { a -> (0 until a.length()).any { same(v, a.opt(it)) } }
        c.has("at_least") -> (v as? Number)?.toDouble()?.let { it >= c.getDouble("at_least") } == true
        c.has("below") -> (v as? Number)?.toDouble()?.let { it < c.getDouble("below") } == true
        else -> false
    }

    /** Equal as a condition sees it: numbers by value, true/false as text, text trimmed and ignoring case. */
    fun same(a: Any?, b: Any?): Boolean = when {
        a == null || b == null || a == JSONObject.NULL || b == JSONObject.NULL -> (a == null || a == JSONObject.NULL) && (b == null || b == JSONObject.NULL)
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is Boolean || b is Boolean -> a.toString() == b.toString()
        else -> a.toString().trim().equals(b.toString().trim(), ignoreCase = true)
    }

    /** A time test at [now]: inside `between` (crossing midnight: the early hours belong to the day before) and on one of `days`. */
    fun inTime(t: JSONObject, now: ZonedDateTime): Boolean {
        val time = now.toLocalTime()
        var day = now.toLocalDate()
        t.optJSONArray("between")?.let { b ->
            val start = LocalTime.parse(b.getString(0)); val end = LocalTime.parse(b.getString(1))
            if (start < end) { if (time < start || time >= end) return false }
            else if (time < end) day = day.minusDays(1)
            else if (time < start) return false
        }
        t.optJSONArray("days")?.let { d -> if ((0 until d.length()).none { d.getString(it) == DAYS[day.dayOfWeek.value - 1] }) return false }
        return true
    }

    /** Today's (the local date of [now]) sunrise or sunset at [lat], [lon], in ms; null in polar day or night. */
    fun todays(on: String, lat: Double, lon: Double, now: ZonedDateTime): Long? {
        val date = now.toLocalDate(); val d = date.toEpochDay()
        return (d - 1..d + 1).mapNotNull { SunTimes.event(it, lat, lon, on == "sunrise") }.firstOrNull { Instant.ofEpochMilli(it).atZone(now.zone).toLocalDate() == date }
    }

    fun sunHolds(s: JSONObject, lat: Double, lon: Double, now: ZonedDateTime): Boolean {
        val after = s.has("after"); val on = s.optString(if (after) "after" else "before")
        val at = todays(on, lat, lon, now)?.plus(s.optInt("offset_min", 0) * 60_000L) ?: return false
        val t = now.toInstant().toEpochMilli()
        return if (after) t >= at else t < at
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    // ---- plain words -----------------------------------------------------------------------------------------

    /**
     * [c] as words without a leading "if": "between 22:00 and 07:00", "when the mode is away". [stepWords] gives a
     * step / trigger test's words (with its own "if" or "unless").
     */
    fun words(c: JSONObject, stepWords: (JSONObject) -> String): String {
        c.optJSONArray("all")?.let { a -> return join((0 until a.length()).map { words(a.getJSONObject(it), stepWords) }, "and") }
        c.optJSONArray("any")?.let { a -> return join((0 until a.length()).map { words(a.getJSONObject(it), stepWords) }, "or") }
        return when {
            c.has("step") || c.has("trigger") -> stepWords(c).removePrefix("if ")
            c.has("time") -> c.getJSONObject("time").let { t ->
                listOfNotNull(t.optJSONArray("between")?.let { "between ${it.getString(0)} and ${it.getString(1)}" },
                    t.optJSONArray("days")?.let { d -> "on " + days((0 until d.length()).map { d.getString(it) }) }).joinToString(" ")
            }
            c.has("sun") -> c.getJSONObject("sun").let { s ->
                val after = s.has("after"); val on = s.optString(if (after) "after" else "before"); val off = s.optInt("offset_min", 0)
                val point = when { off == 0 -> on; off < 0 -> "${span(-off)} before $on"; else -> "${span(off)} after $on" }
                (if (after) "after " else "before ") + point
            }
            c.has("phone") -> when (c.optString("phone")) {
                "charging" -> if ((c.opt("is") ?: !(c.opt("is_not") as Boolean)) == true) "when the phone is charging" else "when the phone isn't charging"
                "battery" -> if (c.has("below")) "when the battery is below ${num(c.opt("below"))}%" else "when the battery is at least ${num(c.opt("at_least"))}%"
                else -> when {
                    c.has("is") -> "when the phone is ${net(c.opt("is"))}"
                    c.has("is_not") -> "when the phone isn't ${net(c.opt("is_not"))}"
                    else -> "when the phone is " + c.getJSONArray("in").let { a -> (0 until a.length()).joinToString(" or ") { net(a.opt(it)) } }
                }
            }
            c.has("zone") -> c.getJSONObject("zone").let { z -> "when the phone is${if (c.optBoolean("is")) "" else "n't"} within ${z.optInt("radius_m", 200)} m of a place" }
            c.has("mode") -> when {
                c.has("is") -> "when the mode is ${c.opt("is")}"
                c.has("is_not") -> "when the mode isn't ${c.opt("is_not")}"
                else -> "when the mode is " + c.getJSONArray("in").let { a -> (0 until a.length()).joinToString(" or ") { a.optString(it) } }
            }
            c.has("count") -> c.getJSONObject("count").let { o ->
                val n = num(c.opt(if (c.has("below")) "below" else "at_least"))
                (if (c.has("below")) "when there are fewer than $n" else "when there are at least $n") + " rows in ${rowsWords(o)}"
            }
            c.has("sum") -> c.getJSONObject("sum").let { o ->
                "when the total of ${o.optString("column")} in ${rowsWords(o)} is " + if (c.has("below")) "below ${num(c.opt("below"))}" else "at least ${num(c.opt("at_least"))}"
            }
            else -> "when a condition holds"
        }
    }

    /** "\"visits\" where taken is false today". */
    fun rowsWords(o: JSONObject): String {
        val where = o.optJSONObject("where")?.let { " where ${it.optString("column")} is ${if (it.isNull("is")) "empty" else it.opt("is").toString()}" } ?: ""
        val period = when {
            o.has("hours") -> o.optInt("hours").let { h -> " in the last ${if (h == 1) "hour" else "$h hours"}" }
            o.optString("since") == "today" -> " today"
            o.optString("since") == "this_week" -> " this week"
            else -> ""
        }
        return "\"${o.optString("table").take(40)}\"$where$period"
    }

    private fun join(parts: List<String>, word: String) = if (parts.size <= 1) parts.joinToString() else parts.dropLast(1).joinToString(", ") + " $word " + parts.last()
    private fun days(d: List<String>): String = when (d.toSet()) {
        setOf("mon", "tue", "wed", "thu", "fri") -> "weekdays"
        setOf("sat", "sun") -> "weekends"
        else -> join(d.map { it.replaceFirstChar { c -> c.uppercase() } }, "and")
    }
    private fun span(m: Int) = when { m % 60 == 0 -> "${m / 60} hour${if (m == 60) "" else "s"}"; else -> "$m minutes" }
    private fun net(v: Any?) = when (v) { "wifi" -> "on Wi-Fi"; "mobile" -> "on mobile data"; "none" -> "offline"; else -> v.toString() }
    private fun num(v: Any?): String = (v as? Number)?.toDouble()?.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() } ?: v.toString()
}
