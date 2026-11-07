package cc.bridgit.app.watch

import android.content.Context
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.core.CallbackRules
import cc.bridgit.app.core.SafeHttp
import cc.bridgit.app.interact.Event
import cc.bridgit.app.judgment.Jev
import cc.bridgit.app.outbound.Categories
import cc.bridgit.app.outbound.Outbound
import cc.bridgit.app.vision.ChangeGate
import cc.bridgit.app.vision.FrameDiff
import cc.bridgit.app.vision.Grids
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID

/**
 * Runs watches (POST /v1/watches). Each watch has its own loop: take a reading, keep the last `window`, ask
 * the model the agent's question once there are enough, and on a match store a `watch.matched` event (which
 * reaches /v1/events and the agent's registered callback) and, if the watch has its own callback_url, POST it
 * there too, signed with the watch's secret. A watch ends on its first match (unless repeat), when it expires,
 * when it's deleted, when access is taken away, or after repeated failures; that's a `watch.ended` event.
 * Definitions survive a restart (SharedPreferences "watches"); readings don't.
 */
class Watches(private val app: BridgeApp) {
    class Watch(
        val id: String, val agentId: String, val agentName: String, request: JSONObject, spec: WatchRules.Spec,
        val createdAt: Long, expiresAt: Long,
    ) {
        @Volatile private var definition = request to spec
        val request get() = definition.first
        val spec get() = definition.second
        internal fun revise(request: JSONObject, spec: WatchRules.Spec) { definition = request to spec }

        /** When the watch ends; an installed applet's watch is renewed ([Watches.renewer]). */
        @Volatile var expiresAt: Long = expiresAt
        val readings = ArrayDeque<JSONObject>()
        var lastSteps: Float? = null
        @Volatile var checks = 0
        @Volatile var matches = 0
        @Volatile var lastMatchAt = 0L
        @Volatile var lastCheckAt = 0L
        @Volatile var failures = 0
        @Volatile var pausedUntil = 0L
        @Volatile var userPaused = false
        /**
         * The applet's budget is used up (applet_budget, all phones): no checks until then (ms; 0 = not paused).
         * Saved, so a restart keeps it; a budget sync that says ok clears it at once ([Watches.budgetResume]).
         */
        @Volatile var budgetPausedUntil = 0L
        /** An interval set by the owner, an agent or the budget sync (the applet's action), kept across restarts; null = the spec's. */
        @Volatile var intervalOverrideS: Int? = null
        /** The seconds between readings now in force. */
        val intervalS: Int get() = intervalOverrideS ?: spec.intervalS
        /** Wakes the loop early (a new interval, a budget pause lifted). */
        val nudge = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        fun budgetPaused(now: Long = System.currentTimeMillis()) = budgetPausedUntil > now
        @Volatile var lastState: JSONObject? = null
        val recent = ArrayDeque<JSONObject>()
        var job: Job? = null
        /** Checks answered on the phone for free (picture unchanged, or model none), paid model calls and their cost. */
        @Volatile var checksLocal = 0
        @Volatile var checksPaid = 0
        @Volatile var paidCostUsd = 0.0
        @Volatile var skippedCap = 0
        private val paidTimes = ArrayDeque<Long>()
        /** The free change check: the last photo a paid model saw and its answer. */
        private val gates = mutableMapOf<String?, ChangeGate<Jev.Answer>>()
        var sampleCamera: String? = spec.camera
        private var nextCameraIndex = 0
        fun nextCamera(): String? {
            if (spec.cameraSources.isEmpty()) return null
            val source = spec.cameraSources[nextCameraIndex]
            nextCameraIndex = (nextCameraIndex + 1) % spec.cameraSources.size
            return source
        }
        val gate get() = gates.getOrPut(sampleCamera) { ChangeGate(spec.local) }
        /** model none: the photo the last change was measured against. */
        private val references = mutableMapOf<String?,IntArray>()
        var reference: IntArray?
            get() = references[sampleCamera]
            set(value) { if (value == null) references.remove(sampleCamera) else references[sampleCamera] = value }
        /** The tally's counts and episodes (spec.tally set); guarded by itself. Saved to its own file. */
        var tally: WatchTally.State? = null
        @Volatile var tallySavedAt = 0L
        @Volatile var rulesFired = 0
        /**
         * Only while [Watches.photoWanted] says so (an installed applet's web portal with photo sharing on): this
         * check's photo until it is answered, then the last answered photo and the check it belongs to. Memory only.
         */
        @Volatile var pendingPhoto: ByteArray? = null
        @Volatile var lastPhoto: ByteArray? = null
        @Volatile var lastPhotoCheck: JSONObject? = null
        /**
         * A sound watch: the last paid answer, the fingerprint of its clip and when (moved on by every sound that
         * reused it). A loud sound within reuse_s that sounds like it reuses the answer for free.
         */
        @Volatile var soundAt = 0L
        @Volatile var soundSource: String? = null
        @Volatile var soundAnswer: Jev.Answer? = null
        @Volatile var soundPrint: cc.bridgit.app.sound.SoundPrint? = null
        /** A sound watch: whether the phone's listener has it right now, and why not (null = listening or unknown). */
        @Volatile var listening: Boolean? = null
        @Volatile var notListeningReason: String? = null
        /** One loud sound at a time per watch. */
        val soundLock = kotlinx.coroutines.sync.Mutex()
        /** One photo check at a time: the watch's own and the owner's camera photos ([Watches.ownerPhoto]). */
        val checkLock = kotlinx.coroutines.sync.Mutex()

        /** The tally summary (null without a tally). */
        fun tallySummary(now: Long = System.currentTimeMillis()): JSONObject? {
            val t = spec.tally ?: return null
            val st = tally ?: return null
            return synchronized(st) { WatchTally.summary(t, st, now, ZoneId.systemDefault(), intervalS) }
        }

        /** One line for the owner: today's counts. */
        fun todayLine(now: Long = System.currentTimeMillis()): String? {
            val t = spec.tally ?: return null
            val st = tally ?: return null
            // A situation counts under its id; the owner reads its title ("Pot boiling over once (2 min) today.").
            val names = if (spec.situation != null) mapOf(t.track to spec.label) else emptyMap()
            return synchronized(st) { WatchTally.todayLine(t, st, now, ZoneId.systemDefault(), intervalS, names) }
        }

        fun paidLastHour(now: Long): Int = synchronized(paidTimes) {
            while (paidTimes.isNotEmpty() && now - paidTimes.first() >= 3_600_000L) paidTimes.removeFirst()
            paidTimes.size
        }

        fun paidCall(now: Long, cost: Double) {
            synchronized(paidTimes) { paidTimes.addLast(now) }
            checksPaid++; paidCostUsd += cost
        }

        /** Free vs paid so far: what the agent pays for this watch. */
        fun usage(now: Long = System.currentTimeMillis()): JSONObject = JSONObject().put("model", spec.model)
            .put("answered_locally", checksLocal).put("paid_calls", checksPaid)
            .put("paid_cost_usd", Math.round(paidCostUsd * 1e7) / 1e7)
            .put("paid_calls_last_hour", paidLastHour(now)).put("max_paid_per_hour", spec.maxPaidPerHour ?: JSONObject.NULL)
            .put("skipped_over_paid_cap", skippedCap)
            .put("change_check", if (spec.camera != null) spec.local.enabled else JSONObject.NULL)

        fun json(full: Boolean): JSONObject = JSONObject().put("watch_id", id).put("label", spec.label)
            .put("user_paused",userPaused).put("created_at", isoAt(createdAt)).put("expires_at", isoAt(expiresAt))
            .put("interval_s", intervalS).apply { if (intervalOverrideS != null) put("package_interval_s", spec.intervalS) }
            .put("paused", if (budgetPaused()) "applet_budget" else JSONObject.NULL)
            .apply { if (budgetPaused()) put("paused_until", isoAt(budgetPausedUntil)) }
            .put("data", JSONArray(spec.data)).put("checks", checks).put("matches", matches)
            .put("last_match_at", if (lastMatchAt > 0) isoAt(lastMatchAt) else JSONObject.NULL)
            .put("readings_held", synchronized(readings) { readings.size })
            .put("last_check", synchronized(recent) { recent.lastOrNull() } ?: JSONObject.NULL)
            .put("usage", usage())
            .apply {
                if (spec.sound != null) put("listening", listening ?: JSONObject.NULL).put("not_listening_reason", notListeningReason ?: JSONObject.NULL)
                if (spec.tally != null) put("tally_today", todayLine() ?: JSONObject.NULL).put("rules_fired", rulesFired)
                if (spec.people != null) peopleStatus?.invoke(this@Watch)?.let { put("people", it) }
                if (full) {
                    tallySummary()?.let { put("tally", it) }
                    put("spec", spec.json())
                    put("recent_checks", JSONArray(synchronized(recent) { recent.toList() }))
                    put("last_state", lastState ?: JSONObject.NULL)
                }
            }
    }

