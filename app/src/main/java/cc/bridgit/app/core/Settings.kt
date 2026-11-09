package cc.bridgit.app.core

import android.content.Context
import android.content.SharedPreferences
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.log.LogStore

/**
 * Settings and master switches (SEC-03: every switch starts off), kept in
 * app-private SharedPreferences and excluded from backup (SEC-15). The API reads and changes them
 * through capability.SettingsController (SET-01); keep its catalog, core.SettingsCatalog, in step.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    fun isOn(switchKey: String) = prefs.getBoolean("switch_$switchKey", switchKey in DEFAULT_ON)
    fun setOn(switchKey: String, on: Boolean) { prefs.edit().putBoolean("switch_$switchKey", on).apply(); onSwitch?.invoke(switchKey, on); changed() }
    /** Feature-use tallies hear which switch was flipped (never by whom). */
    var onSwitch: ((String, Boolean) -> Unit)? = null

    var listenerEnabled: Boolean
        get() = prefs.getBoolean("listener", false)
        set(v) { prefs.edit().putBoolean("listener", v).apply(); changed() }

    /**
     * Pause all external calls (owner, Agents tab): Bridgit's own calls to servers off the phone (Jev, Clef,
     * the team backend, the relay, update checks, webhooks) are refused before they leave. Default off.
     */
    var externalCallsPaused: Boolean
        get() = prefs.getBoolean(EXTERNAL_PAUSED_PREF, false)
        set(v) { prefs.edit().putBoolean(EXTERNAL_PAUSED_PREF, v).apply(); changed() }

    var port: Int
        get() = prefs.getInt("port", BuildConfig.DEFAULT_PORT)
        set(v) { prefs.edit().putInt("port", v).apply(); changed() }

    /** Section 10: "all" (default, D12) or "own". Developer agents always see everything. */
    /** The outbound log (GET /v1/outbound) records new calls. Default on; [outboundLogChanged] says when and by whom it last changed. */
    val outboundLog: Boolean get() = prefs.getBoolean(OUTBOUND_LOG_PREF, true)
    fun setOutboundLog(on: Boolean, by: String) {
        if (on == outboundLog) return
        prefs.edit().putBoolean(OUTBOUND_LOG_PREF, on).putLong("outbound_log_changed_at", System.currentTimeMillis())
            .putString("outbound_log_changed_by", by).apply()
        changed()
    }
    /** (time ms, who) of the last change, or null if it was never changed. */
    val outboundLogChanged: Pair<Long, String>?
        get() = prefs.getLong("outbound_log_changed_at", 0L).takeIf { it > 0 }?.let { it to (prefs.getString("outbound_log_changed_by", null) ?: "owner") }

    var logVisibility: String
        get() = prefs.getString("log_visibility", "all") ?: "all"
        set(v) { prefs.edit().putString("log_visibility", v).apply(); changed() }

    var retentionEntries: Int
        get() = prefs.getInt("retention_entries", 0)
        set(v) { prefs.edit().putInt("retention_entries", v.coerceIn(0, 100_000)).apply(); changed() }

    var retentionDays: Int
        get() = prefs.getInt("retention_days", 0)
        set(v) { prefs.edit().putInt("retention_days", v.coerceIn(0, 365)).apply(); changed() }

    /**
     * A0189: Monitor resources (Settings › Log; device.resource_monitor). Off by default. On, Bridgit samples its own
     * footprint (monitor.ResourceMonitor). Each change stamps [resourceMonitorChangedAt], the "since" agents and the owner see.
     */
    var resourceMonitor: Boolean
        get() = prefs.getBoolean(RESOURCE_MONITOR_PREF, false)
        set(v) {
            if (v == resourceMonitor) return
            prefs.edit().putBoolean(RESOURCE_MONITOR_PREF, v).putLong("resource_monitor_changed_at", System.currentTimeMillis()).apply(); changed()
        }
    /** When [resourceMonitor] last changed (ms), or 0 if never. Set by the switch itself, not editable. */
    val resourceMonitorChangedAt: Long get() = prefs.getLong("resource_monitor_changed_at", 0L)

    /** A0190: how often the resource monitor samples: 1s, 10s, 1m (default) or 1h (device.resource_monitor_interval). */
    var resourceMonitorInterval: String
        get() = prefs.getString("resource_monitor_interval", null)?.takeIf { it in cc.bridgit.app.monitor.ResourceLog.INTERVALS }
            ?: cc.bridgit.app.monitor.ResourceLog.DEFAULT_INTERVAL
        set(v) {
            require(v in cc.bridgit.app.monitor.ResourceLog.INTERVALS) { "Unknown interval $v" }
            if (v == resourceMonitorInterval) return
            prefs.edit().putString("resource_monitor_interval", v).apply(); changed()
        }

    /** "system", "light" or "dark" (UI-19 Appearance). */
    var appearance: String
        get() = prefs.getString("appearance", "system") ?: "system"
        set(v) { prefs.edit().putString("appearance", v).apply(); changed() }

    /** SPK-01: agents' audio is never louder than this share of the media volume. */
    var speakerCeiling: Int
        get() = prefs.getInt("speaker_ceiling", 70)
        set(v) { prefs.edit().putInt("speaker_ceiling", v.coerceIn(10, 100)).apply(); changed() }

    /** FBK-14: send each new issue to the Bridgit team's backend. On unless turned off (owner request 2 Nov 2026). */
    var feedbackUpload: Boolean
        get() = !prefs.getBoolean("feedback_upload_off", false)
        set(v) { prefs.edit().putBoolean("feedback_upload_off", !v).apply(); changed() }

    /** EVT-05: a public HTTPS callback URL needs this (off by default). Tailnet callbacks never do. */
    var allowInternetCallbacks: Boolean
        get() = prefs.getBoolean(PREF_INTERNET_CALLBACKS, false)
        set(v) { prefs.edit().putBoolean(PREF_INTERNET_CALLBACKS, v).apply(); changed() }

    /** Email alerts: "every", "hourly", "daily", "weekly" or "none" (default), see alerts.AlertFrequency. */
    var alertFrequency: String
        get() = prefs.getString("alert_frequency", "none") ?: "none"
        set(v) { prefs.edit().putString("alert_frequency", v).apply(); changed() }

    /** Whether the owner ever picked a frequency (if not, verifying the email turns on a daily digest). */
    val alertFrequencyChosen: Boolean get() = prefs.contains("alert_frequency")

    /** When the first agent call started the 24-hour email-verification clock (alerts.EmailGate), or 0. */
    var emailGraceStartedAt: Long
        get() = prefs.getLong("email_grace_started_at", 0L)
        set(v) { prefs.edit().putLong("email_grace_started_at", v).apply() }

    /** When the owner first confirmed an email address, or 0. Stays set if alerts are later stopped. */
    var emailVerifiedAt: Long
        get() = prefs.getLong("email_verified_at", 0L)
        set(v) { prefs.edit().putLong("email_verified_at", v).apply() }

    /** The one address that gets email alerts for every agent, or null. */
    var alertEmail: String?
        get() = prefs.getString("alert_email", null)
        set(v) { prefs.edit().putString("alert_email", v).apply(); changed() }

    /** Whether bridgit.cc has [alertEmail] confirmed: "none", "pending" or "confirmed". */
    var alertState: String
        get() = prefs.getString("alert_state", "none") ?: "none"
        set(v) { prefs.edit().putString("alert_state", v).apply(); changed() }

    /** Wake words (CAP-47) as JSON: [{phrase, agent_id, send_clip}]. Edited only on the phone; GET /v1/settings shows them locked. */
    var wakeWords: String
        get() = prefs.getString("wake_words", "[]") ?: "[]"
        set(v) { prefs.edit().putString("wake_words", v).apply(); changed() }

    /** "Listen for any sounds" as JSON {agent_id, sensitivity, send_clip}, or empty. */
    var soundsConfig: String
        get() = prefs.getString("sounds_config", "") ?: ""
        set(v) { prefs.edit().putString("sounds_config", v).apply(); changed() }

    /** SEC-13 / D33: agents may save their captures to Downloads/Bridgit. On unless the owner turns it off. */
    var capturesAgentFiles: Boolean
        get() = !prefs.getBoolean("captures_agent_files_off", false)
        set(v) { prefs.edit().putBoolean("captures_agent_files_off", !v).apply(); changed() }

    /** SEC-13 / D33: agents may upload their captures to their own URL. On unless the owner turns it off. */
    var capturesAgentUpload: Boolean
        get() = !prefs.getBoolean("captures_agent_upload_off", false)
        set(v) { prefs.edit().putBoolean("captures_agent_upload_off", !v).apply(); changed() }

    /** The longest an agent may keep a capture in memory, in minutes. */
    var capturesMaxKeepMinutes: Int
        get() = prefs.getInt("captures_max_keep_minutes", cc.bridgit.app.capture.CaptureRules.DEFAULT_MAX_KEEP_MINUTES)
        set(v) { prefs.edit().putInt("captures_max_keep_minutes", v.coerceIn(1, cc.bridgit.app.capture.CaptureRules.MAX_KEEP_MINUTES)).apply(); changed() }

    /** Any agent (with the capability) may fetch any agent's captures. Off by default: each agent only its own. */
    var capturesShareAll: Boolean
        get() = prefs.getBoolean("captures_share_all", false)
        set(v) { prefs.edit().putBoolean("captures_share_all", v).apply(); changed() }

    /** Security: lock each direct agent to the Tailscale device that first used its key. On by default. */
    var securityDeviceLock: Boolean
        get() = prefs.getBoolean("security_device_lock", true)
        set(v) { prefs.edit().putBoolean("security_device_lock", v).apply(); changed() }

    /** Security: notify the owner when an agent shows up from a new device or network. On by default. */
    var securityPlaceAlerts: Boolean
        get() = prefs.getBoolean("security_place_alerts", true)
        set(v) { prefs.edit().putBoolean("security_place_alerts", v).apply(); changed() }

    /** Security: turning on Super Powers and approving agent requests need a fingerprint, face or screen lock. Off by default. */
    var securityBiometric: Boolean
        get() = prefs.getBoolean("security_biometric", false)
        set(v) { prefs.edit().putBoolean("security_biometric", v).apply(); changed() }

    /** Security: relay agents that can control the phone must use sealed (signed, encrypted) mode. Off by default. */
    var securityRelaySignedOnly: Boolean
        get() = prefs.getBoolean("security_relay_signed_only", false)
        set(v) { prefs.edit().putBoolean("security_relay_signed_only", v).apply(); changed() }

    /** The owner's own copy: every capture also goes to Downloads/Bridgit. */
    var capturesOwnerFiles: Boolean
        get() = prefs.getBoolean("captures_owner_files", false)
        set(v) { prefs.edit().putBoolean("captures_owner_files", v).apply(); changed() }

    /**
     * Owner decision, 7 Nov 2026 (report A0049): agents' camera photos work while the phone is locked. On: each such
     * photo first waits for the owner's Allow on the lock screen. Off by default.
     */
    var capturesLockedCameraApproval: Boolean
        get() = prefs.getBoolean("captures_locked_camera_approval", false)
        set(v) { prefs.edit().putBoolean("captures_locked_camera_approval", v).apply(); changed() }

    /** The owner's own upload URL for every capture, or null. */
    var capturesOwnerUploadUrl: String?
        get() = prefs.getString("captures_owner_upload_url", null)?.takeIf { it.isNotBlank() }
        set(v) { prefs.edit().putString("captures_owner_upload_url", v?.takeIf { it.isNotBlank() }).apply(); changed() }

    fun capturePolicy() = cc.bridgit.app.capture.CaptureRules.OwnerPolicy(capturesAgentFiles, capturesAgentUpload,
        capturesMaxKeepMinutes, capturesShareAll, capturesOwnerFiles, capturesOwnerUploadUrl)

    var firstRunDone: Boolean
        get() = prefs.getBoolean("first_run_done", false)
        set(v) { prefs.edit().putBoolean("first_run_done", v).apply() }

    fun retention() = LogStore.Retention(retentionEntries, retentionDays)

    /** For the agents' persistence: one JSON blob in its own prefs file. */
    fun agentStorage(context: Context): AgentStorage = object : AgentStorage {
        private val p = context.getSharedPreferences("agents", Context.MODE_PRIVATE)
        override fun load(): String? = p.getString("agents", null)
        override fun save(json: String) { p.edit().putString("agents", json).apply() }
    }

    companion object {
        /** Master switches that start on (the rest start off, SEC-03): Budgets, since agents can only change within the owner's ceiling (docs/spec-budgets.md). */
        val DEFAULT_ON = setOf("budgets", "applet_portals")
        const val OUTBOUND_LOG_PREF = "outbound_log"
        const val RESOURCE_MONITOR_PREF = "resource_monitor"
        const val EXTERNAL_PAUSED_PREF = "external_calls_paused"
        const val PREF_INTERNET_CALLBACKS = "allow_internet_callbacks"
    }
}
