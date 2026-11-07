package cc.bridgit.app.vision

import android.content.ContentUris
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Base64
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.capability.Encoded
import cc.bridgit.app.capability.envelope
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.capability.rawReply
import cc.bridgit.app.capability.wantsRaw
import cc.bridgit.app.core.Compat
import cc.bridgit.app.judgment.Jev
import cc.bridgit.app.planned.Rules
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * POST /v1/images/find and /v1/images/crop: find things in a photo ("the dog", "each dirty dish",
 * "the face of each person") and zoom in on them. See [Finder] for how they are found.
 */
class FindCropModule(app: BridgeApp) : Capability(app) {
    override val id = "find_crop"
    override val titleRes = R.string.cap_find_crop
    override val descriptionRes = R.string.cap_find_crop_desc
    override val group = "photos"
    override val switchKey = "find_crop"
    override val endpoints = ALL

    companion object {
        private val SOURCE = listOf(
            Param("capture_id", "string", "A photo this agent captured and kept (POST /v1/camera/snapshot with store), or one shared with it"),
            Param("gallery_id", "integer", "An accessible gallery photo (also needs the gallery grant)"),
            Param("data_base64", "string", "The image bytes instead (JPEG, PNG or WebP; about 1.5 MB of image at most, so use capture_id or gallery_id for big photos)"))
        private val MODE = Param("mode", "string", "auto (default: faces on the phone, everything else in the cloud), on_device (faces only, never uploads) or cloud", enum = listOf("auto", "on_device", "cloud"))
        private val ENGINE = Param("engine", "string", "Opt-in: on_device_detector finds anything named in words on the phone (free, offline, 7-16 s; GET /v1/images/engines). Never falls back to the cloud; model_not_ready while its model downloads. Other detector engines may appear in GET /v1/images/engines")
        private val OUTLINE = Param("outline", "boolean", "Opt-in: also trace each item's outline on the phone (on_device_outline engine, +3-4 s): items get outline [[x, y], ...] fractions. On crop, each crop is a PNG cut out along the outline (transparent outside)")
        val FIND = EndpointSpec("POST", "/images/find", "find_crop",
            "Find things in a photo and get their boxes: \"the dog\", \"each dirty dish\", \"the face of each person\". Faces are found on the phone; anything else by a cheap cloud vision model (shares the Jev/Clef budget)",
            body = listOf(Param("query", "string", "What to find, in plain words (300 characters at most)", true)) + SOURCE + listOf(MODE, ENGINE, OUTLINE, Param("max_items", "integer", "1-50, default 20")),
            example = "POST /v1/images/find {\"capture_id\":\"cap_...\",\"query\":\"each dirty dish\"}",
            returns = "items [{label, box {x0,y0,x1,y1 fractions, px {left,top,right,bottom}}, outline [[x,y],...] with outline}], count, source on_device|cloud, model, cost_usd, width_px, height_px",
            errors = listOf("invalid_parameter", "capability_disabled", "photo_not_found", "not_supported", "model_not_ready", "rate_limited", "team_unavailable", "upstream_failed"),
            idempotent = true, privateFields = setOf("data_base64"), maxBody = 24 * 1024 * 1024)
        val CROP = EndpointSpec("POST", "/images/crop", "find_crop",
            "Zoom in on something in a photo: crop to what query finds (or to a box you give) with some margin. which=first (default), all (one crop around every match) or each (one crop per match, 10 at most)",
            body = SOURCE + listOf(
                Param("query", "string", "What to crop to; or give box instead"),
                Param("box", "object", "{x0, y0, x1, y1} fractions (0-1) of the upright photo, for example from /v1/images/find"),
                Param("which", "string", "first, all or each", enum = listOf("first", "all", "each")),
                Param("index", "integer", "With which=first: the match to use, 0 = most prominent"),
                Param("pad", "number", "Margin around the box as a fraction of its size on each side, 0-2, default 0.15"),
                Param("aspect", "number", "Optional width/height of the crop, for example 1 for square"),
                MODE, ENGINE, OUTLINE,
                Param("max_edge_px", "integer", "Longest side of each crop, 64-4096, default 2048"),
                Param("quality", "integer", "JPEG quality 40-100, default 90"),
                Param("raw", "boolean", "1 = return the JPEG bytes (which=first or all only)")),
            example = "POST /v1/images/crop {\"capture_id\":\"cap_...\",\"query\":\"the dog\"}",
            returns = "JPEG (PNG cut-out with outline) (data_base64, width_px, height_px) with crop {left,top,right,bottom px} and the found items; which=each: crops [...]",
            errors = listOf("invalid_parameter", "capability_disabled", "photo_not_found", "not_found", "not_supported", "model_not_ready", "rate_limited", "team_unavailable", "upstream_failed"),
            media = true, idempotent = true, privateFields = setOf("data_base64"), maxBody = 24 * 1024 * 1024)
        val ENGINES = EndpointSpec("GET", "/images/engines", "find_crop",
            "The opt-in on-phone vision engines for find and crop, from the Bridgit team's signed software manifest (checked daily, so new engines can appear without an app update): what each is good at, its speed and size on a mid-range phone, its licence, and whether its model is downloaded. They are free and work offline, and run only when you name them (engine, outline)",
            example = "GET /v1/images/engines",
            returns = "default_routing, manifest {serial, published_at, source bundled|downloaded, checked_at, signed_by, last_error}, engines [{engine, kind detector|outline, model, version, good_at, not_good_at, speed, memory, size_mb, licence, cost, installed, state ready|downloading|not_downloaded|waiting_for_wifi|failed|unsupported, kept_up_to_date, downloaded_mb, reason, how_to_use, runtime {model, size_mb, state}}]")
        val ENGINES_CHECK = EndpointSpec("POST", "/images/engines/check", "find_crop",
            "Check the software manifest now instead of waiting for the daily check; engines in use update to their newest version (on Wi-Fi)",
            example = "POST /v1/images/engines/check",
            returns = "manifest {serial, published_at, source, checked_at, last_error}, engines as GET /v1/images/engines shows them",
            errors = listOf("capability_disabled"))
        val ENGINE_DOWNLOAD = EndpointSpec("POST", "/images/engines/download", "find_crop",
            "Start downloading an opt-in engine's model now (on Wi-Fi), so the first find with it doesn't wait. A find or crop that names it starts the download too",
            body = listOf(Param("engine", "string", "An engine from GET /v1/images/engines, for example on_device_detector or on_device_outline", true)),
            example = "POST /v1/images/engines/download {\"engine\":\"on_device_outline\"}",
            returns = "The engine as GET /v1/images/engines shows it",
            errors = listOf("invalid_parameter", "capability_disabled", "not_supported"), idempotent = true)
        val APPRAISE = EndpointSpec("POST", "/images/appraise", "find_crop",
            "Home inventory from a photo: every belonging in it with a box, category, brand and model where they can be read, visible condition, estimated price new and used (US dollars), whether a home or renters policy would normally cover it, and how sure the model is. One cloud vision call (about half a cent; shares the Jev/Clef budget)",
            body = SOURCE + listOf(Param("room", "string", "Optional: the room the photo is in, e.g. Kitchen (60 characters at most); otherwise the model guesses"),
                Param("max_items", "integer", "1-30, default 20")),
            example = "POST /v1/images/appraise {\"capture_id\":\"cap_...\",\"room\":\"Living room\"}",
            returns = "room, items [{label, category, brand, model, quantity, condition new|like new|good|fair|poor, new_usd, used_usd, insurable, insurable_note, confidence 0-1, box {x0,y0,x1,y1 fractions, px}}], count, currency USD, estimate_basis, model, cost_usd, width_px, height_px. Prices are model estimates, not quotes; brand/model/condition/prices may be null",
            errors = listOf("invalid_parameter", "capability_disabled", "photo_not_found", "not_supported", "rate_limited", "team_unavailable", "upstream_failed"),
            idempotent = true, privateFields = setOf("data_base64"), maxBody = 24 * 1024 * 1024)
        val ALL = listOf(FIND, CROP, APPRAISE, ENGINES, ENGINES_CHECK, ENGINE_DOWNLOAD)
        private const val MAX_BYTES = 16 * 1024 * 1024
    }

