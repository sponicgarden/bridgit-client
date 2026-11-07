package cc.bridgit.app.relay

import android.content.Context
import android.content.Intent
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.BuildConfig
import kotlin.coroutines.resume

/**
 * Relay wake-ups over FCM. Only configured builds (BuildConfig.FCM_*) have it; without Play
 * Services or config, [token] is null and the relay works as before, socket only.
 * Google sees the push token and that a wake-up was sent, never job content or the agent.
 */
object RelayPush {
    @Volatile private var ready = false

    fun init(ctx: Context) {
        if (BuildConfig.FCM_APP_ID.isEmpty() || BuildConfig.FCM_PROJECT_ID.isEmpty()) return
        ready = runCatching {
            if (FirebaseApp.getApps(ctx).isEmpty()) FirebaseApp.initializeApp(ctx, FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FCM_APP_ID).setProjectId(BuildConfig.FCM_PROJECT_ID)
                .setApiKey(BuildConfig.FCM_API_KEY).setGcmSenderId(BuildConfig.FCM_SENDER_ID).build())
            true
        }.getOrDefault(false)
        // Firebase registers this install with Google itself; record that it happens (no reply is visible to Bridgit).
        if (ready) cc.bridgit.app.outbound.Outbound.record("https://fcmregistrations.googleapis.com/", "POST", "firebase-sdk",
            status = cc.bridgit.app.outbound.OutboundEntry.HANDED_OFF, startedAt = System.currentTimeMillis(), durationMs = 0,
            hint = cc.bridgit.app.outbound.Outbound.Hint("Firebase Cloud Messaging (Google)", "Register for push wake-ups from the relay (Firebase SDK)",
                listOf(cc.bridgit.app.outbound.Categories.PUSH_TOKEN, cc.bridgit.app.outbound.Categories.INSTALL_ID, cc.bridgit.app.outbound.Categories.DEVICE_INFO, cc.bridgit.app.outbound.Categories.APP_VERSION)))
    }

    suspend fun token(): String? {
        if (!ready) return null
        // Bounded: the first fetch can be slow, and the relay heartbeat waits on it.
        return runCatching { withTimeoutOrNull(5000) { suspendCancellableCoroutine { c ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resume(null) }
        } } }.getOrNull()
    }
}

class RelayPushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "relay_wake") return
        val app = application as BridgeApp
        if (!app.settings.listenerEnabled || !RelayStore(app).enabled) return
        // Already running: wake the socket directly. startForegroundService here would oblige the
        // service to call startForeground again, and the app would crash if it didn't.
        BridgeService.running?.let { it.relay.wake(); return }
        // A high-priority FCM message lets a background app start its foreground service.
        runCatching { startForegroundService(Intent(this, BridgeService::class.java)
            .setAction(BridgeService.ACTION_RELAY_WAKE).putExtra(BridgeService.EXTRA_BACKGROUND, true)) }
    }

    override fun onNewToken(token: String) { BridgeService.running?.relay?.wake() }
}
