package cc.bridgit.app.dog

import cc.bridgit.app.applets.AppletFetch
import cc.bridgit.app.applets.AppletPackage
import cc.bridgit.app.feedback.InstallIdentity
import cc.bridgit.app.feedback.TeamKeys
import cc.bridgit.app.outbound.Outbound
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.Sizing
import cc.bridgit.app.capability.ImageUtil
import cc.bridgit.app.judgment.Jev
import cc.bridgit.app.vision.ChangeSettings
import cc.bridgit.app.vision.Grids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** What the Pet monitor screen and notification show. [frame] is the latest photo (JPEG). */
data class DogStatus(
    val running: Boolean = false, val startedAt: Long = 0, val frontCamera: Boolean = false,
    /** Independently selected models, in totals preference order. Clef is the default. */
    val models: List<String> = listOf("clef"),
    val verdicts: List<Reading> = emptyList(),
    val photos: Int = 0, val asked: Int = 0, val frame: ByteArray? = null, val frameAt: Long = 0,
    val frameCamera:String? = null,
    /** Photos that looked the same as the last one asked about, so no model was asked (free). */
    val unchanged: Int = 0,
    /** Hourly checks of an unchanged-looking picture that got a different answer: changes the comparison missed. */
    val missed: Int = 0,
    val error: String? = null, val version: Int = 0,
    /** [error] as a fixed code for bridgit.cc/watch (locked, camera, camera_busy, budget, clef_quota, rate_limited, model_busy, model_failed, other). */
    val errorCode: String? = null,
    /** Today's totals, worked out off the main thread after each photo. */
    val day: DogDay? = null,
    /** The owner's camera choice: [DogCamera.AUTO] (default: test every camera, use the best), or a fixed one. */
    val cameraMode: String = DogCamera.AUTO,
    val microphones: List<String> = emptyList(),
    val microphoneStatus: String = "Microphones off",
    val audioReadings: List<DogAudioReading> = emptyList(),
    /** Today's barking (cc.bridgit.app.sound): episodes, time, last bark, barking right now. */
    val bark: cc.bridgit.app.sound.SoundDay = cc.bridgit.app.sound.SoundDay(),
    /** Cameras in use, best first; more than one take turns photo by photo. [frontCamera] = the first is the front one. */
    val cameras: List<String> = listOf(DogCamera.REAR),
    /** What the last camera test found (Auto), null before the first one. */
    val choice: DogCamera.Choice? = null,
    /** Testing each camera right now. */
    val scanning: Boolean = false,
    val panSearchEnabled: Boolean = false,
    val searching: Boolean = false,
    val searchStatus: String = "Pan search off",
    /** Auto-centring a pan/tilt camera on the pet (owner, 7 Nov 2026; on by default). */
    val autoCenterEnabled: Boolean = true,
    /** Fixed code: off, ready, centering, centered, lost, limit, cap, budget, failed, needs_jev. */
    val autoCenterStatus: String = "ready",
    val centering: Boolean = false,
    /** The latest corrections (offset, step, result), newest last. */
    val autoCenterSteps: List<cc.bridgit.app.lancamera.AutoCenterRun.Step> = emptyList(),
    /** Auto and no dog seen: roughly when the other camera is tried again (0 = not planned). */
    val nextScanAt: Long = 0,
    /** When monitoring last stopped (0 = not since the app started) and why, as a fixed code for the web portal:
     *  stopped (someone stopped it), service_stopped (Bridgit's background service closed), app_restarted (the app
     *  restarted, for example after an update, and has not resumed yet), or the stop reason's own code. */
    val stoppedAt: Long = 0, val stopCode: String? = null,
    /** When monitoring turned itself back on after a restart (0 = it was started by hand). */
    val resumedAt: Long = 0,
) {
    /** Backward-compatible primary model for the remote summary. */
    val model: String get() = models.firstOrNull() ?: "clef"
}

/**
 * Runs the Pet monitor (owner, 2 Nov 2026): every [INTERVAL_MS] it takes a small photo with the
 * phone's camera, asks the selected models about it ([DogJev]) and adds the answer to [tracker]. One photo at a time:
 * when a photo and its answers take longer than the interval, the next slot is skipped, never doubled.
 * Photos stay in memory (only the latest); the tracker's naps, meals and drinks are saved on the phone.
 * The camera needs the phone unlocked, so the screen keeps the phone awake while it is open.
 */
class DogMonitor(private val app: BridgeApp) {
    companion object {
        const val INTERVAL_MS = 10_000L
        /** Small enough for Jev to be cheap (~1,550 tokens), big enough to see a dog (Jev needs ≥ 280 px). */
        val PHOTO = Sizing.Spec(640, null, 75)
        /**
         * The change check for the Pet monitor: a photo that looks the same as the last one asked about keeps
         * that answer, and is asked again only once an hour (at most 24 calls a day per camera, about ${'$'}0.006 on
         * Clef), as a safety net for changes too small to see. It is more sensitive than watches ("high": a cell
         * moving 15 of 255, a patch of 1% of the picture), so small moves like a head lifting count. Every change
         * goes to the model; when its answer comes back the same, the change didn't matter and those spots are
         * ignored for a while (NoiseMask), so a swaying plant costs one call, not many.
         */
        val CHANGE = ChangeSettings(sensitivity = "high", maxReuseMs = 60 * 60_000L)
        private const val PREFS = "dog_monitor"
        private const val KEY_LOG = "log"
        private const val KEY_FRONT = "front_camera"
        private const val KEY_MODEL = "model" // legacy exclusive selection
        private const val KEY_MODELS = "models"
        private const val KEY_CAMERA_MODE = "camera_mode"
        /** The cameras the last test chose (comma separated), kept when a later test finds no dog. */
        private const val KEY_CAMERAS = "cameras"
        /** Monitoring should be on. Set by start, cleared only by a deliberate stop, so an update or restart resumes it. */
        private const val KEY_WANTED = "monitor_wanted"
        private const val KEY_AUTOCENTER = "autocenter"
        /** Corrections kept for the owner API and the portal. */
        const val AUTOCENTER_RECENT = 20
        val BARK_SOUNDS = setOf("barking", "growling")
        /** The Pet monitor's [cc.bridgit.app.sound.LoudSounds] subscription. */
        const val LOUD_ID = "pet_monitor"
        /**
         * A camera microphone whose loudness stream (lancamera.CameraMics) can't be held takes a 5-second sample this
         * often instead; the food-view camera takes one every sampling interval (default 20 s) either way (from the stream when it is held).
         */
        const val LAN_POLL_MS = 10 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val feeding = DogFeeding(app)
    val tracker = DogTracker()
    val backendTracker = DogTracker()
    /** Barking, on the shared sound core: a loud sound → Clef → barking/growling counts, an episode per spell. */
    val barks = cc.bridgit.app.sound.SoundEpisodes(BARK_SOUNDS, { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() })
    private fun saveBarks() = runCatching { prefs.edit().putString("barks", barks.toJson().toString()).apply() }
    fun resetBackendHistory() { backendTracker.clear(); prefs.edit().remove("backend_log").apply() }
    private val _state = MutableStateFlow(DogStatus())
    val state: StateFlow<DogStatus> get() = _state

    /** Debug builds only: photos to use instead of the camera, in turn (DebugSetupReceiver dog_fake). */
    @Volatile var fakePhotos: List<File>? = null

    init {
        runCatching { prefs.getString(KEY_LOG, null)?.let { tracker.load(JSONObject(it)) } }
        runCatching { prefs.getString("backend_log", null)?.let { backendTracker.load(JSONObject(it)) } }
        runCatching { prefs.getString("barks", null)?.let { barks.load(JSONObject(it)) } }
        val mode = prefs.getString(KEY_CAMERA_MODE, null)?.takeIf { DogCamera.isMode(it) } ?: DogCamera.AUTO
        // Before Auto existed the owner picked with a front-camera switch: that choice is the starting camera.
        val cams = prefs.getString(KEY_CAMERAS, null)?.split(",")?.filter { DogCamera.isCamera(it) }?.takeIf { it.isNotEmpty() }
            ?: listOf(if (prefs.getBoolean(KEY_FRONT, false)) DogCamera.FRONT else DogCamera.REAR)
        val inUse = if (mode == DogCamera.AUTO) cams else if (mode == DogCamera.MANUAL) prefs.getString("manual_cameras", null)?.split(",")?.filter(DogCamera::isCamera)?.takeIf { it.isNotEmpty() } ?: cams else listOf(mode)
        _state.value = DogStatus(frontCamera = inUse.first() == DogCamera.FRONT, cameraMode = mode, cameras = inUse,
            panSearchEnabled=prefs.getBoolean("pan_search",false), autoCenterEnabled=prefs.getBoolean(KEY_AUTOCENTER,true), autoCenterStatus=if(prefs.getBoolean(KEY_AUTOCENTER,true)) "ready" else "off", microphones=prefs.getStringSet("microphones",emptySet())!!.filter(DogAudio::valid).sorted(), models = DogModels.restore(prefs.getStringSet(KEY_MODELS, null), prefs.getString(KEY_MODEL, null)), day = tracker.day(System.currentTimeMillis()),
            stopCode = if (prefs.getBoolean(KEY_WANTED, false)) "app_restarted" else null, bark = barks.day(System.currentTimeMillis()))
    }

    private fun change(f: (DogStatus) -> DogStatus) = synchronized(this) { _state.value = f(_state.value).let { it.copy(version = it.version + 1) } }

    /** The applet may ask either model or both. With neither selected, stop taking photos. */
    @Synchronized fun setModelEnabled(model: String, enabled: Boolean) {
        val models = DogModels.toggle(_state.value.models, model, enabled)
        if (models == _state.value.models) return
        prefs.edit().putStringSet(KEY_MODELS, models.toSet()).apply()
        change { it.copy(models = models, verdicts = emptyList()) }
        if (models.isEmpty() && _state.value.running) stop()
    }

    /** Atomic API update: switching between models never temporarily selects neither. */
    @Synchronized fun setModels(selected:Set<String>) {
        require(selected.isNotEmpty() && selected.all { it in setOf("jev","clef") })
        val models=listOf("clef","jev").filter { it in selected }
        prefs.edit().putStringSet(KEY_MODELS,selected).apply()
        change { it.copy(models=models,verdicts=emptyList()) }
    }

    private var audioJob:Job?=null
    fun revokeMicrophones(key:String) {
        setMicrophones(_state.value.microphones.filter { (if(key=="mic")it!=DogAudio.BUILTIN else it==DogAudio.BUILTIN) && (it==DogAudio.BUILTIN || it in allowedCameraIds()) })
    }
    fun pruneMicrophones() { setMicrophones(_state.value.microphones.filter { it==DogAudio.BUILTIN || it in allowedCameraIds() }) }
    fun setMicrophones(sources:List<String>) {
        require(sources.size<=10 && sources.distinct().size==sources.size && sources.all { DogAudio.valid(it) && (it==DogAudio.BUILTIN || it in allowedCameraIds()) })
        prefs.edit().putStringSet("microphones",sources.toSet()).apply()
        audioJob?.cancel()
        val removing=_state.value.microphones.any { it !in sources }
        change { it.copy(microphones=sources,audioReadings=it.audioReadings.filter { r -> r.source in sources },microphoneStatus=if(sources.isEmpty()) "Microphones off" else "Ready for short audio samples when monitoring starts") }
        if(removing) cc.bridgit.app.remoteview.AppletCalls.cancelDog()
        if(_state.value.running)startAudio() else syncLoud()
    }

    /** Subscribed to loud sounds while running, for the ticked microphones (the built-in one only while [wantsLoud]). */
    @Volatile private var watchingListener = false
    private fun syncLoud() {
        // The listener can start (or stop) after this: keep the idle status line in step with it.
        if (!watchingListener) { watchingListener = true; scope.launch { app.wake.state.collect { refreshIdleStatus() } } }
        // The built-in microphone only while it may listen (subscribing it opens the phone's microphone); ticked
        // camera microphones always while running: CameraMics asks [wantsLoud] before and during each stream.
        val sources = _state.value.microphones.filter { if (it == DogAudio.BUILTIN) wantsLoud(it) else _state.value.running }.toSet()
        if (sources.isNotEmpty()) app.wake.loud.subscribe(cc.bridgit.app.sound.LoudSounds.Subscriber(LOUD_ID, "normal", sources, { wantsLoud(it) }) { src, wav, dur, at -> heardLoud(src, wav, dur, at) })
        else app.wake.loud.unsubscribe(LOUD_ID)
    }

    /**
     * Monitoring runs with this microphone ticked and allowed: the phone's listener (WakeListener, built-in) or a
     * camera stream (CameraMics, `lan:<id>`) watches for loud sounds, and [heardLoud] decides whether Clef hears the clip.
     */
    fun wantsLoud(source: String): Boolean = _state.value.running && source in _state.value.microphones && app.remoteView.dogBackend &&
        if (source == DogAudio.BUILTIN) app.settings.isOn("mic")
        else app.settings.isOn("lan_camera") && app.settings.isOn("lan_camera_audio") && app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG, source)

