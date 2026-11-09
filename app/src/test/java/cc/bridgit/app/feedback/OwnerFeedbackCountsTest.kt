package cc.bridgit.app.feedback

import android.app.Application
import cc.bridgit.app.feedback.OwnerFeedbackCounts.Counts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OwnerFeedbackCountsTest {
    private val answer = """{"scope":"owner","owner_linked":true,"device_count":2,
        "totals":{"bug":{"filed":5,"fixed":2},"feature":{"filed":1,"fixed":1},"all":{"filed":6,"fixed":3}},
        "devices":[{"domain":"pixel-pal","label":"Pixel 10","this_device":true,"bug":{"filed":3,"fixed":1},"feature":{"filed":0,"fixed":0}},
                   {"domain":null,"label":"A9 Cream Tab","this_device":false,"bug":{"filed":2,"fixed":1},"feature":{"filed":1,"fixed":1}}]}"""

    @Test fun ownerTotalsAddOtherDevicesToThisDevicesLocalCounts() {
        val owner = OwnerFeedbackCounts.parse(answer)!!
        assertEquals(2, owner.devices.size)
        assertEquals("A9 Cream Tab", owner.devices[1].name)
        // This device has 4 local bugs (one not uploaded yet); the tab adds its 2.
        assertEquals(Counts(6, 2), OwnerFeedbackCounts.total(owner, IssueKind.BUG, Counts(4, 1)))
        assertEquals(Counts(1, 1), OwnerFeedbackCounts.total(owner, IssueKind.FEATURE, Counts(0, 0)))
        assertEquals("pixel-pal 4 · 1, A9 Cream Tab 2 · 1", OwnerFeedbackCounts.breakdown(owner, IssueKind.BUG, Counts(4, 1)))
    }

    @Test fun withoutAnAnswerOrWithOneDeviceItIsTheLocalCount() {
        assertEquals(Counts(3, 1), OwnerFeedbackCounts.total(null, IssueKind.BUG, Counts(3, 1)))
        assertNull(OwnerFeedbackCounts.breakdown(null, IssueKind.BUG, Counts(3, 1)))
        val one = OwnerFeedbackCounts.parse("""{"owner_linked":false,"devices":[{"domain":"lone-tab","label":null,"this_device":true,"bug":{"filed":1,"fixed":0}}]}""")
        assertEquals(Counts(3, 1), OwnerFeedbackCounts.total(one, IssueKind.BUG, Counts(3, 1)))
        assertNull(OwnerFeedbackCounts.breakdown(one, IssueKind.BUG, Counts(3, 1)))
        assertNull(OwnerFeedbackCounts.parse("not json"))
        assertNull(OwnerFeedbackCounts.parse("""{"error":"unauthorized"}"""))
    }
}
