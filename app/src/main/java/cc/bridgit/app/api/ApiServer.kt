package cc.bridgit.app.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import cc.bridgit.app.api.engine.GuardedCio
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.AuthResult
import cc.bridgit.app.core.Net
import cc.bridgit.app.log.LogEntry
import cc.bridgit.app.log.Redactor
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.*

/** What an endpoint hands back to the pipeline. */
sealed class Reply {
    class Stream(val contentType: String, val write: (java.io.OutputStream) -> Unit) : Reply()
    class Json(val body: JSONObject, val status: Int = 200) : Reply()
    class Bytes(val bytes: ByteArray, val contentType: String, val headers: Map<String, String> = emptyMap()) : Reply()
    class Text(val text: String, val contentType: String, val status: Int = 200) : Reply()
}

/**
 * One authenticated request, as seen by an endpoint. It carries its own query, path params and
 * headers, so the same handler runs for an HTTP call or in-process for an MCP tool call (API-15).
 */
class Req(val spec: EndpointSpec, val agent: Agent?, val requestId: String, val source: String,
          val body: JSONObject, private var rawFile: java.io.File?, val queries: Map<String, String>,
          private val pathParams: Map<String, String>, private val headers: (String) -> String?) {
    constructor(call: ApplicationCall, spec: EndpointSpec, agent: Agent?, requestId: String, source: String,
                body: JSONObject, rawFile: java.io.File? = null) : this(spec, agent, requestId, source, body, rawFile,
        call.request.queryParameters.entries().associate { it.key to (it.value.firstOrNull() ?: "") },
        call.parameters.names().associateWith { call.parameters[it] ?: "" }, { call.request.headers[it] })

    /**
     * A non-JSON body on a large-body endpoint, streamed to a temp file (API-11). The caller takes ownership:
     * it must move or delete the file. Files nobody took are deleted when the request ends.
     */
    fun takeRawFile(): java.io.File? = rawFile.also { rawFile = null }
    fun hasRawFile(): Boolean = rawFile != null
    internal fun leftoverRawFile(): java.io.File? = rawFile
    /** What goes into the log entry's params (redacted before storing). */
    /** Only the successful intentional listener-pause reply may complete after its own pause; never set on replay. */
    internal var listenerPauseCommitted = false
    var invocation = "http"
    var viaMcp = false
    val params = JSONObject()
    var bytesOut = 0L
    var message: String? = null
    var data: JSONObject? = null
    fun query(name: String): String? = queries[name]
    fun path(name: String): String? = pathParams[name]
    fun header(name: String): String? = headers(name)
    /** Query value, or body value for POSTs. */
    fun arg(name: String): Any? = queries[name] ?: body.opt(name)
    val agentName get() = agent?.name ?: "agent"
}

/**
 * The HTTP API (section 7). Bound only to the phone's Tailscale addresses
 * (ARC-04); every request is checked, rate-limited and logged once.
 * Ktor's own call logging is not installed (SEC-14a).
 */
class RequestState {
    val limiter = RateLimiter()
    val waits = WaitGate()
    val idempotency = IdempotencyCache()
    val uploadSlots = kotlinx.coroutines.sync.Semaphore(2)
}

class ApiServer(val service: BridgeService, private val hosts: List<String>, val port: Int) {
    val app = service.application as BridgeApp
    private val limiter = app.requests.limiter
    val waits = app.requests.waits
    private val idempotency = app.requests.idempotency
    @Volatile private var engine: cc.bridgit.app.api.engine.GuardedCioEngine? = null
    /** Large uploads read at once (finding: concurrent 25 MB bodies). */
    private val uploadSlots = app.requests.uploadSlots
    private val uploads = java.io.File(app.cacheDir, "uploads").apply { mkdirs() }

    /** Register real handlers without opening any listener or trusting loopback. */
    fun prepareRelay() {
        val route = io.ktor.server.routing.Route(null, io.ktor.server.routing.RootRouteSelector(""))
        app.registry.modules.forEach { m -> with(m) { with(route) { install(this@ApiServer) } } }
    }

    fun start() {
        val env = applicationEngineEnvironment {
            hosts.forEach { h -> connector { host = h; port = this@ApiServer.port } }
            module {
                routing {
                    cc.bridgit.app.platform.PlatformApi(app, this@ApiServer).install(this)
                    route("/v1") { app.registry.modules.forEach { m -> with(m) { install(this@ApiServer) } } }
                    route("{...}") { handle { notFound(call) } }
                }
            }
        }
        val e = embeddedServer(GuardedCio, env) { connectionIdleTimeoutSeconds = 90 }
        e.start(wait = false)
        engine = e
        // A listening socket that died (Tailscale went away) ends the engine: ask for a rebind now.
        e.stopped.invokeOnCompletion { cause -> if (cause != null && engine === e) service.poke("listener died") }
    }

    /** False once the engine has ended on its own (its listening socket died). */
    val running get() = engine?.stopped?.isCompleted == false

    fun stop() {
        val e = engine
        engine = null
        e?.stop(500, 2000)
    }

    /**
     * Registers [spec] on this route and runs [block] through the full pipeline. [admit] runs before the body is
     * read (after source, key and power checks): endpoints with large bodies refuse switched-off or
     * rate-limited calls there, before any upload is accepted. It is skipped for idempotent replays.
     * The handler is also recorded for in-process MCP tool calls (API-15).
     */
    fun Route.endpoint(spec: EndpointSpec, admit: (suspend (Map<String, String>, Agent?) -> Unit)? = null, block: suspend Req.() -> Reply) {
        handlers[handlerKey(spec)] = block
        admit?.let { admits[handlerKey(spec)] = it }
        val method = io.ktor.http.HttpMethod.parse(spec.method)
        route(spec.path, method) { handle { pipeline(call, spec, admit, block) } }
    }

    private suspend fun notFound(call: ApplicationCall) {
        val rid = newRequestId()
        val source = sourceOf(call)
        call.response.header("X-Request-Id", rid)
        val err = if (!Net.isTailnet(source)) ErrorCatalog.error("forbidden_source", "Only devices on the owner's Tailscale network may call Bridgit.")
        else ErrorCatalog.error("not_found", "No such endpoint. GET /v1/guide lists them all.")
        call.respondText(err.toJson(rid).toString(), ContentType.Application.Json, HttpStatusCode.fromValue(err.http))
        app.log.append(LogEntry(ts = System.currentTimeMillis(), channel = "audit", kind = "request", level = "warn",
            sourceIp = source, method = call.request.httpMethod.value, endpoint = Redactor.string(call.request.path(), 120),
            http = err.http, result = err.code, requestId = rid, message = "Unknown endpoint"))
    }

