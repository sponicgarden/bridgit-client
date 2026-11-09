package cc.bridgit.app.platform

import android.content.Context
import android.os.Build
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import org.json.JSONArray
import org.json.JSONObject

/**
 * This device's profile (A0116): what it is (device type, model) and what it is for (one or more purposes), and the
 * permission defaults it inherits from its (device type, purpose) pairs. The profile is kept here first (so it works
 * offline and the Agents and Linked Devices tab always shows it) and mirrored to the team backend
 * (PATCH /v1/device-profiles/me), which holds the default tables, resolves them (explicit off wins) and lets the
 * owner's other devices read it. Defaults only ever change switches nobody set ([cc.bridgit.app.core.Settings.isSpecific]).
 */
class DeviceProfile(private val app: BridgeApp) {
    data class Profile(
        val purposes: List<String>,
        val deviceType: String,
        val model: String,
        /** The backend's generation of the copy last synced; 0 before the first sync. */
        val generation: Int,
        /** False while a local change hasn't reached the backend yet. */
        val synced: Boolean,
        /** Permission id to the default its (type, purpose) pairs give it; ids not listed fall back to their baseline. */
        val defaults: Map<String, Boolean>,
        val updatedAt: Long,
        val updatedBy: String,
    ) {
        fun json(): JSONObject = JSONObject().put("purposes", JSONArray(purposes)).put("device_type", deviceType).put("model", model)
            .put("platform", PLATFORM).put("generation", generation).put("synced", synced)
            .put("inherited_defaults", JSONObject().apply { defaults.toSortedMap().forEach { (k, v) -> put(k, if (v) "on" else "off") } })
            .put("updated_at", if (updatedAt > 0) updatedAt else JSONObject.NULL).put("updated_by", updatedBy)
    }

    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(): Profile {
        val p = prefs
        return Profile(
            purposes = p.getString("purposes", null)?.let { s -> runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull() } ?: emptyList(),
            deviceType = p.getString("device_type", null) ?: DeviceType.of(app).wire,
            model = p.getString("model", null) ?: defaultModel(),
            generation = p.getInt("generation", 0),
            synced = p.getBoolean("synced", false),
            defaults = p.getString("defaults", null)?.let { s ->
                runCatching { JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getBoolean(it) } } }.getOrNull()
            } ?: emptyMap(),
            updatedAt = p.getLong("updated_at", 0L),
            updatedBy = p.getString("updated_by", null) ?: "device",
        )
    }

    /**
     * Changes the profile with the backend's own rules (one validation for the tab, the API and the backend).
     * Null leaves a field as it is. Returns the names of the fields that changed (empty: a safe retry, nothing done).
     */
    @Synchronized
    fun update(purposes: List<String>?, deviceType: String?, model: String?, by: String): List<String> {
        val now = get()
        val nextPurposes = purposes?.let { Rules.purposes(it) } ?: now.purposes
        val nextType = deviceType?.let { Rules.type(it) } ?: now.deviceType
        val nextModel = model?.let { Rules.model(it) } ?: now.model
        val changed = listOfNotNull("purposes".takeIf { nextPurposes != now.purposes }, "device_type".takeIf { nextType != now.deviceType },
            "model".takeIf { nextModel != now.model })
        if (changed.isEmpty()) return changed
        prefs.edit().putString("purposes", JSONArray(nextPurposes).toString()).putString("device_type", nextType).putString("model", nextModel)
            .putBoolean("synced", false).putLong("updated_at", System.currentTimeMillis()).putString("updated_by", by).apply()
        app.log.system("audit", "setting", "$by changed this device's profile: ${changed.joinToString()}")
        return changed
    }

    /**
     * Brings the profile and its defaults up to date with the backend, then applies the defaults. A local change goes
     * up (PATCH); otherwise a newer copy changed from another of the owner's devices comes down. Blocking: call off the
     * main thread. Throws the backend's error (team_unavailable when offline); the local profile stays as it is.
     */
    fun sync(): Profile {
        val local = get()
        val res = if (!local.synced) transport(app, "PATCH", PATH, JSONObject().put("purposes", JSONArray(local.purposes))
            .put("device_type", local.deviceType).put("model", local.model).put("platform", PLATFORM))
        else transport(app, "GET", PATH, null)
        if (res.first !in 200..299) {
            val e = res.second
            throw ErrorCatalog.error(if (res.first == 400) "invalid_parameter" else "upstream_failed", e.optString("message").ifEmpty { "The team backend said HTTP ${res.first}" })
        }
        adopt(res.second.optJSONObject("profile") ?: JSONObject(), local)
        applyDefaults()
        return get()
    }

    @Synchronized
    private fun adopt(remote: JSONObject, sent: Profile) {
        val current = get()
        val e = prefs.edit().putInt("generation", remote.optInt("generation", current.generation))
        // A newer change made here while the call was out stays unsynced, so it goes up next time.
        if (current == sent) {
            e.putBoolean("synced", true)
            val purposes = remote.optJSONArray("purposes")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            if (sent.synced && purposes != null) {
                // Another of the owner's devices (or bridgit.cc) changed this profile.
                e.putString("purposes", JSONArray(runCatching { Rules.purposes(purposes) }.getOrDefault(current.purposes)).toString())
                remote.optString("device_type").takeIf { it in Rules.TYPES }?.let { e.putString("device_type", it) }
                remote.optString("model").takeIf { it.isNotBlank() && !remote.isNull("model") }?.let { m -> runCatching { Rules.model(m) }.getOrNull()?.let { e.putString("model", it) } }
            }
        }
        val defaults = JSONObject()
        remote.optJSONArray("defaults")?.let { a ->
            for (i in 0 until a.length()) {
                val d = a.optJSONObject(i) ?: continue
                if (d.optString("decided_by") == "pair" && d.optString("default") in setOf("on", "off")) defaults.put(d.getString("id"), d.getString("default") == "on")
            }
        }
        e.putString("defaults", defaults.toString()).apply()
    }

    /**
     * Applies the inherited defaults to every switch nobody set: on/off where a pair says so, the switch's own baseline
     * where no pair does any more (a purpose was removed). Returns the switch keys that changed.
     */
    fun applyDefaults(): List<String> {
        val defaults = get().defaults
        val changed = mutableListOf<String>()
        for (m in app.registry.switchable()) {
            val key = m.switchKey ?: continue
            if (app.settings.isSpecific(key)) continue
            val want = defaults[m.id]
            if (want != null) { if (app.settings.applyDefault(key, want)) changed += key }
            else if (app.settings.isOn(key) != (key in cc.bridgit.app.core.Settings.DEFAULT_ON)) { app.settings.clearSpecific(key, null); changed += key }
        }
        if (changed.isNotEmpty()) app.log.system("audit", "setting", "Device profile defaults changed: ${changed.sorted().joinToString()}")
        return changed
    }

    /** The default this device's pairs give a permission, or null when none says anything (A0116). */
    fun inherited(id: String): Boolean? = get().defaults[id]

    private fun defaultModel(): String = runCatching { Rules.model(Build.MODEL.orEmpty().ifBlank { "Android device" }) }.getOrDefault("Android device")

    /** The backend's profile rules (feedback/src/device-profiles.mjs and its vocabulary), the same for the tab and the API. */
    object Rules {
        val TYPES = listOf("phone", "tablet", "computer", "tv", "watch", "camera", "other")
        /** The built-in purposes, in the order the profile picker offers them. Others are allowed by id through the API. */
        val PURPOSES = listOf("primary", "dedicated", "network_controller", "monitor", "shared")
        val PURPOSE = Regex("^[a-z][a-z0-9_]{1,31}$")
        const val MAX_PURPOSES = 8
        const val MODEL_CHARS = 80

        fun purposes(list: List<String>): List<String> {
            val out = list.distinct()
            if (out.size > MAX_PURPOSES) throw ErrorCatalog.error("invalid_parameter", "A device has at most $MAX_PURPOSES purposes")
            out.firstOrNull { !PURPOSE.matches(it) }?.let { throw ErrorCatalog.error("invalid_parameter", "Unknown purpose format: use short ids like ${PURPOSES.joinToString()}") }
            return out
        }
        fun type(v: String): String = v.takeIf { it in TYPES } ?: throw ErrorCatalog.error("invalid_parameter", "device_type is one of: ${TYPES.joinToString()}")
        fun model(v: String): String {
            val s = v.replace(Regex("[\\u0000-\\u001f\\u007f]"), "").trim()
            if (s.isEmpty() || s.length > MODEL_CHARS) throw ErrorCatalog.error("invalid_parameter", "model is 1-$MODEL_CHARS characters")
            return s
        }
    }

    companion object {
        const val PREFS = "device_profile"
        const val PLATFORM = "android"
        const val PATH = "/v1/device-profiles/me"
        /** The signed team-backend call; tests replace it. */
        @Volatile var transport: (BridgeApp, String, String, JSONObject?) -> Pair<Int, JSONObject> =
            { a, m, p, b -> cc.bridgit.app.feedback.DeviceNames.call(a, m, p, b) }
    }
}
