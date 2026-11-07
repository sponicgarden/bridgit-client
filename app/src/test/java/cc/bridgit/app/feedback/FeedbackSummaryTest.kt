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
        fun create(kind: ReportKind) = store.create(kind, "Summary test", false, emptyList(), JSONArray(), JSONObject(), "form")
        val fixed = create(ReportKind.BUG)
        val reopened = create(ReportKind.BUG)
        val feature = create(ReportKind.FEATURE)
        for (r in listOf(fixed, reopened, feature)) {
            store.move(r.id, ReportStatus.IN_PROGRESS, Actor.TEAM, "team", null)
            store.move(r.id, ReportStatus.FIXED, Actor.TEAM, "team", null)
        }
        store.move(fixed.id, ReportStatus.CONFIRMED, Actor.OWNER, "owner", null)
        store.move(reopened.id, ReportStatus.REOPENED, Actor.OWNER, "owner", null)
        val declined = create(ReportKind.BUG)
        store.move(declined.id, ReportStatus.WONT_FIX, Actor.TEAM, "team", null)
        store.move(declined.id, ReportStatus.CONFIRMED, Actor.OWNER, "owner", null)
        assertEquals(3 to 1, store.summary(ReportKind.BUG))
        assertEquals(1 to 1, store.summary(ReportKind.FEATURE))
    }
}
