package cc.bridgit.app.watch

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.Sizing
import cc.bridgit.app.capability.LocationReader
import cc.bridgit.app.capability.granted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Takes one reading for a watch: only the elements it asked for. Each element is best effort; one that
 * can't be read says why instead of failing the whole reading. Step counts are per watch (since its last reading).
 */
class WatchSampler(private val app: BridgeApp) {
    private val reader = LocationReader(app)
    private val sm get() = app.getSystemService(SensorManager::class.java)

    /** One reading, the step counter now, and the photo (JPEG) if the watch reads the camera and one was taken. */
    class Sample(val reading: JSONObject, val steps: Float?, val photo: ByteArray?)

    /** One reading: `at_ms` plus one object per element. [lastSteps] is the step counter at the previous reading. */
    suspend fun read(data: List<String>, lastSteps: Float?, camera: String? = null, label: String = "Watch", agentId:String?=null,
                     cameraWaitMs: Long = CAMERA_WAIT_MS): Sample {
        val out = JSONObject().put("at_ms", System.currentTimeMillis())
        var steps: Float? = lastSteps
        var photo: ByteArray? = null
        for (name in data) {
            val v: JSONObject = runCatching {
                when (name) {
                    "location" -> location()
                    "motion" -> motion()
                    "steps" -> stepCount().let { (json, count) -> if (count != null) {
                        lastSteps?.let { json.put("since_last_reading", (count - it).toInt().coerceAtLeast(0)) }
                        steps = count
                    }; json }
                    "light" -> single(Sensor.TYPE_LIGHT, "lux")
                    "pressure" -> single(Sensor.TYPE_PRESSURE, "hpa")
                    "proximity" -> single(Sensor.TYPE_PROXIMITY, "cm")
                    "battery" -> battery()
                    "device" -> device()
                    "camera" -> (if(camera?.startsWith("lan:")==true) lanPhoto(camera,label,agentId) else photo(camera != "front", label, cameraWaitMs)).let { (json, bytes) -> photo = bytes; json }
                    else -> unavailable("unknown")
                }
            }.getOrElse { if(it is kotlinx.coroutines.CancellationException) throw it; unavailable(it.javaClass.simpleName) }
            out.put(name, v)
        }
        return Sample(out, steps, photo)
    }

    /**
     * A small photo (640 px, like the Pet monitor: enough for Clef and Jev, cheap). Best effort: a busy camera
     * (an agent's photo, the Pet monitor), a locked phone or Bridgit not running gives a reason instead.
     */
    private suspend fun photo(rear: Boolean, label: String, waitMs: Long = CAMERA_WAIT_MS): Pair<JSONObject, ByteArray?> {
        var waited = 0
        while (BridgeService.running?.cameraReady != true && waited < 3_000) { delay(250); waited += 250 }
        val svc = BridgeService.running ?: return unavailable("Bridgit isn't running") to null
        // Another watch or a quick agent photo may hold the camera for a moment: wait a little, don't skip.
        val locked = if (waitMs <= 0) app.registry.cameraLock.tryLock() else withTimeoutOrNull(waitMs) { app.registry.cameraLock.lock() } != null
        if (!locked) return unavailable("the camera is busy") to null
        val token = svc.captureStarted(label)
        return try {
            val img = svc.camera.snapshot(rear = rear, flash = false, spec = PHOTO)
            JSONObject().put("taken", true).put("camera", if (rear) "rear" else "front").put("width_px", img.width).put("height_px", img.height) to img.bytes
        } catch (e: ApiError) {
            unavailable(if (e.code == "device_locked") "the phone is locked" else e.message ?: e.code) to null
        } finally {
            svc.captureEnded(token)
            app.registry.cameraLock.unlock()
        }
    }

    private suspend fun lanPhoto(id:String,label:String,agentId:String?):Pair<JSONObject,ByteArray?> {
        fun check() {
            val a=agentId?.let { app.agents.get(it) }
            if(a==null || a.paused || !a.allows("lan_camera") || !app.settings.isOn("lan_camera") || !app.captureNotificationsOn()) throw ApiError(403,"agent_not_allowed","Camera access changed.")
        }
        check()
        val svc=BridgeService.running ?: return unavailable("Bridgit isn't running") to null
        val token=svc.captureStarted(label)
        return try {
            val enrolled=app.lanCameras.get(id)
            val bytes=cc.bridgit.app.lancamera.RtspFrame.snapshot(app,enrolled)
            check()
            val current=app.lanCameras.get(id)
            if (current.host != enrolled.host || current.username != enrolled.username || current.password != enrolled.password)
                throw ApiError(403,"agent_not_allowed","Camera enrollment changed during capture.")
            JSONObject().put("taken",true).put("camera",id).put("width_px",640).put("height_px",360) to bytes
        } finally { svc.captureEnded(token) }
    }

