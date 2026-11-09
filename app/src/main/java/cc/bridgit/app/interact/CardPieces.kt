package cc.bridgit.app.interact

import org.json.JSONArray
import org.json.JSONObject

/**
 * Card pieces (docs/applet-cards.md, phase 2): a card the phone draws itself from a list of pieces (header,
 * metrics, table, timeline, buttons...) and named data sources, so an agent writes no HTML, CSS or script.
 * Pure: no Android types. This holds the rules (what a card may say), the reference and format helpers the
 * renderer uses, the automatic card state and the plain words for the install review. The renderer is
 * [cc.bridgit.app.ui.interactions.PiecesCardView]; sources are read by [cc.bridgit.app.ui.interactions.PieceSources].
 *
 * References are whole values, like `$input`, `$watch` and `$step`: `{"$source":"name.path"}` is replaced by the
 * value and `{"$draft":"id"}` by the text in a draft box. Text fields take a string or a list of strings and
 * references, joined and drawn as plain text, never as HTML or a link.
 */
object CardPieces {
    const val MAX_PIECES = 24
    const val MAX_SOURCES = 8
    const val MAX_CARD_BYTES = 16 * 1024
    const val TEXT_MAX = 1024
    const val DRAFT_MAX = 4096
    const val MIN_FRESH_S = 30
    const val MAX_FRESH_S = 86_400
    const val MIN_EVERY_S = 60
    const val MAX_EVERY_S = 3600

    class Bad(message: String) : Exception(message)
    private fun bad(m: String): Nothing = throw Bad(m)

    private val NAME = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val SEGMENT = Regex("^[A-Za-z0-9_\$-]{1,40}$")
    /** A value that may fill one path segment of a button's call. */
    val PATH_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val TESTS = setOf("is", "is_not", "in", "at_least", "below", "exists")
    val FORMATS = setOf("text", "number", "minutes", "duration_s", "time", "ago", "date", "percent", "celsius", "money", "yes_no", "words")
    val STATES = listOf("loading", "empty", "ready", "stale", "paused", "error")
    val TONES = setOf("info", "ok", "warn", "bad")
    val STYLES = setOf("primary", "plain", "danger")
    private val CARD_FIELDS = setOf("title", "pieces", "sources", "fresh_s", "states")

    /** Every piece type and the fields it takes besides `type` and `only_if`. */
    val PIECES: Map<String, Set<String>> = linkedMapOf(
        "header" to setOf("title", "text", "eyebrow"),
        "status" to setOf("text", "tone"),
        "text" to setOf("text"),
        "note" to setOf("text"),
        "metrics" to setOf("items"),
        "gallery" to setOf("photos", "caption", "caption_format", "limit", "empty", "photo", "words", "caption_count"),
        "video" to setOf("video", "poster", "caption", "empty"),
        "table" to setOf("rows", "columns", "limit", "empty", "owner_delete", "group_by", "totals", "marks", "photo", "owner_edit"),
        "timeline" to setOf("items", "time", "label", "label_format", "label_words", "detail", "time_format", "limit", "empty"),
        "people" to setOf("items", "name", "status", "time", "empty"),
        "devices" to setOf("items", "name", "kind", "availability", "checked_at", "empty"),
        "draft" to setOf("id", "label", "value", "max"),
        "buttons" to setOf("items"),
    )

    /**
     * What a card may name. [watch] says whether a source's `watch` value names a watch it may read (a `$watch`
     * ref in a package, an own watch id from an agent). [tables] maps table id → its column ids. [steps] maps a
     * workflow step id → its step (null: no workflow). [buttonWorkflow] is whether the workflow's trigger is a
     * button. [callCap] returns the capability of "METHOD /v1/path" or throws [Bad] when no card may call it.
     */
    class Scope(
        val watch: (Any?) -> Boolean,
        val tables: Map<String, Set<String>> = emptyMap(),
        val steps: Map<String, JSONObject>? = null,
        val buttonWorkflow: Boolean = false,
        val callCap: (String, String) -> String? = { _, _ -> null },
        /** A package card: `$watch` and `$input` may fill a button's path segments. */
        val packaged: Boolean = false,
        /** Each on-phone table's column types (table id → column id → type), for the wave 2 table, gallery and camera checks. */
        val columnTypes: Map<String, Map<String, String>> = emptyMap(),
        /** The package's flows a button may start (`do.flow`): id → (trigger type, `with` names). Null: none. */
        val flows: Map<String, Pair<String, Set<String>>>? = null,
    )

    /** What a checked card uses: the capabilities its calls need and the tables its buttons write. */
    class Checked(val capabilities: Set<String>, val records: Set<String>, val runsWorkflow: Boolean, val calls: List<String>,
                  /** A camera button takes photos (docs/applet-wave2.md B), so the applet needs `camera`. */
                  val camera: Boolean = false)

    // ---- checking --------------------------------------------------------------------------------------------

    /** Whether [card] (a `ui.card` object or a `kind: pieces` body) is a pieces card. */
    fun isPieces(card: JSONObject?): Boolean = card?.has("pieces") == true

