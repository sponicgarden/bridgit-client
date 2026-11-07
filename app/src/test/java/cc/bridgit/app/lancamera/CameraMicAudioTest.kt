package cc.bridgit.app.lancamera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CameraMicAudioTest {
    private fun pcm(vararg s: Int): ByteArray = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { s.forEach { putShort(it.toShort()) } }.array()
    private fun samples(b: ByteArray): List<Int> { val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); return List(b.size / 2) { bb.short.toInt() } }

    @Test fun joinPassesContinuousPacketsThrough() {
        val j = CameraMicAudio.Join(8000)
        assertArrayEquals(pcm(1, 2, 3), j.add(7, 1000, pcm(1, 2, 3)))
        assertArrayEquals(pcm(4, 5), j.add(7, 1003, pcm(4, 5)))
        assertEquals(0, j.gaps); assertEquals(0, j.resets)
    }

    @Test fun joinFillsAShortGapWithSilenceAndTrimsAnOverlap() {
        val j = CameraMicAudio.Join(8000, jitterMs = 0)
        j.add(7, 0, pcm(1, 1))
        assertEquals(listOf(0, 0, 0, 9), samples(j.add(7, 5, pcm(9)))) // 3 samples lost → 3 silent samples
        assertEquals(listOf(6), samples(j.add(7, 5, pcm(5, 6)))) // repeats sample 5: only the new one
        assertEquals(2, j.gaps); assertEquals(0, j.resets)
        val k = CameraMicAudio.Join(8000) // 20 ms jitter allowance = 160 samples
        k.add(7, 0, ByteArray(1120))
        assertEquals(400 * 2 + 4, k.add(7, 560 + 400, pcm(1, 2)).size) // 50 ms lost: filled
    }

    @Test fun joinTreatsTapoTimestampJitterAsContinuousAudio() {
        // A C210's 560-sample packets, stamped 523, 1163, 1643, 2283 … (±40 around the real timeline)
        val j = CameraMicAudio.Join(8000)
        val stamps = listOf(0L, 523, 1163, 1643, 2283, 2763, 3403, 3883)
        val out = stamps.sumOf { j.add(7, it, ByteArray(1120)).size }
        assertEquals(stamps.size * 1120, out) // nothing inserted, nothing cut
        assertEquals(0, j.gaps); assertEquals(0, j.resets)
    }

    @Test fun joinRestartsAfterALongGapANewSourceOrTimestampWrap() {
        val j = CameraMicAudio.Join(8000, maxGapMs = 250)
        j.add(7, 0, pcm(1))
        assertArrayEquals(pcm(2), j.add(7, 1 + 2001, pcm(2))) // 250 ms is 2000 samples: longer restarts, no silence
        assertArrayEquals(pcm(3), j.add(8, 50, pcm(3))) // new SSRC
        assertEquals(2, j.resets)
        val w = CameraMicAudio.Join(8000, jitterMs = 0)
        w.add(1, 0xffffffffL, pcm(1, 2)) // wraps to 1
        assertEquals(listOf(0, 5), samples(w.add(1, 2, pcm(5))))
    }

    @Test fun upsamplesEightKilohertzByInterpolation() {
        assertEquals(listOf(0, 50, 100, 50, 0, 0), samples(CameraMicAudio.to16k(pcm(0, 100, 0), 8000)))
        val same = pcm(1, 2, 3)
        assertArrayEquals(same, CameraMicAudio.to16k(same, 16000))
        assertEquals(listOf(-32768, -32768), samples(CameraMicAudio.to16k(pcm(-32768), 8000)))
    }

    @Test fun framerCutsWholeHundredMillisecondFrames() {
        val f = CameraMicAudio.Framer()
        assertEquals(3200, CameraMicAudio.FRAME_BYTES)
        assertEquals(0, f.add(ByteArray(1280)).size) // one 40 ms packet at 16 kHz
        assertEquals(0, f.add(ByteArray(1280)).size)
        val out = f.add(ByteArray(1280) { 1 } + ByteArray(6400) { 2 })
        assertEquals(3, out.size)
        assertEquals(1, out[0][2560].toInt()); assertEquals(1, out[1][639].toInt()); assertEquals(2, out[1][640].toInt())
        assertEquals(1, f.add(ByteArray(2560)).size) // the 640-byte rest carried over
    }

    @Test fun cameraMicrophonesRunOneStepMoreSensitive() {
        assertEquals(listOf("quiet", "quiet", "normal"), listOf("quiet", "normal", "loud").map { CameraMicAudio.sensitivity(it) })
    }

    @Test fun backoffDoublesToFiveMinutes() {
        assertEquals(listOf(0L, 5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L),
            (0..8).map { CameraMicAudio.backoffMs(it) })
        assertEquals(300_000L, CameraMicAudio.backoffMs(1000))
    }
}
