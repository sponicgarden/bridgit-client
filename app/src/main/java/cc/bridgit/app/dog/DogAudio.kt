package cc.bridgit.app.dog

import android.Manifest
import android.content.pm.PackageManager
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.capability.MicCapture
import cc.bridgit.app.judgment.Jev
import cc.bridgit.app.lancamera.CameraAudio
import cc.bridgit.app.lancamera.CameraRtsp
import cc.bridgit.app.lancamera.LanNetwork
import cc.bridgit.app.lancamera.CameraAudioModule
import kotlinx.coroutines.*

/** Explicit opt-in, bounded audio samples. Missing coverage is never reported as silence. */
data class DogAudioReading(val source:String,val at:Long,val sound:String,val confidence:Double,
    /** Measured on the device, no model asked: the clip was [DogAudioLevel.QUIET] (a real reading, not a gap). */
    val onDevice:Boolean=false)

/**
 * Loudness check before asking Clef. A clip whose loudest moment stays under [QUIET_PEAK] is recorded as "quiet"
 * without a model call (about 55 calls an hour saved per microphone in a quiet room). Measured on the A9's Tapo
 * microphones on 4 Nov 2026: a quiet room peaks at 8–56 of 32,767; a bark or whine is in the thousands, so 300
 * (about 5× the quiet floor, -41 dBFS) leaves room for soft sounds like lapping. Unknown level = ask the model.
 */
object DogAudioLevel {
    const val QUIET_PEAK = 300
    const val QUIET = "quiet"
    fun quiet(peak:Int?)=peak!=null && peak in 0 until QUIET_PEAK
    /** Peak of a 16-bit PCM WAV (as [cc.bridgit.app.lancamera.CameraAudio.wav] writes it), or null when it isn't one. */
    fun wavPeak(wav:ByteArray):Int? {
        if(wav.size<12 || String(wav,0,4,Charsets.US_ASCII)!="RIFF" || String(wav,8,4,Charsets.US_ASCII)!="WAVE") return null
        val b=java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var at=12; var bits=0
        while(at+8<=wav.size) {
            val id=String(wav,at,4,Charsets.US_ASCII); val size=b.getInt(at+4); val body=at+8
            if(size<0 || body+size>wav.size && id!="data") return null
            if(id=="fmt " && size>=16) { if(b.getShort(body).toInt()!=1) return null; bits=b.getShort(body+14).toInt() }
            if(id=="data") {
                if(bits!=16) return null
                var peak=0; var i=body; val end=minOf(wav.size,body+size)
                while(i+1<end) { peak=maxOf(peak,kotlin.math.abs(b.getShort(i).toInt())); i+=2 }
                return peak
            }
            at=body+size+(size and 1)
        }
        return null
    }
}
object DogAudio {
    class Failure(message:String,cause:Exception):Exception(message,cause)
    const val BUILTIN="builtin"
    val SOUNDS=listOf("barking","growling","eating","drinking","other","unclear")
    fun valid(id:String)=id==BUILTIN || (id.startsWith("lan:") && DogCamera.isCamera(id))
    /** A recorded clip: [mime] is audio/mp4 (the device's microphone) or audio/wav (a camera's). [peak] is null when unknown. */
    class Clip(val bytes:ByteArray,val mime:String,val peak:Int?)

    suspend fun sample(app:BridgeApp,source:String,allowed:()->Boolean):DogAudioReading {
        var stage="access check"
        try {
            val clip=record(app,source,5,"Pet monitor is sampling a microphone for 5 seconds",allowed) { stage=it }
            val at=System.currentTimeMillis()
            if(DogAudioLevel.quiet(clip.peak)) return DogAudioReading(source,at,DogAudioLevel.QUIET,1.0,onDevice=true)
            stage="analysis"
            return ask(app,source,at,clip.bytes,clip.mime) { stillAllowed(app,source,allowed) }
        }catch(e:CancellationException){throw e}
        catch(e:Exception){ throw failure(stage,e) }
    }

