package cc.bridgit.app.remoteview

import android.content.Context
import android.os.Build
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.feedback.FeedbackUploader
import cc.bridgit.app.listen.WakeListener
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.outbound.Outbound
import cc.bridgit.app.update.UpdateLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote view (owner request 2 Nov 2026): the owner watches what agents do on the phone from bridgit.cc/myapps,
 * signed in with Google, while away from the phone. Off by default (Settings › Privacy › Remote view).
 *
 * While on and linked, the phone sends the Bridgit team backend (bridgit-cloud/feedback/src/watch.mjs) one short line per
 * agent action ([RemoteViewLines]) a few seconds after it happens, and every 5 minutes a snapshot: which
 * capabilities are on, off or blocked, which listeners run, the agents' names and the app version. Requests are
 * signed with the install key like report uploads and appear in the outbound log (GET /v1/outbound).
 * Before linking nothing but a "am I linked?" check is sent. Turning it off deletes the link and every line
 * stored for this phone on the backend.
 *
 * Pet monitor panel ([RemoteViewDog]): while linked, the Pet monitor's summary goes to POST /v1/watch/dog at most
 * every 30 s (Start and Stop at once), and, while the owner's "Show the latest photo" switch is on (default), the
 * newest photo (≤ 640 px, ≤ 100 KB) at most every 30 s. Switching the photo off deletes the stored one.
 */
class RemoteView(private val app: BridgeApp) {
    data class Code(val code: String, val url: String, val expiresAt: Long)
    class Failed(message: String, val code: String? = null) : Exception(message)

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _linked = MutableStateFlow(prefs.getString("linked_email", null))
    /** The Google account this phone is linked to, or null. */
    val linked: StateFlow<String?> get() = _linked
    @Volatile private var lastStatusAt = 0L
    private val dogLock = Mutex()
    private var linkPolling: Job? = null
    @Volatile private var dogSentRunning: Boolean? = null
    @Volatile private var dogSentAt = 0L
    @Volatile private var photoSentAt = 0L
    @Volatile private var photoSentFrameAt = 0L
    /** Web portals of installed package applets (the kitchen cleanliness monitor…), each its own owner opt-in. */
    val packages by lazy { PackagePortals(app, ::call, { _linked.value?.takeIf { portalAccountLinked() } }, ::small) }
    /** Anything that needs this device linked and checking in: remote view, the Pet monitor or an applet portal. */
    private val wanted: Boolean get() = enabled || dogBackend || packages.active

    val enabled: Boolean get() = prefs.getBoolean(PREF_ON, false)
    val configured: Boolean get() = FeedbackUploader.configured
    /** Explicit instance consent. This SharedPreferences file belongs to this app install/device. */
    val portalEmail: String? get() = prefs.getString("dog_portal_email", null)
    val portalAlias: String? get() = prefs.getString("dog_portal_alias", null)
    fun setDogPortal(email: String, alias: String) {
        val changed = portalEmail != null && !portalEmail.equals(email.trim(), ignoreCase = true)
        if (changed && dogBackend) {
            dogServerReady = false
            AppletCalls.cancelDog()
            prefs.edit().putBoolean("backend_dog_delete", true).putLong("backend_dog_since", System.currentTimeMillis()).commit()
            app.dog.resetBackendHistory()
        }
        prefs.edit().putString("dog_portal_email", email.trim().lowercase()).putString("dog_portal_alias", alias).commit()
        dogServerReady = false
        if (changed) scope.launch { syncDogBackend() }
    }
    val dogBackendChosen: Boolean get() = prefs.getBoolean("backend_dog_chosen", false)
    val dogBackend: Boolean get() = prefs.getBoolean("backend_dog", false)
    val dogSince: Long get() = prefs.getLong("backend_dog_since", Long.MAX_VALUE)
    val dogPendingDelete: Boolean get() = prefs.getBoolean("backend_dog_delete", false)
    /**
     * Applets with a web portal on this device's account, from the last check-in (owner, 5 Nov 2026): set up on any
     * of its devices or shared with it. Applets › Portals lists them on every device. Empty while unlinked.
     */
    val accountApplets: Set<String> get() = prefs.getStringSet("account_applets", null) ?: emptySet()
    private val _backendStatus = MutableStateFlow("")
    val backendStatus: StateFlow<String> get() = _backendStatus
    @Volatile private var dogServerReady = false

