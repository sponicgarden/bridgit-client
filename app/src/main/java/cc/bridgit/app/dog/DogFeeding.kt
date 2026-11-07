package cc.bridgit.app.dog

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.appdevices.AppDeviceAccess
import cc.bridgit.app.lancamera.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** Sounds request a camera check; only the visual reading may contribute a meal. */
object FeedingRules {
    const val COOLDOWN_MS=60_000L
    fun trigger(sound:DogAudioReading,camera:String,now:Long,last:Long)=sound.source==camera && sound.sound in listOf("eating","drinking") &&
        sound.confidence.isFinite() && sound.confidence in .75..1.0 && now>=sound.at && now-sound.at<=20_000 && (last==0L || now-last>=COOLDOWN_MS)
}

internal class FeedingEpoch {
    private val revision=java.util.concurrent.atomic.AtomicLong()
    val current get()=revision.get()
    fun invalidate() { revision.incrementAndGet() }
    fun isCurrent(expected:Long)=current==expected
}

class DogFeeding(private val app:BridgeApp) {
    private val prefs get()=app.getSharedPreferences("dog_feeding",android.content.Context.MODE_PRIVATE)
    private val epoch=FeedingEpoch()
    @Volatile private var pending:DogAudioReading?=null
    @Volatile private var holdUntil=0L
    @Volatile private var holdToken:String?=null
    private fun releaseHold() { holdToken?.let(CameraMotion::release);holdToken=null;holdUntil=0 }
    @Volatile private var lastTrigger=0L
    /**
     * Where the food-view camera pointed before a sound check aimed it at the bowl (7 Nov 2026). When the 30-second
     * hold ends the camera goes back there, so the monitor doesn't keep photographing the bowl instead of the pet.
     * Cleared when the owner changes the food view or turns the response off: no restore move after their change.
     */
    @Volatile private var returnTo:Pair<String,Onvif.Position>?=null
    @Volatile private var detail:String?=null
    var message:String
        get()=detail ?: if(enabled) "Ready for sampled eating/drinking sounds" else "Eating-sound response off"
        private set(value) { detail=value }
    val enabled get()=prefs.getBoolean("enabled",false)
    private fun view()=runCatching { JSONObject(prefs.getString("view","")!!) }.getOrNull()
    val camera get()=view()?.optString("camera").orEmpty()
    /** Only the host may carry enrollment proof into a reviewed same-device migration. */
    internal fun transferableView(): JSONObject? = view()?.takeIf { saved ->
        val id = saved.optString("camera")
        app.appDevices.allows(AppDeviceAccess.DOG,id) && app.lanCameras.all().any {
            it.id == id && CameraViews.fingerprint(it) == saved.optString("enrollment")
        }
    }

    suspend fun save(cameraId:String, authorize: () -> Unit = {}) = withContext(Dispatchers.IO) {
        authorize()
        check(app.appDevices.allows(AppDeviceAccess.DOG,cameraId) && app.settings.isOn("lan_camera")) { "Enable this camera in Pet monitor Device access first." }
        // Saving a new view cancels a stationary food-photo hold; active motion still owns its lease until Stop.
        this@DogFeeding.cancel()
        val lease="feeding-save-${UUID.randomUUID()}"
        check(CameraMotion.acquire(listOf(cameraId),lease)) { "Camera is moving. Wait for it to stop before saving the food view." }
        try {
        val c=app.lanCameras.get(cameraId);val client=Onvif(c,app,lease) { authorize() };val profile=client.info().profile;val position=client.feedingPosition(profile)
        authorize()
        check(CameraViews.fingerprint(app.lanCameras.get(cameraId))==CameraViews.fingerprint(c) && app.appDevices.allows(AppDeviceAccess.DOG,cameraId)) { "Camera access changed; save again." }
        synchronized(this@DogFeeding) { epoch.invalidate();pending=null;releaseHold();returnTo=null; prefs.edit().putString("view",JSONObject().put("camera",cameraId).put("profile",profile).put("x",position.x).put("y",position.y).put("enrollment",CameraViews.fingerprint(c)).toString()).apply() }
        message="Food view saved for ${c.name}. Eating sounds are not meal confirmation."
        } finally { CameraMotion.release(lease) }
    }
    @Synchronized fun setEnabled(value:Boolean) {
        check(!value || view()!=null) { "Save a food-bowl view first." }
        epoch.invalidate()
        prefs.edit().putBoolean("enabled",value).apply();pending=null;releaseHold();returnTo=null
        message=if(value) "Listening for eating/drinking in short samples; photos confirm activity" else "Eating-sound response off"
    }
    @Synchronized fun clear() {
        epoch.invalidate();pending=null;releaseHold();returnTo=null
        prefs.edit().remove("view").putBoolean("enabled",false).apply()
        message="Eating-sound response off"
    }
    fun status()=JSONObject().put("position",view()?.let { JSONObject().put("x",it.getDouble("x")).put("y",it.getDouble("y")) } ?: JSONObject.NULL).put("enabled",enabled).put("camera",camera.ifEmpty { null } ?: JSONObject.NULL).put("view_saved",view()!=null).put("status",message)
    @Synchronized fun heard(reading:DogAudioReading,now:Long=System.currentTimeMillis()) {
        if(enabled && FeedingRules.trigger(reading,camera,now,lastTrigger)) { pending=reading; message="Eating/drinking sound detected; checking food view" }
    }
    @Synchronized fun cancel() { epoch.invalidate();pending=null;releaseHold();returnTo=null }
    private fun authorized(id:String,view:JSONObject,expectedEpoch:Long) {
        check(epoch.isCurrent(expectedEpoch) && enabled && app.dog.state.value.running && app.remoteView.dogBackend && app.captureNotificationsOn() &&
            app.settings.isOn("lan_camera") && app.settings.isOn("lan_camera_audio") && id in app.dog.state.value.microphones &&
            app.appDevices.allows(AppDeviceAccess.DOG,id) && CameraViews.fingerprint(app.lanCameras.get(id))==view.getString("enrollment")) { "Feeding camera or microphone access changed; save and enable the food view again." }
    }
    @Synchronized fun holdingCamera():String? {
        if(holdUntil>0 && System.currentTimeMillis()>=holdUntil) { releaseHold();message="Listening for sampled eating/drinking sounds" }
        return camera.takeIf { enabled && System.currentTimeMillis()<holdUntil }
    }
    /** After a food-view hold: aim the camera back where it was. A failure leaves it at the bowl (no retry loop). */
    private suspend fun restoreView(id:String,v:JSONObject,expectedEpoch:Long) {
        val (profile,position)=returnTo ?: return
        returnTo=null
        if(profile!=v.getString("profile"))return
        val token="feeding-return-${UUID.randomUUID()}"
        if(!CameraMotion.acquire(listOf(id),token))return
        try {
            authorized(id,v,expectedEpoch)
            val enrollment=app.lanCameras.get(id)
            val client=Onvif(enrollment,app,token) { authorized(id,v,expectedEpoch) }
            val back=CameraAim.run(object:CameraAim.Driver {
                override suspend fun authorize() { authorized(id,v,expectedEpoch) }
                override suspend fun move() = withContext(Dispatchers.IO) { client.aimAt(profile,position) }
                override suspend fun settle() { delay(1000) }
                override suspend fun arrived():Boolean = withContext(Dispatchers.IO) {
                    val actual=client.feedingPosition(profile)
                    kotlin.math.abs(actual.x-position.x)<=.025 && kotlin.math.abs(actual.y-position.y)<=.025
                }
                override suspend fun stop():Boolean = withContext(Dispatchers.IO) { runCatching { Onvif(enrollment,app,token).stop(profile) }.isSuccess }
            })
            message=if(back) "Listening for sampled eating/drinking sounds" else "Camera did not confirm its return from the food view"
        } catch(e:CancellationException) { throw e } catch(_:Exception) { message="Camera did not return from the food view; check camera access" }
        finally { CameraMotion.release(token) }
    }

