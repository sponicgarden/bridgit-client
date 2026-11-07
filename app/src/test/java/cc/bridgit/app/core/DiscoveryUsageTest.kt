package cc.bridgit.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiscoveryUsageTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val key = "discovery:v1:request:catalog"
    @Before fun reset() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.filesDir, "usage_tallies.json").delete()
    }
    @Test fun optOutDeletesPendingBatchAndPreventsNewCounts() {
        val usage = Usage(context)
        usage.count(key)
        val cleared = usage.batchForSend()
        usage.setEnabled(false)
        usage.count(key)
        assertTrue(usage.pending().isEmpty())
        val reloaded = Usage(context)
        assertFalse(reloaded.enabled())
        assertTrue(reloaded.pending().isEmpty())
        reloaded.setEnabled(true)
        reloaded.count(key)
        assertEquals(mapOf(key to 1), reloaded.pending())
        reloaded.acknowledge(cleared.first, cleared.second, System.currentTimeMillis())
        assertEquals(mapOf(key to 1), reloaded.pending())
    }
    @Test fun lostReplyRetryFreezesPayloadAndLeavesNewEventsForNextBatch() {
        val usage = Usage(context)
        usage.count(key)
        val first = usage.batchForSend()
        usage.count(key) // same key receives another event while the first batch is pending
        usage.count("discovery:v1:relevance:helpful") // forces persistence
        val retry = Usage(context)
        assertEquals(first, retry.batchForSend())
        retry.acknowledge(first.first, first.second, System.currentTimeMillis())
        assertEquals(mapOf(key to 1, "discovery:v1:relevance:helpful" to 1), retry.pending())
        assertNotEquals(first.first, retry.batchForSend().first)
    }
    @Test fun debugFlushDoesNotRemoveOrUploadLocalCounts() {
        val usage = Usage(context, "https://must-not-be-contacted.invalid")
        usage.count(key)
        usage.flushIfDue()
        assertEquals(mapOf(key to 1), usage.pending())
    }
}
