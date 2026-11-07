package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime

/**
 * On-phone tables of an applet (docs/applet-cards.md, "The on-phone table"): declaring them in
 * `processing.tables`, checking one row against its columns, and the owner-facing words for an update that
 * deletes data. Pure: no Android, so every rule is unit-tested; [AppletTableStore] keeps the rows.
 */
object AppletTables {
    const val MAX_TABLES = 3
    const val MAX_COLUMNS = 16
    const val MIN_ROWS = 1
    const val MAX_ROWS = 10_000
    const val DEFAULT_MAX_ROWS = 1000
    const val MIN_KEEP_DAYS = 1
    const val MAX_KEEP_DAYS = 3650
    const val DEFAULT_KEEP_DAYS = 90
    /** One row's values, in UTF-8 bytes (a photo counts as its ref). */
    const val MAX_ROW_BYTES = 4096
    /** All rows of one applet. */
    const val MAX_APPLET_ROW_BYTES = 5_000_000L
    const val MAX_KEPT_PHOTOS = 1000
    const val MAX_KEPT_PHOTO_BYTES = 500L * 1024 * 1024
    const val MAX_WRITES_PER_MINUTE = 60
    /** A kept photo no row uses is deleted after this long. */
    const val ORPHAN_PHOTO_MS = 7L * 24 * 3600 * 1000
    const val MAX_TEXT = 1024
    const val MAX_LABEL = 80
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 32
    const val MAX_OPTION_CHARS = 80

    val ID = Regex("^[a-z][a-z0-9_]{0,31}$")
    val PHOTO_REF = Regex("^kept:[a-z0-9]{1,40}$")
    val TYPES = listOf("text", "number", "integer", "boolean", "time", "date", "choice", "photo", "json")
    /** Every row has these besides its columns, so no column may use them. */
    val RESERVED = setOf("id", "at", "by")
    private val TABLE_FIELDS = setOf("id", "label", "columns", "keep_days", "max_rows", "share_with_installer")
    private val COLUMN_FIELDS = setOf("type", "label", "options", "description")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    data class Column(val id: String, val type: String, val label: String, val options: List<String> = emptyList(), val description: String? = null)
    data class Table(val id: String, val label: String, val columns: Map<String, Column>, val keepDays: Int = DEFAULT_KEEP_DAYS,
                     val maxRows: Int = DEFAULT_MAX_ROWS, val shared: Boolean = false)

    /** A row that doesn't fit its table: [code] is `invalid_row` or `row_too_large`. */
    class Refused(val code: String, message: String) : Exception(message)

    private fun bad(message: String): Nothing = throw AppletPackage.Bad(message)

    /** The tables [processing] declares (none when it has no `tables`). */
    fun parse(processing: JSONObject): List<Table> {
        val raw = processing.opt("tables") ?: return emptyList()
        if (raw == JSONObject.NULL) return emptyList()
        val list = raw as? JSONArray ?: bad("processing.tables must be a list of tables.")
        if (list.length() > MAX_TABLES) bad("An applet may declare at most $MAX_TABLES tables.")
        val out = ArrayList<Table>()
        for (i in 0 until list.length()) {
            val t = list.opt(i) as? JSONObject ?: bad("processing.tables[$i] must be an object.")
            val id = t.opt("id") as? String
            if (id == null || !ID.matches(id)) bad("processing.tables[$i]: id must be 1-32 lowercase letters, digits or _ and start with a letter.")
            val at = "Table $id"
            if (out.any { it.id == id }) bad("$at is declared twice.")
            t.keys().asSequence().firstOrNull { it !in TABLE_FIELDS }?.let { bad("$at: $it isn't a table field (${TABLE_FIELDS.joinToString()}).") }
            val label = label(t, id, at)
            val cols = t.opt("columns") as? JSONObject ?: bad("$at needs columns: an object of 1-$MAX_COLUMNS columns.")
            if (cols.length() !in 1..MAX_COLUMNS) bad("$at needs 1-$MAX_COLUMNS columns.")
            val columns = LinkedHashMap<String, Column>()
            for (cid in cols.keys()) {
                val cat = "$at, column $cid"
                if (!ID.matches(cid)) bad("$at: column id $cid must be 1-32 lowercase letters, digits or _ and start with a letter.")
                if (cid in RESERVED) bad("$at: $cid can't be a column id; every row already has ${RESERVED.joinToString()}.")
                val c = cols.opt(cid) as? JSONObject ?: bad("$cat must be an object {type, label?, options?}.")
                c.keys().asSequence().firstOrNull { it !in COLUMN_FIELDS }?.let { bad("$cat: $it isn't a column field (${COLUMN_FIELDS.joinToString()}).") }
                val type = c.opt("type") as? String
                if (type == null || type !in TYPES) bad("$cat: type must be one of ${TYPES.joinToString()}.")
                val options = if (type == "choice") {
                    val o = c.opt("options") as? JSONArray ?: bad("$cat: a choice column needs options, a list of $MIN_OPTIONS-$MAX_OPTIONS texts.")
                    if (o.length() !in MIN_OPTIONS..MAX_OPTIONS) bad("$cat needs $MIN_OPTIONS-$MAX_OPTIONS options.")
                    val texts = (0 until o.length()).map { j -> (o.opt(j) as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_OPTION_CHARS } ?: bad("$cat: each option must be text of 1-$MAX_OPTION_CHARS characters.") }
                    if (texts.toSet().size != texts.size) bad("$cat: options must differ.")
                    texts
                } else {
                    if (c.has("options")) bad("$cat: only a choice column takes options.")
                    emptyList()
                }
                val description = when (val text = c.opt("description")) {
                    null -> null
                    is String -> text.trim().takeIf { it.isNotEmpty() && it.length <= MAX_TEXT } ?: bad("$cat: description must be 1-$MAX_TEXT characters.")
                    else -> bad("$cat: description must be text.")
                }
                if (type == "json" && description == null) bad("$cat: a json column needs a description of its contents and structure.")
                columns[cid] = Column(cid, type, label(c, cid, cat), options, description)
            }
            out += Table(id, label, columns,
                whole(t, "keep_days", DEFAULT_KEEP_DAYS, MIN_KEEP_DAYS, MAX_KEEP_DAYS, at),
                whole(t, "max_rows", DEFAULT_MAX_ROWS, MIN_ROWS, MAX_ROWS, at),
                when (val s = t.opt("share_with_installer")) { null -> false; is Boolean -> s; else -> bad("$at: share_with_installer must be true or false.") })
        }
        return out
    }

