package cc.bridgit.app.capability

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Reply
import org.json.JSONObject
import kotlin.coroutines.resume
import android.os.Build
import androidx.core.location.LocationManagerCompat

/**
 * Location fixes from Android's own LocationManager (no Google Play services, "no third-party
 * SDKs"). Used by GET /v1/location (8.8, pulled forward for City tour, D30) and tour ticks.
 */
class LocationReader(private val ctx: Context) {
    private val lm = ctx.getSystemService(LocationManager::class.java)

    fun precision(): String? = when {
        ctx.granted(Manifest.permission.ACCESS_FINE_LOCATION) -> "precise"
        ctx.granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> "approximate"
        else -> null
    }

    fun enabled(): Boolean = lm.isLocationEnabled

    /** The newest last-known fix from any provider, or null. */
    @Suppress("MissingPermission")
    fun lastKnown(): Location? = runCatching {
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.elapsedRealtimeNanos }
    }.getOrNull()

    /** A fresh fix within [timeoutMs], or null. [high] uses GPS, otherwise the fused/network provider. */
    @Suppress("MissingPermission")
    suspend fun fresh(high: Boolean, timeoutMs: Long): Location? {
        val provider = when {
            high && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && precision() == "precise" -> LocationManager.GPS_PROVIDER
            Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return null
        }
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cancel = androidx.core.os.CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, cancel, ContextCompat.getMainExecutor(ctx)) { loc -> if (cont.isActive) cont.resume(loc) }
            }
        }
    }

    /** An owner refresh subscribes to a new fix; cached positions never satisfy this request. */
    @Suppress("MissingPermission", "DEPRECATION")
    suspend fun freshAfterRequest(high: Boolean, timeoutMs: Long): Location? {
        val precise = precision() == "precise"
        val providers = buildList {
            if (high && precise && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            if (precise && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && LocationManager.GPS_PROVIDER !in this) add(LocationManager.GPS_PROVIDER)
        }
        if (providers.isEmpty()) return null
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (!cont.isActive || location.elapsedRealtimeNanos < started) return
                        if (high && (!location.hasAccuracy() || location.accuracy > 200)) return
                        lm.removeUpdates(this)
                        cont.resume(location)
                    }
                    override fun onProviderDisabled(name: String) {
                        if (providers.any { lm.isProviderEnabled(it) }) return
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onProviderEnabled(name: String) = Unit
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                try {
                    for (provider in providers) {
                        if (!cont.isActive) break
                        lm.requestLocationUpdates(provider, 0L, 0f, listener, android.os.Looper.getMainLooper())
                    }
                } catch (error: Exception) {
                    lm.removeUpdates(listener)
                    throw error
                }
                if (!cont.isActive) lm.removeUpdates(listener)
            }
        }
    }

    companion object {
        fun json(l: Location, precision: String, iso: (Long) -> String): JSONObject = JSONObject()
            .put("latitude", l.latitude).put("longitude", l.longitude)
            .put("altitude_m", if (l.hasAltitude()) l.altitude else JSONObject.NULL)
            .put("accuracy_m", if (l.hasAccuracy()) l.accuracy.toDouble() else JSONObject.NULL)
            .put("vertical_accuracy_m", if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters.toDouble() else JSONObject.NULL)
            .put("speed_mps", if (l.hasSpeed()) l.speed.toDouble() else JSONObject.NULL)
            .put("bearing_deg", if (l.hasBearing()) l.bearing.toDouble() else JSONObject.NULL)
            .put("provider", l.provider ?: JSONObject.NULL).put("fix_time", iso(l.time))
            .put("age_s", ((System.currentTimeMillis() - l.time) / 1000).coerceAtLeast(0))
            .put("precision", precision)
    }
}

/** One compass reading from the rotation-vector sensor: where the top of the phone points. */
object Compass {
    data class Reading(val azimuthDeg: Double, val pitchDeg: Double, val rollDeg: Double, val accuracy: Int)

    suspend fun read(ctx: Context, timeoutMs: Long = 1500): Reading? = withContext(Dispatchers.Main) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            ?: return@withContext null
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        val r = FloatArray(9); val o = FloatArray(3)
                        SensorManager.getRotationMatrixFromVector(r, e.values)
                        SensorManager.getOrientation(r, o)
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(Reading(normalise(Math.toDegrees(o[0].toDouble())),
                            Math.toDegrees(o[1].toDouble()), Math.toDegrees(o[2].toDouble()), e.accuracy))
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
                cont.invokeOnCancellation { sm.unregisterListener(l) }
            }
        }
    }

    /** 0-360, 0 = north. */
    fun normalise(deg: Double) = ((deg % 360) + 360) % 360

    /** "N", "NE", ... for captions. */
    fun cardinal(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5) % 360) / 45).toInt()]

    fun json(r: Reading) = JSONObject().put("azimuth_deg", Math.round(r.azimuthDeg * 10) / 10.0)
        .put("direction", cardinal(r.azimuthDeg)).put("pitch_deg", Math.round(r.pitchDeg * 10) / 10.0)
        .put("roll_deg", Math.round(r.rollDeg * 10) / 10.0).put("accuracy", r.accuracy)
}

/** 8.8 Location (minimal, for City tour): one fix per request, with the compass. Switch "Location". */
class LocationModule(app: BridgeApp) : Capability(app) {
    internal val reader = LocationReader(app)
    override val id = "location"
    override val titleRes = R.string.cap_location
    override val descriptionRes = R.string.cap_location_desc
    override val group = "where"
    override val switchKey = "location"
    override val permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    override val endpoints = listOf(Endpoints.LOCATION)
    override fun blockedReason(): String? = when {
        reader.precision() == null -> "permission:ACCESS_FINE_LOCATION"
        !app.captureNotificationsOn() -> "notifications_off"
        !reader.enabled() -> "location_off"
        else -> null
    }
    override fun details() = JSONObject().put("precision", reader.precision() ?: "none").put("location_setting_on", reader.enabled())

    /** For other modules that read the position (weather): the same errors GET /v1/location would give. */
    fun ensureUsable() = requireUsable()

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.LOCATION) {
            val fresh = query("fresh")?.let { it == "true" || it == "1" } ?: true
            val accuracy = query("accuracy") ?: "high"
            if (accuracy != "high" && accuracy != "balanced") throw ErrorCatalog.error("invalid_parameter", "accuracy must be high or balanced.")
            val withCompass = query("compass")?.let { it != "false" && it != "0" } ?: true
            requireUsable()
            val token = api.service.captureStarted(app.getString(R.string.notif_location, agentName))
            try {
                val l = (if (fresh) reader.fresh(accuracy == "high", 15_000) else null) ?: reader.lastKnown()
                    ?: throw ErrorCatalog.error("capture_timeout", "No location fix yet. Try again outdoors, or with fresh=false.")
                val out = LocationReader.json(l, reader.precision()!!, ::isoAt)
                if (withCompass) Compass.read(app)?.let { out.put("compass", Compass.json(it)) }
                if (fresh && (System.currentTimeMillis() - l.time) > 30_000) out.put("note", "Fresh fix timed out; this is the last known one.")
                // LOG-02: coordinates in the log only to 3 decimals.
                data = JSONObject().put("lat", Math.round(l.latitude * 1000) / 1000.0).put("lon", Math.round(l.longitude * 1000) / 1000.0)
                Reply.Json(out)
            } finally { api.service.captureEnded(token) }
        }
    }
}
