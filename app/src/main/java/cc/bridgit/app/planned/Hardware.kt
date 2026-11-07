package cc.bridgit.app.planned

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.*
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.provider.Settings
import io.ktor.server.routing.Route
import kotlinx.coroutines.*
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import cc.bridgit.app.core.Compat

/** Process-wide hardware leases. A second control replaces its timer but preserves the original value. */
class Hardware(private val app: BridgeApp) {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("control_restore", Context.MODE_PRIVATE)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val cameras = app.getSystemService(CameraManager::class.java)
    private var torch: String? = null
    private var torchTimer: Runnable? = null
    private val ownTorchOff = mutableMapOf<String, Int>()
    private var ringTimer: Runnable? = null
    private var tone: android.media.Ringtone? = null
    private val vibrator = Compat.vibrator(app)
    private val tick = Runnable { synchronized(this@Hardware) { restoreDue(false) } }
    private val alarms = app.getSystemService(android.app.AlarmManager::class.java)
    private val restoreIntent = android.app.PendingIntent.getBroadcast(app, 706,
        Intent(app, RestoreControlsReceiver::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
    init {
        // A killed process cannot keep a timed owner setting indefinitely.
        restoreDue(true)
        cameras.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                synchronized(this@Hardware) {
                    if (!enabled && (ownTorchOff[id] ?: 0) > 0) ownTorchOff[id] = ownTorchOff.getValue(id) - 1
                    else if (!enabled) torchLost(id)
                }
            }
            override fun onTorchModeUnavailable(id: String) { torchLost(id) }
        }, handler)
        app.settings.addListener {
            if (!app.settings.listenerEnabled || !app.settings.isOn("flashlight")) light(false, 1)
            if (!app.settings.listenerEnabled || !app.settings.isOn("ring")) ring(false, 1)
            if (!app.settings.listenerEnabled || !app.settings.isOn("controls")) restoreDue(true)
        }
    }
    private var notificationState: String? = null
    private fun notification() {
        val nm = app.getSystemService(NotificationManager::class.java)
        val state = "${torch != null}:${tone != null}:" + prefs.all.toSortedMap().mapValues { (_, v) -> runCatching { JSONObject(v as String).apply { remove("retry_after") }.toString() }.getOrDefault("invalid") }
        if (state == notificationState) return
        notificationState = state
        if (torch == null && tone == null && prefs.all.isEmpty()) { nm.cancel(704); return }
        val stop = android.app.PendingIntent.getBroadcast(app, 704, Intent(app, ControlsReceiver::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        val open = android.app.PendingIntent.getActivity(app, 704, Intent(app, OwnerToolsActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        nm.notify(704, androidx.core.app.NotificationCompat.Builder(app, BridgeApp.CHANNEL_CAPTURE).setSmallIcon(R.drawable.ic_nav_more)
            .setContentTitle("Bridgit device controls active").setContentText(listOfNotNull(if (torch != null) "Flashlight on" else null,
                if (tone != null) "Ringing" else null, if (prefs.all.isNotEmpty()) "Timed settings active" else null).joinToString(" · "))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(0, "Stop / undo", stop).build())
    }
    @Synchronized private fun torchLost(id: String) {
        if (torch != id) return
        torch = null
        torchTimer?.let(handler::removeCallbacks); torchTimer = null
        app.registry.cameraLock.unlock()
        notification()
    }
    @Synchronized fun light(on: Boolean, seconds: Int): JSONObject {
        torchTimer?.let(handler::removeCallbacks); torchTimer = null
        if (!on) {
            torch?.let { id ->
                ownTorchOff[id] = (ownTorchOff[id] ?: 0) + 1
                runCatching { cameras.setTorchMode(id, false) }.onFailure { ownTorchOff[id] = (ownTorchOff[id] ?: 1) - 1 }
                torch = null; app.registry.cameraLock.unlock()
            }
        } else {
            val id = torch ?: torchCamera ?: throw ErrorCatalog.error("not_supported", "This device has no flashlight.")
            if (torch == null && !app.registry.cameraLock.tryLock()) throw ErrorCatalog.error("sensor_busy", "Camera is in use.")
            try {
                cameras.setTorchMode(id, true); torch = id
            } catch (e: Exception) {
                if (torch == null) app.registry.cameraLock.unlock()
                throw ErrorCatalog.error("sensor_busy", "Android could not turn on the flashlight.")
            }
            torchTimer = Runnable { light(false, 1) }.also { handler.postDelayed(it, seconds * 1000L) }
        }
        notification()
        return JSONObject().put("on", torch != null)
    }
    /** The camera whose flash unit is the torch, or null on devices without one (many tablets). */
    val torchCamera: String? by lazy {
        runCatching { cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }.getOrNull()
    }
    @Synchronized fun lightStatus() = JSONObject().put("on", torch != null)
    @Synchronized fun ring(on: Boolean, seconds: Int): JSONObject {
        ringTimer?.let(handler::removeCallbacks); ringTimer = null
        tone?.stop(); tone = null; vibrator?.cancel()
        if (on) {
            // Respect Android's current ring volume, ringer and DND policy.
            tone = android.media.RingtoneManager.getRingtone(app, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE))
                ?.also { it.audioAttributes = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(); it.isLooping = true; it.play() }
                ?: throw ErrorCatalog.error("not_supported", "No ringtone is configured on this device.")
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 600), 0))
            ringTimer = Runnable { ring(false, 1) }.also { handler.postDelayed(it, seconds * 1000L) }
        }
        notification()
        return JSONObject().put("ringing", on).put("respects_sound_settings", true)
    }
    private fun read(key: String): Int = when {
        key == "dnd" -> app.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        key == "ringer" -> audio.ringerMode
        key == "volume:${AudioManager.STREAM_MUSIC}" && app.speakerReady() -> app.speaker.controlVolume()
        key.startsWith("volume:") -> audio.getStreamVolume(key.substringAfter(':').toInt())
        // Settings.Global keys (battery saver) may never have been written: read them as 0.
        key.startsWith("global:") -> Settings.Global.getInt(app.contentResolver, key.substringAfter(':'), 0)
        else -> Settings.System.getInt(app.contentResolver, key)
    }
    private fun put(key: String, value: Int) {
        when {
            key == "dnd" -> app.getSystemService(NotificationManager::class.java).setInterruptionFilter(value)
            key == "ringer" -> audio.ringerMode = value
            key == "volume:${AudioManager.STREAM_MUSIC}" && app.speakerReady() -> app.speaker.controlVolume(value)
            key.startsWith("volume:") -> audio.setStreamVolume(key.substringAfter(':').toInt(), value, 0)
            key.startsWith("global:") -> if (!Settings.Global.putInt(app.contentResolver, key.substringAfter(':'), value)) throw SecurityException("Setting write refused")
            else -> if (!Settings.System.putInt(app.contentResolver, key, value)) throw SecurityException("Setting write refused")
        }
    }
    @Synchronized fun change(changes: Map<String, Int>, duration: Int?) {
        val before = changes.mapValues { read(it.key) }
        val savedLeases = changes.keys.associateWith { prefs.getString(it, null) }
        val applied = mutableMapOf<String, Int>()
        try {
            changes.forEach { (k, v) ->
                if (duration != null) {
                    val previous = savedLeases[k]?.let(::JSONObject)
                    val lease = JSONObject().put("before", Rules.leaseBaseline(before.getValue(k), previous?.getInt("before"), previous?.getInt("applied")))
                        .put("applied", v).put("until", System.currentTimeMillis() + duration * 60_000L)
                    if (!prefs.edit().putString(k, lease.toString()).commit()) throw java.io.IOException("Cannot persist restoration")
                }
                put(k, v); applied[k] = read(k)
                if (duration != null) {
                    val lease = JSONObject(prefs.getString(k, null)!!).put("applied", read(k))
                    if (!prefs.edit().putString(k, lease.toString()).commit()) throw java.io.IOException("Cannot persist read-back")
                }
                if (duration == null) prefs.edit().remove(k).commit()
            }
        } catch (e: Exception) {
            val retry = mutableSetOf<String>()
            applied.entries.toList().reversed().forEach { (k, value) ->
                if (runCatching { if (read(k) == value) put(k, before.getValue(k)) }.isFailure) {
                    retry += k
                    val previous = savedLeases[k]?.let(::JSONObject)
                    prefs.edit().putString(k, JSONObject().put("before", Rules.leaseBaseline(before.getValue(k), previous?.getInt("before"), previous?.getInt("applied"))).put("applied", value)
                        .put("until", System.currentTimeMillis()).put("retry_after", System.currentTimeMillis() + 60_000).toString()).commit()
                }
            }
            savedLeases.filterKeys { it !in retry }.forEach { (k, old) -> if (old == null) prefs.edit().remove(k).commit() else prefs.edit().putString(k, old).commit() }
            throw ErrorCatalog.error("permission_denied", "Android refused this setting. Earlier writes were rolled back where possible; any pending restoration remains visible in Files and Android access. Check special access on the phone.")
        } finally { scheduleRestore(); notification() }
    }
    private fun scheduleRestore() {
        handler.removeCallbacks(tick)
        alarms.cancel(restoreIntent)
        val now = System.currentTimeMillis()
        val next = prefs.all.values.mapNotNull { runCatching { JSONObject(it as String) }.getOrNull() }
            .filter { !it.optBoolean("retry_paused") }
            .minOfOrNull { maxOf(it.optLong("until"), it.optLong("retry_after"), now + 1000) } ?: return
        handler.postDelayed(tick, next - now)
        // No exact-alarm access required. Android may batch this backup after process death.
        alarms.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next, restoreIntent)
    }
    @Synchronized fun restoreDue(all: Boolean): JSONObject {
        var restored = 0
        prefs.all.keys.forEach { k ->
            val lease = runCatching { JSONObject(prefs.getString(k, "{}")!!) }.getOrNull()
            if (lease == null) { prefs.edit().remove(k).commit(); return@forEach }
            if (all || (!lease.optBoolean("retry_paused") && lease.optLong("until") <= System.currentTimeMillis() && lease.optLong("retry_after") <= System.currentTimeMillis())) {
                // Preserve a change the owner made after the agent's request.
                val current = runCatching { read(k) }.getOrNull()
                if (current != null && current != lease.getInt("applied")) {
                    prefs.edit().remove(k).commit() // Owner changed it: nothing is left to restore.
                    return@forEach
                }
                val done = runCatching { put(k, lease.getInt("before")); true }.getOrDefault(false)
                if (done) { prefs.edit().remove(k).commit(); restored++ }
                else {
                    val attempts = lease.optInt("restore_attempts") + 1
                    prefs.edit().putString(k, lease.put("restore_attempts", attempts).put("retry_paused", attempts >= 12)
                        .put("retry_after", System.currentTimeMillis() + 60_000).toString()).commit()
                }
            }
        }
        notification()
        scheduleRestore()
        return JSONObject().put("restored", restored)
    }
    fun stop(owner: Boolean = false) { light(false, 1); ring(false, 1); restoreDue(true)
        app.log.system("audit", "setting", if (owner) "Owner stopped device controls and undid timed settings" else "Device controls stopped with the listener")
    }
}