    fun setDogBackend(on: Boolean) {
        prefs.edit().putBoolean("backend_dog_chosen", true).commit()
        if (on == dogBackend) return
        if (on) {
            if (dogPendingDelete) { _backendStatus.value = "Finish deleting the previous backend before enabling it again."; return }
            prefs.edit().remove("dog_web_paused").remove("dog_control_generation").remove("dog_control_revision").remove("dog_sampling_generation").remove("dog_sampling_revision").putBoolean("backend_dog", true).putLong("backend_dog_since", System.currentTimeMillis()).commit()
            app.dog.resetBackendHistory()
            dogServerReady = false; photoSentFrameAt = 0L; dogSentRunning = null
            _backendStatus.value = "Link your Google account to start the personal backend."
        } else {
            // Gate first: no new applet uploads or model calls may start after this returns.
            prefs.edit().putBoolean("backend_dog", false).putBoolean("backend_dog_delete", true).commit()
            dogServerReady = false
            AppletCalls.cancelDog()
            app.dog.stop()
            _backendStatus.value = "Device only. Server deletion pending; will retry when online."
        }
        scope.launch { syncDogBackend() }
    }

    val dogPaused: Boolean get() = dogBackend && prefs.getBoolean("dog_web_paused", false)

    @Synchronized private fun applyDogControl(control: JSONObject, consent: Long) {
        if (!dogBackend || dogSince != consent || !control.has("paused")) return
        val generation = control.optString("generation")
        val revision = control.optLong("revision")
        if (generation.isEmpty()) return
        val previousGeneration = prefs.getString("dog_control_generation", null)
        val previousRevision = prefs.getLong("dog_control_revision", 0)
        if (!RemoteViewDog.newControl(generation, revision, previousGeneration, previousRevision)) return
        val paused = control.optBoolean("paused")
        // Since the sampling interval (4 Nov 2026) a revision can change only that: start or stop only when paused changed.
        val wasPaused = prefs.getBoolean("dog_web_paused", false)
        prefs.edit().putBoolean("dog_web_paused", paused).putString("dog_control_generation", generation)
            .putLong("dog_control_revision", revision).commit()
        applySampling(RemoteViewDog.samplingCommand(control.optJSONObject("sampling")), generation)
        if (revision == 0L) return // a new portal starts unpaused but never starts monitoring by itself
        if (paused == wasPaused) return
        if (paused) {
            app.dog.stop("Paused from your web portal.", "portal_paused")
            AppletCalls.cancelDog()
        } else app.dog.start()
        app.log.system("audit", "setting", if (paused) "You paused Pet monitor from the web portal" else "You resumed Pet monitor from the web portal")
    }

    /** The portal's sampling interval: applied once per revision, so a later change on the phone isn't undone by a pause. */
    private fun applySampling(command: RemoteViewDog.SamplingCommand?, generation: String) {
        if (command == null) return
        if (!RemoteViewDog.newControl(generation, command.revision, prefs.getString("dog_sampling_generation", null), prefs.getLong("dog_sampling_revision", -1))) return
        prefs.edit().putString("dog_sampling_generation", generation).putLong("dog_sampling_revision", command.revision).commit()
        app.dog.setSamplingAll(command.allS)
        command.cameras.forEach { (id, seconds) -> runCatching { app.dog.setCameraSearchDelay(id, seconds) } } // a camera removed since is skipped
        app.log.system("audit", "setting", "You set Pet monitor's sampling interval from the web portal: all cameras and microphones ${command.allS ?: "${cc.bridgit.app.appdevices.SensorSearchDelays.DEFAULT_SECONDS} (default)"} s" +
            if (command.cameras.isEmpty()) "" else "; " + command.cameras.entries.joinToString { "${it.key} ${it.value?.let { v -> "$v s" } ?: "follows all"}" })
        scope.launch { runCatching { pushDog(force = true) } }
    }

    private fun legacyCaptureEnabled() = dogBackend && !app.applets.legacyDogReplaced()

