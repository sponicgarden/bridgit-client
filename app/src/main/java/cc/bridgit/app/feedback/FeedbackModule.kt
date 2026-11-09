package cc.bridgit.app.feedback

import android.util.Base64
import io.ktor.server.routing.Route
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Reply
import cc.bridgit.app.api.Req
import cc.bridgit.app.api.Sizing
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.capability.ImageUtil
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.log.LogQuery
import org.json.JSONArray
import org.json.JSONObject

/**
 * Section 11.5: the Issues Tracking API. Any agent may file an issue (FBK-12), list and read the
 * issues it filed, and follow up on them (FBK-12a). Reading everyone's issues, their
 * screenshots, context and log, and moving them needs the agent's Developer switch (AGT-15),
 * because issues carry screenshots of the owner's phone. Always on: there is no master switch.
 */
class FeedbackModule(app: BridgeApp) : Capability(app) {
    override val id = "feedback"
    override val titleRes = R.string.cap_feedback
    override val descriptionRes = R.string.cap_feedback_desc
    override val group = "status"
    // team-key before {id}: path matchers (relay, card actions) take the first spec that fits.
    // /v1/feedback… are the deprecated pre-rename aliases (old JSON keys), kept until the old names are removed.
    override val endpoints = listOf(Endpoints.ISSUES_LIST, Endpoints.ISSUES_TEAM_KEY, Endpoints.ISSUES_GET, Endpoints.ISSUES_STATUS,
        Endpoints.ISSUES_FOLLOWUP, Endpoints.ISSUES_POST) + Endpoints.FEEDBACK_LEGACY

    private fun Req.isDeveloper() = agent?.let { app.agents.get(it.id) }?.fixer == true

    /** FBK-14: a status check pulls the Bridgit team's latest first (at most once a minute, a few seconds at most). */
    private suspend fun freshen() = withContext(Dispatchers.IO) { FeedbackStatusSync.freshen(app) }

    private fun Req.requireDeveloper() {
        cc.bridgit.app.api.PowerGate.require(agent?.let { app.agents.get(it.id) }, cc.bridgit.app.core.Power.FIXER, "Reading every bug report")
    }

    private fun mapError(e: FeedbackError): Nothing = when (e.code) {
        "not_found" -> throw ErrorCatalog.error("not_found", e.message ?: "No such issue.")
        "rate_limited" -> throw ErrorCatalog.error("rate_limited", e.message ?: "Too many issues.", mapOf("retry_after_s" to 3600))
        "internal" -> throw ErrorCatalog.error("internal_error", e.message ?: "The issue couldn't be saved.")
        else -> throw ErrorCatalog.error("invalid_parameter", e.message ?: "Bad issue.")
    }

    /** FBK-15: why there's no team key, as a catalog error with the no-phone fallback in the message. */
    private fun teamKeyError(e: TeamKeys.Failure): Nothing {
        val msg = "${e.message} ${TeamKeys.fallbackHint()}"
        throw when (e.reason) {
            "uploads_off", "not_configured" -> ErrorCatalog.error("capability_disabled", msg,
                mapOf("setting" to "privacy.feedback_upload", "reason" to e.reason, "fallback" to "/v1/agents/register"))
            "rate_limited" -> ErrorCatalog.error("rate_limited", msg, mapOf("retry_after_s" to 3600, "reason" to e.reason))
            "agent_blocked" -> ErrorCatalog.error("forbidden", msg, mapOf("reason" to e.reason))
            else -> ErrorCatalog.error("team_unavailable", msg, mapOf("reason" to e.reason, "retryable" to e.retryable,
                "backend_http" to e.http.takeIf { it > 0 }, "fallback" to "/v1/agents/register"))
        }
    }

    private fun kindPhrase(kind: IssueKind) = when (kind) {
        IssueKind.BUG -> "a bug report"; IssueKind.FEATURE -> "a feature request"; IssueKind.APPLET -> "an applet request"
    }

    /** The applet request's fields (A0178), on applet requests only. */
    private fun JSONObject.withApplet(r: Issue) = apply { if (r.kind == IssueKind.APPLET) put("applet", r.applet ?: JSONObject.NULL) }

