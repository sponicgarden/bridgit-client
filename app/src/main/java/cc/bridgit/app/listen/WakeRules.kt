package cc.bridgit.app.listen

import org.json.JSONArray
import org.json.JSONObject

/** One wake word (CAP-47): the phrase the owner says, the agent it goes to, and whether the audio clip goes too. */
data class WakeWord(val phrase: String, val agentId: String, val sendClip: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("phrase", phrase).put("agent_id", agentId).put("send_clip", sendClip)
    companion object {
        fun fromJson(o: JSONObject) = WakeWord(o.getString("phrase"), o.getString("agent_id"), o.optBoolean("send_clip", false))
    }
}

/** "Listen for any sounds" (8.23): which agent hears about sounds, how loud they must be, and whether a clip goes too. */
data class SoundConfig(val agentId: String, val sensitivity: String = "normal", val sendClip: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("agent_id", agentId).put("sensitivity", sensitivity).put("send_clip", sendClip)
    companion object {
        val SENSITIVITIES = listOf("quiet", "normal", "loud")
        fun fromJson(s: String?): SoundConfig? = runCatching {
            val o = JSONObject(s ?: return null)
            SoundConfig(o.getString("agent_id"), o.optString("sensitivity", "normal").takeIf { it in SENSITIVITIES } ?: "normal", o.optBoolean("send_clip", true))
        }.getOrNull()
    }
}

/**
 * Wake-word rules (8.23, CAP-47/48), pure Kotlin and unit-tested: which phrases are allowed, the spotter's
 * grammar, matching a spotter result, when the follow-up ends, and what `listen.heard` carries.
 */
object WakeRules {
    const val MAX_WORDS = 10
    const val MAX_SECONDS = 60
    /** A pause this long after speech ends what follows the wake word. */
    const val PAUSE_MS = 1_500L
    /** Nobody spoke after the wake word: stop here and send an empty transcript. */
    const val NO_SPEECH_MS = 6_000L
    /** Default memory time for clips; agents and the owner can change it (CaptureRules, SEC-13). */
    const val CLIP_KEEP_MS = cc.bridgit.app.capture.CaptureRules.DEFAULT_KEEP_MINUTES * 60_000L
    const val SOUND_COOLDOWN_MS = 30_000L
    const val SOUND_MAX_MS = 10_000L
    const val MUTE_MS = 15 * 60_000L
    const val LOG_CHARS = 200
    const val SAMPLE_RATE = 16_000

    /** Lower case, letters and apostrophes only, single spaces: "Hey, Bridget!" → "hey bridget". */
    fun normalize(phrase: String): String =
        phrase.lowercase().replace(Regex("[^a-z' ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * Null when [w] can be added to [existing]; otherwise the reason in the owner's words. [known] says whether
     * the speech model knows a word (null when the model isn't downloaded yet, so that check waits).
     */
    fun problem(existing: List<WakeWord>, w: WakeWord, agentExists: (String) -> Boolean, known: ((String) -> Boolean)?): String? {
        val p = normalize(w.phrase)
        val words = p.split(' ').filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> "Type a wake word."
            words.size < 2 -> "Use at least two words, like \"hey bridget\": one word on its own is heard by mistake too often."
            words.size > 4 -> "Keep it to four words or fewer."
            existing.size >= MAX_WORDS -> "You can have up to $MAX_WORDS wake words."
            existing.any { normalize(it.phrase) == p } -> "\"$p\" is already a wake word."
            existing.any { overlaps(normalize(it.phrase), p) } -> "\"$p\" overlaps another wake word; make them start differently."
            !agentExists(w.agentId) -> "Pick an agent."
            known != null && words.any { !known(it) } ->
                "The speech model doesn't know " + words.filter { !known(it) }.joinToString(", ") { "\"$it\"" } + ". Try a spelling from the dictionary (\"bridget\", not \"bridgit\")."
            else -> null
        }
    }

    /** One phrase starting with the other would make the shorter one fire first. */
    private fun overlaps(a: String, b: String) = "$a ".startsWith("$b ") || "$b ".startsWith("$a ")

    /** Vosk grammar: only the wake phrases, and "[unk]" for everything else said in the room. */
    fun grammar(words: List<WakeWord>): String =
        JSONArray(words.map { normalize(it.phrase) }.distinct() + "[unk]").toString()

    /** A spotter hit: the wake word and where it ended in the spotter's audio (seconds), when known. */
    data class Hit(val wake: WakeWord, val endS: Double?)

    /**
     * Finds a wake phrase in a spotter result or partial result ({"text"|"partial", "result"|"partial_result":
     * [{word, conf, start, end}]}). With per-word confidences, every word of the phrase must reach [minConf].
     */
    fun match(json: String, words: List<WakeWord>, minConf: Double = 0.75): Hit? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val items = (o.optJSONArray("result") ?: o.optJSONArray("partial_result"))?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }
        val heard = items?.map { it.optString("word") } ?: (o.optString("text").ifEmpty { o.optString("partial") }).split(' ').filter { it.isNotEmpty() }
        for (w in words) {
            val phrase = normalize(w.phrase).split(' ')
            for (i in 0..heard.size - phrase.size) {
                if (heard.subList(i, i + phrase.size) != phrase) continue
                val span = items?.subList(i, i + phrase.size)
                // Partial words can come without confidences; only a confidence that is present can veto.
                if (span != null && span.any { it.has("conf") && it.optDouble("conf", 1.0) < minConf }) continue
                return Hit(w, span?.last()?.optDouble("end")?.takeIf { !it.isNaN() })
            }
        }
        return null
    }

