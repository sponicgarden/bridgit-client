package cc.bridgit.app.monitor

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A0189/A0190: one reading of Bridgit's own counters (this app's process and UID only, never other apps).
 * Counters ([cpuMs], [rxBytes] ...) are cumulative; [ResourceLog.add] turns two readings into a sample.
 * Null means the platform didn't expose it on this phone.
 */
data class Reading(
    val at: Long,
    val pid: Int,
    val cpuMs: Long?,
    val rssKb: Long?,
    val peakRssKb: Long?,
    val threads: Int?,
    val contextSwitches: Long?,
    val rxBytes: Long?,
    val txBytes: Long?,
    val diskReadBytes: Long?,
    val diskWriteBytes: Long?,
    val javaHeapKb: Long?,
    val nativeHeapKb: Long?,
    val storageBytes: Long?,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val batteryTempC: Double?,
    val thermal: String?,
    val cores: Int,
    val costMicros: Long,
)

/**
 * The resource monitor's bounded log: raw samples (newest [maxSamples]), hourly summaries ([maxHours]) and daily
 * summaries ([maxDays]), each summary keyed by its period start and the build that ran (a new build starts a new
 * summary, so a heavy build shows in its own numbers). Pure Kotlin + org.json; the Android sampler is [ResourceMonitor].
 */