    class Refused(val code: String, message: String, val extra: Map<String, Any?> = emptyMap()) : Exception(message)

    companion object {
        /** The `people` block of a people watch's status; set by [Watches] (a Watch has no app of its own). */
        @Volatile internal var peopleStatus: ((Watch) -> JSONObject)? = null
        const val PEOPLE_REFUSED_CODE = "agent_not_allowed"
        /** `source` of a check or reading made from a photo the owner took (Camera tab, Portal camera). */
        const val OWNER_SOURCE = "owner"
        const val PEOPLE_REFUSED = "people can only be set in an installed applet's own plan, with the people_seen permission the owner approved on the phone; not through POST /v1/watches or a card action."
    }

    private val prefs = app.getSharedPreferences("watches", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sampler = WatchSampler(app)
    /** People watches: bursts, faces and the queue for the applet's portal. */
    val people = WatchPeople(app, sampler).also { p -> peopleStatus = { w -> p.status(w) } }
    private val watches = LinkedHashMap<String, Watch>()
    /** One JSON file per tally watch: weeks of episodes don't belong in SharedPreferences. */
    private val tallyDir = java.io.File(app.filesDir, "watch-tallies")
    /** Held while any watch runs: with the screen off the CPU would sleep and the checks would stop. */
    private val wake = (app.getSystemService(android.os.PowerManager::class.java)).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "bridgit:watches")
        .apply { setReferenceCounted(false) }

    private fun updateWake() = synchronized(watches) {
        val until = watches.values.filter { !it.userPaused }.maxOfOrNull { it.expiresAt }
        if (until == null) { if (wake.isHeld) wake.release() }
        else wake.acquire((until - System.currentTimeMillis()).coerceIn(60_000L, WatchRules.MAX_EXPIRES_TALLY_S * 1000L + 60_000L))
    }

    fun start() {
        val now = System.currentTimeMillis()
        for ((id, raw) in prefs.all) {
            val o = runCatching { JSONObject(raw as String) }.getOrNull()
            val spec = o?.optJSONObject("request")?.let { runCatching { WatchRules.parse(it) }.getOrNull() }
            if (o == null || spec == null) {
                forget(id); app.callbacks.removeWatchSecret(id)
                // E.g. an update changed the rules: say so rather than dropping it silently.
                o?.optString("agent_id")?.takeIf { app.agents.get(it) != null }?.let { agentId ->
                    app.interactions.event(agentId, "watch.ended", JSONObject().put("watch_id", id).put("reason", "rejected")
                        .put("message", "After a Bridgit update this watch no longer fits the rules. Start it again with POST /v1/watches."))
                }
                continue
            }
            val w = Watch(id, o.getString("agent_id"), o.optString("agent"), o.getJSONObject("request"), spec, o.getLong("created_at"), o.getLong("expires_at"))
            w.userPaused=o.optBoolean("user_paused"); w.checks = o.optInt("checks"); w.matches = o.optInt("matches"); w.lastMatchAt = o.optLong("last_match_at")
            w.checksLocal = o.optInt("checks_local"); w.checksPaid = o.optInt("checks_paid"); w.paidCostUsd = o.optDouble("paid_cost_usd", 0.0)
            w.skippedCap = o.optInt("skipped_over_paid_cap"); w.rulesFired = o.optInt("rules_fired")
            w.budgetPausedUntil = o.optLong("budget_paused_until").takeIf { it > now } ?: 0L
            w.intervalOverrideS = o.optInt("interval_s_override", 0).takeIf { it > 0 }
            if (spec.tally != null) w.tally = loadTally(id) ?: WatchTally.State(w.createdAt)
            synchronized(watches) { watches[id] = w }
            if (now >= w.expiresAt && !renewed(w)) end(w, "expired", "The watch ran its time.") else w.job = scope.launch { loop(w) }
        }
        val live = synchronized(watches) { watches.keys.toSet() }
        app.callbacks.watchSecretIds().filter { it !in live }.forEach { app.callbacks.removeWatchSecret(it) }
        tallyDir.listFiles()?.filter { it.nameWithoutExtension !in live }?.forEach { it.delete() }
        updateWake()
        app.agents.addListener { onAgentsChanged() }
    }

    /**
     * Says whether an expiring watch should run for another lifetime: true for an installed applet's watch while
     * the applet is active (set by BridgeApp before [start]). Null: watches end when their time is up.
     */
    @Volatile var renewer: ((Watch) -> Boolean)? = null
    /** Whether to keep [Watch.lastPhoto] for this watch (see there). Null = never. */
    @Volatile var photoWanted: ((Watch) -> Boolean)? = null

    private fun renewed(w: Watch): Boolean {
        if (runCatching { renewer?.invoke(w) }.getOrNull() != true) return false
        w.expiresAt = System.currentTimeMillis() + w.spec.expiresS * 1000L
        save(w); updateWake()
        app.log.system("diagnostic", "watch", "Watch ${w.id} of installed applet ${w.agentName} renewed until ${isoAt(w.expiresAt)}")
        return true
    }

    fun list(agentId: String): List<Watch> = synchronized(watches) { watches.values.filter { it.agentId == agentId } }

    fun get(agentId: String, id: String): Watch? = synchronized(watches) { watches[id]?.takeIf { it.agentId == agentId } }

    /** Paid checks an hour (at most) across the phone, so new watches can't push Jev and Clef over the per-install limit. */
    private fun checksPerHour(): Int = synchronized(watches) { watches.values.sumOf { it.spec.paidPerHour } }

    /** Why [agentId] can't run a watch reading [data] right now, or null. */
    fun accessProblem(agentId: String, data: List<String>, camera:String?=null): Pair<String, String>? {
        val a = app.agents.get(agentId) ?: return "agent_not_allowed" to "Your agent was removed."
        if (!a.allows("watches")) return "agent_not_allowed" to "The owner hasn't given you Watches."
        for (cap in WatchRules.grantsFor(data,camera)) {
            if (!a.allows(cap)) return "agent_not_allowed" to "Reading ${data.filter { WatchRules.ELEMENTS[it] == cap }.joinToString()} needs the $cap permission, which the owner hasn't given you."
            if (!app.settings.isOn(cap)) return "capability_disabled" to "Reading ${data.filter { WatchRules.ELEMENTS[it] == cap }.joinToString()} needs the ${app.registry.byId(cap)?.title ?: cap} switch, which is off on the phone."
        }
        if(camera?.startsWith("lan:")==true && runCatching { app.lanCameras.get(camera) }.isFailure) return "agent_not_allowed" to "The selected camera was removed."
        return null
    }

    fun accessProblem(agentId: String, spec: WatchRules.Spec): Pair<String,String>? {
        val microphones=spec.sound?.sources.orEmpty().filter { it!="builtin" }
        if (microphones.isNotEmpty()) {
            val live=app.agents.get(agentId)
            for (cap in listOf("lan_camera","lan_camera_audio")) {
                if (live==null || live.paused || !live.allows(cap)) return "agent_not_allowed" to "Camera microphone access requires $cap."
                if (!app.settings.isOn(cap)) return "capability_disabled" to "Enable $cap for the selected camera microphones."
            }
            if (microphones.any { id -> app.lanCameras.all().none { it.id==id } })
                return "agent_not_allowed" to "A selected camera microphone was removed."
        }
        return (listOf(spec.camera) + spec.additionalCameras).firstNotNullOfOrNull { accessProblem(agentId, spec.data, it) }
    }

