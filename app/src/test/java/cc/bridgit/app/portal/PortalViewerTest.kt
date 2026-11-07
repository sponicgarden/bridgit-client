package cc.bridgit.app.portal

import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

/** This phone as a Bridgit Portal viewer of other phones: saved links, relay answers, chooser text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortalViewerTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val token = "rly_" + "0123abcd-".repeat(8)
    private fun link(device: String = "a4e5f1c5-dd15-42c0", name: String = "Galaxy Tab A9") =
        PortalData.pairLink("https://bridgit-relay.bridgit-app.workers.dev", device, token, name, install = null, portal = true)

    @Test fun `links are saved with the key encrypted, replaced per phone, selected and removed`() {
        PortalLinks.keyOverride = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val links = PortalLinks(ctx)
        assertEquals(0, links.count())
        val l = links.add(link())
        assertEquals("Galaxy Tab A9", l.name); assertEquals(token, l.token); assertTrue(l.portal)
        // The token never sits in the prefs in plain text.
        val raw = ctx.getSharedPreferences("portal_links_v1", 0).getString("links", "")!!
        assertFalse(raw.contains(token))
        links.add(link(name = "Tab A9 renamed"))
        assertEquals(1, links.count()); assertEquals("Tab A9 renamed", links.all().single().name)
        links.add(link(device = "second-phone-1234", name = "Pixel"))
        assertEquals(2, links.count())
        links.select("second-phone-1234")
        assertEquals("Pixel", links.selectedLink()!!.name)
        links.remove("second-phone-1234")
        assertNull(links.selectedLink()); assertNull(links.selected.value)
        links.select("not-paired"); assertNull(links.selected.value)
        try { links.add("https://example.com/#token=x"); fail("not a link") } catch (_: IllegalArgumentException) {}
        try { links.add(PortalData.pairLink("http://evil.example", "a4e5f1c5-dd15-42c0", token, "x")); fail("http relay") } catch (_: IllegalArgumentException) {}
        PortalLinks.keyOverride = null
    }

    @Test fun `relay answers become the other phone's JSON or a plain failure`() {
        val reply = JSONObject().put("device", JSONObject().put("name", "A9"))
        val ok = JSONObject().put("job_id", "j").put("response", JSONObject().put("status", 200).put("content_type", "application/json")
            .put("body_base64", Base64.encodeToString(reply.toString().toByteArray(), Base64.NO_WRAP))).toString()
        assertEquals("A9", PortalClient.decode(200, ok).getJSONObject("device").getString("name"))
        fun failure(code: Int, body: String) = runCatching { PortalClient.decode(code, body) }.exceptionOrNull()
        assertTrue(failure(401, """{"error":"unauthorized"}""") is PortalClient.Failure.Unpaired)
        assertTrue(failure(503, """{"error":"phone_unreachable"}""") is PortalClient.Failure.Offline)
        assertTrue(failure(429, """{"error":"quota_exceeded"}""") is PortalClient.Failure.Busy)
        val refused = JSONObject().put("response", JSONObject().put("status", 403)
            .put("body_base64", Base64.encodeToString("""{"error":"agent_not_allowed","message":"No."}""".toByteArray(), Base64.NO_WRAP))).toString()
        val f = failure(403, refused)
        assertTrue(f is PortalClient.Failure.Refused); assertEquals("agent_not_allowed", (f as PortalClient.Failure.Refused).code)
        val revoked = JSONObject().put("response", JSONObject().put("status", 401)
            .put("body_base64", Base64.encodeToString("""{"error":"agent_revoked"}""".toByteArray(), Base64.NO_WRAP))).toString()
        assertTrue(failure(401, revoked) is PortalClient.Failure.Unpaired)
    }

    @Test fun `only portal reads can be sent`() {
        val l = PortalLinks.Link("a4e5f1c5-dd15-42c0", "https://relay.example", token, "A9", null, true, 0)
        for (path in listOf("/v1/camera/snapshot", "/v1/portal/../adb/shell", "/v2/call", "/v1/lan/discover"))
            assertTrue(path, runCatching { PortalClient.get(l, path) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `chooser lines show model, Android, Bridgit version, battery and freshness`() {
        val home = JSONObject().put("device", JSONObject().put("model", "SM-X210").put("android", "15").put("app_version", "1.8.1786"))
            .put("battery", JSONObject().put("percent", 82).put("charging", true))
        assertEquals("SM-X210 · Android 15 · Bridgit 1.8.1786 · 82% charging", PortalViewer.summary(home))
        assertEquals("", PortalViewer.summary(null))
        val now = 10_000_000L
        assertEquals("just now", PortalViewer.freshness(now - 5_000, now))
        assertEquals("3 min ago", PortalViewer.freshness(now - 180_000, now))
        assertEquals("2 h ago", PortalViewer.freshness(now - 7_200_000, now))
        assertEquals("not reached yet", PortalViewer.freshness(null, now))
    }
}