    /** Called only by the existing photo loop, never concurrently with another monitor capture. */
    suspend fun cameraForCheck():String? {
        val (expectedEpoch,saved)=synchronized(this) { epoch.current to view() };val v=saved ?: return null;val id=v.getString("camera")
        val sound=synchronized(this) { pending.also { pending=null } }
        if(sound==null) {
            if(holdingCamera()==null) { restoreView(id,v,expectedEpoch);return null }
            return try { authorized(id,v,expectedEpoch);id } catch(_:Exception) { cancel();message="Feeding camera access changed; response paused";null }
        }
        if(!FeedingRules.trigger(sound,id,System.currentTimeMillis(),lastTrigger))return null
        val token="feeding-${UUID.randomUUID()}"
        if(!CameraMotion.acquire(listOf(id),token) { cancel() }) { message="Food-view camera busy; sound check skipped";return null }
        val enrollment=runCatching { app.lanCameras.get(id) }.getOrNull()
        if(enrollment==null) { CameraMotion.release(token);return null }
        val profile=v.getString("profile")
        try {
            val taskOwned = app.applets.all().any { it.optString("native") == "dog" }
            if (taskOwned) cc.bridgit.app.applets.AppletDeviceStops.begin(app, "native:dog", id, profile, token)
            val client=Onvif(enrollment,app,token) { authorized(id,v,expectedEpoch) }
            val ready=CameraAim.run(object:CameraAim.Driver {
                override suspend fun authorize() { authorized(id,v,expectedEpoch) }
                override suspend fun move() = withContext(Dispatchers.IO) {
                    check(client.info().profile==profile) { "Camera profile changed; save food view again." }
                    // Remember the view to come back to (only the first: a later trigger mid-hold starts at the bowl).
                    if(returnTo==null) runCatching { client.feedingPosition(profile) }.getOrNull()?.let { returnTo=profile to it }
                    client.aimAt(profile,Onvif.Position(v.getDouble("x"),v.getDouble("y")))
                }
                override suspend fun settle() { delay(1000) }
                override suspend fun arrived():Boolean = withContext(Dispatchers.IO) {
                    val actual=client.feedingPosition(profile)
                    kotlin.math.abs(actual.x-v.getDouble("x"))<=.025 && kotlin.math.abs(actual.y-v.getDouble("y"))<=.025
                }
                override suspend fun stop():Boolean = withContext(Dispatchers.IO) {
                    if (taskOwned) cc.bridgit.app.applets.AppletDeviceStops.stop(app, token)
                    else runCatching { Onvif(enrollment,app,token).stop(profile) }.isSuccess
                }
            })
            if(!ready) { message="Food view or camera Stop unconfirmed; photo check skipped";return null }
            synchronized(this) { authorized(id,v,expectedEpoch);lastTrigger=System.currentTimeMillis();holdUntil=lastTrigger+30_000;holdToken=token }
            message="Checking the food view with photos; sound alone does not confirm eating"
            return id
        } catch(e:CancellationException) { holdUntil=0;throw e }
        catch(_:Exception) { holdUntil=0;message="Food-view check unavailable. Check camera access and save the food view again.";return null }
        finally { if(holdToken!=token)CameraMotion.release(token) }
    }
}
