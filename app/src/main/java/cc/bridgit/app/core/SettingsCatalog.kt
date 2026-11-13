package cc.bridgit.app.core

/**
 * Why an agent can't change a setting through `POST /v1/settings`, even with App settings on (SET-03).
 * Every locked setting is still listed by `GET /v1/settings`, with this reason.
 */
enum class SettingLock(val id: String, val why: String) {
    GATE("gate", "This switch controls ordinary App settings access. The owner or an explicitly owner-authorized configuration agent manages it."),
    DISCONNECTS("disconnects", "Changing this may cut agents off. Use the advertised owner-configuration route with its separate owner grant, or change it on the phone."),
    OWNER("owner_only", "This needs separate owner authority. Use the owner_configuration route when advertised; security and OS consent keep their human checks."),
    ANDROID("android", "Android lets only the owner change this, in Android's own settings (Bridgit's Hardware access and Software access screens link there)."),
    STATUS("status", "A status, not a setting."),
}

/**
 * One setting the API lists. [type] is boolean, integer, number, string, object or array.
 * [min]/[max] bound integers and numbers; [enum] lists the allowed strings.
 */
data class SettingDef(
    val key: String,
    val type: String,
    val description: String,
    val enum: List<String>? = null,
    val min: Double? = null,
    val max: Double? = null,
    val lock: SettingLock? = null,
) {
    val section: String get() = key.substringBefore('.')
    val writable: Boolean get() = lock == null
}

/**
 * Every setting in Bridgit and every phone setting it can read or change (SET-01), one list for
 * `GET /v1/settings`, the checks in `POST /v1/settings`, the guide and the tests. Pure Kotlin.
 * Feature switches, notification channels and Android permissions are generated from the build's
 * own lists, so new ones show up without editing this file.
 */
object SettingsCatalog {
    /** The switch key of App settings itself. */
    const val GATE_SWITCH = "settings_api"

    val SECTIONS = listOf("features", "listener", "privacy", "log", "alerts", "appearance", "speaker", "listening", "captures", "security",
        "notifications", "phone", "android", "agent", "device", "spending")

    val DND_MODES = listOf("off", "priority", "alarms", "none")
    val RINGER_MODES = listOf("normal", "vibrate", "silent")
    val VOLUME_STREAMS = listOf("media", "ring", "alarm", "notification")

    private fun b(key: String, d: String, lock: SettingLock? = null) = SettingDef(key, "boolean", d, lock = lock)
    private fun i(key: String, d: String, min: Int, max: Int, lock: SettingLock? = null) = SettingDef(key, "integer", d, min = min.toDouble(), max = max.toDouble(), lock = lock)
    private fun e(key: String, d: String, values: List<String>, lock: SettingLock? = null) = SettingDef(key, "string", d, enum = values, lock = lock)

