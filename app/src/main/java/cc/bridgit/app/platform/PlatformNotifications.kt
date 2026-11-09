package cc.bridgit.app.platform

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import cc.bridgit.app.*
import org.json.JSONObject

object PlatformNotifications {
    /**
     * Operations and control sessions waiting for the owner expire in about five minutes, so they go on the
     * high-importance channel (heads-up), like urgent notices (A0145: a default-importance one went unseen).
     */
    fun channelFor(record:JSONObject):String =
        if(record.optString("priority")=="urgent" || record.optString("kind") in setOf("operation","session")) cc.bridgit.app.interact.InteractionNotifier.CHANNEL_QUESTIONS
        else BridgeApp.INTERACTION_CHANNEL
    /** Whether a notification on this channel would reach the shade: permission, app switch and channel all on. */
    fun canPost(app:BridgeApp,channel:String):Boolean {
        if(!app.granted(android.Manifest.permission.POST_NOTIFICATIONS)) return false
        val nm=app.getSystemService(NotificationManager::class.java) ?: return false
        if(!nm.areNotificationsEnabled()) return false
        return (nm.getNotificationChannel(channel)?.importance ?: NotificationManager.IMPORTANCE_DEFAULT)!=NotificationManager.IMPORTANCE_NONE
    }
    /** "device.action open_app" rather than just "device.action". */
    fun actionLabel(record:JSONObject):String { val command=record.optString("command",record.optString("kind")); val type=record.optJSONObject("input")?.optString("type").orEmpty(); return if(command=="device.action" && type.isNotEmpty()) "$command $type" else command }
    /** Posts the review notification; returns whether Android will show it. */
    fun show(app:BridgeApp,record:JSONObject):Boolean {
        val channel=channelFor(record)
        if(!canPost(app,channel)) return false
        val id=record.getString("id"); val name=record.optJSONObject("identity")?.optString("name") ?: "Agent"
        val pending=PendingIntent.getActivity(app,id.hashCode(),Intent(app,ControlCenterActivity::class.java).putExtra(ControlCenterActivity.EXTRA_REVIEW_ID,id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title=if(record.optString("kind")=="interaction") "$name: ${record.optString("title")}" else if(record.optString("command")=="owner.configure") "$name: ${record.optJSONObject("review_context")?.optString("title") ?: "Review request"}" else "$name requests ${actionLabel(record)}"
        val text=if(record.optString("kind")=="interaction") record.optString("text") else "Review the exact request and confirm on this phone."
        // Android 8+ takes importance from the channel, not setPriority (channelFor above).
        val high=channel==cc.bridgit.app.interact.InteractionNotifier.CHANNEL_QUESTIONS
        val deadline=record.optLong(if(record.optString("kind")=="session") "request_expires_at" else "expires_at")
        val builder=NotificationCompat.Builder(app,channel).setSmallIcon(cc.bridgit.app.R.drawable.ic_notif).setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pending).setAutoCancel(true)
        val left=deadline-System.currentTimeMillis()
        if(deadline>0 && left>0) builder.setTimeoutAfter(left) // gone once the request expires
        app.getSystemService(NotificationManager::class.java).notify(id.hashCode(),builder.setPriority(if(high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(NotificationCompat.Builder(app,channel).setSmallIcon(cc.bridgit.app.R.drawable.ic_notif).setContentTitle("Bridgit agent request").build()).build())
        return true
    }
}
class PauseTile:android.service.quicksettings.TileService() {
    override fun onStartListening() { super.onStartListening(); val app=application as BridgeApp; qsTile.state=if(app.policy.listenerEnabled) android.service.quicksettings.Tile.STATE_ACTIVE else android.service.quicksettings.Tile.STATE_INACTIVE; qsTile.label="Bridgit"; qsTile.updateTile() }
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // the Intent form is the only one before Android 14
    override fun onClick() {
        super.onClick(); val app=application as BridgeApp
        if(app.policy.listenerEnabled) { app.platform.stopAll(); app.policy.listenerEnabled=false; BridgeService.stop(this); onStartListening() }
        else { val pending=PendingIntent.getActivity(this,0,Intent(this,cc.bridgit.app.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_IMMUTABLE)
            if(android.os.Build.VERSION.SDK_INT>=34) startActivityAndCollapse(pending) else @Suppress("DEPRECATION") startActivityAndCollapse(Intent(this,cc.bridgit.app.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
class ApplianceAdmin:android.app.admin.DeviceAdminReceiver()
