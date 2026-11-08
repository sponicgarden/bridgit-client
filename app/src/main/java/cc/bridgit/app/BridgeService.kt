package cc.bridgit.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.capability.CameraCapture
import cc.bridgit.app.capability.MicCapture
import cc.bridgit.app.capability.granted
import cc.bridgit.app.core.Net
import cc.bridgit.app.ui.MainActivity
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Bridgit's always-on listener (ARC-02): one foreground service that runs the
 * HTTP listener and owns camera and microphone access. Camera and microphone
 * types can only be added while the app is visible; a start from the background
 * (boot, or Android restarting us) uses specialUse only (ARC-02b, ARC-03).
 */
class BridgeService : LifecycleService() {
    private val app get() = application as BridgeApp
    val cameraReady get() = app.listener.value.cameraReady
    val micReady get() = app.listener.value.micReady
    val locationReady get() = !app.listener.value.startedInBackground
    val bindAddress get() = app.listener.value.boundTo.firstOrNull()
    private var platformTick: Job? = null
    val relay by lazy { cc.bridgit.app.relay.RelayClient(this) }
    private var relayJob: Job? = null
    private var server: ApiServer? = null
    private var boundTo: List<String> = emptyList()
    private val reconnect = cc.bridgit.app.core.TailscaleReconnect()
    private var watcher: Job? = null
    private val recheck = Channel<String>(Channel.CONFLATED)
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var captureNote: String? = null
    val camera by lazy { CameraCapture(this, this) }
    val mic by lazy { MicCapture(this) }
    private val battery by lazy { cc.bridgit.app.power.BatteryWatcher(app) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PAUSE -> {
                app.settings.listenerEnabled = false
                if (!intent.getBooleanExtra(EXTRA_SILENT, false))
                    app.log.system("audit", "setting", "You paused the listener from the notification")
                stopEverything(); return START_NOT_STICKY
            }
            ACTION_WAKE_STOP -> {
                app.settings.setOn(cc.bridgit.app.listen.WakeListener.SWITCH, false)
                app.log.system("audit", "setting", "You turned wake words off from the notification")
                return START_STICKY
            }
            ACTION_WAKE_MUTE -> { app.wake.mute(); app.wake.sync(); refreshNotification(); return START_STICKY }
        }
        if (!app.settings.listenerEnabled) { stopEverything(); return START_NOT_STICKY }
        // A null intent means Android restarted us in the background (START_STICKY).
        val background = intent == null || intent.getBooleanExtra(EXTRA_BACKGROUND, false)
        // A background start (boot, update, restart) never downgrades a service that is
        // already running with camera/mic types from a visible start.
        if (background && watcher != null) return START_STICKY
        if (!promote(background)) return START_NOT_STICKY
        if (watcher == null) {
            app.updateListener { it.copy(running = true, startedAt = System.currentTimeMillis(), port = app.settings.port) }
            app.log.system("diagnostic", "service", if (background) "Listener started in the background (camera and mic need the app opened once)" else "Listener started")
            watcher = lifecycleScope.launch(Dispatchers.IO) { watchTailscale() }
            registerNetworkCallback()
        }
        if(platformTick==null) platformTick=lifecycleScope.launch(Dispatchers.IO) { while(isActive) { runCatching { app.platform.workflows.tick(); app.platform.delivery.tick() }; delay(1000) } }
        if(relayJob==null) relayJob=lifecycleScope.launch(Dispatchers.IO) { relay.run() }
        battery.start()
        running = this
        // An update or restart closes the service; monitoring that was on comes back with it.
        app.dog.resumeIfWanted(if (background) "Bridgit restarted" else "Bridgit opened")
        return START_STICKY
    }

    /**
     * Enters the foreground. From a visible start: every type whose permission is held.
     * From a background start: specialUse only, since Android refuses camera/mic there.
     * specialUse exists from Android 14 and the camera/mic types from 11; earlier versions start with fewer bits.
     */
    private fun promote(background: Boolean): Boolean {
        val wantCamera = !background && granted(Manifest.permission.CAMERA)
        val wantMic = !background && granted(Manifest.permission.RECORD_AUDIO)
        val base = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        var types = base
        if (wantCamera && Build.VERSION.SDK_INT >= 30) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (wantMic && Build.VERSION.SDK_INT >= 30) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        // Location (8.8, City tour) needs the type while in use; playback needs mediaPlayback (SPK-03).
        if (!background && (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)))
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (!background) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (types != base) {
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), types)
                app.updateListener { it.copy(cameraReady = wantCamera, micReady = wantMic, startedInBackground = false) }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "camera/mic foreground start refused, falling back", e)
                app.log.system("diagnostic", "service", "Android refused camera/mic for the listener: ${e.javaClass.simpleName}", level = "warn")
            }
        }
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), base)
            app.updateListener { it.copy(cameraReady = false, micReady = false, startedInBackground = true) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "foreground start refused", e)
            app.log.system("diagnostic", "service", "Android refused to start the listener: ${e.javaClass.simpleName}", level = "error")
            stopSelf()
            false
        }
    }

    /**
     * Binds to the Tailscale addresses and rebinds when they change (ARC-04, ARC-04a).
     * Wakes on network callbacks; a safety re-check runs every 5 minutes.
     */
    private suspend fun watchTailscale() {
        recheck.trySend("start")
        while (lifecycleScope.isActive) {
            // While off the tailnet, wake for the next reconnect try instead of waiting the full 5 minutes.
            val wait = if (boundTo.isEmpty() && reconnect.attempts > 0) reconnect.waitMs(System.currentTimeMillis()).coerceIn(1_000L, RECHECK_MS) else RECHECK_MS
            val why = withTimeoutOrNull(wait) { recheck.receive() } ?: "periodic"
            server?.maintenance()
            lifecycleScope.launch(Dispatchers.IO) { runCatching { app.usage.flushIfDue() }; runCatching { cc.bridgit.app.satellite.SatelliteHub.refreshAllIfDue(app) }; runCatching { app.hubLinks.checkHubsIfDue() }; runCatching { app.links.checkIfDue() }; runCatching { cc.bridgit.app.feedback.DeviceNames.heartbeatIfDue(app) } }
            if (why != "start" && why != "periodic") delay(1500) // let the VPN settle after a change
            val addrs = tailscaleAddresses()
            val port = app.settings.port
            if (addrs != boundTo || (server != null && (server!!.port != port || !server!!.running))) {
                server?.stop(); server = null; boundTo = emptyList()
                if (addrs.isNotEmpty()) {
                    try {
                        ApiServer(this, addrs, port).also { it.start(); server = it }
                        boundTo = addrs
                        val name = reverseName(addrs.first { !Net.isIpv6(it) || addrs.size == 1 })
                        app.updateListener { it.copy(boundTo = addrs, port = port, magicDns = name, bindError = null) }
                        app.log.system("diagnostic", "service", "Listening on ${addrs.joinToString()} port $port ($why)")
                    } catch (e: Exception) {
                        Log.e(TAG, "bind failed", e)
                        app.updateListener { it.copy(boundTo = emptyList(), bindError = "${e.javaClass.simpleName}: ${e.message}") }
                        app.log.system("diagnostic", "service", "Could not listen on port $port: ${e.message}", level = "error")
                    }
                } else {
                    app.updateListener { it.copy(boundTo = emptyList(), magicDns = null) }
                    app.log.system("diagnostic", "service", "Tailscale address gone; listener down ($why)", level = "warn")
                }
                refreshNotification()
            }
            if (addrs.isNotEmpty()) reconnect.reset() else askTailscaleToReconnect()
        }
    }

    /** Tailscale is installed but its VPN is down (e.g. stopped by a Play update, A0091): ask it to connect, with backoff. */
    private fun askTailscaleToReconnect() {
        if (!cc.bridgit.app.platform.TailscaleStatus.installed(this)) return
        if (!reconnect.due(System.currentTimeMillis())) return
        val sent = cc.bridgit.app.platform.TailscaleStatus.connect(this)
        app.log.system("diagnostic", "service", "No Tailscale address; asked Tailscale to connect (try ${reconnect.attempts}${if (sent) "" else ", send failed"})", level = "warn")
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { recheck.trySend("vpn available") }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { recheck.trySend("vpn changed") }
            override fun onLost(network: Network) { recheck.trySend("vpn lost") }
        }
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        runCatching { cm.registerNetworkCallback(req, cb); netCallback = cb }
            .onFailure { app.log.system("diagnostic", "service", "Network callback unavailable: ${it.message}", level = "warn") }
    }

    /** Asks for a re-check now (the port changed, or the UI wants a fresh state). */
    fun poke(reason: String) { recheck.trySend(reason) }

    private val activeCaptures = LinkedHashMap<Long, String>()
    private var nextCapture = 0L

    /** A capture began; the notification names it until [captureEnded]. Camera and mic can overlap. */
    @Synchronized
    fun captureStarted(note: String): Long {
        val t = ++nextCapture
        activeCaptures[t] = note
        showCaptures()
        return t
    }

    @Synchronized
    fun captureEnded(token: Long) {
        activeCaptures.remove(token)
        showCaptures()
    }

    private fun showCaptures() {
        val note = activeCaptures.values.lastOrNull()
        captureNote = note
        app.updateListener { it.copy(capturing = note) }
        refreshNotification()
    }

    fun refreshNotification() {
        if (running !== this) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE)
        val s = app.listener.value
        // 8.23: while wake words have the mic, the notification says so and offers Stop and Mute.
        val wake = app.wake.state.value.takeIf { captureNote == null && it !is cc.bridgit.app.listen.WakeListener.State.Off }
        val title = when {
            captureNote != null -> captureNote!!
            wake is cc.bridgit.app.listen.WakeListener.State.Hearing -> getString(R.string.wake_notif_hearing, wake.phrase)
            wake is cc.bridgit.app.listen.WakeListener.State.Paused -> getString(R.string.wake_notif_paused, when (wake.reason) {
                "muted" -> "muted until " + android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(app.wake.mutedUntil))
                "call" -> "on a call"
                else -> "the microphone is recording" })
            wake != null -> getString(when {
                app.wake.running?.words.isNullOrEmpty() -> R.string.wake_notif_title_sounds
                app.wake.running?.sound != null || app.wake.running?.loud == true -> R.string.wake_notif_title_both
                else -> R.string.wake_notif_title })
            relay.store.enabled -> "Bridgit relay: ${relay.status}"
            boundTo.isEmpty() -> getString(R.string.notif_waiting_tailscale)
            else -> getString(R.string.notif_listening)
        }
        // A portal polls every few seconds: "Last request" names the last control agent instead.
        val last = cc.bridgit.app.ui.home.HomeStates.controlAgents(app).maxByOrNull { it.lastUsedAt }?.takeIf { it.lastUsedAt > 0 }
        val text = when {
            captureNote != null -> getString(R.string.notif_capture_sub)
            wake != null -> getString(R.string.wake_notif_text, app.wake.agentNames())
            relay.store.enabled -> "Relay on · ${relay.status} · Pause stops all access"
            boundTo.isEmpty() -> getString(R.string.notif_not_reachable)
            last != null -> getString(R.string.notif_last_request, last.name,
                cc.bridgit.app.ui.common.Fmt.ago(last.lastUsedAt))
            else -> "${s.magicDns ?: boundTo.first()}:${app.settings.port}"
        }
        return NotificationCompat.Builder(this, if (captureNote != null) BridgeApp.CHANNEL_CAPTURE else BridgeApp.CHANNEL_LISTENER)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(getColor(R.color.brand_primary))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(captureNote == null)
            .setContentIntent(open)
            .apply {
                if (wake != null) {
                    addAction(0, getString(R.string.wake_notif_stop), PendingIntent.getService(this@BridgeService, 2,
                        Intent(this@BridgeService, BridgeService::class.java).setAction(ACTION_WAKE_STOP), PendingIntent.FLAG_IMMUTABLE))
                    addAction(0, getString(R.string.wake_notif_mute), PendingIntent.getService(this@BridgeService, 3,
                        Intent(this@BridgeService, BridgeService::class.java).setAction(ACTION_WAKE_MUTE), PendingIntent.FLAG_IMMUTABLE))
                    addAction(0, getString(R.string.action_pause), pause)
                } else {
                    addAction(0, getString(R.string.action_pause), pause)
                    addAction(0, getString(R.string.action_open), open)
                }
            }
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun stopEverything() {
        battery.stop()
        app.registry.cameraArm.releaseAll(this)
        app.registry.pose.stopAll()
        relayJob?.cancel(); relayJob=null
        app.platform.stopAll(); platformTick?.cancel(); platformTick=null
        app.hardware.stop()
        watcher?.cancel(); watcher = null
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        netCallback = null
        server?.stop(); server = null; boundTo = emptyList()
        app.updateListener { BridgeApp.ListenerState() }
        if (running === this) running = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        app.dog.stop(keepWanted = true) // Release monitoring's CPU wake lock when its foreground host goes away; resume on the next start.
        battery.stop()
        app.registry.cameraArm.releaseAll(this)
        app.registry.pose.stopAll()
        relayJob?.cancel(); relayJob=null
        app.platform.interrupt(); platformTick?.cancel(); platformTick=null
        app.hardware.stop()
        server?.stop(); server = null
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        app.agents.flush()
        if (running === this) running = null
        app.updateListener { it.copy(running = false, boundTo = emptyList()) }
        super.onDestroy()
    }

    private fun reverseName(ip: String): String? = runCatching {
        InetAddress.getByName(ip).canonicalHostName.trimEnd('.').takeIf { it != ip && it.endsWith(".ts.net") }
    }.getOrNull()

    companion object {
        private const val TAG = "BridgeService"
        const val NOTIF_ID = 1
        const val ACTION_PAUSE = "cc.bridgit.app.PAUSE"
        const val ACTION_WAKE_STOP = "cc.bridgit.app.WAKE_STOP"
        const val ACTION_WAKE_MUTE = "cc.bridgit.app.WAKE_MUTE"
        const val ACTION_RELAY_WAKE = "cc.bridgit.app.RELAY_WAKE"
        const val EXTRA_BACKGROUND = "background"
        const val EXTRA_SILENT = "silent"
        private const val RECHECK_MS = 5 * 60 * 1000L

        @Volatile var running: BridgeService? = null; private set

        /** Start from a visible screen: camera and mic types are added if granted. */
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, BridgeService::class.java))

        /** Start from the background (boot): specialUse only (ARC-02b). */
        fun startInBackground(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, BridgeService::class.java).putExtra(EXTRA_BACKGROUND, true))

        fun stop(ctx: Context) = pause(ctx)
        fun tailscaleAddress(): String? = tailscaleAddresses().firstOrNull()
        fun pause(ctx: Context) = ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_PAUSE))

        /** Stops the listener without the "paused from the notification" log line; the caller logs its own reason. */
        fun pauseSilently(ctx: Context) =
            ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_SILENT, true))

        /** The phone's Tailscale addresses: IPv4 in 100.64.0.0/10 and, if present, IPv6 in fd7a:115c:a1e0::/48. */
        fun tailscaleAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp }
                .flatMap { it.inetAddresses.toList() }
                .map { it.hostAddress!!.substringBefore('%') }
                .filter { Net.isTailnet(it) }
                .sortedBy { if (Net.isIpv6(it)) 1 else 0 }
        }.getOrDefault(emptyList())
    }
}