    @Synchronized private fun applyCapture(command: JSONObject?, consent: Long) {
        if (command == null || !legacyCaptureEnabled() || dogPaused || !dogPhoto || dogSince != consent) return
        val id = command.optString("id")
        if (id.isEmpty() || id == prefs.getString("last_capture_id", null) || command.optLong("created_at") <= prefs.getLong("last_capture_at", 0) || command.optLong("expires_at") <= System.currentTimeMillis()) return
        prefs.edit().putString("last_capture_id", id).putLong("last_capture_at", command.optLong("created_at")).commit()
        scope.launch {
            val cameras = command.optJSONArray("cameras") ?: return@launch
            val all=(0 until cameras.length()).map(cameras::getString)
            val (listens,rest)=all.partition { it.endsWith("|listen") }
            val (sounds,targets)=rest.partition { it.endsWith("|sound") }
            for(key in listens) {
                fun allowed()=legacyCaptureEnabled() && dogSince == consent && !dogPaused && dogPhoto && command.optLong("expires_at") > System.currentTimeMillis()
                if (!allowed()) return@launch
                val source=key.removeSuffix("|listen")
                val result=JSONObject().put("id",id).put("camera",key)
                try {
                    check(source==cc.bridgit.app.dog.DogAudio.BUILTIN || source.startsWith("lan:")) { "not_enrolled" }
                    val clip=cc.bridgit.app.dog.DogAudio.record(app,source,RemoteViewDog.LISTEN_SECONDS,"Your web portal is listening for ${RemoteViewDog.LISTEN_SECONDS} seconds",::allowed,small=true)
                    result.put("audio",android.util.Base64.encodeToString(clip.bytes,android.util.Base64.NO_WRAP)).put("mime",clip.mime)
                        .put("duration_s",RemoteViewDog.LISTEN_SECONDS).put("at",System.currentTimeMillis())
                } catch(e:kotlinx.coroutines.CancellationException){throw e}
                catch(e:Exception){result.put("error",RemoteViewDog.listenError(e.message,source==cc.bridgit.app.dog.DogAudio.BUILTIN && app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED))}
                if (!allowed()) return@launch
                runCatching { call("/v1/watch/applet/capture-result",result) }
            }
            for(key in sounds) {
                if (!legacyCaptureEnabled() || dogSince != consent || dogPaused || !dogPhoto || command.optLong("expires_at") <= System.currentTimeMillis()) return@launch
                val camera=key.removeSuffix("|sound")
                val result=JSONObject().put("id",id).put("camera",key)
                try {
                    check(camera.startsWith("lan:")) { "not_enrolled" }
                    cc.bridgit.app.lancamera.PortalChime.play(app,camera) {
                        if (!legacyCaptureEnabled() || dogSince != consent || dogPaused || command.optLong("expires_at") <= System.currentTimeMillis()) throw kotlinx.coroutines.CancellationException("Sound request expired")
                    }
                    result.put("sound","played")
                } catch(e:kotlinx.coroutines.CancellationException){throw e}
                catch(e:Exception){result.put("error",e.message?.takeIf { it in setOf("needs_setup","speaker_off","busy","not_enrolled") } ?: "sound")}
                runCatching { call("/v1/watch/applet/capture-result",result) }
            }
            if(targets.isEmpty()) return@launch
            app.dog.portalPhotos(targets,authorized={
                if (!legacyCaptureEnabled() || dogSince != consent || dogPaused || !dogPhoto || command.optLong("expires_at") <= System.currentTimeMillis()) throw kotlinx.coroutines.CancellationException("Capture consent expired")
            },progress={ camera, step ->
                // Best effort: a lost progress note must never stop the search.
                runCatching { call("/v1/watch/applet/capture-result",JSONObject().put("id",id).put("camera",camera).put("progress",step)) }
            }) { camera, captured, error, extra ->
                if (!legacyCaptureEnabled() || dogSince != consent || dogPaused || !dogPhoto || command.optLong("expires_at") <= System.currentTimeMillis()) throw kotlinx.coroutines.CancellationException("Capture consent expired")
                val result=JSONObject().put("id",id).put("camera",camera)
                extra?.keys()?.forEach { result.put(it,extra.get(it)) }
                try {
                    if(captured==null) { if(error!=null || !result.has("status")) result.put("error",error ?: "capture") }
                    else {
                        val photo=small(captured.first) ?: kotlin.error("Photo too large")
                        result.put("photo",android.util.Base64.encodeToString(photo,android.util.Base64.NO_WRAP)).put("reading",RemoteViewDog.reading(captured.second))
                    }
                } catch(e:kotlinx.coroutines.CancellationException){throw e}
                catch(_:Exception){result.put("error","capture")}
                runCatching { call("/v1/watch/applet/capture-result",result) }
            }
        }
    }

    private fun webPortalDeleted() {
        prefs.edit().putBoolean("backend_dog", false).putBoolean("backend_dog_chosen", true)
            .putBoolean("backend_dog_delete", false).commit()
        dogServerReady = false
        AppletCalls.cancelDog()
        app.dog.stop()
        _backendStatus.value = "Web portal deleted. Local history and your account connection are kept."
    }

    private suspend fun syncDogBackend() = dogLock.withLock {
        try {
            if (dogPendingDelete) {
                call("/v1/watch/applets/backend", JSONObject().put("applet", "dog").put("enabled", false))
                prefs.edit().putBoolean("backend_dog_delete", false).commit()
                _backendStatus.value = "Device only. Server history and viewer access deleted."
            }
            if (dogBackend && portalEmail != null && !portalEmail.equals(_linked.value, ignoreCase = true)) {
                _backendStatus.value = "Link the portal account " + portalEmail + " before uploading this applet."
                return@withLock
            }
            if (dogBackend && _linked.value != null && !dogServerReady) {
                val result = call("/v1/watch/applets/backend", JSONObject().put("applet", "dog").put("enabled", true).put("since", dogSince))
                val boundary = result.optLong("enabled_at", dogSince)
                if (boundary > dogSince) { prefs.edit().putLong("backend_dog_since", boundary).commit(); app.dog.resetBackendHistory() }
                if (dogBackend) { dogServerReady = true; _backendStatus.value = "Personal backend active for Pet monitor on this device." }
            }
        } catch (e: Exception) { if (dogBackend || dogPendingDelete) _backendStatus.value = if (dogPendingDelete) "Device only. Server deletion pending; will retry when online." else "Backend not ready: " + (e.message ?: "Try again when online.") }
    }

