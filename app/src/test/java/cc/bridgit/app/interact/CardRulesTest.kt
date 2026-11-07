package cc.bridgit.app.interact

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * CAP-34 HTML cards, the pure half of the sandbox checklist:
 * the forced CSP is first in the page and contains the four required directives; no page meta can set
 * http-equiv (CSP, refresh); html over 256 KB is refused as payload_too_large; data:text/html, SVG data URLs
 * and frame/object/embed/base tags are refused (also when hidden with case, spaces or character references);
 * Bridgit.respond accepts one strict-JSON answer up to 16 KB and ignores everything after it.
 */
class CardRulesTest {
    private fun card(html: Any?, extra: JSONObject.() -> Unit = {}) = JSONObject().put("kind", "html").put("title", "Pick a slot")
        .apply { if (html != null) put("html", html) }.apply(extra)
    private fun code(b: JSONObject): String? = try { InteractionRules.parse(b); null } catch (e: InteractionError) { e.code }

    @Test fun cspIsForcedFirst() {
        CardRules.CSP_REQUIRED.forEach { assertTrue("CSP lacks $it", it in CardRules.CSP) }
        val out = CardRules.prepare("<p>hi</p>")
        val csp = out.indexOf("Content-Security-Policy")
        assertTrue(csp in 0 until out.indexOf("<p>hi"))
        assertTrue(out.startsWith("<!DOCTYPE html><html><head><meta http-equiv=\"Content-Security-Policy\""))
        assertTrue(out.contains("content=\"${CardRules.CSP}\""))
        // Exactly one real http-equiv remains: Bridgit's own.
        assertEquals(1, Regex("http-equiv", RegexOption.IGNORE_CASE).findAll(out).count())
    }

    @Test fun guardRunsBeforeThePage() {
        val out = CardRules.prepare("<script>new RTCPeerConnection()</script>")
        val guard = out.indexOf("<script>" + CardRules.GUARD + "</script>")
        assertTrue(guard > out.indexOf("Content-Security-Policy"))
        assertTrue(guard < out.indexOf("new RTCPeerConnection"))
        assertTrue("RTCPeerConnection" in CardRules.GUARD && "contentWindow" in CardRules.GUARD)
        assertFalse("</script" in CardRules.GUARD.lowercase())
    }

    @Test fun respondShimRunsAfterGuardAndBeforeThePage() {
        val out = CardRules.prepare("<script>Bridget.respond({button:'go'})</script>")
        val guard = out.indexOf("<script>" + CardRules.GUARD + "</script>")
        val shim = out.indexOf("<script>" + CardRules.RESPOND_SHIM + "</script>")
        assertTrue(shim > guard && guard >= 0)
        assertTrue(shim < out.indexOf("Bridget.respond({button"))
        assertFalse("</script" in CardRules.RESPOND_SHIM.lowercase())
        // Objects are serialized before they reach the native String parameter (bug report #0006).
        assertTrue("JSON.stringify(v)" in CardRules.RESPOND_SHIM && "'Bridgit'" in CardRules.RESPOND_SHIM)
    }

    @Test fun pageMetaCantSetPolicyOrRefresh() {
        for (evil in listOf(
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src *\">",
            "<META HTTP-EQUIV=refresh content=\"0;url=https://evil.example\">",
            "<meta content=\"a>b\" Http-Equiv=\"refresh\">",
        )) {
            val out = CardRules.prepare(evil)
            assertEquals(evil, 1, Regex("http-equiv", RegexOption.IGNORE_CASE).findAll(out).count())
            assertTrue(out.contains("data-blocked-equiv", ignoreCase = true))
        }
    }

    @Test fun htmlValidation() {
        val d = InteractionRules.parse(card("<button onclick=\"Bridgit.respond('{\\\"slot\\\":1}')\">10:00</button><img src=\"data:image/png;base64,iVBOR\">"))
        assertEquals(IKind.HTML, d.kind)
        assertNotNull(d.html)
        assertEquals("invalid_parameter", code(card(null)))
        assertEquals("invalid_parameter", code(card(42)))
        assertEquals("invalid_parameter", code(card("   ")))
        assertEquals("invalid_parameter", code(card("<p>x</p>") { put("controls", JSONArray().put(JSONObject().put("type", "button").put("id", "a").put("label", "A"))) }))
        assertEquals("invalid_parameter", code(card("<p>x</p>") { put("image", JSONObject().put("data_base64", "AAAA")) }))
        assertEquals("invalid_parameter", code(JSONObject().put("kind", "notice").put("title", "x").put("html", "<p>x</p>")))
        assertEquals("invalid_parameter", code(card("<p>x</p>") { remove("title") }))
    }

