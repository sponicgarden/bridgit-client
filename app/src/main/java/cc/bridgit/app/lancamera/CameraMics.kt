package cc.bridgit.app.lancamera

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.listen.WakeRules
import cc.bridgit.app.sound.LoudCatcher
import cc.bridgit.app.sound.LoudSounds
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loud sounds from enrolled camera microphones (the camera side of [LoudSounds]). While someone subscribes a
 * camera source (`lan:<id>`, for example the Pet monitor with that camera's microphone ticked), one RTSP audio
 * stream per camera runs here, at most [MAX_STREAMS] at once. Every 100 ms of audio goes through one
 * [LoudCatcher] per subscriber; a loud sound's clip (16 kHz mono WAV) goes to that subscriber's `onSound`.
 *
 * Bounds and consent, the same as a one-off camera recording:
 *  - the owner's `lan_camera` and `lan_camera_audio` switches, capture notifications, Bridgit running, the camera
 *    still enrolled with the same address and account, and each subscriber's own access ([LoudSounds.Subscriber.allows]),
 *    re-checked about once a second while the stream runs (any change drops it within a second);
 *  - the capture notification names who is listening to which camera for as long as the stream is held;
 *  - audio stays in memory: only a loud sound's clip leaves this class, and only to its subscriber;
 *  - a one-off recording of the same camera ([yielding]: the API's record, a Pet monitor sample) gets the camera
 *    first; the stream lets go and comes back after, so a Tapo's three RTSP slots aren't exhausted;
 *  - each connection lasts [SEGMENT_MS] (a fresh RTSP session within the recorder's byte budget, about 30 KB/s
 *    with the video that Tapo firmware needs negotiated); failures back off (5 s doubling to 5 min).
 */
class CameraMics(private val app: BridgeApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streams = ConcurrentHashMap<String, Stream>()
    private var supervisor: Job? = null

    private inner class Stream(val source: String) {
        var job: Job? = null
        @Volatile var state = "starting"
        @Volatile var reason: String? = null
        @Volatile var failures = 0
        @Volatile var connected = false
        @Volatile var lastFrameAt = 0L
        @Volatile var connectedAt = 0L
        @Volatile var lastRms = 0.0
        @Volatile var peakRms10s = 0.0
        @Volatile var gaps = 0
        @Volatile var resets = 0
        @Volatile var segments = 0
        @Volatile var heard = 0
        val yields = AtomicInteger()
        var token: Long? = null
        var tokenService: BridgeService? = null
        val catchers = HashMap<String, LoudCatcher>()
        var version = -1
        val samplers = ArrayList<Sampler>()
        private var peakWindowStart = 0L
        private var peakSoFar = 0.0

        /** One 100 ms frame of 16 kHz PCM, from the camera or from the debug hook. */
        @Synchronized fun frame(f: ByteArray, now: Long) {
            val rms = WakeRules.rms(f, f.size)
            lastRms = rms; lastFrameAt = now
            if (now - peakWindowStart >= 10_000) { peakRms10s = maxOf(peakSoFar, rms); peakSoFar = rms; peakWindowStart = now } else peakSoFar = maxOf(peakSoFar, rms)
            samplers.removeAll { it.add(f) }
            val loud = app.wake.loud
            if (loud.version != version) {
                version = loud.version
                val want = loud.all().filter { source in it.sources }.associate { "${it.id}/${it.sensitivity}" to it }
                catchers.keys.retainAll(want.keys)
                want.forEach { (k, s) -> catchers.getOrPut(k) { LoudCatcher(s.id, CameraMicAudio.sensitivity(s.sensitivity)) } }
            }
            for (c in catchers.values) c.feed(f, rms, now)?.let { clip -> heard++; deliver(c.id, clip) }
        }

        @Synchronized fun interrupted() { catchers.values.forEach { it.interrupted() } }

        fun deliver(id: String, clip: LoudCatcher.Clip) = scope.launch {
            val s = app.wake.loud.get(id) ?: return@launch
            if (source !in s.sources || !s.allows(source)) return@launch
            runCatching { s.onSound(source, clip.wav, clip.durationMs, clip.at) }
                .onFailure { app.log.system("diagnostic", "sounds", "Loud-sound subscriber $id failed: ${it.javaClass.simpleName}", level = "warn") }
        }

        fun holdCapture(note: String) {
            if (token != null) return
            tokenService = BridgeService.running
            token = tokenService?.captureStarted(note)
        }
        fun releaseCapture() { token?.let { tokenService?.captureEnded(it) }; token = null; tokenService = null }
    }

    /** Collects the next [bytes] of 16 kHz PCM for a timed sample. */
    private class Sampler(val bytes: Int) {
        val out = java.io.ByteArrayOutputStream()
        val done = CompletableDeferred<ByteArray>()
        /** True when full (and then removed). */
        fun add(f: ByteArray): Boolean {
            out.write(f, 0, minOf(f.size, bytes - out.size()))
            if (out.size() < bytes) return false
            done.complete(out.toByteArray()); return true
        }
    }

    /**
     * Called when loud-sound subscriptions change (and again by subscribers now and then; cheap and idempotent):
     * starts the supervisor when a camera microphone is wanted.
     */
    @Volatile private var syncs = 0
    @Volatile private var tickAt = 0L
    @Synchronized fun sync() {
        syncs++
        if (supervisor?.isActive == true || app.wake.loud.cameraSources().isEmpty()) return
        supervisor = scope.launch {
            try { supervise() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { app.log.system("diagnostic", "sounds", "Camera microphone streams stopped: ${e.javaClass.simpleName}", level = "warn") }
        }
    }

    private suspend fun supervise() {
        while (currentCoroutineContext().isActive) {
            tickAt = System.currentTimeMillis()
            val wanted = app.wake.loud.cameraSources().take(MAX_STREAMS).toSet()
            for ((src, st) in streams) if (src !in wanted) { st.job?.cancel(); streams.remove(src) }
            for (src in wanted) {
                val st = streams.getOrPut(src) { Stream(src) }
                if (st.job?.isActive != true) st.job = scope.launch { run(st) }
            }
            if (wanted.isEmpty()) synchronized(this) {
                if (app.wake.loud.cameraSources().isEmpty()) { supervisor = null; return }
            }
            delay(2_000)
        }
    }

    /** Why no camera microphone may stream now (for anyone), or null. */
    private fun blocked(src: String): String? = when {
        BridgeService.running == null -> "service_off"
        !app.captureNotificationsOn() -> "notifications_off"
        !app.settings.isOn("lan_camera") -> "lan_camera_off"
        !app.settings.isOn("lan_camera_audio") -> "lan_camera_audio_off"
        app.lanCameras.all().none { it.id == src } -> "camera_removed"
        else -> null
    }

    private fun subscribers(src: String) = app.wake.loud.all().filter { src in it.sources && runCatching { it.allows(src) }.getOrDefault(false) }

    private suspend fun run(st: Stream) {
        val src = st.source
        try {
            while (currentCoroutineContext().isActive) {
                if (st.yields.get() > 0) { st.state = "yielded"; st.releaseCapture(); delay(250); continue }
                val subs = subscribers(src)
                val why = if (subs.isEmpty()) "no_subscriber" else blocked(src)
                if (why != null) { st.state = "blocked"; st.reason = why; st.releaseCapture(); delay(1_000); continue }
                val cam = app.lanCameras.all().first { it.id == src }
                st.state = "connecting"; st.reason = null
                var lastCheck = 0L
                fun authorized() {
                    val now = System.currentTimeMillis()
                    if (now - lastCheck < 1_000) return
                    lastCheck = now
                    val current = app.lanCameras.all().firstOrNull { it.id == src }
                    check(blocked(src) == null && subscribers(src).isNotEmpty() && current != null && current.host == cam.host &&
                        current.username == cam.username && current.password == cam.password) { "Camera microphone access changed" }
                }
                try {
                    withContext(Dispatchers.IO) {
                        CameraRtsp(cam.host, cam.username, cam.password, LanNetwork.sockets(app, cam.host), SEGMENT_MS + 20_000,
                            cancellationJob = currentCoroutineContext()[Job], authorized = ::authorized, maxBytes = 64 * 1024 * 1024).use { rtsp ->
                            val track = rtsp.describe().firstOrNull { it.supported && it.direction in listOf("recvonly", "sendrecv") }
                                ?: throw CameraFailure("No supported microphone track")
                            val join = CameraMicAudio.Join(track.rate)
                            val framer = CameraMicAudio.Framer()
                            rtsp.stream(track, SEGMENT_MS) { ssrc, ts, pcm ->
                                if (!st.connected) {
                                    st.connected = true; st.connectedAt = System.currentTimeMillis(); st.segments++
                                    if (st.failures > 0 || st.segments == 1) app.log.system("audit", "sounds",
                                        "${names(subs)} listening to ${cam.name} microphone for loud sounds")
                                    st.failures = 0; st.state = "listening"
                                    st.holdCapture(app.getString(cc.bridgit.app.R.string.lan_mic_listening, names(subs), cam.name))
                                }
                                for (f in framer.add(CameraMicAudio.to16k(join.add(ssrc, ts, pcm), track.rate))) st.frame(f, System.currentTimeMillis())
                                st.gaps = join.gaps; st.resets = join.resets
                                st.yields.get() == 0
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    st.failures++
                    st.reason = (e as? CameraFailure)?.message ?: e.message?.takeIf { it == "Camera microphone access changed" } ?: e.javaClass.simpleName
                    st.state = "waiting"
                    st.releaseCapture()
                    if (st.failures == 1 || st.failures == CameraMicAudio.FAILURES_BEFORE_FALLBACK)
                        app.log.system("diagnostic", "sounds", "${cam.name} microphone stream stopped (${st.reason}); retrying in ${CameraMicAudio.backoffMs(st.failures) / 1000} s", level = "warn")
                } finally { st.connected = false; st.interrupted() }
                if (st.failures > 0) delay(CameraMicAudio.backoffMs(st.failures))
            }
        } finally {
            st.releaseCapture(); st.connected = false; st.state = "stopped"
            synchronized(st) { st.samplers.forEach { it.done.cancel() }; st.samplers.clear() }
        }
    }

    private fun names(subs: List<LoudSounds.Subscriber>) = subs.map { app.wake.loudName(it.id) }.distinct().joinToString(", ")

    /** True while [source]'s stream is delivering audio (a frame in the last 3 s). */
    fun holding(source: String): Boolean = streams[source]?.let { it.connected && System.currentTimeMillis() - it.lastFrameAt < 3_000 } ?: false

    /**
     * True when timed samples should cover [source]: its stream has failed often enough, can't run at all (blocked:
     * a switch, notifications, Bridgit not running), or doesn't exist. Never implies coverage that isn't there.
     */
    fun failing(source: String): Boolean = streams[source]?.let { it.state == "blocked" || it.failures >= CameraMicAudio.FAILURES_BEFORE_FALLBACK } ?: true

    /**
     * The next [seconds] of [source]'s held stream as 16 kHz mono PCM, without opening a second connection; null when
     * the stream isn't held or stops first. The caller has already checked its own access.
     */
    suspend fun sample(source: String, seconds: Int): ByteArray? {
        val st = streams[source]?.takeIf { holding(source) } ?: return null
        val s = Sampler(seconds * CameraMicAudio.RATE * 2)
        synchronized(st) { st.samplers.add(s) }
        return try { withTimeoutOrNull(seconds * 1000L + 3_000) { s.done.await() } }
        catch (e: CancellationException) { if (currentCoroutineContext().isActive) null else throw e }
        finally { synchronized(st) { st.samplers.remove(s) } }
    }

    /** Lets a one-off recording of [cameraId] have the camera: the stream disconnects first and reconnects after. */
    suspend fun <T> yielding(cameraId: String, block: suspend () -> T): T {
        val st = streams[cameraId] ?: return block()
        st.yields.incrementAndGet()
        try {
            withTimeoutOrNull(3_000) { while (st.connected) delay(50) }
            return block()
        } finally { st.yields.decrementAndGet() }
    }

    /** Debug builds: 16 kHz mono PCM fed through [source]'s detectors as if the camera had just sent it. */
    fun inject(source: String, pcm16k: ByteArray): Int {
        val st = streams.getOrPut(source) { Stream(source) }
        val framer = CameraMicAudio.Framer()
        val frames = framer.add(pcm16k)
        var t = System.currentTimeMillis() - frames.size * CameraMicAudio.FRAME_MS.toLong()
        frames.forEach { t += CameraMicAudio.FRAME_MS; st.frame(it, t) }
        return frames.size
    }

    /** Diagnostics for one camera source (debug hook, Pet monitor details). */
    fun status(source: String): JSONObject {
        val st = streams[source] ?: return JSONObject().put("state", "off").put("supervisor", supervisor?.isActive == true)
            .put("syncs", syncs).put("tick_age_s", if (tickAt == 0L) -1 else (System.currentTimeMillis() - tickAt) / 1000)
        return JSONObject().put("state", st.state).put("reason", st.reason ?: JSONObject.NULL).put("holding", holding(source))
            .put("failures", st.failures).put("segments", st.segments).put("gaps", st.gaps).put("resets", st.resets)
            .put("rms", Math.round(st.lastRms)).put("peak_rms_10s", Math.round(st.peakRms10s)).put("loud_sounds", st.heard)
            .put("connected_for_s", if (st.connected) (System.currentTimeMillis() - st.connectedAt) / 1000 else 0)
    }

    companion object {
        /** One RTSP session's length before a fresh one (budget: ~30 KB/s of audio + video, 64 MiB). */
        const val SEGMENT_MS = 4 * 60_000L
        /** Streams at once; further camera microphones keep their timed samples. */
        const val MAX_STREAMS = 4
    }
}
