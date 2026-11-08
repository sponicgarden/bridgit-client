package cc.bridgit.app.interact

import android.util.Base64
import io.ktor.server.routing.Route
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.api.RateLimiter
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Reply
import cc.bridgit.app.api.Req
import cc.bridgit.app.api.Sizing
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.capability.Gallery
import cc.bridgit.app.capability.ImageUtil
import cc.bridgit.app.capability.isoAt
import org.json.JSONArray
import org.json.JSONObject

/**
 * 8.13 Interactions and section 9 events. Master switch "Interactions" (off by default, SEC-03).
 * Every read is the calling agent's own (ownership re-checked after any wait).
 */
class InteractionModule(app: BridgeApp) : Capability(app) {
    override val id = "interactions"
    override val titleRes = R.string.cap_interactions
    override val descriptionRes = R.string.cap_interactions_desc
    override val group = "talk"
    override val switchKey = "interactions"
    override val endpoints = listOf(Endpoints.INTERACTION_CREATE, Endpoints.INTERACTION_LIST, Endpoints.INTERACTION_GET,
        Endpoints.INTERACTION_CANCEL, Endpoints.INTERACTION_RENOTIFY, Endpoints.EVENTS, Endpoints.TEXT_STREAM, Endpoints.CALLBACK_TEST)

    /** D26: POST /v1/text/stream calls per agent per second. */
    private val streamLimiter = RateLimiter(windowMs = 1000)
    override fun blockedReason(): String? = if (!app.captureNotificationsOn()) "notifications_off" else null

    private fun fail(e: InteractionError): Nothing = when (e.code) {
        "not_found" -> throw ErrorCatalog.error("not_found", e.message ?: "Not found.")
        "too_many_open" -> throw ErrorCatalog.error("too_many_open", e.message ?: "Too many open.")
        "conflict" -> throw ErrorCatalog.error("conflict", e.message ?: "Not in a state for this.")
        "payload_too_large" -> throw ErrorCatalog.error("payload_too_large", e.message ?: "Too large.")
        else -> throw ErrorCatalog.error("invalid_parameter", e.message ?: "Bad interaction.")
    }

    private fun Req.waitArg(): Int = query("wait")?.let { it.toIntOrNull()?.takeIf { n -> n in 0..55 }
        ?: throw ErrorCatalog.error("invalid_parameter", "wait must be 0 to 55.") } ?: 0

    private fun json(i: Interaction) = i.toJson(::isoAt)

    /**
     * CAP-56: whether the owner's screen was taken over. When it wasn't, `missing` names what is off and
     * `fix` is a sentence the agent can pass on to the owner.
     */
    private fun displayJson(s: NotifRules.Display): JSONObject = JSONObject()
        .put("shown_as", s.shownAs).put("full_screen", s.shownAs == "full_screen")
        .apply {
            if (s.missing.isNotEmpty()) put("missing", JSONArray(s.missing)).put("fix", NotifRules.fix(s.missing))
        }

    /**
     * A0076: the canvas the card's page gets in the presentation used (the whole display less the cutout for full screen,
     * the card frame for standard), and `aspect_mismatch` when the page's declared shape differs by more than 10 %.
     */
    private fun canvas(o: JSONObject, d: Draft) {
        val g = runCatching { DisplayInfo.read(app) }.getOrNull() ?: return
        val px = if (d.presentation == Presentation.FULL_SCREEN) CardViewport.fullScreenNow(g) else DisplayInfo.standardOrEstimate(g)
        o.put("presentation", d.presentation.id).put("viewport", CardViewport.viewportJson(px, g.density))
        if (d.kind != IKind.HTML) return
        val declared = d.html?.let { CardViewport.declaredSize(it) }
        val content = d.contentAspect ?: declared?.ratio
        val label = d.contentAspectLabel ?: declared?.let { "${it.w}x${it.h}" }
        CardViewport.mismatchJson(content, label, px, d.presentation)?.let { o.put("aspect_mismatch", it) }
    }

    /** Questions and html cards wait for an answer or close; notices and text panels for being seen. */
    private fun answerable(k: IKind) = k == IKind.QUESTION || k == IKind.HTML

