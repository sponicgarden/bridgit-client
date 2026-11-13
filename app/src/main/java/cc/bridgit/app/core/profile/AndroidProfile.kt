package cc.bridgit.app.core.profile

import android.content.pm.PackageManager
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.core.Agent
import cc.bridgit.app.capability.appVersion
import org.json.JSONObject

/** Gathers this Android device's facts for [ProfileBuilder] and [SetupState]. */
object AndroidProfile {
    private const val REVISION_KEY = "client_core_profile_revision"
    private const val DIAGNOSTIC_KEY = "client_core_diagnostic_completed_at"

    /** When Device Diagnostics last played to the end (0 = never). A recorded fact, not a setting; the "diagnostic" step reads it. */
    fun diagnosticCompletedAt(app: BridgeApp): Long = app.platform.store.setting(DIAGNOSTIC_KEY).toLongOrNull() ?: 0L
    fun diagnosticCompleted(app: BridgeApp, at: Long = System.currentTimeMillis()) = app.platform.store.setting(DIAGNOSTIC_KEY, at.toString(), true)
    private val SENSORS = setOf("camera", "mic", "video", "sounds", "wakewords", "location", "screen_share")

    fun setupFacts(app: BridgeApp): SetupState.Facts {
        val listener = app.listener.value
        // Control agents only, as Home counts them: not applet principals, viewers, hubs or satellites' return links.
        val agents = runCatching { cc.bridgit.app.ui.home.HomeStates.controlAgents(app).size }.getOrDefault(0)
        val missing = missingPermissions(app)
        val granted = { p: String -> app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }
        return SetupState.Facts(
            deviceNamed = cc.bridgit.app.feedback.DeviceNames.cached(app)?.optString("name").isNullOrEmpty().not(),
            listenerBound = listener.listening,
            relayOn = runCatching { cc.bridgit.app.relay.RelayStore(app).enabled }.getOrDefault(false),
            agents = agents,
            emailVerified = app.settings.emailVerifiedAt > 0L,
            emailPending = app.settings.emailVerifiedAt <= 0L && app.settings.alertEmail != null,
            notificationsOn = app.captureNotificationsOn(),
            sensorsWithheld = listener.running && listener.startedInBackground &&
                (granted(android.Manifest.permission.CAMERA) || granted(android.Manifest.permission.RECORD_AUDIO)),
            missingPermissions = missing,
            batteryRestricted = cc.bridgit.app.ui.home.UiActions.batteryOptimised(app),
            diagnosticDone = diagnosticCompletedAt(app) > 0L,
        )
    }

    /**
     * The OS-blocked set-up steps only (notifications, open_app, permissions, battery), from in-memory and OS reads:
     * Home's banners call this on each render, so it skips the database reads the full list makes.
     */
    fun osSteps(app: BridgeApp): Map<String, SetupState.Step> {
        val listener = app.listener.value
        val granted = { p: String -> app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }
        val missing = missingPermissions(app)
        val facts = SetupState.Facts(deviceNamed = true, listenerBound = true, relayOn = true, agents = 1, emailVerified = true, emailPending = false,
            notificationsOn = app.captureNotificationsOn(),
            sensorsWithheld = listener.running && listener.startedInBackground &&
                (granted(android.Manifest.permission.CAMERA) || granted(android.Manifest.permission.RECORD_AUDIO)),
            missingPermissions = missing, batteryRestricted = cc.bridgit.app.ui.home.UiActions.batteryOptimised(app), diagnosticDone = true)
        return SetupState.steps(facts).filter { it.id in OS_STEPS }.associateBy { it.id }
    }
    private val OS_STEPS = setOf("notifications", "open_app", "permissions", "battery")

    /** The real permission names to request for switched-on features, as Home's banner asks for them (blocked reasons carry a short name). */
    private fun missingPermissions(app: BridgeApp): List<String> = app.registry.switchable().flatMap { m ->
        val reason = m.blockedReason()
        if (m.enabled && reason?.startsWith("permission:") == true) cc.bridgit.app.ui.home.UiActions.permissionsFor(m).toList() else emptyList()
    }