    fun check(card: JSONObject, scope: Scope): Checked {
        if (card.toString().toByteArray().size > MAX_CARD_BYTES) bad("The card is larger than ${MAX_CARD_BYTES / 1024} KB.")
        card.keys().asSequence().firstOrNull { it !in CARD_FIELDS && it != "kind" && it != "expires_in_seconds" && it != "no_takeover" && it != "priority" }
            ?.let { bad("$it isn't a field of a pieces card (${CARD_FIELDS.joinToString()}).") }
        val caps = linkedSetOf<String>(); val records = linkedSetOf<String>(); val calls = mutableListOf<String>()
        var workflow = false; var camera = false
        // Sources first: pieces and buttons name them.
        val sources = card.opt("sources") ?: JSONObject()
        if (sources !is JSONObject) bad("sources must be an object of named sources.")
        if (sources.length() > MAX_SOURCES) bad("Use at most $MAX_SOURCES sources.")
        val pieces = card.opt("pieces") as? JSONArray ?: bad("pieces must be a list of 1-$MAX_PIECES pieces.")
        if (pieces.length() !in 1..MAX_PIECES) bad("Use 1-$MAX_PIECES pieces.")
        // Button replies (`into`) and drafts are names too; collect them before checking references.
        val replies = linkedSetOf<String>(); val drafts = linkedSetOf<String>(); val batches = linkedSetOf<String>()
        for (i in 0 until pieces.length()) {
            val p = pieces.opt(i) as? JSONObject ?: bad("pieces[$i] must be an object.")
            when (p.optString("type")) {
                "draft" -> (p.opt("id") as? String)?.takeIf { NAME.matches(it) }?.let { if (!drafts.add(it)) bad("pieces[$i]: draft id $it is used twice.") }
                    ?: bad("pieces[$i] (draft): id must be a short lowercase name.")
                "buttons" -> p.optJSONArray("items")?.let { items ->
                    // A batch button (do.for_each) is named by its id or label in a {"batch": …} source.
                    for (j in 0 until items.length()) items.optJSONObject(j)?.takeIf { it.optJSONObject("do")?.has("for_each") == true }?.let { b ->
                        listOf(b.opt("id"), b.opt("label")).forEach { (it as? String)?.let { n -> batches += n } }
                    }
                    for (j in 0 until items.length()) (items.optJSONObject(j)?.opt("into") as? String)?.let { n ->
                        if (!NAME.matches(n)) bad("pieces[$i].items[$j].into must be a short lowercase name.")
                        if (!replies.add(n) || (sources.has(n) && sources.optJSONObject(n)?.optString("reply") != n)) bad("pieces[$i].items[$j].into: $n names another source already.")
                    }
                }
            }
        }
        for (name in sources.keys()) {
            if (!NAME.matches(name)) bad("sources: $name must be a short lowercase name.")
            val s = sources.opt(name) as? JSONObject ?: bad("sources.$name must be an object.")
            source(name, s, scope, replies, batches)?.let { (method, path) -> calls += "$method $path"; scope.callCap(method, path)?.let { caps += it } }
        }
        val names = sources.keys().asSequence().toSet() + replies
        card.opt("fresh_s")?.let { f -> if (f !is Int || f !in MIN_FRESH_S..MAX_FRESH_S) bad("fresh_s must be a whole number from $MIN_FRESH_S to $MAX_FRESH_S.") }
        card.opt("states")?.let { st ->
            val o = st as? JSONObject ?: bad("states must be an object like {\"empty\": \"Nothing yet.\"}.")
            for (k in o.keys()) { if (k !in STATES || k == "ready") bad("states.$k isn't a card state (${STATES.filter { it != "ready" }.joinToString()})."); text(o.opt(k), names, drafts, "states.$k", true) }
        }
        val title = card.opt("title")
        if (title != null && (title !is String || title.isBlank() || title.length > 80)) bad("title must be 1-80 characters.")
        for (i in 0 until pieces.length()) {
            val p = pieces.getJSONObject(i)
            val type = p.opt("type") as? String ?: bad("pieces[$i] needs a type: ${PIECES.keys.joinToString()}.")
            val fields = PIECES[type] ?: bad("pieces[$i]: unknown piece $type. Pieces: ${PIECES.keys.joinToString()}.")
            val at = "pieces[$i] ($type)"
            p.keys().asSequence().firstOrNull { it != "type" && it != "only_if" && it !in fields }?.let { bad("$at: $it isn't a field of $type (${fields.joinToString()}).") }
            p.opt("only_if")?.let { condition(it, names, at) }
            fun txt(k: String, required: Boolean = false) = text(p.opt(k), names, drafts, "$at.$k", required)
            fun list(k: String, required: Boolean = true) = value(p.opt(k), names, drafts, "$at.$k", required)
            fun key(k: String, required: Boolean = false) = p.opt(k)?.let { if (it !is String || !SEGMENT.matches(it)) bad("$at.$k must name a field of each item, like \"at\".") } ?: if (required) bad("$at.$k is required.") else Unit
            fun limit(max: Int) = p.opt("limit")?.let { if (it !is Int || it !in 1..max) bad("$at.limit must be 1-$max.") }
            fun fmt(k: String) = p.opt(k)?.let { if (it !in FORMATS) bad("$at.$k must be one of ${FORMATS.joinToString()}.") }
            fun words(k: String) = p.opt(k)?.let { w -> val o = w as? JSONObject ?: bad("$at.$k must be an object of value → words."); if (o.length() !in 1..32 || o.keys().asSequence().any { (o.opt(it) as? String)?.length !in 1..80 }) bad("$at.$k must map 1-32 values to words of 1-80 characters.") }
            when (type) {
                "header" -> { txt("title"); txt("text"); txt("eyebrow") }
                "status" -> { txt("text", true); p.opt("tone")?.let { if (it !in TONES) bad("$at.tone must be ${TONES.joinToString()}.") } }
                "text", "note" -> txt("text", true)
                "metrics" -> {
                    val items = p.opt("items") as? JSONArray ?: bad("$at.items must be a list of 1-6 tiles.")
                    if (items.length() !in 1..6) bad("$at.items must hold 1-6 tiles.")
                    for (j in 0 until items.length()) {
                        val t = items.opt(j) as? JSONObject ?: bad("$at.items[$j] must be {label, value}.")
                        t.keys().asSequence().firstOrNull { it !in setOf("label", "value", "format", "unit", "empty", "currency", "words") }?.let { bad("$at.items[$j]: $it isn't a tile field (label, value, format, unit, empty, currency, words).") }
                        text(t.opt("label"), names, drafts, "$at.items[$j].label", true)
                        value(t.opt("value"), names, drafts, "$at.items[$j].value", true)
                        t.opt("format")?.let { if (it !in FORMATS) bad("$at.items[$j].format must be one of ${FORMATS.joinToString()}.") }
                        (t.opt("unit") as? String)?.let { if (it.length > 12) bad("$at.items[$j].unit is at most 12 characters.") }
                        text(t.opt("empty"), names, drafts, "$at.items[$j].empty", false)
                        t.opt("currency")?.let { c -> if (c is String) { if (!Regex("^[A-Z]{3}$").matches(c)) bad("$at.items[$j].currency must be a 3-letter code.") } else value(c, names, drafts, "$at.items[$j].currency", true) }
                    }
                }
                "gallery" -> { list("photos"); key("caption"); fmt("caption_format"); limit(24); txt("empty"); TablePieces.checkGallery(p, at, TablePieces.tableOf(p.opt("photos"), sources), scope) }
                "video" -> { list("video"); list("poster", false); txt("caption"); txt("empty") }
                "table" -> {
                    list("rows"); txt("empty")
                    p.opt("owner_delete")?.let { if (it !is Boolean) bad("$at.owner_delete must be true or false.") }
                    val cols = p.opt("columns") as? JSONArray ?: bad("$at.columns must be a list of 1-6 columns {key, label, format}.")
                    if (cols.length() !in 1..6) bad("$at.columns must hold 1-6 columns.")
                    for (j in 0 until cols.length()) {
                        val c = cols.opt(j) as? JSONObject ?: bad("$at.columns[$j] must be {key, label, format}.")
                        c.keys().asSequence().firstOrNull { it !in setOf("key", "label", "format", "words", "currency") }?.let { bad("$at.columns[$j]: $it isn't a column field (key, label, format, words, currency).") }
                        (c.opt("key") as? String)?.takeIf { it == "\$key" || SEGMENT.matches(it) } ?: bad("$at.columns[$j].key must name a field of each row (or \$key for an object's keys).")
                        text(c.opt("label"), names, drafts, "$at.columns[$j].label", true)
                        c.opt("format")?.let { if (it !in FORMATS) bad("$at.columns[$j].format must be one of ${FORMATS.joinToString()}.") }
                    }
                    TablePieces.checkTable(p, at, TablePieces.tableOf(p.opt("rows"), sources), scope)
                }
                "timeline" -> { list("items"); key("time", true); key("label", true); key("detail"); fmt("label_format"); words("label_words"); fmt("time_format"); limit(20); txt("empty") }
                "people" -> { list("items"); key("name", true); key("status"); key("time"); txt("empty") }
                "devices" -> { list("items"); key("name", true); key("kind"); key("availability"); key("checked_at"); txt("empty") }
                "draft" -> {
                    txt("label"); value(p.opt("value"), names, drafts, "$at.value", false)
                    p.opt("max")?.let { if (it !is Int || it !in 1..DRAFT_MAX) bad("$at.max must be 1-$DRAFT_MAX.") }
                }
                "buttons" -> {
                    val items = p.opt("items") as? JSONArray ?: bad("$at.items must be a list of 1-4 buttons.")
                    if (items.length() !in 1..4) bad("$at.items must hold 1-4 buttons.")
                    for (j in 0 until items.length()) {
                        val b = items.opt(j) as? JSONObject ?: bad("$at.items[$j] must be {label, do}.")
                        val r = button(b, names, drafts, scope, "$at.items[$j]")
                        r.call?.let { (method, path) ->
                            calls += "$method $path"
                            scope.callCap(method, path)?.let { caps += it }
                            if (method == "POST" && path == "/v1/ocr" && b.optJSONObject("do")?.optJSONObject("body")?.has("gallery_id") == true) caps += "gallery"
                        }
                        r.record?.let { records += it }
                        if (r.workflow) workflow = true
                        if (r.camera) camera = true
                    }
                }
            }
        }
        return Checked(caps, records, workflow, calls, camera)
    }

