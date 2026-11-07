package cc.bridgit.app.feedback

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.R
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.log.Redactor
import cc.bridgit.app.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** What a report carries automatically (FBK-06, FBK-04 item 7). */
object FeedbackContext {
    /**
     * [screen] is the screen the owner was on ("Activity"), [screenContext] a short hint
     * such as the selected agent or filter (≤500 chars). Agent names appear only
     * when the owner was on an agent screen.
     */
    fun build(app: BridgeApp, screen: String?, screenContext: String?, captureSource: String): JSONObject {
        val l = app.listener.value
        val switches = JSONObject()
        app.registry.switchable().forEach { switches.put(it.id, it.enabled) }
        return JSONObject()
            .put("app_version", app.appVersion()).put("version_code", app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode)
            .put("git_commit", BuildConfig.GIT_COMMIT).put("dirty_build", BuildConfig.DIRTY_BUILD)
            .put("build_type", if (BuildConfig.DEBUG) "debug" else "release")
            .put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}").put("android_version", Build.VERSION.RELEASE)
            .put("api_level", Build.VERSION.SDK_INT).put("security_patch", Build.VERSION.SECURITY_PATCH)
            .put("locale", Locale.getDefault().toLanguageTag())
            .put("screen", screen ?: JSONObject.NULL)
            .put("screen_context", screenContext?.let { Redactor.string(it, FeedbackLimits.CONTEXT_MAX) } ?: JSONObject.NULL)
            .put("listener", JSONObject().put("running", l.running).put("listening", l.listening)
                .put("camera_ready", l.cameraReady).put("mic_ready", l.micReady).put("bind_error", l.bindError ?: JSONObject.NULL))
            .put("tailscale", if (l.boundTo.isNotEmpty()) "connected" else "not connected")
            .put("switches", switches).put("agent_count", app.agents.count)
            .put("capture_source", captureSource)
            .put("application_id", app.packageName)
            .put("device", runCatching { device(app) }.getOrElse { JSONObject().put("error", it.javaClass.simpleName) })
    }

    /** More about the phone (FBK-14, owner request 2 Nov 2026): hardware, memory, storage, power, display, network. */
    private fun device(app: BridgeApp): JSONObject {
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = android.os.StatFs(app.filesDir.absolutePath)
        val bat = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.let { val l = it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1); val sc = it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100); if (l >= 0) l * 100 / sc else null }
        val power = app.getSystemService(android.os.PowerManager::class.java)
        val dm = app.resources.displayMetrics
        val cfg = app.resources.configuration
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "none"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val pkg = app.packageManager.getPackageInfo(app.packageName, 0)
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
            else @Suppress("DEPRECATION") app.packageManager.getInstallerPackageName(app.packageName)
        }.getOrNull()
        val mb = 1024L * 1024L
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND).put("model", Build.MODEL)
            .put("device", Build.DEVICE).put("product", Build.PRODUCT).put("hardware", Build.HARDWARE)
            .put("soc", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else JSONObject.NULL)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("build_id", Build.ID).put("build_fingerprint", Build.FINGERPRINT)
            .put("android_codename", Build.VERSION.CODENAME).put("incremental", Build.VERSION.INCREMENTAL)
            .put("ram_total_mb", mem.totalMem / mb).put("ram_available_mb", mem.availMem / mb).put("low_memory", mem.lowMemory)
            .put("storage_free_mb", stat.availableBytes / mb).put("storage_total_mb", stat.totalBytes / mb)
            .put("battery_percent", level ?: JSONObject.NULL)
            .put("charging", (bat?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
            .put("power_save", power?.isPowerSaveMode ?: JSONObject.NULL)
            .put("ignoring_battery_optimizations", power?.isIgnoringBatteryOptimizations(app.packageName) ?: JSONObject.NULL)
            .put("screen_px", "${dm.widthPixels}x${dm.heightPixels}").put("density_dpi", dm.densityDpi)
            .put("font_scale", cfg.fontScale.toDouble())
            .put("dark_mode", (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            .put("timezone", java.util.TimeZone.getDefault().id)
            .put("uptime_min", android.os.SystemClock.elapsedRealtime() / 60_000)
            .put("network", network)
            .put("installer", installer ?: JSONObject.NULL)
            .put("first_install", isoAt(pkg.firstInstallTime)).put("last_update", isoAt(pkg.lastUpdateTime))
    }

    /**
     * Who filed an agent report (FBK-14, owner request 2 Nov 2026): the agent record, what it reported
     * through POST /v1/me (AGT-16), its User-Agent, and anything it adds in `agent_info`. All but the
     * record is the agent's own word, unverified.
     */
    fun agentInfo(app: BridgeApp, agentId: String, userAgent: String?, extra: JSONObject?): JSONObject {
        val a = app.agents.get(agentId)
        val o = JSONObject()
            .put("id", agentId) // FBK-15: the phone agent id links the report to the agent's team key exactly
            .put("name", a?.name ?: JSONObject.NULL).put("connection", a?.connection?.id ?: JSONObject.NULL)
            .put("developer", a?.fixer ?: false) // the backend's name for Fixer Mode
            .put("fixer_mode", a?.fixer ?: false).put("super_powers", a?.superPowers ?: false)
            .put("model", a?.model ?: JSONObject.NULL).put("harness", a?.harness ?: JSONObject.NULL)
            .put("model_reported_at", a?.modelReportedAt?.takeIf { it > 0 }?.let { isoAt(it) } ?: JSONObject.NULL)
            .put("paired_at", a?.createdAt?.let { isoAt(it) } ?: JSONObject.NULL)
            .put("api_revision_known", a?.knownApiRevision ?: 0)
            .put("user_agent", userAgent?.filter { it in ' '..'~' }?.take(200) ?: JSONObject.NULL)
        extra?.let { e ->
            val clean = JSONObject()
            e.keys().asSequence().take(AGENT_INFO_KEYS).forEach { k ->
                val v = e.opt(k)
                if (k.length <= 40 && (v is String || v is Number || v is Boolean)) clean.put(k, if (v is String) v.take(200) else v)
            }
            o.put("reported", clean)
            // The agent's own words win over the older /v1/me values for model and harness.
            clean.optString("model").takeIf { it.isNotBlank() }?.let { o.put("model", it) }
            clean.optString("harness").takeIf { it.isNotBlank() }?.let { o.put("harness", it) }
        }
        return o
    }

    private const val AGENT_INFO_KEYS = 20

    /** The last 50 audit and diagnostic entries, as stored (already redacted, LOG-02). */
    fun recentLog(app: BridgeApp): JSONArray {
        val q = LogQuery(channels = listOf("audit", "diagnostic"), limit = FeedbackLimits.LOG_ENTRIES)
        return JSONArray(app.log.query(q).map { e -> e.toJson(::isoAt, e.agentId?.let { app.agents.get(it)?.name } ?: e.agent) })
    }
}

/**
 * Tells the owner when a developer agent marks a report fixed / won't fix / duplicate
 * (FBK-11), with Confirm and Still broken actions (Spotka's verify step).
 */
object FeedbackNotifier {
    const val CHANNEL = "feedback"
    const val ACTION = "cc.bridgit.app.FEEDBACK_VERIFY"

    fun createChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, ctx.getString(R.string.channel_feedback), NotificationManager.IMPORTANCE_LOW)
                .apply { description = ctx.getString(R.string.channel_feedback_desc) })
    }

    fun statusChanged(ctx: Context, r: Report) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val notifId = 5000 + r.id.toInt()
        if (!StatusMachine.notifiesOwner(r.status)) { nm.cancel(notifId); return }
        fun action(to: ReportStatus, code: Int) = PendingIntent.getBroadcast(ctx, notifId * 10 + code,
            Intent(ctx, FeedbackActionReceiver::class.java).setAction(ACTION).putExtra("id", r.id).putExtra("to", to.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(ctx, notifId, Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_REPORT_ID, r.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = ctx.getString(R.string.feedback_notif_title, r.number, StatusMachine.label(r.status).substringBefore(" —").lowercase())
        nm.notify(notifId, NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif).setColor(ctx.getColor(R.color.brand_primary))
            .setContentTitle(title)
            .setContentText(r.history.lastOrNull()?.note ?: r.firstLine)
            .setContentIntent(open).setAutoCancel(true)
            .addAction(0, ctx.getString(R.string.feedback_confirm), action(ReportStatus.CONFIRMED, 1))
            .addAction(0, ctx.getString(R.string.feedback_still_broken), action(ReportStatus.REOPENED, 2))
            .build())
    }
}

/** Confirm / Still broken from the notification. Not exported: only our own PendingIntents reach it. */
class FeedbackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FeedbackNotifier.ACTION) return
        val app = context.applicationContext as BridgeApp
        val id = intent.getLongExtra("id", -1)
        val to = ReportStatus.of(intent.getStringExtra("to")) ?: return
        runCatching {
            val r = app.feedback.move(id, to, Actor.OWNER, "owner", null)
            app.log.system("audit", "feedback", "You marked report ${r.number} ${if (to == ReportStatus.CONFIRMED) "confirmed" else "still broken"}")
        }
        context.getSystemService(NotificationManager::class.java).cancel(5000 + id.toInt())
    }
}
