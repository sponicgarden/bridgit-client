package cc.bridgit.app.platform

import android.app.job.*
import android.content.*
import android.os.BatteryManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import cc.bridgit.app.*
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.judgment.Jev
import cc.bridgit.app.outbound.Categories
import cc.bridgit.app.outbound.Outbound
import cc.bridgit.app.power.BatteryRules
import cc.bridgit.app.power.PowerRules
import cc.bridgit.app.power.PowerState
import cc.bridgit.app.power.SunTimes
import cc.bridgit.app.watch.WatchRules
import cc.bridgit.app.watch.WatchSampler
import org.json.JSONArray
import org.json.JSONObject

/**
 * A closed, bounded DSL. No script, shell, downloads, arbitrary URLs or UI automation.
 *
 * A workflow is a trigger plus 1-32 steps from [FlowSteps]' menu (docs/applet-flows.md): sense (photo), decide
 * (ask_model), ask the owner and wait, and act (notice, speak, ring, alert, event). Steps name earlier results
 * with whole-value references, may be skipped by an `only_if`, and say what happens when they fail. Every run
 * leaves a step-by-step log (`workflow.runs`).
 */
class WorkflowEngine(private val runtime:AgentPlatform) {
    companion object {
        const val HISTORY_KEEP = 200
        /** Errors that mean the agent lost its authority: the workflow blocks until the owner reactivates it. */
        val BLOCKING = setOf("agent_not_allowed","unauthorized")
        /**
         * The appraise step's model call: a kept photo's JPEG bytes → items (scaled to 1024 px, JPEG 85, as
         * POST /v1/images/appraise does). Tests replace it; it runs inside the agent's Outbound actor (applet budget).
         */
        /** A photo for a send: longest side 1280 px, at most 1 MB. Tests replace it (Robolectric has no JPEG codec). */
        @Volatile internal var sendJpeg:(ByteArray)->ByteArray = { bytes ->
            val bmp=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: throw ApiError(422,"invalid_photo","The photo can't be read")
            try {
                val small=cc.bridgit.app.vision.Finder.scaled(bmp,Connections.PHOTO_MAX_SIDE)
                try {
                    var q=85; var out=cc.bridgit.app.vision.Finder.jpeg(small,q)
                    while(out.size>Connections.PHOTO_MAX_BYTES && q>40) { q-=15; out=cc.bridgit.app.vision.Finder.jpeg(small,q) }
                    if(out.size>Connections.PHOTO_MAX_BYTES) throw ApiError(413,"photo_too_large","The photo is still over 1 MB at low quality")
                    out
                } finally { if(small!==bmp) small.recycle() }
            } finally { bmp.recycle() }
        }
        @Volatile internal var appraiser:(BridgeApp,ByteArray,Int,JSONObject?,()->Unit)->Jev.Appraised = { app,bytes,max,pin,authorize ->
            val bmp=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: throw ApiError(422,"invalid_photo","The kept photo can't be read")
            val jpeg=try { val small=cc.bridgit.app.vision.Finder.scaled(bmp,1024); try { cc.bridgit.app.vision.Finder.jpeg(small,85) } finally { if(small!==bmp) small.recycle() } } finally { bmp.recycle() }
            Jev.appraise(app,jpeg,null,max,library=pin,authorized=authorize)
        }
    }
    private val locks=java.util.concurrent.ConcurrentHashMap<String,Mutex>()
    private val active=java.util.concurrent.ConcurrentHashMap<String,Job>()
    private val sampler by lazy { WatchSampler(runtime.app) }

    fun simulate(args:JSONObject):JSONObject {
        args.only("flow","fixtures","trigger")
        val flow=args.optJSONObject("flow") ?: throw ApiError(422,"invalid_workflow","flow must be a workflow definition")
        validate(flow)
        return FlowSimulation.run(flow,args)
    }

    fun validate(b:JSONObject):JSONObject =
        WorkflowDefinition.validate(b, runtime.delivery::validateDestinations, ::checkSend)

    /** A send step's connection: approved and enabled, and able to take what the step sends (A2, A4). */
    private fun checkSend(use:FlowSteps.SendUse) {
        val conn=runtime.store.find(use.to,"owner","destination")
        if(conn==null || !conn.optBoolean("enabled")) throw ApiError(422,"invalid_workflow","${use.at}: ${use.to} isn't a connection the owner approved (GET /v1/connections lists them; the owner adds one in Applets › Connections)")
        val preset=Connections.preset(conn)
        if(preset.chat && !use.text) throw ApiError(422,"invalid_workflow","${use.at}: ${conn.optString("name")} is ${preset.label}, which shows text: add a text")
        if(use.photo) Connections.photoProblem(preset)?.let { throw ApiError(422,"invalid_workflow","${use.at}: $it") }
    }
    /** `record` and `photo` with `keep` keep data in an installed applet's own tables and folder: other agents can't use them. */
    private fun appletOnly(agent:String,definition:JSONObject, proposing:Boolean = false) {
        val steps=definition.optJSONArray("steps") ?: return
        val uses=(0 until steps.length()).mapNotNull { steps.optJSONObject(it) }.any { it.optString("type") in setOf("record","update","share","lookup") || it.optBoolean("keep") } || FlowConditions.needsOf(definition).tables
        if(uses && !(if (proposing) runtime.app.applets.canProposeTableWorkflow(agent) else runtime.app.applets.forAgent(agent) != null)) throw ApiError(422,"invalid_workflow","record, update, share, lookup, photo keep and count or sum conditions work only in an installed applet's workflow (its tables and kept photos are deleted with it)")
    }
    fun propose(agent:String,b:JSONObject,packageFlow:String?=null):JSONObject {
        val checked=validate(b)
        appletOnly(agent,b,proposing = true)
        b.optJSONObject("trigger")?.takeIf { it.optString("type")=="watch" }?.let { t ->
            if(runtime.app.watches.get(agent,t.getString("watch"))==null) throw ApiError(422,"invalid_workflow","trigger.watch ${t.getString("watch")} isn't one of your running watches")
        }
        if(runtime.store.list(agent,"workflow").size>=8) throw ApiError(429,"workflow_limit","At most eight workflow proposals per agent")
        return runtime.store.save(agent,"workflow",JSONObject().put("definition",b).put("digest",checked.getString("digest")).put("required_scopes",checked.getJSONArray("required_scopes")).put("summary",checked.getString("summary")).put("status","awaiting_owner").put("revision",1).put("identity",AgentBranding.json(runtime.getAgent(agent))).apply { if(packageFlow!=null) put("package_flow",packageFlow) }).also { runtime.store.emit(agent,"workflow.proposed",it.getString("id")); PlatformNotifications.show(runtime.app,it); runtime.changed() }
    }
    fun activate(id:String) = synchronized(runtime.store) {
        val record=runtime.store.find(id,kind="workflow") ?: return
        validate(record.getJSONObject("definition"))
        if(record.getString("digest")!=PlatformStore.digest(record.getJSONObject("definition"))) throw ApiError(409,"workflow_changed","Workflow changed")
        record.strings("required_scopes").forEach { runtime.authorize(record.getString("agent_id"),it) }
        if (record.optString("status") == "active") return@synchronized
        if(record.optString("status") !in setOf("awaiting_owner","paused","blocked")) throw ApiError(409,"workflow_state","Workflow cannot be activated")
        runtime.store.save(record.getString("agent_id"),"workflow",record.put("event_generation",java.util.UUID.randomUUID().toString()).put("status","active").put("approved_digest",record.getString("digest")).put("next_at",System.currentTimeMillis()+1000)
            // A plug or unplug counts only from now (a flow activated while charging doesn't fire at once); the next sunrise or sunset is worked out afresh.
            .put("power_armed_at",System.currentTimeMillis()).apply { remove("sun_next_at"); remove("sun_none") })
        schedule(); runtime.changed()
    }
    fun pause(agent:String,id:String):JSONObject = synchronized(runtime.store) {
        val r=runtime.store.require(id,agent,"workflow"); active[id]?.cancel()
        runtime.store.list(agent,"outbox",1000).filter { it.optString("status")=="queued" && ApplicationEvents.isAppletEvent(it.optJSONObject("payload")?.optString("type")) && it.optJSONObject("payload")?.optString("resource_id")==id }
            .forEach { runtime.store.save(agent,"outbox",it.put("status","cancelled")) }
        runtime.store.save(agent,"workflow",r.put("status","paused")).also { runtime.store.emit(agent,"workflow.paused",id); runtime.changed() }
    }
    suspend fun pauseAndWait(agent: String, id: String) {
        val job = synchronized(runtime.store) {
            pause(agent, id)
            active[id]
        }
        job?.join()
    }

