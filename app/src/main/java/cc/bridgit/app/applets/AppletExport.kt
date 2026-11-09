package cc.bridgit.app.applets

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The `share` step (docs/applet-dev-experience.md O1, docs/applet-wave2.md item 4): a table of an installed applet
 * as a CSV file, built on the phone and handed to Android's share sheet. The owner's tap in the
 * share sheet is the consent, so the step needs no scope, but it needs the owner present: a card button, a flow
 * with a button trigger, or Installed applets › Data › Export.
 *
 * ```
 * {"type": "share", "table": "items", "as": "csv", "title": "Home inventory",
 *  "columns": ["name", {"key": "new_usd", "label": "New", "format": "money"}], "group_by": "category",
 *  "where": {"column": "insurable", "is": true}}
 * ```
 * The pieces below (checking, filtering, grouping and totals, CSV text) are pure so they are unit-tested;
 * [export] reads the rows and [share] opens the share sheet.
 */
object AppletExport {
    const val MAX_ROWS = 10_000
    const val MAX_BYTES = 20L * 1024 * 1024
    const val MAX_TITLE = 120
    const val MAX_COLUMNS = 16
    const val MAX_TOTALS = 8
    /** Files in cacheDir/[DIR] older than this go at the next export. */
    const val KEEP_MS = 24L * 3600 * 1000
    const val DIR = "applet-share"
    /** Extra run time a flow gets for a share step (10,000 rows take a while). */
    const val RUN_SECONDS = 60

    /** Includes legacy fields so immutable installed packages still reopen; build only executes CSV. */
    val FIELDS = setOf("table", "as", "title", "columns", "group_by", "totals", "photos", "where")
    /** Fields new packages may use (published authoring contract): the PDF-only ones are left out. */
    val AUTHORING_FIELDS = FIELDS - setOf("totals", "photos")
    val FORMATS = setOf("text", "number", "money", "yes_no", "date", "time")
    private val COLUMN_FIELDS = setOf("key", "label", "format", "currency")
    private val TESTS = setOf("is", "is_not", "in", "at_least", "below", "exists")
    private val ID = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val CURRENCY = Regex("^[A-Z]{3}$")
    /** Column types shown by default; photo references and unknown types (boxes...) are left out unless listed. */
    private val PLAIN_TYPES = setOf("text", "number", "integer", "boolean", "time", "date", "choice")
    /** Keys every row has besides its columns. */
    private const val SAVED_AT = "at"

    // ---- checking ---------------------------------------------------------------------------------------------

