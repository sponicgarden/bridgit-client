package cc.bridgit.app.schedule

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.capability.SpeakerRules
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object ScheduleEndpoints {
    private const val CAP = "schedule"
    val SET = EndpointSpec("POST", "/schedule", CAP,
        "Hand the phone an action manifest once: any action it can take (any /v1 call you're allowed, plus download, play, show, send and delete of saved files), each with its own schedule. The owner approves each new manifest once on the phone; then it runs by itself and results arrive as schedule.* events",
        idempotent = true, privateFields = setOf("manifest"),
        body = listOf(
            Param("manifest", "object", "{timezone?, actions:[{id, name?, when, do, save_as?, send_to?}]} (see GET /v1/guide → schedule)"),
            Param("url", "string", "Instead of manifest: a public https:// URL the phone polls for the manifest (ETag honoured)"),
            Param("poll_minutes", "integer", "With url: how often to check, 15-1440 (default 60)")),
        example = "POST /v1/schedule {\"manifest\":{\"actions\":[{\"id\":\"pics\",\"when\":{\"start\":\"2026-11-02T16:34\",\"every_minutes\":7,\"count\":3},\"do\":{\"call\":\"POST /v1/camera/snapshot\",\"query\":{\"raw\":\"1\"}},\"send_to\":{\"url\":\"https://example.com/in\"}}]}}",
        returns = "status (awaiting_owner, or active when identical to the running manifest), digest, actions[{id, summary}]",
        errors = listOf("capability_disabled", "invalid_parameter", "agent_not_allowed"))
    val GET = EndpointSpec("GET", "/schedule", CAP, "Your manifest: what's approved, what's waiting for the owner, each action's next run and last result, and your saved files",
        example = "GET /v1/schedule",
        returns = "status (none, awaiting_owner, active, paused), timezone, source{url, poll_minutes, last_poll, last_error}, active{digest, actions[{id, summary, next_at, runs, last_status, last_error}]}, pending{digest, actions}, assets[]")
    val RUN = EndpointSpec("POST", "/schedule/run/{id}", CAP, "Run one approved action now, outside its timetable (its 'after' chain doesn't follow)",
        example = "POST /v1/schedule/run/pics", returns = "action_id, status (ok or failed), result{runs, last_status, last_error}",
        errors = listOf("not_found", "capability_disabled"))
    val PAUSE = EndpointSpec("POST", "/schedule/pause", CAP, "Pause or resume your approved actions",
        body = listOf(Param("paused", "boolean", "true pauses, false resumes", required = true)),
        example = "POST /v1/schedule/pause {\"paused\":true}", returns = "paused", errors = listOf("not_found", "invalid_parameter"))
    val DELETE = EndpointSpec("DELETE", "/schedule", CAP, "Remove your manifest (approved and waiting) and, unless keep_assets=true, your saved files",
        query = listOf(Param("keep_assets", "boolean", "Keep saved files (default false)")),
        example = "DELETE /v1/schedule", returns = "removed")
    val ASSETS = EndpointSpec("GET", "/schedule/assets", CAP, "Files your actions saved (downloads and kept results), up to 100 / 100 MB, kept 30 days",
        example = "GET /v1/schedule/assets", returns = "assets[{name, mime, size, sha256, created_at, by}]")
    val ASSET = EndpointSpec("GET", "/schedule/assets/{name}", CAP, "One saved file's bytes, e.g. a photo a scheduled action took",
        media = true, example = "GET /v1/schedule/assets/garden", returns = "The raw bytes with their Content-Type", errors = listOf("not_found"))
    val ASSET_DELETE = EndpointSpec("DELETE", "/schedule/assets/{name}", CAP, "Delete one saved file",
        example = "DELETE /v1/schedule/assets/garden", returns = "deleted", errors = listOf("not_found"))
    val MANIFEST=EndpointSpec("GET","/schedule/manifest",CAP,"Your exact approved and pending action manifests for inspection or resubmission",
        example="GET /v1/schedule/manifest",returns="active and pending manifest (your submissions only), digests; reading does not approve or change anything")
    val all = listOf(MANIFEST, SET, GET, RUN, PAUSE, DELETE, ASSETS, ASSET, ASSET_DELETE)
}

