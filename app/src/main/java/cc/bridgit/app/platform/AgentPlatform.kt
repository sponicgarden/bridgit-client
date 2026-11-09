package cc.bridgit.app.platform

import android.app.KeyguardManager
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import cc.bridgit.app.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One authority and operation path for HTTP, MCP, phone UI and local workflows. */
class AgentPlatform(val app: BridgeApp) {
    val store = PlatformStore(app)
    val epoch = UUID.randomUUID().toString()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val tools by lazy { DeviceTools(app) }
    val foreground by lazy { AppForeground(app) }
    private val simulationLimiter = cc.bridgit.app.api.RateLimiter()
    val workflows by lazy { WorkflowEngine(this) }
    val applicationEvents by lazy { ApplicationEvents(this) }
    val delivery by lazy { DeliveryManager(this) }
    val files by lazy { DeviceFiles(app,store) }
    val monitor by lazy { CameraMonitor(this) }
    val batches by lazy { FlowBatches(this) }
    val contextVault by lazy { cc.bridgit.app.context.ContextVault(app) }
    val modes by lazy { Modes(app) }
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private val credentialEpochs = java.util.concurrent.ConcurrentHashMap<String,String>()
    @Volatile var activeSensorAgent: String? = null; private set
    @Volatile var activeSensorScope: String? = null; private set

    init {
        app.policy.agents().forEach { credentialEpochs[it.id]=it.hash }
        // One-time migration for agents paired before grants existed. New pairings get maximum grants
        // (PowerAccess.grantAll); limited agents start denied on new scopes.
        if (store.setting("legacy_migrated") != "true") {
            app.policy.agents().filter { app.agents.get(it.id)?.connection!=cc.bridgit.app.core.Connection.RELAY }.forEach { a -> listOf("camera.snapshot","microphone.clip","gallery.read","audit.read").forEach { store.grant(a.id,it,Long.MAX_VALUE) } }
            store.setting("legacy_migrated","true",true)
        }
        reconcileInterrupted()
        // Send steps read as "…to Home Assistant (your private network)" in reviews (docs/applet-reach.md A3).
        FlowSteps.connectionWords = { id -> store.find(id,"owner","destination")?.let { Connections.words(it) } }
        store.list(kind="operation",limit=1000).filter { it.optBoolean("relay") && it.optString("status")=="awaiting_owner" }.forEach { store.save(it.getString("agent_id"),"operation",it.put("status","cancelled").put("error","relay_restarted")) }
        store.list(kind="session").filter { it.optString("status") == "active" }.forEach { store.save(it.getString("agent_id"),"session",it.put("status","interrupted")) }
        store.list(kind="stream").filter { it.optString("status") == "active" }.forEach { store.save(it.getString("agent_id"),"stream",it.put("status","interrupted")) }
        scope.launch {
            store.list("owner","restore").forEach { record -> launch { delay((record.optLong("at")-System.currentTimeMillis()).coerceAtLeast(0)); runCatching { tools.restoreSettings(record) } } }
        }
        scope.launch { while(isActive) { delay(250); runCatching { sweep() } } }
        // Card-button batches go on where they were (docs/applet-dev-experience.md R2), once the app has settled.
        scope.launch { delay(10_000); while (isActive) { contextVault.refresh(); delay(60_000) } }
        scope.launch { delay(5_000); runCatching { batches.resume() } }
    }

