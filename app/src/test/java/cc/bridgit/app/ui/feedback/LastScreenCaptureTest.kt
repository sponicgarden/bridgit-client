package cc.bridgit.app.ui.feedback

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Ported from Spotka's LastScreenCaptureTest (pure parts): usability is injected so the
 * selection contract is tested without decoding bitmaps.
 */
class LastScreenCaptureTest {
    private val earlyBytes = byteArrayOf(1, 2, 3, 4)
    private val laterBytes = byteArrayOf(9, 8, 7, 6)
    private val otherBytes = byteArrayOf(5, 5, 5, 5)

    @Test fun preferEarlyKeepsUsableEarlyEvenWhenLaterAlsoUsable() =
        assertSame(earlyBytes, LastScreenCapture.preferEarlyCapture(earlyBytes, laterBytes) { it != null })

    @Test fun preferEarlyReplacesOnlyWhenEarlyUnusableAndLaterUsable() =
        assertSame(laterBytes, LastScreenCapture.preferEarlyCapture(earlyBytes, laterBytes) { it === laterBytes })

    @Test fun preferEarlyDoesNotReplaceWithUnusableLater() =
        assertSame(earlyBytes, LastScreenCapture.preferEarlyCapture(earlyBytes, laterBytes) { it === earlyBytes })

    @Test fun preferEarlyFallsBackToEarlyWhenBothUnusable() =
        assertSame(earlyBytes, LastScreenCapture.preferEarlyCapture(earlyBytes, laterBytes) { false })

    @Test fun preferEarlyUsesLaterWhenEarlyNullAndLaterUsable() =
        assertSame(laterBytes, LastScreenCapture.preferEarlyCapture(null, laterBytes) { it != null })

    @Test fun preferEarlyReturnsNullWhenBothNull() =
        assertNull(LastScreenCapture.preferEarlyCapture(null, null) { true })

    @Test fun preferEarlyDoesNotPickUnrelatedThirdPayload() =
        assertSame(earlyBytes, LastScreenCapture.preferEarlyCapture(earlyBytes, laterBytes) { it === otherBytes })

    @Test fun selectionUsesPrimaryThenRetryThenTapTime() {
        fun choose(usable: ByteArray?) = LastScreenCapture.selectLastScreen(earlyBytes, laterBytes, otherBytes) { it === usable }
        assertSame(earlyBytes, choose(earlyBytes))
        assertSame(laterBytes, choose(laterBytes))
        assertSame(otherBytes, choose(otherBytes))
    }

    // Bridgit-only (FBK-02): a blank capture is rejected, not kept as a fallback.
    @Test fun selectUsableRejectsWhenNothingPasses() =
        assertNull(LastScreenCapture.selectUsable(earlyBytes, laterBytes) { false })

    @Test fun selectUsablePrefersPrimaryThenRetry() {
        assertSame(earlyBytes, LastScreenCapture.selectUsable(earlyBytes, laterBytes) { it != null })
        assertSame(laterBytes, LastScreenCapture.selectUsable(earlyBytes, laterBytes) { it === laterBytes })
        assertSame(laterBytes, LastScreenCapture.selectUsable(null, laterBytes) { it != null })
    }

    @Test fun selectUsableNullWhenNoCapture() = assertNull(LastScreenCapture.selectUsable(null, null) { true })
}
