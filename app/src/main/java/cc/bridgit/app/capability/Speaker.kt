package cc.bridgit.app.capability

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import io.ktor.server.routing.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.RateLimiter
import cc.bridgit.app.api.Reply
import cc.bridgit.app.core.SafeHttp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** Pure rules for speaker requests (SPK-01, SPK-02), unit-tested. */
object SpeakerRules {
    fun allowed(agentId: String, listenerOn: Boolean, switchOn: Boolean, hasGrant: Boolean) =
        agentId == "owner" || (listenerOn && switchOn && hasGrant)

    const val MAX_QUEUE_PER_AGENT = 5
    const val PLAYS_PER_HOUR = 60
    const val MAX_BYTES = 25L * 1024 * 1024
    const val MAX_CLIP_MS = 10 * 60_000L
    const val MAX_SAY_CHARS = 4000
    const val DEFAULT_CEILING = 70
    /** SPK-02: one deadline for DNS, connect, redirects and the transfer. */
    const val FETCH_DEADLINE_MS = 60_000L
    /** Cache files nobody owns are removed after this long; the cache is also capped in size. */
    const val CACHE_MAX_AGE_MS = 15 * 60_000L
    const val CACHE_MAX_BYTES = 200L * 1024 * 1024

    /** "after_current" (default): replace what's queued for this agent/tour, let the current one finish. "now": stop it too. "queue": add. */
    val REPLACE = setOf("after_current", "now", "queue")

    /** SPK-02: public HTTPS only; no credentials, no odd ports. Address checks happen per connection (SafeHttp). */
    fun urlAllowed(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        return u.scheme.equals("https", true) && !u.host.isNullOrEmpty() && u.userInfo == null && (u.port == -1 || u.port == 443)
    }

    /** A resolved address that is public (not loopback, private, link-local, CGNAT, ULA, multicast). */
    fun publicAddress(a: InetAddress): Boolean = SafeHttp.isPublic(a)

    /** A say/play reply: "silenced" when the phone is on silent, vibrate, Do Not Disturb or in a call, so nothing will be heard. */
    fun replyStatus(position: Int, quiet: Boolean): String = when {
        quiet -> "silenced"
        position == 0 -> "playing"
        else -> "queued"
    }

    /** What one queued or playing sound looks like to [status]. */
    data class Entry(val id: String, val agentId: String, val agent: String, val caption: String?)

    /**
     * GET /v1/speaker for [agentId]: what's playing only if it is that agent's (another agent's caption is
     * private), `busy` when someone else's sound is playing, and only that agent's queue.
     */
    fun status(agentId: String, current: Entry?, queued: List<Entry>, quiet: Boolean, paused: Boolean): JSONObject {
        val mine = current?.takeIf { it.agentId == agentId }
        return JSONObject()
            .put("playing", mine?.let { JSONObject().put("play_id", it.id).put("agent", it.agent).put("text", it.caption ?: JSONObject.NULL) } ?: JSONObject.NULL)
            .put("busy", current != null && mine == null)
            .put("paused", mine != null && paused)
            .put("queued", JSONArray(queued.filter { it.agentId == agentId }.map { JSONObject().put("play_id", it.id) }))
            .put("quiet", quiet)
    }

    /** Which files in the cache [sweep] deletes: unowned ones older than [maxAgeMs], then the oldest unowned until under [maxBytes]. */
    data class CacheFile(val name: String, val modified: Long, val size: Long)
    fun sweep(files: List<CacheFile>, owned: Set<String>, now: Long, maxAgeMs: Long = CACHE_MAX_AGE_MS, maxBytes: Long = CACHE_MAX_BYTES): Set<String> {
        val out = HashSet<String>()
        files.filter { it.name !in owned && now - it.modified > maxAgeMs }.forEach { out += it.name }
        var total = files.filter { it.name !in out }.sumOf { it.size }
        for (f in files.filter { it.name !in owned && it.name !in out }.sortedBy { it.modified }) {
            if (total <= maxBytes) break
            out += f.name; total -= f.size
        }
        return out
    }
}

