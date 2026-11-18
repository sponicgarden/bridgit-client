package cc.bridgit.app.email

import cc.bridgit.app.api.ApiError
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EmailPreviewTest {
    private fun refused(body: JSONObject): ApiError = try { EmailPreview.render(body); fail("expected a refusal"); throw AssertionError() } catch (e: ApiError) { e }

    @Test fun markdownRendersIntoTheCardWithTextFallback() {
        val out = EmailPreview.render(JSONObject().put("subject", "Front door").put("markdown", "**Someone** is at the door."))
        assertEquals("card", out.getString("style"))
        assertTrue(out.getString("html").contains("<strong>Someone</strong>"))
        assertTrue(out.getString("html").contains(EmailComposer.TAGLINE.substringBefore(" —")))
        assertTrue(out.getString("text").contains("Someone is at the door."))
        assertFalse(out.getBoolean("sent"))
        assertTrue(out.getInt("html_bytes") > 0)
    }

    @Test fun plainStyleAndSanitisedHtml() {
        val out = EmailPreview.render(JSONObject().put("subject", "Hi").put("style", "plain").put("html", "<p onclick=\"x()\">Hello</p><script>alert(1)</script>"))
        assertEquals("plain", out.getString("style"))
        val html = out.getString("html")
        assertFalse(html.contains("<script", true)); assertFalse(html.contains("onclick", true))
        assertTrue(out.getString("text").contains("Hello"))
    }

    @Test fun recipientsAreCheckedButNotSent() {
        val out = EmailPreview.render(JSONObject().put("subject", "Hi").put("text", "x").put("to", JSONArray(listOf("a@example.com", "A@example.com"))))
        assertEquals(1, out.getJSONArray("to").length())
        assertEquals("to", refused(JSONObject().put("subject", "Hi").put("text", "x").put("to", JSONArray(listOf("nope")))).extra["field"])
    }

    @Test fun badRequestsAreRefusedWithTheField() {
        assertEquals("subject", refused(JSONObject().put("text", "x")).extra["field"])
        assertEquals("body", refused(JSONObject().put("subject", "Hi")).extra["field"])
        assertEquals("style", refused(JSONObject().put("subject", "Hi").put("text", "x").put("style", "fancy")).extra["field"])
        assertEquals("cc", refused(JSONObject().put("subject", "Hi").put("text", "x").put("cc", "a@example.com")).extra["field"])
        assertEquals("text", refused(JSONObject().put("subject", "Hi").put("text", 5)).extra["field"])
        assertEquals("invalid_parameter", refused(JSONObject().put("text", "x")).code)
    }
}
