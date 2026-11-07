package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.core.SafeHttp
import cc.bridgit.app.outbound.Outbound
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Reads an applet package from a request body, an allowed URL or a template registry revision. */
object AppletFetch {
    class Failed(message: String) : Exception(message)

    data class Download(val bytes: ByteArray, val account: String? = null)

    @androidx.annotation.VisibleForTesting
    internal var download: (BridgeApp, AppletPackage.Link, Outbound.Actor) -> Download = ::downloadSigned

    private fun downloadSigned(app: BridgeApp, link: AppletPackage.Link, actor: Outbound.Actor): Download {
        val headers = mutableMapOf("Accept" to "application/json", "User-Agent" to "Bridgit (Android) applets")
        if (link.template != null) {
            val uri = java.net.URI(link.url)
            val path = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            headers += runCatching { cc.bridgit.app.feedback.InstallIdentity.headers(app, "GET", path, ByteArray(0)) }
                .getOrElse { throw Failed("This phone isn't registered with Bridgit's servers yet, so it can't read templates. Try again in a minute.") }
        }
        val res = try {
            Outbound.on(actor, Outbound.Hint("Bridgit applet package", "Download an applet package to review")) {
                SafeHttp.get(link.url, SafeHttp.Policy.PUBLIC, 20_000, AppletPackage.MAX_BYTES * 2L + 1, headers = headers) }
        } catch (e: SafeHttp.Failure) { throw Failed("Couldn't download the applet: ${e.message}") }
        if (res.status == 404) throw Failed("This applet isn't available to this device's account. Check the link, or ask the person who shared it.")
        if (res.status != 200) throw Failed("Downloading the applet answered HTTP ${res.status}.")
        return Download(res.bytes ?: ByteArray(0),res.header("x-bridgit-library-account"))
    }

    /** Fetches [link] (Bridgit's website or template registry only). Template reads are signed as this phone. */
    fun fetch(app: BridgeApp, link: AppletPackage.Link, actor: Outbound.Actor, raw: Boolean = false): String {
        val text = checkedDownload(app,link,actor).bytes.toString(Charsets.UTF_8)
        if (raw) return text
        if (link.template != null && link.revision != null) {
            val release = registryRelease(text, link.template, link.revision)
            return release.getJSONObject("definition").toString()
        }
        return AppletPackage.unwrap(text)
    }

    private fun checkedDownload(app: BridgeApp, link: AppletPackage.Link, actor: Outbound.Actor): Download {
        val response = download(app,link,actor)
        if (link.sha256 != null && AppletPackage.sha256(response.bytes) != link.sha256)
            throw Failed("The downloaded applet doesn't match the link's fingerprint (it changed since the link was made).")
        return response
    }

    internal fun registryRelease(text: String, key: String, revision: Int?): JSONObject {
        val release = JSONObject(text)
        val actual = release.opt("revision")?.toString()?.toIntOrNull()
        if (release.optString("key") != key || actual == null || actual < 1 || revision != null && actual != revision)
            throw Failed("Registry response does not match the requested applet revision.")
        if (revision == null && release.optString("state") != "published") throw Failed("No published release.")
        return release
    }

    /** Keep the reviewed device account beside the edition, outside the authored definition. */
    fun packageAndSource(app: BridgeApp, link: AppletPackage.Link, actor: Outbound.Actor, kind: String): Pair<AppletPackage.Package, JSONObject> {
        val key = link.template ?: return AppletPackage.parse(fetch(app,link,actor)) to JSONObject().put("kind",kind).put("url",link.url)
        val response = checkedDownload(app,link,actor)
        val release = registryRelease(response.bytes.toString(Charsets.UTF_8),key,link.revision)
        val revision = release.getInt("revision")
        val source = JSONObject().put("kind","template").put("template",key).put("revision",revision)
            .put("url","https://${AppletPackage.REGISTRY_HOST}/v1/templates/$key/$revision")
        val owner = response.account
        if (owner != null) {
            if (!Regex("[a-f0-9]{64}").matches(owner)) throw Failed("Invalid registry account binding.")
            source.put("library_account",owner)
        }
        return AppletPackage.parse(release.getJSONObject("definition").toString()) to source
    }

    fun latest(app: BridgeApp, source: JSONObject, actor: Outbound.Actor): Pair<AppletPackage.Package, JSONObject> {
        val key = if (source.optString("kind") == "bundled") {
            source.getString("bundled").also { if (it.isEmpty()) throw Failed("Missing legacy applet id.") }
        } else source.optString("template")
        if (key.isEmpty()) {
            val url=source.optString("url")
            if (!AppletPackage.allowedSource(url)) throw Failed("No supported template source.")
            return AppletPackage.parse(fetch(app,AppletPackage.Link(url,null,null,null),actor)) to AppletEvolution.copy(source)
        }
        if (!Regex("[a-zA-Z0-9_-]{1,100}").matches(key)) throw Failed("Invalid registry key.")
        val link=AppletPackage.Link("https://${AppletPackage.REGISTRY_HOST}/v1/templates/$key/latest",null,key,null)
        return packageAndSource(app,link,actor,"template")
    }