/**
 * ARC-02b: after a reboot, start the listener with specialUse only. Android sends
 * BOOT_COMPLETED only after the first unlock, so Bridgit comes back at the first
 * unlock, not before.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as BridgeApp
        // Bridgit updated itself without Android's confirm: open it again (Android doesn't).
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) cc.bridgit.app.update.UpdateInstaller.reopenAfterSessionInstall(app)
        // ADB setup (8.27): Wireless debugging turns off on reboot; with the secure-settings grant Bridgit turns it back on.
        if (BuildConfig.PRIVATE_CONTROL && app.settings.isOn("adb") && cc.bridgit.app.adb.AdbGrants.holdsSecureSettings(app))
            runCatching { android.provider.Settings.Global.putInt(app.contentResolver, cc.bridgit.app.adb.AdbClient.ADB_WIFI_ENABLED, 1) }
        if (!app.settings.listenerEnabled || app.agents.count == 0) return
        app.log.system("diagnostic", "service", "Starting after ${if (intent.action == Intent.ACTION_BOOT_COMPLETED) "reboot" else "update"}")
        runCatching { BridgeService.startInBackground(context) }
            .onFailure { app.log.system("diagnostic", "service", "Boot start refused: ${it.javaClass.simpleName}", level = "error") }
    }
}