    companion object {
        val PHOTO = Sizing.Spec(640, null, 75)
        const val CAMERA_WAIT_MS = 10_000L
    }

    private fun unavailable(reason: String) = JSONObject().put("available", false).put("reason", reason)

    private suspend fun location(): JSONObject {
        val precision = reader.precision() ?: return unavailable("no location permission")
        if (!reader.enabled()) return unavailable("location is off on the phone")
        val l = reader.fresh(high = precision == "precise", timeoutMs = 10_000) ?: reader.lastKnown() ?: return unavailable("no fix")
        val o = JSONObject()
            .put("latitude", Math.round(l.latitude * 1e6) / 1e6).put("longitude", Math.round(l.longitude * 1e6) / 1e6)
            .put("accuracy_m", if (l.hasAccuracy()) Math.round(l.accuracy).toInt() else JSONObject.NULL)
            .put("fix_age_s", ((System.currentTimeMillis() - l.time) / 1000).coerceAtLeast(0))
            .put("provider", l.provider ?: JSONObject.NULL)
        if (l.hasSpeed()) o.put("speed_mps", Math.round(l.speed * 10) / 10.0)
        if (l.hasBearing()) o.put("bearing_deg", Math.round(l.bearing).toInt())
        if (l.hasAltitude()) o.put("altitude_m", Math.round(l.altitude).toInt())
        return o
    }

    /** A short accelerometer burst: how hard and how unevenly the phone is moving. */
    private suspend fun motion(): JSONObject {
        val linear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val sensor = linear ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return unavailable("no accelerometer")
        val mags = ArrayList<Double>()
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            override fun onSensorChanged(e: SensorEvent) {
                val m = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
                synchronized(mags) { mags += if (linear != null) m else abs(m - SensorManager.GRAVITY_EARTH) }
            }
        }
        val started = System.nanoTime()
        withContext(Dispatchers.Main) { sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME) }
        try { delay((WatchRules.MOTION_SECONDS * 1000).toLong()) } finally { withContext(Dispatchers.Main) { sm.unregisterListener(listener) } }
        val secs = (System.nanoTime() - started) / 1e9
        return WatchRules.motionStats(synchronized(mags) { mags.toList() }, secs) ?: unavailable("no samples")
    }

    private suspend fun stepCount(): Pair<JSONObject, Float?> {
        if (!app.granted(Manifest.permission.ACTIVITY_RECOGNITION)) return unavailable("Physical activity access isn't granted") to null
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return unavailable("no step counter") to null
        val v = first(s) ?: return unavailable("no reading within 1.5 s") to null
        return JSONObject().put("available", true) to v[0]
    }

    private suspend fun single(type: Int, unit: String): JSONObject {
        val s = sm.getDefaultSensor(type) ?: return unavailable("no sensor")
        val v = first(s) ?: return unavailable("no reading within 1.5 s")
        return JSONObject().put(unit, Math.round(v[0] * 10) / 10.0)
    }

    private suspend fun first(s: Sensor): FloatArray? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(1500) {
            suspendCancellableCoroutine { cont ->
                val l = object : SensorEventListener {
                    override fun onAccuracyChanged(x: Sensor?, a: Int) {}
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values.clone())
                    }
                }
                sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(l) }
            }
        }
    }

    /** The battery now: percent, charging, plugged, estimated_runtime (or available false). */
    fun battery(): JSONObject {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return unavailable("no battery status")
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"; BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"; 0 -> "none"; else -> "other"
        }
        return JSONObject().put("percent", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
            .put("charging", plugged != "none").put("plugged", plugged)
            .put("estimated_runtime", cc.bridgit.app.power.Battery.runtimeReport(app, i))
    }

    private fun device(): JSONObject {
        val power = app.getSystemService(PowerManager::class.java)
        val audio = app.getSystemService(AudioManager::class.java)
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm.activeNetwork?.let(cm::getNetworkCapabilities) }.getOrNull()
        val network = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        return JSONObject().put("screen_on", power.isInteractive)
            .put("locked", app.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            .put("headphones", outputs.any { it in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET) })
            .put("network", network).put("power_save", power.isPowerSaveMode)
    }
}
