package cc.bridgit.app.outbound

import kotlinx.coroutines.asContextElement
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * The outbound log (owner request 2 Nov 2026): one entry for every time the phone sends data to a server
 * it doesn't control, so paired agents can see and analyse exactly what left the phone.
 *
 * Every outbound path ends in [record]: the OkHttp [OutboundInterceptor] on each OkHttp client, [cc.bridgit.app.core.SafeHttp]'s
 * one exchange function, and explicit calls where neither is used (update check, wake-word model, webhooks,
 * relay frames, Sentry, Firebase, ML Kit). Who caused a call comes from [actor]: the API server sets it for the
 * whole handler (it survives dispatcher switches), background paths set it from the agent that owns the job,
 * and everything else is the phone itself.
 *
 * Bridgit records what was sent rather than blocking it: owner decision, Bridgit isn't held to normal app
 * privacy norms. Secrets are never stored ([Redact]); bodies are kept as a capped, redacted preview plus a
 * SHA-256 digest of the exact bytes. Replies to agents over the direct Tailscale API are not recorded here;
 * they are in /v1/log (bytes_out).
 */
object Outbound {
    /** Who caused a call: an agent (id + name), the owner, or the phone itself. */
    data class Actor(val agentId: String?, val name: String) {
        companion object {
            val PHONE = Actor(null, "phone")
            val OWNER = Actor(null, "owner")
            fun agent(id: String, name: String) = Actor(id, name)
        }
    }

    /** Extra description a caller can give for one call (agent-chosen destinations, explicit hooks). */
    data class Hint(val service: String? = null, val purpose: String? = null, val categories: List<String> = emptyList(),
                    /** Keep no preview of the request body (only its size and digest): e.g. face crops and face embeddings. */
                    val noBodyPreview: Boolean = false,
                    /** The applet action a paid call performs (X-Bridgit-Action; budgets): a watch's action id, else main. */
                    val action: String? = null)

    private val actorLocal = ThreadLocal<Actor?>()
    private val hintLocal = ThreadLocal<Hint?>()

    fun actor(): Actor = actorLocal.get() ?: Actor.PHONE
    fun hint(): Hint? = hintLocal.get()

    /** Coroutine context that makes [a] the actor for everything the coroutine (and its children) sends. */
    fun context(a: Actor): CoroutineContext = actorLocal.asContextElement(a)

    /** Runs blocking [block] with [a] as the actor (and optionally [h] as the hint) on this thread. */
    fun <T> on(a: Actor?, h: Hint? = null, block: () -> T): T {
        val prevA = actorLocal.get(); val prevH = hintLocal.get()
        if (a != null) actorLocal.set(a)
        if (h != null) hintLocal.set(h)
        try { return block() } finally { actorLocal.set(prevA); hintLocal.set(prevH) }
    }

    /** Runs blocking [block] with [h] describing the call(s) it makes. */
    fun <T> hinted(h: Hint, block: () -> T): T = on(null, h, block)

    /** False while the owner or an agent has turned the outbound log off (privacy.outbound_log): nothing new is recorded. */
    @Volatile var recording: () -> Boolean = { true }

    /** True while the owner has paused all external calls (Agents tab): [check] refuses every call before it leaves. */
    @Volatile var paused: () -> Boolean = { false }

    /** Thrown instead of sending while external calls are paused. An IOException, so callers' network-error paths handle it. */
    class Paused : java.io.IOException(MESSAGE) {
        companion object { const val MESSAGE = "External calls are paused on this phone. The owner can turn them back on in Bridgit's Agents tab." }
    }

    /** Refuses [url] while paused, recording the refusal (it never left: status 0). */
    fun check(url: String, method: String, transport: String = "https") {
        if (!runCatching { paused() }.getOrDefault(false)) return
        record(url, method, transport, status = 0, error = "paused: " + Paused.MESSAGE, startedAt = System.currentTimeMillis())
        throw Paused()
    }

    /** Where entries go; set by BridgeApp once the store is open. Entries recorded earlier wait (up to 200). */
    @Volatile private var sink: ((OutboundEntry) -> Unit)? = null
    private val early = ConcurrentLinkedQueue<OutboundEntry>()

    fun attach(s: (OutboundEntry) -> Unit) {
        sink = s
        while (true) { val e = early.poll() ?: break; runCatching { s(e) } }
    }

    /** Tests only. */
    internal fun detach() { sink = null; early.clear() }

