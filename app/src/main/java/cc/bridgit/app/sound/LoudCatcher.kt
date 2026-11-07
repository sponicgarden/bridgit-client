package cc.bridgit.app.sound

import cc.bridgit.app.listen.WakeRules
import java.io.ByteArrayOutputStream

/**
 * One subscriber's loudness detector on a stream of 100 ms, 16 kHz mono PCM frames (the same rules as the phone's
 * listener: [WakeRules.SoundDetector], a clip from 500 ms before the sound until it ends). Used for camera
 * microphones; the detector's quiet-floor estimate lives here, so it survives reconnects. Pure.
 */
class LoudCatcher(val id: String, val sensitivity: String) {
    class Clip(val wav: ByteArray, val durationMs: Long, val at: Long)

    private val detector = WakeRules.SoundDetector(sensitivity)
    private val preroll = ArrayDeque<ByteArray>()
    private var pcm: ByteArrayOutputStream? = null
    private var at = 0L

    /** [now] is when this frame ended. Returns the clip when a loud sound just ended. */
    fun feed(frame: ByteArray, rms: Double, now: Long): Clip? {
        var done: Clip? = null
        when (detector.feed(rms)) {
            WakeRules.SoundDetector.Ev.START -> {
                // Like the phone's listener: the two loud frames that started it, plus 300 ms before.
                at = now - 200
                pcm = ByteArrayOutputStream().apply { preroll.forEach { write(it) }; write(frame) }
            }
            WakeRules.SoundDetector.Ev.END -> {
                val p = pcm?.toByteArray(); pcm = null
                if (p != null && p.isNotEmpty()) done = Clip(WakeRules.wav(p), detector.durationMs, at)
            }
            null -> pcm?.write(frame)
        }
        preroll.addLast(frame)
        while (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
        return done
    }

    /** A dropped connection: the sound in progress is lost (never half a clip), the quiet floor is kept. */
    fun interrupted() { pcm = null; preroll.clear() }

    companion object { const val PREROLL_FRAMES = 4 }
}