class PhoneModule(app: BridgeApp) : Capability(app) {
    override val id = "phone"
    override val titleRes = R.string.cap_phone
    override val descriptionRes = R.string.cap_phone_desc
    override val group = "context"
    override val switchKey = "phone"
    override val endpoints = listOf(PlannedEndpoints.STATUS, PlannedEndpoints.BATTERY, PlannedEndpoints.CONTEXT)
    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(PlannedEndpoints.STATUS) { requireUsable(); Reply.Json(phoneStatus()) }
        endpoint(PlannedEndpoints.BATTERY) { requireUsable(); Reply.Json(cc.bridgit.app.power.Battery.report(app)) }
        endpoint(PlannedEndpoints.CONTEXT) {
            requireUsable()
            val p = app.getSystemService(PowerManager::class.java)
            val locked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            Reply.Json(JSONObject().put("screen_on", p.isInteractive).put("locked", locked)
                .put("likely", if (p.isInteractive && !locked) "in_use" else "unknown")
                .put("rule", "Screen interactive and unlocked; no activity inference otherwise.")
                .put("dnd", app.getSystemService(NotificationManager::class.java).currentInterruptionFilter)
                .put("as_of", isoAt(System.currentTimeMillis())))
        }
    }
    private fun phoneStatus(): JSONObject {
        val b = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val a = app.getSystemService(AudioManager::class.java)
        val c = app.getSystemService(ConnectivityManager::class.java)
        val n = c.getNetworkCapabilities(c.activeNetwork)
        // The full report, plus level and scale as before; GET /phone/battery has the same fields.
        val battery = cc.bridgit.app.power.Battery.report(app).apply { remove("as_of"); remove("alerts"); remove("controls") }
            .put("level", b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)).put("scale", b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
        return JSONObject().put("as_of", isoAt(System.currentTimeMillis()))
            .put("battery", battery)
            .put("network", JSONObject().put("wifi", n?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                .put("cellular", n?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true).put("metered", c.isActiveNetworkMetered))
            .put("screen", JSONObject().put("on", app.getSystemService(PowerManager::class.java).isInteractive)
                .put("locked", app.getSystemService(KeyguardManager::class.java).isKeyguardLocked))
            .put("sound", JSONObject().put("ringer_mode", a.ringerMode).put("media_volume", a.getStreamVolume(AudioManager.STREAM_MUSIC))
                .put("dnd", app.getSystemService(NotificationManager::class.java).currentInterruptionFilter))
    }
}