    /**
     * Checks a share step's own fields. [tables] maps table id → column ids when they are known (a card button):
     * then the table and every column named must exist. [flow] allows the flow's common fields. Throws via [bad].
     */
    fun check(step: JSONObject, at: String, tables: Map<String, Set<String>>? = null, flow: Boolean = false, bad: (String) -> Nothing) {
        val common = if (flow) setOf("type", "id", "only_if", "on_error", "retries") else setOf("type")
        step.keys().asSequence().firstOrNull { it !in FIELDS && it !in common }?.let { bad("$at: $it isn't a field of share (${FIELDS.joinToString()}).") }
        val table = step.opt("table") as? String
        if (table == null || !ID.matches(table)) bad("$at: table must be a table id of the applet (in processing.tables).")
        if (step.opt("as") !in setOf("pdf", "csv")) bad("$at: as must be pdf or csv.")
        step.opt("title")?.let { if (it !is String || it.isBlank() || it.length > MAX_TITLE) bad("$at: title must be text of 1-$MAX_TITLE characters.") }
        val known = tables?.let { all -> (all[table] ?: bad("$at: table $table isn't in processing.tables${if (all.isEmpty()) "" else " (${all.keys.joinToString()})"}.")) + SAVED_AT }
        fun column(id: Any?, where: String) {
            if (id !is String || !ID.matches(id)) bad("$where must be a column id of $table.")
            if (known != null && id !in known) bad("$where: $table has no column $id (${known.joinToString()}).")
        }
        step.opt("columns")?.let { c ->
            val list = c as? JSONArray ?: bad("$at: columns must be a list of column ids or {key, label, format}.")
            if (list.length() !in 1..MAX_COLUMNS) bad("$at: columns must hold 1-$MAX_COLUMNS columns.")
            for (i in 0 until list.length()) when (val one = list.opt(i)) {
                is String -> column(one, "$at.columns[$i]")
                is JSONObject -> {
                    one.keys().asSequence().firstOrNull { it !in COLUMN_FIELDS }?.let { bad("$at.columns[$i]: $it isn't a column field (${COLUMN_FIELDS.joinToString()}).") }
                    column(one.opt("key"), "$at.columns[$i].key")
                    one.opt("label")?.let { if (it !is String || it.isBlank() || it.length > AppletTables.MAX_LABEL) bad("$at.columns[$i].label must be 1-${AppletTables.MAX_LABEL} characters.") }
                    one.opt("format")?.let { if (it !in FORMATS) bad("$at.columns[$i].format must be one of ${FORMATS.joinToString()}.") }
                    one.opt("currency")?.let { if (it !is String || !CURRENCY.matches(it)) bad("$at.columns[$i].currency must be a 3-letter code, like USD.") }
                }
                else -> bad("$at.columns[$i] must be a column id or {key, label, format}.")
            }
        }
        step.opt("group_by")?.let { column(it, "$at.group_by") }
        step.opt("totals")?.let { t ->
            val list = t as? JSONArray ?: bad("$at: totals must be a list of number column ids.")
            if (list.length() !in 1..MAX_TOTALS) bad("$at: totals must hold 1-$MAX_TOTALS columns.")
            for (i in 0 until list.length()) column(list.opt(i), "$at.totals[$i]")
        }
        step.opt("photos")?.let { if (it != "thumbs" && it != "none") bad("$at: photos must be thumbs or none.") }
        step.opt("where")?.let { w ->
            val o = w as? JSONObject ?: bad("$at: where must be {\"column\": \"<id>\", \"is\": …}.")
            column(o.opt("column"), "$at.where.column")
            val tests = o.keys().asSequence().filter { it != "column" }.toList()
            if (tests.size != 1 || tests[0] !in TESTS) bad("$at: where needs exactly one test: ${TESTS.joinToString()}.")
            val v = o.opt(tests[0])
            when (tests[0]) {
                "is", "is_not" -> if (v !is String && v !is Number && v !is Boolean) bad("$at: where.${tests[0]} must be text, a number or true/false.")
                "in" -> if (v !is JSONArray || v.length() !in 1..20) bad("$at: where.in must be a list of 1-20 values.")
                "at_least", "below" -> if (v !is Number) bad("$at: where.${tests[0]} must be a number.")
                "exists" -> if (v !is Boolean) bad("$at: where.exists must be true or false.")
            }
        }
    }

    /** Historical PDF declarations remain readable, but the host never generates PDF files. */
    fun words(step: JSONObject): String =
        if (step.optString("as") != "csv") "legacy PDF export unavailable (choose CSV or structured table data)"
        else "share \"${step.optString("title").ifBlank { step.optString("table") }.take(60)}\" as a CSV file (you choose where it goes)"


    // ---- columns, filter, groups and totals (pure) -------------------------------------------------------------

    /** One column of the export: [key] a column id (or `at`), its heading and how its values are written. */
    data class Col(val key: String, val label: String, val format: String, val currency: String = "USD", val type: String = "text") {
        val numeric get() = format == "money" || format == "number"
    }

    private fun defaultFormat(c: AppletTables.Column): String = when (c.type) {
        "number", "integer" -> if (c.id.endsWith("_usd") || c.id == "usd") "money" else "number"
        "boolean" -> "yes_no"
        "time" -> "time"
        "date" -> "date"
        else -> "text"
    }

    private fun col(t: AppletTables.Table, key: String): Col =
        if (key == SAVED_AT && key !in t.columns) Col(SAVED_AT, "Saved", "time", type = "saved")
        else t.columns[key]?.let { Col(it.id, it.label, defaultFormat(it), type = it.type) } ?: Col(key, key, "text")

