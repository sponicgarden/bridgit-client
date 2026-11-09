package cc.bridgit.app.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A0151: the friendly-name rules the app applies before asking the backend, and the display rule. Pure. */
class FriendlyNameTest {
    @Test fun parseAcceptsAnyStyleAndTidiesSpaces() {
        assertEquals("Cracky Pixel", FriendlyName.parse("  Cracky   Pixel "))
        assertEquals("Küche 🍳", FriendlyName.parse("Küche 🍳"))
        assertEquals("x".repeat(40), FriendlyName.parse("x".repeat(40)))
    }

    @Test fun parseRefusesEmptyLongAndControlCharacters() {
        for (bad in listOf(null, "", "   ", "x".repeat(41), "bell\u0007", "tab\u007f")) assertNull(bad, FriendlyName.parse(bad))
    }

    @Test fun displayShowsTheFriendlyNameAndTheDomainOnlyWhenNeeded() {
        assertEquals("Cracky", FriendlyName.display("Cracky", "pixel-pal"))
        assertEquals("pixel-pal", FriendlyName.display(null, "pixel-pal"))
        assertEquals("pixel-pal", FriendlyName.display("  ", "pixel-pal"))
        assertEquals("Cracky (pixel-pal)", FriendlyName.display("Cracky", "pixel-pal", confusable = true))
        assertEquals("Cracky", FriendlyName.display("Cracky", null, confusable = true))
        assertEquals("", FriendlyName.display(null, null))
    }

    @Test fun confusableFlagsOnlyRepeatedFriendlyNamesIgnoringCase() {
        assertEquals(listOf(true, true, false, false), FriendlyName.confusable(listOf("Cracky", "cracky", "Pal", null)))
        assertEquals(listOf(false, false), FriendlyName.confusable(listOf("A", "B")))
    }
}