    /** Starts a watch; returns it and, if it has its own callback_url, the secret that signs deliveries (shown once). */
    fun create(agentId: String, agentName: String, request: JSONObject, spec: WatchRules.Spec, peopleApproved: Boolean = false, initiallyPaused: Boolean = false): Pair<Watch, String?> {
        accessProblem(agentId, spec)?.let { (code, msg) -> throw Refused(code, msg, if (code == "capability_disabled") mapOf("setting" to (listOf(spec.camera) + spec.additionalCameras).flatMap { WatchRules.grantsFor(spec.data,it) }.firstOrNull { !app.settings.isOn(it) }) else emptyMap()) }
        spec.callbackUrl?.let { url ->
            val c = CallbackRules.check(url, CallbackRules.tailnetDomain(app.listener.value.magicDns), app.settings.allowInternetCallbacks)
            if (c is CallbackRules.Check.Bad) throw Refused("invalid_parameter", "callback_url: ${c.reason}")
        }
        // Faces of people who aren't the owner: only an installed applet's own plan (AppletInstances.build) with
        // people_seen among its owner-approved permissions; never POST /v1/watches or a card action, even an applet's.
        if (spec.people != null && (!peopleApproved || app.agents.get(agentId)?.connection != cc.bridgit.app.core.Connection.APPLET))
            throw Refused(PEOPLE_REFUSED_CODE, PEOPLE_REFUSED)
        val now = System.currentTimeMillis()
        val w: Watch
        synchronized(watches) {
            if (watches.values.count { it.agentId == agentId } >= WatchRules.MAX_PER_AGENT)
                throw Refused("too_many_open", "You have ${WatchRules.MAX_PER_AGENT} watches running. Delete one first (DELETE /v1/watches/{id}).")
            val used = checksPerHour()
            val trust = runCatching { cc.bridgit.app.feedback.InstallIdentity.status(app)?.trust }.getOrNull()
            // The account's limit when the phone knows it (GET /v1/limits), else this build's default.
            cc.bridgit.app.api.AccountLimits.refreshSoon(app)
            val cap = cc.bridgit.app.api.AccountLimits.watchPaidChecksPerHour(trust)
            if (spec.paidPerHour > 0 && used + spec.paidPerHour > cap)
                throw Refused("rate_limited", "The phone's watches could make ${used + spec.paidPerHour} paid checks an hour; the limit is $cap" +
                    " for this phone's account (GET /v1/limits)" +
                    ". Use a longer interval_s, set max_paid_per_hour, or delete a watch.",
                    mapOf("checks_per_hour_used" to used, "checks_per_hour_limit" to cap, "retry_after_s" to 600))
            w = Watch("w" + UUID.randomUUID().toString().replace("-", "").take(12), agentId, agentName, request, spec, now, now + spec.expiresS * 1000L)
            w.userPaused = initiallyPaused || spec.startPaused
            if (spec.tally != null) w.tally = WatchTally.State(now)
            watches[w.id] = w
        }
        val secret = spec.callbackUrl?.let { app.callbacks.newWatchSecret(w.id) }
        save(w); saveTally(w)
        app.log.system("audit", "watch", "$agentName started watch ${w.id}: \"${spec.label}\" every ${spec.intervalS} s (${spec.data.joinToString()})" +
            (spec.tally?.let { " counting ${it.track}, ${it.rules.size} rules" } ?: ""))
        w.job = scope.launch { loop(w) }
        updateWake()
        return w to secret
    }

    /** A label or sampling edit preserves the original watch, including its private tally and budget state. */
    fun retainsTally(w: Watch, request: JSONObject): Boolean {
        if (w.spec.tally == null || w.spec.sound != null) return false
        fun content(raw: JSONObject) = JSONObject(raw.toString()).apply { remove("interval_s"); remove("label") }
        return cc.bridgit.app.applets.AppletEvolution.same(content(w.request),content(request))
    }

    internal suspend fun reviseTally(agent: String, id: String, request: JSONObject, spec: WatchRules.Spec): Boolean {
        val w = get(agent,id) ?: return false
        return w.checkLock.withLock {
            synchronized(watches) {
                if (watches[id] !== w || !retainsTally(w,request)) return@synchronized false
                val trust = runCatching { cc.bridgit.app.feedback.InstallIdentity.status(app)?.trust }.getOrNull()
                val cap = cc.bridgit.app.api.AccountLimits.watchPaidChecksPerHour(trust)
                if (spec.paidPerHour > w.spec.paidPerHour && checksPerHour() - w.spec.paidPerHour + spec.paidPerHour > cap)
                    throw Refused("rate_limited","The proposed sampling interval exceeds this phone's paid watch limit.")
                val tally = requireNotNull(w.tally)
                synchronized(tally) { WatchTally.preserveInterval(tally,w.intervalS) }
                saveTally(w,required=true)
                w.revise(request,spec)
                save(w)
                w.nudge.trySend(Unit)
                true
            }
        }
    }

    /** Ends [agentId]'s watch [id]; false if there's none. */
    fun delete(agentId: String, id: String): Boolean {
        val w = get(agentId, id) ?: return false
        end(w, "deleted", "You deleted the watch.")
        return true
    }

    /** Every running people watch (the debug hook). */
    /** Watches that check photos (not sound), for the owner's Camera screen. */
    fun photoWatches(): List<Watch> = synchronized(watches) { watches.values.filter { it.spec.camera != null && it.spec.sound == null } }
    fun peopleWatches(): List<Watch> = synchronized(watches) { watches.values.filter { it.spec.people != null } }

    /** Every running watch with a tally, for the owner's list (Applets → Applets). */
    fun tallies(): List<Watch> = synchronized(watches) { watches.values.filter { it.spec.tally != null } }

    /** The owner stops a watch from the phone. */
    fun stopByOwner(id: String): Boolean {
        val w = synchronized(watches) { watches[id] } ?: return false
        end(w, "stopped_by_owner", "The owner stopped this watch on the phone.")
        return true
    }

    private fun onAgentsChanged() {
        for (w in synchronized(watches) { watches.values.toList() }) {
            accessProblem(w.agentId, w.spec)?.let { (_, msg) -> end(w, "access_revoked", msg) }
        }
    }

    /** The watch's [cc.bridgit.app.sound.LoudSounds] subscription id. */
    private fun soundId(w: Watch) = "watch:${w.id}"
    /** The agent of a running watch, for the listener's notification ("For …"). */
    fun agentNameOf(id: String): String? = synchronized(watches) { watches[id]?.agentName }

    private suspend fun loop(w: Watch) {
        if (w.spec.sound != null) { soundLoop(w); return }
        while (scope.isActive) {
            if (synchronized(watches) { watches[w.id] } !== w) return
            val now = System.currentTimeMillis()
            if (now >= w.expiresAt && !renewed(w)) { end(w, "expired", "The watch ran its time without a match."); return }
            accessProblem(w.agentId, w.spec)?.let { (_, msg) -> end(w, "access_revoked", msg); return }
            budgetExpired(w, now)
            val started = System.currentTimeMillis()
            try { if(!w.userPaused && !w.budgetPaused(started) &&
                app.applets.forAgent(w.agentId)?.let { cc.bridgit.app.applets.AppletLifecycle.state(it) != "running" } != true) tick(w) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                app.log.system("diagnostic", "watch", "Watch ${w.id} check failed: ${e.javaClass.simpleName}", level = "warn")
            }
            // The interval may change while waiting (the applet's action): the wait follows it, from this check's start.
            var first = true
            while (true) {
                val t = System.currentTimeMillis()
                // Paused for the budget: look again each minute (expiry, access), and run at once when it lifts.
                val dueAt = if (w.budgetPaused(t)) minOf(w.budgetPausedUntil, t + 60_000L) else started + w.intervalS * 1000L
                val wait = if (first) (dueAt - t).coerceAtLeast(1000L) else dueAt - t
                first = false
                if (wait <= 0) break
                if (kotlinx.coroutines.withTimeoutOrNull(wait) { w.nudge.receive() } == null) break
            }
        }
    }