    fun start() {
        app.watches.photoWanted = { w -> packages.photoWanted(w.agentId) }
        app.watches.people.blocker = { agentId -> packages.peopleBlocker(agentId) }
        followLink(prefs.getLong("link_expires_at", 0L))
        // Follow the log: a new entry wakes the sender, which waits a moment so a burst goes as one request.
        scope.launch {
            app.log.latestId.collect {
                if (!enabled || _linked.value == null) return@collect
                delay(PUSH_DELAY_MS) // the flow keeps only the newest id meanwhile
                runCatching { push(force = false) }
            }
        }
        // The Pet monitor panel: follow the monitor and the link; a newer state replaces one still waiting out the throttle.
        scope.launch {
            combine(app.dog.state, _linked) { s, l -> s to l }.collectLatest { (s, l) ->
                if (!dogBackend || l == null) return@collectLatest
                syncDogBackend()
                val now = System.currentTimeMillis()
                if (!RemoteViewDog.due(s.running, dogSentRunning, dogSentAt, now)) delay(dogSentAt + RemoteViewDog.EVERY_MS - now)
                scope.launch { runCatching { pushDog() } } // not cancelled by the next state
            }
        }
        // Bridgit Portal: an iPhone/iPad signed in to the linked Google account may ask to view this phone.
        // Checked every minute while linked (the iPhone waits up to 10 minutes); the owner answers on the phone.
        scope.launch {
            while (true) {
                if (enabled && _linked.value != null && configured) runCatching { cc.bridgit.app.portal.PortalApprovals.check(app) }
                delay(PORTAL_CHECK_MS)
            }
        }
        // Check in every few minutes, so the page can tell "quiet" from "phone offline".
        scope.launch {
            while (true) {
                if (enabled || dogBackend || packages.anyEnabled) { runCatching { push(force = true) }; syncDogBackend(); runCatching { pushDog() } }
                else if (dogPendingDelete) syncDogBackend()
                else if (!packages.active && prefs.getBoolean("pending_unlink", false)) runCatching { unlinkNow() }
                if (packages.active) runCatching { packages.sync() }
                delay(if (dogBackend || packages.active) 30_000L else HEARTBEAT_MS)
            }
        }
    }

    /** The owner's switch. On starts from now (nothing from before is sent); off deletes everything on the backend. */
    fun setEnabled(on: Boolean) {
        if (on) {
            prefs.edit().putBoolean(PREF_ON, true).putLong("last_seq", app.log.latestId.value).putBoolean("pending_unlink", false).apply()
            scope.launch { runCatching { push(force = true) } }
        } else {
            setDogBackend(false)
            prefs.edit().putBoolean(PREF_ON, false).remove("linked_email").putBoolean("pending_unlink", true).apply()
            _linked.value = null
            dogSentRunning = null; photoSentFrameAt = 0L
            scope.launch { runCatching { unlinkNow() } }
        }
    }

    /** The Pet monitor's "Show the latest photo on bridgit.cc/myapps" switch. On by default; only matters while linked. */
    val dogPhoto: Boolean get() = prefs.getBoolean(PREF_DOG_PHOTO, true)

    /** Off deletes the stored photo on the backend at the next push, which goes now. */
    fun setDogPhoto(on: Boolean) {
        prefs.edit().putBoolean(PREF_DOG_PHOTO, on).apply()
        if (on) photoSentFrameAt = 0L
        scope.launch { runCatching { pushDog(force = true) } }
    }

