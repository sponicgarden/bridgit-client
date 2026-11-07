package cc.bridgit.app.ownerapi

import android.content.Intent
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.appdevices.AppDeviceAccess
import cc.bridgit.app.listen.*
import cc.bridgit.app.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Small allowlisted owner changes, immutable and approved through the existing control operation engine. */
class OwnerActions(private val app: BridgeApp) {
    private fun agent(id: String) = app.agents.get(id)?.takeUnless { it.paused }
        ?: throw ErrorCatalog.error("unauthorized","Agent is no longer active.")
    private fun requireCap(id: String, cap: String) {
        if (!agent(id).allows(cap)) throw ErrorCatalog.error("agent_not_allowed","This action needs $cap access.")
    }
    fun review(agent: String, args: JSONObject): JSONObject {
        validate(args)
        requireCap(agent,"owner_features")
        val action = args.getString("action")
        val options = args.getJSONObject("options")
        when {
            action.startsWith("dog.") && !(action == "dog.configure" && !options.has("camera") && !options.has("cameras") && !options.has("microphones") && !options.has("models") && !options.has("camera_search_delays") && !options.has("pan_search") && !options.has("autocenter")) -> {
                requireCap(agent,"camera"); requireCap(agent,"owner_features")
                val requestedCameras=options.optJSONArray("cameras")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
                val microphones=options.optJSONArray("microphones")?.let { a -> (0 until a.length()).map(a::getString) } ?: app.dog.state.value.microphones
                if(cc.bridgit.app.dog.DogAudio.BUILTIN in microphones)requireCap(agent,"mic")
                if(microphones.any { it.startsWith("lan:") }) {requireCap(agent,"lan_camera_audio");requireCap(agent,"lan_camera")}
                val cameras=app.dog.state.value.cameras + requestedCameras + if(options.optString("camera")=="auto" || action=="dog.find_camera" || app.dog.state.value.cameraMode=="auto") app.lanCameras.all().filter { app.appDevices.allows(AppDeviceAccess.DOG,it.id) }.map { it.id } else emptyList()
                if(cameras.any { it.startsWith("lan:") } || options.optString("camera").startsWith("lan:")) {
                    requireCap(agent,"lan_camera")
                    if(action in setOf("dog.start","dog.find_camera","dog.configure") && !app.settings.isOn("lan_camera")) throw ErrorCatalog.error("capability_disabled","Local network cameras are off.")
                }
                if(action in setOf("dog.start","dog.find_camera","dog.configure") && (!app.settings.isOn("camera") || !app.captureNotificationsOn())) throw ErrorCatalog.error("capability_disabled","Enable camera and capture notifications first.")
            }
            action == "tour.start" -> listOf("tour","location","speaker","interactions").forEach { requireCap(agent,it) }
            action.startsWith("wake.") -> requireCap(agent,"wakewords")
            action == "sounds.configure" -> requireCap(agent,"sounds")
        }
        val update=if(action=="updates.install") app.resourceDownloads.verifiedUpdate(agent,options.getString("job_id")).manifest else null
        // The review contains the exact safe options; passwords, tokens and arbitrary fields are rejected.
        return JSONObject().put("action",action).put("options",JSONObject(options.toString())).put("title",title(action)).put("description",description(args)+(update?.let { "\nUpdate version: ${it.versionName}" } ?: "")).put("approval","Authorized by the existing owner.configuration grant; OS consent and sign-in remain owner actions.").apply { update?.let { put("update_version",it.versionName); put("update_sha256",it.sha256) } }
    }
    suspend fun execute(agent: String, args: JSONObject): JSONObject {
        review(agent,args) // recheck after approval, including after a key/grant or device change
        val o=args.getJSONObject("options")
        return when(val action=args.getString("action")) {
            "service.model.set" -> withContext(Dispatchers.IO) { cc.bridgit.app.judgment.ModelPreferences.set(app,o.getString("applet_id"),o.getString("task_key"),o.opt("model_id").takeUnless { it == JSONObject.NULL } as? String) }
            "service.budget.set" -> withContext(Dispatchers.IO) { cc.bridgit.app.judgment.ServiceBudgets.set(app,o.getString("service"),o.getJSONArray("limits")) }
            "resource.download","resource.delete","resource.check" -> app.resourceDownloads.start(agent,o.getString("resource"),action.substringAfter('.'),o.optString("language").ifEmpty { null })
            "dog.configure" -> {
                val camera = o.optString("camera",app.dog.state.value.cameraMode)
                if(o.has("camera") && camera.startsWith("lan:")) {
                    requireCap(agent,"lan_camera")
                    app.lanCameras.get(camera)
                    if(!app.appDevices.allows(AppDeviceAccess.DOG,camera)) throw ErrorCatalog.error("forbidden","The owner must add this camera to Pet monitor first.")
                }
                // validate every option before applying any
                val models = o.optJSONArray("models")?.let { (0 until it.length()).map(it::getString) }
                val cameras=o.optJSONArray("cameras")?.let { a -> (0 until a.length()).map(a::getString) }
                val microphones=o.optJSONArray("microphones")?.let { a -> (0 until a.length()).map(a::getString) }
                val searchDelays = o.optJSONObject("camera_search_delays")
                searchDelays?.keys()?.forEach { cameraId ->
                    if (cameraId.startsWith("lan:")) {
                        requireCap(agent,"lan_camera")
                        app.lanCameras.get(cameraId)
                        if (!app.appDevices.allows(AppDeviceAccess.DOG,cameraId)) throw ErrorCatalog.error("forbidden","Add this camera to Pet monitor first.")
                    }
                }
                ((cameras ?: emptyList())+(microphones ?: emptyList())).filter { it.startsWith("lan:") }.forEach {
                    app.lanCameras.get(it)
                    if(!app.appDevices.allows(AppDeviceAccess.DOG,it))throw ErrorCatalog.error("forbidden","The owner must add this camera to Pet monitor first.")
                }
                microphones?.forEach {
                    if(it==cc.bridgit.app.dog.DogAudio.BUILTIN && (!app.settings.isOn("mic") || app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED))throw ErrorCatalog.error("capability_disabled","Enable the built-in microphone on the device first.")
                    if(it.startsWith("lan:") && (!app.settings.isOn("lan_camera") || !app.settings.isOn("lan_camera_audio")))throw ErrorCatalog.error("capability_disabled","Enable camera microphone capture first.")
                }
                if(o.has("camera")) app.dog.setCameraMode(camera)
                cameras?.let { app.dog.setCameras(it) }
                if(o.has("sampling_all_s")) app.dog.setSamplingAll(if(o.isNull("sampling_all_s")) null else o.getInt("sampling_all_s"))
                searchDelays?.keys()?.forEach { id -> app.dog.setCameraSearchDelay(id, if(searchDelays.isNull(id)) null else searchDelays.getInt(id)) }
                microphones?.let { app.dog.setMicrophones(it) }
                models?.let { app.dog.setModels(it.toSet()) }
                if(o.has("pan_search")) app.dog.setPanSearch(o.getBoolean("pan_search"))
                if(o.has("autocenter")) app.dog.setAutoCenter(o.getBoolean("autocenter"))
                if (o.has("backend_email") && o.has("user_alias")) app.remoteView.setDogPortal(o.getString("backend_email"),o.getString("user_alias"))
                if (o.has("personal_backend")) app.remoteView.setDogBackend(o.getBoolean("personal_backend"))
                if (o.has("photo_sharing")) app.remoteView.setDogPhoto(o.getBoolean("photo_sharing"))
                val result=dogControlStatus()
                if(o.optBoolean("personal_backend")) withContext(Dispatchers.IO) { val code=app.remoteView.linkCode(); result.put("link_url",code.url).put("link_code",code.code).put("expires_at",code.expiresAt) }
                result
            }
            "applet.configure" -> {
                val id = o.getString("applet")
                val inst = app.applets.byPackage(id) ?: app.applets.get(id)
                    ?: throw ErrorCatalog.error("not_found","No installed applet $id. Use its package id, for example kitchen-cleanliness.")
                if (inst.optString("native") == "dog") throw ErrorCatalog.error("invalid_parameter","Pet monitor has its own portal: use dog.configure.")
                val agentId = inst.optString("agent_id")
                if (app.watches.list(agentId).none { it.spec.tally != null }) throw ErrorCatalog.error("invalid_parameter","This applet has no counting watch to show on a portal.")
                val pkg = inst.getString("package_id")
                if (o.has("backend_email") && o.has("user_alias")) app.remoteView.setDogPortal(o.getString("backend_email"),o.getString("user_alias"))
                try { app.remoteView.packages.configure(pkg, if (o.has("personal_backend")) o.getBoolean("personal_backend") else null, if (o.has("photo_sharing")) o.getBoolean("photo_sharing") else null) }
                catch (e: IllegalStateException) { throw ErrorCatalog.error("conflict", e.message ?: "Try again later.") }
                app.remoteView.syncPackagesSoon()
                val result = app.remoteView.packages.status(pkg).put("name", inst.optString("name")).put("portal_email", app.remoteView.portalEmail ?: JSONObject.NULL)
                if (o.optBoolean("personal_backend") && !app.remoteView.portalLinked()) withContext(Dispatchers.IO) { val code=app.remoteView.linkCode(); result.put("link_url",code.url).put("link_code",code.code).put("expires_at",code.expiresAt) }
                result
            }
            "dog.start" -> {
                if(!app.settings.listenerEnabled) throw ErrorCatalog.error("capability_disabled","The owner paused the listener.")
                val camera=app.dog.state.value.cameraMode
                if(app.dog.state.value.cameras.any { it.startsWith("lan:") }) {
                    requireCap(agent,"lan_camera")
                    if(!app.settings.isOn("lan_camera")) throw ErrorCatalog.error("capability_disabled","Local network cameras are off.")
                }
                if((camera=="auto" || app.dog.state.value.cameras.any { !it.startsWith("lan:") }) && !app.settings.isOn("camera")) throw ErrorCatalog.error("capability_disabled","Camera is off.")
                if(!app.captureNotificationsOn()) throw ErrorCatalog.error("notifications_required","Enable capture notifications first.")
                if(app.dog.state.value.models.isEmpty()) throw ErrorCatalog.error("invalid_parameter","Choose at least one model.")
                withContext(Dispatchers.Main) { cc.bridgit.app.BridgeService.start(app); app.dog.start() }
                dogControlStatus()
            }
            "dog.stop" -> { app.dog.stop(); dogControlStatus() }
            "dog.find_camera" -> { app.dog.findBestCamera(); dogControlStatus() }
            "dog.clear_history" -> { app.dog.clearHistory(); dogControlStatus() }
            "tour.start" -> {
                if(app.tours.current()!=null) throw ErrorCatalog.error("conflict","A City tour already runs.")
                val missing=app.tours.missing()
                if(missing.isNotEmpty()) throw ErrorCatalog.error("access_needed",missing.joinToString("; "))
                withContext(Dispatchers.Main) { app.tours.start(agent,o.strings("kinds"),o.optInt("interval_s",60)).json() }
            }
            "wake.configure" -> {
                val others=runCatching { JSONArray(app.settings.wakeWords).let { a -> (0 until a.length()).map { WakeWord.fromJson(a.getJSONObject(it)) }.filter { it.agentId!=agent } } }.getOrDefault(emptyList())
                val next=others.toMutableList()
                val words=o.getJSONArray("words")
                for(i in 0 until words.length()) {
                    val row=words.getJSONObject(i); val w=WakeWord(row.getString("phrase"),agent,row.optBoolean("send_clip",false))
                    WakeRules.problem(next,w,{ app.agents.get(it)!=null },if(app.wake.model.ready) app.wake.model::knows else null)?.let { throw ErrorCatalog.error("invalid_parameter",it) }
                    next.add(w)
                }
                app.settings.wakeWords=JSONArray(next.map { it.toJson() }).toString(); app.wake.sync()
                app.wake.status(agent)
            }
            "sounds.configure" -> {
                app.settings.soundsConfig=if(o.optBoolean("enabled",true)) SoundConfig(agent,o.optString("sensitivity","normal"),o.optBoolean("send_clip",false)).toJson().toString() else ""
                app.wake.sync(); app.wake.soundStatus(agent)
            }
            "remote.configure" -> {
                if(o.has("enabled")) app.remoteView.setEnabled(o.getBoolean("enabled"))
                if(o.has("dog_photo")) app.remoteView.setDogPhoto(o.getBoolean("dog_photo"))
                remoteStatus()
            }
            "callback.configure" -> {
                if(o.optBoolean("remove",false)) app.callbacks.remove(agent)
                else {
                    val url=o.getString("url")
                    val checked=cc.bridgit.app.core.CallbackRules.check(url,cc.bridgit.app.core.CallbackRules.tailnetDomain(app.listener.value.magicDns),app.settings.allowInternetCallbacks)
                    if(checked !is cc.bridgit.app.core.CallbackRules.Check.Ok) throw ErrorCatalog.error("invalid_parameter","Callback must follow the owner's tailnet/public HTTPS policy.")
                    // Setting from an agent rotates a secret, deliberately shown only to the approving owner.
                    app.callbacks.set(agent,url)
                }
                callbackStatus(agent)
            }
            "delivery.subscribe" -> { val id=o.optString("destination"); if(id.isEmpty()) app.platform.store.setting("callback:$agent","",true) else app.platform.delivery.subscribe(agent,id); deliveryStatus(agent) }
            "updates.install" -> {
                requireOwnerForeground()
                val ready=app.resourceDownloads.verifiedUpdate(agent,o.getString("job_id"))
                if(cc.bridgit.app.update.UpdateInstaller.reverify(app,ready) !is cc.bridgit.app.update.UpdateInstaller.Download.Ready) throw ErrorCatalog.error("conflict","Cached update failed verification; download again.")
                withContext(Dispatchers.Main) { app.startActivity(Intent(app,OwnerSetupActivity::class.java).putExtra("target","install_update").putExtra("agent_id",agent).putExtra("job_id",o.getString("job_id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                JSONObject().put("status","awaiting_android_install_confirmation").put("installed",false).put("version",ready.manifest.versionName)
            }
            "updates.offers" -> { cc.bridgit.app.update.UpdateDialog.setAutoOffers(app,o.getBoolean("enabled")); settingsStatus() }
            "android.setup" -> {
                requireOwnerForeground()
                withContext(Dispatchers.Main) { app.startActivity(Intent(app,OwnerSetupActivity::class.java).putExtra("target",o.getString("target")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                JSONObject().put("status","owner_setup_opened").put("completed",false).put("target",o.getString("target"))
            }
            else -> throw ErrorCatalog.error("invalid_parameter","Unsupported owner action.")
        }
    }
    private fun requireOwnerForeground() {
        if(!cc.bridgit.app.platform.ControlCenterActivity.foreground) throw ErrorCatalog.error("access_needed","Keep Agent permissions open to finish this approved setup request.")
    }
    private fun dogControlStatus():JSONObject = app.dog.state.value.let { JSONObject().put("running",it.running).put("camera",it.cameraMode).put("cameras",JSONArray(it.cameras)).put("microphones",JSONArray(it.microphones)).put("models",JSONArray(it.models)).put("camera_search_delays",app.dog.cameraSearchDelays()).put("sampling",app.dog.sampling()).put("pan_search",app.dog.state.value.panSearchEnabled).put("searching",app.dog.state.value.searching).put("autocenter",app.dog.autoCenterJson()) }
    fun dogStatus(s:cc.bridgit.app.dog.DogStatus,readings:List<cc.bridgit.app.dog.Reading>,day:cc.bridgit.app.dog.DogDay): JSONObject {
        return cc.bridgit.app.remoteview.RemoteViewDog.summary(s,day,readings,app.remoteView.dogPhoto)
            .put("personal_backend",app.remoteView.dogBackend).put("backend_deletion_pending",app.remoteView.dogPendingDelete).put("photo_sharing",app.remoteView.dogPhoto)
            .put("backend_status",app.remoteView.backendStatus.value).put("portal_alias",app.remoteView.portalAlias ?: JSONObject.NULL).put("portal_email",app.remoteView.portalEmail ?: JSONObject.NULL).put("models",JSONArray(s.models)).put("camera",s.cameraMode).put("cameras",JSONArray(s.cameras)).put("microphones",JSONArray(s.microphones)).put("feeding",app.dog.feeding.status()).put("microphone_status",s.microphoneStatus).put("audio_readings",JSONArray(s.audioReadings.map { JSONObject().put("source",it.source).put("at",it.at).put("sound",it.sound).put("confidence",it.confidence).put("checked_on_device",it.onDevice) })).put("barking",JSONObject().put("now",s.bark.now).put("episodes_today",s.bark.episodes).put("barking_ms_today",s.bark.hitMs).put("last_bark",s.bark.lastHit ?: JSONObject.NULL).put("unchecked_today",s.bark.skipped).put("checks_today",s.bark.checks).put("paused_until",if(s.bark.pausedUntil>0) s.bark.pausedUntil else JSONObject.NULL)).put("scanning",s.scanning)
            .put("next_scan_at",s.nextScanAt).put("frame_at",s.frameAt).put("frame_url",if(s.frame!=null) "/v1/applets/dog-monitor/frame" else JSONObject.NULL)
            .put("interval_s",cc.bridgit.app.dog.DogMonitor.INTERVAL_MS/1000)
            .put("camera_search_delay_default_s",cc.bridgit.app.appdevices.SensorSearchDelays.DEFAULT_SECONDS)
            .put("camera_search_delays",app.dog.cameraSearchDelays()).put("sampling",app.dog.sampling()).put("pan_search",app.dog.state.value.panSearchEnabled).put("searching",app.dog.state.value.searching)
            .put("autocenter",app.dog.autoCenterJson())
    }
    fun remoteStatus(): JSONObject = JSONObject().put("configured",app.remoteView.configured).put("enabled",app.remoteView.enabled)
        .put("linked",app.remoteView.linked.value!=null).put("dog_photo",app.remoteView.dogPhoto).put("viewer_issuance","owner_only")
        .put("link_requires_google_consent",true).put("owner_page",cc.bridgit.app.remoteview.RemoteView.PAGE)
    fun callbackStatus(agent: String): JSONObject = JSONObject().put("set",app.callbacks.get(agent)!=null)
        .put("host",app.callbacks.get(agent)?.host ?: JSONObject.NULL).put("secret_readable",false).put("test","POST /v1/callback/test")
    fun deliveryStatus(agent: String): JSONObject {
        val store=app.platform.store
        val rows=store.list(agent,"outbox",100).map { row -> JSONObject().put("id",row.getString("id")).put("destination_id",row.optString("destination_id"))
            .put("status",row.optString("status")).put("attempts",row.optInt("attempts")).put("next_at",row.optLong("next_at"))
            .put("error",row.optString("error").ifEmpty { null } ?: JSONObject.NULL) }
        return JSONObject().put("subscribed",store.setting("callback:$agent").isNotEmpty()).put("outbox",JSONArray(rows))
    }
    fun settingsStatus(): JSONObject {
        val keys=cc.bridgit.app.judgment.OwnKeys(app)
        return JSONObject().put("update_offers",cc.bridgit.app.update.UpdateDialog.autoOffersOn(app)).put("remote",remoteStatus())
            .put("own_keys",JSONArray(cc.bridgit.app.judgment.OwnKeys.PROVIDERS.map { JSONObject().put("provider",it.id).put("configured",keys.has(it.id)).put("readable",false).put("write_url","/v1/settings/ai-keys") }))
            .put("configuration_grant","GET /v2/capabilities → owner.configuration").put("grants_owner_only",false).put("grant_authority","owner_or_existing_super_agent").put("access_url","/v1/owner/configuration/access").put("initial_super_grant_owner_only",true)
    }
    companion object {
        val NAMES=listOf("service.model.set","service.budget.set","resource.download","resource.delete","resource.check","dog.configure","applet.configure","dog.start","dog.stop","dog.find_camera","dog.clear_history","tour.start","wake.configure","sounds.configure","remote.configure","callback.configure","delivery.subscribe","updates.offers","updates.install","android.setup")
        val SETUP_TARGETS=listOf("speech","tts","remote_link","camera_enrollment","permissions","updates","own_keys")
        fun title(action:String):String = when(action) {
            "service.model.set" -> "Applet model preference"
            "service.budget.set" -> "Service budget"
            "resource.download" -> "Download required data"; "resource.delete" -> "Remove downloaded data"; "resource.check" -> "Check for an app update"
            "dog.start" -> "Start Pet monitor"; "dog.stop" -> "Stop Pet monitor"; "dog.configure" -> "Change Pet monitor"
            "applet.configure" -> "Change an applet’s web portal"
            "dog.find_camera" -> "Find the best Pet monitor camera"; "dog.clear_history" -> "Clear Pet monitor history"
            "tour.start" -> "Start a City tour"; "wake.configure" -> "Change this agent’s wake words"; "sounds.configure" -> "Change sound listening"
            "remote.configure" -> "Change remote viewing"; "callback.configure" -> "Change this agent’s callback"
            "delivery.subscribe" -> "Change event delivery"; "updates.offers" -> "Change update reminders"; "updates.install" -> "Install the verified app update"
            else -> "Open owner setup"
        }
        fun description(args:JSONObject):String {
            val action=args.getString("action"); val o=args.getJSONObject("options")
            val details=when(action) {
                "service.model.set" -> "${o.getString("applet_id")}: ${o.getString("task_key")} → ${if(o.isNull("model_id")) "Use default" else o.getString("model_id")}"
                "service.budget.set" -> "${o.getString("service")}: ${o.getJSONArray("limits")}"
                "resource.download","resource.delete","resource.check" -> {
                    val resource=o.getString("resource")
                    val name=when(resource) { "wake-english" -> "Offline English wake-word model (about 40 MB)"; "app-update" -> "Bridgit’s official app update"; "android-speech" -> "Android offline speech data"; else -> "Translation language: ${resource.removePrefix("translation:")}" }
                    name+o.optString("language").takeIf { it.isNotEmpty() }?.let { "\nLanguage: $it" }.orEmpty()+"\nDownloading never enables listening or grants microphone access."+(if(action=="resource.delete" && resource=="wake-english") "\nWake words will turn off until this model is downloaded again." else "")
                }
                "dog.configure" -> listOfNotNull(if(o.has("backend_email")) "Portal email: ${o.getString("backend_email")}. Account verification is required." else null,if(o.has("user_alias")) "Portal address: https://bridgit.cc/${o.getString("user_alias")}/myapps" else null,if(o.has("personal_backend")) if(o.getBoolean("personal_backend")) "Enable a personal backend for Pet monitor on this device only. New readings and metrics are uploaded; shared photos are kept for 30 days (up to 1 GB across your devices). Invited viewers may see this applet. Clef/Jev send images for remote analysis. No other applet/device is enabled." else "Device only: stop remote AI and uploads, revoke viewers and delete server history. Offline deletion stays pending until confirmed. Local records stay." else null, if(o.has("photo_sharing")) if(o.getBoolean("photo_sharing")) "Share photos in this instance’s private gallery." else "Delete this instance’s server photos and stop sharing new ones." else null, if(o.has("camera")) "Camera: ${o.getString("camera")}" else null, if(o.has("cameras")) "Cameras taking turns: ${o.strings("cameras").joinToString(", ")}" else null, if(o.has("microphones")) "Microphones: ${o.strings("microphones").joinToString(", ")}. The built-in microphone listens on the phone for loud sounds while monitoring runs; only a loud sound's clip goes to Clef to tell barking (at most 6 checks an hour). Camera microphones are sampled for 5 seconds about every 10 minutes (every sampling interval, default 20 seconds, for the food view). Clips are not retained." else null,if(o.has("sampling_all_s")) "Pet monitor sampling interval for all cameras and microphones on this device: ${if(o.isNull("sampling_all_s")) "default (${cc.bridgit.app.appdevices.SensorSearchDelays.DEFAULT_SECONDS})" else o.get("sampling_all_s")} seconds" else null,if(o.has("camera_search_delays")) "Pet monitor sampling interval per camera on this device (seconds; null follows the all-cameras value): ${o.getJSONObject("camera_search_delays")}" else null,if(o.has("pan_search")) if(o.getBoolean("pan_search")) "Allow bounded pan/tilt searches near the last confirmed pet view after one minute missing. Up to one minute per search, five minute cooldown. Selected models analyze each view." else "Turn off automatic pan search." else null,if(o.has("autocenter")) if(o.getBoolean("autocenter")) "Auto-centre pan/tilt cameras on the pet: when Jev sees it well off the middle, steer toward it and photograph again at once, up to ${cc.bridgit.app.lancamera.AutoCenter.MAX_STEPS} steps per run and ${cc.bridgit.app.lancamera.AutoCenter.HOURLY_STEPS} extra Jev photos an hour." else "Turn off auto-centring: cameras stay where they are." else null,if(o.has("models")) "Models: ${o.strings("models").joinToString(", ")}" else null).joinToString("\n")
                "applet.configure" -> listOfNotNull("Applet: ${o.getString("applet")}",if(o.has("backend_email")) "Portal email: ${o.getString("backend_email")}. Account verification is required." else null,if(o.has("user_alias")) "Portal address: https://bridgit.cc/${o.getString("user_alias")}/myapps" else null,if(o.has("personal_backend")) if(o.getBoolean("personal_backend")) "Show this applet on your private web portal, from this device only. Today’s counts, its latest answer, the notices it sent and its pause state are uploaded about once a minute; shared photos are kept for 30 days (up to 1 GB across your devices). You can pause it from the portal. Invited viewers may see this applet. No other applet is enabled." else "Device only: stop uploads, revoke viewers and delete this applet’s server history. Offline deletion stays pending until confirmed. Counts on the phone stay." else null, if(o.has("photo_sharing")) if(o.getBoolean("photo_sharing")) "Share the photo of its latest check (small, at most one a minute)." else "Delete this applet’s server photos and stop sharing new ones." else null).joinToString("\n")
                "dog.start" -> "The selected camera will take repeated photos and ask the selected decision models. Normal capture permissions still apply."
                "dog.find_camera" -> "Test the cameras assigned to Pet monitor and use the best view. This takes new photos."
                "dog.clear_history" -> "Permanently remove the Pet monitor’s saved readings and totals."
                "tour.start" -> "Topics: ${o.strings("kinds").joinToString(", ")}\nUpdates every ${o.optInt("interval_s",60)} seconds."
                "wake.configure" -> "Only this agent’s phrases change; other agents’ phrases stay.\n"+(0 until o.getJSONArray("words").length()).joinToString("\n") { i -> val word=o.getJSONArray("words").getJSONObject(i); "${word.getString("phrase")} — send audio: ${if(word.optBoolean("send_clip")) "yes" else "no"}" }
                "sounds.configure" -> "${if(o.optBoolean("enabled",true)) "Send loud-sound events to this agent, replacing the previous recipient." else "Turn sound-event listening off."}\nSensitivity: ${o.optString("sensitivity","normal")}\nSend audio: ${if(o.optBoolean("send_clip")) "yes" else "no"}"
                "remote.configure" -> listOfNotNull(if(o.has("enabled")) if(o.getBoolean("enabled")) "Enable remote viewing (owner sign-in still required)." else "Turn remote viewing off and delete its server link and activity." else null,if(o.has("dog_photo")) if(o.getBoolean("dog_photo")) "Share Pet monitor photos in its private gallery only if this applet’s personal backend is enabled (kept 30 days, up to 1 GB across your devices)." else "Stop sharing the Pet monitor photo." else null).joinToString("\n")
                "callback.configure" -> if(o.optBoolean("remove")) "Remove this agent’s callback." else "Send this agent’s events to:\n${o.getString("url")}\nThe signing secret will be replaced; share the refreshed setup prompt with your agent."
                "delivery.subscribe" -> if(o.getString("destination").isEmpty()) "Stop this agent’s event delivery." else "Send this agent’s scoped events to approved destination ${o.getString("destination")}."
                "updates.offers" -> if(o.getBoolean("enabled")) "Offer available app updates." else "Stop automatic update offers. Manual update checks still work."
                "updates.install" -> "Use verified download ${o.getString("job_id")}. Its original checksum and app signature are checked again. Android may ask you to confirm installation."
                "android.setup" -> "Open ${o.getString("target").replace('_',' ')} setup. You still complete any Android permission or account sign-in prompts."
                else -> "Stop the monitor; keep saved history."
            }
            return title(action)+"\n\n"+details+"\n\nApproval is for this request only; it grants no additional permissions."
        }
        /** Pure preflight: reject unknown fields (particularly credentials) before any operation is persisted. */
        fun validate(args: JSONObject) {
            args.only("action","options")
            val action=args.text("action",64)
            val o=args.optJSONObject("options") ?: throw ErrorCatalog.error("invalid_parameter","options must be an object.")
            fun bool(k:String) { if(o.has(k) && o.opt(k) !is Boolean) throw ErrorCatalog.error("invalid_parameter","$k must be boolean.") }
            when(action) {
                "service.model.set" -> {
                    o.only("applet_id","task_key","model_id")
                    if(!o.has("model_id") || (!o.isNull("model_id") && o.opt("model_id") !is String)) throw ErrorCatalog.error("invalid_parameter","model_id must be a registered ID or explicit null to clear.")
                    cc.bridgit.app.judgment.ModelPreferences.validate(o.text("applet_id",63),o.text("task_key",80),o.opt("model_id").takeUnless { it==JSONObject.NULL } as? String)
                }
                "service.budget.set" -> { o.only("service","limits"); cc.bridgit.app.judgment.ServiceBudgets.validate(o.text("service",32),o.optJSONArray("limits") ?: throw ErrorCatalog.error("invalid_parameter","limits must be an array.")) }
                "resource.download","resource.delete","resource.check" -> { o.only("resource","language"); if(o.has("language") && !o.text("language",40).matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))) throw ErrorCatalog.error("invalid_parameter","Use a language tag such as en-US."); ResourceDownloads.validate(o.text("resource",80),action.substringAfter('.')) }
                "dog.configure" -> {
                    o.only("camera","cameras","microphones","models","camera_search_delays","sampling_all_s","pan_search","autocenter","personal_backend","photo_sharing","backend_email","user_alias"); bool("personal_backend"); bool("photo_sharing"); bool("pan_search"); bool("autocenter")
                    if(o.optBoolean("personal_backend") || o.has("backend_email") || o.has("user_alias")) {
                        if(!o.text("backend_email",200).matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) throw ErrorCatalog.error("invalid_parameter","Ask the user for the portal email.")
                        if(!o.text("user_alias",30).matches(Regex("[a-z][a-z0-9-]{2,29}"))) throw ErrorCatalog.error("invalid_parameter","Ask the user for a portal alias.")
                    }
                    if (o.has("camera_search_delays")) {
                        val delays = o.optJSONObject("camera_search_delays") ?: throw ErrorCatalog.error("invalid_parameter","camera_search_delays must be an object.")
                        if(delays.length() > 20) throw ErrorCatalog.error("invalid_parameter","At most 20 camera delays.")
                        delays.keys().forEach { id ->
                            if(!cc.bridgit.app.dog.DogCamera.isCamera(id) || (!delays.isNull(id) && !cc.bridgit.app.appdevices.SensorSearchDelays.validSeconds(delays.opt(id))))
                                throw ErrorCatalog.error("invalid_parameter","Camera delays must be whole seconds from 10 to 3600, or null to follow sampling_all_s.")
                        }
                    }
                    if (o.has("sampling_all_s") && !o.isNull("sampling_all_s") && !cc.bridgit.app.appdevices.SensorSearchDelays.validSeconds(o.opt("sampling_all_s")))
                        throw ErrorCatalog.error("invalid_parameter","sampling_all_s must be whole seconds from 10 to 3600, or null to restore ${cc.bridgit.app.appdevices.SensorSearchDelays.DEFAULT_SECONDS}.")
                    if(o.has("camera") && o.text("camera",80).let { it !in listOf("auto","rear","front") && !it.matches(Regex("lan:[a-zA-Z0-9-]{1,60}")) }) throw ErrorCatalog.error("invalid_parameter","Unknown camera choice.")
                    if(o.has("camera") && o.has("cameras")) throw ErrorCatalog.error("invalid_parameter","Choose camera or cameras, not both.")
                    if(o.has("cameras") && o.strings("cameras",20).let { it.isEmpty() || it.size!=o.getJSONArray("cameras").length() || it.any { c -> !cc.bridgit.app.dog.DogCamera.isCamera(c) } }) throw ErrorCatalog.error("invalid_parameter","Choose camera IDs: rear, front or lan:<id>.")
                    if(o.has("microphones") && o.strings("microphones",10).let { it.size!=o.getJSONArray("microphones").length() || it.any { id -> !cc.bridgit.app.dog.DogAudio.valid(id) } }) throw ErrorCatalog.error("invalid_parameter","Choose builtin or lan:<id> microphone IDs; an empty array turns them off.")
                    if(o.has("models") && o.strings("models",2).let { it.isEmpty() || it.any { m -> m !in listOf("jev","clef") } }) throw ErrorCatalog.error("invalid_parameter","Choose Jev and/or Clef.")
                }
                "applet.configure" -> {
                    o.only("applet","personal_backend","photo_sharing","backend_email","user_alias"); bool("personal_backend"); bool("photo_sharing")
                    if(!o.text("applet",64).matches(Regex("[a-z0-9][a-z0-9-]{1,63}"))) throw ErrorCatalog.error("invalid_parameter","applet must be an installed applet's package id or instance id.")
                    if(!o.has("personal_backend") && !o.has("photo_sharing")) throw ErrorCatalog.error("invalid_parameter","Choose personal_backend and/or photo_sharing.")
                    if(o.has("backend_email") != o.has("user_alias")) throw ErrorCatalog.error("invalid_parameter","Send backend_email and user_alias together.")
                    if(o.has("backend_email")) {
                        if(!o.text("backend_email",200).matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) throw ErrorCatalog.error("invalid_parameter","Ask the user for the portal email.")
                        if(!o.text("user_alias",30).matches(Regex("[a-z][a-z0-9-]{2,29}"))) throw ErrorCatalog.error("invalid_parameter","Ask the user for a portal alias.")
                    }
                }
                "dog.start","dog.stop","dog.find_camera","dog.clear_history" -> o.only()
                "tour.start" -> { o.only("kinds","interval_s"); if(o.strings("kinds",8).let { it.isEmpty() || it.any { k -> k !in cc.bridgit.app.tour.TourKinds.all } }) throw ErrorCatalog.error("invalid_parameter","Choose tour topics."); if(o.whole("interval_s",60,30,300) !in cc.bridgit.app.tour.TourKinds.intervals) throw ErrorCatalog.error("invalid_parameter","Use a supported tour interval.") }
                "wake.configure" -> {
                    o.only("words"); val a=o.optJSONArray("words") ?: throw ErrorCatalog.error("invalid_parameter","words must be a list.")
                    if(a.length()>WakeRules.MAX_WORDS) throw ErrorCatalog.error("invalid_parameter","Too many wake words.")
                    for(i in 0 until a.length()) { val w=a.optJSONObject(i) ?: throw ErrorCatalog.error("invalid_parameter","Each word must have phrase and send_clip."); w.only("phrase","send_clip"); w.text("phrase",80); if(w.has("send_clip") && w.opt("send_clip") !is Boolean) throw ErrorCatalog.error("invalid_parameter","send_clip must be boolean.") }
                }
                "sounds.configure" -> { o.only("enabled","sensitivity","send_clip"); bool("enabled"); bool("send_clip"); if(o.has("sensitivity") && o.text("sensitivity") !in SoundConfig.SENSITIVITIES) throw ErrorCatalog.error("invalid_parameter","Unknown sensitivity.") }
                "remote.configure" -> { o.only("enabled","dog_photo"); bool("enabled"); bool("dog_photo") }
                "callback.configure" -> { o.only("url","remove"); bool("remove"); if(!o.optBoolean("remove",false)) o.text("url",2048) }
                "delivery.subscribe" -> { o.only("destination"); o.text("destination",128) }
                "updates.install" -> { o.only("job_id"); o.text("job_id",80) }
                "updates.offers" -> { o.only("enabled"); if(o.opt("enabled") !is Boolean) throw ErrorCatalog.error("invalid_parameter","enabled is required.") }
                "android.setup" -> { o.only("target"); if(o.text("target") !in SETUP_TARGETS) throw ErrorCatalog.error("invalid_parameter","Unknown setup screen.") }
                else -> throw ErrorCatalog.error("invalid_parameter","Unknown owner action.")
            }
        }
    }
}
