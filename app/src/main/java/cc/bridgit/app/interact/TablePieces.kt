package cc.bridgit.app.interact

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wave 2 parts of the `table` and `gallery` pieces and the `camera` button (docs/applet-wave2.md B-E), kept
 * out of [CardPieces] so that file stays small: their checks, grouping and totals, row boxes and row marks. Pure.
 */
object TablePieces {
    const val MAX_GROUPED_ROWS = 500
    const val MAX_ROWS = 50
    const val MAX_SOURCE_ROWS = 500
    const val MAX_TOTALS = 6
    const val MAX_MARKS = 3
    val SIZES = setOf("large", "medium")
    val LENSES = setOf("back", "front")
    private val NUMERIC = setOf("number", "integer")
    private val KEY = Regex("^[A-Za-z0-9_\$-]{1,40}$")

    private fun bad(m: String): Nothing = throw CardPieces.Bad(m)

    fun tableOf(v: Any?, sources: JSONObject): String? {
        val path = ((v as? JSONObject)?.takeIf { it.length() == 1 }?.opt("\$source") as? String) ?: return null
        val parts = path.split('.')
        if (parts.size > 2 || (parts.size == 2 && parts[1] != "items")) return null
        return sources.optJSONObject(parts[0])?.optString("table")?.ifEmpty { null }
    }

    private fun column(table: String?, scope: CardPieces.Scope, key: String, at: String): String? {
        table ?: return null
        val types = scope.columnTypes[table] ?: return null
        return types[key] ?: bad("$at: $key isn't a column of $table (${types.keys.joinToString()}).")
    }

    private fun key(v: Any?, at: String): String = (v as? String)?.takeIf { KEY.matches(it) } ?: bad("$at must name a column, like \"category\".")

    fun checkTable(p: JSONObject, at: String, table: String?, scope: CardPieces.Scope) {
        val shown = p.optJSONArray("columns")?.let { c -> (0 until c.length()).mapNotNull { c.optJSONObject(it)?.optString("key") } }?.toSet() ?: emptySet()
        p.opt("group_by")?.let { g -> column(table, scope, key(g, "$at.group_by"), "$at.group_by") }
        p.opt("totals")?.let { t ->
            val list = t as? JSONArray ?: bad("$at.totals must be a list of number columns, like [\"new_usd\"].")
            if (list.length() !in 1..MAX_TOTALS) bad("$at.totals must name 1-$MAX_TOTALS columns.")
            for (i in 0 until list.length()) {
                val k = key(list.opt(i), "$at.totals[$i]")
                val type = column(table, scope, k, "$at.totals")
                if (type != null && type !in NUMERIC) bad("$at.totals: $k is a $type column; totals add up number or integer columns only.")
                if (k !in shown) bad("$at.totals: $k must also be one of the piece's columns (its total uses that column's format).")
            }
        }
        p.opt("marks")?.let { m ->
            val o = m as? JSONObject ?: bad("$at.marks must be an object like {\"insurable\": {\"true\": \"Insurable\", \"false\": \"Not insurable\"}}.")
            if (o.length() !in 1..MAX_MARKS) bad("$at.marks must name 1-$MAX_MARKS columns.")
            for (k in o.keys()) {
                key(k, "$at.marks")
                val type = column(table, scope, k, "$at.marks")
                if (type != null && type != "boolean") bad("$at.marks: $k is a $type column; a mark shows a true/false column.")
                when (val w = o.opt(k)) {
                    true -> Unit
                    is JSONObject -> w.keys().forEach { b ->
                        if (b != "true" && b != "false") bad("$at.marks.$k: use the keys true and false.")
                        if ((w.opt(b) as? String)?.trim()?.length !in 1..40) bad("$at.marks.$k.$b must be 1-40 characters.")
                    }
                    else -> bad("$at.marks.$k must be true or {\"true\": \"…\", \"false\": \"…\"}.")
                }
            }
        }
        p.opt("photo")?.let { ph ->
            val o = ph as? JSONObject ?: bad("$at.photo must be {\"column\": \"photo\", \"box\": \"box\"}.")
            o.keys().asSequence().firstOrNull { it != "column" && it != "box" }?.let { bad("$at.photo: $it isn't allowed (column, box).") }
            val c = key(o.opt("column"), "$at.photo.column")
            column(table, scope, c, "$at.photo.column")?.let { if (it != "photo") bad("$at.photo.column: $c is a $it column, not a photo column.") }
            o.opt("box")?.let { b -> val k = key(b, "$at.photo.box"); column(table, scope, k, "$at.photo.box")?.let { if (it != "text") bad("$at.photo.box: $k is a $it column; a box is kept in a text column.") } }
        }
        p.opt("owner_edit")?.let { e ->
            if (e !is Boolean) bad("$at.owner_edit must be true or false.")
            if (e && table == null) bad("$at.owner_edit works only on rows of an on-phone table (a table source).")
        }
        val max = if (p.has("group_by")) MAX_GROUPED_ROWS else MAX_ROWS
        p.opt("limit")?.let { if (it !is Int || it !in 1..max) bad("$at.limit must be 1-$max" + (if (max == MAX_ROWS) " (up to $MAX_GROUPED_ROWS with group_by)." else ".")) }
    }