    /**
     * Records one outbound call. Never throws: a recording problem must never break the call it describes.
     * [sent] is the request body (null when none or unreadable); [sentBytes] its size when the bytes aren't at hand.
     * [received] is a sample of the reply body for the preview; [receivedBytes] the full reply size (-1 unknown).
     * [status] is the HTTP status, or 0 when no reply came back ([error] says why).
     */
    fun record(
        url: String, method: String, transport: String = "https",
        headers: Map<String, String> = emptyMap(), sent: ByteArray? = null, sentBytes: Long = sent?.size?.toLong() ?: 0L,
        sentType: String? = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value,
        status: Int, receivedBytes: Long = -1, received: ByteArray? = null, receivedType: String? = null,
        error: String? = null, startedAt: Long, durationMs: Long = System.currentTimeMillis() - startedAt,
        actor: Actor? = null, hint: Hint? = null,
    ) {
        runCatching {
            if (!recording()) return
            val e = build(url, method, transport, headers, sent, sentBytes, sentType, status, receivedBytes, received, receivedType,
                error, startedAt, durationMs, actor ?: actor(), hint ?: hint())
            val s = sink
            if (s != null) s(e) else { early.add(e); while (early.size > 200) early.poll() }
        }
    }

    /** What an explicit hook learned about the reply while [call] ran. */
    class Meter {
        var status = 0
        var receivedBytes = -1L
        var received: ByteArray? = null
        var receivedType: String? = null
        var sent: ByteArray? = null
        var sentBytes = 0L
        var sentType: String? = null
        val headers = LinkedHashMap<String, String>()
    }