class ControlsModule(app: BridgeApp) : Capability(app) {
    override val id = "controls"
    override val titleRes = R.string.cap_controls
    override val descriptionRes = R.string.cap_controls_desc
    override val group = "controls"
    override val switchKey = "controls"
    override val endpoints = listOf(PlannedEndpoints.DND, PlannedEndpoints.SOUND, PlannedEndpoints.BRIGHTNESS, PlannedEndpoints.POWER, PlannedEndpoints.UNDO)
    override fun details() = JSONObject().put("dnd_access", app.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted)
        .put("write_settings", Settings.System.canWrite(app))
        .put("battery_saver_control", cc.bridgit.app.power.Battery.saverControl(app))
        .apply { if (!cc.bridgit.app.power.Battery.saverControl(app)) put("battery_saver_grant", cc.bridgit.app.power.BatteryRules.grantCommand(app.packageName)) }
    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(PlannedEndpoints.DND) {
            requireUsable()
            val modes = mapOf("off" to 1, "priority" to 2, "none" to 3, "alarms" to 4)
            val value = modes[body.optString("mode")] ?: throw ErrorCatalog.error("invalid_parameter", "mode must be off, priority, alarms or none.")
            if (!app.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) throw ErrorCatalog.error("permission_denied", "Grant Do Not Disturb access on the phone.")
            app.hardware.change(mapOf("dnd" to value), duration(body))
            Reply.Json(JSONObject().put("mode", body.getString("mode")).put("alarms_muted", value == 3))
        }
        endpoint(PlannedEndpoints.SOUND) {
            requireUsable()
            val a = app.getSystemService(AudioManager::class.java)
            val changes = linkedMapOf<String, Int>()
            if (body.has("ringer_mode")) {
                if (!app.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) throw ErrorCatalog.error("permission_denied", "Grant Do Not Disturb access before changing the ringer.")
                changes["ringer"] = mapOf("silent" to 0, "vibrate" to 1, "normal" to 2)[body.optString("ringer_mode")]
                    ?: throw ErrorCatalog.error("invalid_parameter", "ringer_mode must be normal, vibrate or silent.")
            }
            body.optJSONObject("volume")?.let { v -> v.keys().forEach { key ->
                val stream = mapOf("media" to AudioManager.STREAM_MUSIC, "ring" to AudioManager.STREAM_RING, "alarm" to AudioManager.STREAM_ALARM)[key]
                    ?: throw ErrorCatalog.error("invalid_parameter", "volume supports media, ring and alarm.")
                changes["volume:$stream"] = (Rules.integer(v.opt(key), key, 0, 100) * a.getStreamMaxVolume(stream) / 100.0).toInt()
            } }
            if (changes.isEmpty()) throw ErrorCatalog.error("invalid_parameter", "Provide ringer_mode or volume.")
            app.hardware.change(changes, duration(body)); Reply.Json(JSONObject().put("changed", true))
        }
        endpoint(PlannedEndpoints.BRIGHTNESS) {
            requireUsable()
            if (!Settings.System.canWrite(app)) throw ErrorCatalog.error("permission_denied", "Grant Modify system settings on the phone.")
            val changes = if (Rules.bool(body, "auto", false)) mapOf(Settings.System.SCREEN_BRIGHTNESS_MODE to 1)
                else mapOf(Settings.System.SCREEN_BRIGHTNESS_MODE to 0, Settings.System.SCREEN_BRIGHTNESS to (Rules.number(body.opt("level"), "level", 0.0, 1.0) * 255).toInt().coerceAtLeast(1))
            app.hardware.change(changes, duration(body)); Reply.Json(JSONObject().put("changed", true))
        }
        endpoint(PlannedEndpoints.POWER) {
            requireUsable()
            val changes = linkedMapOf<String, Int>()
            val words = mutableListOf<String>()
            if (body.has("screen_timeout_seconds")) {
                if (!Settings.System.canWrite(app)) throw ErrorCatalog.error("permission_denied", "Grant Modify system settings on the phone.")
                val s = Rules.integer(body.opt("screen_timeout_seconds"), "screen_timeout_seconds", cc.bridgit.app.power.BatteryRules.TIMEOUT_MIN_S, cc.bridgit.app.power.BatteryRules.TIMEOUT_MAX_S)
                changes[Settings.System.SCREEN_OFF_TIMEOUT] = s * 1000; words += "screen timeout to ${s}s"
            }
            if (body.has("battery_saver")) {
                if (!cc.bridgit.app.power.Battery.saverControl(app)) throw ErrorCatalog.error("permission_denied",
                    "Battery saver needs a one-time grant with the phone on USB: ${cc.bridgit.app.power.BatteryRules.grantCommand(app.packageName)}")
                val on = Rules.bool(body, "battery_saver", false)
                changes[cc.bridgit.app.power.Battery.SAVER_KEY] = if (on) 1 else 0; words += "battery saver ${if (on) "on" else "off"}"
            }
            if (changes.isEmpty()) throw ErrorCatalog.error("invalid_parameter", "Provide battery_saver or screen_timeout_seconds.")
            val minutes = duration(body)
            app.hardware.change(changes, minutes)
            // Never change power protections without a trace (REQ-28).
            app.log.system("audit", "setting", "$agentName set ${words.joinToString(" and ")}" + (minutes?.let { " for $it min" } ?: ""))
            val wanted = if (body.has("battery_saver")) Rules.bool(body, "battery_saver", false) else null
            val saverOn = if (wanted != null) cc.bridgit.app.power.Battery.settleSaver(app, wanted) else app.getSystemService(PowerManager::class.java).isPowerSaveMode
            val reply = JSONObject().put("changed", true).put("battery_saver", saverOn)
                .put("screen_timeout_seconds", cc.bridgit.app.power.BatteryRules.timeoutSeconds(Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 0)) ?: JSONObject.NULL)
            if (wanted != null && saverOn != wanted)
                reply.put("note", "Android kept battery saver ${if (saverOn) "on" else "off"}. Some phones keep it off while charging; check GET /v1/phone/battery.")
            Reply.Json(reply)
        }
        endpoint(PlannedEndpoints.UNDO) { requireUsable(); Reply.Json(app.hardware.restoreDue(true)) }
    }
    private fun duration(b: JSONObject) = if (!b.has("duration_minutes") || b.isNull("duration_minutes")) null else Rules.integer(b.opt("duration_minutes"), "duration_minutes", 1, 1440)
}

