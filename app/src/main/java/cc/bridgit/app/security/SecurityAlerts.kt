package cc.bridgit.app.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.ui.MainActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * Owner notifications when an agent's key shows up somewhere new: a direct agent from another Tailscale device
 * (refused while the device lock is on), or a relay agent from a new network. Block pauses the agent; Allow adds
 * the device to the agent's lock. With the fingerprint setting on, Allow isn't offered here: the owner opens the
 * agent and confirms there.
 */
object SecurityAlerts {
    const val CHANNEL = "security"
    const val ACTION_ALLOW = "cc.bridgit.app.security.ALLOW"
    const val ACTION_BLOCK = "cc.bridgit.app.security.BLOCK"
    const val EXTRA_AGENT = "agent"
    const val EXTRA_IP = "ip"
    private const val NOTIFICATION_BASE = 0x5345 // "SE"
    /** One alert per agent and place per 10 minutes, so a retrying agent doesn't flood the shade. */
    private const val QUIET_MS = 10 * 60_000L
    private val lastAlert = ConcurrentHashMap<String, Long>()

    fun createChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.channel_security), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = ctx.getString(R.string.channel_security_desc) })
    }

    /** A direct request with [agentId]'s key came from [ip], which isn't one of its devices. [refused]: the lock refused it. */
    fun newDevice(app: BridgeApp, agentId: String, ip: String, refused: Boolean) {
        val a = app.agents.get(agentId) ?: return
        app.log.system("audit", "security", app.getString(if (refused) R.string.security_log_refused else R.string.security_log_new_device, a.name, ip), level = "warn")
        if (!app.settings.securityPlaceAlerts || !due("$agentId|$ip")) return
        val title = app.getString(if (refused) R.string.security_alert_refused_title else R.string.security_alert_device_title, a.name)
        val text = app.getString(if (refused) R.string.security_alert_refused_text else R.string.security_alert_device_text, a.name, ip)
        post(app, agentId, title, text, allowIp = if (refused && !app.settings.securityBiometric) ip else null)
    }

    /** A relay request for [agentId] came from a network it hasn't used before ([place], e.g. "AS13335 US · Cloudflare"). */
    fun newNetwork(app: BridgeApp, agentId: String, place: String) {
        val a = app.agents.get(agentId) ?: return
        app.log.system("audit", "security", app.getString(R.string.security_log_new_network, a.name, place), level = "warn")
        if (!app.settings.securityPlaceAlerts || !due("$agentId|$place")) return
        post(app, agentId, app.getString(R.string.security_alert_network_title, a.name),
            app.getString(R.string.security_alert_network_text, a.name, place), allowIp = null)
    }

    private fun due(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastAlert[key] ?: 0
        if (now - last < QUIET_MS) return false
        lastAlert[key] = now
        return true
    }

    fun notificationId(agentId: String) = NOTIFICATION_BASE + (agentId.hashCode() and 0xfff)

    private fun post(app: BridgeApp, agentId: String, title: String, text: String, allowIp: String?) {
        val open = PendingIntent.getActivity(app, notificationId(agentId),
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_AGENT, agentId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(action: String, code: Int) = PendingIntent.getBroadcast(app, notificationId(agentId) * 4 + code,
            Intent(app, SecurityActionReceiver::class.java).setAction(action).putExtra(EXTRA_AGENT, agentId).putExtra(EXTRA_IP, allowIp),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, app.getString(R.string.security_action_block), action(ACTION_BLOCK, 1))
        if (allowIp != null) n.addAction(0, app.getString(R.string.security_action_allow), action(ACTION_ALLOW, 2))
        runCatching { app.getSystemService(NotificationManager::class.java)?.notify(notificationId(agentId), n.build()) }
    }
}

/** Block and Allow from a security alert. Not exported: only Bridgit's own notification can send these. */
class SecurityActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as BridgeApp
        val id = intent.getStringExtra(SecurityAlerts.EXTRA_AGENT) ?: return
        val a = app.agents.get(id) ?: return
        when (intent.action) {
            SecurityAlerts.ACTION_BLOCK -> {
                app.agents.update(id) { it.copy(paused = true) }
                app.log.system("audit", "security", app.getString(R.string.security_log_blocked, a.name))
            }
            SecurityAlerts.ACTION_ALLOW -> {
                // The fingerprint setting may have been turned on after the alert was posted.
                if (app.settings.securityBiometric) return
                val ip = intent.getStringExtra(SecurityAlerts.EXTRA_IP) ?: return
                app.agents.allowDevice(id, ip)
                app.log.system("audit", "security", app.getString(R.string.security_log_allowed, a.name, ip))
            }
        }
        app.getSystemService(NotificationManager::class.java)?.cancel(SecurityAlerts.notificationId(id))
    }
}
