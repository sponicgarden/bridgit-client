package cc.bridgit.app.ui.feedback

import cc.bridgit.app.feedback.Issue
import cc.bridgit.app.feedback.IssueKind
import cc.bridgit.app.feedback.IssueStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A0405: My reports' kind group and status group are separate and combine. */
class IssueFilterTest {
    private fun issue(id: Long, kind: IssueKind, status: IssueStatus) = Issue(
        id = id, kind = kind, description = "x", noAutofix = false, status = status,
        createdAt = 0, updatedAt = 0, captureSource = "form", agentId = null, agent = null,
        context = JSONObject(), log = JSONArray(), images = emptyList(), lastBy = null)

    private val openBug = issue(1, IssueKind.BUG, IssueStatus.NEW)
    private val closedBug = issue(2, IssueKind.BUG, IssueStatus.CONFIRMED)
    private val openFeature = issue(3, IssueKind.FEATURE, IssueStatus.IN_PROGRESS)
    private val closedFeature = issue(4, IssueKind.FEATURE, IssueStatus.CONFIRMED)
    private val all = listOf(openBug, closedBug, openFeature, closedFeature)

    private fun ids(f: IssueFilter) = f.apply(all).map { it.id }

    @Test fun allAndAnyStatusShowsEverything() {
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(IssueFilter(null, false)))
        assertFalse(IssueFilter(null, false).narrowed)
    }

    @Test fun bugsOnly() = assertEquals(listOf(1L, 2L), ids(IssueFilter(IssueKind.BUG, false)))

    @Test fun openAcrossKinds() = assertEquals(listOf(1L, 3L), ids(IssueFilter(null, true)))

    @Test fun featuresAndOpenCombine() {
        assertEquals(listOf(3L), ids(IssueFilter(IssueKind.FEATURE, true)))
        assertTrue(IssueFilter(IssueKind.FEATURE, true).narrowed)
    }

    @Test fun bugsAndOpenCombine() = assertEquals(listOf(1L), ids(IssueFilter(IssueKind.BUG, true)))
}