    /** The photo the request names, as raw bytes. */
    private fun source(r: Req): ByteArray {
        val b = r.body
        val n = listOf("capture_id", "gallery_id", "data_base64").count { b.has(it) && !b.isNull(it) }
        if (n != 1) throw ErrorCatalog.error("invalid_parameter", "Give exactly one of capture_id, gallery_id or data_base64.")
        if (b.has("capture_id")) {
            val id = b.optString("capture_id")
            return app.captures.get(id, r.agent!!.id)?.data?.takeIf { it.size <= MAX_BYTES }
                ?: throw ErrorCatalog.error("photo_not_found", "No kept capture $id that this agent may read.")
        }
        if (b.has("gallery_id")) {
            if (!app.settings.isOn("gallery") || !app.agents.get(r.agent!!.id)!!.allows("gallery")) throw ErrorCatalog.error("capability_disabled", "Enable this agent's gallery access first.")
            val id = b.optString("gallery_id").toLongOrNull()?.takeIf { it > 0 } ?: throw ErrorCatalog.error("invalid_parameter", "gallery_id must be a positive image ID.")
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val raw = try { app.contentResolver.openInputStream(uri)?.use { Compat.readAtMost(it, MAX_BYTES + 1) } }
                catch (e: SecurityException) { throw ErrorCatalog.error("permission_denied", "This photo is outside your media grant.") }
                ?: throw ErrorCatalog.error("photo_not_found", "Photo unavailable.")
            if (raw.size > MAX_BYTES) throw ErrorCatalog.error("payload_too_large", "Photos must be under 16 MB.")
            return raw
        }
        return try { Base64.decode(b.getString("data_base64"), Base64.DEFAULT) } catch (e: Exception) { throw ErrorCatalog.error("invalid_parameter", "data_base64 isn't valid base64.") }
            .also { if (it.size > MAX_BYTES) throw ErrorCatalog.error("payload_too_large", "Photos must be under 16 MB.") }
    }