    /**
     * Wraps one call that doesn't go through OkHttp or SafeHttp: [block] fills the [Meter], and one entry is
     * recorded when it ends, with the exception's name as the error if it threw.
     */
    inline fun <T> call(url: String, method: String = "GET", transport: String = "https", actor: Actor? = null, hint: Hint? = null,
                        block: (Meter) -> T): T {
        check(url, method, transport)
        val m = Meter()
        val started = System.currentTimeMillis()
        var error: String? = null
        try { return block(m) } catch (e: Throwable) { error = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""); throw e }
        finally {
            record(url, method, transport, m.headers, m.sent, if (m.sent != null) m.sent!!.size.toLong() else m.sentBytes, m.sentType,
                status = if (error != null && m.status == 0) 0 else m.status, receivedBytes = m.receivedBytes, received = m.received,
                receivedType = m.receivedType, error = error, startedAt = started, actor = actor, hint = hint)
        }
    }

    internal fun build(
        url: String, method: String, transport: String, headers: Map<String, String>, sent: ByteArray?, sentBytes: Long,
        sentType: String?, status: Int, receivedBytes: Long, received: ByteArray?, receivedType: String?, error: String?,
        startedAt: Long, durationMs: Long, actor: Actor, hint: Hint?,
    ): OutboundEntry {
        val u = runCatching { URI(url) }.getOrNull()
        val host = (u?.host ?: url.substringAfter("://").substringBefore('/').substringBefore('?')).lowercase().removePrefix("[").removeSuffix("]")
        val path = u?.rawPath?.ifEmpty { "/" } ?: "/"
        val query = Redact.query(u?.rawQuery)
        val rule = Classifier.classify(host, path, method)
        val speechPrivate = path.startsWith("/v1/speech/") || path.startsWith("/v1/images/")
        val body = if (speechPrivate || hint?.noBodyPreview == true) null else sent?.let { Redact.body(it, sentType, Redact.MAX_REQUEST_PREVIEW) }
        val cats = LinkedHashSet<String>()
        cats += rule.categories
        hint?.categories?.let { cats += it }
        cats += Detect.fromQuery(query)
        if (sent != null) cats += Detect.fromBody(sent, sentType)
        Detect.fromHeaders(headers).let { cats += it }
        if (cats.contains(Categories.LOCATION_EXACT)) cats.remove(Categories.LOCATION_1KM)
        return OutboundEntry(
            ts = startedAt, host = host, service = hint?.service ?: rule.service, method = method.uppercase(), path = Redact.path(path),
            purpose = hint?.purpose ?: rule.purpose, agentId = actor.agentId, agent = actor.name,
            categories = cats.toList(), bytesSent = if (sent != null) sent.size.toLong() else sentBytes,
            bytesReceived = receivedBytes, status = status, error = error?.let { Redact.text(it, 300) }, durationMs = durationMs.coerceAtLeast(0),
            transport = transport, query = query, headers = Redact.headers(headers),
            requestDigest = sent?.let { sha256(it) }, requestType = sentType?.substringBefore(';')?.trim(),
            requestPreview = body, responseType = receivedType?.substringBefore(';')?.trim(),
            responsePreview = if (speechPrivate) null else received?.let { Redact.body(it, receivedType, Redact.MAX_RESPONSE_PREVIEW) },
        )
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

/** One recorded outbound call. [status] 0 = no reply (see [error]); [bytesReceived] -1 = unknown. */
data class OutboundEntry(
    val id: Long = 0,
    val ts: Long,
    val host: String,
    val service: String,
    val method: String,
    val path: String,
    val purpose: String,
    val agentId: String?,
    val agent: String,
    val categories: List<String>,
    val bytesSent: Long,
    val bytesReceived: Long,
    val status: Int,
    val error: String?,
    val durationMs: Long,
    val transport: String,
    val query: JSONObject? = null,
    val headers: JSONObject? = null,
    val requestDigest: String? = null,
    val requestType: String? = null,
    val requestPreview: String? = null,
    val responseType: String? = null,
    val responsePreview: String? = null,
) {
    /** [HANDED_OFF]: given to a third-party SDK (Sentry, Firebase) that sends it itself; the reply isn't visible. */
    val ok: Boolean get() = (status in 100..399 || status == HANDED_OFF) && error == null

    companion object { const val HANDED_OFF = -1 }

    /** The API shape. [bodies] false leaves the previews out (lists stay small). */
    fun toJson(iso: (Long) -> String, bodies: Boolean = true): JSONObject = JSONObject()
        .put("id", id).put("at", iso(ts)).put("ts_ms", ts)
        .put("destination", JSONObject().put("host", host).put("service", service))
        .put("method", method).put("path", path).put("transport", transport).put("purpose", purpose)
        .put("agent", agent).put("agent_id", agentId ?: JSONObject.NULL)
        .put("categories", JSONArray(categories))
        .put("bytes_sent", bytesSent).put("bytes_received", if (bytesReceived < 0) JSONObject.NULL else bytesReceived)
        .put("status", if (status == HANDED_OFF) JSONObject.NULL else status).put("ok", ok)
        .apply { if (status == HANDED_OFF) put("delivery", "handed_to_sdk") }.put("error", error ?: JSONObject.NULL).put("duration_ms", durationMs)
        .apply {
            query?.takeIf { it.length() > 0 }?.let { put("query", it) }
            headers?.takeIf { it.length() > 0 }?.let { put("headers", it) }
            requestDigest?.let { put("sent_sha256", it) }
            if (bodies) {
                put("sent", JSONObject().put("type", requestType ?: JSONObject.NULL).put("preview", requestPreview ?: JSONObject.NULL))
                put("received", JSONObject().put("type", responseType ?: JSONObject.NULL).put("preview", responsePreview ?: JSONObject.NULL))
            }
        }
}

/** The data categories an entry can carry. Detected from the destination, the body's field names and its type. */
object Categories {
    const val INSTALL_ID = "install_id"
    const val DEVICE_INFO = "device_info"
    const val APP_VERSION = "app_version"
    const val AGENT_INFO = "agent_info"
    const val REPORT_TEXT = "report_text"
    const val TEXT = "text"
    const val IMAGE = "image"
    const val AUDIO = "audio"
    const val VIDEO = "video"
    const val LOG_ENTRIES = "log_entries"
    const val LOCATION_1KM = "location_1km"
    const val LOCATION_EXACT = "location_exact"
    const val BATTERY = "battery_state"
    const val AGENT_STATE = "agent_state"
    const val QUESTIONS = "questions"
    const val USAGE_COUNTS = "usage_counts"
    const val ALERT_TEXT = "alert_text"
    const val EMAIL = "email_address"
    const val PUSH_TOKEN = "push_token"
    const val CRASH_REPORT = "crash_report"
    const val AUTH_PROOF = "auth_proof"
    const val PUBLIC_KEY = "public_key"
    const val OWNER_ANSWER = "owner_answer"
    const val API_RESULT = "api_result"
    const val ENCRYPTED = "encrypted"
    const val REPORT_STATUS = "report_status"
    const val NONE = "no_payload"
}

/** Known destinations: service name, purpose and the categories every call there carries. */
object Classifier {
    data class Rule(val service: String, val purpose: String, val categories: List<String>)

    /** Hosts filled in at startup from the build (team backend, relay, update host, Sentry). */
    @Volatile var teamHosts: Set<String> = setOf("bridgit-feedback.bridgit-app.workers.dev")
    /** The relay's host can change at runtime (owner sets it up), so it is looked up per call. */
    @Volatile var relayHosts: () -> Set<String> = { emptySet() }
    @Volatile var updateHosts: Set<String> = emptySet()

    fun classify(host: String, path: String, method: String): Rule {
        val m = method.uppercase()
        with(Categories) {
            if (host in teamHosts || host.startsWith("bridgit-feedback.")) return when {
                path.startsWith("/v1/images/") -> Rule("Azure Sunburst (via Bridgit)", "Image generation and account allowance; content previews disabled", listOf(TEXT, IMAGE, INSTALL_ID))
                path.startsWith("/v1/speech/") -> Rule("Bridgit speech", "Registry-selected speech turn; content previews disabled", listOf(AUDIO, TEXT, INSTALL_ID))
                path == "/v1/clef" -> Rule("Cloudflare Clef (via Bridgit team backend)", "Clef decision: the state and questions, sent to Clef-omni on Workers AI", listOf(AGENT_STATE, QUESTIONS, INSTALL_ID, APP_VERSION))
                path == "/v1/locate" -> Rule("Locate (OpenRouter via Bridgit)", "Find in photo", listOf(IMAGE, AGENT_STATE, INSTALL_ID))
                path == "/v1/appraise" -> Rule("Appraise (OpenRouter via Bridgit)", "Inventory", listOf(IMAGE, AGENT_STATE, INSTALL_ID))
                path == "/v1/chef" -> Rule("Chef (OpenRouter via Bridgit)", "Cooking suggestion", listOf(IMAGE, AGENT_STATE, INSTALL_ID))
                path == "/v1/glm" -> Rule("GLM 5.3 Flash (OpenRouter via Bridgit)", "GLM decision", listOf(AGENT_STATE, QUESTIONS, INSTALL_ID, APP_VERSION))
                path == "/v1/jev" -> Rule("TypeSafe Jev (via Bridgit team backend)", "Jev decision: the agent's state and questions, forwarded to TypeSafe", listOf(AGENT_STATE, QUESTIONS, INSTALL_ID, APP_VERSION))
                path == "/v1/watch/events" -> Rule("Bridgit remote view", "Remote view (bridgit.cc/watch): one short line per agent action (time, agent, part of the phone, status) and which parts are on; never content", listOf(LOG_ENTRIES, AGENT_INFO, INSTALL_ID, APP_VERSION))
                path == "/v1/watch/link-code" -> Rule("Bridgit remote view", "Get a one-time code to link this phone to the owner's Google account", listOf(INSTALL_ID, DEVICE_INFO))
                path == "/v1/watch/unlink" -> Rule("Bridgit remote view", "Turn remote view off: delete the link and everything stored for this phone", listOf(INSTALL_ID))
                path == "/v1/reports" && m == "POST" -> Rule("Bridgit team backend", "Upload a bug report or feature request", listOf(REPORT_TEXT, INSTALL_ID, APP_VERSION, DEVICE_INFO))
                path.startsWith("/v1/reports/updates") -> Rule("Bridgit team backend", "Check for status changes on filed reports", listOf(INSTALL_ID))
                path.endsWith("/history") -> Rule("Bridgit team backend", "Send a report's status history (verify loop)", listOf(REPORT_STATUS, INSTALL_ID))
                path.startsWith("/v1/agent-keys/revoke") -> Rule("Bridgit team backend", "Revoke an agent's team key", listOf(AGENT_INFO, INSTALL_ID))
                path.startsWith("/v1/agent-keys") -> Rule("Bridgit team backend", "Mint an agent's team key", listOf(AGENT_INFO, INSTALL_ID))
                path.startsWith("/v1/install/challenge") -> Rule("Bridgit team backend", "Ask for a one-time challenge before registering this install", listOf(INSTALL_ID))
                path.startsWith("/v1/install") -> Rule("Bridgit team backend", "Register this install's signing key (with its attestation)", listOf(INSTALL_ID, PUBLIC_KEY, APP_VERSION))
                else -> Rule("Bridgit team backend", "Team backend call", listOf(INSTALL_ID))
            }
            if (host == "api.met.no") return Rule("MET Norway weather", "Weather forecast for a location", emptyList())
            if (host == "bridgit.cc" || host == "www.bridgit.cc") return when {
                path.startsWith("/api/usage") -> Rule("Bridgit website", "Daily anonymous feature-use counts", listOf(USAGE_COUNTS, APP_VERSION))
                path.startsWith("/api/alerts/send") -> Rule("Bridgit email alerts", "Send an email alert to the owner", listOf(ALERT_TEXT))
                path.startsWith("/api/alerts") -> Rule("Bridgit email alerts", "Email alert subscription", listOf(INSTALL_ID))
                else -> Rule("Bridgit website", "Website request", emptyList())
            }
            if (host in runCatching { relayHosts() }.getOrDefault(emptySet())) return when {
                path.endsWith("/enroll") -> Rule("Bridgit relay", "Enroll this phone with the relay", listOf(PUBLIC_KEY, AUTH_PROOF))
                path.endsWith("/challenge") -> Rule("Bridgit relay", "Prove this phone's identity to the relay", listOf(AUTH_PROOF))
                path.endsWith("/socket") -> Rule("Bridgit relay", "Open the relay socket", listOf(AUTH_PROOF))
                else -> Rule("Bridgit relay", "Relay call", emptyList())
            }
            if (host in updateHosts) return Rule("Bridgit updates", if (path.endsWith(".json")) "Check for an app update" else "Download an app update", listOf(APP_VERSION, DEVICE_INFO))
            if (host == "alphacephei.com") return Rule("Vosk model host", "Download the wake-word speech model", listOf(NONE))
            if (host.endsWith(".sentry.io") || host.endsWith(".ingest.sentry.io") || host.contains("sentry")) return Rule("Sentry", "Crash report", listOf(CRASH_REPORT, APP_VERSION, DEVICE_INFO))
            if (host.endsWith(".googleapis.com") || host.endsWith(".google.com")) return Rule("Google", "Google service", emptyList())
            return Rule(host, "Outbound call", emptyList())
        }
    }
}

/** Categories found in the data itself, so a new path or field is labelled even without a rule. */
object Detect {
    private val byKey: List<Pair<Regex, String>> = with(Categories) { listOf(
        Regex("(?i)^(install_?id|install)$") to INSTALL_ID,
        Regex("(?i)^(device|device_?model|model|manufacturer|android|android_?version|sdk|os|os_?version|brand)$") to DEVICE_INFO,
        Regex("(?i)^(app_?version|version_?code|version_?name|app)$") to APP_VERSION,
        Regex("(?i)^(agent_?info|agent|agent_?name|harness|harness_?version)$") to AGENT_INFO,
        Regex("(?i)^(images?|screenshots?|photos?)$") to IMAGE,
        Regex("(?i)^(audio|clip|recording)$") to AUDIO,
        Regex("(?i)^(log|logs|log_?entries)$") to LOG_ENTRIES,
        Regex("(?i)^(battery.*|charging|plugged)$") to BATTERY,
        Regex("(?i)^(state)$") to AGENT_STATE,
        Regex("(?i)^(questions)$") to QUESTIONS,
        Regex("(?i)^(description|title|report|kind)$") to REPORT_TEXT,
        Regex("(?i)^(text|message|note|caption|body)$") to TEXT,
        Regex("(?i)^(e-?mail|email_?address|to)$") to EMAIL,
        Regex("(?i)^(push_?token|fcm_?token)$") to PUSH_TOKEN,
        Regex("(?i)^(answer|answers|choice|response|reply|value)$") to OWNER_ANSWER,
        Regex("(?i)^(public_?key|pubkey|attestation|certificates?|chain)$") to PUBLIC_KEY,
        Regex("(?i)^(proof|signature|nonce|challenge)$") to AUTH_PROOF,
        Regex("(?i)^(sealed|ciphertext|enc|encapsulated)$") to ENCRYPTED,
    ) }

    fun fromQuery(q: JSONObject?): Set<String> {
        if (q == null) return emptySet()
        val out = HashSet<String>()
        for (k in q.keys()) {
            if (k.equals("lat", true) || k.equals("lon", true) || k.equals("latitude", true) || k.equals("longitude", true)) {
                out += if (decimals(q.optString(k)) <= 2) Categories.LOCATION_1KM else Categories.LOCATION_EXACT
            } else byKey.firstOrNull { it.first.matches(k) }?.let { out += it.second }
        }
        return out
    }

    fun fromHeaders(h: Map<String, String>): Set<String> {
        val out = HashSet<String>()
        if (h.keys.any { it.equals("X-Bridgit-Install", true) }) out += Categories.INSTALL_ID
        h.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value?.let { ua ->
            if (ua.contains("Android")) out += Categories.DEVICE_INFO
            if (Regex("Bridgit/\\d").containsMatchIn(ua)) out += Categories.APP_VERSION
        }
        return out
    }

    fun fromBody(b: ByteArray, type: String?): Set<String> {
        val t = type?.lowercase().orEmpty()
        when {
            t.startsWith("image/") -> return setOf(Categories.IMAGE)
            t.startsWith("audio/") -> return setOf(Categories.AUDIO)
            t.startsWith("video/") -> return setOf(Categories.VIDEO)
        }
        if (b.isEmpty()) return emptySet()
        val json = Redact.parseJson(b) ?: return if (Redact.isText(b, type)) setOf(Categories.TEXT) else emptySet()
        val out = HashSet<String>()
        walk(json, 0, out)
        return out
    }

    private fun walk(v: Any?, depth: Int, out: MutableSet<String>) {
        if (depth > 5) return
        when (v) {
            is JSONObject -> {
                val keys = v.keys().asSequence().toList()
                val lat = keys.firstOrNull { it.equals("lat", true) || it.equals("latitude", true) }
                if (lat != null && keys.any { it.equals("lon", true) || it.equals("lng", true) || it.equals("longitude", true) })
                    out += if (decimals(v.opt(lat).toString()) <= 2) Categories.LOCATION_1KM else Categories.LOCATION_EXACT
                for (k in keys) {
                    val child = v.opt(k)
                    byKey.firstOrNull { it.first.matches(k) }?.let { out += it.second }
                    if (k.equals("data_base64", true) || k.equals("base64", true)) out += mimeCategory(v.optString("mime")) ?: Categories.IMAGE
                    if (k.equals("location", true)) out += Categories.LOCATION_EXACT
                    walk(child, depth + 1, out)
                }
            }
            is JSONArray -> for (i in 0 until minOf(v.length(), 20)) walk(v.opt(i), depth + 1, out)
        }
    }

    private fun mimeCategory(m: String): String? = when {
        m.startsWith("image/") -> Categories.IMAGE
        m.startsWith("audio/") -> Categories.AUDIO
        m.startsWith("video/") -> Categories.VIDEO
        else -> null
    }

    fun decimals(s: String): Int = s.substringAfter('.', "").takeWhile { it.isDigit() }.length
}

/** Makes previews safe to store: no keys, tokens, signatures or Authorization values, and capped sizes. */
object Redact {
    const val MAX_REQUEST_PREVIEW = 8 * 1024
    const val MAX_RESPONSE_PREVIEW = 2 * 1024
    const val MAX_STRING = 1000
    const val REDACTED = "[redacted]"

    /** Field and header names whose values are never stored. */
    val SECRET_NAME = Regex("(?i)(key|token|secret|password|passwd|pwd|authorization|auth|bearer|cookie|signature|hmac|proof|credential|nonce|otp|session|private|dsn|^sig$|^s$|_sig$|^code$)")
    /** Field names that are identifiers or labels, not secrets, despite matching [SECRET_NAME]. */
    private val NOT_SECRET = Regex("(?i)^(idempotency_?key|key_?prefix|public_?key|push_?token_?present|has_?key|keys)$")
    /** Header values kept as they are; every other header keeps only its name. */
    private val SAFE_HEADERS = setOf("content-type", "accept", "user-agent", "if-none-match", "if-modified-since", "x-bridgit-install",
        "x-bridgit-action", "x-bridgit-slot", "x-bridgit-event", "x-bridgit-timestamp", "idempotency-key", "content-length",
        "x-bridgit-capture-id", "x-bridgit-kind", "x-bridgit-agent", "x-bridgit-captured-at")

    private val TOKEN_PATTERNS = listOf(
        Regex("brg_[A-Za-z0-9_-]{8,}") to "brg_[redacted]",
        Regex("bgk_[A-Za-z0-9_-]{8,}") to "bgk_[redacted]",
        Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}") to "Bearer [redacted]",
        Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}") to "[jwt redacted]",
        Regex("https://[0-9a-f]{16,}@[^\\s\"]+") to "[dsn redacted]",
    )
    private val BASE64ISH = Regex("^[A-Za-z0-9+/=_-]+$")

    fun isSecretName(name: String): Boolean = SECRET_NAME.containsMatchIn(name) && !NOT_SECRET.matches(name)

    fun text(s: String, max: Int = MAX_STRING): String {
        var t = s
        for ((re, rep) in TOKEN_PATTERNS) t = re.replace(t, rep)
        return if (t.length <= max) t else t.take(max - 1) + "…"
    }

    fun headers(h: Map<String, String>): JSONObject {
        val o = JSONObject()
        for ((k, v) in h) o.put(k, if (k.lowercase() in SAFE_HEADERS && !isSecretName(k)) text(v, 300) else REDACTED)
        return o
    }

    /** Long random-looking path segments become [token]: webhook services (Slack, Discord…) put their secret in the path. */
    private val TOKEN_SEGMENT = Regex("^[A-Za-z0-9_-]{20,}$")
    fun path(p: String): String = text(p.split('/').joinToString("/") { seg ->
        if (TOKEN_SEGMENT.matches(seg) && seg.any { it.isDigit() } && seg.any { it.isLetter() }) "[token]" else seg }, 500)

    fun query(raw: String?): JSONObject? {
        if (raw.isNullOrEmpty()) return null
        val o = JSONObject()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val k = decode(part.substringBefore('='))
            val v = decode(part.substringAfter('=', ""))
            o.put(text(k, 60), if (isSecretName(k)) REDACTED else text(v, 300))
        }
        return o
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    fun parseJson(b: ByteArray): Any? {
        val first = b.firstOrNull { it.toInt().toChar().isWhitespace().not() }?.toInt()?.toChar() ?: return null
        if (first != '{' && first != '[') return null
        return runCatching { JSONTokener(String(b, Charsets.UTF_8)).nextValue().takeIf { it is JSONObject || it is JSONArray } }.getOrNull()
    }

    fun isText(b: ByteArray, type: String?): Boolean {
        val t = type?.lowercase().orEmpty()
        if (t.startsWith("text/") || t.contains("json") || t.contains("xml") || t.contains("x-www-form-urlencoded")) return true
        if (t.isNotEmpty()) return false
        val sample = b.take(512)
        return sample.none { val c = it.toInt() and 0xFF; c < 0x09 || (c in 0x0E..0x1F) }
    }

    /** A redacted, capped preview of a body: JSON with secrets replaced and media summarised, text scrubbed, binary described. */
    fun body(b: ByteArray, type: String?, max: Int): String {
        if (b.isEmpty()) return ""
        val t = type?.substringBefore(';')?.trim()?.lowercase()
        parseJson(b)?.let { return cap(clean(it, 0).toString(), max) }
        if (t != null && t.contains("x-www-form-urlencoded")) return cap(query(String(b, Charsets.UTF_8))?.toString() ?: "", max)
        if (isText(b, type)) return cap(text(String(b, Charsets.UTF_8), max), max)
        return "[${b.size} bytes${t?.let { " $it" } ?: " binary"}]"
    }

    private fun cap(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"

    internal fun clean(v: Any?, depth: Int, name: String? = null): Any? = when {
        name != null && isSecretName(name) -> REDACTED
        v == null || v == JSONObject.NULL -> JSONObject.NULL
        v is String -> when {
            v.length > 200 && BASE64ISH.matches(v) -> "[${v.length} chars base64]"
            else -> text(v)
        }
        v is Number || v is Boolean -> v
        depth >= 6 -> "[nested]"
        v is JSONObject -> JSONObject().also { o ->
            var n = 0
            for (k in v.keys()) {
                if (++n > 60) { o.put("…", "${v.length() - 60} more fields"); break }
                o.put(text(k, 60), clean(v.opt(k), depth + 1, k))
            }
        }
        // Long lists of numbers (vectors, embeddings) say only how long they are.
        v is JSONArray && v.length() > 16 && (0 until v.length()).all { v.opt(it) is Number } -> "[${v.length()} numbers]"
        v is JSONArray -> JSONArray().also { a ->
            for (i in 0 until minOf(v.length(), 50)) a.put(clean(v.opt(i), depth + 1))
            if (v.length() > 50) a.put("[${v.length() - 50} more items]")
        }
        else -> text(v.toString())
    }
}
