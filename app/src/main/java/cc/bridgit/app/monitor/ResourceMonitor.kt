package cc.bridgit.app.monitor

import android.app.usage.StorageStatsManager
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.storage.StorageManager
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * A0189/A0190 "Monitor resources": an Activity-Monitor-style watch on Bridgit's own footprint (this process and UID,
 * never other apps or any content), off by default. Settings › Log (device.resource_monitor and
 * device.resource_monitor_interval, the same keys GET/POST /v1/settings and POST /v1/owner/configuration/settings use)
 * switch it; [sync] follows the stored settings, so the screen and the APIs share one path.
 *
 * Each sample reads a few /proc/self files, the process CPU time, the UID's traffic counters, the battery broadcast
 * and (at most every 10 minutes) the app's storage: about a millisecond, measured into monitor_cost_ms. The log is
 * bounded ([ResourceLog]) and written to app storage at most every 15 minutes, on the hour and when stopped.
 * Read it with GET /v1/device/resources.
 */
object ResourceMonitor {
    private const val FILE = "resource-monitor.json"
    private const val PERSIST_EVERY_MS = 15 * 60_000L
    private const val STORAGE_EVERY_MS = 10 * 60_000L

    val log = ResourceLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var runningInterval: String? = null
    @Volatile private var loaded = false
    private var lastPersist = 0L
    private var lastPersistHour = 0L
    private var storageAt = 0L
    private var storageBytes: Long? = null
    /** Sampling source; tests replace it. */
    var reader: (BridgeApp) -> Reading = ::read

    /** Called once from BridgeApp.onCreate. */
    fun attach(app: BridgeApp) {
        app.settings.addListener { sync(app) }
        sync(app)
    }

    /** Starts, restarts (new interval) or stops the sampler to match the stored settings. Cheap when nothing changed. */
    @Synchronized fun sync(app: BridgeApp) {
        val on = app.settings.resourceMonitor
        val interval = app.settings.resourceMonitorInterval
        if (on && job?.isActive == true && runningInterval == interval) return
        if (!on && job == null) return
        job?.cancel(); job = null; runningInterval = null
        if (!on) { log.resetBaseline(); scope.launch { persist(app) }; return }
        runningInterval = interval
        val every = ResourceLog.seconds(interval) * 1000L
        job = scope.launch {
            ensureLoaded(app)
            while (isActive) {
                runCatching { sampleOnce(app) }
                delay(every)
            }
        }
    }

    fun running() = job?.isActive == true