class ResourceLog(val maxSamples: Int = MAX_SAMPLES, val maxHours: Int = MAX_HOURS, val maxDays: Int = MAX_DAYS, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    companion object {
        /** The interval choices (A0190), shortest first. 1s and 10s are for live diagnosis; 1h for cheap always-on watching. */
        val INTERVALS = listOf("1s", "10s", "1m", "1h")
        /** One minute: fine enough for useful hourly peaks, cheap enough to leave on (one ~1 ms read a minute). */
        const val DEFAULT_INTERVAL = "1m"
        const val MAX_SAMPLES = 1440
        const val MAX_HOURS = 168
        const val MAX_DAYS = 60
        /** Raw samples kept in the file across restarts (the rest stay in memory only, to keep the monitor's own writes small). */
        const val PERSISTED_SAMPLES = 120

        fun seconds(interval: String): Long = when (interval) { "1s" -> 1; "10s" -> 10; "1m" -> 60; "1h" -> 3600; else -> error("Unknown interval $interval") }

        /**
         * What counts as "a lot" (A0190): elevated / high per summary. Fixed constants, not settings; CPU is percent of one
         * core averaged over the period, the rest per hour of monitored time.
         */
        val THRESHOLDS: Map<String, Pair<Double, Double>> = linkedMapOf(
            "cpu_percent" to (5.0 to 20.0),
            "peak_rss_mb" to (300.0 to 600.0),
            "network_mb_per_hour" to (50.0 to 250.0),
            "disk_write_mb_per_hour" to (100.0 to 500.0),
            "storage_mb" to (1024.0 to 4096.0),
            "context_switches_per_s" to (200.0 to 1000.0),
        )

        fun thresholdsJson(): JSONObject = JSONObject().apply {
            for ((k, v) in THRESHOLDS) put(k, JSONObject().put("elevated", v.first).put("high", v.second))
        }

        private fun mb(kb: Long?) = kb?.let { Math.round(it / 102.4) / 10.0 }
        private fun mbBytes(b: Long?) = b?.let { Math.round(it / 104857.6) / 10.0 }
        private fun r1(d: Double) = Math.round(d * 10) / 10.0
        private val THERMAL_ORDER = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")
    }

    /** One summary period. Fields are running totals; [json] turns them into averages and rates. */
    class Bucket(val period: String, val start: Long, val build: String, val versionCode: Long) {
        var end = start; var samples = 0; var coveredMs = 0L
        var cpuMs = 0L; var cpuCoveredMs = 0L; var cpuPeak = 0.0
        var rssSumKb = 0L; var rssCount = 0; var rssPeakKb = 0L; var peakRssKb = 0L
        var rx = 0L; var tx = 0L; var netCoveredMs = 0L
        var diskRead = 0L; var diskWrite = 0L; var diskCoveredMs = 0L
        var switches = 0L; var switchesCoveredMs = 0L
        var storageBytes: Long? = null; var costMicros = 0L
        var thermal: String? = null; var batteryFirst: Int? = null; var batteryLast: Int? = null

        fun json(): JSONObject {
            val hours = coveredMs / 3_600_000.0
            val o = JSONObject().put("period", period).put("start", Instant.ofEpochMilli(start).toString()).put("end", Instant.ofEpochMilli(end).toString())
                .put("build", build).put("version_code", versionCode).put("samples", samples).put("covered_s", coveredMs / 1000)
            o.put("cpu_percent", if (cpuCoveredMs > 0) JSONObject().put("avg", r1(cpuMs * 100.0 / cpuCoveredMs)).put("peak", r1(cpuPeak)) else JSONObject.NULL)
            o.put("rss_mb", if (rssCount > 0) JSONObject().put("avg", mb(rssSumKb / rssCount)).put("peak", mb(rssPeakKb)) else JSONObject.NULL)
            o.put("peak_rss_mb", if (peakRssKb > 0) mb(peakRssKb) else JSONObject.NULL)
            o.put("network_bytes", if (netCoveredMs > 0) JSONObject().put("rx", rx).put("tx", tx) else JSONObject.NULL)
            o.put("network_mb_per_hour", if (netCoveredMs > 0) r1((rx + tx) / 1_048_576.0 / (netCoveredMs / 3_600_000.0)) else JSONObject.NULL)
            o.put("disk_bytes", if (diskCoveredMs > 0) JSONObject().put("read", diskRead).put("write", diskWrite) else JSONObject.NULL)
            o.put("disk_write_mb_per_hour", if (diskCoveredMs > 0) r1(diskWrite / 1_048_576.0 / (diskCoveredMs / 3_600_000.0)) else JSONObject.NULL)
            o.put("context_switches", if (switchesCoveredMs > 0) switches else JSONObject.NULL)
            o.put("context_switches_per_s", if (switchesCoveredMs > 0) r1(switches * 1000.0 / switchesCoveredMs) else JSONObject.NULL)
            o.put("storage_mb", mbBytes(storageBytes) ?: JSONObject.NULL)
            o.put("battery_percent", if (batteryFirst != null) JSONObject().put("first", batteryFirst).put("last", batteryLast) else JSONObject.NULL)
            o.put("thermal_worst", thermal ?: JSONObject.NULL)
            o.put("monitor_cost_ms", if (samples > 0) r1(costMicros / 1000.0 / samples) else JSONObject.NULL)
            if (hours > 0 || samples > 0) level(o)
            return o
        }

        /** Adds level (ok / elevated / high) and over: the metrics past their threshold, for "is this a lot" at a glance. */
        private fun level(o: JSONObject) {
            val over = JSONArray(); var worst = 0
            for ((k, t) in THRESHOLDS) {
                val v = when (val x = o.opt(k)) { is JSONObject -> x.optDouble("avg", Double.NaN); is Number -> x.toDouble(); else -> Double.NaN }
                if (v.isNaN()) continue
                val l = if (v >= t.second) 2 else if (v >= t.first) 1 else 0
                if (l > 0) over.put(JSONObject().put("metric", k).put("value", v).put("level", if (l == 2) "high" else "elevated"))
                worst = maxOf(worst, l)
            }
            o.put("level", listOf("ok", "elevated", "high")[worst]).put("over", over)
        }

        fun toStore(): JSONObject = JSONObject().put("p", period).put("s", start).put("b", build).put("v", versionCode).put("e", end).put("n", samples).put("c", coveredMs)
            .put("cpu", cpuMs).put("cpuc", cpuCoveredMs).put("cpup", cpuPeak).put("rs", rssSumKb).put("rn", rssCount).put("rp", rssPeakKb).put("hwm", peakRssKb)
            .put("rx", rx).put("tx", tx).put("nc", netCoveredMs).put("dr", diskRead).put("dw", diskWrite).put("dc", diskCoveredMs).put("sw", switches).put("swc", switchesCoveredMs)
            .put("st", storageBytes ?: JSONObject.NULL).put("cost", costMicros).put("th", thermal ?: JSONObject.NULL)
            .put("bf", batteryFirst ?: JSONObject.NULL).put("bl", batteryLast ?: JSONObject.NULL)

        fun accumulate(s: Delta) {
            end = s.at; samples++; costMicros += s.costMicros
            s.dtMs?.let { coveredMs += it }
            if (s.cpuMs != null && s.dtMs != null) { cpuMs += s.cpuMs; cpuCoveredMs += s.dtMs; cpuPeak = maxOf(cpuPeak, s.cpuMs * 100.0 / s.dtMs) }
            s.rssKb?.let { rssSumKb += it; rssCount++; rssPeakKb = maxOf(rssPeakKb, it) }
            s.peakRssKb?.let { peakRssKb = maxOf(peakRssKb, it) }
            if (s.rx != null && s.tx != null && s.dtMs != null) { rx += s.rx; tx += s.tx; netCoveredMs += s.dtMs }
            if (s.diskRead != null && s.diskWrite != null && s.dtMs != null) { diskRead += s.diskRead; diskWrite += s.diskWrite; diskCoveredMs += s.dtMs }
            if (s.switches != null && s.dtMs != null) { switches += s.switches; switchesCoveredMs += s.dtMs }
            s.storageBytes?.let { storageBytes = it }
            s.thermal?.let { t -> if (thermal == null || THERMAL_ORDER.indexOf(t) > THERMAL_ORDER.indexOf(thermal)) thermal = t }
            s.batteryPercent?.let { if (batteryFirst == null) batteryFirst = it; batteryLast = it }
        }

        companion object {
            fun fromStore(o: JSONObject) = Bucket(o.getString("p"), o.getLong("s"), o.getString("b"), o.getLong("v")).apply {
                end = o.getLong("e"); samples = o.getInt("n"); coveredMs = o.getLong("c")
                cpuMs = o.getLong("cpu"); cpuCoveredMs = o.getLong("cpuc"); cpuPeak = o.getDouble("cpup")
                rssSumKb = o.getLong("rs"); rssCount = o.getInt("rn"); rssPeakKb = o.getLong("rp"); peakRssKb = o.getLong("hwm")
                rx = o.getLong("rx"); tx = o.getLong("tx"); netCoveredMs = o.getLong("nc")
                diskRead = o.getLong("dr"); diskWrite = o.getLong("dw"); diskCoveredMs = o.getLong("dc"); switches = o.getLong("sw"); switchesCoveredMs = o.getLong("swc")
                storageBytes = if (o.isNull("st")) null else o.getLong("st"); costMicros = o.getLong("cost"); thermal = if (o.isNull("th")) null else o.getString("th")
                batteryFirst = if (o.isNull("bf")) null else o.getInt("bf"); batteryLast = if (o.isNull("bl")) null else o.getInt("bl")
            }
        }
    }

    /** The difference between two readings (null counters: unavailable, or reset by a restart). */
    data class Delta(val at: Long, val dtMs: Long?, val cpuMs: Long?, val rssKb: Long?, val peakRssKb: Long?, val rx: Long?, val tx: Long?,
                     val diskRead: Long?, val diskWrite: Long?, val switches: Long?, val storageBytes: Long?, val thermal: String?, val batteryPercent: Int?, val costMicros: Long)

    private val samples = ArrayDeque<JSONObject>()
    private val hours = ArrayDeque<Bucket>()
    private val days = ArrayDeque<Bucket>()
    private var last: Reading? = null

    @Synchronized fun sampleCount() = samples.size
    @Synchronized fun latest(): JSONObject? = samples.lastOrNull()
    /** The next reading starts over (after the monitor was stopped), so a gap is never counted as monitored time. */
    @Synchronized fun resetBaseline() { last = null }
    @Synchronized fun clear() { samples.clear(); hours.clear(); days.clear(); last = null }

    /** Adds [r] and returns the sample. The first reading after a start has no rates (nothing to compare with). */
    @Synchronized fun add(r: Reading, build: String, versionCode: Long): JSONObject {
        val prev = last?.takeIf { it.pid == r.pid && r.at > it.at }
        last = r
        fun d(a: Long?, b: Long?) = if (a != null && b != null && a >= b) a - b else null
        val dt = prev?.let { r.at - it.at }
        val delta = Delta(r.at, dt, d(r.cpuMs, prev?.cpuMs), r.rssKb, r.peakRssKb, d(r.rxBytes, prev?.rxBytes), d(r.txBytes, prev?.txBytes),
            d(r.diskReadBytes, prev?.diskReadBytes), d(r.diskWriteBytes, prev?.diskWriteBytes), d(r.contextSwitches, prev?.contextSwitches),
            r.storageBytes, r.thermal, r.batteryPercent, r.costMicros)
        val cpu = if (delta.cpuMs != null && dt != null && dt > 0) r1(delta.cpuMs * 100.0 / dt) else null
        val s = JSONObject().put("at", Instant.ofEpochMilli(r.at).toString()).put("interval_s", dt?.let { r1(it / 1000.0) } ?: JSONObject.NULL)
            .put("build", build).put("version_code", versionCode)
            .put("cpu_percent", cpu ?: JSONObject.NULL)
            .put("cpu_device_percent", cpu?.let { r1(it / r.cores.coerceAtLeast(1)) } ?: JSONObject.NULL)
            .put("rss_mb", mb(r.rssKb) ?: JSONObject.NULL).put("peak_rss_mb", mb(r.peakRssKb) ?: JSONObject.NULL)
            .put("java_heap_mb", mb(r.javaHeapKb) ?: JSONObject.NULL).put("native_heap_mb", mb(r.nativeHeapKb) ?: JSONObject.NULL)
            .put("threads", r.threads ?: JSONObject.NULL)
            .put("network_bytes", if (delta.rx != null && delta.tx != null) JSONObject().put("rx", delta.rx).put("tx", delta.tx) else JSONObject.NULL)
            .put("disk_bytes", if (delta.diskRead != null && delta.diskWrite != null) JSONObject().put("read", delta.diskRead).put("write", delta.diskWrite) else JSONObject.NULL)
            .put("context_switches", delta.switches ?: JSONObject.NULL)
            .put("storage_mb", mbBytes(r.storageBytes) ?: JSONObject.NULL)
            .put("battery", JSONObject().put("percent", r.batteryPercent ?: JSONObject.NULL).put("charging", r.charging ?: JSONObject.NULL)
                .put("temperature_c", r.batteryTempC ?: JSONObject.NULL))
            .put("thermal", r.thermal ?: JSONObject.NULL)
            .put("monitor_cost_ms", r1(r.costMicros / 1000.0))
        samples.addLast(s); while (samples.size > maxSamples) samples.removeFirst()
        val z = zone()
        val hour = Instant.ofEpochMilli(r.at).atZone(z).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()
        val day = Instant.ofEpochMilli(r.at).atZone(z).truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli()
        bucket(hours, "hour", hour, build, versionCode, maxHours).accumulate(delta)
        bucket(days, "day", day, build, versionCode, maxDays).accumulate(delta)
        return s
    }

    private fun bucket(list: ArrayDeque<Bucket>, period: String, start: Long, build: String, code: Long, max: Int): Bucket {
        val current = list.lastOrNull()
        if (current != null && current.start == start && current.build == build && current.versionCode == code) return current
        return Bucket(period, start, build, code).also { list.addLast(it); while (list.size > max) list.removeFirst() }
    }

    @Synchronized fun samples(sinceMs: Long?, limit: Int): List<JSONObject> =
        samples.filter { sinceMs == null || Instant.parse(it.getString("at")).toEpochMilli() >= sinceMs }.takeLast(limit)
    @Synchronized fun hourly(sinceMs: Long?, limit: Int): List<JSONObject> = hours.filter { sinceMs == null || it.end >= sinceMs }.takeLast(limit).map { it.json() }
    @Synchronized fun daily(sinceMs: Long?, limit: Int): List<JSONObject> = days.filter { sinceMs == null || it.end >= sinceMs }.takeLast(limit).map { it.json() }
    @Synchronized fun currentHour(): JSONObject? = hours.lastOrNull()?.json()
    @Synchronized fun today(): JSONObject? = days.lastOrNull()?.json()

    /** What the file keeps: summaries plus the newest [PERSISTED_SAMPLES] raw samples. */
    @Synchronized fun toStore(): JSONObject = JSONObject().put("v", 1)
        .put("samples", JSONArray(samples.toList().takeLast(PERSISTED_SAMPLES)))
        .put("hours", JSONArray(hours.map { it.toStore() })).put("days", JSONArray(days.map { it.toStore() }))

    @Synchronized fun load(o: JSONObject) {
        clear()
        o.optJSONArray("samples")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { samples.addLast(it) } }
        o.optJSONArray("hours")?.let { a -> for (i in 0 until a.length()) runCatching { hours.addLast(Bucket.fromStore(a.getJSONObject(i))) } }
        o.optJSONArray("days")?.let { a -> for (i in 0 until a.length()) runCatching { days.addLast(Bucket.fromStore(a.getJSONObject(i))) } }
        while (samples.size > maxSamples) samples.removeFirst()
        while (hours.size > maxHours) hours.removeFirst()
        while (days.size > maxDays) days.removeFirst()
    }
}
