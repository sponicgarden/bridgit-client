package cc.bridgit.app.update

import android.content.Context
import cc.bridgit.app.BridgeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Automatic updates (A0050): on by default. Checks the official manifest, downloads and verifies the
 * APK exactly as the dialog does, then installs it without a tap when the phone allows that: over
 * Bridgit's own ADB link, or (Android 12+) a self-update session once "Install unknown apps" is on.
 * Android still requires the same signing key. When no silent path exists, the daily offer is used.
 * Runs from [AutoUpdateWorker] every few hours, when Bridgit is opened, and when an owner-approved
 * agent asks (`updates.update_now`).
 */
object AutoUpdate {
    private const val PREFS = "update"
    private const val KEY_OFF = "auto_install_off"
    private const val KEY_RESULT = "auto_install_result"
    private const val KEY_AT = "auto_install_at"
    private const val KEY_VERSION = "auto_install_version"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /** Outcomes, also returned by the API as `last_result`. */
    object Result {
        const val INSTALLING = "installing"
        const val UP_TO_DATE = "up_to_date"
        const val NEEDS_OWNER = "needs_owner"
        const val UNSUPPORTED = "unsupported"
        const val OFF = "off"
        const val FAILED = "failed"
        const val BUSY = "busy"
    }

    fun enabled(ctx: Context): Boolean = !prefs(ctx).getBoolean(KEY_OFF, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_OFF, !on).apply()
        if (on) runCatching { AutoUpdateWorker.schedule(ctx) }
    }

    /** This build can update itself at all (not Play, not a debug/test build). */
    val supported: Boolean get() = AutoUpdateLogic.supported(UpdateDialog.isDebugBuild, UpdateDialog.playUpdates)

    /** Bridgit can install an update now without anyone tapping. */
    fun canInstallSilently(app: BridgeApp): Boolean = AutoUpdateLogic.canInstallSilently(
        UpdateInstaller.adbInstallAvailable(app), UpdateInstaller.sessionInstallAvailable(), UpdateInstaller.canRequestInstalls(app))

    fun status(app: BridgeApp): JSONObject {
        val p = prefs(app)
        return JSONObject().put("enabled", enabled(app)).put("supported", supported)
            .put("silent_install_available", supported && canInstallSilently(app))
            .put("last_result", p.getString(KEY_RESULT, null) ?: JSONObject.NULL)
            .put("last_at", p.getLong(KEY_AT, 0L).takeIf { it > 0 } ?: JSONObject.NULL)
            .put("last_version", p.getString(KEY_VERSION, null) ?: JSONObject.NULL)
            .put("check_interval_hours", AutoUpdateWorker.INTERVAL_HOURS)
    }

    /** Starts [run] in the background; for app open and agent requests. */
    fun runInBackground(app: BridgeApp, reason: String, force: Boolean = false) {
        scope.launch { run(app, reason, force) }
    }

    /**
     * One check-download-install pass. [force] runs it even when automatic updates are off (an
     * owner-approved agent request). Returns a [Result] value and records it for [status].
     */
    suspend fun run(app: BridgeApp, reason: String, force: Boolean = false): String {
        if (!lock.tryLock()) return Result.BUSY
        try {
            val gate = AutoUpdateLogic.gate(supported, force || enabled(app), canInstallSilently(app))
            if (gate != null) return record(app, gate, null)
            val m = when (val c = UpdateInstaller.check(app)) {
                is UpdateInstaller.Check.Newer -> c.manifest
                is UpdateInstaller.Check.Refused -> return record(app, Result.FAILED, null, "check ${c.problem.name.lowercase()}")
                else -> return record(app, Result.UP_TO_DATE, null)
            }
            log(app, "Automatic update ($reason): downloading ${m.versionName} (${m.versionCode})")
            val ready = when (val d = UpdateInstaller.download(app, m) {}) {
                is UpdateInstaller.Download.Ready -> d
                is UpdateInstaller.Download.Refused -> return record(app, Result.FAILED, m.versionName, "download ${d.problem.name.lowercase()}")
            }
            record(app, Result.INSTALLING, m.versionName)
            if (UpdateInstaller.adbInstallAvailable(app) &&
                UpdateInstaller.installViaAdb(app, ready.file, m) is UpdateInstaller.AdbInstall.Installed) return Result.INSTALLING
            if (UpdateInstaller.sessionInstallAvailable() && UpdateInstaller.canRequestInstalls(app)) {
                when (UpdateInstaller.installViaSession(app, ready.file, m)) {
                    is UpdateInstaller.SessionInstall.Installed -> return Result.INSTALLING
                    // Android wants its own confirm: leave it for the owner's next open (the daily offer).
                    is UpdateInstaller.SessionInstall.NeedsConfirm -> { ready.file.delete(); return record(app, Result.NEEDS_OWNER, m.versionName) }
                    is UpdateInstaller.SessionInstall.Fallback -> Unit
                }
            }
            ready.file.delete()
            return record(app, Result.NEEDS_OWNER, m.versionName)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return record(app, Result.FAILED, null, e.javaClass.simpleName)
        } finally {
            lock.unlock()
        }
    }

    private fun record(app: BridgeApp, result: String, version: String?, detail: String? = null): String {
        prefs(app).edit().putString(KEY_RESULT, result).putLong(KEY_AT, System.currentTimeMillis())
            .putString(KEY_VERSION, version).apply()
        if (result != Result.UP_TO_DATE && result != Result.OFF && result != Result.UNSUPPORTED)
            log(app, "Automatic update: $result${version?.let { " $it" } ?: ""}${detail?.let { " ($it)" } ?: ""}",
                if (result == Result.FAILED) "warn" else "info")
        return result
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun log(app: BridgeApp, message: String, level: String = "info") =
        runCatching { app.log.system("diagnostic", "service", message, level) }
}

/** Pure decisions for [AutoUpdate], unit-tested. */
object AutoUpdateLogic {
    fun supported(debug: Boolean, play: Boolean): Boolean = !debug && !play

    fun canInstallSilently(adb: Boolean, sessionApi: Boolean, installsAllowed: Boolean): Boolean =
        adb || (sessionApi && installsAllowed)

    /** Why a pass stops before checking, or null to go ahead. */
    fun gate(supported: Boolean, on: Boolean, silent: Boolean): String? = when {
        !supported -> AutoUpdate.Result.UNSUPPORTED
        !on -> AutoUpdate.Result.OFF
        !silent -> AutoUpdate.Result.NEEDS_OWNER
        else -> null
    }
}

/** The periodic background pass. Needs a network and a battery that isn't low. */
class AutoUpdateWorker(ctx: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BridgeApp
        if (AutoUpdate.enabled(app)) AutoUpdate.run(app, "scheduled")
        return Result.success()
    }

    companion object {
        const val INTERVAL_HOURS = 6L
        private const val WORK = "auto-update"

        fun schedule(ctx: Context) {
            if (!AutoUpdate.supported) return
            val req = androidx.work.PeriodicWorkRequestBuilder<AutoUpdateWorker>(INTERVAL_HOURS, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true).build()).build()
            androidx.work.WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, androidx.work.ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