    /** One Pet monitor summary, with the newest photo when its switch is on and one is due. */
    private suspend fun pushDog(force: Boolean = false) = dogLock.withLock {
        if (!dogBackend || !dogServerReady || !configured || _linked.value == null || (portalEmail != null && !portalEmail.equals(_linked.value, ignoreCase = true))) return@withLock
        val s = app.dog.state.value
        val day = app.dog.backendTracker.day(System.currentTimeMillis())
        // A monitor that never ran sends nothing, so the page shows no Pet monitor panel.
        if (!s.running && s.startedAt == 0L && (day?.photos ?: 0) == 0 && dogSentRunning == null) return@withLock
        val now = System.currentTimeMillis()
        if (!force && !RemoteViewDog.due(s.running, dogSentRunning, dogSentAt, now)) return@withLock
        val photoOn = dogPhoto
        val recent = app.dog.backendTracker.recent().filter { it.at >= dogSince }
        if (RemoteViewDog.awaitingReading(s, recent, dogSince, photoOn)) return@withLock
        val l = app.listener.value
        val body = JSONObject().put("dog", RemoteViewDog.summary(s.copy(startedAt = maxOf(s.startedAt, dogSince)), day, recent, photoOn, app.dog.sampling())
                // After a background restart Android keeps the device's own cameras closed until Bridgit is opened once.
                .put("builtin_camera_blocked", s.running && l.running && l.startedInBackground))
            .put("timezone", java.util.TimeZone.getDefault().id)
        val frame = s.frame
        if (photoOn && frame != null && s.frameAt >= dogSince && recent.any { it.at == s.frameAt } && s.frameAt != photoSentFrameAt && now - photoSentAt >= RemoteViewDog.PHOTO_EVERY_MS) {
            small(frame)?.let { body.put("photo", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)).put("photo_at", s.frameAt) }
        }
        recent.lastOrNull { it.at == s.frameAt }?.let { body.put("reading", RemoteViewDog.reading(it)).put("camera", it.camera) }
        if (!dogBackend) return@withLock
        val j = call("/v1/watch/dog", body)
        if (!j.optBoolean("linked")) return@withLock // unlinked on the web: the next events push notices
        dogSentAt = now; dogSentRunning = s.running
        if (body.has("photo")) { photoSentAt = now; photoSentFrameAt = s.frameAt }
    }

    /** The photo at most 640 px and 100 KB, re-encoded smaller when needed; null if it can't be. */
    private fun small(jpeg: ByteArray): ByteArray? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
        if (edge <= 0) return null
        if (jpeg.size <= RemoteViewDog.PHOTO_BYTES && edge <= RemoteViewDog.PHOTO_EDGE) return jpeg
        var bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        if (edge > RemoteViewDog.PHOTO_EDGE) {
            val k = RemoteViewDog.PHOTO_EDGE.toFloat() / edge
            bmp = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        }
        for (q in intArrayOf(70, 55, 40, 25)) {
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= RemoteViewDog.PHOTO_BYTES) return out.toByteArray()
        }
        return null
    }

    /** A one-time code (10 minutes) the owner types at bridgit.cc/myapps after signing in with Google. */
    suspend fun linkCode(): Code = withContext(Dispatchers.IO) {
        val body = JSONObject().put("phone_name", phoneName()).put("device_type", deviceType())
        if (dogBackend || packages.anyEnabled) { portalEmail?.let { body.put("email", it) }; portalAlias?.let { body.put("user_alias", it) } }
        val j = call("/v1/watch/link-code", body)
        Code(j.getString("code"), j.optString("url", "https://bridgit.cc/myapps?code=" + j.getString("code")), j.optLong("expires_at")).also {
            prefs.edit().putLong("link_expires_at", it.expiresAt).commit()
            followLink(it.expiresAt)
        }
    }

    /** This device is linked to the portal account its applet portals were set up for. */
    fun portalLinked(): Boolean = portalAccountLinked()
    /** After an applet portal change: check in and send at once instead of on the next heartbeat. */
    fun syncPackagesSoon() { scope.launch { runCatching { push(force = true) }; runCatching { packages.sync() } } }

    /** Before opening a newly installed portal, let its first report finish rather than racing the web page. */
    suspend fun preparePackagePortal() {
        // The sync belongs to the app: an opener may stop waiting after a few seconds while
        // synchronous network IO finishes independently. Never hold the page on a socket timeout.
        scope.launch { runCatching { push(force = true) }; runCatching { packages.sync() } }.join()
    }


    private fun portalAccountLinked(): Boolean = _linked.value != null &&
        (!(dogBackend || packages.anyEnabled) || portalEmail == null || portalEmail.equals(_linked.value, ignoreCase = true))

    @Synchronized private fun followLink(expiresAt: Long) {
        linkPolling?.cancel()
        if (expiresAt <= System.currentTimeMillis()) return
        linkPolling = scope.launch {
            RemoteViewLink.awaitClaim(expiresAt,
                eligible = { wanted && !portalAccountLinked() },
                refresh = {
                    val linked = runCatching { refresh(); portalAccountLinked() }.getOrDefault(false)
                    if (linked) {
                        prefs.edit().remove("link_expires_at").apply()
                        runCatching { pushDog(force = true) }
                    }
                    linked
                })
        }
    }

    /** Asks the backend whether a Google account has linked this phone yet (sends the snapshot too). */
    /**
     * A one-use code (60 s) that signs this phone's linked account into the bridgit.cc portal without an email code
     * (owner, 4 Nov 2026): the portal web view puts it in the page's fragment and the page trades it for its normal
     * session. Null when the phone isn't linked, is offline, or the backend refuses; the page then asks to sign in.
     */
    suspend fun portalLoginCode(): String? = withContext(Dispatchers.IO) {
        if (_linked.value == null || !configured) return@withContext null
        runCatching { call("/v1/watch/login-code", null).optString("code") }.getOrNull()
            ?.takeIf { cc.bridgit.app.ui.applets.AppletLists.LOGIN_CODE.matches(it) }
    }

    suspend fun refresh(): String? = withContext(Dispatchers.IO) { push(force = true); syncDogBackend(); _linked.value }

    private suspend fun push(force: Boolean) = lock.withLock {
        if (!wanted || !configured) return@withLock
        var statusDue = force || System.currentTimeMillis() - lastStatusAt > STATUS_EVERY_MS
        while (true) {
            val wasLinked = _linked.value != null
            val since = prefs.getLong("last_seq", 0L)
            // Before linking only an "am I linked?" check goes out: no lines, no snapshot.
            val entries = if (enabled && wasLinked) app.log.query(LogQuery(kinds = RemoteViewLines.KINDS.toList(), sinceId = since, ascending = true, limit = BATCH))
                else emptyList()
            val lines = JSONArray()
            entries.forEach { e -> RemoteViewLines.line(e, e.agentId?.let { app.agents.get(it)?.name })?.let(lines::put) }
            if (wasLinked && lines.length() == 0 && !statusDue) {
                // Nothing for remote view (polls, settings): move past them so they aren't read again.
                if (entries.isNotEmpty()) prefs.edit().putLong("last_seq", entries.last().id).apply()
                return@withLock
            }
            val body = JSONObject().put("events", lines)
            if (enabled && wasLinked && statusDue) body.put("status", snapshot())
            if (wasLinked && statusDue) body.put("device_inventory", deviceInventory())
            // The name shown on bridgit.cc and Bridgit Portal follows a rename on the phone.
            if (wasLinked && statusDue) body.put("phone_name", phoneName()).put("device_type", deviceType())
            val consentAtRequest=dogSince
            val j = call("/v1/watch/events", body)
            if (j.optBoolean("dog_portal_deleted") && dogBackend && dogSince==consentAtRequest && j.optLong("dog_portal_deleted_at")>=dogSince) webPortalDeleted()
            if (wasLinked && statusDue) { lastStatusAt = System.currentTimeMillis(); statusDue = false }
            val email = if (j.optBoolean("linked")) j.optString("email").ifEmpty { "your Google account" } else null
            // Older servers don't send the list: keep the last one then.
            if (email == null) prefs.edit().remove("account_applets").apply()
            else j.optJSONArray("account_applets")?.let { a -> prefs.edit().putStringSet("account_applets", (0 until a.length()).map(a::optString).filter(String::isNotEmpty).toSet()).apply() }
            if (email != _linked.value) { dogServerReady = false; packages.relinked(); if (dogBackend) { prefs.edit().putLong("backend_dog_since", System.currentTimeMillis()).commit(); app.dog.resetBackendHistory() }; prefs.edit().putString("linked_email", email).apply(); _linked.value = email }
            // Unlinked: entries are skipped, never queued, so linking later doesn't send the past.
            prefs.edit().putLong("last_seq", if (entries.isNotEmpty()) entries.last().id else if (email == null) app.log.latestId.value else since).apply()
            when {
                email == null -> return@withLock
                !wasLinked -> statusDue = true // just linked: send the snapshot now
                entries.size < BATCH -> return@withLock
            }
        }
    }

    private fun unlinkNow() {
        if (!configured) return
        call("/v1/watch/unlink", null)
        prefs.edit().putBoolean("pending_unlink", false).apply()
    }

    /** Bounded owner overview metadata. No addresses, keys, readings or rule contents. */
    fun snapshot(): JSONObject {
        val caps = JSONObject()
        app.registry.caps().forEach { caps.put(it.id, RemoteViewLines.capState(it.enabled, it.ready)) }
        val listening = JSONArray()
        val st = app.wake.state.value
        val active = st is WakeListener.State.Listening || st is WakeListener.State.Hearing
        if (active && !app.wake.running?.words.isNullOrEmpty()) listening.put("voicerec")
        if (active && app.wake.running?.sound != null) listening.put("sounds")
        val memory = android.app.ActivityManager.MemoryInfo().also { app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
        val mb = 1024L * 1024L
        val device = JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE).put("api_level", Build.VERSION.SDK_INT)
            .put("ram_mb", memory.totalMem / mb).put("storage_mb", android.os.StatFs(app.filesDir.path).totalBytes / mb)
        val relay = cc.bridgit.app.BridgeService.running?.relay
        val connections = JSONObject().put("tailscale", if (cc.bridgit.app.BridgeService.tailscaleAddresses().isNotEmpty()) "connected" else "disconnected")
            .put("adb", if (app.adb.connected) "connected" else "disconnected")
            .put("relay", if (relay?.status == "Connected") "connected" else "disconnected")
        val externalReady = FeedbackUploader.configured && !app.settings.externalCallsPaused
        val services = JSONObject().put("jev", if (externalReady) "ready" else "off")
            .put("clef", if (externalReady) "ready" else "off").put("weather", if (app.settings.externalCallsPaused) "off" else caps.optString("weather", "unknown"))
        val now = System.currentTimeMillis()
        val agents = cc.bridgit.app.ui.home.HomeStates.controlAgents(app) // portal viewers are not agents
        val agentStatus = JSONArray(agents.take(20).map { a -> JSONObject().put("name", a.name).put("last_used_at", a.lastUsedAt).put("paused", a.paused) })
        val applets = applets(agents)
        return JSONObject().put("caps", caps).put("listening", listening)
            .put("agents", JSONArray(agents.map { it.name })).put("app_version", app.appVersion())
            .put("device", device).put("connections", connections).put("services", services).put("agent_status", agentStatus).put("applets", applets)
    }

    /** The applets the owner sees (Pet monitor, City tour, live watches); shared with GET /v1/portal/applets. */
    fun applets(agents: List<cc.bridgit.app.core.Agent> = app.agents.all()): JSONArray {
        val now = System.currentTimeMillis()
        val dog = app.dog.state.value
        val applets = JSONArray().put(JSONObject().put("id", "dog").put("name", "Pet monitor").put("kind", "dog")
            .put("running", dog.running).put("sensors", JSONArray(listOf("camera"))).put("actions", JSONArray(listOf("pet_observation")))
            .put("last_at", dog.frameAt).put("last_action", "camera_capture"))
        app.tours.current()?.let { tour -> applets.put(JSONObject().put("id", "tour-" + tour.id).put("name", "City tour").put("kind", "tour")
            .put("running", !tour.paused).put("sensors", JSONArray(listOf("location", "camera"))).put("actions", JSONArray(listOf("tour_update")))
            .put("last_at", tour.lastTickAt).put("last_action", "tour_update")) }
        agents.flatMap { app.watches.list(it.id) }.filter { it.expiresAt > now }.take(48).forEach { watch ->
            applets.put(JSONObject().put("id", "watch-" + watch.id).put("name", watch.spec.label).put("kind", "watch")
                .put("running", !watch.userPaused && watch.pausedUntil <= now).put("sensors", JSONArray(watch.spec.data))
                .put("actions", JSONArray(listOf("watch_check"))).put("last_at", watch.lastCheckAt).put("last_action", "watch_check"))
        }
        // Installed packages, by name only: the web portal draws their tabs from this before the owner shares any data.
        app.applets.all().filter { it.optString("native") != "dog" && PACKAGE_ID.matches(it.optString("package_id")) }.take(20).forEach { r ->
            val declared = r.optJSONArray("declared")?.let { d -> (0 until d.length()).map { d.optString(it) } } ?: emptyList()
            applets.put(JSONObject().put("id", "pkg." + r.getString("package_id")).put("name", r.optString("name")).put("kind", "package")
                .put("running", r.optString("status") == "active").put("version", r.optString("version"))
                .put("sensors", JSONArray(declared.filter { it in PACKAGE_SENSORS }.sorted())).put("actions", JSONArray(listOf("watch_check"))))
        }
        return applets
    }

    /** Bridgit Portal requests waiting for the owner: [{request, email, device_name, created_at, expires_at}]. Blocking. */
    fun portalPending(): JSONArray = call("/v1/watch/portal/pending", JSONObject()).optJSONArray("requests") ?: JSONArray()

    /** The owner's answer to a Bridgit Portal request; [pairing] only on Allow. Blocking. */
    fun portalAnswer(request: String, allow: Boolean, pairing: JSONObject?) {
        call("/v1/watch/portal/answer", JSONObject().put("request", request).put("allow", allow).apply { pairing?.let { put("pairing", it) } })
    }

    /** Portal device inventory: display metadata only; never LAN addresses or camera credentials. */
    private fun deviceInventory(): JSONArray {
        val state = app.dog.state.value
        return JSONArray(app.lanCameras.all().map { camera ->
            val used = app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG, camera.id)
            val active = state.running && (camera.id in state.cameras || app.dog.feeding.holdingCamera()==camera.id)
            // A bounded reachability check, independent of monitoring. No image/audio capture.
            val online = if (app.settings.isOn("lan_camera")) runCatching {
                cc.bridgit.app.lancamera.LanNetwork.sockets(app, camera.host).createSocket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(camera.host, 554), 1500)
                }
                true
            }.getOrDefault(false) else null
            JSONObject().put("id", camera.id).put("type", "camera").put("name", camera.name)
                .put("model", camera.model ?: JSONObject.NULL).put("verified_at", camera.verifiedAt ?: JSONObject.NULL)
                .put("online", online ?: JSONObject.NULL).put("connection_checked_at", System.currentTimeMillis())
                .put("features", cc.bridgit.app.lancamera.PortalCameraFeatures.json(camera,
                    app.settings.isOn("lan_camera"), app.settings.isOn("lan_camera_audio"), app.settings.isOn("lan_camera_speaker")))
                .put("features_checked_at", camera.capabilityReport?.optLong("checked_at")?.takeIf { it > 0 } ?: JSONObject.NULL)
                .put("state", if (active && state.errorCode in setOf("camera", "camera_busy", "device_access_removed")) "needs_attention" else if (active) "in_use" else "idle")
                .put("applets", JSONArray(if (used && dogBackend) listOf("dog") else emptyList<String>()))
                .put("apps", JSONArray(if (used && dogBackend) listOf("dog") else emptyList<String>()))
        })
    }

    /** "phone" or "tablet", as the team backend has always had it (a TV, watch or computer reads as a tablet). */
    internal fun deviceType(): String =
        if (cc.bridgit.app.platform.DeviceType.of(app) == cc.bridgit.app.platform.DeviceType.PHONE) "phone" else "tablet"
    fun deviceLabel(): String = phoneName() + " · " + deviceType().replaceFirstChar { it.uppercase() }
    private fun phoneName(): String = cc.bridgit.app.platform.DeviceName.of(app)

    /** One signed POST to the team backend. Throws [Failed] with a plain message, or IOException when offline. */
    private fun call(path: String, body: JSONObject?): JSONObject = Outbound.on(Outbound.Actor.PHONE) {
        val consentAtRequest=dogSince
        val outgoing = body?.let { JSONObject(it.toString()) }
        if (dogBackend && path in setOf("/v1/watch/events", "/v1/watch/dog")) {
            outgoing?.put("dog_listen",JSONObject().put("builtin",RemoteViewDog.builtinMic(app.settings.isOn("mic"),
                app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)))
            outgoing?.put("dog_capture_version",4)?.put("dog_capture_supported", true)?.put("dog_control_supported", true)?.put("dog_sampling_supported", true)?.put("dog_control_ack", JSONObject()
                .put("generation", prefs.getString("dog_control_generation", ""))
                .put("revision", prefs.getLong("dog_control_revision", 0)))
        }
        val bytes = (outgoing?.toString() ?: "").toByteArray()
        val url = (BuildConfig.FEEDBACK_URL.trimEnd('/') + path).toHttpUrl()
        val headers = FeedbackUploader.auth(app, "POST", url, bytes, register = true)
            ?: throw Failed("This phone has no Bridgit install key yet.")
        if (headers["X-Bridgit-Install"]?.startsWith("bgi_") != true) throw Failed("This phone hasn't registered its install key with Bridgit yet. Try again in a minute.")
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("User-Agent", UpdateLogic.userAgent(app.appVersion(), Build.VERSION.RELEASE))
            .post(bytes.toRequestBody(JSON)).build()
        AppletCalls.execute(app, if (path == "/v1/watch/dog") "dog" else null, FeedbackUploader.client.newCall(req), allowPaused = true).use { res ->
            val text = res.body?.string().orEmpty()
            if (res.isSuccessful) {
                val result = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                result.optJSONObject("dog_control")?.let { applyDogControl(it, consentAtRequest); applyCapture(it.optJSONObject("capture"), consentAtRequest) }
                return@on result
            }
            FeedbackUploader.onSignedRefusal(app, res.code, text)
            val error = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            // Only the Pet monitor's own routes speak for its portal; an applet portal's refusal is that applet's.
            if (error.optString("error") == "portal_deleted" && dogSince==consentAtRequest && (path == "/v1/watch/dog" || path == "/v1/watch/events" || body?.optString("applet") == "dog")) webPortalDeleted()
            val msg = error.optString("message")
            throw Failed(msg.ifEmpty { "The Bridgit server said HTTP ${res.code}." }, error.optString("error").ifEmpty { null })
        }
    }

    companion object {
        const val PREFS = "remote_view"
        const val PREF_ON = "on"
        const val PREF_DOG_PHOTO = "dog_photo"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val BATCH = 100
        private const val PUSH_DELAY_MS = 3_000L
        private const val HEARTBEAT_MS = 5 * 60_000L
        private const val STATUS_EVERY_MS = 60_000L
        private const val PORTAL_CHECK_MS = 60_000L
        const val PAGE = "https://bridgit.cc/myapps"
        private val PACKAGE_ID = Regex("^[a-z][a-z0-9-]{1,63}$")
        private val PACKAGE_SENSORS = setOf("camera", "sounds", "location")
    }
}
