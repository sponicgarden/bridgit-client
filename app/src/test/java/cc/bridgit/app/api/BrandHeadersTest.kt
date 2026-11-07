package cc.bridgit.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Every X-Bridgit- header also goes out under its pre-rename X-Bridget- name (API revision 17). */
class BrandHeadersTest {
    @Test fun copiesOnlyOurHeaders() {
        val h = BrandHeaders.withLegacy(linkedMapOf("Content-Type" to "image/jpeg", "X-Bridgit-Width" to "640", "X-Bridgit-Id" to "7"))
        assertEquals(listOf("Content-Type", "X-Bridgit-Width", "X-Bridgit-Id", "X-Bridget-Width", "X-Bridget-Id"), h.keys.toList())
        assertEquals("640", h["X-Bridget-Width"]); assertEquals("7", h["X-Bridget-Id"])
    }

    @Test fun leavesOtherMapsAlone() {
        val plain = mapOf("Content-Disposition" to "attachment")
        assertSame(plain, BrandHeaders.withLegacy(plain))
        assertNull(BrandHeaders.legacyName("X-Request-Id"))
    }
}
