package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.platform.*
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object OwnerFeatureEndpoints {
    private fun read(path:String, summary:String, returns:String) = EndpointSpec("GET",path,"owner_features",summary,example="GET /v1$path",returns=returns)
    val FEATURES=read("/owner/features","Owner feature coverage, approval actions and security boundaries","actions[{action, options}], HTTP/MCP discovery links, owner-only operations; no credentials")
    val APPLETS=read("/applets","Native and agent-built applets visible to this agent","native applet ids, state URLs, your tally-watch applets; no other agent's tour")
    val DOG=read("/applets/dog-monitor","Pet monitor status, models, camera, today's totals and recent readings","running, models, camera, cameras, camera_search_delays (effective seconds per camera), camera_search_delay_default_s=20, sampling {all_s, default_s=20, min_s, max_s, cameras (effective seconds), overrides (cameras with their own value), photo_interval_s}, feeding (saved food-view and sampled eating-sound response; setup on device), pan_search, searching, scanning, error code, today totals, recent verdicts; does not take a photo")
    val DOG_HISTORY=EndpointSpec("GET","/applets/dog-monitor/history","owner_features","Pet monitor readings and daily summaries, without taking new photos",
        query=listOf(Param("limit","integer","Newest 1-500 readings (default 100)")),example="GET /v1/applets/dog-monitor/history?limit=100",returns="readings, today, timezone")
    val DOG_FRAME=EndpointSpec("GET","/applets/dog-monitor/frame","owner_features","Pet monitor's existing latest frame; never opens the camera",media=true,
        example="GET /v1/applets/dog-monitor/frame",returns="JPEG bytes; X-Bridgit-Frame-At; not_found when no frame, capability_disabled when camera access is off")
    val RESOURCES=EndpointSpec("GET","/resources","owner_features","Required downloads and bundled/system dependencies",
        query=listOf(Param("language","string","BCP-47 language tag for Android speech/TTS readiness, e.g. en-US; default phone language")),
        example="GET /v1/resources?language=en-US",returns="resources[id,kind,installed,state,download_bytes,disk_bytes,percent,operations], jobs_url; Android speech/TTS are owner-managed; OCR/barcode bundled")
    val JOBS=read("/resource-jobs","Your resource download/check/delete jobs","jobs[id,resource,operation,status,percent,result,error]; completed download means verified where supported")
    val JOB=read("/resource-jobs/{id}","One of your resource jobs","job status/progress/result/error; no APK filesystem paths")
    val CANCEL=EndpointSpec("DELETE","/resource-jobs/{id}","owner_features","Cancel your resource job; SDK-managed transfers may continue after the waiter is cancelled",
        example="DELETE /v1/resource-jobs/r123",returns="status; cancellation is best effort for Android/ML Kit managed transfers")
    val REMOTE=read("/remote-view","Remote viewing and photo-sharing policy status","configured,enabled,linked,dog_photo,viewer_issuance=owner_only,link_requires_google_consent; never link codes or viewer tokens")
    val CALLBACK=read("/callback","Your callback configuration without its secret","set,host,secret_readable=false,test path")
    val DELIVERY=read("/delivery","Your alert/callback delivery status and permitted destination ids","outbox status, attempts, next_at, last error; destinations id/name/enabled only, no URL or signing secrets; alert list URL")
    val MODELS=read("/services/models","Model tasks and explicit account overrides; defaults apply live to every instance of an applet", "tasks,overrides,inheritance[account_override,applet_default,shared_default],defaults_resolved_live")
    val MODEL=read("/services/models/{applet_id}/{task_key}","Effective model and choices for a stable applet package; set/clear with service.model.set", "applet_id,task_key,source,models,available_models,override,inherits,enabled,scope")
    val SERVICES=read("/services/budgets","Paid external services, account spending and limits; set or clear with owner/configure service.budget.set","services[{service,name,limits[{id,usd,per,spent_usd,resets_at}],timezone,state}]")
    val SETTINGS=read("/owner/settings","Supplemental owner settings, model-key presence and consent requirements","update_offers,remote,own_keys provider/configured flags,configuration_grant; no key suffixes or tokens")
    val CONFIGURE=EndpointSpec("POST","/owner/configure","owner_features","Apply an app-owned configuration change with the separate owner.configuration grant; Android consent and sign-in still require the owner",
        idempotent=true,idempotencyRequired=true,privateFields=setOf("options"),body=listOf(Param("action","string","Action from GET /v1/owner/features",true,OwnerActions.NAMES),Param("options","object","Exact action options from GET /v1/owner/features; unknown fields and all credential fields refused",true)),
        example="POST /v1/owner/configure {\"action\":\"resource.download\",\"options\":{\"resource\":\"wake-english\"}}",
        returns="durable v2 operation: accepted/completed/failed; inspect /v2/operations/{id} or MCP bridgit_operation_get; resource operations return a resource job id",
        errors=listOf("invalid_parameter","agent_not_allowed","forbidden"))
    val FOREGROUND_TARGETS=read("/app/foreground/targets","Discover available Bridgit screens, your cards, installed applet pages and consumer portals","screens,cards,applets,portals with type and id; app.foreground grant required; portal targets also need applet_portals; min_interval_ms,max_per_minute")
    val FOREGROUND=EndpointSpec("POST","/app/foreground","owner_features","Bring Bridgit itself forward using the owner-granted app.foreground scope",
        idempotent=true,idempotencyRequired=true,body=listOf(Param("reason","string","Required 1-256 character audit reason; 1-second minimum interval, maximum 12 switches/minute",true),Param("target","object","Optional {type:screen|card|applet|portal,id:<discovered id>} from GET /v1/app/foreground/targets")),
        example="POST /v1/app/foreground {\"reason\":\"Restart screenshot service for the morning review\"}",
        returns="durable v2 operation; result.foreground is observed requested target visibility, notification_posted is a fallback requiring an owner tap; error=foreground_user_action_required|foreground_not_confirmed|device_locked when not visible; platform=android; never unlocks or grants Android consent")
    val PROFILE=EndpointSpec("GET","/agent/profile","status","Your display name and artwork",example="GET /v1/agent/profile",returns="self-supplied name and artwork; no verified identity claim")
    val PROFILE_SET=EndpointSpec("POST","/agent/profile","status","Update your self-supplied name and artwork",idempotent=true,privateFields=setOf("icon_base64","wordmark_base64"),
        body=listOf(Param("name","string","1-24 letters, digits, space, _ or -"),Param("icon_base64","string","PNG, at most 128 KiB; null removes"),Param("wordmark_base64","string","PNG, at most 128 KiB; null removes")),
        example="POST /v1/agent/profile {\"name\":\"Soly\"}",returns="updated profile; unverified self-description")
    val ALL=listOf(FOREGROUND_TARGETS,FOREGROUND,MODELS,MODEL,SERVICES,PROFILE,PROFILE_SET,FEATURES,APPLETS,DOG,DOG_HISTORY,DOG_FRAME,RESOURCES,JOBS,JOB,CANCEL,REMOTE,CALLBACK,DELIVERY,SETTINGS,CONFIGURE) + ConfigurationEndpoints.ALL
}

