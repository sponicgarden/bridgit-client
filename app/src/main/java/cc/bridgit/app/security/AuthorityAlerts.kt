package cc.bridgit.app.security

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.core.authority.AuthorityOutbox
import cc.bridgit.app.ui.MainActivity
import java.text.DateFormat
import java.util.Date

/**
 * Approver alerts on this device (architecture §4.7): one notification on the security channel and one
 * audit line per stored alert. Waiting requests that were cancelled are logged only.
 */
object AuthorityAlerts {
    private const val NOTIFICATION_BASE = 0x4155_0000 // "AU"

    fun title(app: BridgeApp, kind: String): Int? = when (kind) {
        "approver_enrolled", "approver_added" -> R.string.approver_alert_added
        "approver_removed" -> R.string.approver_alert_removed
        "recovery_started" -> R.string.approver_alert_recovery_started
        "recovery_cancelled" -> R.string.approver_alert_recovery_cancelled
        "recovery_cleared" -> R.string.approver_alert_recovery_cleared
        "recovery_cancel_late" -> R.string.approver_alert_recovery_late
        else -> null
    }

    fun show(app: BridgeApp, alert: AuthorityOutbox.Alert) {
        val id = title(app, alert.kind)
        app.log.system("audit", "security", "Approver devices: ${alert.kind.replace('_', ' ')} ${alert.body}", level = if (id == null) "info" else "warn")
        if (id == null) return
        val text = alert.body.optLong("clear_at").takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
        val open = PendingIntent.getActivity(app, (alert.id and 0xffff).toInt(), Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, SecurityAlerts.CHANNEL).setSmallIcon(R.drawable.ic_notif).setContentTitle(app.getString(id))
            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (text != null) n.setContentText(text)
        runCatching { app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_BASE + (alert.id and 0xffff).toInt(), n.build()) }
    }
}