    fun checkGallery(p: JSONObject, at: String, table: String?, scope: CardPieces.Scope) {
        p.opt("photo")?.let { ph ->
            val c = key(ph, "$at.photo")
            column(table, scope, c, "$at.photo")?.let { if (it != "photo") bad("$at.photo: $c is a $it column, not a photo column.") }
        }
        (p.opt("caption") as? String)?.let { c -> if (c !in setOf("at", "id", "taken_at", "ref")) column(table, scope, c, "$at.caption") }
        p.opt("caption_count")?.let { c -> column(table, scope, key(c, "$at.caption_count"), "$at.caption_count") }
        p.opt("words")?.let { w ->
            val o = w as? JSONObject ?: bad("$at.words must be an object of value → words.")
            if (o.length() !in 1..32 || o.keys().asSequence().any { (o.opt(it) as? String)?.length !in 1..80 }) bad("$at.words must map 1-32 values to words of 1-80 characters.")
        }
    }

    fun checkCamera(v: Any?, at: String, scope: CardPieces.Scope, value: (Any?, String) -> Unit): String {
        val c = v as? JSONObject ?: bad("$at must be {\"table\": \"photos\", \"photo\": \"photo\", \"row\": {…}, \"size\": \"large\"}.")
        c.keys().asSequence().firstOrNull { it !in setOf("table", "photo", "row", "size", "lens") }?.let { bad("$at: $it isn't allowed (table, photo, row, size, lens).") }
        val table = c.optString("table")
        val types = scope.columnTypes[table] ?: bad("$at.table must name an on-phone table in processing.tables.")
        val photo = (c.opt("photo") ?: "photo") as? String ?: bad("$at.photo must name the table's photo column.")
        val type = types[photo] ?: bad("$at.photo: $photo isn't a column of $table.")
        if (type != "photo") bad("$at.photo: $photo is a $type column, not a photo column.")
        c.opt("row")?.let { r ->
            val row = r as? JSONObject ?: bad("$at.row must be an object of column → value.")
            for (k in row.keys()) {
                if (k !in types) bad("$at.row: $k isn't a column of $table.")
                if (k == photo) bad("$at.row: $k is filled with each photo.")
                value(row.opt(k), "$at.row.$k")
            }
        }
        c.opt("size")?.let { if (it !in SIZES) bad("$at.size must be large or medium.") }
        c.opt("lens")?.let { if (it !in LENSES) bad("$at.lens must be back or front.") }
        return table
    }

    fun usesCamera(card: JSONObject?): Boolean {
        val pieces = card?.optJSONArray("pieces") ?: return false
        return (0 until pieces.length()).any { i ->
            val items = pieces.optJSONObject(i)?.takeIf { it.optString("type") == "buttons" }?.optJSONArray("items") ?: return@any false
            (0 until items.length()).any { items.optJSONObject(it)?.optJSONObject("do")?.has("camera") == true }
        }
    }

    fun number(v: Any?): Double? = when (v) {
        null, JSONObject.NULL -> null
        is Number -> v.toDouble().takeIf { !it.isNaN() && !it.isInfinite() }
        is String -> v.trim().replace(Regex("[\\p{Sc}\\s\\u00a0]"), "").replace(Regex(",(?=\\d{3}(\\D|$))"), "").toDoubleOrNull()
        else -> null
    }