    fun pauseAgent(agent:String) { runtime.store.list(agent,"workflow").filter { it.optString("status")=="active" }.forEach { pause(agent,it.getString("id")) } }

    /** The newest runs of workflow [id], step by step. */
    fun runs(agent:String,id:String,limit:Int):JSONObject {
        runtime.store.require(id,agent,"workflow")
        val list=runtime.store.list(agent,"execution",1000).filter { it.optString("workflow_id")==id }.sortedByDescending { it.optLong("started_at",it.optLong("created_at")) }.take(limit)
        return JSONObject().put("workflow_id",id).put("runs",JSONArray(list))
    }

    private fun schedule() {
        val scheduler=runtime.app.getSystemService(JobScheduler::class.java)
        scheduler.schedule(JobInfo.Builder(8702,ComponentName(runtime.app,WorkflowJobService::class.java)).setPersisted(true).setPeriodic(15*60*1000L).build())
    }
    private fun eventDriven(type:String?) = ApplicationEvents.isAppletEvent(type) || type=="watch" || type=="called" || type=="battery" || type=="mode"

    /**
     * The workflow [ref] names for [agent]: its id, or (for an installed applet's own parts) the package flow name
     * the installer gave it (`package_flow`). Only [agent]'s own workflows ever match.
     */
    fun resolve(agent:String,ref:String):JSONObject? {
        runtime.store.find(ref,agent,"workflow")?.let { return it }
        return runtime.store.list(agent,"workflow",1000).filter { it.optString("package_flow")==ref }.maxByOrNull { it.optLong("created_at") }
    }

    /** Whether [ref] names one of [agent]'s workflows that a rule, schedule or button may start (a called or button trigger). Null when it may; else why not. */
    fun startable(agent:String,ref:String):String? {
        val r=resolve(agent,ref) ?: return "flow $ref isn't one of your workflows (workflow.list)"
        val t=r.optJSONObject("definition")?.optJSONObject("trigger")?.optString("type")
        if(shares(r.optJSONObject("definition"))) return "flow $ref has a share step: only a button can start it (the owner must be there)"
        return if(t=="called" || t=="button") null else "flow $ref has a $t trigger: only a called or button flow can be started by a rule, schedule or button"
    }
    /**
     * Whether a do's [with] names only values flow [ref] of [agent] takes (its called trigger's with), as a package
     * install checks. Null when they fit (or the flow isn't found: [startable] says so); else why not.
     */
    fun withProblem(agent:String,ref:String,with:JSONObject?):String? {
        val takes=resolve(agent,ref)?.optJSONObject("definition")?.optJSONObject("trigger")?.optJSONObject("with") ?: JSONObject()
        val names=takes.keys().asSequence().toList()
        val k=with?.keys()?.asSequence()?.firstOrNull { !takes.has(it) } ?: return null
        return "flow $ref doesn't take $k${if(names.isEmpty()) " (it takes no values)" else " (it takes ${names.joinToString()})"}"
    }
    /** Whether [definition] has a share step: only the owner's own tap on a button may start it. */
    fun shares(definition:JSONObject?) = definition?.optJSONArray("steps")?.let { s -> (0 until s.length()).any { s.optJSONObject(it)?.optString("type")=="share" } }==true

    /**
     * Starts flow [ref] of [agent] for a caller (docs/applet-vocabulary.md): a rule, a schedule action, a button or
     * the agent. [data] is the caller's own `$trigger` fields (caller, watch_id, rule, ...); [with] the values it hands
     * over. Never queues: a flow already running drops the start and counts it. Returns {flow, status, run_id?, error?,
     * message?} with status started, dropped, flow_not_active, flow_not_found or bad_with.
     */
    private fun available(record: JSONObject, caller: String?): Boolean {
        val approved = record.optString("approved_digest") == record.optString("digest")
        if (!approved) return false
        val instance = runtime.app.applets.forAgent(record.getString("agent_id")) ?: return record.optString("status") == "active"
        val state = cc.bridgit.app.applets.AppletLifecycle.state(instance)
        val manual = caller in setOf("button", "agent")
        if (state in setOf("pausing", "starting")) return false
        if (state == "paused") return manual && record.optString("status") in setOf("active", "paused")
        return record.optString("status") == "active"
    }

