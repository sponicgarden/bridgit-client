package cc.bridgit.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeCheckTest {
    private val n = FrameDiff.SIZE
    private fun grid(f: (Int) -> Int) = IntArray(n * n) { f(it).coerceIn(0, 255) }
    private val room = grid { (it * 37) % 200 + 20 }
    /** A bright w x h block at (x0, y0) on [base]. */
    private fun block(x0: Int, y0: Int, w: Int, h: Int, base: IntArray = room) =
        grid { i -> if (i % n in x0 until x0 + w && i / n in y0 until y0 + h) 250 else base[i] }

    @Test fun gateReusesTheAnswerUntilThePictureChanges() {
        val g = ChangeGate<String>()
        assertNull(g.reusable(room, 0, "clef")) // nothing asked yet
        g.answered(room, 0, "clef", "empty") { a, b -> a == b }
        assertEquals("empty", g.reusable(room, 10_000, "clef"))
        assertNull(g.reusable(room, 10_000, "jev")) // a different model never reuses Clef's answer
        assertNull(g.reusable(block(0, 0, 10, 10), 10_000, "clef")) // someone walked in
        assertNull(g.reusable(room, FrameDiff.MAX_REUSE_MS, "clef")) // asked again after max reuse
        assertNull(g.reusable(null, 10_000, "clef")) // a photo that wouldn't decode
    }

    @Test fun turnedOffMeansEveryPhotoIsAsked() {
        val g = ChangeGate<String>(ChangeSettings(enabled = false))
        g.answered(room, 0, "clef", "empty") { a, b -> a == b }
        assertNull(g.reusable(room, 10_000, "clef"))
    }

    @Test fun sensitivityPresetsMoveTheBar() {
        val twitch = block(4, 20, 4, 3) // ~1.2% of the picture
        val smallDog = block(4, 20, 6, 5) // ~2.9%
        val big = block(0, 0, 8, 8) // ~6.3%
        fun reused(g: IntArray, s: String) = FrameDiff.reuse(g, room, 10_000, settings = ChangeSettings(sensitivity = s))
        assertTrue(reused(twitch, "normal")); assertFalse(reused(twitch, "high"))
        assertFalse(reused(smallDog, "normal")); assertTrue(reused(smallDog, "low"))
        assertFalse(reused(big, "low"))
        assertEquals(setOf("low", "normal", "high"), ChangeSettings.PRESETS.keys)
    }

    @Test fun maxReuseIsTheAgentsChoice() {
        val s = ChangeSettings(maxReuseMs = 60_000)
        assertTrue(FrameDiff.reuse(room, room, 59_000, settings = s))
        assertFalse(FrameDiff.reuse(room, room, 60_000, settings = s))
    }

    @Test fun sameAnswerTeachesTheMaskOnlyWhenLearningIsOn() {
        fun tv(level: Int) = grid { i -> if (i % n in 24..31 && i / n in 0..5) level else room[i] }
        val learning = ChangeGate<String>()
        learning.answered(tv(10), 0, "clef", "empty") { a, b -> a == b }
        assertNull(learning.reusable(tv(240), 10_000, "clef")) // TV flicker: paid
        assertTrue(learning.answered(tv(240), 10_000, "clef", "empty") { a, b -> a == b }) // same answer: learn
        assertEquals("empty", learning.reusable(tv(10), 20_000, "clef")) // next flicker: free
        assertNotNull(learning.changeShare(tv(10), 20_000))

        val strict = ChangeGate<String>(ChangeSettings(learnNoise = false))
        strict.answered(tv(10), 0, "clef", "empty") { a, b -> a == b }
        assertFalse(strict.answered(tv(240), 10_000, "clef", "empty") { a, b -> a == b })
        assertNull(strict.reusable(tv(10), 20_000, "clef"))

        // A different answer is never noise.
        val g = ChangeGate<String>()
        g.answered(tv(10), 0, "clef", "empty") { a, b -> a == b }
        assertFalse(g.answered(tv(240), 10_000, "clef", "dog") { a, b -> a == b })
    }

    @Test fun describeListsPresetsAndDefaults() {
        val d = ChangeSettings.describe()
        assertEquals(25, d.getJSONObject("sensitivity").getJSONObject("normal").getInt("cell_change_of_255"))
        assertTrue(d.getJSONObject("default").getBoolean("change_check"))
        assertEquals(300, d.getJSONObject("default").getInt("max_reuse_s"))
    }

    @Test fun onlyANearBlackPhotoCountsAsDark() {
        val n = FrameDiff.SIZE * FrameDiff.SIZE
        assertTrue(FrameDiff.dark(IntArray(n)))
        assertTrue(FrameDiff.dark(IntArray(n) { 4 + (it * 13) % 17 }))  // A9 at night: average ~12, brightest 20
        assertFalse(FrameDiff.dark(IntArray(n) { 30 }))                   // a dim but exposed room
        assertFalse(FrameDiff.dark(IntArray(n) { if (it % FrameDiff.SIZE < 4) 120 else 5 }))  // dark, but a lamp shows
        assertFalse(FrameDiff.dark(IntArray(0)))
    }
}
