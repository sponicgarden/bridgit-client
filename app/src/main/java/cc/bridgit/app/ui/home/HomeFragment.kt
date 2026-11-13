package cc.bridgit.app.ui.home

import cc.bridgit.app.power.DefaultAccess
import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.R as M
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.capability.granted
import cc.bridgit.app.databinding.FragmentHomeBinding
import cc.bridgit.app.databinding.HomeBannerBinding
import cc.bridgit.app.databinding.TourHomeCardBinding
import cc.bridgit.app.interact.Interaction
import cc.bridgit.app.tour.TourKinds
import cc.bridgit.app.ui.common.themeColor
import cc.bridgit.app.ui.interactions.InteractionUi

/**
 * Home: Device Diagnostics (2 Nov 2026). Banners that need the owner on top (UI-10), and under
 * them the live scene of every part of the phone Bridgit can use ([DiagnosticsScene]).
 *
 * What the old Home showed moved: the listener card (state, address, Pause) to the top of Agents,
 * the agents list to Agents, "Last 24 hours" to Activity, and the quick switches and Max Agentic
 * Control to More > Feature enablement.
 */
class HomeFragment : Fragment() {
    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp

    private val perms = PermissionAsker(this) { requestRender() }

    /** Bumped by registry/settings listeners (any thread); collected and throttled on main. */
    private val dirty = MutableStateFlow(0)
    private val onChange: () -> Unit = { dirty.update { it + 1 } }

