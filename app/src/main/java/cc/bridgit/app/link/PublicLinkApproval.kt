package cc.bridgit.app.link

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.security.SecurityAlerts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Devices in the wild (§16): every request from a public link that changes anything waits up to [WAIT_MS] for the
 * owner's Allow on a notification. Deny, swiping it away or no answer refuses it. One request waits at a time; another
 * meanwhile is refused at once (`reason`: busy), so a stranger can't queue prompts.
 */
object PublicLinkApproval {
    const val ACTION_ALLOW = "cc.bridgit.app.link.PUBLIC_ALLOW"
    const val ACTION_DENY = "cc.bridgit.app.link.PUBLIC_DENY"
    const val EXTRA_REQUEST = "request"
    const val WAIT_MS = 30_000L
    private const val NOTIFICATION_ID = 0x4C50 // "LP"

    private class Pending(val id: String, val answer: CompletableDeferred<Boolean>)
    private val pending = AtomicReference<Pending?>(null)

    /** Test hook: answers every request without a notification. Null in the app. */
    @Volatile var autoAnswer: Boolean? = null

    suspend fun require(app: BridgeApp, peer: String, spec: EndpointSpec, waitMs: Long = WAIT_MS) {
        autoAnswer?.let { if (it) return else throw declined("declined") }
        val p = Pending(UUID.randomUUID().toString(), CompletableDeferred())
        if (!pending.compareAndSet(null, p)) throw declined("busy")
        try {
            post(app, p.id, peer, "${spec.method} ${spec.fullPath}")
            app.log.system("audit", "link", app.getString(R.string.link_public_log_asked, peer, spec.fullPath))
            val allowed = withTimeoutOrNull(waitMs) { p.answer.await() }
            app.log.system("audit", "link", app.getString(if (allowed == true) R.string.link_public_log_allowed else R.string.link_public_log_refused, peer, spec.fullPath))
            if (allowed != true) throw declined(if (allowed == false) "declined" else "no_answer")
        } finally {
            pending.compareAndSet(p, null)
            runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        }
    }

    private fun declined(reason: String) = ErrorCatalog.error("link_approval_declined",
        when (reason) { "busy" -> "Another request from a device in the wild is waiting for the owner. Retry later."
            "declined" -> "The owner declined this request."; else -> "The owner didn't answer within ${WAIT_MS / 1000} s." },
        mapOf("reason" to reason))

    fun answer(id: String, allow: Boolean): Boolean {
        val p = pending.get() ?: return false
        return p.id == id && p.answer.complete(allow)
    }

    private fun post(app: BridgeApp, id: String, peer: String, what: String) {
        fun action(action: String, code: Int) = PendingIntent.getBroadcast(app, NOTIFICATION_ID * 4 + code,
            Intent(app, PublicLinkApprovalReceiver::class.java).setAction(action).putExtra(EXTRA_REQUEST, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val allow = NotificationCompat.Action.Builder(0, app.getString(R.string.locked_photo_allow), action(ACTION_ALLOW, 1))
            .setAuthenticationRequired(true).build()
        val deny = NotificationCompat.Action.Builder(0, app.getString(R.string.locked_photo_deny), action(ACTION_DENY, 2)).build()
        val n = NotificationCompat.Builder(app, SecurityAlerts.CHANNEL).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(app.getString(R.string.link_public_ask_title, peer)).setContentText(what)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setDeleteIntent(action(ACTION_DENY, 3)).setTimeoutAfter(WAIT_MS + 1_000)
            .addAction(deny).addAction(allow)
        runCatching { app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n.build()) }
    }
}

/** Allow and Deny from the device-in-the-wild notification. Not exported: only Bridgit's own notification sends these. */
class PublicLinkApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(PublicLinkApproval.EXTRA_REQUEST) ?: return
        when (intent.action) {
            PublicLinkApproval.ACTION_ALLOW -> PublicLinkApproval.answer(id, true)
            PublicLinkApproval.ACTION_DENY -> PublicLinkApproval.answer(id, false)
        }
    }
}
