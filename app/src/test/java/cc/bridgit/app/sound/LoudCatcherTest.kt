package cc.bridgit.app.sound

import cc.bridgit.app.listen.WakeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LoudCatcherTest {
    /** 100 ms of 16 kHz PCM: a tone of [amp], or near-silence (a Tapo in a quiet room decodes to about ±8). */
    private fun frame(amp: Double, seed: Int = 0): ByteArray = ByteArray(3200).also { b ->
        for (i in 0 until 1600) { val v = if (amp == 0.0) (if ((i + seed) % 2 == 0) 8 else -8) else (amp * sin(2 * PI * 600 * i / 16000.0)).toInt()
            b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
    }

    private fun run(c: LoudCatcher, frames: List<ByteArray>, t0: Long = 1_000_000L): List<Pair<Int, LoudCatcher.Clip>> =
        frames.mapIndexedNotNull { i, f -> c.feed(f, WakeRules.rms(f, f.size), t0 + (i + 1) * 100L)?.let { i to it } }

    @Test fun aBarkOnAQuietCameraBecomesOneClipWithItsStart() {
        val c = LoudCatcher("pet_monitor", "normal")
        val quiet = List(30) { frame(0.0, it) }
        val bark = List(6) { frame(6000.0) }
        val out = run(c, quiet + bark + List(15) { frame(0.0, it) })
        assertEquals(1, out.size)
        val (_, clip) = out[0]
        assertTrue(clip.wav.size > 44)
        // the clip starts before the bark: up to 4 quiet frames, then the bark, then the second of quiet that ended it
        val frames = (clip.wav.size - 44) / 3200
        assertTrue("frames=$frames", frames in 6 + 10..4 + 6 + 11)
        assertNotNull(SoundPrint.of(clip.wav))
        assertTrue(clip.durationMs in 600L..1_800L)
        assertEquals(1_000_000L + 30 * 100L, clip.at) // the first loud frame (index 30) began here
    }

    @Test fun quietHissAndAShortClickAreNotLoudSounds() {
        val c = LoudCatcher("pet_monitor", "normal")
        assertTrue(run(c, List(100) { frame(0.0, it) }).isEmpty())
        assertTrue(run(c, listOf(frame(8000.0)) + List(20) { frame(0.0, it) }).isEmpty()) // one frame: a click, not a sound
    }

    @Test fun cameraSensitivityHearsASofterBarkButNotTheTapoFloor() {
        val soft = List(30) { frame(0.0, it) } + List(6) { frame(600.0) } + List(15) { frame(0.0, it) } // RMS ~420
        assertTrue(run(LoudCatcher("p", "normal"), soft).isEmpty())
        assertEquals(1, run(LoudCatcher("p", "quiet"), soft).size)
        assertTrue(run(LoudCatcher("p", "quiet"), List(300) { frame(0.0, it) }).isEmpty())
    }

    @Test fun anInterruptedSoundGivesNoHalfClipAndTheNextOneStillCounts() {
        val c = LoudCatcher("pet_monitor", "normal")
        run(c, List(20) { frame(0.0, it) } + List(3) { frame(6000.0) })
        c.interrupted()
        assertTrue(run(c, List(15) { frame(0.0, it) }).isEmpty())
        // the detector's own 30 s gap still applies; after it, a new bark is caught
        val out = run(c, List(300) { frame(0.0, it) } + List(5) { frame(6000.0) } + List(12) { frame(0.0, it) })
        assertEquals(1, out.size)
        assertNull(out.firstOrNull { it.second.wav.size <= 44 })
    }
}
