package cc.bridgit.app.ui.feedback

import cc.bridgit.app.feedback.Issue
import cc.bridgit.app.feedback.IssueKind
import cc.bridgit.app.feedback.IssueStatus

/**
 * My reports filter (A0405: one type per pill group). The kind group (All · Bugs · Features) and the
 * status group (Open) are separate; the list shows reports that match both. `kind == null` is All.
 * "Open" means anything not yet confirmed closed.
 */
data class IssueFilter(val kind: IssueKind?, val openOnly: Boolean) {
    fun apply(all: List<Issue>): List<Issue> =
        all.filter { (kind == null || it.kind == kind) && (!openOnly || it.status != IssueStatus.CONFIRMED) }

    /** True when either group narrows the list. */
    val narrowed get() = kind != null || openOnly
}