    private fun mode(r: Req): String = r.body.optString("mode", "auto").also {
        if (it !in listOf("auto", "on_device", "cloud")) throw ErrorCatalog.error("invalid_parameter", "mode must be auto, on_device or cloud.")
    }

    /** The opt-in engine the caller named, or null for the default routing. */
    private fun engine(r: Req): String? {
        if (!r.body.has("engine") || r.body.isNull("engine")) return null
        val e = r.body.optString("engine")
        val m = app.onDeviceVision.models
        if (m.manifest.items.none { it.engine == e && it.kind == "detector" })
            throw ErrorCatalog.error("invalid_parameter", "engine must be a detector from GET /v1/images/engines, for example ${OnDeviceEngines.DETECTOR}.")
        m.whyNot(e)?.let { throw ErrorCatalog.error("not_supported", "Engine $e can't run on this phone: $it.", mapOf("engine" to e)) }
        if (mode(r) == "cloud") throw ErrorCatalog.error("invalid_parameter", "engine $e runs on the phone; leave mode out (or on_device), not cloud.")
        return e
    }

    private fun outline(r: Req): Boolean = when (val v = r.body.opt("outline")) {
        null, JSONObject.NULL -> false
        is Boolean -> v
        else -> throw ErrorCatalog.error("invalid_parameter", "outline must be true or false.")
    }

    private fun notReady(e: OnDeviceVision.NotReady) = ErrorCatalog.error("model_not_ready", e.message ?: "The model isn't ready.",
        mapOf("engine" to e.engine.id, "state" to e.state, "size_mb" to e.engine.sizeMb,
            "retry_after_s" to if (e.state == OnDeviceModels.WAITING_FOR_WIFI || e.state == OnDeviceModels.FAILED) 900 else 60))

    private fun query(r: Req, required: Boolean): String? {
        val q = r.body.optString("query").trim()
        if (q.isEmpty()) { if (required) throw ErrorCatalog.error("invalid_parameter", "query is required: what to find, for example \"the dog\"."); return null }
        if (q.length > 300) throw ErrorCatalog.error("invalid_parameter", "query is at most 300 characters.")
        return q
    }