    /** {package} inline, or {url} / {template, revision} to fetch. */
    fun fromBody(app: BridgeApp, body: JSONObject, actor: Outbound.Actor): Pair<AppletPackage.Package, JSONObject> {
        val sources = listOf("package", "url", "template", "bundled").count { body.has(it) }
        if (sources != 1) throw AppletPackage.Bad("Send exactly one of package, url, template + revision, or bundled (a built-in applet id).")
        if (body.has("bundled")) {
            val id = body.opt("bundled") as? String ?: throw AppletPackage.Bad("bundled must be an applet id.")

            return try { latest(app, JSONObject().put("kind","bundled").put("bundled",id), actor) }
            catch (e: Failed) { throw AppletPackage.Bad(e.message ?: "Download failed.") }
        }
        body.optJSONObject("package")?.let { return AppletPackage.parse(it.toString()) to JSONObject().put("kind", "agent_inline") }
        val link = if (body.has("url")) {
            val url = body.optString("url")
            if (!AppletPackage.allowedSource(url)) throw AppletPackage.Bad("url must be an https address on bridgit.cc or Bridgit's template registry; send other packages inline as package.")
            AppletPackage.Link(url, body.optString("sha256").takeIf { it.isNotEmpty() }, null, null)
        } else {
            AppletPackage.registryLink(body.optString("template"), body.opt("revision")?.toString()?.toIntOrNull())
                ?: throw AppletPackage.Bad("template must be a registry key and revision a number.")
        }
        return try { packageAndSource(app,link,actor,"url") }
        catch (e: Failed) { throw AppletPackage.Bad(e.message ?: "Download failed.") }
    }
}

