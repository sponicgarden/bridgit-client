package cc.bridgit.app.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OnDeviceVisionTest {
    @Test fun phrases() {
        assertEquals("dog", OnDeviceVision.phrase("the dog"))
        assertEquals("dog's face", OnDeviceVision.phrase("the dog's face"))
        assertEquals("dirty dish", OnDeviceVision.phrase("each dirty dish"))
        assertEquals("faces", OnDeviceVision.phrase("the face of each person"))
        assertEquals("cat", OnDeviceVision.phrase("Each cat."))
        assertEquals("remote control", OnDeviceVision.phrase("each remote control"))
        assertEquals("red mugs", OnDeviceVision.phrase("find all the red mugs"))
        assertEquals("lids", OnDeviceVision.phrase("the lid on every pot"))
        assertEquals("the", OnDeviceVision.phrase("the"))
    }

    @Test fun plurals() {
        assertEquals("dishes", OnDeviceVision.plural("dish"))
        assertEquals("puppies", OnDeviceVision.plural("puppy"))
        assertEquals("toys", OnDeviceVision.plural("toy"))
        assertEquals("boxes", OnDeviceVision.plural("box"))
        assertEquals("glasses", OnDeviceVision.plural("glass"))
        assertEquals("cats", OnDeviceVision.plural("cats"))
        assertEquals("human faces", OnDeviceVision.plural("human face"))
    }

    @Test fun parsesLabelsAndBoxes() {
        val loc0 = OnDeviceVision.LOC0
        val toks = listOf(100L, 101L, loc0 + 212, loc0 + 225, loc0 + 871, loc0 + 895, loc0 + 10, loc0 + 20, loc0 + 30, loc0 + 40, 102L, loc0 + 1, loc0 + 2, loc0 + 3)
        val out = OnDeviceVision.parse(toks, loc0) { ids -> ids.joinToString(",") }
        assertEquals(2, out.size)
        assertEquals("100,101", out[0].first); assertArrayEquals(doubleArrayOf(0.2125, 0.2255, 0.8715, 0.8955), out[0].second, 1e-9)
        assertEquals("100,101", out[1].first); assertArrayEquals(doubleArrayOf(0.0105, 0.0205, 0.0305, 0.0405), out[1].second, 1e-9)
    }
}