/**
 * The phone's speaker (CAP-51, CAP-53, SPK-01..03): one player, a queue with the City-tour replace rule,
 * a volume ceiling, audio focus, Stop that clears everything. Text is spoken by synthesising it to a file
 * and playing that, so agent speech, City-tour narration and interaction `speak` share one queue and one
 * set of rules.
 *
 * Threads: queue and [current] change under [lock] (cheap list work only). Everything that touches the
 * MediaPlayer, audio focus or volume runs on one playback HandlerThread ([sync] reconciles the player
 * with [current]); preparation is asynchronous, so nothing blocks the main thread or the queue lock.
 */
class SpeakerQueue(private val app: BridgeApp) {
    data class Item(val id: String, val agentId: String, val agent: String, val file: File, val caption: String?,
                    val tourId: String?, val urgent: Boolean, val queuedAt: Long = System.currentTimeMillis(),
                    /** Interaction speech the owner allowed through silent/DND (urgent) or tapped themselves. Never during a call. */
                    val allowInQuiet: Boolean = false,
                    /** Log only: text-to-speech (not an audio file), and whether an API request asked for it or Bridgit did. */
                    val spoken: Boolean = false, val fromApi: Boolean = true, val loop:Boolean = false,
                    /** A transient applet session may end while synthesis or playback preparation is running. */
                    val canPlay: () -> Boolean = { true })

    private val lock = Any()
    private val queue = ArrayDeque<Item>()
    private var hearing = false
    class Preparation(val agent: String, val job: kotlinx.coroutines.Job, var stopped: Boolean = false)
    private val preparations = mutableSetOf<Preparation>()
    suspend fun beginPreparation(agent: String): Preparation {
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
        return synchronized(lock) {
        if (preparations.size >= 2 || preparations.any { it.agent == agent }) throw ErrorCatalog.error("rate_limited", "A speech turn is already preparing; retry after it finishes.")
        Preparation(agent, job).also { preparations.add(it) }
        }
    }
    fun endPreparation(p: Preparation) = synchronized(lock) { preparations.remove(p) }
    fun beginHearing() = synchronized(lock) {
        if (hearing || current != null || queue.isNotEmpty()) throw ErrorCatalog.error("sensor_busy", "Speech playback or hearing is already active.")
        hearing = true
    }
    fun endHearing() = synchronized(lock) { hearing = false }

    private val speechStops = mutableMapOf<String, Long>()
    private var allSpeechStops = 0L
    @Volatile internal var synthesizeSpeech: suspend (String, File) -> Boolean = { text, file -> tts.synth(text, file, null, 1f, 1f) }
    @Volatile internal var synthesizeAppletSpeech: suspend (String, File, () -> Boolean) -> Boolean = { text, file, valid ->
        val selected = cc.bridgit.app.speech.SpeechEngine.select(app, "speak", true)
        if (!valid()) false else {
            cc.bridgit.app.speech.SpeechEngine.synth(app, selected, text, file, null)
            true
        }
    }

    @Volatile var current: Item? = null; private set
    private val am = app.getSystemService(AudioManager::class.java)
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val dir = File(app.cacheDir, "speaker").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    private val listeners = mutableListOf<() -> Unit>()
    private val limiter = RateLimiter(windowMs = 3_600_000L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tts by lazy { TtsFiles(app) }

    private val thread = HandlerThread("bridgit-speaker").apply { start() }
    private val handler = Handler(thread.looper)
    private val onHandler = Executor { handler.post(it) }

    // ---- playback-thread state (touched only on [handler]) ----
    private var player: MediaPlayer? = null
    private var playerItem: Item? = null
    private var prepared = false
    private var pausedByFocus = false
    private var pausedByCall = false
    private var focus: AudioFocusRequest? = null
    private var restoreVolume: Int? = null
    private var setVolume: Int? = null
    @Volatile var paused = false; private set

    init {
        app.settings.addListener { recheckAccess() }
        // SPK-01/03: react while a clip plays, not only before it starts.
        // Android 10–11 lack the listener; the periodic sweep and pre-play checks still apply.
        if (Build.VERSION.SDK_INT >= 31) am.addOnModeChangedListener(onHandler) { checkConditions() }
        val f = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = checkConditions()
        }, f, null, handler, ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.postDelayed(object : Runnable { override fun run() { sweep(); handler.postDelayed(this, 10 * 60_000L) } }, 10 * 60_000L)
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }

    fun newFile(ext: String) = File(dir, "${UUID.randomUUID()}.$ext")

    fun queued(): List<Item> = synchronized(lock) { queue.toList() }

