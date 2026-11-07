package cc.bridgit.app.lancamera

import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import java.io.File
import javax.net.SocketFactory
import org.json.JSONObject

/** Explicit opt-in only: reuses owner-provided private local camera profiles; never prints credentials/audio. */
class CameraAudioHardwareTest {
    @Test fun authorizedC210PassiveDiscoveryAndFiveSecondMicrophoneClips() {
        assumeTrue(System.getenv("BRIDGIT_CAMERA_AUDIO_HARDWARE")=="1")
        val results=mutableListOf<JSONObject>()
        val targets=System.getenv("BRIDGIT_CAMERA_AUDIO_TARGET")?.split(',') ?: listOf("dog","cat")
        require(targets.isNotEmpty() && targets.all{it in listOf("dog","cat")})
        for(name in targets) {
            val profile=JSONObject(File(System.getProperty("user.home"),".config/bridgit/cameras/$name.json").readText())
            try { CameraRtsp(profile.getString("host"),profile.getString("username"),profile.getString("password"),SocketFactory.getDefault(),17000){}.use { rtsp ->
                val tracks=rtsp.describe()
                val track=tracks.firstOrNull{it.supported && it.direction in listOf("recvonly","sendrecv")}
                    ?:error("No supported camera microphone track; codec report: ${tracks.map{it.json()}}")
                val pcm=try{rtsp.record(track,5)}catch(e:Exception){throw AssertionError("Hardware capture failed; ${track.json()} transport=${rtsp.diagnostics}",e)}
                assertEquals(5*track.rate*2,pcm.size)
                results+=JSONObject().put("camera",name).put("track",track.json()).put("decoded_samples",pcm.size/2).put("signal",CameraAudio.signal(pcm)).put("transport",rtsp.diagnostics)
            } } catch(e:Exception) { results+=JSONObject().put("camera",name).put("success",false).put("error",e.javaClass.simpleName);throw e }
            finally { val out=File("build/camera-audio-hardware.json");out.parentFile.mkdirs();out.writeText(org.json.JSONArray(results).toString(2)) }
        }
    }
    @Test fun authorizedTapoCloudCredentialHandshakeWithoutSpeakerPlayback() {
        val secretPath=System.getenv("BRIDGIT_TAPO_TALK_CREDENTIAL_FILE")
        assumeTrue(!secretPath.isNullOrEmpty())
        val cloud=JSONObject(File(secretPath!!).readText()).getString("password")
        for(name in listOf("dog","cat")) {
            val profile=JSONObject(File(System.getProperty("user.home"),".config/bridgit/cameras/$name.json").readText())
            TapoTalk(profile.getString("host"),cloud,SocketFactory.getDefault(),6000){}.use{}
        }
    }
}
