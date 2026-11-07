package cc.bridgit.app.power

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.platform.Capabilities
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Android side of [BatteryRules]: the full battery report and who may change power settings. */
object Battery {
    private val runtime = BatteryRuntime()

    fun resetRuntime() = runtime.reset()

    /** Approximate time to empty at recent usage. Same metric for health, phone/context and applets. */
    fun runtimeReport(app: Context, b: Intent? = sticky(app)): JSONObject {
        val percent = b?.let { BatteryRules.percent(it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)) }
        val e = runtime.observe(android.os.SystemClock.elapsedRealtime(), percent,
            (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
        return JSONObject().put("status", e.status)
            .put("remaining_seconds", e.seconds ?: JSONObject.NULL)
            .put("lower_seconds", e.lowerSeconds ?: JSONObject.NULL)
            .put("upper_seconds", e.upperSeconds ?: JSONObject.NULL)
            .put("observed_seconds", e.observedSeconds)
            .put("drain_percent_per_hour", e.percentPerHour ?: JSONObject.NULL)
            .put("method", "recent_discharge_trend")
            .put("estimated", true)
            .put("note", "At recent usage; changes in workload, temperature or battery health change runtime. Range reflects 1% level resolution, not a guarantee. Learns after at least 10 minutes and 2% drain within the recent 2-hour window while unplugged; resets on charge, restart, rising level or gaps over 30 minutes.")
    }
    /** Settings.Global.LOW_POWER_MODE; Android's battery saver follows it. Needs WRITE_SECURE_SETTINGS. */
    const val SAVER_KEY = "global:low_power"

    fun saverControl(app: Context) = app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Android applies battery saver a moment after the setting changes: wait up to a second for [want]. */
    suspend fun settleSaver(app: Context, want: Boolean): Boolean {
        val pm = app.getSystemService(PowerManager::class.java)
        repeat(10) { if (pm.isPowerSaveMode == want) return want; kotlinx.coroutines.delay(100) }
        return pm.isPowerSaveMode
    }

    fun sticky(app: Context): Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    fun reading(app: Context, b: Intent? = sticky(app)): BatteryRules.Reading = BatteryRules.Reading(
        percent = b?.let { BatteryRules.percent(it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)) },
        charging = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
        full = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_FULL,
        powerSave = app.getSystemService(PowerManager::class.java).isPowerSaveMode,
        celsius = b?.let { BatteryRules.celsius(it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)) })

    /** Every battery value this phone reports. Values the phone does not report are null, never guessed. */
    fun report(app: Context): JSONObject {
        val b = sticky(app)
        val bm = app.getSystemService(BatteryManager::class.java)
        val pm = app.getSystemService(PowerManager::class.java)
        val plugged = b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        fun n(v: Any?): Any = v ?: JSONObject.NULL
        val cycles = if (Build.VERSION.SDK_INT >= 34) b?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 } else null
        val toFull = if (Build.VERSION.SDK_INT >= 28) bm.computeChargeTimeRemaining().takeIf { it >= 0 }?.let { it / 1000 } else null
        val timeoutMs = runCatching { Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()
        return JSONObject()
            .put("as_of", isoAt(System.currentTimeMillis()))
            .put("percent", n(b?.let { BatteryRules.percent(it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)) }))
            .put("charging", plugged != 0)
            .put("status", BatteryRules.status(b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1))
            .put("plugged", BatteryRules.plugged(plugged))
            .put("health", BatteryRules.health(b?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1))
            .put("present", b?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) ?: true)
            .put("technology", n(b?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() }))
            .put("temperature_c", n(b?.let { BatteryRules.celsius(it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)) }))
            .put("voltage_v", n(b?.let { BatteryRules.volts(it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)) }))
            .put("cycle_count", n(cycles))
            .put("charge_counter_mah", n(BatteryRules.micro(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toLong())))
            .put("current_now_ma", n(BatteryRules.micro(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong())))
            .put("current_average_ma", n(BatteryRules.micro(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE).toLong())))
            .put("current_sign", "Phone makers differ: on some, negative means discharging; on others, positive does")
            .put("charge_time_remaining_seconds", n(toFull))
            .put("estimated_runtime", runtimeReport(app, b))
            .put("battery_saver", pm.isPowerSaveMode)
            .put("thermal_status", BatteryRules.thermal(pm.currentThermalStatus))
            .put("bridgit_unrestricted", pm.isIgnoringBatteryOptimizations(app.packageName))
            .put("screen_timeout_seconds", n(timeoutMs?.let(BatteryRules::timeoutSeconds)))
            .put("screen_timeout_never", timeoutMs != null && BatteryRules.timeoutSeconds(timeoutMs) == null)
            .put("controls", JSONObject()
                .put("battery_saver", saverControl(app))
                .put("screen_timeout", Settings.System.canWrite(app))
                .put("battery_saver_grant", if (saverControl(app)) JSONObject.NULL else "Once, with the phone on USB: " + BatteryRules.grantCommand(app.packageName))
                .put("charge_limit", "not_available: Android has no public charge-limit setting; set it in the phone's own battery settings"))
            .put("alerts", JSONObject().put("events", org.json.JSONArray(BatteryRules.EVENTS))
                .put("low_percent", BatteryRules.LOW).put("critical_percent", BatteryRules.CRITICAL)
                .put("okay_percent", BatteryRules.OKAY).put("hot_c", BatteryRules.HOT_C))
    }
}