    private suspend fun pipeline(call: ApplicationCall, spec: EndpointSpec, admit: (suspend (Map<String, String>, Agent?) -> Unit)?,
                                 block: suspend Req.() -> Reply) {
        val started = System.currentTimeMillis()
        val rid = newRequestId()
        val source = sourceOf(call)
        call.response.header("X-Request-Id", rid)
        BrandHeaders.withLegacy(mapOf("X-Bridgit-API-Revision" to ApiChanges.CURRENT.toString())).forEach { (k, v) -> call.response.header(k, v) }
        val logParams = JSONObject()
        call.request.queryParameters.entries().forEach { (k, v) -> logParams.put(k, spec.loggedParam(k, v.firstOrNull())) }
        call.parameters.names().filter { "{$it}" in spec.path }.forEach { logParams.put(it, call.parameters[it]) }
        var agent: Agent? = null
        var http = 200
        var result = "ok"
        var bytesIn = 0L
        var req: Req? = null
        var revokedReason: String? = null
        var idemKey: String? = null
        var rawFile: java.io.File? = null
        /** Satellites: where a forwarded call ran, its bytes out, and (on a satellite) the hub agent it ran for. */
        var ranOn: String? = null
        var forwardedOut = 0L
        var onBehalf: Pair<String, String>? = null
        val inFlight = app.latency.begin()
        try {
            if (!Net.isTailnet(source)) throw ErrorCatalog.error("forbidden_source", "Only devices on the owner's Tailscale network may call Bridgit.")
            if (spec.auth) {
                val token = call.request.headers["Authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
                when (val r = app.agents.authenticate(token)) {
                    is AuthResult.Ok -> {
                        if(r.agent.connection == cc.bridgit.app.core.Connection.RELAY) throw ErrorCatalog.error("unauthorized", "Relay credentials require the relay transport.")
                        agent = r.agent
                    }
                    else -> {
                        // SEC-06: only requests without a valid key are blocked by the per-IP failure count.
                        // tryAcquire checks and records in one step, so concurrent bad keys can't slip past.
                        if (!limiter.tryAcquire("auth:$source", RateLimiter.AUTH_FAILS_PER_IP)) throw authBlockedError(source)
                        if (r is AuthResult.Revoked && r.reason == cc.bridgit.app.link.Links.REVOKED_REASON) {
                            // LNK-30: a link agent removed by a disconnect. The other device marks its side off.
                            revokedReason = r.reason
                            throw ErrorCatalog.error("link_revoked", "The link with this device was disconnected. Linking again needs a new link code.", mapOf("scope" to "link"))
                        }
                        if (r is AuthResult.Revoked) {
                            revokedReason = r.reason
                            throw ErrorCatalog.error("unauthorized",
                                if (r.reason == "key_rotated") "${r.agentName}'s key was rotated. Ask the owner for the new setup prompt."
                                else "${r.agentName} was removed from Bridgit. Ask the owner to add you again.",
                                mapOf("reason" to r.reason))
                        }
                        throw ErrorCatalog.error("unauthorized", "Missing or wrong key. Send Authorization: Bearer <your key>.")
                    }
                }
                requireDevice(agent!!, source)
                // A link agent (docs/spec-linked-bridgits.md, LNK-11): the other Bridgit of a link, using this one.
                if (app.links.isLinkAgent(agent!!.id)) onBehalf = linkAgentCall(spec, agent!!) { call.request.headers[it] }
                requireGrant(agent!!, spec)
                app.agents.recordUse(agent!!.id, source)
                // Agents switch models: X-Bridgit-Model (and -Harness) on any call keeps the owner's card current.
                call.request.headers["X-Bridgit-Model"]?.let { m ->
                    app.agents.reportModel(agent!!.id, m, call.request.headers["X-Bridgit-Harness"])
                        ?.let { app.log.system("audit", "agent", "${it.name} says it runs on ${it.modelLabel}") }
                }
                if (agent!!.knownApiRevision < ApiChanges.CURRENT)
                    BrandHeaders.withLegacy(mapOf("X-Bridgit-Whats-New" to "/v1/changes?since=${agent!!.knownApiRevision}"))
                        .forEach { (k, v) -> call.response.header(k, v) }
                spec.power?.let { PowerGate.require(agent, it, spec.fullPath) }
            }
            if (spec.idempotencyRequired && call.request.headers["Idempotency-Key"].isNullOrBlank())
                throw ErrorCatalog.error("invalid_parameter", "Idempotency-Key is required for this operation")
            // Satellites (docs/spec-satellites.md, mode A): a target runs the call on a satellite instead of here.
            val target = cc.bridgit.app.satellite.SatellitePolicy.target(call.request.headers[cc.bridgit.app.satellite.SatellitePolicy.TARGET_HEADER],
                call.request.queryParameters[cc.bridgit.app.satellite.SatellitePolicy.TARGET_QUERY])
            if (target != null && !app.links.isSelf(target)) {
                logParams.put("target", Redactor.string(target, 50))
                val a = agent ?: throw ErrorCatalog.error("not_on_target", "${spec.fullPath} can't run on another device.")
                // LNK-32: a call is passed on at most once; a link agent's key never forwards.
                if (app.links.isLinkAgent(a.id)) throw ErrorCatalog.error("not_on_target", "A call that came over a link can't be passed on to another device. To use that device, link it directly.")
                val f = forwardToSatellite(call, spec, a, source, target, rid)
                http = f.status; result = f.result; ranOn = target; forwardedOut = f.bytesOut; bytesIn = f.bytesIn
                return
            }
            // Pairing has no key, but wrong codes count as failed sign-ins (SEC-06).
            if ((spec.path == "/pair" || spec.path == "/satellite/claim" || spec.path == "/link/claim") && limiter.count("auth:$source") >= RateLimiter.AUTH_FAILS_PER_IP) throw authBlockedError(source)
            // Before any body is read: the endpoint's own admission (switch, rate, queue). A request that will be
            // answered from the idempotency cache skips it, as it never runs the endpoint.
            if (admit != null) {
                val key = call.request.headers["Idempotency-Key"]?.trim()
                val replay = spec.idempotent && !spec.backendIdempotency && agent != null && key != null && IdempotencyCache.KEY_RE.matches(key) && idempotency.hasResult(agent!!.id, key, "${spec.method} ${spec.fullPath}")
                if (!replay) admit(call.request.queryParameters.entries().associate { it.key to (it.value.firstOrNull() ?: "") }, agent)
            }
            val body = if (spec.method == "POST" || spec.method == "PUT" || spec.method == "PATCH") {
                val ctype = call.request.headers["Content-Type"] ?: ""
                // Media endpoints take raw bytes (audio/*, image/*, ...), streamed to a size-limited temp file;
                // everything else is a JSON object, held to the normal 2 MB even on those endpoints.
                if (spec.maxBody > MAX_BODY && ctype.isNotEmpty() && !ctype.startsWith("application/json") && (spec.capability == "files" || !ctype.startsWith("text/"))) {
                    val f = readBodyToFile(call, spec.maxBody)
                    rawFile = f
                    bytesIn = f.length()
                    JSONObject()
                } else {
                    val bytes = readBody(call, minOf(spec.maxBody, MAX_BODY))
                    bytesIn = bytes.size.toLong()
                    parseBody(bytes)
                }
            } else JSONObject()
            body.keys().forEach { k ->
                val v = body.get(k)
                logParams.put(k, spec.loggedParam(k, v))
            }
            val r = Req(call, spec, agent, rid, source, body, rawFile)
            req = r

            // API-13: one result per (agent, Idempotency-Key) for 24 h.
            idemKey = call.request.headers["Idempotency-Key"]?.trim()?.takeIf { spec.idempotent && !spec.backendIdempotency && agent != null }
            if (idemKey != null) {
                if (!IdempotencyCache.KEY_RE.matches(idemKey)) { idemKey = null
                    throw ErrorCatalog.error("invalid_parameter", "Idempotency-Key must be 8-128 letters, digits, - or _ (a UUID works).") }
                logParams.put("idempotency", "key")
                when (val c = idempotency.claim(agent!!.id, idemKey, "${spec.method} ${spec.fullPath}")) {
                    is IdempotencyCache.Claim.Full -> { idemKey = null; throw ErrorCatalog.error("rate_limited", "Too many Idempotency-Keys in the last 24 h. Retry later.", mapOf("retry_after_s" to 60)) }
                    is IdempotencyCache.Claim.Mismatch -> { idemKey = null; throw ErrorCatalog.error("invalid_parameter", "This Idempotency-Key was already used for a different endpoint. Use a new key for each new request.") }
                    is IdempotencyCache.Claim.Running -> { idemKey = null; throw ErrorCatalog.error("in_progress", "A request with this Idempotency-Key is still running. Retry with the same key shortly.") }
                    is IdempotencyCache.Claim.Expired -> { idemKey = null; throw ErrorCatalog.error("idempotency_expired", "This Idempotency-Key was already used; its media result is no longer kept.") }
                    is IdempotencyCache.Claim.Done -> {
                        idemKey = null
                        requireReplayUsable(spec)
                        http = c.stored.status
                        logParams.put("replay", true)
                        c.stored.headers.forEach { (k, v) -> call.response.header(k, v) }
                        call.response.header("Idempotent-Replay", "true")
                        // API-03: the replay gets its own request_id (matching its header and log entry);
                        // the first request's id is kept as idempotent_replay_of.
                        var body = c.stored.body!!
                        if (c.stored.contentType.startsWith("application/json")) runCatching {
                            val o = JSONObject(String(body))
                            val first = o.optString("request_id")
                            body = o.put("request_id", rid).put("idempotent_replay_of", first).toString().toByteArray()
                            r.data = JSONObject().put("replay_of", first)
                        }
                        r.bytesOut = body.size.toLong()
                        call.respondBytes(body, ContentType.parse(c.stored.contentType), HttpStatusCode.fromValue(http))
                        r.message = "${agent!!.name}: replayed ${spec.method} ${spec.fullPath} (Idempotency-Key)"
                        return
                    }
                    IdempotencyCache.Claim.New -> {}
                }
            }

            // Capture limits are charged only for work that really runs (not for replays, SEC-07).
            chargeCapture(spec, agent, source, onBehalf?.first)
            val reply = runGuarded(r, block)
            if (reply is Reply.Stream) {
                call.respondOutputStream(ContentType.parse(reply.contentType)) {
                    withContext(Dispatchers.IO) { reply.write(this@respondOutputStream) }
                }
                return
            }
            val (status, ctype, bytes, headers) = render(reply, rid)
            http = status
            headers.forEach { (k, v) -> call.response.header(k, v) }
            if (r.bytesOut == 0L) r.bytesOut = bytes.size.toLong()
            idemKey?.let { idempotency.finish(agent!!.id, it, status, ctype, bytes, headers, spec.media); idemKey = null }
            call.respondBytes(bytes, ContentType.parse(ctype), HttpStatusCode.fromValue(status))
        } catch (e: ApiError) {
            http = e.http; result = e.code
            val body = e.toJson(rid).toString().toByteArray()
            idemKey?.let { k -> agent?.let { idempotency.finish(it.id, k, e.http, "application/json", body, emptyMap(), false) } }
            call.respondBytes(body, ContentType.Application.Json, HttpStatusCode.fromValue(e.http))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                http = 499; result = "cancelled"
                idemKey?.let { k -> agent?.let { idempotency.abandon(it.id, k) } }
                throw e
            }
            http = 500; result = "internal_error"
            idemKey?.let { k -> agent?.let { idempotency.abandon(it.id, k) } }
            app.log.system("diagnostic", "crash", "Error in ${spec.fullPath}: ${e.javaClass.simpleName} at ${e.stackTrace.firstOrNull()}", level = "error")
            val err = ErrorCatalog.error("internal_error", "Bridgit hit a bug (${e.javaClass.simpleName}). It's in the diagnostic log.")
            runCatching { call.respondBytes(err.toJson(rid).toString().toByteArray(), ContentType.Application.Json, HttpStatusCode.InternalServerError) }
        } finally {
            app.latency.end()
            // An upload the endpoint didn't take (error, replay, crash) is deleted now.
            (if (req != null) req?.leftoverRawFile() else rawFile)?.delete()
            val r = req
            r?.params?.keys()?.forEach { logParams.put(it, r.params.get(it)) }
            if (spec.path == "/pair") logParams.remove("code")
            app.usage.count("api:${spec.method} ${spec.fullPath}:$result")
            val name = agent?.name?.let { n -> onBehalf?.let { "${it.second} via ${n.removePrefix("Hub ")}" } ?: n }
            val tookMs = System.currentTimeMillis() - started
            app.latency.record(ApiLatency.Call(ts = started, api = OpenApi.opId(spec), method = spec.method, path = spec.fullPath, via = "http",
                requestId = rid, agentId = agent?.id, agent = name, durationMs = tookMs, http = http, result = result,
                bytesIn = bytesIn, bytesOut = r?.bytesOut ?: forwardedOut, inFlight = inFlight, ranOn = ranOn))
            val msg = r?.message ?: describe(name, spec, http, result) + (ranOn?.let { " on $it" } ?: "")
            app.log.append(LogEntry(
                ts = started, channel = "audit", kind = if (spec.path == "/pair") "pairing" else "request",
                level = when { http >= 500 -> "error"; http >= 400 -> "warn"; else -> "info" },
                agentId = agent?.id, agent = name, sourceIp = source, method = spec.method, endpoint = spec.fullPath,
                params = Redactor.params(logParams), http = http, result = result, bytesIn = bytesIn,
                bytesOut = r?.bytesOut ?: forwardedOut, durationMs = tookMs, requestId = rid,
                message = Redactor.string(msg, 300),
                data = (r?.data ?: JSONObject()).also { d -> revokedReason?.let { d.put("reason", it) }; ranOn?.let { d.put("ran_on", it) }
                    onBehalf?.let { d.put("on_behalf_of", JSONObject().put("id", it.first).put("name", it.second)) } }.let { Redactor.params(it) }))
        }
    }