    suspend fun start(agent:String,ref:String,data:JSONObject,with:JSONObject?):JSONObject {
        val out=JSONObject().put("flow",ref)
        val record=resolve(agent,ref) ?: return out.put("status","flow_not_found").put("message","No workflow $ref")
        val id=record.getString("id"); out.put("workflow_id",id)
        val trigger=record.optJSONObject("definition")?.optJSONObject("trigger")
        val type=trigger?.optString("type")
        if(type!="called" && type!="button") return out.put("status","flow_not_active").put("message","Flow $ref has a $type trigger, so nothing else can start it")
        if(data.optString("caller")!="button" && shares(record.optJSONObject("definition"))) return out.put("status","flow_not_active").put("message","Flow $ref has a share step, so only a button can start it")
        if(!available(record, data.optString("caller"))) return out.put("status","flow_not_active").put("message","Flow $ref is ${record.optString("status")}")
        val values=try { FlowSteps.withValues(trigger.optJSONObject("with"),with) } catch(e:ApiError) { return out.put("status","bad_with").put("message",e.message) }
        if(active[id]?.isActive==true || locks[id]?.isLocked==true) { dropped(agent,id); return out.put("status","dropped") }
        val triggerData=JSONObject(data.toString()).put("with",values)
        val begun=CompletableDeferred<String>()
        val job=runtime.scope.launch {
            try { runOnce(agent,id,null,triggerData) { begun.complete(it) } }
            catch(e:CancellationException) { begun.completeExceptionally(e); throw e }
            catch(e:Exception) { begun.completeExceptionally(e) }
        }
        active.putIfAbsent(id,job)
        return try {
            val run=withTimeoutOrNull(10_000) { begun.await() }
            if(run==null) out.put("status","started") else out.put("status","started").put("run_id",run)
        } catch(e:ApiError) {
            out.put("status",when(e.code) { "workflow_busy" -> "dropped"; "workflow_skipped" -> "skipped"; else -> "flow_not_active" }).put("error",e.code).put("message",e.message)
        } catch(e:CancellationException) { throw e
        } catch(e:Exception) { out.put("status","flow_not_active").put("error","internal_error").put("message",e.message ?: e.javaClass.simpleName) }
    }
    suspend fun tick() {
        // The power state is saved phone-wide (a receiver in the listener records each plug and unplug as it happens);
        // this reading is the back-up for when the listener wasn't running.
        val sticky=runtime.app.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power=if(sticky!=null) PowerState.observe(runtime.app,sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0) else PowerState.load(runtime.app)
        for(record in runtime.store.list(kind="workflow").filter { it.optString("status")=="active" }) {
            val id=record.getString("id"); val agent=record.getString("agent_id"); val trigger=record.getJSONObject("definition").getJSONObject("trigger")
            if(eventDriven(trigger.optString("type"))) continue
            val now=System.currentTimeMillis()
            // Only what the tick owns, and only when it changes, is saved onto the latest record (a run may have changed it meanwhile).
            val changes=JSONObject(); var data:JSONObject?=null
            val due=when(trigger.getString("type")) {
                "interval" -> record.optLong("next_at")<=now
                "charging","power" -> {
                    val on=if(trigger.getString("type")=="charging") "plugged" else trigger.optString("on")
                    if(!record.has("power_armed_at")) { changes.put("power_armed_at",now); false }
                    else PowerRules.due(on,trigger.optInt("for_s",0),power,record.optLong("power_armed_at"),if(record.has("power_fired")) record.optLong("power_fired") else null,now)?.let { f ->
                        changes.put("power_fired",f.marker)
                        data=JSONObject().put("on",on).also { d -> f.data.forEach { (k,v) -> d.put(k,if(k.endsWith("_at")) isoAt(v as Long) else v) } }
                        true
                    } ?: false
                }
                "sun" -> {
                    val fire=sunDue(record,trigger,now,changes)
                    if(fire) data=JSONObject().put("on",trigger.optString("on")).put("offset_min",trigger.optInt("offset_min",0))
                    fire
                }
                "geofence" -> {
                    if(now-record.optLong("last_location_check")<60_000) false else {
                        changes.put("last_location_check",now)
                        val location=runCatching { runtime.authorize(agent,"location"); runtime.tools.read(agent,"location.fix",JSONObject()) }.getOrNull()
                        if(location==null) false else {
                            val distance=FloatArray(1); android.location.Location.distanceBetween(trigger.getDouble("latitude"),trigger.getDouble("longitude"),location.getDouble("latitude"),location.getDouble("longitude"),distance)
                            val inside=distance[0]<=WorkflowDefinition.geofenceRadius(trigger); val entered=inside && !record.optBoolean("last_inside"); changes.put("last_inside",inside); entered
                        }
                    }
                }
                else -> false
            }
            if(changes.length()>0) synchronized(runtime.store) {
                runtime.store.find(id,agent,"workflow")?.let { latest -> for(k in changes.keys()) latest.put(k,changes.get(k)); runtime.store.save(agent,"workflow",latest) }
            }
            if(due) {
                if(active[id]?.isActive==true || locks[id]?.isLocked==true) dropped(agent,id)
                else { val d=data; active[id]=runtime.scope.launch { runCatching { runOnce(agent,id,null,d) } } }
            }
        }
    }

    /**
     * A `sun` trigger: due at the saved next sunrise or sunset (with its offset), then the next one is worked out. The
     * position is the trigger's own, or a rough one (0.1°) the phone saves from one location fix and never reads again.
     * A time missed by more than 6 hours (the phone was off) is skipped, not run late.
     */
    private suspend fun sunDue(record:JSONObject,trigger:JSONObject,now:Long,changes:JSONObject):Boolean {
        var lat=if(trigger.has("latitude")) trigger.getDouble("latitude") else record.optDouble("sun_lat",Double.NaN)
        var lon=if(trigger.has("longitude")) trigger.getDouble("longitude") else record.optDouble("sun_lon",Double.NaN)
        if(lat.isNaN() || lon.isNaN()) {
            if(now-record.optLong("sun_location_check")<60_000) return false
            changes.put("sun_location_check",now)
            val agent=record.getString("agent_id")
            val fix=runCatching { runtime.authorize(agent,"location"); runtime.tools.read(agent,"location.fix",JSONObject()) }.getOrNull() ?: return false
            lat=Math.round(fix.getDouble("latitude")*10)/10.0; lon=Math.round(fix.getDouble("longitude")*10)/10.0
            changes.put("sun_lat",lat).put("sun_lon",lon)
        }
        val next=record.optLong("sun_next_at",0L)
        if(next!=0L && now<next) return false
        val fire=next!=0L && !record.optBoolean("sun_none") && now-next<6*3_600_000L
        val upcoming=SunTimes.next(trigger.getString("on"),trigger.optInt("offset_min",0),lat,lon,now)
        changes.put("sun_next_at",upcoming ?: (now+6*3_600_000L)).put("sun_none",upcoming==null)
        return fire
    }

    /**
     * Battery changes ([BatteryRules.transitions], e.g. battery.low) from the listener's battery receiver: runs every
     * active flow with a `battery` trigger on one of them, with the reading as `$trigger`. Busy flows drop the start.
     */
    fun onBattery(events:List<String>,reading:BatteryRules.Reading) {
        val names=events.map { it.removePrefix("battery.") }.toSet()
        val hits=runtime.store.list(kind="workflow").filter { r ->
            val t=r.optJSONObject("definition")?.optJSONObject("trigger")
            r.optString("status")=="active" && t?.optString("type")=="battery" && t.optString("on") in names
        }
        for(r in hits) {
            val id=r.getString("id"); val agent=r.getString("agent_id")
            val data=JSONObject().put("on",r.getJSONObject("definition").getJSONObject("trigger").getString("on")).put("percent",reading.percent ?: JSONObject.NULL)
                .put("charging",reading.charging).put("battery_saver",reading.powerSave).put("temperature_c",reading.celsius ?: JSONObject.NULL)
            if(active[id]?.isActive==true || locks[id]?.isLocked==true) { dropped(agent,id); continue }
            active[id]=runtime.scope.launch { runCatching { runOnce(agent,id,null,data) } }
        }
    }

    /**
     * The phone's mode changed ([Modes.set]): runs every active flow with a `mode` trigger whose `to` (and `from`, if
     * given) match, with {from, to, by} as `$trigger`. Busy flows drop the start, as for battery triggers.
     */
    fun onMode(from:String,to:String,by:Modes.By) {
        val hits=runtime.store.list(kind="workflow").filter { r ->
            val t=r.optJSONObject("definition")?.optJSONObject("trigger")
            r.optString("status")=="active" && t?.optString("type")=="mode" && (!t.has("to") || t.optString("to")==to) && (!t.has("from") || t.optString("from")==from)
        }
        for(r in hits) {
            val id=r.getString("id"); val agent=r.getString("agent_id")
            val data=JSONObject().put("from",from).put("to",to).put("by",JSONObject().put("type",by.type).put("name",by.name))
            if(active[id]?.isActive==true || locks[id]?.isLocked==true) { dropped(agent,id); continue }
            active[id]=runtime.scope.launch { runCatching { runOnce(agent,id,null,data) } }
        }
    }

    /**
     * A watch of [agent]'s matched (`on` = match) or fired a tally rule (`on` = rule): runs that agent's active
     * workflows triggered by it, with [data] as the trigger's data. A run that arrives while the same workflow
     * is still running is dropped and counted (`dropped_runs`), not queued.
     */
    fun onWatch(agent:String,watchId:String,on:String,rule:String?,data:JSONObject) {
        val hits=runtime.store.list(agent,"workflow").filter { r ->
            val t=r.optJSONObject("definition")?.optJSONObject("trigger")
            r.optString("status")=="active" && t?.optString("type")=="watch" && t.optString("watch")==watchId && t.optString("on","match")==on && (on!="rule" || t.optString("rule")==rule)
        }
        for(r in hits) {
            val id=r.getString("id")
            if(active[id]?.isActive==true || locks[id]?.isLocked==true) { dropped(agent,id); continue }
            active[id]=runtime.scope.launch { runCatching { runOnce(agent,id,null,data) } }
        }
    }
    /** Whether workflow [id] is running now (a batch waits for it instead of being dropped). */
    fun busy(id:String):Boolean = active[id]?.isActive==true || locks[id]?.isLocked==true
    private fun dropped(agent:String,id:String) = synchronized(runtime.store) {
        runtime.store.find(id,agent,"workflow")?.let { runtime.store.save(agent,"workflow",it.put("dropped_runs",it.optInt("dropped_runs")+1)) }
    }

