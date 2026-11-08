package cc.bridgit.app.feedback

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.interact.CardViewport
import cc.bridgit.app.interact.DisplayGeometry
import cc.bridgit.app.interact.DisplayInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * A0077/A0078: this Bridgit's static spec sheet (display, full-screen card size, cameras, CPU, memory, storage,
 * sensors, model, OS), stored with Bridgit once the Bridgit has a Device Domain Name, so agents ask the team backend
 * (GET /v1/names/{name}/specs) instead of a phone that may be busy or offline. Live state stays on the phone.
 *
 * On by default; the owner can switch it off (Settings › Team, or POST /v1/owner/configuration/specs), which deletes
 * the stored sheet. Uploaded right after the name is registered and then whenever the sheet's hash changes, checked
 * at most once an hour from the service loop. Failures are silent and retried later; no name yet means skip.
 * Never identifying: no serial numbers, IMEI, MAC, accounts or names; only model-level facts.
 */
object DeviceSpecs {
    const val MAX_BYTES = 8 * 1024
    const val MAX_SENSORS = 64
    const val MAX_CAMERAS = 12
    const val SENSOR_NAME_MAX = 40
    const val CHECK_EVERY_MS = 3_600_000L
    const val OPERATION = "POST /v1/owner/configuration/specs"
    const val PATH = "/v1/names/me/specs"
    /** Platform-store keys: the owner's switch ("0" = off; default on), what was last stored, a deletion still owed. */
    internal const val SHARE_KEY = "device_specs_share"
    internal const val HASH_KEY = "device_specs_hash"
    internal const val AT_KEY = "device_specs_uploaded_at"
    internal const val DELETE_KEY = "device_specs_delete_pending"
    /** Every field the backend accepts (strict: unknown fields are rejected there). */
    val FIELDS = setOf("display", "full_screen_card", "cameras", "cpu", "memory_mb", "storage_gb", "sensors",
        "manufacturer", "model", "os", "os_version", "api_level", "app_version")
    private val FACINGS = setOf("back", "front", "external")

    data class Camera(val facing: String, val megapixels: Int? = null)

    /** What the phone found; [build] turns it into the sheet. Pure data so the builder is unit-tested. */
    data class Facts(
        val display: DisplayGeometry, val cameras: List<Camera> = emptyList(), val cores: Int? = null, val maxMhz: Int? = null,
        val abi: String? = null, val memoryMb: Long? = null, val storageGb: Long? = null, val sensors: List<String?> = emptyList(),
        val manufacturer: String? = null, val model: String? = null, val osVersion: String? = null, val apiLevel: Int? = null,
        val appVersion: String? = null,
    )

    /** A sensor type as a short lowercase name: "android.sensor.accelerometer" → "accelerometer". Null when nothing is left. */
    fun sensorName(raw: String?): String? {
        val last = raw?.trim()?.substringAfterLast('.')?.lowercase() ?: return null
        val n = last.replace(Regex("[^a-z0-9_]+"), "_").replace(Regex("_+"), "_").trim('_').take(SENSOR_NAME_MAX).trimEnd('_')
        return n.ifEmpty { null }
    }

    private fun text(v: String?, max: Int) = v?.filter { !it.isISOControl() }?.trim()?.take(max)?.ifEmpty { null }

    /** The spec sheet, in the backend's shape; at most [MAX_BYTES] (sensors, then cameras, are trimmed to fit). Pure. */
    fun build(f: Facts): JSONObject {
        val g = f.display
        val (portrait, landscape) = CardViewport.fullScreen(g)
        val nat = g.natural
        val sheet = JSONObject()
            .put("display", JSONObject().put("width_px", nat.w).put("height_px", nat.h).put("density_dpi", g.densityDpi)
                .apply { g.refreshHz?.let { put("refresh_hz", it) } })
            .put("full_screen_card", JSONObject().put("portrait", portrait.json()).put("landscape", landscape.json()))
        val cameras = f.cameras.filter { it.facing in FACINGS }.take(MAX_CAMERAS)
        if (cameras.isNotEmpty()) sheet.put("cameras", JSONArray(cameras.map { c ->
            JSONObject().put("facing", c.facing).apply { c.megapixels?.takeIf { it > 0 }?.let { put("megapixels", it) } } }))
        val cpu = JSONObject().apply {
            f.cores?.takeIf { it > 0 }?.let { put("cores", it) }
            f.maxMhz?.takeIf { it > 0 }?.let { put("max_mhz", it) }
            text(f.abi, 32)?.let { put("abi", it) }
        }
        if (cpu.length() > 0) sheet.put("cpu", cpu)
        f.memoryMb?.takeIf { it > 0 }?.let { sheet.put("memory_mb", it) }
        f.storageGb?.takeIf { it > 0 }?.let { sheet.put("storage_gb", it) }
        val sensors = f.sensors.mapNotNull(::sensorName).distinct().sorted().take(MAX_SENSORS)
        if (sensors.isNotEmpty()) sheet.put("sensors", JSONArray(sensors))
        text(f.manufacturer, 60)?.let { sheet.put("manufacturer", it) }
        text(f.model, 60)?.let { sheet.put("model", it) }
        sheet.put("os", "android")
        text(f.osVersion, 20)?.let { sheet.put("os_version", it) }
        f.apiLevel?.takeIf { it > 0 }?.let { sheet.put("api_level", it) }
        text(f.appVersion, 40)?.let { sheet.put("app_version", it) }
        // The 8 KB cap: drop sensors from the end, then cameras, until it fits.
        while (bytes(sheet) > MAX_BYTES) {
            val s = sheet.optJSONArray("sensors"); val c = sheet.optJSONArray("cameras")
            when {
                s != null && s.length() > 0 -> { s.remove(s.length() - 1); if (s.length() == 0) sheet.remove("sensors") }
                c != null && c.length() > 0 -> { c.remove(c.length() - 1); if (c.length() == 0) sheet.remove("cameras") }
                else -> break
            }
        }
        return sheet
    }