    /**
     * SPK-01 admission, cheap enough to run before a request body is read: 60 plays an hour per agent and,
     * for replace=queue, at most 5 waiting. Records the play.
     */
    fun admit(agentId: String, replace: String?) {
        if (replace == "queue" && synchronized(lock) { queue.count { it.agentId == agentId } } >= SpeakerRules.MAX_QUEUE_PER_AGENT)
            throw ErrorCatalog.error("rate_limited", "At most ${SpeakerRules.MAX_QUEUE_PER_AGENT} sounds waiting per agent.")
        if (!limiter.tryAcquire(agentId, SpeakerRules.PLAYS_PER_HOUR))
            throw ErrorCatalog.error("rate_limited", "At most ${SpeakerRules.PLAYS_PER_HOUR} plays an hour.", mapOf("retry_after_s" to limiter.retryAfterS(agentId)))
    }

    /**
     * Adds [item]; the queue owns its file from here on (it is deleted if this throws).
     * [replace] is SpeakerRules.REPLACE. Returns the item's position (0 = playing now).
     */
    private fun allowed(agentId: String) = SpeakerRules.allowed(agentId, app.settings.listenerEnabled,
        app.settings.isOn("speaker"), app.agents.get(agentId)?.allows("speaker") == true) ||
        runCatching { app.platform.authorize(agentId, "audio.speak"); true }.getOrDefault(false)

    fun recheckAccess() {
        (queued().map { it.agentId } + listOfNotNull(current?.agentId)).distinct()
            .filterNot(::allowed).forEach { stop(it) }
    }

    fun enqueue(item: Item, replace: String, preparation: Preparation? = null): Int {
        val dropped = ArrayList<Item>()
        val pos: Int
        try {
            synchronized(lock) {
                if (preparation?.stopped == true) throw CancellationException("Speech stopped")
                if (hearing) throw ErrorCatalog.error("sensor_busy", "Hearing is active; playback would transcribe itself.")
                if (!allowed(item.agentId) || !item.canPlay()) throw ErrorCatalog.error("permission_denied", "Speaker access is disabled or revoked.")
                val mine = queue.count { it.agentId == item.agentId }
                if (replace == "queue" && mine >= SpeakerRules.MAX_QUEUE_PER_AGENT)
                    throw ErrorCatalog.error("rate_limited", "At most ${SpeakerRules.MAX_QUEUE_PER_AGENT} sounds waiting per agent.")
                if (replace != "queue") {
                    // City tour (D30): a newer narration replaces anything still waiting from the same agent / tour.
                    val drop = queue.filter { it.agentId == item.agentId && (item.tourId == null || it.tourId == item.tourId) }
                    queue.removeAll(drop.toSet()); dropped += drop
                    val cur = current
                    if (replace == "now" && cur != null && cur.agentId == item.agentId) { dropped += cur; current = null }
                }
                if (item.urgent) queue.addFirst(item) else queue.addLast(item)
                if (current == null) current = queue.removeFirst()
                pos = if (current === item) 0 else queue.indexOf(item) + 1
            }
        } catch (e: Throwable) {
            item.file.delete(); throw e
        }
        dropped.forEach { it.file.delete() }
        handler.post { sync() }
        changed()
        return pos
    }

    suspend fun stopAndWait(agentId: String) {
        val jobs = synchronized(lock) { preparations.filter { it.agent == agentId }.map { it.job } }
        stop(agentId)
        jobs.forEach { it.join() }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { sync() }
    }

    /** Stop (SPK-01): stops playback and clears every queue, or only [agentId]'s. */
    fun stop(agentId: String? = null) {
        val pending = synchronized(lock) { preparations.filter { agentId == null || it.agent == agentId }.onEach { it.stopped = true } }
        pending.forEach { it.job.cancel(CancellationException("Speech stopped")) }
        val dropped = ArrayList<Item>()
        synchronized(lock) {
            if (agentId == null) allSpeechStops++ else speechStops[agentId] = (speechStops[agentId] ?: 0L) + 1
            val drop = queue.filter { agentId == null || it.agentId == agentId }
            queue.removeAll(drop.toSet()); dropped += drop
            val cur = current
            if (cur != null && (agentId == null || cur.agentId == agentId)) { dropped += cur; current = queue.removeFirstOrNull() }
        }
        dropped.forEach { it.file.delete() }
        handler.post { sync() }
        changed()
    }

