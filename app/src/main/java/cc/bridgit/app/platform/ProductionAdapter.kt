package cc.bridgit.app

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import cc.bridgit.app.log.LogEntry
import org.json.JSONObject

typealias ApiError=cc.bridgit.app.api.ApiError
typealias Gallery=cc.bridgit.app.capability.Gallery
typealias MicCapture=cc.bridgit.app.capability.MicCapture
fun Context.granted(permission:String)=ContextCompat.checkSelfPermission(this,permission)==android.content.pm.PackageManager.PERMISSION_GRANTED
class CameraCapture(context:Context,owner:LifecycleOwner) {
    private val delegate=cc.bridgit.app.capability.CameraCapture(context,owner)
    suspend fun snapshot(rear:Boolean,flash:Boolean,maxWidth:Int?)=delegate.snapshot(rear,flash,cc.bridgit.app.api.Sizing.Spec(null,maxWidth,85))
}
object BuildConfigInfo { fun versionName(context:Context):String=context.packageManager.getPackageInfo(context.packageName,0).versionName ?: "unknown" }
/** Platform records appear in the existing native Activity screen, with no input payloads. */
class PlatformAudit(private val app:BridgeApp) {
    fun append(record:JSONObject) { app.log.append(entry(record,System.currentTimeMillis())) }
    companion object {
        /** The log entry for one platform record. A /v2/call or MCP tools/call record names its tool in data.tool. */
        fun entry(record:JSONObject,now:Long)=LogEntry(ts=now,channel="audit",kind="request",agentId=record.optString("agent_id").takeIf { it.isNotBlank() },agent=record.optString("agent").takeIf { it.isNotBlank() },endpoint=record.optString("endpoint"),result=record.optString("result"),http=record.optInt("http",0).takeIf { it>0 },requestId=record.optString("request_id"),
            data=JSONObject().put("operation_id",record.optString("operation_id")).apply { record.optString("tool").takeIf { it.isNotBlank() }?.let { put("tool",cc.bridgit.app.log.Redactor.string(it,80)) } })
    }
}