class ScheduleModule(app: BridgeApp) : Capability(app) {
    override val id = "schedule"
    override val titleRes = R.string.cap_schedule
    override val descriptionRes = R.string.cap_schedule_desc
    override val group = "files"
    override val switchKey = Schedules.SWITCH
    override val endpoints = ScheduleEndpoints.all
    override fun details() = JSONObject().put("min_poll_minutes", ScheduleRules.MIN_POLL_MINUTES).put("max_actions", ScheduleRules.MAX_ACTIONS)
        .put("local_actions", JSONArray(ScheduleRules.LOCAL_VERBS)).put("timing", "Runs on time while Bridgit's listener runs; Android may delay runs by minutes while the phone dozes.")

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(ScheduleEndpoints.SET) {
            requireUsable()
            val a = agent!!
            val result = withContext(Dispatchers.IO) {
                when {
                    body.has("manifest") && body.has("url") -> throw ErrorCatalog.error("invalid_parameter", "Send manifest or url, not both.")
                    body.has("manifest") -> app.schedules.submit(a, body.optJSONObject("manifest") ?: throw ErrorCatalog.error("invalid_parameter", "manifest must be an object."), null)
                    body.has("url") -> {
                        val url = body.optString("url")
                        if (!SpeakerRules.urlAllowed(url)) throw ErrorCatalog.error("invalid_parameter", "url must be a public https:// address.")
                        val minutes = if (body.has("poll_minutes")) (body.opt("poll_minutes") as? Int)?.takeIf { it in ScheduleRules.MIN_POLL_MINUTES..1440 }
                            ?: throw ErrorCatalog.error("invalid_parameter", "poll_minutes must be ${ScheduleRules.MIN_POLL_MINUTES} to 1440.") else 60
                        params.put("url", ScheduleRules.host(url))
                        app.schedules.poll(a, JSONObject().put("url", url).put("poll_minutes", minutes))
                            ?: JSONObject().put("status", "unchanged")
                    }
                    else -> throw ErrorCatalog.error("invalid_parameter", "Send {\"manifest\":{...}} or {\"url\":\"https://...\"}.")
                }
            }
            message = "${a.name} sent scheduled actions (${result.optString("status")})"
            Reply.Json(result.put("note", if (result.optString("status") == "awaiting_owner") "The owner approves this manifest once on the phone; the schedule.approved event tells you when." else "Already approved and running."))
        }
        endpoint(ScheduleEndpoints.MANIFEST) {
            val record=app.schedules.record(agent!!.id)
            Reply.Json(JSONObject().put("active",record?.optJSONObject("active")?.optJSONObject("manifest") ?: JSONObject.NULL)
                .put("pending",record?.optJSONObject("pending")?.optJSONObject("manifest") ?: JSONObject.NULL))
        }
        endpoint(ScheduleEndpoints.GET) { Reply.Json(view(agent!!.id)) }
        endpoint(ScheduleEndpoints.RUN) {
            requireUsable()
            val id = path("id") ?: throw ErrorCatalog.error("invalid_parameter", "id is required.")
            Reply.Json(app.schedules.runNow(agent!!.id, id))
        }
        endpoint(ScheduleEndpoints.PAUSE) {
            val paused = body.opt("paused") as? Boolean ?: throw ErrorCatalog.error("invalid_parameter", "paused must be true or false.")
            app.schedules.record(agent!!.id)?.optJSONObject("active") ?: throw ErrorCatalog.error("not_found", "No approved schedule.")
            app.schedules.setPaused(agent!!.id, paused, byOwner = false)
            Reply.Json(JSONObject().put("paused", paused))
        }
        endpoint(ScheduleEndpoints.DELETE) {
            app.schedules.remove(agent!!.id, keepAssets = query("keep_assets") == "true")
            Reply.Json(JSONObject().put("removed", true))
        }
        endpoint(ScheduleEndpoints.ASSETS) { Reply.Json(JSONObject().put("assets", assetList(agent!!.id))) }
        endpoint(ScheduleEndpoints.ASSET) {
            val name = path("name") ?: ""
            val meta = app.schedules.asset(agent!!.id, name) ?: throw ErrorCatalog.error("not_found", "No saved file \"$name\".")
            val file = app.schedules.assetFile(agent!!.id, meta)
            Reply.Bytes(withContext(Dispatchers.IO) { file.readBytes() }, meta.getString("mime"), mapOf("Content-Disposition" to "attachment"))
        }
        endpoint(ScheduleEndpoints.ASSET_DELETE) {
            if (!app.schedules.deleteAsset(agent!!.id, path("name") ?: "")) throw ErrorCatalog.error("not_found", "No saved file of that name.")
            Reply.Json(JSONObject().put("deleted", true))
        }
    }

    private fun assetList(agentId: String): JSONArray {
        val assets = app.schedules.assets(agentId)
        return JSONArray(assets.keys().asSequence().sorted().map { n ->
            val m = assets.getJSONObject(n)
            JSONObject().put("name", n).put("mime", m.optString("mime")).put("size", m.optLong("size")).put("sha256", m.optString("sha256"))
                .put("created_at", ScheduleRules.iso(m.optLong("created_at"), app.schedules.zone())).put("by", m.optString("by"))
        }.toList())
    }

    fun view(agentId: String): JSONObject {
        val s = app.schedules
        val agent = app.agents.get(agentId) ?: return JSONObject().put("status", "none")
        val r = s.record(agentId) ?: return JSONObject().put("status", "none").put("timezone", s.zone().id).put("assets", JSONArray())
        val zone = s.zone()
        fun iso(ms: Long) = ScheduleRules.iso(ms, zone)
        val out = JSONObject().put("timezone", zone.id)
        r.optJSONObject("source")?.let { src ->
            out.put("source", JSONObject().put("url", src.optString("url")).put("poll_minutes", src.optInt("poll_minutes"))
                .put("last_poll", src.optLong("last_poll").takeIf { it > 0 }?.let(::iso) ?: JSONObject.NULL).put("last_error", src.optString("last_error").ifEmpty { null } ?: JSONObject.NULL))
        }
        r.optJSONObject("active")?.let { act ->
            val m = runCatching { s.parse(agent, act.getJSONObject("manifest")) }.getOrNull()
            val state = r.optJSONObject("state") ?: JSONObject()
            out.put("active", JSONObject().put("digest", act.optString("digest")).put("approved_at", iso(act.optLong("approved_at")))
                .put("actions", JSONArray(m?.actions?.map { a ->
                    val st = state.optJSONObject(a.id) ?: JSONObject()
                    val next = ScheduleRules.nextSlot(a.whenSpec, if (st.has("last_slot")) st.getLong("last_slot") else null, st.optInt("consumed"))
                    JSONObject().put("id", a.id).put("name", a.name ?: JSONObject.NULL).put("summary", ScheduleRules.describe(a, m.zone))
                        .put("next_at", next?.let(::iso) ?: JSONObject.NULL).put("runs", st.optInt("runs"))
                        .put("last_run_at", st.optLong("last_run_at").takeIf { it > 0 }?.let(::iso) ?: JSONObject.NULL)
                        .put("last_status", st.optString("last_status").ifEmpty { null } ?: JSONObject.NULL)
                        .put("last_error", st.optString("last_error").ifEmpty { null } ?: JSONObject.NULL)
                } ?: emptyList<JSONObject>())))
        }
        r.optJSONObject("pending")?.let { p ->
            val m = runCatching { s.parse(agent, p.getJSONObject("manifest")) }.getOrNull()
            out.put("pending", JSONObject().put("digest", p.optString("digest")).put("received_at", iso(p.optLong("received_at")))
                .put("actions", JSONArray(m?.actions?.map { JSONObject().put("id", it.id).put("summary", ScheduleRules.describe(it, m.zone)) } ?: emptyList<JSONObject>())))
        }
        out.put("status", when {
            r.has("active") && r.optBoolean("paused") -> "paused"
            r.has("pending") -> "awaiting_owner"
            r.has("active") -> "active"
            else -> "none"
        })
        return out.put("assets", assetList(agentId))
    }
}