    fun bytes(o: JSONObject) = o.toString().toByteArray(Charsets.UTF_8).size

    /** The same JSON with object keys sorted, so equal sheets hash equally. */
    fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.opt(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
        is String -> JSONObject.quote(v)
        null -> "null"
        else -> if (v == JSONObject.NULL) "null" else v.toString()
    }

    /** SHA-256 of [canonical]: what was stored, so an unchanged sheet isn't sent again. */
    fun hash(sheet: JSONObject): String = MessageDigest.getInstance("SHA-256").digest(canonical(sheet).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    enum class Action { NONE, UPLOAD, DELETE }

    /** Whether the hourly check is due (a forced check always is). Pure. */
    fun due(lastCheckAt: Long, now: Long, force: Boolean) = force || lastCheckAt == 0L || now - lastCheckAt >= CHECK_EVERY_MS

    /** What one check does. Off with a deletion owed → delete; on and named with a new hash (or forced) → upload. Pure. */
    fun action(share: Boolean, named: Boolean, deletePending: Boolean, storedHash: String?, newHash: String?, force: Boolean): Action = when {
        !share -> if (deletePending && named) Action.DELETE else Action.NONE
        !named -> Action.NONE
        force || newHash != storedHash -> Action.UPLOAD
        else -> Action.NONE
    }

    // ---- Android lookups -------------------------------------------------------------------------------------------

    /** Reads the facts from Android. Nothing identifying: model-level hardware facts only. */
    fun collect(context: Context): Facts {
        val cams = runCatching {
            val cm = context.getSystemService(CameraManager::class.java)
            cm.cameraIdList.mapNotNull { id ->
                val c = cm.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"; CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"; else -> null
                } ?: return@mapNotNull null
                val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                Camera(facing, px?.let { (it.width.toLong() * it.height / 1_000_000.0).roundToInt() })
            }
        }.getOrDefault(emptyList())
        val maxKhz = runCatching {
            File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
                .mapNotNull { runCatching { File(it, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull() }.maxOrNull()
        }.getOrNull()
        val mem = runCatching {
            ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem / (1024 * 1024)
        }.getOrNull()
        val storage = runCatching { (StatFs(Environment.getDataDirectory().path).totalBytes / 1_000_000_000.0).roundToInt().toLong() }.getOrNull()
        val sensors = runCatching { context.getSystemService(SensorManager::class.java).getSensorList(Sensor.TYPE_ALL).map { it.stringType } }.getOrDefault(emptyList())
        return Facts(DisplayInfo.read(context), cams, Runtime.getRuntime().availableProcessors(), maxKhz?.let { (it / 1000).toInt() },
            Build.SUPPORTED_ABIS.firstOrNull(), mem, storage, sensors, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT, cc.bridgit.app.BuildConfig.VERSION_NAME)
    }

    /** The signed backend call (DeviceNames.call); replaceable in tests. */
    @Volatile internal var transport: (BridgeApp, String, String, JSONObject?) -> Pair<Int, JSONObject> = { app, m, p, b -> DeviceNames.call(app, m, p, b) }
    /** Builds the current sheet; replaceable in tests. */
    @Volatile internal var sheet: (BridgeApp) -> JSONObject = { app -> build(collect(app)) }
    @Volatile internal var lastCheckAt = 0L

    fun shared(app: BridgeApp) = app.platform.store.setting(SHARE_KEY, "1") != "0"
    private fun named(app: BridgeApp) = DeviceNames.cached(app) != null

    /** The hourly check from the service loop (and, forced, right after the name is registered). Silent on failure. */
    fun syncIfDue(app: BridgeApp, force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!due(lastCheckAt, now, force)) return null
        lastCheckAt = now
        val store = app.platform.store
        val share = shared(app); val isNamed = named(app)
        val current = if (share && isNamed) runCatching { sheet(app) }.getOrNull() ?: return null else null
        return when (action(share, isNamed, store.setting(DELETE_KEY) == "1", store.setting(HASH_KEY).ifEmpty { null }, current?.let(::hash), force)) {
            Action.UPLOAD -> upload(app, current!!)
            Action.DELETE -> delete(app)
            Action.NONE -> if (!isNamed) "not_named" else "unchanged"
        }
    }

    /** After a name is registered: store the sheet now, off the caller's thread. */
    fun afterRegistration(app: BridgeApp) { Thread({ runCatching { syncIfDue(app, force = true) } }, "bridgit-specs").start() }

    private fun upload(app: BridgeApp, sheet: JSONObject): String {
        val (code, json) = runCatching { transport(app, "PUT", PATH, sheet) }.getOrNull() ?: return "failed"
        if (code in 200..299) {
            app.platform.store.setting(HASH_KEY, hash(sheet), true)
            app.platform.store.setting(AT_KEY, json.optString("specs_updated_at").ifEmpty { java.time.Instant.now().toString() }, true)
            return "uploaded"
        }
        return if (code == 404 && json.optString("error") == "no_name") "not_named" else "failed"
    }

    private fun delete(app: BridgeApp): String {
        val (code, json) = runCatching { transport(app, "DELETE", PATH, null) }.getOrNull() ?: return "pending"
        if (code in 200..299 || (code == 404 && json.optString("error") in setOf("no_name", "no_specs"))) {
            app.platform.store.setting(DELETE_KEY, "", true); return "deleted"
        }
        return "pending"
    }

    /**
     * The owner's switch (Settings › Team and the configuration API, same logic). Off deletes the stored sheet (retried
     * later when the backend can't be reached); on stores the current sheet now. Returns the backend outcome:
     * uploaded, unchanged, deleted, pending (deletion owed), not_named or failed. Does network: call off the main thread.
     */
    fun setShare(app: BridgeApp, on: Boolean): String {
        val store = app.platform.store
        store.setting(SHARE_KEY, if (on) "1" else "0", true)
        if (on) { store.setting(DELETE_KEY, "", true); return syncIfDue(app, force = true) ?: "unchanged" }
        store.setting(HASH_KEY, "", true); store.setting(AT_KEY, "", true)
        store.setting(DELETE_KEY, "1", true)
        if (!named(app)) { store.setting(DELETE_KEY, "", true); return "not_named" }
        return delete(app)
    }

    /** Stores the current sheet now (refresh). Needs sharing on. */
    fun refresh(app: BridgeApp): String {
        if (!shared(app)) throw ErrorCatalog.error("invalid_parameter", "Spec sharing is off; send {\"share\":true} to turn it on.")
        return syncIfDue(app, force = true) ?: "unchanged"
    }

    /** Local state, and with [remote] the copy stored with the team backend (GET /v1/names/me/specs). */
    fun status(app: BridgeApp, remote: Boolean): JSONObject {
        val store = app.platform.store
        val name = DeviceNames.cached(app)?.optString("name")
        val o = JSONObject().put("share", shared(app)).put("name", name ?: JSONObject.NULL)
            .put("specs", runCatching { sheet(app) }.getOrNull() ?: JSONObject.NULL)
            .put("uploaded_at", store.setting(AT_KEY).ifEmpty { null } ?: JSONObject.NULL)
            .put("delete_pending", store.setting(DELETE_KEY) == "1")
            .put("lookup", if (name != null) "GET ${TeamKeys.publicBase}/v1/names/$name/specs" else JSONObject.NULL)
            .put("how", "$OPERATION {\"share\":false} deletes the stored sheet and stops sharing; {\"share\":true} or {\"refresh\":true} stores the current sheet now")
        if (!remote) return o
        if (name == null) return o.put("stored", JSONObject().put("available", false).put("reason", "no_name"))
        val stored = runCatching { transport(app, "GET", PATH, null) }.fold({ (code, json) ->
            if (code in 200..299) JSONObject().put("available", true).put("specs", json.opt("specs") ?: JSONObject.NULL)
                .put("specs_updated_at", json.opt("specs_updated_at") ?: JSONObject.NULL)
            else JSONObject().put("available", false).put("reason", json.optString("error").ifEmpty { "http_$code" })
        }, { e -> JSONObject().put("available", false).put("reason", (e as? cc.bridgit.app.api.ApiError)?.code ?: "team_unavailable") })
        return o.put("stored", stored)
    }

    /** The agent hint (capabilities and health, inside bridgit_name): ask Bridgit for static specs, the phone for live state. */
    fun hint(name: String?, shared: Boolean): String = when {
        name == null -> "Once named, this Bridgit stores its static specs (screen size, aspect ratio, cameras) with Bridgit for agents to read without the phone."
        !shared -> "The owner switched spec sharing off: read static specs from GET /v1/device/display on the phone."
        else -> "For static specs (screen size, aspect ratio, cameras) ask Bridgit first: GET ${TeamKeys.publicBase}/v1/names/$name/specs; query the phone only for live state. " +
            "Compose full-screen images to its full_screen_image size and post them in an html card with presentation \"full_screen\"."
    }
}
