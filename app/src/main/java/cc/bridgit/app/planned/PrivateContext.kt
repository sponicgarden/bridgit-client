package cc.bridgit.app.planned

import android.Manifest
import android.app.AppOpsManager
import android.app.Notification
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.ktor.server.routing.Route
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

object NotificationMemory {
    private val items = linkedMapOf<String, JSONObject>()
    @Synchronized fun put(key: String, value: JSONObject) { items.remove(key); items[key] = value; while (items.size > 100) items.remove(items.keys.first()) }
    @Synchronized fun remove(key: String) { items.remove(key) }
    @Synchronized fun clear() { items.clear() }
    @Synchronized fun read() = JSONArray(items.values.toList().reversed().map { JSONObject(it.toString()) })
    fun allowed(ctx: Context): Boolean = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        .orEmpty().split(':').any { ComponentName.unflattenFromString(it)?.className == NotificationReaderService::class.java.name && ComponentName.unflattenFromString(it)?.packageName == ctx.packageName }
}
class NotificationReaderService : cc.bridgit.app.platform.NotificationBridge() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        val app = application as BridgeApp
        if (!app.settings.isOn("notifications") || !app.settings.listenerEnabled || sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.visibility == Notification.VISIBILITY_SECRET) return
        NotificationMemory.put(sbn.key, JSONObject().put("package", sbn.packageName).put("posted_at", isoAt(sbn.postTime))
            .put("title", n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(500) ?: "")
            .put("text", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.take(4000)
                ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(4000) ?: ""))
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { NotificationMemory.remove(sbn.key) }
    override fun onListenerDisconnected() { super.onListenerDisconnected(); NotificationMemory.clear() }
}
class NotificationsModule(app: BridgeApp) : Capability(app) {
    override val id = "notifications"
    override val titleRes = R.string.cap_notifications
    override val descriptionRes = R.string.cap_notifications_desc
    override val group = "private context"
    override val switchKey = "notifications"
    override val endpoints = listOf(PlannedEndpoints.NOTIFICATIONS)
    override fun blockedReason() = if (!NotificationMemory.allowed(app)) "notification_access_off" else null
    override fun Route.install(api: ApiServer) = with(api) { endpoint(PlannedEndpoints.NOTIFICATIONS) {
        requireUsable(); Reply.Json(JSONObject().put("items", NotificationMemory.read()).put("retention", "RAM only; since listener connected, at most 100 active notifications"))
    } }
}
class UsageModule(app: BridgeApp) : Capability(app) {
    override val id = "usage"
    override val titleRes = R.string.cap_usage
    override val descriptionRes = R.string.cap_usage_desc
    override val group = "private context"
    override val switchKey = "usage"
    override val endpoints = listOf(PlannedEndpoints.USAGE)
    override fun blockedReason() = if (usageAllowed(app)) null else "usage_access_off"
    override fun Route.install(api: ApiServer) = with(api) { endpoint(PlannedEndpoints.USAGE) {
        requireUsable()
        val until = time(query("until"), System.currentTimeMillis())
        val since = time(query("since"), until - 86400_000)
        if (since >= until || until - since > 7L * 86400_000 || until > System.currentTimeMillis() + 60_000) throw ErrorCatalog.error("invalid_parameter", "Use an ordered since/until interval of at most seven days, ending no later than now.")
        val records = app.getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(since, until)
        Reply.Json(JSONObject().put("since", isoAt(since)).put("until", isoAt(until)).put("items", JSONArray(records.values.sortedByDescending { it.totalTimeInForeground }.take(100).map {
            JSONObject().put("package", it.packageName).put("foreground_ms", it.totalTimeInForeground).put("last_used_at", isoAt(it.lastTimeUsed))
        })).put("note", "Android usage buckets may extend beyond the requested interval."))
    } }
    companion object {
        fun usageAllowed(ctx: Context): Boolean {
            val mode = ctx.getSystemService(AppOpsManager::class.java)
                .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            return UsageAccessRule.allowed(mode, ctx.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        fun time(s: String?, fallback: Long): Long = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() ?: throw ErrorCatalog.error("invalid_parameter", "Times must be ISO-8601 UTC, e.g. 2026-10-28T00:00:00Z.") } ?: fallback
    }
}
