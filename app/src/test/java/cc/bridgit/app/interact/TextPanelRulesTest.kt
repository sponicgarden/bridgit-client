package cc.bridgit.app.interact

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D26 live text panel: kind=text parsing (title required, no controls, no html, text seeds the panel);
 * the 16 KB per-append limit counted in bytes; the 200 KB tail kept, cut at a line break when possible
 * and never inside a surrogate pair.
 */
class TextPanelRulesTest {
    private fun bad(b: JSONObject) = try { InteractionRules.parse(b); fail("accepted $b") } catch (_: InteractionError) {}

    @Test fun parse() {
        val d = InteractionRules.parse(JSONObject().put("kind", "text").put("title", "Claude session").put("text", "Starting…"))
        assertEquals(IKind.TEXT, d.kind)
        assertEquals("Starting…", d.text)
        assertNull(d.html)
        assertNull(InteractionRules.parse(JSONObject().put("kind", "text").put("title", "Log")).text)
        bad(JSONObject().put("kind", "text"))
        bad(JSONObject().put("kind", "text").put("title", "x").put("html", "<p>"))
        bad(JSONObject().put("kind", "text").put("title", "x").put("controls", JSONArray().put(JSONObject().put("type", "button").put("id", "a").put("label", "A"))))
        bad(JSONObject().put("kind", "text").put("title", "x").put("image", JSONObject().put("data_base64", "AAAA")))
        bad(JSONObject().put("kind", "stream").put("title", "x"))
    }

    @Test fun appendLimitInBytes() {
        assertNull(TextPanelRules.checkAppend("x".repeat(TextPanelRules.APPEND_MAX_BYTES)))
        assertNotNull(TextPanelRules.checkAppend("x".repeat(TextPanelRules.APPEND_MAX_BYTES + 1)))
        assertNotNull(TextPanelRules.checkAppend("é".repeat(TextPanelRules.APPEND_MAX_BYTES / 2 + 1)))
    }

    @Test fun keepTail() {
        assertEquals("short", TextPanelRules.keepTail("short", 10))
        assertEquals("0123456789", TextPanelRules.keepTail("0123456789", 10))
        // No line break: plain cut from the front.
        assertEquals("cdefghijkl", TextPanelRules.keepTail("abcdefghijkl", 10))
        // A line break in the kept part: start just after it.
        assertEquals("line3\nline4", TextPanelRules.keepTail("line1\nline2\nline3\nline4", 14))
        // A break exactly at the cut point.
        assertEquals("bbbb\ncc", TextPanelRules.keepTail("aaaa\nbbbb\ncc", 7))
        // A line break too far ahead: cut mid-line rather than drop most of the text.
        assertEquals(5000, TextPanelRules.keepTail("a".repeat(100) + "x".repeat(4999) + "\nz", 5000).length)
        // A break only as the very last character: keep the plain cut rather than an empty panel.
        assertEquals("bcdef\n", TextPanelRules.keepTail("abcdef\n", 6))
        // Never starts with half of a surrogate pair.
        val emoji = "a" + "😀".repeat(5)
        val t = TextPanelRules.keepTail(emoji, 5)
        assertTrue(!Character.isLowSurrogate(t[0]))
        // Real size: 300 KB of lines comes back at most 200 KB, ending with the newest text.
        val big = (1..30_000).joinToString("") { "line $it\n" }
        val kept = TextPanelRules.keepTail(big)
        assertTrue(kept.length <= TextPanelRules.KEEP_CHARS)
        assertTrue(kept.startsWith("line "))
        assertTrue(kept.endsWith("line 30000\n"))
    }
}
