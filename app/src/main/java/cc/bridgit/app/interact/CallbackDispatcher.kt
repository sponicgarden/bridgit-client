package cc.bridgit.app.interact

import cc.bridgit.app.outbound.Categories
import cc.bridgit.app.outbound.Outbound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Reply
import cc.bridgit.app.api.Req
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.core.CallbackQueue
import cc.bridgit.app.core.CallbackRules
import cc.bridgit.app.core.CallbackStore
import cc.bridgit.app.core.SafeHttp
import cc.bridgit.app.log.LogEntry
import cc.bridgit.app.log.Redactor
import org.json.JSONObject

/**
 * Delivers events to agents' callback URLs (EVT-06). One coroutine worker, an in-memory queue capped per
 * agent (oldest dropped), retries at 10 s, 60 s and 5 min, then gives up (the event stays in /v1/events).
 * Every attempt goes through [SafeHttp] (EVT-05a) and is logged with the host only, never the path or query.
 */
class CallbackDispatcher(private val app: BridgeApp, private val store: CallbackStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = CallbackQueue<Event>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var knownAgents: Set<String> = app.agents.all().map { it.id }.toSet()

    private var knownGrants = app.agents.all().associate { it.id to it.caps }

    /** The outcome of one attempt: [status] when the receiver answered, else [reason]. */
    class Outcome(val host: String, val status: Int?, val reason: String?) {
        val delivered get() = status != null && CallbackRules.success(status)
        fun words(): String = status?.let { "HTTP $it" } ?: (reason ?: "failed")
    }

    init {
        // Agent removal deletes its callback config and pending deliveries; rotation keeps both (same id).
        app.agents.addListener {
            val grants = app.agents.all().associate { it.id to it.caps }
            synchronized(queue) {
                (knownGrants.keys + grants.keys).filter { knownGrants[it] != grants[it] || it !in grants }.forEach { queue.removeAgent(it) }
                knownGrants = grants
            }
            val ids = grants.keys
            if (ids != knownAgents) {
                knownAgents = ids
                val gone = store.prune(ids)
                if (gone.isNotEmpty()) synchronized(queue) { gone.forEach { queue.removeAgent(it) } }
            }
        }
        scope.launch { worker() }
    }

    /** Called for every stored event (InteractionStore). Only enqueues: it runs under the store's lock. */
    fun onEvent(e: Event) {
        if (e.type == TEST_TYPE) return // delivered synchronously by POST /v1/callback/test
        if (store.get(e.agentId) == null || !EventAccess.allowed(app.agents.get(e.agentId), e.type)) return
        val dropped = synchronized(queue) { queue.add(CallbackQueue.Item(e.agentId, e, 1, System.currentTimeMillis())) }
        if (dropped > 0) log(e.agentId, "warn", null, null,
            "Callback queue full: dropped $dropped oldest event${if (dropped == 1) "" else "s"} (they stay in /v1/events)")
        wake.trySend(Unit)
    }

    /** Forgets pending deliveries for [agentId] (its URL was removed). */
    fun cancel(agentId: String) = synchronized(queue) { queue.removeAgent(agentId) }

    private suspend fun worker() {
        while (true) {
            val now = System.currentTimeMillis()
            val item = synchronized(queue) { queue.takeDue(now) }
            if (item == null) {
                val next = synchronized(queue) { queue.nextDueAt() }
                withTimeoutOrNull(next?.let { (it - now).coerceAtLeast(1) } ?: Long.MAX_VALUE) { wake.receive() }
                continue
            }
            runCatching { attempt(item) }.onFailure {
                log(item.agentId, "warn", null, null, "Callback delivery crashed: ${it.javaClass.simpleName}")
            }
        }
    }

    private fun attempt(item: CallbackQueue.Item<Event>) {
        if (!EventAccess.allowed(app.agents.get(item.agentId), item.value.type)) return
        val cfg = store.get(item.agentId) ?: return // removed meanwhile
        val name = app.agents.get(item.agentId)?.name ?: return
        val e = app.interactions.eventById(item.value.id, item.agentId) ?: return
        val out = deliver(cfg, name, e)
        if (out.delivered) {
            log(item.agentId, "info", out, e, "Callback to ${out.host}: ${out.words()} (${e.type})")
            return
        }
        val delay = CallbackRules.nextDelayMs(item.attempt)
        if (delay != null) {
            synchronized(queue) { queue.add(CallbackQueue.Item(item.agentId, e, item.attempt + 1, System.currentTimeMillis() + delay)) }
            log(item.agentId, "warn", out, e, "Callback to ${out.host} failed (${out.words()}), retrying in ${CallbackRules.delayWords(delay)}")
        } else {
            log(item.agentId, "warn", out, e, "Callback to ${out.host} failed (${out.words()}), giving up; the event is still in /v1/events")
        }
    }

    /** One signed POST (blocking, at most 10 s). */
    private fun deliver(cfg: CallbackStore.Config, agentName: String, e: Event): Outcome {
        val host = cfg.host
        if (!EventAccess.allowed(app.agents.get(cfg.agentId), e.type)) return Outcome(host, null, "access_revoked")
        val check = CallbackRules.check(cfg.url, CallbackRules.tailnetDomain(app.listener.value.magicDns), app.settings.allowInternetCallbacks)
        if (check !is CallbackRules.Check.Ok) return Outcome(host, null, "not_allowed")
        val secret = store.secret(cfg.agentId) ?: return Outcome(host, null, "no_secret")
        val body = CallbackRules.body(e.toJson(::isoAt), agentName)
        val headers = CallbackRules.headers(secret, System.currentTimeMillis() / 1000, e.id.toString(), body, app.appVersion())
        return try {
            val status = Outbound.on(Outbound.Actor.agent(cfg.agentId, agentName), Outbound.Hint("Agent's callback URL", "Interaction callback: ${e.type} event to the agent", listOf(Categories.OWNER_ANSWER))) {
                SafeHttp.post(cfg.url, check.policy, headers, body, CallbackRules.TIMEOUT_MS, check.extraPort) }
            Outcome(host, status, null)
        } catch (f: SafeHttp.Failure) {
            Outcome(host, null, f.reason.name.lowercase())
        } catch (t: Exception) {
            Outcome(host, null, "error")
        }
    }

    /** POST /v1/callback/test: stores a `callback.test` event and sends it now (EVT-06). */
    suspend fun test(r: Req): Reply {
        val a = r.agent!!
        val cfg = store.get(a.id) ?: throw ErrorCatalog.error("not_found", "The owner hasn't set a callback URL for you. Use GET /v1/events instead.")
        val e = app.interactions.event(a.id, TEST_TYPE, JSONObject().put("note", "Test from Bridgit. If you can read this, your callback works."))
        val out = withContext(Dispatchers.IO) { deliver(cfg, a.name, e) }
        r.params.put("host", out.host)
        r.message = "Callback test to ${out.host}: ${out.words()}"
        r.data = JSONObject().put("host", out.host).put("http_status", out.status ?: JSONObject.NULL).put("delivered", out.delivered)
        log(a.id, if (out.delivered) "info" else "warn", out, e, "Callback test to ${out.host}: ${out.words()}")
        if (!out.delivered) {
            val extra = mutableMapOf<String, Any?>("host" to out.host, "delivered" to false)
            if (out.status != null) extra["http_status"] = out.status else extra["reason"] = out.reason
            throw ErrorCatalog.error("callback_failed", "Your callback URL didn't accept the test event (${out.words()}).", extra)
        }
        return Reply.Json(JSONObject().put("delivered", true).put("http_status", out.status).put("host", out.host))
    }

    private fun log(agentId: String, level: String, out: Outcome?, e: Event?, message: String) {
        val data = JSONObject()
        out?.let { data.put("host", it.host); it.reason?.let { r -> data.put("reason", r) } }
        e?.let { data.put("event_id", it.id).put("event_type", it.type) }
        app.log.append(LogEntry(ts = System.currentTimeMillis(), channel = "diagnostic", kind = "agent", level = level,
            agentId = agentId, agent = app.agents.get(agentId)?.name, http = out?.status,
            result = if (out == null) null else if (out.delivered) "delivered" else "failed",
            message = Redactor.string(message, 300), data = data))
    }

    companion object {
        const val TEST_TYPE = "callback.test"
    }
}
