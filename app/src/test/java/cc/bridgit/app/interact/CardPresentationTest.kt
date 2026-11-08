package cc.bridgit.app.interact

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A0074–A0076: the `presentation` and `content_aspect` fields of an interaction post, the canvas a card gets
 * (viewport and aspect mismatch), and the fit-to-viewport decision that keeps a fixed-size full-screen page whole.
 */
class CardPresentationTest {
    private fun card(extra: JSONObject.() -> Unit = {}) = JSONObject().put("kind", "html").put("title", "Slides").put("html", "<p>hi</p>").apply(extra)
    private fun rejected(b: JSONObject, contains: String? = null) = try { InteractionRules.parse(b); fail("accepted $b") } catch (e: InteractionError) {
        assertEquals("invalid_parameter", e.code); contains?.let { assertTrue(e.message, e.message!!.contains(it)) }
    }

    @Test fun presentationDefaultsToStandardAndAcceptsFullScreenForHtml() {
        assertEquals(Presentation.STANDARD, InteractionRules.parse(card()).presentation)
        assertEquals(Presentation.STANDARD, InteractionRules.parse(card { put("presentation", JSONObject.NULL) }).presentation)
        assertEquals(Presentation.STANDARD, InteractionRules.parse(card { put("presentation", "standard") }).presentation)
        assertEquals(Presentation.FULL_SCREEN, InteractionRules.parse(card { put("presentation", "full_screen") }).presentation)
    }

    @Test fun unknownOrMistypedPresentationIsInvalidParameter() {
        for (v in listOf("fullscreen", "FULL_SCREEN", "", "immersive", 1, true, JSONObject())) rejected(card { put("presentation", v) }, "presentation")
    }

    @Test fun fullScreenIsOnlyForHtmlCards() {
        val question = JSONObject().put("kind", "question").put("title", "Pay?").put("presentation", "full_screen")
            .put("controls", org.json.JSONArray().put(JSONObject().put("type", "button").put("id", "y").put("label", "Yes")))
        rejected(question, "kind html")
        rejected(JSONObject().put("kind", "notice").put("title", "Hi").put("presentation", "full_screen"), "kind html")
        // standard stays valid everywhere
        assertEquals(Presentation.STANDARD, InteractionRules.parse(JSONObject().put("kind", "notice").put("title", "Hi").put("presentation", "standard")).presentation)
        // a pieces card has a strict field list
        rejected(JSONObject().put("kind", "pieces").put("title", "P").put("presentation", "full_screen"))
    }

    @Test fun contentAspectParsesAndValidates() {
        val d = InteractionRules.parse(card { put("content_aspect", "16:9") })
        assertEquals(16.0 / 9, d.contentAspect!!, 1e-9)
        assertEquals("16:9", d.contentAspectLabel)
        assertEquals(1280.0 / 720, InteractionRules.parse(card { put("content_aspect", "1280x720") }).contentAspect!!, 1e-9)
        assertEquals(4.0 / 3, CardViewport.parseAspect(" 4 / 3 ")!!, 1e-9)
        for (v in listOf("wide", "16:", "0:9", "16:0", "-16:9", 16, "1:100")) rejected(card { put("content_aspect", v) }, "content_aspect")
        rejected(JSONObject().put("kind", "notice").put("title", "Hi").put("content_aspect", "16:9"), "kind html")
        assertNull(InteractionRules.parse(card()).contentAspect)
    }

    @Test fun readsCarryThePresentation() {
        val i = Interaction(1, "a", "A", IKind.HTML, "t", null, null, emptyList(), "<p/>", "normal", false, IStatus.PENDING, null, 0, 1,
            null, null, null, presentation = Presentation.FULL_SCREEN)
        assertEquals("full_screen", i.toJson { it.toString() }.getString("presentation"))
        assertEquals("standard", i.copy(presentation = Presentation.STANDARD).toJson { it.toString() }.getString("presentation"))
    }

    private val pixel = DisplayGeometry(1344, 2992, 486, 3.0f, 0, cutoutLongPx = 0, statusBarPx = 141, navBarPx = 72, refreshHz = 120)

    @Test fun aspectRatiosReduceToFamiliarLabels() {
        assertEquals("20:9", CardViewport.aspectLabel(1344, 2992))
        assertEquals("20:9", CardViewport.aspectLabel(2992, 1344))
        assertEquals("20:9", CardViewport.aspectLabel(1080, 2400))
        assertEquals("16:9", CardViewport.aspectLabel(1280, 720))
        assertEquals("4:3", CardViewport.aspectLabel(1600, 1200))
        assertEquals("1:1", CardViewport.aspectLabel(500, 500))
        assertEquals("9:16", CardViewport.orientedLabel(720, 1280))
    }

