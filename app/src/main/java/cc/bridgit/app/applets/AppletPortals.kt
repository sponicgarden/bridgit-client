package cc.bridgit.app.applets

import android.content.Intent
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.Agent
import cc.bridgit.app.ui.interactions.CardWebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Applet portals (docs/spec-portals-and-cards.md): an applet's page shown full screen, which the person at the
 * device drives by touch and a controlling agent drives through the portal API. This holds one live session per
 * applet: the screen showing it ([Host]), the functions its page exposed, the state it published, and the agent
 * calls waiting for the page's answer. Nothing here is stored; a portal starts fresh each time it opens.
 */
class AppletPortals(private val app: BridgeApp) {
    /** The portal screen, as this registry sees it. Every method is called on the main thread. */
    interface Host {
        fun call(id: String, name: String, argsJson: String)
        fun text(done: (String) -> Unit)
        fun close()
        /** An agent's calls are waiting on the page ([busy]), or the last one just ended. */
        fun agentBusy(busy: Boolean) {}
    }

    private class Session(val instanceId: String) {
        @Volatile var host: Host? = null
        @Volatile var onScreen = false
        @Volatile var functions: List<PortalRules.Function> = emptyList()
        @Volatile var state: Any? = null
        @Volatile var stateAt = 0L
        @Volatile var lastInput: JSONObject? = null
        @Volatile var lastStateEvent = 0L
        @Volatile var stateEventQueued = false
        val ready = MutableStateFlow(false)
        val watchers: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val random = SecureRandom()

    private fun session(id: String) = sessions.getOrPut(id) { Session(id) }

    // ---- what has a portal --------------------------------------------------------------------------------------

    /** "html", "pieces", or null when [r] has no portal (the native Pet monitor, or a package without a page). */
    fun kind(r: JSONObject): String? {
        if (r.optString("native") == "dog") return null
        val c = r.optJSONObject("definition")?.optJSONObject("ui")?.optJSONObject("card") ?: return null
        return if (cc.bridgit.app.interact.CardPieces.isPieces(c)) "pieces" else "html"
    }

    fun list(): JSONArray = JSONArray(app.applets.all().mapNotNull { r ->
        val k = kind(r) ?: return@mapNotNull null
        val s = sessions[r.getString("id")]
        JSONObject().put("id", r.getString("id")).put("name", r.optString("name")).put("package_id", r.optString("package_id"))
            .put("status", r.optString("status")).put("kind", k).put("open", s?.host != null).put("on_screen", s?.onScreen == true)
    })

    private fun instance(id: String): JSONObject {
        val r = app.applets.get(id) ?: throw ErrorCatalog.error("not_found", "No installed applet $id.")
        if (kind(r) == null) throw ErrorCatalog.error("not_supported", "${r.optString("name")} has no portal (its package has no page).")
        return r
    }

    // ---- the agent side -----------------------------------------------------------------------------------------