    /** SEC-07 capture limits, per agent and per source address. */
    /** [sub]: on a satellite, the hub agent a hub-link call is for, so each hub agent has its own bucket (SAT-37); the per-address limit still caps the hub as a whole. */
    private fun chargeCapture(spec: EndpointSpec, agent: Agent?, source: String, sub: String? = null) {
        if (!spec.capture) return
        val a = agent!!
        val b = RateLimiter.bucket(spec.capability)
        val k = "${b.key}-agent:${a.id}" + (sub?.let { ":$it" } ?: "")
        if (!limiter.tryAcquire(k, b.perAgent))
            throw ErrorCatalog.error("rate_limited", "At most ${b.perAgent} ${b.noun} per minute per agent.",
                mapOf("retry_after_s" to limiter.retryAfterS(k)))
        val ik = "${b.key}-ip:$source"
        if (!limiter.tryAcquire(ik, b.perIp))
            throw ErrorCatalog.error("rate_limited", "At most ${b.perIp} ${b.noun} per minute from one address.",
                mapOf("retry_after_s" to limiter.retryAfterS(ik)))
    }

    /**
     * A call made with a link agent's key (docs/spec-linked-bridgits.md, LNK-11/12): the other Bridgit of a link,
     * using this one. Its grants are the direction's service list, so the grant check that follows is the target's
     * opt-in; here, the shape of a link: the owner's pause pills, a direction being disconnected or paused, and only
     * calls that pass a link. Returns the agent the other device calls for (its On-Behalf-Of), when it says.
     */
    private suspend fun linkAgentCall(spec: EndpointSpec, agent: Agent, header: (String) -> String?): Pair<String, String>? {
        val P = cc.bridgit.app.satellite.SatellitePolicy
        val hub = app.hubLinks.isHub(agent.id)
        // The owner's pills, by role as on Linked devices: Hubs pauses this device's hubs, Satellites its satellites, both ways.
        if (hub && cc.bridgit.app.satellite.LinkPause.hubsPaused)
            throw ErrorCatalog.error("forbidden", cc.bridgit.app.satellite.LinkPause.HUBS_MESSAGE, mapOf("reason" to "hubs_paused"))
        if (!hub && cc.bridgit.app.satellite.LinkPause.satellites)
            throw ErrorCatalog.error("forbidden", cc.bridgit.app.satellite.LinkPause.SATELLITES_MESSAGE, mapOf("reason" to "satellites_paused"))
        val plumbing = cc.bridgit.app.link.LinkPolicy.isPlumbing(spec)
        val rec = app.links.byInAgent(agent.id)
        if (!plumbing) {
            // While Undo can still run the other side is told "paused", so it doesn't mark its side off for good.
            if (rec != null && !rec.inRevoked && rec.covers("in"))
                throw ErrorCatalog.error("link_paused", "The owner is disconnecting this link here. Don't retry.", mapOf("reason" to "disconnecting", "link_id" to rec.id))
            if (rec != null && rec.inRevoked)
                throw ErrorCatalog.error("link_revoked", "This device no longer lets ${app.links.peerLabel(rec)} use it: the link was disconnected. Linking again needs a new link code.", mapOf("scope" to "direction", "link_id" to rec.id))
            if (app.agents.get(agent.id)?.paused == true)
                throw ErrorCatalog.error("link_paused", "The owner paused this link here. Wait until it is resumed; don't retry before then.", mapOf("link_id" to rec?.id))
        }
        // Trust tier (§16): what the tier allows bounds the grants, whatever they say; a link agent with no record gets
        // the most restrictive tier.
        val T = cc.bridgit.app.link.LinkTierPolicy
        val tier = rec?.effectiveTier ?: cc.bridgit.app.link.LinkTier.PUBLIC
        if (!plumbing && rec != null && rec.expired())
            throw ErrorCatalog.error("link_expired", "This device's link with ${app.links.peerLabel(rec)} has ended. Its owner can renew it or link again.", mapOf("link_id" to rec.id, "tier" to tier.wire))
        val services = T.effectiveServices(tier, cc.bridgit.app.link.LinkModel.servicesOf(app.agents.get(agent.id)?.caps))
        if (!cc.bridgit.app.link.LinkPolicy.mayCall(spec, services))
            throw ErrorCatalog.error("not_on_target", "${spec.method} ${spec.fullPath} doesn't pass a link here (this device shares: ${cc.bridgit.app.link.LinkModel.words(services)}).")
        if (!T.mayCall(tier, spec, services))
            throw ErrorCatalog.error("tier_not_allowed", "${spec.method} ${spec.fullPath} is only for the owner's own devices; this link is ${T.label(tier)}.", mapOf("tier" to tier.wire))
        T.perMinute(tier)?.let { n -> if (!limiter.tryAcquire("link-tier:${agent.id}", n))
            throw ErrorCatalog.error("rate_limited", "At most $n calls a minute from ${T.label(tier)}.", mapOf("retry_after_s" to limiter.retryAfterS("link-tier:${agent.id}"))) }
        if (T.needsApproval(tier, spec)) cc.bridgit.app.link.PublicLinkApproval.require(app, rec?.let { app.links.peerLabel(it) } ?: agent.name, spec)
        if (!plumbing && rec != null) runCatching { cc.bridgit.app.link.LinkNotifier.used(app, rec) }
        return if (hub) P.parseOnBehalfOf(header(P.ON_BEHALF_HEADER)) else null
    }