    fun wantsLoudSounds(): Boolean = wantsLoud(DogAudio.BUILTIN)

    /** Offered once: the first Start turns the built-in microphone on (the owner can untick it after). */
    fun takeMicrophoneOffer(): Boolean {
        if (prefs.getBoolean("mic_offered", false)) return false
        prefs.edit().putBoolean("mic_offered", true).apply()
        return DogAudio.BUILTIN !in _state.value.microphones
    }

    /** The idle line last shown, so a newer one replaces it but a reading's line isn't overwritten. */
    @Volatile private var idleShown: String? = null
    private fun refreshIdleStatus() {
        val s = _state.value
        if (s.running && (s.microphoneStatus == idleShown || s.microphoneStatus in setOf(app.getString(R.string.dog_mic_listening), app.getString(R.string.dog_mic_not_listening)))) {
            val line = micIdleStatus()
            if (line != s.microphoneStatus) change { it.copy(microphoneStatus = line) }
        }
    }
    private fun micIdleStatus(): String {
        val cams = _state.value.microphones.filter { it != DogAudio.BUILTIN }
        val held = cams.count { app.cameraMics.holding(it) }
        val camLine = when {
            cams.isEmpty() -> null
            held == cams.size -> app.getString(R.string.dog_mic_cameras_listening)
            held > 0 -> app.getString(R.string.dog_mic_cameras_some, held, cams.size)
            else -> app.getString(R.string.dog_mic_cameras_poll)
        }
        return when {
            DogAudio.BUILTIN in _state.value.microphones -> app.getString(if (app.wake.running?.loud == true && app.wake.loud.get(LOUD_ID) != null) R.string.dog_mic_listening else R.string.dog_mic_not_listening)
            camLine != null -> camLine
            else -> "Microphones off"
        }.also { idleShown = it }
    }

    /**
     * A loud sound from the phone's listener or a camera stream ([source]), [wav] already recorded (16 kHz WAV).
     * Counted, checked by Clef, or skipped (SoundRules); the reading keeps [source], so it says which microphone.
     * One episode counter serves every microphone: the same bark heard by two of them is one episode (its time may
     * count twice).
     */
    fun heardLoud(source: String, wav: ByteArray, durationMs: Long, at: Long) {
        if (!wantsLoud(source)) return
        val print = cc.bridgit.app.sound.SoundPrint.of(wav)
        val (decision, reused) = barks.onLoud(at, durationMs, print)
        when (decision) {
            cc.bridgit.app.sound.SoundRules.Decision.COUNT -> { saveBarks(); change { it.copy(bark = barks.day(at), microphoneStatus =
                if (reused in BARK_SOUNDS) app.getString(R.string.dog_mic_counted) else app.getString(R.string.dog_mic_same, reused.orEmpty())) }; repost() }
            cc.bridgit.app.sound.SoundRules.Decision.SKIP -> { saveBarks(); val paused = barks.day(at).pausedUntil > 0
                change { it.copy(bark = barks.day(at), microphoneStatus = app.getString(if (paused) R.string.dog_mic_paused else R.string.dog_mic_capped)) } }
            cc.bridgit.app.sound.SoundRules.Decision.ASK -> scope.launch {
                try {
                    val reading = DogAudio.classifyHeard(app, source, wav, at) { wantsLoud(source) }
                    feeding.heard(reading)
                    heard(reading, durationMs, print)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { soundFailed(e) }
            }
        }
    }

    private fun soundFailed(e: Exception) {
        if (DogAudio.budgetFailure(e)) barks.pause(System.currentTimeMillis())
        change { it.copy(bark = barks.day(System.currentTimeMillis()), microphoneStatus = when {
            DogAudio.budgetFailure(e) -> app.getString(R.string.dog_mic_paused)
            e is DogAudio.Failure -> e.message.orEmpty()
            else -> "Microphone sample unavailable. Check microphone permissions, camera access and network; this is not a silence reading." }) }
    }

    /** Clef's answer about a sound: kept, counted as barking when it is, one alert per barking spell. */
    private fun heard(reading: DogAudioReading, durationMs: Long, print: cc.bridgit.app.sound.SoundPrint? = null) {
        val fresh = barks.onAnswer(reading.at, durationMs, reading.sound, print)
        saveBarks()
        change { it.copy(audioReadings = (it.audioReadings + reading).takeLast(20), bark = barks.day(System.currentTimeMillis()),
            microphoneStatus = if (reading.onDevice) "Last audio sample: quiet (checked on device, no model asked)" else app.getString(R.string.dog_mic_last, reading.sound, (reading.confidence * 100).toInt())) }
        if (reading.sound in BARK_SOUNDS) app.log.system("audit", "sounds", "Pet monitor heard ${reading.sound} (${reading.source})")
        if (fresh && _state.value.running) DogNotifier.barking(app, reading.at)
        repost()
    }

    private fun repost() = synchronized(this) { if (_state.value.running) DogNotifier.post(app, _state.value, tracker) }
    /**
     * Every ticked microphone goes through a loudness listener (free; [heardLoud]): the phone's listener for the
     * built-in one, a camera stream (CameraMics) for each camera. A camera whose stream keeps failing takes a
     * 5-second sample every [LAN_POLL_MS] instead; the food-view camera takes one every sampling interval (default 20 s) either way, from its
     * stream when held. A quiet clip is answered on the device. These samples sit outside the loud sounds' hourly cap.
     */
    private fun startAudio() {
        audioJob?.cancel(); audioJob=null
        syncLoud()
        change { it.copy(microphoneStatus=micIdleStatus()) }
        if(_state.value.microphones.none { it!=DogAudio.BUILTIN }) return
        audioJob=scope.launch {
            val last=HashMap<String,Long>()
            val started=System.currentTimeMillis()
            while(isActive && _state.value.running) {
                app.cameraMics.sync() // in case the subscription came before Bridgit had finished starting
                refreshIdleStatus()
                for(source in _state.value.microphones.filter { it!=DogAudio.BUILTIN }) {
                    val now=System.currentTimeMillis()
                    val food=feeding.enabled && feeding.camera==source
                    // The loudness stream covers this camera unless it keeps failing (then timed samples come back).
                    if(!food && !app.cameraMics.failing(source)) { last[source]=now; continue }
                    if(now-(last[source] ?: started) < (if(food) micSampleMs(source) else LAN_POLL_MS)-1_000) continue
                    // Bounded by the timer (and quiet clips never reach Clef), so it doesn't take the loud sounds' hourly checks.
                    if(!barks.reserve(now,exempt=true)) continue
                    last[source]=now
                    try {
                        val reading=DogAudio.sample(app,source) { _state.value.running && source in _state.value.microphones }
                        feeding.heard(reading)
                        heard(reading,5_000)
                    }catch(e:CancellationException){throw e}
                    catch(e:Exception){soundFailed(e)}
                }
                delay(if(feeding.enabled) 5_000 else 15_000) // the idle line follows the camera streams; samples keep their own timers (5 s steps keep a 20 s food-bowl interval near 20 s)
            }
        }
    }

    fun setFrontCamera(front: Boolean) = setCameraMode(if (front) DogCamera.FRONT else DogCamera.REAR)

    /** Auto, Rear or Front; takes effect from the next photo. Auto tests every camera on the next photo. */
    @Volatile private var cameraSelectionEpoch=0L
    @Synchronized fun setCameraMode(mode: String) {
        cameraSelectionEpoch++
        require(DogCamera.isMode(mode))
        require(!mode.startsWith("lan:") || app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG, mode)) { "Camera is not included in Pet monitor" }
        prefs.edit().putString(KEY_CAMERA_MODE, mode).apply()
        val cams = if (mode == DogCamera.AUTO) savedCameras() else if(mode == DogCamera.MANUAL) savedCameras() else listOf(mode)
        if (mode == DogCamera.AUTO) scanRequested = true
        change { it.copy(cameraMode = mode, cameras = cams, frontCamera = cams.first() == DogCamera.FRONT, nextScanAt = 0) }
    }