    /** The fixed settings, in the order the phone's Settings screen shows them. */
    val fixed: List<SettingDef> = listOf(
        SettingDef("device.name", "string", "Device display name, up to 60 characters; empty text clears the override"),
        b("device.resource_monitor", "Monitor resources: Bridgit samples its own CPU, memory, network, disk, storage and wakeup proxy over time (never other apps). Off by default; read the log with GET /v1/device/resources"),
        e("device.resource_monitor_interval", "How often Monitor resources samples: 1s or 10s for live diagnosis, 1m (default) or 1h for cheap always-on watching", cc.bridgit.app.monitor.ResourceLog.INTERVALS),
        b("listener.enabled", "Always-on listener: Bridgit answers agents"),
        i("listener.port", "The port agents call", 1024, 65535),
        b("listener.allow_internet_callbacks", "Callback URLs may be public HTTPS addresses, not only tailnet ones"),
        e("privacy.log_visibility", "What other agents may read in the log: all, or only their own entries (agents with Agent Super Powers always see all)", listOf("all", "own")),
        b("privacy.outbound_log", "Keep a log of everything the phone sends off the device (GET /v1/outbound); off records nothing new, kept entries stay readable"),
        b("privacy.pause_external_calls", "Pause all external calls: Bridgit's own calls to servers off the owner's network (Jev, Clef, the team backend, the relay, update checks, webhooks) are refused before they leave; tailnet callbacks still go"),
        b("privacy.lockscreen_text", "Show question and notice text on the lock screen"),
        b("privacy.speak_urgent_in_dnd", "Read urgent questions aloud even in Do Not Disturb"),
        b("privacy.open_on_arrival", "Open Bridgit over other apps when a question arrives (works once Android's \"Display over other apps\" is allowed, see android.display_over_apps)"),
        b("privacy.crash_reports", "Send crash reports to the Bridgit team"),
        b("privacy.usage_tallies", "Send feature-use tallies (counts only) to the Bridgit team; off deletes counts not yet sent"),
        b("privacy.feedback_upload", "Send bug reports and feature requests to the Bridgit team; off keeps unsent ones on the phone"),
        b("privacy.remote_view", "Remote view: the owner watches agents' activity at bridgit.cc/watch, signed in with Google (one short line per action, never content); off deletes it on the server", SettingLock.OWNER),
        i("log.retention_entries", "Audit/agent entries kept; 0 = unlimited (default). Diagnostics are kept until app data is deleted", 0, 100_000),
        i("log.retention_days", "Audit/agent days kept; 0 = unlimited (default). Explicit entry and day limits apply independently", 0, 365),
        e("alerts.frequency", "Email alerts about agent activity", listOf("every", "hourly", "daily", "weekly", "none")),
        SettingDef("alerts.email", "string", "The address that gets email alerts, or null", lock = SettingLock.OWNER),
        e("alerts.state", "Whether bridgit.cc has confirmed alerts.email", listOf("none", "pending", "confirmed"), SettingLock.STATUS),
        e("appearance.theme", "App theme", listOf("system", "light", "dark")),
        i("speaker.volume_ceiling", "Agents' audio never plays louder than this percent of the media volume", 10, 100),
        SettingDef("listening.wake_words", "array", "Wake words: [{phrase, agent_id, send_clip}]", lock = SettingLock.OWNER),
        SettingDef("listening.sounds", "object", "Listen for any sounds: {agent_id, sensitivity, send_clip}, or null", lock = SettingLock.OWNER),
        b("captures.agent_files", "Agents may save their captures to the phone's Downloads/Bridgit folder (each picks with POST /v1/captures/storage)"),
        b("captures.agent_upload", "Agents may upload their captures to their own URL (tailnet, or public HTTPS when listener.allow_internet_callbacks is on)"),
        i("captures.max_keep_minutes", "The longest an agent may keep a capture in memory, minutes", 1, cc.bridgit.app.capture.CaptureRules.MAX_KEEP_MINUTES),
        b("captures.any_agent_can_fetch", "Any agent with the capability may fetch any agent's captures (off: each agent only its own)"),
        b("captures.owner_files", "The owner's copy: every capture also goes to Downloads/Bridgit"),
        b("captures.locked_camera_approval", "Agents' camera photos while the phone is locked first wait for the owner's Allow on the lock screen (off, the default: taken without asking). Owner-only; owner configuration agents set it with POST /v1/owner/configuration/settings", SettingLock.OWNER),
        b("security.device_lock", "Each direct agent's key works only from the Tailscale device(s) it is locked to (bridgit.cc/security)", SettingLock.OWNER),
        b("security.new_place_alerts", "The owner is notified when an agent's key is used from a new device or network", SettingLock.OWNER),
        b("security.biometric", "Super Powers, approvals and allowing a new device need the owner's fingerprint, face or screen lock", SettingLock.OWNER),
        b("security.approvers_enabled", "Approver devices: this device keeps an approver list, and an enrolled approver device can decide approvals with the owner's screen-lock check (off, the default: nothing changes). The published build can't turn it on yet (platform_unsupported, reason not_available_in_this_build; can_enable in the approver list); test builds can. Owner-only; owner configuration agents set it with POST /v1/owner/configuration/settings and read the list at GET /v1/owner/configuration/approvers", SettingLock.OWNER),
        b("security.relay_signed_only", "Relay agents that can control the phone must use sealed (signed) mode; portals exempt", SettingLock.OWNER),
        b("spending.core_enabled", "The spending core: budgets, holds and the spend journal run on this device, for the sandbox namespace only in this build (off, the default: nothing changes, and today's budgets are untouched either way). Owner-only; owner configuration agents set it with POST /v1/owner/configuration/settings and read it at GET /v1/owner/configuration/spending", SettingLock.OWNER),
        SettingDef("captures.owner_upload_url", "string", "The owner's upload URL for every capture, or null (shown only as set or not)", lock = SettingLock.OWNER),
        e("phone.dnd", "Do Not Disturb (needs android.dnd_access; none also mutes alarms)", DND_MODES),
        e("phone.ringer_mode", "Ringer (needs android.dnd_access)", RINGER_MODES),
    ) + VOLUME_STREAMS.map { i("phone.volume_$it", "${it.replaceFirstChar(Char::uppercase)} volume, percent", 0, 100) } + listOf(
        SettingDef("phone.brightness", "number", "Screen brightness, 0.0-1.0 (needs android.modify_system_settings; turns automatic brightness off)", min = 0.0, max = 1.0),
        b("phone.brightness_auto", "Automatic brightness (needs android.modify_system_settings)"),
        i("phone.screen_timeout_seconds", "Screen turns off after this many seconds idle (needs android.modify_system_settings). Phones set to never may report a larger value than you can set", 15, 1800),
        b("phone.auto_rotate", "Auto-rotate the screen (needs android.modify_system_settings)"),
        b("android.notifications", "Bridgit may post notifications", SettingLock.ANDROID),
        b("android.battery_unrestricted", "Bridgit is exempt from battery optimisation", SettingLock.ANDROID),
        b("android.display_over_apps", "\"Display over other apps\" allowed", SettingLock.ANDROID),
        b("android.accessibility", "Bridgit's Accessibility service is on", SettingLock.ANDROID),
        b("android.notification_access", "Notification access granted", SettingLock.ANDROID),
        b("android.dnd_access", "Do Not Disturb access granted", SettingLock.ANDROID),
        b("android.modify_system_settings", "\"Modify system settings\" allowed", SettingLock.ANDROID),
        SettingDef("agent.name", "string", "Your self-supplied display name; edit it with POST /v1/agent/profile (separate from settings writes)", lock = SettingLock.OWNER),
        b("agent.fixer_mode", "You have Bridgit Fixer Mode: every bug report, and moving them", SettingLock.OWNER),
        b("agent.super_powers", "You have Agent Super Powers: ADB shell, changing settings, every agent's log", SettingLock.OWNER),
        SettingDef("agent.capabilities", "array", "Capabilities you may use, or null for all", lock = SettingLock.OWNER),
    )