/**
 * Sends battery.* events while the listener runs. Android repeats the battery broadcast on
 * every level and temperature tick, so only state changes in [BatteryRules.transitions] go out.
 * REST agents with the phone grant get them on /v1/events (phone switch on); platform agents
 * with diagnostics get them on their events stream.
 */
class BatteryWatcher(private val app: BridgeApp) {
    private var last: BatteryRules.Reading? = null
    private var latch = BatteryRules.Latch()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val b = if (intent.action == Intent.ACTION_BATTERY_CHANGED) intent else Battery.sticky(app)
            // Power triggers (part G): the plug or unplug is saved the moment it happens; POWER_CONNECTED/DISCONNECTED
            // say so directly, the battery broadcast is the back-up. Flows read the saved state on their next tick.
            val plugged = when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> true
                Intent.ACTION_POWER_DISCONNECTED -> false
                else -> b?.let { it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 }
            }
            if (plugged != null) PowerState.observe(app, plugged)
            Battery.runtimeReport(app, b)
            val now = Battery.reading(app, b)
            synchronized(this@BatteryWatcher) {
                val prev = last
                if (prev == null) { latch = BatteryRules.initial(now); last = now; return }
                val (events, next) = BatteryRules.transitions(prev, now, latch)
                last = now; latch = next
                if (events.isNotEmpty()) app.platform.scope.launch {
                    // Battery-triggered flows run whatever the phone switch: the owner approved them.
                    runCatching { app.platform.workflows.onBattery(events, now) }
                    runCatching { send(events, now) }
                }
            }
        }
    }
    private var registered = false

    @Synchronized fun start() {
        if (registered) return
        last = null
        Battery.resetRuntime()
        app.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        registered = true
    }

    @Synchronized fun stop() {
        if (!registered) return
        runCatching { app.unregisterReceiver(receiver) }
        registered = false; last = null
    }

    private fun send(events: List<String>, r: BatteryRules.Reading) {
        val data = { type: String -> JSONObject().put("type", type).put("percent", r.percent ?: JSONObject.NULL).put("charging", r.charging)
            .put("battery_saver", r.powerSave).put("temperature_c", r.celsius ?: JSONObject.NULL).put("details", "/v1/phone/battery") }
        if (app.settings.listenerEnabled && app.settings.isOn("phone"))
            for (a in app.agents.all().filter { it.allows("phone") }) events.forEach { app.interactions.event(a.id, it, data(it)) }
        if (Capabilities.enabled(app, "diagnostics"))
            for (a in app.policy.agents().filter { app.platform.hasGrant(it.id, "diagnostics") }) events.forEach { app.platform.store.emit(a.id, it) }
        app.log.system("diagnostic", "battery", events.joinToString(", ") + " at ${r.percent ?: "?"}%")
    }
}
