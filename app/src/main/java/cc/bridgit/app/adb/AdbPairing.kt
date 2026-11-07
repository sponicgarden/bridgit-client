package cc.bridgit.app.adb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R

/**
 * Pairing (8.27). Settings closes its pairing dialog when another app comes to the front, so the code is typed
 * into a notification (inline reply) while the dialog stays open. The dialog's port is found by mDNS
 * (_adb-tls-pairing._tcp), the same way Android Studio finds it.
 */
object AdbPairing {
    private const val CHANNEL = "adb_setup"
    private const val NOTIFICATION_ID = 8030
    private const val KEY_CODE = "code"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mdns: AdbMdns? = null
    @Volatile var pairingPort: Int = -1; private set

    /** What the wizard shows under the Pair row. */
    val state: StateFlow<String?> get() = _state
    private val _state = MutableStateFlow<String?>(null)

    /**
     * Connects if Bridgit already can (paired before, or an allowed `adb tcpip` port); otherwise starts looking for
     * the pairing dialog, posts the code notification and calls [needsPairing] so the caller can open Wireless debugging.
     */
    fun connectOrPair(ctx: Context, needsPairing: () -> Unit) {
        val app = ctx.applicationContext as BridgeApp
        // Never paired: go straight to pairing rather than make the owner wait for a connection that can't work.
        if (!app.adb.paired) { start(app); needsPairing(); return }
        _state.value = "Connecting…"
        scope.launch {
            val connected = runCatching { app.adb.connect() }.isSuccess
            if (connected) _state.value = "Connected. Bridgit can use ADB."
            else kotlinx.coroutines.withContext(Dispatchers.Main) { start(app); needsPairing() }
        }
    }

    /** Starts looking for the pairing dialog and posts the code notification. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext as BridgeApp
        if (mdns?.isRunning != true) {
            pairingPort = -1
            mdns = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, port -> if (port > 0) pairingPort = port }.also { it.start() }
        }
        _state.value = "Waiting for the pairing code"
        notify(app, "Enter the pairing code here",
            "In Wireless debugging, tap Pair device with pairing code. Then pull down the notifications, open this one with its arrow, tap Enter code, type the 6 digits and tap Send.", reply = true)
    }

    fun stop() { runCatching { mdns?.stop() }; mdns = null }

    /**
     * Pairs with [code]; used by the notification reply and by the wizard's own field (split screen).
     * The field also takes the port shown in the dialog ("482913 41235" or "482913 192.168.1.5:41235"),
     * for networks where mDNS doesn't find the dialog.
     */
    fun submit(ctx: Context, code: String, done: () -> Unit = {}) {
        val app = ctx.applicationContext as BridgeApp
        scope.launch {
            val parts = code.trim().split(Regex("[\\s,]+")).filter { it.isNotBlank() }
            val clean = parts.firstOrNull().orEmpty().filter(Char::isDigit)
            parts.getOrNull(1)?.substringAfterLast(':')?.toIntOrNull()?.takeIf { it in 1..65535 }?.let { pairingPort = it }
            try {
                if (clean.length != 6) error("The pairing code has 6 digits.")
                var waited = 0
                while (pairingPort <= 0 && waited < 10_000) { Thread.sleep(250); waited += 250 }
                if (pairingPort <= 0) error("Bridgit can't see the pairing dialog. Open Wireless debugging, tap Pair device with pairing code, and keep it open.")
                _state.value = "Pairing…"
                app.adb.pair(pairingPort, clean)
                app.adb.connect()
                stop()
                _state.value = "Paired. Bridgit can use ADB."
                notify(app, "Bridgit is paired", "Open ADB setup in Bridgit to give it access.", reply = false)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                app.log.system("diagnostic", "setting", "ADB pairing failed: $msg", level = "warn")
                _state.value = "Pairing didn't work: $msg"
                notify(app, "Pairing didn't work", "$msg Try a new code.", reply = true)
            }
            done()
        }
    }

    private fun notify(app: BridgeApp, title: String, text: String, reply: Boolean) {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "ADB setup", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(app, 0, app.packageManager.getLaunchIntentForPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_tile_bridgit)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setOnlyAlertOnce(true).setAutoCancel(!reply)
        if (reply) {
            val pi = PendingIntent.getBroadcast(app, 1, Intent(app, AdbPairReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            b.addAction(NotificationCompat.Action.Builder(0, "Enter code", pi)
                .addRemoteInput(RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()).build())
        }
        runCatching { nm.notify(NOTIFICATION_ID, b.build()) }
    }

    internal fun codeFrom(intent: Intent): String? = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()
}

/** Receives the code typed into the pairing notification. */
class AdbPairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = AdbPairing.codeFrom(intent) ?: return
        // Pairing and connecting can take longer than a receiver may block; the listener service keeps the process up.
        AdbPairing.submit(context, code)
    }
}
