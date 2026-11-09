package cc.bridgit.app.monitor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** A0189/A0190: samples from cumulative counters, restart handling, bounds, per-hour/day/build summaries and thresholds. */
class ResourceLogTest {
    private val t0 = Instant.parse("2026-11-09T10:00:00Z").toEpochMilli()

    private fun reading(at: Long, cpuMs: Long = 0, rssKb: Long = 100 * 1024, rx: Long = 0, tx: Long = 0, write: Long = 0, sw: Long = 0, pid: Int = 42,
                        storage: Long? = 50L * 1024 * 1024, disk: Boolean = true) = Reading(
        at = at, pid = pid, cpuMs = cpuMs, rssKb = rssKb, peakRssKb = rssKb + 1024, threads = 30, contextSwitches = sw, rxBytes = rx, txBytes = tx,
        diskReadBytes = if (disk) 0 else null, diskWriteBytes = if (disk) write else null, javaHeapKb = 20 * 1024, nativeHeapKb = 30 * 1024,
        storageBytes = storage, batteryPercent = 80, charging = false, batteryTempC = 31.5, thermal = "none", cores = 8, costMicros = 900)

    private fun log(max: Int = 1440) = ResourceLog(maxSamples = max, zone = { ZoneOffset.UTC })

    @Test fun firstSampleHasNoRatesThenDeltasBecomeRates() {
        val l = log()
        val first = l.add(reading(t0), "1.8.900", 900)
        assertTrue(first.isNull("cpu_percent"))
        assertTrue(first.isNull("network_bytes"))
        assertEquals(100.0, first.getDouble("rss_mb"), 0.01)
        assertEquals("1.8.900", first.getString("build"))
        // 60 s later: 6 s of CPU = 10 % of one core, 1.25 % of 8 cores.
        val s = l.add(reading(t0 + 60_000, cpuMs = 6_000, rx = 3_000, tx = 1_000, write = 4096, sw = 600), "1.8.900", 900)
        assertEquals(10.0, s.getDouble("cpu_percent"), 0.01)
        assertEquals(1.3, s.getDouble("cpu_device_percent"), 0.051)
        assertEquals(3000, s.getJSONObject("network_bytes").getLong("rx"))
        assertEquals(4096, s.getJSONObject("disk_bytes").getLong("write"))
        assertEquals(600, s.getLong("context_switches"))
        assertEquals(60.0, s.getDouble("interval_s"), 0.01)
        assertEquals(0.9, s.getDouble("monitor_cost_ms"), 0.01)
        assertEquals(31.5, s.getJSONObject("battery").getDouble("temperature_c"), 0.01)
    }

    @Test fun aRestartOrCounterResetNeverProducesNegativeOrHugeRates() {
        val l = log()
        l.add(reading(t0, cpuMs = 50_000, rx = 10_000), "b", 1)
        val restarted = l.add(reading(t0 + 60_000, cpuMs = 100, rx = 50, pid = 43), "b", 1)
        assertTrue(restarted.isNull("cpu_percent"))
        assertTrue(restarted.isNull("network_bytes"))
        val reset = l.add(reading(t0 + 120_000, cpuMs = 700, rx = 10, pid = 43), "b", 1)
        assertEquals(1.0, reset.getDouble("cpu_percent"), 0.01) // CPU counter went up
        assertTrue(reset.isNull("network_bytes")) // UID counter went down (reboot): skipped, not negative
        l.resetBaseline()
        assertTrue(l.add(reading(t0 + 999_000, cpuMs = 900_000, pid = 43), "b", 1).isNull("cpu_percent"))
    }

    @Test fun unavailableCountersStayNull() {
        val l = log()
        l.add(reading(t0, disk = false, storage = null), "b", 1)
        val s = l.add(reading(t0 + 1000, disk = false, storage = null), "b", 1)
        assertTrue(s.isNull("disk_bytes"))
        assertTrue(s.isNull("storage_mb"))
        assertTrue(l.currentHour()!!.isNull("disk_write_mb_per_hour"))
    }

