package cc.bridgit.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import cc.bridgit.app.capability.Registry
import cc.bridgit.app.core.AgentRegistry
import cc.bridgit.app.core.PendingKeyVault
import cc.bridgit.app.core.Settings
import cc.bridgit.app.feedback.FeedbackNotifier
import cc.bridgit.app.feedback.FeedbackStore
import cc.bridgit.app.log.LogStore

/** Process-wide state. Everything here lives as long as the process. */
class BridgeApp : Application() {
    lateinit var settings: Settings
    lateinit var agents: AgentRegistry
    lateinit var log: LogStore
    lateinit var registry: Registry
    /** Remote view (bridgit.cc/watch), off unless the owner turns it on. */
    lateinit var remoteView: cc.bridgit.app.remoteview.RemoteView
    lateinit var vault: PendingKeyVault
    lateinit var feedback: FeedbackStore
    lateinit var interactions: cc.bridgit.app.interact.InteractionStore
    lateinit var speaker: cc.bridgit.app.capability.SpeakerQueue
    lateinit var tours: cc.bridgit.app.tour.TourManager
    lateinit var watches: cc.bridgit.app.watch.Watches
    /** Pet monitor applet (owner, 2 Nov 2026). */
    val cameraFraming by lazy { cc.bridgit.app.lancamera.FramingSessions(this) }
    val sshProfiles by lazy { cc.bridgit.app.ssh.SshStore(this) }
    val lanDevices by lazy { cc.bridgit.app.lan.LanStore(this) }
    internal val cameraViews by lazy { cc.bridgit.app.lancamera.CameraViews(this) }
    val lanCameras by lazy { cc.bridgit.app.lancamera.LanCameras(this) }
    /** Loud sounds from enrolled camera microphones, for LoudSounds subscribers (Pet monitor barking). */
    val cameraMics by lazy { cc.bridgit.app.lancamera.CameraMics(this) }
    /** Bridgit Satellites (docs/spec-satellites.md): the satellites this phone uses as a hub, and its own hub links. */
    val satellites by lazy { cc.bridgit.app.satellite.SatelliteStore(this) }
    val hubLinks by lazy { cc.bridgit.app.satellite.HubLinks(this) }
    /** Linked Bridgits (docs/spec-linked-bridgits.md): two-way links with double opt-in, over the two stores above. */
    val links by lazy { cc.bridgit.app.link.Links(this) }
    /** The opt-in on-phone vision engines (any-word detector, outlines); idle until a caller names one. */
    val onDeviceVision by lazy {
        cc.bridgit.app.vision.OnDeviceVision(this, cc.bridgit.app.vision.OnDeviceModels(this, { runCatching { applets.engines() }.getOrDefault(emptySet()) }) { level, msg -> log.system("diagnostic", "vision", msg, level = level) })
    }
    val sensorSearchDelays by lazy {
        val prefs = getSharedPreferences("application_sensor_search_delays", MODE_PRIVATE)
        cc.bridgit.app.appdevices.SensorSearchDelays(
            read = { key -> if (prefs.contains(key)) prefs.getInt(key, 60) else null },
            write = { key, seconds -> check(prefs.edit().apply {
                if (seconds == null) remove(key) else putInt(key, seconds)
            }.commit()) }
        )
    }
    val appDevices by lazy {
        cc.bridgit.app.appdevices.AppDeviceAccess(this, lanCameras.all().map { it.id }).apply {
            onChanged = { revoked ->
                val state = dog.state.value
                if(state.microphones.any { it.startsWith("lan:") && !allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG,it) }) dog.pruneMicrophones()
                if (cc.bridgit.app.appdevices.AppDeviceRules.shouldStopMonitor(state.running,
                    state.cameraMode == cc.bridgit.app.dog.DogCamera.AUTO, state.cameras, revoked) {
                        allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG, it)
                    })
                    dog.stop(getString(R.string.app_device_revoked), "device_access_removed")
            }
        }
    }
    val ownerFeatures by lazy { cc.bridgit.app.ownerapi.OwnerActions(this) }
    val resourceDownloads by lazy { cc.bridgit.app.ownerapi.ResourceDownloads(this) }
    val dog by lazy { cc.bridgit.app.dog.DogMonitor(this) }
    lateinit var callbacks: cc.bridgit.app.core.CallbackStore
    lateinit var callbackDispatcher: cc.bridgit.app.interact.CallbackDispatcher
    lateinit var screenGrants: cc.bridgit.app.planned.ScreenGrants
    lateinit var hardware: cc.bridgit.app.planned.Hardware
    lateinit var transfers: cc.bridgit.app.planned.Transfers
    lateinit var schedules: cc.bridgit.app.schedule.Schedules
    lateinit var clipboardRequests: cc.bridgit.app.planned.ClipboardRequests
    val policy by lazy { Policy(this) }
    val audit by lazy { PlatformAudit(this) }
    val platform by lazy { cc.bridgit.app.platform.AgentPlatform(this) }
    /** Installed applets (one approved package each). */
    val applets by lazy { cc.bridgit.app.applets.AppletInstances(this) }
    /** Applet portals on this screen (docs/spec-portals-and-cards.md). */
    val appletPortals by lazy { cc.bridgit.app.applets.AppletPortals(this) }
    /** The budget sync with the backend: applet budgets, actions, events (docs/plans/budgets-phase1-contract.md). */
    val budgetSync by lazy { cc.bridgit.app.applets.BudgetSync(this) }
    /** Photos the owner takes with the Camera tab or the Portal, checked by the photo watches. */
    val ownerPhotos by lazy { cc.bridgit.app.watch.OwnerPhotos(this) }
    lateinit var alerts: cc.bridgit.app.alerts.Alerts
    lateinit var usage: cc.bridgit.app.core.Usage
    lateinit var wake: cc.bridgit.app.listen.WakeListener
    /** Where captures are kept: memory, phone files, uploads (SEC-13, D33). */
    val captures by lazy { cc.bridgit.app.capture.CaptureStore(this) }
    val pairing = Pairing()
    /** ADB setup (8.27): Bridgit's own client of this device's Wireless debugging. */
    val adb by lazy { cc.bridgit.app.adb.AdbClient(this) }
    val requests = cc.bridgit.app.api.RequestState()

    /** What the listener is doing, for Home, the notification and the Add-agent check. */
    data class ListenerState(
        val running: Boolean = false,
        val boundTo: List<String> = emptyList(),
        /** The phone's MagicDNS name, from a reverse lookup of its Tailscale IP (null if MagicDNS is off). */
        val magicDns: String? = null,
        val port: Int = 0,
        val startedAt: Long = 0,
        val cameraReady: Boolean = false,
        val micReady: Boolean = false,
        val bindError: String? = null,
        val capturing: String? = null,
        /** True when the service last started from the background (boot or restart), so camera/mic are off (ARC-03). */
        val startedInBackground: Boolean = false,
    ) {
        val listening get() = running && boundTo.isNotEmpty()
    }

    private val _listener = MutableStateFlow(ListenerState())
    val listener: StateFlow<ListenerState> get() = _listener
    fun updateListener(f: (ListenerState) -> ListenerState) { _listener.value = f(_listener.value) }

    /** What the phone sends off the device (GET /v1/outbound). Opened first, so nothing sent at startup is missed. */
    lateinit var outbound: cc.bridgit.app.outbound.OutboundStore

    private fun startOutboundLog() {
        outbound = cc.bridgit.app.outbound.OutboundStore(this)
        val c = cc.bridgit.app.outbound.Classifier
        c.teamHosts = setOfNotNull(runCatching { java.net.URI(BuildConfig.FEEDBACK_URL).host }.getOrNull(), "bridgit-feedback.bridgit-app.workers.dev")
        c.updateHosts = setOf(cc.bridgit.app.update.UpdateLogic.HOST)
        val relay = cc.bridgit.app.relay.RelayStore(this)
        c.relayHosts = { setOfNotNull(runCatching { java.net.URI(relay.base).host }.getOrNull()) }
        // Read straight from the prefs: Settings isn't created yet, and startup calls must honour "off" too.
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        cc.bridgit.app.outbound.Outbound.recording = { prefs.getBoolean(cc.bridgit.app.core.Settings.OUTBOUND_LOG_PREF, true) }
        cc.bridgit.app.outbound.Outbound.paused = { prefs.getBoolean(cc.bridgit.app.core.Settings.EXTERNAL_PAUSED_PREF, false) }
        cc.bridgit.app.outbound.Outbound.attach(outbound::append)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        cc.bridgit.app.satellite.LinkPause.attach(this)
        cc.bridgit.app.ui.VersionStamp.install(this) // version in the corner of every screen, for screenshots
        startOutboundLog()
        cc.bridgit.app.core.CrashReports.start(this)
        cc.bridgit.app.relay.RelayPush.init(this)
        settings = Settings(this)
        usage = cc.bridgit.app.core.Usage(this)
        settings.onSwitch = { key, on ->
            usage.count("switch:$key:${if (on) "on" else "off"}")
            if(!on && key in setOf("mic","lan_camera","lan_camera_audio")) dog.revokeMicrophones(key)
        }
        agents = AgentRegistry(settings.agentStorage(this))
        // A Bridgit Portal pairing only views the phone: its first call is not "an agent connected".
        agents.onFirstUse = { a -> if (cc.bridgit.app.ui.home.HomeStates.controlAgents(this).any { it.id == a.id }) cc.bridgit.app.ui.common.AgentConnectionSound.play(this) }
        agents.onKeyRetired = { id -> cc.bridgit.app.feedback.TeamKeys.retire(this, id) } // FBK-15
        log = LogStore(this) { settings.retention() }
        // The optional on-phone software's manifest, once a day; it only fetches while an engine is in use.
        runCatching { cc.bridgit.app.vision.SoftwareCheckWorker.schedule(this) }
        // Automatic app updates (A0050), on unless the owner turned them off.
        if (cc.bridgit.app.update.AutoUpdate.enabled(this)) runCatching { cc.bridgit.app.update.AutoUpdateWorker.schedule(this) }
        alerts = cc.bridgit.app.alerts.Alerts(this)
        log.onAppended = { e -> if (!cc.bridgit.app.remoteview.RemoteViewLines.isAppletDiagnostic(e)) alerts.onLog(e) }
        runCatching { alerts.poke() }
        Thread({ runCatching { cc.bridgit.app.log.DeviceDiagnostics.archiveExits(this, log) } }, "bridgit-exit-archive")
            .apply { isDaemon = true; start() }
        createChannels()
        java.io.File(cacheDir, "uploads").listFiles()?.forEach { it.delete() }
        registry = Registry(this)
        screenGrants = cc.bridgit.app.planned.ScreenGrants(this)
        hardware = cc.bridgit.app.planned.Hardware(this)
        transfers = cc.bridgit.app.planned.Transfers(this)
        clipboardRequests = cc.bridgit.app.planned.ClipboardRequests(this)
        settings.addListener { if (!settings.listenerEnabled || !settings.isOn("notifications")) cc.bridgit.app.planned.NotificationMemory.clear() }
        vault = PendingKeyVault(this)
        feedback = FeedbackStore(this, uploadEnabled = { settings.feedbackUpload })
        interactions = cc.bridgit.app.interact.InteractionStore(this)
        speaker = cc.bridgit.app.capability.SpeakerQueue(this)
        tours = cc.bridgit.app.tour.TourManager(this)
        wake = cc.bridgit.app.listen.WakeListener(this)
        agents.addListener {
            tours.current()?.let { t -> if (agents.get(t.agentId)?.let { it.allows("tour") && it.allows("interactions") && it.allows("location") && it.allows("speaker") } != true) tours.end("Agent access revoked") }
            speaker.recheckAccess()
        }
        callbacks = cc.bridgit.app.core.CallbackStore(this)
        callbackDispatcher = cc.bridgit.app.interact.CallbackDispatcher(this, callbacks)
        interactions.addEventListener { callbackDispatcher.onEvent(it) } // EVT-06
        interactions.recoverInterruptedTours()
        // Watches (POST /v1/watches) resume after a restart; their readings start over.
        watches = cc.bridgit.app.watch.Watches(this)
        watches.renewer = { w -> applets.renews(w.agentId) }
        // An installed applet's watch charges that applet (package id) and names its action (budgets).
        watches.budgetKey = { w -> applets.forAgent(w.agentId)?.let { r ->
            val pkgWatch = r.optJSONObject("watches")?.let { ws -> ws.keys().asSequence().firstOrNull { ws.optJSONObject(it)?.optString("watch_id") == w.id } }
            cc.bridgit.app.applets.AppletBudget.appletOf(r) to (pkgWatch?.let { cc.bridgit.app.applets.AppletActions.actionOfWatch(r.getJSONObject("definition"), it) } ?: cc.bridgit.app.applets.AppletBudget.MAIN_ACTION)
        } }
        watches.start()
        // CAP-57: approved action manifests run by themselves; re-arm after a restart or a switch change.
        schedules = cc.bridgit.app.schedule.Schedules(this)
        schedules.start()
        applets.start()
        runCatching { applets.grantBudgetsOnce() }
        budgetSync.start()
        settings.addListener { schedules.tickSoon() }
        agents.addListener { schedules.onAgentsChanged() }
        // Play aloud (UI-16): the owner's own tap, so silent/DND don't block it; a call still does. Same queue as everything else.
        cc.bridgit.app.ui.interactions.SpeakHook.speak = { _, agent, title, text ->
            if (!speaker.inCall()) speaker.speakText("owner", agent, cc.bridgit.app.interact.NotifRules.utterance(agent, title, text), allowInQuiet = true)
        }
        feedback.onStatusChange { FeedbackNotifier.statusChanged(this, it) }
        // FBK-14: send new issues to the Bridgit team; also picks up anything left from before a restart.
        feedback.onCreated { cc.bridgit.app.feedback.FeedbackUploadWorker.enqueue(this) }
        feedback.onHistoryQueued { cc.bridgit.app.feedback.FeedbackUploadWorker.enqueue(this) } // follow-ups, Confirm, moves
        cc.bridgit.app.feedback.FeedbackUploadWorker.enqueue(this)
        cc.bridgit.app.feedback.FeedbackStatusSyncWorker.schedule(this) // and the team's status changes back
        if (BuildConfig.PRIVATE_CONTROL) runCatching { cc.bridgit.app.adb.SuperPowers.finishIfInterrupted(this) }
        // Notifications and running with the screen off are on by default: put back over ADB if Android dropped them.
        runCatching { cc.bridgit.app.power.DefaultAccess.healOverAdb(this) }
        interactions.addListener { i, what -> cc.bridgit.app.interact.InteractionNotifier.onChange(this, i, what) }
        // AGT-12: a removed agent's open questions and cards can never be answered: cancel them (all removal paths go through the registry).
        var agentIds = agents.all().associate { it.id to it.name }
        fun closeRemoved(gone: Map<String, String>) = gone.forEach { (id, name) ->
            val n = interactions.closeAllFor(id)
            if (n > 0) log.system("audit", "interaction", "Cancelled $n open interaction${if (n == 1) "" else "s"} from $name: the agent was removed")
        }
        agents.addListener {
            val now = agents.all().associate { it.id to it.name }
            val gone = agentIds - now.keys
            agentIds = now
            if (gone.isNotEmpty()) closeRemoved(gone)
            gone.keys.forEach { runCatching { satellites.forgetAgent(it) }; runCatching { links.forgetAgent(it) } }
        }
        closeRemoved((interactions.agentsWithOpen() - agentIds.keys).associateWith { "a removed agent" })
        // UI-23: keep the Quick Settings tile in step when Home, the notification or the tile pauses or resumes.
        var tileListening = settings.listenerEnabled
        settings.addListener {
            val now = settings.listenerEnabled
            if (now != tileListening) { tileListening = now; cc.bridgit.app.ui.home.ListenerTile.requestUpdate(this) }
        }
        agents.addListener { platform.recheckCredentials(); resourceDownloads.stopRevoked() }
        settings.addListener { resourceDownloads.stopRevoked() }
        platform
        cc.bridgit.app.power.PowerAccess(this).reconcileMaximum()
        wake.start()
        agents.addListener { wake.sync() }
        remoteView = cc.bridgit.app.remoteview.RemoteView(this)
        remoteView.start()
        applyAppearance()
        // Existing Bridgit Portal pairings gain new read-only routes without re-pairing.
        runCatching { cc.bridgit.app.relay.RelayStore(this).upgradePortalRoutes() }
    }

    fun speakerReady() = ::speaker.isInitialized

    fun applyAppearance() = AppCompatDelegate.setDefaultNightMode(when (settings.appearance) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    })

    /**
     * UI-21 channels: listener, capture, questions and notices (1.5), feedback.
     * Files and grants channels are added with their features (P4, P5).
     */
    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(INTERACTION_CHANNEL,"Agent control requests",NotificationManager.IMPORTANCE_DEFAULT))
        nm.deleteNotificationChannel("bridge") // 1.2's single channel, if a debug build carried it over
        nm.createNotificationChannel(NotificationChannel(CHANNEL_LISTENER, getString(R.string.channel_listener),
            NotificationManager.IMPORTANCE_LOW).apply { description = getString(R.string.channel_listener_desc) })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_CAPTURE, getString(R.string.channel_capture),
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = getString(R.string.channel_capture_desc); setSound(null, null)
        })
        nm.createNotificationChannel(NotificationChannel(cc.bridgit.app.interact.InteractionNotifier.CHANNEL_QUESTIONS,
            getString(R.string.channel_questions), NotificationManager.IMPORTANCE_HIGH).apply { description = getString(R.string.channel_questions_desc) })
        nm.createNotificationChannel(NotificationChannel(cc.bridgit.app.interact.InteractionNotifier.CHANNEL_NOTICES,
            getString(R.string.channel_notices), NotificationManager.IMPORTANCE_DEFAULT).apply { description = getString(R.string.channel_notices_desc) })
        FeedbackNotifier.createChannel(this)
        cc.bridgit.app.security.SecurityAlerts.createChannel(this)
        cc.bridgit.app.link.LinkNotifier.createChannel(this)
        cc.bridgit.app.update.UpdateInstaller.cleanStale(this) // UPD-05: downloads older than an hour go
    }

    /** PRD-05: captures need Bridgit's notifications on, including the capture channel. */
    fun captureNotificationsOn(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        return (nm.getNotificationChannel(CHANNEL_CAPTURE)?.importance ?: 0) != NotificationManager.IMPORTANCE_NONE
    }

    companion object {
        const val CAPTURE_CHANNEL = "capture"
        const val INTERACTION_CHANNEL = "platform_interactions"
        const val CHANNEL_LISTENER = "listener"
        const val CHANNEL_CAPTURE = "capture"
        lateinit var instance: BridgeApp; private set
    }
}

