package cc.bridgit.app.listen

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.VibrationEffect
import cc.bridgit.app.core.Compat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.capability.granted
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.capture.CaptureRules
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * Always-on wake words (8.23, CAP-46/47/48). While the switch is on and the listener has the microphone,
 * a grammar-limited Vosk recogniser spots the owner's wake words in RAM. After a hit, what follows is
 * recorded until a pause (max 60 s), transcribed on the phone, and sent to the mapped agent as `listen.heard`.
 * Audio from before the wake word is never kept: the spotter's 2 s buffer is overwritten as it goes.
 */
class WakeListener(private val app: BridgeApp) {
    sealed class State {
        /** Not listening; [reason] is the first thing missing (switch, model, words, microphone). */
        data class Off(val reason: String) : State()
        object Listening : State()
        data class Hearing(val phrase: String) : State()
        /** Microphone released for a while: "muted", "call" or "mic_in_use". */
        data class Paused(val reason: String) : State()
    }

    data class Clip(val agentId: String, val wav: ByteArray, val at: Long, val seconds: Double, val capability: String)
    data class LastHit(val phrase: String, val agentName: String, val transcript: String, val at: Long)

    val model = WakeModel(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<State>(State.Off("switch_off"))
    val state: StateFlow<State> get() = _state
    private var job: Job? = null
    @Volatile var mutedUntil = 0L; private set
    @Volatile var lastHit: LastHit? = null; private set

    fun words(): List<WakeWord> = WakeRules.fromJson(app.settings.wakeWords)
    /** Wake words whose agent still exists. */
    fun activeWords(): List<WakeWord> = words().filter { app.agents.get(it.agentId) != null }
    fun saveWords(words: List<WakeWord>) { app.settings.wakeWords = WakeRules.toJson(words) }

    fun start() {
        app.settings.addListener { sync() }
        scope.launch { app.listener.collect { sync() } }
        scope.launch { model.state.collect { sync() } }
    }

    /** "Listen for any sounds", if the owner set it up for an agent that still exists. */
    fun soundConfig(): SoundConfig? = SoundConfig.fromJson(app.settings.soundsConfig)?.takeIf { app.agents.get(it.agentId) != null }
    fun saveSoundConfig(c: SoundConfig?) { app.settings.soundsConfig = c?.toJson()?.toString() ?: "" }

    /** Why the listener can't have the microphone now, or null. */
    fun micMissing(): String? = when {
        !app.granted(Manifest.permission.RECORD_AUDIO) -> "permission:RECORD_AUDIO"
        !app.captureNotificationsOn() -> "notifications_off"
        !app.listener.value.running -> "listener_off"
        !app.listener.value.micReady -> "open_app_once"
        else -> null
    }

    /** Null when wake words can run; otherwise why not (also the module's blocked reason, minus the switch). */
    fun missing(): String? = micMissing() ?: when {
        !model.ready -> "model_missing"
        activeWords().isEmpty() -> "no_wake_words"
        else -> null
    }

    /** The same for "listen for any sounds": no speech model needed, only an agent. */
    fun soundsMissing(): String? = micMissing() ?: if (soundConfig() == null) "no_agent" else null

    /**
     * What the microphone loop is doing: wake words (empty when off), an agent's sounds (null when off), and
     * [loud]: there are [LoudSounds] subscribers; each gets its own detector on the same stream, and the running loop
     * picks up new and removed ones itself, so subscribing never restarts the microphone.
     */
    data class Run(val words: List<WakeWord>, val sound: SoundConfig?, val loud: Boolean = false)

    /** Loud sounds for sound watches and applets (cc.bridgit.app.sound). Subscribing turns the listener on. */
    val loud = cc.bridgit.app.sound.LoudSounds {
        // Camera microphones first: the phone's listener may not be able to start yet (early in a restart).
        runCatching { app.cameraMics.sync() }.onFailure { app.log.system("diagnostic", "sounds", "Camera microphone streams didn't start: ${it.javaClass.simpleName}", level = "warn") }
        sync()
    }

    @Synchronized fun sync() {
        val wakeOn = app.settings.isOn(SWITCH) && missing() == null
        val soundOn = app.settings.isOn(SOUND_SWITCH) && soundsMissing() == null
        val loudOn = micMissing() == null && loud.active().isNotEmpty()
        if (!wakeOn && !soundOn && !loudOn) {
            val reason = when {
                app.settings.isOn(SWITCH) -> missing() ?: "error"
                app.settings.isOn(SOUND_SWITCH) -> soundsMissing() ?: "error"
                else -> "switch_off"
            }
            job?.cancel(); job = null; running = null
            if (_state.value != State.Off(reason)) setState(State.Off(reason))
            return
        }
        val run = Run(if (wakeOn) activeWords() else emptyList(), if (soundOn) soundConfig() else null, loudOn)
        if (job?.isActive == true && running == run) return
        job?.cancel()
        running = run
        job = scope.launch { runCatching { loop(run) }.onFailure { e ->
            if (e is CancellationException) throw e
            app.log.system("diagnostic", "wakewords", "Listening stopped: ${e.javaClass.simpleName}: ${e.message}", level = "error")
            setState(State.Off("error"))
        } }
    }
    @Volatile var running: Run? = null; private set

    fun mute(ms: Long = WakeRules.MUTE_MS) {
        mutedUntil = System.currentTimeMillis() + ms
        app.log.system("audit", "wakewords", "You muted wake words for ${ms / 60_000} minutes")
    }
    fun unmute() { mutedUntil = 0 }

    /**
     * Called by Bridgit's own recorders after they take the mic lock: the listener lets go of the
     * microphone within a frame (or after the follow-up it's hearing is cut short).
     */
    suspend fun awaitMicReleased(timeoutMs: Long = 3_000) {
        withTimeoutOrNull(timeoutMs) {
            while (_state.value is State.Listening || _state.value is State.Hearing) delay(50)
        }
    }

    private fun pausedReason(): String? = when {
        System.currentTimeMillis() < mutedUntil -> "muted"
        app.speakerReady() && app.speaker.inCall() -> "call"
        app.registry.micLock.isLocked -> "mic_in_use"
        else -> null
    }

    private fun setState(s: State) {
        val old = _state.value
        _state.value = s
        if (old::class != s::class || s is State.Hearing) {
            if (s is State.Listening && old !is State.Hearing && old !is State.Paused) app.log.system("audit", "wakewords", "Listening for wake words started")
            if (s is State.Off && (old is State.Listening || old is State.Paused)) app.log.system("audit", "wakewords", "Listening for wake words stopped (${s.reason})")
        }
        BridgeService.running?.refreshNotification()
    }

    private suspend fun loop(run: Run) {
        val m = if (run.words.isEmpty()) null else withContext(Dispatchers.IO) { model.load() }
        while (currentCoroutineContext().isActive) {
            val why = pausedReason()
            if (why != null) { if (_state.value != State.Paused(why)) setState(State.Paused(why)); delay(1_000); continue }
            if (!listenUntilPaused(m, run)) delay(5_000)
        }
    }

    /** Spots wake words and sounds until something wants the microphone back. False when the microphone couldn't be opened. */
    @SuppressLint("MissingPermission")
    private suspend fun listenUntilPaused(m: Model?, run: Run): Boolean {
        val words = run.words
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 4)) }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            app.log.system("diagnostic", "wakewords", "Couldn't open the microphone for wake words", level = "warn")
            return false
        }
        val spotter = m?.let { Recognizer(it, RATE.toFloat(), WakeRules.grammar(words)).apply { setWords(true); setPartialWords(true) } }
        val sounds = run.sound?.let { WakeRules.SoundDetector(it.sensitivity) }
        var soundPcm: ByteArrayOutputStream? = null
        var soundAt = 0L
        var catches = emptyList<Catch>()
        var loudVersion = -1
        val ring = Ring(RATE * 2 * 2)
        var fed = 0L
        val gate = WakeRules.SoundGate()
        var quiet = 0
        try {
            rec.startRecording()
            setState(State.Listening)
            val buf = ByteArray(FRAME)
            var frames = 0
            while (currentCoroutineContext().isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) return false
                if (n == 0) continue
                ring.write(buf, n)
                val level = WakeRules.rms(buf, n)
                if (sounds != null) when (sounds.feed(level)) {
                    // The clip starts 500 ms back so the start of the sound is in it.
                    WakeRules.SoundDetector.Ev.START -> { soundAt = System.currentTimeMillis() - 200
                        soundPcm = ByteArrayOutputStream().apply { write(ring.last(5 * FRAME)) } }
                    WakeRules.SoundDetector.Ev.END -> { val p = soundPcm?.toByteArray() ?: ByteArray(0); soundPcm = null
                        val (dur, peak, mean) = Triple(sounds.durationMs / 1000.0, sounds.peakRms, sounds.meanRms)
                        scope.launch { deliverSound(run.sound, p, dur, peak, mean, soundAt) } }
                    null -> soundPcm?.write(buf, 0, n)
                }
                if (run.loud && loud.version != loudVersion) {
                    loudVersion = loud.version
                    val old = catches
                    catches = loud.active().map { (id, sens) -> old.firstOrNull { it.id == id && it.sensitivity == sens } ?: Catch(id, sens, WakeRules.SoundDetector(sens)) }
                }
                for (c in catches) when (c.detector.feed(level)) {
                    WakeRules.SoundDetector.Ev.START -> { c.at = System.currentTimeMillis() - 200
                        c.pcm = ByteArrayOutputStream().apply { write(ring.last(5 * FRAME)) } }
                    WakeRules.SoundDetector.Ev.END -> { val p = c.pcm?.toByteArray() ?: ByteArray(0); c.pcm = null
                        val dur = c.detector.durationMs; val at = c.at; val id = c.id
                        if (p.isNotEmpty()) scope.launch { runCatching { loud.get(id)?.onSound?.invoke(cc.bridgit.app.sound.LoudSounds.BUILTIN, WakeRules.wav(p), dur, at) }
                            .onFailure { app.log.system("diagnostic", "sounds", "Loud-sound subscriber $id failed: ${it.javaClass.simpleName}", level = "warn") } } }
                    null -> c.pcm?.write(buf, 0, n)
                }
                // Silence never reaches Vosk; when sound starts, it gets up to 300 ms of what came just before.
                if (spotter == null || !gate.feed(level)) {
                    quiet++
                    if (app.registry.micLock.isLocked || (++frames % 10 == 0 && pausedReason() != null)) return true
                    continue
                }
                val chunk = if (quiet > 0) ring.last(minOf(quiet, PREROLL_FRAMES) * FRAME + n) else buf.copyOf(n)
                quiet = 0
                val full = spotter.acceptWaveForm(chunk, chunk.size)
                fed += chunk.size
                val hit = WakeRules.match(if (full) spotter.result else spotter.partialResult, words)
                if (hit != null) {
                    // Keep only what came after the wake word ended.
                    val endByte = hit.endS?.let { (it * RATE * 2).toLong() and 1L.inv() } ?: fed
                    val tail = ring.last((fed - endByte).coerceIn(0, ring.size.toLong()).toInt())
                    ring.clear(); spotter.reset(); fed = 0
                    soundPcm = null; catches.forEach { it.pcm = null }
                    follow(rec, m!!, hit.wake, tail)
                    setState(State.Listening)
                }
                // The mic lock is checked every frame so a recording agent isn't kept waiting; the rest once a second.
                if (app.registry.micLock.isLocked || (++frames % 10 == 0 && pausedReason() != null)) return true
            }
            return true
        } finally {
            runCatching { rec.stop() }; rec.release(); spotter?.close(); ring.clear()
        }
    }

    /** Records what follows the wake word until a pause, then transcribes and delivers it off this thread. */
    private suspend fun follow(rec: AudioRecord, m: Model, wake: WakeWord, tail: ByteArray) {
        val heardAt = System.currentTimeMillis()
        setState(State.Hearing(WakeRules.normalize(wake.phrase)))
        tick()
        val pcm = ByteArrayOutputStream()
        val det = WakeRules.PauseDetector(FRAME_MS)
        val free = Recognizer(m, RATE.toFloat())
        var end: WakeRules.PauseDetector.End? = null
        try {
            var i = 0
            while (i < tail.size && end == null) {
                val n = minOf(FRAME, tail.size - i)
                val chunk = tail.copyOfRange(i, i + n)
                pcm.write(chunk); free.acceptWaveForm(chunk, n); end = det.feed(WakeRules.rms(chunk, n)); i += n
            }
            val buf = ByteArray(FRAME)
            while (end == null && currentCoroutineContext().isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                pcm.write(buf, 0, n); free.acceptWaveForm(buf, n)
                end = det.feed(WakeRules.rms(buf, n))
                if (app.registry.micLock.isLocked || pausedReason() == "call") break
            }
            val voskText = runCatching { JSONObject(free.finalResult).optString("text") }.getOrDefault("")
            val bytes = pcm.toByteArray()
            val endedBy = end?.let { WakeRules.endedBy(it) } ?: "interrupted"
            scope.launch { deliver(wake, bytes, voskText, det.heardSpeech, endedBy, heardAt) }
        } finally { free.close() }
    }

    private suspend fun deliver(wake: WakeWord, pcm: ByteArray, voskText: String, spoke: Boolean, endedBy: String, heardAt: Long) {
        // Re-checked now: the owner may have taken wake words away while this was recording.
        val agent = app.agents.get(wake.agentId)?.takeIf { it.allows("wakewords") } ?: return
        var recogniser = "vosk"
        var transcript = voskText
        if (spoke && cc.bridgit.app.planned.Transcriber.available(app)) {
            val f = File(app.cacheDir, "wake-${System.nanoTime()}.pcm")
            try {
                withContext(Dispatchers.IO) { f.writeBytes(pcm) }
                runCatching { cc.bridgit.app.planned.Transcriber.recognizePcm(app, f, RATE, 1, null) }
                    .onFailure { app.log.system("diagnostic", "wakewords", "On-device recogniser gave nothing, using Vosk's text: ${it.message}", level = "warn") }
                    .getOrNull()?.takeIf { it.isNotBlank() }?.let { transcript = it; recogniser = "android_on_device" }
            } finally { f.delete() }
        }
        val seconds = pcm.size / (RATE * 2.0)
        val stored = if (wake.sendClip && pcm.isNotEmpty()) keepClip(agent.id, CaptureRules.WAKE_CLIP, pcm, heardAt, seconds) else null
        val clipId = stored?.optString("capture_id")
        val phrase = WakeRules.normalize(wake.phrase)
        app.interactions.event(agent.id, "listen.heard", WakeRules.heardEvent(wake, transcript, isoAt(heardAt), endedBy, seconds, recogniser, clipId)
            .apply { if (stored != null) put("capture", stored) })
        app.log.system("audit", "wakewords", "Heard \"$phrase\" for ${agent.name}: \"${WakeRules.forLog(transcript)}\"",
            data = cc.bridgit.app.log.LogFacts.wakeHeard(agent.id, endedBy, clipId != null, WakeRules.forLog(transcript), recogniser))
        lastHit = LastHit(phrase, agent.name, WakeRules.forLog(transcript), heardAt)
        BridgeService.running?.refreshNotification()
    }

    private val soundTimes = ArrayDeque<Long>()

    /** "Listen for any sounds": a `sound.heard` event, at most 60 an hour on top of the detector's 30 s gap. */
    private fun deliverSound(cfg: SoundConfig, pcm: ByteArray, seconds: Double, peak: Double, mean: Double, heardAt: Long) {
        val agent = app.agents.get(cfg.agentId)?.takeIf { it.allows("sounds") } ?: return
        synchronized(soundTimes) {
            while (soundTimes.isNotEmpty() && soundTimes.first() < heardAt - 3_600_000L) soundTimes.removeFirst()
            if (soundTimes.size >= 60) return
            soundTimes.addLast(heardAt)
        }
        val stored = if (cfg.sendClip && pcm.isNotEmpty()) keepClip(agent.id, CaptureRules.SOUND_CLIP, pcm, heardAt, pcm.size / (RATE * 2.0)) else null
        val clipId = stored?.optString("capture_id")
        val e = WakeRules.soundEvent(isoAt(heardAt), seconds, peak, mean, cfg.sensitivity, clipId)
            .apply { if (stored != null) put("capture", stored) }
        app.interactions.event(agent.id, "sound.heard", e)
        app.log.system("audit", "sounds", "Heard a sound for ${agent.name}: ${e.getDouble("duration_seconds")} s, peak ${e.getDouble("peak_dbfs")} dBFS",
            data = JSONObject().put("agent_id", agent.id).put("clip", clipId != null))
        lastSound = LastSound(agent.name, e.getDouble("peak_dbfs"), seconds, heardAt)
    }
    data class LastSound(val agentName: String, val peakDbfs: Double, val seconds: Double, val at: Long)
    @Volatile var lastSound: LastSound? = null; private set

    /**
     * Keeps a clip where the agent and owner chose (always in memory too, for clip_url; SEC-13, D33).
     * Returns the capture summary, or null if it couldn't be kept.
     */
    private fun keepClip(agentId: String, kind: String, pcm: ByteArray, at: Long, seconds: Double): JSONObject? = runCatching {
        app.captures.keep(agentId, kind, WakeRules.wav(pcm), "audio/wav", at, app.captures.plan(agentId, kind, null),
            JSONObject().put("duration_seconds", Math.round(seconds * 10) / 10.0))
    }.onFailure { app.log.system("diagnostic", kind, "Couldn't keep the clip: ${it.message}", level = "warn") }.getOrNull()

    /** A clip goes only to its agent, through the capability that recorded it, while that agent still has it (unless the owner shares captures). */
    fun clip(id: String, agentId: String, capability: String): Clip? = app.captures.get(id, agentId, capability)?.let { f ->
        Clip(f.entry.agentId, f.data, f.entry.at, f.entry.meta.optDouble("duration_seconds", f.data.size / (RATE * 2.0)), f.entry.kind)
    }

    /** JSON for GET /v1/listen: the state, and only this agent's wake words. */
    fun status(agentId: String): JSONObject {
        val s = _state.value.let { st ->
            // Sounds alone keep the mic open; for wake words that still counts as off.
            if (st !is State.Off && running?.words.isNullOrEmpty()) State.Off(if (app.settings.isOn(SWITCH)) missing() ?: "error" else "switch_off") else st }
        return JSONObject().put("enabled", app.settings.isOn(SWITCH))
            .put("state", when (s) { is State.Off -> "off"; State.Listening -> "listening"; is State.Hearing -> "hearing"; is State.Paused -> "paused" })
            .put("reason", when (s) { is State.Off -> s.reason; is State.Paused -> s.reason; else -> JSONObject.NULL })
            .put("muted_until", if (mutedUntil > System.currentTimeMillis()) isoAt(mutedUntil) else JSONObject.NULL)
            .put("model", when (val ms = model.state.value) { WakeModel.State.Ready -> "ready"; WakeModel.State.Absent -> "not_downloaded"
                is WakeModel.State.Downloading -> "downloading"; is WakeModel.State.Failed -> "failed" })
            .put("model_name", WakeModel.NAME)
            .put("max_seconds", WakeRules.MAX_SECONDS)
            .put("wake_words", JSONArray(activeWords().filter { it.agentId == agentId }.map {
                JSONObject().put("phrase", WakeRules.normalize(it.phrase)).put("send_clip", it.sendClip) }))
    }

    /** JSON for GET /v1/sounds: whether the phone listens for sounds for this agent. */
    fun soundStatus(agentId: String): JSONObject {
        val cfg = soundConfig()
        val mine = cfg?.agentId == agentId
        val st = _state.value
        val on = running?.sound != null
        return JSONObject().put("enabled", app.settings.isOn(SOUND_SWITCH)).put("for_you", mine)
            .put("state", when { !on -> "off"; st is State.Paused -> "paused"; else -> "listening" })
            .put("reason", when { !on -> (if (app.settings.isOn(SOUND_SWITCH)) soundsMissing() ?: "error" else "switch_off"); st is State.Paused -> st.reason; else -> JSONObject.NULL })
            .put("sensitivity", if (mine) cfg!!.sensitivity else JSONObject.NULL).put("send_clip", mine && cfg!!.sendClip)
            .put("cooldown_seconds", WakeRules.SOUND_COOLDOWN_MS / 1000).put("max_seconds", WakeRules.SOUND_MAX_MS / 1000)
    }

    /** Names of the agents that get wake words or sounds, for the notification. */
    fun agentNames(): String = (((running?.words.orEmpty().map { it.agentId }) + listOfNotNull(running?.sound?.agentId))
        .mapNotNull { app.agents.get(it)?.name } + (if (running?.loud == true) loud.active().map { loudName(it.first) } else emptyList())).distinct().joinToString(", ")

    /** "pet_monitor" → Pet monitor; "watch:<id>" → that watch's agent. */
    fun loudName(id: String): String = when {
        id == "pet_monitor" -> app.getString(cc.bridgit.app.R.string.dog_title)
        id.startsWith("watch:") -> app.watches.agentNameOf(id.removePrefix("watch:")) ?: "a sound watch"
        else -> id
    }

    private class Catch(val id: String, val sensitivity: String, val detector: WakeRules.SoundDetector) { var pcm: ByteArrayOutputStream? = null; var at = 0L }

    private fun tick() = runCatching {
        Compat.vibrator(app)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }


    /** The spotter's last few seconds, RAM only. */
    private class Ring(cap: Int) {
        private val data = ByteArray(cap)
        private var pos = 0
        var size = 0; private set
        fun write(b: ByteArray, n: Int) { for (i in 0 until n) { data[pos] = b[i]; pos = (pos + 1) % data.size }; size = minOf(data.size, size + n) }
        fun last(n: Int): ByteArray { val k = minOf(n, size); return ByteArray(k) { data[(pos - k + it + data.size) % data.size] } }
        fun clear() { data.fill(0); pos = 0; size = 0 }
    }

    companion object {
        const val SWITCH = "wakewords"
        const val SOUND_SWITCH = "sounds"
        const val RATE = WakeRules.SAMPLE_RATE
        const val FRAME_MS = 100L
        const val FRAME = (RATE / 10) * 2
        const val PREROLL_FRAMES = 3
    }
}