    private fun label(o: JSONObject, fallback: String, at: String): String = when (val l = o.opt("label")) {
        null -> fallback
        is String -> l.trim().takeIf { it.isNotEmpty() && it.length <= MAX_LABEL } ?: bad("$at: label must be 1-$MAX_LABEL characters.")
        else -> bad("$at: label must be text.")
    }

    private fun whole(o: JSONObject, key: String, default: Int, min: Int, max: Int, at: String): Int {
        val v = o.opt(key) ?: return default
        if ((v !is Int && v !is Long) || (v as Number).toLong() !in min.toLong()..max.toLong()) bad("$at: $key must be a whole number from $min to $max.")
        return v.toInt()
    }

    private fun refuse(message: String): Nothing = throw Refused("invalid_row", message)

    /**
     * [row] checked against [t]: every column is present in the result (missing ones as null), each value
     * fits its type. Numbers and true/false written as text are accepted and stored as numbers/booleans; a
     * number as text may carry a currency sign and thousands commas ("$1,200.50"). A choice matches its options
     * whatever the case or spaces around it, and is stored as the option is written.
     */
    fun coerce(t: Table, row: JSONObject): JSONObject {
        row.keys().asSequence().firstOrNull { it !in t.columns }?.let { refuse("${t.label} has no column $it (columns: ${t.columns.keys.joinToString()}).") }
        val out = JSONObject()
        for (c in t.columns.values) {
            val v = row.opt(c.id)
            out.put(c.id, if (v == null || v == JSONObject.NULL) JSONObject.NULL else value(c, v))
        }
        val bytes = out.toString().toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_ROW_BYTES) throw Refused("row_too_large", "This row is $bytes bytes; a row may hold at most $MAX_ROW_BYTES.")
        return out
    }

    private fun value(c: Column, v: Any): Any {
        val name = c.label
        return when (c.type) {
            "text" -> (v as? String ?: if (v is Number || v is Boolean) v.toString() else refuse("$name must be text."))
                .also { if (it.length > MAX_TEXT) refuse("$name is ${it.length} characters; at most $MAX_TEXT.") }
            "number" -> {
                val d = when (v) { is Number -> v.toDouble(); is String -> numberText(v)?.toDoubleOrNull(); else -> null }
                if (d == null || d.isNaN() || d.isInfinite()) refuse("$name must be a number.")
                if (v is Int || v is Long) v else d
            }
            "integer" -> {
                val n: Long? = when (v) {
                    is Int -> v.toLong(); is Long -> v
                    is Number -> v.toDouble().takeIf { it == Math.floor(it) && !it.isInfinite() && Math.abs(it) < 9.0e15 }?.toLong()
                    is String -> numberText(v)?.let { t -> t.toLongOrNull() ?: t.toDoubleOrNull()?.takeIf { it == Math.floor(it) && Math.abs(it) < 9.0e15 }?.toLong() }
                    else -> null
                }
                n ?: refuse("$name must be a whole number.")
            }
            "boolean" -> when (v) {
                is Boolean -> v
                is String -> when (v.trim().lowercase()) { "true" -> true; "false" -> false; else -> refuse("$name must be true or false.") }
                else -> refuse("$name must be true or false.")
            }
            "time" -> (v as? String)?.trim()?.takeIf { it.length <= 64 && time(it) } ?: refuse("$name must be an ISO time, like 14:30 or 2026-11-05T14:30:00Z.")
            "date" -> (v as? String)?.trim()?.takeIf { DATE.matches(it) && runCatching { LocalDate.parse(it) }.isSuccess } ?: refuse("$name must be a date, YYYY-MM-DD.")
            "choice" -> (v as? String)?.trim()?.let { s -> c.options.firstOrNull { it == s } ?: c.options.firstOrNull { it.trim().equals(s, ignoreCase = true) } }
                ?: refuse("$name must be one of ${c.options.joinToString()}.")
            "json" -> {
                if (v !is JSONObject && v !is JSONArray) refuse("$name must be a JSON object or array, not encoded text.")
                fun check(value: Any?, depth: Int) {
                    if (depth > 8) refuse("$name JSON may nest at most 8 levels.")
                    when (value) {
                        is JSONObject -> value.keys().forEach { check(value.opt(it), depth + 1) }
                        is JSONArray -> (0 until value.length()).forEach { check(value.opt(it), depth + 1) }
                        is Number -> if (!value.toDouble().isFinite()) refuse("$name JSON numbers must be finite.")
                    }
                }
                check(v, 0)
                // Detach from the caller; subsequent mutation must not change saved values.
                if (v is JSONObject) JSONObject(v.toString()) else JSONArray(v.toString())
            }
            "photo" -> (v as? String)?.takeIf { PHOTO_REF.matches(it) } ?: refuse("$name must be a kept photo; keep the photo: add keep: true to the photo step.")
            else -> refuse("$name has an unknown type.")
        }
    }

    private val THOUSANDS = Regex(",(?=\\d{3}(\\D|$))")
    private val PLAIN_NUMBER = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

    /**
     * A number written as text, without currency signs, spaces and thousands commas: "$1,200.50" → "1200.50",
     * "-€ 3" → "-3". A comma that isn't before exactly three digits ("1,5") stays, so it isn't read as 15.
     */
    internal fun numberText(raw: String): String? {
        val t = raw.trim().replace(Regex("[\\p{Sc}\\s\\u00a0]"), "").replace(THOUSANDS, "")
        return t.takeIf { PLAIN_NUMBER.matches(it) }
    }

    private fun time(s: String): Boolean = listOf<(String) -> Any>(
        { OffsetDateTime.parse(it) }, { LocalDateTime.parse(it) }, { OffsetTime.parse(it) }, { LocalTime.parse(it) },
    ).any { p -> runCatching { p(s) }.isSuccess }

    private fun rows(n: Int) = if (n == 1) "1 row" else "$n rows"

    /**
     * What an update from [old] to [new] deletes, as sentences for the owner; empty when nothing is lost.
     * [rows] counts the rows of a table (column null) or the rows that have a value in that column.
     * Added tables and columns delete nothing. A lower max_rows or keep_days that would drop rows is reported too,
     * so such an update waits for the owner. Newly shared tables are [shares].
     * [older] counts a table's rows written before a time (ms).
     */
    fun changes(old: List<Table>, new: List<Table>, rows: (table: String, column: String?) -> Int,
                older: (table: String, beforeMs: Long) -> Int = { _, _ -> 0 }, now: Long = System.currentTimeMillis()): List<String> {
        val out = ArrayList<String>()
        val next = new.associateBy { it.id }
        for (t in old) {
            val n = next[t.id]
            if (n == null) {
                val count = rows(t.id, null)
                if (count > 0) out += "This update deletes the table ${t.label} and its ${rows(count)}."
                continue
            }
            for (c in t.columns.values) {
                val nc = n.columns[c.id]
                if (nc != null && nc.type == c.type) continue
                val count = rows(t.id, c.id)
                if (count == 0) continue
                out += if (nc == null) "This update deletes the column ${c.label} from ${rows(count)}."
                else "This update changes the type of the column ${c.label}, which deletes it from ${rows(count)}."
            }
            val total = rows(t.id, null)
            if (n.maxRows < t.maxRows && total > n.maxRows) out += "This update keeps at most ${n.maxRows} rows in ${t.label}, which deletes the oldest ${rows(total - n.maxRows)}."
            if (n.keepDays < t.keepDays) older(t.id, now - n.keepDays * 86_400_000L).takeIf { it > 0 }?.let { out += "This update keeps ${t.label} rows for ${n.keepDays} days, which deletes ${rows(it)} older than that." }
        }
        return out
    }

    /**
     * What an update newly lets the installing agent read, as sentences for the owner: a table that turns
     * share_with_installer on, or a new table that has it. Like a new permission, such an update waits for the owner.
     * [rows] counts a table's rows now (shown so the owner knows how much it is).
     */
    fun shares(old: List<Table>, new: List<Table>, rows: (table: String) -> Int = { 0 }): List<String> {
        val before = old.associateBy { it.id }
        return new.filter { it.shared && before[it.id]?.shared != true }.map { t ->
            val n = if (before.containsKey(t.id)) rows(t.id) else 0
            "The agent that installed this applet will be able to read the rows of ${t.label}" + (if (n > 0) " (${rows(n)} now)." else ".")
        }
    }
}

