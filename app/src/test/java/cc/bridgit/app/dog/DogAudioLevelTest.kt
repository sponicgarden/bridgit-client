package cc.bridgit.app.dog

import cc.bridgit.app.lancamera.CameraAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class DogAudioLevelTest {
    private val rate = 8000
    private val random = java.util.Random(7)

    /** Five seconds of 16-bit mono PCM from [sample] (seconds → amplitude). */
    private fun pcm(sample: (Double) -> Double): ByteArray {
        val b = ByteBuffer.allocate(rate * 5 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until rate * 5) b.putShort(sample(i.toDouble() / rate).roundToInt().coerceIn(-32768, 32767).toShort())
        return b.array()
    }
    private fun wav(sample: (Double) -> Double) = CameraAudio.wav(pcm(sample), rate)
    private fun hiss(level: Double) = { _: Double -> random.nextGaussian() * level }

    @Test fun quietRoomIsQuietAndNeedsNoModel() {
        val digitalSilence = DogAudioLevel.wavPeak(wav { 0.0 })!!
        val roomHiss = DogAudioLevel.wavPeak(wav(hiss(12.0)))!! // like the A9's Tapo mics: peaks 8–56
        assertEquals(0, digitalSilence)
        assertTrue(roomHiss in 1 until DogAudioLevel.QUIET_PEAK)
        assertTrue(DogAudioLevel.quiet(digitalSilence))
        assertTrue(DogAudioLevel.quiet(roomHiss))
    }

    @Test fun barkWhineAndSoftLappingAllGoToTheModel() {
        val noise = hiss(12.0)
        // Bark: 0.2 s bursts of a 600 Hz tone at about -12 dBFS.
        val bark = DogAudioLevel.wavPeak(wav { t -> noise(t) + if (t % 1.0 < 0.2) 8000 * sin(2 * PI * 600 * t) else 0.0 })!!
        // Whine: a quiet steady 1 kHz tone at about -36 dBFS.
        val whine = DogAudioLevel.wavPeak(wav { t -> noise(t) + 500 * sin(2 * PI * 1000 * t) })!!
        // Lapping: brief soft clicks, four a second, at about -40 dBFS.
        val lapping = DogAudioLevel.wavPeak(wav { t -> noise(t) + if (t % 0.25 < 0.01) 330.0 else 0.0 })!!
        listOf(bark, whine, lapping).forEach { assertFalse("peak $it must reach the model", DogAudioLevel.quiet(it)) }
    }

    @Test fun unknownLevelAlwaysAsksTheModel() {
        assertFalse(DogAudioLevel.quiet(null))
        assertFalse(DogAudioLevel.quiet(-1))
        assertNull(DogAudioLevel.wavPeak(ByteArray(0)))
        assertNull(DogAudioLevel.wavPeak("not a wav file at all".toByteArray()))
        // An m4a/AAC clip (the tablet's own mic) isn't a WAV: its level comes from the recorder instead.
        assertNull(DogAudioLevel.wavPeak(byteArrayOf(0, 0, 0, 24) + "ftypM4A ".toByteArray() + ByteArray(64)))
        // 8-bit or compressed WAV: unknown, not quiet.
        val eightBit = wav { 0.0 }.also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(34, 8) }
        assertNull(DogAudioLevel.wavPeak(eightBit))
    }

    @Test fun builtInMicPeaksUseTheSameThreshold() {
        assertTrue(DogAudioLevel.quiet(0))
        assertTrue(DogAudioLevel.quiet(DogAudioLevel.QUIET_PEAK - 1))
        assertFalse(DogAudioLevel.quiet(DogAudioLevel.QUIET_PEAK))
        assertFalse(DogAudioLevel.quiet(12_000))
    }
}
