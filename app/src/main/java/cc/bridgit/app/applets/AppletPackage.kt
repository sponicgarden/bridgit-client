package cc.bridgit.app.applets

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * An installable applet package (4 Nov 2026): one `bridgit-template/1` definition (the template registry's own
 * format) whose runtime adapter is [ADAPTER]. It names typed blanks (`config_schema` + `defaults`), and the
 * parts the phone builds from them: watches, a schedule, a workflow and a card (`processing` and `ui.card`).
 *
 * Blanks are filled by whole-value substitution only: a `{"$input":"name"}` node is replaced by that blank's
 * checked value, and `{"$watch":"id"}` (card data and the workflow) by the id of a watch the install created. Nothing is
 * spliced into text, so a value can never change the shape of a request, a URL or the card's HTML.
 *
 * Pure: no Android, no storage. [compile] turns a package plus filled blanks into a [Plan] the installer runs.
 */
object AppletPackage {
    const val FORMAT = "bridgit-template/1"
    /** Packages the phone builds from watches, schedule, workflow and card. */
    const val ADAPTER = "android.package.v1"
    const val PORTABLE_ADAPTER = "bridgit.package.v1"
    fun adapterFamily(adapter: String) = if (adapter == PORTABLE_ADAPTER) ADAPTER else adapter
    /** The native Pet monitor: installing it sets and starts the built-in monitor. */
    const val DOG_ADAPTER = "android.dog.v1"
    /** 128 KB per package and 48 KB per asset (raised from 64 and 16 KB for cards that draw and analyse, such as Exercise check). */
    const val MAX_BYTES = 131072
    const val MAX_WATCHES = 5
    const val MAX_MEDIA_BYTES = 4194304
    data class Media(val size: Int, val digest: String)

    fun detachedMedia(definition: JSONObject): Map<String, Media> {
        val assets = definition.optJSONObject("assets") ?: return emptyMap()
        return buildMap {
            for (name in assets.keys()) {
                val descriptor = assets.optJSONObject(name) ?: continue
                if (!descriptor.has("storage")) continue
                val size = descriptor.opt("size") as? Number
                val digest = descriptor.opt("sha256") as? String
                if (!name.matches(Regex("[a-z][a-z0-9_.-]{0,79}")) || descriptor.length() != 4 ||
                    descriptor.opt("storage") != "revision" || descriptor.opt("media_type") != "image/jpeg" ||
                    size == null || size.toDouble() != size.toInt().toDouble() || size.toInt() !in 4..MAX_MEDIA_BYTES ||
                    digest == null || !digest.matches(Regex("[a-f0-9]{64}")))
                    throw Bad("assets.$name must be a revision JPEG with byte size 4–4194304 and a lowercase SHA-256.")
                put(name, Media(size.toInt(), digest))
            }
        }
    }

    const val MAX_ASSET_BYTES = 49152
    /** Clef's price for one photo, for the review screen's monthly worst case (website recipes use the same). */
    const val CLEF_USD_PER_PHOTO = 0.000105

    class Bad(message: String) : Exception(message)

    private val ID = Regex("^[a-z][a-z0-9-]{1,63}$")
    private val VERSION = Regex("^(0|[1-9]\\d{0,4})\\.(0|[1-9]\\d{0,4})\\.(0|[1-9]\\d{0,4})$")
    private val BLANK = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val COMPONENT = Regex("^[a-z][a-z0-9_-]{0,31}$")
    private val SCHEMA_KEYWORDS = setOf("type", "properties", "required", "additionalProperties", "items", "enum", "minimum", "maximum",
        "minLength", "maxLength", "minItems", "maxItems", "format")
    private val TYPES = setOf("object", "array", "string", "number", "integer", "boolean", "null")
    private val PROCESSING_KEYS = setOf("watches", "schedule", "workflow", "flows", "native", "tables")
    /** Flows per package (docs/applet-vocabulary.md): inside the per-principal cap of 8 workflows even mid-update. */
    const val MAX_FLOWS = 4
    private val FLOW_ID = Regex("^[a-z][a-z0-9_]{0,23}$")
    /** The flow `processing.workflow` stands for. */
    const val MAIN_FLOW = "main"
    const val CARD_MARKER_START = "/*RECIPE*/"
    const val CARD_MARKER_END = "/*END RECIPE*/"

    /** A checked package: the definition as sent, its content hash, and the parts the installer needs. */
    class Package(val definition: JSONObject, val sha256: String) {
        val id: String get() = definition.getString("id")
        val version: String get() = definition.getString("version")
        val name: String get() = definition.getString("name")
        val summary: String get() = definition.getString("summary")
        val adapter: String get() = definition.getJSONObject("runtime").getString("adapter")
        val configSchema: JSONObject get() = definition.getJSONObject("config_schema")
        val defaults: JSONObject get() = definition.getJSONObject("defaults")
        fun grantsFor(derived: Set<String>): Set<String> {
            val optional = definition.getJSONObject("permissions").optJSONArray("optional")?.let(::strings).orEmpty()
            return declared + optional.filter { it in derived }
        }

        val declared: Set<String> get() = strings(definition.getJSONObject("permissions").getJSONArray("required")).toSet()
        /** The opt-in on-phone vision engines it asks for by name (runtime.engines), e.g. on_device_detector. */
        val engines: List<String> get() = definition.getJSONObject("runtime").optJSONArray("engines")?.let(::strings) ?: emptyList()
        /** The owner-facing label and help for each blank (ui.inputs), when the author gave one. */
        fun inputLabel(name: String): String = definition.optJSONObject("ui")?.optJSONObject("inputs")?.optJSONObject(name)?.optString("label")
            ?.takeIf { it.isNotBlank() } ?: name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        fun inputHelp(name: String): String? = definition.optJSONObject("ui")?.optJSONObject("inputs")?.optJSONObject(name)?.optString("help")?.takeIf { it.isNotBlank() }
    }

    /** What an install creates, with every blank filled. Watch requests are POST /v1/watches bodies. */
    data class Plan(
        val watches: List<Pair<String, JSONObject>>,
        val schedule: JSONObject?,
        /** Package flow name → workflow body (`processing.flows`, or `processing.workflow` as flow main). */
        val flows: List<Pair<String, JSONObject>>,
        val card: Card?,
        val native: JSONObject?,
        val config: JSONObject,
        /** On-phone tables the applet keeps (processing.tables). */
        val tables: List<AppletTables.Table> = emptyList(),
        /** Its web portal is its screen (ui.portal.tables): the card's buttons show on the portal page, not in the Cards inbox. */
        val portalScreen: Boolean = false,
    ) {
        /** Content hash of each part, so an update can keep a part that didn't change (and its history). */
        fun watchDigest(id: String): String? = watches.firstOrNull { it.first == id }?.second?.let(::digest)
    }

    /**
     * The card: its title and either the HTML with the data block filled, or (phase 2) the pieces the phone draws.
     * [piecesRaw] is `ui.card` as written and [piecesFilled] the same with every blank filled; watch ids come at install.
     */
    data class Card(val title: String, val htmlTemplate: String?, val data: JSONObject, val piecesRaw: JSONObject? = null, val piecesFilled: JSONObject? = null,
                    /** Every "METHOD /v1/path" its sources and buttons call (for the derived permissions). */
                    val calls: List<String> = emptyList(),
                    /** A camera button takes photos when tapped (docs/applet-wave2.md B): the applet needs `camera`. */
                    val usesCamera: Boolean = false, val cardCapabilities: Set<String> = emptySet()) {
        val isPieces: Boolean get() = piecesRaw != null
        /** The pieces card once the watches exist ([watchIds]: package watch id → real watch_id), as kind pieces stores it. */
        fun pieces(watchIds: Map<String, String>): JSONObject {
            val filled = resolve(piecesFilled!!, null, watchIds) as JSONObject
            cc.bridgit.app.interact.CardPieces.seal(piecesRaw!!, filled)
            return JSONObject().put("title", title).put("pieces", filled.getJSONArray("pieces"))
                .apply { for (k in listOf("sources", "fresh_s", "states")) filled.opt(k)?.let { put(k, it) } }
        }
        /** The final HTML once the watches exist: [watchIds] maps package watch id → real watch_id. */
        fun html(watchIds: Map<String, String>): String {
            val htmlTemplate = htmlTemplate ?: return ""
            val filled = resolve(data, null, watchIds) as JSONObject
            // JSON in a <script>: "<" escaped so a value can't close the script tag.
            val text = filled.toString().replace("<", "\\u003c")
            val start = htmlTemplate.indexOf(CARD_MARKER_START)
            val end = htmlTemplate.indexOf(CARD_MARKER_END, start + 1)
            if (start < 0 || end < 0) return htmlTemplate
            return htmlTemplate.substring(0, start) + CARD_MARKER_START + text + htmlTemplate.substring(end)
        }
    }

    // ---- reading and checking ---------------------------------------------------------------------------------

