package cc.bridgit.app.vision

import cc.bridgit.app.judgment.Jev
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FindRulesTest {
    private fun box(x0: Double, y0: Double, x1: Double, y1: Double) = FindRules.Box(x0, y0, x1, y1)

    @Test fun faceQueriesStayOnThePhoneButAnimalFacesDont() {
        assertTrue(FindRules.isFaceQuery("the face of the individual"))
        assertTrue(FindRules.isFaceQuery("each person's face"))
        assertTrue(FindRules.isFaceQuery("all faces"))
        assertFalse(FindRules.isFaceQuery("the dog's face"))
        assertFalse(FindRules.isFaceQuery("cat face"))
        assertFalse(FindRules.isFaceQuery("the dog"))
        assertFalse(FindRules.isFaceQuery("each dirty dish"))
        assertFalse(FindRules.isFaceQuery("surface of the table")) // whole word only
        assertEquals("on_device", FindRules.route("auto", "faces"))
        assertEquals("cloud", FindRules.route("auto", "the dog"))
        assertEquals("cloud", FindRules.route("cloud", "faces"))
        assertEquals("on_device", FindRules.route("on_device", "the dog")) // refused later with a reason
    }

    @Test fun cropIsTheUnionPlusMargin() {
        // 1000 x 500 photo, box 0.2..0.4 wide (200 px) and 0.2..0.6 tall (200 px), 10% margin = 20 px.
        assertArrayEquals(intArrayOf(180, 80, 420, 320), FindRules.cropRect(listOf(box(.2, .2, .4, .6)), 1000, 500, pad = .1))
        // Two boxes: their union.
        assertArrayEquals(intArrayOf(100, 50, 900, 450), FindRules.cropRect(listOf(box(.1, .1, .3, .3), box(.7, .5, .9, .9)), 1000, 500, pad = 0.0))
    }

    @Test fun cropAtTheEdgeSlidesInsideAndKeepsItsSize() {
        val r = FindRules.cropRect(listOf(box(0.0, 0.0, .2, .2)), 1000, 1000, pad = .5)
        assertArrayEquals(intArrayOf(0, 0, 400, 400), r) // 200 px box + 100 px each side, pushed back from -100
        val whole = FindRules.cropRect(listOf(box(0.0, 0.0, 1.0, 1.0)), 640, 480, pad = .2)
        assertArrayEquals(intArrayOf(0, 0, 640, 480), whole)
    }

    @Test fun tinyBoxesGetAMinimumSideAndAspectIsKept() {
        val r = FindRules.cropRect(listOf(box(.5, .5, .501, .501)), 1000, 1000, pad = 0.0, minSide = 64)
        assertEquals(64, r[2] - r[0]); assertEquals(64, r[3] - r[1])
        val sq = FindRules.cropRect(listOf(box(.1, .4, .9, .6)), 1000, 1000, pad = 0.0, aspect = 1.0)
        assertEquals(sq[2] - sq[0], sq[3] - sq[1])
        assertEquals(800, sq[2] - sq[0])
    }

    @Test fun agentBoxesAreChecked() {
        assertNotNull(FindRules.boxOf(JSONObject("""{"x0":0.1,"y0":0.2,"x1":0.5,"y1":0.9}""")))
        assertNull(FindRules.boxOf(JSONObject("""{"x0":0.5,"y0":0.2,"x1":0.1,"y1":0.9}""")))
        assertNull(FindRules.boxOf(JSONObject("""{"x0":0.1,"y0":0.2,"x1":1.5,"y1":0.9}""")))
        assertNull(FindRules.boxOf(JSONObject("""{"left":0.1}""")))
        assertNull(FindRules.boxOf(null))
    }

    @Test fun locateRepliesParseAndBadBoxesAreDropped() {
        val r = Jev.parseLocated(200, """{"items":[{"label":"dog","box":{"x0":0.2,"y0":0.1,"x1":0.9,"y1":0.6}},
            {"label":"bad","box":{"x0":0.9,"y0":0.1,"x1":0.2,"y1":0.6}},{"label":"nobox"}],"model":"openrouter:google/gemini-3.1-flash-lite","cost_usd":0.0004,"key":"team"}""")
        assertEquals(1, r.items.size)
        assertEquals("dog", r.items[0].label)
        assertEquals(0.9, r.items[0].x1, 1e-9)
        assertEquals(0.0004, r.costUsd, 1e-12)
        val cap = assertThrows(Jev.Failure::class.java) { Jev.parseLocated(429, """{"error":"spend_cap","message":"used up"}""") }
        assertEquals("spend_cap", cap.reason)
        assertEquals("malformed", assertThrows(Jev.Failure::class.java) { Jev.parseLocated(200, "nope") }.reason)
    }

    @Test fun appraiseRepliesKeepTypedItemsAndDropBadBoxes() {
        val r = Jev.parseAppraised(200, """{"room":"pantry","items":[{"label":"peanut butter jar","category":"Food & consumables","new_usd":6,"used_usd":1,"insurable":false,
            "box":{"x0":0.1,"y0":0.1,"x1":0.3,"y1":0.4}},{"label":"bad","box":{"x0":0.9,"y0":0.1,"x1":0.2,"y1":0.6}},{"label":"","box":{"x0":0,"y0":0,"x1":1,"y1":1}}],
            "model":"openrouter:google/gemini-3.8-flash","cost_usd":0.005,"key":"team"}""")
        assertEquals("pantry", r.room)
        assertEquals(1, r.items.size)
        assertEquals(6, r.items[0].getInt("new_usd"))
        assertFalse(r.items[0].getBoolean("insurable"))
        assertEquals(0.005, r.costUsd, 1e-12)
        assertNull(Jev.parseAppraised(200, """{"room":null,"items":[]}""").room)
        assertEquals("spend_cap", assertThrows(Jev.Failure::class.java) { Jev.parseAppraised(429, """{"error":"spend_cap","message":"used up"}""") }.reason)
    }

    @Test fun appletRecordPagesStayUnderTheBudgetButNeverEmpty() {
        fun rec(n: Int) = JSONObject().put("key", "k$n").put("value", JSONObject().put("s", "x".repeat(n)))
        val page = cc.bridgit.app.applets.AppletData.page(listOf(rec(30), rec(30), rec(30)), budget = 180)
        assertEquals(2, page.size)
        assertEquals(1, cc.bridgit.app.applets.AppletData.page(listOf(rec(500)), budget = 80).size)
        assertTrue(cc.bridgit.app.applets.AppletData.KEY.matches("item.k3j9-a_b"))
        assertFalse(cc.bridgit.app.applets.AppletData.KEY.matches("../x"))
        assertFalse(cc.bridgit.app.applets.AppletData.KEY.matches("a/b"))
    }
}