/**
 * The rows and kept photos of installed applets, on the phone only. Rows are platform-store records of kind
 * `applet_row` with ids `row:<agent>:<table>:<row id>`; row ids sort by time, so the store pages them in
 * order. Kept photos are JPEG files under `files/applet-photos/<agent>/` plus an `applet_photo` record each.
 * Check-and-write runs under the store's lock (like [AppletDataModule]), so parallel writes can't pass a limit.
 */
class AppletTableStore(private val app: BridgeApp) {
    private val store get() = app.platform.store
    private val writes = HashMap<String, ArrayDeque<Long>>()
    private val listeners = mutableListOf<(String) -> Unit>()
    private var lastStamp = 0L

    fun addListener(l: (agentId: String) -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: (agentId: String) -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed(agentId: String) = synchronized(listeners) { listeners.toList() }.forEach { runCatching { it(agentId) } }

    private fun prefix(agent: String, table: String) = "row:$agent:$table:"
    private fun photoId(agent: String, key: String) = "photo:$agent:$key"
    private fun dir(agent: String) = File(app.filesDir, "applet-photos/" + agent.replace(Regex("[^A-Za-z0-9_-]"), "_"))
    private fun file(agent: String, key: String) = File(dir(agent), "$key.jpg")

    private var seeded = false

    /**
     * A new id that sorts after every earlier one (base 36 of microseconds, kept increasing). On first use it
     * starts after the largest row or photo id already stored, so a restart after the clock stepped back still
     * gives later ids (rows page by id, and an equal id would replace a row).
     */
    private fun stamp(): String = synchronized(this) {
        if (!seeded) {
            seeded = true
            val top = runCatching { synchronized(store) {
                store.readableDatabase.rawQuery("SELECT MAX(substr(id,-12)) FROM records WHERE kind IN (?,?)", arrayOf(ROW, PHOTO))
                    .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
            } }.getOrNull()?.toLongOrNull(36) ?: 0L
            lastStamp = maxOf(lastStamp, top)
        }
        lastStamp = maxOf(System.currentTimeMillis() * 1000, lastStamp + 1)
        lastStamp.toString(36).padStart(12, '0')
    }

    private fun refused(code: String, message: String): Nothing = throw AppletTables.Refused(code, message)

    /** The row as readers see it: {id, at, by, ...columns}. */
    private fun view(r: JSONObject) = JSONObject().put("id", r.optString("row_id")).put("at", r.optLong("at")).put("by", r.opt("by") ?: JSONObject.NULL).also { v ->
        r.optJSONObject("values")?.let { vals -> for (k in vals.keys()) v.put(k, vals.opt(k)) }
    }

    private fun refs(r: JSONObject): List<String> = r.optJSONArray("photos")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

    // ---- SQL over the store's records table (same lock as PlatformStore's own methods) ----------------------

    private fun countWhere(agent: String, kind: String, idPrefix: String): Pair<Int, Long> = synchronized(store) {
        store.readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(LENGTH(value)),0) FROM records WHERE agent=? AND kind=? AND substr(id,1,?)=?",
            arrayOf(agent, kind, idPrefix.length.toString(), idPrefix)).use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }
    }

    private fun select(agent: String, kind: String, idPrefix: String, extra: String, args: List<String>, order: String, limit: Int): List<JSONObject> = synchronized(store) {
        store.readableDatabase.rawQuery("SELECT value FROM records WHERE agent=? AND kind=? AND substr(id,1,?)=?$extra ORDER BY id $order LIMIT $limit",
            (listOf(agent, kind, idPrefix.length.toString(), idPrefix) + args).toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) } }
    }

    private fun used(agent: String, ref: String, exceptId: String? = null): Boolean = synchronized(store) {
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM records WHERE agent=? AND kind=? AND instr(value,?)>0 AND id<>?",
            arrayOf(agent, ROW, "\"$ref\"", exceptId ?: "")).use { it.moveToFirst(); it.getInt(0) > 0 }
    }

    /** Every kept photo ref [agent]'s rows use, in one pass over its rows (not one scan per photo). */
    private fun usedRefs(agent: String): Set<String> = synchronized(store) {
        val out = HashSet<String>(); var after = ""
        while (true) {
            val page = store.keyed(agent, ROW, "row:$agent:", after, 1000)
            page.forEach { out += refs(it) }
            if (page.size < 1000) break
            after = page.last().getString("id")
        }
        out
    }

    private fun dropPhoto(agent: String, ref: String) {
        val key = ref.removePrefix("kept:")
        store.delete(photoId(agent, key), agent)
        file(agent, key).delete()
    }

    /** Deletes the rows (records) and then each kept photo they used that no other row uses. */
    private fun deleteRows(agent: String, rows: List<JSONObject>) {
        val photos = rows.flatMap { refs(it) }.toSet()
        // One transaction: thousands of single deletes outside one hold the shared store far longer.
        val db = store.writableDatabase
        db.beginTransaction()
        try { rows.forEach { store.delete(it.getString("id"), agent) }; db.setTransactionSuccessful() } finally { db.endTransaction() }
        photos.forEach { if (!used(agent, it)) dropPhoto(agent, it) }
    }

    // ---- rows ---------------------------------------------------------------------------------------------

    /**
     * Writes one row of [table] for [agentId] and returns it ({id, at, by, ...columns}). Throws
     * [AppletTables.Refused]: invalid_row, row_too_large, rate_limited (60 a minute), quota_exceeded (5 MB of rows).
     * A full table (max_rows) drops its oldest rows to make room.
     */
    fun add(agentId: String, table: AppletTables.Table, row: JSONObject, by: JSONObject): JSONObject = addMany(agentId, table, listOf(row), by).single()

    /**
     * Writes [rows] (1-[MAX_MANY]) of [table] at once, as [add] does one: every row is checked first, then all are
     * written in one transaction, so a refused row leaves none. Counts as one write for the 60-a-minute limit.
     */
    fun addMany(agentId: String, table: AppletTables.Table, rows: List<JSONObject>, by: JSONObject): List<JSONObject> {
        if (rows.size !in 1..MAX_MANY) refused("invalid_row", "Write 1-$MAX_MANY rows at once (not ${rows.size}).")
        // One write never empties a whole table to make room for itself: more rows than the table keeps is refused.
        if (rows.size > table.maxRows) refused("invalid_row", "${table.label} keeps at most ${table.maxRows} rows; this writes ${rows.size} at once.")
        val checked = rows.mapIndexed { i, row ->
            val values = try { AppletTables.coerce(table, row) } catch (e: AppletTables.Refused) { if (rows.size == 1) throw e else throw AppletTables.Refused(e.code, "Row ${i + 1}: ${e.message}") }
            values to table.columns.values.filter { it.type == "photo" }.mapNotNull { (values.opt(it.id) as? String) }.distinct()
        }
        val result = synchronized(store) {
            checked.flatMap { it.second }.toSet().forEach { ref -> if (store.find(photoId(agentId, ref.removePrefix("kept:")), agentId, PHOTO) == null) refused("invalid_row", "The photo $ref isn't kept on this phone any more.") }
            val now = System.currentTimeMillis()
            val recent = writes.getOrPut(agentId) { ArrayDeque() }
            while (recent.isNotEmpty() && recent.first() < now - 60_000) recent.removeFirst()
            // The owner's own writes (an edit, a photo from the camera button) are taps, not a loop: never limited.
            val counted = !ownerWrite(by)
            if (counted && recent.size >= AppletTables.MAX_WRITES_PER_MINUTE) refused("rate_limited", "At most ${AppletTables.MAX_WRITES_PER_MINUTE} rows a minute; try again in a minute.")
            val records = checked.map { (values, photos) ->
                // Never replace a row: an id already taken (it shouldn't be) moves on to the next one.
                var key = stamp()
                while (store.find(prefix(agentId, table.id) + key) != null) key = stamp()
                JSONObject().put("id", prefix(agentId, table.id) + key).put("row_id", key).put("table", table.id).put("at", now).put("by", by)
                    .put("values", values).put("photos", JSONArray(photos))
            }
            val size = records.sumOf { it.toString().length + 200L }
            val (count, _) = countWhere(agentId, ROW, prefix(agentId, table.id))
            val writing = records
            val drop = if (count + writing.size > table.maxRows) select(agentId, ROW, prefix(agentId, table.id), "", emptyList(), "ASC", (count + writing.size - table.maxRows).coerceAtMost(count)) else emptyList()
            val (_, total) = countWhere(agentId, ROW, "row:$agentId:")
            if (total - drop.sumOf { it.toString().length.toLong() } + size > AppletTables.MAX_APPLET_ROW_BYTES)
                refused("quota_exceeded", "This applet's rows would pass ${AppletTables.MAX_APPLET_ROW_BYTES / 1_000_000} MB; delete some rows first.")
            val db = store.writableDatabase
            db.beginTransaction()
            val saved = try {
                // Save first: the oldest rows go after, so a photo the new row shares with one of them is still in use.
                val out = writing.map { view(store.save(agentId, ROW, it)) }
                deleteRows(agentId, drop)
                db.setTransactionSuccessful(); out
            } finally { db.endTransaction() }
            if (counted) recent.addLast(now)
            saved
        }
        changed(agentId)
        if (table.shared) result.forEach { r -> runCatching { rowAdded?.invoke(agentId, table.id, r.optString("id")) } }
        return result
    }

    /**
     * Changes some values of one row (docs/applet-wave2.md A): [set] is merged over the row's values (a JSON null
     * clears a value) and the whole row is checked again as in [add]. The row keeps its id. Kept photos the row no
     * longer uses (and no other row uses) are deleted, except after an agent's edit: those wait for the 7-day sweep
     * of unused photos ([clean]), so an agent can't wipe the owner's photos at once. An agent's edits count toward
     * the 60-writes-a-minute limit (rate_limited). Throws Refused("row_not_found") when the row is gone.
     */
    fun update(agentId: String, table: AppletTables.Table, rowId: String, set: JSONObject, by: JSONObject): JSONObject {
        val result = synchronized(store) {
            val id = prefix(agentId, table.id) + rowId
            val old = store.find(id, agentId, ROW) ?: refused("row_not_found", "That row isn't in ${table.label} any more.")
            val now = System.currentTimeMillis()
            val fromAgent = agentWrite(by)
            val recent = writes.getOrPut(agentId) { ArrayDeque() }
            while (recent.isNotEmpty() && recent.first() < now - 60_000) recent.removeFirst()
            if (fromAgent && recent.size >= AppletTables.MAX_WRITES_PER_MINUTE) refused("rate_limited", "At most ${AppletTables.MAX_WRITES_PER_MINUTE} writes a minute; try again in a minute.")
            val merged = JSONObject(old.optJSONObject("values")?.toString() ?: "{}")
            set.keys().forEach { k -> if (set.isNull(k)) merged.remove(k) else merged.put(k, set.get(k)) }
            val values = AppletTables.coerce(table, merged)
            val photos = table.columns.values.filter { it.type == "photo" }.mapNotNull { (values.opt(it.id) as? String) }.distinct()
            photos.forEach { ref -> if (store.find(photoId(agentId, ref.removePrefix("kept:")), agentId, PHOTO) == null) refused("invalid_row", "The photo $ref isn't kept on this phone any more.") }
            val record = JSONObject(old.toString()).put("values", values).put("photos", JSONArray(photos))
                .put("edited_at", now).put("edited_by", by)
            val saved = view(store.save(agentId, ROW, record))
            (refs(old) - photos.toSet()).forEach { ref ->
                if (used(agentId, ref)) return@forEach
                // An agent freed it: kept 7 more days (freed_at), then the sweep deletes it if still unused.
                if (fromAgent) store.find(photoId(agentId, ref.removePrefix("kept:")), agentId, PHOTO)?.let { store.save(agentId, PHOTO, it.put("freed_at", now)) }
                else dropPhoto(agentId, ref)
            }
            if (fromAgent) recent.addLast(now)
            saved
        }
        changed(agentId)
        return result
    }

    /** A write the owner made by hand: `by` is {"owner": true} or {"camera": true} (or has that type). */
    private fun ownerWrite(by: JSONObject) = by.optBoolean("owner") || by.optBoolean("camera") || by.optString("type") in setOf("owner", "camera")
    /** A write an agent made through the API (`by` {"type": "agent"}), not the applet's own flow or the owner. */
    private fun agentWrite(by: JSONObject) = by.optString("type") == "agent"

    /** Called after a row is added to a table shared with the installing agent (AppletInstances sends applet.row_added). */
    @Volatile var rowAdded: ((agentId: String, table: String, rowId: String) -> Unit)? = null

    /**
     * Rows of [tableId], at most [limit]. Newest first: [since] is the last row id of the previous page (rows
     * older than it). Oldest first: rows written after [since].
     */
    fun rows(agentId: String, tableId: String, limit: Int, since: String? = null, newestFirst: Boolean = true): List<JSONObject> {
        val p = prefix(agentId, tableId); val n = limit.coerceIn(1, 1000)
        val list = if (newestFirst) select(agentId, ROW, p, if (since.isNullOrEmpty()) "" else " AND id<?", listOfNotNull(since?.takeIf { it.isNotEmpty() }?.let { p + it }), "DESC", n)
        else store.keyed(agentId, ROW, p, since?.takeIf { it.isNotEmpty() }?.let { p + it } ?: "", n)
        return list.map(::view)
    }

    /** What [scan] found: how many rows match, the newest [Scan.rows] of them, and the total of a number column. */
    class Scan(val count: Int, val rows: List<JSONObject>, val sum: Double)

    /**
     * Rows of [tableId] written at or after [sinceMs] (all when null) whose [where] column {column, is} matches, newest
     * first: their count, the newest [keep] (as readers see them) and the total of [sumColumn] (numbers only). For flow
     * conditions and the lookup step (docs/applet-reach.md D1, D4).
     */
    fun scan(agentId: String, tableId: String, sinceMs: Long?, where: JSONObject?, keep: Int = 0, sumColumn: String? = null): Scan {
        val p = prefix(agentId, tableId)
        var n = 0; var total = 0.0; val kept = mutableListOf<JSONObject>(); var before: String? = null
        while (true) {
            val extra = (if (sinceMs != null) " AND created>=?" else "") + (if (before != null) " AND id<?" else "")
            val page = select(agentId, ROW, p, extra, listOfNotNull(sinceMs?.toString(), before), "DESC", 500)
            for (r in page) {
                val v = r.optJSONObject("values") ?: JSONObject()
                if (where != null && !cc.bridgit.app.platform.FlowConditions.same(v.opt(where.optString("column"))?.takeIf { it != JSONObject.NULL }, where.opt("is"))) continue
                n++
                if (kept.size < keep) kept += view(r)
                if (sumColumn != null) (v.opt(sumColumn) as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { total += it }
            }
            if (page.size < 500) break
            before = page.last().getString("id")
        }
        return Scan(n, kept, total)
    }

    fun get(agentId: String, tableId: String, rowId: String): JSONObject? = store.find(prefix(agentId, tableId) + rowId, agentId, ROW)?.let(::view)
    fun count(agentId: String, tableId: String): Int = countWhere(agentId, ROW, prefix(agentId, tableId)).first
    /** Space the rows of [tableId] (or of every table when null) take, in characters. */
    fun bytes(agentId: String, tableId: String? = null): Long = countWhere(agentId, ROW, if (tableId == null) "row:$agentId:" else prefix(agentId, tableId)).second

    /** How many rows of [tableId] have a value in [columnId] (for [AppletTables.changes]). */
    fun countWith(agentId: String, tableId: String, columnId: String): Int = synchronized(store) {
        var n = 0; var after = ""
        while (true) {
            val page = store.keyed(agentId, ROW, prefix(agentId, tableId), after, 1000)
            n += page.count { it.optJSONObject("values")?.let { v -> v.has(columnId) && !v.isNull(columnId) } == true }
            if (page.size < 1000) break
            after = page.last().getString("id")
        }
        n
    }

    /** Whether [agentId] keeps any row or kept photo (Remove's left_behind). */
    fun hasAny(agentId: String): Boolean = countWhere(agentId, ROW, "row:$agentId:").first > 0 || countWhere(agentId, PHOTO, photoId(agentId, "")).first > 0 ||
        (dir(agentId).listFiles()?.isNotEmpty() == true)

    /** Deletes one row and the kept photos only it used. Returns whether it existed. */
    fun delete(agentId: String, tableId: String, rowId: String): Boolean {
        val existed = synchronized(store) {
            val r = store.find(prefix(agentId, tableId) + rowId, agentId, ROW) ?: return@synchronized false
            deleteRows(agentId, listOf(r)); true
        }
        if (existed) changed(agentId)
        return existed
    }

    /** Empties [tableId] (and the kept photos only its rows used). Returns how many rows went. */
    fun deleteAll(agentId: String, tableId: String): Int {
        var n = 0
        synchronized(store) {
            while (true) {
                val page = store.keyed(agentId, ROW, prefix(agentId, tableId), "", 1000)
                if (page.isEmpty()) break
                deleteRows(agentId, page); n += page.size
            }
        }
        if (n > 0) changed(agentId)
        return n
    }

    /** An approved update removed (or retyped) [columnId]: its values go from every row, with photos only they used. */
    fun dropColumn(agentId: String, tableId: String, columnId: String): Int {
        var n = 0
        synchronized(store) {
            val gone = HashSet<String>(); var after = ""
            while (true) {
                val page = store.keyed(agentId, ROW, prefix(agentId, tableId), after, 1000)
                for (r in page) {
                    val v = r.optJSONObject("values") ?: continue
                    if (!v.has(columnId)) continue
                    v.remove(columnId)
                    val still = v.keys().asSequence().mapNotNull { v.opt(it) as? String }.toSet()
                    val before = refs(r)
                    gone += before.filter { it !in still }
                    r.put("photos", JSONArray(before.filter { it in still }))
                    store.save(agentId, ROW, r); n++
                }
                if (page.size < 1000) break
                after = page.last().getString("id")
            }
            gone.forEach { if (!used(agentId, it)) dropPhoto(agentId, it) }
        }
        if (n > 0) changed(agentId)
        return n
    }

    /** Rows of [tableId] written before [beforeMs] (what a shorter keep_days would delete). */
    fun countOlder(agentId: String, tableId: String, beforeMs: Long): Int =
        select(agentId, ROW, prefix(agentId, tableId), " AND created<?", listOf(beforeMs.toString()), "ASC", AppletTables.MAX_ROWS + 1).size

    /** An approved update removed [tableId]. */
    fun dropTable(agentId: String, tableId: String): Int = deleteAll(agentId, tableId)

    /**
     * The daily clean-up: rows older than their table's keep_days, rows past max_rows (oldest first), and kept
     * photos no row has used for 7 days. Also drops photo records whose file is gone and files with no record.
     */
    fun clean(agentId: String, tables: List<AppletTables.Table>, now: Long = System.currentTimeMillis()) {
        var did = false
        synchronized(store) {
            for (t in tables) {
                val p = prefix(agentId, t.id)
                while (true) {
                    val old = select(agentId, ROW, p, " AND created<?", listOf((now - t.keepDays * 86_400_000L).toString()), "ASC", 1000)
                    if (old.isEmpty()) break
                    deleteRows(agentId, old); did = true
                }
                val count = count(agentId, t.id)
                if (count > t.maxRows) { deleteRows(agentId, select(agentId, ROW, p, "", emptyList(), "ASC", count - t.maxRows)); did = true }
            }
            val known = HashSet<String>()
            val inUse by lazy { usedRefs(agentId) }
            for (ph in select(agentId, PHOTO, photoId(agentId, ""), "", emptyList(), "ASC", 10_000)) {
                val ref = ph.optString("ref"); val key = ref.removePrefix("kept:")
                if (!file(agentId, key).exists()) { store.delete(ph.getString("id"), agentId); did = true; continue }
                if (maxOf(ph.optLong("taken_at"), ph.optLong("freed_at")) < now - AppletTables.ORPHAN_PHOTO_MS && ref !in inUse) { dropPhoto(agentId, ref); did = true; continue }
                known += "$key.jpg"
            }
            dir(agentId).listFiles()?.forEach { f -> if (f.name !in known && f.lastModified() < now - 3_600_000L) { f.delete(); did = true } }
        }
        if (did) changed(agentId)
    }

    // ---- kept photos --------------------------------------------------------------------------------------

    /**
     * Keeps [bytes] (a JPEG from flow step [stepId]) and returns its ref `kept:<id>`. Over 1000 photos or 500 MB,
     * the oldest photos no row uses go first; if that isn't enough, throws [AppletTables.Refused] `storage_full`.
     */
    fun keepPhoto(agentId: String, stepId: String, bytes: ByteArray): String {
        val ref = synchronized(store) {
            if (bytes.size > AppletTables.MAX_KEPT_PHOTO_BYTES) refused("storage_full", "This photo is larger than the ${AppletTables.MAX_KEPT_PHOTO_BYTES / (1024 * 1024)} MB kept photos may use.")
            val all = select(agentId, PHOTO, photoId(agentId, ""), "", emptyList(), "ASC", 10_000)
            var count = all.size; var total = all.sumOf { it.optLong("bytes") }
            fun over() = count + 1 > AppletTables.MAX_KEPT_PHOTOS || total + bytes.size > AppletTables.MAX_KEPT_PHOTO_BYTES
            if (over()) {
                // One pass over the rows for the refs in use, then the oldest unused photos make room. Which ones
                // go is worked out first, so a photo that can't fit deletes nothing.
                val inUse = usedRefs(agentId)
                val drop = ArrayList<JSONObject>()
                for (p in all) {
                    if (!over()) break
                    if (p.optString("ref") in inUse) continue
                    drop += p; count--; total -= p.optLong("bytes")
                }
                if (over()) refused("storage_full", "This applet keeps ${AppletTables.MAX_KEPT_PHOTOS} photos or ${AppletTables.MAX_KEPT_PHOTO_BYTES / (1024 * 1024)} MB at most, and its rows use them all; delete some rows first.")
                drop.forEach { dropPhoto(agentId, it.optString("ref")) }
            }
            var key = stamp()
            while (store.find(photoId(agentId, key)) != null || file(agentId, key).exists()) key = stamp()
            val f = file(agentId, key)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "$key.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { tmp.delete(); refused("storage_full", "The photo couldn't be saved on the phone.") }
            store.save(agentId, PHOTO, JSONObject().put("id", photoId(agentId, key)).put("ref", "kept:$key").put("step", stepId)
                .put("taken_at", System.currentTimeMillis()).put("bytes", bytes.size))
            "kept:$key"
        }
        changed(agentId)
        return ref
    }

    /** Kept photos (of flow step [stepId], or all), newest first: {ref, step, taken_at, bytes}. */
    fun photos(agentId: String, stepId: String? = null, limit: Int = 24): List<JSONObject> =
        select(agentId, PHOTO, photoId(agentId, ""), "", emptyList(), "DESC", 10_000).asSequence()
            .filter { stepId == null || it.optString("step") == stepId }.take(limit.coerceIn(1, 1000))
            .map { JSONObject().put("ref", it.optString("ref")).put("step", it.optString("step")).put("taken_at", it.optLong("taken_at")).put("bytes", it.optLong("bytes")) }
            .toList()

    /** The JPEG file of kept photo [ref], or null if it isn't one of [agentId]'s. */
    fun photoFile(agentId: String, ref: String): File? {
        if (!AppletTables.PHOTO_REF.matches(ref)) return null
        val key = ref.removePrefix("kept:")
        if (store.find(photoId(agentId, key), agentId, PHOTO) == null) return null
        return file(agentId, key).takeIf { it.isFile }
    }

    /** The applet is removed: every row, kept photo and photo file of [agentId]. Returns whether all of it went. */
    fun dropAll(agentId: String): Boolean {
        val ok = synchronized(store) {
            store.writableDatabase.delete("records", "agent=? AND kind IN (?,?)", arrayOf(agentId, ROW, PHOTO))
            val d = dir(agentId)
            !d.exists() || d.deleteRecursively()
        }
        changed(agentId)
        return ok
    }

    companion object {
        const val ROW = "applet_row"
        const val PHOTO = "applet_photo"
        /** Rows one [addMany] writes at most (a flow's record with for_each). */
        const val MAX_MANY = 50
        @Volatile private var instance: AppletTableStore? = null
        fun of(app: BridgeApp) = instance ?: synchronized(this) { instance ?: AppletTableStore(app).also { instance = it } }
    }
}