    /** Checks one source; returns the (method, path) of a call source. */
    private fun source(name: String, s: JSONObject, scope: Scope, replies: Set<String>, batches: Set<String> = emptySet()): Pair<String, String>? {
        val at = "sources.$name"
        val kinds = listOf("watch", "runs", "table", "photos", "call", "reply", "batch", "mode").filter { s.has(it) }
        if (kinds.size != 1) bad("$at needs exactly one of watch, runs, table, photos, call, reply, batch or mode.")
        fun only(vararg keys: String) = s.keys().asSequence().firstOrNull { it !in keys }?.let { bad("$at: $it doesn't go with ${kinds[0]}.") }
        fun limit(max: Int) = s.opt("limit")?.let { if (it !is Int || it !in 1..max) bad("$at.limit must be 1-$max.") }
        when (kinds[0]) {
            "watch" -> {
                only("watch", "read")
                if (!scope.watch(s.opt("watch"))) bad(if (scope.packaged) "$at.watch must be {\"\$watch\":\"<a watch in processing.watches>\"}." else "$at.watch must be the id of one of your watches.")
                if (s.opt("read") !in setOf("tally", "status")) bad("$at.read must be tally or status.")
            }
            "runs" -> {
                only("runs", "limit", "step")
                if (s.opt("runs") != "workflow") bad("$at.runs must be \"workflow\" (the applet's own workflow).")
                val steps = scope.steps ?: bad("$at reads the workflow's runs, but there is no workflow.")
                limit(50)
                s.opt("step")?.let { st -> if (st !is String || st !in steps) bad("$at.step must name a step id of the workflow.") }
            }
            "table" -> {
                only("table", "limit")
                if (s.optString("table") !in scope.tables) bad("$at.table must name a table in processing.tables.")
                limit(TablePieces.MAX_SOURCE_ROWS)
            }
            "photos" -> {
                only("photos", "limit")
                val step = scope.steps?.get(s.optString("photos"))
                if (step?.optString("type") != "photo" || step.optBoolean("keep") != true) bad("$at.photos must name a photo step of the workflow that has keep: true.")
                limit(24)
            }
            "call" -> {
                only("call", "every_s")
                val c = s.opt("call") as? JSONObject ?: bad("$at.call must be {method: \"GET\", path, query}.")
                c.keys().asSequence().firstOrNull { it !in setOf("method", "path", "query") }?.let { bad("$at.call: $it isn't allowed (method, path, query).") }
                if (c.optString("method", "GET").uppercase() != "GET") bad("$at.call reads only: method must be GET (use a button to change something).")
                val path = c.opt("path") as? String ?: bad("$at.call.path must be text, like \"/v1/weather\".")
                c.opt("query")?.let { q -> if (q !is JSONObject || q.keys().asSequence().any { q.opt(it) !is String && q.opt(it) !is Number && q.opt(it) !is Boolean }) bad("$at.call.query must be an object of plain values.") }
                s.opt("every_s")?.let { if (it !is Int || it !in MIN_EVERY_S..MAX_EVERY_S) bad("$at.every_s must be $MIN_EVERY_S-$MAX_EVERY_S.") }
                return "GET" to path
            }
            "reply" -> {
                only("reply")
                if (s.optString("reply") !in replies) bad("$at.reply must name a button's into.")
            }
            "mode" -> {
                only("mode")
                if (s.opt("mode") != true) bad("$at.mode must be true: {\"mode\": true} reads the phone's mode (mode, since, set_by, modes).")
            }
            "batch" -> {
                only("batch")
                if (s.optString("batch") !in batches) bad("$at.batch must name a button with do.for_each by its id or label${if (batches.isEmpty()) "" else " (${batches.joinToString()})"}.")
            }
        }
        return null
    }

    private class ButtonUse(val call: Pair<String, String>?, val record: String?, val workflow: Boolean, val camera: Boolean = false)