    // ---- Satellites: forwarding a call to a satellite (docs/spec-satellites.md, mode A) -------

    class Forwarded(val status: Int, val result: String, val bytesIn: Long, val bytesOut: Long)

    /**
     * Runs [spec] on satellite [target] for [agent] and streams the satellite's answer back unchanged. The hub's own
     * checks (source, key, device lock, grant, power) have passed; here: the satellite exists, the owner lets this
     * agent use it, the endpoint can pass a link, and the hub's limits. The satellite then applies its own switches,
     * grants and limits to the hub's link key. Pausing or narrowing the agent, or taking the satellite away from it,
     * cancels the call (as [runGuarded] does for local work).
     */
    private suspend fun forwardToSatellite(call: ApplicationCall, spec: EndpointSpec, agent: Agent, source: String,
                                           target: String, rid: String): Forwarded {
        val P = cc.bridgit.app.satellite.SatellitePolicy
        // sat: ids keep their satellite error codes (aliases, LNK-49); names use the link ones.
        val old = cc.bridgit.app.satellite.SatelliteLink.ID_RE.matches(target)
        val sat = app.links.reach(target, agent)
        if (!P.forwardable(spec)) throw ErrorCatalog.error(if (old) "not_on_satellite" else "not_on_target", "${spec.method} ${spec.fullPath} can't run on a linked device yet. Leave target out to run it on this device.")
        if (!limiter.tryAcquire("sat-fwd:${agent.id}", RateLimiter.SATELLITE_CALLS_PER_AGENT))
            throw ErrorCatalog.error("rate_limited", "At most ${RateLimiter.SATELLITE_CALLS_PER_AGENT} satellite calls per minute per agent.",
                mapOf("retry_after_s" to limiter.retryAfterS("sat-fwd:${agent.id}")))
        chargeCapture(spec, agent, source)
        var file: java.io.File? = null
        try {
            val ctype = call.request.headers["Content-Type"]
            val body: okhttp3.RequestBody? = when {
                spec.method == "GET" || spec.method == "DELETE" -> null
                spec.maxBody > MAX_BODY && ctype != null && !ctype.startsWith("application/json") ->
                    readBodyToFile(call, spec.maxBody).also { file = it }.asRequestBody(ctype.toMediaTypeOrNull())
                else -> readBody(call, MAX_BODY).toRequestBody(ctype?.toMediaTypeOrNull())
            }
            val bytesIn = file?.length() ?: (body?.contentLength() ?: 0L)
            val headers = mutableListOf<Pair<String, String>>()
            call.request.headers.forEach { k, vs ->
                val lk = k.lowercase()
                if (lk !in P.DROPPED_REQUEST_HEADERS && !lk.startsWith("x-bridgit-") && !lk.startsWith("x-bridget-")) vs.forEach { headers += k to it }
            }
            headers += P.ON_BEHALF_HEADER to P.onBehalfOf(agent.id, agent.name)
            call.request.headers["Idempotency-Key"]?.trim()?.let { k ->
                if (!IdempotencyCache.KEY_RE.matches(k)) throw ErrorCatalog.error("invalid_parameter", "Idempotency-Key must be 8-128 letters, digits, - or _ (a UUID works).")
                if (spec.idempotent) headers += "Idempotency-Key" to P.forwardedIdempotencyKey(agent.id, k)
            }
            val query = call.request.queryParameters.entries().filter { it.key != P.TARGET_QUERY }.flatMap { e -> e.value.map { e.key to it } }
            val okCall = cc.bridgit.app.satellite.SatelliteClient.newCall(sat.base, sat.key, spec.method, call.request.path(), query, headers, body, toHub = sat.toHub)
            // Stops the satellite's work when this agent may no longer do it (paused, removed, re-keyed, narrowed, untargeted).
            var revoked = false
            return coroutineScope {
                val watcher = launch {
                    while (isActive) {
                        delay(200)
                        val live = app.agents.get(agent.id)
                        if (live == null || live.keyHash != agent.keyHash || live.suspended || !live.allows(spec.capability) || !sat.stillAllowed(agent.id)) {
                            revoked = true; okCall.cancel(); break
                        }
                    }
                }
                try {
                    val res = try {
                        withContext(Dispatchers.IO) {
                            cc.bridgit.app.outbound.Outbound.on(cc.bridgit.app.outbound.Outbound.Actor.agent(agent.id, agent.name)) { okCall.execute() }
                        }
                    } catch (e: java.io.IOException) {
                        if (revoked) { requireGrant(agent, spec)
                            throw ErrorCatalog.error("conflict", "Authorization changed while this request was running. Start a new request if it is still wanted.") }
                        sat.offline()
                        throw ErrorCatalog.error(if (old) "satellite_offline" else "target_offline", "${sat.name} didn't answer. Retry later; the owner may need to check Tailscale or Bridgit on it.",
                            mapOf("last_seen" to (sat.lastSeen.takeIf { it > 0 }), "retry_after_s" to 30))
                    }
                    res.use { r ->
                        sat.seen(r.code)
                        r.headers.forEach { (k, v) ->
                            val lk = k.lowercase()
                            when {
                                lk == "x-request-id" -> call.response.header(P.SAT_REQUEST_ID_HEADER, v)
                                lk in P.DROPPED_RESPONSE_HEADERS -> {}
                                else -> runCatching { call.response.header(k, v) }
                            }
                        }
                        call.response.header(P.RAN_ON_HEADER, if (old) sat.id else sat.rec.peerName ?: target)
                        val ct = r.body?.contentType()?.toString()?.let { runCatching { ContentType.parse(it) }.getOrNull() } ?: ContentType.Application.Json
                        // An error is small JSON: read it whole so the hub's log names the satellite's error code.
                        if (r.code !in 200..299 && ct.match(ContentType.Application.Json)) {
                            val bytes = withContext(Dispatchers.IO) { r.body?.source()?.let { src -> src.request(256 * 1024L); src.buffer.readByteArray() } ?: ByteArray(0) }
                            call.respondBytes(bytes, ct, HttpStatusCode.fromValue(r.code))
                            val err = runCatching { JSONObject(String(bytes)) }.getOrNull()
                            val code = err?.optString("error")?.takeIf { it.isNotEmpty() }
                            // LNK-30: the other device disconnected while this one was offline: mark this side off too.
                            if (code == "link_revoked") runCatching { app.links.farRevoked(sat.rec, err.optString("scope").ifEmpty { null }) }
                            return@coroutineScope Forwarded(r.code, code ?: "satellite_http_${r.code}", bytesIn, bytes.size.toLong())
                        }
                        var out = 0L
                        var cut: String? = null
                        call.respondOutputStream(ct, HttpStatusCode.fromValue(r.code)) {
                            withContext(Dispatchers.IO) {
                                // The headers are sent: a failure now can only end the body early, never become an error reply.
                                try {
                                    r.body?.byteStream()?.use { input ->
                                        val buf = ByteArray(64 * 1024)
                                        while (true) { val n = input.read(buf); if (n < 0) break; write(buf, 0, n); out += n }
                                    }
                                } catch (e: java.io.IOException) { cut = if (revoked) "conflict" else "satellite_cut_off" }
                            }
                        }
                        Forwarded(r.code, cut ?: if (r.code in 200..299) "ok" else "satellite_http_${r.code}", bytesIn, out)
                    }
                } finally { watcher.cancel() }
            }
        } finally { file?.delete() }
    }