    fun skip() {
        val cur: Item
        synchronized(lock) { cur = current ?: return; current = queue.removeFirstOrNull() }
        cur.file.delete()
        handler.post { sync() }
        changed()
    }

    /**
     * Synthesises [text] and queues it for [agentId] (CAP-31 `speak`, Play aloud). Same queue, ceiling,
     * 5-item limit and Stop as agent speech. Fire and forget; failures are logged.
     */
    fun speakText(agentId: String, agent: String, text: String, allowInQuiet: Boolean, allowCloud: Boolean = false, stillAllowed: () -> Boolean = { true }) {
        val stamp = synchronized(lock) { allSpeechStops to (speechStops[agentId] ?: 0L) }
        val valid = { synchronized(lock) { stamp == (allSpeechStops to (speechStops[agentId] ?: 0L)) } && stillAllowed() }
        scope.launch(cc.bridgit.app.outbound.Outbound.context(cc.bridgit.app.outbound.Outbound.Actor.agent(agentId, agent))) {
            val preparation = try { beginPreparation(agentId) } catch (_: ApiError) { return@launch }
            val f = newFile("wav")
            var owned = false
            try {
                if (!allowed(agentId) || !valid()) return@launch
                val synthesized = if (allowCloud) synthesizeAppletSpeech(text, f, valid) else synthesizeSpeech(text, f)
                if (!synthesized) {
                    app.log.system("diagnostic", "service", "Couldn't read $agent's interaction aloud (text-to-speech failed)", level = "warn")
                    return@launch
                }
                enqueue(Item(UUID.randomUUID().toString().take(12), agentId, agent, f, text.take(500), null, urgent = false,
                    allowInQuiet = allowInQuiet, spoken = true, fromApi = false, canPlay = valid), "queue", preparation)
                owned = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = when (e) {
                    is ApiError -> e.code
                    is cc.bridgit.app.judgment.Jev.Failure -> e.reason
                    else -> e.javaClass.simpleName
                }
                app.log.system("diagnostic", "service", "Didn't read $agent's interaction aloud: $reason", level = "warn")
            } finally {
                endPreparation(preparation)
                if (!owned) f.delete()
            }
        }
    }

    /** A phone call (or VoIP call) is active: nothing plays (SPK-03). MODE_CALL_SCREENING is a constant older versions never report. */
    @Suppress("InlinedApi")
    fun inCall(): Boolean = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION ||
        am.mode == AudioManager.MODE_RINGTONE || am.mode == AudioManager.MODE_CALL_SCREENING

    /** Silent, vibrate or Do Not Disturb (SPK-01: media playback isn't silenced by Android on its own). */
    fun silenced(): Boolean = am.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
        nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL

    /** Silent / vibrate / DND / a call: don't play (SPK-01, SPK-03). */
    fun quiet(): Boolean = inCall() || silenced()

    private fun blocked(i: Item) = !i.canPlay() || !allowed(i.agentId) || inCall() || (silenced() && !i.allowInQuiet)

    // ---- playback thread ---------------------------------------------------------------------------

