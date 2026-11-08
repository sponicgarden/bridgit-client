package cc.bridgit.app.capability

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.security.SecurityAlerts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Owner decision, 7 Nov 2026 (report A0049): an agent's camera photo works while the phone is locked. With
 * captures.locked_camera_approval on (off by default), each such photo first waits up to [WAIT_MS] for the owner's
 * Allow on a lock-screen notification; Deny or no answer returns approval_declined and no photo is taken.
 * With the fingerprint setting (security.biometric) on, Allow also needs the owner to unlock.
 */
object LockedCameraApproval {
    const val ACTION_ALLOW = "cc.bridgit.app.camera.LOCKED_ALLOW"
    const val ACTION_DENY = "cc.bridgit.app.camera.LOCKED_DENY"
    const val EXTRA_REQUEST = "request"
    const val WAIT_MS = 30_000L
    private const val NOTIFICATION_ID = 0x434C // "CL"

    private class Pending(val id: String, val answer: CompletableDeferred<Boolean>)
    private val pending = AtomicReference<Pending?>(null)

    /** A photo waits for the owner only when the phone is locked and the owner asked for approval. */
    fun needed(locked: Boolean, approvalOn: Boolean) = locked && approvalOn

    fun locked(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    /**
     * Call before taking the camera. Returns whether this photo may be taken while the phone is locked: true with
     * approval off, or once the owner tapped Allow; false when the phone is unlocked and approval is on, so the camera
     * keeps its own lock check (a phone that locks while the photo waits for the camera is refused, never photographed
     * unasked). Throws approval_declined (owner said no or didn't answer) or sensor_busy (another photo is already
     * waiting for the owner).
     */
    suspend fun require(app: BridgeApp, agentName: String, waitMs: Long = WAIT_MS): Boolean {
        if (!app.settings.capturesLockedCameraApproval) return true
        if (!locked(app)) return false
        val p = Pending(UUID.randomUUID().toString(), CompletableDeferred())
        if (!pending.compareAndSet(null, p))
            throw ErrorCatalog.error("sensor_busy", "Another photo is waiting for the owner's approval. Retry in a few seconds.")
        try {
            post(app, p.id, agentName)
            app.log.system("audit", "camera", app.getString(R.string.locked_photo_log_asked, agentName))
            val allowed = withTimeoutOrNull(waitMs) { p.answer.await() }
            app.log.system("audit", "camera", app.getString(when (allowed) {
                true -> R.string.locked_photo_log_allowed
                false -> R.string.locked_photo_log_denied
                null -> R.string.locked_photo_log_no_answer
            }, agentName))
            if (allowed != true) throw ErrorCatalog.error("approval_declined",
                if (allowed == false) "The owner declined this photo." else "The owner didn't answer within ${waitMs / 1000} s.",
                mapOf("reason" to if (allowed == false) "declined" else "no_answer"))
            return true
        } finally {
            pending.compareAndSet(p, null)
            runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        }
    }

    /** The owner's answer to request [id]. False when that request is no longer waiting. */
    fun answer(id: String, allow: Boolean): Boolean {
        val p = pending.get() ?: return false
        return p.id == id && p.answer.complete(allow)
    }

    private fun post(app: BridgeApp, id: String, agentName: String) {
        fun action(action: String, code: Int) = PendingIntent.getBroadcast(app, NOTIFICATION_ID * 4 + code,
            Intent(app, LockedCameraApprovalReceiver::class.java).setAction(action).putExtra(EXTRA_REQUEST, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val allow = NotificationCompat.Action.Builder(0, app.getString(R.string.locked_photo_allow), action(ACTION_ALLOW, 1))
            .setAuthenticationRequired(app.settings.securityBiometric).build()
        val deny = NotificationCompat.Action.Builder(0, app.getString(R.string.locked_photo_deny), action(ACTION_DENY, 2)).build()
        val n = NotificationCompat.Builder(app, SecurityAlerts.CHANNEL).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(app.getString(R.string.locked_photo_title, agentName))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Shown in full on the lock screen: the buttons are the point.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Swiping it away answers Deny. Android 10-11 can't require an unlock for one button, so with the
            // fingerprint setting on there the owner unlocks first, then taps Allow in the shade.
            .setDeleteIntent(action(ACTION_DENY, 3)).setTimeoutAfter(WAIT_MS + 1_000)
            .addAction(deny).addAction(allow)
        runCatching { app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n.build()) }
    }
}

/** Allow and Deny from the locked-phone photo notification. Not exported: only Bridgit's own notification sends these. */
class LockedCameraApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as BridgeApp
        val id = intent.getStringExtra(LockedCameraApproval.EXTRA_REQUEST) ?: return
        val allow = when (intent.action) {
            LockedCameraApproval.ACTION_ALLOW -> {
                // The fingerprint setting may have been turned on after the notification was posted.
                if (app.settings.securityBiometric && LockedCameraApproval.locked(app)) return
                true
            }
            LockedCameraApproval.ACTION_DENY -> false
            else -> return
        }
        LockedCameraApproval.answer(id, allow)
    }
}