    /**
     * workflow.run: runs [id] once now. A called flow gets [with] (checked against its trigger) and caller agent.
     * A flow with a share step is refused unless [ownerTap] (the owner's Run button on the phone).
     */
    suspend fun runNow(agent:String,id:String,with:JSONObject?,ownerTap:Boolean=false,personStarted:Boolean=ownerTap):JSONObject {
        val definition=runtime.store.require(id,agent,"workflow").optJSONObject("definition")
        if(!ownerTap && shares(definition)) throw ApiError(403,"workflow_not_active","This flow has a share step, so only the owner's tap on its button can start it")
        val trigger=definition?.optJSONObject("trigger")
        if(trigger?.optString("type")!="called") {
            if(with!=null) throw ApiError(422,"bad_with","with goes with a called flow (this one's trigger is ${trigger?.optString("type")})")
            return runOnce(agent,id,triggerData=JSONObject().put("caller", if(personStarted) "button" else "agent"),gated=!ownerTap)
        }
        val values=FlowSteps.withValues(trigger.optJSONObject("with"),with)
        return runOnce(agent,id,null,JSONObject().put("caller",if(personStarted)"button" else "agent").put("with",values),gated=!ownerTap)
    }

    /**
     * Why a start of [record] is skipped now (its `cooldown_s` since the last run, or `max_runs_per_day` since local
     * midnight already used), or null to run. Docs/applet-reach.md D3.
     */
    internal fun skipReason(record:JSONObject,now:Long):String? {
        val definition=record.optJSONObject("definition") ?: return null
        val cooldown=if(WorkflowDefinition.perEvent(definition)) 0 else WorkflowDefinition.cooldownOf(definition)
        val last=record.optLong("last_run",0L)
        if(cooldown>0 && last>0 && now-last<cooldown*1000L) return "Skipped: the flow ran ${(now-last)/1000} s ago and waits $cooldown s between runs (cooldown_s)"
        if(definition.has("max_runs_per_day")) {
            val cap=definition.optInt("max_runs_per_day")
            val runs=if(record.optString("run_day")==java.time.LocalDate.now().toString()) record.optInt("day_runs") else 0
            if(runs>=cap) return "Skipped: the flow already ran $cap times today (max_runs_per_day)"
        }
        return null
    }

    /**
     * Runs workflow [id] once. A start [gated] by the flow's cooldown or daily cap that is too soon is counted in
     * `skipped_runs` and refused with workflow_skipped; only the owner's own Run tap is not gated.
     */
    suspend fun runOnce(agent:String,id:String,eventId:String?=null,triggerData:JSONObject?=null,gated:Boolean=true,onStarted:((String)->Unit)?=null):JSONObject {
        val mutex=locks.getOrPut(id) { Mutex() }
        // An agent's workflow.run gets the 409 and may try again; a batch waits and retries: neither is a dropped run.
        if(!mutex.tryLock()) { if(triggerData!=null && triggerData.optString("caller") !in setOf("agent","batch")) dropped(agent,id); throw ApiError(409,"workflow_busy","Workflow already running") }
        val runningJob=currentCoroutineContext()[Job]
        if(runningJob!=null) active[id]=runningJob
        try {
            val record=runtime.store.require(id,agent,"workflow")
            if(!available(record, triggerData?.optString("caller"))) throw ApiError(403,"workflow_not_active","Activate this exact workflow on the phone")
            // Before the run: the workflow switch and every grant. Each step checks its own feature when it runs.
            val scopes=record.strings("required_scopes")
            runtime.authorize(agent,"workflows")
            scopes.forEach { if(!runtime.hasGrant(agent,it)) throw ApiError(403,"agent_not_allowed","Owner must grant $it to this agent") }
            val definition=record.getJSONObject("definition"); val trigger=definition.getJSONObject("trigger")
            appletOnly(agent,definition)
            if(ApplicationEvents.isAppletEvent(trigger.optString("type"))) {
                runtime.applicationEvents.permitted(agent,trigger)
                if(eventId==null || eventId!=record.optString("last_event_id")) throw ApiError(409,"event_required","This workflow requires a matching applet event")
            }
            fun skip(why:String):Nothing {
                // An interval flow keeps its rhythm: the next try is a whole interval later, not every tick.
                synchronized(runtime.store) { runtime.store.find(id,agent,"workflow")?.let { latest -> runtime.store.save(agent,"workflow",latest.put("skipped_runs",latest.optInt("skipped_runs")+1)
                    .apply { if(trigger.optString("type")=="interval") put("next_at",System.currentTimeMillis()+trigger.optInt("seconds",900)*1000L) }) } }
                throw ApiError(409,"workflow_skipped",why)
            }
            if(gated) skipReason(record,System.currentTimeMillis())?.let { skip(it) }
            val started=System.currentTimeMillis()
            val triggerJson=JSONObject((triggerData ?: JSONObject()).toString()).put("type",trigger.optString("type")).put("at",isoAt(started))
            val facts=Facts(agent,id,record,FlowConditions.needsOf(definition)).also { it.prepare() }
            // The flow's own only_if: a condition, not a rate limit, so even the owner's Run tap is held to it (D1).
            definition.optJSONObject("only_if")?.let { c -> if(!FlowSteps.holds(c,emptyMap(),triggerJson,facts)) skip("Skipped: the flow runs only ${FlowSteps.onlyWords(c)}, and that doesn't hold now (only_if)") }
            // Checkpoint before effects. Interrupted runs are reconciled instead of silently replayed.
            val execution=synchronized(runtime.store) {
                val latest=runtime.store.require(id,agent,"workflow")
                if(!available(latest, triggerData?.optString("caller")) || latest.optString("event_generation")!=record.optString("event_generation")) throw ApiError(409,"workflow_paused","Workflow paused or reapproved")
                val saved=runtime.store.save(agent,"execution",JSONObject().put("workflow_id",id).put("status","running").put("step",0).put("event_id",eventId)
                    .put("trigger",triggerJson).put("steps",JSONArray()).put("started_at",started))
                val today=java.time.LocalDate.now().toString()
                val runs=if(latest.optString("run_day")==today) latest.optInt("day_runs") else 0
                runtime.store.save(agent,"workflow",latest.put("last_run",started).put("next_at",started+trigger.optInt("seconds",900)*1000L).put("run_day",today).put("day_runs",runs+1))
                saved
            }
            onStarted?.invoke(execution.getString("id"))
            try {
                val steps=definition.getJSONArray("steps")
                // A called flow's with.x references are its own: checked with them, its waits count (not a 120 s fallback).
                val limitMs=FlowSteps.runLimitSeconds(definition)*1000L
                val photos=HashMap<String,ByteArray>()
                val run=FlowRun(steps,triggerJson)
                try {
                    withTimeout(limitMs) {
                        run.run(
                            beforeStep = {
                                if(ApplicationEvents.isAppletEvent(trigger.optString("type"))) runtime.applicationEvents.permitted(agent,trigger)
                                if(runtime.store.require(id,agent).optString("status")!="active") throw CancellationException("Workflow paused")
                            },
                            facts = { facts.at() },
                            checkpoint = { index, state ->
                                if(state!="skipped") execution.put("step",index).put("status",state)
                                if(state!="dispatching") execution.put("steps",run.log)
                                runtime.store.save(agent,"execution",execution)
                            },
                            retry = { attempt -> delay(FlowSteps.RETRY_DELAYS_MS[attempt-1]) },
                            effect = { step, sid ->
                                scopes.forEach { if(!runtime.hasGrant(agent,it)) throw ApiError(403,"agent_not_allowed","Owner must grant $it to this agent") }
                                FlowSteps.scopeOf(step.getString("type"))?.let { runtime.authorize(agent,it) }
                                execute(agent,definition,record,step,sid,run.results,photos,triggerJson,execution.getString("id"))
                            }
                        )
                    }
                } catch(e:TimeoutCancellationException) {
                    run.timedOut()
                }
                val outcome=run.status; val error=run.error; val log=run.log; val cost=run.cost
                runtime.store.save(agent,"execution",execution.put("status",outcome).put("steps",log).put("finished_at",System.currentTimeMillis()).put("cost_usd",cost).apply { if(error!=null) put("error",error) })
                runtime.store.emit(agent,if(outcome=="completed") "workflow.completed" else "workflow.failed",id)
                return JSONObject().put("execution_id",execution.getString("id")).put("status",outcome).put("steps",log).apply { if(error!=null) put("error",error) }
            } catch(e:Exception) {
                runtime.store.save(agent,"execution",execution.put("status","unknown_outcome").put("finished_at",System.currentTimeMillis()))
                // Only lost authority blocks the workflow; an interrupted or crashed run is marked and later starts still work.
                if(e is ApiError && e.code in BLOCKING) synchronized(runtime.store) {
                    val latest=runtime.store.require(id,agent,"workflow")
                    if(latest.optString("status")!="paused") runtime.store.save(agent,"workflow",latest.put("status","blocked").put("blocked_reason",e.code))
                }
                throw e
            }
        } finally {
            if(runningJob!=null) active.remove(id,runningJob)
            mutex.unlock()
            runCatching {
                runtime.store.trimFinished(agent,"execution",HISTORY_KEEP,setOf("completed","unknown_outcome","failed","cancelled"))
                runtime.store.trimFinished(agent,"outbox",HISTORY_KEEP,setOf("acknowledged","delivered","accepted","failed","cancelled","expired","unknown_outcome"))
            }
            runtime.changed()
        }
    }