    @Test fun rawSamplesAreBounded() {
        val l = log(max = 5)
        for (i in 0 until 12) l.add(reading(t0 + i * 1000L, cpuMs = i * 10L), "b", 1)
        assertEquals(5, l.sampleCount())
        assertEquals(Instant.ofEpochMilli(t0 + 11_000).toString(), l.latest()!!.getString("at"))
        assertEquals(3, l.samples(t0 + 9_000, 100).size)
        assertEquals(2, l.samples(null, 2).size)
        val small = ResourceLog(maxSamples = 10, maxHours = 3, maxDays = 2, zone = { ZoneOffset.UTC })
        for (h in 0 until 80) small.add(reading(t0 + h * 3_600_000L), "b", 1)
        assertEquals(3, small.hourly(null, 100).size)
        assertEquals(2, small.daily(null, 100).size)
    }

    @Test fun summariesSplitByHourAndByBuild() {
        val l = log()
        l.add(reading(t0), "1.8.900", 900)
        l.add(reading(t0 + 30 * 60_000, cpuMs = 180_000), "1.8.900", 900) // 10 % over 30 min
        l.add(reading(t0 + 70 * 60_000, cpuMs = 180_000 + 24_000), "1.8.900", 900) // next hour, 1 %
        l.add(reading(t0 + 80 * 60_000, cpuMs = 204_000 + 6_000), "1.8.901", 901) // new build, same hour
        val hours = l.hourly(null, 10)
        assertEquals(3, hours.size)
        assertEquals("2026-11-09T10:00:00Z", hours[0].getString("start"))
        assertEquals(10.0, hours[0].getJSONObject("cpu_percent").getDouble("avg"), 0.01)
        assertEquals("1.8.900", hours[1].getString("build"))
        assertEquals("1.8.901", hours[2].getString("build"))
        assertEquals(901, hours[2].getLong("version_code"))
        val days = l.daily(null, 10)
        assertEquals(2, days.size) // one day, two builds
        assertEquals("day", days[0].getString("period"))
        assertEquals(3, days[0].getInt("samples"))
    }

    @Test fun thresholdsMakeALotVisible() {
        val l = log()
        l.add(reading(t0), "b", 1)
        val quiet = l.add(reading(t0 + 60_000, cpuMs = 600), "b", 1) // 1 %
        assertEquals("ok", l.currentHour()!!.getString("level"))
        assertEquals(1.0, quiet.getDouble("cpu_percent"), 0.01)
        // 50 % CPU and 700 MB peak in the next minute.
        l.add(reading(t0 + 120_000, cpuMs = 600 + 60_000, rssKb = 700 * 1024), "b", 1)
        val h = l.currentHour()!!
        assertEquals("high", h.getString("level"))
        val over = (0 until h.getJSONArray("over").length()).map { h.getJSONArray("over").getJSONObject(it).getString("metric") }
        assertTrue(over.containsAll(listOf("cpu_percent", "peak_rss_mb")))
        assertFalse("storage_mb" in over)
        val t = ResourceLog.thresholdsJson()
        assertEquals(20.0, t.getJSONObject("cpu_percent").getDouble("high"), 0.0)
        assertEquals(listOf("1s", "10s", "1m", "1h"), ResourceLog.INTERVALS)
        assertEquals("1m", ResourceLog.DEFAULT_INTERVAL)
        assertEquals(3600L, ResourceLog.seconds("1h"))
    }

    @Test fun storeRoundTripKeepsSummariesAndRecentSamples() {
        val l = log()
        for (i in 0 until 200) l.add(reading(t0 + i * 10_000L, cpuMs = i * 100L, rx = i * 1000L), "b", 7)
        val stored = JSONObject(l.toStore().toString())
        assertEquals(ResourceLog.PERSISTED_SAMPLES, stored.getJSONArray("samples").length())
        val back = log().apply { load(stored) }
        assertEquals(ResourceLog.PERSISTED_SAMPLES, back.sampleCount())
        assertEquals(l.currentHour().toString(), back.currentHour().toString())
        assertEquals(l.today().toString(), back.today().toString())
        // After a load, the next reading starts a fresh baseline.
        assertTrue(back.add(reading(t0 + 3_000_000, cpuMs = 99_999), "b", 7).isNull("cpu_percent"))
    }
}