/**
 * Pair with code (AGT-13): a 6-digit code for 5 minutes. POST /v1/pair with it
 * returns the agent's key once. Five wrong codes close the window.
 */
class Pairing {
    class Window(val agentId: String, val agentName: String, val code: String, val key: String,
                 val expiresAt: Long, var wrongAttempts: Int = 0) {
        @Volatile var redeemed: Boolean = false
    }

    @Volatile var window: Window? = null
        private set

    fun open(agentId: String, agentName: String, key: String): Window {
        val code = (java.security.SecureRandom().nextInt(900000) + 100000).toString()
        return Window(agentId, agentName, code, key, System.currentTimeMillis() + WINDOW_MS).also { window = it }
    }

    fun close() { window = null }

    @Synchronized
    fun redeem(code: String?): Window? {
        val w = window ?: return null
        if (System.currentTimeMillis() > w.expiresAt) { window = null; return null }
        if (code != null && java.security.MessageDigest.isEqual(code.toByteArray(), w.code.toByteArray())) {
            window = null
            w.redeemed = true
            return w
        }
        w.wrongAttempts++
        if (w.wrongAttempts >= 5) window = null
        return null
    }

    fun isOpen(): Boolean = window?.let { System.currentTimeMillis() <= it.expiresAt } ?: false

    companion object { const val WINDOW_MS = 5 * 60 * 1000L }
}