    /** Makes the player match [current]: tears down a stale one, starts the wanted one. Playback thread only. */
    private fun sync() {
        while (true) {
            val want = current
            if (playerItem != null && playerItem === want) return
            teardown()
            if (want == null) { releaseAudio(); paused = false; changed(); return }
            if (blocked(want)) { advance(want); continue }
            if (!requestFocus()) {
                app.log.system("diagnostic", "service", "Audio focus refused; ${want.agent}'s audio was dropped", level = "warn")
                advance(want); continue
            }
            applyCeiling()
            app.log.system("audit", "interaction", "${want.agent} played audio" + (want.tourId?.let { " (tour)" } ?: ""),
                data = cc.bridgit.app.log.LogFacts.played(want.agentId, want.spoken, want.fromApi, want.caption, want.tourId != null))
            val mp = MediaPlayer()
            player = mp; playerItem = want; prepared = false
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(want.file.absolutePath)
                mp.setOnPreparedListener { onPrepared(mp, want) }
                mp.setOnCompletionListener { if (player === mp) finished(want) }
                mp.setOnErrorListener { _, _, _ -> if (player === mp) finished(want); true }
                mp.prepareAsync()
                changed()
                return
            } catch (e: Exception) {
                app.log.system("diagnostic", "service", "Couldn't play ${want.agent}'s audio: ${e.javaClass.simpleName}", level = "warn")
                teardown(); advance(want)
            }
        }
    }

    private fun onPrepared(mp: MediaPlayer, item: Item) {
        if (player !== mp) return
        if (blocked(item)) { finished(item); return }
        mp.isLooping = item.loop
        prepared = true
        if (mp.duration > SpeakerRules.MAX_CLIP_MS) {
            app.log.system("diagnostic", "service", "${item.agent}'s audio is longer than 10 minutes; not played", level = "warn")
            finished(item); return
        }
        if (pausedByFocus || pausedByCall || inCall()) { pausedByCall = pausedByCall || inCall(); paused = true; changed(); return }
        mp.start(); paused = false
    }

    /** The item finished (or failed): move on. */
    private fun finished(item: Item) {
        teardown()
        advance(item)
        sync()
        changed()
    }

    /** Removes [item] from [current] (if it still is) and deletes its file. */
    private fun advance(item: Item) {
        synchronized(lock) { if (current === item) current = queue.removeFirstOrNull() }
        item.file.delete()
    }

    private fun teardown() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null; playerItem = null; prepared = false
    }

    private fun resumeIfPossible() {
        val mp = player ?: return
        if (!prepared || pausedByFocus || pausedByCall || inCall()) return
        if (!mp.isPlaying) { mp.start(); paused = false; changed() }
    }

    private fun pausePlayer() {
        player?.let { if (prepared && it.isPlaying) it.pause() }
        if (player != null) { paused = true; changed() }
    }

    /** A call started or ended, or silent/DND changed, while something plays. */
    private fun checkConditions() {
        val item = playerItem ?: return
        if (inCall()) { pausedByCall = true; pausePlayer(); return }
        if (silenced() && !item.allowInQuiet) {
            app.log.system("diagnostic", "service", "Phone went silent or Do Not Disturb; speaker stopped")
            stop(); return
        }
        if (pausedByCall) { pausedByCall = false; resumeIfPossible() }
    }

    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { pausedByFocus = false; stop() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { pausedByFocus = true; pausePlayer() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.2f, 0.2f)
            AudioManager.AUDIOFOCUS_GAIN -> { player?.setVolume(1f, 1f); pausedByFocus = false; resumeIfPossible() }
        }
    }

    private fun requestFocus(): Boolean {
        if (focus != null) return true
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, handler).build()
        return if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { focus = req; true } else false
    }

    /** SPK-01: never louder than the owner's ceiling; put back afterwards unless they changed it meanwhile. */
    @Synchronized private fun applyCeiling() {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val ceiling = max * app.settings.speakerCeiling / 100
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (now > ceiling) {
            if (restoreVolume == null) restoreVolume = now
            am.setStreamVolume(AudioManager.STREAM_MUSIC, ceiling, 0); setVolume = ceiling
        }
    }

    @Synchronized fun controlVolume(): Int {
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (setVolume == now) restoreVolume ?: now else now
    }

    @Synchronized fun controlVolume(value: Int) {
        val ceilingActive = restoreVolume != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == setVolume
        if (ceilingActive) {
            val ceiling = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * app.settings.speakerCeiling / 100
            am.setStreamVolume(AudioManager.STREAM_MUSIC, minOf(value, ceiling), 0)
            setVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            restoreVolume = value
        } else am.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
    }

    @Synchronized private fun releaseAudio() {
        focus?.let { am.abandonAudioFocusRequest(it) }; focus = null
        pausedByFocus = false; pausedByCall = false
        val r = restoreVolume
        if (r != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == setVolume) am.setStreamVolume(AudioManager.STREAM_MUSIC, r, 0)
        restoreVolume = null; setVolume = null
    }

    /** Deletes cache files nobody owns (failed downloads or synthesis, abandoned uploads) and caps the cache size. */
    fun sweep() {
        val owned = synchronized(lock) { (queue.map { it.file.name } + listOfNotNull(current?.file?.name)).toSet() }
        val files = dir.listFiles()?.map { SpeakerRules.CacheFile(it.name, it.lastModified(), it.length()) } ?: return
        SpeakerRules.sweep(files, owned, System.currentTimeMillis()).forEach { File(dir, it).delete() }
    }
}

/**
 * Text to an audio file with the phone's TTS (CAP-53). Offline voices only where the engine says so.
 * One engine, one permanent progress listener that completes requests by utterance id, and requests
 * serialised (language, rate, pitch and voice are engine-wide settings).
 */
