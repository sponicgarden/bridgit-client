package cc.bridgit.app.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.Sizing
import cc.bridgit.app.capability.ImageUtil
import cc.bridgit.app.capture.VisibleCapture
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import java.io.File

/** Person-started capture: visible preview, deliberate shutter, review, optional playback and deletion. */
class CaptureReviewActivity:AppCompatActivity() {
    private val app get()=applicationContext as BridgeApp
    private lateinit var request:VisibleCapture.Request
    private lateinit var preview:PreviewView
    private lateinit var photo:ImageView
    private lateinit var player:VideoView
    private lateinit var status:TextView
    private lateinit var progress:ProgressBar
    private lateinit var shutter:MaterialButton
    private var voiceShutter:MaterialButton? = null
    private lateinit var flip:MaterialButton
    private lateinit var use:MaterialButton
    private lateinit var delete:MaterialButton
    private lateinit var play:MaterialButton
    private lateinit var retake:MaterialButton
    private var provider:ProcessCameraProvider?=null
    private var previewUseCase:Preview?=null
    private var capture:ImageCapture?=null
    private var video:VideoCapture<Recorder>?=null
    private var recording:Recording?=null
    private var photoBytes:ByteArray?=null
    private var rear=true
    private var locked=false
    private var micLocked=false
    private var reviewing=false
    private var waitingPreview=false
    private var working=false
    private var resumed=false
    private var hadFocus=false
    private var opening:Job?=null
    private var timer:Job?=null
    private var recordStarted=0L

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val found=VisibleCapture.get(intent.getStringExtra("request").orEmpty())
        if(found==null) { finish();return }
        request=found;rear=request.camera=="rear"
        setContentView(layout())
        preview.previewStreamState.observe(this) { state->if(waitingPreview&&resumed&&state==PreviewView.StreamState.STREAMING) { waitingPreview=false;busy(false,"${if(rear) "Rear" else "Selfie"} camera · ready") } }
        if(request.reviewOnly) reviewVideo()
        lifecycleScope.launch { try { request.result.await() } catch(_:Exception) {} finally { if(!isFinishing) finish() } }
    }
    private fun layout():View {
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK) }
        val viewport=FrameLayout(this)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _,insets-> val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());root.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets }
        preview=PreviewView(this).apply { scaleType=PreviewView.ScaleType.FIT_CENTER }
        photo=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER;visibility=View.GONE }
        player=VideoView(this).apply { visibility=View.GONE }
        viewport.addView(preview,FrameLayout.LayoutParams(-1,-1));viewport.addView(photo,FrameLayout.LayoutParams(-1,-1));viewport.addView(player,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        val top=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,20,24,20);setBackgroundColor(0xB3000000.toInt()) }
        status=TextView(this).apply { textSize=21f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate=true }
        if (request.instructions.isNotBlank()) top.addView(TextView(this).apply {
            text = request.instructions
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        top.addView(status);top.addView(progress,LinearLayout.LayoutParams(-1,12).apply { topMargin=16 });root.addView(top,LinearLayout.LayoutParams(-1,-2));root.addView(viewport,LinearLayout.LayoutParams(-1,0,1f))
        val controls=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12);setBackgroundColor(0xC9000000.toInt()) }
        fun button(label:String,action:()->Unit)=MaterialButton(this).apply { text=label;minHeight=56;setOnClickListener { action() };controls.addView(this,LinearLayout.LayoutParams(-1,-2)) }
        shutter=button(if(request.video==null) "Take photo" else "Record video") { begin() }
        if (request.groupPhoto) voiceShutter=button("Listen for GO") {
            if (!working && !reviewing) listenForGo()
        }
        flip=button("Flip camera") { if(!working&&!reviewing) { listening?.cancel(); rear=!rear;open() } }
        play=button("Play video") { if(player.isPlaying) { player.pause();play.text="Play video" } else { player.start();play.text="Pause video" } }
        use=button(if(request.video==null) "Use photo" else "Use video") { if(reviewing) { try { allowed();request.result.complete(VisibleCapture.Result(photoBytes,camera=if(request.externalPhoto!=null) request.camera else if(rear)"rear" else "front"));finish() } catch(e:Exception) { cancel(e.message ?: "Capture unavailable") } } }
        retake=button("Retake") { discard();reviewing=false;photo.visibility=View.GONE;player.visibility=View.GONE;preview.visibility=View.VISIBLE;open() }
        delete=button("Delete") { if(request.reviewOnly) { request.result.complete(VisibleCapture.Result(deleted=true));finish() } else cancel("Capture deleted") }
        button("Cancel") { cancel("Capture cancelled") }
        root.addView(controls,LinearLayout.LayoutParams(-1,-2));setReview(false);return root
    }
    private fun allowed() {
        if(getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) throw ApiError(423,"device_locked","Unlock the phone to use the camera")
        if(request.externalPhoto==null&&!request.reviewOnly&&androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED) throw ApiError(403,"permission_denied","Camera permission was revoked")
        val agent=app.agents.get(request.agent)
        val cap=if(request.externalPhoto!=null) "lan_camera" else if(request.video==null) "camera" else "video"
        if(agent==null||agent.suspended||!agent.allows(cap)||!app.settings.isOn(cap)) throw ApiError(403,"agent_not_allowed","Camera access was revoked")
        if(request.audio && (!app.settings.isOn("mic") || !agent.allows("mic") || androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)) throw ApiError(403,"agent_not_allowed","Microphone access was revoked")
        val inst=app.applets.forAgent(request.agent)
        if(inst!=null&&inst.optString("status")!="active") throw ApiError(403,"agent_not_allowed","The applet is paused or removed")
    }
    override fun onResume() { super.onResume();resumed=true;current=this;if(::request.isInitialized)request.shown.complete(Unit);if(::request.isInitialized&&!reviewing)open() }
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) listening?.cancel()
        if(hasFocus)hadFocus=true
        else if(hadFocus&&resumed&&working&&!reviewing&&::request.isInitialized&&!request.result.isCompleted)cancel("Capture cancelled when the viewfinder was covered")
    }
    override fun onPause() {
        listening?.cancel()
        resumed=false
        if(::request.isInitialized&&!request.result.isCompleted) cancel("Camera closed before capture was accepted")
        closeCamera();super.onPause()
    }
    override fun onDestroy() { if(current===this) current=null;closeCamera();if(::request.isInitialized&&!request.result.isCompleted)cancel("Capture cancelled");super.onDestroy() }
    private fun cancel(message:String) {
        if(::request.isInitialized&&!request.result.isCompleted) { discard();request.result.completeExceptionally(ApiError(409,"capture_cancelled",message)) }
        finish()
    }
    private fun setReview(on:Boolean) {
        reviewing=on
        for(v in listOf(use,retake,delete))v.visibility=if(on)View.VISIBLE else View.GONE
        if(request.reviewOnly) { retake.visibility=View.GONE;use.text="Done" }
        if(!on) { shutter.text=if(request.video==null) "Take photo" else "Record video";shutter.setOnClickListener { begin() } }
        play.visibility=if(on&&request.video!=null)View.VISIBLE else View.GONE
        shutter.visibility=if(on)View.GONE else View.VISIBLE
        voiceShutter?.visibility=if(on)View.GONE else View.VISIBLE
        flip.visibility=if(on)View.GONE else View.VISIBLE
    }
    private fun busy(on:Boolean,label:String) { working=on;status.text=label;progress.visibility=if(on)View.VISIBLE else View.GONE;shutter.isEnabled=!on;flip.isEnabled=!on;voiceShutter?.isEnabled=!on }
    private fun open() {
        val external = request.externalPhoto
        if (external != null) {
            setReview(false)
            flip.visibility=View.GONE
            busy(false,"Take a photo from the selected local camera. A saved view may move the camera.")
            shutter.setOnClickListener {
                if (resumed && hasWindowFocus() && !working) lifecycleScope.launch {
                    try {
                        allowed()
                        busy(true,"Taking local camera photo…")
                        val bytes=external()
                        allowed()
                        if (resumed && !request.result.isCompleted) reviewPhoto(bytes)
                    } catch (e:Exception) { cancel(e.message ?: "Local camera unavailable") }
                }
            }
            return
        }
        opening?.cancel();closeCamera();busy(true,"Starting camera…")
        waitingPreview=true
        opening=lifecycleScope.launch {
            try {
                allowed()
                withTimeout(15_000) { while(!app.registry.cameraLock.tryLock()) { status.text="Waiting for camera…";delay(100) } }
                locked=true
                if(!resumed)throw ApiError(409,"capture_cancelled","The viewfinder is not visible")
                val p=withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(this@CaptureReviewActivity).get() };provider=p
                ensureActive();allowed();if(!resumed||request.result.isCompleted)throw ApiError(409,"capture_cancelled","The viewfinder is not visible")
                val selector=if(rear)CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                if(!p.hasCamera(selector))throw ApiError(409,"not_supported","This device has no ${if(rear) "rear" else "front"} camera")
                val pv=Preview.Builder().build().also { it.surfaceProvider=preview.surfaceProvider;previewUseCase=it }
                if(request.video==null) {
                    capture=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build().also { it.flashMode=if(request.flash)ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }
                    p.bindToLifecycle(this@CaptureReviewActivity,selector,pv,capture)
                } else {
                    video=VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(if(request.resolution=="1080p")Quality.FHD else Quality.HD)).build())
                    p.bindToLifecycle(this@CaptureReviewActivity,selector,pv,video)
                }
                request.zoom?.let { p.bindToLifecycle(this@CaptureReviewActivity,selector).cameraControl.setZoomRatio(it) }
                setReview(false)
            } catch(e:Exception) { if(e is CancellationException)throw e;cancel(e.message ?: "Camera unavailable") }
        }
    }
    private var listening:Job? = null
    private fun listenForGo() {
        if (listening?.isActive == true) return
        listening = lifecycleScope.launch {
            val granted = app.agents.get(request.agent)?.allows("mic") == true && app.settings.isOn("mic") &&
                androidx.core.content.ContextCompat.checkSelfPermission(this@CaptureReviewActivity, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted || !app.registry.micLock.tryLock()) {
                status.text = "Microphone unavailable. Tap Take photo."
                return@launch
            }
            try {
                allowed()
                app.wake.awaitMicReleased()
                status.text = "Listening for GO…"
                val word = cc.bridgit.app.speech.NativeHearing.listen(app, 5, "en", shortCommand = true)
                if (word.trim().trimEnd('.', '!').equals("go", ignoreCase = true) &&
                    app.agents.get(request.agent)?.allows("mic") == true && app.settings.isOn("mic")) begin()
                else status.text = "Tap Listen for GO to try again."
            } catch (e:CancellationException) { throw e }
            catch (e:Exception) { status.text = "Voice shutter unavailable. Tap Take photo." }
            finally { app.registry.micLock.unlock() }
        }
    }
    private fun begin() {
        listening?.cancel()
        if(working||reviewing||!resumed||!hasWindowFocus()||preview.previewStreamState.value!=PreviewView.StreamState.STREAMING)return
        lifecycleScope.launch {
            try {
                allowed();busy(true,"Get ready…")
                for(n in (if (request.groupPhoto) 0 else request.countdown) downTo 1) { status.text="${if(request.video==null) "Photo" else "Recording"} in $n";delay(1000);allowed();if(!resumed||!hasWindowFocus())throw ApiError(409,"capture_cancelled","Viewfinder closed") }
                if(request.video==null) takePhoto() else record()
            } catch(e:Exception) { cancel(e.message ?: "Capture cancelled") }
        }
    }
    private fun takePhoto() {
        status.text="Taking photo…"
        val file=File(cacheDir,"visible-photo-${request.id}.jpg")
        // Match the usable selfie preview on devices whose still image is underexposed.
        val frame=if(!rear)preview.bitmap else null
        if(frame!=null) {
            val upright=Bitmap.createBitmap(frame,0,0,frame.width,frame.height,android.graphics.Matrix().apply { preScale(-1f,1f) },true)
            val bytes=java.io.ByteArrayOutputStream().use { upright.compress(Bitmap.CompressFormat.JPEG,90,it);it.toByteArray() }
            if(upright!==frame)upright.recycle();frame.recycle();reviewPhoto(bytes);return
        }
        capture?.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),app.mainExecutor,object:ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(out:ImageCapture.OutputFileResults) { try { if(!request.result.isCompleted&&resumed)reviewPhoto(file.readBytes()) } finally { file.delete() } }
            override fun onError(e:ImageCaptureException) { file.delete();cancel("Photo could not be taken") }
        }) ?: cancel("Camera is not ready")
    }
    private fun reviewPhoto(bytes:ByteArray) {
        lifecycleScope.launch {
            try {
                val ready=withContext(Dispatchers.IO) { ImageUtil.prepare(bytes,"image/jpeg",Sizing.Spec(1600,null,85),forceJpeg=true).bytes }
                allowed();if(!resumed||request.result.isCompleted)return@launch
                photoBytes=bytes;closeCamera();preview.visibility=View.GONE;photo.setImageBitmap(BitmapFactory.decodeByteArray(ready,0,ready.size));photo.visibility=View.VISIBLE
                busy(false,"Review your photo");setReview(true)
            } catch(e:CancellationException) { throw e }
              catch(e:Exception) { cancel(e.message ?: "Photo unavailable") }
        }
    }
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun record() {
        if(request.audio) {
            if(!app.registry.micLock.tryLock())throw ApiError(409,"sensor_busy","Microphone in use")
            micLocked=true;app.wake.awaitMicReleased();allowed()
            if(!resumed||request.result.isCompleted)throw ApiError(409,"capture_cancelled","Viewfinder closed")
        }
        val output=request.video ?: return
        status.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_NONE;status.announceForAccessibility("Recording started")
        status.text="● Recording · 0 / ${request.seconds}s";progress.isIndeterminate=false;progress.max=request.seconds;progress.progress=0
        shutter.visibility=View.VISIBLE;shutter.isEnabled=true;shutter.text="Stop recording";shutter.setOnClickListener { shutter.isEnabled=false;status.text="Finishing video…";recording?.stop() }
        var pending=video!!.output.prepareRecording(this,FileOutputOptions.Builder(output).setDurationLimitMillis(request.seconds*1000L).setFileSizeLimit(40L*1024*1024).build())
        if(request.audio)pending=pending.withAudioEnabled()
        recordStarted=System.currentTimeMillis()
        recording=pending.start(app.mainExecutor) { event->
            if(event is VideoRecordEvent.Finalize) {
                recording=null;timer?.cancel()
                if(request.result.isCompleted)return@start
                if(event.hasError()&&event.error !in listOf(VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED)) { cancel("Video recording failed");return@start }
                reviewVideo()
            }
        }
        timer=lifecycleScope.launch { while(isActive&&recording!=null) { try { allowed() } catch(e:Exception) { cancel(e.message ?: "Capture cancelled");break };val elapsed=((System.currentTimeMillis()-recordStarted)/1000).toInt();status.text="● Recording · $elapsed / ${request.seconds}s";progress.progress=elapsed;delay(250) } }
    }
    private fun reviewVideo() {
        val file=request.video ?: return
        if(!file.exists()||file.length()<1000) { cancel("The video was too short. Record it again.");return }
        closeCamera();preview.visibility=View.GONE;player.visibility=View.VISIBLE;player.setVideoURI(Uri.fromFile(file))
        player.setOnPreparedListener { it.isLooping=false;player.seekTo(1) };player.setOnCompletionListener { play.text="Play video" }
        player.setOnErrorListener { _,_,_->status.text="Video could not be played. Delete it and record again.";use.isEnabled=false;true }
        status.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;busy(false,"Review your video");setReview(true)
    }
    private fun discard() { player.stopPlayback();photoBytes=null;request.video?.delete() }
    private fun closeCamera() {
        timer?.cancel();timer=null
        recording?.close();recording=null
        provider?.let { p->previewUseCase?.let { p.unbind(it) };capture?.let { p.unbind(it) };video?.let { p.unbind(it) } };provider=null;previewUseCase=null;capture=null;video=null
        if(locked) { locked=false;app.registry.cameraLock.unlock() }
        if(micLocked) { micLocked=false;app.registry.micLock.unlock() }
    }
    companion object {
        private var current:CaptureReviewActivity?=null
        fun close(id:String) { current?.takeIf { it::request.isInitialized&&it.request.id==id }?.finish() }
    }
}