    /**
     * Records [seconds] from [source] (the device's own microphone, or an enrolled camera's) with the same access
     * checks the Pet monitor uses, showing [notice] while it records. [small] asks the device's microphone for a
     * compact 16 kHz / 32 kbps clip (the web portal's listen button). [stage] hears "capture" once access checks pass.
     */
    suspend fun record(app:BridgeApp,source:String,seconds:Int,notice:String,allowed:()->Boolean,small:Boolean=false,stage:(String)->Unit={}):Clip {
        var captureService:BridgeService?=null
        var token:Long?=null
        try {
        val service=BridgeService.running ?: error("Open Bridgit before microphone capture")
        // After a background restart Android keeps the microphone closed until Bridgit is opened once.
        if(source==BUILTIN && app.settings.isOn("mic") && !service.micReady) error("Open Bridgit before microphone capture")
        val enrolled=if(source==BUILTIN)null else app.lanCameras.get(source)
        fun check(){
            check(allowed() && app.remoteView.dogBackend && app.captureNotificationsOn()) { "Microphone access changed" }
            if(source==BUILTIN) check(app.settings.isOn("mic") && service.micReady && app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) { "Microphone access changed" }
            else {
                check(app.settings.isOn("lan_camera") && app.settings.isOn("lan_camera_audio") && app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG,source)) { "Microphone access changed" }
                val current=app.lanCameras.get(source)
                check(enrolled!=null && current.host==enrolled.host && current.username==enrolled.username && current.password==enrolled.password) { "Microphone access changed" }
            }
        }
        check()
        captureService=service
        token=service.captureStarted(notice)
        stage("capture")
            var peak:Int?=null
            val (bytes,mime)=if(source==BUILTIN) {
                check(app.settings.isOn("mic") && service.micReady && app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) { "Microphone access changed" }
                check(app.registry.micLock.tryLock()) { "Microphone busy" }
                try {
                    delay(300);check()
                    val clip=if(small) MicCapture(app).record(seconds,builtIn=true,rate=16_000,bitRate=32_000,shared=true) else MicCapture(app).record(seconds,builtIn=true)
                    peak=clip.peak; clip.bytes to "audio/mp4"
                } finally { app.registry.micLock.unlock() }
            }else {
                val camera=requireNotNull(enrolled)
                fun authorized(){check()}
                authorized()
                // The camera's loudness stream is held: take the next seconds from it, no second connection.
                val streamed=app.cameraMics.sample(source,seconds)
                if(streamed!=null) { authorized(); CameraAudio.wav(streamed,cc.bridgit.app.lancamera.CameraMicAudio.RATE) to "audio/wav" }
                else app.cameraMics.yielding(source) {
                    authorized();check(CameraAudioModule.audioLock.tryLock()) { "Camera microphone busy" }
                    try {
                        withContext(Dispatchers.IO) {
                            CameraRtsp(camera.host,camera.username,camera.password,LanNetwork.sockets(app,camera.host),seconds*1000L+12000,
                                cancellationJob=currentCoroutineContext()[Job],authorized=::authorized).use { rtsp ->
                                val track=rtsp.describe().firstOrNull { it.supported && it.direction in listOf("recvonly","sendrecv") } ?: error("No supported microphone track")
                                CameraAudio.wav(rtsp.record(track,seconds),track.rate) to "audio/wav"
                            }
                        }
                    }finally{CameraAudioModule.audioLock.unlock()}
                }
            }
            check()
            if(mime=="audio/wav") peak=DogAudioLevel.wavPeak(bytes)
            return Clip(bytes,mime,peak)
        }finally{token?.let { captureService?.captureEnded(it) }}
    }

    /** Asks Clef about a clip that was already recorded. [check] throws when access changed. */
    private suspend fun ask(app:BridgeApp,source:String,at:Long,bytes:ByteArray,mime:String,check:()->Unit):DogAudioReading {
        val (questions, verifyProfile) = app.dog.audioClassifier()
        fun authorize() {
            check()
            verifyProfile()
        }
        val answer=cc.bridgit.app.sound.SoundCheck.ask(app,bytes,mime,questions,applet="dog",authorized=::authorize)
        authorize()
        val result=answer.choice("sound",SOUNDS) ?: error("No reliable audio answer")
        return DogAudioReading(source,at,if(result.second>=0.6)result.first else "unclear",result.second)
    }

    /**
     * A loud sound a listener already caught while the Pet monitor runs with [source] ticked (the phone's listener for
     * the built-in microphone, a camera stream for `lan:<id>`): only this clip goes to Clef, no microphone is opened
     * again. The access checks are [sample]'s for that source; the reading keeps [source].
     */
    suspend fun classifyHeard(app:BridgeApp,source:String,wav:ByteArray,at:Long,allowed:()->Boolean):DogAudioReading = try {
        stillAllowed(app,source,allowed)
        ask(app,source,at,wav,"audio/wav") { stillAllowed(app,source,allowed) }
    }catch(e:CancellationException){throw e}
    catch(e:Exception){ throw failure("analysis",e) }

    /** Throws when the owner turned off [source]'s microphone, its permission or the Pet monitor since the clip was taken. */
    private fun stillAllowed(app:BridgeApp,source:String,allowed:()->Boolean) {
        check(allowed() && app.remoteView.dogBackend && app.captureNotificationsOn()) { "Microphone access changed" }
        if(source==BUILTIN) check(app.settings.isOn("mic") && app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) { "Microphone access changed" }
        else check(app.settings.isOn("lan_camera") && app.settings.isOn("lan_camera_audio") &&
            app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG,source) && app.lanCameras.all().any { it.id==source }) { "Microphone access changed" }
    }

    /** True when Clef said the phone's or Bridgit's budget is used up. */
    fun budgetFailure(e:Throwable)=cc.bridgit.app.sound.SoundRules.budgetReason((e.cause as? Jev.Failure)?.reason)

    private fun failure(stage:String,e:Exception):Failure {
            val detail=when(e){
                is cc.bridgit.app.lancamera.CameraFailure -> e.message
                is Jev.Failure -> e.reason
                else -> e.message?.takeIf { it in setOf("Open Bridgit before microphone capture","Microphone access changed","Microphone busy","Camera microphone busy","No supported microphone track","No reliable audio answer") } ?: e.javaClass.simpleName
            }
            return Failure("Microphone $stage unavailable: ${detail?.trimEnd('.')}. This is not a silence reading.",e)
    }
}