    @Synchronized fun setCameras(cameras: List<String>) {
        cameraSelectionEpoch++
        require(cameras.isNotEmpty() && cameras.distinct().size == cameras.size && cameras.all { it in allowedCameraIds() }) { "Choose available cameras assigned to Pet monitor" }
        prefs.edit().putString(KEY_CAMERA_MODE, DogCamera.MANUAL).putString("manual_cameras",cameras.joinToString(",")).apply()
        scanRequested=false
        change { it.copy(cameraMode=DogCamera.MANUAL,cameras=cameras,frontCamera=cameras.first()==DogCamera.FRONT,nextScanAt=0) }
    }

    /** The "Find the best camera" button: Auto, and test every camera on the next photo. */
    fun findBestCamera() {
        if (_state.value.cameraMode != DogCamera.AUTO) setCameraMode(DogCamera.AUTO) else scanRequested = true
    }

    private fun allowedCameraIds() = DogCamera.ALL + app.lanCameras.all().map { it.id }
        .filter { app.settings.isOn("lan_camera") && app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG, it) }

    private fun savedCameras(): List<String> {
        val allowed = allowedCameraIds().toSet()
        return (prefs.getString(KEY_CAMERAS, null)?.split(",") ?: _state.value.cameras)
            .filter { it in allowed }.takeIf { it.isNotEmpty() } ?: listOf(DogCamera.REAR)
    }

    @Synchronized fun start() {
        if (app.applets.legacyDogReplaced() || job?.isActive == true || _state.value.models.isEmpty()) return
        if (!app.remoteView.dogBackend) {
            change { it.copy(error = "Device only: no photos or applet data leave this device. Clef and Jev require a personal backend; no local AI model is installed.", errorCode = "backend_off") }
            return
        }
        if (_state.value.cameraMode != DogCamera.AUTO && _state.value.cameras.any { it !in allowedCameraIds() }) {
            change { it.copy(error = app.getString(R.string.app_device_revoked), errorCode = "device_access_removed") }
            return
        }
        if (app.remoteView.dogPaused) {
            change { it.copy(error = "Paused from your web portal. Choose Resume in Edit Details.", errorCode = "portal_paused") }
            return
        }
        checks.clear()
        scanRequested = _state.value.cameraMode == DogCamera.AUTO
        lastDogAt.clear(); lastScanAt = 0
        change { it.copy(running = true, startedAt = System.currentTimeMillis(), photos = 0, asked = 0, unchanged = 0, missed = 0, error = null, errorCode = null, stopCode = null, resumedAt = 0) }
        prefs.edit().putBoolean(KEY_WANTED, true).apply()
        job = scope.launch { loop() }
        startAudio()
        change { it.copy(bark = barks.day(System.currentTimeMillis())) }
        app.log.system("audit", "setting", "You started the Pet monitor")
        DogNotifier.post(app, _state.value)
    }

    /**
     * [keepWanted]: the stop is not the owner's choice (Bridgit's service is closing, for example for an update),
     * so [resumeIfWanted] turns monitoring back on when the service starts again.
     */
    @Synchronized fun stop(reason: String? = null, reasonCode: String? = null, keepWanted: Boolean = false) {
        val wasRunning = _state.value.running
        searchJob?.cancel(); job?.cancel(); job = null
        audioJob?.cancel();audioJob=null;feeding.cancel()
        if (!keepWanted) prefs.edit().putBoolean(KEY_WANTED, false).apply()
        if (!wasRunning && keepWanted) return // nothing was running: keep the earlier stop time and reason
        change { it.copy(running = false, error = reason, errorCode = reason?.let { reasonCode ?: "other" },
            stoppedAt = System.currentTimeMillis(), stopCode = reasonCode ?: if (keepWanted) "service_stopped" else "stopped",
            frame = if (reasonCode == "device_access_removed" || reasonCode == "device_removed") null else it.frame) }
        DogNotifier.cancel(app)
        runCatching { syncLoud() }
        app.log.system("audit", "setting", "Pet monitor stopped${reason?.let { " ($it)" } ?: ""}")
    }

    internal suspend fun stopForConversion() {
        val pending = synchronized(this) { listOfNotNull(job,audioJob,searchJob).also { stop() } }
        pending.forEach { it.join() }
    }

    /** Turns monitoring back on after an update, crash or service restart, unless someone stopped it on purpose. */
    @Synchronized fun resumeIfWanted(after: String) {
        if (!prefs.getBoolean(KEY_WANTED, false) || _state.value.running || job?.isActive == true) return
        start()
        if (!_state.value.running) return
        change { it.copy(resumedAt = System.currentTimeMillis()) }
        app.log.system("audit", "setting", "Pet monitor turned back on after $after")
        // Barking detection reaches monitors that were already running before it existed (owner, 4 Nov 2026):
        // once, if Android already lets Bridgit use the microphone (a background restart can't ask).
        if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            takeMicrophoneOffer()) {
            app.settings.setOn("mic", true)
            setMicrophones((_state.value.microphones + DogAudio.BUILTIN).distinct())
            app.log.system("audit", "setting", "Pet monitor turned on the built-in microphone for barking detection")
        }
    }

    fun clearHistory() {
        tracker.clear(); save(); change { it.copy(day = tracker.day(System.currentTimeMillis())) }
    }

    private fun save() = runCatching { prefs.edit().putString(KEY_LOG, tracker.toJson().toString()).apply() }

    private suspend fun loop() {
        // A foreground listener alone does not keep the CPU awake between ten-second checks.
        // The timeout is renewed by healthy iterations, and cancellation always releases it.
        val wake = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bridgit:dog_monitor")
            .apply { setReferenceCounted(false) }
        wakeLock = wake
        var next = System.currentTimeMillis()
        var n = 0
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                wake.acquire(120_000L)
                try { once(n++) } catch (e: CancellationException) { throw e } catch (_: CameraBusy) {
                    change { it.copy(error = app.getString(R.string.dog_err_camera_busy), errorCode = "camera_busy") }
                } catch (e: ModelsFailed) {
                    val errors = failureText(e.result)
                    val code = e.result.failures.values.firstOrNull()?.let(::errorCode)
                    change { it.copy(error = errors, errorCode = code, verdicts = emptyList()) }
                    if (e.result.terminal) { stop(errors, code); return }
                } catch (e: Exception) {
                    change { it.copy(error = plain(e), errorCode = errorCode(e)) }
                    if (e is Jev.Failure && !e.retryable) { stop(plain(e), errorCode(e)); return }
                }
                val now = System.currentTimeMillis()
                next += INTERVAL_MS
                while (next <= now) next += INTERVAL_MS // a slow photo skips slots, never runs two at once
                delay(next - now)
            }
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    /** The loop's wake lock, renewed by long auto-centring runs. */
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var checks = DogChecks(CHANGE)
    private val classifiers by lazy {
        // Restore only the durable installed definition. Never keep a second private copy in monitor preferences.
        val installed = app.applets.all().filter { it.optString("native") == "dog" }.maxByOrNull { it.optLong("updated_at") }
        val profile = installed?.optJSONObject("definition")?.optJSONObject("processing")?.optJSONObject("classifier")
        PetClassifierStore(prefs, profile) {
            val key = "dog-monitor"
            val revision = PetClassifierStore.REVISION
            val link = AppletPackage.Link("https://${AppletPackage.REGISTRY_HOST}/v1/templates/$key/$revision", null, key, revision)
            InstallIdentity.ensure(app, TeamKeys.base) ?: throw AppletFetch.Failed("Register this device before downloading the Pet monitor classifier.")
            val release = AppletPackage.parse(AppletFetch.fetch(app, link, Outbound.Actor.PHONE))
            if (release.id != key || release.adapter != AppletPackage.DOG_ADAPTER)
                throw AppletFetch.Failed("The registry returned the wrong Pet monitor package.")
            release.definition.getJSONObject("processing").getJSONObject("classifier")
        }
    }

    internal fun audioClassifier(): Pair<JSONObject, () -> Unit> {
        val selection = checks
        return classifiers.audioQuestions() to {
            check(selection === checks) { "The Pet monitor classifier changed." }
        }
    }

    fun configureClassifier(profile: JSONObject?) {
        classifiers.select(profile)
        checks = DogChecks(CHANGE)
    }

    // Camera tests (Auto). Only the loop coroutine reads and writes these, apart from the request flag.
    @Volatile private var scanRequested = false
    private var lastScanAt = 0L
    private val lastDogAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun cameraSearchDelaySeconds(camera: String): Int = app.sensorSearchDelays.seconds(
        cc.bridgit.app.appdevices.AppDeviceAccess.DOG, "camera/$camera")
    private fun cameraSearchDelayMs(camera: String) = cameraSearchDelaySeconds(camera) * 1000L
    fun setCameraSearchDelay(camera: String, seconds: Int?) {
        require(camera in DogCamera.ALL || app.lanCameras.all().any { it.id == camera && app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG, camera) })
        app.sensorSearchDelays.set(cc.bridgit.app.appdevices.AppDeviceAccess.DOG, "camera/$camera", seconds)
        change { it.copy(nextScanAt = 0) }
    }
    fun cameraSearchDelays(): JSONObject = JSONObject().apply {
        allowedCameraIds().forEach { put(it, cameraSearchDelaySeconds(it)) }
    }
    /** The sampling interval every camera and microphone follows unless it has its own (default 20 s). */
    fun samplingAllSeconds(): Int = app.sensorSearchDelays.allSeconds(cc.bridgit.app.appdevices.AppDeviceAccess.DOG)
    fun setSamplingAll(seconds: Int?) {
        app.sensorSearchDelays.setAll(cc.bridgit.app.appdevices.AppDeviceAccess.DOG, seconds)
        change { it.copy(nextScanAt = 0) }
    }
    /** How often the food-bowl camera's microphone takes a sample: its own value, else the all value. */
    private fun micSampleMs(source: String) = app.sensorSearchDelays.seconds(cc.bridgit.app.appdevices.AppDeviceAccess.DOG, "microphone/$source") * 1000L
    /**
     * The sampling interval for the portal and the owner API: all_s, default_s, the seconds each camera uses now
     * (cameras) and the ones set on their own (overrides; the others follow all_s).
     */
    fun sampling(): JSONObject {
        val d = app.sensorSearchDelays; val a = cc.bridgit.app.appdevices.AppDeviceAccess.DOG
        val overrides = JSONObject()
        allowedCameraIds().forEach { id -> d.override(a, "camera/$id")?.let { overrides.put(id, it) } }
        return JSONObject().put("all_s", samplingAllSeconds()).put("default_s", cc.bridgit.app.appdevices.SensorSearchDelays.DEFAULT_SECONDS)
            .put("min_s", cc.bridgit.app.appdevices.SensorSearchDelays.MIN_SECONDS).put("max_s", cc.bridgit.app.appdevices.SensorSearchDelays.MAX_SECONDS)
            .put("cameras", cameraSearchDelays()).put("overrides", overrides)
            .put("photo_interval_s", INTERVAL_MS / 1000)
    }

    /**
     * Fresh portal photos. [deliver] gets each key's photo and reading (or an error) plus optional extra result
     * fields (the pet's box, what centring did, not_found). [progress] gets each step of a PTZ search.
     */
    suspend fun portalPhotos(targets:List<String>, authorized:()->Unit, progress:suspend (String,JSONObject)->Unit = { _,_ -> },
                             deliver:suspend (String,Pair<ByteArray,Reading>?,String?,JSONObject?)->Unit) {
        for ((camera,keys) in targets.groupBy { it.substringBefore('|') }) {
            authorized()
            // The owner's portal check comes first: a running auto-centring correction on this camera steps aside.
            if(centeringCamera==camera) centerJob?.let { it.cancel(); it.join() }
            val context=kotlin.coroutines.coroutineContext
            val completed=mutableSetOf<String>()
            val token="portal-"+UUID.randomUUID()
            var leased=false
            try {
                if(keys.any { '|' in it }) {
                    require(camera.startsWith("lan:"))
                    leased=cc.bridgit.app.lancamera.CameraMotion.acquire(listOf(camera),token) { context[Job]?.cancel() }
                    check(leased) { "Device already has an active positioning operation" }
                    val enrolled=app.lanCameras.get(camera)
                    fun allowed() {
                        if(context[Job]?.isActive==false)throw CancellationException()
                        authorized()
                        check(app.remoteView.dogBackend && !app.remoteView.dogPaused && app.remoteView.dogPhoto && app.captureNotificationsOn() && camera in allowedCameraIds())
                        val current=app.lanCameras.get(camera)
                        check(current.host==enrolled.host && current.username==enrolled.username && current.password==enrolled.password) { "Device configuration changed" }
                    }
                    val client=cc.bridgit.app.lancamera.Onvif(enrolled,app,token,::allowed)
                    val profile=withContext(Dispatchers.IO) { allowed();client.info().profile }
                    val space=withContext(Dispatchers.IO) { client.controls(profile).positionSpace } ?: throw cc.bridgit.app.lancamera.PtzSearch.PositionUnavailable()
                    val views=keys.map { it.substringAfter('|') }
                    if(views.none { it=="top" || it=="bottom" }) {
                        // Version 4: a 360° ring at the current tilt, then centre the pet (lancamera/PtzCentering.kt).
                        val withBox=true // the box needs Jev, so the search asks Jev even when Clef is selected
                        cc.bridgit.app.lancamera.PtzCenterSearch(space,object:cc.bridgit.app.lancamera.PtzCenterSearch.Driver {
                            override suspend fun authorize()=allowed()
                            override suspend fun position()=withContext(Dispatchers.IO) { client.searchPosition(profile) }
                            override suspend fun move(point:cc.bridgit.app.lancamera.PtzPoint)=withContext(Dispatchers.IO) { client.moveTo(profile,space,point) }
                            override suspend fun settle(ms:Long)=delay(ms)
                            override suspend fun progress(view:String,step:String,index:Int?,of:Int?,move:Int?,moves:Int?) {
                                runCatching { progress(camera+"|"+view,JSONObject().put("step",step).put("view",view).put("index",index ?: JSONObject.NULL).put("of",of ?: JSONObject.NULL)
                                    .put("move",move ?: JSONObject.NULL).put("moves",moves ?: JSONObject.NULL)) }.onFailure { if(it is CancellationException) throw it }
                            }
                            override suspend fun shoot(view:String):cc.bridgit.app.lancamera.PtzCenterSearch.Shot? = try {
                                val (bytes,reading,box)=portalPhotoWithBox(camera,token,withBox) {
                                    runCatching { progress(camera+"|"+view,JSONObject().put("step","analyzing").put("view",view)) }.onFailure { e -> if(e is CancellationException) throw e }
                                }
                                cc.bridgit.app.lancamera.PtzCenterSearch.Shot(reading.dogP,box,bytes to reading)
                            } catch(e:CancellationException){throw e} catch(_:Exception){null}
                            override suspend fun deliver(view:String,shot:cc.bridgit.app.lancamera.PtzCenterSearch.Shot?,centering:cc.bridgit.app.lancamera.PtzCenterSearch.Centering?) {
                                val key=camera+"|"+view
                                @Suppress("UNCHECKED_CAST") val photo=shot?.payload as? Pair<ByteArray,Reading>
                                val extra=JSONObject().apply { shot?.box?.let { put("box",it.json()) }; centering?.let { put("centering",it.json()) } }
                                deliver(key,photo,if(photo==null)"capture" else null,extra); completed.add(key)
                            }
                            override suspend fun notFound() { val key=camera+"|centered"; deliver(key,null,null,JSONObject().put("status","not_found")); completed.add(key) }
                            override suspend fun stop() { withContext(kotlinx.coroutines.NonCancellable+Dispatchers.IO) { runCatching { cc.bridgit.app.lancamera.Onvif(enrolled,app,token).stop(profile) } } }
                        }).run(views)
                    } else cc.bridgit.app.lancamera.PtzSearch(space,object:cc.bridgit.app.lancamera.PtzSearch.Driver {
                        override suspend fun authorize()=allowed()
                        override suspend fun position()=withContext(Dispatchers.IO) { client.searchPosition(profile) }
                        override suspend fun move(point:cc.bridgit.app.lancamera.PtzPoint)=withContext(Dispatchers.IO) { client.moveTo(profile,space,point) }
                        override suspend fun settle(ms:Long)=delay(ms)
                        override suspend fun capture(view:String) {
                            val key=camera+"|"+view
                            try { deliver(key,portalPhoto(camera,token),null,null) }
                            catch(e:CancellationException){throw e}
                            catch(_:Exception){deliver(key,null,"capture",null)}
                            completed.add(key)
                        }
                        override suspend fun stop() { withContext(kotlinx.coroutines.NonCancellable+Dispatchers.IO) { runCatching { cc.bridgit.app.lancamera.Onvif(enrolled,app,token).stop(profile) } } }
                    }).run(views)
                } else {
                    authorized();deliver(camera,portalPhoto(camera),null,null);completed.add(camera)
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){
                android.util.Log.w("BridgitDog","Portal check on $camera stopped: ${e.javaClass.simpleName} ${e.message}")
                for(key in keys.filter { it !in completed })deliver(key,null,if('|' in key)"position" else "capture",null)
            } finally { if(leased) cc.bridgit.app.lancamera.CameraMotion.release(token) }
        }
    }

    private suspend fun portalPhoto(camera: String, motionToken:String?=null): Pair<ByteArray, Reading> =
        portalPhotoWithBox(camera,motionToken,false).let { it.first to it.second }

    /** A portal photo and its reading; with [withBox] (Jev only) also where the pet is in the photo. */
    private suspend fun portalPhotoWithBox(camera: String, motionToken:String?, withBox:Boolean, asking:suspend ()->Unit = {}): Triple<ByteArray, Reading, cc.bridgit.app.lancamera.PetBox?> {
        check(app.remoteView.dogBackend && !app.remoteView.dogPaused && app.remoteView.dogPhoto) { "Photo sharing is unavailable" }
        check(app.captureNotificationsOn() && camera in allowedCameraIds()) { "Camera access is unavailable" }
        if (!camera.startsWith("lan:")) check(app.settings.isOn("camera")) { "Camera is off" }
        val bytes = photo(0, camera, motionToken)
        val at = System.currentTimeMillis()
        val model = state.value.models.firstOrNull() ?: error("Select an analysis model")
        asking()
        val (reading, box) = ask(bytes, at, if (withBox) "jev" else model, camera, withBox)
        check(app.remoteView.dogBackend && !app.remoteView.dogPaused && app.remoteView.dogPhoto && camera in allowedCameraIds() && app.captureNotificationsOn() && (camera.startsWith("lan:") || app.settings.isOn("camera"))) { "Camera access changed" }
        return Triple(bytes, reading, box)
    }

    private suspend fun once(n: Int) {
        val feedingCamera=feeding.cameraForCheck()
        if(feedingCamera!=null) {
            val bytes=photo(n,feedingCamera);val at=System.currentTimeMillis()
            change { it.copy(frame=bytes,frameAt=at,frameCamera=feedingCamera,photos=it.photos+1) }
            record(check(bytes,at,feedingCamera,Grids.fromJpeg(bytes),_state.value.models,force=true),feedingCamera)
            return
        }
        if (_state.value.cameraMode == DogCamera.AUTO) {
            val now = System.currentTimeMillis()
            if (scanRequested) { scan(n, full = true); return }
            if (DogCamera.rescanDue(now, lastDogAt, lastScanAt, _state.value.cameras, ::cameraSearchDelayMs)) { scan(n, full = false); return }
        }
        val s = _state.value
        val camera = DogCamera.forPhoto(s.cameras, n)
        val bytes = photo(n, camera)
        val at = System.currentTimeMillis()
        change { it.copy(frame = bytes, frameAt = at, frameCamera=camera, photos = it.photos + 1) }
        val grid = Grids.fromJpeg(bytes)
        var box: cc.bridgit.app.lancamera.PetBox? = null
        val result = check(bytes, at, camera, grid, s.models, withBox = wantsBox(camera, s)) { box = it }
        record(result, camera)
        result.readings.firstOrNull { it.model == "jev" && !it.reused }?.let { autoCenter(n, camera, it, box) }
        maybeSearch(result.tracked, n)
    }

    private suspend fun check(bytes: ByteArray, at: Long, camera: String, grid: IntArray?, models: List<String>, force: Boolean = false,
                              withBox: Boolean = false, box: (cc.bridgit.app.lancamera.PetBox?) -> Unit = {}): DogChecks.Result {
        val selection = checks
        val result = selection.check(camera, grid, at, models, force, verifySelection = { requireCurrentClassifier(selection) }) { model ->
            val boxed = withBox && model == "jev"
            ask(bytes, at, model, camera, boxed, selection).also { if (boxed) box(it.second) }.first
        }
        requireCurrentClassifier(selection)
        if (result.readings.isEmpty()) throw ModelsFailed(result)
        return result
    }

    private class ModelsFailed(val result: DogChecks.Result) : Exception()

    private fun failureText(result: DogChecks.Result): String? =
        result.failures.entries.joinToString("\n") { (model, e) -> "${DogWords.model(model)}: ${plain(e, model)}" }.ifEmpty { null }

    private fun requireCurrentClassifier(selection: DogChecks) {
        if (selection !== checks) throw ApiError(409, "conflict", "The Pet monitor classifier changed.")
    }

    /** [ask], plus where the pet is when [withBox] (Jev's box question; never sent to Clef). */
    private suspend fun ask(bytes: ByteArray, at: Long, model: String, camera: String, withBox: Boolean, selection: DogChecks = checks): Pair<Reading, cc.bridgit.app.lancamera.PetBox?> {
        val answer = withContext(Dispatchers.IO) {
            fun authorize() {
                requireCurrentClassifier(selection)
                if (!app.remoteView.dogBackend) throw ApiError(403, "backend_off", "Personal backend is off for this applet on this device.")
                if (camera.startsWith("lan:") && (!app.settings.isOn("lan_camera") || !app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG, camera)))
                    throw ApiError(403, "device_access_removed", app.getString(R.string.app_device_revoked))
            }
            authorize()
            val classifier = classifiers.current()
            authorize()
            val result = if (model == "clef") Jev.ask(app, classifier.state(), classifier.questions("the photo"), route = Jev.CLEF_ROUTE, images = listOf(Jev.dataUrl(bytes)), applet = "dog", authorized = ::authorize)
            else {
                val ref = "dog-" + UUID.randomUUID().toString()
                Jev.ask(app, classifier.state(ref), classifier.questions(withBox = withBox), assets = JSONObject().put(ref, Jev.image(bytes)), applet = "dog", authorized = ::authorize)
            }
            authorize()
            result
        }
        requireCurrentClassifier(selection)
        change { it.copy(asked = it.asked + 1) }
        val reading = DogJev.reading(at, answer, model, camera)
        return reading to (if (withBox && model != "clef" && reading.dog) DogJev.box(answer) else null)
    }

    /**
     * Tests cameras and picks the best ([DogCamera.pick]). [full]: every camera (start, the button, switching to
     * Auto), each asked fresh. Otherwise (no dog seen for a while) only the cameras not in use; those in use count
     * with their latest reading, and an idle camera whose picture hasn't changed reuses its last answer (free). Each selected model checks each tested camera. A busy camera ends the test; the next slot retries.
     */
    private suspend fun scan(n: Int, full: Boolean) {
        val epoch=cameraSelectionEpoch
        val before = _state.value
        fun current()=cameraSelectionEpoch==epoch && _state.value.cameraMode==DogCamera.AUTO
        if(!current())return
        val allowed = allowedCameraIds().toSet()
        change { it.copy(scanning = true) }
        try {
            val scores = LinkedHashMap<String, Double?>()
            if (!full) for (c in before.cameras.filter { it in allowed }) scores[c] = tracker.recent().lastOrNull { it.camera == c }?.dogP
            val tested = HashMap<String, DogChecks.Result>()
            for (c in allowed.filter { full || it !in before.cameras }) {
                if(!current())return
                val bytes = try { photo(n, c) } catch (e: ApiError) {
                    if (e.code == "invalid_parameter" || e.code == "sensor_busy" || e.code == "capture_timeout" || (c.startsWith("lan:") && e.code == "upstream_failed")) { scores[c] = null; continue } else throw e
                }
                if(!current())return
                val at = System.currentTimeMillis()
                change { it.copy(frame = bytes, frameAt = at, frameCamera=c, photos = it.photos + 1) }
                // A full scan always asks fresh. A rescan (no pet for a while) reuses a camera's last answer while
                // its picture is unchanged, re-asked at least hourly by the change gate (about 135 calls/h saved).
                val result = check(bytes, at, c, Grids.fromJpeg(bytes), before.models, force = DogChecks.scanForce(full))
                if(!current())return
                scores[c] = result.primary?.dogP
                tested[c] = result
            }
            val now = System.currentTimeMillis()
            val choice = DogCamera.pick(scores, before.cameras.firstOrNull { it in allowed }, now)
            synchronized(this) {
            if(!current())return
            lastScanAt = now
            scanRequested = false
            if (choice.why != DogCamera.Why.NO_DOG) choice.cameras.forEach { lastDogAt[it] = now }
            prefs.edit().putString(KEY_CAMERAS, choice.cameras.joinToString(",")).apply()
            if (choice.cameras.toSet() != before.cameras.toSet()) app.log.system("audit", "setting",
                "Pet monitor camera: ${choice.cameras.joinToString(" and ")} (tested ${scores.entries.joinToString { "${it.key} ${it.value?.let { p -> "%.2f".format(p) } ?: "no photo"}" }})")
            val next = if (choice.why == DogCamera.Why.NO_DOG) now + choice.cameras.maxOf(::cameraSearchDelayMs) else 0L
            change { it.copy(cameras = choice.cameras, frontCamera = choice.primary == DogCamera.FRONT, choice = choice, nextScanAt = next) }
            // The chosen camera's test photo is a real reading of the room: it goes into the day like any other.
            // Other cameras' test photos don't (a "no dog" from the wrong camera would end a nap).
            }
            if(current())tested[choice.primary]?.let { record(it, choice.primary) }
        } finally {
            change { it.copy(scanning = false) }
        }
    }

    private suspend fun record(result: DogChecks.Result, camera: String) {
        val reading = result.tracked ?: return
        if (reading.dog) {
            lastDogAt[camera] = reading.at
            rememberSearchPose(camera, reading)
        }
        tracker.add(reading)
        if (app.remoteView.dogBackend && reading.at >= app.remoteView.dogSince) {
            backendTracker.add(reading)
            prefs.edit().putString("backend_log", backendTracker.toJson().toString()).apply()
        }
        save()
        val day = tracker.day(System.currentTimeMillis())
        val errors = failureText(result)
        val code = result.failures.values.firstOrNull()?.let(::errorCode)
        change { it.copy(error = errors, errorCode = code, day = day, verdicts = result.readings, bark = barks.day(System.currentTimeMillis()),
            unchanged = it.unchanged + if (result.reused) 1 else 0, missed = it.missed + result.missed) }
        if (result.missed > 0) app.log.system("diagnostic", "judgment",
            "Pet monitor: an hourly check found a change the photo comparison missed ($camera)", level = "warn")
        synchronized(this) { if (job?.isActive == true && _state.value.running) DogNotifier.post(app, _state.value, tracker) }
    }

    private data class SearchAnchor(val pose: PetCameraSearch.Pose, val at: Long, val enrollment: cc.bridgit.app.lancamera.LanCamera)
    private val searchAnchors = java.util.concurrent.ConcurrentHashMap<String,SearchAnchor>()
    private val searchAttempts = java.util.concurrent.ConcurrentHashMap<String,Long>()
    @Volatile private var searchJob: Job? = null
    fun setPanSearch(enabled: Boolean) {
        prefs.edit().putBoolean("pan_search",enabled).apply()
        if(!enabled) searchJob?.cancel()
        change { it.copy(panSearchEnabled=enabled,searchStatus=if(enabled) "Ready to look around near the last confirmed view" else "Pan search off") }
        app.log.system("audit","setting","Pet monitor pan search ${if(enabled) "enabled" else "disabled"}")
    }
    private suspend fun rememberSearchPose(camera: String, reading: Reading) {
        if(!_state.value.panSearchEnabled || !camera.startsWith("lan:") || reading.dogP<.75) return
        val previous=searchAnchors[camera]
        if(previous!=null && reading.at-previous.at<60_000) return
        try {
            val enrolled=app.lanCameras.get(camera)
            val pose=withContext(Dispatchers.IO) {
                val client=cc.bridgit.app.lancamera.Onvif(enrolled,app)
                val profile=client.info().profile
                if(!client.positionSupported(profile)) null else client.position(profile)
            }
            if(pose!=null) searchAnchors[camera]=SearchAnchor(pose,reading.at,enrolled)
        } catch(e:CancellationException) { throw e } catch(_:Exception) { /* Unsupported positions never trigger a blind sweep. */ }
    }
    private suspend fun maybeSearch(reading: Reading?, n: Int) {
        if(!_state.value.panSearchEnabled || reading==null || reading.dark) return
        val now=System.currentTimeMillis()
        val lastSighting=lastDogAt.values.maxOrNull() ?: return
        if(now-lastSighting<PetCameraSearch.LOST_MS) return
        val target=searchAnchors.entries.filter { (id,_) -> app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG,id) &&
            (_state.value.cameraMode==DogCamera.AUTO || id in _state.value.cameras) }.maxByOrNull { it.value.at } ?: return
        val camera=target.key;val anchor=target.value
        if(now-anchor.at>PetCameraSearch.ANCHOR_MAX_AGE_MS || !PetCameraSearch.due(now,lastSighting,searchAttempts[camera],reading.dog)) return
        val enrolled=app.lanCameras.get(camera)
        if(enrolled.host!=anchor.enrollment.host || enrolled.username!=anchor.enrollment.username || enrolled.password!=anchor.enrollment.password) { searchAnchors.remove(camera); return }
        val token="pet-search-"+UUID.randomUUID()
        val epoch=cameraSelectionEpoch
        supervisorScope {
            val task=launch {
                val ctx=currentCoroutineContext()
                fun checkSearchAccess() {
                    ctx.ensureActive()
                    check(_state.value.running && _state.value.panSearchEnabled && cameraSelectionEpoch==epoch &&
                        (camera in _state.value.cameras || _state.value.cameraMode==DogCamera.AUTO) && app.remoteView.dogBackend && app.settings.isOn("lan_camera") &&
                        app.captureNotificationsOn() && app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG,camera)) { "Search permission changed" }
                    val live=app.lanCameras.get(camera)
                    check(live.host==enrolled.host && live.username==enrolled.username && live.password==enrolled.password) { "Camera enrollment changed" }
                }
                if(!cc.bridgit.app.lancamera.CameraMotion.acquire(listOf(camera),token) { ctx[Job]?.cancel() }) return@launch
                var profile: String?=null
                val watchdog=launch {
                    while(isActive) {
                        delay(250)
                        try { checkSearchAccess() } catch(_:Exception) { ctx[Job]?.cancel(); break }
                    }
                }
                try {
                    checkSearchAccess(); searchAttempts[camera]=now
                    val client=cc.bridgit.app.lancamera.Onvif(enrolled,app,token,::checkSearchAccess)
                    val initial=withContext(Dispatchers.IO) {
                        val p=client.info().profile;profile=p
                        if(!client.positionSupported(p)) null else client.position(p)
                    } ?: run { change { it.copy(searchStatus="This camera does not report a supported pan/tilt position") }; return@launch }
                    if(!PetCameraSearch.nearAnchor(anchor.pose,initial)) {
                        change { it.copy(searchStatus="Camera moved away from the last confirmed view; waiting for a new sighting") };return@launch
                    }
                    change { it.copy(searching=true, searchStatus="Looking around near the last confirmed view") }
                    val taskOwned = app.applets.all().any { it.optString("native") == "dog" }
                    if (taskOwned) cc.bridgit.app.applets.AppletDeviceStops.begin(app, "native:dog", camera, profile!!, token)
                    val found=PetCameraSearch.run(object: PetCameraSearch.Driver {
                        override suspend fun authorize() { authorizeSearch() }
                        private fun authorizeSearch() { checkSearchAccess() }
                        override suspend fun move(pose: PetCameraSearch.Pose) {
                            withContext(Dispatchers.IO) { client.goTo(profile!!,pose) }
                            delay(2000); authorizeSearch()
                        }
                        override suspend fun observe(): Boolean {
                            authorizeSearch()
                            val bytes=photo(n,camera,token);val at=System.currentTimeMillis()
                            change { it.copy(frame=bytes,frameAt=at,frameCamera=camera,photos=it.photos+1) }
                            val result=check(bytes,at,camera,Grids.fromJpeg(bytes),_state.value.models,force=true)
                            authorizeSearch(); record(result,camera)
                            return result.tracked?.dog==true
                        }
                        override suspend fun stop() {
                            withContext(Dispatchers.IO) {
                                if (taskOwned) cc.bridgit.app.applets.AppletDeviceStops.stop(app, token)
                                else runCatching { cc.bridgit.app.lancamera.Onvif(enrolled,app,token).stop(profile!!) }.isSuccess
                            }
                        }
                    },anchor.pose,initial)
                    if(found && _state.value.cameraMode==DogCamera.AUTO) {
                        prefs.edit().putString(KEY_CAMERAS,camera).apply()
                        lastScanAt=System.currentTimeMillis()
                        change { it.copy(cameras=listOf(camera),frontCamera=false) }
                    }
                    change { it.copy(searchStatus=if(found) "Pet found; watching this view" else "Pet not found nearby; returned to the starting view") }
                } catch(_:TimeoutCancellationException) {
                    change { it.copy(searchStatus="Search reached its time limit; camera stopped") }
                } catch(e:CancellationException) {
                    change { it.copy(searchStatus="Search stopped") };throw e
                } catch(_:Exception) {
                    change { it.copy(searchStatus="Search stopped; check camera access and connection") }
                } finally {
                    watchdog.cancel()
                    // Also covers failures before the bounded search starts.
                    withContext(kotlinx.coroutines.NonCancellable+Dispatchers.IO) { profile?.let { runCatching { cc.bridgit.app.lancamera.Onvif(enrolled,app,token).stop(it) } } }
                    cc.bridgit.app.lancamera.CameraMotion.release(token)
                    change { it.copy(searching=false) }
                }
            }
            searchJob=task
            try { task.join() } finally { if(searchJob===task)searchJob=null }
        }
    }

    // ---- Auto-centring (owner, 7 Nov 2026; lancamera/PtzAutoCenter.kt) ----
    private data class Ptz(val enrollment: cc.bridgit.app.lancamera.LanCamera, val profile: String, val space: cc.bridgit.app.lancamera.PtzSpace?, val at: Long)
    private val ptzCache = java.util.concurrent.ConcurrentHashMap<String, Ptz>()
    private val stepBudget = cc.bridgit.app.lancamera.StepBudget()
    private val centerGate = cc.bridgit.app.lancamera.AutoCenterGate()
    @Volatile private var centerJob: Job? = null
    /** The camera an auto-centring run is turning, so a portal check of it can take over at once. */
    @Volatile private var centeringCamera: String? = null

    /** On: after a photo where Jev sees the pet well off the middle of a pan/tilt camera, steer toward it and re-shoot at once. */
    fun setAutoCenter(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOCENTER, enabled).apply()
        if (!enabled) centerJob?.cancel()
        centerGate.clear()
        change { it.copy(autoCenterEnabled = enabled, autoCenterStatus = if (enabled) "ready" else "off") }
        app.log.system("audit", "setting", "Pet monitor auto-centring ${if (enabled) "on" else "off"}")
    }

    /** For the owner API and the portal: the switch, what the last run did, and the latest corrections. */
    fun autoCenterJson(): JSONObject = _state.value.let { s ->
        JSONObject().put("enabled", s.autoCenterEnabled).put("status", s.autoCenterStatus).put("centering", s.centering)
            .put("dead_zone", cc.bridgit.app.lancamera.AutoCenter.DEAD_ZONE).put("max_steps", cc.bridgit.app.lancamera.AutoCenter.MAX_STEPS)
            .put("hourly_steps", cc.bridgit.app.lancamera.AutoCenter.HOURLY_STEPS).put("steps_left_this_hour", stepBudget.left(System.currentTimeMillis()))
            .put("recent", org.json.JSONArray(s.autoCenterSteps.takeLast(10).map { it.json() }))
    }

    /** Ask Jev for the pet's box with this photo (one extra question in the same call): only for a pan/tilt camera being auto-centred. */
    private fun wantsBox(camera: String, s: DogStatus): Boolean {
        if (!s.autoCenterEnabled || !camera.startsWith("lan:") || (feeding.enabled && feeding.camera == camera)) return false
        if ("jev" !in s.models) { if (s.autoCenterStatus != "needs_jev") change { it.copy(autoCenterStatus = "needs_jev") }; return false }
        if (s.autoCenterStatus == "needs_jev") change { it.copy(autoCenterStatus = "ready") }
        val known = ptzCache[camera]
        return known == null || known.space != null || System.currentTimeMillis() - known.at > PTZ_RECHECK_MS
    }

    private val PTZ_RECHECK_MS = 10 * 60_000L

    /** The camera's pan/tilt space, or null for a fixed camera (checked again every [PTZ_RECHECK_MS]). */
    private suspend fun ptz(camera: String): Ptz? {
        val enrolled = app.lanCameras.get(camera)
        val now = System.currentTimeMillis()
        ptzCache[camera]?.let { c ->
            val same = c.enrollment.host == enrolled.host && c.enrollment.username == enrolled.username && c.enrollment.password == enrolled.password
            if (same && (c.space != null || now - c.at < PTZ_RECHECK_MS)) return c.takeIf { it.space != null }
        }
        val found = try {
            withContext(Dispatchers.IO) {
                val client = cc.bridgit.app.lancamera.Onvif(enrolled, app)
                val profile = client.info().profile
                Ptz(enrolled, profile, client.controls(profile).positionSpace, now)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Ptz(enrolled, "", null, now) }
        ptzCache[camera] = found
        return found.takeIf { it.space != null }
    }

    private fun outOfBudget(e: Exception) = e is Jev.Failure && e.reason in setOf("spend_cap", "applet_budget", "rate_limited", "clef_quota")

    /**
     * [reading] is a fresh Jev answer for a monitor photo from [camera] and [box] where it saw the pet. When the pet is
     * outside the dead zone, steer toward it and photograph again at once until it is centred or a stop applies
     * ([cc.bridgit.app.lancamera.AutoCenterRun]). Each correction photo is a normal reading (it counts in the day).
     */
    private suspend fun autoCenter(n: Int, camera: String, reading: Reading, box: cc.bridgit.app.lancamera.PetBox?) {
        val s = _state.value
        if (!s.autoCenterEnabled || !camera.startsWith("lan:") || reading.dark || (feeding.enabled && feeding.camera == camera)) return
        if (!cc.bridgit.app.lancamera.AutoCenter.needs(reading.dogP, box)) {
            if (box != null && reading.dogP >= cc.bridgit.app.lancamera.AutoCenter.PET_MIN && ptzCache[camera]?.space != null && s.autoCenterStatus != "centered") change { it.copy(autoCenterStatus = "centered") }
            return
        }
        val started = System.currentTimeMillis()
        if (!centerGate.mayStart(camera, started)) return
        if (stepBudget.left(started) <= 0) { if (s.autoCenterStatus != "budget") change { it.copy(autoCenterStatus = "budget") }; return }
        val ptz = ptz(camera) ?: return
        val space = ptz.space!!
        val enrolled = ptz.enrollment
        val token = "pet-center-" + UUID.randomUUID()
        val target = camera
        val epoch = cameraSelectionEpoch
        var outcome: cc.bridgit.app.lancamera.AutoCenterRun.Outcome? = null
        var leased = false
        supervisorScope {
            val task = launch {
                val ctx = currentCoroutineContext()
                fun allowed() {
                    ctx.ensureActive()
                    check(_state.value.running && _state.value.autoCenterEnabled && cameraSelectionEpoch == epoch && app.remoteView.dogBackend && !app.remoteView.dogPaused &&
                        app.settings.isOn("lan_camera") && app.captureNotificationsOn() && app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG, camera) &&
                        (camera in _state.value.cameras || _state.value.cameraMode == DogCamera.AUTO)) { "Auto-centring permission changed" }
                    val live = app.lanCameras.get(camera)
                    check(live.host == enrolled.host && live.username == enrolled.username && live.password == enrolled.password) { "Camera enrollment changed" }
                }
                if (!cc.bridgit.app.lancamera.CameraMotion.acquire(listOf(camera), token) { ctx[Job]?.cancel() }) return@launch
                leased = true; centeringCamera = camera
                change { it.copy(centering = true, autoCenterStatus = "centering") }
                // The camera turned since its last photo was compared: that baseline no longer matches the view.
                var movedSinceShot = false
                try {
                    val client = cc.bridgit.app.lancamera.Onvif(enrolled, app, token, ::allowed)
                    outcome = cc.bridgit.app.lancamera.AutoCenterRun(space, object : cc.bridgit.app.lancamera.AutoCenterRun.Driver {
                        override val camera = target
                        override suspend fun authorize() = allowed()
                        override suspend fun position() = withContext(Dispatchers.IO) { client.searchPosition(ptz.profile) }
                        override suspend fun move(point: cc.bridgit.app.lancamera.PtzPoint) { movedSinceShot = true; withContext(Dispatchers.IO) { client.moveTo(ptz.profile, space, point) } }
                        override suspend fun settle(ms: Long) = delay(ms)
                        override suspend fun shoot(): cc.bridgit.app.lancamera.AutoCenterRun.Shot? {
                            allowed(); wakeLock?.acquire(120_000L)
                            val bytes = photo(n, camera, token)
                            val at = System.currentTimeMillis()
                            change { it.copy(frame = bytes, frameAt = at, frameCamera = camera, photos = it.photos + 1) }
                            checks.forgetCamera(camera)
                            var seen: cc.bridgit.app.lancamera.PetBox? = null
                            // Only Jev gives the box, so a correction photo asks only Jev: no extra Clef call per step.
                            val result = try { check(bytes, at, camera, Grids.fromJpeg(bytes), listOf("jev"), force = true, withBox = true) { seen = it } }
                                catch (e: ModelsFailed) { if (e.result.failures.values.any(::outOfBudget)) throw cc.bridgit.app.lancamera.AutoCenterRun.OutOfBudget(); throw e }
                            movedSinceShot = false
                            allowed(); record(result, camera)
                            if (result.readings.any { it.dark }) return cc.bridgit.app.lancamera.AutoCenterRun.Shot(0.0, null) // too dark to see: lost
                            val jev = result.readings.firstOrNull { it.model == "jev" }
                                ?: if (result.failures["jev"]?.let(::outOfBudget) == true) throw cc.bridgit.app.lancamera.AutoCenterRun.OutOfBudget() else return null
                            return cc.bridgit.app.lancamera.AutoCenterRun.Shot(if (jev.dark) 0.0 else jev.dogP, seen)
                        }
                        override suspend fun logged(step: cc.bridgit.app.lancamera.AutoCenterRun.Step) {
                            change { it.copy(autoCenterSteps = (it.autoCenterSteps + step).takeLast(AUTOCENTER_RECENT)) }
                            app.log.system("diagnostic", "lancamera", "Pet monitor auto-centre ($camera) ${step.line()}")
                        }
                    }, stepBudget).run(cc.bridgit.app.lancamera.AutoCenterRun.Shot(reading.dogP, box))
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    outcome = cc.bridgit.app.lancamera.AutoCenterRun.Outcome("failed", 0, false)
                    android.util.Log.w("BridgitDog", "Auto-centring on $camera stopped: ${e.javaClass.simpleName}")
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { cc.bridgit.app.lancamera.Onvif(enrolled, app, token).stop(ptz.profile) } }
                    if (movedSinceShot) checks.forgetCamera(camera)
                    centeringCamera = null
                    cc.bridgit.app.lancamera.CameraMotion.release(token)
                    change { it.copy(centering = false, autoCenterStatus = if (outcome != null) it.autoCenterStatus else if (it.autoCenterEnabled) "ready" else "off") }
                }
            }
            centerJob = task
            try { task.join() } finally { if (centerJob === task) centerJob = null }
        }
        if (!leased) return
        val o = outcome ?: run {
            // Stopped by another camera operation (a portal check) or the switch: try again after a short wait.
            centerGate.finished(camera, "lost", System.currentTimeMillis())
            app.log.system("diagnostic", "lancamera", "Pet monitor auto-centre ($camera): stopped for another camera operation")
            return
        }
        centerGate.finished(camera, o.result, System.currentTimeMillis())
        change { it.copy(autoCenterStatus = if (it.autoCenterEnabled) o.result else "off") }
        app.log.system("diagnostic", "lancamera", "Pet monitor auto-centre ($camera): ${o.result} after ${o.steps} step${if (o.steps == 1) "" else "s"}${if (o.limited) " (camera limit)" else ""}")
    }

    /** The camera is in use (an agent's photo, an armed camera): this slot is skipped. */
    private class CameraBusy : Exception()

    private suspend fun photo(n: Int, camera: String, motionToken:String?=null): ByteArray {
        if(camera.startsWith("lan:")) try { cc.bridgit.app.lancamera.CameraMotion.check(camera,motionToken) } catch(_:Exception){throw CameraBusy()}
        if (camera.startsWith("lan:") && (!app.settings.isOn("lan_camera") || !app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG, camera)))
            throw ApiError(403, "device_access_removed", app.getString(R.string.app_device_revoked))
        fakePhotos?.takeIf { it.isNotEmpty() }?.let { files ->
            // Debug: each camera gets its own file in turn, so a test can show the dog to one camera only.
            val f = files[Math.floorMod(n + DogCamera.ALL.indexOf(camera), files.size)]
            return withContext(Dispatchers.IO) { ImageUtil.prepare(f.readBytes(), "image/jpeg", PHOTO).bytes }
        }
        if (camera.startsWith("lan:")) {
            val captureToken=motionToken ?: "photo-"+UUID.randomUUID()
            if(motionToken==null && !cc.bridgit.app.lancamera.CameraMotion.acquire(listOf(camera),captureToken))throw CameraBusy()
            return try { cc.bridgit.app.lancamera.RtspFrame.snapshot(app, app.lanCameras.get(camera)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw ApiError(502, "upstream_failed", "Local camera unavailable. Check Wi-Fi and its Camera Account.") }
            finally { if(motionToken==null)cc.bridgit.app.lancamera.CameraMotion.release(captureToken) }
        }
        // Start may have just launched the service: give it a few seconds to come up with the camera.
        var waited = 0
        while (BridgeService.running?.cameraReady != true && waited < 8_000) { delay(250); waited += 250 }
        val svc = BridgeService.running ?: throw ApiError(503, "service_start_blocked", "Bridgit isn't running. Open Bridgit.")
        // Same lock as agents' camera calls, so the two never open the camera at once.
        if (!app.registry.cameraLock.tryLock()) throw CameraBusy()
        val token = svc.captureStarted(app.getString(R.string.dog_title))
        try {
            return svc.camera.snapshot(rear = camera != DogCamera.FRONT, flash = false, spec = PHOTO).bytes
        } finally {
            svc.captureEnded(token)
            app.registry.cameraLock.unlock()
        }
    }

    /** The same error as a fixed code ([DogStatus.errorCode]); never carries server text. */
    private fun errorCode(e: Exception): String = when {
        e is ApiError && e.code == "device_locked" -> "locked"
        e is ApiError -> "camera"
        e is Jev.Failure && e.reason == "spend_cap" -> "budget"
        e is Jev.Failure && e.reason == "clef_quota" -> "clef_quota"
        e is Jev.Failure && e.reason == "rate_limited" -> "rate_limited"
        e is Jev.Failure && e.retryable -> "model_busy"
        e is Jev.Failure -> "model_failed"
        else -> "other"
    }

    /** An error in plain words for the screen. */
    private fun plain(e: Exception, model: String = _state.value.model): String = when {
        e is ApiError && e.code == "device_locked" -> app.getString(R.string.dog_err_locked)
        e is ApiError -> app.getString(R.string.dog_err_camera, e.message ?: e.code)
        e is Jev.Failure && e.reason == "spend_cap" -> e.message ?: app.getString(R.string.dog_err_budget)
        e is Jev.Failure && e.reason == "clef_quota" -> app.getString(R.string.dog_err_clef_quota)
        e is Jev.Failure && e.reason == "rate_limited" -> app.getString(R.string.dog_err_rate, DogWords.model(model))
        e is Jev.Failure && e.retryable -> app.getString(R.string.dog_err_jev_busy, DogWords.model(model))
        e is Jev.Failure -> app.getString(R.string.dog_err_jev, DogWords.model(model), e.message ?: e.reason)
        else -> app.getString(R.string.dog_err_other, e.javaClass.simpleName)
    }
}