    /** Links an agent uses to check back on issue [id]. */
    private fun links(id: Long, base: String) = JSONObject().put("url", "$base/$id").put("followup_url", "$base/$id/followup")

    private fun Req.issueId(): Long = path("id")?.toLongOrNull()
        ?: throw ErrorCatalog.error("invalid_parameter", "id must be an issue number, e.g. 7.")

    override fun Route.install(api: ApiServer) {
        installIssues(api, legacy = false)
        installIssues(api, legacy = true)
    }

    /** [legacy]: the pre-rename /v1/feedback… aliases, which still answer with the old keys (`reports`). */
    private fun Route.installIssues(api: ApiServer, legacy: Boolean) = with(api) {
        val base = if (legacy) "/v1/feedback" else "/v1/issues"
        fun spec(new: cc.bridgit.app.api.EndpointSpec, old: cc.bridgit.app.api.EndpointSpec) = if (legacy) old else new
        endpoint(spec(Endpoints.ISSUES_LIST, Endpoints.FEEDBACK_LIST)) {
            // Developer agents see every issue (or theirs with mine=true); other agents only the ones they filed.
            val mine = query("mine")?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
            val onlyFor = if (mine || !isDeveloper()) agent!!.id else null
            val status = query("status")?.let { IssueStatus.of(it) ?: throw ErrorCatalog.error("invalid_parameter", "Unknown status \"$it\".") }
            val kind = query("kind")?.let { IssueKind.of(it) ?: throw ErrorCatalog.error("invalid_parameter", "kind must be ${IssueKind.names}.") }
            val since = query("since")?.let { LogQuery.iso(it) ?: throw ErrorCatalog.error("invalid_parameter", "since must be an ISO time.") }
            val limit = query("limit")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..100 } ?: throw ErrorCatalog.error("invalid_parameter", "limit must be 1 to 100.") } ?: 50
            val wait = query("wait")?.let { it.toIntOrNull()?.takeIf { n -> n in 0..55 } ?: throw ErrorCatalog.error("invalid_parameter", "wait must be 0 to 55.") } ?: 0
            if (wait > 0 && since == null) throw ErrorCatalog.error("invalid_parameter", "wait needs since (an ISO time).")
            // Read the change counter before querying, so an issue filed in between is never missed.
            freshen()
            var seen = app.feedback.changed.value
            var list = app.feedback.list(status, kind, since, limit, onlyFor)
            if (list.isEmpty() && wait > 0) {
                if (!api.waits.enter(agent!!.id)) throw ErrorCatalog.error("too_many_waits", "At most 4 open waits per agent.")
                try {
                    withTimeoutOrNull(wait * 1000L) {
                        while (list.isEmpty()) {
                            app.feedback.changed.first { it > seen }
                            seen = app.feedback.changed.value
                            list = app.feedback.list(status, kind, since, limit, onlyFor)
                        }
                    }
                } finally { api.waits.exit(agent!!.id) }
                // The Developer switch may have been turned off (or the agent removed) during the wait.
                if (onlyFor == null) requireDeveloper()
                else if (app.agents.get(agent!!.id) == null) throw ErrorCatalog.error("unauthorized", "$agentName was removed from Bridgit.")
            }
            params.put("returned", list.size)
            Reply.Json(JSONObject().put(if (legacy) "reports" else "issues", JSONArray(list.map { it.summaryJson(::isoAt) })))
        }

        endpoint(spec(Endpoints.ISSUES_GET, Endpoints.FEEDBACK_GET)) {
            val id = issueId()
            val dev = isDeveloper()
            app.feedback.get(id)?.takeIf { dev || it.filedBy(agent!!.id) } ?: throw ErrorCatalog.error("not_found", "No issue $id.")
            freshen()
            var r = app.feedback.get(id) ?: throw ErrorCatalog.error("not_found", "No issue $id.")
            val own = r.filedBy(agent!!.id)
            val history = JSONArray(r.history.map { it.toJson(::isoAt) })
            if (!dev) {
                // The filing agent gets its issue's text, status and history; never the owner's screenshots, context or log.
                data = JSONObject().put("issue", r.number)
                return@endpoint Reply.Json(r.summaryJson(::isoAt).put("description", r.description).withApplet(r)
                    .put("team_note", r.teamNote ?: JSONObject.NULL).put("history", history)
                    .put("followup_outcomes", JSONArray(StatusMachine.outcomesForReporter(r.status))).put("links", links(r.id, base)))
            }
            // FBK-11a: the first developer read moves a new (or reopened) issue to seen.
            if (r.status == IssueStatus.NEW || r.status == IssueStatus.REOPENED)
                r = runCatching { app.feedback.move(id, IssueStatus.SEEN, Actor.AUTO, agentName, null) }.getOrDefault(r)
            val spec = Sizing.resolve({ arg(it) }, "agent")
            var total = 0L
            val images = JSONArray()
            r.images.forEachIndexed { i, name ->
                val f = app.feedback.imageFile(name) ?: return@forEachIndexed
                val img = ImageUtil.prepare(f.readBytes(), "image/jpeg", spec)
                total += img.bytes.size
                images.put(JSONObject().put("index", i + 1).put("width_px", img.width).put("height_px", img.height)
                    .put("size_bytes", img.bytes.size).put("data_base64", Base64.encodeToString(img.bytes, Base64.NO_WRAP)))
            }
            if (total * 4 / 3 > ApiServer.MAX_PAYLOAD) throw ErrorCatalog.error("payload_too_large", "The images are over 12 MB. Ask with size=thumb.")
            bytesOut = total
            data = JSONObject().put("issue", r.number)
            Reply.Json(r.summaryJson(::isoAt).put("description", r.description).withApplet(r).put("context", r.context)
                .put("images", images).put("log", r.log)
                .put("team_note", r.teamNote ?: JSONObject.NULL)
                .put("history", JSONArray(r.history.map { it.toJson(::isoAt) }))
                .put("allowed_next", JSONArray(StatusMachine.nextForDeveloper(r.status).map { it.id }))
                .put("followup_outcomes", JSONArray(if (own) StatusMachine.outcomesForReporter(r.status) else emptyList()))
                .put("links", links(r.id, base)))
        }

        endpoint(spec(Endpoints.ISSUES_FOLLOWUP, Endpoints.FEEDBACK_FOLLOWUP)) {
            val a = agent!!
            val id = issueId()
            val existing = app.feedback.get(id)
            if (existing != null && !existing.filedBy(a.id) && isDeveloper()) throw ErrorCatalog.error("forbidden",
                "Only the agent that filed issue ${existing.number} can follow up on it. Agents in Fixer Mode move issues with POST $base/$id/status.",
                mapOf("reason" to if (legacy) "not_your_report" else "not_your_issue"))
            val outcome = body.optString("outcome", "").ifBlank { null }
            val note = body.optString("note", "").ifBlank { null }
            val r = try { app.feedback.followUp(id, a.id, a.name, note, outcome) } catch (e: FeedbackError) { mapError(e) }
            params.put("note", "[${note?.length ?: 0} chars]")
            message = when (outcome) {
                "works" -> "${a.name} confirmed report ${r.number} works now"
                "still_broken" -> "${a.name} says report ${r.number} is still broken"
                else -> "${a.name} followed up on report ${r.number}"
            }
            data = JSONObject().put("issue", r.number).put("status", r.status.id)
            Reply.Json(r.summaryJson(::isoAt).put("links", links(r.id, base)))
        }

        endpoint(spec(Endpoints.ISSUES_STATUS, Endpoints.FEEDBACK_STATUS)) {
            requireDeveloper()
            val id = issueId()
            val to = IssueStatus.of(body.optString("status")) ?: throw ErrorCatalog.error("invalid_parameter",
                "status must be in_progress, fixed, wont_fix or duplicate.")
            val note = body.optString("note", "").ifBlank { null }
            val r = try { app.feedback.move(id, to, Actor.DEVELOPER, agentName, note) } catch (e: FeedbackError) { mapError(e) }
            message = "$agentName moved report ${r.number} to ${to.id}"
            data = JSONObject().put("issue", r.number).put("status", to.id)
            Reply.Json(r.summaryJson(::isoAt))
        }

        endpoint(spec(Endpoints.ISSUES_POST, Endpoints.FEEDBACK_POST)) {
            val a = agent!!
            val kind = IssueKind.of(body.optString("kind")) ?: throw ErrorCatalog.error("invalid_parameter", "kind must be ${IssueKind.names}.")
            val description = body.optString("description", "")
            val applet = when {
                !body.has("applet") || body.isNull("applet") -> null
                else -> body.optJSONObject("applet") ?: throw ErrorCatalog.error("invalid_parameter", "applet must be an object.")
            }
            val imgs = body.optJSONArray("images")
            if (imgs != null && imgs.length() > 0)
                cc.bridgit.app.api.PowerGate.require(app.agents.get(a.id), cc.bridgit.app.core.Power.FIXER, "Attaching images")
            if (imgs != null && imgs.length() > FeedbackLimits.MAX_IMAGES) throw ErrorCatalog.error("invalid_parameter", "Up to 5 images.")
            val jpegs = (0 until (imgs?.length() ?: 0)).map { i ->
                val b64 = imgs!!.optJSONObject(i)?.optString("data_base64", "") ?: ""
                val raw = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                    ?: throw ErrorCatalog.error("invalid_parameter", "images[$i].data_base64 is not base64.")
                bytesToDownscaledJpeg(raw) ?: throw ErrorCatalog.error("invalid_parameter", "images[$i] is not an image.")
            }
            // The attached log is generated by Bridgit, never supplied by the agent (FBK-12).
            val r = try {
                val ctx = FeedbackContext.build(app, null, null, "agent")
                    .put("agent", FeedbackContext.agentInfo(app, a.id, header("User-Agent"), body.optJSONObject("agent_info")))
                app.feedback.create(kind, description, body.optBoolean("no_autofix", false), jpegs, FeedbackContext.recentLog(app),
                    ctx, "agent", a.id, a.name, applet)
            } catch (e: FeedbackError) { mapError(e) }
            params.put("description", "[${description.length} chars]")
            message = "${a.name} filed ${kindPhrase(kind)} ${r.number}"
            data = JSONObject().put("issue", r.number)
            // ref (the team number, e.g. A0042) is null until the phone has sent the issue; GET url shows it then.
            val reply = JSONObject().put("id", r.id).put("ref", r.ref ?: JSONObject.NULL).put("number", r.ref ?: JSONObject.NULL).put("status", r.status.id).put("no_autofix", r.noAutofix)
                .put("kind", r.kind.id).withApplet(r)
                .put("sent_to_team", r.upload).put("url", "$base/${r.id}").put("followup_url", "$base/${r.id}/followup")
                .put("check_back", "GET $base/${r.id} shows its status, the Bridgit team's note and history; GET $base lists your issues. " +
                    "Once it's marked fixed, POST $base/${r.id}/followup {\"outcome\":\"works\"} or {\"outcome\":\"still_broken\",\"note\":\"…\"}.")
            // FBK-15: where to follow this issue and re-test the fix without the phone.
            if (FeedbackUploader.configured) reply.put("team_api", TeamKeys.teamApi())
            Reply.Json(reply, status = 201)
        }

        endpoint(spec(Endpoints.ISSUES_TEAM_KEY, Endpoints.FEEDBACK_TEAM_KEY)) {
            val a = app.agents.get(agent!!.id) ?: throw ErrorCatalog.error("unauthorized", "This agent was removed.")
            val rotate = query("rotate")?.let { it == "true" || it == "1" } ?: false
            val k = try {
                withContext(Dispatchers.IO) { TeamKeys.get(app, a, rotate) }
            } catch (e: TeamKeys.Failure) { teamKeyError(e) }
            // The key never goes into the log (params, data, message).
            message = if (rotate) "${a.name} got a new Bridgit team key" else null
            data = JSONObject().put("team_agent_id", k.optString("agent_id")).put("rotated", rotate)
            Reply.Json(JSONObject().put("base_url", k.optString("base_url")).put("key", k.getString("key"))
                .put("agent_id", k.optString("agent_id")).put("role", k.optString("role", "reporter")).put("docs", k.optString("docs"))
                .put("how_to", JSONArray(TeamKeys.howTo(k.optString("base_url")))))
        }
    }
}
