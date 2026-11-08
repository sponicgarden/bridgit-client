package cc.bridgit.app.feedback

import android.app.Application
import org.robolectric.RuntimeEnvironment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FeedbackSummaryTest {
    @Test fun totalsAreSeparatedByKindAndOnlyCurrentFixesCount() {
        val store = FeedbackStore(RuntimeEnvironment.getApplication(), uploadEnabled = { false })
        fun create(kind: IssueKind) = store.create(kind, "Summary test", false, emptyList(), JSONArray(), JSONObject(), "form")
        val fixed = create(IssueKind.BUG)
        val reopened = create(IssueKind.BUG)
        val feature = create(IssueKind.FEATURE)
        for (r in listOf(fixed, reopened, feature)) {
            store.move(r.id, IssueStatus.IN_PROGRESS, Actor.TEAM, "team", null)
            store.move(r.id, IssueStatus.FIXED, Actor.TEAM, "team", null)
        }
        store.move(fixed.id, IssueStatus.CONFIRMED, Actor.OWNER, "owner", null)
        store.move(reopened.id, IssueStatus.REOPENED, Actor.OWNER, "owner", null)
        val declined = create(IssueKind.BUG)
        store.move(declined.id, IssueStatus.WONT_FIX, Actor.TEAM, "team", null)
        store.move(declined.id, IssueStatus.CONFIRMED, Actor.OWNER, "owner", null)
        assertEquals(3 to 1, store.summary(IssueKind.BUG))
        assertEquals(1 to 1, store.summary(IssueKind.FEATURE))
    }
}