    /**
     * A sound watch has no interval: it subscribes to loud sounds ([soundTick] per sound) and only checks expiry
     * and access here. Paused, it lets go of the microphone.
     */
    private suspend fun soundLoop(w: Watch) {
        val opts = w.spec.sound ?: return
        val id = soundId(w)
        try {
            while (scope.isActive) {
                if (synchronized(watches) { watches[w.id] } !== w) return
                if (System.currentTimeMillis() >= w.expiresAt && !renewed(w)) { end(w, "expired", "The watch ran its time."); return }
                accessProblem(w.agentId, w.spec)?.let { (_, msg) -> end(w, "access_revoked", msg); return }
                if (!w.userPaused) dueOnly(w, System.currentTimeMillis())
                if (w.userPaused || opts.sources.isEmpty()) app.wake.loud.unsubscribe(id)
                else app.wake.loud.subscribe(cc.bridgit.app.sound.LoudSounds.Subscriber(id, opts.sensitivity,opts.sources.toSet(), allows = { source ->
                    !w.userPaused && synchronized(watches) { watches[w.id] } === w && source in opts.sources && accessProblem(w.agentId,w.spec)==null
                }) { source, wav, dur, at ->
                    val captured=if(source=="builtin") null else app.lanCameras.all().firstOrNull { it.id==source }
                    scope.launch {
                        try { w.soundLock.withLock { soundTick(w, source, captured, wav, dur, at) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            app.log.system("diagnostic", "watch", "Watch ${w.id} sound check failed: ${e.javaClass.simpleName}", level = "warn")
                        }
                    }
                })
                delay(1_000) // let the listener take the subscription before reporting it
                w.listening = !w.userPaused && app.wake.loud.get(id) != null && opts.sources.any {
                    if(it=="builtin") app.wake.running?.loud == true else app.cameraMics.status(it).optString("state")=="listening"
                }
                w.notListeningReason = when {
                    w.userPaused || w.listening == true -> null
                    opts.sources.isEmpty() -> "no_microphone_selected"
                    "builtin" !in opts.sources -> "camera_microphone_unavailable"
                    else -> app.wake.micMissing() ?: "listener_starting"
                }
                delay(29_000)
            }
        } finally {
            if (w.userPaused || synchronized(watches) { watches[w.id] } !== w) app.wake.loud.unsubscribe(id)
        }
    }

    /** Recheck the live grant on every signed attempt, including a fallback to an owner's service key. */
    private fun authorizeSample(w: Watch) {
        if (w.userPaused || synchronized(watches) { watches[w.id] } !== w)
            throw Jev.Failure("access_revoked", "The watch stopped before this sample could be sent.")
        accessProblem(w.agentId, w.spec)?.let { (_,reason) -> throw Jev.Failure("access_revoked", reason) }
    }

    /** One loud sound for a sound watch: reuse the last answer (same episode), skip (cap or pause), or ask Clef. */
    private suspend fun soundTick(w: Watch, source: String, captured: cc.bridgit.app.lancamera.LanCamera?, wav: ByteArray, durationMs: Long, at: Long) {
        val opts = w.spec.sound ?: return
        fun eligible() {
            authorizeSample(w)
            if (source!="builtin") {
                val current=app.lanCameras.all().firstOrNull { it.id==source }
                if (captured==null || current==null || captured.host!=current.host || captured.username!=current.username || captured.password!=current.password)
                    throw Jev.Failure("access_revoked","The microphone enrollment changed.")
            }
        }
        eligible()
        if (w.userPaused || synchronized(watches) { watches[w.id] } !== w) return
        budgetExpired(w, System.currentTimeMillis())
        if (w.budgetPaused()) return
        accessProblem(w.agentId, w.spec)?.let { (_, msg) -> end(w, "access_revoked", msg); return }
        val now = System.currentTimeMillis()
        w.lastCheckAt = now
        val seconds = Math.round(durationMs / 100.0) / 10.0
        val reading = JSONObject().put("at_ms", at).put("sound", JSONObject().put("source", source).put("heard", true).put("duration_seconds", seconds).put("sensitivity", opts.sensitivity))
        synchronized(w.readings) { w.readings.clear(); w.readings.addLast(reading) }
        val check = JSONObject().put("at", isoAt(now)).put("readings", 1).put("model", w.spec.model).put("sound_seconds", seconds)
        val state = soundState(w, reading, now)
        w.lastState = state
        val cap = w.spec.maxPaidPerHour ?: cc.bridgit.app.sound.SoundRules.DEFAULT_CHECKS_PER_HOUR
        val last = w.soundAnswer.takeIf { w.soundSource == source }
        val print = cc.bridgit.app.sound.SoundPrint.of(wav)
        val similar = last != null && print != null && print.similar(w.soundPrint)
        print?.let { p -> w.soundPrint?.let { check.put("similarity", Math.round(p.cosine(it) * 100) / 100.0) } }
        when (cc.bridgit.app.sound.SoundRules.decide(at, if (last != null) w.soundAt else 0L, opts.reuseMs, similar, w.paidLastHour(now), cap, w.pausedUntil)) {
            cc.bridgit.app.sound.SoundRules.Decision.COUNT -> {
                val ago = (at - w.soundAt).coerceAtLeast(0) / 1000
                w.checks++; w.checksLocal++; w.soundAt = maxOf(w.soundAt, at)
                val matched = WatchRules.matches(w.spec.conditions, last!!)
                remember(w, check.put("answers", last.answers).put("matched", matched).put("model", last.model).put("paid", false)
                    .put("answered_locally", "this sound sounds like the one checked $ago s before (compared on the phone), so that answer was reused"))
                counted(w, last, at)
                if (matched && WatchRules.callbackDue(w.spec, w.lastMatchAt, now)) onMatch(w, last, state, now, local = true)
                save(w); return
            }
            cc.bridgit.app.sound.SoundRules.Decision.SKIP -> {
                w.checks++; w.skippedCap++
                val paused = now < w.pausedUntil
                remember(w, check.put("skipped", if (paused) "paused" else "max_paid_per_hour").put("paid", false)
                    .put("message", if (paused) "Paid checks are paused for a while (the model was busy or over its limit)." else "$cap paid checks in the last hour already (max_paid_per_hour); this sound wasn't checked."))
                counted(w, null, at)
                save(w); return
            }
            cc.bridgit.app.sound.SoundRules.Decision.ASK -> {}
        }
        val hint = Outbound.Hint("Cloudflare Clef (via Bridgit team backend)", "Watch ${w.id}: a loud sound clip and the agent's question",
            listOf(Categories.QUESTIONS, Categories.AGENT_STATE, Categories.AUDIO), action = budgetOf(w)?.second)
        val answer = try {
            withContext(Dispatchers.IO) { Outbound.on(Outbound.Actor.agent(w.agentId, w.agentName), hint) {
                Jev.ask(app, state, w.spec.questions, route = Jev.CLEF_ROUTE, audio = listOf(cc.bridgit.app.sound.SoundCheck.dataUrl(wav)), authorized = ::eligible)
            } }
        } catch (f: Jev.Failure) { failed(w, f, check, at, now); return }
        w.checks++; w.failures = 0
        w.paidCall(now, answer.costUsd)
        val matched = WatchRules.matches(w.spec.conditions, answer)
        if (at >= w.soundAt) { w.soundAt = at; w.soundAnswer = answer; w.soundPrint = print; w.soundSource = source }
        remember(w, check.put("answers", answer.answers).put("matched", matched).put("model", answer.model).put("paid", true).put("cost_usd", answer.costUsd))
        counted(w, answer, at)
        if (matched && WatchRules.callbackDue(w.spec, w.lastMatchAt, now)) onMatch(w, answer, state, now, local = false)
        save(w)
    }

    /** A sound watch in silence: rules like none_for_s still fire on time (no check is counted). */
    private fun dueOnly(w: Watch, now: Long) {
        val t = w.spec.tally ?: return
        val st = w.tally ?: return
        val fired = synchronized(st) { WatchTally.due(t, st, now, ZoneId.systemDefault(), w.intervalS) }
        for (f in fired) fire(w, f, now)
        if (fired.isNotEmpty()) saveTally(w)
    }

    /** What Clef is told about a loud sound: the agent's context, the local time, and the clip is the attached audio. */
    private fun soundState(w: Watch, reading: JSONObject, now: Long): JSONObject {
        val zone = ZoneId.systemDefault()
        val iso = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val heard = reading.optLong("at_ms", now)
        return JSONObject()
            .put("about", "The owner's Android phone heard a loud sound; the attached audio is that clip (from just before it started). " +
                "Answer only from what can be heard in it.")
            .apply { w.spec.context?.let { put("context", it) } }
            .put("now", iso.format(java.time.Instant.ofEpochMilli(now).atZone(zone)))
            .put("heard_at", iso.format(java.time.Instant.ofEpochMilli(heard).atZone(zone)))
            .put("sound", reading.getJSONObject("sound"))
    }

    /** A model call failed: count the gap and decide whether the watch goes on (shared by photo and sound checks). */
    internal fun failed(w: Watch, f: Jev.Failure, check: JSONObject, at: Long, now: Long) {
        w.checks++
        remember(w, check.put("error", f.reason).put("message", f.message))
        counted(w, null, at)
        when {
            f.reason == "service_budget" -> { w.pausedUntil = f.resetsAt?.takeIf { it > now } ?: now + WatchRules.RATE_LIMIT_PAUSE_MS }
            f.reason == "spend_cap" -> end(w, "budget", f.message ?: "The Jev and Clef budget is used up.")
            // The applet's budget (all phones) is used up: every watch of the applet pauses until it starts over.
            f.reason == "applet_budget" || f.reason == "budget_reached" -> {
                val until = f.resetsAt?.takeIf { it > now } ?: cc.bridgit.app.applets.AppletBudget.nextReset(now)
                budgetPause(w.agentId, until, f.message ?: "The applet's budget is used up.")
            }
            f.reason in setOf("jev_rejected", "clef_rejected", "invalid_parameter", "invalid_json", "not_configured", "refused") ->
                end(w, "rejected", "The model refused the question: ${f.message}")
            // Over the hourly limit, busy or unreachable: keep reading and try again; only the expiry ends it.
            f.retryable -> {
                if (f.reason == "rate_limited" || f.http == 429) w.pausedUntil = now + WatchRules.RATE_LIMIT_PAUSE_MS
                app.log.system("diagnostic", "watch", "Watch ${w.id}: ${w.spec.model} unavailable (${f.reason}), trying again later", level = "warn")
            }
            ++w.failures >= WatchRules.MAX_FAILURES -> end(w, "failing", "${WatchRules.MAX_FAILURES} checks in a row failed; the last: ${f.message}")
        }
        save(w)
    }

    private suspend fun tick(w: Watch) {
        // A people watch takes no photo at all while its portal can't take faces or the battery is low.
        if (w.spec.people != null && !people.beforeCheck(w)) {
            w.reference = null // the next photo starts afresh, so a stale picture isn't counted as a change
            w.lastCheckAt = System.currentTimeMillis()
            return
        }
        val camera = w.nextCamera()
        val sample = sampler.read(w.spec.data, w.lastSteps, camera, "Watch: ${w.spec.label}",w.agentId)
        w.checkLock.withLock {
            w.sampleCamera = camera
            checkSample(w, sample)
        }
    }

    /** The rest of [tick] once the reading is taken (under the watch's [Watch.checkLock]). */
    private suspend fun checkSample(w: Watch, sample: WatchSampler.Sample) {
        w.lastSteps = sample.steps
        val shotAt = System.currentTimeMillis()
        w.lastCheckAt = shotAt
        val grid = sample.photo?.let { Grids.fromJpeg(it) }
        w.pendingPhoto = sample.photo?.takeIf { photoWanted?.invoke(w) == true }
        if (w.pendingPhoto == null && w.lastPhoto != null && photoWanted?.invoke(w) != true) { w.lastPhoto = null; w.lastPhotoCheck = null }
        // How much of the picture changed since the photo it's compared with (free, on the phone).
        if (grid != null) sample.reading.optJSONObject("camera")?.let { c ->
            val share = if (w.spec.model == "none") w.reference?.let { FrameDiff.largestPatch(grid, it, cellChange = w.spec.local.cellChange) }
                else if (w.spec.local.enabled) w.gate.changeShare(grid, shotAt) else null
            share?.let { c.put("changed_share", Math.round(it * 1000) / 1000.0) }
        }
        val readings = synchronized(w.readings) {
            w.readings.addLast(sample.reading)
            while (w.readings.size > w.spec.window) w.readings.removeFirst()
            w.readings.toList()
        }
        val now = System.currentTimeMillis()
        if (!WatchRules.shouldAsk(w.spec, readings.size, w.lastMatchAt, now, w.pausedUntil)) return
        val check = JSONObject().put("at", isoAt(now)).put("readings", readings.size).put("model", w.spec.model)
            .apply { w.sampleCamera?.let { put("camera",it) } }
        if (w.spec.camera != null && sample.photo == null) {
            // No photo, no point paying: try again next reading. Not a failure (a locked phone isn't the watch's fault).
            val why = sample.reading.optJSONObject("camera")?.optString("reason").orEmpty()
            remember(w, check.put("error", "no_photo").put("message", "No photo this time${if (why.isNotEmpty()) ": $why" else ""}.").put("paid", false))
            counted(w, null, now)
            return
        }
        if (w.spec.model == "none") {
            val changed = pictureChanged(w, grid, readings, now, check)
            if (w.spec.people != null) people.afterCheck(w, sample.photo, shotAt, changed)
            return
        }

        val reused = if (w.spec.local.enabled && w.spec.camera != null) w.gate.reusable(grid, now, w.spec.model) else null
        if (reused != null) {
            w.checks++; w.checksLocal++
            val state = WatchRules.state(w.spec, readings, now, ZoneId.systemDefault())
            w.lastState = state
            val matched = WatchRules.matches(w.spec.conditions, reused)
            remember(w, check.put("answers", reused.answers).put("matched", matched).put("model", reused.model).put("paid", false)
                .put("answered_locally", "the picture hasn't changed since the last paid check, so its answer was reused"))
            counted(w, reused, now)
            if (matched && WatchRules.callbackDue(w.spec, w.lastMatchAt, now)) onMatch(w, reused, state, now, local = true)
            save(w)
            return
        }
        w.spec.maxPaidPerHour?.let { cap ->
            if (w.paidLastHour(now) >= cap) {
                w.checks++; w.skippedCap++
                remember(w, check.put("skipped", "max_paid_per_hour").put("paid", false)
                    .put("message", "$cap paid checks in the last hour already (max_paid_per_hour); no model was asked."))
                counted(w, null, now)
                save(w)
                return
            }
        }

        askModel(w, sample.photo, readings, check, now, grid)
    }

    /**
     * A photo the owner took with the Camera screen (or the Portal's camera), checked by watch [w] the way its own
     * photos are, right away. Not a change-check photo: it may be from any angle, so it never trains or resets the
     * picture-change check, and it doesn't wait for the readings window. The hourly paid cap and the model's
     * rate-limit pause still apply. [jpeg] is already shrunk ([WatchSampler.PHOTO]); [faces] is the larger copy
     * the people watch looks for faces in. Returns the check as stored in the watch's recent checks.
     */
    suspend fun ownerPhoto(w: Watch, jpeg: ByteArray, faces: ByteArray, camera: String, width: Int, height: Int): JSONObject = w.checkLock.withLock {
        val now = System.currentTimeMillis()
        val reading = JSONObject().put("at_ms", now).put("camera", JSONObject().put("taken", true).put("camera", camera)
            .put("width_px", width).put("height_px", height).put("source", OWNER_SOURCE))
        val check = JSONObject().put("at", isoAt(now)).put("model", w.spec.model).put("source", OWNER_SOURCE)
        // The watch may have ended, or lost its access, since the owner's screen listed it.
        if (synchronized(watches) { watches[w.id] } !== w) return@withLock check.put("skipped", "ended").put("paid", false).put("message", "The watch has ended.")
        accessProblem(w.agentId, w.spec)?.let { (_, msg) -> return@withLock check.put("skipped", "access").put("paid", false).put("message", msg) }
        if (w.spec.people != null) {
            if (!people.beforeCheck(w) || people.captureState(w) != PeopleRules.ON)
                return@withLock check.put("skipped", "people_off").put("paid", false)
                    .put("message", "People spotting is ${people.words(w)}.")
            people.burst(w, faces, now, force = false, size = 1)
            return@withLock check.put("people", "checked").put("paid", false)
        }
        if (w.spec.model == "none")
            return@withLock check.put("skipped", "change_only").put("paid", false).put("message", "This watch only looks for picture changes.")
        if (w.userPaused) return@withLock check.put("skipped", "paused").put("paid", false).put("message", "The watch is paused.")
        if (w.budgetPaused(now)) return@withLock check.put("skipped", "applet_budget").put("paid", false)
            .put("message", "The applet's budget is used up until ${isoAt(w.budgetPausedUntil)}.")
        if (now < w.pausedUntil) return@withLock check.put("skipped", "model_busy").put("paid", false)
            .put("message", "Paid checks are paused for a while (the model was busy or over its limit).")
        w.spec.maxPaidPerHour?.let { cap ->
            if (w.paidLastHour(now) >= cap) return@withLock check.put("skipped", "max_paid_per_hour").put("paid", false)
                .put("message", "$cap paid checks in the last hour already; this photo wasn't checked.")
        }
        val readings = synchronized(w.readings) { w.readings.toList().takeLast((w.spec.window - 1).coerceAtLeast(0)) } + reading
        w.pendingPhoto = jpeg.takeIf { photoWanted?.invoke(w) == true }
        askModel(w, jpeg, readings, check.put("readings", readings.size), now, grid = null)
        check
    }

    /** The paid model call shared by the watch's own photos and the owner's ([grid] null: don't train the change check). */
    private suspend fun askModel(w: Watch, photo: ByteArray?, readings: List<JSONObject>, check: JSONObject, now: Long, grid: IntArray?) {
        val clef = w.spec.model == "clef"
        val ref = if (photo != null && !clef) "photo-${w.id}-${UUID.randomUUID().toString().take(8)}" else null
        val state = WatchRules.state(w.spec, readings, now, ZoneId.systemDefault(),
            photo = if (photo == null) null else ref ?: "the image sent before this state")
        w.lastState = state
        val service = if (w.spec.model == "glm") "GLM 5.3 Flash (OpenRouter via Bridgit)" else if (clef) "Cloudflare Clef (via Bridgit team backend)" else "TypeSafe Jev (via Bridgit team backend)"
        val hint = Outbound.Hint(service, "Watch ${w.id}: phone readings and the agent's question",
            listOf(Categories.QUESTIONS, Categories.AGENT_STATE) + (if ("location" in w.spec.data) listOf(Categories.LOCATION_EXACT) else emptyList()) +
                (if ("battery" in w.spec.data) listOf(Categories.BATTERY) else emptyList()) + (if (photo != null) listOf(Categories.IMAGE) else emptyList()),
            action = budgetOf(w)?.second)
        val answer = try {
            withContext(Dispatchers.IO) { Outbound.on(Outbound.Actor.agent(w.agentId, w.agentName), hint) {
                Jev.ask(app, state, w.spec.questions, route = WatchRules.route(w.spec.model),
                    assets = if (ref != null && photo != null) JSONObject().put(ref, Jev.image(photo)) else null,
                    images = if (clef && photo != null) listOf(Jev.dataUrl(photo)) else null, authorized = { authorizeSample(w) })
            } }
        } catch (f: Jev.Failure) { failed(w, f, check, now, now); return }
        w.checks++; w.failures = 0
        w.paidCall(now, answer.costUsd)
        // A paid call for a change that meant nothing teaches the change check to ignore those spots for a while.
        val learned = grid != null && w.spec.camera != null && w.spec.local.enabled && w.gate.answered(grid, now, w.spec.model, answer, WatchRules::sameAnswer)
        val matched = WatchRules.matches(w.spec.conditions, answer)
        remember(w, check.put("answers", answer.answers).put("matched", matched).put("model", answer.model).put("paid", true)
            .put("cost_usd", answer.costUsd).apply { if (learned) put("learned_noise", true) })
        counted(w, answer, now)
        if (matched && WatchRules.callbackDue(w.spec, w.lastMatchAt, now)) onMatch(w, answer, state, now, local = false)
        save(w)
    }

    /**
     * A tally watch's check: count [answer] (null = no usable answer, a gap in watched time), then run the
     * rules that are due. Saves the tally when an episode ends, a rule fires, or once a minute.
     */
    private fun counted(w: Watch, answer: Jev.Answer?, now: Long) {
        val t = w.spec.tally ?: return
        val st = w.tally ?: return
        val zone = ZoneId.systemDefault()
        val (closed, fired) = synchronized(st) {
            WatchTally.record(t, st, now, answer, zone, w.intervalS) to WatchTally.due(t, st, now, zone, w.intervalS)
        }
        for (f in fired) fire(w, f, now)
        if (closed.isNotEmpty() || fired.isNotEmpty() || now - w.tallySavedAt >= 60_000L) saveTally(w)
    }

    /** A refused call (4xx: not allowed, switched off, bad request), unlike a timeout, a busy 409 or 429, or a 5xx. */
    private fun permanent(result: JSONObject): Boolean {
        val status = result.optInt("status")
        val error = result.optJSONObject("body")?.optString("error").orEmpty().ifEmpty { result.optString("error") }
        return status in 400..499 && status != 408 && status != 429 && !(status == 409 && error.contains("busy"))
    }

    /** A rule fired: tell the agent (watch.rule), and run its `do` call on the phone as the agent. */
    private fun fire(w: Watch, f: WatchTally.Fired, now: Long) {
        w.rulesFired++
        app.log.system("audit", "watch", "Watch ${w.id} for ${w.agentName}: rule ${f.rule.id} fired: ${f.message}")
        val data = JSONObject().put("watch_id", w.id).put("label", w.spec.label).put("rule", f.rule.id).put("message", f.message)
            .put("when", f.rule.json().optJSONObject("when") ?: JSONObject()).put("fired_at", isoAt(now))
            .put("today", w.tallySummary(now)?.optJSONObject("today") ?: JSONObject.NULL)
        // The flow trigger data (docs/applet-vocabulary.md): the same fields whether a flow listens for this rule
        // ({"type":"watch","on":"rule"}) or the rule starts it (do.flow).
        val of = f.rule.`when`.label
        val today = w.tallySummary(now)?.optJSONObject("today") ?: JSONObject.NULL
        fun triggerData() = JSONObject().put("caller", "rule").put("watch_id", w.id).put("label", w.spec.label).put("rule", f.rule.id)
            .put("message", f.message).put("of", of).put("today", today).put("at", isoAt(now))
        flows(w, "rule", f.rule.id, triggerData())
        f.rule.flow?.let { ref ->
            scope.launch {
                val started = try { app.platform.workflows.start(w.agentId, ref, triggerData(), f.rule.with) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { JSONObject().put("flow", ref).put("status", "flow_not_active").put("error", "internal_error").put("message", e.message ?: e.javaClass.simpleName) }
                app.log.system("audit", "watch", "Watch ${w.id}: rule ${f.rule.id} ${if (started.optString("status") == "started") "started" else "couldn't start (${started.optString("status")})"} flow $ref")
                send(w, "watch.rule", data.put("action", started))
            }
            return
        }
        val raw = f.rule.call ?: f.rule.step?.let { cc.bridgit.app.platform.FlowSteps.inlineCall(it, triggerData(), w.spec.label) }
        if (raw == null) { send(w, "watch.rule", data.put("action", JSONObject.NULL)); return }
        val step = f.rule.step
        scope.launch {
            val agent = app.agents.get(w.agentId)
            // A single step with on_error retry tries again (2, 5, 10 s), like a step in a flow.
            val tries = 1 + (if (step?.optString("on_error") == "retry") step.optInt("retries", 1) else 0)
            var result = JSONObject()
            for (attempt in 0 until tries) {
            if (attempt > 0) delay(cc.bridgit.app.platform.FlowSteps.RETRY_DELAYS_MS[attempt - 1])
            result = if (agent == null) cc.bridgit.app.interact.CardActions.refused("unauthorized", "The agent was removed.", 401) else try {
                // A single step's text is already filled from the trigger; a raw call fills {message} and {label}.
                val c = cc.bridgit.app.interact.CardActions.parse((if (step != null) raw else WatchTally.fill(raw, f.message, w.spec.label)).toString())
                cc.bridgit.app.interact.CardActionRunner.call(app, agent, c, "watch:${w.id}", "watch",
                    JSONObject().put("watch_id", w.id).put("rule", f.rule.id), "watch rule")
            } catch (e: cc.bridgit.app.interact.CardActions.Refused) {
                cc.bridgit.app.interact.CardActions.refused(e.code, e.message ?: e.code, if (e.code == "not_found") 404 else 400)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                app.log.system("diagnostic", "crash", "Watch rule action failed: ${e.javaClass.simpleName}", level = "error")
                cc.bridgit.app.interact.CardActions.refused("internal_error", "Bridgit hit a bug running this rule's action.", 500)
            }
            // Only a passing failure is tried again: a refusal (403, permission missing, bad request) would only repeat.
            if (result.optBoolean("ok") || agent == null || permanent(result)) break
            }
            send(w, "watch.rule", data.put("action", if (step != null) JSONObject().put("step", step.optString("type"))
                .put("status", if (result.optBoolean("ok")) "ok" else "failed").put("result", result.opt("body") ?: JSONObject.NULL)
                .apply { if (!result.optBoolean("ok")) put("error", result.optJSONObject("body")?.optString("error")?.ifEmpty { null } ?: "failed") }
                else JSONObject().put("method", raw.optString("method", "POST")).put("path", raw.optString("path"))
                .put("ok", result.optBoolean("ok")).put("status", result.optInt("status")).put("body", result.opt("body") ?: JSONObject.NULL)))
        }
    }

    /** A match: tell the agent, and end the watch unless it repeats. */
    private fun onMatch(w: Watch, answer: Jev.Answer, state: JSONObject, now: Long, local: Boolean) {
        w.matches++; w.lastMatchAt = now
        app.log.system("audit", "watch", "Watch ${w.id} for ${w.agentName} matched: \"${w.spec.label}\"")
        send(w, "watch.matched", JSONObject().put("watch_id", w.id).put("label", w.spec.label)
            .put("question", w.spec.question ?: JSONObject.NULL).put("answers", answer.answers)
            .put("callback_when", JSONArray(w.spec.conditions.map { it.json() })).put("model", answer.model)
            .put("answered_locally", local).put("cost_usd", if (local) 0.0 else answer.costUsd)
            .put("checked_at", isoAt(now)).put("match", w.matches).put("repeat", w.spec.repeat).put("state", state).put("usage", w.usage(now)))
        flows(w, "match", null, cc.bridgit.app.platform.FlowSteps.answers(answer).put("watch_id", w.id).put("label", w.spec.label).put("at", isoAt(now)))
        if (!w.spec.repeat) end(w, "matched", "The answer matched; the watch is done.")
    }

    /** Runs the agent's workflows triggered by this watch (docs/applet-flows.md); never breaks the watch. */
    private fun flows(w: Watch, on: String, rule: String?, data: JSONObject) {
        runCatching { app.platform.workflows.onWatch(w.agentId, w.id, on, rule, data) }
            .onFailure { app.log.system("diagnostic", "watch", "Watch ${w.id}: couldn't start its workflows: ${it.javaClass.simpleName}", level = "error") }
    }

    /** model none: no model, free. The first photo is the reference; a solid change from it calls the agent back. */
    /** Returns whether the picture changed. */
    private fun pictureChanged(w: Watch, grid: IntArray?, readings: List<JSONObject>, now: Long, check: JSONObject): Boolean {
        if (grid == null) { remember(w, check.put("error", "no_photo").put("message", "The photo couldn't be read.").put("paid", false)); return false }
        w.checks++; w.checksLocal++
        val ref = w.reference
        if (ref == null) {
            w.reference = grid
            remember(w, check.put("paid", false).put("matched", false).put("answered_locally", "first photo: the reference later photos are compared with"))
            save(w)
            return false
        }
        val share = FrameDiff.largestPatch(grid, ref, cellChange = w.spec.local.cellChange)
        val changed = share >= w.spec.local.minPatch
        remember(w, check.put("paid", false).put("matched", changed).put("changed_share", Math.round(share * 1000) / 1000.0)
            .put("answered_locally", "change check on the phone (model none)"))
        if (changed) {
            w.reference = grid
            w.matches++; w.lastMatchAt = now
            val state = WatchRules.state(w.spec, readings, now, ZoneId.systemDefault())
            w.lastState = state
            app.log.system("audit", "watch", "Watch ${w.id} for ${w.agentName}: the picture changed")
            send(w, "watch.matched", JSONObject().put("watch_id", w.id).put("label", w.spec.label).put("model", "none")
                .put("change", JSONObject().put("largest_patch_share", Math.round(share * 1000) / 1000.0).put("sensitivity", w.spec.local.sensitivity))
                .put("answered_locally", true).put("cost_usd", 0.0)
                .put("checked_at", isoAt(now)).put("match", w.matches).put("repeat", w.spec.repeat).put("state", state).put("usage", w.usage(now)))
            flows(w, "match", null, JSONObject().put("watch_id", w.id).put("label", w.spec.label).put("changed_share", Math.round(share * 1000) / 1000.0).put("at", isoAt(now)))
            if (!w.spec.repeat) end(w, "matched", "The picture changed; the watch is done.")
        }
        save(w)
        return changed
    }

    private fun remember(w: Watch, check: JSONObject) = synchronized(w.recent) {
        runCatching { budgetOf(w)?.let { (applet, action) -> cc.bridgit.app.applets.AppletBudget.ran(app, applet, action) } }
        val photo = w.pendingPhoto
        if (photo != null && check.has("answers")) { w.lastPhoto = photo; w.lastPhotoCheck = check }
        w.pendingPhoto = null
        w.recent.addLast(check)
        while (w.recent.size > WatchRules.RECENT_CHECKS) w.recent.removeFirst()
    }

    private fun end(w: Watch, reason: String, message: String) {
        val removed = synchronized(watches) { watches.remove(w.id) === w }
        if (!removed) return
        forget(w.id)
        if (w.spec.sound != null) app.wake.loud.unsubscribe(soundId(w))
        if (w.spec.people != null) people.ended(w.id)
        val summary = w.tallySummary()
        java.io.File(tallyDir, "${w.id}.json").delete()
        updateWake()
        app.log.system("audit", "watch", "Watch ${w.id} for ${w.agentName} ended ($reason)")
        if (app.agents.get(w.agentId) != null)
            send(w, "watch.ended", JSONObject().put("watch_id", w.id).put("label", w.spec.label).put("reason", reason)
                .put("message", message).put("checks", w.checks).put("matches", w.matches).put("usage", w.usage())
                .apply { summary?.let { put("tally", it).put("rules_fired", w.rulesFired) } })
        app.callbacks.removeWatchSecret(w.id) // send() already read it for the last delivery
        w.job?.cancel() // from inside the loop this only stops it at its next suspension
    }

    /** Stores the event (for /v1/events and the agent's registered callback) and POSTs it to the watch's own URL. */
    private fun send(w: Watch, type: String, data: JSONObject) {
        val e = app.interactions.event(w.agentId, type, data)
        val url = w.spec.callbackUrl ?: return
        val secret = app.callbacks.watchSecret(w.id) ?: return
        scope.launch(Dispatchers.IO) { deliver(w, url, secret, e) }
    }

    private suspend fun deliver(w: Watch, url: String, secret: String, e: Event) {
        val host = CallbackRules.hostOf(url)
        val body = CallbackRules.body(e.toJson(::isoAt), w.agentName)
        var attempt = 1
        while (true) {
            val check = CallbackRules.check(url, CallbackRules.tailnetDomain(app.listener.value.magicDns), app.settings.allowInternetCallbacks)
            val words = if (check !is CallbackRules.Check.Ok) "not allowed" else try {
                val headers = CallbackRules.headers(secret, System.currentTimeMillis() / 1000, e.id.toString(), body, app.appVersion())
                val status = Outbound.on(Outbound.Actor.agent(w.agentId, w.agentName), Outbound.Hint("Watch callback URL", "Watch ${w.id}: ${e.type} to the agent", listOf(Categories.AGENT_STATE))) {
                    SafeHttp.post(url, check.policy, headers, body, CallbackRules.TIMEOUT_MS, check.extraPort) }
                if (CallbackRules.success(status)) {
                    app.log.system("diagnostic", "watch", "Watch callback to $host: HTTP $status (${e.type})")
                    return
                }
                "HTTP $status"
            } catch (f: SafeHttp.Failure) { f.reason.name.lowercase() } catch (x: Exception) { "error" }
            val wait = if (check is CallbackRules.Check.Ok) CallbackRules.nextDelayMs(attempt) else null
            if (wait == null) {
                app.log.system("diagnostic", "watch", "Watch callback to $host failed ($words), giving up; the event is still in /v1/events", level = "warn")
                return
            }
            app.log.system("diagnostic", "watch", "Watch callback to $host failed ($words), retrying in ${CallbackRules.delayWords(wait)}", level = "warn")
            delay(wait); attempt++
        }
    }

    /**
     * The applet (package id) and action a watch's paid calls are for, or null for an ordinary agent's watch
     * (set by BridgeApp: the installed applet whose own agent runs the watch).
     */
    @Volatile var budgetKey: ((Watch) -> Pair<String, String>?)? = null
    private fun budgetOf(w: Watch): Pair<String, String>? = runCatching { budgetKey?.invoke(w) }.getOrNull()

    /** Every running watch of [agentId] (an installed applet's own agent). */
    private fun ofAgent(agentId: String) = synchronized(watches) { watches.values.filter { it.agentId == agentId } }

    /**
     * The applet's budget is used up on every phone: each of [agentId]'s watches pauses until [until] (saved), with
     * a watch.paused event (reason applet_budget). Free checks stop too, so the camera and the battery rest.
     */
    fun budgetPause(agentId: String, until: Long, message: String) {
        for (w in ofAgent(agentId)) {
            val already = w.budgetPausedUntil >= until
            w.budgetPausedUntil = maxOf(w.budgetPausedUntil, until)
            save(w)
            if (already) continue
            app.log.system("diagnostic", "watch", "Watch ${w.id} of ${w.agentName} paused until ${isoAt(until)}: $message", level = "warn")
            send(w, "watch.paused", JSONObject().put("watch_id", w.id).put("label", w.spec.label).put("reason", "applet_budget")
                .put("until", isoAt(until)).put("message", message))
        }
    }

    /** The budget sync said ok (or the owner topped up): [agentId]'s watches run again at once. */
    fun budgetResume(agentId: String, reason: String = "budget_ok") {
        for (w in ofAgent(agentId)) {
            if (w.budgetPausedUntil == 0L) continue
            val wasPaused = w.budgetPaused()
            w.budgetPausedUntil = 0L; save(w)
            w.nudge.trySend(Unit)
            if (wasPaused) send(w, "watch.resumed", JSONObject().put("watch_id", w.id).put("label", w.spec.label).put("reason", reason))
        }
    }

    /** A budget pause whose time has come: cleared, and the agent hears so. */
    private fun budgetExpired(w: Watch, now: Long) {
        if (w.budgetPausedUntil == 0L || w.budgetPausedUntil > now) return
        w.budgetPausedUntil = 0L; save(w)
        send(w, "watch.resumed", JSONObject().put("watch_id", w.id).put("label", w.spec.label).put("reason", "budget_reset"))
    }

    /**
     * Changes a running watch's interval in place (the applet's action, set by the owner, an agent or the budget
     * sync): its counts, readings and tally are kept. [seconds] null goes back to the watch's own. Returns false
     * when there's no such watch or it has no interval (a sound watch).
     */
    fun setInterval(id: String, seconds: Int?): Boolean {
        val w = synchronized(watches) { watches[id] } ?: return false
        if (w.spec.sound != null) return false
        // Never faster than the watch rules allow: 10 s only for a camera tally with the free change check, else 30 s.
        val floor = if (w.spec.tally != null && w.spec.camera != null && w.spec.local.enabled) WatchRules.MIN_INTERVAL_TALLY_S else minOf(WatchRules.MIN_INTERVAL_S, w.spec.intervalS)
        val s = seconds?.coerceIn(floor, cc.bridgit.app.applets.AppletActions.MAX_S)?.takeIf { it != w.spec.intervalS }
        if (s == w.intervalOverrideS) return true
        w.intervalOverrideS = s
        save(w)
        w.nudge.trySend(Unit)
        app.log.system("audit", "watch", "Watch ${w.id} of ${w.agentName} now checks every ${w.intervalS} s")
        return true
    }

    /** The watch [id], whoever it belongs to (the applet's screen, the budget sync). */
    fun byId(id: String): Watch? = synchronized(watches) { watches[id] }

    private val pauseLock=kotlinx.coroutines.sync.Mutex()
    suspend fun setPaused(agent:String,id:String,paused:Boolean):JSONObject {
        pauseLock.lock()
        try {
            val w=get(agent,id) ?: throw cc.bridgit.app.api.ErrorCatalog.error("not_found","No watch for you.")
            w.job?.cancelAndJoin(); w.userPaused=paused; save(w)
            w.job=scope.launch { loop(w) }; updateWake()
            app.interactions.event(agent,if(paused) "watch.paused" else "watch.resumed",JSONObject().put("watch_id",id))
            return w.json(full=false)
        } finally { pauseLock.unlock() }
    }

    private fun save(w: Watch) {
        if (synchronized(watches) { watches[w.id] } !== w) return
        prefs.edit().putString(w.id, JSONObject().put("agent_id", w.agentId).put("agent", w.agentName).put("request", w.request)
            .put("user_paused",w.userPaused).put("created_at", w.createdAt).put("expires_at", w.expiresAt).put("checks", w.checks).put("matches", w.matches)
            .put("last_match_at", w.lastMatchAt).put("checks_local", w.checksLocal).put("checks_paid", w.checksPaid)
            .put("paid_cost_usd", w.paidCostUsd).put("skipped_over_paid_cap", w.skippedCap).put("rules_fired", w.rulesFired)
            .apply { if (w.budgetPausedUntil > 0) put("budget_paused_until", w.budgetPausedUntil); w.intervalOverrideS?.let { put("interval_s_override", it) } }
            .toString()).apply()
    }

    private fun saveTally(w: Watch, required: Boolean = false) {
        val st = w.tally ?: return
        if (synchronized(watches) { watches[w.id] } !== w) return
        val text = synchronized(st) { st.json().toString() }
        runCatching {
            tallyDir.mkdirs()
            val tmp = java.io.File(tallyDir, "${w.id}.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(java.io.File(tallyDir, "${w.id}.json"))) throw java.io.IOException("rename failed")
            w.tallySavedAt = System.currentTimeMillis()
        }.onFailure {
            app.log.system("diagnostic", "watch", "Watch ${w.id}: couldn't save its tally (${it.javaClass.simpleName})", level = "warn")
            if (required) throw Refused("storage_unavailable","Could not save this watch's history. Retry the update when storage is available.")
        }
    }

    private fun loadTally(id: String): WatchTally.State? = runCatching {
        WatchTally.State.from(JSONObject(java.io.File(tallyDir, "$id.json").readText()))
    }.getOrNull()

    /** The tally of [agentId]'s watch [id]: summary plus episodes of the last [days] days (optionally one [label]). */
    fun tallyOf(agentId: String, id: String, days: Int, label: String?): JSONObject? {
        val w = get(agentId, id) ?: return null
        val t = w.spec.tally ?: return JSONObject()
        val st = w.tally ?: return JSONObject()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        return synchronized(st) {
            WatchTally.summary(t, st, now, zone, w.intervalS).put("watch_id", w.id).put("label", w.spec.label)
                .put("episodes", WatchTally.episodesJson(t, st, now, zone, w.intervalS, days, label)).put("rules_fired", w.rulesFired)
        }
    }

    private fun forget(id: String) { prefs.edit().remove(id).apply() }
}