object AppletEndpoints {
    private const val CAP = "applet_install"
    private val SOURCE = listOf(
        Param("package", "object", "The applet file itself: a bridgit-template/1 definition with runtime.adapter bridgit.package.v1 (legacy alias android.package.v1; see https://bridgit.cc/learn/builder/applet-packages.md)"),
        Param("url", "string", "Instead of package: an https://bridgit.cc/… package file (for example a recipe's applet.json)"),
        Param("template", "string", "Instead of package: a template registry key (with revision)"),
        Param("revision", "integer", "With template: the immutable revision"),
        Param("bundled", "string", "Instead of package/url/template: a built-in applet id (including a-sorta-fairytale-about-me, chef-helper, insurance-inventory, exercise-check); downloads its registry release"),
        Param("config", "object", "Values for the applet's blanks (config_schema); the rest use defaults. The owner can still change them on the review screen"))
    val CATALOG = EndpointSpec("GET", "/applets/catalog", CAP,
        "Browse the applet library without installing or running packages",
        example = "GET /v1/applets/catalog?q=kitchen",
        query = listOf(Param("q", "string", "Search title and summary; at most 100 characters"), Param("after", "string", "Opaque next-page key")),
        returns = "format, applets[{key,id,revision,name,summary,generation}], next")
    val PRACTICES = EndpointSpec("GET", "/applets/practices", CAP, "Read the shared applet practice catalog, applicability, defaults, acceptance checks and exception rules", example = "GET /v1/applets/practices", returns = "contract, version, practices[], by_task")
    val VALIDATE = EndpointSpec("POST", "/applets/validate", CAP,
        "Dry run: check an applet package and fill its blanks without storing or creating anything; returns exactly what the owner would review",
        body = SOURCE, privateFields = setOf("package"), maxBody = 256 * 1024,
        example = "POST /v1/applets/validate {\"url\":\"https://bridgit.cc/recipes/room-change-watcher/applet.json\",\"config\":{\"interval_s\":60}}",
        returns = "valid, review{package_id, name, version, parts[] (plain words: what, how often, cost), permissions[{id, words}], blockers[], warnings[{code, where, says, fix}] (never block the install: card_request_too_big, card_reply_too_big, card_form_submit, card_storage, permission_unused, data_policy_contradicts, paid_without_cap), config, update}, package (the complete file: observation_schema, metrics, lifecycle, migration, data_policy, a missing permissions.required and asset sha256 filled in; publish this), data_contract, data_discovery, warnings[], or errors[]",
        errors = listOf("invalid_parameter"))
    val INSTALL = EndpointSpec("POST", "/applets/install", CAP,
        "Ask the owner to install an applet package. Nothing runs and nothing is granted until the owner approves it once on the phone; then the phone creates its watches, schedule, workflow and card itself and keeps them running without you",
        idempotent = true, body = SOURCE, privateFields = setOf("package"), maxBody = 256 * 1024,
        example = "POST /v1/applets/install {\"url\":\"https://bridgit.cc/recipes/room-change-watcher/applet.json\"}",
        returns = "status awaiting_owner, pending_id, review (as validate). applet.installed or applet.declined follows in /v1/events",
        errors = listOf("invalid_parameter", "conflict", "too_many_open"))
    val LIST = EndpointSpec("GET", "/applets/installed", CAP, "Discover installed applets and their data_url. Owner control agents see all applets, including owner-installed ones; no installer ownership or new approval required",
        example = "GET /v1/applets/installed", returns = "installed[{id, package_id, name, version, status, run_mode, lifecycle{task_id,generation,state,participants,pending}, lifecycle_errors, config, template_update{status, version, id}|null, watches{part: watch_id}, card_id, workflow_id, flows{name: workflow_id}, budget{day_usd, month_usd, spent_today_usd, spent_month_usd, set_by: owner|package|default}, installed_at, updated_at}], pending[]")
    val GET = EndpointSpec("GET", "/applets/installed/{id}", CAP, "Inspect an installed applet, including data_url for schemas, saved rows, photos and processing status; control agents can inspect any applet",
        example = "GET /v1/applets/installed/ai0123456789abcd", returns = "as one entry of the list", errors = listOf("not_found"))
    val UPDATE = EndpointSpec("POST", "/applets/installed/{id}/update", CAP,
        "Propose a new version of this personal applet. Read its development snapshot, validate the complete candidate and include change evidence. The owner or an authorized controlling agent reviews every agent edit. Approval checks the expected instance revision again.",
        idempotent = true, body = SOURCE + Param("practice_review","object","Reported GLM practice review: model, source_sha256 and disposition, or unavailable status and reason") + Param("change", "object", "Required for agent edits: expected_revision, intent (1-1000 chars), acceptance (1-20 behavioral checks), evidence (1-4000 chars); task_id when integrating a prepared template. Read the modification guide first", required=true), privateFields = setOf("package", "change"), maxBody = 256 * 1024,
        example = "POST /v1/applets/installed/ai0123456789abcd/update {\"template\":\"my-notebook\",\"revision\":2,\"change\":{\"expected_revision\":3,\"intent\":\"Keep an extra month of notes\",\"acceptance\":[\"Existing notes remain readable\"],\"evidence\":\"Dry run passed and previous notes reopened on the test instance.\"}}",
        returns = "status awaiting_owner, pending_id and review. After the owner or delegated controller approves through /owner/configuration/approvals, read back the installed version and data.", errors = listOf("not_found", "invalid_parameter", "conflict", "capability_disabled"))
    val PAUSE = EndpointSpec("POST", "/applets/installed/{id}/pause", CAP, "Pause or resume an applet monitoring task and its device work; manual actions remain available once paused",
        body = listOf(Param("paused", "boolean", "true pauses, false resumes", required = true)),
        example = "POST /v1/applets/installed/ai0123456789abcd/pause {\"paused\":true}", returns = "the instance with lifecycle.state starting, running, pausing or paused, pending participants and lifecycle_errors; repeat pause to retry unconfirmed stops",
        errors = listOf("not_found", "invalid_parameter", "capability_disabled"))
    val REMOVE = EndpointSpec("DELETE", "/applets/installed/{id}", CAP, "Remove an installed applet you asked for and everything it created (watches, schedule, workflow, card, its grants)",
        example = "DELETE /v1/applets/installed/ai0123456789abcd", returns = "removed, left_behind[] (empty when nothing is left)", errors = listOf("not_found"))
    val ROWS = EndpointSpec("GET", "/applets/installed/{id}/tables/{table}/rows", CAP,
        "Saved table rows, oldest first after since. Owner control agents can read every applet table without approval or share_with_installer; applet principals remain restricted. Discover tables at /applets/installed/{id}/data",
        query = listOf(Param("limit", "integer", "1-200, default 50"), Param("since", "string", "A row id: only rows after it (the last page's next)")),
        example = "GET /v1/applets/installed/ai0123456789abcd/tables/receipts/rows?limit=50", returns = "items[{id, at, by, ...columns}], next (null at the end), count",
        errors = listOf("not_found", "not_shared"))
    val DATA = EndpointSpec("GET", "/applets/installed/{id}/data", CAP,
        "Discover an applet's saved data without running it: uniform schemas, row counts, choice/status counts, batch outcomes, export and original-photo URLs. Works for existing and future templates; owner control keys need no extra approval",
        example = "GET /v1/applets/installed/ai0123456789abcd/data",
        returns = "contract:bridgit-applet-data/2, instance_id, access, complete, excluded_tables[], tables[{id,label,columns,count,choice_counts,rows_url}], photos[{ref,available,url,archive_path}], media_complete, batches, export_url, archive_url, consistency, next",
        errors = listOf("not_found"))
    val RECORDS = EndpointSpec("GET", "/applets/installed/{id}/records", CAP,
        "Read an installed applet's existing free-form key/value records with an owner control key; no installer ownership or extra approval. Complements typed tables for older/custom applets",
        query = listOf(Param("after", "string", "Last page's next key; omit on the first page")),
        example = "GET /v1/applets/installed/ai0123456789abcd/records",
        returns = "records[{key,value,updated_at,chars}], count, next (pass as after until null); pages capped near 40 KB",
        errors = listOf("not_found", "not_shared", "invalid_parameter"))
    val EXPORT = EndpointSpec("GET", "/applets/installed/{id}/data/export", CAP,
        "Export all accessible saved rows, schemas, status and photo references as JSON, or ZIP with manifest.json and original JPEGs. Read-only; no model calls, captures or Android share sheet. Same control-key authorization as data",
        query = listOf(Param("format", "string", "json (default) or zip; at most 20 MB; larger datasets use paginated rows and individual photos")),
        example = "GET /v1/applets/installed/ai0123456789abcd/data/export?format=zip",
        returns = "JSON bridgit-applet-data/2 with tables[].rows, or application/zip containing manifest.json and photos/*.jpg; missing photos are explicitly marked, not recaptured; live read, not an atomic snapshot",
        errors = listOf("not_found", "invalid_parameter", "export_too_large"))
    val PHOTO = EndpointSpec("GET", "/applets/installed/{id}/photos/{photo}", CAP,
        "Read an existing applet JPEG using a photo URL from data; never captures a new photo. Control agents can read all saved applet photos; other principals only photos linked in accessible tables",
        query = listOf(Param("offset", "integer", "Optional byte offset for a large-photo chunk, at least 0"), Param("length", "integer", "Chunk byte length, 1-8388608; default 8388608 with offset. Omit both for a whole JPEG up to 20 MB")),
        example = "GET /v1/applets/installed/ai0123456789abcd/photos/0f3k2m9x1a2b",
        returns = "image/jpeg (whole photo) or application/octet-stream (chunk with X-Bridgit-Photo-Offset/Total/Next headers); authenticate with the same Bearer key, never put the key in the URL",
        errors = listOf("not_found", "invalid_parameter", "payload_too_large"))
    val ADD_ROW = EndpointSpec("POST", "/applets/installed/{id}/tables/{table}/rows", CAP,
        "Add a table row, checked against its columns. Owner control agents can act on any applet; applet principals need installer sharing",
        body = listOf(Param("row", "object", "column → value; missing columns are empty", required = true)),
        example = "POST /v1/applets/installed/ai0123456789abcd/tables/receipts/rows {\"row\":{\"text\":\"Coffee 3.20\"}}", returns = "the stored row {id, at, by, ...columns}",
        errors = listOf("not_found", "not_shared", "invalid_parameter", "quota_exceeded", "rate_limited"))
    val EDIT_ROW = EndpointSpec("PATCH", "/applets/installed/{id}/tables/{table}/rows/{row}", CAP,
        "Change table columns while preserving the row id and other values. Owner control agents can act on any applet; applet principals need installer sharing; values are validated",
        body = listOf(Param("set", "object", "column → new value; null empties a column", required = true)),
        example = "PATCH /v1/applets/installed/ai0123456789abcd/tables/receipts/rows/0f3k2m9x1a2b {\"set\":{\"total\":12.5}}", returns = "the stored row {id, at, by, ...columns}",
        errors = listOf("not_found", "not_shared", "row_not_found", "invalid_parameter"))
    val DELETE_ROW = EndpointSpec("DELETE", "/applets/installed/{id}/tables/{table}/rows/{row}", CAP,
        "Delete a declared table row using owner-UI table-store cleanup. Owner control agents can act on every applet; applet principals need installer sharing",
        example = "DELETE /v1/applets/installed/ai0123456789abcd/tables/receipts/rows/abc",
        returns = "deleted, existed", errors = listOf("not_found", "not_shared"))
    val BUDGET = EndpointSpec("GET", "/applets/{id}/budget", CAP,
        "An installed applet's budget on all the owner's phones: limits with used, forecast and reset, state (ok, warning, out), the owner's ceiling, whether agents may change it, its actions with intervals and projections, and the last changes. For the applet you asked for, or any applet when you have Budgets",
        example = "GET /v1/applets/ai0123456789abcd/budget",
        returns = "applet, instance_id, name, state (ok|warning|out|unknown), limits[{id, usd, per, check_at, top_up_usd, used_usd, resets_at, forecast_usd, forecast_runs_out_at, estimate}], source (package|owner|agent), ceiling, agents_may_change, actions[{action, every_s, min_s, max_s, per, priority, places, paid_share, usd_per_run, projected_usd, set_by}], revision, this_phone{spent_today_usd, spent_month_usd}, synced_at, changes[]. budget_forecast and budget_out events follow in /v1/events",
        errors = listOf("not_found"))
    val DEVELOPMENT = EndpointSpec("GET", "/applets/installed/{id}/development", CAP,
        "Read the exact personal package, template baseline, customization journal, revision and pending upgrade task. Owner control agents can inspect any installed applet; private, keep local unless the owner consents to model upload",
        example = "GET /v1/applets/installed/ai0123456789abcd/development",
        returns = "instance_id, revision, package, config, evolution{state, origin, baseline_known, baseline?, journal[], policy_generation, task?}, guide",
        errors = listOf("not_found"))
    val TEMPLATE_POLICY = EndpointSpec("POST", "/applets/installed/{id}/template-policy", CAP,
        "Track, pause template updates, or detach and repurpose your applet; never pauses execution or deletes data. Detached copies cannot silently reattach",
        body = listOf(Param("state", "string", "tracking, paused or detached; tracking resumes a paused relationship only", required=true), Param("expected_revision", "integer", "revision from development snapshot", required=true), Param("reason", "string", "Owner's requested reason, 1-1000 characters", required=true)),
        example = "POST /v1/applets/installed/ai0123456789abcd/template-policy {\"state\":\"detached\",\"expected_revision\":1,\"reason\":\"Repurpose as inventory\"}",
        returns = "the instance with template_state and revision", errors=listOf("not_found","conflict","invalid_parameter"))
    val PREPARE = EndpointSpec("POST", "/applets/installed/{id}/template-upgrade", CAP,
        "Fetch the same template origin. An unchanged applet gets an owner update review; a customized applet gets a local three-way upgrade task. Nothing applies automatically or calls a model. Read development first",
        body = SOURCE + Param("expected_revision", "integer", "revision from development; use url, template+revision or bundled, not an inline package", required=true),
        example = "POST /v1/applets/installed/ai0123456789abcd/template-upgrade {\"url\":\"https://bridgit.cc/recipes/voice-notebook/applet.json\",\"expected_revision\":1}",
        returns = "status:awaiting_owner with id (pending review), review and expected_revision for unchanged applets; otherwise task{id, type:applet.template_upgrade, status:needs_agent|needs_verification, expected_revision, candidate, conflicts, incoming, guide} or status:up_to_date; no automatic activation",
        errors=listOf("not_found","conflict","invalid_parameter"))
    val CONTEXT = EndpointSpec("GET", "/applets/context", CAP,
        "Discover typed device-context variables without collecting readings or granting access; consult live capabilities before reading",
        example="GET /v1/applets/context", returns="variables[{id,type,unit,operation,field,capability,purpose,freshness,fallback}], readiness:GET /v1/capabilities",
        errors=emptyList())
    val ALL = listOf(CATALOG, DEVELOPMENT, TEMPLATE_POLICY, PREPARE, CONTEXT, PRACTICES, VALIDATE, INSTALL, LIST, GET, UPDATE, PAUSE, REMOVE, DATA, RECORDS, EXPORT, PHOTO, ROWS, ADD_ROW, EDIT_ROW, DELETE_ROW, BUDGET)
}

