package cc.bridgit.app.platform

import cc.bridgit.app.*
import cc.bridgit.app.judgment.Jev
import org.json.JSONArray
import org.json.JSONObject

/**
 * The flow step menu (docs/applet-flows.md, phase 1): which steps a workflow may use, how a step names an
 * earlier step's result, conditions, error policy and the plain-sentence summary. Pure: no Android, no
 * storage, so the rules are unit-tested on their own; [WorkflowEngine] runs the steps.
 *
 * References are whole values, like `$input` and `$watch` in packages: `{"$step":"id.path"}` or
 * `{"$trigger":"path"}` is replaced by the value. Plain-text fields may also be an array of strings and
 * references, joined into one string. Nothing is spliced into a request, a URL or HTML.
 */
object FlowSteps {
    const val MAX_PAID_STEPS = 4
    const val DEFAULT_PAID_PER_DAY = 50
    const val MAX_PAID_PER_DAY = 500
    const val BASE_RUN_SECONDS = 120
    /** Waits between retries of a step with `on_error: retry`. */
    val RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 10_000L)

    private val ID = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val SEGMENT = Regex("^[A-Za-z0-9_-]{1,40}$")
    private val COMMON = setOf("type", "id", "only_if", "on_error", "retries")
    private val LEGACY = setOf("notice", "speak", "ring", "alert", "event")
    private val LEGACY_FIELDS = setOf("type", "text", "title", "seconds", "destinations")

    /** Every step type, the fields it takes besides [COMMON], and the scope it needs (null: none). */
    val STEPS: Map<String, Pair<Set<String>, String?>> = linkedMapOf(
        "photo" to (setOf("camera", "keep", "view") to "camera.snapshot"),
        "ask_model" to (setOf("model", "questions", "photo") to "decisions"),
        "ask_owner" to (setOf("title", "text", "choices", "wait_s") to "interactions"),
        "wait" to (setOf("seconds") to null),
        "stop" to (emptySet<String>() to null),
        "notice" to (setOf("title", "text", "priority") to "interactions"),
        "speak" to (setOf("text", "allow_cloud") to "audio.speak"),
        "hear" to (setOf("duration_seconds", "language", "allow_cloud") to "microphone.clip"),
        "ring" to (setOf("seconds") to "phone.control"),
        "alert" to (setOf("text", "destinations") to "alerts"),
        "event" to (setOf("text") to null),
        "record" to (setOf("table", "row", "for_each") to null),
        // A paid Jev question like ask_model, so the same workflow scope (find_crop is an agent endpoint, not a flow scope).
        "appraise" to (setOf("photo", "max_items") to "decisions"),
        "service" to (setOf("service","operation","photo","max_items","area","session_s","tip_gap_s") to "decisions"),
        "kitchen" to (setOf("photo", "mode", "area", "session_s", "tip_gap_s") to "decisions"),
        "update" to (setOf("table", "row", "set") to null),
        "share" to (cc.bridgit.app.applets.AppletExport.FIELDS to null),
        // Webhooks out (docs/applet-reach.md A3): to an owner-approved connection, never a URL.
        "send" to (setOf("to", "data", "text", "photo", "wait_reply") to "connections.send"),
        // Modes (docs/applet-reach.md D2): one mode per phone; changing it is an action.
        "context" to (emptySet<String>() to "context.read"),
        "set_mode" to (setOf("mode") to "modes.set"),
        // Reading the applet's own table inside a flow (D4): the newest matching rows (at most 10) and their count.
        "lookup" to (setOf("table", "where", "since", "hours", "limit") to null),
    )
    /** At most this many rows per `record` with `for_each` (one write). */
    const val MAX_FOR_EACH = 50

    /** Plain-text fields per step type: max length and whether they're required. */
    private val TEXT: Map<String, Map<String, Pair<Int, Boolean>>> = mapOf(
        "notice" to mapOf("title" to (120 to true), "text" to (4096 to false)),
        "speak" to mapOf("text" to (600 to true)),
        "alert" to mapOf("text" to (1024 to true)),
        "event" to mapOf("text" to (120 to true)),
        "ask_owner" to mapOf("title" to (120 to true), "text" to (4096 to false)),
        "send" to mapOf("text" to (Connections.MAX_TEXT to false)),
    )

    fun scopeOf(type: String): String? = STEPS[type]?.second

    /** Steps a rule, a schedule or a button may run on their own (`do.step`, docs/applet-vocabulary.md). */
    val INLINE = setOf("notice", "speak", "send", "set_mode")
    /** Value types a `called` flow may take in `with`. */
    val WITH_TYPES = setOf("text", "number", "boolean")
    const val MAX_WITH = 8
    const val MAX_WITH_TEXT = 4096

    /** One send step, for checks that need the owner's connections: [to] its connection, whether it has text and a photo. */
    class SendUse(val at: String, val to: String, val text: Boolean, val photo: Boolean)

    class Checked(val scopes: Set<String>, val runSeconds: Int, val paidSteps: Int, val destinations: List<List<String>>, val sends: List<SendUse> = emptyList(),
        val needs: FlowConditions.Needs = FlowConditions.Needs())

    private fun bad(message: String): Nothing = throw ApiError(422, "invalid_workflow", message)

    /** Compile immutable vision selectors before applying their host operation's field, scope and budget checks. */
    fun hostStep(step: JSONObject): JSONObject {
        if (step.opt("type") != "service") return step
        if (step.has("mode")) bad("A service step selects its operation with operation")
        if (step.optString("on_error") == "retry") bad("Signed vision operations cannot be retried automatically")
        val operation = step.opt("operation")
        if (operation == "observe" && (step.has("photo") || step.has("area"))) bad("Observation captures its own frame; omit photo and area")
        val host = when (step.opt("service")) {
            "chef.signed-edition-budget.v2" -> if (operation == "scan" || operation == "observe") "kitchen" else bad("Chef accepts scan or observe")
            "appraise.signed-edition-budget.v2" -> if (operation == "appraise") "appraise" else bad("Appraise accepts appraise")
            else -> bad("Unknown signed vision service")
        }
        return JSONObject(step.toString()).apply {
            remove("service")
            remove("operation")
            put("type",host)
            if (host == "kitchen") put("mode",operation)
        }
    }

    /** Checks [steps] (1-32 objects). Returns the scopes they need, the run time limit and the alert destinations to check. */
    fun check(steps: JSONArray, with: Set<String> = emptySet()): Checked {
        if (steps.length() !in 1..32) bad("Use 1-32 steps")
        val ids = mutableMapOf<String, String>() // id -> type, for steps before the current one
        val scopes = linkedSetOf<String>()
        var extra = 0; var paid = 0
        val destinations = mutableListOf<List<String>>()
        val sends = mutableListOf<SendUse>()
        val needs = FlowConditions.Needs()
        for (i in 0 until steps.length()) {
            val step = hostStep(steps.optJSONObject(i) ?: bad("Each step must be an object"))
            val type = step.opt("type") as? String ?: bad("steps[$i] needs a type: ${STEPS.keys.joinToString()}")
            val (fields, scope) = STEPS[type] ?: bad("steps[$i]: unknown step type $type. Steps: ${STEPS.keys.joinToString()}")
            val at = "steps[$i] ($type)"
            // The five original steps always accepted the same shared fields (unused ones ignored): saved workflows stay valid.
            val accepted = fields + COMMON + (if (type in LEGACY) LEGACY_FIELDS else emptySet())
            step.keys().asSequence().firstOrNull { it !in accepted }?.let { bad("$at: $it isn't a field of $type (${(fields + COMMON).joinToString()})") }
            val id = step.opt("id")?.let { it as? String ?: bad("$at: id must be text") }
            if (id != null) {
                if (!ID.matches(id)) bad("$at: id must be 1-32 lowercase letters, digits or _ and start with a letter")
                if (id in ids) bad("$at: id $id is used twice")
            }
            step.opt("only_if")?.let { needs.add(condition(it, ids, at, with, flow = false)) }
            when (val p = step.opt("on_error")) {
                null, "stop", "skip" -> if (step.has("retries")) bad("$at: retries goes with on_error retry")
                "retry" -> { val r = step.opt("retries") ?: 1; if (r !is Int || r !in 1..3) bad("$at: retries must be 1-3"); extra += 20 * r }
                else -> bad("$at: on_error must be stop, skip or retry (not $p)")
            }
            TEXT[type]?.forEach { (field, rule) -> text(step, field, rule.first, rule.second, ids, "$at.$field", with) }
            when (type) {
                "photo" -> {
                    step.opt("camera")?.let { c -> if (c !is String || !(c == "rear" || c == "front" || (c.startsWith("lan:") && c.length <= 128))) bad("$at: camera must be rear, front or a local camera id (lan:…)") }
                    if (step.has("view") && (step.opt("view") !is String || !Regex("[a-z][a-z0-9_]{0,31}").matches(step.getString("view")))) bad("$at: invalid saved camera view name")
                    step.opt("keep")?.let { if (it !is Boolean) bad("$at: keep must be true or false") }
                }
                "record" -> record(step, ids, at, with)
                "share" -> { cc.bridgit.app.applets.AppletExport.check(step, at, flow = true, bad = ::bad); extra += cc.bridgit.app.applets.AppletExport.RUN_SECONDS }
                "update" -> update(step, ids, at, with)
                "kitchen" -> {
                    if (step.optString("mode") !in setOf("scan", "observe")) bad("$at: mode must be scan or observe")
                    if(step.optString("mode")=="scan") ref(step.optJSONObject("photo") ?: bad("$at: photo must be a reference"), ids, "$at.photo", with)
                    else { scopes += "camera.snapshot"; scopes += "audio.speak" }
                    if (step.has("area") && step.optString("area") !in setOf("Fridge", "Cupboard", "Spices")) bad("$at: area must be Fridge, Cupboard or Spices")
                    if (step.optString("mode") == "observe") cookingTiming(step, at)
                    else if (step.has("session_s") || step.has("tip_gap_s")) bad("$at: session_s and tip_gap_s require observe mode")
                    if (++paid > MAX_PAID_STEPS) bad("Too many paid kitchen steps")
                }
                "appraise" -> {
                    when (val p = step.opt("photo")) {
                        is String -> if (!Regex("^kept:[a-z0-9]{1,40}$").matches(p)) bad("$at: photo must be a kept photo ref or a reference, like {\"\$trigger\":\"with.photo\"}")
                        is JSONObject -> ref(p, ids, "$at.photo", with)
                        else -> bad("$at: photo is required: a kept photo, like {\"\$trigger\":\"with.photo\"} or {\"\$step\":\"shot.photo\"}")
                    }
                    whole(step, "max_items", 20, 1, 30, at)
                    if (++paid > MAX_PAID_STEPS) bad("A workflow may ask Jev or Clef at most $MAX_PAID_STEPS times per run")
                }
                "ask_model" -> {
                    if (step.opt("model") !in setOf("jev", "clef", "glm")) bad("$at: model must be jev, clef or glm")
                    questions(step.opt("questions"), at)
                    step.opt("photo")?.let { p ->
                        val ref = (p as? JSONObject)?.takeIf { it.length() == 1 }?.opt("\$step") as? String ?: bad("$at: photo must be {\"\$step\":\"<photo step id>.photo\"}")
                        val parts = ref.split('.')
                        if (parts.size != 2 || parts[1] != "photo" || ids[parts[0]] != "photo") bad("$at: photo must name an earlier photo step, like {\"\$step\":\"shot.photo\"}")
                    }
                    if (++paid > MAX_PAID_STEPS) bad("A workflow may ask Jev or Clef at most $MAX_PAID_STEPS times per run")
                }
                "ask_owner" -> {
                    val c = step.optJSONArray("choices") ?: bad("$at: choices must be a list of 2-6 short texts")
                    if (c.length() !in 2..6 || (0 until c.length()).any { (c.opt(it) as? String)?.length !in 1..80 }) bad("$at: choices must be a list of 2-6 texts of 1-80 characters")
                    if ((0 until c.length()).map { c.getString(it) }.toSet().size != c.length()) bad("$at: choices must differ")
                    extra += whole(step, "wait_s", 300, 30, 3600, at)
                }
                "wait" -> { if (!step.has("seconds")) bad("$at: seconds is required (1-300)"); extra += whole(step, "seconds", 1, 1, 300, at) }
                "ring" -> whole(step, "seconds", 10, 1, 30, at)
                "hear", "speak" -> {
                    step.opt("allow_cloud")?.let { if (it !is Boolean) bad("$at: allow_cloud must be a boolean") }
                    if (type == "hear") {
                        whole(step, "duration_seconds", 5, 1, 30, at)
                        step.opt("language")?.let { if (it !is String || it.length !in 2..32) bad("$at: invalid language") }
                        extra += 60
                    }
                    if (step.optBoolean("allow_cloud")) {
                        if (++paid > MAX_PAID_STEPS) bad("At most $MAX_PAID_STEPS paid steps per run")
                        if (step.optString("on_error") == "retry") bad("$at: cloud speech cannot retry implicitly; its first attempt may be billed")
                    }
                }
                "notice" -> step.opt("priority")?.let { if (it != "normal" && it != "urgent") bad("$at: priority must be normal or urgent") }
                "send" -> { sends += send(step, ids, at, with); if (step.optBoolean("wait_reply")) extra += (Connections.REPLY_WAIT_MS / 1000).toInt() }
                "set_mode" -> { val m = step.opt("mode") as? String; if (m == null || !Modes.NAME.matches(m)) bad("$at: mode must be one of the phone's modes, like \"away\" (${Modes.DEFAULTS.joinToString()} or one the owner added)") }
                "lookup" -> {
                    FlowConditions.rowFilter(step, at, COMMON + "limit", ::bad)
                    whole(step, "limit", 1, 1, FlowConditions.MAX_LOOKUP, at)
                    needs.tables = true
                }
                "alert" -> {
                    val d = step.optJSONArray("destinations") ?: bad("$at: destinations must be a list of 1-3 owner-approved destination ids")
                    val list = (0 until d.length()).map { (d.opt(it) as? String)?.takeIf { s -> s.length in 1..256 } ?: bad("$at: invalid destination id") }
                    if (list.size !in 1..3) bad("$at: choose 1-3 destinations")
                    destinations += list
                }
            }
            scope?.let { scopes += it }
            if (id != null) ids[id] = type
        }
        return Checked(scopes, BASE_RUN_SECONDS + extra, paid, destinations, sends, needs)
    }

    /**
     * A `send` step: `to` (a connection id the owner approved; never a reference, so nothing a trigger, a model or a
     * reply says can pick it), `data` (up to 32 keys of literals or references), `text`, `photo` (a photo step's
     * result or a kept photo) and `wait_reply`.
     */
    private fun send(step: JSONObject, ids: Map<String, String>, at: String, with: Set<String>): SendUse {
        val to = step.opt("to") as? String
        if (to == null || to.length !in 1..256 || to.any { it.isWhitespace() }) bad("$at: to must be the id of a connection the owner approved (GET /v1/connections lists them)")
        step.opt("data")?.let { d ->
            val o = d as? JSONObject ?: bad("$at: data must be an object {key: value}")
            if (o.length() > Connections.MAX_DATA_KEYS) bad("$at: data has more than ${Connections.MAX_DATA_KEYS} keys")
            for (k in o.keys()) {
                if (!SEGMENT.matches(k)) bad("$at: data key $k must be 1-40 letters, digits, - or _")
                when (val v = o.opt(k)) {
                    JSONObject.NULL, is Boolean, is Number -> Unit
                    is String -> if (v.length > 1024) bad("$at: data.$k is longer than 1024 characters")
                    is JSONObject -> ref(v, ids, "$at.data.$k", with)
                    else -> bad("$at: data.$k must be text, a number, true/false, null or a {\"\$step\"} or {\"\$trigger\"} reference")
                }
            }
            if (o.toString().toByteArray().size > Connections.MAX_DATA_BYTES) bad("$at: data is larger than 16 KiB")
        }
        val photo = step.opt("photo")?.let { p ->
            when (p) {
                is String -> if (!Regex("^kept:[a-z0-9]{1,40}$").matches(p)) bad("$at: photo must be a photo step's result, like {\"\$step\":\"snap\"}, or a kept photo")
                is JSONObject -> {
                    if (p.length() != 1) bad("$at: photo must be alone: {\"\$step\":\"snap\"}")
                    when {
                        p.has("\$step") -> (p.opt("\$step") as? String)?.split('.')?.let { parts ->
                            if (parts.any { !SEGMENT.matches(it) }) bad("$at: photo: invalid step reference")
                            if (parts[0] !in ids) bad("$at: photo names no earlier step with id ${parts[0]}")
                        } ?: bad("$at: photo must be {\"\$step\":\"snap\"}")
                        else -> ref(p, ids, "$at.photo", with)
                    }
                }
                else -> bad("$at: photo must be a photo step's result, like {\"\$step\":\"snap\"}")
            }
            true
        } ?: false
        step.opt("wait_reply")?.let { if (it !is Boolean) bad("$at: wait_reply must be true or false") }
        return SendUse(at, to, step.has("text"), photo)
    }

    /** Names a connection id in the owner's words for the plain sentence; set by the platform (tests may too). */
    @Volatile var connectionWords: (String) -> String? = { null }

    /**
     * A `record` step: `table` (a table id) and `row`, 1-16 columns → fixed values or references. With `for_each`
     * (a `$step` reference to a list), one row per element: `{"$item":"<path>"}` reads the element.
     */
    private fun record(step: JSONObject, ids: Map<String, String>, at: String, with: Set<String>) {
        tableId(step, at)
        val each = step.opt("for_each")?.let { f ->
            val o = f as? JSONObject
            if (o == null || o.length() != 1 || !o.has("\$step")) bad("$at: for_each must be a list from an earlier step, like {\"\$step\":\"look.items\"}")
            stepPath(o.opt("\$step"), ids, "$at.for_each"); true
        } ?: false
        columns(step, "row", ids, at, with, each)
    }

    /** An `update` step: `table`, `row` (the row id: a reference or text) and `set`, 1-16 columns like record's row. */
    private fun update(step: JSONObject, ids: Map<String, String>, at: String, with: Set<String>) {
        tableId(step, at)
        when (val r = step.opt("row")) {
            is String -> if (!Regex("^[a-z0-9]{1,40}$").matches(r)) bad("$at: row must be a row id or a reference, like {\"\$trigger\":\"with.row\"}")
            is JSONObject -> ref(r, ids, "$at.row", with)
            else -> bad("$at: row is required: the row's id, like {\"\$trigger\":\"with.row\"}")
        }
        columns(step, "set", ids, at, with, false)
    }

    private fun tableId(step: JSONObject, at: String) {
        val table = step.opt("table") as? String
        if (table == null || !ID.matches(table)) bad("$at: table must be a table id (1-32 lowercase letters, digits or _, starting with a letter)")
    }

    private fun columns(step: JSONObject, key: String, ids: Map<String, String>, at: String, with: Set<String>, item: Boolean) {
        val row = step.opt(key) as? JSONObject ?: bad("$at: $key must be an object {column: value}")
        if (row.length() !in 1..16) bad("$at: $key must set 1-16 columns")
        for (c in row.keys()) {
            if (!ID.matches(c)) bad("$at: column $c must be 1-32 lowercase letters, digits or _ and start with a letter")
            when (val v = row.opt(c)) {
                JSONObject.NULL, is Boolean, is Number -> Unit
                is String -> if (v.length > 1024) bad("$at: $key.$c is longer than 1024 characters")
                is JSONObject -> if (v.has("\$item")) {
                    if (!item) bad("$at: $key.$c: {\"\$item\"} goes with for_each")
                    val p = v.opt("\$item") as? String
                    if (v.length() != 1 || p == null || p.split('.').any { !SEGMENT.matches(it) }) bad("$at: $key.$c: {\"\$item\":\"<field>\"} names a field of each element, like \"label\"")
                } else ref(v, ids, "$at.$key.$c", with)
                else -> bad("$at: $key.$c must be text, a number, true/false, null or a {\"\$step\"} or {\"\$trigger\"} reference")
            }
        }
    }

    /** The run time limit of a workflow [definition], counting its waits; a called flow's `with` names are its own. */
    fun runLimitSeconds(definition: JSONObject): Int {
        val trigger = definition.optJSONObject("trigger")
        val with = if (trigger?.optString("type") == "called") runCatching { withDeclared(trigger.opt("with")) }.getOrDefault(emptySet()) else emptySet()
        return runCatching { check(definition.getJSONArray("steps"), with).runSeconds }.getOrDefault(BASE_RUN_SECONDS)
    }

    data class CookingTiming(val sessionS: Int, val tipGapS: Int)
    fun cookingTiming(step: JSONObject, at: String = "kitchen") = CookingTiming(
        whole(step, "session_s", 3600, 10, 3600, at),
        whole(step, "tip_gap_s", 45, 10, 3600, at)
    )

    private fun whole(o: JSONObject, key: String, default: Int, min: Int, max: Int, at: String): Int {
        val v = o.opt(key) ?: return default
        if (v !is Int || v !in min..max) bad("$at: $key must be a whole number from $min to $max")
        return v
    }

    private fun questions(v: Any?, at: String) {
        val q = v as? JSONObject ?: bad("$at: questions must be an object {id: {type, instructions, criteria?}}")
        if (q.length() !in 1..cc.bridgit.app.watch.WatchRules.MAX_QUESTIONS) bad("$at: questions must hold 1-${cc.bridgit.app.watch.WatchRules.MAX_QUESTIONS} questions")
        for (k in q.keys()) {
            if (!SEGMENT.matches(k)) bad("$at: question id $k must be 1-40 letters, digits, - or _")
            val one = q.opt(k) as? JSONObject ?: bad("$at: questions.$k must be an object {type, instructions, criteria?}")
            if ((one.opt("type") as? String).isNullOrBlank()) bad("$at: questions.$k needs a type (choice, noul, ...)")
            val instr = one.opt("instructions") as? String
            if (instr.isNullOrBlank() || instr.length > cc.bridgit.app.watch.WatchRules.MAX_QUESTION_CHARS) bad("$at: questions.$k needs instructions (at most ${cc.bridgit.app.watch.WatchRules.MAX_QUESTION_CHARS} characters)")
        }
    }

    /** A plain-text field: text, one reference, or a list of texts and references. */
    private fun text(step: JSONObject, field: String, max: Int, required: Boolean, ids: Map<String, String>, at: String, with: Set<String>) {
        val v = step.opt(field) ?: if (required) bad("$at is required") else return
        val parts = when (v) { is JSONArray -> (0 until v.length()).map { v.opt(it) }.also { if (it.size !in 1..20) bad("$at: use 1-20 parts") }; else -> listOf(v) }
        var literal = 0
        for (p in parts) when (p) {
            is String -> literal += p.length
            is JSONObject -> ref(p, ids, at, with)
            else -> bad("$at must be text, a {\"\$step\"} or {\"\$trigger\"} reference, or a list of them")
        }
        if (literal > max) bad("$at is longer than $max characters")
    }

    private fun ref(o: JSONObject, ids: Map<String, String>, at: String, with: Set<String>) {
        if (o.length() != 1) bad("$at: a reference must be alone: {\"\$step\":\"id.path\"} or {\"\$trigger\":\"path\"}")
        when {
            o.has("\$step") -> stepPath(o.opt("\$step"), ids, at)
            o.has("\$trigger") -> triggerPath(o.opt("\$trigger"), at, with)
            else -> bad("$at: unknown reference ${o.keys().next()}")
        }
    }

    private fun stepPath(v: Any?, ids: Map<String, String>, at: String) {
        val path = v as? String ?: bad("$at: a step reference is \"<step id>.<field>\"")
        val parts = path.split('.')
        if (parts.size < 2 || parts.any { !SEGMENT.matches(it) }) bad("$at: a step reference is \"<step id>.<field>\", like \"look.answers.eating\"")
        if (parts[0] !in ids) bad("$at: $path names no earlier step with id ${parts[0]}")
    }

    private fun triggerPath(v: Any?, at: String, with: Set<String>) {
        val path = v as? String ?: bad("$at: a trigger reference is a field path, like \"message\"")
        val parts = path.split('.')
        if (parts.any { !SEGMENT.matches(it) }) bad("$at: invalid trigger path $path")
        // with.<name> is a value a caller hands over: only the names the called trigger declares.
        if (parts[0] == "with" && (parts.size < 2 || parts[1] !in with))
            bad("$at: $path isn't declared: a called trigger lists its values, like {\"type\":\"called\",\"with\":{\"${parts.getOrElse(1) { "note" }}\":\"text\"}}")
    }

    private fun condition(v: Any, ids: Map<String, String>, at: String, with: Set<String>, flow: Boolean): FlowConditions.Needs =
        FlowConditions.check(v, at, flow, { source, value, where -> if (source == "step") stepPath(value, ids, where) else triggerPath(value, where, with) }, ::bad)

    /** A flow-level `only_if` (checked before the first step): it may read the trigger, never a step. */
    fun flowCondition(v: Any, with: Set<String>): FlowConditions.Needs = condition(v, emptyMap(), "flow", with, flow = true)

    /** A called trigger's `with`: 0-8 names, each text, number or boolean. Returns the names. */
    fun withDeclared(v: Any?, at: String = "trigger.with"): Set<String> {
        if (v == null) return emptySet()
        val o = v as? JSONObject ?: bad("$at must be an object like {\"note\":\"text\"}")
        if (o.length() > MAX_WITH) bad("$at: at most $MAX_WITH values")
        for (k in o.keys()) {
            if (!ID.matches(k)) bad("$at: $k must be 1-32 lowercase letters, digits or _ and start with a letter")
            if (o.opt(k) !in WITH_TYPES) bad("$at.$k must be one of ${WITH_TYPES.joinToString()}")
        }
        return o.keys().asSequence().toSet()
    }

    /**
     * The values a caller hands a called flow, checked against [declared] (the trigger's `with`). A name the flow
     * doesn't take, or a value of the wrong type, is refused with code bad_with and nothing runs. Missing names
     * are null.
     */
    fun withValues(declared: JSONObject?, given: JSONObject?): JSONObject {
        val out = JSONObject()
        val types = declared ?: JSONObject()
        given?.keys()?.forEach { k -> if (!types.has(k)) throw ApiError(422, "bad_with", "with.$k isn't a value this flow takes${if (types.length() == 0) " (it takes none)" else " (${types.keys().asSequence().joinToString()})"}") }
        for (k in types.keys()) {
            val v = given?.opt(k)?.takeIf { it != JSONObject.NULL }
            if (v == null) { out.put(k, JSONObject.NULL); continue }
            when (types.optString(k)) {
                "text" -> { val t = v as? String ?: throw ApiError(422, "bad_with", "with.$k must be text"); if (t.length > MAX_WITH_TEXT) throw ApiError(422, "bad_with", "with.$k is longer than $MAX_WITH_TEXT characters"); out.put(k, t) }
                "number" -> out.put(k, (v as? Number) ?: throw ApiError(422, "bad_with", "with.$k must be a number"))
                "boolean" -> out.put(k, (v as? Boolean) ?: throw ApiError(422, "bad_with", "with.$k must be true or false"))
            }
        }
        if (out.toString().length > 4096 + 1024) throw ApiError(422, "bad_with", "with is larger than 4 KB")
        return out
    }

    /**
     * One step run on its own (`do.step` in a rule, a schedule action or a card button). Only [INLINE] steps: the
     * others need a flow around them. No `id` or `only_if` (one step has nothing to name, and its caller has its own
     * condition). Text may use `$trigger` references when [triggerRefs]. Returns the step's scope.
     */
    fun checkInline(step: Any?, triggerRefs: Boolean = true): String? {
        val s = step as? JSONObject ?: bad("step must be one step from the flow menu, like {\"type\":\"notice\",\"title\":\"…\"}")
        val type = s.opt("type") as? String ?: bad("step needs a type: ${INLINE.joinToString()}")
        if (type !in INLINE) {
            if (type == "share") bad("step: share needs the owner present: use it on an installed applet's card button (do.step) or in a flow with a button trigger. On its own here a step may be ${INLINE.joinToString()}")
            if (type in STEPS) bad("step: $type needs a flow: put it in processing.flows (or propose a workflow) and use do.flow. On its own a step may be ${INLINE.joinToString()}")
            bad("step: unknown step type $type. On its own a step may be ${INLINE.joinToString()}")
        }
        for (k in listOf("id", "only_if")) if (s.has(k)) bad("step: $k doesn't go with a single step (use a flow)")
        if (!triggerRefs) refsIn(s)?.let { bad("step: $it references don't go here") }
        check(JSONArray().put(s))
        return scopeOf(type)
    }

    private fun refsIn(v: Any?): String? = when (v) {
        is JSONObject -> if (v.has("\$trigger")) "\$trigger" else v.keys().asSequence().firstNotNullOfOrNull { refsIn(v.opt(it)) }
        is JSONArray -> (0 until v.length()).firstNotNullOfOrNull { refsIn(v.opt(it)) }
        else -> null
    }

    /**
     * The endpoint call a single [step] makes, with its text filled from [trigger]: a notice is POST /v1/interactions,
     * speak is POST /v1/speaker/say. Run as the caller's own agent, through the same checks as a raw call.
     */
    fun inlineCall(step: JSONObject, trigger: JSONObject, fallbackTitle: String = "Bridgit"): JSONObject {
        fun text(field: String, max: Int) = textOf(step.opt(field), emptyMap(), trigger, max)
        return when (step.getString("type")) {
            "notice" -> JSONObject().put("method", "POST").put("path", "/v1/interactions").put("body", JSONObject().put("kind", "notice")
                .put("title", text("title", 80).ifBlank { fallbackTitle.take(80) }).apply { text("text", 2000).takeIf { it.isNotBlank() }?.let { put("text", it) } }
                .put("priority", step.optString("priority", "normal")))
            "speak" -> JSONObject().put("method", "POST").put("path", "/v1/speech/speak").put("body", JSONObject().put("text", text("text", 600)).put("allow_cloud", step.optBoolean("allow_cloud",false)))
            "send" -> JSONObject().put("method", "POST").put("path", "/v1/connections/send").put("body", JSONObject().put("to", step.optString("to"))
                .apply {
                    step.optJSONObject("data")?.let { d -> put("data", JSONObject().apply { for (k in d.keys()) put(k, fillValue(d.opt(k), emptyMap(), trigger) ?: JSONObject.NULL) }) }
                    text("text", Connections.MAX_TEXT).takeIf { it.isNotEmpty() }?.let { put("text", it) }
                    (fillValue(step.opt("photo"), emptyMap(), trigger) as? String)?.takeIf { it.startsWith("kept:") }?.let { put("photo", it) }
                    if (step.optBoolean("wait_reply")) put("wait_reply", true)
                })
            "set_mode" -> JSONObject().put("method", "POST").put("path", "/v1/mode").put("body", JSONObject().put("mode", step.optString("mode")))
            else -> throw ApiError(422, "invalid_workflow", "step: ${step.optString("type")} can't run on its own")
        }
    }

    /** The endpoint a single step calls, for deriving permissions ("POST /v1/interactions"). */
    fun inlineEndpoint(type: String): String? = when (type) { "notice" -> "POST /v1/interactions"; "speak" -> "POST /v1/speaker/say"; "send" -> "POST /v1/connections/send"; "set_mode" -> "POST /v1/mode"; else -> null }

    /** A data value with its reference filled: literals stay, a reference becomes its value (null when it has none). */
    fun fillValue(v: Any?, results: Map<String, JSONObject>, trigger: JSONObject): Any? = when {
        v is JSONObject && v.has("\$step") -> value(v.getString("\$step"), results, true, trigger)
        v is JSONObject && v.has("\$trigger") -> value(v.getString("\$trigger"), results, false, trigger)
        else -> v
    }

    /** A single step in plain words: "show you a notice "No meal seen yet"". */
    fun inlineWords(step: JSONObject): String = when (step.optString("type")) {
        "notice" -> "shows you ${if (step.optString("priority") == "urgent") "an urgent" else "a"} notice ${quoted(step.opt("title"))}"
        "speak" -> "says ${quoted(step.opt("text"))}"
        "send" -> stepWords(step).replaceFirst("send ", "sends ")
        "context" -> "reads Automated Context"
        "set_mode" -> "sets the phone's mode to ${step.optString("mode")}"
        else -> stepWords(step)
    }

    /**
     * The raw call a stored rule or schedule makes, as the single step that does the same, when there is one
     * (a hint for agents: never applied by itself). [call] is {method, path, body}.
     */
    fun stepHint(call: JSONObject): JSONObject? {
        val path = call.optString("path").let { if (it.startsWith("/v1/")) it else "/v1/" + it.trimStart('/') }.trimEnd('/')
        if (call.optString("method", "POST").uppercase() != "POST") return null
        val body = call.optJSONObject("body") ?: JSONObject()
        fun t(v: Any?): Any? = (v as? String)?.let { s -> if ("{message}" in s) JSONArray(s.split("{message}").flatMapIndexed { i, part -> listOfNotNull(if (i > 0) JSONObject().put("\$trigger", "message") else null, part.takeIf { it.isNotEmpty() }) }) else s }
        return when (path) {
            "/v1/interactions" -> if (body.optString("kind", "question") != "notice" || body.has("controls") || body.has("image")) null else
                JSONObject().put("step", JSONObject().put("type", "notice").put("title", t(body.opt("title")) ?: "Notice").apply { t(body.opt("text"))?.let { put("text", it) } }
                    .apply { if (body.optString("priority") == "urgent") put("priority", "urgent") })
            "/v1/speaker/say", "/v1/speech/speak" -> body.opt("text")?.let { JSONObject().put("step", JSONObject().put("type", "speak").put("text", t(it))) }
            else -> null
        }
    }

    // ---- running ------------------------------------------------------------------------------------------------

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

    /** What a reference stands for: [results] holds only steps that ran ok. */
    fun value(path: String, results: Map<String, JSONObject>, step: Boolean, trigger: JSONObject): Any? =
        if (step) path.split('.').let { lookup(results[it[0]], it.drop(1)) } else lookup(trigger, path.split('.'))

    /** A plain-text field's final text (references become text; at most [max] characters). */
    fun textOf(v: Any?, results: Map<String, JSONObject>, trigger: JSONObject, max: Int = 4096): String {
        val parts = if (v is JSONArray) (0 until v.length()).map { v.opt(it) } else listOf(v)
        return parts.joinToString("") { p ->
            when (p) {
                null, JSONObject.NULL -> ""
                is String -> p
                is JSONObject -> when {
                    p.has("\$step") -> show(value(p.getString("\$step"), results, true, trigger))
                    p.has("\$trigger") -> show(value(p.getString("\$trigger"), results, false, trigger))
                    else -> ""
                }
                else -> p.toString()
            }
        }.take(max)
    }

    private fun show(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else (Math.round(v * 100) / 100.0).toString()
        is Float -> show(v.toDouble())
        else -> v.toString()
    }

    /** Whether a step's (or the flow's) only_if holds. A step that was skipped or failed gives no value. */
    fun holds(c: JSONObject, results: Map<String, JSONObject>, trigger: JSONObject, facts: FlowConditions.Facts = FlowConditions.Clock(java.time.ZonedDateTime.now())): Boolean =
        FlowConditions.holds(c, facts) { source, path -> value(path, results, source == "step", trigger) }

    /**
     * A model answer as flow results: `answers.<q>` is the choice (text) for a choice question, the likelihood
     * 0-1 for a noul question, the answer object otherwise; `confidence.<q>` for choices.
     */
    fun answers(answer: Jev.Answer): JSONObject {
        val answers = JSONObject(); val confidence = JSONObject()
        for (q in answer.answers.keys()) {
            val c = answer.choice(q)
            when {
                c != null -> { answers.put(q, c.first); confidence.put(q, c.second) }
                answer.noul(q) != null -> answers.put(q, answer.noul(q))
                else -> answers.put(q, answer.answers.opt(q))
            }
        }
        return JSONObject().put("answers", answers).put("confidence", confidence).put("model", answer.model).put("cost_usd", answer.costUsd)
    }

    // ---- plain words ------------------------------------------------------------------------------------------

    /** ", at most once an hour, at most 10 times a day" for a flow's cooldown_s (or its old alias) and max_runs_per_day. */
    fun limitWords(definition: JSONObject): String {
        val parts = mutableListOf<String>()
        val c = WorkflowDefinition.cooldownOf(definition)
        if (c > 0) parts += "at most " + when (c) { 60 -> "once a minute"; 3600 -> "once an hour"; 86400 -> "once a day"; else -> "once every ${minutes(c)}" } +
            if (WorkflowDefinition.perEvent(definition)) " per device and kind of event" else ""
        if (definition.has("max_runs_per_day")) definition.optInt("max_runs_per_day").let { n -> parts += if (n == 1) "at most once a day" else "at most $n times a day" }
        return if (parts.isEmpty()) "" else ", " + parts.distinct().joinToString(", ")
    }

    private fun minutes(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} hour${if (seconds == 3600) "" else "s"}"
        seconds % 60 == 0 -> "${seconds / 60} minute${if (seconds == 60) "" else "s"}"
        else -> "$seconds seconds"
    }

    private fun quoted(v: Any?): String = when (v) {
        is String -> "\"${v.take(80)}\""
        is JSONArray -> quoted((0 until v.length()).joinToString("") { (v.opt(it) as? String) ?: "…" })
        is JSONObject -> "\"…\""
        else -> "a message"
    }

    private fun whenWords(t: JSONObject?): String = when (t?.optString("type")) {
        "button" -> "When you tap its button"
        "interval" -> "Every ${minutes(t.optInt("seconds", 900))}"
        "charging" -> "When the phone starts charging"
        "power" -> cc.bridgit.app.power.PowerRules.words(t.optString("on"), t.optInt("for_s", 0))
        "battery" -> when (t.optString("on")) {
            "low" -> "When the battery gets low (${cc.bridgit.app.power.BatteryRules.LOW}%)"
            "critical" -> "When the battery is nearly empty (${cc.bridgit.app.power.BatteryRules.CRITICAL}%)"
            "okay" -> "When the battery is back above ${cc.bridgit.app.power.BatteryRules.OKAY}%"
            "full" -> "When the battery is full"
            "hot" -> "When the battery gets hot (${cc.bridgit.app.power.BatteryRules.HOT_C.toInt()} °C)"
            "cooled" -> "When the battery has cooled down"
            "saver_on" -> "When battery saver turns on"
            "saver_off" -> "When battery saver turns off"
            else -> "When the battery changes"
        }
        "sun" -> cc.bridgit.app.power.SunTimes.words(t.optString("on"), t.optInt("offset_min", 0)) +
            if (t.has("latitude")) "" else " where the phone is"
        "geofence" -> "When the phone arrives within ${t.optInt("radius_m", 200)} m of a place"
        "applet.event", "application.event" -> "When ${t.optString("applet_id", t.optString("application_id", "an applet")).ifEmpty { "an applet" }} sends an event"
        "watch" -> if (t.optString("on", "match") == "rule") "When its watch's rule \"${t.optString("rule")}\" fires" else "When its watch sees a match"
        "called" -> "When it's started"
        "mode" -> t.optString("to").ifEmpty { null }?.let { "When the phone's mode changes to $it" } ?: "When the phone's mode changes"
        else -> "When it's started"
    }

    /** A step's only_if as words: "if you choose \"Yes\"", or for the newer sources "only between 22:00 and 07:00". */
    private fun conditionWords(c: JSONObject, types: Map<String, String>): String =
        if (c.has("step") || c.has("trigger")) testWords(c, types) else "only " + FlowConditions.words(c) { testWords(it, types) }

    private fun testWords(c: JSONObject, types: Map<String, String>): String {
        val path = c.optString("step").ifEmpty { "trigger." + c.optString("trigger") }
        val parts = path.split('.')
        val type = types[parts[0]]
        val subject = when {
            type == "ask_owner" && parts.getOrNull(1) == "choice" -> "you"
            type == "ask_owner" && parts.getOrNull(1) == "timed_out" -> null
            type == "ask_model" && parts.getOrNull(1) == "answers" && parts.size > 2 -> "the answer to \"${parts[2]}\""
            parts[0] == "trigger" -> "the watch's ${parts.drop(1).joinToString(" ")}"
            else -> "${parts[0]}'s ${parts.drop(1).joinToString(" ")}"
        }
        if (subject == null) return if (c.optBoolean("exists") == true || c.opt("is") == true) "if you don't answer" else "if you answer"
        if (subject == "you") return when {
            c.has("is") -> "if you choose \"${c.opt("is")}\""
            c.has("is_not") -> "unless you choose \"${c.opt("is_not")}\""
            c.has("in") -> "if you choose one of ${c.getJSONArray("in").join(", ")}"
            c.has("exists") -> if (c.getBoolean("exists")) "if you answer" else "if you don't answer"
            else -> "depending on your answer"
        }
        return when {
            c.has("is") -> "if $subject is ${show(c.opt("is"))}"
            c.has("is_not") -> "if $subject isn't ${show(c.opt("is_not"))}"
            c.has("in") -> "if $subject is one of ${c.getJSONArray("in").join(", ")}"
            c.has("at_least") -> "if $subject is at least ${show(c.opt("at_least"))}"
            c.has("below") -> "if $subject is below ${show(c.opt("below"))}"
            c.has("exists") -> if (c.getBoolean("exists")) "if there is $subject" else "if there is no $subject"
            else -> "if a condition holds"
        }
    }

    private fun stepWords(s: JSONObject, table: (String) -> String = { "\"${it.take(40)}\"" }): String = when (s.optString("type")) {
        "photo" -> "take a photo with the " + when (val c = s.optString("camera", "rear")) { "rear", "front" -> "$c camera"; else -> "local camera" } + (if (s.optBoolean("keep")) " and keep it" else "")
        "ask_model" -> {
            val q = s.optJSONObject("questions"); val first = q?.keys()?.asSequence()?.firstOrNull()
            val instr = first?.let { q.optJSONObject(it)?.optString("instructions") }.orEmpty()
            "ask ${when (s.optString("model")) { "clef" -> "Clef"; "glm" -> "GLM 5.3 Flash"; else -> "Jev" }} \"${instr.take(80)}\"" + (q?.length()?.takeIf { it > 1 }?.let { " and ${it - 1} more question${if (it > 2) "s" else ""}" } ?: "")
        }
        "ask_owner" -> "ask you ${quoted(s.opt("title"))} (waits up to ${minutes(s.optInt("wait_s", 300))})"
        "wait" -> "wait ${minutes(s.optInt("seconds", 1))}"
        "stop" -> "stop"
        "notice" -> "show you ${if (s.optString("priority") == "urgent") "an urgent notice " else ""}${quoted(s.opt("title"))}"
        "speak" -> "say ${quoted(s.opt("text"))}"
        "ring" -> "ring for ${s.optInt("seconds", 10)} seconds"
        "alert" -> s.optJSONArray("destinations")?.length().let { n -> "alert ${n ?: 1} contact${if (n == 1) "" else "s"}" }
        "event" -> "tell its agent"
        "record" -> if (s.has("for_each")) "save a row in ${table(s.optString("table"))} for each item (at most $MAX_FOR_EACH)" else "save a row in ${table(s.optString("table"))}"
        "kitchen" -> "read a kitchen photo with GLM 5.3 Flash"
        "appraise" -> "send a kept photo to Jev to list the things in it with prices"
        "update" -> "change a row in ${table(s.optString("table"))}"
        "share" -> cc.bridgit.app.applets.AppletExport.words(s)
        "send" -> {
            val to = s.optString("to").let { id -> connectionWords(id) ?: "a connection ($id)" }
            val what = listOfNotNull("the photo".takeIf { s.has("photo") }, s.opt("text")?.let { quoted(it) }, "its data".takeIf { !s.has("text") && s.has("data") }).joinToString(" and ").ifEmpty { "a message" }
            "send $what to $to" + if (s.optBoolean("wait_reply")) " and read the reply" else ""
        }
        "context" -> "read Automated Context"
        "set_mode" -> "set the phone's mode to ${s.optString("mode")}"
        "lookup" -> s.optInt("limit", 1).let { n -> "look up ${if (n == 1) "the newest row" else "the newest $n rows"} of ${FlowConditions.rowsWords(s)}" }
        else -> s.optString("type")
    } + when (s.optString("on_error")) { "skip" -> " (carries on if this fails)"; "retry" -> " (tries again if this fails)"; else -> "" }

    /** A flow-level only_if in words, without "only": "between 22:00 and 07:00 and when the mode is away". */
    fun onlyWords(c: JSONObject): String = FlowConditions.words(c) { testWords(it, emptyMap()) }

    /** Explain the deciding observation without copying arbitrary payloads from events, tables or services into UI text. */
    internal fun skipReason(c: JSONObject, types: Map<String, String>, results: Map<String, JSONObject>, trigger: JSONObject): String {
        val required = conditionWords(c, types)
        val path = c.opt("step") as? String
        val source = path?.substringBefore('.')
        val detail = when {
            path == null || source == null -> null
            source !in results -> "The earlier step returned no result."
            types[source] == "ask_model" && path.substringAfter('.').startsWith("answers.") ||
                types[source] == "ask_owner" && path.substringAfter('.') == "choice" -> {
                when (val actual = value(path, results, true, trigger)) {
                    null, JSONObject.NULL -> "No answer was available."
                    is String, is Boolean, is Number -> "The answer was “${show(actual).take(80)}”."
                    else -> null
                }
            }
            else -> null
        }
        return listOfNotNull("Runs $required.", detail).joinToString(" ")
    }

    /**
     * The applet review's lines for a workflow (design pass, docs/design/applet-install.md): when it starts, then each
     * step as its own sentence, in order, with [table] naming a table by its label. Nothing is cut short.
     */
    fun reviewLines(definition: JSONObject, starters: String?, table: (String) -> String): Pair<String, List<String>> {
        val steps = definition.optJSONArray("steps") ?: JSONArray()
        val types = mutableMapOf<String, String>()
        val lines = (0 until steps.length()).mapNotNull { i ->
            val s = hostStep(steps.optJSONObject(i) ?: return@mapNotNull null)
            val cond = s.optJSONObject("only_if")?.let { conditionWords(it, types) }
            val w = when (s.optString("type")) {
                "appraise" -> "sends the kept photo off this device to Jev, a paid model, which lists the things in it with prices"
                "ask_model" -> stepWords(s, table).replaceFirst(Regex("^ask (Clef|Jev) "), "asks $1, a paid model (what it looks at leaves the device): ")
                else -> thirdPerson(stepWords(s, table))
            }
            s.optString("id").takeIf { it.isNotEmpty() }?.let { types[it] = s.optString("type") }
            ((cond?.let { "$it, it " } ?: "") + w).replaceFirstChar { it.uppercase() }.let { if (it.endsWith(".") || it.endsWith(".\"")) it else "$it." }
        }
        val only = definition.optJSONObject("only_if")?.let { ", only " + onlyWords(it) } ?: ""
        val start = "${starters ?: whenWords(definition.optJSONObject("trigger"))}${limitWords(definition)}$only."
        return start to lines
    }

    private val VERBS = mapOf("send" to "sends", "save" to "saves", "change" to "updates", "take" to "takes", "show" to "shows", "wait" to "waits",
        "stop" to "stops", "say" to "says", "ring" to "rings", "alert" to "alerts", "tell" to "tells", "share" to "shares", "set" to "sets", "look" to "looks", "ask" to "asks")

    /** "save a row in Items" → "saves a row in Items", for the review's step sentences. */
    private fun thirdPerson(w: String): String = w.substringBefore(' ').let { v -> VERBS[v]?.let { it + w.removePrefix(v) } ?: w }

    /** The whole workflow as one plain sentence, for the owner's approval and the applet review. */
    fun summary(definition: JSONObject, starters: String? = null): String {
        val steps = definition.optJSONArray("steps") ?: JSONArray()
        val types = mutableMapOf<String, String>()
        val words = (0 until steps.length()).mapNotNull { i ->
            val s = hostStep(steps.optJSONObject(i) ?: return@mapNotNull null)
            val w = (s.optJSONObject("only_if")?.let { conditionWords(it, types) + " " } ?: "") + stepWords(s)
            s.optString("id").takeIf { it.isNotEmpty() }?.let { types[it] = s.optString("type") }
            w
        }
        val only = definition.optJSONObject("only_if")?.let { ", only " + onlyWords(it) } ?: ""
        return "${starters ?: whenWords(definition.optJSONObject("trigger"))}${limitWords(definition)}$only: ${words.joinToString(", ")}.".take(1000)
    }
}
