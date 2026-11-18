package cc.bridgit.app.ui.interactions

import cc.bridgit.app.ui.interactions.InteractionFilter.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A0405: the inbox's kind group and status group are separate and combine. */
class InteractionFilterTest {
    private data class Row(val id: Int, val question: Boolean, val waiting: Boolean)

    private val rows = listOf(Row(1, true, true), Row(2, true, false), Row(3, false, true), Row(4, false, false))
    private fun ids(f: InteractionFilter) = f.apply(rows, { it.question }, { it.waiting }).map { it.id }

    @Test fun allShowsEverything() {
        assertEquals(listOf(1, 2, 3, 4), ids(InteractionFilter(Kind.ALL, false)))
        assertFalse(InteractionFilter(Kind.ALL, false).narrowed)
    }

    @Test fun questionsAndOpenCombine() = assertEquals(listOf(1), ids(InteractionFilter(Kind.QUESTIONS, true)))

    @Test fun noticesAnyStatus() = assertEquals(listOf(3, 4), ids(InteractionFilter(Kind.NOTICES, false)))

    @Test fun openAcrossKinds() {
        assertEquals(listOf(1, 3), ids(InteractionFilter(Kind.ALL, true)))
        assertTrue(InteractionFilter(Kind.ALL, true).narrowed)
    }
}