    /**
     * Start-up: operations and workflow runs the last process left half-way are marked unknown_outcome, never replayed.
     * An interrupted run doesn't block its workflow: later starts (and a batch on top of it) still work.
     */
    fun reconcileInterrupted() {
        store.list(kind="operation",limit=1000).filter { it.optString("status") in setOf("running","accepted") }.forEach {
            store.save(it.getString("agent_id"),"operation",it.put("status","unknown_outcome").put("error","interrupted_reconcile_before_retry"))
        }
        store.list(kind="execution",limit=1000).filter { it.optString("status") in setOf("running","dispatching") }.forEach { execution ->
            store.save(execution.getString("agent_id"),"execution",execution.put("status","unknown_outcome").put("error","interrupted").put("finished_at",System.currentTimeMillis()))
        }
    }
    fun hasGrant(agent: String, id: String): Boolean {
        val live = app.agents.get(agent)?.takeUnless { it.suspended } ?: return false
        return (Capabilities.legacy(id)?.let { live.allows(it.key) } ?: true) && store.allowed(agent,id)
    }
    fun authorize(agent: String, id: String) {
        if(!app.settings.listenerEnabled) throw ApiError(403,"listener_paused","Resume the listener on the phone")
        val cap = Capabilities.all.firstOrNull { it.id == id } ?: throw ApiError(422,"unknown_scope","Unknown capability")
        val superConfiguration = app.agents.get(agent)?.let { live -> live.superPowers && live.allows("settings") && app.settings.isOn("settings_api") &&
            cc.bridgit.app.ui.home.HomeStates.controlAgents(app).any { it.id == agent } } == true
        val accessUrl = "/v1/owner/configuration/access"
        if (!hasGrant(agent,id)) {
            if (id == "owner.configuration" && superConfiguration) throw ApiError(403,"agent_not_allowed",
                "This Super agent can grant its configuration scope through $accessUrl; no phone approval is needed",
                mapOf("configuration_access_url" to accessUrl, "user_action_required" to false))
            throw ApiError(403,"agent_not_allowed","The owner or an existing Super agent must grant $id to this agent")
        }
        val blockers = Capabilities.blockers(app,cap)
        if (blockers.isNotEmpty()) {
            if (superConfiguration && blockers.all { it == "feature_disabled" }) throw ApiError(403,blockers.first(),
                "This Super agent can enable ${cap.label} through $accessUrl/features; no phone approval is needed",
                mapOf("blockers" to JSONArray(blockers), "configuration_access_url" to accessUrl, "user_action_required" to false))
            throw ApiError(403,blockers.first(),"Open Agent permissions on the phone: ${cap.label}",mapOf("blockers" to JSONArray(blockers),"user_action_required" to true))
        }
    }
    fun grant(agent: String,id: String,until: Long) { require(Capabilities.all.any { it.id == id }); if (until != Long.MAX_VALUE) app.agents.update(agent) { it.copy(maximumControl = false) }; store.grant(agent,id,until); store.emit(agent,"grant.changed",id) }
    fun revoke(agent: String,id: String? = null) {
        app.agents.update(agent) { it.copy(maximumControl = false) }
        store.revoke(agent,id)
        // Camera, microphone and gallery are also reachable on the older /v1 routes, which use the agent's own
        // switches. Revoking here turns those off too, so "revoke" really stops the access everywhere.
        val legacy = (if (id == null) Capabilities.all.map { it.id } else listOf(id)).mapNotNull { Capabilities.legacy(it)?.key }.toSet()
        if (legacy.isNotEmpty()) app.agents.update(agent) { a ->
            if (legacy.none { it in (a.caps ?: legacy) }) a else a.copy(caps = (a.caps ?: app.registry.modules.map { it.id }.toSet()) - legacy)
        }
        invalidate(agent); store.emit(agent,"grant.revoked",id ?: "all")
    }
    fun invalidateScope(id: String) { app.policy.agents().forEach { a -> invalidate(a.id) } }
    fun invalidate(agent: String) {
        app.resourceDownloads.stopAgent(agent)
        jobs.entries.filter { it.key.startsWith("$agent:") }.forEach { it.value.cancel() }
        store.list(agent,"session").filter { it.optString("status") in setOf("active","awaiting_owner") }.forEach { closeSession(it,"revoked") }
        store.list(agent,"interaction").filter { it.optString("status") == "pending" }.forEach { store.save(agent,"interaction",it.put("status","cancelled")); store.emit(agent,"interaction.cancelled",it.getString("id")) }
        store.list(agent,"operation",1000).filter { it.optString("status") in setOf("accepted","awaiting_owner","running") }.forEach { store.save(agent,"operation",it.put("status",if (it.optString("status") == "running") "unknown_outcome" else "cancelled")) }
        tools.stopAgent(agent)
        app.speaker.stop(agent)
        delivery.stopAgent(agent)
        monitor.stopAgent(agent)
        workflows.pauseAgent(agent)
    }
    fun interrupt() {
        jobs.values.forEach { it.cancel() }; store.list(kind="session").filter { it.optString("status")=="active" }.forEach { closeSession(it,"interrupted") }; app.policy.agents().forEach { monitor.stopAgent(it.id) }; tools.stopAudio()
    }
    fun stopAll() { store.setting("active_control").takeIf { it.isNotEmpty() }?.let { store.find(it,kind="session") }?.let { closeSession(it,"stopped_by_owner") }; app.policy.agents().forEach { invalidate(it.id) }; ProjectionService.instance?.stopSelf(); tools.stopAudio() }
    fun getAgent(id: String) = app.policy.agents().firstOrNull { it.id == id } ?: throw ApiError(401,"unauthorized","Agent was removed")
    /** Bumped on every change, so open screens (Home, Agent permissions) refresh what is waiting for the owner. */
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    fun changed() { changes.value = changes.value + 1; app.sendBroadcast(Intent("cc.bridgit.app.PLATFORM_CHANGED").setPackage(app.packageName)) }
    /** Requests waiting for the owner's tap, newest first, expired ones left out (A0145: Home and Agent permissions show these). */
    fun pendingApprovals(now: Long = System.currentTimeMillis()): List<JSONObject> = store.list(limit=300).filter { isPendingApproval(it,now) }
    fun recheckCredentials() {
        val agents=app.policy.agents()
        credentialEpochs.keys.filter { id -> agents.none { it.id==id } }.forEach { id -> invalidate(id); credentialEpochs.remove(id) }
        agents.forEach { a ->
            val old = credentialEpochs.put(a.id,a.hash)
            if (old != null && old != a.hash) invalidate(a.id)
        }
    }
    private fun sweep() {
        recheckCredentials()
        val now = System.currentTimeMillis()
        store.list(kind="interaction").filter { it.optString("status") == "pending" && it.optLong("expires_at") <= now }.forEach { store.save(it.getString("agent_id"),"interaction",it.put("status","expired")); store.emit(it.getString("agent_id"),"interaction.expired",it.getString("id")) }
        store.list(kind="operation",limit=1000).filter { it.optString("status")=="awaiting_owner" && it.optLong("expires_at")<=now }.forEach { store.save(it.getString("agent_id"),"operation",it.put("status","expired")) }
        store.list(kind="session").filter { it.optString("status") == "active" }.forEach { s ->
            if (s.optLong("expires_at") <= now || s.optLong("heartbeat_until") <= now || !hasGrant(s.getString("agent_id"),"screen.control") || !Capabilities.enabled(app,"screen.control") || !Capabilities.visible(app) || app.getSystemService(KeyguardManager::class.java).isDeviceLocked) closeSession(s,"expired")
        }
    }
    fun session(agent: String,id: String): JSONObject {
        val s = store.require(id,agent,"session")
        if (store.setting("active_control")!=id || s.optString("status") != "active" || s.optString("epoch") != epoch || s.optLong("expires_at") <= System.currentTimeMillis() || s.optLong("heartbeat_until") <= System.currentTimeMillis()) throw ApiError(409,"session_inactive","Start or renew a control session on the phone")
        authorize(agent,"screen.control"); return s
    }
    fun closeSession(s: JSONObject,state: String) {
        val agent=s.getString("agent_id")
        if(store.setting("active_control")==s.optString("id")) store.setting("active_control","",true)
        store.list(agent,"operation",1000).filter { it.optString("command") in setOf("ui.action","ui.sequence") && it.optJSONObject("input")?.optString("session_id")==s.optString("id") && it.optString("status") in setOf("accepted","awaiting_owner","running") }.forEach { cancelOperation(agent,it.getString("id")) }
        store.save(agent,"session",s.put("status",state)); DeviceControlService.instance?.clearObservation()
        store.emit(s.getString("agent_id"),"session.$state",s.getString("id")); changed()
    }
    fun approveSession(id: String) = synchronized(store) {
        val s = store.find(id,kind="session") ?: return
        if (s.optString("status") != "awaiting_owner" || s.optLong("request_expires_at") <= System.currentTimeMillis()) throw ApiError(409,"approval_expired","Request expired")
        authorize(s.getString("agent_id"),"screen.control")
        val controller=store.setting("active_control").takeIf { it.isNotEmpty() }?.let { store.find(it,kind="session") }
        if (controller!=null && controller.optString("status")=="active" && controller.optLong("expires_at")>System.currentTimeMillis() && controller.optLong("heartbeat_until")>System.currentTimeMillis()) throw ApiError(409,"controller_busy","Stop the current session first")
        val now = System.currentTimeMillis()
        store.save(s.getString("agent_id"),"session",s.put("status","active").put("expires_at",now+s.getInt("duration_seconds")*1000L).put("heartbeat_until",now+60_000).put("epoch",epoch).put("fence",UUID.randomUUID().toString()))
        store.setting("active_control",id,true)
        DeviceControlService.instance?.showController(s.getString("agent_id")); store.emit(s.getString("agent_id"),"session.active",id); changed()
    }
    fun answer(id: String,value: String,dismiss: Boolean = false) {
        val item = store.find(id,kind="interaction") ?: return
        synchronized(store) {
            if (item.optString("status") != "pending" || item.optLong("expires_at") <= System.currentTimeMillis()) throw ApiError(409,"interaction_closed","Question is already closed")
            if (value.length > 4096) throw ApiError(422,"invalid_parameter","Answer is too long")
            store.save(item.getString("agent_id"),"interaction",item.put("status",if(dismiss) "dismissed" else "answered").put("answer",value).put("answered_at",System.currentTimeMillis()))
            store.emit(item.getString("agent_id"),"interaction.answered",id)
        }
        changed()
    }
    fun interaction(agent: String,b: JSONObject): JSONObject {
        b.only("kind","title","text","choices","expires_seconds","priority")
        val kind = b.text("kind",16,"question"); if (kind !in listOf("question","notice")) throw ApiError(422,"invalid_parameter","kind must be notice or question")
        if (store.list(agent,"interaction").count { it.optString("status") == "pending" } >= 20) throw ApiError(429,"interaction_limit","Too many unanswered interactions")
        val a = getAgent(agent)
        val item = store.save(agent,"interaction",JSONObject().put("interaction_kind",kind).put("title",b.text("title",120)).put("text",b.text("text",4096,""))
            .put("choices",if(b.has("choices")) JSONArray(b.strings("choices",6)) else JSONArray()).put("status",if(kind=="notice") "delivered" else "pending")
            .put("identity",AgentBranding.json(a)).put("expires_at",System.currentTimeMillis()+b.whole("expires_seconds",3600,30,86400)*1000L)
            .apply { if(b.optString("priority")=="urgent") put("priority","urgent") })
        store.emit(agent,"interaction.created",item.getString("id")); PlatformNotifications.show(app,item); changed(); return item
    }
    suspend fun <T> capture(agent: String,id: String,block: suspend () -> T): T = coroutineScope {
        authorize(agent,id)
        if (app.getSystemService(KeyguardManager::class.java).isDeviceLocked) throw ApiError(409,"device_locked","Unlock the phone to capture")
        val sensorLock=if(id in setOf("microphone.clip","speech.transcribe")) app.registry.micLock else app.registry.cameraLock
        if (!sensorLock.tryLock()) throw ApiError(409,"sensor_busy","A sensor operation already owns the camera/microphone")
        val credential = getAgent(agent).hash
        activeSensorAgent = agent; activeSensorScope = id
        val parent = currentCoroutineContext()[Job]!!
        val watchdog = launch {
            while (isActive) { delay(125); if (!runCatching { authorize(agent,id); getAgent(agent).hash == credential }.getOrDefault(false) || app.getSystemService(KeyguardManager::class.java).isDeviceLocked) parent.cancel(CancellationException("Capture authority or visibility lost")) }
        }
        val service=BridgeService.running
        val captureToken=service?.captureStarted("${getAgent(agent).name}: ${id.substringBefore('.')}")
        try { block() }
        finally { watchdog.cancel(); activeSensorAgent=null; activeSensorScope=null; if(captureToken!=null) service.captureEnded(captureToken); sensorLock.unlock() }
    }
    private fun sensitive(command: String,args: JSONObject): Boolean = OwnerApprovalPolicy.needsOwnerTap(command,args)
    fun mutate(agent: String, command: String,args: JSONObject,key: String,credential: String? = null,relayDeadline: Long? = null,relayJob: String? = null): JSONObject {
        // Both command spellings share the same durable idempotency reservation.
        if (command == "applet.event") return mutate(agent, "application.event", args, key, credential, relayDeadline, relayJob)
        if(credential!=null && getAgent(agent).hash!=credential) throw ApiError(401,"unauthorized","Key was rotated")
        if(relayDeadline!=null && relayDeadline<=System.currentTimeMillis()) throw ApiError(409,"deadline_expired","Relay deadline expired")
        app.alerts.checkAgentCall(exempt = command in cc.bridgit.app.alerts.EmailGate.EXEMPT_TOOLS)
        val scopeId = commandScope(command,args)
        if(command!="operation.cancel") authorize(agent,scopeId)
        if(command=="app.foreground") AppForeground.validate(args)
        if(command=="owner.configure") app.ownerFeatures.review(agent,args)
        val (op,fresh) = store.reserve(agent,key,command,args)
        if (!fresh) return publicOperation(op)
        try {
        if(command=="owner.configure") op.put("review_context",app.ownerFeatures.review(agent,args))
        if(command=="ui.action") op.put("review_context",DeviceControlService.instance?.review(agent,args) ?: throw ApiError(409,"accessibility_required","Enable private control"))
        if(command=="ui.sequence") op.put("review_context",DeviceControlService.instance?.reviewSequence(agent,args) ?: throw ApiError(409,"accessibility_required","Enable private control"))
        if(command=="notification.reply") op.put("review_context",NotificationBridge.instance?.review(agent,args) ?: throw ApiError(409,"notification_access_required","Enable notification access"))
        if(command=="alert.trigger") op.put("review_context",JSONObject().put("recipients",JSONArray(args.strings("destinations",3).map { id -> store.require(id,"owner","destination").let { JSONObject().put("name",it.getString("name")).put("url",it.getString("url")) } })))
        } catch(e:ApiError) { store.save(agent,"operation",op.put("status","failed").put("error",e.code)); throw e }
        op.put("credential_hash",getAgent(agent).hash).put("expires_at",minOf(System.currentTimeMillis()+5*60_000,relayDeadline ?: Long.MAX_VALUE)).put("relay",relayDeadline!=null).put("relay_job",relayJob).put("identity",AgentBranding.json(getAgent(agent)))
        val unattendedConfiguration = command == "owner.configure" &&
            cc.bridgit.app.ui.home.HomeStates.controlAgents(app).any { it.id == agent }
        val pending = sensitive(command,args) && !unattendedConfiguration
        store.save(agent,"operation",op.put("status",if(pending) "awaiting_owner" else "accepted"))
        if (!pending) launchOperation(op) else { store.save(agent,"operation",op.put("notification_posted",PlatformNotifications.show(app,op))); store.emit(agent,"operation.awaiting_owner",op.getString("id")); changed() }
        return publicOperation(store.require(op.getString("id"),agent,"operation"))
    }
    fun approveOperation(id: String) {
        val op = store.find(id,kind="operation") ?: return
        synchronized(store) {
            if (op.optString("status") != "awaiting_owner" || op.optLong("expires_at") <= System.currentTimeMillis()) throw ApiError(409,"approval_expired","Request is no longer pending")
            // An operation is immutable; approvals bind to the canonical command/input digest.
            if (op.getString("digest") != PlatformStore.digest(JSONObject().put("command",op.getString("command")).put("body",op.getJSONObject("input")))) throw ApiError(409,"approval_changed","Action changed")
            if(op.optString("credential_hash")!=getAgent(op.getString("agent_id")).hash) throw ApiError(401,"unauthorized","Key was rotated")
            authorize(op.getString("agent_id"),commandScope(op.getString("command"),op.getJSONObject("input")))
            store.save(op.getString("agent_id"),"operation",op.put("status","accepted"))
        }
        launchOperation(op)
    }
    fun cancelRelayOperation(agent:String,id:String) {
        val op=store.find(id,agent,"operation") ?: return
        if(!op.optBoolean("relay"))return
        val result=op.optJSONObject("result")
        if(op.optString("command")=="session.request" && result?.optString("kind")=="session")store.find(result.optString("id"),agent,"session")?.let { closeSession(it,"cancelled") }
        cancelOperation(agent,id)
    }
    fun cancelOperation(agent: String,id: String): JSONObject {
        val op = store.require(id,agent,"operation")
        jobs["$agent:$id"]?.cancel()
        if (op.optString("status") !in setOf("completed","failed","cancelled")) store.save(agent,"operation",op.put("status",if (op.optString("status")=="running") "unknown_outcome" else "cancelled"))
        store.emit(agent,"operation.cancelled",id); changed(); return publicOperation(store.require(id,agent,"operation"))
    }
    private fun launchOperation(original: JSONObject) {
        val agent = original.getString("agent_id"); val id = original.getString("id")
        val job = scope.launch(start=CoroutineStart.LAZY) {
            var op = store.require(id,agent,"operation")
            try {
                if(op.optString("credential_hash")!=getAgent(agent).hash) throw ApiError(401,"unauthorized","Key was rotated")
                if(op.optLong("expires_at")<=System.currentTimeMillis()) throw ApiError(409,"deadline_expired","Execution deadline expired")
                if(op.getString("command")!="operation.cancel") authorize(agent,commandScope(op.getString("command"),op.getJSONObject("input")))
                if (op.optString("status") != "accepted") return@launch
                op = store.save(agent,"operation",op.put("status","running"))
                val budget=if(op.optBoolean("relay")) minOf(150_000L,op.getLong("expires_at")-System.currentTimeMillis()) else 150_000L
                val result = withTimeout(budget.coerceAtLeast(1)) { execute(agent,op.getString("command"),op.getJSONObject("input"),if(op.optBoolean("relay"))op.getLong("expires_at") else null,op.optString("relay_job").ifEmpty { null }) }
                // Revocation cannot be overwritten with a late success.
                if (store.require(id,agent).optString("status") == "running") store.save(agent,"operation",op.put("status","completed").put("result",result).put("finished_at",System.currentTimeMillis()))
            } catch(e: CancellationException) {
                store.save(agent,"operation",op.put("status","unknown_outcome").put("error","cancelled_reconcile_before_retry")); throw e
            } catch(e: Exception) {
                store.save(agent,"operation",op.put("status","failed").put("error",if(e is ApiError) e.code else "execution_failed").put("message",if(e is ApiError) e.message else "Operation failed; inspect readiness").put("details",if(e is ApiError) JSONObject(e.extra) else JSONObject()))
            } finally {
                jobs.remove("$agent:$id"); store.emit(agent,"operation.finished",id)
                app.audit.append(JSONObject().put("agent_id",agent).put("endpoint","platform:${op.optString("command")}").put("operation_id",id).put("result",store.find(id,agent)?.optString("status")))
                changed()
            }
        }
        jobs["$agent:$id"] = job; job.start()
    }
    private fun commandScope(command:String,args:JSONObject) = if(command=="device.action") DeviceTools.scopeFor(args.optString("type")) else Commands.scope(command)
    private fun publicSession(record: JSONObject): JSONObject = PlatformStore.copy(record).apply { remove("credential_hash") }
    fun publicOperation(op: JSONObject): JSONObject = PlatformStore.copy(op).apply { remove("input"); remove("identity"); remove("digest"); remove("credential_hash")
        val posted=if(has("notification_posted")) optBoolean("notification_posted") else PlatformNotifications.canPost(app,PlatformNotifications.channelFor(this)); remove("notification_posted")
        if(optString("status")=="awaiting_owner") put("approval",approvalHint(posted,optLong("expires_at"))) }
    /** Tells the agent where the owner approves a waiting operation, so it can direct them (A0145). */
    fun approvalHint(notificationShown: Boolean, expiresAt: Long): JSONObject = JSONObject()
        .put("where",if(notificationShown) "A Bridgit notification on the owner's phone titled with this agent's name opens the review. Or the owner opens Bridgit and taps Review on the banner at the top of Home; the request is also first on More, Agent permissions, under Questions and approvals."
            else "Bridgit notifications (or its Agent control requests channel) are off, so no notification was posted. The owner opens Bridgit and taps Review on the banner at the top of Home, or opens More, Agent permissions, where the request is first under Questions and approvals.")
        .put("home_banner",true)
        .put("notification_posted",notificationShown)
        .put("screen","More > Agent permissions > Questions and approvals")
        .put("expires_at",expiresAt)
        .put("note","Waiting for the owner is not success. Tell the owner exactly where to tap; poll operation.get until it is no longer awaiting_owner.")
    suspend fun read(agent: String,command: String,args: JSONObject,credential:String?=null): JSONObject {
        if(credential!=null && getAgent(agent).hash!=credential) throw ApiError(401,"unauthorized","Key was rotated")
        app.alerts.checkAgentCall(exempt = command in cc.bridgit.app.alerts.EmailGate.EXEMPT_TOOLS)
        if(command == "capabilities") return Capabilities.describe(app,getAgent(agent))
        if(command !in setOf("operation.get","operation.list","session.list")) authorize(agent,Commands.scope(command))
        val waiting=command=="screen.wait" || (command in setOf("events","interaction.get") && args.optInt("wait",0)>0)
        if(waiting && !app.requests.waits.enter(agent)) throw ApiError(429,"too_many_waits","Close another wait before starting one")
        try {
        val result = when(command) {
            "app.foreground.targets" -> foreground.targets(agent)
            "context.status" -> contextVault.status()
            "events" -> {
                val after = args.optLong("after",0); val wait=args.whole("wait",0,0,55); val end=System.currentTimeMillis()+wait*1000L
                var result=applicationEvents.events(agent,after)
                while(result.getJSONArray("events").length()==0 && System.currentTimeMillis()<end) { delay(200); authorize(agent,"events.read"); result=applicationEvents.events(agent,after) }
                result.put("epoch",epoch).put("device_id",store.deviceId)
            }
            "operation.get" -> publicOperation(store.require(args.text("id"),agent,"operation"))
            "operation.list" -> JSONObject().put("operations",JSONArray(store.list(agent,"operation").map(::publicOperation)))
            "session.list" -> JSONObject().put("sessions",JSONArray(store.list(agent,"session").map(::publicSession)))
            "screen.observe" -> DeviceControlService.instance?.observe(agent,args) ?: ProjectionService.instance?.observe(agent) ?: throw ApiError(409,"screen_share_required","Start screen sharing on the phone")
            "screen.wait" -> DeviceControlService.instance?.waitFor(agent,args) ?: throw ApiError(409,"accessibility_required","Enable private control")
            "interaction.get" -> {
                val end=System.currentTimeMillis()+args.whole("wait",0,0,55)*1000L
                var item=store.require(args.text("id"),agent,"interaction")
                while(item.optString("status")=="pending" && System.currentTimeMillis()<end) { delay(200); authorize(agent,"interactions"); item=store.require(args.text("id"),agent,"interaction") }; item
            }
            "interaction.list" -> JSONObject().put("interactions",JSONArray(store.list(agent,"interaction")))
            "notification.list" -> NotificationBridge.instance?.list(agent) ?: throw ApiError(409,"notification_access_required","Enable notification access")
            "file.list" -> files.list(agent)
            "file.get" -> files.get(agent,args.text("id"))
            "alert.list" -> delivery.list(agent)
            "workflow.list" -> JSONObject().put("workflows",JSONArray(store.list(agent,"workflow")))
            "workflow.validate" -> workflows.validate(args)
            "workflow.simulate" -> {
                if (!simulationLimiter.tryAcquire(agent, 30)) {
                    throw ApiError(429, "rate_limited", "Up to 30 simulations per minute per agent.",
                        mapOf("retry_after_s" to simulationLimiter.retryAfterS(agent)))
                }
                workflows.simulate(args)
            }
            "workflow.runs" -> { args.only("id","limit"); workflows.runs(agent,args.text("id"),args.whole("limit",10,1,50)) }
            "stream.status" -> monitor.status(agent,args.text("id"))
            "stream.frame" -> monitor.frame(agent,args.text("id"))
            "phone.status", "phone.context", "location.fix", "sensors.read", "images.ocr", "images.barcode", "translation.run", "peripheral.list", "diagnostics" -> tools.read(agent,command,args)
            else -> throw ApiError(404,"unknown_tool","Unknown read tool")
        }
        if(credential!=null && getAgent(agent).hash!=credential) throw ApiError(401,"unauthorized","Key was rotated")
        if(command !in setOf("operation.get","operation.list","session.list")) authorize(agent,Commands.scope(command))
        return result
        } finally { if(waiting) app.requests.waits.exit(agent) }
    }
    private suspend fun execute(agent: String,command: String,args: JSONObject,relayDeadline:Long?=null,relayJob:String?=null): JSONObject = when(command) {
        "app.foreground" -> foreground.open(agent,args)
        "interaction.create" -> interaction(agent,args)
        "interaction.cancel" -> store.require(args.text("id"),agent,"interaction").also { store.save(agent,"interaction",it.put("status","cancelled")); store.emit(agent,"interaction.cancelled",it.getString("id")) }
        "session.request" -> {
            args.only("apps","duration_seconds","purpose")
            val apps=args.strings("apps",10)
            if(apps.isEmpty() || apps.any { !it.matches(Regex("[a-zA-Z][a-zA-Z0-9_.]+")) || (it==app.packageName && !BuildConfig.DEBUG) }) throw ApiError(422,"invalid_parameter","Choose at least one external app")
            store.save(agent,"session",JSONObject().put("credential_hash",getAgent(agent).hash).put("apps",JSONArray(apps)).put("duration_seconds",args.whole("duration_seconds",300,30,1800)).put("purpose",args.text("purpose",256)).put("status","awaiting_owner").put("request_expires_at",minOf(System.currentTimeMillis()+300_000,relayDeadline ?: Long.MAX_VALUE)).put("relay_job",relayJob).put("identity",AgentBranding.json(getAgent(agent)))).also { store.emit(agent,"session.requested",it.getString("id")); PlatformNotifications.show(app,it); changed() }.let(::publicSession)
        }
        "session.heartbeat" -> session(agent,args.text("id")).let { store.save(agent,"session",it.put("heartbeat_until",minOf(it.getLong("expires_at"),System.currentTimeMillis()+60_000))) }.let(::publicSession)
        "session.stop" -> store.require(args.text("id"),agent,"session").also { closeSession(it,"stopped") }.let(::publicSession)
        "ui.action" -> DeviceControlService.instance?.act(agent,args) ?: throw ApiError(409,"accessibility_required","Enable private control")
        "ui.sequence" -> DeviceControlService.instance?.sequence(agent,args) ?: throw ApiError(409,"accessibility_required","Enable private control")
        "device.action" -> tools.action(agent,args)
        "speech.transcribe" -> tools.transcribe(agent,args)
        "translation.download" -> tools.downloadTranslation(args)
        "owner.configure" -> app.ownerFeatures.execute(agent,args)
        "operation.cancel" -> cancelOperation(agent,args.text("id"))
        "notification.reply" -> NotificationBridge.instance?.reply(agent,args) ?: throw ApiError(409,"notification_access_required","Enable notification access")
        "file.put" -> files.put(agent,args)
        "application.event" -> applicationEvents.receive(agent,args)
        "workflow.propose" -> workflows.propose(agent,args)
        "workflow.pause" -> workflows.pause(agent,args.text("id"))
        "workflow.run" -> workflows.runNow(agent,args.text("id"),args.optJSONObject("with"))
        "stream.start" -> monitor.start(agent,args)
        "stream.stop" -> monitor.stop(agent,args.text("id"))
        "peripheral.action" -> tools.peripheralAction(agent,args)
        "alert.trigger" -> delivery.trigger(agent,args)
        "alert.cancel" -> delivery.cancel(agent,args.text("id"))
        "note.create","feedback.create" -> {
            val text=args.text("text",4096); val kind=if(command=="note.create") "note" else "feedback"
            store.save(agent,kind,JSONObject().put("text",text).put("status","new")).also { store.emit(agent,"$kind.created",it.getString("id")) }
        }
        else -> throw ApiError(404,"unknown_tool","Unknown action tool")
    }
}