    /** Runs one step and returns its result (photos stay in [photos], referred to as "photo:<step id>"). */
    private suspend fun execute(agent:String,definition:JSONObject,record:JSONObject,rawStep:JSONObject,sid:String,results:Map<String,JSONObject>,photos:MutableMap<String,ByteArray>,trigger:JSONObject,runId:String):JSONObject {
        val step = FlowSteps.hostStep(rawStep)
        val signed = rawStep.optString("type") == "service"
        fun text(field:String,max:Int) = FlowSteps.textOf(step.opt(field),results,trigger,max)
        return when(step.getString("type")) {
            "notice" -> runtime.interaction(agent,JSONObject().put("kind","notice").put("title",text("title",120).ifBlank { definition.optString("name") }).put("text",text("text",4096))
                .apply { if(step.optString("priority")=="urgent") put("priority","urgent") })
                .let { JSONObject().put("interaction_id",it.getString("id")) }
            "speak" -> runtime.tools.action(agent,JSONObject().put("type","speak").put("text",text("text",600)).put("allow_cloud",step.optBoolean("allow_cloud",false)))
            "hear" -> runtime.tools.hear(agent, step)
            "ring" -> { runtime.tools.action(agent,JSONObject().put("type","ring").put("duration_seconds",step.optInt("seconds",10))); JSONObject() }
            "alert" -> runtime.delivery.trigger(agent,JSONObject().put("destinations",step.getJSONArray("destinations")).put("text",text("text",1024).ifBlank { definition.optString("name") }),fromWorkflow=true)
                .let { JSONObject().put("alert_id",it.optString("id")) }
            "event" -> { runtime.store.emit(agent,"workflow.signal",record.getString("id")); JSONObject() }
            "context" -> { runtime.authorize(agent, "context.read"); runtime.contextVault.status() }
            "set_mode" -> {
                val from=runtime.modes.current()
                val changed=runtime.modes.set(step.getString("mode"),Modes.By("flow",definition.optString("name").ifBlank { runtime.getAgent(agent).name },agent))
                JSONObject().put("mode",step.getString("mode")).put("from",from).put("changed",changed)
            }
            "lookup" -> {
                val table=appletTable(agent,step.getString("table"))
                val scan=cc.bridgit.app.applets.AppletTableStore.of(runtime.app).scan(agent,table.id,FlowConditions.sinceMs(step,java.time.ZonedDateTime.now()),step.optJSONObject("where"),step.optInt("limit",1))
                JSONObject().put("rows",JSONArray(scan.rows)).put("count",scan.count).put("row",scan.rows.firstOrNull() ?: JSONObject.NULL)
            }
            "send" -> {
                val data=JSONObject(); step.optJSONObject("data")?.let { d -> for(k in d.keys()) data.put(k,FlowSteps.fillValue(d.opt(k),results,trigger) ?: JSONObject.NULL) }
                val photo=step.opt("photo")?.let { sendPhoto(agent,FlowSteps.fillValue(it,results,trigger),photos) }
                withContext(Dispatchers.IO) { runtime.delivery.send(agent,step.getString("to"),data,text("text",Connections.MAX_TEXT),photo,step.optBoolean("wait_reply"),definition.optString("name"),runId) }
            }
            "wait" -> { delay(step.optInt("seconds",1)*1000L); JSONObject() }
            "stop" -> JSONObject()
            "photo" -> {
                val camera=step.optString("camera","rear")
                if (camera.startsWith("lan:")) {
                    try { runtime.app.lanCameras.get(camera) }
                    catch (_: cc.bridgit.app.lancamera.CameraFailure) { throw ApiError(404,"camera_unavailable","That local camera was removed") }
                }
                val interactive=cc.bridgit.app.capture.CapturePresentationRules.interactiveFlow(trigger.optString("caller"))
                val bytes=if(camera.startsWith("lan:") && (step.has("view") || interactive)) {
                    val app=runtime.app
                    fun allowed() {
                        runtime.authorize(agent,"camera.snapshot")
                        val principal=app.agents.get(agent)
                        if (principal==null || principal.suspended || !principal.allows("lan_camera") || !app.settings.isOn("lan_camera") ||
                            !app.captureNotificationsOn() || app.applets.forAgent(agent)?.let {
                                val state = cc.bridgit.app.applets.AppletLifecycle.state(it)
                                state != "running" && !(state == "paused" && trigger.optString("caller") in setOf("button", "agent"))
                            } == true)
                            throw ApiError(403,"agent_not_allowed","Local camera access changed.")
                    }
                    suspend fun take(): ByteArray {
                        allowed()
                        if (step.has("view")) return app.cameraViews.capture(app,agent,step.getString("view"),camera,::allowed)
                        return sampler.read(listOf("camera"),null,camera,"Workflow photo",agent).photo
                            ?: throw ApiError(409,"camera_unavailable","The local camera did not return a photo.")
                    }
                    val result=if(interactive) cc.bridgit.app.capture.VisibleCapture.take(app,agent,camera,externalPhoto=::take).photo
                        ?: throw ApiError(409,"capture_cancelled","No photo accepted") else take()
                    allowed()
                    result
                } else if(interactive) {
                    runtime.authorize(agent,"camera.snapshot")
                    cc.bridgit.app.capture.VisibleCapture.take(runtime.app,agent,camera).photo ?: throw ApiError(409,"capture_cancelled","No photo accepted")
                } else {
                    val sample=sampler.read(listOf("camera"),null,camera,"Workflow: ${definition.optString("name")}",agent)
                    sample.photo ?: throw ApiError(409,"camera_unavailable","No photo: ${sample.reading.optJSONObject("camera")?.optString("reason")?.ifEmpty { null } ?: "the camera didn't return one"}")
                }
                photos[sid]=bytes
                // keep: true stores it in the applet's own folder; "photo" stays the in-run ref, so ask_model still works.
                val kept=if(step.optBoolean("keep")) try { cc.bridgit.app.applets.AppletTableStore.of(runtime.app).keepPhoto(agent,sid,bytes) } catch(e:cc.bridgit.app.applets.AppletTables.Refused) { throw ApiError(409,e.code,e.message ?: e.code) } else null
                JSONObject().put("photo","photo:$sid").put("taken_at",isoAt(System.currentTimeMillis())).put("camera",camera).apply { if(kept!=null) put("kept",kept) }
            }
            "record" -> if(step.has("for_each")) recordRows(agent,runId,step,results,trigger) else recordRow(agent,runId,step,results,trigger)
            "update" -> updateRow(agent,runId,step,results,trigger)
            "kitchen" -> {
                if (signed) {
                    val instance = runtime.app.applets.forAgent(agent) ?: throw ApiError(409,"edition_review_required","Install the reviewed package edition before capturing a frame.")
                    cc.bridgit.app.applets.LibraryVision.context(instance,record,required=true)
                }
                val observing=step.optString("mode")=="observe"
                val timing = FlowSteps.cookingTiming(step)
                if(observing && !cc.bridgit.app.applets.ChefHelper.watching(runtime.app,agent,timing.sessionS * 1000L)) return JSONObject().put("skipped",true)
                val ref=cellValue(step.opt("photo"),null,results,trigger) as? String
                val bytes=if(observing) {
                    runtime.authorize(agent,"camera.snapshot")
                    val settings = cc.bridgit.app.applets.ChefHelper.settings(runtime.app,agent)
                    val camera = cc.bridgit.app.applets.ChefHelper.camera(settings)
                    sampler.read(listOf("camera"),null,camera,"Workflow: ${definition.optString("name")}",agent).photo?.also {
                        val latest = cc.bridgit.app.applets.ChefHelper.settings(runtime.app,agent)
                        if (cc.bridgit.app.applets.ChefHelper.camera(latest) != camera || latest?.optLong("started_at") != settings?.optLong("started_at")) return JSONObject().put("skipped",true)
                        cc.bridgit.app.applets.ChefHelper.notePreview(runtime.app,agent,it,camera,settings?.optLong("started_at") ?: 0,timing.sessionS * 1000L)
                    }
                } else ref?.let { cc.bridgit.app.applets.AppletTableStore.of(runtime.app).photoFile(agent,it)?.readBytes() }
                if(bytes==null) throw ApiError(404,"photo_not_found","No kitchen photo")
                countPaid(agent,record.getString("id"),definition)
                withContext(Dispatchers.IO) { cc.bridgit.app.applets.ChefHelper.read(runtime.app,agent,record,bytes,step.optString("mode"),step.optString("area"),timing,signed) }
            }
            "appraise" -> appraise(agent,definition,record,step,results,trigger,signed)
            "share" -> {
                val built=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cc.bridgit.app.applets.AppletExport.export(runtime.app,agent,step) }
                cc.bridgit.app.applets.AppletExport.share(runtime.app,built)
                built.json()
            }
            "ask_model" -> askModel(agent,definition,record,step,results,photos,trigger)
            "ask_owner" -> {
                val wait=step.optInt("wait_s",300)
                val item=runtime.interaction(agent,JSONObject().put("kind","question").put("title",text("title",120).ifBlank { definition.optString("name") }).put("text",text("text",4096))
                    .put("choices",step.getJSONArray("choices")).put("expires_seconds",wait))
                val qid=item.getString("id"); val end=System.currentTimeMillis()+wait*1000L
                var latest=item
                while(latest.optString("status")=="pending" && System.currentTimeMillis()<end) { delay(500); latest=runtime.store.require(qid,agent,"interaction") }
                if(latest.optString("status")=="answered") JSONObject().put("interaction_id",qid).put("choice",latest.optString("answer")).put("timed_out",false)
                else JSONObject().put("interaction_id",qid).put("timed_out",true)
            }
            else -> throw ApiError(422,"invalid_workflow","Unknown step")
        }
    }

