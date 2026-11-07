package cc.bridgit.app.ownerapi

import android.content.Intent
import android.os.Build
import android.speech.SpeechRecognizer
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.listen.WakeModel
import cc.bridgit.app.platform.*
import cc.bridgit.app.update.UpdateInstaller
import cc.bridgit.app.update.UpdateLogic
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import kotlinx.coroutines.*
import cc.bridgit.app.capability.appVersion
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Shared resources are device-wide; jobs belong to the requesting agent. No arbitrary download URLs. */
class ResourceDownloads(private val app: BridgeApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("resource_jobs", 0)
    private val running = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val readyUpdates = java.util.concurrent.ConcurrentHashMap<String, UpdateInstaller.Download.Ready>()
    init {
        // A process restart never means a partially completed transfer succeeded.
        prefs.all.keys.forEach { id -> runCatching { JSONObject(prefs.getString(id, "{}")!!).let { row ->
            if (row.optString("status") in setOf("queued", "running", "cancelling")) save(row.put("status", "interrupted").put("error", "process_restarted"))
        } } }
    }
    private fun save(row: JSONObject): JSONObject {
        prefs.edit().putString(row.getString("id"), row.toString()).apply()
        return row
    }
    @Synchronized fun jobs(agent: String): JSONObject = JSONObject().put("jobs", JSONArray(prefs.all.keys.mapNotNull { id ->
        runCatching { JSONObject(prefs.getString(id, "{}")!!) }.getOrNull()?.takeIf { it.optString("agent_id") == agent }
    }.sortedByDescending { it.optLong("created_at") }.take(100)))
    fun job(agent: String, id: String): JSONObject = prefs.getString(id, null)?.let(::JSONObject)
        ?.takeIf { it.optString("agent_id") == agent } ?: throw ErrorCatalog.error("not_found", "No resource job for you.")
    @Synchronized fun cancel(agent: String, id: String): JSONObject {
        val row = job(agent, id)
        if (row.optString("status") in setOf("queued", "running")) {
            save(row.put("status", "cancelling"))
            running[id]?.cancel()
        }
        return job(agent, id)
    }
    private fun progress(agent: String, id: String, percent: Int) = synchronized(this) {
        val row = job(agent, id)
        if (row.optString("status") == "running") save(row.put("percent", percent.coerceIn(0, 100)))
    }
    /** Called only after an owner-approved immutable operation. Resource id, not URL, chooses the source. */
    @Synchronized fun start(agent: String, resource: String, operation: String, language: String? = null): JSONObject {
        validate(resource, operation)
        jobs(agent).getJSONArray("jobs").let { rows ->
            if ((0 until rows.length()).count { rows.getJSONObject(it).optString("status") in setOf("queued", "running", "cancelling") } >= 3)
                throw ErrorCatalog.error("rate_limited", "At most three resource jobs per agent.")
        }
        if (prefs.all.keys.any { key -> runCatching { JSONObject(prefs.getString(key, "{}")!!).let { it.optString("resource") == resource && it.optString("status") in setOf("queued", "running", "cancelling") } }.getOrDefault(false) })
            throw ErrorCatalog.error("conflict", "This resource already has a job.")
        // Bound persisted metadata; only finished jobs are evicted.
        prefs.all.keys.mapNotNull { key -> runCatching { JSONObject(prefs.getString(key, "{}")!!) }.getOrNull() }
            .filter { it.optString("status") !in setOf("queued", "running", "cancelling") }.sortedByDescending { it.optLong("created_at") }
            .drop(100).forEach { prefs.edit().remove(it.getString("id")).apply() }
        val id = "r" + UUID.randomUUID().toString().replace("-", "")
        val row = save(JSONObject().put("id", id).put("agent_id", agent).put("resource", resource).put("operation", operation)
            .put("status", "queued").put("percent", 0).put("created_at", System.currentTimeMillis()))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val parent=currentCoroutineContext().job
            val guard=scope.launch {
                while(isActive) {
                    if(runCatching { checkAccess(agent,resource) }.isFailure) { parent.cancel(); return@launch }
                    delay(250)
                }
            }
            try {
                checkAccess(agent, resource)
                synchronized(this@ResourceDownloads) { if (job(agent,id).optString("status") == "cancelling") throw CancellationException(); save(row.put("status", "running")) }
                val result = perform(agent, id, resource, operation, language)
                currentCoroutineContext().ensureActive()
                synchronized(this@ResourceDownloads) { save(job(agent,id).put("status", if(result.optBoolean("completion_pending")) "provider_pending" else "completed").put("percent",if(result.optBoolean("completion_pending")) JSONObject.NULL else 100).put("result", result).put("finished_at",System.currentTimeMillis())) }
            } catch (e: CancellationException) {
                synchronized(this@ResourceDownloads) { save(job(agent,id).put("status", if(resource.startsWith("translation:")) "wait_cancelled_sdk_may_continue" else "cancelled").put("finished_at",System.currentTimeMillis())) }
            } catch (e: Exception) {
                synchronized(this@ResourceDownloads) { save(job(agent,id).put("status", "failed").put("error", if (e is cc.bridgit.app.api.ApiError) e.code else "resource_failed")
                    .put("message", if (e is cc.bridgit.app.api.ApiError) e.message else "Download failed; inspect resource readiness and retry.").put("finished_at",System.currentTimeMillis())) }
            } finally {
                guard.cancel()
                running.remove(id)
                app.interactions.event(agent, "resource.finished", JSONObject().put("job_id",id).put("status",job(agent,id).optString("status")))
            }
        }
        running[id] = job
        job.invokeOnCompletion { cause -> synchronized(this) {
            val current=runCatching { job(agent,id) }.getOrNull()
            if(current!=null && current.optString("status") in setOf("queued","running","cancelling")) {
                save(current.put("status",if(cause is CancellationException) "cancelled" else "interrupted").put("finished_at",System.currentTimeMillis()))
                app.interactions.event(agent,"resource.finished",JSONObject().put("job_id",id).put("status",current.optString("status")))
            }
            running.remove(id)
        } }
        job.start()
        return JSONObject(row.toString())
    }
    private fun checkAccess(agent: String, resource: String) {
        app.platform.authorize(agent, "owner.configuration")
        val a = app.agents.get(agent) ?: throw ErrorCatalog.error("unauthorized", "Agent removed.")
        if (a.paused || !a.allows("owner_features")) throw ErrorCatalog.error("forbidden", "Owner feature access revoked.")
        if (resource == "wake-english" && !a.allows("wakewords")) throw ErrorCatalog.error("agent_not_allowed", "Wake word access is required.")
        if (resource.startsWith("translation:")) app.platform.authorize(agent,"translation")
    }
    fun stopAgent(agent: String) { running.keys.forEach { id -> if(runCatching { job(agent,id) }.isSuccess) running[id]?.cancel() } }
    fun stopRevoked() { running.keys.forEach { id ->
        val row = prefs.getString(id,null)?.let(::JSONObject) ?: return@forEach
        if (runCatching { checkAccess(row.getString("agent_id"),row.getString("resource")) }.isFailure) running[id]?.cancel()
    } }
    private suspend fun perform(agent: String, id: String, resource: String, operation: String, language: String?): JSONObject = when {
        resource == "wake-english" -> {
            if (operation == "delete") {
                if (app.wake.model.state.value is WakeModel.State.Downloading) throw ErrorCatalog.error("conflict", "Cancel the current speech-model download first.")
                app.settings.setOn(cc.bridgit.app.listen.WakeListener.SWITCH, false)
                if(!app.wake.model.delete()) throw ErrorCatalog.error("conflict","Cancel the current speech-model download first.")
            } else {
                if(app.wake.model.state.value is WakeModel.State.Downloading) throw ErrorCatalog.error("conflict","The speech model is already downloading.")
                val reporter = scope.launch { while (isActive) { runCatching { (app.wake.model.state.value as? WakeModel.State.Downloading)?.let { progress(agent,id,it.percent) } }; delay(300) } }
                try { app.wake.model.download() } finally { reporter.cancel() }
                if (!app.wake.model.ready) throw ErrorCatalog.error("upstream_failed", "Speech model was not installed; retry after checking the connection and storage.")
            }
            JSONObject().put("installed", app.wake.model.ready)
        }
        resource.startsWith("translation:") -> {
            val lang = translation(resource)
            val manager = RemoteModelManager.getInstance()
            val model = TranslateRemoteModel.Builder(lang).build()
            if (operation == "delete") manager.deleteDownloadedModel(model).await()
            else cc.bridgit.app.outbound.Outbound.call("https://dl.google.com/mlkit/translate", hint = cc.bridgit.app.outbound.Outbound.Hint("Google ML Kit", "Download translation model ($lang)", listOf(cc.bridgit.app.outbound.Categories.NONE))) { meter ->
                manager.download(model, DownloadConditions.Builder().requireWifi().build()).await(); meter.status = cc.bridgit.app.outbound.OutboundEntry.HANDED_OFF
            }
            JSONObject().put("installed",manager.isModelDownloaded(model).await()).put("progress_available",false)
        }
        resource == "android-speech" -> SystemModels.requestSpeechDownload(app,language ?: java.util.Locale.getDefault().toLanguageTag()) { progress(agent,id,it) }
        resource == "app-update" -> {
            val check = UpdateInstaller.check(app)
            val manifest = when (check) {
                is UpdateInstaller.Check.Newer -> check.manifest
                is UpdateInstaller.Check.UpToDate -> check.manifest
                is UpdateInstaller.Check.LocalAhead -> check.manifest
                is UpdateInstaller.Check.Refused -> throw ErrorCatalog.error("upstream_failed", "Update check refused: ${check.problem.name.lowercase()}")
            }
            if (operation == "download") {
                if (check !is UpdateInstaller.Check.Newer) throw ErrorCatalog.error("conflict", "No newer update to download.")
                when (val result = UpdateInstaller.download(app,manifest) { progress(agent,id,it) }) {
                    is UpdateInstaller.Download.Ready -> readyUpdates[id] = result
                    is UpdateInstaller.Download.Refused -> throw ErrorCatalog.error("upstream_failed", "Update download refused: ${result.problem.name.lowercase()}")
                }
            }
            JSONObject().put("available_version",manifest.versionName).put("version_code",manifest.versionCode).put("size_bytes",manifest.sizeBytes)
                .put("newer",check is UpdateInstaller.Check.Newer).put("verified",operation == "download").put("install_requires_owner",true)
        }
        else -> throw ErrorCatalog.error("not_supported", "This resource is managed by Android. Use owner.configure action android.setup to open its owner setup screen; installation is not silently performed.")
    }
    fun verifiedUpdate(agent: String, jobId: String): UpdateInstaller.Download.Ready {
        job(agent,jobId).let { if (it.optString("resource") != "app-update" || it.optString("status") != "completed") throw ErrorCatalog.error("conflict","No completed update job.") }
        return readyUpdates[jobId]?.takeIf { it.file.exists() } ?: throw ErrorCatalog.error("not_found","Verified APK is no longer cached; download it again.")
    }
    suspend fun inventory(language:String=java.util.Locale.getDefault().toLanguageTag()): JSONObject {
        val installed = RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()
        val wake = app.wake.model
        val state = wake.state.value
        val rows = JSONArray().put(JSONObject().put("id","wake-english").put("kind","voice_model").put("name",WakeModel.NAME)
            .put("installed",wake.ready).put("state",when(state) { WakeModel.State.Ready -> "ready"; WakeModel.State.Absent -> "not_downloaded"; is WakeModel.State.Downloading -> "downloading"; is WakeModel.State.Failed -> "failed" })
            .put("download_bytes",WakeModel.SIZE).put("disk_bytes",wake.bytesOnDisk()).put("sha256",WakeModel.SHA256)
            .put("percent",(state as? WakeModel.State.Downloading)?.percent ?: JSONObject.NULL)
            .put("error",if (state is WakeModel.State.Failed) "download_failed" else JSONObject.NULL).put("operations",JSONArray(listOf("download","delete"))))
        TranslateLanguage.getAllLanguages().sorted().forEach { lang -> rows.put(JSONObject().put("id","translation:$lang").put("kind","translation_model")
            .put("language",lang).put("installed",lang == "en" || lang in installed).put("bundled",lang == "en").put("wifi_required",true)
            .put("progress_available",false).put("cancel_stops_provider_transfer",false).put("operations",JSONArray(if (lang == "en") emptyList<String>() else listOf("download","delete")))) }
        rows.put(JSONObject().put("id","android-speech").put("kind","system_voice_model").put("engine_available",Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app))
            .put("language_readiness",SystemModels.speech(app,language)).put("manager","android").put("setup_target","speech").put("operations",JSONArray(listOf("download","owner_setup"))))
        rows.put(JSONObject().put("id","android-tts").put("kind","system_voice_data").put("manager","android").put("language_readiness",SystemModels.tts(app,language))
            .put("setup_target","tts").put("operations",JSONArray(listOf("owner_setup"))))
        for (name in listOf("ocr","barcode")) rows.put(JSONObject().put("id","bundled-$name").put("kind","recognition").put("bundled",true)
            .put("installed",true).put("operations",JSONArray()))
        rows.put(JSONObject().put("id","app-update").put("kind","apk").put("installed_version",app.appVersion())
            .put("operations",JSONArray(listOf("check","download","owner_install"))).put("source","official_updater_only"))
        return JSONObject().put("resources",rows).put("jobs_url","/v1/resource-jobs").put("note","Selected Downloads (/v1/downloads) are owner-shared files; scheduled downloads use /v1/schedule and /v1/schedule/assets. Neither installs models.")
    }
    companion object {
        fun translation(resource: String): String = TranslateLanguage.fromLanguageTag(resource.removePrefix("translation:"))
            ?: throw ErrorCatalog.error("invalid_parameter","Unknown translation language.")
        fun validate(resource: String, operation: String) {
            when {
                resource == "wake-english" -> requireOperation(operation,setOf("download","delete"))
                resource.startsWith("translation:") -> { val lang=translation(resource); if(lang == "en") throw ErrorCatalog.error("forbidden","English is bundled and cannot be deleted or downloaded."); requireOperation(operation,setOf("download","delete")) }
                resource == "android-speech" -> requireOperation(operation,setOf("download"))
                resource == "app-update" -> requireOperation(operation,setOf("check","download"))
                else -> throw ErrorCatalog.error("invalid_parameter","Unknown downloadable resource. Read GET /v1/resources.")
            }
        }
        private fun requireOperation(value: String, allowed: Set<String>) { if(value !in allowed) throw ErrorCatalog.error("invalid_parameter","Unsupported resource operation.") }
    }
}
