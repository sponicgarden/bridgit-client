package cc.bridgit.app.lancamera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalChimeTest {
    private fun samples(pcm: ByteArray) = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { b -> ShortArray(b.remaining()).also { b.get(it) } }

    @Test fun chimeFitsTheTapoTalkLimits() {
        val pcm = PortalChime.pcm()
        assertEquals(0, pcm.size % 2)
        assertTrue("TapoTalk.play accepts 320..480000 bytes", pcm.size in 320..480000)
        assertEquals(0.9, pcm.size / 2.0 / PortalChime.RATE, 0.01)
    }

    @Test fun chimeIsAudibleButSoftAndEndsInSilence() {
        val s = samples(PortalChime.pcm())
        val peak = s.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("audible: $peak", peak > 4000)
        assertTrue("soft, never clipped: $peak", peak < 20000)
        assertTrue("starts quietly", kotlin.math.abs(s.first().toInt()) < 200)
        assertTrue("fades out", s.takeLast(20).all { kotlin.math.abs(it.toInt()) < 200 })
    }
}