    private suspend fun found(r: Req, bmp: Bitmap, q: String, max: Int): Finder.Found = try {
        // Both engines asked for: start both downloads, and say at once if the outline model is missing, before a long detection.
        if (outline(r)) {
            engine(r)?.let { name -> app.onDeviceVision.models.let { m -> m.engine(name)?.let { e -> m.ensure(e) } } }
            app.onDeviceVision.requireReady(OnDeviceEngines.OUTLINE)
        }
        Finder.find(app, bmp, q, mode(r), max, engine = engine(r)).let { f -> if (outline(r)) Finder.outlined(app, bmp, f) else f }
    } catch (e: OnDeviceVision.NotReady) {
        throw notReady(e)
    } catch (e: OnDeviceVision.Unknown) {
        throw ErrorCatalog.error("not_supported", "Engine ${e.name} can't run on this phone: ${e.message}.", mapOf("engine" to e.name))
    } catch (e: Finder.Unavailable) {
        throw ErrorCatalog.error("not_supported", e.message ?: "Not available on this phone.", mapOf("reason" to e.reason))
    } catch (e: Jev.Failure) {
        val extra = mapOf("reason" to e.reason, "service" to "locate", "backend_http" to e.http.takeIf { it > 0 })
        throw when (e.reason) {
            "budget_reached" -> ErrorCatalog.error("budget_reached", e.message ?: "This applet's spending limit is used up.", extra)
                        "applet_budget" -> ErrorCatalog.error("applet_budget", e.message ?: "This applet's budget is used up.", extra + mapOf("applet" to e.applet, "resets_at" to e.extra?.opt("resets_at")))
            "rate_limited", "spend_cap" -> ErrorCatalog.error("rate_limited", e.message ?: "Over the cloud finder's limit.", extra + ("retry_after_s" to 3600))
            "invalid_parameter", "payload_too_large" -> ErrorCatalog.error("invalid_parameter", e.message ?: "The cloud finder refused the photo.", extra)
            "not_configured", "external_paused" -> ErrorCatalog.error("not_supported", e.message ?: "The cloud finder isn't available.", extra)
            "team_unreachable", "no_install_identity" -> ErrorCatalog.error("team_unavailable", e.message ?: "The team backend can't be reached.", extra)
            else -> ErrorCatalog.error("upstream_failed", e.message ?: "The cloud finder failed.", extra)
        }
    }.also { f ->
        r.data = JSONObject().put("query_chars", q.length).put("count", f.items.size).put("source", f.source).put("cost_usd", f.costUsd).put("model", f.model)
    }