    // ---- In-process dispatch for MCP tool calls (API-15) -------------------------------------

    /** Every registered handler, filled while routing is installed, so MCP can run an endpoint without HTTP. */
    private val handlers = java.util.concurrent.ConcurrentHashMap<String, suspend Req.() -> Reply>()
    /** Admission checks (switch, rate, queue) of large-body endpoints; MCP runs them too, it has no upload stage. */
    private val admits = java.util.concurrent.ConcurrentHashMap<String, suspend (Map<String, String>, Agent?) -> Unit>()
    private suspend fun runGuarded(req: Req, block: suspend Req.() -> Reply): Reply = coroutineScope {
        val a = req.agent ?: return@coroutineScope req.block()
        // Everything this handler sends off the phone is recorded as caused by this agent (outbound log).
        val work = async(cc.bridgit.app.outbound.Outbound.context(cc.bridgit.app.outbound.Outbound.Actor.agent(a.id, a.name))) { req.block() }
        val watcher = launch {
            while (work.isActive) {
                delay(200)
                val live = app.agents.get(a.id)
                val switchedOff = req.spec.capture && app.registry.byId(req.spec.capability)?.enabled == false
                val scopeRevoked = req.spec.controlScope?.let { scope ->
                    !app.platform.hasGrant(a.id, scope) || !cc.bridgit.app.platform.Capabilities.enabled(app, scope)
                } ?: false
                val linkStopped = !cc.bridgit.app.link.LinkPolicy.isPlumbing(req.spec) && app.links.isLinkAgent(a.id) && app.links.inBlocked(a.id)
                val pausedNow = linkStopped || (live?.suspended == true && !(cc.bridgit.app.link.LinkPolicy.isPlumbing(req.spec) && app.links.isLinkAgent(a.id)))
                if (live == null || live.keyHash != a.keyHash || pausedNow || !live.allows(req.spec.capability) || switchedOff || scopeRevoked) {
                    work.cancel(); break
                }
            }
        }
        try {
            val reply = work.await()
            requireGrant(a, req.spec, allowListenerPaused = req.listenerPauseCommitted)
            reply
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (e is TimeoutCancellationException) throw ErrorCatalog.error("capture_timeout", "The operation did not finish before its deadline.")
            requireGrant(a, req.spec)
            if (req.spec.capture && app.registry.byId(req.spec.capability)?.enabled == false)
                throw ErrorCatalog.error("capability_disabled", "Capability was switched off during this capture.")
            throw ErrorCatalog.error("conflict", "Authorization changed while this request was running. Start a new request if it is still wanted.")
        } finally { watcher.cancel() }
    }

