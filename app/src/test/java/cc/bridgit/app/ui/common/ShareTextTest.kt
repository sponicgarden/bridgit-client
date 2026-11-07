package cc.bridgit.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTextTest {
    @Test fun `prompt keeps the agent text unchanged between two dividers`() {
        val prompt = "Bridgit connects you to the owner's Android device.\n\nYOUR KEY\n- abc"
        val text = ShareText.prompt("Bridgit setup for the agent laptop", "Note for the person.", prompt)
        val parts = text.split(ShareText.RULE)
        assertEquals(3, parts.size)
        assertEquals(prompt, parts[1].trim())
        assertTrue(parts[0].startsWith("Bridgit setup for the agent laptop\n────"))
        assertTrue(parts[0].contains("Note for the person.\n\nPaste everything between the two lines into your control agent:"))
        assertTrue(text.endsWith(ShareText.SIGN_OFF))
    }

    @Test fun `title underline matches the title, within limits`() {
        assertEquals("Bridgit setup for the agent laptop\n" + "─".repeat(18), ShareText.title("Bridgit setup for the agent laptop"))
        assertEquals(6, ShareText.title("Hi").substringAfter('\n').length)
        assertTrue("Divider must fit a phone screen", ShareText.RULE.length <= 20)
    }
}
