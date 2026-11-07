package cc.bridgit.app.capture

import android.util.Base64
import io.ktor.server.routing.Route
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Reply
import cc.bridgit.app.api.Req
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.capability.wantsRaw
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kept captures (SEC-13, D33): each agent chooses where its photos, screenshots, recordings and clips go
 * (memory, phone files, upload, any mix) within what the owner allows, and fetches what it kept.
 * Always on: every capture still needs the capability that recorded it.
 */
class CapturesModule(app: BridgeApp) : Capability(app) {
    override val id = "captures"
    override val titleRes = R.string.cap_captures
    override val descriptionRes = R.string.cap_captures_desc
    override val group = "capture"
    // Storage first: Schedules.match takes the first endpoint whose path fits, and /captures/{id} would swallow it.
    override val endpoints = listOf(Endpoints.CAPTURES_STORAGE_GET, Endpoints.CAPTURES_STORAGE_SET,
        Endpoints.CAPTURES_LIST, Endpoints.CAPTURES_GET, Endpoints.CAPTURES_DELETE)
    override fun details() = owner()

    private fun owner(): JSONObject {
        val p = app.settings.capturePolicy()
        return JSONObject().put("agent_files", p.agentFiles).put("agent_upload", p.agentUpload)
            .put("max_keep_minutes", p.maxKeepMinutes).put("any_agent_can_fetch", p.shareAll)
            .put("owner_files", p.ownerFiles).put("owner_upload", p.ownerUploadUrl != null)
    }

    private fun storageView(agentId: String): JSONObject {
        val prefs = app.captures.prefs(agentId)
        val effective = JSONObject()
        for (k in CaptureRules.KINDS) {
            val plan = app.captures.plan(agentId, k, null)
            effective.put(k, JSONObject().put("store", JSONArray(plan.destinations())).put("keep_minutes", plan.keepMs / 60_000))
        }
        return JSONObject().put("yours", prefs?.publicJson() ?: CaptureRules.AgentPrefs().publicJson())
            .put("effective", effective).put("owner", owner())
            .put("destinations", JSONArray(CaptureRules.DESTINATIONS)).put("kinds", JSONArray(CaptureRules.KINDS))
    }

