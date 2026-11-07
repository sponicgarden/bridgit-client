package cc.bridgit.app.ownerapi

import android.content.Intent
import android.os.Build
import android.speech.*
import android.speech.tts.TextToSpeech
import cc.bridgit.app.BridgeApp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** OS engines are inspected without recording audio. Missing data is never reported as ready. */
object SystemModels {
    suspend fun speech(app:BridgeApp,language:String):JSONObject = withContext(Dispatchers.Main) {
        val result=JSONObject().put("language",language).put("manager","android").put("download_supported",Build.VERSION.SDK_INT>=33)
        if(Build.VERSION.SDK_INT<31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) return@withContext result.put("engine_available",false).put("ready",false).put("reason","on_device_speech_unavailable")
        result.put("engine_available",true)
        if(Build.VERSION.SDK_INT<33) return@withContext result.put("ready",JSONObject.NULL).put("reason","language_readiness_unavailable")
        val recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
        try {
            val support=withTimeoutOrNull(5000) { suspendCancellableCoroutine<JSONObject> { cont ->
                recognizer.checkRecognitionSupport(intent(language),app.mainExecutor,object:RecognitionSupportCallback {
                    override fun onSupportResult(info:RecognitionSupport) { if(cont.isActive) cont.resume(JSONObject()
                        .put("installed_languages",JSONArray(info.installedOnDeviceLanguages)).put("pending_languages",JSONArray(info.pendingOnDeviceLanguages))
                        .put("supported_languages",JSONArray(info.supportedOnDeviceLanguages)).put("ready",info.installedOnDeviceLanguages.any { it.equals(language,true) }),null) }
                    override fun onError(code:Int) { if(cont.isActive) cont.resume(JSONObject().put("ready",JSONObject.NULL).put("reason","support_query_failed").put("android_error",code),null) }
                })
            } }
            if(support==null) result.put("ready",JSONObject.NULL).put("reason","support_query_timeout") else support.keys().forEach { key -> result.put(key,support.get(key)) }
            result
        } finally { recognizer.destroy() }
    }
    suspend fun requestSpeechDownload(app:BridgeApp,language:String,progress:(Int)->Unit):JSONObject = withContext(Dispatchers.Main) {
        if(Build.VERSION.SDK_INT<33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) throw cc.bridgit.app.api.ErrorCatalog.error("not_supported","This Android recognizer cannot request a language download. Use android.setup with target speech.")
        val recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
        fun pending()=JSONObject().put("requested",true).put("completion_pending",true).put("language",language)
            .put("readiness_url","/v1/resources?language=$language").put("note","Android manages this transfer. Recheck installed_languages; requested is not installed, and cancellation may only stop waiting.")
        try {
            if(Build.VERSION.SDK_INT>=34) {
                withTimeoutOrNull(120_000) { suspendCancellableCoroutine<JSONObject> { cont ->
                    recognizer.triggerModelDownload(intent(language),app.mainExecutor,object:ModelDownloadListener {
                        override fun onProgress(percent:Int) { progress(percent) }
                        override fun onSuccess() { if(cont.isActive) cont.resume(JSONObject().put("installed",true).put("language",language),null) }
                        override fun onScheduled() { if(cont.isActive) cont.resume(pending(),null) }
                        override fun onError(code:Int) { if(cont.isActive) cont.resumeWith(Result.failure(cc.bridgit.app.api.ErrorCatalog.error("upstream_failed","Android language download refused ($code)."))) }
                    })
                } } ?: pending()
            } else {
                // API 33 has no download listener. Retain the recognizer across service binding and wait
                // for a subsequent support callback before destroying it. Never label this installed.
                recognizer.triggerModelDownload(intent(language))
                withTimeoutOrNull(10_000) { suspendCancellableCoroutine<Unit> { cont ->
                    recognizer.checkRecognitionSupport(intent(language),app.mainExecutor,object:RecognitionSupportCallback {
                        override fun onSupportResult(info:RecognitionSupport) { if(cont.isActive) cont.resume(Unit,null) }
                        override fun onError(code:Int) { if(cont.isActive) cont.resume(Unit,null) }
                    })
                } }
                pending()
            }
        } finally { recognizer.destroy() }
    }
    suspend fun tts(app:BridgeApp,language:String):JSONObject {
        var engine:TextToSpeech?=null
        val ready=CompletableDeferred<Int>()
        try {
            withContext(Dispatchers.Main) { engine=TextToSpeech(app) { ready.complete(it) } }
            if(withTimeoutOrNull(5000) { ready.await() } != TextToSpeech.SUCCESS) return JSONObject().put("engine_available",false).put("ready",false)
            return withContext(Dispatchers.IO) {
                val voices=engine?.voices.orEmpty().filter { it.locale.toLanguageTag().equals(language,true) }
                JSONObject().put("engine_available",true).put("language",language).put("ready",voices.any { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() })
                    .put("voices",JSONArray(voices.map { JSONObject().put("id",it.name).put("language",it.locale.toLanguageTag()).put("network_required",it.isNetworkConnectionRequired)
                        .put("installed",TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()) })).put("download_method","owner_setup")
            }
        } finally { withContext(NonCancellable+Dispatchers.Main) { engine?.shutdown() } }
    }
    private fun intent(language:String)=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE,language).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
}