    /** Questions the owner hasn't answered, expired ones included (loaded off the main thread). */
    private var waiting: List<Interaction> = emptyList()
    /** Agent requests waiting for approval in Agent permissions (A0145), loaded off the main thread. */
    private var approvals: List<org.json.JSONObject> = emptyList()
    private var bannerKey = ""
    private var tourCard: TourHomeCardBinding? = null
    private var scene: DiagnosticsScene? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        // No three-dot menu (owner, 2 Nov 2026): Rerun lives on the scene, pause in Quick Settings and the notification, everything else is under More.
        buildTourCard()
        cc.bridgit.app.portal.PortalLinks.init(app)
        buildScene()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.listener.collect { render() } }
                launch { app.wake.state.collect { scene?.refresh() } }
                launch { app.tours.state.collect { renderTourCard(); scene?.refresh() } }
                launch { app.dog.state.collect { scene?.refresh() } }
                // An agent asked for Device Diagnostics (foreground target device_diagnostics): play the full run.
                launch { DiagnosticsRerun.requests.collect { if (scene != null && DiagnosticsRerun.take()) scene?.play() } }
                launch { while (true) { scene?.refreshStatus(); delay(1_000) } }
                launch { dirty.collect { render(); delay(300) } }
                launch { watchEmailVerification() }
                // Bridgit Portal viewer: switching phones rebuilds the scene for the chosen one.
                launch { cc.bridgit.app.portal.PortalLinks(app).selected.collect { d -> if (d != shownDevice) rebuildScene() } }
                launch { app.log.latestId.collectLatest { delay(150); scene?.pollLog() } }
                // Approvals: refresh on every platform change, and every 15 s so expired ones drop off.
                launch { app.platform.changes.collectLatest { delay(300); loadApprovals() } }
                launch { while (true) { delay(15_000); loadApprovals() } }
                launch {
                    app.interactions.changed.collectLatest {
                        waiting = withContext(Dispatchers.IO) { runCatching { app.interactions.waitingQuestions() }.getOrDefault(emptyList()) }
                        requestRender()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.agents.addListener(onChange)
        app.settings.addListener(onChange)
    }

    override fun onResume() {
        super.onResume()
        render() // permissions or notification settings may have changed while away
        DefaultAccess.healOverAdb(app) { requestRender() }
        autoAsk()
    }

    /** Without Bridgit's ADB link, open Android's own one-tap dialog for each default that's off, once per install. */
    private fun autoAsk() {
        val ask = DefaultAccess.nextAsk(app) ?: return
        DefaultAccess.markAsked(app, ask)
        when (ask) {
            DefaultAccess.Ask.NOTIFICATIONS -> perms.request(Manifest.permission.POST_NOTIFICATIONS)
            DefaultAccess.Ask.BATTERY -> UiActions.requestBatteryExemption(requireContext())
        }
    }

    /**
     * While Home is on screen and the email link hasn't been tapped yet, ask bridgit.cc every few seconds
     * (every 5 s for the first 10 minutes, then every 30 s). Starts again each time Home comes back, so
     * returning from the email app notices the tap at once. Says thank you when it flips to verified.
     */
    private suspend fun watchEmailVerification() {
        val pending = cc.bridgit.app.alerts.Alerts.STATE_PENDING
        val started = System.currentTimeMillis()
        while (true) {
            if (cc.bridgit.app.alerts.EmailVerificationRules.shouldRefresh(app.settings.alertEmail, app.settings.alertState)) {
                val now = withContext(Dispatchers.IO) { runCatching { app.alerts.refreshVerification() }.getOrNull() }
                if (now == cc.bridgit.app.alerts.Alerts.STATE_CONFIRMED)
                    view?.let { com.google.android.material.snackbar.Snackbar.make(it, R.string.email_verify_done, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() }
                if (now != pending) requestRender()
            }
            scene?.refresh() // The subtle email shortcut can become due while Home stays open.
            delay(if (System.currentTimeMillis() - started < 10 * 60_000L) 5_000L else 30_000L)
        }
    }

    override fun onStop() {
        app.agents.removeListener(onChange)
        app.settings.removeListener(onChange)
        super.onStop()
    }

    override fun onDestroyView() {
        scene?.release(); scene = null
        super.onDestroyView()
        tourCard = null
        bannerKey = ""
        _b = null
    }

    private fun requestRender() = dirty.update { it + 1 }

    private suspend fun loadApprovals() {
        val now = withContext(Dispatchers.IO) { runCatching { app.platform.pendingApprovals().filter { it.optString("kind") != "interaction" } }.getOrDefault(emptyList()) }
        if (now.map { it.optString("id") } != approvals.map { it.optString("id") }) { approvals = now; requestRender() }
    }

    private fun openApproval(id: String?) = startActivity(
        android.content.Intent(requireContext(), cc.bridgit.app.platform.ControlCenterActivity::class.java)
            .apply { if (id != null) putExtra(cc.bridgit.app.platform.ControlCenterActivity.EXTRA_REVIEW_ID, id) })

    private fun render() {
        if (_b == null) return
        renderBanners()
        scene?.refresh()
    }

    /** The other phone Home shows, or null for this phone. */
    private var shownDevice: String? = null

    private fun rebuildScene() {
        scene?.release(); scene = null
        b.sceneFrame.removeAllViews()
        buildScene()
    }

    private fun buildScene() {
        val links = cc.bridgit.app.portal.PortalLinks(app)
        val remote = remoteHome(links)
        shownDevice = remote?.device
        showPortalHeader(remote)
        val s = DiagnosticsScene(requireContext(), app, onOpen = ::openFromScene, onPauseAsked = ::setPaused,
            remote = remote, onRemoteStatus = { at, error -> remote?.let { showPortalStatus(it, at, error) } })
        b.sceneFrame.addView(s, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The full run plays once per app start; later visits open on the finished map (Rerun, or an agent's
        // device_diagnostics request, plays it again).
        val rerun = DiagnosticsRerun.take()
        s.load(play = !playedThisRun || rerun)
        playedThisRun = true
        scene = s
    }

    /** The scene's Active / Paused switch and the menu item: the same pause as the Quick Settings tile. */
    private fun setPaused(pause: Boolean) {
        if (shownDevice != null) return // another phone: read-only
        if (pause) ListenerCard.pause(app)
        else if (app.agents.count == 0) { findNavController().navigate(R.id.addAgent); return }
        else ListenerCard.resume(app)
        requestRender()
    }

    /** The phone Home shows instead of this one: a paired phone over the relay, or this satellite's hub. */
    private fun remoteHome(links: cc.bridgit.app.portal.PortalLinks): RemoteHome? {
        links.selectedLink()?.let { l -> return RemoteHome(l.device, l.name, false) { path, q -> cc.bridgit.app.portal.PortalClient.get(l, path, q) } }
        val h = links.selectedHub() ?: return null
        return RemoteHome(cc.bridgit.app.portal.PortalLinks.HUB + h.linkId, h.name, true) { path, q -> app.hubLinks.portalGet(h, path, q) }
    }

    private fun showPortalHeader(remote: RemoteHome?) {
        // Viewing another phone: no logo row (owner, 5 Nov 2026). The device banner above every tab names it and
        // carries the way back; Home adds how fresh the data is.
        b.brand.isVisible = remote == null
        b.toolbar.minimumHeight = if (remote == null) toolbarMinHeight else 0
        hubBar()?.setStatus(if (remote == null) null else getString(R.string.portal_viewing_waiting))
    }

    private fun hubBar() = (activity as? cc.bridgit.app.ui.MainActivity)?.hubBar

    private val toolbarMinHeight by lazy { (44 * resources.displayMetrics.density).toInt() }

    private fun showPortalStatus(remote: RemoteHome, at: Long, error: String?) {
        if (_b == null) return
        val fmt = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        val lastGood = cc.bridgit.app.portal.PortalViewer.seen(remote.device)?.at
        hubBar()?.setStatus(when {
            error == null -> getString(R.string.portal_viewing, fmt.format(java.util.Date(at)))
            lastGood != null -> getString(R.string.portal_viewing_failed, error, fmt.format(java.util.Date(lastGood)))
            else -> getString(R.string.portal_viewing_failed_never, error)
        })
    }

    /**
     * The hexagon's phone chooser, the same on a portal, a satellite and a hub (owner, 5 Nov 2026): this phone, each
     * phone it views as a portal (with what it last reported), each hub it is a satellite of, and Linked devices and
     * pairing. The phone Home shows now is the one ticked. A hub that hasn't checked in yet is listed too: it can't be
     * shown until it sends its Home, and choosing it says why instead of quietly leaving it out.
     */
    private fun showPhoneChooser() {
        val links = cc.bridgit.app.portal.PortalLinks(app)
        val viewer = cc.bridgit.app.portal.PortalViewer
        // Tick what Home shows now (the saved choice can lag the screen for a moment while it switches).
        val shown = shownDevice
        val stale = mutableSetOf<String>() // hubs that stopped checking in: Linked devices offers Pair again
        val forgotten = mutableSetOf<String>() // of those, the ones where Bridgit answers but doesn't list this phone
        val rows = mutableListOf(PhoneChooserSheet.Row(null, getString(R.string.portal_chooser_this),
            cc.bridgit.app.portal.PortalPairing.phoneName(app), thisPhoneDetail(), null, thisPhone = true, waiting = false,
            battery = thisPhoneBattery(), selected = shown == null))
        links.all().forEach { l ->
            val seen = viewer.seen(l.device)
            rows += PhoneChooserSheet.Row(l.device, l.name, getString(R.string.connected_hub_portal), viewer.details(seen?.home),
                seen?.at, thisPhone = false, waiting = false, battery = viewer.battery(seen?.home), selected = shown == l.device)
        }
        val info = app.hubLinks.hubs().associateBy { it.linkId }
        app.hubLinks.agents().forEach { a ->
            val h = info[a.id]
            val id = cc.bridgit.app.portal.PortalLinks.HUB + a.id
            val seen = viewer.seen(id)
            val state = if (h == null) app.hubLinks.state(a.id) else null
            if (state?.needsPairing == true) stale += id
            if (state == cc.bridgit.app.satellite.HubLinks.LinkState.FORGOTTEN) forgotten += id
            rows += PhoneChooserSheet.Row(id, h?.name ?: a.name.removePrefix("Hub "),
                getString(when {
                    h != null -> R.string.portal_chooser_hub
                    id in forgotten -> R.string.portal_chooser_hub_forgotten
                    id in stale -> R.string.portal_chooser_hub_stale
                    else -> R.string.portal_chooser_hub_waiting }),
                if (h == null) "" else viewer.details(seen?.home), if (h == null) null else seen?.at, thisPhone = false,
                waiting = h == null, battery = if (h == null) null else viewer.battery(seen?.home), selected = shown == id)
        }
        PhoneChooserSheet.show(requireContext(), rows, onLinked = ::openLinkedDevices, onPick = { r ->
            if (r.waiting) com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(when (r.id) { in forgotten -> R.string.portal_chooser_forgotten_title
                    in stale -> R.string.portal_chooser_stale_title; else -> R.string.portal_chooser_waiting_title }, r.name))
                .setMessage(when (r.id) { in forgotten -> getString(R.string.linked_hub_forgotten, selfNoun())
                    in stale -> getString(R.string.linked_hub_stale, selfNoun()); else -> getString(R.string.linked_hub_waiting) })
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.linked_title) { _, _ -> openLinkedDevices() }.show()
            else links.select(r.id)
        })
    }

    /** "SM-X210 · Android 15 · Bridgit 1.8.2201" for this phone's card. */
    /** "phone", "tablet": what this device is, for "doesn't list this tablet as its satellite". */
    private fun selfNoun(): String = cc.bridgit.app.platform.DeviceType.of(requireContext()).noun

    private fun thisPhoneDetail(): String = listOf(android.os.Build.MODEL.orEmpty(),
        "Android ${android.os.Build.VERSION.RELEASE}", cc.bridgit.app.ui.common.brandVersion(requireContext()).let { if (it.isEmpty()) "" else "Bridgit $it" })
        .filter { it.isNotBlank() }.joinToString(" · ")

    /** This phone's battery percent and charging, or null when Android doesn't report it. */
    private fun thisPhoneBattery(): Pair<Int, Boolean>? = runCatching {
        val bm = requireContext().getSystemService(android.os.BatteryManager::class.java)
        val p = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (p == Int.MIN_VALUE || p !in 0..100) null else p to bm.isCharging
    }.getOrNull()

    private fun openLinkedDevices() = startActivity(android.content.Intent(requireContext(), cc.bridgit.app.portal.PortalActivity::class.java))

    private fun openFromScene(id: String) {
        if (id == "phones") { showPhoneChooser(); return }
        // The top-right connected phones tile opens the Linked devices map.
        if (id == "connected") { openLinkedDevices(); return }
        // On a satellite, the agent tile is its hub link ("Hub Pixel 2 XL"): tapping it switches hubs, as the hexagon does.
        if (id == "agent" && app.agents.all().let { all -> all.isNotEmpty() && all.all { app.hubLinks.isHub(it.id) } }) { showPhoneChooser(); return }
        // Another phone's Home is read-only: nothing on it opens this phone's screens.
        remoteHome(cc.bridgit.app.portal.PortalLinks(app))?.let { remote ->
            view?.let { com.google.android.material.snackbar.Snackbar.make(it, getString(R.string.portal_read_only, remote.name, selfNoun()), com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() }
            return
        }
        if (id.startsWith("capability:")) {
            val capability = id.removePrefix("capability:")
            if (app.registry.byId(capability) != null) findNavController().navigate(R.id.features,
                bundleOf(cc.bridgit.app.ui.more.FeaturesFragment.ARG_CAPABILITY to capability))
            return
        }
        when (id) {
            "email" -> EmailPrompt.ask(this) { requestRender() }
            "addAgent" -> findNavController().navigate(R.id.addAgent)
            "externalCameras" -> findNavController().navigate(R.id.cameras)
            "applets" -> goTab(R.id.interactions, bundleOf("showApplets" to true))
            "voicerec", "sounds" -> startActivity(android.content.Intent(requireContext(), cc.bridgit.app.listen.WakeWordsActivity::class.java))
            "touch" -> findNavController().navigate(R.id.adbSetup)
            "video" -> findNavController().navigate(R.id.features,
                bundleOf(cc.bridgit.app.ui.more.FeaturesFragment.ARG_CAPABILITY to "video"))
            "agent", "tailscale" -> goTab(R.id.agents) // the listener card and address live at the top of Agents
            else -> findNavController().navigate(R.id.features)
        }
    }

    /** "Notifications, camera, microphone are off": only switched-on features that notifications being off stops right now. */
    private fun notificationsBannerText(): String {
        val off = app.registry.switchable()
            .filter { it.enabled && runCatching { it.blockedReason() }.getOrNull() == "notifications_off" }
            .map { it.title.lowercase() }
        return if (off.isEmpty()) getString(R.string.home_banner_notifications)
        else getString(R.string.home_banner_notifications_list, off.joinToString(", "))
    }

    // ---- City tour (8.25): a card at the top while a tour runs ----

    /** Inserted in code above the banners. */
    private fun buildTourCard() {
        val parent = b.top
        val c = TourHomeCardBinding.inflate(layoutInflater, parent, false)
        parent.addView(c.root, 0)
        c.root.setOnClickListener { findNavController().navigate(R.id.tour) }
        c.tourOpen.setOnClickListener { findNavController().navigate(R.id.tour) }
        tourCard = c
        renderTourCard()
    }

    private fun renderTourCard() {
        val c = tourCard ?: return
        val t = app.tours.current()
        c.root.isVisible = t != null
        if (t == null) return
        var text = getString(R.string.tour_home_card, t.agent, t.kinds.joinToString(", ") { TourKinds.labels[it] ?: it })
        if (t.paused) text += " · " + getString(R.string.tour_home_paused)
        if (c.tourText.text.toString() != text) c.tourText.text = text
    }

    // ---- Banners (UI-10) ----

    private data class Banner(val key: String, val text: String, val action: String, val error: Boolean,
                              val icon: Int? = null, val onDismiss: (() -> Unit)? = null, val onClick: () -> Unit)

    /** Only when action is needed. Order: questions waiting for the owner, nothing works without Tailscale, then what blocks captures. */
    private fun banners(): List<Banner> {
        val ctx = requireContext()
        val s = app.listener.value
        val out = mutableListOf<Banner>()
        // The set-up banners below read the one set-up step list (Client Core 4.8), the list the Set-up screen and
        // GET /v1/device/profile show; a banner appears while its step is blocked.
        val setup = cc.bridgit.app.core.profile.AndroidProfile.osSteps(app)
        fun stepBlocked(id: String) = setup[id]?.state == cc.bridgit.app.core.profile.SetupState.BLOCKED
        // Agent requests waiting for approval come first: they expire in minutes (A0145).
        approvals.singleOrNull()?.let { r ->
            val name = r.optJSONObject("identity")?.optString("name")?.ifBlank { null } ?: "Agent"
            val what = r.optJSONObject("review_context")?.optString("title")?.ifBlank { null } ?: cc.bridgit.app.platform.PlatformNotifications.actionLabel(r)
            out += Banner("approve1:${r.optString("id")}", getString(R.string.home_banner_approval_one, cap(name, 18), what),
                getString(R.string.home_banner_review), false, R.drawable.ic_nav_interactions) { openApproval(r.optString("id")) }
        }
        if (approvals.size > 1)
            out += Banner("approveN:${approvals.size}", resources.getQuantityString(R.plurals.home_banner_approvals, approvals.size, approvals.size),
                getString(R.string.home_banner_review), false, R.drawable.ic_nav_interactions) { openApproval(null) }
        waiting.singleOrNull()?.let { q ->
            out += Banner("ask1:${q.id}", getString(R.string.home_banner_question_one, cap(InteractionUi.agentName(q), 18), q.title),
                getString(R.string.home_banner_answer), false, R.drawable.ic_nav_interactions) {
                findNavController().navigate(R.id.interactionDetail, bundleOf("interactionId" to q.id))
            }
        }
        if (waiting.size > 1)
            out += Banner("askN:${waiting.size}", resources.getQuantityString(R.plurals.home_banner_questions, waiting.size, waiting.size),
                getString(R.string.home_banner_see_questions), false, R.drawable.ic_nav_interactions) { goTab(R.id.interactions, bundleOf("showCards" to true)) }
        if (!UiActions.isTailscaleInstalled(ctx)) {
            val direct = cc.bridgit.app.update.TailscaleInstaller.available()
            out += Banner("ts", getString(R.string.home_banner_tailscale_missing_short),
                getString(if (direct) R.string.ts_step_install_button else R.string.home_banner_setup_tailscale), true) {
                findNavController().navigate(R.id.tailscaleSetup, bundleOf(cc.bridgit.app.ui.tailscale.TailscaleSetupFragment.ARG_AUTO_INSTALL to direct))
            }
        }
        else if (s.running && s.boundTo.isEmpty() && s.bindError == null)
            out += Banner("ts", getString(R.string.home_banner_tailscale), getString(R.string.home_banner_setup_tailscale), true) {
                findNavController().navigate(R.id.tailscaleSetup)
            }
        if (stepBlocked("notifications") && !DefaultAccess.isDismissed(ctx, DefaultAccess.Ask.NOTIFICATIONS))
            out += Banner("notif", notificationsBannerText(), getString(R.string.home_banner_turn_on), false,
                onDismiss = { DefaultAccess.dismiss(ctx, DefaultAccess.Ask.NOTIFICATIONS); renderBanners() }) {
                if (android.os.Build.VERSION.SDK_INT >= 33 && !ctx.granted(Manifest.permission.POST_NOTIFICATIONS))
                    perms.request(Manifest.permission.POST_NOTIFICATIONS)
                else if (!ctx.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled())
                    UiActions.openAppNotificationSettings(ctx)
                else UiActions.openChannelSettings(ctx, BridgeApp.CHANNEL_CAPTURE)
            }
        if (stepBlocked("open_app"))
            out += Banner("once", getString(R.string.home_banner_open_once), getString(R.string.home_banner_fix_now), false) {
                runCatching { BridgeService.start(ctx) }
            }
        // One banner per missing permission, not per feature: mic, wake words and sounds share "Microphone".
        val blocked = app.registry.switchable().mapNotNull { m ->
            val reason = m.blockedReason()
            if (!m.enabled || reason?.startsWith("permission:") != true) null
            else PermissionBanners.Blocked(reason.removePrefix("permission:"), UiActions.permissionsFor(m).toList())
        }
        if (stepBlocked("permissions")) for (g in PermissionBanners.group(blocked)) {
            val title = UiActions.permissionTitle(ctx, g.kind)
            out += Banner("perm:${g.kind}", getString(R.string.home_banner_permission, title), getString(R.string.home_banner_grant), false) {
                perms.request(*g.request.toTypedArray())
            }
        }
        if (stepBlocked("battery") && !DefaultAccess.isDismissed(ctx, DefaultAccess.Ask.BATTERY))
            out += Banner("battery", getString(R.string.home_banner_battery), getString(R.string.home_banner_fix), false,
                onDismiss = { DefaultAccess.dismiss(ctx, DefaultAccess.Ask.BATTERY); renderBanners() }) {
                UiActions.requestBatteryExemption(ctx)
            }
        return out
    }

    /** Banners are one line: long agent names are cut so the question still shows. */
    private fun cap(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    /** Every banner has an X. Banners with no lasting dismiss hide until their text changes or the app restarts. */
    private fun dismiss(bn: Banner) {
        val d = bn.onDismiss
        if (d != null) d() else { hiddenBanners += "${bn.key}:${bn.text}"; renderBanners() }
    }

    private fun renderBanners() {
        val list = banners().filter { "${it.key}:${it.text}" !in hiddenBanners }
        b.banners.isVisible = list.isNotEmpty()
        val key = list.joinToString("|") { "${it.key}:${it.text}" }
        if (key == bannerKey) {
            // Same banners: just rebind click handlers (captured context may be new).
            list.forEachIndexed { i, bn ->
                b.banners.getChildAt(i)?.let { row ->
                    row.findViewById<View>(R.id.action)?.setOnClickListener { bn.onClick() }
                    row.findViewById<View>(R.id.dismiss)?.setOnClickListener { dismiss(bn) }
                }
            }
            return
        }
        bannerKey = key
        b.banners.removeAllViews()
        val ctx = requireContext()
        for (bn in list) {
            val v = HomeBannerBinding.inflate(layoutInflater, b.banners, false)
            val bg = if (bn.error) M.attr.colorErrorContainer else M.attr.colorSecondaryContainer
            val fg = ctx.themeColor(if (bn.error) M.attr.colorOnErrorContainer else M.attr.colorOnSecondaryContainer)
            v.root.setCardBackgroundColor(ctx.themeColor(bg))
            v.icon.setImageResource(bn.icon ?: if (bn.error) R.drawable.ic_error else R.drawable.ic_warning)
            v.icon.setColorFilter(fg)
            v.text.text = bn.text
            v.text.setTextColor(fg)
            v.action.text = bn.action
            v.action.setTextColor(fg)
            v.action.setOnClickListener { bn.onClick() }
            v.dismiss.imageTintList = android.content.res.ColorStateList.valueOf(fg)
            v.dismiss.contentDescription = getString(R.string.home_banner_dismiss, bn.text)
            v.dismiss.setOnClickListener { dismiss(bn) }
            b.banners.addView(v.root)
        }
    }

    private fun goTab(id: Int, args: Bundle? = null) {
        val nav = findNavController()
        nav.navigate(id, args, NavOptions.Builder().setLaunchSingleTop(true).setRestoreState(args == null)
            .setPopUpTo(nav.graph.startDestinationId, inclusive = false, saveState = true).build())
    }

    companion object {
        /** The full diagnostics run has played since the app started. */
        private var playedThisRun = false
        /** Banners closed with X that have no lasting dismiss ("key:text"); they come back when the app restarts. */
        private val hiddenBanners = mutableSetOf<String>()
    }
}