    /**
     * What a run's conditions read (docs/applet-reach.md D1): the phone's clock and zone, mode, charging, battery and
     * network, a location fix when a zone or a sun test without coordinates needs one (fetched once per run, before the
     * first test), and the applet's own tables.
     */
    private inner class Facts(val agent:String,val id:String,val record:JSONObject,val needs:FlowConditions.Needs):FlowConditions.Facts {
        override var now:java.time.ZonedDateTime=java.time.ZonedDateTime.now(); private set
        private var fix:Pair<Double,Double>?=null
        private var sun:Pair<Double,Double>?=record.optDouble("sun_lat",Double.NaN).let { lat -> record.optDouble("sun_lon",Double.NaN).let { lon -> if(lat.isNaN()||lon.isNaN()) null else lat to lon } }
        /** The same readings at the time a step's test runs. */
        fun at():Facts { now=java.time.ZonedDateTime.now(); return this }
        suspend fun prepare() {
            if(!(needs.zone || (needs.sunPosition && sun==null))) return
            fix=runCatching { runtime.authorize(agent,"location"); runtime.tools.read(agent,"location.fix",JSONObject()) }.getOrNull()?.let { it.getDouble("latitude") to it.getDouble("longitude") }
            val f=fix
            if(needs.sunPosition && sun==null && f!=null) {
                val rough=Math.round(f.first*10)/10.0 to Math.round(f.second*10)/10.0; sun=rough
                synchronized(runtime.store) { runtime.store.find(id,agent,"workflow")?.let { runtime.store.save(agent,"workflow",it.put("sun_lat",rough.first).put("sun_lon",rough.second)) } }
            }
        }
        override fun mode()=runtime.modes.current()
        override fun location()=fix
        override fun sunPosition()=sun
        override fun phone(reading:String):Any? = when(reading) {
            "charging","battery" -> runtime.app.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { i ->
                if(reading=="charging") i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0
                else { val level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1); val scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,100); if(level<0||scale<=0) null else level*100/scale }
            }
            "network" -> runCatching { networkKind(runtime.app) }.getOrNull()
            else -> null
        }
        private fun table(name:String)=runCatching { appletTable(agent,name) }.getOrNull()
        override fun count(table:String,sinceMs:Long?,where:JSONObject?):Int? = table(table)?.let { cc.bridgit.app.applets.AppletTableStore.of(runtime.app).scan(agent,it.id,sinceMs,where).count }
        override fun sum(table:String,column:String,sinceMs:Long?,where:JSONObject?):Double? = table(table)?.let { cc.bridgit.app.applets.AppletTableStore.of(runtime.app).scan(agent,it.id,sinceMs,where,sumColumn=column).sum }
    }

    /**
     * The JPEG a send step attaches: a photo step's result ({"photo":"photo:<id>"} or that ref) from this run, or a
     * kept photo. Scaled to 1280 px on the longest side and at most 1 MB (A4).
     */
    private fun sendPhoto(agent:String,v:Any?,photos:Map<String,ByteArray>):ByteArray {
        val ref=when(v) { is JSONObject -> v.optString("photo").ifEmpty { v.optString("kept") }; is String -> v; else -> "" }
        val bytes=when {
            ref.startsWith("photo:") -> photos[ref.removePrefix("photo:")]
            ref.startsWith("kept:") -> cc.bridgit.app.applets.AppletTableStore.of(runtime.app).photoFile(agent,ref)?.readBytes()
            else -> null
        } ?: throw ApiError(404,"photo_not_found","The send has no photo: ${if(ref.isEmpty()) "the photo step didn't run or took none" else "$ref isn't on this phone"}")
        return sendJpeg(bytes)
    }

    private fun appletTable(agent:String,tableId:String):cc.bridgit.app.applets.AppletTables.Table {
        val instance=runtime.app.applets.forAgent(agent) ?: throw ApiError(404,"no_table","Only an installed applet's workflow can save rows")
        val tables=try { cc.bridgit.app.applets.AppletTables.parse(instance.optJSONObject("definition")?.optJSONObject("processing") ?: JSONObject()) }
            catch(e:cc.bridgit.app.applets.AppletPackage.Bad) { throw ApiError(404,"no_table","The applet's tables can't be read: ${e.message}") }
        return tables.firstOrNull { it.id==tableId } ?: throw ApiError(404,"no_table","The applet has no table $tableId")
    }

    /**
     * A row value from a step: fixed, a `$step` / `$trigger` reference or (with for_each) `$item` of [item]. A kept
     * photo step's "<id>.photo" stands for its kept ref. A box {x0,y0,x1,y1} going into a text column is "x0,y0,x1,y1".
     */
    private fun cellValue(v:Any?,column:cc.bridgit.app.applets.AppletTables.Column?,results:Map<String,JSONObject>,trigger:JSONObject,item:Any?=null):Any? {
        val resolved:Any?=if(v is JSONObject) when {
            v.has("\$step") -> {
                val path=v.optString("\$step"); val parts=path.split('.')
                if(parts.size==2 && parts[1]=="photo" && results[parts[0]]?.has("kept")==true) results[parts[0]]!!.getString("kept")
                else FlowSteps.value(path,results,true,trigger)
            }
            v.has("\$trigger") -> FlowSteps.value(v.optString("\$trigger"),results,false,trigger)
            v.has("\$item") -> FlowSteps.lookup(item,v.optString("\$item").split('.'))
            else -> null
        } else v
        if(column?.type=="photo" && resolved is String && resolved.startsWith("photo:"))
            throw ApiError(422,"invalid_row","${column.label} needs a kept photo: keep the photo: add keep: true to the photo step")
        if(column?.type=="text" && resolved is JSONObject && listOf("x0","y0","x1","y1").all { resolved.opt(it) is Number })
            return listOf("x0","y0","x1","y1").joinToString(",") { String.format(java.util.Locale.ROOT,"%.4f",resolved.getDouble(it)) }
        return resolved
    }

    private fun tableRefused(e:cc.bridgit.app.applets.AppletTables.Refused):Nothing =
        throw ApiError(when(e.code) { "rate_limited","quota_exceeded" -> 429; "row_not_found" -> 404; else -> 422 },e.code,e.message ?: e.code)

    /** `record` with `for_each`: one row per element of a list, all written in one go (none when one fails). */
    private fun recordRows(agent:String,runId:String,step:JSONObject,results:Map<String,JSONObject>,trigger:JSONObject):JSONObject {
        val table=appletTable(agent,step.optString("table"))
        val list=when(val l=step.optJSONObject("for_each")?.optString("\$step")?.let { FlowSteps.value(it,results,true,trigger) }) {
            is JSONArray -> l
            null -> JSONArray()
            else -> throw ApiError(422,"invalid_row","for_each must name a list (it names a ${l.javaClass.simpleName})")
        }
        if(list.length()>FlowSteps.MAX_FOR_EACH) throw ApiError(422,"invalid_row","for_each gave ${list.length()} items; a record step writes at most ${FlowSteps.MAX_FOR_EACH} rows")
        val given=step.optJSONObject("row") ?: JSONObject()
        val rows=(0 until list.length()).map { i ->
            val item=list.opt(i)
            JSONObject().also { row -> for(c in given.keys()) row.put(c,cellValue(given.opt(c),table.columns[c],results,trigger,item) ?: JSONObject.NULL) }
        }
        if(rows.isEmpty()) return JSONObject().put("row_ids",JSONArray()).put("count",0)
        val by=JSONObject().put("type","flow").put("run_id",runId)
        val saved=try { cc.bridgit.app.applets.AppletTableStore.of(runtime.app).addMany(agent,table,rows,by) } catch(e:cc.bridgit.app.applets.AppletTables.Refused) { tableRefused(e) }
        return JSONObject().put("row_ids",JSONArray(saved.map { it.getString("id") })).put("count",saved.size)
    }

    /** An `update` step: changes columns of one row of the applet's table (docs/applet-wave2.md A). */
    private fun updateRow(agent:String,runId:String,step:JSONObject,results:Map<String,JSONObject>,trigger:JSONObject):JSONObject {
        val table=appletTable(agent,step.optString("table"))
        val rowId=(cellValue(step.opt("row"),null,results,trigger) as? String)?.takeIf { it.isNotBlank() } ?: throw ApiError(404,"row_not_found","The step names no row (its row reference is empty)")
        val given=step.optJSONObject("set") ?: JSONObject()
        val set=JSONObject().also { o -> for(c in given.keys()) o.put(c,cellValue(given.opt(c),table.columns[c],results,trigger) ?: JSONObject.NULL) }
        val saved=try { cc.bridgit.app.applets.AppletTableStore.of(runtime.app).update(agent,table,rowId,set,JSONObject().put("type","flow").put("run_id",runId)) }
            catch(e:cc.bridgit.app.applets.AppletTables.Refused) { tableRefused(e) }
        return JSONObject().put("row_id",saved.getString("id"))
    }

    /** The paid-question count of workflow [id] for today, checked against its max_paid_per_day and raised by one. */
    private fun countPaid(agent:String,id:String,definition:JSONObject) {
        val cap=definition.optInt("max_paid_per_day",FlowSteps.DEFAULT_PAID_PER_DAY)
        val today=java.time.LocalDate.now().toString()
        synchronized(runtime.store) {
            val latest=runtime.store.require(id,agent,"workflow")
            val used=if(latest.optString("paid_day")==today) latest.optInt("paid_count") else 0
            if(used>=cap) throw ApiError(429,"paid_cap","This workflow already asked $cap paid questions today (max_paid_per_day)")
            runtime.store.save(agent,"workflow",latest.put("paid_day",today).put("paid_count",used+1))
        }
    }

    /** An `appraise` step: a kept photo → the things in it with prices (POST /v1/images/appraise's model). */
    private suspend fun appraise(agent:String,definition:JSONObject,record:JSONObject,step:JSONObject,results:Map<String,JSONObject>,trigger:JSONObject,signed:Boolean):JSONObject {
        val ref=cellValue(step.opt("photo"),null,results,trigger) as? String
        if(ref!=null && ref.startsWith("photo:")) throw ApiError(422,"invalid_photo","appraise needs a kept photo: add keep: true to the photo step")
        val file=ref?.let { cc.bridgit.app.applets.AppletTableStore.of(runtime.app).photoFile(agent,it) } ?: throw ApiError(404,"photo_not_found","No kept photo ${ref ?: "(the reference is empty)"} on this phone")
        countPaid(agent,record.getString("id"),definition)
        val a=runtime.getAgent(agent)
        val hint=Outbound.Hint("TypeSafe Jev (via Bridgit team backend)","Workflow ${definition.optString("name")}: list the things in a photo",listOf(Categories.IMAGE))
        val appraised=try {
            withContext(Dispatchers.IO) {
                val instance = runtime.app.applets.forAgent(agent) ?: throw ApiError(404,"no_table","Install the applet to appraise its photo")
                val pin = cc.bridgit.app.applets.LibraryVision.context(instance,record,required=signed)
                if (pin != null) runtime.app.budgetSync.confirmEditionBudget(instance,pin)
                fun authorize() = cc.bridgit.app.applets.LibraryVision.verify(runtime.app,agent,instance,record,pin)
                Outbound.on(Outbound.Actor.agent(agent,a.name),hint) {
                    appraiser(runtime.app,file.readBytes(),step.optInt("max_items",20),pin,::authorize)
                }.also { authorize() }
            }
        } catch(f:Jev.Failure) {
            if(f.reason=="budget_reached") throw ApiError(429,"budget_reached",f.message ?: "This applet's spending limit is used up")
            if(f.reason=="service_budget") throw ApiError(429,"paid_cap",f.message ?: "Service budget used up")
            if(f.reason=="applet_budget") throw ApiError(429,"applet_budget",f.message ?: "This applet's budget is used up")
            throw ApiError(502,"model_failed","Jev gave no answer: ${f.message} (${f.reason})")
        }
        return JSONObject().put("items",JSONArray(appraised.items)).put("count",appraised.items.size).put("cost_usd",appraised.costUsd).put("model",appraised.model)
            .apply { appraised.room?.let { put("room",it) } }
    }

    /** A `record` step: one row into a table of the applet this agent belongs to (docs/applet-cards.md). */
    private fun recordRow(agent:String,runId:String,step:JSONObject,results:Map<String,JSONObject>,trigger:JSONObject):JSONObject {
        val tableId=step.optString("table")
        val instance=runtime.app.applets.forAgent(agent) ?: throw ApiError(404,"no_table","Only an installed applet's workflow can save rows")
        val tables=try { cc.bridgit.app.applets.AppletTables.parse(instance.optJSONObject("definition")?.optJSONObject("processing") ?: JSONObject()) }
            catch(e:cc.bridgit.app.applets.AppletPackage.Bad) { throw ApiError(404,"no_table","The applet's tables can't be read: ${e.message}") }
        val table=tables.firstOrNull { it.id==tableId } ?: throw ApiError(404,"no_table","The applet has no table $tableId")
        val given=step.optJSONObject("row") ?: JSONObject()
        val row=JSONObject()
        for(c in given.keys()) {
            val v=given.opt(c)
            val resolved:Any?=if(v is JSONObject) {
                val path=v.optString("\$step")
                when {
                    v.has("\$step") -> {
                        val parts=path.split('.')
                        // A kept photo's ref stands for "<photo step>.photo" in a row, so the row names a file that stays.
                        if(parts.size==2 && parts[1]=="photo" && results[parts[0]]?.has("kept")==true) results[parts[0]]!!.getString("kept")
                        else FlowSteps.value(path,results,true,trigger)
                    }
                    v.has("\$trigger") -> FlowSteps.value(v.optString("\$trigger"),results,false,trigger)
                    else -> null
                }
            } else v
            if(table.columns[c]?.type=="photo" && resolved is String && resolved.startsWith("photo:"))
                throw ApiError(422,"invalid_row","${table.columns[c]!!.label} needs a kept photo: keep the photo: add keep: true to the photo step")
            row.put(c,resolved ?: JSONObject.NULL)
        }
        val by=JSONObject().put("type","flow").put("run_id",runId)
        val saved=try { cc.bridgit.app.applets.AppletTableStore.of(runtime.app).add(agent,table,row,by) }
            catch(e:cc.bridgit.app.applets.AppletTables.Refused) { throw ApiError(if(e.code=="rate_limited"||e.code=="quota_exceeded") 429 else 422,e.code,e.message ?: e.code) }
        return JSONObject().put("row_id",saved.getString("id"))
    }

    private suspend fun askModel(agent:String,definition:JSONObject,record:JSONObject,step:JSONObject,results:Map<String,JSONObject>,photos:Map<String,ByteArray>,trigger:JSONObject):JSONObject {
        if(!cc.bridgit.app.feedback.FeedbackUploader.configured) throw ApiError(503,"team_unavailable","This build can't ask Jev or Clef",mapOf("effect_started" to false))
        val id=record.getString("id")
        val cap=definition.optInt("max_paid_per_day",FlowSteps.DEFAULT_PAID_PER_DAY)
        val today=java.time.LocalDate.now().toString()
        synchronized(runtime.store) {
            val latest=runtime.store.require(id,agent,"workflow")
            val used=if(latest.optString("paid_day")==today) latest.optInt("paid_count") else 0
            if(used>=cap) throw ApiError(429,"paid_cap","This workflow already asked $cap paid questions today (max_paid_per_day)")
            runtime.store.save(agent,"workflow",latest.put("paid_day",today).put("paid_count",used+1))
        }
        val clef=step.getString("model")=="clef"
        val photo=(step.optJSONObject("photo")?.optString("\$step"))?.let { ref -> FlowSteps.value(ref,results,true,trigger) as? String }?.removePrefix("photo:")?.let { photos[it] }
        if(step.has("photo") && photo==null) throw ApiError(409,"camera_unavailable","The photo step gave no photo")
        val ref=if(photo!=null && !clef) "photo-${java.util.UUID.randomUUID().toString().take(8)}" else null
        val state=JSONObject().put("workflow",definition.optString("name")).put("trigger",trigger).put("at",isoAt(System.currentTimeMillis()))
            .apply { if(photo!=null) put("photo",ref ?: "the image sent with this state") }
        val a=runtime.getAgent(agent)
        val hint=Outbound.Hint(if(clef) "Cloudflare Clef (via Bridgit team backend)" else if(step.optString("model")=="glm") "GLM 5.3 Flash (OpenRouter via Bridgit)" else "TypeSafe Jev (via Bridgit team backend)","Workflow ${definition.optString("name")}: the agent's question",
            listOf(Categories.QUESTIONS,Categories.AGENT_STATE)+(if(photo!=null) listOf(Categories.IMAGE) else emptyList()))
        val answer=try {
            withContext(Dispatchers.IO) { Outbound.on(Outbound.Actor.agent(agent,a.name),hint) {
                Jev.ask(runtime.app,state,step.getJSONObject("questions"),route=WatchRules.route(step.getString("model")),
                    assets=if(ref!=null && photo!=null) JSONObject().put(ref,Jev.image(photo)) else null,
                    images=if(clef && photo!=null) listOf(Jev.dataUrl(photo)) else null)
            } }
        } catch(f:Jev.Failure) {
            if(f.reason=="budget_reached") throw ApiError(429,"budget_reached",f.message ?: "This applet's spending limit is used up")
            if(f.reason=="service_budget") throw ApiError(429,"paid_cap",f.message ?: "Service budget used up")
            if(f.reason=="applet_budget") throw ApiError(429,"applet_budget",f.message ?: "This applet's budget is used up")
            throw ApiError(502,"model_failed","${if(clef) "Clef" else "Jev"} gave no answer: ${f.message} (${f.reason})")
        }
        return FlowSteps.answers(answer)
    }
}
/** wifi (or wired), mobile or none, from the real networks under any VPN (Tailscale is always the default network). */
internal fun networkKind(context:Context):String {
    val cm=context.getSystemService(android.net.ConnectivityManager::class.java)
    @Suppress("DEPRECATION") val caps=cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.filter { it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) }
    return when {
        caps.any { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) } -> "wifi"
        caps.any { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) } -> "mobile"
        else -> "none"
    }
}
class WorkflowJobService:JobService() {
    private val jobs=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onStartJob(params:JobParameters):Boolean { jobs.launch { try { (application as BridgeApp).platform.workflows.tick() } finally { jobFinished(params,false) } }; return true }
    override fun onStopJob(params:JobParameters):Boolean { jobs.coroutineContext.cancelChildren(); return true }
    override fun onDestroy() { jobs.cancel(); super.onDestroy() }
}