object Commands {
    val immediate = cc.bridgit.app.ownerapi.ConfigurationTools.NAMES
    val all: Set<String> get() = reads + mutations + immediate
    val reads = setOf("app.foreground.targets","context.status","capabilities","events","operation.get","operation.list","session.list","screen.observe","screen.wait","interaction.get","interaction.list","notification.list","file.list","file.get","workflow.list","workflow.validate","workflow.simulate","workflow.runs","stream.status","stream.frame","phone.status","phone.context","location.fix","sensors.read","images.ocr","images.barcode","translation.run","peripheral.list","diagnostics","alert.list")
    val mutations = setOf("app.foreground","applet.event","application.event","interaction.create","interaction.cancel","session.request","session.heartbeat","session.stop","ui.action","ui.sequence","device.action","speech.transcribe","translation.download","notification.reply","file.put","workflow.propose","workflow.pause","workflow.run","stream.start","stream.stop","peripheral.action","note.create","feedback.create","alert.trigger","alert.cancel","owner.configure","operation.cancel")
    fun scope(name: String): String = when {
        name.startsWith("app.foreground") -> "app.foreground"
        name=="context.status" -> "context.read"
        name=="owner.configure" || name in immediate -> "owner.configuration"
        name=="operation.cancel" -> "diagnostics"
        name.startsWith("session.") || name.startsWith("ui.") || name=="screen.wait" -> "screen.control"
        name=="screen.observe" -> "screen.observe"
        name.startsWith("interaction.") || name=="note.create" || name=="feedback.create" -> "interactions"
        name=="events" -> "events.read"
        name.startsWith("notification.") -> if(name.endsWith("reply")) "notifications.reply" else "notifications.read"
        name.startsWith("file.") -> "files"
        name.startsWith("workflow.") || ApplicationEvents.isAppletEvent(name) -> "workflows"
        name.startsWith("stream.") -> "monitoring"
        name=="device.action" -> "intents" // Adapters additionally enforce their specific scope.
        name.startsWith("alert.") -> "alerts"
        name.startsWith("translation.") -> "translation"
        name.startsWith("peripheral.") -> "peripherals"
        name in setOf("phone.status","phone.context","diagnostics","operation.get","operation.list") -> "diagnostics"
        name=="location.fix" -> "location"
        name=="sensors.read" -> "sensors"
        name in setOf("images.ocr","images.barcode","speech.transcribe") -> name
        else -> throw ApiError(404,"unknown_tool","Unknown tool")
    }
}

/** An operation, control session or applet flow at awaiting_owner, or a pending question, that has not expired. */
fun isPendingApproval(record: JSONObject, now: Long = System.currentTimeMillis()): Boolean {
    val waiting = when (record.optString("kind")) {
        "operation", "session", "workflow" -> record.optString("status") == "awaiting_owner"
        "interaction" -> record.optString("status") == "pending"
        else -> false
    }
    if (!waiting) return false
    val deadline = record.optLong(if (record.optString("kind") == "session") "request_expires_at" else "expires_at")
    return deadline <= 0 || deadline > now
}
