package cc.bridgit.app.platform

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import cc.bridgit.app.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** User starts each projection using Android's real consent dialog. */
class ProjectionService:Service() {
    private val app get()=application as BridgeApp
    private var projection:MediaProjection?=null
    private var reader:ImageReader?=null
    private var virtual:VirtualDisplay?=null
    private val handler=Handler(Looper.getMainLooper())
    @Volatile var active=false; private set
    @Volatile private var last:ByteArray?=null
    @Volatile private var at=0L
    private var width=0; private var height=0
    private val stopCheck=object:Runnable { override fun run() { if(!Capabilities.visible(this@ProjectionService)) stopSelf() else handler.postDelayed(this,250) } }
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop") { stopSelf(); return START_NOT_STICKY }
        if(active) return START_NOT_STICKY
        val data=intent?.let { androidx.core.content.IntentCompat.getParcelableExtra(it,"consent",Intent::class.java) } ?: return START_NOT_STICKY
        if(!Capabilities.visible(this)) { stopSelf(); return START_NOT_STICKY }
        val stop=PendingIntent.getService(this,912,Intent(this,ProjectionService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        ServiceCompat.startForeground(this,912,NotificationCompat.Builder(this,BridgeApp.CAPTURE_CHANNEL).setSmallIcon(cc.bridgit.app.R.drawable.ic_notif).setContentTitle("Bridgit screen sharing").setContentText("Screen content can be read by your approved agents").setOngoing(true).addAction(0,"Stop sharing",stop).build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,data)
        projection?.registerCallback(object:MediaProjection.Callback() { override fun onStop() { stopSelf() } },handler)
        val metrics=android.util.DisplayMetrics()
        getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        width=metrics.widthPixels; height=metrics.heightPixels
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2)
        reader!!.setOnImageAvailableListener({ source ->
            source.acquireLatestImage()?.use { image ->
                if(System.currentTimeMillis()-at<350) return@use
                val plane=image.planes[0]; val padding=plane.rowStride-plane.pixelStride*width
                val bmp=Bitmap.createBitmap(width+padding/plane.pixelStride,height,Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped=Bitmap.createBitmap(bmp,0,0,width,height); val out=ByteArrayOutputStream(); cropped.compress(Bitmap.CompressFormat.JPEG,80,out)
                last=out.toByteArray(); at=System.currentTimeMillis(); if(cropped!==bmp) cropped.recycle(); bmp.recycle()
            }
        },handler)
        virtual=projection!!.createVirtualDisplay("Bridgit owner screen share",width,height,resources.displayMetrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
        active=true; instance=this; handler.post(stopCheck); app.platform.changed()
        return START_NOT_STICKY
    }
    suspend fun observe(agent:String):JSONObject {
        app.platform.authorize(agent,"screen.observe")
        if(!active || getSystemService(KeyguardManager::class.java).isDeviceLocked) throw ApiError(409,"screen_share_required","Start a fresh share session on the phone")
        val end=System.currentTimeMillis()+3000
        while(last==null && System.currentTimeMillis()<end) delay(100)
        val data=last ?: throw ApiError(409,"no_frame","Waiting for the first screen frame")
        return JSONObject().put("as_of",at).put("age_ms",System.currentTimeMillis()-at).put("stale",System.currentTimeMillis()-at>3000).put("backend","media_projection").put("content_is_untrusted",true)
            .put("image",JSONObject().put("mime","image/jpeg").put("width_px",width).put("height_px",height).put("data_base64",android.util.Base64.encodeToString(data,android.util.Base64.NO_WRAP)))
    }
    override fun onDestroy() { active=false; handler.removeCallbacks(stopCheck); virtual?.release(); reader?.close(); projection?.stop(); last=null; if(instance===this) instance=null; app.platform.changed(); super.onDestroy() }
    companion object { @Volatile var instance:ProjectionService?=null; private set }
}
