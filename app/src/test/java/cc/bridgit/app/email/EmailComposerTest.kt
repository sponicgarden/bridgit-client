package cc.bridgit.app.email

import cc.bridgit.app.email.EmailComposer.Result
import cc.bridgit.app.email.EmailComposer.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailComposerTest {
    private fun ok(r: Result) = (r as? Result.Ok)?.mail ?: error("expected Ok, got $r")
    private fun bad(r: Result) = (r as? Result.Bad)?.error ?: error("expected Bad, got $r")

    @Test fun validationErrors() {
        assertEquals("subject", bad(EmailComposer.compose("", text = "hi")).field)
        assertEquals("subject", bad(EmailComposer.compose(null, text = "hi")).field)
        assertEquals("subject", bad(EmailComposer.compose("x".repeat(201), text = "hi")).field)
        assertEquals("subject", bad(EmailComposer.compose("a\r\nBcc: x@y.com", text = "hi")).field)
        assertEquals("body", bad(EmailComposer.compose("Hi")).field)
        assertEquals("body", bad(EmailComposer.compose("Hi", text = "   ")).field)
        assertEquals("html", bad(EmailComposer.compose("Hi", html = "a".repeat(100 * 1024 + 1))).field)
        assertEquals("body", bad(EmailComposer.compose("Hi", html = "<script>x()</script>")).field)
        assertTrue(ok(EmailComposer.compose("x".repeat(200), text = "hi")).subject.length == 200)
    }

    @Test fun recipients() {
        assertTrue(EmailComposer.validateRecipients(emptyList()) is EmailComposer.Recipients.Bad)
        assertTrue(EmailComposer.validateRecipients(listOf("not-an-address")) is EmailComposer.Recipients.Bad)
        assertTrue(EmailComposer.validateRecipients(listOf("a@b")) is EmailComposer.Recipients.Bad)
        assertTrue(EmailComposer.validateRecipients(listOf("a..b@c.com")) is EmailComposer.Recipients.Bad)
        assertTrue(EmailComposer.validateRecipients(listOf("a b@c.com")) is EmailComposer.Recipients.Bad)
        assertTrue(EmailComposer.validateRecipients(listOf("a@c.com\r\nBcc: z@z.com")) is EmailComposer.Recipients.Bad)
        assertTrue(EmailComposer.validateRecipients((1..21).map { "u$it@example.com" }) is EmailComposer.Recipients.Bad)
        assertTrue(EmailComposer.validateRecipients((1..20).map { "u$it@example.com" }) is EmailComposer.Recipients.Ok)
        val r = EmailComposer.validateRecipients(listOf(" Sam@Example.com ", "sam@example.com", "t.o+x@mail.example.org")) as EmailComposer.Recipients.Ok
        assertEquals(listOf("Sam@Example.com", "t.o+x@mail.example.org"), r.addresses)
    }

    @Test fun cardContainsTextAndBrandFrame() {
        val m = ok(EmailComposer.compose("Door camera", text = "Nothing moved.\n\nSee https://bridgit.cc/x for more & more."))
        assertEquals(Style.CARD, m.style)
        assertTrue(m.html.contains("Nothing moved."))
        assertTrue(m.html.contains("bridgit"))
        assertTrue(m.html.contains(EmailComposer.TAGLINE))
        assertTrue(m.html.contains("<h1"))
        assertTrue(m.html.contains("<a href=\"https://bridgit.cc/x\""))
        assertTrue(m.html.contains("more &amp; more"))
        assertFalse(m.html.contains("<script"))
        assertTrue(m.text.startsWith("Nothing moved."))
        assertTrue(m.text.contains("more & more"))
    }

    @Test fun textIsEscaped() {
        val m = ok(EmailComposer.compose("<b>x</b>", text = "<img src=x onerror=alert(1)> & \"q\""))
        assertFalse(m.html.contains("<img"))
        assertTrue(m.html.contains("&lt;img src=x onerror=alert(1)&gt; &amp; &quot;q&quot;"))
        assertTrue(m.html.contains("&lt;b&gt;x&lt;/b&gt;"))
    }

    @Test fun markdownRenders() {
        val m = ok(EmailComposer.compose("Status", markdown = "# Heading\n\nSome **bold**, *it* and `code` with [a link](https://example.com/a).\n\n- one\n- two\n\n1. first\n2. second"))
        assertTrue(m.html.contains("<strong>bold</strong>"))
        assertTrue(m.html.contains("<em>it</em>"))
        assertTrue(m.html.contains("<code"))
        assertTrue(m.html.contains("<a href=\"https://example.com/a\""))
        assertTrue(m.html.contains("<ul") && m.html.contains("<ol"))
        assertEquals(2, Regex("<h1").findAll(m.html).count().let { it }) // the subject heading and the markdown one
        assertTrue(m.text.contains("Heading"))
        assertTrue(m.text.contains("a link (https://example.com/a)"))
        assertTrue(m.text.contains("- one"))
        assertFalse(m.text.contains("**"))
        assertFalse(m.text.contains("#"))
    }

    @Test fun markdownRejectsScriptLinks() {
        val m = ok(EmailComposer.compose("S", markdown = "[x](javascript:alert(1)) <script>alert(1)</script>"))
        assertFalse(m.html.contains("href=\"javascript", ignoreCase = true))
        assertFalse(m.html.contains("<a "))
        assertFalse(m.html.contains("<script"))
    }

    @Test fun sanitizerStripsDangerousHtml() {
        val dirty = "<p onclick=\"x()\" style=\"color:red;background:url(http://evil/x);position:fixed\">Hi" +
            "<script>alert(1)</script><iframe src=\"https://e.com\">inner</iframe>" +
            "<form action=\"/x\"><input name=a><button>Go</button></form>" +
            "<a href=\"javascript:alert(1)\">bad</a> <a href=\" JaVaScRiPt:alert(1)\">bad2</a> <a href=\"https://ok.example/p\" onmouseover=\"x\">good</a>" +
            "<img src=\"http://insecure/x.png\"><img src=\"https://ok.example/i.png\" onerror=\"x()\" alt=\"pic\">" +
            "<style>@import url(https://e.com/f.css); p{color:red}</style><link rel=stylesheet href=https://e.com/x.css>" +
            "<!-- <script>x</script> --><svg onload=alert(1)><circle/></svg></p>"
        val m = ok(EmailComposer.compose("S", html = dirty))
        val body = m.html.substringAfter("<h1").substringAfter("</h1>")
        for (bad in listOf("<script", "<iframe", "<form", "<input", "<button", "onclick", "onerror", "onmouseover", "onload", "javascript", "JaVaScRiPt", "url(", "position", "@import", "<style", "<link", "<svg", "http://insecure", "evil"))
            assertFalse("$bad survived in: $body", body.contains(bad, ignoreCase = true))
        assertTrue(body.contains("color:red"))
        assertTrue(body.contains("<a href=\"https://ok.example/p\">good</a>"))
        assertTrue(body.contains("<img src=\"https://ok.example/i.png\" alt=\"pic\">"))
        assertTrue(body.contains("Hi"))
        assertTrue(body.contains("bad"))
    }

    @Test fun sanitizerBalancesTagsAndHandlesBrokenInput() {
        assertEquals("<p><b>x</b></p>", EmailComposer.sanitizeHtml("<p><b>x"))
        assertEquals("a &lt; b", EmailComposer.sanitizeHtml("a < b"))
        assertEquals("x", EmailComposer.sanitizeHtml("x</div></p>"))
        assertEquals("&lt;b&gt;", EmailComposer.sanitizeHtml("&lt;b&gt;"))
        assertEquals("Hi", EmailComposer.sanitizeHtml("Hi<script>never closed alert(1)"))
        assertEquals("<p>ok</p>", EmailComposer.sanitizeHtml("<p>ok</p><SCRIPT SRC=//evil></SCRIPT>"))
    }

    @Test fun htmlGetsTextFallback() {
        val m = ok(EmailComposer.compose("S", html = "<h2>Report</h2><p>Hello &amp; welcome,<br>team</p><ul><li>one</li><li>two</li></ul><p>See <a href=\"https://example.com/r\">the report</a>.</p><script>x()</script>"))
        for (piece in listOf("Report", "Hello & welcome,\nteam", "- one", "- two", "See the report (https://example.com/r).")) assertTrue("$piece missing in: ${m.text}", m.text.contains(piece))
        assertFalse(m.text.contains("<"))
        assertFalse(m.text.contains("x()"))
    }

    @Test fun htmlWinsOverMarkdownOverText() {
        assertTrue(ok(EmailComposer.compose("S", text = "T", markdown = "M", html = "<p>H</p>")).text == "H")
        assertTrue(ok(EmailComposer.compose("S", text = "T", markdown = "M")).text == "M")
    }

    @Test fun plainStyleHasNoBrandFrame() {
        val m = ok(EmailComposer.compose("S", html = "<p>Mine</p>", style = Style.PLAIN))
        assertEquals(Style.PLAIN, m.style)
        assertTrue(m.html.contains("<p>Mine</p>"))
        assertFalse(m.html.contains(EmailComposer.TAGLINE))
        assertFalse(m.html.contains("<h1"))
        assertEquals("Mine", m.text)
    }

    @Test fun cardUsesInlineCssAndTablesOnly() {
        val m = ok(EmailComposer.compose("S", text = "Hello"))
        assertTrue(m.html.contains("<table"))
        assertTrue(m.html.contains("color-scheme"))
        assertFalse(m.html.contains("<style"))
        assertFalse(m.html.contains("<script"))
        assertFalse(m.html.contains("http://"))
        assertFalse(Regex("(src|href)=\"https?://").containsMatchIn(m.html))
    }

    @Test fun styleParsing() {
        assertEquals(Style.CARD, Style.of("card"))
        assertEquals(Style.PLAIN, Style.of("plain"))
        assertEquals(null, Style.of("fancy"))
        assertEquals(null, Style.of(null))
    }
}
