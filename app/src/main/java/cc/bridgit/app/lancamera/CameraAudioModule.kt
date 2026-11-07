package cc.bridgit.app.lancamera

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.Capability
import io.ktor.server.routing.Route
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.util.Base64

/** Independent, default-off owner switches for external microphone capture and external speaker output. */
class CameraAudioModule(app:BridgeApp, override val id:String):Capability(app) {
    override val titleRes=if(id=="lan_camera_audio")R.string.lan_audio_title else R.string.lan_talk_title
    override val descriptionRes=if(id=="lan_camera_audio")R.string.lan_audio_desc else R.string.lan_talk_desc
    override val group="capture"
    override val switchKey=id
    override val endpoints=ALL.filter { it.capability==id }
    override fun blockedReason()=if(!app.captureNotificationsOn())"notifications_off" else null
    override fun details()=JSONObject().put("requires","Existing lan_camera grant/switch plus this separate owner switch and agent grant")
        .put("formats","Capture: G.711 PCMA/PCMU 8/16 kHz mono decoded to WAV. Output: supplied PCM s16le 8 kHz mono, encoded to PCMA; ONVIF backchannel or authenticated Tapo /stream.")
        .put("enrollment","POST /v1/lan-cameras/probe refreshes passive per-device capabilities; no sound is recorded or played during probing.")
    private fun camera(req:Req)=app.lanCameras.all().firstOrNull { it.id==req.body.optString("camera") }
        ?:throw ErrorCatalog.error("not_found","Choose an enrolled camera from GET /v1/lan-cameras.")
    private fun guard(req:Req,camera:LanCamera) {
        requireUsable()
        val original=req.agent?:throw ErrorCatalog.error("unauthorized","Agent required.")
        val live=app.agents.get(original.id)
        if(live==null || live.paused || live.keyHash!=original.keyHash || !live.allows(id) || !live.allows("lan_camera") || !app.settings.isOn("lan_camera"))
            throw ErrorCatalog.error("agent_not_allowed","Camera and audio permissions must remain enabled.")
        val current=app.lanCameras.all().firstOrNull { it.id==camera.id }
        if(current==null || current.host!=camera.host || current.username!=camera.username || current.password!=camera.password || current.talkPassword!=camera.talkPassword)
            throw ErrorCatalog.error("conflict","Camera enrollment or credentials changed; no further audio request will be sent.")
    }
    private suspend fun run(req:Req, c:LanCamera, message:String, block:suspend(()->Unit)->Reply):Reply {
        guard(req,c)
        if(!audioLock.tryLock())throw ErrorCatalog.error("sensor_busy","Another external-camera audio operation is running.")
        val ctx=currentCoroutineContext()
        val service=cc.bridgit.app.BridgeService.running
        var token:Long?=null
        try {
            token=service?.captureStarted(message)
            // A recording gets the camera first: a Pet monitor loudness stream on it lets go and comes back after.
            val work:suspend()->Reply={ withTimeout(45_000) { withContext(Dispatchers.IO) { block { ctx.ensureActive();guard(req,c) } } } }
            return if(id=="lan_camera_audio") app.cameraMics.yielding(c.id,work) else work()
        }catch(e:CancellationException){throw e}
        catch(e:ApiError){throw e}
        catch(_:Exception){throw ErrorCatalog.error("upstream_failed","Camera audio connection, authentication or codec verification failed. Any speaker audio already sent cannot be recalled; do not retry blindly.")}
        finally { if(token!=null)service?.captureEnded(token);audioLock.unlock() }
    }
    override fun Route.install(api:ApiServer)=with(api) {
        endpoint(RECORD) {
            requireUsable();val req=this;val c=camera(req)
            val seconds=(body.opt("duration_seconds") as? Number)?.toDouble()?.takeIf { it.isFinite() && it==it.toInt().toDouble() && it in 1.0..30.0 }?.toInt()
                ?:throw ErrorCatalog.error("invalid_parameter","duration_seconds must be a whole number from 1 to 30.")
            cc.bridgit.app.capture.CapturesModule.checkStore(app,req,"lan_camera_audio")
            run(req,c,"$agentName is recording ${c.name} microphone for $seconds seconds") { authorized ->
                val started=System.currentTimeMillis()
                var timing=JSONObject()
                var sampleRate=8000
                val pcm=CameraRtsp(c.host,c.username,c.password,LanNetwork.sockets(app,c.host),(seconds+12)*1000L,cancellationJob=currentCoroutineContext()[Job],authorized=authorized).use { rtsp ->
                    val track=rtsp.describe().firstOrNull { it.supported && it.direction in listOf("recvonly","sendrecv") }
                        ?:throw ErrorCatalog.error("not_supported","No supported PCMA/PCMU 8/16 kHz mono microphone track is advertised by this camera.")
                    sampleRate=track.rate
                    rtsp.record(track,seconds).also { timing=JSONObject().put("inserted_silence_samples",rtsp.diagnostics.optInt("inserted_silence_samples"))
                        .put("trimmed_overlap_samples",rtsp.diagnostics.optInt("trimmed_overlap_samples"))
                        .put("note","Small RTP clock gaps are represented by silence; overlaps are trimmed. Video packets required by C210 combined-session firmware are discarded without decoding or storage.") }
                }
                authorized()
                val bytes=CameraAudio.wav(pcm,sampleRate);bytesOut=bytes.size.toLong()
                val kept=cc.bridgit.app.capture.CapturesModule.keep(app,req,"lan_camera_audio",bytes,"audio/wav",started,JSONObject().put("camera",c.id).put("duration_seconds",seconds))
                val report=JSONObject(c.capabilityReport?.toString() ?: "{}")
                val audio=report.optJSONObject("audio_in")?:JSONObject().also{report.put("audio_in",it)}
                audio.put("last_capture_verified_at",System.currentTimeMillis()).put("verified_capture",true).put("room_sound_verified",false)
                app.lanCameras.replace(c,c.assessed(report))
                if(arg("raw").let{it==1||it=="1"||it==true})Reply.Bytes(bytes,"audio/wav",cc.bridgit.app.capability.keptHeaders(kept))
                else Reply.Json(JSONObject().put("camera",c.id).put("mime","audio/wav").put("format","wav").put("sample_rate_hz",sampleRate).put("channels",1)
                    .put("duration_seconds",seconds).put("recorded_at",java.time.Instant.ofEpochMilli(started).toString()).put("signal",CameraAudio.signal(pcm)).put("transport_quality",timing)
                    .put("data_base64",Base64.getEncoder().encodeToString(bytes)).apply{if(kept!=null)put("capture",kept)})
            }
        }
        endpoint(PLAY) {
            requireUsable();val req=this;val c=camera(req)
            val encoded=body.optString("pcm_s16le_base64")
            val pcm=try { require(encoded.length<=640000);Base64.getDecoder().decode(encoded).also{require(it.size in 320..480000 && it.size%2==0)} }
                catch(_:Exception){throw ErrorCatalog.error("invalid_parameter","Supply 0.02..30 seconds of mono PCM signed 16-bit little-endian at 8000 Hz as pcm_s16le_base64; no WAV header.")}
            val transport=body.optString("transport","onvif_backchannel")
            if(transport !in listOf("onvif_backchannel","tapo"))throw ErrorCatalog.error("invalid_parameter","transport must be onvif_backchannel or tapo.")
            if(transport=="tapo" && c.talkPassword.isEmpty())throw ErrorCatalog.error("access_needed","Enroll an authorized Tapo cloud password using /v1/lan-cameras/talk-account. The local camera account is a different credential.")
            run(req,c,"$agentName is sending speaker audio to ${c.name}") { authorized ->
                if(transport=="tapo") TapoTalk(c.host,c.talkPassword,LanNetwork.sockets(app,c.host),pcm.size/16L+10000,cancellationJob=currentCoroutineContext()[Job],authorized=authorized).use{it.play(pcm)}
                else CameraRtsp(c.host,c.username,c.password,LanNetwork.sockets(app,c.host),pcm.size/16L+10000,cancellationJob=currentCoroutineContext()[Job],authorized=authorized).use { rtsp ->
                    val track=rtsp.describe(true).firstOrNull{it.outputSupported && it.direction=="sendonly"}
                        ?:throw ErrorCatalog.error("not_supported","This camera does not advertise a supported ONVIF PCMA audio backchannel. A physical speaker or Tapo app two-way audio does not imply ONVIF support.")
                    rtsp.talk(track,pcm)
                }
                authorized()
                Reply.Json(JSONObject().put("camera",c.id).put("transport",transport).put("duration_seconds",pcm.size/16000.0)
                    .put("status","audio_sent").put("audibly_verified",false))
            }
        }
        endpoint(ACCOUNT) {
            requireUsable();val req=this;val c=camera(req);guard(req,c)
            if(agent?.allows("lan_enrollment")!=true || !app.settings.isOn("lan_enrollment"))throw ErrorCatalog.error("agent_not_allowed","Tapo talk-account changes also require the owner-enabled lan_enrollment capability and grant.")
            val password=body.opt("cloud_password") as? String ?:throw ErrorCatalog.error("invalid_parameter","cloud_password is required; empty clears the stored talk credential.")
            if(password.length>256)throw ErrorCatalog.error("invalid_parameter","Cloud password is too long.")
            run(req,c,"$agentName is verifying ${c.name} talk account (no sound)") { authorized ->
                val check={authorized();if(app.agents.get(agent!!.id)?.allows("lan_enrollment")!=true || !app.settings.isOn("lan_enrollment"))throw ErrorCatalog.error("agent_not_allowed","Enrollment permission revoked.")}
                if(password.isNotEmpty())TapoTalk(c.host,password,LanNetwork.sockets(app,c.host),6000,cancellationJob=currentCoroutineContext()[Job],authorized=check).use{}
                check()
                val report=JSONObject(c.capabilityReport?.toString()?:"{}").put("tapo_talk",JSONObject().put("state",if(password.isEmpty())"credentials_required" else "authenticated").put("implemented",true).put("audibly_verified",false))
                if(!app.lanCameras.replace(c,c.assessed(report,password)))throw ErrorCatalog.error("conflict","Camera changed during verification; credentials were not saved.")
                Reply.Json(JSONObject().put("camera",c.id).put("talk_credentials_configured",password.isNotEmpty()).put("authentication_verified",password.isNotEmpty()).put("audibly_verified",false))
            }
        }
    }
    companion object {
        internal val audioLock=Mutex()
        val RECORD=EndpointSpec("POST","/lan-cameras/audio/record","lan_camera_audio","Record the enrolled camera microphone, never the Android microphone",capture=true,media=true,
            body=listOf(Param("camera","string","Enrolled camera id",true),Param("duration_seconds","integer","1..30 whole seconds",true),Param("raw","integer","1 returns WAV bytes"),Param("store","array","memory, files or upload subject to owner capture policy")),
            example="POST /v1/lan-cameras/audio/record {\"camera\":\"lan:example\",\"duration_seconds\":5}",returns="camera, WAV audio, sample_rate_hz (8000 or 16000), mono, duration, recorded_at, signal (silence metrics; never claims room pickup). Separate lan_camera_audio and lan_camera grants/switches required.")
        val PLAY=EndpointSpec("POST","/lan-cameras/audio/play","lan_camera_speaker","Send a bounded supplied audio clip to a supported enrolled camera speaker",capture=true,idempotent=true,
            body=listOf(Param("camera","string","Enrolled camera id",true),Param("pcm_s16le_base64","string","Raw signed 16-bit little-endian mono PCM, 8000Hz, 0.02..30 seconds; no WAV header",true),Param("transport","string","Explicit adapter; no automatic credential fallback",enum=listOf("onvif_backchannel","tapo"))),
            privateFields=setOf("pcm_s16le_base64"),example="POST /v1/lan-cameras/audio/play {\"camera\":\"lan:example\",\"pcm_s16le_base64\":\"...\",\"transport\":\"tapo\"}",returns="status=audio_sent, duration_seconds, audibly_verified=false. Requires separate owner-enabled lan_camera_speaker and existing lan_camera grants. Use Idempotency-Key; no automatic retries.")
        val ACCOUNT=EndpointSpec("POST","/lan-cameras/talk-account","lan_camera_speaker","Verify and privately store or clear this camera's separate Tapo cloud-password credential",capture=true,idempotent=true,
            body=listOf(Param("camera","string","Enrolled camera id",true),Param("cloud_password","string","Authorized cloud account password, not RTSP password; empty clears",true)),privateFields=setOf("cloud_password"),
            example="POST /v1/lan-cameras/talk-account {\"camera\":\"lan:example\",\"cloud_password\":\"<private authorized password>\"}",returns="camera, talk_credentials_configured, authentication_verified, audibly_verified=false; also requires lan_enrollment permission. No speaker playback during authentication.")
        val ALL=listOf(RECORD,PLAY,ACCOUNT)
    }
}