    /** "features.camera" etc. for each master switch; App settings' own switch is locked. */
    fun features(switches: List<Pair<String, String>>): List<SettingDef> = switches.map { (key, title) ->
        b("features.$key", "Feature switch: $title", if (key == GATE_SWITCH) SettingLock.GATE else null)
    }

    fun channels(ids: List<Pair<String, String>>): List<SettingDef> = ids.map { (id, title) ->
        e("notifications.$id", "Notification channel \"$title\": its importance", listOf("off", "min", "low", "default", "high"), SettingLock.ANDROID)
    }

    /** "android.permission.CAMERA" etc. for each runtime permission the build's features use. */
    fun permissions(names: List<String>): List<SettingDef> = names.map { it.substringAfterLast('.') }.distinct().sorted().map {
        b("android.permission.$it", "Android permission $it granted", SettingLock.ANDROID)
    }

    fun all(switches: List<Pair<String, String>>, channels: List<Pair<String, String>>, permissions: List<String>): List<SettingDef> =
        features(switches) + fixed.filter { it.section != "android" && it.section != "agent" } + channels(channels) +
            fixed.filter { it.section == "android" } + permissions(permissions) + fixed.filter { it.section == "agent" }

    /** The checked value, or the reason it was refused. */
    sealed class Parsed {
        data class Ok(val value: Any) : Parsed()
        data class Bad(val message: String) : Parsed()
    }

