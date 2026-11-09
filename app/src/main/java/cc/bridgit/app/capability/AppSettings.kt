package cc.bridgit.app.capability

import android.app.NotificationManager
import android.media.AudioManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Reply
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Power
import cc.bridgit.app.api.PowerGate
import cc.bridgit.app.core.SettingDef
import cc.bridgit.app.core.SettingLock
import cc.bridgit.app.core.SettingsCatalog
import cc.bridgit.app.interact.InteractionNotifier
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads and changes every setting in [SettingsCatalog] (SET-01). Changes run the same side effects as the
 * phone's Settings screen (which calls these too), so a change made by an agent and one made by the owner
 * behave the same. Each change is written to the audit log.
 */
class SettingsController(private val app: BridgeApp) {
    private val prefs get() = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    private val audio get() = app.getSystemService(AudioManager::class.java)
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    fun defs(): List<SettingDef> = SettingsCatalog.all(
        app.registry.switchable().map { it.switchKey!! to it.title },
        channels().map { it.id to it.name.toString() },
        app.registry.modules.flatMap { it.permissions },
    ).filter { available(it.key) }

    fun def(key: String): SettingDef? = defs().firstOrNull { it.key == key }

    /** Settings this build doesn't have (no crash or feedback backend configured) are left out. */
    private fun available(key: String) = when (key) {
        "privacy.crash_reports" -> cc.bridgit.app.core.CrashReports.configured
        "privacy.feedback_upload" -> cc.bridgit.app.feedback.FeedbackUploader.configured
        "privacy.remote_view" -> cc.bridgit.app.feedback.FeedbackUploader.configured
        else -> true
    }

    private fun channels() = nm.notificationChannels.filter { !it.id.startsWith("miscellaneous") }.sortedBy { it.id }

    private fun streamOf(name: String) = when (name) {
        "media" -> AudioManager.STREAM_MUSIC; "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM; else -> AudioManager.STREAM_NOTIFICATION
    }

    private fun system(key: String, default: Int) = runCatching { Settings.System.getInt(app.contentResolver, key) }.getOrDefault(default)

    private fun serviceOn(secureKey: String) = Settings.Secure.getString(app.contentResolver, secureKey).orEmpty()
        .split(':').any { it.substringBefore('/') == app.packageName }

    fun dndAccess() = nm.isNotificationPolicyAccessGranted
    fun canWriteSystem() = Settings.System.canWrite(app)