    @Test fun fullScreenCanvasIsTheDisplayLessTheCutoutInBothOrientations() {
        val (p, l) = CardViewport.fullScreen(pixel)
        assertEquals(Px(1344, 2992), p); assertEquals(Px(2992, 1344), l)
        val notched = pixel.copy(cutoutLongPx = 130)
        assertEquals(Px(1344, 2862) to Px(2862, 1344), CardViewport.fullScreen(notched))
        assertEquals(Px(2862, 1344), CardViewport.fullScreenNow(notched.copy(widthPx = 2992, heightPx = 1344, rotation = 90)))
    }

    @Test fun standardCanvasIsSmallerThanFullScreen() {
        val s = CardViewport.standardEstimate(pixel)
        assertEquals(1344 - 2 * 24 - 2 * 3, s.w)
        assertEquals(2992 - 141 - 72 - 168 - 192 - 24 - 6, s.h)
        val v = CardViewport.viewportJson(s, pixel.density)
        assertEquals(s.w, v.getInt("width_px")); assertEquals(Math.round(s.w / 3.0).toInt(), v.getInt("width_dp")); assertEquals(3.0, v.getDouble("density"), 0.0)
    }

    @Test fun aspectMismatchOver10PercentIsFlagged() {
        val portrait = Px(1344, 2992)
        val landscape = Px(2992, 1344)
        val m = CardViewport.mismatchJson(16.0 / 9, "16:9", portrait, Presentation.FULL_SCREEN)
        assertNotNull(m)
        assertEquals("16:9", m!!.getString("content"))
        assertTrue(m.getString("viewport").contains("1344x2992"))
        assertTrue(m.getString("advice").contains("1344x2992"))
        // 16:9 content on a 20:9 landscape canvas is 20 % off; 2.1 vs 2.226 is under 10 %
        assertNotNull(CardViewport.mismatchJson(16.0 / 9, "16:9", landscape, Presentation.STANDARD))
        assertNull(CardViewport.mismatchJson(2.1, "2.1:1", landscape, Presentation.FULL_SCREEN))
        assertNull(CardViewport.mismatchJson(null, null, landscape, Presentation.FULL_SCREEN))
        assertFalse(CardViewport.mismatched(2992.0 / 1344, landscape))
    }

    @Test fun pageViewportMetaGivesDeclaredSizeAndResponsiveness() {
        val fixed = "<html><head><meta name=\"viewport\" content=\"width=1280, height=720\"></head><body></body></html>"
        assertEquals(Px(1280, 720), CardViewport.declaredSize(fixed))
        assertFalse(CardViewport.responsive(fixed))
        val responsive = "<meta content='width=device-width, initial-scale=1' name='viewport'>"
        assertTrue(CardViewport.responsive(responsive))
        assertNull(CardViewport.declaredSize(responsive))
        assertNull(CardViewport.declaredSize("<div style='width:1280px'>"))
        assertNull(CardViewport.viewportMeta("<meta name=\"description\" content=\"width=1280, height=720\">"))
    }

    @Test fun fitToViewportOnlyForFullScreenPagesThatArentResponsive() {
        val fixed = "<div style=\"width:1280px;height:720px\">slide</div>"
        val responsive = "<meta name=\"viewport\" content=\"width=device-width\"><p>hi</p>"
        assertTrue(CardRules.fitToViewport(fixed, fullScreen = true))
        assertFalse(CardRules.fitToViewport(fixed, fullScreen = false))
        assertFalse(CardRules.fitToViewport(responsive, fullScreen = true))
        assertFalse(CardRules.fitToViewport(null, fullScreen = true))
    }

    @Test fun fitScriptIsAddedOnlyWhenFittingAndTheCspStaysFirst() {
        val html = "<div style=\"width:1280px\">x</div>"
        val standard = CardRules.prepare(html)
        assertFalse(standard.contains("__bridgitFit"))
        assertEquals(standard, CardRules.prepare(html, fit = false))
        val full = CardRules.prepare(html, fit = true)
        assertTrue(full.contains(CardRules.FIT_SCRIPT))
        assertTrue(full.indexOf("Content-Security-Policy") < full.indexOf("__bridgitFit"))
        assertTrue(full.indexOf(CardRules.GUARD) < full.indexOf("__bridgitFit"))
        // The fit script is display-only: no network, storage or bridge.
        for (word in listOf("fetch", "XMLHttpRequest", "localStorage", "Bridgit.", "requestFullscreen", "http")) assertFalse(word, CardRules.FIT_SCRIPT.contains(word))
    }

    @Test fun displayJsonDescribesBothCanvases() {
        val o = CardViewport.displayJson(pixel, null)
        assertEquals("20:9", o.getString("aspect_ratio")); assertEquals("portrait", o.getString("orientation")); assertEquals(0, o.getInt("rotation"))
        assertEquals(2992, o.getJSONObject("full_screen_card").getJSONObject("landscape").getInt("width_px"))
        assertFalse(o.getJSONObject("standard_card").getBoolean("measured"))
        assertTrue(CardViewport.displayJson(pixel, Px(1290, 2300)).getJSONObject("standard_card").getBoolean("measured"))
        assertEquals("/v1/device/display", CardViewport.healthJson(pixel).getString("details"))
    }
}