class TtsFiles(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private var ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val mutex = Mutex()
    /** Chosen offline voice per language tag (null = keep the engine's default). */
    private val voices = HashMap<String, android.speech.tts.Voice?>()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(u: String?) {}
        override fun onDone(u: String?) { u?.let { pending[it]?.complete(true) } }
        @Deprecated("old API") override fun onError(u: String?) { u?.let { pending[it]?.complete(false) } }
        override fun onError(u: String?, code: Int) { u?.let { pending[it]?.complete(false) } }
        override fun onStop(u: String?, interrupted: Boolean) { u?.let { pending[it]?.complete(false) } }
    }

    private fun ensure() {
        val signal = ready
        if (tts == null) tts = TextToSpeech(ctx) { status -> signal.complete(status == TextToSpeech.SUCCESS) }.also {
            it.setOnUtteranceProgressListener(listener)
        }
    }

    suspend fun readiness(): JSONObject {
        withContext(Dispatchers.Main) { ensure() }
        if (withTimeoutOrNull(5000) { ready.await() } != true) return JSONObject().put("ready", false).put("reason", "engine_unavailable")
        val available = withContext(Dispatchers.IO) { tts?.voices.orEmpty().filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() } }
        return JSONObject().put("ready", available.isNotEmpty()).put("offline_voices", available.size)
            .put("engine", tts?.defaultEngine ?: JSONObject.NULL).put("voice", tts?.voice?.name ?: JSONObject.NULL)
            .put("voice_quality", tts?.voice?.quality ?: JSONObject.NULL).put("voice_latency", tts?.voice?.latency ?: JSONObject.NULL)
    }
    /** Writes [text] to [out]. False on failure; the caller deletes [out]. */
    suspend fun synth(text: String, out: File, language: String?, rate: Float, pitch: Float): Boolean = mutex.withLock {
        withContext(Dispatchers.Main) { ensure() }
        if (withTimeoutOrNull(5000) { ready.await() } != true) {
            withContext(Dispatchers.Main) { tts?.shutdown(); tts = null; ready = CompletableDeferred(); voices.clear() }
            return false
        }
        val t = tts!!
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        try {
            // Off the main thread: these are calls into the speech service, and listing voices can take seconds
            // (an ANR in 1.5.2 testing). Serialised by [mutex], since language, rate and voice are engine-wide.
            val started = withContext(Dispatchers.IO) {
                val locale = language?.let { java.util.Locale.forLanguageTag(it) } ?: java.util.Locale.getDefault()
                if (t.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) return@withContext false
                t.setSpeechRate(rate); t.setPitch(pitch)
                // Prefer a voice that doesn't need the network (Codex review: TTS may use network voices); looked up once per language.
                val tag = locale.toLanguageTag()
                val v = if (voices.containsKey(tag)) voices[tag] else {
                    val offline = t.voices.orEmpty().filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() && it.locale.language == locale.language }
                    // Honor the owner's installed default voice; otherwise prefer quality over the lowest latency tier.
                    offline.firstOrNull { it.name == t.defaultVoice?.name } ?: offline.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.latency }).firstOrNull()
                }.also { voices[tag] = it }
                if (v == null) return@withContext false
                t.voice = v
                t.synthesizeToFile(text, null, out, id) == TextToSpeech.SUCCESS
            }
            if (!started) return false
            val ok = withTimeoutOrNull(30_000) { done.await() } == true && out.length() > 0
            if (!ok) runCatching { t.stop() }
            ok
        } catch (e: CancellationException) {
            runCatching { t.stop() }; throw e
        } finally {
            pending.remove(id)
        }
    }
}

/** CAP-51 / CAP-53 (minimal, for City tour): play audio and speak text. Switch "Speaker". */
class SpeakerModule(app: BridgeApp) : Capability(app) {
    override val id = "speaker"
    override val titleRes = R.string.cap_speaker
    override val descriptionRes = R.string.cap_speaker_desc
    override val group = "talk"
    override val switchKey = "speaker"
    override val endpoints = listOf(Endpoints.SPEAKER_PLAY, Endpoints.SPEAKER_SAY, Endpoints.SPEAKER_STATUS, Endpoints.SPEAKER_STOP, Endpoints.SPEECH_SPEAK)
    override fun blockedReason(): String? = if (!app.captureNotificationsOn()) "notifications_off" else null
    override fun details() = JSONObject().put("volume_ceiling_percent", app.settings.speakerCeiling)
        .put("queue_per_agent", SpeakerRules.MAX_QUEUE_PER_AGENT).put("plays_per_hour", SpeakerRules.PLAYS_PER_HOUR)

