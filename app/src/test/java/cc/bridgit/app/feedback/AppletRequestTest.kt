package cc.bridgit.app.feedback

import cc.bridgit.app.ui.feedback.AppletForm
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A0178: applet requests, the third issue kind, and the fields they carry (same rules as the team's backend). */
class AppletRequestTest {
    private fun refused(kind: IssueKind, raw: JSONObject) {
        try { AppletRequest.normalize(kind, raw); fail("accepted $raw") } catch (e: FeedbackError) { assertEquals("invalid_parameter", e.code) }
    }

    @Test fun kindIsParsedAndHeld() {
        assertEquals(IssueKind.APPLET, IssueKind.of("applet"))
        assertNull(IssueKind.of("portal"))
        assertEquals("bug, feature or applet", IssueKind.names)
        assertTrue(IssueKind.APPLET.alwaysHeld)
        assertTrue(IssueKind.FEATURE.alwaysHeld)
        assertFalse(IssueKind.BUG.alwaysHeld)
    }

    @Test fun fieldsAreTrimmedAndEmptyOnesDropped() {
        val out = AppletRequest.normalize(IssueKind.APPLET, JSONObject()
            .put("name", "  Doorstep  ").put("what_it_does", "").put("devices", JSONArray(listOf("A11 tablet", " ")))
            .put("data_used", JSONObject.NULL))!!
        assertEquals("Doorstep", out.getString("name"))
        assertEquals(1, out.getJSONArray("devices").length())
        assertFalse(out.has("what_it_does"))
        assertFalse(out.has("data_used"))
        assertNull(AppletRequest.normalize(IssueKind.APPLET, JSONObject().put("name", " ")))
        assertNull(AppletRequest.normalize(IssueKind.APPLET, null))
    }

    @Test fun badFieldsAreRefused() {
        refused(IssueKind.BUG, JSONObject().put("name", "x"))
        refused(IssueKind.APPLET, JSONObject().put("nmae", "x"))
        refused(IssueKind.APPLET, JSONObject().put("name", "n".repeat(121)))
        refused(IssueKind.APPLET, JSONObject().put("devices", "A11"))
        refused(IssueKind.APPLET, JSONObject().put("devices", JSONArray((1..11).map { "d$it" })))
        refused(IssueKind.APPLET, JSONObject().put("data_used", JSONArray(listOf(5))))
    }

    @Test fun theFormBuildsTheObject() {
        val a = AppletForm.build("Greets visitors", mapOf(AppletForm.NAME to "Doorstep", AppletForm.DEVICES to "A11 tablet, Pixel 2 XL,",
            AppletForm.DATA to "", AppletForm.PORTAL to " Today's visitors "))
        assertEquals("Greets visitors", a.getString("what_it_does"))
        assertEquals(2, a.getJSONArray("devices").length())
        assertFalse(a.has("data_used"))
        assertEquals("Today's visitors", a.getString("portal_shows"))
    }
}