/** Words for activities and moods. */
object DogWords {
    fun model(m: String): String = if (m == "jev") "Jev" else "Clef"

    fun activity(ctx: Context, a: String?): String = ctx.getString(when (a) {
        "sleeping" -> R.string.dog_act_sleeping; "resting" -> R.string.dog_act_resting; "eating" -> R.string.dog_act_eating
        "drinking" -> R.string.dog_act_drinking; "playing" -> R.string.dog_act_playing; "moving" -> R.string.dog_act_moving
        "other" -> R.string.dog_act_other; else -> R.string.dog_act_unsure
    })

    fun mood(ctx: Context, m: String?): String = ctx.getString(when (m) {
        "relaxed" -> R.string.dog_mood_relaxed; "happy" -> R.string.dog_mood_happy; "alert" -> R.string.dog_mood_alert
        "anxious" -> R.string.dog_mood_anxious; "low" -> R.string.dog_mood_low; else -> R.string.dog_mood_unsure
    })

    /** "1 h 5 min", "12 min", "40 s". */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min" + if (s % 60 > 0) " ${s % 60} s" else ""
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }

    /** One line for the latest reading. */
    fun line(ctx: Context, r: Reading?): String = when {
        r == null -> ctx.getString(R.string.dog_waiting)
        r.dark -> ctx.getString(R.string.dog_too_dark)
        !r.dog -> ctx.getString(R.string.dog_none)
        else -> listOfNotNull(activity(ctx, r.activity), r.mood?.let { mood(ctx, it) }).joinToString(" · ")
    }
}