class AppletModule(app: BridgeApp) : Capability(app) {
    override val id = "applet_install"
    override val titleRes = R.string.cap_applet_install
    override val descriptionRes = R.string.cap_applet_install_desc
    override val group = "apps"
    override val endpoints = AppletEndpoints.ALL

    private fun bad(e: Exception): Nothing = throw when (e) {
        is AppletPackage.Bad -> ErrorCatalog.error("invalid_parameter", e.message ?: "Invalid applet.")
        is AppletInstances.Refused -> ErrorCatalog.error(if (runCatching { ErrorCatalog.http(e.code) }.isSuccess) e.code else "conflict", e.message ?: "Refused.")
        is cc.bridgit.app.api.ApiError -> e
        is cc.bridgit.app.watch.Watches.Refused -> ErrorCatalog.error(e.code, e.message ?: "Refused.", e.extra)
        else -> ErrorCatalog.error("invalid_parameter", e.message ?: e.javaClass.simpleName)
    }

    /** What an agent sees of an instance: no full definition (it sent it), ids of what runs. */
    private fun view(r: JSONObject) = JSONObject().put("id", r.getString("id")).put("package_id", r.optString("package_id")).put("name", r.optString("name"))
        .put("lifecycle", r.optJSONObject("lifecycle") ?: AppletLifecycle.json(AppletLifecycle.read(r, emptyList())))
        .put("lifecycle_errors", r.optJSONObject("lifecycle_errors") ?: JSONObject())
        .put("run_mode", r.optJSONObject("definition")?.let(AppletPackage::runMode) ?: "unspecified")
        .put("version", r.optString("version")).put("revision", r.optLong("revision")).put("template_state", AppletEvolution.state(r).optString("state")).put("modification_guide", AppletEvolution.GUIDE).put("status", r.optString("status")).put("config", r.optJSONObject("config") ?: JSONObject())
        .apply { if (r.optString("native") != "dog") put("web_portal", app.remoteView.packages.status(r.optString("package_id")).put("include", if (cc.bridgit.app.remoteview.PortalRecords.always(r.optJSONObject("definition"))) "always" else "optional")) }
        .put("watches", JSONObject().also { o -> r.optJSONObject("watches")?.let { w -> w.keys().forEach { k -> o.put(k, w.getJSONObject(k).optString("watch_id")) } } })
        .put("card_id", r.optLong("card_id", -1).takeIf { it > 0 }?.toString() ?: JSONObject.NULL)
        .put("workflow_id", r.optString("workflow_id").ifEmpty { null } ?: JSONObject.NULL)
        .put("flows", JSONObject().also { o -> r.optJSONObject("flows")?.let { f -> f.keys().forEach { k -> o.put(k, f.optJSONObject(k)?.optString("workflow_id")) } } })
        .put("schedule", r.has("schedule_digest")).put("native", r.optString("native").ifEmpty { null } ?: JSONObject.NULL)
        .put("problem", r.optString("problem").ifEmpty { null } ?: JSONObject.NULL)
        .put("budget", AppletBudget.status(app, r))
        .put("data_url", "${AppletDataDiscovery.base(r)}/data").put("data_contract", AppletDataDiscovery.CONTRACT)
        .put("template_update", app.applets.templateUpdate(r)?.let { update ->
            JSONObject().put("status", update.optString("status"))
                .put("version", (update.optJSONObject("incoming") ?: update.optJSONObject("definition"))?.optString("version"))
                .put("id", update.optString("id"))
        } ?: JSONObject.NULL)
        .put("installed_at", r.optLong("installed_at")).put("updated_at", r.optLong("updated_at"))

