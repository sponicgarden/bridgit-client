package cc.bridgit.app.adb

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.planned.NotificationReaderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Revoke Super Powers (AGT-15b): one owner tap that takes back everything that gives agents full control
 * of the phone. In order:
 *  1. Super Powers off for every agent, and ADB control off, so nothing can run a command from here on.
 *  2. Over Bridgit's own ADB connection, undo each ADB grant ([AdbGrants]) that Android has no switch for or
 *     that only ADB gave: restricted settings, accessibility, notification and DND access, usage, system
 *     settings, overlay, screen sharing without the prompt, installs, background running.
 *  3. Last, in one shell call that runs even if the link drops: take back "change secure settings" (so Bridgit
 *     can't turn Wireless debugging back on by itself) and turn Wireless debugging off.
 *  4. Forget the ADB pairing.
 * Ordinary app permissions (camera, microphone…) stay: revoking one makes Android stop Bridgit mid-way, and the
 * owner can change them in App info. Each line of the result says what happened and, if it didn't, where to do it.
 */
object SuperPowers {
    /** One line of the result. [how] says where to finish it by hand when [ok] is false. */
    data class Line(val what: String, val ok: Boolean, val how: String? = null)

    private const val PREFS = "adb"
    /** Set at the start of a revoke; blocks Bridgit from turning Wireless debugging back on until the next pairing. */
    const val REVOKED = "revoked"
    /** Set while a revoke runs, so one Android stopped half way is finished at the next start. */
    private const val IN_PROGRESS = "revoke_in_progress"

    private class Undo(val key: String, val what: String, val how: String, val commands: (Context) -> List<String>)

    private fun pkg(c: Context) = c.packageName

    private fun accessibilityWithoutMe(c: Context): String {
        val mine = AdbGrants.ownAccessibilityServices(c).mapNotNull(ComponentName::unflattenFromString).toSet()
        return Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) !in mine }.joinToString(":")
    }

    /** The reverse of each [AdbGrants] grant except "permissions" (see the class comment) and "secure_settings" (last). */
    private val undos = listOf(
        Undo("restricted_settings", "Restricted settings", "Settings → Apps → Bridgit → ⋮ → restrict settings",
            { listOf("appops set ${pkg(it)} ACCESS_RESTRICTED_SETTINGS default") }),
        Undo("accessibility", "Accessibility (screenshots)", "Settings → Accessibility → Bridgit → off",
            { c ->
                val rest = accessibilityWithoutMe(c)
                if (rest.isEmpty()) listOf("settings put secure enabled_accessibility_services ''", "settings put secure accessibility_enabled 0")
                else listOf("settings put secure enabled_accessibility_services '$rest'")
            }),
        Undo("notification_listener", "Notification access", "Settings → Apps → Special app access → Notification access → Bridgit → off",
            { c -> listOf("cmd notification disallow_listener ${ComponentName(c, NotificationReaderService::class.java).flattenToString()}") }),
        Undo("dnd", "Do Not Disturb access", "Settings → Apps → Special app access → Do Not Disturb access → Bridgit → off",
            { listOf("cmd notification disallow_dnd ${pkg(it)}") }),
        Undo("usage", "Usage access", "Settings → Apps → Special app access → Usage access → Bridgit → off",
            { listOf("appops set ${pkg(it)} GET_USAGE_STATS default") }),
        Undo("write_settings", "Modify system settings", "Settings → Apps → Special app access → Modify system settings → Bridgit → off",
            { listOf("appops set ${pkg(it)} WRITE_SETTINGS default") }),
        Undo("overlay", "Display over other apps", "Settings → Apps → Special app access → Display over other apps → Bridgit → off",
            { listOf("appops set ${pkg(it)} SYSTEM_ALERT_WINDOW default") }),
        Undo("screen_capture", "Screen sharing without the prompt", "Only ADB can undo this; uninstalling Bridgit also does",
            { listOf("appops set ${pkg(it)} PROJECT_MEDIA default") }),
        Undo("installs", "Install updates", "Settings → Apps → Special app access → Install unknown apps → Bridgit → off",
            { listOf("appops set ${pkg(it)} REQUEST_INSTALL_PACKAGES default") }),
        Undo("battery", "Always run in the background", "Settings → Apps → Bridgit → Battery → Optimised",
            { listOf("dumpsys deviceidle whitelist -${pkg(it)}", "cmd appops set ${pkg(it)} RUN_ANY_IN_BACKGROUND default") }),
    )

    /** Runs the whole revoke. Blocking network-free work on IO; call from a coroutine. */
    suspend fun revoke(app: BridgeApp, progress: (String) -> Unit = {}): List<Line> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<Line>()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(REVOKED, true).putBoolean(IN_PROGRESS, true).commit()

        // 1. Nothing can use them from here on.
        progress("Control agents")
        val had = app.agents.all().filter { it.superPowers }
        had.forEach { a -> app.agents.update(a.id) { it.copy(superPowers = false) } }
        lines += Line(if (had.isEmpty()) "No control agent had Super Powers" else "Super Powers off for ${had.joinToString { it.name }}", true)
        if (app.settings.isOn("adb")) app.settings.setOn("adb", false)
        lines += Line("ADB control for control agents off", true)

        // 2-3. Take back what ADB gave, over Bridgit's own connection.
        val connected = BuildConfig.PRIVATE_CONTROL && app.adb.paired &&
            withTimeoutOrNull(20_000) { runCatching { app.adb.connect() }.isSuccess } == true && app.adb.connected
        if (connected) {
            for (u in undos) {
                val g = AdbGrants.all.firstOrNull { it.key == u.key }
                if (g?.check(app) == false) continue // never given, or already off
                progress(u.what)
                u.commands(app).forEach { cmd -> runCatching { app.adb.shell(cmd, timeoutMs = 15_000) } }
                val stillOn = g?.check(app) == true
                lines += Line(u.what, !stillOn, u.how.takeIf { stillOn })
                prefs.edit().remove("granted_${u.key}").apply()
            }
            progress("Wireless debugging")
            // One call: adbd runs both even when turning Wireless debugging off drops this connection.
            runCatching {
                app.adb.shell("pm revoke ${app.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}; " +
                    "settings put global ${AdbClient.ADB_WIFI_ENABLED} 0", timeoutMs = 10_000)
            }
            prefs.edit().remove("granted_secure_settings").apply()
        } else {
            // No ADB link: do what Bridgit can by itself, and say where to do the rest.
            if (AdbGrants.holdsSecureSettings(app)) runCatching {
                Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, accessibilityWithoutMe(app))
            }
            for (u in undos) {
                val g = AdbGrants.all.firstOrNull { it.key == u.key }
                if (g?.check(app) != true) continue
                lines += Line(u.what, false, u.how)
            }
        }
        val secureOff = !AdbGrants.holdsSecureSettings(app)
        lines += Line("Change secure settings", secureOff,
            "Only ADB can undo this (or uninstalling Bridgit); Bridgit no longer uses it while ADB is revoked".takeIf { !secureOff })
        if (AdbClient.wirelessDebugging(app) && !secureOff) runCatching { Settings.Global.putInt(app.contentResolver, AdbClient.ADB_WIFI_ENABLED, 0) }
        val wifiOff = !AdbClient.wirelessDebugging(app)
        lines += Line("Wireless debugging off", wifiOff, "Settings → Developer options → Wireless debugging → off".takeIf { !wifiOff })

        // 4. Forget the pairing: getting ADB back means pairing again.
        app.adb.forget()
        prefs.edit().remove(IN_PROGRESS).apply()
        lines += Line("ADB pairing forgotten", true)
        lines += Line("App permissions (camera, microphone, location…) stay on", true,
            "Change them in Settings → Apps → Bridgit → Permissions")

        val done = lines.count { it.ok }
        app.log.system("audit", "setting", "Owner revoked Super Powers: $done of ${lines.size} steps done" +
            lines.filter { !it.ok }.takeIf { it.isNotEmpty() }?.joinToString(prefix = "; still on: ") { it.what }.orEmpty())
        lines
    }

    /** A revoke cut short by Android stopping Bridgit: finish forgetting the pairing at the next start. */
    fun finishIfInterrupted(app: BridgeApp) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(IN_PROGRESS, false)) return
        app.adb.forget()
        if (app.settings.isOn("adb")) app.settings.setOn("adb", false)
        prefs.edit().remove(IN_PROGRESS).apply()
        app.log.system("audit", "setting", "Finished revoking Super Powers after Bridgit was stopped part way")
    }
}
