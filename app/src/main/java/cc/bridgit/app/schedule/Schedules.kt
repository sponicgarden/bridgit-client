package cc.bridgit.app.schedule

import cc.bridgit.app.outbound.Categories
import cc.bridgit.app.outbound.Outbound
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.capability.SpeakerQueue
import cc.bridgit.app.capability.SpeakerRules
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.SafeHttp
import cc.bridgit.app.interact.InteractionRules
import cc.bridgit.app.schedule.ScheduleRules.Do
import cc.bridgit.app.schedule.ScheduleRules.When
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * CAP-57 action manifests: one per agent. A new manifest (sent inline or fetched from the agent's URL)
 * waits for the owner's one-tap approval; the approved one keeps running meanwhile. Approved actions
 * then run on the phone by themselves, through the same handlers, grants and log as live calls.
 *
 * Record per agent (SharedPreferences "schedules", key = agent id):
 * {active:{manifest,digest,approved_at}, pending:{manifest,digest,received_at}, paused, source:{url,poll_minutes,etag,last_poll,last_error},
 *  state:{<action id>:{digest,last_slot,consumed,runs,last_run_at,last_status,last_error}}, assets:{<name>:{file,mime,size,sha256,created_at,by}}}
 */
class Schedules(private val app: BridgeApp) {
    private val prefs = app.getSharedPreferences("schedules", Context.MODE_PRIVATE)
    private val root = File(app.filesDir, "schedule").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tickLock = Mutex()
    private val runningTasks = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val agentLocks = ConcurrentHashMap<String, Mutex>()
    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = Runnable { tickSoon() }
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val listeners = mutableListOf<() -> Unit>()
    @Volatile private var workArmed = false
    @Volatile private var dispatcher: Pair<BridgeService, ApiServer>? = null

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }

    // ---- records -------------------------------------------------------------------------------------

    @Synchronized fun record(agentId: String): JSONObject? = prefs.getString(agentId, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    @Synchronized private fun save(agentId: String, r: JSONObject) { prefs.edit().putString(agentId, r.toString()).commit(); changed() }
    @Synchronized fun all(): Map<String, JSONObject> = prefs.all.mapNotNull { (k, v) -> runCatching { k to JSONObject(v as String) }.getOrNull() }.toMap()

    fun zone(): ZoneId = ZoneId.systemDefault()

    /** Parses [manifest] for [agent]: every call must be a route this agent could make itself. */
    /**
     * [checkFlows]: whether each do.flow must name one of the agent's startable flows now. Only when a manifest is
     * submitted: a flow removed later fails that one action when it runs (flow_not_found), never the whole schedule.
     */
    fun parse(agent: Agent, manifest: JSONObject, checkFlows: Boolean = false): ScheduleRules.Manifest =
        ScheduleRules.parse(manifest, zone(), System.currentTimeMillis(), SpeakerRules::urlAllowed, { method, path -> routeProblem(agent, method, path) }) { ref ->
            // An installed applet names its own flows by package name; its installer checked them (and may build the schedule first).
            if (!checkFlows || agent.connection == cc.bridgit.app.core.Connection.APPLET) null else app.platform.workflows.startable(agent.id, ref)
        }

    fun routeProblem(agent: Agent, method: String, path: String): String? {
        val (spec, _) = match(method, path) ?: return "$method $path isn't a Bridgit endpoint."
        return when {
            spec.path.startsWith("/ssh/connections") || spec.path == "/ssh/probe" || !spec.auth || spec.privileged || spec.path == "/mcp" || spec.capability in NOT_SCHEDULABLE -> "$method $path can't be scheduled."
            !agent.allows(spec.capability) -> "This agent has no ${spec.capability} access, so it can't schedule $method $path."
            else -> null
        }
    }

    fun match(method: String, path: String): Pair<EndpointSpec, Map<String, String>>? {
        if (!path.startsWith("/v1/") || path.contains('?') || path.contains('%')) return null
        val b = path.split('/')
        for (spec in app.registry.modules.flatMap { it.endpoints }.filter { it.method == method }) {
            val a = spec.fullPath.split('/')
            if (a.size != b.size) continue
            val params = mutableMapOf<String, String>()
            if (a.indices.all { i -> if (a[i].startsWith('{')) { params[a[i].trim('{', '}')] = b[i]; b[i].isNotEmpty() } else a[i] == b[i] }) return spec to params
        }
        return null
    }

    /**
     * Stores [manifest] as [agent]'s next revision. Identical to the running one: nothing changes.
     * Otherwise it waits for the owner (one notification), and the running revision keeps going.
     */
    @Synchronized fun submit(agent: Agent, manifest: JSONObject, source: JSONObject?): JSONObject {
        val m = try { parse(agent, manifest, checkFlows = true) } catch (e: ScheduleRules.Invalid) { throw ErrorCatalog.error("invalid_parameter", "Manifest: ${e.message}") }
        // An agent's do.with names only values its flow takes (an installed applet's were checked at install).
        if (agent.connection != cc.bridgit.app.core.Connection.APPLET) for (a in m.actions) (a.action as? ScheduleRules.Do.Flow)?.let { f ->
            app.platform.workflows.withProblem(agent.id, f.ref, f.with)?.let { throw ErrorCatalog.error("invalid_parameter", "Manifest: actions ${a.id}: with: $it.") }
        }
        val r = record(agent.id) ?: JSONObject().put("state", JSONObject()).put("assets", JSONObject())
        // An inline manifest replaces a URL subscription; a polled one keeps it.
        if (source != null) r.put("source", source) else r.remove("source")
        val activeDigest = r.optJSONObject("active")?.optString("digest")
        val status = when {
            m.digest == activeDigest -> { r.remove("pending"); "active" }
            m.digest == r.optJSONObject("pending")?.optString("digest") -> "awaiting_owner"
            else -> {
                r.put("pending", JSONObject().put("manifest", manifest).put("digest", m.digest).put("received_at", System.currentTimeMillis()))
                save(agent.id, r)
                app.interactions.event(agent.id, "schedule.proposed", JSONObject().put("digest", m.digest).put("actions", m.actions.size))
                app.log.system("audit", "schedule", "${agent.name} proposed ${m.actions.size} scheduled action(s)")
                notifyOwner(agent, m)
                "awaiting_owner"
            }
        }
        save(agent.id, r)
        return JSONObject().put("status", status).put("digest", m.digest)
            .put("actions", JSONArray(m.actions.map { JSONObject().put("id", it.id).put("summary", ScheduleRules.describe(it, m.zone)) }))
    }

    /** Bind delegated approval to the exact pending manifest while submissions are excluded. */
    @Synchronized fun decideByControl(agentId: String, approve: Boolean, check: (JSONObject) -> Unit) {
        val pending = record(agentId)?.optJSONObject("pending") ?: throw ErrorCatalog.error("not_found", "No pending schedule.")
        check(pending)
        if (approve) approve(agentId) else decline(agentId)
    }

    /** Owner only (ScheduleActivity). */
    @Synchronized fun approve(agentId: String) {
        val agent = app.agents.get(agentId) ?: return
        val r = record(agentId) ?: return
        val pending = r.optJSONObject("pending") ?: return
        val m = parse(agent, pending.getJSONObject("manifest"))
        if (m.digest != pending.getString("digest")) throw ErrorCatalog.error("invalid_parameter", "The manifest changed; review it again.")
        val old = r.optJSONObject("state") ?: JSONObject()
        val state = JSONObject()
        m.actions.forEach { a -> old.optJSONObject(a.id)?.takeIf { it.optString("digest") == a.digest }?.let { state.put(a.id, it) } ?: state.put(a.id, JSONObject().put("digest", a.digest)) }
        r.put("active", JSONObject().put("manifest", pending.getJSONObject("manifest")).put("digest", m.digest).put("approved_at", System.currentTimeMillis()))
            .put("state", state).put("paused", false).remove("pending")
        save(agentId, r)
        app.interactions.event(agentId, "schedule.approved", JSONObject().put("digest", m.digest))
        app.log.system("audit", "schedule", "Owner approved ${agent.name}'s scheduled actions")
        cancelNotification(agentId)
        tickSoon()
    }

    @Synchronized fun decline(agentId: String) {
        val r = record(agentId) ?: return
        val digest = r.optJSONObject("pending")?.optString("digest") ?: return
        r.remove("pending"); save(agentId, r)
        app.interactions.event(agentId, "schedule.declined", JSONObject().put("digest", digest))
        app.log.system("audit", "schedule", "Owner declined ${app.agents.get(agentId)?.name ?: "an agent"}'s scheduled actions")
        cancelNotification(agentId)
    }

    fun setPaused(agentId: String, paused: Boolean, byOwner: Boolean) {
        val r = record(agentId) ?: return
        r.put("paused", paused); save(agentId, r)
        app.interactions.event(agentId, if (paused) "schedule.paused" else "schedule.resumed", JSONObject().put("by", if (byOwner) "owner" else "agent"))
        if (byOwner) app.log.system("audit", "schedule", "Owner ${if (paused) "paused" else "resumed"} ${app.agents.get(agentId)?.name ?: "an agent"}'s scheduled actions")
        rearm()
    }

    suspend fun pauseAndWait(agentId: String) {
        setPaused(agentId, true, byOwner = true)
        runningTasks[agentId]?.let { it.cancel(); it.join() }
    }

    fun remove(agentId: String, keepAssets: Boolean = false) {
        val r = record(agentId) ?: return
        if (keepAssets) { save(agentId, JSONObject().put("assets", r.optJSONObject("assets") ?: JSONObject()).put("state", JSONObject())) }
        else { synchronized(this) { prefs.edit().remove(agentId).commit() }; File(root, safe(agentId)).deleteRecursively(); changed() }
        cancelNotification(agentId)
        rearm()
    }

    // ---- assets --------------------------------------------------------------------------------------

    private fun safe(s: String) = s.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
    fun assetFile(agentId: String, meta: JSONObject) = File(File(root, safe(agentId)), safe(meta.getString("file")))
    fun asset(agentId: String, name: String): JSONObject? = record(agentId)?.optJSONObject("assets")?.optJSONObject(name)
    fun assets(agentId: String): JSONObject = record(agentId)?.optJSONObject("assets") ?: JSONObject()

    @Synchronized fun saveAsset(agentId: String, name: String, mime: String, src: File, by: String): JSONObject {
        val r = record(agentId) ?: throw ErrorCatalog.error("not_found", "No schedule.")
        val assets = r.optJSONObject("assets") ?: JSONObject().also { r.put("assets", it) }
        val size = src.length()
        if (size > ScheduleRules.MAX_ASSET_BYTES) { src.delete(); throw ErrorCatalog.error("payload_too_large", "Saved results are limited to 25 MB each.") }
        val others = assets.keys().asSequence().filter { it != name }.toList()
        val used = others.sumOf { assets.getJSONObject(it).optLong("size") }
        if (used + size > ScheduleRules.MAX_AGENT_ASSET_BYTES || (!assets.has(name) && others.size >= ScheduleRules.MAX_AGENT_ASSETS)) {
            src.delete(); throw ErrorCatalog.error("quota_exceeded", "Each agent can keep 100 saved results, 100 MB in total. Delete some with a delete action or DELETE /v1/schedule/assets/{name}.")
        }
        val dir = File(root, safe(agentId)).apply { mkdirs() }
        val file = UUID.randomUUID().toString()
        val dest = File(dir, file)
        if (!src.renameTo(dest)) { src.copyTo(dest, overwrite = true); src.delete() }
        assets.optJSONObject(name)?.let { File(dir, safe(it.getString("file"))).delete() }
        val meta = JSONObject().put("file", file).put("mime", mime).put("size", size).put("sha256", ScheduleRules.sha256(dest.readBytes()))
            .put("created_at", System.currentTimeMillis()).put("by", by)
        assets.put(name, meta); save(agentId, r)
        return meta
    }

    @Synchronized fun deleteAsset(agentId: String, name: String): Boolean {
        val r = record(agentId) ?: return false
        val meta = r.optJSONObject("assets")?.optJSONObject(name) ?: return false
        assetFile(agentId, meta).delete()
        r.getJSONObject("assets").remove(name); save(agentId, r)
        return true
    }

    /** Assets older than 30 days, and everything of removed agents, go. */
    fun prune() {
        val cutoff = System.currentTimeMillis() - 30L * 86400_000
        all().forEach { (agentId, r) ->
            if (app.agents.get(agentId) == null) { remove(agentId); return@forEach }
            val assets = r.optJSONObject("assets") ?: return@forEach
            assets.keys().asSequence().toList().filter { assets.getJSONObject(it).optLong("created_at") < cutoff }.forEach { deleteAsset(agentId, it) }
        }
        root.listFiles()?.filter { it.isDirectory && all().keys.none { k -> safe(k) == it.name } }?.forEach { it.deleteRecursively() }
    }

    // ---- timing --------------------------------------------------------------------------------------

    fun start() {
        scope.launch { runCatching { prune() }; tick() }
    }

    fun tickSoon() { scope.launch { tick() } }

    @Volatile private var agentPrint = ""
    /** The agent registry notifies on every request; only a change in agents or their grants matters here. */
    fun onAgentsChanged() {
        val print = app.agents.all().joinToString("|") { it.id + ":" + (it.caps?.sorted()?.joinToString(",") ?: "*") }
        if (print != agentPrint) { agentPrint = print; tickSoon() }
    }

    /** Runs whatever is due, polls manifest URLs that are due, then arms the next wake-up. */
    suspend fun tick() {
        tickLock.withLock {
            val now = System.currentTimeMillis()
            for ((agentId, r) in all()) {
                val agent = app.agents.get(agentId)
                if (agent == null) { remove(agentId); continue }
                runCatching { pollIfDue(agent, r, now) }
                val active = record(agentId)?.optJSONObject("active") ?: continue
                if (record(agentId)?.optBoolean("paused") == true || !runnable(agent)) continue
                val parsed = runCatching { parse(agent, active.getJSONObject("manifest")) }
                val m = parsed.getOrNull()
                if (m == null) {
                    // E.g. the owner took away a capability an action needs: stop and say why.
                    setPaused(agentId, true, byOwner = false)
                    app.interactions.event(agentId, "schedule.blocked", JSONObject().put("error", parsed.exceptionOrNull()?.message?.take(300)))
                    continue
                }
                for (a in m.actions) {
                    if (a.whenSpec is When.After) continue
                    val rec = record(agentId) ?: break
                    val st = rec.getJSONObject("state").optJSONObject(a.id) ?: JSONObject().put("digest", a.digest)
                    val last = if (st.has("last_slot")) st.getLong("last_slot") else null
                    val due = ScheduleRules.due(a.whenSpec, last, st.optInt("consumed"), now)
                    if (due.run == null && due.skipped == 0) continue
                    // Checkpoint before running: a run interrupted by a crash is reported, not repeated.
                    due.lastConsumed?.let { st.put("last_slot", it) }
                    st.put("consumed", due.consumedAfter)
                    if (due.next == null) st.put("finished", true)
                    rec.getJSONObject("state").put(a.id, st); save(agentId, rec)
                    if (due.skipped > 0) app.interactions.event(agentId, "schedule.missed", JSONObject().put("action_id", a.id).put("slots", due.skipped)
                        .put("note", "The phone was asleep, off or busy past the action's grace_minutes."))
                    due.run?.let { slot -> launchRun(agentId, m, a, slot) }
                }
            }
            rearm()
        }
    }

    private fun runnable(agent: Agent) = app.settings.listenerEnabled && app.settings.isOn(SWITCH) && agent.allows("schedule")

    private fun launchRun(agentId: String, m: ScheduleRules.Manifest, a: ScheduleRules.Action, slot: Long) {
        scope.launch {
            val wake = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bridgit:schedule")
            wake.acquire(5 * 60_000L)
            try {
                agentLocks.getOrPut(agentId) { Mutex() }.withLock {
                    if (record(agentId)?.optBoolean("paused") == true ||
                            app.applets.forAgent(agentId)?.let { cc.bridgit.app.applets.AppletLifecycle.state(it) != "running" } == true) return@withLock
                    val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
                    runningTasks[agentId] = job
                    try {
                        if (record(agentId)?.optBoolean("paused") == true ||
                            app.applets.forAgent(agentId)?.let { cc.bridgit.app.applets.AppletLifecycle.state(it) != "running" } == true) return@withLock
                        var ok = run(agentId, m, a, slot)
                        // Chains: actions that run "after" this one, in manifest order, while each succeeds.
                        var parents = if (ok) listOf(a.id) else emptyList()
                        var guard = 0
                        while (parents.isNotEmpty() && guard++ < ScheduleRules.MAX_ACTIONS) {
                            val next = m.actions.filter { (it.whenSpec as? When.After)?.action in parents }
                            parents = next.filter { child -> run(agentId, m, child, slot) }.map { it.id }
                            ok = ok && parents.size == next.size
                        }
                    } finally { runningTasks.remove(agentId, job) }
                }
            } finally { if (wake.isHeld) wake.release(); rearm() }
        }
    }

    /** Owner or agent "run now": the approved revision only, outside the timetable. */
    suspend fun runNow(agentId: String, actionId: String): JSONObject {
        app.applets.forAgent(agentId)?.let {
            if (cc.bridgit.app.applets.AppletLifecycle.state(it) in setOf("starting", "pausing"))
                throw ErrorCatalog.error("conflict", "Wait for the applet transition to finish.")
        }
        val agent = app.agents.get(agentId) ?: throw ErrorCatalog.error("not_found", "No such agent.")
        val active = record(agentId)?.optJSONObject("active") ?: throw ErrorCatalog.error("not_found", "No approved schedule. The owner approves new manifests on the phone.")
        val m = parse(agent, active.getJSONObject("manifest"))
        val a = m.actions.firstOrNull { it.id == actionId } ?: throw ErrorCatalog.error("not_found", "No action \"$actionId\" in the approved manifest.")
        if (!runnable(agent)) throw ErrorCatalog.error("capability_disabled", "Scheduled actions are switched off on the phone.", mapOf("setting" to SWITCH))
        val ok = agentLocks.getOrPut(agentId) { Mutex() }.withLock { run(agentId, m, a, System.currentTimeMillis()) }
        val result = record(agentId)?.optJSONObject("state")?.optJSONObject(actionId)
        return JSONObject().put("action_id", actionId).put("status", if (ok) "ok" else if (result?.optString("last_status") == "dropped") "dropped" else "failed")
            .put("result", result)
    }

    private fun rearm() {
        handler.removeCallbacks(tickRunnable)
        val now = System.currentTimeMillis()
        var next: Long? = null
        for ((agentId, r) in all()) {
            val agent = app.agents.get(agentId) ?: continue
            r.optJSONObject("source")?.let { s -> next = minOf(next ?: Long.MAX_VALUE, s.optLong("last_poll") + s.optInt("poll_minutes", 60) * 60_000L) }
            val active = r.optJSONObject("active") ?: continue
            if (r.optBoolean("paused") || !runnable(agent)) continue
            val m = runCatching { parse(agent, active.getJSONObject("manifest")) }.getOrNull() ?: continue
            for (a in m.actions) {
                val st = r.optJSONObject("state")?.optJSONObject(a.id) ?: JSONObject()
                ScheduleRules.nextSlot(a.whenSpec, if (st.has("last_slot")) st.getLong("last_slot") else null, st.optInt("consumed"))
                    ?.let { next = minOf(next ?: Long.MAX_VALUE, it) }
            }
        }
        val pi = PendingIntent.getBroadcast(app, 8710, Intent(app, ScheduleAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.cancel(pi)
        val wm = WorkManager.getInstance(app)
        val at = next
        if (at == null) {
            if (all().isEmpty()) { wm.cancelUniqueWork(WORK); workArmed = false }
            return
        }
        val delay = maxOf(500L, at - now)
        handler.postDelayed(tickRunnable, delay)
        // No exact-alarm permission: Android may batch this while the phone dozes (documented inexactness).
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + delay, pi)
        if (!workArmed) { workArmed = true; wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ScheduleWorker>(15, TimeUnit.MINUTES).build()) }
    }

    // ---- polling -------------------------------------------------------------------------------------

    private fun pollIfDue(agent: Agent, r: JSONObject, now: Long) {
        val s = r.optJSONObject("source") ?: return
        if (now < s.optLong("last_poll") + s.optInt("poll_minutes", 60) * 60_000L) return
        if (!app.settings.listenerEnabled || !app.settings.isOn(SWITCH) || !agent.allows("schedule")) return
        poll(agent, s)
    }

    /** Fetches the agent's manifest URL (public https only), honouring ETag. Returns the submit result, or null when unchanged. */
    fun poll(agent: Agent, source: JSONObject): JSONObject? {
        val url = source.getString("url")
        val headers = mutableMapOf("User-Agent" to "Bridgit (Android) schedule", "Accept" to "application/json")
        source.optString("etag").takeIf { it.isNotEmpty() }?.let { headers["If-None-Match"] = it }
        val s = JSONObject(source.toString()).put("last_poll", System.currentTimeMillis())
        fun store(err: String?) {
            val r = record(agent.id) ?: return
            val before = r.optJSONObject("source")?.optString("last_error")
            if (err == null) s.remove("last_error") else s.put("last_error", err)
            r.put("source", s); save(agent.id, r)
            if (err != null && err != before) app.interactions.event(agent.id, "schedule.poll_failed", JSONObject().put("url_host", ScheduleRules.host(url)).put("error", err))
        }
        val res = try {
            Outbound.on(Outbound.Actor.agent(agent.id, agent.name), Outbound.Hint("Agent's manifest URL", "Poll the agent's schedule manifest")) {
                SafeHttp.get(url, SafeHttp.Policy.PUBLIC, 30_000, ScheduleRules.MAX_MANIFEST_BYTES.toLong() + 1, headers = headers, maxRedirects = SafeHttp.MAX_REDIRECTS) }
        } catch (e: SafeHttp.Failure) { store("Couldn't fetch the manifest: ${e.message}"); throw ErrorCatalog.error("invalid_parameter", "Couldn't fetch the manifest URL: ${e.message}") }
        if (res.status == 304) { store(null); return null }
        if (res.status != 200) { store("The manifest URL answered HTTP ${res.status}."); throw ErrorCatalog.error("invalid_parameter", "The manifest URL answered HTTP ${res.status}.") }
        val json = runCatching { JSONObject(String(res.bytes ?: ByteArray(0), Charsets.UTF_8)) }.getOrElse {
            store("The manifest isn't JSON."); throw ErrorCatalog.error("invalid_parameter", "The manifest URL didn't return a JSON object.")
        }
        res.header("etag")?.take(200)?.let { s.put("etag", it) }
        return try { submit(agent, json, s).also { store(null) } } catch (e: ApiError) { store(e.message); throw e }
    }

    // ---- running one action ---------------------------------------------------------------------------

    private class Fail(val code: String, message: String) : Exception(message)

    private suspend fun run(agentId: String, m: ScheduleRules.Manifest, a: ScheduleRules.Action, slot: Long): Boolean {
        val agent = app.agents.get(agentId) ?: return false
        val started = System.currentTimeMillis()
        val ev = JSONObject().put("action_id", a.id).put("slot", ScheduleRules.iso(slot, m.zone))
        var ok = false
        try {
            if (!runnable(agent)) throw Fail("capability_disabled", "Scheduled actions are switched off on the phone.")
            withTimeout(150_000) { perform(agent, a, slot, ev) }
            ok = true
        } catch (e: Fail) { ev.put("error", e.code).put("message", e.message)
        } catch (e: ApiError) { ev.put("error", e.code).put("message", e.message)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) { ev.put("error", "timeout").put("message", "The action took longer than 150 seconds.")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { ev.put("error", "internal_error").put("message", e.message?.take(300) ?: e.javaClass.simpleName) }
        // A flow already running drops the start (never queued): not a failure of this action.
        val dropped = ev.optBoolean("dropped")
        ev.remove("dropped")
        ev.put("status", if (ok) "ok" else if (dropped) "dropped" else "failed").put("duration_ms", System.currentTimeMillis() - started)
        record(agentId)?.let { r ->
            val state = r.optJSONObject("state") ?: JSONObject().also { r.put("state", it) }
            val st = state.optJSONObject(a.id) ?: JSONObject().put("digest", a.digest)
            st.put("runs", st.optInt("runs") + 1).put("last_run_at", started).put("last_status", ev.getString("status"))
            if (ok || dropped) st.remove("last_error") else st.put("last_error", ev.optString("message"))
            state.put(a.id, st); save(agentId, r)
        }
        app.interactions.event(agentId, "schedule.ran", ev)
        return ok
    }

    private suspend fun perform(agent: Agent, a: ScheduleRules.Action, slot: Long, ev: JSONObject) {
        when (val d = a.action) {
            is Do.Call -> call(agent, d, a, slot, ev)
            is Do.Step -> {
                val c = cc.bridgit.app.platform.FlowSteps.inlineCall(d.step, trigger(a, slot), a.name ?: "Scheduled")
                val q = mutableMapOf<String, String>()
                ev.put("step", d.step.optString("type"))
                val tries = 1 + (if (d.step.optString("on_error") == "retry") d.step.optInt("retries", 1) else 0)
                for (attempt in 0 until tries) {
                    if (attempt > 0) kotlinx.coroutines.delay(cc.bridgit.app.platform.FlowSteps.RETRY_DELAYS_MS[attempt - 1])
                    try { call(agent, Do.Call(c.getString("method"), c.getString("path"), q, c.getJSONObject("body")), a, slot, ev); break }
                    catch (e: Fail) { if (attempt == tries - 1) throw e }
                }
            }
            is Do.Flow -> {
                // Counts as run when the flow starts: a flow that asks the owner may take an hour.
                val started = app.platform.workflows.start(agent.id, d.ref, trigger(a, slot), d.with)
                ev.put("flow", started)
                if (started.optString("status") == "skipped") { ev.put("dropped", true); throw Fail("skipped", started.optString("message").ifEmpty { "Flow ${d.ref} is in its cooldown or used its runs for today, so this start was skipped." }) }
                if (started.optString("status") == "dropped") { ev.put("dropped", true); throw Fail("dropped", "Flow ${d.ref} was still running, so this start was dropped.") }
                if (started.optString("status") != "started") throw Fail(started.optString("status"), started.optString("message").ifEmpty { "Flow ${d.ref} didn't start (${started.optString("status")})." })
            }
            else -> perform2(agent, a, slot, ev)
        }
    }

    /** The `$trigger` a flow or a single step gets from a schedule action (docs/applet-vocabulary.md). */
    private fun trigger(a: ScheduleRules.Action, slot: Long): JSONObject {
        val now = System.currentTimeMillis()
        return JSONObject().put("caller", "schedule").put("action", a.id).put("name", a.name ?: a.id)
            .put("due_at", cc.bridgit.app.capability.isoAt(slot)).put("late_s", maxOf(0L, (now - slot) / 1000)).put("at", cc.bridgit.app.capability.isoAt(now))
    }

    private suspend fun call(agent: Agent, d: Do.Call, a: ScheduleRules.Action, slot: Long, ev: JSONObject) {
                val svc = BridgeService.running ?: throw Fail("listener_not_running", "Bridgit's listener isn't running, so API actions can't run. Ask the owner to resume it.")
                val (spec, params) = match(d.method, d.path) ?: throw Fail("not_found", "${d.method} ${d.path} no longer exists.")
                routeProblem(agent, d.method, d.path)?.let { throw Fail("agent_not_allowed", it) }
                // Required-key endpoints get a stable slot key. Other calls rely on the existing slot checkpoint.
                val out = api(svc).dispatch(spec, agent, "schedule", d.query, params, JSONObject(d.body.toString()),
                    if (spec.idempotencyRequired) "schedule-${agent.id}-${a.id}-$slot" else null, via = "schedule")
                ev.put("http", out.status)
                if (out.status !in 200..299) {
                    val err = runCatching { JSONObject(String(out.bytes)) }.getOrNull()
                    throw Fail(err?.optString("error")?.ifEmpty { null } ?: "http_${out.status}", err?.optString("message")?.ifEmpty { null } ?: "HTTP ${out.status}")
                }
                if (spec.path == "/ssh/exec") {
                    val result = JSONObject(String(out.bytes, Charsets.UTF_8))
                    if (result.opt("exit_code") != 0) throw Fail("ssh_command_failed", "SSH command returned a nonzero or unknown exit code; dependent actions will not run.")
                }
                val p = ScheduleRules.payload(out.contentType, out.bytes) { Base64.decode(it, Base64.DEFAULT) }
                a.saveAs?.let { name -> ev.put("asset", name).put("asset_meta", keep(agent.id, name, p, a.id)) }
                a.sendTo?.let { ev.put("sent", send(agent, it, p, a.id, slot, zoneOf(agent))) }
                // Small JSON replies come back in the event itself, so the agent needn't fetch anything.
                if (a.saveAs == null && p.mime == "application/json" && p.bytes.size <= 8192) runCatching { ev.put("result", JSONObject(String(p.bytes))) }
    }

    private suspend fun perform2(agent: Agent, a: ScheduleRules.Action, slot: Long, ev: JSONObject) {
        when (val d = a.action) {
            is Do.Call, is Do.Step, is Do.Flow -> {}
            is Do.Download -> {
                val tmp = File(app.cacheDir, "schedule-dl-${UUID.randomUUID()}")
                try {
                    val r = try {
                        Outbound.on(Outbound.Actor.agent(agent.id, agent.name), Outbound.Hint("Scheduled download", "Scheduled action: download a file")) {
                            SafeHttp.get(d.url, SafeHttp.Policy.PUBLIC, 120_000, d.maxBytes, headers = mapOf("User-Agent" to "Bridgit (Android) schedule"), maxRedirects = SafeHttp.MAX_REDIRECTS, out = tmp) }
                    } catch (e: SafeHttp.Failure) { throw Fail(if (e.reason == SafeHttp.Reason.TOO_LARGE) "payload_too_large" else "download_failed", "Download failed: ${e.message}") }
                    if (r.status != 200) throw Fail("download_failed", "The download answered HTTP ${r.status}.")
                    val sha = ScheduleRules.sha256(tmp.readBytes())
                    if (d.sha256 != null && sha != d.sha256) throw Fail("checksum_mismatch", "The download's sha256 didn't match.")
                    val mime = d.mime ?: r.header("content-type")?.substringBefore(';')?.trim()?.takeIf { it.contains('/') } ?: "application/octet-stream"
                    val name = a.saveAs ?: a.id
                    ev.put("asset", name).put("asset_meta", saveAsset(agent.id, name, mime, tmp, a.id))
                    a.sendTo?.let { s -> asset(agent.id, name)?.let { meta -> ev.put("sent", send(agent, s, ScheduleRules.Payload(assetFile(agent.id, meta).readBytes(), mime), a.id, slot, zoneOf(agent))) } }
                } finally { tmp.delete() }
            }
            is Do.Play -> {
                if (!agent.allows("speaker")) throw Fail("agent_not_allowed", "This agent has no speaker access.")
                if (app.registry.byId("speaker")?.status()?.usable != true) throw Fail("capability_disabled", "The speaker is switched off or not ready on the phone.")
                val meta = asset(agent.id, d.asset) ?: throw Fail("not_found", "No saved asset \"${d.asset}\".")
                if (!meta.getString("mime").startsWith("audio/") && meta.getString("mime") != "application/octet-stream") throw Fail("invalid_parameter", "${d.asset} isn't audio.")
                val file = app.speaker.newFile(meta.getString("mime").substringAfter('/').filter { it.isLetterOrDigit() }.take(8).ifEmpty { "audio" })
                assetFile(agent.id, meta).copyTo(file, overwrite = true)
                val pos = app.speaker.enqueue(SpeakerQueue.Item(UUID.randomUUID().toString().take(12), agent.id, agent.name, file, d.caption, null, false), "queue")
                ev.put("position", pos)
            }
            is Do.Show -> {
                if (!agent.allows("interactions")) throw Fail("agent_not_allowed", "This agent has no interactions access.")
                if (app.registry.byId("interactions")?.status()?.usable != true) throw Fail("capability_disabled", "Interactions are switched off or notifications are off on the phone.")
                val meta = asset(agent.id, d.asset) ?: throw Fail("not_found", "No saved asset \"${d.asset}\".")
                val bytes = assetFile(agent.id, meta).readBytes()
                val mime = meta.getString("mime")
                val body = JSONObject().put("title", d.title)
                var jpeg: ByteArray? = null
                when {
                    mime == "text/html" -> body.put("kind", "html").put("html", String(bytes, Charsets.UTF_8))
                    mime.startsWith("image/") -> {
                        body.put("kind", "notice"); d.text?.let { body.put("text", it) }
                        jpeg = cc.bridgit.app.feedback.bytesToDownscaledJpeg(bytes) ?: throw Fail("invalid_parameter", "${d.asset} isn't a picture the phone can show.")
                    }
                    mime.startsWith("text/") || mime == "application/json" -> body.put("kind", "notice").put("text", ((d.text?.let { "$it\n\n" } ?: "") + String(bytes, Charsets.UTF_8)).take(4000))
                    else -> throw Fail("invalid_parameter", "${d.asset} ($mime) can't be shown: use an image, text or text/html.")
                }
                val draft = try { InteractionRules.parse(body) } catch (e: Exception) { throw Fail("invalid_parameter", e.message ?: "Can't show ${d.asset}.") }
                val i = app.interactions.create(agent.id, agent.name, draft, jpeg)
                ev.put("interaction_id", i.id)
            }
            is Do.Send -> {
                val meta = asset(agent.id, d.asset) ?: throw Fail("not_found", "No saved asset \"${d.asset}\".")
                ev.put("sent", send(agent, a.sendTo!!, ScheduleRules.Payload(assetFile(agent.id, meta).readBytes(), meta.getString("mime")), a.id, slot, zoneOf(agent)))
            }
            is Do.Delete -> ev.put("deleted", deleteAsset(agent.id, d.asset))
        }
    }

    private fun zoneOf(agent: Agent): ZoneId = record(agent.id)?.optJSONObject("active")?.optJSONObject("manifest")
        ?.let { runCatching { parse(agent, it).zone }.getOrNull() } ?: zone()

    private fun keep(agentId: String, name: String, p: ScheduleRules.Payload, by: String): JSONObject {
        val tmp = File(app.cacheDir, "schedule-keep-${UUID.randomUUID()}")
        tmp.writeBytes(p.bytes)
        return saveAsset(agentId, name, p.mime, tmp, by)
    }

    /** POSTs [p] to the approved URL (public https only, no redirects). Returns {status, host}; a non-2xx answer fails the run. */
    private fun send(agent: Agent, s: ScheduleRules.SendTo, p: ScheduleRules.Payload, actionId: String, slot: Long, zone: ZoneId): JSONObject {
        val headers = LinkedHashMap(s.headers)
        headers["Content-Type"] = p.mime
        headers["User-Agent"] = "Bridgit (Android) schedule"
        headers["X-Bridgit-Action"] = actionId
        headers["X-Bridgit-Slot"] = ScheduleRules.iso(slot, zone)
        val status = try { Outbound.on(Outbound.Actor.agent(agent.id, agent.name), Outbound.Hint("Agent's send_to URL", "Scheduled action: send the result to the agent's server", listOf(Categories.API_RESULT))) {
                SafeHttp.post(s.url, SafeHttp.Policy.PUBLIC, headers, p.bytes, 120_000) } }
            catch (e: SafeHttp.Failure) { throw Fail("send_failed", "Couldn't send to ${ScheduleRules.host(s.url)}: ${e.message}") }
        if (status !in 200..299) throw Fail("send_failed", "${ScheduleRules.host(s.url)} answered HTTP $status.")
        return JSONObject().put("status", status).put("host", ScheduleRules.host(s.url)).put("bytes", p.bytes.size).put("mime", p.mime)
    }

    @Synchronized private fun api(svc: BridgeService): ApiServer {
        dispatcher?.takeIf { it.first === svc }?.let { return it.second }
        return ApiServer(svc, emptyList(), app.settings.port).apply { prepareRelay() }.also { dispatcher = svc to it }
    }

    // ---- owner notification ---------------------------------------------------------------------------

    private fun notifyOwner(agent: Agent, m: ScheduleRules.Manifest) {
        val id = NOTIF_BASE + (agent.id.hashCode() and 0xfff)
        val open = PendingIntent.getActivity(app, id, Intent(app, ScheduleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = m.actions.size
        app.getSystemService(NotificationManager::class.java).notify(id, NotificationCompat.Builder(app, BridgeApp.CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_nav_more)
            .setContentTitle(app.getString(R.string.schedule_notif_title, agent.name, n))
            .setContentText(app.getString(R.string.schedule_notif_text))
            .setContentIntent(open).setAutoCancel(true).build())
    }
    private fun cancelNotification(agentId: String) = app.getSystemService(NotificationManager::class.java).cancel(NOTIF_BASE + (agentId.hashCode() and 0xfff))

    companion object {
        const val SWITCH = "schedule"
        const val WORK = "schedule-tick"
        private const val NOTIF_BASE = 0x5c00_0000
        /** The schedule can't schedule itself; issues stay with live agents. */
        val NOT_SCHEDULABLE = setOf("schedule", "feedback")
    }
}

/** Backup wake-up for the next slot (inexact while dozing). */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val app = context.applicationContext as BridgeApp
        CoroutineScope(Dispatchers.IO).launch { try { app.schedules.tick() } finally { pending.finish() } }
    }
}

/** Every 15 minutes while any schedule exists: polls manifest URLs and catches anything the alarm missed. */
class ScheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { (applicationContext as BridgeApp).schedules.tick(); return Result.success() }
}
