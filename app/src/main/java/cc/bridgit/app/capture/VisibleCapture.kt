package cc.bridgit.app.capture

import android.content.Intent
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

/** Native provenance decides presentation; a card cannot opt out with a JSON flag. */
object CapturePresentationRules {
    fun requiresViewfinder(via: String, requested: Boolean = false): Boolean = requested || via in setOf("card", "mcp_app", "portal")
    fun interactiveFlow(caller: String): Boolean = caller in setOf("button","portal","owner")
}

object VisibleCapture {
    data class Result(val photo: ByteArray? = null,val deleted:Boolean=false,val camera:String?=null)
    class Request(val agent: String, val camera: String, val video: File?, val seconds: Int,
                  val resolution: String, val audio: Boolean, val countdown: Int,
                  val flash: Boolean, val zoom: Float?,val reviewOnly:Boolean, val instructions:String, val groupPhoto:Boolean, val externalPhoto:(suspend () -> ByteArray)? = null) {
        val id=UUID.randomUUID().toString()
        val result=CompletableDeferred<Result>()
        val shown=CompletableDeferred<Unit>()
    }
    private val requests=mutableMapOf<String,Request>()
    @Synchronized fun get(id:String):Request?=requests[id]
    @Synchronized private fun add(request:Request) {
        if(requests.isNotEmpty()) throw ApiError(409,"sensor_busy","Another camera review is open")
        requests[request.id]=request
    }
    fun cancel(agent:String) {
        val active=synchronized(this) { requests.values.filter { it.agent==agent } }
        active.forEach { it.result.completeExceptionally(ApiError(409,"capture_cancelled","Capture cancelled")) }
    }
    suspend fun take(app:BridgeApp,agent:String,camera:String,video:File?=null,seconds:Int=0,resolution:String="720p",audio:Boolean=false,countdown:Int=0,flash:Boolean=false,zoom:Float?=null,reviewOnly:Boolean=false,instructions:String="",groupPhoto:Boolean=false,externalPhoto:(suspend () -> ByteArray)?=null):Result {
        val request=Request(agent,camera,video,seconds,resolution,audio,countdown,flash,zoom,reviewOnly,instructions,groupPhoto,externalPhoto)
        add(request)
        try {
            withContext(Dispatchers.Main) { app.startActivity(Intent(app,cc.bridgit.app.ui.camera.CaptureReviewActivity::class.java).putExtra("request",request.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            // Includes time for the owner to review or play the captured clip.
            try { withTimeout(5000) { request.shown.await() } } catch(_:kotlinx.coroutines.TimeoutCancellationException) { throw ApiError(409,"viewfinder_required","Open Bridgit to show the camera viewfinder") }
            return withTimeout(180_000) { request.result.await() }
        } finally {
            synchronized(this) { requests.remove(request.id) }
            withContext(kotlinx.coroutines.NonCancellable+Dispatchers.Main) { cc.bridgit.app.ui.camera.CaptureReviewActivity.close(request.id) }
        }
    }
}
