package cc.bridgit.app.ui.interactions

import cc.bridgit.app.ui.common.Fmt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerTextTest {
    @Test fun cardAnswerReadsAsLines() {
        val r = AnswerText.of(JSONObject("""{"button":"yes","values":{},"step":2}"""))
        assertEquals("yes", r.button)
        assertEquals(listOf("Step: 2"), r.lines)
    }

    @Test fun valuesAndNestedFields() {
        val r = AnswerText.of(JSONObject("""{"values":{"whatIsIt":"a green cup","ok":true},"tags":["a","b"],"meta":{"try_count":3}}"""))
        assertNull(r.button)
        // org.json does not keep key order on the JVM, so compare as a set.
        assertEquals(setOf("What is it: a green cup", "Ok: yes", "Tags: a, b", "Meta, try count: 3"), r.lines.toSet())
    }

    @Test fun plainValue() {
        assertEquals(listOf("done"), AnswerText.of("done").lines)
    }

    @Test fun shortAge() {
        val now = 1_000_000_000_000L
        assertEquals("now", Fmt.agoShort(now - 20_000, now))
        assertEquals("2m", Fmt.agoShort(now - 2 * 60_000, now))
        assertEquals("3h", Fmt.agoShort(now - 3 * 3_600_000L, now))
        assertEquals("5d", Fmt.agoShort(now - 5 * 86_400_000L, now))
    }

    @Test fun compactAgo() {
        val now = 1_000_000_000_000L
        assertEquals("0m ago", Fmt.ago(now - 20_000, now))
        assertEquals("1m ago", Fmt.ago(now - 60_000, now))
        assertEquals("3h ago", Fmt.ago(now - 3 * 3_600_000L, now))
        assertEquals("5d ago", Fmt.ago(now - 5 * 86_400_000L, now))
        assertEquals("never", Fmt.ago(0, now))
        assertEquals("Up 3m", Fmt.uptime(3 * 60_000L))
        assertEquals("Up 1h 5m", Fmt.uptime(65 * 60_000L))
    }
}