class TorchModule(app: BridgeApp) : Capability(app) {
    override val id = "flashlight"
    override val titleRes = R.string.cap_torch
    override val descriptionRes = R.string.cap_torch_desc
    override val group = "controls"
    override val switchKey = "flashlight"
    override val permissions = listOf(Manifest.permission.CAMERA)
    override val endpoints = listOf(PlannedEndpoints.TORCH_GET, PlannedEndpoints.TORCH)
    override fun blockedReason() = missingPermission() ?: if (app.hardware.torchCamera == null) "no_hardware"
        else if (!app.captureNotificationsOn()) "notifications_off" else null
    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(PlannedEndpoints.TORCH_GET) { requireUsable(); Reply.Json(app.hardware.lightStatus()) }
        endpoint(PlannedEndpoints.TORCH) {
            requireUsable()
            val on = Rules.bool(body, "on", true)
            val seconds = Rules.integer(body.opt("auto_off_seconds"), "auto_off_seconds", 1, 600, 60)
            val t = api.service.captureStarted("$agentName ${if (on) "turned on" else "turned off"} the flashlight")
            try { Reply.Json(app.hardware.light(on, seconds)) } finally { api.service.captureEnded(t) }
        }
    }
}
class RingModule(app: BridgeApp) : Capability(app) {
    override val id = "ring"
    override val titleRes = R.string.cap_ring
    override val descriptionRes = R.string.cap_ring_desc
    override val group = "controls"
    override val switchKey = "ring"
    override val endpoints = listOf(PlannedEndpoints.RING, PlannedEndpoints.RING_STOP)
    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(PlannedEndpoints.RING) { requireUsable(); Reply.Json(app.hardware.ring(true, Rules.integer(body.opt("duration_seconds"), "duration_seconds", 1, 60, 15))) }
        endpoint(PlannedEndpoints.RING_STOP) { requireUsable(); Reply.Json(app.hardware.ring(false, 1)) }
    }
}
class SensorsModule(app: BridgeApp) : Capability(app) {
    override val id = "sensors"
    override val titleRes = R.string.cap_sensors
    override val descriptionRes = R.string.cap_sensors_desc
    override val group = "context"
    override val switchKey = "sensors"
    override val endpoints = listOf(PlannedEndpoints.SENSORS)
    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(PlannedEndpoints.SENSORS) {
            requireUsable()
            val types = mapOf("light" to Sensor.TYPE_LIGHT, "pressure" to Sensor.TYPE_PRESSURE, "proximity" to Sensor.TYPE_PROXIMITY,
                "steps" to Sensor.TYPE_STEP_COUNTER, "orientation" to Sensor.TYPE_ROTATION_VECTOR, "motion" to Sensor.TYPE_ACCELEROMETER)
            val requested = (query("which") ?: "light,pressure,proximity").split(',')
            if (requested.isEmpty() || requested.any { it !in types }) throw ErrorCatalog.error("invalid_parameter", "which must contain light,pressure,proximity,steps,orientation,motion.")
            if ("steps" in requested && !app.granted(Manifest.permission.ACTIVITY_RECOGNITION)) throw ErrorCatalog.error("permission_denied", "Grant Physical activity access on the phone.")
            val out = JSONObject()
            for (name in requested.distinct()) out.put(name, sample(types.getValue(name)))
            Reply.Json(out)
        }
    }
    private suspend fun sample(type: Int): JSONObject = withContext(Dispatchers.Main) {
        val sm = app.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(type) ?: return@withContext JSONObject().put("available", false)
        withTimeoutOrNull(1100) {
            suspendCancellableCoroutine<JSONObject> { cont ->
                val l = object : SensorEventListener {
                    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(JSONObject().put("available", true).put("values", JSONArray(e.values.map { it.toDouble() }))
                            .put("accuracy", e.accuracy).put("as_of", isoAt(System.currentTimeMillis())).put("age_s", (SystemClock.elapsedRealtimeNanos() - e.timestamp).coerceAtLeast(0) / 1e9))
                    }
                }
                sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(l) }
            }
        } ?: JSONObject().put("available", true).put("fresh", false).put("reason", "No sensor event within 1.1 seconds")
    }
}

class RestoreControlsReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { (context.applicationContext as BridgeApp).hardware.restoreDue(false) }
}

class ControlsReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { (context.applicationContext as BridgeApp).hardware.stop(owner = true) }
}