/** The ongoing "Watching your dog" notification with Stop; tap opens the Pet monitor. */
object DogNotifier {
    const val CHANNEL = "dog_monitor"
    const val NOTIF_ID = 6200
    const val ALERT_CHANNEL = "dog_alerts"
    const val ALERT_ID = 6210
    /** Same string as MainActivity reads. */
    const val EXTRA_OPEN_DOG = "open_dog"

    fun cancel(ctx: Context) { runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) } }

    fun post(ctx: Context, s: DogStatus, tracker: DogTracker? = null) {
        if (!s.running) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.dog_title), NotificationManager.IMPORTANCE_LOW).apply {
                description = ctx.getString(R.string.dog_channel_desc); setSound(null, null); enableVibration(false)
            })
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(ctx, NOTIF_ID,
            Intent().setClassName(ctx, "cc.bridgit.app.ui.MainActivity").putExtra(EXTRA_OPEN_DOG, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getBroadcast(ctx, NOTIF_ID + 1, Intent(ctx, DogStopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val base = s.error ?: DogWords.line(ctx, tracker?.latest())
        val text = when {
            s.bark.now -> ctx.getString(R.string.dog_barking_now) + " · " + base
            s.bark.episodes > 0 -> base + " · " + ctx.getString(R.string.dog_barked_today, s.bark.episodes)
            else -> base
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif).setColor(ctx.getColor(R.color.brand_primary))
            .setContentTitle(ctx.getString(R.string.dog_notif_title)).setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, ctx.getString(R.string.dog_stop), stop)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }
}

/** One alert when a barking spell starts (SoundEpisodes): its own channel, so the owner can silence it in Android. */
fun DogNotifier.barking(ctx: Context, at: Long) {
    val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
    if (nm.getNotificationChannel(DogNotifier.ALERT_CHANNEL) == null)
        nm.createNotificationChannel(NotificationChannel(DogNotifier.ALERT_CHANNEL, ctx.getString(R.string.dog_alert_channel), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = ctx.getString(R.string.dog_alert_channel_desc) })
    if (!nm.areNotificationsEnabled()) return
    val open = PendingIntent.getActivity(ctx, DogNotifier.ALERT_ID,
        Intent().setClassName(ctx, "cc.bridgit.app.ui.MainActivity").putExtra(DogNotifier.EXTRA_OPEN_DOG, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val n = NotificationCompat.Builder(ctx, DogNotifier.ALERT_CHANNEL)
        .setSmallIcon(R.drawable.ic_notif).setColor(ctx.getColor(R.color.brand_primary))
        .setContentTitle(ctx.getString(R.string.dog_alert_title))
        .setContentText(ctx.getString(R.string.dog_alert_text, android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(at))))
        .setContentIntent(open).setAutoCancel(true).setWhen(at).setShowWhen(true)
        .setCategory(NotificationCompat.CATEGORY_EVENT)
        .build()
    runCatching { nm.notify(DogNotifier.ALERT_ID, n) }
}

/** The notification's Stop. Not exported. */
class DogStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as BridgeApp).dog.stop()
    }
}