    /**
     * Decides when what follows a wake word is over, from one loudness reading per audio frame. The noise
     * floor adapts to the room, so a fan or traffic doesn't count as speech.
     */
    class PauseDetector(private val frameMs: Long, private val maxMs: Long = MAX_SECONDS * 1000L) {
        enum class End { PAUSE, MAX, NO_SPEECH }
        private var elapsed = 0L
        private var quietFor = 0L
        private var spoke = false
        private var floor = -1.0
        val heardSpeech get() = spoke

        /** [rms] of the frame's 16-bit samples. Returns why it ended, or null to keep listening. */
        fun feed(rms: Double): End? {
            elapsed += frameMs
            if (floor < 0) floor = rms
            val speech = rms > maxOf(floor * 2.5, MIN_SPEECH_RMS)
            // The floor drops at once and rises only on quiet frames, so a long sentence never becomes "the room".
            if (rms < floor) floor = rms else if (!speech) floor += (rms - floor) * 0.05
            if (speech) { spoke = true; quietFor = 0 } else quietFor += frameMs
            return when {
                elapsed >= maxMs -> End.MAX
                spoke && quietFor >= PAUSE_MS -> End.PAUSE
                !spoke && elapsed >= NO_SPEECH_MS -> End.NO_SPEECH
                else -> null
            }
        }
        companion object { const val MIN_SPEECH_RMS = 300.0 }
    }

    /**
     * The cheap stage in front of the spotter (CAP-47): Vosk only hears frames with sound in them, plus a short
     * hangover, so a quiet room costs almost nothing. The floor adapts like [PauseDetector]'s.
     */
    class SoundGate(private val hangoverFrames: Int = 8) {
        private var floor = -1.0
        private var hangover = 0
        /** True when this frame should go to the spotter. */
        fun feed(rms: Double): Boolean {
            if (floor < 0) floor = rms
            val loud = rms > maxOf(floor * 2.0, MIN_SOUND_RMS)
            if (rms < floor) floor = rms else if (!loud) floor += (rms - floor) * 0.05
            if (loud) hangover = hangoverFrames else if (hangover > 0) hangover-- else return false
            return true
        }
        companion object { const val MIN_SOUND_RMS = 150.0 }
    }