    /** Brings the portal to the front and waits up to [waitMs] for its page to load. */
    suspend fun open(id: String, by: Agent?, waitMs: Long): JSONObject {
        val r = instance(id)
        app.applets.openCard(id) ?: throw ErrorCatalog.error("not_supported", "${r.optString("name")} has no page to show.")
        val s = session(id)
        by?.let { s.watchers += it.id }
        val launched = runCatching {
            withContext(Dispatchers.Main) { app.startActivity(cc.bridgit.app.ui.applets.AppletPortalActivity.intent(app, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }.isSuccess
        if (!launched) throw ErrorCatalog.error("service_start_blocked", "Android didn't let Bridgit open the portal.")
        if (waitMs > 0) withTimeoutOrNull(waitMs) { s.ready.first { it } }
        val out = snapshot(id)
        if (s.host == null) out.put("note", "The portal didn't come to the front yet. If Bridgit isn't on screen, Android may need the owner to let Bridgit display over other apps.")
        return out
    }

    /** What the portal shows now. */
    suspend fun snapshot(id: String): JSONObject {
        val r = instance(id)
        val s = session(id)
        val host = s.host
        val text = host?.let { h ->
            withTimeoutOrNull(2000) { withContext(Dispatchers.Main) { suspendCancellableCoroutine<String> { c -> h.text { t -> if (c.isActive) c.resumeWith(Result.success(t)) } } } }
        }
        return JSONObject().put("id", id).put("name", r.optString("name")).put("kind", kind(r))
            .put("open", host != null).put("on_screen", host != null && s.onScreen).put("loaded", s.ready.value)
            .put("functions", PortalRules.functionsJson(s.functions))
            .put("state", s.state ?: JSONObject.NULL).put("state_at", if (s.stateAt > 0) s.stateAt else JSONObject.NULL)
            .put("text", text?.take(PortalRules.MAX_TEXT_CHARS) ?: JSONObject.NULL)
            .put("last_input", s.lastInput ?: JSONObject.NULL)
    }

    /** Runs one exposed function on the page and waits for its answer. */
    suspend fun call(id: String, by: Agent, body: JSONObject): JSONObject {
        instance(id)
        val s = session(id)
        val host = s.host ?: throw ErrorCatalog.error("conflict", "The portal isn't open. POST /v1/applets/$id/portal/open first.")
        val (fn, args, timeout) = try { PortalRules.call(body, s.functions) }
            catch (e: PortalRules.NotExposed) { throw ErrorCatalog.error("not_found", e.message ?: "No such function.", mapOf("functions" to JSONArray(e.known))) }
            catch (e: PortalRules.Bad) { throw ErrorCatalog.error("invalid_parameter", e.message ?: "Bad call.") }
        if (s.pending.size >= PortalRules.MAX_IN_FLIGHT) throw ErrorCatalog.error("rate_limited", "At most ${PortalRules.MAX_IN_FLIGHT} calls can wait on a portal at once.")
        s.watchers += by.id
        val callId = "pc" + java.lang.Long.toHexString(random.nextLong() and Long.MAX_VALUE)
        val d = CompletableDeferred<JSONObject>()
        s.pending[callId] = d
        s.lastInput = JSONObject().put("by", "agent").put("agent", by.name).put("function", fn).put("at", System.currentTimeMillis())
        try {
            withContext(Dispatchers.Main) { host.agentBusy(true); host.call(callId, fn, args.toString()) }
            val r = withTimeoutOrNull(timeout) { d.await() }
                ?: return JSONObject().put("ok", false).put("error", "The portal didn't answer within ${timeout / 1000} s.").put("timed_out", true)
            return r
        } finally {
            s.pending.remove(callId)
            if (s.pending.isEmpty()) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { s.host?.agentBusy(false) }
        }
    }

    suspend fun close(id: String): JSONObject {
        instance(id)
        val host = sessions[id]?.host
        if (host != null) withContext(Dispatchers.Main) { host.close() }
        return JSONObject().put("id", id).put("closed", host != null)
    }

    // ---- the screen side ----------------------------------------------------------------------------------------

    /** The portal screen for [id] is up (a new page: its functions and state start empty). */
    fun attach(id: String, host: Host, kind: String) {
        val s = session(id)
        s.pending.values.forEach { it.complete(JSONObject().put("ok", false).put("error", "The portal page was reloaded.")) }
        s.host = host; s.functions = emptyList(); s.state = null; s.stateAt = 0
        s.ready.value = kind == "pieces"
        emit(s, "portal.opened", JSONObject())
    }

    fun detach(id: String, host: Host) {
        val s = sessions[id] ?: return
        if (s.host !== host) return
        s.host = null; s.onScreen = false; s.ready.value = false; s.functions = emptyList()
        s.pending.values.forEach { it.complete(JSONObject().put("ok", false).put("error", "The portal was closed.")) }
        emit(s, "portal.closed", JSONObject())
        s.watchers.clear()
    }

    fun onScreen(id: String, host: Host, on: Boolean) { sessions[id]?.takeIf { it.host === host }?.onScreen = on }

    /** Bridgit.portal for the page of [id] shown by [host]. */
    fun bridge(id: String, host: () -> Host?): CardWebView.PortalBridge = object : CardWebView.PortalBridge {
        private fun live(): Session? = sessions[id]?.takeIf { it.host != null && it.host === host() }
        override fun expose(name: String?, spec: String?): Boolean {
            val s = live() ?: return false
            return try {
                s.functions = PortalRules.withFunction(s.functions, PortalRules.function(name, spec))
                emit(s, "portal.functions", JSONObject().put("functions", PortalRules.functionsJson(s.functions)))
                true
            } catch (e: PortalRules.Bad) { false }
        }
        override fun setState(json: String?): Boolean {
            val s = live() ?: return false
            val v = PortalRules.state(json) ?: return false
            s.state = v; s.stateAt = System.currentTimeMillis()
            stateEvent(s)
            return true
        }
        override fun reply(id: String?, json: String?) {
            val s = live() ?: return
            s.pending[id ?: return]?.complete(PortalRules.reply(json) ?: JSONObject().put("ok", false).put("error", "The portal's answer was too big or not JSON."))
        }
        override fun touched() { live()?.lastInput = JSONObject().put("by", "owner").put("at", System.currentTimeMillis()) }
        override fun loaded() { live()?.ready?.value = true }
    }

    /** The person at the device touched a pieces portal. */
    fun touched(id: String) { sessions[id]?.lastInput = JSONObject().put("by", "owner").put("at", System.currentTimeMillis()) }

    /** portal.state at most once a second: a burst sends the first change now and the latest one after the second. */
    private fun stateEvent(s: Session) {
        val wait = s.lastStateEvent + PortalRules.STATE_EVENT_MS - System.currentTimeMillis()
        if (wait <= 0) { s.lastStateEvent = System.currentTimeMillis(); emit(s, "portal.state", JSONObject().put("state", s.state ?: JSONObject.NULL)); return }
        if (s.stateEventQueued) return
        s.stateEventQueued = true
        scope.launch { delay(wait); s.stateEventQueued = false; if (s.host != null) stateEvent(s) }
    }

    /** One event to every agent watching [s], and to the agent that installed the applet. */
    private fun emit(s: Session, type: String, data: JSONObject) {
        val r = app.applets.get(s.instanceId) ?: return
        data.put("instance_id", s.instanceId).put("name", r.optString("name"))
        val to = s.watchers.toMutableSet()
        r.optString("requested_by").takeIf { !r.isNull("requested_by") && it.isNotEmpty() }?.let { to += it }
        for (a in to) if (app.agents.get(a) != null) runCatching { app.interactions.event(a, type, JSONObject(data.toString())) }
    }
}
