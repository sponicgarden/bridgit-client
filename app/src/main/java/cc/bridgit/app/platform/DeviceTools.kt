package cc.bridgit.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.BitmapFactory
import android.hardware.*
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.*
import android.os.*
import android.provider.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.translate.*
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.common.model.DownloadConditions
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.*
import cc.bridgit.app.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

class DeviceTools(private val app:BridgeApp) {
    private val runtime get()=app.platform
    val peripherals=PeripheralTools(app)
    private var tts:TextToSpeech?=null
    private val timedEffects=java.util.concurrent.ConcurrentHashMap<String,Job>()
    @Volatile private var torchLease:String?=null
    fun stopAgent(agent:String) { app.speaker.stop(agent); timedEffects.entries.filter { it.key.startsWith("$agent:") }.forEach { it.value.cancel() } }
    fun stopAudio() { tts?.stop(); tts?.shutdown(); tts=null; app.speaker.stop() }
    suspend fun read(agent:String,name:String,args:JSONObject):JSONObject = when(name) {
        "phone.status","phone.context","diagnostics" -> status().apply { if(name=="diagnostics") put("capabilities",Capabilities.describe(app,runtime.getAgent(agent))) }
        "location.fix" -> location()
        "sensors.read" -> sensors(args)
        "images.ocr","images.barcode" -> image(agent,name,args)
        "translation.run" -> translate(args,false)
        "peripheral.list" -> peripherals.list()
        else -> throw ApiError(404,"unknown_tool","Unknown device tool")
    }
    fun status():JSONObject {
        val battery=app.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED)); val power=app.getSystemService(PowerManager::class.java)
        val sound=app.getSystemService(AudioManager::class.java); val network=app.getSystemService(ConnectivityManager::class.java)
        val caps=network.activeNetwork?.let(network::getNetworkCapabilities)
        return JSONObject().put("device_id",runtime.store.deviceId).put("device_name",runtime.store.setting("device_name",Build.MODEL)).put("model",Build.MODEL).put("android_api",Build.VERSION.SDK_INT)
            .put("as_of",System.currentTimeMillis()).put("battery_percent",battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1).put("charging",battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0)
            .put("screen_on",power.isInteractive).put("locked",app.getSystemService(KeyguardManager::class.java).isDeviceLocked).put("power_save",power.isPowerSaveMode).put("thermal_status",power.currentThermalStatus)
            .put("free_storage_bytes",app.filesDir.usableSpace).put("ringer_mode",sound.ringerMode).put("dnd",app.getSystemService(NotificationManager::class.java).currentInterruptionFilter)
            .put("headphones",sound.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in setOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,android.media.AudioDeviceInfo.TYPE_USB_HEADSET) })
            .put("network_validated",caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true).put("network_metered",network.isActiveNetworkMetered)
            .put("private_address",BridgeService.tailscaleAddress() ?: JSONObject.NULL).put("listener_ready",BridgeService.running?.bindAddress!=null)
            .put("likely_context","unknown").put("location_history_retained",false)
            .put("battery",cc.bridgit.app.power.Battery.report(app))
    }
    @SuppressLint("MissingPermission")
    private suspend fun location():JSONObject = withTimeout(12_000) {
        val lm=app.getSystemService(LocationManager::class.java)
        val provider=when { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && app.granted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationManager.GPS_PROVIDER; lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER; else -> throw ApiError(409,"location_disabled","Enable device location") }
        suspendCancellableCoroutine { cont ->
            val cancel=CancellationSignal(); cont.invokeOnCancellation { cancel.cancel() }
            androidx.core.location.LocationManagerCompat.getCurrentLocation(lm,provider,cancel,app.mainExecutor) { loc ->
                if(cont.isActive) {
                    if(loc==null) cont.resumeWith(kotlin.Result.failure(ApiError(504,"location_timeout","No location fix")))
                    else cont.resume(JSONObject().put("latitude",loc.latitude).put("longitude",loc.longitude).put("accuracy_m",loc.accuracy).put("as_of",loc.time).put("age_ms",(SystemClock.elapsedRealtimeNanos()-loc.elapsedRealtimeNanos)/1_000_000).put("provider",loc.provider))
                }
            }
        }
    }
    private suspend fun sensors(args:JSONObject):JSONObject {
        val names=if(args.has("which")) args.strings("which",5) else listOf("light","pressure","motion")
        val manager=app.getSystemService(SensorManager::class.java)
        val result=JSONObject()
        for(name in names) {
            val type=when(name) { "light" -> Sensor.TYPE_LIGHT; "pressure" -> Sensor.TYPE_PRESSURE; "motion" -> Sensor.TYPE_ACCELEROMETER; "proximity" -> Sensor.TYPE_PROXIMITY; "temperature" -> Sensor.TYPE_AMBIENT_TEMPERATURE; else -> throw ApiError(422,"invalid_parameter","Unknown sensor") }
            val sensor=manager.getDefaultSensor(type)
            if(sensor==null) { result.put(name,JSONObject().put("supported",false)); continue }
            val sample=withTimeoutOrNull(1500) { suspendCancellableCoroutine<JSONObject> { cont ->
                val listener=object:SensorEventListener {
                    override fun onAccuracyChanged(s:Sensor?,accuracy:Int)=Unit
                    override fun onSensorChanged(event:SensorEvent) { manager.unregisterListener(this); if(cont.isActive) cont.resume(JSONObject().put("supported",true).put("values",JSONArray(event.values.toList())).put("sensor_timestamp_ns",event.timestamp).put("fresh",true)) }
                }
                manager.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_NORMAL); cont.invokeOnCancellation { manager.unregisterListener(listener) }
            } }
            result.put(name,sample ?: JSONObject().put("supported",true).put("fresh",false))
        }
        return JSONObject().put("sensors",result).put("as_of",System.currentTimeMillis())
    }
    private suspend fun image(agent:String,name:String,args:JSONObject):JSONObject {
        val data=when {
            args.has("file_id") -> { runtime.authorize(agent,"files"); runtime.files.file(agent,args.text("file_id")).readBytes() }
            args.has("gallery_id") -> { runtime.authorize(agent,"gallery.read"); Gallery(app).read(args.text("gallery_id").toLongOrNull() ?: throw ApiError(422,"invalid_parameter","Invalid gallery ID")) ?: throw ApiError(404,"photo_not_found","No such image") }
            else -> try { Base64.decode(args.text("image_base64",720_000),Base64.NO_WRAP) } catch(e:Exception) { throw ApiError(422,"invalid_parameter","Invalid image") }
        }
        if(data.size>4*1024*1024) throw ApiError(413,"payload_too_large","Downsize the image first")
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(data,0,data.size,bounds)
        if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth.toLong()*bounds.outHeight>32_000_000) throw ApiError(422,"invalid_image","Unsupported or oversized image")
        val prepared=cc.bridgit.app.capability.ImageUtil.prepare(data,bounds.outMimeType ?: "image/jpeg",cc.bridgit.app.api.Sizing.Spec(2048,null,90))
        val bitmap=BitmapFactory.decodeByteArray(prepared.bytes,0,prepared.bytes.size) ?: throw ApiError(422,"invalid_image","Cannot decode image")
        try {
            if(name=="images.ocr") {
                val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                try { val text=recognizer.process(InputImage.fromBitmap(bitmap,0)).await(); return JSONObject().put("text",text.text).put("backend","mlkit_on_device_latin").put("content_is_untrusted",true) } finally { recognizer.close() }
            }
            val pixels=IntArray(bitmap.width*bitmap.height); bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val reader=MultiFormatReader()
            val found=runCatching { reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels)))) }.getOrNull()
            return JSONObject().put("found",found!=null).put("text",found?.text ?: "").put("format",found?.barcodeFormat?.name ?: "").put("content_is_untrusted",true)
        } finally { bitmap.recycle() }
    }
    private suspend fun translate(args:JSONObject,download:Boolean):JSONObject {
        val from=TranslateLanguage.fromLanguageTag(args.text("source",16)) ?: throw ApiError(422,"unsupported_language","Unknown source language")
        val to=TranslateLanguage.fromLanguageTag(args.text("target",16)) ?: throw ApiError(422,"unsupported_language","Unknown target language")
        val translator=Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        try {
            if(download) { cc.bridgit.app.outbound.Outbound.call("https://dl.google.com/mlkit/translate", hint=cc.bridgit.app.outbound.Outbound.Hint("Google ML Kit", "Download on-device translation models ($from, $to); text is translated on the phone", listOf(cc.bridgit.app.outbound.Categories.NONE))) { m -> translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).await(); m.status=cc.bridgit.app.outbound.OutboundEntry.HANDED_OFF }; return JSONObject().put("models_ready",true).put("source",from).put("target",to) }
            val manager=RemoteModelManager.getInstance()
            for(lang in setOf(from,to)-TranslateLanguage.ENGLISH) {
                if(!manager.isModelDownloaded(TranslateRemoteModel.Builder(lang).build()).await()) throw ApiError(409,"model_download_required","Call translation.download first to download language models over Wi-Fi")
            }
            return JSONObject().put("text",translator.translate(args.text("text",10_000)).await()).put("source",from).put("target",to).put("backend","mlkit_on_device").put("content_is_untrusted",true)
        } finally { translator.close() }
    }
    suspend fun downloadTranslation(args:JSONObject)=translate(args,true)
    suspend fun action(agent:String,args:JSONObject):JSONObject {
        val type=args.text("type",64); runtime.authorize(agent,scopeFor(type))
        return when(type) {
            "speak" -> speak(agent,args.text("text",600),args.text("language",32,Locale.getDefault().toLanguageTag()),args.optBoolean("allow_cloud",false))
            "camera.snapshot" -> runtime.capture(agent,"camera.snapshot") {
                val svc=BridgeService.running ?: throw ApiError(503,"service_start_blocked","Open Bridgit first",mapOf("effect_started" to false))
                val camera=args.text("camera",8,"rear"); if(camera !in setOf("rear","front")) throw ApiError(422,"invalid_parameter","Unknown camera")
                val image=CameraCapture(svc,svc).snapshot(camera=="rear",false,args.whole("max_width_px",1568,256,4096))
                JSONObject().put("data_base64",Base64.encodeToString(image.bytes,Base64.NO_WRAP)).put("mime",image.mime).put("width_px",image.width).put("height_px",image.height)
            }
            "microphone.clip" -> runtime.capture(agent,"microphone.clip") { val clip=MicCapture(app).record(args.whole("duration_seconds",5,1,120)); JSONObject().put("mime","audio/mp4").put("duration_seconds",clip.durationSeconds).put("data_base64",Base64.encodeToString(clip.bytes,Base64.NO_WRAP)) }
            "flashlight","volume","dnd","brightness","ring","media","battery_saver","screen_timeout" -> phoneControl(agent,type,args)
            "clipboard" -> withContext(Dispatchers.Main) { app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Agent text",args.text("text",4096))); JSONObject().put("outcome","clipboard_set") }
            "open_link","open_app","calendar_draft","message_draft","email_draft","dial","alarm","timer","contact_picker","open_file" -> launchDraft(agent,type,args)
            else -> throw ApiError(422,"unsupported_action","Unknown device action")
        }
    }
    private suspend fun speak(agent: String, text: String, language: String, allowCloud: Boolean): JSONObject {
        val preparation = app.speaker.beginPreparation(agent)
        val file = app.speaker.newFile("audio")
        var owned = false
        try {
            app.speaker.admit(agent, "after_current")
            val selection = cc.bridgit.app.speech.SpeechEngine.select(app, "speak", allowCloud)
            val meta = cc.bridgit.app.speech.SpeechEngine.synth(app, selection, text, file, language)
            runtime.authorize(agent, "audio.speak")
            val item = cc.bridgit.app.capability.SpeakerQueue.Item(java.util.UUID.randomUUID().toString(), agent, runtime.getAgent(agent).name, file, text.take(500), null, false, spoken = true)
            val position = app.speaker.enqueue(item, "after_current", preparation); owned = true
            return JSONObject().put("play_id", item.id).put("position", position).put("status", cc.bridgit.app.capability.SpeakerRules.replyStatus(position, app.speaker.quiet())).put("speech", meta)
        } finally { app.speaker.endPreparation(preparation); if (!owned) file.delete() }
    }
    suspend fun hear(agent: String, args: JSONObject): JSONObject = runtime.capture(agent, "microphone.clip") {
        val seconds = args.whole("duration_seconds",5,1,30)
        val preparation = app.speaker.beginPreparation(agent)
        try {
            val selection = cc.bridgit.app.speech.SpeechEngine.select(app,"hear",args.optBoolean("allow_cloud",false))
            app.speaker.beginHearing()
            try {
                app.wake.awaitMicReleased()
                val language = args.optString("language").ifBlank { null }
                if (!selection.cloud) JSONObject().put("transcript", cc.bridgit.app.speech.NativeHearing.listen(app,seconds,language))
                    .put("model",selection.model).put("processing","device").put("cost_usd",0)
                else {
                    val clip = MicCapture(app).record(seconds)
                    cc.bridgit.app.speech.SpeechEngine.hear(app, selection, clip.bytes, language, seconds)
                }
            } finally { app.speaker.endHearing() }
        } finally { app.speaker.endPreparation(preparation) }
    }
    suspend fun transcribe(agent:String,args:JSONObject):JSONObject = runtime.capture(agent,"speech.transcribe") {
        withTimeout(45_000) { withContext(Dispatchers.Main) {
            if(!ControlCenterActivity.foreground) throw ApiError(409,"foreground_required","Keep Agent permissions open for dictation")
            if(android.os.Build.VERSION.SDK_INT<31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) throw ApiError(409,"on_device_speech_unavailable","This device has no available on-device recognizer")
            @Suppress("NewApi") val recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            try { suspendCancellableCoroutine { cont ->
                recognizer.setRecognitionListener(object:RecognitionListener {
                    override fun onReadyForSpeech(params:Bundle?)=Unit
                    override fun onBeginningOfSpeech()=Unit
                    override fun onRmsChanged(rmsdB:Float)=Unit
                    override fun onBufferReceived(buffer:ByteArray?)=Unit
                    override fun onEndOfSpeech()=Unit
                    override fun onError(error:Int) { if(cont.isActive) cont.resumeWith(kotlin.Result.failure(ApiError(409,"speech_recognition_failed","Recognizer error $error"))) }
                    override fun onResults(results:Bundle?) { if(cont.isActive) cont.resume(JSONObject().put("text",results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: "").put("backend","android_on_device").put("content_is_untrusted",true)) }
                    override fun onPartialResults(partialResults:Bundle?)=Unit
                    override fun onEvent(eventType:Int,params:Bundle?)=Unit
                })
                recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,args.text("language",32,Locale.getDefault().toLanguageTag())).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true))
                cont.invokeOnCancellation { android.os.Handler(Looper.getMainLooper()).post { recognizer.cancel() } }
            } } finally { recognizer.destroy() }
        } }
    }
    private suspend fun phoneControl(agent:String,type:String,args:JSONObject):JSONObject {
        val sound=app.getSystemService(AudioManager::class.java); val duration=args.whole("duration_seconds",60,1,3600)
        when(type) {
            "flashlight" -> {
                if(runtime.activeSensorAgent!=null) throw ApiError(409,"sensor_busy","Camera or microphone is already in use")
                if(!app.granted(Manifest.permission.CAMERA)) throw ApiError(403,"permission_required","Grant Camera permission")
                val cm=app.getSystemService(CameraManager::class.java); val id=cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)==true } ?: throw ApiError(409,"not_supported","No flashlight")
                val on=args.optBoolean("on",true)
                synchronized(timedEffects) {
                    if(!on) { timedEffects.entries.filter { it.key.endsWith(":torch") }.forEach { it.value.cancel() }; cm.setTorchMode(id,false) }
                    else {
                        if(torchLease!=null) throw ApiError(409,"setting_busy","Stop the active flashlight session first")
                        val lease=java.util.UUID.randomUUID().toString(); torchLease=lease; cm.setTorchMode(id,true)
                        val key="$agent:torch"
                        val job=runtime.scope.launch(start=CoroutineStart.LAZY) {
                            try { delay(duration*1000L) } finally { if(torchLease==lease) { runCatching { cm.setTorchMode(id,false) }; torchLease=null }; timedEffects.remove(key) }
                        }
                        timedEffects[key]=job; job.start()
                    }
                }
            }
            "volume" -> {
                val stream=when(args.text("stream",16,"media")) { "media" -> AudioManager.STREAM_MUSIC; "alarm" -> AudioManager.STREAM_ALARM; else -> throw ApiError(422,"invalid_parameter","Choose media or alarm") }
                val level=args.whole("percent",50,0,100); sound.setStreamVolume(stream,sound.getStreamMaxVolume(stream)*level/100,0)
            }
            "dnd" -> {
                val nm=app.getSystemService(NotificationManager::class.java)
                if(!nm.isNotificationPolicyAccessGranted) throw ApiError(403,"dnd_access_required","Grant Do Not Disturb access on the phone")
                val before=nm.currentInterruptionFilter; val desired=when(args.text("mode",16,"priority")) { "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY; "all" -> NotificationManager.INTERRUPTION_FILTER_ALL; else -> throw ApiError(422,"invalid_parameter","Choose priority or all") }
                synchronized(timedEffects) {
                    if(timedEffects.keys.any { it.endsWith(":dnd") }) throw ApiError(409,"setting_busy","Stop the active DND session before replacing it")
                    nm.setInterruptionFilter(desired)
                    val restore=runtime.store.save("owner","restore",JSONObject().put("type","dnd").put("before",before).put("desired",desired).put("owner_actor",agent).put("at",System.currentTimeMillis()+duration*1000L))
                    val key="$agent:dnd"
                    val job=runtime.scope.launch(start=CoroutineStart.LAZY) { try { delay(duration*1000L) } finally { runCatching { restoreSettings(restore) }; timedEffects.remove(key) } }
                    timedEffects[key]=job; job.start()
                }
            }
            "battery_saver" -> {
                if(!cc.bridgit.app.power.Battery.saverControl(app)) throw ApiError(403,"secure_settings_required","Battery saver needs a one-time grant with the phone on USB: "+cc.bridgit.app.power.BatteryRules.grantCommand(app.packageName))
                val on=args.optBoolean("on",true)
                runCatching { app.hardware.change(mapOf(cc.bridgit.app.power.Battery.SAVER_KEY to if(on) 1 else 0),null) }.onFailure { throw ApiError(403,"setting_refused","Android refused the battery saver change") }
                app.log.system("audit","setting","${runtime.getAgent(agent).name} turned battery saver ${if(on) "on" else "off"}")
                val now=cc.bridgit.app.power.Battery.settleSaver(app,on)
                return JSONObject().put("dispatched",true).put("type",type).put("battery_saver",now).put("outcome",if(now==on) "setting_applied" else "android_kept_previous_state")
            }
            "screen_timeout" -> {
                if(!Settings.System.canWrite(app)) throw ApiError(403,"write_settings_required","Allow modifying system settings on the phone")
                val s=args.whole("seconds",60,cc.bridgit.app.power.BatteryRules.TIMEOUT_MIN_S,cc.bridgit.app.power.BatteryRules.TIMEOUT_MAX_S)
                runCatching { app.hardware.change(mapOf(Settings.System.SCREEN_OFF_TIMEOUT to s*1000),null) }.onFailure { throw ApiError(403,"setting_refused","Android refused the screen timeout change") }
                app.log.system("audit","setting","${runtime.getAgent(agent).name} set the screen timeout to ${s}s")
            }
            "brightness" -> {
                if(!Settings.System.canWrite(app)) throw ApiError(403,"write_settings_required","Allow modifying system settings on the phone")
                Settings.System.putInt(app.contentResolver,Settings.System.SCREEN_BRIGHTNESS,(args.whole("percent",50,1,100)*255/100))
            }
            "ring" -> {
                val ringtone=android.media.RingtoneManager.getRingtone(app,android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM))
                ringtone.audioAttributes=android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build(); ringtone.play()
                try { delay(duration.coerceAtMost(60)*1000L) } finally { ringtone.stop() }
            }
            "media" -> {
                val key=when(args.text("command",16)) { "play_pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> throw ApiError(422,"invalid_parameter","Unknown media command") }
                sound.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,key)); sound.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,key))
            }
        }
        return JSONObject().put("dispatched",true).put("type",type).put("outcome","setting_applied_or_signal_dispatched")
    }
    fun restoreSettings(record:JSONObject) {
        if(record.optString("type")=="dnd") {
            val nm=app.getSystemService(NotificationManager::class.java)
            if(nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter==record.optInt("desired")) nm.setInterruptionFilter(record.getInt("before"))
        }
        runtime.store.delete(record.getString("id"),"owner")
    }
    private suspend fun launchDraft(agent:String,type:String,args:JSONObject):JSONObject = withContext(Dispatchers.Main) {
        if(!ControlCenterActivity.foreground) throw ApiError(409,"foreground_required","Open Agent permissions to launch the reviewed action")
        val intent=when(type) {
            "open_link" -> {
                val uri=Uri.parse(args.text("url",2048)); if(uri.scheme !in setOf("https","geo","tel","mailto") || uri.userInfo!=null) throw ApiError(422,"invalid_parameter","Allowed link schemes: https, geo, tel, mailto")
                Intent(if(uri.scheme=="tel") Intent.ACTION_DIAL else Intent.ACTION_VIEW,uri)
            }
            "open_app" -> app.packageManager.getLaunchIntentForPackage(args.text("package",256)) ?: throw ApiError(404,"app_not_found","No launchable app")
            "calendar_draft" -> Intent(Intent.ACTION_INSERT,CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE,args.text("title",256)).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,args.optLong("begin_ms")).putExtra(CalendarContract.EXTRA_EVENT_END_TIME,args.optLong("end_ms"))
            "message_draft" -> Intent(Intent.ACTION_SENDTO,Uri.fromParts("smsto",args.text("recipient",256),null)).putExtra("sms_body",args.text("text",4096))
            "email_draft" -> Intent(Intent.ACTION_SENDTO,Uri.fromParts("mailto",args.text("recipient",256),null)).putExtra(Intent.EXTRA_SUBJECT,args.text("subject",256,"")).putExtra(Intent.EXTRA_TEXT,args.text("text",4096))
            "dial" -> Intent(Intent.ACTION_DIAL,Uri.fromParts("tel",args.text("number",64),null))
            "alarm" -> Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,args.whole("hour",0,0,23)).putExtra(AlarmClock.EXTRA_MINUTES,args.whole("minute",0,0,59)).putExtra(AlarmClock.EXTRA_MESSAGE,args.text("label",120,"Agent alarm")).putExtra(AlarmClock.EXTRA_SKIP_UI,false)
            "timer" -> Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH,args.whole("seconds",60,1,86400)).putExtra(AlarmClock.EXTRA_MESSAGE,args.text("label",120,"Agent timer")).putExtra(AlarmClock.EXTRA_SKIP_UI,false)
            "contact_picker" -> Intent(Intent.ACTION_PICK,ContactsContract.Contacts.CONTENT_URI)
            "open_file" -> {
                runtime.authorize(agent,"files"); val id=args.text("file_id"); val f=runtime.files.file(agent,id); val meta=runtime.store.require(id,agent,"file")
                Intent(Intent.ACTION_VIEW).setDataAndType(androidx.core.content.FileProvider.getUriForFile(app,app.packageName+".files",f),meta.getString("mime")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            else -> throw ApiError(422,"unsupported_action","Unknown intent")
        }
        try { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch(e:ActivityNotFoundException) { throw ApiError(409,"no_handler","No installed app handles this action") }
        JSONObject().put("dispatched",true).put("outcome","opened_for_owner_completion").put("completed",false)
    }
    suspend fun peripheralAction(agent:String,args:JSONObject):JSONObject=peripherals.action(agent,args)
    companion object {
        fun scopeFor(type:String):String = when(type) {
            "speak" -> "audio.speak"; "camera.snapshot" -> "camera.snapshot"; "microphone.clip" -> "microphone.clip"
            "flashlight","volume","dnd","brightness","ring","media","battery_saver","screen_timeout" -> "phone.control"
            else -> "intents"
        }
    }
}

suspend fun <T> com.google.android.gms.tasks.Task<T>.await():T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if(cont.isActive) cont.resume(it) }; addOnFailureListener { if(cont.isActive) cont.resumeWith(kotlin.Result.failure(it)) }; addOnCanceledListener { cont.cancel() }
}