    /**
     * The columns an export shows: listed columns or plain columns of [t]. Photo columns are omitted by default.
     */
    fun columns(t: AppletTables.Table, step: JSONObject): List<Col> {
        val list = step.optJSONArray("columns") ?: return t.columns.values.filter { it.type in PLAIN_TYPES }.map { col(t, it.id) }
        return (0 until list.length()).map { i ->
            when (val one = list.opt(i)) {
                is JSONObject -> col(t, one.optString("key")).let { c ->
                    c.copy(label = one.optString("label").ifBlank { c.label }, format = one.optString("format").ifBlank { c.format },
                        currency = one.optString("currency").ifBlank { c.currency })
                }
                else -> col(t, one.toString())
            }
        }
    }

    /** A number in a value: a number, or text like "$1,200.50". */
    fun numberOf(v: Any?): Double? = when (v) {
        is Boolean -> null
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is String -> AppletTables.numberText(v)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    private fun same(a: Any?, b: Any?): Boolean = when {
        a == null || a == JSONObject.NULL || b == null -> false
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is Boolean || b is Boolean -> a.toString().equals(b.toString(), ignoreCase = true)
        else -> a.toString().trim().equals(b.toString().trim(), ignoreCase = true)
    }

    /** Whether [row] passes a share step's `where` (none: every row). */
    fun keep(row: JSONObject, where: JSONObject?): Boolean {
        if (where == null) return true
        val v = row.opt(where.optString("column"))?.takeIf { it != JSONObject.NULL }
        return when {
            where.has("exists") -> (v != null) == where.optBoolean("exists")
            where.has("is") -> same(v, where.opt("is"))
            where.has("is_not") -> v != null && !same(v, where.opt("is_not"))
            where.has("in") -> where.optJSONArray("in")?.let { a -> (0 until a.length()).any { same(v, a.opt(it)) } } == true
            where.has("at_least") -> numberOf(v)?.let { it >= where.optDouble("at_least") } == true
            where.has("below") -> numberOf(v)?.let { it < where.optDouble("below") } == true
            else -> true
        }
    }

    class Group(val name: String, val rows: List<JSONObject>, val sums: Map<String, Double>)
    class Grouped(val groups: List<Group>, val sums: Map<String, Double>, val rows: Int)

    const val OTHER = "Other"

    /**
     * [rows] grouped by the text of [groupBy] (no column: one group named ""), groups sorted by name with "Other"
     * (empty values) last, rows keeping their order. [totals] are summed per group and overall; a value that isn't a
     * number counts as nothing.
     */
    fun group(rows: List<JSONObject>, groupBy: Col?, totals: List<String>): Grouped {
        fun sums(list: List<JSONObject>) = totals.associateWith { k -> list.sumOf { numberOf(it.opt(k)) ?: 0.0 } }
        val groups = if (groupBy == null) listOf(Group("", rows, sums(rows))) else {
            val by = LinkedHashMap<String, MutableList<JSONObject>>()
            for (r in rows) by.getOrPut(text(groupBy, r.opt(groupBy.key), csv = false).ifBlank { OTHER }) { ArrayList() } += r
            by.entries.sortedWith(compareBy<Map.Entry<String, MutableList<JSONObject>>> { it.key == OTHER }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
                .map { Group(it.key, it.value, sums(it.value)) }
        }
        return Grouped(groups, sums(rows), rows.size)
    }

    // ---- values as text (pure) ----------------------------------------------------------------------------------

    private fun symbols() = DecimalFormatSymbols(Locale.US)

    /** "$1,234.00"; another currency shows its sign (€, £) or its code. */
    fun money(d: Double, currency: String = "USD"): String {
        val r = Math.round(d * 100) / 100.0
        val sign = when (currency) { "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; "JPY" -> "¥"; "INR" -> "₹"; else -> "$currency " }
        return (if (r < 0) "-" else "") + sign + DecimalFormat("#,##0.00", symbols()).format(Math.abs(r))
    }

    private fun grouped(d: Double): String = DecimalFormat("#,##0.##", symbols()).format(d)
    private fun plain(d: Double, decimals: Boolean): String =
        if (decimals) DecimalFormat("0.00", symbols()).format(d) else if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else DecimalFormat("0.##########", symbols()).format(d)

    private fun pretty(v: Any, zone: ZoneId): String {
        val s = v.toString().trim()
        val dt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.US)
        return runCatching { OffsetDateTime.parse(s).atZoneSameInstant(zone).format(dt) }.getOrNull()
            ?: runCatching { Instant.parse(s).atZone(zone).format(dt) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s).format(dt) }.getOrNull()
            ?: runCatching { LocalTime.parse(s).format(DateTimeFormatter.ofPattern("HH:mm", Locale.US)) }.getOrNull()
            ?: s
    }

    /** A value as one cell: for a CSV ([csv]) machine-friendly (1234.50, ISO times), for a native display for reading ($1,234.50). Empty: "". */
    fun text(c: Col, v: Any?, csv: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        if (v == null || v == JSONObject.NULL) return ""
        if (v is JSONObject || v is JSONArray) return if (csv) v.toString() else ""
        return when (c.format) {
            "money" -> numberOf(v)?.let { if (csv) plain(it, true) else money(it, c.currency) } ?: v.toString()
            "number" -> numberOf(v)?.let { if (csv) plain(it, false) else grouped(it) } ?: v.toString()
            "yes_no" -> when (v) {
                is Boolean -> if (v) "Yes" else "No"
                is Number -> if (v.toDouble() != 0.0) "Yes" else "No"
                else -> when (v.toString().trim().lowercase()) { "true", "yes" -> "Yes"; "false", "no" -> "No"; else -> v.toString() }
            }
            "time" -> if (c.type == "saved" || v is Number) {
                val ms = (v as? Number)?.toLong() ?: return v.toString()
                val at = Instant.ofEpochMilli(ms).atZone(zone)
                if (csv) at.toLocalDateTime().withNano(0).toString() else at.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.US))
            } else if (csv) v.toString() else pretty(v, zone)
            "date" -> if (csv) v.toString() else runCatching { LocalDate.parse(v.toString().trim()).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)) }.getOrDefault(v.toString())
            else -> when (v) {
                is Boolean -> if (v) "Yes" else "No"
                is Number -> numberOf(v)?.let { if (csv) plain(it, false) else grouped(it) } ?: v.toString()
                else -> v.toString()
            }
        }
    }

    // ---- CSV (pure) -------------------------------------------------------------------------------------------

    private fun quote(s: String): String = if (s.any { it == '"' || it == ',' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /**
     * Text a spreadsheet would run as a formula (=, +, -, @ first) gets a leading apostrophe; numbers are left alone.
     */
    private fun safe(c: Col, s: String): String =
        if (!c.numeric && s.isNotEmpty() && s[0] in "=+-@\t\r" && numberOf(s) == null) "'$s" else s

    /** RFC 4180: a header row of labels, one line per row, CRLF, quoted where needed; UTF-8 with a byte order mark. */
    fun csv(cols: List<Col>, rows: List<JSONObject>, zone: ZoneId = ZoneId.systemDefault()): ByteArray {
        val sb = StringBuilder("\uFEFF")
        sb.append(cols.joinToString(",") { quote(it.label) }).append("\r\n")
        for (r in rows) sb.append(cols.joinToString(",") { c -> quote(safe(c, text(c, r.opt(c.key), csv = true, zone = zone))) }).append("\r\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // ---- building and sharing ---------------------------------------------------------------------------------

    /** A built export: the file in its own folder of cacheDir/applet-share, its type, and what is in it. */
    class Built(val file: File, val mime: String, val title: String, val rows: Int, val pages: Int, val photos: Int) {
        fun json(): JSONObject = JSONObject().put("file", file.name).put("as", "csv").put("rows", rows).put("pages", pages)
            .put("photos", photos).put("bytes", file.length())
    }

    private fun refuse(message: String): Nothing = throw ApiError(413, "export_too_large", message)

    private fun fileName(title: String, ext: String, now: Long, zone: ZoneId): String {
        val base = title.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(60).ifEmpty { "Bridgit-export" }
        return "$base-${Instant.ofEpochMilli(now).atZone(zone).toLocalDate()}.$ext"
    }

    /** Deletes exports older than a day from [dir]: each export's own folder (and files from before folders). */
    fun clean(dir: File, now: Long = System.currentTimeMillis()) {
        dir.listFiles()?.forEach { f -> if (f.lastModified() < now - KEEP_MS) f.deleteRecursively() }
    }

    /** Build a bounded CSV without capturing photos, running models or changing rows. */
    fun build(dir: File, t: AppletTables.Table, step: JSONObject, all: List<JSONObject>, now: Long = System.currentTimeMillis(),
              zone: ZoneId = ZoneId.systemDefault()): Built {
        if (step.optString("as") != "csv") throw ApiError(422, "invalid_export", "PDF generation is unavailable. Choose CSV or read structured table data.")
        check(step, "share", flow = true) { throw ApiError(422, "invalid_export", it) }
        if (all.size > MAX_ROWS) refuse("${t.label} has ${all.size} rows; an export holds at most $MAX_ROWS.")
        val rows = all.filter { keep(it, step.optJSONObject("where")) }
        val title = step.optString("title").ifBlank { t.label }.take(MAX_TITLE)
        val cols = columns(t, step).ifEmpty { listOf(col(t, SAVED_AT)) }
        val groupBy = step.optString("group_by").takeIf { it.isNotEmpty() }?.let { col(t, it) }
        val ordered = if (groupBy == null) rows else group(rows, groupBy, emptyList()).groups.flatMap { it.rows }
        val bytes = csv(cols, ordered, zone)
        if (bytes.size > MAX_BYTES) refuse("This CSV exceeds ${MAX_BYTES / 1048576} MB. Read paginated rows through the data API.")
        dir.mkdirs(); clean(dir, now)
        var own: File
        do { own = File(dir, java.util.UUID.randomUUID().toString().take(12)) } while (own.exists())
        own.mkdirs()
        val file = File(own, fileName(title, "csv", now, zone))
        file.writeBytes(bytes)
        return Built(file, "text/csv", title, rows.size, 1, 0)
    }

    /** Every row of [tableId] oldest first, refusing past [MAX_ROWS]. */
    fun readRows(store: AppletTableStore, agentId: String, tableId: String): List<JSONObject> {
        val out = ArrayList<JSONObject>(); var since: String? = null
        while (true) {
            val page = store.rows(agentId, tableId, 1000, since, newestFirst = false)
            out += page
            if (out.size > MAX_ROWS) refuse("This table has more than $MAX_ROWS rows; an export holds at most $MAX_ROWS.")
            if (page.size < 1000) break
            since = page.last().optString("id")
        }
        return out
    }

    /** The applet's table [tableId] or a plain refusal. */
    fun table(app: BridgeApp, agentId: String, tableId: String): AppletTables.Table {
        val inst = app.applets.forAgent(agentId) ?: throw ApiError(404, "no_table", "Only an installed applet can share its tables.")
        val tables = try { AppletTables.parse(inst.optJSONObject("definition")?.optJSONObject("processing") ?: JSONObject()) }
            catch (e: AppletPackage.Bad) { throw ApiError(404, "no_table", "The applet's tables can't be read: ${e.message}") }
        return tables.firstOrNull { it.id == tableId } ?: throw ApiError(404, "no_table", "The applet has no table $tableId.")
    }

    /** Builds a share step's file from [agentId]'s applet table, on the calling (background) thread. */
    fun export(app: BridgeApp, agentId: String, step: JSONObject, table: AppletTables.Table = table(app, agentId, step.optString("table"))): Built {
        val store = AppletTableStore.of(app)
        return build(File(app.cacheDir, DIR), table, step, readRows(store, agentId, table.id))
    }

    /** The share sheet for [built]: ACTION_SEND with a read grant on the file only, in a chooser. */
    fun intent(context: Context, built: Built): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", built.file)
        val send = Intent(Intent.ACTION_SEND).setType(built.mime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, built.title)
            .putExtra(Intent.EXTRA_TITLE, built.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(built.title, uri)
        return Intent.createChooser(send, "Share ${built.title}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /** Opens the share sheet; Android may refuse when Bridgit isn't on screen. */
    fun share(context: Context, built: Built) {
        try { context.startActivity(intent(context, built)) }
        catch (e: Exception) { throw ApiError(409, "share_unavailable", "The share sheet couldn't open: open Bridgit and try again.") }
    }
}