    private suspend fun decoded(r: Req): Bitmap {
        val bytes = source(r)
        return withContext(Dispatchers.Default) { Finder.decode(bytes) } ?: throw ErrorCatalog.error("invalid_parameter", "The image could not be decoded.")
    }

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(ENGINES) {
            requireUsable()
            Reply.Json(JSONObject().put("engines", app.onDeviceVision.models.jsonAll()).put("manifest", app.onDeviceVision.models.manifestJson())
                .put("default_routing", "Without engine or outline: faces on the phone (ML Kit), everything else in the cloud. The engines here run only when named."))
        }
        endpoint(ENGINES_CHECK) {
            requireUsable()
            val m = app.onDeviceVision.models
            Reply.Json(JSONObject().put("manifest", m.check()).put("engines", m.jsonAll()))
        }
        endpoint(ENGINE_DOWNLOAD) {
            requireUsable()
            val name = body.optString("engine")
            val m = app.onDeviceVision.models
            if (m.manifest.items.none { it.engine == name }) throw ErrorCatalog.error("invalid_parameter", "engine must be one from GET /v1/images/engines.")
            val e = m.engine(name) ?: throw ErrorCatalog.error("not_supported", "Engine $name can't run on this phone: ${m.whyNot(name)}.", mapOf("engine" to name))
            m.ensure(e)
            Reply.Json(app.onDeviceVision.models.json(e))
        }
        endpoint(FIND) {
            requireUsable()
            val q = query(this, true)!!
            val max = Rules.integer(body.opt("max_items"), "max_items", 1, 50, 20)
            mode(this); engine(this); outline(this)
            val bmp = decoded(this)
            try {
                val f = found(this, bmp, q, max)
                // An installed applet's card looking for things (e.g. left out in the kitchen): its web portal keeps the result.
                val req = this
                agent?.let { a -> runCatching { app.remoteView.packages.noteFind(a.id, f.items) { source(req) } } }
                Reply.Json(f.json(bmp.width, bmp.height).put("query", q))
            } finally { bmp.recycle() }
        }
        endpoint(APPRAISE) {
            requireUsable()
            val room = body.opt("room")?.takeIf { it != JSONObject.NULL }?.let { v ->
                (v as? String)?.trim()?.takeIf { it.length <= 60 && it.none { c -> c < ' ' } } ?: throw ErrorCatalog.error("invalid_parameter", "room must be text, 60 characters at most.")
            }
            val max = Rules.integer(body.opt("max_items"), "max_items", 1, 30, 20)
            val bmp = decoded(this)
            try {
                val w = bmp.width; val h = bmp.height
                val bytes = withContext(Dispatchers.Default) { val small = Finder.scaled(bmp, 1024); try { Finder.jpeg(small, 85) } finally { if (small !== bmp) small.recycle() } }
                val a = try { withContext(Dispatchers.IO) { Jev.appraise(app, bytes, room, max) } } catch (e: Jev.Failure) {
                    val extra = mapOf("reason" to e.reason, "service" to "appraise", "backend_http" to e.http.takeIf { it > 0 })
                    throw when (e.reason) {
                        "budget_reached" -> ErrorCatalog.error("budget_reached", e.message ?: "This applet's spending limit is used up.", extra)
                        "applet_budget" -> ErrorCatalog.error("applet_budget", e.message ?: "This applet's budget is used up.", extra + mapOf("applet" to e.applet, "resets_at" to e.extra?.opt("resets_at")))
                        "rate_limited", "spend_cap" -> ErrorCatalog.error("rate_limited", e.message ?: "Over the cloud model's limit.", extra + ("retry_after_s" to 3600))
                        "invalid_parameter", "payload_too_large" -> ErrorCatalog.error("invalid_parameter", e.message ?: "The cloud model refused the photo.", extra)
                        "not_configured", "external_paused" -> ErrorCatalog.error("not_supported", e.message ?: "Home inventory isn't available.", extra)
                        "team_unreachable", "no_install_identity" -> ErrorCatalog.error("team_unavailable", e.message ?: "The team backend can't be reached.", extra)
                        else -> ErrorCatalog.error("upstream_failed", e.message ?: "The cloud model failed.", extra)
                    }
                }
                val items = JSONArray(a.items.map { o ->
                    val b = o.getJSONObject("box")
                    FindRules.boxOf(b)?.let { box -> o.put("box", box.json(w, h)) }
                    o
                })
                data = JSONObject().put("count", items.length()).put("cost_usd", a.costUsd).put("model", a.model).put("room_given", room != null)
                Reply.Json(JSONObject().put("room", a.room ?: JSONObject.NULL).put("items", items).put("count", items.length()).put("currency", "USD")
                    .put("estimate_basis", "Model estimates of typical US prices (new: retail today; used: resale in the visible condition). Not a quote or an appraisal.")
                    .put("model", a.model).put("cost_usd", a.costUsd).put("key", a.key).put("width_px", w).put("height_px", h))
            } finally { bmp.recycle() }
        }
        endpoint(CROP) {
            requireUsable()
            val q = query(this, false)
            val given = if (body.has("box")) FindRules.boxOf(body.optJSONObject("box")) ?: throw ErrorCatalog.error("invalid_parameter", "box must be {x0, y0, x1, y1} fractions with x0 < x1 and y0 < y1.") else null
            if ((q == null) == (given == null)) throw ErrorCatalog.error("invalid_parameter", "Give query (what to crop to) or box, not both.")
            val which = body.optString("which", "first").also { if (it !in listOf("first", "all", "each")) throw ErrorCatalog.error("invalid_parameter", "which must be first, all or each.") }
            val index = Rules.integer(body.opt("index"), "index", 0, 49, 0)
            val pad = (body.opt("pad") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..2.0 }
                ?: if (body.has("pad")) throw ErrorCatalog.error("invalid_parameter", "pad must be a number from 0 to 2.") else 0.15
            val aspect = if (!body.has("aspect")) null else (body.opt("aspect") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.1..10.0 }
                ?: throw ErrorCatalog.error("invalid_parameter", "aspect must be a width/height ratio from 0.1 to 10.")
            val edge = Rules.integer(body.opt("max_edge_px"), "max_edge_px", 64, 4096, 2048)
            val quality = Rules.integer(body.opt("quality"), "quality", 40, 100, 90)
            mode(this); engine(this)
            val cutout = outline(this)
            val bmp = decoded(this)
            try {
                val w = bmp.width; val h = bmp.height
                val f = if (q != null) found(this, bmp, q, if (which == "each") 10 else 20) else null
                var items = f?.items ?: listOf(FindRules.Item("box", given!!))
                if (f == null && cutout) items = try { Finder.outlined(app, bmp, Finder.Found(items, "box", "box")).items } catch (e: OnDeviceVision.NotReady) { throw notReady(e) }
                if (items.isEmpty()) throw ErrorCatalog.error("not_found", "Nothing in the photo matched \"$q\".", mapOf("source" to f!!.source))
                val groups: List<List<FindRules.Item>> = when (which) {
                    "all" -> listOf(items)
                    "each" -> items.take(10).map { listOf(it) }
                    else -> listOf(listOf(items.getOrNull(index) ?: throw ErrorCatalog.error("not_found", "Only ${items.size} matches; index $index is past the end.")))
                }
                val crops = withContext(Dispatchers.Default) { groups.map { g ->
                    val rect = FindRules.cropRect(g.map { it.box }, w, h, pad, aspect = aspect)
                    var cut = Bitmap.createBitmap(bmp, rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
                    if (cutout) cut = Finder.cutOut(cut, rect, w, h, g.mapNotNull { it.outline }).also { if (it !== cut && cut !== bmp) cut.recycle() }
                    val out = Finder.scaled(cut, edge)
                    val enc = if (cutout) Encoded(Finder.png(out), out.width, out.height, "image/png") else Encoded(Finder.jpeg(out, quality), out.width, out.height, "image/jpeg")
                    if (out !== cut) out.recycle(); if (cut !== bmp) cut.recycle()
                    Triple(enc, rect, g)
                } }
                val at = isoAt(System.currentTimeMillis())
                fun meta(rect: IntArray, g: List<FindRules.Item>) = JSONObject()
                    .put("crop", JSONObject().put("left", rect[0]).put("top", rect[1]).put("right", rect[2]).put("bottom", rect[3]))
                    .put("items", JSONArray(g.map { it.json(w, h) }))
                if (which == "each") {
                    if (wantsRaw()) throw ErrorCatalog.error("invalid_parameter", "raw=1 returns one image; use which=first or all, or leave raw off.")
                    var total = 0L
                    val arr = JSONArray(crops.map { (enc, rect, g) ->
                        val e = envelope(enc, at, "cropped_at", null); total += enc.bytes.size
                        meta(rect, g).also { m -> e.keys().forEach { k -> m.put(k, e.get(k)) } }
                    })
                    bytesOut = total
                    Reply.Json(JSONObject().put("crops", arr).put("count", arr.length()).put("width_px", w).put("height_px", h)
                        .apply { if (f != null) put("source", f.source).put("model", f.model).put("cost_usd", f.costUsd).put("query", q) })
                } else {
                    val (enc, rect, g) = crops.single()
                    if (wantsRaw()) rawReply(enc, mapOf("X-Bridgit-Crop" to rect.joinToString(","), "X-Bridgit-Source" to (f?.source ?: "box")))
                    else Reply.Json(envelope(enc, at, "cropped_at", null).also { e -> meta(rect, g).let { m -> m.keys().forEach { k -> e.put(k, m.get(k)) } } }
                        .put("source_width_px", w).put("source_height_px", h).put("found", items.size)
                        .apply { if (f != null) put("source", f.source).put("model", f.model).put("cost_usd", f.costUsd).put("query", q) })
                }
            } finally { bmp.recycle() }
        }
    }
}