    private fun button(b: JSONObject, names: Set<String>, drafts: Set<String>, scope: Scope, at: String): ButtonUse {
        b.keys().asSequence().firstOrNull { it !in setOf("id", "label", "style", "do", "confirm", "into", "only_if") }?.let { bad("$at: $it isn't a button field (id, label, style, do, confirm, into, only_if).") }
        b.opt("id")?.let { if (it !is String || !NAME.matches(it)) bad("$at.id must be a short lowercase name.") }
        val label = b.opt("label") as? String
        if (label == null || label.isBlank() || label.length > 24) bad("$at.label must be 1-24 characters.")
        b.opt("style")?.let { if (it !in STYLES) bad("$at.style must be ${STYLES.joinToString()}.") }
        b.opt("confirm")?.let { if (it !is String || it.isBlank() || it.length > 200) bad("$at.confirm must be a question of 1-200 characters.") }
        b.opt("only_if")?.let { condition(it, names, at) }
        val d = b.opt("do") as? JSONObject ?: bad("$at.do is required: refresh, pause_applet, run_workflow, flow, record, camera, step (a share step) or a call {method, path, body}.")
        val into = b.opt("into") as? String
        return when {
            d.has("refresh") -> { if (d.length() != 1 || d.opt("refresh") != true) bad("$at.do: {\"refresh\": true}."); ButtonUse(null, null, false) }
            d.has("pause_applet") -> {
                if (d.length() != 1 || d.opt("pause_applet") != true) bad("$at.do: {\"pause_applet\": true}.")
                if (!scope.packaged) bad("$at: pause_applet works on an installed applet's card only.")
                ButtonUse(null, null, false)
            }
            d.has("run_workflow") -> {
                if (d.length() != 1 || d.opt("run_workflow") != true) bad("$at.do: {\"run_workflow\": true}.")
                if (!scope.buttonWorkflow) bad("$at: run_workflow needs the applet's workflow to have a button trigger.")
                ButtonUse(null, null, true)
            }
            d.has("flow") -> { flowDo(d, names, drafts, scope, at); if (into != null) bad("$at: into goes with a call button."); ButtonUse(null, null, false) }
            d.has("record") -> {
                d.keys().asSequence().firstOrNull { it !in setOf("record", "row") }?.let { bad("$at.do: $it doesn't go with record (record, row).") }
                val table = d.optString("record")
                val cols = scope.tables[table] ?: bad("$at.do.record must name a table in processing.tables.")
                val row = d.opt("row") as? JSONObject ?: bad("$at.do.row must be an object of column → value.")
                if (row.length() !in 1..16) bad("$at.do.row must set 1-16 columns.")
                for (k in row.keys()) {
                    if (k !in cols) bad("$at.do.row: $k isn't a column of $table (${cols.joinToString()}).")
                    value(row.opt(k), names, drafts, "$at.do.row.$k", false, allowInput = scope.packaged)
                }
                if (into != null) bad("$at: into goes with a call button.")
                ButtonUse(null, table, false)
            }
            d.has("step") -> {
                // One step on a tap: only share (the owner's tap and the share sheet are the consent; O1).
                if (d.length() != 1) bad("$at.do: {\"step\": {\"type\": \"share\", …}}.")
                val st = d.opt("step") as? JSONObject ?: bad("$at.do.step must be a step object, like {\"type\": \"share\", \"table\": \"items\", \"as\": \"csv\"}.")
                if (st.opt("type") != "share") bad("$at.do.step: a card button may run a share step only (for other steps use run_workflow).")
                if (!scope.packaged) bad("$at: a share step works on an installed applet's card only.")
                if (into != null) bad("$at: into goes with a call button.")
                cc.bridgit.app.applets.AppletExport.check(st, "$at.do.step", tables = scope.tables, bad = ::bad)
                ButtonUse(null, null, false)
            }
            d.has("camera") -> {
                if (d.length() != 1) bad("$at.do: camera goes alone, as {\"camera\": {\"table\": …}}.")
                if (into != null) bad("$at: into goes with a call button.")
                val table = TablePieces.checkCamera(d.opt("camera"), "$at.do.camera", scope) { v, a -> value(v, names, drafts, a, false, allowInput = scope.packaged) }
                ButtonUse(null, table, false, camera = true)
            }
            d.has("path") -> {
                d.keys().asSequence().firstOrNull { it !in setOf("method", "path", "query", "body") }?.let { bad("$at.do: $it isn't part of a call (method, path, query, body).") }
                val method = d.optString("method", if (d.has("body")) "POST" else "GET").uppercase()
                if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) bad("$at.do.method must be GET, POST, PUT, PATCH or DELETE.")
                val path = when (val p = d.opt("path")) {
                    is String -> p
                    is JSONArray -> {
                        if (!scope.packaged) bad("$at.do.path must be text.")
                        if (p.length() !in 2..8) bad("$at.do.path must be 2-8 parts.")
                        // A reference fills one whole path segment: the text before it ends with /, the text after starts with /.
                        for (i in 0 until p.length()) if (p.opt(i) is JSONObject) {
                            if ((p.opt(i - 1) as? String)?.endsWith("/") != true) bad("$at.do.path[$i]: a reference must follow text that ends with /.")
                            if (i + 1 < p.length() && (p.opt(i + 1) as? String)?.startsWith("/") != true) bad("$at.do.path[$i]: the text after a reference must start with /.")
                        }
                        (0 until p.length()).joinToString("") { i ->
                            when (val part = p.opt(i)) {
                                is String -> part
                                is JSONObject -> if (part.length() == 1 && (part.has("\$watch") || part.has("\$input"))) "x0" else bad("$at.do.path[$i] must be text or a {\"\$watch\"} / {\"\$input\"} reference.")
                                else -> bad("$at.do.path[$i] must be text or a reference.")
                            }
                        }
                    }
                    else -> bad("$at.do.path must be text, like \"/v1/flashlight\".")
                }
                d.opt("body")?.let { if (it !is JSONObject) bad("$at.do.body must be an object.") }
                d.opt("query")?.let { if (it !is JSONObject) bad("$at.do.query must be an object.") }
                ButtonUse(method to path, null, false)
            }
            else -> bad("$at.do must be refresh, pause_applet, run_workflow, flow, record, camera, step (a share step) or a call {method, path, body}.")
        }
    }

    /**
     * A button that starts one of the applet's flows: `{"flow": {"$flow": "<id>"}, "with"?: {…}}`, or a batch, one run
     * per element: `"for_each": {"$source": "<array or table source>"}`, `"only_if"?: {"item": "<path>", <test>}`,
     * `"max"?: 1-200` (default 50), `"progress"?: "Reading"` (the button says "Reading 3 of 12…"), and `{"$item": "<path>"}`
     * in `with` for the element's values.
     */
    private fun flowDo(d: JSONObject, names: Set<String>, drafts: Set<String>, scope: Scope, at: String) {
        d.keys().asSequence().firstOrNull { it !in setOf("flow", "with", "for_each", "only_if", "max", "progress") }?.let { bad("$at.do: $it doesn't go with flow (flow, with, for_each, only_if, max, progress).") }
        if (!scope.packaged) bad("$at: do.flow works on an installed applet's card only.")
        val name = when (val f = d.opt("flow")) {
            is String -> f
            is JSONObject -> f.takeIf { it.length() == 1 }?.opt("\$flow") as? String
            else -> null
        } ?: bad("$at.do.flow must be {\"\$flow\": \"<id in processing.flows>\"}.")
        val flows = scope.flows ?: emptyMap()
        val (type, takes) = flows[name] ?: bad("$at.do.flow: $name isn't a flow in processing.flows${if (flows.isEmpty()) "" else " (${flows.keys.joinToString()})"}.")
        if (type != "called" && type != "button") bad("$at.do.flow: flow $name has a $type trigger; a button starts only a called or button flow.")
        val each = d.has("for_each")
        if (each) {
            val f = d.opt("for_each") as? JSONObject
            if (f == null || f.length() != 1 || !f.has("\$source")) bad("$at.do.for_each must be {\"\$source\": \"<source>\"}: a list or a table.")
            sourcePath(f.opt("\$source"), names, "$at.do.for_each")
            d.opt("max")?.let { if (it !is Int || it !in 1..200) bad("$at.do.max must be 1-200.") }
            d.opt("progress")?.let { if (it !is String || it.isBlank() || it.length > 24) bad("$at.do.progress must be 1-24 characters, like \"Reading\".") }
            d.opt("only_if")?.let { c ->
                val o = c as? JSONObject ?: bad("$at.do.only_if must be {\"item\": \"<field>\", \"is\": …}.")
                val path = o.opt("item") as? String
                if (path == null || path.split('.').any { !SEGMENT.matches(it) }) bad("$at.do.only_if.item must name a field of each element, like \"status\".")
                val tests = o.keys().asSequence().filter { it != "item" }.toList()
                if (tests.size != 1 || tests[0] !in TESTS) bad("$at.do.only_if needs exactly one test: ${TESTS.joinToString()}.")
                condition(JSONObject(o.toString()).apply { remove("item"); put("source", "item") }, setOf("item"), "$at.do")
            }
        } else for (k in listOf("only_if", "max", "progress")) if (d.has(k)) bad("$at.do.$k goes with for_each.")
        d.opt("with")?.let { w ->
            val o = w as? JSONObject ?: bad("$at.do.with must be an object of value names.")
            for (k in o.keys()) {
                if (k !in takes) bad("$at.do.with.$k: flow $name doesn't take $k${if (takes.isEmpty()) " (it takes no values)" else " (it takes ${takes.joinToString()})"}.")
                when (val v = o.opt(k)) {
                    is JSONObject -> if (v.has("\$item")) {
                        if (!each) bad("$at.do.with.$k: {\"\$item\"} goes with for_each.")
                        val p = v.opt("\$item") as? String
                        if (v.length() != 1 || p == null || p.split('.').any { !SEGMENT.matches(it) }) bad("$at.do.with.$k: {\"\$item\": \"<field>\"} names a field of each element, like \"id\".")
                    } else value(v, names, drafts, "$at.do.with.$k", false)
                    is String, is Number, is Boolean, JSONObject.NULL -> Unit
                    else -> bad("$at.do.with.$k must be text, a number, true/false or a reference.")
                }
            }
        }
    }

    /**
     * The elements a batch button goes through, as (key, with): [list] filtered by `do.only_if`, at most `do.max`,
     * each `with` filled from the element (`$item`) and the card ([values]). The key is the element's `id`, or its
     * place in the list.
     */
    fun batchElements(d: JSONObject, list: JSONArray, values: Values): List<Pair<String, JSONObject>> {
        val max = d.optInt("max", 50).coerceIn(1, 200)
        val test = d.optJSONObject("only_if")?.let { JSONObject(it.toString()).apply { put("source", "item." + optString("item")); remove("item") } }
        val with = d.optJSONObject("with") ?: JSONObject()
        val out = ArrayList<Pair<String, JSONObject>>()
        for (i in 0 until list.length()) {
            if (out.size >= max) break
            val item = list.opt(i)
            if (test != null && !holds(test, Values(values.sources + ("item" to item), values.drafts))) continue
            val w = JSONObject()
            for (k in with.keys()) {
                val v = with.opt(k)
                w.put(k, (if (v is JSONObject && v.has("\$item")) lookup(item, v.getString("\$item").split('.')) else resolve(v, values)) ?: JSONObject.NULL)
            }
            out += ((item as? JSONObject)?.opt("id")?.takeIf { it != JSONObject.NULL }?.toString() ?: i.toString()) to w
        }
        return out
    }

    /** A text field: a string, one reference, or a list of strings and references (1-20 parts). */
    private fun text(v: Any?, names: Set<String>, drafts: Set<String>, at: String, required: Boolean) {
        if (v == null) { if (required) bad("$at is required.") else return }
        val parts = if (v is JSONArray) (0 until v.length()).map { v.opt(it) }.also { if (it.size !in 1..20) bad("$at: use 1-20 parts.") } else listOf(v)
        var literal = 0
        for (p in parts) when (p) {
            is String -> literal += p.length
            is JSONObject -> ref(p, names, drafts, at)
            is Number, is Boolean -> literal += p.toString().length
            else -> bad("$at must be text, a {\"\$source\"} reference or a list of them.")
        }
        if (literal > TEXT_MAX) bad("$at is longer than $TEXT_MAX characters.")
        if (required && literal == 0 && parts.none { it is JSONObject }) bad("$at is required.")
    }

    /** A value: a fixed value or one reference. */
    private fun value(v: Any?, names: Set<String>, drafts: Set<String>, at: String, required: Boolean, allowInput: Boolean = false) {
        when (v) {
            null -> if (required) bad("$at is required.")
            is JSONObject -> if (allowInput && v.length() == 1 && v.has("\$input")) Unit else ref(v, names, drafts, at)
            is JSONArray -> if (v.length() > 50) bad("$at: at most 50 values.")
            else -> Unit
        }
    }

    private fun ref(o: JSONObject, names: Set<String>, drafts: Set<String>, at: String) {
        if (o.length() != 1) bad("$at: a reference must be alone, like {\"\$source\":\"name.path\"}.")
        when {
            o.has("\$source") -> sourcePath(o.opt("\$source"), names, at)
            o.has("\$draft") -> if (o.opt("\$draft") !in drafts) bad("$at: {\"\$draft\"} must name a draft piece's id.")
            else -> bad("$at: unknown reference ${o.keys().next()} (use \$source or \$draft).")
        }
    }

    private fun sourcePath(v: Any?, names: Set<String>, at: String) {
        val path = v as? String ?: bad("$at: a source reference is \"<source>.<field>\".")
        val parts = path.split('.')
        if (parts.any { !SEGMENT.matches(it) }) bad("$at: invalid source reference $path.")
        if (parts[0] !in names) bad("$at: $path names no source ${parts[0]} (sources: ${names.joinToString()}).")
    }

    private fun condition(v: Any, names: Set<String>, at: String) {
        val c = v as? JSONObject ?: bad("$at: only_if must be {\"source\":\"name.path\", \"is\": …}.")
        if (!c.has("source")) bad("$at: only_if needs source.")
        sourcePath(c.opt("source"), names, "$at.only_if")
        val tests = c.keys().asSequence().filter { it != "source" }.toList()
        if (tests.size != 1 || tests[0] !in TESTS) bad("$at: only_if needs exactly one test: ${TESTS.joinToString()}.")
        val value = c.opt(tests[0])
        when (tests[0]) {
            "is", "is_not" -> if (value !is String && value !is Number && value !is Boolean) bad("$at: only_if.${tests[0]} must be text, a number or true/false.")
            "in" -> if (value !is JSONArray || value.length() !in 1..20) bad("$at: only_if.in must be a list of 1-20 values.")
            "at_least", "below" -> if (value !is Number) bad("$at: only_if.${tests[0]} must be a number.")
            "exists" -> if (value !is Boolean) bad("$at: only_if.exists must be true or false.")
        }
    }

    /**
     * After every `$input` and `$watch` in a package card is filled: each button path given as parts becomes one
     * path, and a part that came from a reference must be a plain id, so a blank's value can't reshape the call.
     * [raw] is the card as written, [filled] the same card with references replaced (changed in place).
     */
    fun seal(raw: JSONObject, filled: JSONObject): JSONObject {
        val rp = raw.optJSONArray("pieces") ?: return filled
        val fp = filled.optJSONArray("pieces") ?: return filled
        for (i in 0 until rp.length()) {
            val ri = rp.optJSONObject(i)?.optJSONArray("items") ?: continue
            if (rp.getJSONObject(i).optString("type") != "buttons") continue
            val fi = fp.getJSONObject(i).getJSONArray("items")
            for (j in 0 until ri.length()) {
                val rpath = ri.optJSONObject(j)?.optJSONObject("do")?.opt("path") as? JSONArray ?: continue
                val fdo = fi.getJSONObject(j).getJSONObject("do")
                val fpath = fdo.getJSONArray("path")
                val joined = (0 until rpath.length()).joinToString("") { k ->
                    val v = fpath.opt(k)
                    if (rpath.opt(k) is JSONObject) (v as? String)?.takeIf { PATH_ID.matches(it) } ?: (v as? Number)?.toString()?.takeIf { PATH_ID.matches(it) }
                        ?: bad("pieces[$i].items[$j].do.path: a filled-in part must be a plain id (letters, digits, - or _), not \"$v\".")
                    else v.toString()
                }
                fdo.put("path", joined)
            }
        }
        return filled
    }

    // ---- running: references, text, conditions -----------------------------------------------------------------

    /** The value at [path] (object keys, list indexes) under [root], or null. */
    fun lookup(root: Any?, path: List<String>): Any? {
        var cur = root
        for (seg in path) cur = when (cur) {
            is JSONObject -> cur.opt(seg)?.takeIf { it != JSONObject.NULL }
            is JSONArray -> seg.toIntOrNull()?.let { cur.opt(it) }?.takeIf { it != JSONObject.NULL }
            else -> null
        }
        return cur
    }

    /** Reads sources and drafts while a card is drawn. */
    class Values(val sources: Map<String, Any?>, val drafts: Map<String, String> = emptyMap())

    /** A value node with its references replaced; a missing value is null. */
    fun resolve(v: Any?, values: Values): Any? = when {
        v is JSONObject && v.length() == 1 && v.has("\$source") -> v.getString("\$source").split('.').let { p -> lookup(values.sources[p[0]], p.drop(1)) }
        v is JSONObject && v.length() == 1 && v.has("\$draft") -> values.drafts[v.getString("\$draft")]
        v == JSONObject.NULL -> null
        else -> v
    }

    /** A text field's final text: references become text, at most [max] characters. */
    fun textOf(v: Any?, values: Values, max: Int = TEXT_MAX): String {
        val parts = if (v is JSONArray) (0 until v.length()).map { v.opt(it) } else listOf(v)
        return parts.joinToString("") { p -> show(if (p is JSONObject) resolve(p, values) else p) }.take(max)
    }

    fun show(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else (Math.round(v * 100) / 100.0).toString()
        is Float -> show(v.toDouble())
        is JSONObject, is JSONArray -> ""
        else -> v.toString()
    }

    /** Whether an `only_if` holds, the same tests as a flow step's. */
    fun holds(c: JSONObject, values: Values): Boolean {
        val v = c.getString("source").split('.').let { p -> lookup(values.sources[p[0]], p.drop(1)) }
        fun same(a: Any?, b: Any?): Boolean = when {
            a == null || b == null -> false
            a is Number && b is Number -> a.toDouble() == b.toDouble()
            a is Boolean || b is Boolean -> a.toString() == b.toString()
            else -> a.toString().trim().equals(b.toString().trim(), ignoreCase = true)
        }
        fun num(x: Any?) = (x as? Number)?.toDouble() ?: (x as? String)?.toDoubleOrNull() ?: (x as? JSONArray)?.length()?.toDouble()
        return when {
            c.has("exists") -> (v != null) == c.getBoolean("exists")
            c.has("is") -> same(v, c.opt("is"))
            c.has("is_not") -> v != null && !same(v, c.opt("is_not"))
            c.has("in") -> c.getJSONArray("in").let { a -> (0 until a.length()).any { same(v, a.opt(it)) } }
            c.has("at_least") -> num(v)?.let { it >= c.getDouble("at_least") } == true
            c.has("below") -> num(v)?.let { it < c.getDouble("below") } == true
            else -> false
        }
    }

    /** The items a list piece draws: a list as is, or an object's entries as rows with `$key`. */
    fun items(v: Any?): List<JSONObject> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { i -> v.opt(i) as? JSONObject ?: v.opt(i)?.takeIf { it != JSONObject.NULL }?.let { JSONObject().put("value", it) } }
        is JSONObject -> if (v.optJSONArray("items") != null) items(v.optJSONArray("items"))
            else v.keys().asSequence().map { k -> (v.opt(k) as? JSONObject)?.let { JSONObject(it.toString()) }?.put("\$key", k) ?: JSONObject().put("\$key", k).put("value", v.opt(k)) }.toList()
        else -> emptyList()
    }

    // ---- formats --------------------------------------------------------------------------------------------

    /** Milliseconds since 1970 from an ISO time, a date, or epoch seconds/ms; null when it isn't a time. */
    fun epochMs(v: Any?): Long? = when (v) {
        is Number -> v.toLong().let { if (it in 1..99_999_999_999L) it * 1000 else it }.takeIf { it > 0 }
        is String -> runCatching { java.time.Instant.parse(v).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(v).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
            ?: v.toLongOrNull()?.let { epochMs(it) }
        else -> null
    }

    /**
     * [value] shown in [format] (null: text). [opts] may hold `currency`, `words`. [now] and [zone] for times.
     * A missing or wrong-typed value gives null, which the piece shows as its empty text.
     */
    fun format(value: Any?, format: String?, opts: JSONObject? = null, now: Long = System.currentTimeMillis(),
               zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), locale: java.util.Locale = java.util.Locale.getDefault()): String? {
        if (value == null || value == JSONObject.NULL) return null
        fun num(): Double? = (value as? Number)?.toDouble() ?: (value as? String)?.trim()?.toDoubleOrNull()
        fun grouped(d: Double, decimals: Int = if (d == Math.floor(d)) 0 else 1) = java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = decimals; minimumFractionDigits = 0 }.format(d)
        return when (format ?: "text") {
            "text" -> show(value).ifEmpty { null }
            "number" -> num()?.let { grouped(it, if (it == Math.floor(it)) 0 else 2) }
            "minutes" -> num()?.let { m -> val t = Math.round(m).toLong(); if (t < 60) "$t min" else "${t / 60} h" + (if (t % 60 > 0) " ${t % 60} min" else "") }
            "duration_s" -> num()?.let { s -> val t = Math.round(s).toLong(); when { t < 60 -> "$t s"; t < 3600 -> "${t / 60} min" + (if (t % 60 > 0) " ${t % 60} s" else ""); else -> "${t / 3600} h" + (if (t % 3600 / 60 > 0) " ${t % 3600 / 60} min" else "") } }
            "time" -> epochMs(value)?.let { java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", locale).withZone(zone).format(java.time.Instant.ofEpochMilli(it)) }
            "date" -> epochMs(value)?.let { java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", locale).withZone(zone).format(java.time.Instant.ofEpochMilli(it)) }
            "ago" -> epochMs(value)?.let { ago(now - it) }
            "percent" -> num()?.let { "${Math.round(it * 100)}%" }
            "celsius" -> num()?.let { "${grouped(it, 1)} °C" }
            "money" -> num()?.let { n ->
                val cur = opts?.opt("currency")?.let { it as? String }?.takeIf { Regex("^[A-Z]{3}$").matches(it) }
                val f = java.text.NumberFormat.getCurrencyInstance(locale)
                if (cur != null) runCatching { f.currency = java.util.Currency.getInstance(cur) }
                if (cur != null) f.format(n) else grouped(n, 2)
            }
            "yes_no" -> when (value) { is Boolean -> if (value) "Yes" else "No"; is Number -> if (value.toDouble() >= 0.5) "Yes" else "No"; is String -> when (value.lowercase()) { "true", "yes" -> "Yes"; "false", "no" -> "No"; else -> null }; else -> null }
            "words" -> opts?.optJSONObject("words")?.let { w -> w.optString(show(value)).ifEmpty { null } } ?: show(value).ifEmpty { null }
            else -> show(value).ifEmpty { null }
        }
    }

    fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 45 -> "just now"
            s < 3600 -> "${(s + 30) / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    // ---- the card's state -----------------------------------------------------------------------------------

    /** Whether a source's data counts as nothing yet. */
    fun isEmpty(v: Any?): Boolean = when (v) {
        null, JSONObject.NULL -> true
        is JSONArray -> v.length() == 0
        is JSONObject -> when {
            v.has("items") -> (v.optJSONArray("items")?.length() ?: 0) == 0
            v.has("runs") -> (v.optJSONArray("runs")?.length() ?: 0) == 0
            v.has("today") || v.has("episodes") -> (v.optJSONObject("today")?.optInt("checks", 0) ?: 0) == 0 && (v.optJSONArray("episodes")?.length() ?: 0) == 0
            v.has("recent_checks") || v.has("last_check") -> v.optJSONObject("last_check") == null && (v.optJSONArray("recent_checks")?.length() ?: 0) == 0
            v.has("devices") -> (v.optJSONArray("devices")?.length() ?: 0) == 0
            else -> v.length() == 0
        }
        else -> false
    }

    /** When a source's newest data was observed (ms), or null when it doesn't say. */
    fun observedAt(v: Any?): Long? {
        val o = v as? JSONObject ?: return null
        for (k in listOf("last_check.at", "last_seen_at", "recorded_at", "fetched_at", "forecast_updated_at", "items.0.at", "items.0.started_at", "items.0.taken_at", "runs.0.started_at", "at"))
            lookup(o, k.split('.'))?.let { epochMs(it) }?.let { return it }
        return null
    }

    /** A source's own error to show (a watch whose last check failed), or null. */
    fun sourceError(v: Any?): String? = (v as? JSONObject)?.optJSONObject("last_check")?.takeIf { it.optBoolean("error") || (it.opt("error") is String) }
        ?.let { it.optString("message").ifEmpty { (it.opt("error") as? String) ?: "The latest check failed. Earlier readings are kept." } }

    data class State(val id: String, val words: String?)

    /**
     * The state strip: [loaded] maps each source name to its data (absent: still loading), [errors] to a read
     * error. Paused wins, then errors, loading, empty, stale. Ready shows no words.
     */
    fun state(card: JSONObject, loaded: Map<String, Any?>, errors: Map<String, String>, paused: Boolean, now: Long = System.currentTimeMillis()): State {
        val names = card.optJSONObject("sources")?.keys()?.asSequence()?.filter { card.getJSONObject("sources").optJSONObject(it)?.has("reply") != true }?.toList() ?: emptyList()
        val custom = card.optJSONObject("states")
        fun words(id: String, default: String) = custom?.opt(id)?.let { textOf(it, Values(loaded)) }?.ifBlank { null } ?: default
        if (paused) return State("paused", words("paused", "Paused. Counts are kept."))
        (errors.values.firstOrNull() ?: names.firstNotNullOfOrNull { sourceError(loaded[it]) })?.let { return State("error", custom?.opt("error")?.let { textOf(it, Values(loaded)) }?.ifBlank { null } ?: it) }
        if (names.any { it !in loaded }) return State("loading", words("loading", "Reading from the phone…"))
        if (names.isNotEmpty() && names.all { isEmpty(loaded[it]) }) return State("empty", words("empty", "Nothing yet."))
        val fresh = card.optInt("fresh_s", 0)
        if (fresh > 0) {
            val newest = names.mapNotNull { observedAt(loaded[it]) }.maxOrNull()
            if (newest != null && now - newest > fresh * 1000L) return State("stale", words("stale", "Last update ${ago(now - newest)}"))
        }
        return State("ready", null)
    }

    // ---- plain words for the review ---------------------------------------------------------------------------

    /** "a, b and c". */
    private fun andList(l: List<String>) = if (l.size <= 1) l.joinToString() else l.dropLast(1).joinToString(", ") + " and " + l.last()

    private fun pieceWords(p: JSONObject): String? = when (p.optString("type")) {
        "metrics" -> p.optJSONArray("items")?.let { a -> andList((0 until a.length()).mapNotNull { (a.optJSONObject(it)?.opt("label") as? String)?.lowercase() }).ifEmpty { null } }
        "table" -> "a table" + ((p.optJSONArray("columns")?.let { c -> (0 until c.length()).mapNotNull { (c.optJSONObject(it)?.opt("label") as? String)?.lowercase() } } ?: emptyList()).takeIf { it.isNotEmpty() }?.let { " of ${andList(it)}" } ?: "")
        "timeline" -> "a timeline"
        "gallery" -> "photos"
        "video" -> "a video"
        "people" -> "a list of people"
        "devices" -> "a list of devices"
        "draft" -> "a text box you can edit"
        else -> null
    }

    /** What a batch button goes through, in words: "\"photos\"" for {"$source": "photos"} (or "photos.items"). */
    internal fun eachOf(d: JSONObject): String = (d.opt("for_each") as? JSONObject)?.optString("\$source")?.substringBefore('.')?.takeIf { it.isNotEmpty() }?.let { "\"$it\"" } ?: "the list"

    private fun buttonWords(b: JSONObject, callWords: (String) -> String?): String {
        val d = b.optJSONObject("do") ?: JSONObject()
        val label = "\"${b.optString("label")}\""
        return when {
            d.has("refresh") -> "$label (reads again)"
            d.has("pause_applet") -> "$label (pauses the applet)"
            d.has("run_workflow") -> "$label (runs its workflow)"
            d.has("flow") -> "$label (runs the flow \"${d.opt("flow").let { (it as? JSONObject)?.optString("\$flow") ?: it.toString() }}\"${if (d.has("for_each")) " once for each entry in ${eachOf(d)}, at most ${d.optInt("max", 50)}" else ""})"
            d.has("record") -> "$label (saves a row on the phone)"
            d.has("step") -> d.optJSONObject("step").let { s -> "$label (makes ${if (s?.optString("as") == "csv") "a CSV file" else "an unavailable legacy PDF export"} of ${s?.optString("title")?.ifBlank { null }?.let { "\"${it.take(60)}\"" } ?: "the table ${s?.optString("table")}"} for you to share when you tap it)" }
            d.has("camera") -> "$label (takes photos when you tap ${b.optString("label")})"
            else -> {
                val call = "${d.optString("method", if (d.has("body")) "POST" else "GET").uppercase()} ${d.opt("path").let { if (it is JSONArray) (0 until it.length()).joinToString("") { i -> (it.opt(i) as? String) ?: "x0" } else it.toString() }}"
                "$label (${callWords(call) ?: call})"
            }
        }
    }

    /**
     * The card for the applet review's "What it does" (design pass): one sentence on what it shows, and each button as
     * its label and effect. [table] names a table by its label and [flow] a flow by its name.
     */
    fun reviewItem(card: JSONObject, title: String, callWords: (String) -> String?, table: (String) -> String, flow: (String) -> String): Pair<String, List<Pair<String, String>>> {
        val pieces = card.optJSONArray("pieces") ?: JSONArray()
        val shows = (0 until pieces.length()).mapNotNull { pieces.optJSONObject(it)?.let(::pieceWords) }.distinct()
        val buttons = (0 until pieces.length()).mapNotNull { pieces.optJSONObject(it) }.filter { it.optString("type") == "buttons" }
            .flatMap { p -> p.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList() }
            .map { b -> b.optString("label") to buttonEffect(b, callWords, table, flow) }
        // Pieces can hold their own "a, b and c" lists, so three or more get a serial comma.
        val list = if (shows.size <= 2) shows.joinToString(" and ") else shows.dropLast(1).joinToString(", ") + ", and " + shows.last()
        return "Shows $title as its own page" + (if (shows.isNotEmpty()) ", with $list." else ".") to buttons
    }

    private fun buttonEffect(b: JSONObject, callWords: (String) -> String?, table: (String) -> String, flow: (String) -> String): String {
        val d = b.optJSONObject("do") ?: JSONObject()
        fun each() = (d.opt("for_each") as? JSONObject)?.optString("\$source")?.substringBefore('.')?.takeIf { it.isNotEmpty() }?.let(table) ?: "the list"
        return when {
            d.has("refresh") -> "reads it again"
            d.has("pause_applet") -> "pauses the applet"
            d.has("run_workflow") -> "runs its workflow"
            d.has("flow") -> "runs ${flow(d.opt("flow").let { (it as? JSONObject)?.optString("\$flow") ?: it.toString() })}" +
                (if (d.has("for_each")) " once for each entry in ${each()}, at most ${"%,d".format(java.util.Locale.US, d.optInt("max", 50))}" else "")
            d.has("record") -> "saves a row on this device"
            d.has("step") -> d.optJSONObject("step").let { s -> "makes ${if (s?.optString("as") == "csv") "a CSV file" else "an unavailable legacy PDF export"} of ${s?.optString("title")?.ifBlank { null }?.take(60) ?: s?.optString("table")?.let(table) ?: "the table"} for you to share" }
            d.has("camera") -> "takes photos"
            else -> {
                val call = "${d.optString("method", if (d.has("body")) "POST" else "GET").uppercase()} ${d.opt("path").let { if (it is JSONArray) (0 until it.length()).joinToString("") { i -> (it.opt(i) as? String) ?: "x0" } else it.toString() }}"
                callWords(call)?.replaceFirstChar { it.lowercase() } ?: "calls $call"
            }
        } + "."
    }

    /** One sentence for the review screen: what the card shows and which buttons it has. */
    fun describe(card: JSONObject, title: String, callWords: (String) -> String? = { null }): String {
        val pieces = card.optJSONArray("pieces") ?: JSONArray()
        val shows = (0 until pieces.length()).mapNotNull { pieces.optJSONObject(it)?.let(::pieceWords) }.distinct()
        val buttons = (0 until pieces.length()).mapNotNull { pieces.optJSONObject(it) }.filter { it.optString("type") == "buttons" }
            .flatMap { p -> p.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { b -> buttonWords(b, callWords) } } } ?: emptyList() }
        return "It has its own page, \"$title\"" +
            (if (shows.isNotEmpty()) ", with ${shows.joinToString(", ")}" else "") +
            (if (buttons.isNotEmpty()) ", and the button${if (buttons.size == 1) "" else "s"} ${buttons.joinToString(", ")}" else "") + "."
    }
}