class OwnerFeaturesModule(app:BridgeApp):Capability(app) {
    override val id="owner_features"
    override val titleRes=R.string.cap_owner_features
    override val descriptionRes=R.string.cap_owner_features_desc
    override val group="status"
    override val endpoints=OwnerFeatureEndpoints.ALL
    override fun details()=JSONObject().put("writes","owner.configuration: unattended app-owned configuration at /v1/owner/configuration; native app-owned actions use the same grant; real OS/account steps remain; secrets write-only")
    override fun Route.install(api:ApiServer)=with(api) {
        installConfiguration(api, app)
        endpoint(OwnerFeatureEndpoints.PROFILE) { Reply.Json(cc.bridgit.app.AgentBranding.json(app.policy.agents().first { it.id==agent!!.id })) }
        endpoint(OwnerFeatureEndpoints.PROFILE_SET) { Reply.Json(cc.bridgit.app.AgentBranding.json(app.policy.updateProfile(agent!!.id,body))) }
        endpoint(OwnerFeatureEndpoints.FEATURES) { Reply.Json(featureCatalog()) }
        endpoint(OwnerFeatureEndpoints.APPLETS) {
            val a=agent!!
            val native=JSONArray()
            if(a.allows("camera")) native.put(JSONObject().put("id","dog_monitor").put("status_url","/v1/applets/dog-monitor"))
            if(a.allows("tour")) native.put(JSONObject().put("id","city_tour").put("status_url","/v1/tour").put("owner_starts",true))
            Reply.Json(JSONObject().put("native",native).put("agent_apps",if(a.allows("watches")) JSONArray(app.watches.tallies().filter { it.agentId==a.id }.map { JSONObject().put("id",it.id).put("label",it.spec.label).put("status_url","/v1/watches/${it.id}") }) else JSONArray()))
        }
        endpoint(OwnerFeatureEndpoints.DOG) {
            val state=app.dog.state.value
            val (readings,day)=app.dog.tracker.apiSnapshot(System.currentTimeMillis())
            dogAccess(agent!!,state,readings)
            Reply.Json(app.ownerFeatures.dogStatus(state,readings,day))
        }
        endpoint(OwnerFeatureEndpoints.DOG_HISTORY) {
            val state=app.dog.state.value
            val (readings,day)=app.dog.tracker.apiSnapshot(System.currentTimeMillis())
            dogAccess(agent!!,state,readings)
            val limit=query("limit")?.toIntOrNull()?.takeIf { it in 1..500 } ?: if(query("limit")==null) 100 else throw ErrorCatalog.error("invalid_parameter","limit must be 1-500.")
            Reply.Json(JSONObject().put("readings",JSONArray(readings.takeLast(limit).map { cc.bridgit.app.remoteview.RemoteViewDog.reading(it).put("camera",it.camera) }))
                .put("today",app.ownerFeatures.dogStatus(state,readings,day).optJSONObject("today")).put("timezone",java.util.TimeZone.getDefault().id))
        }
        endpoint(OwnerFeatureEndpoints.DOG_FRAME) {
            val s=app.dog.state.value
            dogAccess(agent!!,s,emptyList())
            Reply.Bytes(s.frame ?: throw ErrorCatalog.error("not_found","No existing Pet monitor frame."),"image/jpeg",mapOf("X-Bridgit-Frame-At" to s.frameAt.toString()))
        }
        endpoint(OwnerFeatureEndpoints.RESOURCES) { Reply.Json(withContext(Dispatchers.IO) { app.resourceDownloads.inventory(query("language")?.takeIf { it.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")) } ?: if(query("language")==null) java.util.Locale.getDefault().toLanguageTag() else throw ErrorCatalog.error("invalid_parameter","Invalid language tag.")) }) }
        endpoint(OwnerFeatureEndpoints.JOBS) { Reply.Json(app.resourceDownloads.jobs(agent!!.id)) }
        endpoint(OwnerFeatureEndpoints.JOB) { Reply.Json(app.resourceDownloads.job(agent!!.id,path("id") ?: "")) }
        endpoint(OwnerFeatureEndpoints.CANCEL) { Reply.Json(app.resourceDownloads.cancel(agent!!.id,path("id") ?: "")) }
        endpoint(OwnerFeatureEndpoints.REMOTE) { Reply.Json(app.ownerFeatures.remoteStatus()) }
        endpoint(OwnerFeatureEndpoints.CALLBACK) { Reply.Json(app.ownerFeatures.callbackStatus(agent!!.id)) }
        endpoint(OwnerFeatureEndpoints.DELIVERY) {
            app.platform.authorize(agent!!.id,"alerts")
            Reply.Json(app.ownerFeatures.deliveryStatus(agent!!.id).put("destinations",JSONArray(app.platform.store.list("owner","destination").map { JSONObject().put("id",it.getString("id")).put("name",it.getString("name")).put("enabled",it.optBoolean("enabled")) })).put("alerts","MCP bridgit_alert_list"))
        }
        endpoint(OwnerFeatureEndpoints.MODELS) { Reply.Json(withContext(Dispatchers.IO) { cc.bridgit.app.judgment.ModelPreferences.read(app) }) }
        endpoint(OwnerFeatureEndpoints.MODEL) { Reply.Json(withContext(Dispatchers.IO) { cc.bridgit.app.judgment.ModelPreferences.read(app,path("applet_id").orEmpty(),path("task_key").orEmpty()) }) }
        endpoint(OwnerFeatureEndpoints.SERVICES) { Reply.Json(withContext(Dispatchers.IO) { cc.bridgit.app.judgment.ServiceBudgets.read(app) }) }
        endpoint(OwnerFeatureEndpoints.SETTINGS) { Reply.Json(app.ownerFeatures.settingsStatus()) }
        endpoint(OwnerFeatureEndpoints.FOREGROUND_TARGETS) { Reply.Json(app.platform.foreground.targets(agent!!.id)) }
        endpoint(OwnerFeatureEndpoints.FOREGROUND) {
            cc.bridgit.app.platform.AppForeground.validate(body)
            Reply.Json(app.platform.mutate(agent!!.id,"app.foreground",body,header("Idempotency-Key") ?: "",agent!!.keyHash))
        }
        endpoint(OwnerFeatureEndpoints.CONFIGURE) {
            OwnerActions.validate(body)
            Reply.Json(app.platform.mutate(agent!!.id,"owner.configure",body,header("Idempotency-Key") ?: throw ErrorCatalog.error("invalid_parameter","Idempotency-Key is required for owner changes; reuse the same UUID on retries."),agent!!.keyHash))
        }
    }
    private fun dogAccess(agent:cc.bridgit.app.core.Agent,state:cc.bridgit.app.dog.DogStatus,readings:List<cc.bridgit.app.dog.Reading>) {
        if(!agent.allows("camera") || !app.settings.isOn("camera")) throw ErrorCatalog.error("forbidden","Pet monitor reads require enabled camera access.")
        if(state.microphones.contains("builtin") && (!agent.allows("mic") || !app.settings.isOn("mic"))) throw ErrorCatalog.error("forbidden","This Pet monitor includes microphone observations; microphone access is required.")
        if(state.microphones.any { it.startsWith("lan:") } && (!agent.allows("lan_camera_audio") || !app.settings.isOn("lan_camera_audio"))) throw ErrorCatalog.error("forbidden","This Pet monitor includes camera microphone observations; camera audio access is required.")
        val ids=listOfNotNull(app.dog.feeding.camera.takeIf { it.isNotEmpty() }) + state.cameras + state.microphones + state.audioReadings.map { it.source } + listOfNotNull(state.frameCamera) + readings.map { it.camera }
        if(ids.any { it.startsWith("lan:") } && (!agent.allows("lan_camera") || !app.settings.isOn("lan_camera"))) throw ErrorCatalog.error("forbidden","This Pet monitor uses local cameras; their access is required too.")
    }
    private fun featureCatalog():JSONObject {
        val options=mapOf(
            "resource.download" to "{resource: wake-english | translation:<language> | app-update | android-speech, language?:en-US}","resource.delete" to "{resource: wake-english | translation:<language>}","resource.check" to "{resource: app-update}",
            "service.model.set" to "{applet_id: stable package ID, task_key, model_id: registered model ID or null to clear; account override shared by all instances; no override means live applet/shared default}",
            "service.budget.set" to "{service: jev|clef|glm|locate|appraise|chef|speech|images, limits: [{id,usd:0.01..100,per:hour|day|week|month}]; [] clears limits; applies across all account phones in addition to applet limits",
            "dog.configure" to "{camera?: auto|rear|front|lan:<id> OR cameras?: [rear,front,lan:<id>], pan_search?: boolean (default off; bounded nearby pan/tilt search after 60 seconds missing; 5 minute cooldown), microphones?: [builtin,lan:<id>] (default off; 5-second samples to Clef; [] disables), models?: [jev,clef], sampling_all_s?: 10..3600 seconds or null to restore the default 20 (the sampling interval every camera and microphone follows: Auto tests the other cameras after this long without seeing the pet, and the food-view microphone takes a sample this often), camera_search_delays?: {rear|front|lan:<id>:10..3600 seconds or null to follow sampling_all_s; device-local and per applet; Auto only}, personal_backend?:boolean, photo_sharing?:boolean, backend_email?:string, user_alias?:string (email and alias required when enabling)}",
            "applet.configure" to "{applet: an installed package applet's package id (e.g. kitchen-cleanliness) or instance id, personal_backend?: boolean (show it on the owner's web portal: today's tally counts, latest answer, fired notices, pause state; portal Pause pauses the applet), photo_sharing?: boolean (default on: the latest answered check's photo, ≤640 px, ≤100 KB, at most one a minute, kept 30 days), backend_email?: string, user_alias?: string (together; the device's portal account, shared with Pet monitor). Returns link_url when this device still has to be linked to that account. Needs a counting (tally) watch.}",
            "tour.start" to "{kinds: [history,architecture,environment,food,art,anything], interval_s?:30|60|120|300}","wake.configure" to "{words:[{phrase,send_clip?:boolean}]} (only your own words; preserves others)",
            "sounds.configure" to "{enabled?:boolean,sensitivity?:quiet|normal|loud,send_clip?:boolean}","remote.configure" to "{enabled?:boolean,dog_photo?:boolean}",
            "callback.configure" to "{url:string} OR {remove:true}; signing secret remains owner-only", "delivery.subscribe" to "{destination:id} or empty string to unsubscribe", "updates.offers" to "{enabled:boolean}","updates.install" to "{job_id:completed app-update download job; exact cached APK is reverified, then Android asks to install}","android.setup" to "{target:speech|tts|remote_link|camera_enrollment|permissions|updates|own_keys}")
        return JSONObject().put("unattended_configuration", "/v1/owner/configuration").put("actions",JSONArray(OwnerActions.NAMES.map { JSONObject().put("action",it).put("options",options[it] ?: "{}").put("approval_required",false) }))
            .put("configure","POST /v1/owner/configure").put("mcp","owner_configure").put("control_mcp","bridgit_owner_configure")
            .put("capabilities","/v2/capabilities").put("grant","owner.configuration (owner enables and grants it in Agent permissions)")
            .put("operations","/v2/operations/{id}").put("boundaries",JSONArray(listOf("No passwords, model keys, callback secrets or viewer tokens returned", "No grant creation or approval by agents", "Camera enrollment, Google sign-in and OS permission dialogs remain owner actions", "Network discovery/enrollment/router/device control: /v1/lan/discover and /v1/lan/devices; native assignments: /v1/app-devices. Each has its separate feature switch and grant (API revision 45+)")))
    }
}