    /** Takes one sample now and adds it to the log. */
    fun sampleOnce(app: BridgeApp): JSONObject {
        ensureLoaded(app)
        val s = log.add(reader(app), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
        val now = System.currentTimeMillis()
        val hour = now / 3_600_000L
        if (now - lastPersist >= PERSIST_EVERY_MS || hour != lastPersistHour) persist(app)
        return s
    }

    private fun ensureLoaded(app: BridgeApp) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching { File(app.filesDir, FILE).takeIf { it.exists() }?.readText()?.let { log.load(JSONObject(it)) } }
            loaded = true
        }
    }

    @Synchronized private fun persist(app: BridgeApp) {
        if (!loaded) return
        val now = System.currentTimeMillis()
        lastPersist = now; lastPersistHour = now / 3_600_000L
        runCatching {
            val f = File(app.filesDir, FILE); val tmp = File(app.filesDir, "$FILE.tmp")
            tmp.writeText(log.toStore().toString()); tmp.renameTo(f)
        }
    }

    /** For tests: forget everything, in memory and on disk. */
    @Synchronized fun resetForTest(app: BridgeApp) {
        job?.cancel(); job = null; runningInterval = null
        log.clear(); loaded = true; lastPersist = 0L; storageAt = 0L; storageBytes = null
        File(app.filesDir, FILE).delete()
        reader = ::read
    }

    // --- Reading Bridgit's own counters. Anything the phone refuses is left null. ---

    private fun read(app: BridgeApp): Reading {
        val t0 = System.nanoTime()
        val status = procFields("/proc/self/status")
        val io = procFields("/proc/self/io")
        val uid = Process.myUid()
        fun traffic(v: Long) = v.takeIf { it != TrafficStats.UNSUPPORTED.toLong() && it >= 0 }
        val bat = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = bat?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0 && sc > 0) l * 100 / sc else null }
        val temp = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        val rt = Runtime.getRuntime()
        val r = Reading(
            at = System.currentTimeMillis(), pid = Process.myPid(), cpuMs = Process.getElapsedCpuTime().takeIf { it >= 0 },
            rssKb = status["VmRSS"], peakRssKb = status["VmHWM"], threads = status["Threads"]?.toInt(),
            contextSwitches = status["voluntary_ctxt_switches"]?.let { v -> v + (status["nonvoluntary_ctxt_switches"] ?: 0) },
            rxBytes = traffic(TrafficStats.getUidRxBytes(uid)), txBytes = traffic(TrafficStats.getUidTxBytes(uid)),
            diskReadBytes = io["read_bytes"], diskWriteBytes = io["write_bytes"],
            javaHeapKb = (rt.totalMemory() - rt.freeMemory()) / 1024, nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024,
            storageBytes = storage(app), batteryPercent = level,
            charging = bat?.let { it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 }, batteryTempC = temp,
            thermal = thermal(app), cores = Runtime.getRuntime().availableProcessors(), costMicros = 0,
        )
        return r.copy(costMicros = (System.nanoTime() - t0) / 1000)
    }

    /** "Key: 123 kB" lines as numbers (kB stays kB). Unreadable files (SELinux on some phones) give an empty map. */
    internal fun procFields(path: String): Map<String, Long> = runCatching {
        File(path).readLines().mapNotNull { line ->
            val k = line.substringBefore(':', "").trim(); val v = line.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull()
            if (k.isEmpty() || v == null) null else k to v
        }.toMap()
    }.getOrDefault(emptyMap())

    /** The app's own code + data + cache bytes; Android lets an app ask about its own UID without a permission. */
    private fun storage(app: BridgeApp): Long? {
        val now = System.currentTimeMillis()
        if (now - storageAt < STORAGE_EVERY_MS && storageAt > 0) return storageBytes
        storageAt = now
        storageBytes = runCatching {
            val st = app.getSystemService(StorageStatsManager::class.java).queryStatsForUid(StorageManager.UUID_DEFAULT, Process.myUid())
            st.appBytes + st.dataBytes + st.cacheBytes
        }.getOrNull()
        return storageBytes
    }

    private fun thermal(app: BridgeApp): String? = runCatching {
        when (app.getSystemService(PowerManager::class.java).currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"; PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"; PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"; PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"; else -> null
        }
    }.getOrNull()

    // --- What agents and the owner see. ---

    /** On/off, since when, interval and the latest numbers: the health block and the head of GET /v1/device/resources. */
    fun status(app: BridgeApp): JSONObject {
        val s = app.settings
        val changed = s.resourceMonitorChangedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() }
        val hour = log.currentHour()
        return JSONObject().put("enabled", s.resourceMonitor).put("running", running())
            .put("since", if (s.resourceMonitor) changed ?: JSONObject.NULL else JSONObject.NULL)
            .put("stopped_at", if (!s.resourceMonitor) changed ?: JSONObject.NULL else JSONObject.NULL)
            .put("interval", s.resourceMonitorInterval).put("interval_s", ResourceLog.seconds(s.resourceMonitorInterval))
            .put("intervals", JSONArray(ResourceLog.INTERVALS)).put("default_interval", ResourceLog.DEFAULT_INTERVAL)
            .put("level", hour?.optString("level") ?: JSONObject.NULL)
            .put("latest", log.latest() ?: JSONObject.NULL)
            .put("settings", JSONObject().put("enabled", "device.resource_monitor").put("interval", "device.resource_monitor_interval"))
            .put("details", "/v1/device/resources")
    }

    /** The short block in GET /v1/health. */
    fun healthJson(app: BridgeApp): JSONObject {
        val st = status(app)
        val latest = log.latest()
        return JSONObject().put("enabled", st.get("enabled")).put("since", st.get("since")).put("interval", st.get("interval"))
            .put("level", st.get("level"))
            .put("cpu_percent", latest?.opt("cpu_percent") ?: JSONObject.NULL).put("rss_mb", latest?.opt("rss_mb") ?: JSONObject.NULL)
            .put("details", "/v1/device/resources")
    }

    /** GET /v1/device/resources: status, current hour and today, plus the requested series ([view]). */
    fun report(app: BridgeApp, view: String, sinceMs: Long?, limit: Int): JSONObject {
        ensureLoaded(app)
        val name = cc.bridgit.app.feedback.DeviceNames.cached(app)?.optString("name")?.takeIf { it.isNotEmpty() }
        val o = status(app)
            .put("scope", "bridgit_only").put("platform", "android")
            .put("bridgit_name", name ?: JSONObject.NULL)
            .put("build", JSONObject().put("version", BuildConfig.VERSION_NAME).put("version_code", BuildConfig.VERSION_CODE).put("commit", BuildConfig.GIT_COMMIT))
            .put("current_hour", log.currentHour() ?: JSONObject.NULL).put("today", log.today() ?: JSONObject.NULL)
            .put("thresholds", ResourceLog.thresholdsJson())
            .put("retention", JSONObject().put("samples", ResourceLog.MAX_SAMPLES).put("hours", ResourceLog.MAX_HOURS).put("days", ResourceLog.MAX_DAYS)
                .put("samples_kept", log.sampleCount()))
            .put("measures", MEASURES)
            .put("view", view)
        when (view) {
            "samples" -> o.put("samples", JSONArray(log.samples(sinceMs, limit)))
            "hourly" -> o.put("hours", JSONArray(log.hourly(sinceMs, limit)))
            "daily" -> o.put("days", JSONArray(log.daily(sinceMs, limit)))
        }
        return o
    }

    val VIEWS = listOf("summary", "samples", "hourly", "daily")

    /** What each number is and what Android doesn't give an app; returned with every report. */
    private val MEASURES: JSONObject get() = JSONObject()
        .put("cpu_percent", "Bridgit's process CPU time over the interval, percent of one core (cpu_device_percent: of all cores)")
        .put("rss_mb", "Resident memory now; peak_rss_mb is the process's high-water mark")
        .put("network_bytes", "Bytes Bridgit's app UID received/sent during the interval (TrafficStats)")
        .put("disk_bytes", "Bytes Bridgit's process read from / wrote to storage during the interval (/proc/self/io; null where the phone hides it)")
        .put("storage_mb", "Bridgit's app, data and cache size (checked at most every 10 minutes)")
        .put("context_switches", "Bridgit's thread context switches during the interval: the closest measure of wakeups an app can read")
        .put("battery", "Device-wide battery level, charging and temperature, as context; Android gives apps no per-app energy figure")
        .put("thermal", "Device thermal status, as context")
        .put("monitor_cost_ms", "How long taking the sample took")
}
