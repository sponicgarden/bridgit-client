package cc.bridgit.app.portal

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.relay.RelaySetup
import cc.bridgit.app.relay.RelayStore
import org.json.JSONObject

/**
 * Bridgit Portal, Google-approve pairing: an iPhone/iPad signed in to the Google account this phone is linked to
 * (remote view) asks to view it through the Bridgit backend. The phone shows a notification; the owner allows or
 * denies on the phone. Allow pairs exactly like the QR code (a new read-only portal agent) and hands the pairing to
 * the backend, which gives it to that iPhone once. Nothing is paired without the owner's tap.
 */
object PortalApprovals {
    data class Request(val id: String, val email: String, val deviceName: String, val expiresAt: Long)

    const val EXTRA_REQUEST = "portal_request"
    const val EXTRA_EMAIL = "portal_email"
    const val EXTRA_DEVICE = "portal_device"
    const val EXTRA_EXPIRES = "portal_expires"
    private const val PREFS = "portal_approvals"
    private const val NOTIFICATION_BASE = 0x5047 // "PG"

    /** Requests from the backend's answer; anything malformed is skipped. */
    fun parse(json: org.json.JSONArray): List<Request> = (0 until json.length()).mapNotNull { i ->
        val o = json.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("request").takeIf { Regex("ptr_[a-f0-9]{32}").matches(it) } ?: return@mapNotNull null
        Request(id, o.optString("email").take(120).ifEmpty { "your Google account" }, o.optString("device_name").take(60).ifEmpty { "an iPhone or iPad" },
            o.optLong("expires_at"))
    }

    /** Blocking (network). Notifies the owner once about each new request. */
    fun check(app: BridgeApp) {
        val pending = parse(app.remoteView.portalPending())
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seen", emptySet()) ?: emptySet()
        pending.filter { it.id !in seen }.forEach { notify(app, it) }
        // Remember only what is still pending, so the set stays small.
        prefs.edit().putStringSet("seen", pending.map { it.id }.toSet()).apply()
    }

    fun intent(ctx: Context, r: Request): Intent = Intent(ctx, PortalActivity::class.java)
        .putExtra(EXTRA_REQUEST, r.id).putExtra(EXTRA_EMAIL, r.email).putExtra(EXTRA_DEVICE, r.deviceName).putExtra(EXTRA_EXPIRES, r.expiresAt)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun notify(app: BridgeApp, r: Request) {
        val pi = PendingIntent.getActivity(app, r.id.hashCode(), intent(app, r), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = app.getString(R.string.portal_request_title)
        val text = app.getString(R.string.portal_request_text, r.email, r.deviceName)
        app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_BASE + (r.id.hashCode() and 0xfff),
            NotificationCompat.Builder(app, cc.bridgit.app.interact.InteractionNotifier.CHANNEL_QUESTIONS)
                .setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true)
                .setTimeoutAfter((r.expiresAt - System.currentTimeMillis()).coerceIn(60_000L, 10 * 60_000L))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }

    fun cancelNotification(app: BridgeApp, id: String) =
        app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_BASE + (id.hashCode() and 0xfff))

    /**
     * Blocking. Allow: turn the relay on, pair a new portal agent and hand the pairing to the backend. If the backend
     * refuses (expired, answered elsewhere), the new agent is removed again so nothing is left paired.
     */
    fun allow(app: BridgeApp, r: Request): PortalPairing.Pairing {
        val store = RelayStore(app)
        RelaySetup.turnOn(app, store)
        val p = PortalPairing.create(app, store, singleUse = false)
        cc.bridgit.app.BridgeService.running?.relay?.wake()
        try { app.remoteView.portalAnswer(r.id, true, pairingJson(p.link)) }
        catch (e: Exception) { PortalPairing.disconnect(app, store, p.agent.id); throw e }
        return p
    }

    fun deny(app: BridgeApp, r: Request) = app.remoteView.portalAnswer(r.id, false, null)

    /** The pairing link's fields, as the backend hands them to the iPhone. */
    fun pairingJson(link: String): JSONObject {
        val f = PortalData.parsePairLink(link) ?: error("Not a pairing link")
        return JSONObject().put("relay", f["relay"]).put("device", f["device"]).put("token", f["token"]).put("name", f["name"])
            .apply { f["install"]?.let { put("install", it) } }.put("portal", f["portal"] == "1")
    }
}