    /** While a card still waits for the owner, tells the agent to keep waiting (agents that ended their turn missed taps). */
    private fun withNext(o: JSONObject, i: Interaction): JSONObject {
        if (answerable(i.kind) && i.status.open) o.put("next", "Call GET /v1/interactions/${i.id}?wait=55 again now and repeat until status is no longer pending or seen. " +
            "The owner's tap reaches you only through this call, so don't end your turn while this card is open.")
        return o
    }

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.INTERACTION_CREATE) {
            // CAP-34: the raw HTML size is checked before anything else.
            (body.opt("html") as? String)?.let { h ->
                if (h.length > CardRules.HTML_MAX_BYTES || h.toByteArray(Charsets.UTF_8).size > CardRules.HTML_MAX_BYTES)
                    throw ErrorCatalog.error("payload_too_large", "html is limited to 256 KB.")
            }
            if (!enabled) throw ErrorCatalog.error("capability_disabled", "Interactions are switched off on the phone. Ask the owner to turn them on in Feature enablement.",
                mapOf("setting" to switchKey))
            if (!app.captureNotificationsOn()) throw ErrorCatalog.error("notifications_required",
                "Bridgit's notifications are off, so the owner wouldn't see your message. Ask them to turn notifications on.")
            val a = agent!!
            // A pieces card may name only this agent's own watches, and only endpoints a card can call.
            val scope = CardPieces.Scope(watch = { w -> w is String && app.watches.get(a.id, w) != null }, callCap = { m, path ->
                try { CardActions.match(app.registry.modules.flatMap { it.endpoints }, CardActions.parse(org.json.JSONObject().put("method", m).put("path", path).toString())).first.capability }
                catch (e: CardActions.Refused) { throw CardPieces.Bad("$m $path: ${e.message}") }
            })
            val d = try { InteractionRules.parse(body, scope) } catch (e: InteractionError) { fail(e) }
            val jpeg: ByteArray? = when {
                d.imageGalleryId != null -> {
                    if (!a.allows("gallery")) throw ErrorCatalog.error("permission_denied", "This agent has no Gallery access.")
                    val g = Gallery(app)
                    if (g.accessMode() == null || !app.settings.isOn("gallery"))
                        throw ErrorCatalog.error("capability_disabled", "image.gallery_id needs the Gallery switch and photo permission.", mapOf("setting" to "gallery"))
                    val item = g.find(d.imageGalleryId) ?: throw ErrorCatalog.error("photo_not_found", "No photo with that id.")
                    val bytes = g.read(item.id) ?: throw ErrorCatalog.error("photo_not_found", "That photo could not be opened.")
                    ImageUtil.prepare(bytes, item.mime, Sizing.PRESETS.getValue("agent"), forceJpeg = true).bytes
                }
                d.imageBase64 != null -> {
                    val raw = runCatching { Base64.decode(d.imageBase64, Base64.DEFAULT) }.getOrNull()
                        ?: throw ErrorCatalog.error("invalid_parameter", "image.data_base64 is not base64.")
                    if (raw.size > InteractionRules.IMAGE_MAX_BYTES) throw ErrorCatalog.error("invalid_parameter", "image is limited to 2 MB.")
                    cc.bridgit.app.feedback.bytesToDownscaledJpeg(raw) ?: throw ErrorCatalog.error("invalid_parameter", "image is not a picture.")
                }
                else -> null
            }
            // Read before create: creating may bring Bridgit to the front, which would change the answer.
            val shown = InteractionNotifier.display(app, d.noTakeover)
            val i = try { app.interactions.create(a.id, a.name, d, jpeg) } catch (e: InteractionError) { fail(e) }
            params.put("controls", d.controls.size)
            d.html?.let { params.put("html_bytes", it.toByteArray(Charsets.UTF_8).size) }
            val what = when (d.kind) { IKind.QUESTION -> "sent a question"; IKind.NOTICE -> "sent a notice"; IKind.HTML, IKind.PIECES -> "sent a card"; IKind.TEXT -> "opened a text panel" }
            message = "${a.name} $what: ${d.title.take(60)}"
            data = JSONObject().put("interaction_id", i.id).put("kind", d.kind.id)
            d.stack?.let { params.put("stack", it).put("stack_index", i.stackIndex); data?.put("stack", it) }
            Reply.Json(JSONObject().put("interaction_id", i.id).put("status", i.status.id).put("expires_at", isoAt(i.expiresAt))
                .put("display", displayJson(shown).also { canvas(it, d) }).apply {
                    if (i.stack != null) put("stack", i.stack).put("stack_index", i.stackIndex).put("stack_size", i.stackSize)
                    withNext(this, i)
                }, status = 201)
        }

        endpoint(Endpoints.INTERACTION_GET) {
            val a = agent!!
            val id = path("id")?.toLongOrNull() ?: throw ErrorCatalog.error("invalid_parameter", "id must be an interaction id.")
            var i = app.interactions.getFor(a.id, id) ?: throw ErrorCatalog.error("not_found", "No interaction $id of yours.")
            val wait = waitArg()
            val waitingFor = setOf(IStatus.PENDING, IStatus.SEEN)
            if (wait > 0 && i.status in waitingFor && (answerable(i.kind) || i.status == IStatus.PENDING)) {
                if (!api.waits.enter(a.id)) throw ErrorCatalog.error("too_many_waits", "At most 4 open waits per agent.")
                try {
                    withTimeoutOrNull(wait * 1000L) {
                        var seen = app.interactions.changed.value
                        while (true) {
                            val now = app.interactions.getFor(a.id, id) ?: break
                            // A question waits for an answer or close; a notice for being seen or closed.
                            if (if (answerable(now.kind)) !now.status.open else now.status != IStatus.PENDING) break
                            app.interactions.changed.first { it > seen }
                            seen = app.interactions.changed.value
                        }
                    }
                } finally { api.waits.exit(a.id) }
                i = app.interactions.getFor(a.id, id) ?: throw ErrorCatalog.error("not_found", "No interaction $id of yours.")
            }
            Reply.Json(withNext(json(i), i))
        }

        endpoint(Endpoints.INTERACTION_CANCEL) {
            val a = agent!!
            val id = path("id")?.toLongOrNull() ?: throw ErrorCatalog.error("invalid_parameter", "id must be an interaction id.")
            val i = try { app.interactions.cancel(a.id, id) } catch (e: InteractionError) { fail(e) }
            Reply.Json(json(i))
        }

        endpoint(Endpoints.INTERACTION_RENOTIFY) {
            if (!enabled) throw ErrorCatalog.error("capability_disabled", "Interactions are switched off.")
            if (!app.captureNotificationsOn()) throw ErrorCatalog.error("notifications_required", "Bridgit's notifications are off.")
            val id = path("id")?.toLongOrNull() ?: throw ErrorCatalog.error("invalid_parameter", "id must be an interaction id.")
            val i = try { app.interactions.renotify(agent!!.id, id) } catch (e: InteractionError) { fail(e) }
            params.put("interaction_id", id)
            message = "${agent!!.name} requested a card reminder"
            Reply.Json(json(i).put("notification_requested", true))
        }

        endpoint(Endpoints.INTERACTION_LIST) {
            val a = agent!!
            val status = query("status")?.let { IStatus.of(it) ?: throw ErrorCatalog.error("invalid_parameter", "Unknown status \"$it\".") }
            val limit = query("limit")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..100 } ?: throw ErrorCatalog.error("invalid_parameter", "limit must be 1 to 100.") } ?: 50
            Reply.Json(JSONObject().put("interactions", JSONArray(app.interactions.listFor(a.id, status, limit).map { json(it) })))
        }

        endpoint(Endpoints.TEXT_STREAM) {
            val a = agent!!
            if (!streamLimiter.tryAcquire(a.id, TextPanelRules.CALLS_PER_SECOND))
                throw ErrorCatalog.error("rate_limited", "At most ${TextPanelRules.CALLS_PER_SECOND} stream calls a second. Send bigger chunks.", mapOf("retry_after_s" to 1))
            val id = when (val v = body.opt("interaction_id")) {
                is Number -> v.toLong()
                is String -> v.trim().toLongOrNull()
                else -> null
            } ?: throw ErrorCatalog.error("invalid_parameter", "interaction_id is required (the id from POST /v1/interactions).")
            val append = when (val v = body.opt("append")) {
                null, JSONObject.NULL -> ""
                is String -> v
                else -> throw ErrorCatalog.error("invalid_parameter", "append must be a string.")
            }
            val done = when (val v = body.opt("done")) {
                null, JSONObject.NULL -> false
                is Boolean -> v
                else -> throw ErrorCatalog.error("invalid_parameter", "done must be true or false.")
            }
            TextPanelRules.checkAppend(append)?.let { throw ErrorCatalog.error("invalid_parameter", it) }
            if (append.isEmpty() && !done) throw ErrorCatalog.error("invalid_parameter", "Send append (text to add) or done=true.")
            // LOG-02: only the length is logged, never the text (append is also a private field).
            params.put("interaction_id", id).put("append_chars", append.length).put("done", done)
            val i = try { app.interactions.appendText(a.id, id, append, done) } catch (e: InteractionError) { fail(e) }
            Reply.Json(JSONObject().put("interaction_id", i.id).put("length", i.streamText.length).put("status", i.status.id).put("done", i.streamDone))
        }
        // EVT-06: a signed test event to the caller's callback URL, sent now.
        endpoint(Endpoints.CALLBACK_TEST) { app.callbackDispatcher.test(this) }

        endpoint(Endpoints.EVENTS) {
            val a = agent!!
            app.interactions.notePoll(a.id)
            val since = query("since")?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw ErrorCatalog.error("invalid_parameter", "since must be a cursor from next_cursor.") }
            val types = query("types")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
            val wait = waitArg()
            val limit = query("limit")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..100 } ?: throw ErrorCatalog.error("invalid_parameter", "limit must be 1 to 100.") }
            var seen = app.interactions.changed.value
            var list = app.interactions.eventsFor(a.id, since, types, limit)
            if (list.isEmpty() && wait > 0 && since != null) {
                if (!api.waits.enter(a.id)) throw ErrorCatalog.error("too_many_waits", "At most 4 open waits per agent.")
                try {
                    withTimeoutOrNull(wait * 1000L) {
                        while (list.isEmpty()) {
                            app.interactions.changed.first { it > seen }
                            seen = app.interactions.changed.value
                            list = app.interactions.eventsFor(a.id, since, types, limit)
                        }
                    }
                } finally { api.waits.exit(a.id) }
            }
            app.interactions.notePoll(a.id)
            val next = list.lastOrNull()?.id ?: since ?: 0L
            val permitted = list.filter { EventAccess.allowed(app.agents.get(a.id), it.type) }
            params.put("returned", permitted.size)
            Reply.Json(JSONObject().put("events", JSONArray(permitted.map { it.toJson(::isoAt) })).put("next_cursor", next.toString()))
        }
    }
}
