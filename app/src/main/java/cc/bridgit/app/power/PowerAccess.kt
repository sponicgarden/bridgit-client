package cc.bridgit.app.power

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.adb.AdbGrants
import cc.bridgit.app.adb.SuperPowers
import kotlinx.coroutines.withTimeoutOrNull
import cc.bridgit.app.capability.granted
import cc.bridgit.app.core.Compat
import cc.bridgit.app.core.CrashReports
import cc.bridgit.app.interact.InteractionNotifier
import cc.bridgit.app.platform.Capabilities
import cc.bridgit.app.power.PowerPlan.Kind
import cc.bridgit.app.power.PowerPlan.Step
import cc.bridgit.app.ui.home.UiActions

/** Android side of [PowerPlan]: what this build declares, what is on, and the real page for each step. */
class PowerAccess(private val app: BridgeApp) {
    private val pm get() = app.packageManager

    fun steps(): List<Step> {
        val info = runCatching { Compat.packageInfo(pm, app.packageName, PackageManager.GET_PERMISSIONS) }.getOrNull()
        // Before Android 14 Health Connect is a separate app that Android's permission prompt can't reach:
        // asking fails at once and would stop Max Agentic Control, so the step is left out there.
        val declared = info?.requestedPermissions?.toList().orEmpty()
            .filter { Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !it.startsWith(PowerPlan.HEALTH_PREFIX) }
            // From Android 13 storage access is never granted to Bridgit's target SDK: the prompt is refused at once
            // and the row could never turn on, so it isn't offered there.
            .filter { Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || it !in LEGACY_STORAGE }
        return PowerPlan.steps(declared, ::dangerous, services(AccessibilityService.SERVICE_INTERFACE, android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE),
            services(NotificationListenerService.SERVICE_INTERFACE, android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE),
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && sideloaded())
    }