    /** Checks a value for [def] as it arrived in JSON (numbers may be Int, Long or Double). Locks are not checked here. */
    fun parse(def: SettingDef, value: Any?): Parsed = when (def.type) {
        "boolean" -> (value as? Boolean)?.let { Parsed.Ok(it) } ?: Parsed.Bad("${def.key} must be true or false.")
        "integer" -> {
            val n = value as? Number
            val whole = n != null && n.toDouble() % 1.0 == 0.0
            if (!whole) Parsed.Bad("${def.key} must be a whole number${range(def)}.")
            else if (!inRange(def, n!!.toDouble())) Parsed.Bad("${def.key} must be${range(def)}.")
            else Parsed.Ok(n.toInt())
        }
        "number" -> {
            val n = (value as? Number)?.toDouble()
            if (n == null || n.isNaN()) Parsed.Bad("${def.key} must be a number${range(def)}.")
            else if (!inRange(def, n)) Parsed.Bad("${def.key} must be${range(def)}.")
            else Parsed.Ok(n)
        }
        "string" -> {
            val s = value as? String
            if (s == null) Parsed.Bad("${def.key} must be text${def.enum?.let { ": one of ${it.joinToString(", ")}" } ?: ""}.")
            else if (def.key == "device.name" && (s.length > 60 || s.any { it.code < 32 || it.code == 127 }))
                Parsed.Bad("device.name must be at most 60 characters without control characters; empty text resets it.")
            else if (def.enum != null && s !in def.enum) Parsed.Bad("${def.key} must be one of ${def.enum.joinToString(", ")}.")
            else Parsed.Ok(s)
        }
        else -> Parsed.Bad("${def.key} can't be set through the API.")
    }

    private fun inRange(def: SettingDef, n: Double) = (def.min == null || n >= def.min) && (def.max == null || n <= def.max)

    private fun range(def: SettingDef): String = if (def.min == null || def.max == null) "" else {
        fun f(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
        " from ${f(def.min)} to ${f(def.max)}"
    }

    /** Percent (0-100) to an Android stream step, and back; always within the stream's range. */
    fun percentToStep(percent: Int, maxStep: Int): Int = Math.round(percent.coerceIn(0, 100) * maxStep / 100.0).toInt().coerceIn(0, maxStep)
    fun stepToPercent(step: Int, maxStep: Int): Int = if (maxStep <= 0) 0 else Math.round(step.coerceIn(0, maxStep) * 100.0 / maxStep).toInt()

    /** Brightness 0.0-1.0 to Android's 1-255, and back. */
    fun brightnessToLevel(b: Double): Int = Math.round(b.coerceIn(0.0, 1.0) * 255).toInt().coerceIn(1, 255)
    fun levelToBrightness(level: Int): Double = Math.round(level.coerceIn(0, 255) / 255.0 * 100) / 100.0

    /** NotificationManager interruption filters (1 all, 2 priority, 3 none, 4 alarms) and back. */
    fun dndToFilter(mode: String): Int = when (mode) { "off" -> 1; "priority" -> 2; "none" -> 3; "alarms" -> 4; else -> error(mode) }
    fun filterToDnd(filter: Int): String = when (filter) { 2 -> "priority"; 3 -> "none"; 4 -> "alarms"; else -> "off" }
    /** AudioManager ringer modes (0 silent, 1 vibrate, 2 normal). */
    fun ringerToMode(r: String): Int = when (r) { "silent" -> 0; "vibrate" -> 1; "normal" -> 2; else -> error(r) }
    fun modeToRinger(m: Int): String = when (m) { 0 -> "silent"; 1 -> "vibrate"; else -> "normal" }
    /** NotificationManager importance (0 none .. 4 high) as a word. */
    fun importance(i: Int): String = when { i <= 0 -> "off"; i == 1 -> "min"; i == 2 -> "low"; i == 3 -> "default"; else -> "high" }
}
