package cc.bridgit.app.lancamera

import java.io.ByteArrayOutputStream

/**
 * Pure pieces of the camera-microphone loudness stream (CameraMics). Unit-tested.
 *
 * A 5-second recording ([CameraRtsp.record]) throws the clip away on any packet loss; a stream that runs for hours
 * can't, so [Join] fills short gaps with silence and starts over after a long one.
 */
object CameraMicAudio {
    /** The loudness detector and SoundPrint both work on 16 kHz mono frames of this many milliseconds. */
    const val RATE = 16_000
    const val FRAME_MS = 100
    const val FRAME_BYTES = RATE / 1000 * FRAME_MS * 2

    /**
     * RTP audio payloads (already decoded to 16-bit PCM at the track's rate) in arrival order → one continuous PCM
     * stream. Timestamps within [jitterMs] of where the audio so far ends are jitter, not loss: a Tapo C210 stamps
     * its 560-sample packets about 40 samples early or late, alternately (measured 4 Nov 2026), and the audio is
     * continuous. A real gap up to [maxGapMs] becomes silence; anything stranger (a new source, a jump back, a long
     * gap) restarts the timeline without failing the stream. [gaps] and [resets] count them, for diagnostics.
     */
    class Join(private val rate: Int, private val maxGapMs: Int = 250, private val jitterMs: Int = 20) {
        private var expected: Long? = null
        private var ssrc: Int? = null
        var gaps = 0; private set
        var resets = 0; private set

        /** PCM to append for this packet (may start with inserted silence, or be trimmed by an overlap). */
        fun add(ssrc: Int, timestamp: Long, pcm: ByteArray): ByteArray {
            val samples = pcm.size / 2
            val exp = expected
            val out: ByteArray = when {
                exp == null || this.ssrc != ssrc -> { if (exp != null) resets++; pcm }
                else -> {
                    val raw = (timestamp - exp) and 0xffffffffL
                    val delta = if (raw > 0x7fffffffL) raw - 0x100000000L else raw
                    when {
                        kotlin.math.abs(delta) <= rate.toLong() * jitterMs / 1000 -> { this.ssrc = ssrc; expected = (exp + samples) and 0xffffffffL; return pcm }
                        delta > 0 && delta <= rate.toLong() * maxGapMs / 1000 -> { gaps++; ByteArray(delta.toInt() * 2) + pcm }
                        delta < 0 && -delta < samples -> { gaps++; pcm.copyOfRange((-delta).toInt() * 2, pcm.size) }
                        else -> { resets++; pcm }
                    }
                }
            }
            this.ssrc = ssrc
            expected = (timestamp + samples) and 0xffffffffL
            return out
        }
    }

    /**
     * 8 kHz → 16 kHz by linear interpolation (16 kHz passes through). An 8 kHz camera has nothing above 4 kHz,
     * so SoundPrint's top bands stay near zero: a camera clip and a phone clip of the same bark match less well
     * and may each get a Clef check. That costs a check, not a wrong answer.
     */
    fun to16k(pcm: ByteArray, rate: Int): ByteArray {
        if (rate == RATE) return pcm
        require(rate == 8_000) { "Unsupported camera audio rate" }
        val n = pcm.size / 2
        val out = ByteArray(n * 4)
        for (i in 0 until n) {
            val cur = sample(pcm, i)
            val next = if (i + 1 < n) sample(pcm, i + 1) else cur
            put(out, 2 * i, cur)
            put(out, 2 * i + 1, (cur + next) / 2)
        }
        return out
    }
    private fun sample(b: ByteArray, i: Int) = ((b[2 * i + 1].toInt() shl 8) or (b[2 * i].toInt() and 0xff)).toShort().toInt()
    private fun put(b: ByteArray, i: Int, v: Int) { b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }

    /** Cuts a PCM stream into whole [FRAME_BYTES] frames (RTP packets are 20–40 ms; the detector wants 100 ms). */
    class Framer {
        private val buf = ByteArrayOutputStream()
        fun add(pcm: ByteArray): List<ByteArray> {
            buf.write(pcm)
            if (buf.size() < FRAME_BYTES) return emptyList()
            val all = buf.toByteArray(); buf.reset()
            val whole = all.size / FRAME_BYTES * FRAME_BYTES
            buf.write(all, whole, all.size - whole)
            return (0 until whole / FRAME_BYTES).map { all.copyOfRange(it * FRAME_BYTES, (it + 1) * FRAME_BYTES) }
        }
        fun clear() = buf.reset()
    }

    /**
     * Wait before reconnecting after [failures] failed connections in a row: 5 s, 10 s, 20 s … up to 5 minutes.
     * A planned reconnect (end of a segment, or after letting a recording have the camera) waits nothing.
     */
    fun backoffMs(failures: Int): Long = if (failures <= 0) 0L else minOf(5_000L shl minOf(failures - 1, 6), 5 * 60_000L)

    /**
     * A camera microphone runs one step more sensitive than the subscriber asked for. The phone's thresholds
     * (WakeRules.SoundDetector: "normal" needs a 100 ms RMS over 800) come from its own microphone; a Tapo in a
     * quiet room reads RMS 8 and barks peak in the low thousands (DogAudioLevel, 4 Nov 2026), so "normal" could
     * miss a bark across the room. "quiet" (RMS over 300, the same line DogAudioLevel uses) can't trigger on that floor.
     */
    fun sensitivity(asked: String): String = when (asked) { "loud" -> "normal"; else -> "quiet" }

    /** After this many failures in a row the stream doesn't count as held, so the Pet monitor's timed samples come back. */
    const val FAILURES_BEFORE_FALLBACK = 3
}