    private fun dangerous(p: String): Boolean = runCatching {
        pm.getPermissionInfo(p, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
    }.getOrDefault(false)

    /** Bridgit's own services bound with [permission] for [action], so a service added later joins the list. */
    private fun services(action: String, permission: String): List<String> =
        Compat.queryServices(pm, Intent(action).setPackage(app.packageName))
            .mapNotNull { it.serviceInfo }.filter { it.permission == permission }
            .map { ComponentName(it.packageName, it.name).flattenToString() }.sorted()

    /** Not installed by a store: Android 13+ may block accessibility and notification access as "restricted settings". */
    fun sideloaded(): Boolean = runCatching {
        Compat.installer(pm, app.packageName) !in STORES
    }.getOrDefault(true)

    fun granted(s: Step): Boolean = when {
        s.id == "notifications" -> app.granted(PowerPlan.NOTIFICATIONS) && app.captureNotificationsOn()
        s.id == "photos" -> s.permissions.filter { it != "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" }.all(app::granted)
        s.kind == Kind.RUNTIME -> s.permissions.all(app::granted)
        s.id == "battery" -> app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true
        s.id == "usage" -> cc.bridgit.app.planned.UsageModule.usageAllowed(app)
        s.id == "write_settings" -> Settings.System.canWrite(app)
        s.id == "dnd" -> app.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        s.id == "installs" -> pm.canRequestPackageInstalls()
        s.id == "overlay" -> Settings.canDrawOverlays(app)
        s.id.startsWith(PowerPlan.A11Y) -> enabledIn(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, s.target)
        s.id.startsWith(PowerPlan.LISTENER) -> enabledIn("enabled_notification_listeners", s.target)
        else -> false
    }

    private fun enabledIn(key: String, flat: String?): Boolean {
        val me = flat?.let(ComponentName::unflattenFromString) ?: return false
        return Settings.Secure.getString(app.contentResolver, key).orEmpty().split(':')
            .any { ComponentName.unflattenFromString(it) == me }
    }

    /** The settings page for a SPECIAL or RESTRICTED step. App info is the fallback when it doesn't resolve. */
    fun intent(s: Step): Intent {
        val pkg = Uri.parse("package:${app.packageName}")
        return when {
            s.id == "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
            s.id == "health" -> healthPage()
            s.id == "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            s.id == "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkg)
            s.id == "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
            // Bridgit's own Do Not Disturb switch where Android has that page, else the list.
            s.id == "dnd" -> Intent(DND_DETAIL, pkg).takeIf { it.resolveActivity(pm) != null }
                ?: Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            s.id == "installs" -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg)
            s.id == "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            s.id.startsWith(PowerPlan.LISTENER) -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, s.target)
            s.id.startsWith(PowerPlan.A11Y) -> Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, s.target)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
    }

    /** Health Connect's own page for Bridgit's health permissions (Android 14 and later). */
    private fun healthPage() = Intent(HEALTH_PAGE).putExtra(Intent.EXTRA_PACKAGE_NAME, app.packageName)

    /**
     * Health Connect has its own page for Bridgit here (Android 14+), a way in when its prompt bounces. Not checked
     * with resolveActivity: package visibility can hide it; if it doesn't open, the step falls back to App info.
     */
    fun hasHealthPage(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** The service's own name ("Bridgit screenshots"), for "tap … and turn it on" on Android's list. */
    fun serviceName(s: Step): String = serviceLabel(s.target)

    /** Instructions stay on Bridgit's checklist and are shown before leaving for Android settings. */
    fun instructions(s: Step): String? {
        val label = app.applicationInfo.loadLabel(pm).toString()
        return when {
            s.id.startsWith(PowerPlan.A11Y) -> app.getString(R.string.power_help_a11y, serviceName(s))
            s.id.startsWith(PowerPlan.LISTENER) -> app.getString(R.string.power_help_listener, serviceName(s))
            else -> HELP[s.id]?.let { app.getString(it, label) }
        }
    }

    /** Fallback page when a step's own page doesn't open. */
    fun fallback(s: Step): Intent = when {
        s.id.startsWith(PowerPlan.A11Y) -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        s.id.startsWith(PowerPlan.LISTENER) -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        s.id == "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        s.id == "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
    }

    fun title(s: Step): String = when {
        s.id.startsWith(PowerPlan.A11Y) || s.id.startsWith(PowerPlan.LISTENER) ->
            app.getString(if (s.id.startsWith(PowerPlan.A11Y)) R.string.power_a11y else R.string.power_listener, serviceLabel(s.target))
        s.id.startsWith("perm:") -> permissionLabel(s.permissions.first())
        else -> TITLES[s.id]?.let(app::getString) ?: s.id
    }

    fun unlocks(s: Step): String = when {
        s.id.startsWith(PowerPlan.A11Y) -> app.getString(R.string.power_a11y_unlocks)
        s.id.startsWith(PowerPlan.LISTENER) -> app.getString(R.string.power_listener_unlocks)
        else -> UNLOCKS[s.id]?.let(app::getString) ?: app.getString(R.string.power_other_unlocks)
    }

    private fun serviceLabel(flat: String?): String {
        val cn = flat?.let(ComponentName::unflattenFromString) ?: return app.getString(R.string.app_name)
        return runCatching { Compat.serviceInfo(pm, cn).loadLabel(pm).toString() }
            .getOrDefault(cn.shortClassName)
    }

    private fun permissionLabel(p: String): String = runCatching {
        pm.getPermissionInfo(p, 0).loadLabel(pm).toString().replaceFirstChar { it.uppercase() }
    }.getOrDefault(p.substringAfterLast('.'))

    // ---- Switches and agent grants

    /**
     * A Settings switch outside the capability registry that Max power also turns on. [message] builds the
     * audit line from "on" / "off".
     */
    private class PrefSwitch(val read: () -> Boolean, val write: (Boolean) -> Unit, val label: String, val message: (String) -> String) {
        constructor(read: () -> Boolean, write: (Boolean) -> Unit, changed: Int, app: BridgeApp) :
            this(read, write, app.getString(when (changed) {
                R.string.settings_changed_internet_callbacks -> R.string.settings_internet_callbacks
                R.string.settings_changed_lockscreen -> R.string.settings_lockscreen_text
                R.string.settings_changed_speak_urgent -> R.string.settings_speak_urgent
                R.string.settings_changed_crash_reports -> R.string.settings_crash_reports
                R.string.settings_changed_usage_tallies -> R.string.settings_usage_tallies
                R.string.settings_changed_open_on_arrival -> R.string.settings_open_on_arrival
                else -> changed
            }), { onOff: String -> app.getString(changed, onOff) })
    }

    private val prefs get() = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun rawPref(key: String, changed: Int, default: Boolean = false) =
        PrefSwitch({ prefs.getBoolean(key, default) }, { prefs.edit().putBoolean(key, it).apply() }, changed, app)

    /** Every on/off switch in Settings, in screen order. Crash reports only when this build can send them. */
    private fun prefSwitches(): List<PrefSwitch> = buildList {
        add(PrefSwitch({ app.settings.allowInternetCallbacks }, { app.settings.allowInternetCallbacks = it }, R.string.settings_changed_internet_callbacks, app))
        add(rawPref(InteractionNotifier.PREF_LOCKSCREEN_TEXT, R.string.settings_changed_lockscreen))
        add(rawPref(InteractionNotifier.PREF_SPEAK_URGENT_IN_DND, R.string.settings_changed_speak_urgent))
        if (CrashReports.configured) add(PrefSwitch({ CrashReports.enabled(app) }, { on ->
            prefs.edit().putBoolean(CrashReports.PREF, !on).apply()
            if (on) CrashReports.start(app) else CrashReports.stop()
        }, R.string.settings_changed_crash_reports, app))
        add(PrefSwitch({ app.usage.enabled() }, { app.usage.setEnabled(it) }, R.string.settings_changed_usage_tallies, app))
        // Captures (SEC-13): what agents may do with what they capture, and the owner's own copy. Same audit line as their Settings rows.
        val s = app.settings
        add(captures(R.string.settings_captures_agent_files, { s.capturesAgentFiles }) { s.capturesAgentFiles = it })
        add(captures(R.string.settings_captures_agent_upload, { s.capturesAgentUpload }) { s.capturesAgentUpload = it })
        add(captures(R.string.settings_captures_share_all, { s.capturesShareAll }) { s.capturesShareAll = it })
        add(captures(R.string.settings_captures_owner_files, { s.capturesOwnerFiles }) { s.capturesOwnerFiles = it })
        // Also needs "Display over other apps", which is the overlay step in the guided pass.
        add(rawPref(InteractionNotifier.PREF_OPEN_ON_ARRIVAL, R.string.settings_changed_open_on_arrival, default = true))
    }

    private fun captures(title: Int, read: () -> Boolean, write: (Boolean) -> Unit) =
        PrefSwitch(read, write, app.getString(title)) { onOff -> app.getString(R.string.settings_captures_changed, app.getString(title), onOff) }

    private fun setPrefs(on: Boolean) = prefSwitches().filter { it.read() != on }.forEach {
        it.write(on)
        audit(it.message(app.getString(if (on) R.string.home_on else R.string.home_off)))
    }

    private fun audit(message: String) = app.log.system("audit", "setting", message)

    /**
     * Max Agentic Control: every master switch on, each with its audit line (UI-18), plus Always on, agents reading
     * every log entry, every switch in Settings and every Device tools switch. Capabilities added later are picked
     * up from the registry. App settings is included: the owner asked for every switch on the side of more control.
     */
    fun allSwitchesOn() {
        maxSwitches().forEach { UiActions.setSwitch(app, it, true) }
        setDeviceTools(true)
        if (!app.settings.listenerEnabled) {
            app.settings.listenerEnabled = true
            runCatching { BridgeService.start(app) }
            audit(app.getString(R.string.settings_changed_listener, app.getString(R.string.home_on)))
        }
        if (app.settings.logVisibility != "all") {
            app.settings.logVisibility = "all"
            audit(app.getString(R.string.settings_changed_visibility, app.getString(R.string.settings_visibility_all)))
        }
        setPrefs(true)
    }

    /**
     * Every master switch and every Settings switch off. Always on and log visibility stay, so paired agents
     * can still check status. Android permissions stay as they are; Access links to each one.
     */
    fun allSwitchesOff() {
        app.registry.switchable().forEach { UiActions.setSwitch(app, it, false) }
        setPrefs(false)
        setDeviceTools(false)
    }

    /**
     * Max Agentic Control's switches: every capability, log visibility "all" and every Settings switch. Open on arrival
     * counts only with "Display over other apps", as its Settings row shows it. Always on is left out: Pause turns it off.
     */
    fun everySwitchOn(): Boolean = maxSwitches().all { it.enabled } && deviceTools().all { Capabilities.enabled(app, it.id) } &&
        app.settings.logVisibility == "all" && prefSwitches().all { it.read() } && Settings.canDrawOverlays(app)

    private fun maxSwitches() = app.registry.switchable()

    /**
     * The feature switches on Device tools (Agent control and appliances) this build supports. Camera, microphone
     * and gallery share their switch with the registry, so they are left to [maxSwitches]. Per-agent grants there
     * are granted to control agents when the owner chooses Max Agentic Control.
     */
    private fun deviceTools() = Capabilities.all.filter {
        (!it.privateOnly || cc.bridgit.app.BuildConfig.PRIVATE_CONTROL) && Capabilities.legacy(it.id) == null
    }

    private fun setDeviceTools(on: Boolean) = deviceTools().filter { Capabilities.enabled(app, it.id) != on }.forEach {
        runCatching { Capabilities.setEnabled(app, it.id, on) }
        audit(app.getString(R.string.home_turned, it.label, app.getString(if (on) R.string.home_on else R.string.home_off)))
    }

    /**
     * Lets a control agent use all supported capabilities until revoked, including device tools.
     * Returns an Undo that restores its exact previous regular and device-tool grants.
     */
    private fun supportedGrants() = Capabilities.all.filter { !it.privateOnly || cc.bridgit.app.BuildConfig.PRIVATE_CONTROL }

    private fun hasAllDeviceGrants(agentId: String): Boolean {
        val grants = app.platform.store.grants(agentId)
        return supportedGrants().all { grants.optLong(it.id) == Long.MAX_VALUE }
    }

    fun grantAll(agentId: String, superPowers: Boolean? = null): () -> Unit {
        val old = cc.bridgit.app.ui.home.HomeStates.controlAgents(app).firstOrNull { it.id == agentId } ?: return {}
        val previous = app.platform.store.grants(agentId)
        app.agents.update(agentId) { it.copy(
            caps = if (it.connection == cc.bridgit.app.core.Connection.RELAY) it.caps.orEmpty() + app.registry.modules.map { m -> m.id } else null,
            fixer = true, superPowers = superPowers ?: old.superPowers, maximumControl = true) }
        supportedGrants().forEach { app.platform.grant(agentId, it.id, Long.MAX_VALUE) }
        app.log.system("audit", "agent", "Owner granted ${old.name} all supported capabilities until revoked")
        return {
            if (app.agents.get(agentId) != null) {
                app.platform.revoke(agentId)
                app.agents.update(agentId) { it.copy(caps = old.caps, fixer = old.fixer, superPowers = old.superPowers, maximumControl = old.maximumControl) }
                previous.keys().forEach { id -> app.platform.store.grant(agentId, id, previous.getLong(id)) }
                app.log.system("audit", "agent", "Owner restored ${old.name}'s previous grants")
            }
        }
    }

    // ---- Max power: every setting at its most capable, most informative choice

    /**
     * True once [everySwitchOn] holds and every other setting sits at its most capable choice too:
     * listener on (when an agent is paired), every agent allowed everything, the log kept as long as
     * Settings allows, full speaker volume, and bug reports sent to the Bridgit team.
     */
    data class MissingAccess(val permissions: List<String>, val settings: List<String>) {
        val complete get() = permissions.isEmpty() && settings.isEmpty()
    }

    /** The same checklist drives the CTA, confirmation and completion test. */
    fun missingAccess(): MissingAccess {
        val s = app.settings
        val permissions = steps().filter { it.kind != Kind.RESTRICTED && !granted(it) }.map(::title)
        val settings = buildList {
            addAll(maxSwitches().filter { !it.enabled }.map { it.title })
            addAll(deviceTools().filter { !Capabilities.enabled(app, it.id) }.map { it.label })
            addAll(prefSwitches().filter { !it.read() }.map { it.label })
            if (s.logVisibility != "all") add(app.getString(R.string.max_missing_log))
            if (app.agents.count > 0 && !s.listenerEnabled) add(app.getString(R.string.max_missing_listener))
            // Portal pairings only view the phone: Max Agentic Control never widens them.
            cc.bridgit.app.ui.home.HomeStates.controlAgents(app).filter { !isMaximum(it.id) }.forEach {
                add(app.getString(R.string.max_missing_agent, it.name))
            }
            if (s.retentionEntries < MAX_ENTRIES || s.retentionDays < MAX_DAYS) add(app.getString(R.string.max_missing_retention))
            if (s.speakerCeiling < 100) add(app.getString(R.string.max_missing_volume))
            if (s.externalCallsPaused) add(app.getString(R.string.agents_pause_external))
            if (cc.bridgit.app.feedback.FeedbackUploader.configured && !s.feedbackUpload)
                add(app.getString(R.string.settings_feedback_upload))
        }.distinct()
        return MissingAccess(permissions, settings)
    }

    fun everythingOn(): Boolean = missingAccess().complete

    /**
     * Max power: [allSwitchesOn], then every other setting at its most capable choice (see [everythingOn]),
     * each change audited. [allAgents] false leaves agent grants alone (the per-agent pass grants its one
     * agent itself). Returns Undo, which puts back exactly what this changed.
     */
    fun maxEverything(allAgents: Boolean = true): () -> Unit {
        val s = app.settings
        // What allSwitchesOn may change, as it was before.
        val offModules = maxSwitches().filter { !it.enabled }
        val offPrefs = prefSwitches().filter { !it.read() }
        val offTools = deviceTools().filter { !Capabilities.enabled(app, it.id) }
        val listenerWas = s.listenerEnabled
        val visibilityWas = s.logVisibility
        val undo = mutableListOf<() -> Unit>()
        val changed = mutableListOf<String>()

        allSwitchesOn()
        undo += {
            offModules.forEach { UiActions.setSwitch(app, it, false) }
            offPrefs.forEach { it.write(false); audit(it.message(app.getString(R.string.home_off))) }
            offTools.forEach { runCatching { Capabilities.setEnabled(app, it.id, false) } }
            if (!listenerWas && s.listenerEnabled) { s.listenerEnabled = false; runCatching { BridgeService.pause(app) } }
            if (s.logVisibility != visibilityWas) s.logVisibility = visibilityWas
        }
        if (allAgents) cc.bridgit.app.ui.home.HomeStates.controlAgents(app).forEach { a ->
            undo += grantAll(a.id)
        }
        if (s.retentionEntries < MAX_ENTRIES || s.retentionDays < MAX_DAYS) {
            val (oldEntries, oldDays) = s.retentionEntries to s.retentionDays
            s.retentionEntries = MAX_ENTRIES; s.retentionDays = MAX_DAYS
            changed += "log kept $MAX_ENTRIES entries / $MAX_DAYS days"
            undo += { s.retentionEntries = oldEntries; s.retentionDays = oldDays }
        }
        if (s.speakerCeiling < 100) {
            val old = s.speakerCeiling
            s.speakerCeiling = 100; changed += "speaker ceiling 100%"
            undo += { s.speakerCeiling = old }
        }
        if (cc.bridgit.app.feedback.FeedbackUploader.configured && !s.feedbackUpload) {
            s.feedbackUpload = true
            runCatching { cc.bridgit.app.feedback.FeedbackUploadWorker.enqueue(app) }
            changed += "bug reports sent to the Bridgit team"
            undo += { s.feedbackUpload = false }
        }
        if (s.externalCallsPaused) {
            s.externalCallsPaused = false; changed += "external calls back on"
            undo += { s.externalCallsPaused = true }
        }
        if (changed.isNotEmpty()) audit("Max power set: " + changed.joinToString(", "))
        return {
            undo.asReversed().forEach { runCatching { it() } }
            audit("Max power undone")
        }
    }

    // ---- Bridgit's own ADB link: every switch without a Settings page

    /** Bridgit can give itself access over its own ADB link: set up once, and not revoked since (Revoke Super Powers). */
    fun adbReady(): Boolean = cc.bridgit.app.BuildConfig.PRIVATE_CONTROL && app.adb.paired &&
        !app.getSharedPreferences("adb", Context.MODE_PRIVATE).getBoolean(SuperPowers.REVOKED, false)

    /**
     * Runs every ADB grant that isn't on yet (install unknown apps, overlay, usage, accessibility, every permission…),
     * so the guided pass has nothing left to open. Turning installs on this way doesn't stop Bridgit, unlike its
     * Settings switch. [AdbGrants.grantAll] writes the audit line. False when ADB isn't set up or couldn't be reached.
     */
    suspend fun grantOverAdb(): Boolean {
        if (!adbReady()) return false
        val only = PowerPlan.adbPending(AdbGrants.all.associate { g -> g.key to runCatching { g.check(app) }.getOrNull() })
        if (only.isEmpty()) return true
        val lines = withTimeoutOrNull(ADB_TIMEOUT_MS) { runCatching { AdbGrants.grantAll(app, only) }.getOrNull() }
        return lines?.any { it.startsWith("ok") } == true
    }

    /** Maximum powers for an agent: it may use everything and every switch is on. */
    fun isMaximum(agentId: String, requireSuper: Boolean = false): Boolean = cc.bridgit.app.ui.home.HomeStates.controlAgents(app)
        .firstOrNull { it.id == agentId }?.let { a ->
            (a.caps == null || app.registry.modules.all { a.allows(it.id) }) && a.fixer && (!requireSuper || a.superPowers) && hasAllDeviceGrants(agentId)
        } == true

    /** Owner-authorized maximum pairing enables untouched features without undoing a saved off switch. */
    fun enableUnconfiguredFeatures() {
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        app.registry.switchable().filter { !prefs.contains("switch_${it.switchKey}") }
            .forEach { UiActions.setSwitch(app, it, true) }
    }

    /** Reconcile only recorded owner intent. Historical empty grants can also mean revoked. */
    fun reconcileMaximum() {
        // Older Maximum wrote a complete durable platform snapshot, then broke relay admission by
        // clearing caps. Restore only the equivalent explicit list, never missing grants or powers.
        cc.bridgit.app.ui.home.HomeStates.controlAgents(app).filter {
            it.connection == cc.bridgit.app.core.Connection.RELAY && it.caps == null && hasAllDeviceGrants(it.id)
        }.forEach { a ->
            app.agents.update(a.id) { it.copy(caps = it.caps.orEmpty() + app.registry.modules.map { m -> m.id }) }
            audit("Restored ${a.name}'s explicit relay permissions after a previous maximum grant")
        }
        cc.bridgit.app.ui.home.HomeStates.controlAgents(app).filter { it.maximumControl }.forEach { a ->
            enableUnconfiguredFeatures()
            if (a.connection == cc.bridgit.app.core.Connection.RELAY) {
                app.agents.update(a.id) { it.copy(caps = it.caps.orEmpty() + app.registry.modules.map { m -> m.id }) }
            }
            supportedGrants().filter { !app.platform.store.grants(a.id).has(it.id) }
                .forEach { app.platform.grant(a.id, it.id, Long.MAX_VALUE) }
        }
    }

    companion object {
        /** The longest log Settings allows (same bounds as Settings.retentionEntries / retentionDays). */
        const val MAX_ENTRIES = 100_000
        const val MAX_DAYS = 365
        private const val ADB_TIMEOUT_MS = 60_000L
        private const val DND_DETAIL = "android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS"
        private const val HEALTH_PAGE = "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"
        private val STORES = setOf("com.android.vending", "com.google.android.feedback")
        private val LEGACY_STORAGE = setOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE")
        private val HELP = mapOf(
            "overlay" to R.string.power_help_overlay, "battery" to R.string.power_help_battery,
            "usage" to R.string.power_help_usage, "write_settings" to R.string.power_help_write,
            "dnd" to R.string.power_help_dnd, "installs" to R.string.power_help_installs,
            "health" to R.string.power_help_health, "notifications" to R.string.power_help_notifications,
            "background_location" to R.string.power_help_background, PowerPlan.RESTRICTED to R.string.power_restricted_unlocks,
        )
        private val TITLES = mapOf(
            "notifications" to R.string.access_notifications, "camera" to R.string.access_camera, "mic" to R.string.access_mic,
            "photos" to R.string.access_photos, "location" to R.string.power_location, "background_location" to R.string.power_background_location,
            "activity" to R.string.power_activity, "health" to R.string.power_health, "battery" to R.string.access_battery,
            "usage" to R.string.power_usage, "write_settings" to R.string.power_write_settings, "dnd" to R.string.power_dnd,
            "installs" to R.string.access_installs, "overlay" to R.string.power_overlay, PowerPlan.RESTRICTED to R.string.power_restricted,
        )
        private val UNLOCKS = mapOf(
            "notifications" to R.string.access_notifications_unlocks, "camera" to R.string.access_camera_unlocks,
            "mic" to R.string.access_mic_unlocks, "photos" to R.string.access_photos_unlocks, "location" to R.string.power_location_unlocks,
            "background_location" to R.string.power_location_unlocks, "activity" to R.string.power_activity_unlocks,
            "health" to R.string.power_health_unlocks, "battery" to R.string.access_battery_unlocks, "usage" to R.string.power_usage_unlocks,
            "write_settings" to R.string.power_write_settings_unlocks, "dnd" to R.string.power_dnd_unlocks,
            "installs" to R.string.power_installs_unlocks, "overlay" to R.string.power_overlay_unlocks, PowerPlan.RESTRICTED to R.string.power_restricted_unlocks,
        )
    }
}