    /** Parses and checks [text]. Throws [Bad] with words an author (person or agent) can act on. */
    fun parse(text: String): Package {
        if (text.toByteArray().size > MAX_BYTES) throw Bad("The applet file is larger than ${MAX_BYTES / 1024} KB.")
        val d = runCatching { JSONObject(text) }.getOrElse { throw Bad("The applet file isn't a JSON object.") }
        return check(d)
    }

    /** Classification describes the source of work. Execution authority stays with the installed grants. */
    fun runMode(definition: JSONObject): String {
        val processing = definition.optJSONObject("processing") ?: JSONObject()
        val triggers = flowBodies(processing).map { it.optJSONObject("trigger")?.opt("type") }
        val autonomous = definition.optJSONObject("runtime")?.optString("adapter") == DOG_ADAPTER ||
            (processing.optJSONArray("watches")?.length() ?: 0) > 0 || processing.has("schedule") ||
            triggers.any { it is String && it !in setOf("called", "button") }
        val inferred = when {
            autonomous -> "autonomous"
            triggers.any { it !is String } || processing.has("native") -> "unspecified"
            else -> "passive"
        }
        if (!definition.has("run_mode")) return inferred
        val declared = definition.opt("run_mode")
        if (declared != "autonomous" && declared != "passive") throw Bad("run_mode must be autonomous or passive.")
        if (inferred != "unspecified" && declared != inferred) throw Bad("run_mode conflicts with the package triggers.")
        return declared as String
    }