    /**
     * Device lock: a direct agent's key works only from the Tailscale device(s) it is locked to. A refusal is not a
     * failed sign-in (the key is right), so it doesn't count toward the per-address limit; the owner is alerted.
     */
    private fun requireDevice(agent: Agent, source: String) {
        val ip = Net.normalise(source)
        if (app.agents.checkDevice(agent.id, ip)) return
        if (!app.settings.securityDeviceLock) { app.agents.allowDevice(agent.id, ip); cc.bridgit.app.security.SecurityAlerts.newDevice(app, agent.id, ip, refused = false); return }
        cc.bridgit.app.security.SecurityAlerts.newDevice(app, agent.id, ip, refused = true)
        throw ErrorCatalog.error("wrong_device", "${agent.name}'s key is locked to another device. Use it from the device it was set up on, or ask the owner to allow this one.")
    }

    private fun requireReplayUsable(spec: EndpointSpec) {
        if (app.registry.byId(spec.capability)?.enabled == false)
            throw ErrorCatalog.error("capability_disabled", "The capability is off; cached responses are unavailable.")
    }

    private fun requireGrant(agent: Agent, spec: EndpointSpec, allowListenerPaused: Boolean = false) {
        val live = app.agents.get(agent.id) ?: throw ErrorCatalog.error("unauthorized", "Agent removed.")
        if (live.keyHash != agent.keyHash) throw ErrorCatalog.error("unauthorized", "Agent key was rotated during the request.")
        // A paused link agent still makes the link's own calls (events, disconnect), so both sides stay in step.
        val plumbing = cc.bridgit.app.link.LinkPolicy.isPlumbing(spec) && app.links.isLinkAgent(live.id)
        if (!live.apiAccess && !plumbing) throw ApiAccess.refusal(live)
        if (live.paused && !plumbing)
            throw ErrorCatalog.error("forbidden", "The owner paused ${live.name} on this phone. Wait until they resume you; don't retry before then.",
            mapOf("reason" to "agent_paused"))
        if (!live.allows(spec.capability)) throw ErrorCatalog.error("agent_not_allowed", "This agent does not have ${spec.capability} access. Ask the owner to update its permissions on the phone.")
        spec.controlScope?.let { scope ->
            if (scope == "owner.configuration") cc.bridgit.app.ownerapi.ConfigurationAccess(app, live).require(allowListenerPaused)
            else app.platform.authorize(live.id, scope)
        }
        app.alerts.checkAgentCall(exempt = cc.bridgit.app.alerts.EmailGate.exempt(spec.capability))
    }

    private fun handlerKey(spec: EndpointSpec) = "${spec.method} ${spec.path}"

    /** The MCP endpoint's JSON-RPC side; StatusModule routes POST /v1/mcp to it. */
    val mcp = McpDispatch(this)