    /** replace, tour_id, urgent. With [charge], also counts the play (SPEAKER_PLAY is admitted before its body is read). */
    private fun cc.bridgit.app.api.Req.common(charge: Boolean): Triple<String, String?, Boolean> {
        val replace = (arg("replace") as? String) ?: "after_current"
        if (replace !in SpeakerRules.REPLACE) throw ErrorCatalog.error("invalid_parameter", "replace must be after_current, now or queue.")
        val tourId = (arg("tour_id") as? String)?.takeIf { it.isNotBlank() }
        if (tourId != null && app.tours.current()?.let { it.id == tourId && it.agentId == agent!!.id } != true)
            throw ErrorCatalog.error("not_found", "No running tour $tourId of yours.")
        val urgent = (arg("urgent") as? Boolean) ?: (arg("urgent") == "true")
        if (charge) app.speaker.admit(agent!!.id, replace)
        return Triple(replace, tourId, urgent)
    }

    private fun reply(item: SpeakerQueue.Item, pos: Int) = Reply.Json(JSONObject().put("play_id", item.id)
        .put("position", pos).put("status", SpeakerRules.replyStatus(pos, app.speaker.quiet())))

    override fun Route.install(api: ApiServer) = with(api) {
        // Up to 25 MB: switch, rate and queue are checked before the body is read (finding: concurrent big uploads).
        endpoint(Endpoints.SPEAKER_PLAY, admit = { queries, agent ->
            requireUsable()
            val replace = queries["replace"]
            app.speaker.admit(agent!!.id, replace)
        }) {
            requireUsable()
            val (replace, tourId, urgent) = common(charge = false)
            val a = agent!!
            val preparation=app.speaker.beginPreparation(a.id)
            var file: File? = null
            var owned = false
            try {
                val ctype = header("Content-Type") ?: ""
                // Checked before taking the upload, so the pipeline still deletes a refused one.
                if (hasRawFile() && !ctype.startsWith("audio/")) throw ErrorCatalog.error("invalid_parameter", "Send audio/* bytes as the body, or a JSON {\"url\"}.")
                val upload = takeRawFile()
                if (upload != null) {
                    val ext = ctype.substringAfter('/').substringBefore(';').filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
                    file = app.speaker.newFile(ext)
                    try { if (!upload.renameTo(file)) upload.copyTo(file, overwrite = true) } finally { upload.delete() }
                } else {
                    val url = body.optString("url", "")
                    if (!SpeakerRules.urlAllowed(url)) throw ErrorCatalog.error("invalid_parameter", "url must be a public https:// address (or send audio/* bytes as the body).")
                    params.put("url", URI(url).host)
                    file = app.speaker.newFile("audio")
                    fetchPublic(url, file)
                }
                val item = SpeakerQueue.Item(UUID.randomUUID().toString().take(12), a.id, a.name, file,
                    (arg("text") as? String)?.take(500), tourId, urgent, loop=arg("loop")==true)
                val pos = app.speaker.enqueue(item, replace, preparation)
                owned = true
                tourId?.let { app.tours.narrated(it, item.caption) }
                message = "${a.name} sent audio to play"
                reply(item, pos)
            } finally {
                if (!owned) file?.delete()
                app.speaker.endPreparation(preparation)
            }
        }
        for (spec in listOf(Endpoints.SPEAKER_SAY, Endpoints.SPEECH_SPEAK)) endpoint(spec) {
            requireUsable()
            val (replace, tourId, urgent) = common(charge = true)
            val a = agent!!
            val presentationToken=if(body.has("presentation_token")) body.getString("presentation_token") else null
            cc.bridgit.app.judgment.ContextServices.withPresentation(a.id,presentationToken) {}
            val text = body.optString("text", "").trim()
            if (text.isEmpty() || text.length > SpeakerRules.MAX_SAY_CHARS) throw ErrorCatalog.error("invalid_parameter", "text must be 1 to ${SpeakerRules.MAX_SAY_CHARS} characters.")
            val rate = body.optDouble("rate", 1.0).toFloat().takeIf { it in 0.5f..2f } ?: throw ErrorCatalog.error("invalid_parameter", "rate must be 0.5 to 2.")
            val pitch = body.optDouble("pitch", 1.0).toFloat().takeIf { it in 0.5f..2f } ?: throw ErrorCatalog.error("invalid_parameter", "pitch must be 0.5 to 2.")
            val preparation = app.speaker.beginPreparation(a.id)
            try {
            val selected = if (spec == Endpoints.SPEECH_SPEAK) cc.bridgit.app.speech.SpeechEngine.select(app, "speak", body.optBoolean("allow_cloud", false),body.optString("task_key").ifBlank { null }) else null
            if(body.has("expected_model") && selected?.model != body.optString("expected_model")) throw cc.bridgit.app.api.ApiError(409,"speech_model_changed","The selected narration voice changed")
            val file = app.speaker.newFile("audio")
            var owned = false
            var speech: JSONObject? = null
            try {
                if (selected != null) speech = cc.bridgit.app.speech.SpeechEngine.synth(app, selected, text, file, body.optString("language").ifBlank { null })
                else if (!app.speaker.tts.synth(text, file, body.optString("language", "").ifEmpty { null }, rate, pitch))
                    throw ErrorCatalog.error("internal_error", "The phone's text-to-speech didn't produce audio.")
                if(body.optBoolean("wait_for_reveal")) cc.bridgit.app.judgment.ContextServices.awaitReveal(a.id,presentationToken)
                val item = SpeakerQueue.Item(UUID.randomUUID().toString().take(12), a.id, a.name, file, text.take(500), tourId, urgent, spoken = true)
                val pos = cc.bridgit.app.judgment.ContextServices.withPresentation(a.id,presentationToken) { app.speaker.enqueue(item, replace, preparation) }
                owned = true
                tourId?.let { app.tours.narrated(it, text) }
                message = "${a.name} sent speech to read aloud"
                if (speech != null) Reply.Json(JSONObject().put("play_id", item.id).put("position", pos).put("status", SpeakerRules.replyStatus(pos, app.speaker.quiet())).put("speech", speech)) else reply(item, pos)
            } finally {
                if (!owned) file.delete()
            }
            } finally { app.speaker.endPreparation(preparation) }
        }
        endpoint(Endpoints.SPEAKER_STATUS) {
            fun e(i: SpeakerQueue.Item) = SpeakerRules.Entry(i.id, i.agentId, i.agent, i.caption)
            Reply.Json(SpeakerRules.status(agent!!.id, app.speaker.current?.let(::e), app.speaker.queued().map(::e),
                app.speaker.quiet(), app.speaker.paused))
        }
        endpoint(Endpoints.SPEAKER_STOP) {
            app.speaker.stop(agent!!.id)
            Reply.Json(JSONObject().put("stopped", true))
        }
    }

