package cc.bridgit.app.platform

import android.util.Base64
import kotlinx.coroutines.*
import cc.bridgit.app.*
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** Bounded JPEG monitoring, not a claim of WebRTC or continuous audio streaming. */
class CameraMonitor(private val runtime:AgentPlatform) {
    private data class Frame(val data:ByteArray,val at:Long,val width:Int,val height:Int)
    private val frames=java.util.concurrent.ConcurrentHashMap<String,Frame>()
    private val jobs=java.util.concurrent.ConcurrentHashMap<String,Job>()
    @Synchronized fun start(agent:String,args:JSONObject):JSONObject {
        args.only("camera","duration_seconds","interval_seconds")
        if(jobs.values.any { it.isActive }) throw ApiError(409,"sensor_busy","A monitoring session is already active")
        val camera=args.text("camera",80,"rear"); if(camera !in setOf("rear","front") && !camera.matches(Regex("lan:[a-zA-Z0-9-]{1,60}"))) throw ApiError(422,"invalid_parameter","Unknown camera")
        checkSource(agent,camera)
        val duration=args.whole("duration_seconds",300,10,1800); val interval=args.whole("interval_seconds",3,2,30)
        val record=runtime.store.save(agent,"stream",JSONObject().put("status","active").put("expires_at",System.currentTimeMillis()+duration*1000L).put("camera",camera).put("backend","bounded_jpeg").put("interval_seconds",interval).put("audio",false))
        val id=record.getString("id"); val job=runtime.scope.launch(start=CoroutineStart.LAZY) {
            try {
                while(isActive && System.currentTimeMillis()<record.getLong("expires_at")) {
                    runtime.capture(agent,"monitoring") {
                        val svc=BridgeService.running ?: throw ApiError(503,"service_start_blocked","Open Bridgit")
                        checkSource(agent,camera)
                        val frame=if(camera.startsWith("lan:")) Frame(cc.bridgit.app.lancamera.RtspFrame.snapshot(runtime.app,runtime.app.lanCameras.get(camera)),System.currentTimeMillis(),640,360)
                            else CameraCapture(svc,svc).snapshot(camera=="rear",false,1024).let { Frame(it.bytes,System.currentTimeMillis(),it.width,it.height) }
                        checkSource(agent,camera)
                        frames[id]=frame; JSONObject()
                    }
                    runtime.store.emit(agent,"stream.frame",id); delay(interval*1000L)
                }
            } catch(e:Exception) {
                runtime.store.save(agent,"stream",runtime.store.require(id,agent).put("status",if(e is CancellationException) "stopped" else "blocked").put("error",if(e is ApiError) e.code else "interrupted"))
            } finally {
                jobs.remove(id); frames.remove(id)
                val current=runtime.store.require(id,agent)
                if(current.optString("status")=="active") runtime.store.save(agent,"stream",current.put("status","stopped"))
                runtime.store.emit(agent,"stream.stopped",id); runtime.changed()
            }
        }
        jobs[id]=job; job.start(); runtime.changed(); return record
    }
    private fun checkSource(agent:String,camera:String) {
        if(!camera.startsWith("lan:")) return
        val a=runtime.app.agents.get(agent)
        if(a==null || a.suspended || !a.allows("lan_camera") || !runtime.app.settings.isOn("lan_camera")) throw ApiError(403,"agent_not_allowed","Local camera access was removed or switched off")
        runtime.app.lanCameras.get(camera)
    }
    fun stop(agent:String,id:String):JSONObject { val record=runtime.store.require(id,agent,"stream"); jobs[id]?.cancel(); frames.remove(id); return runtime.store.save(agent,"stream",record.put("status","stopped")) }
    fun stopAgent(agent:String) { runtime.store.list(agent,"stream").forEach { stop(agent,it.getString("id")) } }
    fun status(agent:String,id:String):JSONObject {
        val record=runtime.store.require(id,agent,"stream"); checkSource(agent,record.optString("camera","rear")); val at=frames[id]?.at ?: 0
        return record.put("last_frame_at",at).put("stale",at==0L || System.currentTimeMillis()-at>5000).put("disconnected",at==0L || System.currentTimeMillis()-at>15000)
    }
    fun frame(agent:String,id:String):JSONObject {
        val status=status(agent,id); val frame=frames[id] ?: throw ApiError(409,"no_frame","No live frame; inspect stream.status")
        return status.put("image",JSONObject().put("mime","image/jpeg").put("width_px",frame.width).put("height_px",frame.height).put("data_base64",Base64.encodeToString(frame.data,Base64.NO_WRAP)))
    }
    /** Called only from owner UI. Viewer credentials cannot administer or read other data. */
    fun issueViewer(agent:String,id:String):String {
        runtime.store.require(id,agent,"stream")
        val token=ByteArray(32).also { SecureRandom().nextBytes(it) }.toHex()
        runtime.store.setting("viewer:$id",hash(token),true); return token
    }
    fun revokeViewer(id:String) { runtime.store.setting("viewer:$id","",true) }
    fun viewer(id:String,token:String):JSONObject {
        val digest=runtime.store.setting("viewer:$id")
        if(digest.isEmpty() || !MessageDigest.isEqual(digest.toByteArray(),hash(token).toByteArray())) throw ApiError(401,"unauthorized","Invalid viewer credential")
        val record=runtime.store.find(id,kind="stream") ?: throw ApiError(404,"stream_not_found","No such stream")
        runtime.authorize(record.getString("agent_id"),"monitoring")
        if(record.optString("status")!="active") throw ApiError(409,"stream_stopped","Monitoring stopped")
        val result=frame(record.getString("agent_id"),id); result.remove("agent_id"); result.remove("kind"); return result
    }
    private fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).toHex()
}