    data class Group(val name: String?, val rows: List<JSONObject>, val totals: Map<String, Double?>)
    data class Grouped(val groups: List<Group>, val totals: Map<String, Double?>)

    private fun sums(rows: List<JSONObject>, totals: List<String>): Map<String, Double?> = totals.associateWith { k ->
        rows.mapNotNull { number(it.opt(k)) }.takeIf { it.isNotEmpty() }?.sum()
    }

    fun group(rows: List<JSONObject>, groupBy: String?, totals: List<String> = emptyList()): Grouped {
        if (groupBy == null) return Grouped(listOf(Group(null, rows, sums(rows, totals))), sums(rows, totals))
        val by = LinkedHashMap<String?, MutableList<JSONObject>>()
        for (r in rows) {
            val name = CardPieces.show(r.opt(groupBy)?.takeIf { it != JSONObject.NULL }).trim().ifEmpty { null }
            by.getOrPut(name) { ArrayList() } += r
        }
        val named = by.keys.filterNotNull().sortedWith(String.CASE_INSENSITIVE_ORDER.then(naturalOrder()))
        val order = named + (if (by.containsKey(null)) listOf(null) else emptyList())
        return Grouped(order.map { Group(it, by.getValue(it), sums(by.getValue(it), totals)) }, sums(rows, totals))
    }

    data class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    fun box(v: Any?): Box? {
        val nums: List<Double> = when (v) {
            is JSONObject -> listOf("x0", "y0", "x1", "y1").map { number(v.opt(it)) ?: return null }
            is JSONArray -> if (v.length() == 4) (0 until 4).map { number(v.opt(it)) ?: return null } else return null
            is String -> {
                val s = v.trim()
                if (s.startsWith("{") || s.startsWith("[")) return runCatching { if (s.startsWith("{")) JSONObject(s) else JSONArray(s) }.getOrNull()?.let { box(it) }
                s.split(',').map { it.trim().toDoubleOrNull() ?: return null }.takeIf { it.size == 4 } ?: return null
            }
            else -> return null
        }
        if (nums.any { it.isNaN() || it < -0.05 || it > 1.05 }) return null
        val c = nums.map { it.coerceIn(0.0, 1.0) }
        val b = Box(minOf(c[0], c[2]), minOf(c[1], c[3]), maxOf(c[0], c[2]), maxOf(c[1], c[3]))
        return b.takeIf { it.x1 - it.x0 > 0.001 && it.y1 - it.y0 > 0.001 }
    }

    fun mark(value: Any?, words: Any?, column: String): String? {
        val b = when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) { "true", "yes" -> true; "false", "no" -> false; else -> return null }
            is Number -> value.toDouble() != 0.0
            else -> return null
        }
        val w = (words as? JSONObject)?.optString(b.toString())?.trim()?.ifEmpty { null }
        return w ?: if (b) column.replace('_', ' ').replaceFirstChar { it.uppercase() } else null
    }

    /**
     * A gallery photo's caption: the `caption` field in `caption_format` (or as `words` when the piece has words; by
     * default a time as "ago", anything else as text), then "3 items" from `caption_count`.
     */
    fun caption(p: JSONObject, item: JSONObject): String? {
        val words = p.optJSONObject("words")
        val main = p.optString("caption").ifEmpty { null }?.let { k ->
            val raw = item.opt(k)?.takeIf { it != JSONObject.NULL }
            val f = p.optString("caption_format").ifEmpty { if (words != null) "words" else null }
            if (f == null) CardPieces.format(raw, "ago") ?: CardPieces.format(raw, "text")
            else CardPieces.format(raw, f, JSONObject().apply { words?.let { put("words", it) } })
        }
        val count = p.optString("caption_count").ifEmpty { null }?.let { countWords(item.opt(it)) }
        return listOfNotNull(main, count).joinToString(" · ").ifEmpty { null }
    }

    fun countWords(v: Any?): String? =number(v)?.takeIf { it >= 0 && it == Math.floor(it) }?.toLong()?.let { if (it == 1L) "1 item" else "$it items" }
}
