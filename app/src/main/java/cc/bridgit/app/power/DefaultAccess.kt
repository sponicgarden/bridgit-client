package cc.bridgit.app.power

import android.Manifest
import android.content.Context
import android.os.Build
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.adb.AdbGrants
import cc.bridgit.app.capability.granted
import cc.bridgit.app.ui.home.UiActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Notifications and "run when the screen is off" (no battery optimisation) are on by default.
 * Android drops both on a fresh install, and Samsung sometimes drops the battery one on its own.
 *
 * - With Bridgit's own ADB link set up (and not revoked), they are put back silently: at every app
 *   start and whenever Home opens.
 * - Without it, Home asks for each one once per install with Android's own one-tap dialog, so the
 *   owner isn't left with banners to find and tap.
 */
object DefaultAccess {
    private const val PREFS = "default_access"
    private const val ASKED_NOTIF = "asked_notifications"
    private const val ASKED_BATTERY = "asked_battery"
    private const val MIN_GAP_MS = 5 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    @Volatile private var lastTry = 0L

    fun notificationsMissing(c: Context) =
        Build.VERSION.SDK_INT >= 33 && !c.granted(Manifest.permission.POST_NOTIFICATIONS)

    fun batteryMissing(c: Context) = UiActions.batteryOptimised(c)

    /** Puts both back over Bridgit's ADB link when one is missing. [onDone] runs only when something changed. */
    fun healOverAdb(app: BridgeApp, onDone: () -> Unit = {}) {
        if (!notificationsMissing(app) && !batteryMissing(app)) return
        if (!PowerAccess(app).adbReady()) return
        val now = System.currentTimeMillis()
        if (now - lastTry < MIN_GAP_MS || !running.compareAndSet(false, true)) return
        lastTry = now
        scope.launch {
            try {
                val pkg = app.packageName
                val cmds = mutableListOf<String>()
                val notif = notificationsMissing(app)
                val battery = batteryMissing(app)
                if (notif) cmds += "pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}"
                if (battery) cmds += AdbGrants.all.first { it.key == "battery" }.commands(app)
                withTimeoutOrNull(45_000) {
                    for (cmd in cmds) runCatching { app.adb.shell(cmd, timeoutMs = 15_000) }
                }
                val fixed = listOfNotNull(
                    "notifications".takeIf { notif && !notificationsMissing(app) },
                    "running when the screen is off".takeIf { battery && !batteryMissing(app) },
                )
                if (fixed.isNotEmpty()) {
                    app.log.system("audit", "setting", "Turned ${fixed.joinToString(" and ")} back on over ADB")
                    onDone()
                }
            } finally {
                running.set(false)
            }
        }
    }

    /** Which one-tap Android dialog Home should open by itself now, if any. Each is asked at most once per install. */
    enum class Ask { NOTIFICATIONS, BATTERY }

    fun nextAsk(app: BridgeApp): Ask? {
        if (PowerAccess(app).adbReady()) return null // fixed silently instead
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when {
            notificationsMissing(app) && !isDismissed(app, Ask.NOTIFICATIONS) && !p.getBoolean(ASKED_NOTIF, false) -> Ask.NOTIFICATIONS
            batteryMissing(app) && !isDismissed(app, Ask.BATTERY) && !p.getBoolean(ASKED_BATTERY, false) -> Ask.BATTERY
            else -> null
        }
    }

    /** A dismissal survives app restarts and in-place updates, independently for each alert.
     * It hides the reminder only: Android's permission and capture safety checks stay authoritative.
     */
    fun isDismissed(context: Context, ask: Ask): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("dismissed_${ask.name}", false)

    fun dismiss(context: Context, ask: Ask) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("dismissed_${ask.name}", true)
            .putBoolean(if (ask == Ask.NOTIFICATIONS) ASKED_NOTIF else ASKED_BATTERY, true).apply()
    }

    fun markAsked(app: Context, ask: Ask) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(if (ask == Ask.NOTIFICATIONS) ASKED_NOTIF else ASKED_BATTERY, true).apply()
    }
}
