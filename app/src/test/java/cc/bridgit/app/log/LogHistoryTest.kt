package cc.bridgit.app.log

import android.app.Application
import cc.bridgit.app.core.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LogHistoryTest {
    private fun entry(n: Int, channel: String = "audit") = LogEntry(ts = n.toLong(), channel = channel, kind = "request", message = "event $n")

    @Test fun unlimitedDefaultKeepsOldEntriesAndDiagnosticsAcrossTrimCyclesAndReopen() {
        val ctx = RuntimeEnvironment.getApplication()
        val settings = Settings(ctx)
        assertEquals(LogStore.Retention(), settings.retention())
        val store = LogStore(ctx) { settings.retention() }
        repeat(10400) { store.append(entry(it, if (it % 2 == 0) "diagnostic" else "audit")) }
        store.flush()
        assertEquals(10400, store.count(LogQuery()))
        // Multiple pages contain every id exactly once.
        val ids = mutableListOf<Long>()
        var cursor: Long? = null
        do {
            val page = store.query(LogQuery(limit = 137, ascending = true, cursor = cursor))
            ids += page.map { it.id }
            cursor = page.lastOrNull()?.id
        } while (page.isNotEmpty())
        assertEquals(10400, ids.size); assertEquals(ids.size, ids.toSet().size)
        val reopened = LogStore(ctx) { settings.retention() }
        assertEquals(10400, reopened.count(LogQuery()))
        assertEquals(ids.last(), reopened.latestId.value)
    }

    @Test fun exportIsFiniteWhileNewRowsArriveAndHonorsFilters() {
        val store = LogStore(RuntimeEnvironment.getApplication()) { LogStore.Retention() }
        repeat(600) { store.append(entry(it)) }; store.flush()
        val ids = mutableListOf<Long>()
        store.forEach(LogQuery(sinceId = 10)) { e ->
            ids += e.id
            if (ids.size == 1) { repeat(20) { store.append(entry(900 + it)) }; store.flush() }
        }
        assertEquals((11L..600L).toList(), ids)
        assertEquals(620, store.count(LogQuery()))
    }

    @Test fun explicitCountAndAgeLimitsStillWorkWithoutDeletingDiagnostics() {
        val store = LogStore(RuntimeEnvironment.getApplication()) { LogStore.Retention(maxEntries = 100) }
        repeat(399) { store.append(entry(it)) }
        store.append(entry(400, "diagnostic")); store.flush()
        assertEquals(100, store.count(LogQuery(channels = listOf("audit"))))
        assertEquals(1, store.count(LogQuery(channels = listOf("diagnostic"))))
    }

    @Test fun commandRejectsShellInjectionAndScrubsCredentials() {
        for (bad in listOf("all;id", "main -c", "", "radio")) {
            assertTrue(runCatching { DeviceDiagnostics.command(bad, 1) }.isFailure)
        }
        for (n in listOf(0, -1, 10001)) assertTrue(runCatching { DeviceDiagnostics.command("all", n) }.isFailure)
        assertEquals(listOf("logcat", "-d", "-v", "threadtime", "-b", "crash", "-t", "50"), DeviceDiagnostics.command("crash", 50))
        val key = "brg_" + "x".repeat(43)
        val clean = DeviceDiagnostics.scrub("$key Authorization: Bearer abc password=xyz token:secretvalue")
        for (secret in listOf(key, "abc", "xyz", "secretvalue")) assertFalse(clean, clean.contains(secret))
    }
}