    /** What an in-process call produced: the rendered reply (an error JSON when [error] is set). */
    class Outcome(val status: Int, val contentType: String, val bytes: ByteArray, val headers: Map<String, String>, val error: ApiError? = null)

    /**
     * Runs [spec]'s handler in-process for an MCP tool call, with the checks the HTTP pipeline applies
     * once the key is accepted: power (Fixer Mode / Super Powers), Idempotency-Key, capture limits (the handler checks its own
     * switch and rate limits as usual). Writes its own log entry for the tool's real path, marked via=mcp.
     * The caller (POST /v1/mcp) has already passed the source and key checks for [agent].
     */
    suspend fun dispatch(spec: EndpointSpec, agent: Agent, source: String, queries: Map<String, String>,
                         pathParams: Map<String, String>, body: JSONObject, idempotencyKey: String?, via: String = "mcp"): Outcome {
        val started = System.currentTimeMillis()
        val rid = newRequestId()
        val logParams = JSONObject()
        queries.forEach { (k, v) -> logParams.put(k, spec.loggedParam(k, v)) }
        pathParams.forEach { (k, v) -> logParams.put(k, v) }
        body.keys().forEach { k ->
            val v = body.get(k)
            logParams.put(k, spec.loggedParam(k, v))
        }
        logParams.put("via", via)
        val r = Req(spec, agent, rid, source, body, null, queries, pathParams) { name ->
            when { name.equals("Content-Type",ignoreCase=true) -> "application/json"; name.equals("Idempotency-Key",ignoreCase=true) -> idempotencyKey; else -> null }
        }
        r.viaMcp = true
        r.invocation = via
        var http = 200
        var result = "ok"
        var idemKey: String? = null
        val inFlight = app.latency.begin()
        try {
            // A linked Bridgit over the relay gets the same link checks as over Tailscale.
            if (app.links.isLinkAgent(agent.id)) linkAgentCall(spec, agent) { null }
            requireGrant(agent, spec)
            if(cc.bridgit.app.capture.CapturePresentationRules.requiresViewfinder(via) && spec.path in setOf("/camera/arm","/camera/pose/start","/lan-cameras/snapshot")) throw ApiError(409,"viewfinder_required","Person-started captures must use the visible camera capture and review flow")
            if (body.toString().toByteArray(Charsets.UTF_8).size > spec.maxBody) throw ErrorCatalog.error("payload_too_large", "Request exceeds this operation’s body limit")
            val block = handlers[handlerKey(spec)] ?: throw ErrorCatalog.error("not_found", "No such endpoint.")
            spec.power?.let { PowerGate.require(agent, it, spec.fullPath) }
            if (spec.idempotencyRequired && idempotencyKey.isNullOrBlank()) throw ErrorCatalog.error("invalid_parameter", "idempotency_key is required for this operation")
            idemKey = idempotencyKey?.trim()?.takeIf { spec.idempotent && !spec.backendIdempotency }
            if (idemKey != null) {
                if (!IdempotencyCache.KEY_RE.matches(idemKey)) { idemKey = null
                    throw ErrorCatalog.error("invalid_parameter", "idempotency_key must be 8-128 letters, digits, - or _ (a UUID works).") }
                logParams.put("idempotency", "key")
                when (val c = idempotency.claim(agent.id, idemKey, "${spec.method} ${spec.fullPath}")) {
                    is IdempotencyCache.Claim.Full -> { idemKey = null; throw ErrorCatalog.error("rate_limited", "Too many Idempotency-Keys in the last 24 h. Retry later.", mapOf("retry_after_s" to 60)) }
                    is IdempotencyCache.Claim.Mismatch -> { idemKey = null; throw ErrorCatalog.error("invalid_parameter", "This Idempotency-Key was already used for a different endpoint. Use a new key for each new request.") }
                    is IdempotencyCache.Claim.Running -> { idemKey = null; throw ErrorCatalog.error("in_progress", "A request with this idempotency_key is still running. Retry with the same key shortly.") }
                    is IdempotencyCache.Claim.Expired -> { idemKey = null; throw ErrorCatalog.error("idempotency_expired", "This idempotency_key was already used; its media result is no longer kept.") }
                    is IdempotencyCache.Claim.Done -> {
                        idemKey = null
                        requireReplayUsable(spec)
                        http = c.stored.status
                        logParams.put("replay", true)
                        var bytes = c.stored.body!!
                        if (c.stored.contentType.startsWith("application/json")) runCatching {
                            val o = JSONObject(String(bytes))
                            val first = o.optString("request_id")
                            bytes = o.put("request_id", rid).put("idempotent_replay_of", first).toString().toByteArray()
                            r.data = JSONObject().put("replay_of", first)
                        }
                        r.bytesOut = bytes.size.toLong()
                        r.message = "${agent.name}: replayed ${spec.method} ${spec.fullPath} (idempotency_key, via MCP)"
                        return Outcome(http, c.stored.contentType, bytes, c.stored.headers + ("Idempotent-Replay" to "true"))
                    }
                    IdempotencyCache.Claim.New -> {}
                }
            }
            admits[handlerKey(spec)]?.invoke(queries, agent)
            chargeCapture(spec, agent, source)
            val (status, ctype, bytes, headers) = render(runGuarded(r, block), rid)
            http = status
            if (r.bytesOut == 0L) r.bytesOut = bytes.size.toLong()
            idemKey?.let { idempotency.finish(agent.id, it, status, ctype, bytes, headers, spec.media); idemKey = null }
            return Outcome(status, ctype, bytes, headers)
        } catch (e: ApiError) {
            http = e.http; result = e.code
            val bytes = e.toJson(rid).toString().toByteArray()
            idemKey?.let { idempotency.finish(agent.id, it, e.http, "application/json", bytes, emptyMap(), false) }
            return Outcome(e.http, "application/json", bytes, emptyMap(), e)
        } catch (e: Exception) {
            idemKey?.let { idempotency.abandon(agent.id, it) }
            if (e is kotlinx.coroutines.CancellationException) { http = 499; result = "cancelled"; throw e }
            http = 500; result = "internal_error"
            app.log.system("diagnostic", "crash", "Error in ${spec.fullPath} (via MCP): ${e.javaClass.simpleName} at ${e.stackTrace.firstOrNull()}", level = "error")
            val err = ErrorCatalog.error("internal_error", "Bridgit hit a bug (${e.javaClass.simpleName}). It's in the diagnostic log.")
            return Outcome(500, "application/json", err.toJson(rid).toString().toByteArray(), emptyMap(), err)
        } finally {
            app.latency.end()
            r.params.keys().forEach { logParams.put(it, r.params.get(it)) }
            app.usage.count("mcp:${spec.method} ${spec.fullPath}:$result")
            val tookMs = System.currentTimeMillis() - started
            app.latency.record(ApiLatency.Call(ts = started, api = OpenApi.opId(spec), method = spec.method, path = spec.fullPath, via = via,
                requestId = rid, agentId = agent.id, agent = agent.name, durationMs = tookMs, http = http, result = result,
                bytesIn = body.toString().length.toLong(), bytesOut = r.bytesOut, inFlight = inFlight))
            // Relay and card calls share this path; the log says which one it was.
            val viaLabel = if (via == "mcp") "MCP" else via
            val msg = r.message?.let { if ("MCP" in it || viaLabel in it) it else "$it (via $viaLabel)" } ?: (describe(agent.name, spec, http, result) + " (via $viaLabel)")
            app.log.append(LogEntry(
                ts = started, channel = "audit", kind = "request",
                level = when { http >= 500 -> "error"; http >= 400 -> "warn"; else -> "info" },
                agentId = agent.id, agent = agent.name, sourceIp = source, method = spec.method, endpoint = spec.fullPath,
                params = Redactor.params(logParams), http = http, result = result, bytesIn = body.toString().length.toLong(),
                bytesOut = r.bytesOut, durationMs = tookMs, requestId = rid,
                message = Redactor.string(msg, 300),
                data = (r.data ?: JSONObject()).put("via", via).let { Redactor.params(it) }))
        }
    }

