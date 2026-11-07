package cc.bridgit.app.lancamera

import android.content.Context
import org.json.JSONObject

/** Passive capability checks never start a recording, move a motor, or allocate a talk session. */
object CameraProbe {
    fun inspect(context:Context, camera:LanCamera, info:Onvif.Info, authorized:()->Unit = {}):JSONObject {
        authorized()
        val report=try {
            CameraRtsp(camera.host,camera.username,camera.password,LanNetwork.sockets(context,camera.host),9000,authorized=authorized).use { rtsp ->
                val tracks=rtsp.describe()
                var backUnverified=false
                val back=try{rtsp.describe(true)}catch(e:kotlinx.coroutines.CancellationException){throw e}
                    catch(_:Exception){authorized();backUnverified=true;emptyList()}
                CameraAudio.manifest(tracks,back,System.currentTimeMillis()).also{if(backUnverified)it.getJSONObject("audio_out").put("state","unverified")}
            }
        } catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(_:Exception){
            authorized()
            JSONObject().put("checked_at",System.currentTimeMillis()).put("probe","RTSP discovery failed; no audio recorded or played")
                .put("audio_in",JSONObject().put("state","unverified").put("implemented",false))
                .put("audio_out",JSONObject().put("state","unverified").put("implemented",false).put("audibly_verified",false))
        }
        val controls=try{Onvif(camera,context,authorized=authorized).controls(info.profile)}
            catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(_:Exception){authorized();null}
        report.put("onvif",info.inventory).put("controls",controls?.json() ?: JSONObject().put("state","unverified"))
            .put("video",JSONObject().put("advertised",true).put("implemented",true).put("snapshot","/v1/lan-cameras/snapshot"))
            .put("pan_tilt",JSONObject().put("advertised",if(info.inventory.opt("ptz_query_succeeded")==false)JSONObject.NULL else info.ptz).put("implemented",controls?.relative==true).put("path","/v1/lan-cameras/move"))
            .put("zoom",JSONObject().put("advertised",info.inventory.opt("relative_zoom_advertised") ?: JSONObject.NULL).put("implemented",false))
            .put("metadata_events",JSONObject().put("advertised",info.inventory.optBoolean("metadata_advertised")).put("implemented",false))
            .put("scope","Capabilities are per protocol and firmware. Unadvertised or unverified is not proof that physical hardware is absent.")
        if(camera.talkPassword.isNotEmpty()) {
            try {
                TapoTalk(camera.host,camera.talkPassword,LanNetwork.sockets(context,camera.host),6000,authorized=authorized).use { }
                report.put("tapo_talk",JSONObject().put("state","authenticated").put("implemented",true).put("codec","PCMA").put("sample_rate_hz",8000).put("audibly_verified",false))
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(_:Exception){authorized();report.put("tapo_talk",JSONObject().put("state","authentication_or_connection_failed").put("implemented",true).put("audibly_verified",false))}
        }else report.put("tapo_talk",JSONObject().put("state","credentials_required").put("implemented",true).put("audibly_verified",false))
        authorized();return report
    }
}
