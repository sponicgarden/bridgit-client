package cc.bridgit.app.vision

import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.vision.PeopleCropRules.Crop
import cc.bridgit.app.vision.PeopleCropRules.Person
import cc.bridgit.app.vision.PeopleCropRules.Rect
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PeopleCropRulesTest {
    private fun req(vararg kv: Pair<String, Any?>) = PeopleCropRules.request { k -> kv.toMap()[k] }
    private fun bad(vararg kv: Pair<String, Any?>) = try { req(*kv); fail("accepted ${kv.toList()}") } catch (e: ApiError) { assertEquals("invalid_parameter", e.code) }
    private fun Crop.contains(r: Rect) = x <= r.left + 1 && y <= r.top + 1 && right >= r.right - 1 && bottom >= r.bottom - 1
    private fun ratio(c: Crop) = c.width.toDouble() / c.height

    @Test fun viewDefaultsToFullAndAspectToBestFit() {
        assertFalse(req().people)
        assertFalse(req("view" to "full").people)
        assertFalse(req("view" to JSONObject.NULL).people)
        val p = req("view" to "people")
        assertTrue(p.people); assertNull(p.aspect); assertEquals("best_fit", p.aspectText); assertEquals(0.08, p.padding, 1e-9)
        assertNull(req("view" to "people", "aspect" to "best_fit").aspect)
        assertNull(req("view" to "people", "aspect" to "best fit").aspect)
    }

    @Test fun aspectReadsRatiosAndNumbers() {
        assertEquals(20.0 / 9, req("view" to "people", "aspect" to "20:9").aspect!!, 1e-9)
        assertEquals("20:9", req("view" to "people", "aspect" to "20:9").aspectText)
        assertEquals(1.0, req("view" to "people", "aspect" to "1:1").aspect!!, 1e-9)
        assertEquals(0.8, req("view" to "people", "aspect" to "4:5").aspect!!, 1e-9)
        assertEquals(1.91, req("view" to "people", "aspect" to "1.91:1").aspect!!, 1e-9)
        assertEquals(1.5, req("view" to "people", "aspect" to 1.5).aspect!!, 1e-9)
        assertEquals(1.5, req("view" to "people", "aspect" to "1.5").aspect!!, 1e-9)
        assertEquals(0.25, req("view" to "people", "padding" to 0.25).padding, 1e-9)
        assertEquals(0.0, req("view" to "people", "padding" to "0").padding, 1e-9)
    }

    @Test fun malformedRequestsAreRefused() {
        bad("view" to "faces"); bad("view" to 3)
        bad("view" to "people", "aspect" to "0:9"); bad("view" to "people", "aspect" to "wide")
        bad("view" to "people", "aspect" to "100:1"); bad("view" to "people", "aspect" to 0)
        bad("view" to "people", "aspect" to true); bad("view" to "people", "padding" to -0.1)
        bad("view" to "people", "padding" to 0.9); bad("view" to "people", "padding" to "lots")
        // aspect and padding only mean something for the people view.
        bad("aspect" to "1:1"); bad("view" to "full", "padding" to 0.1)
    }

    @Test fun bestFitIsThePersonPlusPadding() {
        // 1000 x 2000 frame; a person 200 x 800 at (400, 600). Padding 0.1 of 800 = 80 on every side.
        val c = PeopleCropRules.cropRect(listOf(Rect(400.0, 600.0, 600.0, 1400.0)), 1000, 2000, padding = 0.1)
        assertEquals(Crop(320, 520, 360, 960, null), c)
        // No padding: exactly the person.
        assertEquals(Crop(400, 600, 200, 800, null), PeopleCropRules.cropRect(listOf(Rect(400.0, 600.0, 600.0, 1400.0)), 1000, 2000, padding = 0.0))
    }

    @Test fun paddingStopsAtTheFrameEdge() {
        // Person touching the left and top edges: the padding is only added where there is frame.
        val c = PeopleCropRules.cropRect(listOf(Rect(0.0, 0.0, 300.0, 600.0)), 1080, 2400, padding = 0.1)
        assertEquals(Crop(0, 0, 360, 660, null), c)
        // A box partly outside the frame is clamped to it first.
        val d = PeopleCropRules.cropRect(listOf(Rect(-50.0, 100.0, 200.0, 2600.0)), 1080, 2400, padding = 0.0)
        assertEquals(Crop(0, 100, 200, 2300, null), d)
    }

    @Test fun severalPeopleGetOneCropAroundAllOfThem() {
        val a = Rect(100.0, 500.0, 300.0, 1200.0); val b = Rect(700.0, 400.0, 900.0, 1300.0)
        val c = PeopleCropRules.cropRect(listOf(a, b), 1080, 2400, padding = 0.0)
        assertEquals(Crop(100, 400, 800, 900, null), c)
        assertTrue(c.contains(a) && c.contains(b))
    }

    @Test fun aspectGrowsTheCropInsideTheFrame() {
        val person = Rect(400.0, 600.0, 600.0, 1400.0)
        for ((r, label) in listOf(1.0 to "1:1", 0.8 to "4:5", 9.0 / 16 to "9:16")) {
            val c = PeopleCropRules.cropRect(listOf(person), 1080, 2400, padding = 0.08, aspect = r)
            assertEquals(label, r, ratio(c), 0.01)
            assertEquals(true, c.aspectMet)
            assertTrue("$label holds the person", c.contains(person))
            assertTrue(c.x >= 0 && c.y >= 0 && c.right <= 1080 && c.bottom <= 2400)
        }
    }

    @Test fun aspectNearTheEdgeSlidesBackInsideTheFrame() {
        // Person in the bottom-right corner, asked for 1:1: the square slides inside and still holds the person.
        val person = Rect(900.0, 2000.0, 1080.0, 2400.0)
        val c = PeopleCropRules.cropRect(listOf(person), 1080, 2400, padding = 0.1, aspect = 1.0)
        assertEquals(1.0, ratio(c), 0.01)
        assertEquals(1080, c.right); assertEquals(2400, c.bottom)
        assertTrue(c.contains(person))
    }

    @Test fun aspectTooWideForTheFrameShrinksPaddingFirst() {
        // A 1080 x 2400 portrait screen, a 20:9 card. The person (800 x 400) plus padding would need more than the
        // frame's width, but a 1080 x 486 crop still holds them: the shape is met, the padding gives way.
        val person = Rect(140.0, 1000.0, 940.0, 1400.0)
        val c = PeopleCropRules.cropRect(listOf(person), 1080, 2400, padding = 0.2, aspect = 20.0 / 9)
        assertEquals(true, c.aspectMet)
        assertEquals(20.0 / 9, ratio(c), 0.01)
        assertEquals(1080, c.width)
        assertTrue(c.contains(person))
    }

    @Test fun aspectThatWouldCutAPersonIsNotMet() {
        // A standing person 300 x 1800 on a portrait screen, asked for 20:9: no 20:9 rectangle inside the frame holds
        // them, so the crop keeps the whole person (and padding), spans the full width and says aspect_met false.
        val person = Rect(400.0, 300.0, 700.0, 2100.0)
        val c = PeopleCropRules.cropRect(listOf(person), 1080, 2400, padding = 0.05, aspect = 20.0 / 9)
        assertEquals(false, c.aspectMet)
        assertTrue(c.contains(person))
        assertEquals(1080, c.width)
        assertTrue(ratio(c) < 20.0 / 9)
    }

    @Test fun bodyBoxCoversTheWholeBodyNotJustTheFace() {
        // Pose points (id, x, y, likelihood) of a standing person in a 1000 x 2000 frame: face 0-10, shoulders, hips, ankles.
        val pts = mutableListOf<DoubleArray>()
        for (i in 0..10) pts += doubleArrayOf(i.toDouble(), 480.0 + (i % 5) * 10, 300.0 + (i / 5) * 15, 0.99)
        pts += doubleArrayOf(11.0, 400.0, 420.0, 0.99); pts += doubleArrayOf(12.0, 600.0, 420.0, 0.99)
        pts += doubleArrayOf(23.0, 430.0, 900.0, 0.95); pts += doubleArrayOf(24.0, 570.0, 900.0, 0.95)
        pts += doubleArrayOf(27.0, 440.0, 1600.0, 0.9); pts += doubleArrayOf(28.0, 560.0, 1600.0, 0.9)
        pts += doubleArrayOf(31.0, 300.0, 1900.0, 0.1) // not in frame: ignored
        val b = PeopleCropRules.bodyBox(pts, 1000, 2000)!!
        assertTrue("top of the head is above the eyes", b.top < 300.0)
        assertTrue("reaches the feet", b.bottom >= 1600.0)
        assertTrue("wider than the shoulders", b.left < 400.0 && b.right > 600.0)
        assertTrue("the low-likelihood point is left out", b.bottom < 1900.0 && b.left > 300.0)
        assertTrue("much taller than a face", b.height > 5 * 40)
    }

    @Test fun tooFewPosePointsIsNobody() {
        assertNull(PeopleCropRules.bodyBox(listOf(doubleArrayOf(0.0, 10.0, 10.0, 0.9), doubleArrayOf(11.0, 20.0, 20.0, 0.9)), 100, 100))
        assertNull(PeopleCropRules.bodyBox((0..32).map { doubleArrayOf(it.toDouble(), 50.0, 50.0, 0.2) }, 100, 100))
    }

    @Test fun aFaceWithoutABodyBecomesHeadAndShoulders() {
        val face = Rect(500.0, 400.0, 600.0, 520.0)
        val b = PeopleCropRules.bodyFromFace(face, 1080, 2400)
        assertTrue(b.contains(face))
        assertTrue(b.height > 3 * face.height && b.width >= 3 * face.width - 1)
        assertTrue(PeopleCropRules.covered(face, listOf(Rect(400.0, 300.0, 700.0, 1500.0))))
        assertFalse(PeopleCropRules.covered(face, listOf(Rect(0.0, 0.0, 300.0, 300.0))))
        val area = PeopleCropRules.searchAround(face, 1080, 2400)
        assertTrue(area.contains(face) && area.bottom > face.bottom + 5 * face.height)
    }

    @Test fun duplicatesAreDropped() {
        val a = Rect(100.0, 100.0, 300.0, 700.0)
        assertTrue(PeopleCropRules.duplicate(Rect(110.0, 110.0, 310.0, 690.0), listOf(a)))
        assertFalse(PeopleCropRules.duplicate(Rect(600.0, 100.0, 800.0, 700.0), listOf(a)))
    }

    @Test fun replyStatesSizeShapeAndCropBox() {
        val r = req("view" to "people", "aspect" to "20:9")
        val crop = Crop(0, 900, 1080, 486, true)
        val o = PeopleCropRules.json(r, crop, 1080, 2400, listOf(Person(Rect(140.0, 1000.0, 940.0, 1400.0), "pose")), 1080, 486, PeopleView.DETECTOR)
        assertEquals("people", o.getString("view"))
        assertEquals(900, o.getJSONObject("crop").getInt("y")); assertEquals(486, o.getJSONObject("crop").getInt("height"))
        assertEquals(2400, o.getJSONObject("source").getInt("height_px"))
        assertEquals("20:9", o.getString("aspect_ratio")); assertEquals("20:9", o.getString("aspect_requested"))
        assertTrue(o.getBoolean("aspect_met")); assertEquals(1, o.getInt("people_count"))
        assertEquals(800, o.getJSONArray("people").getJSONObject(0).getInt("width"))
        val h = PeopleCropRules.headers(o)
        assertEquals("0,900,1080,486", h["X-Bridgit-Crop"]); assertEquals("1080x2400", h["X-Bridgit-Source-Size"]); assertEquals("1", h["X-Bridgit-People-Count"])
        // Best fit: the actual shape, reduced.
        assertEquals("3:4", PeopleCropRules.aspectText(600, 800, null, "best_fit"))
        val b = PeopleCropRules.json(req("view" to "people"), Crop(1, 2, 600, 800, null), 1000, 1000, emptyList(), 600, 800, "x")
        assertTrue(b.isNull("aspect_met")); assertEquals("best_fit", b.getString("aspect_requested"))
    }

    @Test fun noPersonFoundIsACatalogError() {
        assertEquals(422, ErrorCatalog.http("no_person_found"))
        assertNotNull(ErrorCatalog.all.firstOrNull { it.code == "no_person_found" })
    }
}