    fun check(d: JSONObject): Package {
        runMode(d)
        AppletPractices.check(d)
        if (d.optString("format") != FORMAT) throw Bad("format must be \"$FORMAT\" (the template registry's format).")
        val id = d.optString("id"); if (!ID.matches(id)) throw Bad("id must be 2-64 lowercase letters, digits or -, starting with a letter.")
        if (!VERSION.matches(d.optString("version"))) throw Bad("version must be major.minor.patch, like 1.0.0.")
        if (d.optString("kind") != "applet") throw Bad("kind must be \"applet\".")
        for (k in listOf("name", "summary")) {
            val v = d.optString(k); if (v.isBlank() || v.length > 500) throw Bad("$k is required (at most 500 characters).")
        }
        val runtime = d.optJSONObject("runtime") ?: throw Bad("runtime is required.")
        val adapter = runtime.optString("adapter")
        if (adapterFamily(adapter) != ADAPTER && adapter != DOG_ADAPTER) throw Bad("runtime.adapter must be \"$ADAPTER\" (or \"$DOG_ADAPTER\" for the Pet monitor) to install on a phone.")
        if (runtime.opt("supports_dynamic_execution") != false) throw Bad("runtime.supports_dynamic_execution must be false: packages are declarative, never code.")
        runtime.opt("engines")?.let { e ->
            // Names only here (pure); whether this phone has them is checked on the review screen (software manifest).
            if (e !is JSONArray || e.length() > 8 || (0 until e.length()).any { (e.opt(it) as? String)?.let(cc.bridgit.app.vision.SoftwareManifest::isEngineName) != true })
                throw Bad("runtime.engines must list on-phone engines by name, for example on_device_detector (GET /v1/images/engines).")
            if (e.length() > 0 && "find_crop" !in (d.optJSONObject("permissions")?.optJSONArray("required")?.let(::strings) ?: emptyList()))
                throw Bad("runtime.engines are used through /v1/images/find and /v1/images/crop, so permissions.required must list find_crop.")
        }
        AppletBudget.parse(d.opt("budget"))
        AppletActions.checkWatches(d)
        val schema = d.optJSONObject("config_schema") ?: throw Bad("config_schema is required (use an object with no properties for an applet with no blanks).")
        checkSchema(schema, 0, "config_schema")
        if (schema.optString("type") != "object") throw Bad("config_schema must be an object schema.")
        val defaults = d.optJSONObject("defaults") ?: throw Bad("defaults is required.")
        validate(schema, defaults).firstOrNull()?.let { throw Bad("defaults don't fit config_schema: $it") }
        val perms = d.optJSONObject("permissions") ?: throw Bad("permissions is required.")
        if (perms.opt("automatic_grants") != false) throw Bad("permissions.automatic_grants must be false: the owner approves every grant.")
        val required = perms.optJSONArray("required") ?: throw Bad("permissions.required must list every permission the applet uses.")
        if ((0 until required.length()).any { required.opt(it) !is String }) throw Bad("permissions.required must be a list of permission names.")
        if (perms.has("optional")) {
            val optional = perms.optJSONArray("optional") ?: throw Bad("permissions.optional must be a list of permission names.")
            if ((0 until optional.length()).any { optional.opt(it) !is String }) throw Bad("permissions.optional must be a list of permission names.")
        }
        val processing = d.optJSONObject("processing") ?: JSONObject()
        processing.keys().forEach { k -> if (k !in PROCESSING_KEYS && adapterFamily(adapter) == ADAPTER) throw Bad("processing.$k isn't a part a package can have (use ${PROCESSING_KEYS.joinToString()}).") }
        if (adapter == DOG_ADAPTER && processing.has("classifier")) {
            cc.bridgit.app.dog.PetClassifier.parse(processing.optJSONObject("classifier") ?: throw Bad("processing.classifier must be an object."))
        }
        val watches = processing.optJSONArray("watches") ?: JSONArray()
        if (watches.length() > MAX_WATCHES) throw Bad("At most $MAX_WATCHES watches per applet.")
        val seen = HashSet<String>()
        for (i in 0 until watches.length()) {
            val w = watches.optJSONObject(i) ?: throw Bad("processing.watches[$i] must be {id, request}.")
            val wid = w.optString("id")
            if (!COMPONENT.matches(wid) || !seen.add(wid)) throw Bad("processing.watches[$i].id must be a unique short name (letters, digits, - or _).")
            if (w.optJSONObject("request") == null) throw Bad("processing.watches[$i].request must be a POST /v1/watches body.")
        }
        processing.opt("schedule")?.let { if (it !is JSONObject || it.optJSONObject("manifest") == null) throw Bad("processing.schedule must be {manifest} (a POST /v1/schedule body).") }
        processing.opt("workflow")?.let { if (it !is JSONObject) throw Bad("processing.workflow must be a workflow.propose body.") }
        if (processing.has("workflow") && processing.has("flows")) throw Bad("Use processing.workflow (one flow) or processing.flows, not both.")
        val flowList = processing.opt("flows")?.let { f ->
            if (f !is JSONArray || f.length() !in 1..MAX_FLOWS) throw Bad("processing.flows must be a list of 1-$MAX_FLOWS flows, each a workflow.propose body plus id.")
            f
        }
        // Package flow name → (trigger type, the with names it takes): what a {"$flow": …} may name.
        val flows = LinkedHashMap<String, Pair<String, Set<String>>>()
        if (flowList != null) for (i in 0 until flowList.length()) {
            val f = flowList.optJSONObject(i) ?: throw Bad("processing.flows[$i] must be a workflow body plus id.")
            val fid = f.opt("id") as? String
            if (fid == null || !FLOW_ID.matches(fid)) throw Bad("processing.flows[$i].id must be 1-24 lowercase letters, digits or _, starting with a letter.")
            if (flows.containsKey(fid)) throw Bad("processing.flows[$i].id $fid is used twice.")
            flows[fid] = flowInfo(f)
        }
        processing.optJSONObject("workflow")?.let { flows[MAIN_FLOW] = flowInfo(it) }
        for (flow in flowBodies(processing)) {
            val steps = flow.optJSONArray("steps") ?: continue
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.opt("type") != "service") continue
                cc.bridgit.app.platform.FlowSteps.hostStep(step)
                val remote = d.optJSONObject("permissions")?.optJSONArray("remote")
                if (d.optJSONObject("data_policy")?.optJSONObject("device_only")?.opt("remote_models") != true ||
                    remote == null || (0 until remote.length()).none { (remote.opt(it) as? String)?.isNotBlank() == true })
                    throw Bad("A signed vision service requires data_policy.device_only.remote_models=true and a permissions.remote disclosure describing the transfer.")
            }
        }
        processing.opt("native")?.let { if (it !is JSONObject) throw Bad("processing.native must be an object.") }
        if (adapter == DOG_ADAPTER && watches.length() + (if (processing.has("schedule")) 1 else 0) + flows.size > 0)
            throw Bad("The Pet monitor package installs the built-in monitor only; it has no watches, schedule or workflow.")
        val assets = d.optJSONObject("assets") ?: JSONObject()
        if (assets.length() > 16) throw Bad("At most 16 assets per package.")
        val detached = detachedMedia(d)
        for (name in assets.keys()) {
            if (name in detached) continue
            val a = assets.optJSONObject(name) ?: throw Bad("assets.$name must be {media_type, text, sha256}.")
            if (a.has("blob") || a.has("path")) throw Bad("assets.$name must contain its payload.")
            val bytes = if (a.optString("media_type") == "image/png") pngBytes(a) else {
                if (a.has("base64") || a.opt("text") !is String) throw Bad("assets.$name requires text.")
                a.getString("text").toByteArray()
            }
            if (bytes.size > MAX_ASSET_BYTES || a.optString("sha256") != sha256(bytes))
                throw Bad("assets.$name exceeds the size limit or has an incorrect sha256.")
        }
        d.optJSONObject("ui")?.let { ui ->
            if (ui.has("icon") && assets.optJSONObject(ui.optString("icon"))?.optString("media_type") != "image/png")
                throw Bad("ui.icon must name a PNG package asset.")
        }
        cc.bridgit.app.remoteview.PortalRecords.checkDeclaration(d.optJSONObject("ui"))?.let { throw Bad(it) }
        if (cc.bridgit.app.remoteview.PortalRecords.always(d)) {
            if (adapter == DOG_ADAPTER) throw Bad("ui.portal.include \"always\" is for package applets; the Pet monitor's portal is set up on its own screen.")
            if (!portalCapable(d)) throw Bad("ui.portal.include is \"always\", but the applet sends nothing to a portal: add ui.portal.tables, ui.portal.records or a watch with a tally.")
        }
        val tables = try { AppletTables.parse(processing) } catch (e: Bad) { throw e } catch (e: Exception) { throw Bad("processing.tables: ${e.message}") }
        for (f in flowBodies(processing)) { recordSteps(f.optJSONArray("steps"), tables); tableReads(f, tables) }
        d.optJSONObject("ui")?.optJSONObject("card")?.takeIf { cc.bridgit.app.interact.CardPieces.isPieces(it) }?.let { c ->
            if (c.has("asset") || c.has("data")) throw Bad("ui.card has pieces, so it takes no asset or data (a card is either pieces or custom HTML).")
            if (c.optString("title").isBlank()) throw Bad("ui.card.title is required.")
            try { cc.bridgit.app.interact.CardPieces.check(c, cardScope(d, processing, null)) } catch (e: cc.bridgit.app.interact.CardPieces.Bad) { throw Bad("ui.card: ${e.message}") }
        }
        cc.bridgit.app.remoteview.PortalTables.check(d.optJSONObject("ui"), tables.map { it.id }.toSet(),
            cc.bridgit.app.remoteview.PortalTables.buttonIds(d.optJSONObject("ui")?.optJSONObject("card")?.takeIf { cc.bridgit.app.interact.CardPieces.isPieces(it) }),
            tables.associate { it.id to it.maxRows })?.let { throw Bad(it) }
        d.optJSONObject("ui")?.optJSONObject("portal")?.optJSONObject("records")?.let { target ->
            val table = tables.firstOrNull { it.id == target.optString("table") }
            if (table?.columns?.get(target.optString("column"))?.type != "json")
                throw Bad("ui.portal.records must name a declared table and described json column using table and column; durable records may only be stored in processing.tables.")
        }
        d.optJSONObject("ui")?.optJSONObject("card")?.takeIf { !cc.bridgit.app.interact.CardPieces.isPieces(it) }?.let { c ->
            val asset = assets.optJSONObject(c.optString("asset")) ?: throw Bad("ui.card.asset must name an asset in assets (or give the card pieces).")
            if (asset.optString("media_type") != "text/html") throw Bad("ui.card.asset must be text/html.")
            if (c.optString("title").isBlank()) throw Bad("ui.card.title is required.")
            val data = c.opt("data")
            if (data != null && data !is JSONObject) throw Bad("ui.card.data must be an object.")
            val html = asset.getString("text")
            val start = html.indexOf(CARD_MARKER_START)
            if (data != null && (start < 0 || html.indexOf(CARD_MARKER_END, start + CARD_MARKER_START.length) < 0)) throw Bad("ui.card.data needs a $CARD_MARKER_START{}$CARD_MARKER_END block in the card's HTML.")
        }
        // Every $input names a blank, every $watch a watch of this package: caught here, not half-way through an install.
        val blanks = schema.optJSONObject("properties")?.keys()?.asSequence()?.toSet() ?: emptySet()
        val watchIds = (0 until watches.length()).mapNotNull { watches.optJSONObject(it)?.optString("id") }.toSet()
        for (k in processing.keys()) refs(processing.get(k), blanks, if (k == "workflow" || k == "flows") watchIds else emptySet(), "processing.$k",
            if (k == "watches" || k == "schedule") flows else null)
        d.optJSONObject("ui")?.optJSONObject("card")?.optJSONObject("data")?.let { refs(it, blanks, seen, "ui.card.data") }
        d.optJSONObject("ui")?.optJSONObject("card")?.takeIf { cc.bridgit.app.interact.CardPieces.isPieces(it) }?.let { c ->
            for (k in listOf("sources", "pieces", "states")) c.opt(k)?.let { refs(it, blanks, seen, "ui.card.$k", if (k == "pieces") flows else null) }
        }
        if (adapterFamily(adapter) == ADAPTER && watches.length() == 0 && !processing.has("schedule") && flows.isEmpty() && d.optJSONObject("ui")?.optJSONObject("card") == null)
            throw Bad("An applet needs at least one part: a watch, a schedule, a workflow or a card.")
        return Package(d, digest(d))
    }

    /** A flow's trigger type and the `with` names a called trigger declares. */
    private fun flowInfo(f: JSONObject): Pair<String, Set<String>> {
        val t = f.optJSONObject("trigger")
        return (t?.optString("type") ?: "") to (t?.optJSONObject("with")?.keys()?.asSequence()?.toSet() ?: emptySet())
    }

    /**
     * Checks `$input`, `$watch` and `$flow` references. [flows] is null where a `do` can't start a flow (the flows
     * themselves, the card data); otherwise a `do.flow` must be {"$flow": "<package flow id>"} naming a called or button
     * flow, with only the `with` names it declares.
     */
    private fun refs(v: Any?, blanks: Set<String>, watches: Set<String>, at: String, flows: Map<String, Pair<String, Set<String>>>? = null) {
        when (v) {
            is JSONObject -> {
                if (v.has("\$flow")) throw Bad("$at: {\"\$flow\":\"…\"} goes in a do, as {\"flow\": {\"\$flow\": \"…\"}}.")
                // Only a do's flow (or a {"$flow"} value) is a flow ref: a question id or a call body field "flow" is just data.
                if (v.has("flow") && flows != null && (at.endsWith(".do") || (v.opt("flow") as? JSONObject)?.has("\$flow") == true)) {
                    val ref = v.opt("flow")
                    if (ref is String) throw Bad("$at.flow: in a package name the flow as {\"\$flow\": \"$ref\"}.")
                    val name = (ref as? JSONObject)?.takeIf { it.length() == 1 }?.opt("\$flow") as? String
                        ?: throw Bad("$at.flow must be {\"\$flow\": \"<id in processing.flows>\"}.")
                    val info = flows[name] ?: throw Bad("$at.flow: $name isn't a flow in processing.flows${if (flows.isEmpty()) "" else " (${flows.keys.joinToString()})"}.")
                    if (info.first != "called" && info.first != "button") throw Bad("$at.flow: flow $name has a ${info.first} trigger; only a called or button flow can be started by a rule or schedule.")
                    v.optJSONObject("with")?.keys()?.forEach { k -> if (k !in info.second) throw Bad("$at.with.$k: flow $name doesn't take $k${if (info.second.isEmpty()) " (it takes no values)" else " (it takes ${info.second.joinToString()})"}.") }
                    v.keys().forEach { if (it != "flow") refs(v.get(it), blanks, watches, "$at.$it", flows) }
                    return
                }
                if (v.has("\$input")) {
                    if (v.length() != 1 || v.optString("\$input") !in blanks) throw Bad("$at: {\"\$input\":\"…\"} must be alone and name a blank in config_schema.")
                    return
                }
                if (v.has("\$watch")) {
                    if (v.length() != 1 || v.optString("\$watch") !in watches) throw Bad("$at: {\"\$watch\":\"…\"} must be alone and name a watch in processing.watches.")
                    return
                }
                v.keys().forEach { refs(v.get(it), blanks, watches, "$at.$it", flows) }
            }
            is JSONArray -> for (i in 0 until v.length()) refs(v.get(i), blanks, watches, "$at[$i]", flows)
        }
    }

    /** Every `record` step names a table the package declares, and only its columns. */
    private fun recordSteps(steps: JSONArray?, tables: List<AppletTables.Table>) {
        steps ?: return
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i) ?: continue
            val type = s.optString("type")
            if (type != "record" && type != "update") continue
            val t = tables.firstOrNull { it.id == s.optString("table") }
                ?: throw Bad("processing.workflow.steps[$i] ($type): table ${s.optString("table")} isn't in processing.tables.")
            s.optJSONObject(if (type == "update") "set" else "row")?.keys()?.forEach { k -> if (k !in t.columns) throw Bad("processing.workflow.steps[$i] ($type): $k isn't a column of ${t.id} (${t.columns.keys.joinToString()}).") }
        }
    }

    /** Every lookup step and count / sum condition names a table the package declares, and only its columns (D1, D4). */
    private fun tableReads(flow: JSONObject, tables: List<AppletTables.Table>) {
        fun check(o: JSONObject, at: String, sum: Boolean) {
            val t = tables.firstOrNull { it.id == o.optString("table") } ?: throw Bad("$at: table ${o.optString("table")} isn't in processing.tables.")
            o.optJSONObject("where")?.optString("column")?.let { if (it !in t.columns) throw Bad("$at: where.column $it isn't a column of ${t.id} (${t.columns.keys.joinToString()}).") }
            if (sum) o.optString("column").let { c -> if (t.columns[c]?.type !in setOf("number", "integer")) throw Bad("$at: sum.column $c must be a number column of ${t.id}.") }
        }
        fun condition(c: Any?, at: String) {
            val o = c as? JSONObject ?: return
            listOf("all", "any").forEach { g -> o.optJSONArray(g)?.let { a -> for (i in 0 until a.length()) condition(a.opt(i), "$at.$g[$i]") } }
            o.optJSONObject("count")?.let { check(it, "$at.count", false) }
            o.optJSONObject("sum")?.let { check(it, "$at.sum", true) }
        }
        condition(flow.opt("only_if"), "processing.workflow.only_if")
        val steps = flow.optJSONArray("steps") ?: return
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i) ?: continue
            condition(s.opt("only_if"), "processing.workflow.steps[$i].only_if")
            if (s.optString("type") == "lookup") check(s, "processing.workflow.steps[$i] (lookup)", false)
        }
    }

    /** Every workflow body of a package: processing.workflow (the main flow) first, then processing.flows. */
    fun flowBodies(processing: JSONObject): List<JSONObject> = listOfNotNull(processing.optJSONObject("workflow")) +
        (processing.optJSONArray("flows")?.let { f -> (0 until f.length()).mapNotNull { f.optJSONObject(it) } } ?: emptyList())

    /**
     * What a package's pieces card may name: its watches (by `$watch`), its tables, its workflow's steps.
     * [callCap] maps a call to its capability (null: unknown here, checked later on the phone).
     */
    fun cardScope(d: JSONObject, processing: JSONObject = d.optJSONObject("processing") ?: JSONObject(), callCap: ((String, String) -> String?)?): cc.bridgit.app.interact.CardPieces.Scope {
        val watches = processing.optJSONArray("watches")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id") } }?.toSet() ?: emptySet()
        val parsed = runCatching { AppletTables.parse(processing) }.getOrDefault(emptyList())
        val tables = parsed.associate { it.id to it.columns.keys.toSet() }
        // The card's runs source and run_workflow button use the main flow (processing.workflow, or the first of processing.flows).
        val bodies = flowBodies(processing)
        val wf = bodies.firstOrNull()
        val steps = wf?.optJSONArray("steps")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id").isNotEmpty() }.associateBy { it.optString("id") } }
        return cc.bridgit.app.interact.CardPieces.Scope(
            watch = { v -> v is JSONObject && v.length() == 1 && v.optString("\$watch") in watches },
            tables = tables, steps = if (wf != null) steps ?: emptyMap() else null,
            buttonWorkflow = wf?.optJSONObject("trigger")?.optString("type") == "button",
            callCap = callCap ?: { _, _ -> null }, packaged = true,
            flows = LinkedHashMap<String, Pair<String, Set<String>>>().apply {
                processing.optJSONObject("workflow")?.let { put(MAIN_FLOW, flowInfo(it)) }
                processing.optJSONArray("flows")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { f -> f.optString("id").takeIf { it.isNotEmpty() }?.let { put(it, flowInfo(f)) } } }
            },
            columnTypes = parsed.associate { t -> t.id to t.columns.mapValues { it.value.type } })
    }

    /** The registry's bounded JSON Schema subset (feedback/src/template-schema.mjs checkSchema). */
    fun checkSchema(s: JSONObject, depth: Int, at: String) {
        if (depth > 10 || s.keys().asSequence().any { it !in SCHEMA_KEYWORDS }) throw Bad("$at: unsupported schema keyword (allowed: ${SCHEMA_KEYWORDS.joinToString()}).")
        val ts = types(s)
        if (ts.isEmpty() || ts.any { it !in TYPES }) throw Bad("$at: type is required.")
        s.optJSONArray("enum")?.let { e -> if (e.length() == 0 || e.length() > 100) throw Bad("$at: enum must have 1-100 values.") }
        if ("object" in ts) {
            val p = s.optJSONObject("properties"); val r = s.optJSONArray("required")
            if (p == null || r == null || s.opt("additionalProperties") != false || strings(r).any { !p.has(it) })
                throw Bad("$at: objects need properties, required and additionalProperties:false.")
            for (k in p.keys()) { if (!BLANK.matches(k)) throw Bad("$at: property name $k isn't allowed."); checkSchema(p.getJSONObject(k), depth + 1, "$at.$k") }
        }
        if ("array" in ts) {
            val items = s.optJSONObject("items") ?: throw Bad("$at: arrays need items.")
            if (s.optInt("maxItems", -1) !in 0..1000) throw Bad("$at: arrays need maxItems (at most 1000).")
            checkSchema(items, depth + 1, "$at.items")
        }
    }

    private fun types(s: JSONObject): List<String> = when (val t = s.opt("type")) { is String -> listOf(t); is JSONArray -> strings(t); else -> emptyList() }

    /** The registry's value check (feedback/src/template-values.mjs): returns what's wrong, empty when it fits. */
    fun validate(s: JSONObject, value: Any?, path: String = "$", errors: MutableList<String> = mutableListOf()): List<String> {
        val ts = types(s)
        val type = when (value) { null, JSONObject.NULL -> "null"; is JSONArray -> "array"; is JSONObject -> "object"; is String -> "string"; is Boolean -> "boolean"
            is Int, is Long -> "integer"; is Number -> if ((value.toDouble() % 1.0) == 0.0) "integer" else "number"; else -> "unknown" }
        val fits = type in ts || (type == "integer" && "number" in ts)
        if (!fits) { errors += "$path: should be ${ts.joinToString(" or ")}"; return errors }
        s.optJSONArray("enum")?.let { e -> if ((0 until e.length()).none { same(e.get(it), value) }) errors += "$path: must be one of ${(0 until e.length()).joinToString { e.get(it).toString() }}" }
        if (value is Number) {
            val n = value.toDouble()
            if (s.has("minimum") && n < s.getDouble("minimum")) errors += "$path: at least ${num(s.getDouble("minimum"))}"
            if (s.has("maximum") && n > s.getDouble("maximum")) errors += "$path: at most ${num(s.getDouble("maximum"))}"
        }
        if (value is String && (value.length < s.optInt("minLength", 0) || value.length > s.optInt("maxLength", 100000))) errors += "$path: ${s.optInt("minLength", 0)}-${s.optInt("maxLength", 100000)} characters"
        if (value is String && s.optString("format") == "timezone" && runCatching { java.time.ZoneId.of(value) }.isFailure) errors += "$path: not a time zone"
        if (value is JSONArray) {
            if (value.length() < s.optInt("minItems", 0) || value.length() > s.optInt("maxItems", 1000)) errors += "$path: wrong number of items"
            s.optJSONObject("items")?.let { item -> for (i in 0 until value.length()) validate(item, value.get(i), "$path[$i]", errors) }
        }
        if (value is JSONObject) {
            val props = s.optJSONObject("properties") ?: JSONObject()
            strings(s.optJSONArray("required") ?: JSONArray()).forEach { if (!value.has(it)) errors += "$path.$it: required" }
            value.keys().forEach { k -> if (!props.has(k)) errors += "$path.$k: unknown blank" else validate(props.getJSONObject(k), value.get(k), "$path.$k", errors) }
        }
        return errors
    }

    private fun same(a: Any?, b: Any?): Boolean = if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
    private fun num(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    // ---- filling and compiling ------------------------------------------------------------------------------------

    /**
     * The blanks for an install: [given] over the package's defaults. For an update, [previous] (the old instance's
     * values) comes between, so the owner's choices survive a new revision; a value the new revision no longer
     * accepts is refused rather than silently reset.
     */
    fun fill(p: Package, given: JSONObject?, previous: JSONObject? = null): JSONObject {
        val props = p.configSchema.optJSONObject("properties") ?: JSONObject()
        val out = JSONObject()
        for (k in props.keys()) {
            when {
                given?.has(k) == true -> out.put(k, given.get(k))
                previous?.has(k) == true -> out.put(k, previous.get(k))
                p.defaults.has(k) -> out.put(k, p.defaults.get(k))
            }
        }
        given?.keys()?.forEach { if (!props.has(it)) throw Bad("$it isn't a blank in ${p.name}.") }
        validate(p.configSchema, out).firstOrNull()?.let { throw Bad("Blank $it") }
        return out
    }

    /**
     * Replaces `$input` nodes with [config] values and `$watch` nodes with [watchIds] (when given). A `$flow` node
     * becomes the package flow's name: bound late, at run time, to that applet's own flow of that name, so an update
     * that changes a flow keeps the watches and schedule that start it (docs/applet-vocabulary.md).
     */
    fun resolve(v: Any?, config: JSONObject?, watchIds: Map<String, String>?): Any? = when (v) {
        is JSONObject -> when {
            v.has("\$flow") && v.length() == 1 -> v.getString("\$flow")
            v.has("\$input") && config != null -> config.opt(v.getString("\$input")) ?: JSONObject.NULL
            v.has("\$watch") && watchIds != null -> watchIds[v.getString("\$watch")] ?: JSONObject.NULL
            else -> JSONObject().also { o -> v.keys().forEach { o.put(it, resolve(v.get(it), config, watchIds)) } }
        }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until v.length()) a.put(resolve(v.get(i), config, watchIds)) }
        else -> v
    }

    /** Fills every blank and returns what the install creates. Watch ids in card data stay as refs until the watches exist. */
    fun compile(p: Package, config: JSONObject): Plan {
        val processing = p.definition.optJSONObject("processing") ?: JSONObject()
        val watches = processing.optJSONArray("watches") ?: JSONArray()
        val ws = (0 until watches.length()).map { i ->
            val w = watches.getJSONObject(i)
            w.getString("id") to (resolve(w.getJSONObject("request"), config, null) as JSONObject)
        }
        val schedule = processing.optJSONObject("schedule")?.let { resolve(it.getJSONObject("manifest"), config, null) as JSONObject }
        val flows = processing.optJSONObject("workflow")?.let { listOf(MAIN_FLOW to (resolve(it, config, null) as JSONObject)) }
            ?: processing.optJSONArray("flows")?.let { f -> (0 until f.length()).map { i ->
                val body = resolve(f.getJSONObject(i), config, null) as JSONObject
                body.getString("id") to JSONObject(body.toString()).apply { remove("id") }
            } } ?: emptyList()
        val native = if (p.adapter == DOG_ADAPTER) JSONObject(config.toString()).apply {
            // The package owns the classifier, never a configuration field supplied by a caller.
            remove("classifier")
            processing.optJSONObject("classifier")?.let { put("classifier", it) }
        } else processing.optJSONObject("native")?.let { resolve(it, config, null) as JSONObject }
        val card = p.definition.optJSONObject("ui")?.optJSONObject("card")?.let { c ->
            if (cc.bridgit.app.interact.CardPieces.isPieces(c)) return@let cc.bridgit.app.interact.CardPieces.check(c, cardScope(p.definition, processing, null)).let { checked ->
                Card(c.getString("title"), null, JSONObject(), c, resolve(c, config, null) as JSONObject, checked.calls, checked.camera, checked.capabilities) }
            val html = p.definition.getJSONObject("assets").getJSONObject(c.getString("asset")).getString("text")
            val data = c.optJSONObject("data")?.let { resolve(it, config, null) as JSONObject } ?: JSONObject()
            Card(c.getString("title"), html, data)
        }
        val tables = AppletTables.parse(processing)
        val tableColumns = tables.associate { it.id to it.columns.keys }
        for ((name, flow) in flows) {
            val steps = flow.optJSONArray("steps") ?: continue
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("type") == "share") {
                    AppletExport.check(step, "flow $name steps[$i] (share)", tableColumns, flow = true) { throw Bad(it) }
                }
            }
        }
        return Plan(ws, schedule, flows, card, native, config, tables, cc.bridgit.app.remoteview.PortalTables.portalIsInterface(p.definition))
    }

    // ---- permissions and plain words ----------------------------------------------------------------------------

    /**
     * The permissions a plan really uses, worked out from its parts (never just trusted from the file):
     * watches + each watch's data grants, interactions for a card, schedule for a schedule, the workflow's
     * scopes. [grantsFor] is WatchRules.grantsFor; [callCap] maps a "METHOD /v1/path" a rule or action calls
     * to its capability (null when unknown); [workflowScopes] is WorkflowEngine.validate's required_scopes.
     */
    /** LAN photo steps resolved from the exact installed configuration. */
    fun lanPhotos(plan: Plan): List<JSONObject> = plan.flows.flatMap { (_, flow) ->
        val steps = flow.getJSONArray("steps")
        (0 until steps.length()).map { steps.getJSONObject(it) }.filter {
            it.optString("type") == "photo" && it.optString("camera").startsWith("lan:")
        }
    }

    fun cameraViewBindings(record: JSONObject): Map<String,String> {
        val pkg = check(record.getJSONObject("definition"))
        return lanPhotos(compile(pkg,record.getJSONObject("config"))).filter { it.has("view") }
            .groupBy { it.getString("view") }.mapValues { (_,steps) ->
                steps.map { it.getString("camera") }.distinct().single()
            }
    }

    fun derived(plan: Plan, grantsFor: (List<String>, String?) -> Set<String>, callCap: (String) -> String?,
                workflowScopes: (JSONObject) -> Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for ((_, w) in plan.watches) {
            out += "watches"
            if (cc.bridgit.app.watch.WatchRules.soundSources(w.optJSONObject("sound")).any { it.startsWith("lan:") })
                out += setOf("lan_camera","lan_camera_audio")
            val data = w.optJSONArray("data")?.let(::strings) ?: emptyList()
            out += grantsFor(data, w.optString("camera").takeIf { it.isNotEmpty() && !w.isNull("camera") })
            w.optJSONArray("additional_cameras")?.let { ids -> strings(ids).forEach { out += grantsFor(data, it) } }
            // Faces of people who aren't the owner: its own permission, so adding it to an applet needs the owner's approval.
            if (w.has("people") && !w.isNull("people")) out += PEOPLE_SEEN
            for (rules in listOfNotNull(w.optJSONArray("rules"), w.optJSONObject("tally")?.optJSONArray("rules")))
                for (i in 0 until rules.length()) rules.optJSONObject(i)?.optJSONObject("do")?.let { d -> doCall(d)?.let { c -> callCap(c)?.let { out += it } } }
        }
        plan.schedule?.let { m ->
            out += "schedule"
            m.optJSONArray("actions")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optJSONObject("do")?.let { d -> doCall(d)?.let { c -> callCap(c)?.let { out += it } } } }
        }
        plan.card?.let { c -> out += "interactions"; out += c.cardCapabilities; c.calls.forEach { call -> callCap(call)?.let { out += it } }; if (c.usesCamera) out += "camera" }
        if (lanPhotos(plan).isNotEmpty()) out += "lan_camera"
        for ((_, f) in plan.flows) out += workflowScopes(f)
        if (plan.native != null) out += "camera"
        return out
    }

    /**
     * The endpoint a `do` calls, as "METHOD /v1/path", for permissions: a raw call (`call` string or method + path),
     * or the call a single step makes. A `do.flow` calls nothing itself (the flow has its own scopes).
     */
    fun doCall(d: JSONObject): String? = when {
        d.has("flow") -> null
        d.has("step") -> d.optJSONObject("step")?.optString("type")?.let { cc.bridgit.app.platform.FlowSteps.inlineEndpoint(it) }
        d.optString("call").isNotEmpty() -> d.optString("call")
        d.has("path") -> "${d.optString("method", "POST").uppercase()} ${d.optString("path").let { if (it.startsWith("/v1/")) it else "/v1/" + it.trimStart('/') }}"
        else -> null
    }

    /**
     * Whether the plan can make paid cloud model calls: a watch that asks Jev or Clef, a flow with ask_model, or a
     * card or flow allowed the paid endpoints (judgment, find_crop). The applet budget (R1) caps them.
     */
    fun paid(plan: Plan, declared: Set<String>): Boolean =
        plan.watches.any { (_, raw) ->
            val w = if (raw.has("situation")) runCatching { cc.bridgit.app.watch.Situations.expand(raw).body }.getOrDefault(raw) else raw
            val data = w.optJSONArray("data")?.let(::strings) ?: emptyList()
            val model = w.optString("model").ifEmpty { if (w.has("question") || w.has("questions")) cc.bridgit.app.watch.WatchRules.defaultModel(data) else "none" }
            model in cc.bridgit.app.watch.WatchRules.PAID_MODELS
        } || plan.flows.any { (_, f) -> f.optJSONArray("steps")?.let { st -> (0 until st.length()).any { st.optJSONObject(it)?.optString("type").let { t -> t == "ask_model" || t == "appraise" || t == "kitchen" || t == "service" } } } == true } ||
            "judgment" in declared || "find_crop" in declared

    /** A watch with `people` (faces seen in the kitchen, sent to the applet's portal). */
    const val PEOPLE_SEEN = "people_seen"

    /** Permissions the package uses but didn't declare: the install is refused while this isn't empty. */
    fun undeclared(p: Package, derived: Set<String>): Set<String> = derived - p.grantsFor(derived)

    /** What an update adds: an update that adds nothing applies without asking the owner again (as browsers do). */
    fun added(old: Set<String>, new: Set<String>): Set<String> = new - old

    /** "every 30 seconds", "every 5 minutes", "every hour". */
    fun every(seconds: Int): String = when {
        seconds % 3600 == 0 -> if (seconds == 3600) "every hour" else "every ${seconds / 3600} hours"
        seconds % 60 == 0 -> if (seconds == 60) "every minute" else "every ${seconds / 60} minutes"
        else -> "every $seconds seconds"
    }

    /**
     * Plain sentences for the review screen about the on-phone engines [p] asks for (runtime.engines). [lookup] gives
     * an engine's kind, model, size in MB and speed, or null when this phone can't run it (then it is a blocker).
     */
    fun engineWords(p: Package, lookup: (String) -> EngineInfo?): List<String> = p.engines.map { name ->
        lookup(name)?.let { e ->
            "It uses the on-device ${if (e.kind == "detector") "any-word detector" else if (e.kind == "outline") "outline maker" else e.kind} (${e.model}): a ${e.sizeMb} MB download on Wi-Fi the first time, then free and offline${e.speed.substringBefore(" on ").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}."
        } ?: "It asks for the on-device engine $name, which this device can't run."
    }

    data class EngineInfo(val kind: String, val model: String, val sizeMb: Int, val speed: String)

    /** Whether [definition] has anything to show on a web portal: its tables (ui.portal.tables), card records (ui.portal.records) or a watch that tallies. */
    fun portalCapable(definition: JSONObject): Boolean {
        if (cc.bridgit.app.remoteview.PortalRecords.kindOf(definition) != null) return true
        if (cc.bridgit.app.remoteview.PortalTables.tablesOf(definition).isNotEmpty()) return true
        val ws = definition.optJSONObject("processing")?.optJSONArray("watches") ?: return false
        return (0 until ws.length()).any { ws.optJSONObject(it)?.optJSONObject("request")?.has("tally") == true }
    }

    /** The review's line about the applet's web portal, or null when it has none (nothing to send, or the Pet monitor). */
    fun portalWords(definition: JSONObject): String? {
        if (definition.optJSONObject("runtime")?.optString("adapter") == DOG_ADAPTER || !portalCapable(definition)) return null
        val records = cc.bridgit.app.remoteview.PortalRecords.kindOf(definition) != null
        val tables = cc.bridgit.app.remoteview.PortalTables.tablesOf(definition)
        if (tables.isNotEmpty()) {
            val what = "this device copies its ${tables.joinToString(" and ")} (rows and photos) to your private portal at bridgit.cc, and you use the applet there: in Applets › Portals on this device, or in a browser on a computer. Kept until the portal or the applet is removed. Only you, and people you invite, can see it."
            return if (cc.bridgit.app.remoteview.PortalRecords.always(definition)) "Web portal: comes with this applet and is its screen. When you install it, $what Removing the applet stops it and deletes its data there."
                else "Web portal: off until you turn it on in Installed applets. Then $what"
        }
        if (!cc.bridgit.app.remoteview.PortalRecords.always(definition))
            return "Web portal: off until you turn it on in Installed applets."
        val what = if (records) "Each time you finish with the card, this device sends the result to your private portal at bridgit.cc: numbers and words, never pictures."
            else "About once a minute this device sends today's counts and each check's answer to your private portal at bridgit.cc, with a small photo of the latest check."
        return "Web portal: comes with this applet and starts when you install it. $what Kept 30 days. Only you, and people you invite, can see it. Removing the applet stops it and deletes its data there."
    }

    /**
     * One item of the review's "What it does" (design pass, docs/design/applet-install.md): a short [title], plain
     * sentences, then indented [subs] (a card's buttons as label and effect, or a workflow's steps with no label).
     * [text] is the same item as one plain string, which the API returns as a part.
     */
    data class Part(val title: String, val lines: List<String>, val subs: List<Pair<String?, String>> = emptyList()) {
        val text: String get() = "$title: " + (lines + subs.map { (l, e) -> if (l != null) "$l: $e" else e }).joinToString(" ")
    }

    /** "3,650 days (about 10 years)", "30 days", "365 days (about a year)". */
    fun daysWords(days: Int): String {
        val n = "%,d".format(java.util.Locale.US, days)
        val years = days / 365.0
        return when {
            days >= 365 && days % 365 in listOf(0, 1, 2, 3) -> "$n days (about ${if (days / 365 == 1) "a year" else "${days / 365} years"})"
            years >= 2 && kotlin.math.abs(years - Math.round(years)) < 0.05 -> "$n days (about ${Math.round(years)} years)"
            else -> "$n day${if (days == 1) "" else "s"}"
        }
    }

    private fun count(n: Int) = "%,d".format(java.util.Locale.US, n)

    /** Plain sentences for the review screen, one string per item: what each part does, how often, and the paid-call worst case. */
    fun describe(plan: Plan, callWords: (String) -> String? = { null }): List<String> = parts(plan, callWords).map { it.text }

    /** The review's items: what each part does, how often, what it costs, what it keeps and what leaves the device. */
    fun parts(plan: Plan, callWords: (String) -> String? = { null }): List<Part> {
        val out = mutableListOf<Part>()
        val table: (String) -> String = { id -> plan.tables.firstOrNull { it.id == id }?.label ?: id }
        val flow: (String) -> String = { id -> plan.flows.firstOrNull { it.first == id }?.let { flowName(it.second, it.first) } ?: id }
        for ((_, raw) in plan.watches) {
            // A situation watch is described as the device will run it: its question, label and tally filled in.
            val w = if (raw.has("situation")) runCatching { cc.bridgit.app.watch.Situations.expand(raw).body }.getOrDefault(raw) else raw
            val data = w.optJSONArray("data")?.let(::strings) ?: emptyList()
            val additionalCameras = w.optJSONArray("additional_cameras")?.let(::strings).orEmpty()
            val what = when {
                additionalCameras.isNotEmpty() -> "one camera at a time (" + (listOf(w.optString("camera", "rear")) + additionalCameras).joinToString() + ")"
                "camera" in data -> "the ${w.optString("camera", "rear").let { if (it.startsWith("lan:")) "network" else it }} camera"
                "sound" in data -> "for loud sounds with the microphone"
                else -> data.joinToString(" and ")
            }
            val interval = w.optInt("interval_s", 60)
            val label = w.optString("label").ifBlank { "Watch" }
            // A question left without a model gets the device's default (Clef for a photo); model none asks nothing.
            val model = w.optString("model").ifEmpty { if (w.has("question") || w.has("questions")) cc.bridgit.app.watch.WatchRules.defaultModel(data) else "none" }
            val often = if ("sound" in data) "each time it hears a loud sound" else every(interval)
            val lines = mutableListOf("$label checks $what $often.")
            if ("sound" in data) {
                val sources=cc.bridgit.app.watch.WatchRules.soundSources(w.optJSONObject("sound"))
                lines += if (sources.isEmpty()) "No microphone is selected." else "Selected microphones: "+sources.joinToString { if(it=="builtin") "this device" else it }
            }
            lines += when (model) {
                "none" -> "It compares pictures on this device and makes no paid model calls."
                "clef", "jev", "glm" -> {
                    val perHour = minOf((3600 + interval - 1) / interval, w.optInt("max_paid_per_hour", Int.MAX_VALUE).takeIf { w.has("max_paid_per_hour") && !w.isNull("max_paid_per_hour") } ?: Int.MAX_VALUE)
                    val month = perHour * 24 * 30
                    val change = w.optJSONObject("local")?.optBoolean("change_check") == true
                    val name = model.replaceFirstChar { it.uppercase() }
                    if ("sound" in data) {
                        val limit=w.optInt("max_paid_per_hour",cc.bridgit.app.sound.SoundRules.DEFAULT_CHECKS_PER_HOUR)
                        "It sends up to $limit loud audio clips per hour to $name. Paid checks share the applet budget."
                    } else "It sends up to ${count(perHour)} photo${if (perHour == 1) "" else "s"} an hour off this device to $name, a paid model" +
                        (if (model == "clef") " (worst case about \$${"%.2f".format(java.util.Locale.US, month * CLEF_USD_PER_PHOTO)} a month, from the device's model budget)." else ", from the device's model budget.") +
                        (if (change) " Unchanged pictures reuse the last answer for free." else "")
                }
                else -> "It uses the $model model."
            }
            w.optJSONObject("people")?.let { p ->
                lines += "When the picture changes, it takes up to ${p.optInt("burst", 3)} photos, finds faces on this device and sends the face photos to your private web portal" +
                    " (at most ${p.optInt("max_bursts_per_hour", 20)} times an hour, only while charging or above ${p.optInt("battery_floor", 50)}% battery). Photos there are deleted after 30 days."
            }
            w.optJSONObject("tally")?.let { t -> lines += "It keeps a count of ${if (raw.has("situation")) "each time it sees $label" else t.optString("track")} on this device${t.optInt("keep_days", 0).takeIf { it > 0 }?.let { " for ${daysWords(it)}" } ?: ""}." }
            (w.optJSONArray("rules") ?: w.optJSONObject("tally")?.optJSONArray("rules"))?.takeIf { it.length() > 0 }?.let { rules ->
                for (i in 0 until rules.length()) rules.optJSONObject(i)?.let { r -> lines += "${ruleWhen(r.optJSONObject("when")).replaceFirstChar { it.uppercase() }}, it ${doWords(r.optJSONObject("do"), plan)}." }
            }
            out += Part("${if ("sound" in data) "Sound check" else if ("camera" in data) "Camera check" else "Check"} · $label", lines)
        }
        plan.schedule?.optJSONArray("actions")?.let { a ->
            for (i in 0 until a.length()) {
                val act = a.optJSONObject(i) ?: continue
                val w = act.optJSONObject("when")
                val times = w?.optJSONArray("daily")?.let(::strings)
                val whenText = when {
                    times != null -> "every day at ${times.joinToString(", ")}"
                    w?.has("every_minutes") == true -> every(w.getInt("every_minutes") * 60)
                    else -> "on its timetable"
                } + (w?.optInt("count", 0)?.takeIf { it > 0 }?.let { ", $it times" } ?: "")
                val d = act.optJSONObject("do")
                val name = act.optString("name").ifBlank { act.optString("id") }
                out += Part("Schedule · $name", listOf(if (d?.has("flow") == true || d?.has("step") == true) "${whenText.replaceFirstChar { it.uppercase() }}, it ${doWords(d, plan)}."
                    else "It runs $whenText: ${d?.let(::doCall)?.let { c -> callWords(c) ?: c } ?: "an action"}."))
            }
        }
        for ((fid, wf) in plan.flows) {
            val t = wf.optJSONObject("trigger")
            val starters = starters(plan, fid, table).let { s -> if (t?.optString("type") == "button") s + "you tap its button" else s }
            val lead = when {
                t?.optString("type") != "called" && t?.optString("type") != "button" -> null
                starters.isEmpty() -> "Nothing in the applet starts it yet"
                else -> "It runs when " + joinOr(starters)
            }
            val (start, steps) = cc.bridgit.app.platform.FlowSteps.reviewLines(wf, lead, table)
            val from = t?.optString("package")?.takeIf { it.isNotEmpty() }?.let { " It reads notifications from $it." } ?: ""
            out += Part("Workflow · ${flowName(wf, fid)}", listOf(start + from), steps.map { null to it })
        }
        plan.card?.let { c ->
            out += if (c.isPieces) cc.bridgit.app.interact.CardPieces.reviewItem(c.piecesRaw!!, c.title, callWords, table, flow).let { (shows, buttons) -> Part("Page · ${c.title}", listOf(if (plan.portalScreen) "Its screen is the applet's web portal (Applets › Portals on this device, or bridgit.cc on a computer). These buttons are on it and work on this device:" else shows), buttons.map { it.first to it.second }) }
                else Part("Page · ${c.title}", listOf("Shows ${c.title} as its own page. Open in Installed applets shows it, and agents you allow can open it too."))
        }
        for (t in plan.tables) {
            out += Part("Saved data · ${t.label}", listOfNotNull(
                "It keeps ${t.label} on this device for ${daysWords(t.keepDays)}, at most ${count(t.maxRows)} rows. You can see and delete them under Installed applets › Data.",
                "The agent that installed it can read the ${t.label} rows.".takeIf { t.shared }))
        }
        plan.native?.let { n ->
            // The tracker controls timing and model selection. The installed package supplies its questions.
            n.optJSONObject("classifier")?.let { profile ->
                val questions = JSONObject(profile.getJSONObject("questions").toString())
                profile.optJSONObject("sound")?.let { questions.put("sound", it) }
                val details = questions.keys().asSequence().sorted().map { key ->
                    val q = questions.getJSONObject(key)
                    val criteria = q.optJSONObject("criteria")
                    val options = criteria?.keys()?.asSequence()?.sorted()?.joinToString("\n") { "$it: ${criteria.getString(it)}" }
                    key to listOfNotNull(q.getString("instructions"), options).joinToString("\n")
                }.toList()
                out += Part("Pet classifier", listOf(profile.getString("note")), details)
            }
            out += Part("Pet monitor", listOf(
                "It takes a small photo with the ${n.optString("camera", "rear")} camera every 10 seconds and sends it off this device to Jev, a paid model, from the device's model budget. Unchanged scenes reuse the last answer.",
                "When microphone access is on, it listens for loud sounds to count barking."))
        }
        return out
    }

    // ---- filling in what the registry wants (POST /v1/applets/validate) -------------------------------------------

    /** Sections the template registry requires and the phone ignores; validate fills them in when missing. */
    val REGISTRY_SECTIONS = listOf("observation_schema", "metrics", "lifecycle", "migration", "data_policy")

    /**
     * Before checking a file for validate: computes a missing asset `sha256`, and, when `permissions.required` is
     * missing, puts an empty list there so the file can be checked ([needsPermissions] tells the caller to fill
     * it with the derived permissions afterwards). Returns a copy.
     */
    fun prefill(d: JSONObject): Pair<JSONObject, Boolean> {
        val out = JSONObject(d.toString())
        out.optJSONObject("assets")?.let { a -> for (k in a.keys()) a.optJSONObject(k)?.let { x -> if (!x.has("sha256") && x.opt("text") is String) x.put("sha256", sha256(x.getString("text"))) } }
        var needs = false
        val perms = out.optJSONObject("permissions") ?: JSONObject().put("automatic_grants", false).also { out.put("permissions", it) }
        if (!perms.has("required")) { perms.put("required", JSONArray()); needs = true }
        if (!perms.has("optional")) perms.put("optional", JSONArray())
        if (!perms.has("remote")) perms.put("remote", JSONArray())
        return out to needs
    }

    /** Whether any part asks Jev or Clef (a paid call that leaves the phone). */
    private fun usesModels(plan: Plan): Boolean =
        plan.watches.any { (_, w) -> w.optString("model").let { it in cc.bridgit.app.watch.WatchRules.PAID_MODELS } || ((w.has("question") || w.has("questions") || w.has("situation")) && w.optString("model") != "none") } ||
            plan.flows.any { (_, f) -> f.optJSONArray("steps")?.let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optString("type") in setOf("ask_model","service","appraise","kitchen") } } == true }

    /**
     * The complete file to publish: [d] with the registry-only sections filled in where missing (written ones are
     * kept as they are) and `permissions.required` set to [derived] when [fillPermissions]. `data_policy` is worked
     * out from the parts, so it can't claim less than the applet does. Pure.
     */
    fun completed(d: JSONObject, plan: Plan, derived: Set<String>, fillPermissions: Boolean): JSONObject {
        val out = JSONObject(d.toString())
        if (fillPermissions) out.getJSONObject("permissions").put("required", JSONArray(derived.sorted()))
        val models = usesModels(plan)
        val leaves = buildList {
            if (models) add("model questions to Jev or Clef through Bridgit's team backend (from the phone's model budget)")
            if ("weather" in derived) add("weather requests")
            if ("alerts" in derived) add("alerts to the contacts you chose")
            if (PEOPLE_SEEN in derived) add("face photos to your private web portal")
        }
        val keeps = buildList {
            if (plan.watches.isNotEmpty()) add("counts and checks")
            plan.tables.forEach { add("the ${it.label} table (${it.keepDays} days)") }
            if (plan.flows.any { (_, f) -> f.optJSONArray("steps")?.let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optBoolean("keep") == true } } == true }) add("kept photos")
        }
        val defaults = mapOf(
            "observation_schema" to JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()).put("additionalProperties", false),
            "metrics" to JSONArray(),
            "lifecycle" to JSONObject().put("states", JSONArray(listOf("awaiting_owner", "active", "paused", "removed")))
                .put("start", "The owner approves the package once on the phone; the phone creates every part.")
                .put("stop", "Pause stops every part and keeps counts; Remove deletes every part and its permissions.")
                .put("delete", "Remove deletes everything the applet created on the phone."),
            "migration" to JSONObject().put("strategy", "explicit_owner_opt_in").put("from", JSONObject.NULL)
                .put("rules", JSONArray(listOf("A revision that adds a permission waits for the owner; otherwise the phone swaps it in and keeps unchanged parts.")))
                .put("rollback", "Install the earlier revision as an update."),
            "data_policy" to JSONObject().put("backend_default", "device_only")
                .put("device_only", JSONObject().put("server_registration", false).put("uploads", PEOPLE_SEEN in derived).put("remote_models", models)
                    .put("derived_telemetry", false).put("queue", false))
                .put("scope", JSONArray(listOf("device_id", "applet_instance_id")))
                .put("sharing", JSONObject().put("role", "owner_control").put("control_capability", "applet_install")
                    .put("installer_shared_tables", JSONArray(plan.tables.filter { it.shared }.map { it.id }))
                    .put("discovery_contract", AppletDataDiscovery.CONTRACT))
                .put("retention", JSONObject().put("local", if (keeps.isEmpty()) "Nothing is kept on the phone beyond the applet's own settings."
                    else "Kept on the phone until the applet is removed (table rows for their keep_days): ${keeps.joinToString(", ")}."))
                .put("enable", "Owner approval on the phone.").put("disable", "Pause or Remove on the phone.")
                .put("execution", "Runs on the phone with no agent. " + (if (leaves.isEmpty()) "No automatic uploads." else "Automatic outbound data: ${leaves.joinToString("; ")}.") + " Authenticated owner control agents can read saved applet data and photos with their existing key."),
        )
        for ((k, v) in defaults) if (!out.has(k)) out.put(k, v)
        return out
    }

    /** A written `data_policy` that says less than the parts do (a warning for validate, not an error). */
    fun policyWarnings(d: JSONObject, plan: Plan): List<String> {
        val dp = d.optJSONObject("data_policy") ?: return emptyList()
        val out = mutableListOf<String>()
        if (usesModels(plan) && dp.optJSONObject("device_only")?.optBoolean("remote_models", false) == false)
            out += "data_policy.device_only.remote_models says false, but the applet asks Jev or Clef. Leave data_policy out and validate fills it in."
        return out
    }

    private fun flowName(wf: JSONObject?, fid: String) = wf?.optString("name")?.takeIf { it.isNotBlank() } ?: fid

    private fun joinOr(list: List<String>) = if (list.size <= 1) list.joinToString() else list.dropLast(1).joinToString(", ") + " or " + list.last()

    /** What in the plan starts flow [fid]: its rules and schedule actions, in words ("the rule \"no-meal\" fires"). */
    fun starters(plan: Plan, fid: String, table: (String) -> String = { "\"$it\"" }): List<String> {
        val out = mutableListOf<String>()
        fun names(d: JSONObject?) = d?.opt("flow") == fid
        for ((_, w) in plan.watches) for (rules in listOfNotNull(w.optJSONArray("rules"), w.optJSONObject("tally")?.optJSONArray("rules")))
            for (i in 0 until rules.length()) rules.optJSONObject(i)?.takeIf { names(it.optJSONObject("do")) }?.let { out += "the ${it.optString("id", "rule${i + 1}")} rule fires" }
        plan.schedule?.optJSONArray("actions")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { names(it.optJSONObject("do")) }?.let {
            out += "the ${it.optString("name").ifBlank { it.optString("id") }} schedule runs" } }
        // Card buttons (docs/applet-wave2.md): {"flow": {"$flow": id}}, once or for each element.
        plan.card?.piecesRaw?.optJSONArray("pieces")?.let { pieces ->
            for (i in 0 until pieces.length()) pieces.optJSONObject(i)?.takeIf { it.optString("type") == "buttons" }?.optJSONArray("items")?.let { items ->
                for (j in 0 until items.length()) items.optJSONObject(j)?.let { b ->
                    val d = b.optJSONObject("do") ?: return@let
                    val f = d.opt("flow")
                    if (f == fid || (f as? JSONObject)?.optString("\$flow") == fid)
                        out += if (d.has("for_each")) "you tap ${b.optString("label")}, once for each entry in ${(d.opt("for_each") as? JSONObject)?.optString("\$source")?.substringBefore('.')?.takeIf { it.isNotEmpty() }?.let(table) ?: "the list"}" else "you tap ${b.optString("label")}"
                }
            }
        }
        return out
    }

    /** A tally rule's condition in words: "if no eating is seen by 18:00". */
    fun ruleWhen(w: JSONObject?): String {
        if (w == null) return "when it fires"
        val of = w.optString("of", "it")
        fun span(s: Int) = every(s).removePrefix("every ").let { if (it == "hour" || it == "minute") "an $it".replace("an minute", "a minute") else it }
        return when {
            w.has("fewer_than") -> if (w.optInt("fewer_than") <= 1) "if no $of is seen by ${w.optString("by")}" else "if $of is seen fewer than ${w.optInt("fewer_than")} times by ${w.optString("by")}"
            w.has("none_for_s") -> "if no $of is seen for ${span(w.optInt("none_for_s"))}"
            w.has("at_least") -> "if $of is seen ${w.optInt("at_least")} time${if (w.optInt("at_least") == 1) "" else "s"} within ${span(w.optInt("within_s"))}"
            else -> "when it fires"
        }
    }

    /** What a `do` does, in words: "runs Dinner check", "shows you a notice "…"", "calls POST /v1/…". */
    fun doWords(d: JSONObject?, plan: Plan): String = when {
        d == null -> "tells your agent"
        d.has("flow") -> d.opt("flow").let { ref -> "runs ${plan.flows.firstOrNull { it.first == ref }?.let { flowName(it.second, it.first) } ?: "the flow $ref"}" }
        d.has("step") -> d.optJSONObject("step")?.let { cc.bridgit.app.platform.FlowSteps.inlineWords(it) } ?: "runs a step"
        else -> doCall(d)?.let { if (it == "POST /v1/interactions") "shows you a notice" else "calls $it" } ?: "runs an action"
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    fun strings(a: JSONArray): List<String> = (0 until a.length()).mapNotNull { a.opt(it) as? String }

    internal fun pngBytes(asset: JSONObject): ByteArray {
        val encoded = asset.opt("base64") as? String ?: throw Bad("PNG needs base64.")
        if (encoded.length > 65536 || asset.has("text")) throw Bad("Invalid PNG payload.")
        val bytes = try { java.util.Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw Bad("Invalid PNG base64.") }
        val signature = byteArrayOf(-119,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82)
        if (bytes.size < 33 || bytes.size.toDouble() != (asset.opt("size") as? Number)?.toDouble() || java.util.Base64.getEncoder().encodeToString(bytes) != encoded ||
            !bytes.copyOfRange(0,16).contentEquals(signature)) throw Bad("Invalid PNG size or header.")
        val header = java.nio.ByteBuffer.wrap(bytes)
        if (header.getInt(16) !in 1..1024 || header.getInt(20) !in 1..1024) throw Bad("PNG must be at most 1024 pixels per side.")
        return bytes
    }

    fun sha256(text: String): String = sha256(text.toByteArray())
    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Content hash of canonical JSON (sorted keys), the same as PlatformStore.digest. */
    fun digest(v: JSONObject): String = sha256(canonical(v))

    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    /** Where the phone may fetch a package from: Bridgit's website and template registry only, https. */
    fun allowedSource(url: String): Boolean {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        return u.scheme == "https" && u.port == -1 && u.userInfo == null &&
            (host == "bridgit.cc" || host.endsWith(".bridgit.cc") || host == REGISTRY_HOST)
    }
    const val REGISTRY_HOST = "bridgit-feedback.bridgit-app.workers.dev"

    /**
     * An Import link, `bridgit://install?src=<https url>&sha256=<hex>` or `…?template=<key>&revision=<n>`
     * (also accepted as https://bridgit.cc/install/?…). Returns the fetch URL and the expected hash, or null.
     */
    data class Link(val url: String, val sha256: String?, val template: String?, val revision: Int?)

    fun registryLink(key: String, revision: Int?, hash: String? = null): Link? {
        if (!Regex("^[a-zA-Z0-9_-]{1,100}$").matches(key) || revision == null || revision < 1) return null
        return Link("https://$REGISTRY_HOST/v1/templates/$key/$revision", hash, key, revision)
    }

    fun link(uri: String): Link? {
        val u = runCatching { java.net.URI(uri) }.getOrNull() ?: return null
        val isApp = u.scheme == "bridgit" && u.host == "install"
        val isWeb = u.scheme == "https" && u.host == "bridgit.cc" && (u.path == "/install/" || u.path == "/install")
        if (!isApp && !isWeb) return null
        val q = (u.rawQuery ?: "").split('&').filter { '=' in it }.associate {
            java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val hash = q["sha256"]?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) }
        q["template"]?.let { key ->
            return registryLink(key, q["revision"]?.toIntOrNull(), hash)
        }
        val src = q["src"] ?: return null
        val full = if (src.startsWith("/")) "https://bridgit.cc$src" else src
        return if (allowedSource(full)) Link(full, hash, null, null) else null
    }

    /** A registry read returns {definition, …}; a static file is the definition itself. */
    fun unwrap(text: String): String {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return text
        return if (o.optString("format") != FORMAT && o.optJSONObject("definition") != null) o.getJSONObject("definition").toString() else text
    }
}
