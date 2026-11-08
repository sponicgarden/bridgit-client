package cc.bridgit.app.link

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R

/**
 * LNK-28: while another Bridgit is using this one, a quiet notification "Used by kitchen-tab" with Disconnect. After
 * a disconnect it offers Undo for the 10 seconds Undo works (LNK-26). Shown again at most every [QUIET_MS] per link,
 * and it goes away by itself [SHOW_MS] after the last use.
 */
object LinkNotifier {
    const val CHANNEL = "linked_bridgits"
    private const val ACTION_DISCONNECT = "cc.bridgit.app.link.DISCONNECT"
    private const val ACTION_UNDO = "cc.bridgit.app.link.UNDO"
    private const val EXTRA_LINK = "link"
    private const val SHOW_MS = 2 * 60_000L
    private const val QUIET_MS = 30_000L
    private val last = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun createChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.channel_links), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
    }

    fun id(linkId: String) = 47_000 + (linkId.hashCode() and 0xfff)

    private fun action(app: BridgeApp, linkId: String, action: String, code: Int) = PendingIntent.getBroadcast(app, id(linkId) * 4 + code,
        Intent(app, LinkActionReceiver::class.java).setAction(action).putExtra(EXTRA_LINK, linkId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** A link agent just used this device for one of its services. */
    fun used(app: BridgeApp, r: Links.Rec, now: Long = System.currentTimeMillis()) {
        if (now - (last[r.id] ?: 0) < QUIET_MS) return
        last[r.id] = now
        val name = app.links.peerLabel(r)
        val n = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(app.getString(R.string.link_used_by, name)).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS).setTimeoutAfter(SHOW_MS)
            .addAction(0, app.getString(R.string.link_disconnect), action(app, r.id, ACTION_DISCONNECT, 1))
        runCatching { app.getSystemService(NotificationManager::class.java)?.notify(id(r.id), n.build()) }
    }

    /** After a disconnect from the notification: the same notification, with Undo while it works. */
    fun disconnected(app: BridgeApp, r: Links.Rec) {
        val n = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(app.getString(R.string.link_disconnected, app.links.peerLabel(r))).setOnlyAlertOnce(true).setSilent(true)
            .setTimeoutAfter(Links.UNDO_MS).addAction(0, app.getString(R.string.link_undo), action(app, r.id, ACTION_UNDO, 2))
        runCatching { app.getSystemService(NotificationManager::class.java)?.notify(id(r.id), n.build()) }
    }

    fun cancel(app: BridgeApp, linkId: String) = runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(id(linkId)) }

    /** Disconnect and Undo from the notification. Not exported: only Bridgit's own notification can send these. */
    class LinkActionReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val app = ctx.applicationContext as BridgeApp
            val id = intent.getStringExtra(EXTRA_LINK) ?: return
            val pending = goAsync()
            kotlin.concurrent.thread(isDaemon = true, name = "link-action") {
                try {
                    val person = Granter.person(app.links.selfName())
                    when (intent.action) {
                        ACTION_DISCONNECT -> runCatching { app.links.disconnect(id, "in", person) }.onSuccess {
                            app.links.get(id)?.let { disconnected(app, it) } }
                        ACTION_UNDO -> { runCatching { app.links.undo(id, person) }; cancel(app, id) }
                    }
                } finally { pending.finish() }
            }
        }
    }
}