    /**
     * "Listen for any sounds": a sound starts when the room gets [factor] times louder than its quiet level (and
     * above an absolute floor) for 200 ms, and ends after 1 s of quiet or at [maxMs]. After one, the next can't
     * start for [cooldownMs], so a noisy room doesn't flood the agent.
     */
    class SoundDetector(sensitivity: String, private val frameMs: Long = 100, private val cooldownMs: Long = SOUND_COOLDOWN_MS,
                        private val maxMs: Long = SOUND_MAX_MS) {
        enum class Ev { START, END }
        private val factor = when (sensitivity) { "quiet" -> 3.0; "loud" -> 8.0; else -> 5.0 }
        private val minRms = when (sensitivity) { "quiet" -> 300.0; "loud" -> 3000.0; else -> 800.0 }
        private var floor = -1.0
        private var t = 0L
        private var loudFrames = 0
        private var quietMs = 0L
        private var startT = 0L
        private var lastEnd = Long.MIN_VALUE / 2
        private var sum = 0.0
        private var frames = 0
        var active = false; private set
        var peakRms = 0.0; private set
        val meanRms get() = if (frames == 0) 0.0 else sum / frames
        val durationMs get() = (if (active) t else lastEnd) - startT

        fun feed(rms: Double): Ev? {
            t += frameMs
            if (floor < 0) floor = rms
            val loud = rms > maxOf(floor * factor, minRms)
            if (!active) {
                if (rms < floor) floor = rms else if (!loud) floor += (rms - floor) * 0.05
                loudFrames = if (loud) loudFrames + 1 else 0
                if (loudFrames >= 2 && t - lastEnd >= cooldownMs) {
                    active = true; startT = t - 2 * frameMs; quietMs = 0; peakRms = rms; sum = rms; frames = 1
                    return Ev.START
                }
                return null
            }
            peakRms = maxOf(peakRms, rms); sum += rms; frames++
            if (loud) quietMs = 0 else quietMs += frameMs
            if (quietMs >= 1_000 || t - startT >= maxMs) { active = false; lastEnd = t; loudFrames = 0; return Ev.END }
            return null
        }
    }

    /** dB relative to full scale for a 16-bit RMS value (0 is the loudest possible). */
    fun dbfs(rms: Double): Double = if (rms <= 0) -96.0 else Math.round(20 * Math.log10(rms / 32768.0) * 10) / 10.0

    /** The `sound.heard` event. */
    fun soundEvent(heardAt: String, durationS: Double, peakRms: Double, meanRms: Double, sensitivity: String, clipId: String?): JSONObject = JSONObject()
        .put("heard_at", heardAt).put("duration_seconds", Math.round(durationS * 10) / 10.0)
        .put("peak_dbfs", dbfs(peakRms)).put("mean_dbfs", dbfs(meanRms)).put("sensitivity", sensitivity)
        .put("clip_id", clipId ?: JSONObject.NULL)
        .apply { if (clipId != null) put("clip_url", "/v1/sounds/clip/$clipId") }

    fun rms(pcm: ByteArray, n: Int): Double {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < n) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s; count++; i += 2
        }
        return if (count == 0) 0.0 else Math.sqrt(sum / count)
    }

    fun endedBy(e: PauseDetector.End) = when (e) {
        PauseDetector.End.PAUSE -> "pause"
        PauseDetector.End.MAX -> "max_seconds"
        PauseDetector.End.NO_SPEECH -> "no_speech"
    }

    /** CAP-48: the `listen.heard` event. Nothing from before the wake word is ever in it. */
    fun heardEvent(w: WakeWord, transcript: String, heardAt: String, endedBy: String, durationS: Double,
                   recogniser: String, clipId: String?): JSONObject = JSONObject()
        .put("wake_word", normalize(w.phrase)).put("transcript", transcript).put("heard_at", heardAt)
        .put("ended_by", endedBy).put("duration_seconds", Math.round(durationS * 10) / 10.0)
        .put("recogniser", recogniser).put("clip_id", clipId ?: JSONObject.NULL)
        .apply { if (clipId != null) put("clip_url", "/v1/listen/clip/$clipId") }

    /** LOG-02: the log keeps at most 200 characters of what was said. */
    fun forLog(transcript: String) = if (transcript.length <= LOG_CHARS) transcript else transcript.take(LOG_CHARS - 1) + "…"

    fun toJson(words: List<WakeWord>): String = JSONArray(words.map { it.toJson() }).toString()
    fun fromJson(s: String?): List<WakeWord> = runCatching {
        val a = JSONArray(s ?: "[]"); (0 until a.length()).map { WakeWord.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** 16-bit mono PCM as a WAV file, for the optional clip. */
    fun wav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val out = java.io.ByteArrayOutputStream(44 + pcm.size)
        fun int(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff) }
        fun short(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