    @Test fun sizeIsCheckedFirst() {
        val big = "<p>" + "x".repeat(CardRules.HTML_MAX_BYTES) + "</p>"
        assertEquals("payload_too_large", code(card(big)))
        // Multi-byte characters count as bytes, not characters.
        assertEquals("payload_too_large", code(card("é".repeat(CardRules.HTML_MAX_BYTES / 2 + 1))))
        // Size wins even over a forbidden payload.
        assertEquals("payload_too_large", code(card("data:text/html," + "x".repeat(CardRules.HTML_MAX_BYTES))))
        assertNull(code(card("x".repeat(CardRules.HTML_MAX_BYTES))))
    }

    @Test fun forbiddenEmbedsAreRefused() {
        for (evil in listOf(
            "<a href=\"data:text/html,<script>alert(1)</script>\">x</a>",
            "<img src=\"DATA:Text/HTML;base64,PHNjcmlwdD4=\">",
            "<img src=\"data :\ttext/html,x\">",
            "<img src=\"data&#58;text/html,x\">",
            "<img src=\"data&#x3A;text&#x2F;html,x\">",
            "<img src=\"data&colon;text/html,x\">",
            "<img src=\"data:image/svg+xml;base64,PHN2Zz4=\">",
            "<img src=\"data:application/xhtml+xml,x\">",
            "<iframe srcdoc=\"<p>x</p>\"></iframe>",
            "<IFRAME src=x>", "<object data=x>", "<embed src=x>", "<base href=\"https://evil.example/\">", "<frameset>",
        )) assertEquals(evil, "invalid_parameter", code(card(evil)))
        // Pictures and ordinary words are fine.
        for (ok in listOf("<img src=\"data:image/jpeg;base64,/9j/\">", "<img src='data:image/webp;base64,UklG'>", "<p>Metadata: none. Data: 5</p>"))
            assertNull(ok, code(card(ok)))
    }

    @Test fun respondParsing() {
        assertEquals(1, (CardRules.parseRespond("{\"slot\":1}") as JSONObject).getInt("slot"))
        assertEquals(2, (CardRules.parseRespond(" [1,2] ") as JSONArray).length())
        assertEquals("yes", CardRules.parseRespond("\"yes\""))
        assertEquals(true, CardRules.parseRespond("true"))
        assertEquals(JSONObject.NULL, CardRules.parseRespond("null"))
        assertNotNull(CardRules.parseRespond("3.5"))
        for (bad in listOf(null, "", "   ", "yes", "tru", "{\"a\":1} trailing", "{\"a\":", "{\"a\":1}}", "[" + "[".repeat(70) + "]".repeat(71)))
            assertNull("accepted $bad", CardRules.parseRespond(bad))
        // 16 KB limit, in bytes.
        val ok = "\"" + "x".repeat(CardRules.RESPOND_MAX_BYTES - 2) + "\""
        assertNotNull(CardRules.parseRespond(ok))
        assertNull(CardRules.parseRespond("\"" + "x".repeat(CardRules.RESPOND_MAX_BYTES - 1) + "\""))
        assertNull(CardRules.parseRespond("\"" + "é".repeat(CardRules.RESPOND_MAX_BYTES / 2) + "\""))
        // Lenient JSONTokener extras are refused, and can't hide nesting from the depth check (Codex review).
        for (lenient in listOf("[/*\"*/" + "[".repeat(100) + "0" + "]".repeat(101), "{'a':1}", "[1;2]", "{a:1}", "[1] // c", "#x\n1"))
            assertNull("accepted $lenient", CardRules.parseRespond(lenient))
        assertEquals(1, (CardRules.parseRespond("{\"note\":\"it's /* fine */ in a string ['\"}") as JSONObject).length())
        // A 16 KB bracket bomb is refused without parsing (no stack overflow).
        assertNull(CardRules.parseRespond("[".repeat(CardRules.RESPOND_MAX_BYTES)))
    }

    @Test fun respondIsAcceptedOnce() {
        val g = RespondGate()
        assertFalse(g.accepted)
        assertNull(g.offer("not json"))            // a refused call doesn't use the slot
        assertNull(g.offer("\"" + "x".repeat(20_000) + "\""))
        assertFalse(g.accepted)
        assertEquals(1, (g.offer("{\"slot\":1}") as JSONObject).getInt("slot"))
        assertTrue(g.accepted)
        assertNull(g.offer("{\"slot\":2}"))        // later calls ignored
        assertNull(g.offer("{\"slot\":1}"))
    }
}
