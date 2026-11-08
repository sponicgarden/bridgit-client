package cc.bridgit.app.ui

import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.databinding.ActivityMainBinding
import cc.bridgit.app.interact.IKind
import cc.bridgit.app.interact.IStatus
import cc.bridgit.app.interact.Interaction
import cc.bridgit.app.interact.InteractionNotifier
import cc.bridgit.app.interact.MainActivityState
import cc.bridgit.app.ui.interactions.InteractionUi
import cc.bridgit.app.ui.interactions.TextPanel
import cc.bridgit.app.update.UpdateDialog

/**
 * The single Activity (ARC-06). Each screen is a Fragment in nav_main.xml.
 * Top-level tabs show the bottom bar; full-screen flows (Add agent, first run) hide it.
 */
class MainActivity : AppCompatActivity() {
    /** The device banner; Home sets its freshness line. */
    var hubBar: HubBar? = null; private set
    private lateinit var b: ActivityMainBinding
    private val app get() = application as BridgeApp
    lateinit var nav: NavController; private set
    private lateinit var panel: TextPanel
    /** D26: the text panel the owner picked (list or notification); null = the newest open one, if any. */
    private var panelChosen: Long? = null
    private val panelSeen = HashSet<Long>()
    private var navInset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val launchBranding = savedInstanceState == null && intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener {
                it.remove()
                // Start the readable interval only once Android's covering window is gone.
                if (launchBranding) showLaunchBranding()
            }
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        nav = (supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment).navController
        b.bottomNav.setupWithNavController(nav)
        // UI-07: icons only at large font sizes, so the bar stays usable at 200 %.
        if (resources.configuration.fontScale >= 1.5f)
            b.bottomNav.labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_UNLABELED
        panel = TextPanel(b.textPanel, ::closePanel)
        hubBar = HubBar(app, b.root, b.navHost).also { it.install(this) }
        nav.addOnDestinationChangedListener { _, dest, args ->
            MainActivityState.destination = dest.id
            MainActivityState.cardId = if(dest.id==R.id.interactionDetail) args?.getLong("interactionId") else null
            runCatching { app.usage.count("screen:" + resources.getResourceEntryName(dest.id)) }
            hubBar?.onDestination(dest.id in HUB_TABS, dest.id !in NO_DEVICE_BAR, home = dest.id == R.id.home)
            b.bottomNav.isVisible = dest.id in TOP_LEVEL
            applyPanelInset()
            lifecycleScope.launch { refreshPanel() }
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.bottomNav) { v, insets ->
            navInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = navInset); applyPanelInset(); insets
        }
        if (savedInstanceState == null) { route(); openReportFrom(intent); val arrival = wakeFor(intent); openInteractionFrom(intent, arrival); openTourFrom(intent); openDogFrom(intent); openOwnerSetupFrom(intent); openAgentFrom(intent); openPortalFrom(intent); openForegroundScreen(intent) }
        watchInteractionsBadge()
        watchTextPanel()
        if (launchBranding && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) showLaunchBranding()
    }

    private fun showLaunchBranding() {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val branding = layoutInflater.inflate(R.layout.launch_splash, content, false)
        content.addView(branding)
        branding.doOnLayout {
            val density = resources.displayMetrics.density
            val gap = 24 * density
            val wordmarkRatio = 927f / 3004f
            val width = minOf(it.width * .72f, 240 * density,
                (it.height * .72f - gap) / (1 + wordmarkRatio)).toInt().coerceAtLeast(1)
            branding.findViewById<View>(R.id.splash_stack).updateLayoutParams { this.width = width }
            branding.findViewById<View>(R.id.splash_logo).updateLayoutParams { height = width }
            branding.findViewById<View>(R.id.splash_wordmark).updateLayoutParams { height = (width * wordmarkRatio).toInt() }
        }
        // Count from the first rendered frame; initialization continues underneath.
        branding.doOnPreDraw {
            lifecycleScope.launch {
                delay(1200)
                content.removeView(branding)
            }
        }
    }

    /**
     * D26: keeps the live text panel in step with the store. Throttled (render, then wait 300 ms) rather
     * than debounced, so a fast stream still shows about three updates a second.
     */
    private fun watchTextPanel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.interactions.changed.collect {
                    refreshPanel()
                    delay(300)
                }
            }
        }
    }

    private suspend fun refreshPanel() {
        if (!::panel.isInitialized) return
        // Not over first run or an unfinished setup prompt.
        if (nav.currentDestination?.id in SETUP) { panel.show(null); MainActivityState.textPanelId = null; return }
        val chosen = panelChosen
        val store = app.interactions
        val i: Interaction? = withContext(Dispatchers.IO) {
            runCatching { (chosen?.let { store.get(it) } ?: store.latestOpenText())?.takeIf { it.kind == IKind.TEXT } }.getOrNull()
        }
        if (i == null && chosen != null) panelChosen = null
        panel.show(i)
        MainActivityState.textPanelId = panel.visibleId
        // Also clear an explicit reminder for a text panel that was already seen.
        if (i != null) InteractionNotifier.cancel(this, i.id)
        // Showing it is seeing it: the agent gets interaction.seen and the notification goes.
        if (i != null && i.status == IStatus.PENDING && panelSeen.add(i.id))
            withContext(Dispatchers.IO) { runCatching { store.markSeen(i.id) } }
    }

    /** Opens (or reopens) the live text panel for interaction [id] (Interactions list, notification). */
    fun showTextPanel(id: Long) {
        panelChosen = id
        lifecycleScope.launch { refreshPanel() }
    }

    /** Close = dismiss (the agent is told); one already closed is just hidden. */
    private fun closePanel(i: Interaction) {
        val store = app.interactions
        panelChosen = null
        lifecycleScope.launch {
            if (i.status.open) withContext(Dispatchers.IO) { runCatching { store.dismiss(i.id) } }
            else panel.show(null)
            if (i.status.open) com.google.android.material.snackbar.Snackbar.make(b.root,
                getString(R.string.interactions_closed_snack, InteractionUi.agentName(i)), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
            refreshPanel()
        }
    }

    private fun applyPanelInset() {
        if (::panel.isInitialized) panel.setBottomInset(if (b.bottomNav.isVisible) 0 else navInset)
    }

    /** D26: the Interactions tab badge = questions waiting plus notices not yet seen ("99+" cap, hidden at 0). */
    private fun watchInteractionsBadge() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.interactions.changed.collectLatest {
                    val n = withContext(Dispatchers.IO) { runCatching { app.interactions.unreadCount() }.getOrDefault(0) }
                    val badge = b.bottomNav.getOrCreateBadge(R.id.interactions)
                    badge.maxCharacterCount = 3
                    badge.setContentDescriptionQuantityStringsResource(R.plurals.interactions_badge)
                    badge.number = n
                    badge.isVisible = n > 0
                }
            }
        }
    }

    /** The feedback notification (FBK-11) opens its report. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
            nav.currentDestination?.id !in SETUP) {
            nav.navigate(R.id.interactions, null, androidx.navigation.NavOptions.Builder()
                .setPopUpTo(R.id.interactions, inclusive = true).build())
        }
        openReportFrom(intent)
        val arrival = wakeFor(intent)
        openInteractionFrom(intent, arrival)
        openTourFrom(intent)
        openDogFrom(intent)
        openOwnerSetupFrom(intent)
        openAgentFrom(intent)
        openPortalFrom(intent)
        openForegroundScreen(intent)
    }

    private fun openForegroundScreen(intent: Intent?) {
        val screen = intent?.getStringExtra(cc.bridgit.app.platform.AppForeground.EXTRA_SCREEN) ?: return
        intent.removeExtra(cc.bridgit.app.platform.AppForeground.EXTRA_SCREEN)
        if (nav.currentDestination?.id in SETUP) return
        val destination = cc.bridgit.app.platform.AppForeground.SCREENS[screen] ?: return
        // Already there (e.g. Home behind another app): keep it, so a Device Diagnostics replay plays on the shown scene.
        if (nav.currentDestination?.id == destination) return
        nav.navigate(destination, null, androidx.navigation.NavOptions.Builder().setLaunchSingleTop(true)
            .setPopUpTo(nav.graph.startDestinationId, inclusive = false).build())
    }

    /** A security alert opens the agent it is about. */
    private fun openPortalFrom(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_OPEN_PORTAL) ?: return
        intent.removeExtra(EXTRA_OPEN_PORTAL)
        if (nav.currentDestination?.id in SETUP) return
        val host = supportFragmentManager.findFragmentById(R.id.nav_host) as? NavHostFragment
        val inbox = host?.childFragmentManager?.primaryNavigationFragment as? cc.bridgit.app.ui.interactions.InteractionsFragment
        if (nav.currentDestination?.id == R.id.interactions && inbox != null) { inbox.showPortal(id); return }
        cc.bridgit.app.ui.applets.AppletPortalsFragment.wanted = id
        // Switch to the Applets tab like a tab tap, so Applets › Portals doesn't land on top of the
        // current tab's stack (it did on More, which then kept reopening Portals).
        nav.navigate(R.id.interactions, androidx.core.os.bundleOf("showPortals" to true),
            androidx.navigation.NavOptions.Builder().setLaunchSingleTop(true)
                .setPopUpTo(nav.graph.startDestinationId, inclusive = false, saveState = true).build())
    }

    private fun openAgentFrom(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_OPEN_AGENT) ?: return
        intent.removeExtra(EXTRA_OPEN_AGENT)
        if (app.agents.get(id) == null || nav.currentDestination?.id in setOf(R.id.addAgent, R.id.pairCode, R.id.firstRun)) return
        nav.navigate(R.id.agentDetail, androidx.core.os.bundleOf("agentId" to id))
    }

    /**
     * CAP-56: an arriving interaction turns the screen on and asks Android to lift the lock screen. Never shown
     * over the lock screen, so nobody answers a card without unlocking. Lasts only for this launch.
     */
    private fun wakeFor(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(InteractionNotifier.EXTRA_ARRIVAL, false) != true) return false
        intent.removeExtra(InteractionNotifier.EXTRA_ARRIVAL)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        return true
    }

    /** The City tour notification opens the tour screen. */
    private fun openTourFrom(intent: Intent?) {
        if (intent?.getBooleanExtra(cc.bridgit.app.tour.TourNotifier.EXTRA_OPEN_TOUR, false) != true) return
        intent.removeExtra(cc.bridgit.app.tour.TourNotifier.EXTRA_OPEN_TOUR)
        if (nav.currentDestination?.id in setOf(R.id.addAgent, R.id.pairCode, R.id.firstRun, R.id.tour)) return
        nav.navigate(R.id.tour)
    }

    /** The Pet monitor notification opens the Pet monitor. */
    private fun openOwnerSetupFrom(intent: Intent?) {
        val target=intent?.getStringExtra("owner_setup_target") ?: return
        intent.removeExtra("owner_setup_target")
        // "home": back from adding a phone to view (Bridgit Portal viewer), Home shows that phone.
        when(target) { "add_agent" -> nav.navigate(R.id.addAgent); "cameras" -> nav.navigate(R.id.cameras); "settings" -> nav.navigate(R.id.settings); "network" -> nav.navigate(R.id.networkDevices); "home" -> b.bottomNav.selectedItemId = R.id.home }
    }

    private fun openDogFrom(intent: Intent?) {
        if (intent?.getBooleanExtra(cc.bridgit.app.dog.DogNotifier.EXTRA_OPEN_DOG, false) != true) return
        intent.removeExtra(cc.bridgit.app.dog.DogNotifier.EXTRA_OPEN_DOG)
        if (nav.currentDestination?.id in setOf(R.id.addAgent, R.id.pairCode, R.id.firstRun, R.id.dogMonitor)) return
        nav.navigate(R.id.dogMonitor)
    }

    /**
     * A question or notice notification (CAP-33) opens its interaction. [arrival]: Bridgit came to the front on
     * its own for it (CAP-56), so the card steps aside again once the owner is done.
     */
    private fun openInteractionFrom(intent: Intent?, arrival: Boolean = false) {
        val id = intent?.getLongExtra(EXTRA_INTERACTION_ID, -1L) ?: -1L
        if (id < 0) return
        intent?.removeExtra(EXTRA_INTERACTION_ID)
        val automatic = intent?.getBooleanExtra(cc.bridgit.app.interact.InteractionNotifier.EXTRA_AUTOMATIC, false) == true
        intent?.removeExtra(cc.bridgit.app.interact.InteractionNotifier.EXTRA_AUTOMATIC)
        // Not over an unfinished setup prompt or first run: those must finish first.
        if (nav.currentDestination?.id in SETUP) return
        // A text panel opens in the lower half (D26); everything else opens its detail.
        lifecycleScope.launch {
            val interaction = withContext(Dispatchers.IO) { runCatching { app.interactions.get(id) }.getOrNull() }
                ?: return@launch
            // Notifications/intents can already be queued when an answer is saved.
            // Only a new agent request may bring a completed question back.
            val explicitForeground = intent?.let { app.platform.foreground.consumeCardRequest(it,id) } == true
            if (!cc.bridgit.app.interact.CardLifecycle.mayOpenFromNotification(interaction.status) && !explicitForeground) return@launch
            val kind = interaction.kind
            if (kind == IKind.TEXT) showTextPanel(id)
            // A new card replaces the one on screen, so closing it doesn't land on the old one. It keeps that
            // screen's takeover, so a flow of cards steps aside once, at its end, not between cards.
            else {
                val onCard = nav.currentBackStackEntry?.takeIf { it.destination.id == R.id.interactionDetail }
                val inherited = cc.bridgit.app.interact.NotifRules.replacementArrival(arrival, onCard?.arguments?.getBoolean("arrival", false) == true)
                nav.navigate(R.id.interactionDetail, Bundle().apply { putLong("interactionId", id); putBoolean("arrival", inherited); putBoolean("automatic", automatic) },
                androidx.navigation.NavOptions.Builder().setPopUpTo(R.id.interactionDetail, true).build())
            }
        }
    }

    private fun openReportFrom(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_REPORT_ID, -1L) ?: -1L
        if (id < 0) return
        intent?.removeExtra(EXTRA_REPORT_ID)
        // Not over an unfinished setup prompt or first run: those must finish first.
        if (nav.currentDestination?.id in setOf(R.id.addAgent, R.id.pairCode, R.id.firstRun)) return
        nav.navigate(R.id.reportDetail, Bundle().apply { putLong("reportId", id) })
    }

    /** First run, or back to an unfinished setup prompt after a crash (AGT-05a). */
    private fun route() {
        val pending = app.vault.get()
        when {
            pending != null && app.agents.get(pending.agentId) != null ->
                nav.navigate(R.id.addAgent, Bundle().apply { putString("agentId", pending.agentId) })
            !app.settings.firstRunDone && app.agents.count == 0 -> nav.navigate(R.id.firstRun)
        }
    }

    /** Android lets an app see the clipboard only while its window has focus, so SEC-11 runs here, not in onResume. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { clearOurClip(); leftApp = false }
    }

    /** True once Bridgit went to the background; a copied callback secret is cleared only after that. */
    private var leftApp = false

    override fun onStop() {
        super.onStop()
        leftApp = true
        setTurnScreenOn(false)
    }

    override fun onPause() {
        super.onPause()
        MainActivityState.inFront = false
    }

    override fun onResume() {
        super.onResume()
        MainActivityState.inFront = true
        // A visible start lets Android grant the camera and microphone service types (ARC-03).
        if (app.settings.listenerEnabled && app.agents.count > 0) {
            val s = app.listener.value
            if (!s.running || s.startedInBackground) runCatching { BridgeService.start(this) }
        }
        cc.bridgit.app.update.UpdateInstaller.noteAdbInstallResult(app) // back after an update over ADB
        cc.bridgit.app.update.UpdateInstaller.clearUpdatedNotice(this) // the "tap to open" notice after an update
        UpdateDialog.resumeAfterPermission(this) // back from "Install unknown apps"
        UpdateDialog.autoCheckIfDue(this) // UPD-02: at most once a day, only when the app is opened.
    }

    /**
     * SEC-11: a copied prompt or key is cleared the next time Bridgit is in the
     * foreground. Only the clip's label is read, which doesn't show Android's paste toast.
     */
    private fun clearOurClip() {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val label = runCatching { cm.primaryClipDescription?.label?.toString() }.getOrNull() ?: return
        if (label == CLIP_LABEL) runCatching { cm.clearPrimaryClip() }
        // The secret's Copy button closes a dialog, which refocuses this window at once: wait until the owner has been away.
        if (label == CLIP_LABEL_SECRET && leftApp) runCatching { cm.clearPrimaryClip() }
    }

    fun showBottomBar(show: Boolean) { b.bottomNav.visibility = if (show) View.VISIBLE else View.GONE }

    companion object {
        const val CLIP_LABEL = "Bridgit setup prompt"
        const val CLIP_LABEL_SECRET = "Bridgit callback secret"
        /** Long extra: open this report (from the feedback notification). */
        const val EXTRA_REPORT_ID = "report_id"
        const val EXTRA_OPEN_AGENT = "open_agent"
        /** Applets › Portals with this portal open ("pkg.<package id>"): Installed applets › Open for a portal-first applet. */
        const val EXTRA_OPEN_PORTAL = "open_portal"
        /** Long extra: open this interaction (from a question or notice notification). */
        const val EXTRA_INTERACTION_ID = "interaction_id"
        /** D26: Activity is no longer a tab; it is opened from More and shows a back arrow. */
        val TOP_LEVEL = setOf(R.id.home, R.id.interactions, R.id.camera, R.id.agents, R.id.more)
        /** The tabs that show the hub while a satellite is switched to it, under [HubBar]. */
        val HUB_TABS = setOf(R.id.home, R.id.interactions, R.id.camera, R.id.agents, R.id.cameras)
        /** Full-screen setup and tour screens: no device bar. */
        private val NO_DEVICE_BAR = setOf(R.id.addAgent, R.id.pairCode, R.id.firstRun, R.id.tour)
        /** Setup screens nothing may open over. */
        private val SETUP = setOf(R.id.addAgent, R.id.pairCode, R.id.firstRun)
    }
}