    private fun <T> refusing(block: () -> T): T = try { block() } catch (r: CaptureRules.Refused) {
        if (r.reason == "owner_disallowed") throw ErrorCatalog.error("forbidden", r.message ?: "", mapOf("reason" to "owner_disallowed"))
        throw ErrorCatalog.error("invalid_parameter", r.message ?: "")
    }

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.CAPTURES_STORAGE_GET) { Reply.Json(storageView(agent!!.id)) }

        endpoint(Endpoints.CAPTURES_STORAGE_SET) {
            val me = agent!!.id
            if (!body.has("store")) throw ErrorCatalog.error("invalid_parameter", "Send store: a list of memory, files and upload, [] or null.")
            val next = refusing {
                val store = CaptureRules.parseStore(body.opt("store"))
                val kinds = CaptureRules.parseKinds(body.opt("kinds"))
                val keep = body.opt("keep_minutes").let { v ->
                    when (v) { null, JSONObject.NULL -> null; is Number -> v.toInt().takeIf { v.toDouble() % 1.0 == 0.0 }
                        ?: throw CaptureRules.Refused("invalid_parameter", "keep_minutes must be a whole number.")
                        else -> throw CaptureRules.Refused("invalid_parameter", "keep_minutes must be a whole number.") }
                }
                CaptureRules.checkAllowed(store, keep, app.settings.capturePolicy())
                val url = body.optString("upload_url").takeIf { body.has("upload_url") && !body.isNull("upload_url") && it.isNotBlank() }?.trim()
                if (store != null && CaptureRules.UPLOAD in store && url == null)
                    throw CaptureRules.Refused("invalid_parameter", "upload needs an upload_url.")
                url?.let { u -> app.captures.uploadUrlProblem(u)?.let { throw CaptureRules.Refused("invalid_parameter", "upload_url: $it") } }
                val headers = body.optJSONObject("upload_headers")?.let { h ->
                    CaptureRules.checkHeaders(h.keys().asSequence().associateWith { k -> h.opt(k)?.toString().orEmpty() })
                } ?: emptyMap()
                if (store == null && kinds == null && url == null && keep == null) null
                else CaptureRules.AgentPrefs(store, kinds, url, headers, keep)
            }
            app.captures.setPrefs(me, next)
            app.log.system("audit", "capture_storage", "$agentName chose where its captures go: " +
                (next?.store?.let { if (it.isEmpty()) "only returned" else it.joinToString(", ") } ?: "defaults") +
                (next?.keepMinutes?.let { ", memory $it min" } ?: "") + (next?.uploadUrl?.let { ", upload to ${runCatching { java.net.URI(it).host }.getOrNull()}" } ?: ""))
            Reply.Json(storageView(me))
        }

        endpoint(Endpoints.CAPTURES_LIST) {
            val kind = query("kind")?.also { if (it !in CaptureRules.KINDS) throw ErrorCatalog.error("invalid_parameter", "kind must be one of ${CaptureRules.KINDS.joinToString(", ")}.") }
            val limit = query("limit")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..200 } ?: throw ErrorCatalog.error("invalid_parameter", "limit must be 1 to 200.") } ?: 50
            Reply.Json(JSONObject().put("captures", JSONArray(app.captures.list(agent!!.id, kind, limit).map { app.captures.view(it) })))
        }

        endpoint(Endpoints.CAPTURES_GET) {
            val cid = path("id") ?: ""
            val f = app.captures.get(cid, agent!!.id) ?: throw ErrorCatalog.error("not_found",
                "No capture $cid for you. Memory keeps captures for the time you chose (POST /v1/captures/storage); files last until deleted.")
            bytesOut = f.data.size.toLong()
            if (wantsRaw()) {
                if (f.data.size > ApiServer.MAX_RAW) throw ErrorCatalog.error("payload_too_large", "The capture is over 40 MB.")
                Reply.Bytes(f.data, f.entry.mime, mapOf("X-Bridgit-Capture-Id" to f.entry.id, "X-Bridgit-Kind" to f.entry.kind,
                    "X-Bridgit-Captured-At" to isoAt(f.entry.at)))
            } else {
                val edge=query("max_edge_px")?.let { it.toIntOrNull()?.takeIf { n->n in 80..1600 } ?: throw ErrorCatalog.error("invalid_parameter","max_edge_px must be 80–1600") }
                val bytes=if(edge!=null&&f.entry.mime.startsWith("image/")) cc.bridgit.app.capability.ImageUtil.prepare(f.data,f.entry.mime,cc.bridgit.app.api.Sizing.Spec(edge,null,65),forceJpeg=true).bytes else f.data
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                if (b64.length > ApiServer.MAX_PAYLOAD) throw ErrorCatalog.error("payload_too_large", "The capture is over 12 MB as base64. Add raw=1.")
                Reply.Json(app.captures.view(f.entry).put("data_base64", b64).apply { if(edge!=null&&f.entry.mime.startsWith("image/"))put("mime","image/jpeg") })
            }
        }

        endpoint(Endpoints.CAPTURES_DELETE) {
            val cid = path("id") ?: ""
            if (!app.captures.delete(cid, agent!!.id)) throw ErrorCatalog.error("not_found", "No capture $cid for you.")
            Reply.Json(JSONObject().put("deleted", true).put("id", cid))
        }
    }

    companion object {
        /** The plan for this request's capture; throws the API error for a `store` that can't be honoured. */
        private fun planFor(app: BridgeApp, r: Req, kind: String): CaptureRules.Plan? {
            val agentId = r.agent?.id ?: return null
            return try {
                val requested = CaptureRules.parseStore(r.arg("store"))
                if (requested != null && CaptureRules.UPLOAD in requested && app.captures.prefs(agentId)?.uploadUrl == null)
                    throw CaptureRules.Refused("invalid_parameter", "upload needs your upload_url: set it with POST /v1/captures/storage first.")
                app.captures.plan(agentId, kind, requested)
            } catch (e: CaptureRules.Refused) {
                if (e.reason == "owner_disallowed") throw ErrorCatalog.error("forbidden", e.message ?: "", mapOf("reason" to "owner_disallowed"))
                throw ErrorCatalog.error("invalid_parameter", e.message ?: "")
            }
        }

        /** Checks `store` before anything is captured, so a refused store never takes a photo. */
        fun checkStore(app: BridgeApp, r: Req, kind: String) { planFor(app, r, kind) }

        /**
         * For capture endpoints: keeps [data] where the request's `store` (or the agent's choice) says.
         * Returns the summary to add to the reply as `capture`, or null when nothing was kept.
         */
        fun keep(app: BridgeApp, r: Req, kind: String, data: ByteArray, mime: String, at: Long, meta: JSONObject = JSONObject()): JSONObject? {
            val plan = planFor(app, r, kind) ?: return null
            return app.captures.keep(r.agent!!.id, kind, data, mime, at, plan, meta)
        }
    }
}