    /** SPK-02 through SafeHttp: public addresses only (pinned), every redirect re-checked (max 3), 25 MB, 60 s in total. */
    private suspend fun fetchPublic(url: String, out: File) = withContext(Dispatchers.IO) {
        val r = try {
            cc.bridgit.app.outbound.Outbound.hinted(cc.bridgit.app.outbound.Outbound.Hint("Agent's audio URL", "Fetch audio the agent asked the phone to play")) {
            SafeHttp.get(url, SafeHttp.Policy.PUBLIC, SpeakerRules.FETCH_DEADLINE_MS, SpeakerRules.MAX_BYTES,
                headers = mapOf("User-Agent" to "Bridgit/${app.appVersion()} (Android)"), maxRedirects = SafeHttp.MAX_REDIRECTS, out = out) }
        } catch (e: SafeHttp.Failure) {
            out.delete()
            throw when (e.reason) {
                SafeHttp.Reason.TOO_LARGE -> ErrorCatalog.error("payload_too_large", "Audio is limited to 25 MB.")
                SafeHttp.Reason.TIMEOUT -> ErrorCatalog.error("invalid_parameter", "The audio URL took longer than 60 seconds.")
                else -> ErrorCatalog.error("invalid_parameter", "Can't fetch the audio URL: ${e.message}")
            }
        }
        if (r.status != 200) { out.delete(); throw ErrorCatalog.error("invalid_parameter", "The audio URL answered HTTP ${r.status}.") }
    }
}