    private val appletData get() = AppletDataDiscovery(app)

    /** An installed applet [agentId] asked for, and its table [table], which must be shared with that agent. */
    private fun sharedTable(agentId: String, id: String, table: String): Pair<JSONObject, AppletTables.Table> {
        return appletData.table(app.agents.get(agentId) ?: throw ErrorCatalog.error("not_found", "Agent no longer exists."), id, table)
    }

    private fun editable(agentId: String, id: String): JSONObject =
        app.applets.get(id)?.takeIf { AppletEditAccess(app).canEdit(it, agentId) }
            ?: throw ErrorCatalog.error("not_found", "No installed applet $id you can edit.")

    private fun mine(agentId: String, id: String): JSONObject =
        app.applets.get(id)?.takeIf { it.optString("requested_by") == agentId } ?: throw ErrorCatalog.error("not_found", "No installed applet $id that you asked for.")

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(AppletEndpoints.CATALOG) {
            val a = agent!!
            val q = query("q").orEmpty(); val after = query("after").orEmpty()
            if (q.length > 100 || after.length > 100) throw ErrorCatalog.error("invalid_parameter", "Search/cursor is too long.")
            val path = "/v1/templates/catalog?kind=applet&limit=50&q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&after=" + java.net.URLEncoder.encode(after, "UTF-8")
            val link = AppletPackage.Link("https://${AppletPackage.REGISTRY_HOST}$path", null, "catalog", null)
            Reply.Json(withContext(Dispatchers.IO) {
                JSONObject(AppletFetch.fetch(app, link, Outbound.Actor.agent(a.id, a.name), raw = true))
            })
        }
        endpoint(AppletEndpoints.DEVELOPMENT) {
            val r=if (AppletDataDiscovery.controls(agent!!)) appletData.record(agent!!,path("id").orEmpty()) else editable(agent!!.id,path("id").orEmpty())
            Reply.Json(JSONObject().put("instance_id",r.getString("id")).put("revision",r.optLong("revision"))
                .put("package",r.getJSONObject("definition")).put("config",r.optJSONObject("config") ?: JSONObject())
                .put("practice_compliance",AppletPractices.assess(r.getJSONObject("definition"))).put("evolution",AppletEvolution.state(r)).put("guide",AppletEvolution.GUIDE).put("practice_review","Before proposing an update, review the candidate with GLM 5.3 Flash: https://bridgit.cc/learn/builder/practice-review.md"))
        }
        endpoint(AppletEndpoints.TEMPLATE_POLICY) {
            val r=mine(agent!!.id,path("id").orEmpty())
            val revision=(body.opt("expected_revision") as? Number)?.toLong() ?: throw ErrorCatalog.error("invalid_parameter","expected_revision is required.")
            Reply.Json(view(try { app.applets.setTemplatePolicy(r.getString("id"),body.optString("state"),revision,body.optString("reason"),agent!!.id) } catch(e: Exception) { bad(e) }))
        }
        endpoint(AppletEndpoints.PREPARE) {
            val a=agent!!;val r=mine(a.id,path("id").orEmpty())
            if (!AppletEvolution.tracking(r)) throw ErrorCatalog.error("conflict","Template syncing is paused or detached; no fetch attempted.")
            if (body.has("package")) throw ErrorCatalog.error("invalid_parameter","Fetch the original template using url or template+revision; an inline package is a personal edit.")
            val revision=(body.opt("expected_revision") as? Number)?.toLong() ?: throw ErrorCatalog.error("invalid_parameter","expected_revision is required.")
            if (revision != r.optLong("revision")) throw ErrorCatalog.error("conflict","Read a fresh development snapshot first.")
            Reply.Json(try {
                val (p,source)=withContext(Dispatchers.IO) { AppletFetch.fromBody(app,body,Outbound.Actor.agent(a.id,a.name)) }
                app.applets.prepareTemplate(r.getString("id"),p,source,revision,a.id)
            } catch(e: Exception) { bad(e) })
        }
        endpoint(AppletEndpoints.PRACTICES) { Reply.Json(AppletPracticeCatalog.get()) }
        endpoint(AppletEndpoints.CONTEXT) { Reply.Json(AppletContext.catalog()) }