    private fun hardware(app: BridgeApp): Map<String, String> {
        val pm = app.packageManager
        fun has(feature: String) = if (pm.hasSystemFeature(feature)) "present" else "absent"
        return mapOf("camera" to has(PackageManager.FEATURE_CAMERA_ANY), "microphone" to has(PackageManager.FEATURE_MICROPHONE),
            "speaker" to has(PackageManager.FEATURE_AUDIO_OUTPUT), "location" to has(PackageManager.FEATURE_LOCATION))
    }

    fun profile(app: BridgeApp, agent: Agent?, nowMs: Long = System.currentTimeMillis()): JSONObject {
        val names = cc.bridgit.app.feedback.DeviceNames.cached(app)
        val device = ProfileBuilder.Device(
            installId = runCatching { cc.bridgit.app.feedback.InstallIdentity.status(app)?.installId }.getOrNull(),
            relayDeviceId = runCatching { cc.bridgit.app.relay.RelayStore(app).device }.getOrNull(),
            name = names?.optString("friendly_name")?.takeIf { it.isNotEmpty() && it != "null" } ?: android.os.Build.MODEL,
            deviceType = cc.bridgit.app.platform.DeviceType.of(app).wire,
            osVersion = android.os.Build.VERSION.RELEASE ?: "",
            appVersion = app.appVersion(),
            apiRevision = cc.bridgit.app.api.ApiChanges.CURRENT,
            services = listOf("host"),
            hardware = hardware(app),
        )
        val ops = Endpoints.all.groupBy { it.capability }
        val caps = app.registry.modules.map { m ->
            val st = m.status()
            ProfileBuilder.Capability(m.id, enabled = m.switchKey == null || st.enabled, ready = st.ready,
                agentAllowed = agent?.allows(m.id) ?: false, blockedReason = st.blockedReason,
                execution = if (m.id in SENSORS) ProfileBuilder.SENSOR_EXECUTION else emptyList(),
                // Caller-filtered: an operation that needs a power the caller lacks (Fixer Mode, Super Powers) isn't listed.
                operations = (ops[m.id] ?: emptyList()).filter { spec -> spec.power == null || agent?.has(spec.power) == true }
                    .map { it.method to it.fullPath })
        }
        val setup = SetupState.json(setupFacts(app))
        val online = app.settings.listenerEnabled
        val inForeground = runCatching {
            android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }.importance ==
                android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }.getOrDefault(false)
        val body = ProfileBuilder.build(0, nowMs, device, online, inForeground, caps, setup)
        // The revision follows what the device is, not who asks: the per-caller fields and foreground state are left out.
        // Digest every operation and the device's own blocked reason (agent_allowed true leaves blocked_reason as the device's).
        val stable = ProfileBuilder.build(0, 0L, device, online, false,
            caps.map { c -> c.copy(agentAllowed = true, operations = (ops[c.id] ?: emptyList()).map { it.method to it.fullPath }) }, setup)
        return body.put("profile_revision", revision(app, stable))
    }

    /** Goes up by one whenever what the device reports (apart from times and caller-specific fields) changes. */
    @Synchronized
    private fun revision(app: BridgeApp, body: JSONObject): Int {
        val copy = JSONObject(body.toString()).apply { remove("observed_at"); remove("expires_at"); remove("profile_revision") }
        val digest = cc.bridgit.app.feedback.InstallAuth.hex(cc.bridgit.app.feedback.InstallAuth.sha256(copy.toString().toByteArray()))
        val saved = runCatching { JSONObject(app.platform.store.setting(REVISION_KEY)) }.getOrNull()
        if (saved?.optString("digest") == digest) return saved.optInt("revision")
        val next = (saved?.optInt("revision") ?: 0) + 1
        app.platform.store.setting(REVISION_KEY, JSONObject().put("digest", digest).put("revision", next).toString(), true)
        return next
    }

    /** For /v1/health: how many set-up steps are not done, and where the list is. */
    fun healthSetup(app: BridgeApp): JSONObject = JSONObject()
        .put("not_done", runCatching { SetupState.steps(setupFacts(app)).count { it.state != SetupState.DONE } }.getOrDefault(0))
        .put("profile", "/v1/device/profile")
}