    private fun authBlockedError(source: String) = ErrorCatalog.error("rate_limited",
        "Too many failed sign-ins from this address. Wait a minute.", mapOf("retry_after_s" to limiter.retryAfterS("auth:$source")))

    /** A wrong pairing code counts as a failed sign-in (SEC-06). */
    fun recordAuthFailure(source: String) = limiter.record("auth:$source")

    /** Frees expired idempotency results and abandoned uploads; called from the service's periodic check. */
    fun maintenance() {
        idempotency.prune()
        val now = System.currentTimeMillis()
        uploads.listFiles()?.filter { now - it.lastModified() > 30 * 60_000L }?.forEach { it.delete() }
        runCatching { app.speaker.sweep() }
    }

    private data class Rendered(val status: Int, val contentType: String, val bytes: ByteArray, val headers: Map<String, String>)

    private fun render(reply: Reply, rid: String): Rendered = when (reply) {
        // MCP/relay must envelope the result: bound each response, never the retained history.
        is Reply.Stream -> {
            val out = object : java.io.ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (count + len > 8 * 1024 * 1024) throw ErrorCatalog.error("payload_too_large",
                        "MCP/relay export exceeds 8 MiB. Use direct HTTP streaming, or /v1/log pages with next_cursor.")
                    super.write(b, off, len)
                }
            }
            reply.write(out)
            Rendered(200, reply.contentType, out.toByteArray(), emptyMap())
        }
        is Reply.Json -> Rendered(reply.status, "application/json", reply.body.put("request_id", rid).toString().toByteArray(), emptyMap())
        is Reply.Bytes -> Rendered(200, reply.contentType, reply.bytes, BrandHeaders.withLegacy(reply.headers))
        is Reply.Text -> Rendered(reply.status, reply.contentType, reply.text.toByteArray(), emptyMap())
    }

    private fun describe(agent: String?, spec: EndpointSpec, http: Int, result: String): String {
        val who = agent ?: "Unknown caller"
        return if (http < 400) "$who: ${spec.method} ${spec.fullPath}" else "$who: ${spec.method} ${spec.fullPath} failed ($result)"
    }

    /** API-11: 2 MB request bodies outside file uploads. */
    private suspend fun readBody(call: ApplicationCall, max: Int): ByteArray {
        val limit = "Request bodies are limited to ${max / 1024 / 1024} MB."
        val declared = call.request.headers["Content-Length"]?.toLongOrNull()
        if (declared != null && declared > max) throw ErrorCatalog.error("payload_too_large", limit)
        val bytes = call.receiveChannel().readRemaining(max + 1L).readBytes()
        if (bytes.size > max) throw ErrorCatalog.error("payload_too_large", limit)
        return bytes
    }

    /**
     * API-11 for raw uploads: streams the body into a temp file, at most [max] bytes and [MAX_CONCURRENT_UPLOADS]
     * at a time; never holds the whole body in memory. The file is deleted on any failure.
     */
    private suspend fun readBodyToFile(call: ApplicationCall, max: Int): java.io.File {
        val limit = "Request bodies are limited to ${max / 1024 / 1024} MB."
        val declared = call.request.headers["Content-Length"]?.toLongOrNull()
        if (declared != null && declared > max) throw ErrorCatalog.error("payload_too_large", limit)
        kotlinx.coroutines.withTimeoutOrNull(10_000) { uploadSlots.acquire() }
            ?: throw ErrorCatalog.error("rate_limited", "Too many uploads at once. Retry in a few seconds.", mapOf("retry_after_s" to 5))
        val f = java.io.File(uploads, "${UUID.randomUUID()}.part")
        var ok = false
        try {
            val ch = call.receiveChannel()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            f.outputStream().use { out ->
                while (true) {
                    val n = ch.readAvailable(buf, 0, buf.size)
                    if (n < 0) break
                    total += n
                    if (total > max) throw ErrorCatalog.error("payload_too_large", limit)
                    out.write(buf, 0, n)
                }
            }
            ok = true
            return f
        } finally {
            uploadSlots.release()
            if (!ok) f.delete()
        }
    }

    private fun parseBody(bytes: ByteArray): JSONObject {
        val t = String(bytes).trim()
        if (t.isEmpty()) return JSONObject()
        return runCatching { JSONObject(t) }.getOrElse { throw ErrorCatalog.error("bad_request", "The body must be a JSON object.") }
    }

    private fun sourceOf(call: ApplicationCall): String = call.request.local.remoteAddress.removePrefix("/").substringBefore('%')

    private fun newRequestId() = UUID.randomUUID().toString().replace("-", "").take(12)

    companion object {
        const val MAX_BODY = 2 * 1024 * 1024
        const val MAX_PAYLOAD = 12 * 1024 * 1024
        const val MAX_RAW = 40 * 1024 * 1024
        const val MAX_CONCURRENT_UPLOADS = 2
    }
}