        endpoint(AppletEndpoints.VALIDATE) {
            val a = agent!!
            val out = withContext(Dispatchers.IO) {
                try {
                    // Phase 2: a missing asset sha256, permissions.required and the registry-only sections are filled in,
                    // and the complete file comes back as package, ready to install or publish.
                    val (pre, fillPermissions) = body.optJSONObject("package")?.let { AppletPackage.prefill(it) } ?: (null to false)
                    val (p0, _) = AppletFetch.fromBody(app, if (pre != null) JSONObject(body.toString()).put("package", pre) else body, Outbound.Actor.agent(a.id, a.name))
                    val cfg = app.applets.filledConfig(p0, body.optJSONObject("config"), app.applets.byPackage(p0.id))
                    val plan = AppletPackage.compile(p0, cfg)
                    // Checked like review does, so a bad workflow comes back as valid:false with its words, not as an error.
                    val derived = try { app.applets.derived(plan) } catch (e: AppletPackage.Bad) { throw e } catch (e: Exception) { throw AppletPackage.Bad("Workflow: ${e.message}") }
                    val full = AppletPackage.completed(p0.definition, plan, derived, fillPermissions)
                    val p = AppletPackage.check(full)
                    JSONObject().put("valid", AppletPractices.assess(full).getBoolean("valid")).put("practice_compliance", AppletPractices.assess(full)).put("review", app.applets.review(p, cfg, app.applets.byPackage(p.id)?.getString("id")))
                        .put("practice_review","Before publishing or customizing: https://bridgit.cc/learn/builder/practice-review.md").put("package", full).put("data_contract", AppletDataDiscovery.CONTRACT)
                        .put("data_discovery", "After install, follow data_url from GET /v1/applets/installed. The runtime derives schemas, counts, rows, records, saved photos and export from the package; no custom exporter is needed.")
                        .put("warnings", JSONArray(AppletPackage.policyWarnings(full, plan)))
                } catch (e: AppletPackage.Bad) { JSONObject().put("valid", false).put("errors", JSONArray().put(e.message)) }
            }
            Reply.Json(out)
        }
        endpoint(AppletEndpoints.INSTALL) {
            val a = agent!!
            val out = withContext(Dispatchers.IO) {
                try {
                    val (p, source) = AppletFetch.fromBody(app, body, Outbound.Actor.agent(a.id, a.name))
                    if (source.optString("kind") != "template" || p.definition.optJSONObject("ui")?.has("practices") == true) AppletPractices.requireChange(p.definition)
                    app.applets.byPackage(p.id)?.let { throw ErrorCatalog.error("conflict", "${p.name} is already installed (${it.getString("id")}). Use POST /v1/applets/installed/{id}/update.") }
                    app.applets.propose(p, body.optJSONObject("config"), source, a)
                } catch (e: Exception) { bad(e) }
            }
            message = "${a.name} asked to install applet ${out.optJSONObject("review")?.optString("name")}"
            Reply.Json(JSONObject().put("status", "awaiting_owner").put("pending_id", out.getString("id")).put("review", out.getJSONObject("review"))
                .put("next", "The owner reviews it on the phone. Watch /v1/events for applet.installed (with instance_id) or applet.declined."), 202)
        }
        endpoint(AppletEndpoints.LIST) {
            Reply.Json(JSONObject().put("installed", JSONArray(app.applets.all().filter { AppletDataDiscovery.visible(agent!!, it) }.map(::view)))
                .put("pending", JSONArray(app.applets.pending().filter { AppletDataDiscovery.visible(agent!!, it) }.map {
                    JSONObject().put("pending_id", it.getString("id")).put("package_id", it.getJSONObject("definition").optString("id")).put("replaces", it.opt("replaces")).put("created_at", it.optLong("created_at"))
                })))
        }
        endpoint(AppletEndpoints.GET) { Reply.Json(view(appletData.record(agent!!, path("id").orEmpty()))) }
        endpoint(AppletEndpoints.BUDGET) {
            // The applet you asked for; with Budgets, any installed applet (the Pet monitor has no asker).
            val a = agent!!
            val r = app.applets.get(path("id").orEmpty())?.takeIf { it.optString("requested_by") == a.id || (a.allows(BudgetModule.ID) && app.settings.isOn(BudgetModule.ID)) }
                ?: throw ErrorCatalog.error("not_found", "No installed applet ${path("id")} that you asked for.")
            app.budgetSync.soonIfStale("an agent read")
            Reply.Json(BudgetModule.view(app, r))
        }
        endpoint(AppletEndpoints.UPDATE) {
            val a = agent!!
            val r = editable(a.id, path("id").orEmpty())
            // The control agent holds the proposed source. Keep private snapshots on-device
            // and ask it to review its sanitized candidate before opening owner approval.
            val disposition = body.optJSONObject("practice_review")
            val reviewed = disposition?.optString("model") == "z-ai/glm-5.3-flash" &&
                disposition.optString("source_sha256").matches(Regex("[0-9a-f]{64}")) &&
                disposition.optString("disposition").isNotBlank()
            val unavailable = disposition?.optString("status") == "unavailable" && disposition.optString("reason").isNotBlank()
            if (!reviewed && !unavailable) {
                return@endpoint Reply.Json(JSONObject().put("status","needs_practice_review")
                    .put("model","z-ai/glm-5.3-flash").put("workflow","customize")
                    .put("review_path","/v1/templates/practice-review")
                    .put("instructions","Review your sanitized candidate. Test adopted edits, then retry with practice_review {model,source_sha256,disposition}. If cloud review is unavailable, report {status:unavailable,reason}.")
                    .put("guide","https://bridgit.cc/learn/builder/practice-review.md"))
            }
            val out = try {
                val (p, source) = withContext(Dispatchers.IO) { AppletFetch.fromBody(app, body, Outbound.Actor.agent(a.id, a.name)) }
                if (source.optString("kind") != "template" || p.definition.optJSONObject("ui")?.has("practices") == true || r.getJSONObject("definition").optJSONObject("ui")?.has("practices") == true) AppletPractices.requireChange(p.definition, r.getJSONObject("definition"))
                app.applets.update(r.getString("id"), p, body.optJSONObject("config"), source, a, AppletEvolution.change(body.optJSONObject("change")))
            } catch (e: Exception) { bad(e) }
            out.optJSONObject("instance")?.let { out.put("instance", view(it)) }
            if (out.optString("status") == "awaiting_owner") { out.put("pending_id", out.optString("id")); out.remove("definition"); out.remove("id") }
            Reply.Json(out)
        }
        endpoint(AppletEndpoints.PAUSE) {
            val paused = body.opt("paused") as? Boolean ?: throw ErrorCatalog.error("invalid_parameter", "paused must be true or false.")
            val r = mine(agent!!.id, path("id").orEmpty())
            Reply.Json(view(try { app.applets.setPaused(r.getString("id"), paused) } catch (e: Exception) { bad(e) }))
        }
        endpoint(AppletEndpoints.DATA) {
            val r = appletData.record(agent!!, path("id").orEmpty())
            Reply.Json(withContext(Dispatchers.IO) { appletData.catalog(agent!!, r) })
        }
        endpoint(AppletEndpoints.RECORDS) {
            val r = appletData.record(agent!!, path("id").orEmpty())
            Reply.Json(withContext(Dispatchers.IO) { appletData.records(agent!!, r, query("after")) })
        }
        endpoint(AppletEndpoints.EXPORT) {
            val r = appletData.record(agent!!, path("id").orEmpty())
            val format = query("format") ?: "json"
            if (format !in setOf("json", "zip")) throw ErrorCatalog.error("invalid_parameter", "format must be json or zip.")
            val bytes = withContext(Dispatchers.IO) { appletData.export(agent!!, r, format == "zip") }
            Reply.Bytes(bytes, if (format == "zip") "application/zip" else "application/json",
                mapOf("Content-Disposition" to "attachment; filename=applet-data.$format", "Cache-Control" to "no-store"))
        }
        endpoint(AppletEndpoints.PHOTO) {
            val r = appletData.record(agent!!, path("id").orEmpty())
            withContext(Dispatchers.IO) {
                val file = appletData.photo(agent!!, r, path("photo").orEmpty())
                val chunk = query("offset") != null || query("length") != null
                val offset = query("offset")?.toLongOrNull() ?: if (query("offset") == null) 0L else -1L
                val length = query("length")?.toIntOrNull() ?: if (query("length") == null) 8 * 1024 * 1024 else -1
                if (offset < 0 || (chunk && offset >= file.length()) || length !in 1..8 * 1024 * 1024)
                    throw ErrorCatalog.error("invalid_parameter", "Use offset within the file and length from 1 to 8388608.")
                if (!chunk && file.length() > AppletDataDiscovery.MAX_EXPORT_BYTES)
                    throw ErrorCatalog.error("payload_too_large", "Photo exceeds 20 MB. Download chunks with offset=0&length=8388608, then advance offset by bytes received until total.")
                val total = file.length()
                val size = if (chunk) minOf(length.toLong(), total - offset).toInt() else total.toInt()
                val bytes = ByteArray(size)
                java.io.RandomAccessFile(file, "r").use { input -> input.seek(offset); input.readFully(bytes) }
                Reply.Bytes(bytes, if (chunk) "application/octet-stream" else "image/jpeg", mapOf("Cache-Control" to "no-store",
                    "X-Bridgit-Photo-Offset" to offset.toString(), "X-Bridgit-Photo-Total" to total.toString(),
                    "X-Bridgit-Photo-Next" to if (offset + size < total) (offset + size).toString() else ""))
            }
        }
        endpoint(AppletEndpoints.ROWS) {
            val (r, t) = sharedTable(agent!!.id, path("id").orEmpty(), path("table").orEmpty())
            val limit = query("limit")?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            Reply.Json(AppletDataDiscovery.rowPage(AppletTableStore.of(app), r.getString("agent_id"), t.id, limit, query("since")))
        }
        endpoint(AppletEndpoints.ADD_ROW) {
            val (r, t) = sharedTable(agent!!.id, path("id").orEmpty(), path("table").orEmpty())
            val row = body.optJSONObject("row") ?: throw ErrorCatalog.error("invalid_parameter", "row must be an object of column → value.")
            val out = try { AppletTableStore.of(app).add(r.getString("agent_id"), t, row, JSONObject().put("type", "agent").put("agent_id", agent!!.id)) }
                catch (e: AppletTables.Refused) { throw ErrorCatalog.error(when (e.code) { "quota_exceeded", "rate_limited" -> e.code; else -> "invalid_parameter" }, e.message ?: e.code) }
            Reply.Json(out)
        }
        endpoint(AppletEndpoints.EDIT_ROW) {
            val (r, t) = sharedTable(agent!!.id, path("id").orEmpty(), path("table").orEmpty())
            val set = body.optJSONObject("set") ?: throw ErrorCatalog.error("invalid_parameter", "set must be an object of column → value.")
            if (set.length() == 0) throw ErrorCatalog.error("invalid_parameter", "set must change at least one column.")
            val out = try { AppletTableStore.of(app).update(r.getString("agent_id"), t, path("row").orEmpty(), set, JSONObject().put("type", "agent").put("agent_id", agent!!.id)) }
                catch (e: AppletTables.Refused) { throw ErrorCatalog.error(if (e.code == "row_not_found" || e.code == "rate_limited") e.code else "invalid_parameter", e.message ?: e.code) }
            Reply.Json(out)
        }
        endpoint(AppletEndpoints.DELETE_ROW) {
            val (r, t) = sharedTable(agent!!.id, path("id").orEmpty(), path("table").orEmpty())
            val row = path("row").orEmpty()
            Reply.Json(JSONObject().put("deleted", row).put("existed", AppletTableStore.of(app).delete(r.getString("agent_id"), t.id, row)))
        }
        endpoint(AppletEndpoints.REMOVE) {
            val r = mine(agent!!.id, path("id").orEmpty())
            Reply.Json(try { app.applets.remove(r.getString("id")) } catch (e: Exception) { bad(e) })
        }
    }
}