    /** The current value of [key], for [agent] where it's about the caller. JSONObject.NULL when there is none. */
    fun read(key: String, agent: Agent?): Any {
        val s = app.settings
        val section = key.substringBefore('.')
        val name = key.substringAfter('.')
        return when (section) {
            "features" -> s.isOn(name)
            "notifications" -> channels().firstOrNull { it.id == name }?.let {
                if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) "off" else SettingsCatalog.importance(it.importance)
            } ?: JSONObject.NULL
            else -> when (key) {
                "device.name" -> app.platform.store.setting("device_name", "")
                "device.resource_monitor" -> s.resourceMonitor
                "device.resource_monitor_interval" -> s.resourceMonitorInterval
                "listener.enabled" -> s.listenerEnabled
                "listener.port" -> s.port
                "listener.allow_internet_callbacks" -> s.allowInternetCallbacks
                "privacy.log_visibility" -> s.logVisibility
                "privacy.outbound_log" -> s.outboundLog
                "privacy.pause_external_calls" -> s.externalCallsPaused
                "privacy.lockscreen_text" -> prefs.getBoolean(InteractionNotifier.PREF_LOCKSCREEN_TEXT, false)
                "privacy.speak_urgent_in_dnd" -> prefs.getBoolean(InteractionNotifier.PREF_SPEAK_URGENT_IN_DND, false)
                "privacy.open_on_arrival" -> prefs.getBoolean(InteractionNotifier.PREF_OPEN_ON_ARRIVAL, true)
                "privacy.crash_reports" -> cc.bridgit.app.core.CrashReports.enabled(app)
                "privacy.usage_tallies" -> app.usage.enabled()
                "privacy.feedback_upload" -> s.feedbackUpload
                "privacy.remote_view" -> app.remoteView.enabled
                "log.retention_entries" -> s.retentionEntries
                "log.retention_days" -> s.retentionDays
                "alerts.frequency" -> s.alertFrequency
                "alerts.email" -> s.alertEmail ?: JSONObject.NULL
                "alerts.state" -> s.alertState
                "appearance.theme" -> s.appearance
                "speaker.volume_ceiling" -> s.speakerCeiling
                "listening.wake_words" -> runCatching { JSONArray(s.wakeWords) }.getOrDefault(JSONArray())
                "listening.sounds" -> s.soundsConfig.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL
                "captures.agent_files" -> s.capturesAgentFiles
                "captures.agent_upload" -> s.capturesAgentUpload
                "captures.max_keep_minutes" -> s.capturesMaxKeepMinutes
                "captures.any_agent_can_fetch" -> s.capturesShareAll
                "captures.owner_files" -> s.capturesOwnerFiles
                "captures.locked_camera_approval" -> s.capturesLockedCameraApproval
                "security.device_lock" -> s.securityDeviceLock
                "security.new_place_alerts" -> s.securityPlaceAlerts
                "security.biometric" -> s.securityBiometric
                "security.relay_signed_only" -> s.securityRelaySignedOnly
                // The URL may carry a token, so agents only learn whether there is one.
                "captures.owner_upload_url" -> if (s.capturesOwnerUploadUrl != null) "set" else JSONObject.NULL
                "phone.dnd" -> SettingsCatalog.filterToDnd(nm.currentInterruptionFilter)
                "phone.ringer_mode" -> SettingsCatalog.modeToRinger(audio.ringerMode)
                "phone.brightness" -> SettingsCatalog.levelToBrightness(system(Settings.System.SCREEN_BRIGHTNESS, 128))
                "phone.brightness_auto" -> system(Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1
                "phone.screen_timeout_seconds" -> system(Settings.System.SCREEN_OFF_TIMEOUT, 30_000) / 1000
                "phone.auto_rotate" -> system(Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                "android.notifications" -> app.captureNotificationsOn()
                "android.battery_unrestricted" -> app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true
                "android.display_over_apps" -> Settings.canDrawOverlays(app)
                "android.accessibility" -> serviceOn(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                "android.notification_access" -> serviceOn("enabled_notification_listeners")
                "android.dnd_access" -> dndAccess()
                "android.modify_system_settings" -> canWriteSystem()
                "agent.name" -> agent?.name ?: JSONObject.NULL
                "agent.fixer_mode" -> agent?.fixer ?: false
                "agent.super_powers" -> agent?.superPowers ?: false
                "agent.capabilities" -> agent?.caps?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL
                else -> when {
                    key.startsWith("phone.volume_") -> streamOf(key.removePrefix("phone.volume_")).let {
                        SettingsCatalog.stepToPercent(audio.getStreamVolume(it), audio.getStreamMaxVolume(it))
                    }
                    key.startsWith("android.permission.") -> app.granted("android.permission." + key.removePrefix("android.permission."))
                    else -> JSONObject.NULL
                }
            }
        }
    }

    /**
     * Why [def] can't be changed right now, beyond its fixed lock: a phone setting whose Android access is off,
     * or Phone controls switched off. Null when it can be changed.
     */
    fun blocker(def: SettingDef): String? = when {
        def.section != "phone" -> null
        !app.settings.isOn("controls") -> "controls_off"
        def.key == "phone.dnd" || def.key == "phone.ringer_mode" -> if (dndAccess()) null else "dnd_access_off"
        def.key.startsWith("phone.volume_") -> null
        else -> if (canWriteSystem()) null else "write_settings_off"
    }

    /** Describes one setting for GET /v1/settings. */
    fun describe(def: SettingDef, agent: Agent?, canWrite: Boolean): JSONObject {
        val o = JSONObject().put("key", def.key).put("section", def.section).put("type", def.type)
            .put("value", runCatching { read(def.key, agent) }.getOrDefault(JSONObject.NULL))
            .put("description", def.description)
        cc.bridgit.app.ownerapi.ConfigurationSettings.pathFor(def)?.let { url ->
            o.put("owner_configuration", JSONObject().put("url", url).put("scope", "owner.configuration").put("approval_required", false))
        }
        def.enum?.let { o.put("enum", JSONArray(it)) }
        def.min?.let { o.put("min", if (def.type == "integer") it.toInt() else it) }
        def.max?.let { o.put("max", if (def.type == "integer") it.toInt() else it) }
        val blocked = def.lock?.id ?: blocker(def)
        o.put("writable", def.writable && blocker(def) == null && canWrite)
        if (blocked != null) o.put("locked_reason", blocked).put("why", def.lock?.why ?: blockerWhy(blocked))
        return o
    }

    fun blockerWhy(b: String) = when (b) {
        "controls_off" -> "Phone controls is switched off on the phone (features.controls)."
        "dnd_access_off" -> "Bridgit doesn't have Do Not Disturb access (android.dnd_access). The owner grants it in Bridgit under More > Software access > Files and Android access."
        "write_settings_off" -> "Bridgit isn't allowed to modify system settings (android.modify_system_settings). The owner grants it in Bridgit under More > Software access > Files and Android access."
        else -> b
    }

    /** Applies one checked value. Must run on the main thread (some changes restart services or redraw the app). */
    fun apply(key: String, value: Any, by: String = "owner") {
        val s = app.settings
        when {
            key.startsWith("features.") -> {
                val module = app.registry.switchable().first { it.switchKey == key.removePrefix("features.") }
                cc.bridgit.app.ui.home.UiActions.setSwitch(app, module, value as Boolean)
            }
            key == "listener.enabled" -> { s.listenerEnabled = value as Boolean; if (value) cc.bridgit.app.BridgeService.start(app) else cc.bridgit.app.BridgeService.pause(app) }
            key == "listener.port" -> { s.port = value as Int; if (s.listenerEnabled) cc.bridgit.app.BridgeService.running?.poke("port changed") }
            key == "listener.allow_internet_callbacks" -> s.allowInternetCallbacks = value as Boolean
            key == "privacy.pause_external_calls" -> s.externalCallsPaused = value as Boolean
            key == "captures.agent_files" -> s.capturesAgentFiles = value as Boolean
            key == "captures.agent_upload" -> s.capturesAgentUpload = value as Boolean
            key == "captures.max_keep_minutes" -> s.capturesMaxKeepMinutes = value as Int
            key == "captures.any_agent_can_fetch" -> s.capturesShareAll = value as Boolean
            key == "captures.owner_files" -> s.capturesOwnerFiles = value as Boolean
            key == "captures.locked_camera_approval" -> s.capturesLockedCameraApproval = value as Boolean
            key == "device.name" -> cc.bridgit.app.platform.DeviceName.set(app, value as String)
            // A0189/A0190: the monitor follows these through Settings' listener (monitor.ResourceMonitor.sync).
            key == "device.resource_monitor" -> s.resourceMonitor = value as Boolean
            key == "device.resource_monitor_interval" -> s.resourceMonitorInterval = value as String
            key == "privacy.log_visibility" -> s.logVisibility = value as String
            key == "privacy.outbound_log" -> s.setOutboundLog(value as Boolean, by)
            key == "privacy.lockscreen_text" -> prefs.edit().putBoolean(InteractionNotifier.PREF_LOCKSCREEN_TEXT, value as Boolean).apply()
            key == "privacy.speak_urgent_in_dnd" -> prefs.edit().putBoolean(InteractionNotifier.PREF_SPEAK_URGENT_IN_DND, value as Boolean).apply()
            key == "privacy.open_on_arrival" -> prefs.edit().putBoolean(InteractionNotifier.PREF_OPEN_ON_ARRIVAL, value as Boolean).apply()
            key == "privacy.crash_reports" -> setCrashReports(value as Boolean)
            key == "privacy.usage_tallies" -> app.usage.setEnabled(value as Boolean)
            key == "privacy.feedback_upload" -> setFeedbackUpload(value as Boolean)
            key == "log.retention_entries" -> s.retentionEntries = value as Int
            key == "log.retention_days" -> s.retentionDays = value as Int
            key == "alerts.frequency" -> { s.alertFrequency = value as String; Thread { runCatching { app.alerts.poke() } }.start() }
            key == "appearance.theme" -> setAppearance(value as String)
            key == "speaker.volume_ceiling" -> s.speakerCeiling = value as Int
            key == "phone.dnd" -> app.hardware.change(mapOf("dnd" to SettingsCatalog.dndToFilter(value as String)), null)
            key == "phone.ringer_mode" -> app.hardware.change(mapOf("ringer" to SettingsCatalog.ringerToMode(value as String)), null)
            key.startsWith("phone.volume_") -> {
                val stream = streamOf(key.removePrefix("phone.volume_"))
                app.hardware.change(mapOf("volume:$stream" to SettingsCatalog.percentToStep(value as Int, audio.getStreamMaxVolume(stream))), null)
            }
            key == "phone.brightness" -> app.hardware.change(linkedMapOf(Settings.System.SCREEN_BRIGHTNESS_MODE to 0,
                Settings.System.SCREEN_BRIGHTNESS to SettingsCatalog.brightnessToLevel(value as Double)), null)
            key == "phone.brightness_auto" -> app.hardware.change(mapOf(Settings.System.SCREEN_BRIGHTNESS_MODE to if (value as Boolean) 1 else 0), null)
            key == "phone.screen_timeout_seconds" -> app.hardware.change(mapOf(Settings.System.SCREEN_OFF_TIMEOUT to (value as Int) * 1000), null)
            key == "phone.auto_rotate" -> app.hardware.change(mapOf(Settings.System.ACCELEROMETER_ROTATION to if (value as Boolean) 1 else 0), null)
            else -> error("No way to apply $key")
        }
    }

    /** D32: crash reports are on unless turned off. */
    fun setCrashReports(on: Boolean) {
        prefs.edit().putBoolean(cc.bridgit.app.core.CrashReports.PREF, !on).apply()
        if (on) cc.bridgit.app.core.CrashReports.start(app) else cc.bridgit.app.core.CrashReports.stop()
    }

    /** FBK-14: on sends what's waiting now; off keeps anything not yet sent on the phone. */
    fun setFeedbackUpload(on: Boolean) {
        app.settings.feedbackUpload = on
        if (on) { cc.bridgit.app.feedback.FeedbackUploadWorker.enqueue(app); cc.bridgit.app.feedback.FeedbackStatusSyncWorker.runNow(app) }
        else Thread { runCatching { app.feedback.stopPendingUploads() } }.start()
    }

    fun setAppearance(theme: String) {
        app.settings.appearance = theme
        app.applyAppearance()
    }

    /** The value as a short word for the audit log. */
    fun show(v: Any?): String = when (v) {
        true -> app.getString(R.string.home_on)
        false -> app.getString(R.string.home_off)
        null, JSONObject.NULL -> "none"
        else -> v.toString()
    }
}

/**
 * App settings (SET-01..05): every setting in Bridgit and the phone settings it manages, as one API section.
 * Any agent with the grant can read them; only agents with Agent Super Powers can change them, and only while the owner
 * has this switch on (off by default, SEC-03). Settings that would cut agents off or widen what they may do
 * stay phone-only and are listed with a locked_reason.
 */
class AppSettingsModule(app: BridgeApp) : Capability(app) {
    override val id = "settings"
    override val titleRes = R.string.cap_settings
    override val descriptionRes = R.string.cap_settings_desc
    override val group = "control"
    override val switchKey = SettingsCatalog.GATE_SWITCH
    override val endpoints = listOf(Endpoints.SETTINGS_GET, Endpoints.SETTINGS_SET) + SettingsConfigurationApi.ALL

    private val controller by lazy { SettingsController(app) }

    override fun details() = JSONObject().put("agents_can_change", enabled).put("writes", "agents with Agent Super Powers")

    /** Null when [agent] may change settings now; otherwise the reason. */
    private fun writeBlocker(agent: Agent?): String? = when {
        !enabled -> "settings_api_off"
        agent?.superPowers != true -> Power.SUPER.reason
        else -> null
    }

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(SettingsConfigurationApi.KEYS_GET) {
            val keys = cc.bridgit.app.judgment.OwnKeys(app)
            Reply.Json(JSONObject().put("providers", JSONArray(cc.bridgit.app.judgment.OwnKeys.PROVIDERS.map {
                JSONObject().put("provider",it.id).put("configured",keys.has(it.id)).put("readable",false).put("field_count",it.fields.size)
            })))
        }
        endpoint(SettingsConfigurationApi.KEYS_SET) {
            SettingsConfigurationApi.requireWriter(app, agent?.id, agent?.keyHash)
            val (provider, secret) = SettingsConfigurationApi.keyChange(body)
            val keys = cc.bridgit.app.judgment.OwnKeys(app)
            if (secret == null) keys.clear(provider) else keys.set(provider, secret) { SettingsConfigurationApi.requireWriter(app, agent?.id, agent?.keyHash) }
            app.log.system("audit", "setting", "Saved AI key configuration changed for $provider")
            Reply.Json(JSONObject().put("provider",provider).put("configured",keys.has(provider)).put("readable",false))
        }
        endpoint(SettingsConfigurationApi.CAPTURE_UPLOAD) {
            SettingsConfigurationApi.requireWriter(app, agent?.id, agent?.keyHash)
            if (body.keys().asSequence().toSet() != setOf("url")) throw ErrorCatalog.error("invalid_parameter", "Send only url")
            val raw = body.opt("url")
            val url = when (raw) {
                JSONObject.NULL -> null
                is String -> raw.trim().ifEmpty { null }
                else -> throw ErrorCatalog.error("invalid_parameter", "url must be text or null")
            }
            url?.let { app.captures.uploadUrlProblem(it)?.let { throw ErrorCatalog.error("invalid_parameter", "Invalid capture upload URL") } }
            app.settings.capturesOwnerUploadUrl = url
            app.log.system("audit", "setting", "Owner capture upload configuration changed")
            Reply.Json(JSONObject().put("configured",url != null))
        }
        endpoint(SettingsConfigurationApi.MODES_GET) {
            Reply.Json(app.platform.modes.json().put("custom", JSONArray(app.platform.modes.custom())))
        }
        endpoint(SettingsConfigurationApi.MODES_SET) {
            SettingsConfigurationApi.requireWriter(app, agent?.id, agent?.keyHash)
            val names = SettingsConfigurationApi.customModes(body)
            withContext(Dispatchers.Main) {
                val live = SettingsConfigurationApi.requireWriter(app, agent?.id, agent?.keyHash)
                app.platform.modes.replaceCustom(names)
                app.log.system("audit", "setting", "${live.name} changed custom modes")
            }
            Reply.Json(app.platform.modes.json().put("custom", JSONArray(app.platform.modes.custom())))
        }
        endpoint(Endpoints.SETTINGS_GET) {
            val live = agent?.let { app.agents.get(it.id) } ?: agent
            val section = query("section")?.trim()?.takeIf { it.isNotEmpty() }
            val key = query("key")?.trim()?.takeIf { it.isNotEmpty() }
            if (section != null && section !in SettingsCatalog.SECTIONS)
                throw ErrorCatalog.error("invalid_parameter", "section must be one of ${SettingsCatalog.SECTIONS.joinToString(", ")}.")
            val all = controller.defs()
            val defs = all.filter { (section == null || it.section == section) && (key == null || it.key == key) }
            if (key != null && defs.isEmpty()) throw ErrorCatalog.error("not_found", "No setting \"$key\". GET /v1/settings lists them all.")
            val blocker = writeBlocker(live)
            val list = defs.map { controller.describe(it, live, blocker == null) }
            params.put("count", list.size)
            Reply.Json(JSONObject().put("can_write", blocker == null)
                .put("write_blocked_reason", blocker ?: JSONObject.NULL)
                .put("write_blocked_why", when (blocker) {
                    "settings_api_off" -> "The owner hasn't let agents change settings (Features > App settings)."
                    Power.SUPER.reason -> PowerGate.message(Power.SUPER, live?.name ?: "you", "Changing settings")
                    else -> JSONObject.NULL
                })
                .put("sections", JSONArray(SettingsCatalog.SECTIONS))
                .put("settings", JSONArray(list)))
        }

        endpoint(Endpoints.SETTINGS_SET) {
            val live = agent?.let { app.agents.get(it.id) } ?: agent
            when (writeBlocker(live)) {
                "settings_api_off" -> throw ErrorCatalog.error("capability_disabled",
                    "The owner hasn't let agents change settings. Ask them to turn on App settings in Feature enablement.", mapOf("setting" to switchKey))
                Power.SUPER.reason -> PowerGate.require(live, Power.SUPER, "Changing settings")
            }
            val changes = body.optJSONObject("changes")
                ?: throw ErrorCatalog.error("invalid_parameter", "Send {\"changes\": {\"<key>\": <value>, ...}}. GET /v1/settings lists the keys.")
            if (changes.length() == 0) throw ErrorCatalog.error("invalid_parameter", "changes is empty.")
            if (changes.length() > 50) throw ErrorCatalog.error("invalid_parameter", "At most 50 changes at once.")
            // Check every change before applying any, so a bad one changes nothing.
            val defs = controller.defs().associateBy { it.key }
            val checked = linkedMapOf<String, Any>()
            for (k in changes.keys()) {
                val def = defs[k] ?: throw ErrorCatalog.error("invalid_parameter", "No setting \"$k\". GET /v1/settings lists them all.", mapOf("key" to k))
                def.lock?.let { throw ErrorCatalog.error("setting_locked", "$k can't be changed by agents. ${it.why}", mapOf("key" to k, "reason" to it.id)) }
                when (val b = controller.blocker(def)) {
                    null -> {}
                    "controls_off" -> throw ErrorCatalog.error("capability_disabled", "$k needs Phone controls, which is switched off on the phone.", mapOf("setting" to "controls", "key" to k))
                    else -> throw ErrorCatalog.error("access_needed", "$k: ${controller.blockerWhy(b)}", mapOf("setting" to b, "key" to k))
                }
                when (val p = SettingsCatalog.parse(def, changes.opt(k))) {
                    is SettingsCatalog.Parsed.Bad -> throw ErrorCatalog.error("invalid_parameter", p.message, mapOf("key" to k))
                    is SettingsCatalog.Parsed.Ok -> checked[k] = p.value
                }
            }
            val changed = JSONArray()
            val name = live?.name ?: "A control agent"
            withContext(Dispatchers.Main) {
                SettingsConfigurationApi.requireWriter(app, live?.id, agent?.keyHash)
                for ((k, v) in checked) {
                    val before = runCatching { controller.read(k, live) }.getOrNull()
                    if (before == v || (before is Number && v is Number && before.toDouble() == v.toDouble())) continue
                    try { controller.apply(k, v, name) } catch (e: SecurityException) {
                        throw ErrorCatalog.error("access_needed", "Android refused to change $k.", mapOf("key" to k))
                    }
                    val after = runCatching { controller.read(k, live) }.getOrDefault(v)
                    changed.put(JSONObject().put("key", k).put("from", before ?: JSONObject.NULL).put("to", after))
                    app.log.system("audit", "setting", "$name changed $k from ${controller.show(before)} to ${controller.show(after)}")
                }
            }
            params.put("keys", JSONArray(checked.keys.toList()))
            message = "$name: changed ${changed.length()} setting(s)"
            Reply.Json(JSONObject().put("changed", changed)
                .put("unchanged", JSONArray(checked.keys.filter { k -> (0 until changed.length()).none { changed.getJSONObject(it).getString("key") == k } }))
                .put("settings", JSONArray(checked.keys.mapNotNull { defs[it] }.map { controller.describe(it, live, true) })))
        }
    }
}
