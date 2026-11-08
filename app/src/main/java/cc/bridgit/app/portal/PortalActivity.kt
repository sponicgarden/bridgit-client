package cc.bridgit.app.portal

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.databinding.ActivityPortalBinding
import cc.bridgit.app.portal.LinkedDeviceSheet.Tone
import cc.bridgit.app.portal.LinkedDevicesLayout.Kind
import cc.bridgit.app.portal.LinkedDevicesLayout.Node
import cc.bridgit.app.portal.LinkedDevicesLayout.Peer
import cc.bridgit.app.portal.LinkedDevicesLayout.Role
import cc.bridgit.app.relay.RelayStore
import cc.bridgit.app.satellite.HubLinks
import cc.bridgit.app.satellite.LinkPause
import cc.bridgit.app.satellite.SatelliteEndpoints
import cc.bridgit.app.satellite.SatelliteHub
import cc.bridgit.app.satellite.SatelliteStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * More › Linked devices (owner, 5 Nov 2026: the Portals and Satellites screens as two). The top two thirds is a still
 * map in Home's look of every hub, portal and satellite linked to this phone ([LinkedDevicesLayout]); tapping one shows
 * it with what can be done (show its Home, check, choose agents, unlink). Below is one Pair a device area, which opens
 * [PairDeviceActivity] for every kind of pairing. "Bridgit Portal wants to view this phone" notifications open here.
 * Not exported. Nothing secret is on this screen (pairing codes are on Pair a device, in secure sheets).
 */
class PortalActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private val store by lazy { RelayStore(app) }
    private lateinit var b: ActivityPortalBinding
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPortalBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.toolbar.updatePadding(top = bars.top)
            v.updatePadding(bottom = bars.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { finish() }
        b.pairDevice.setOnClickListener { startActivity(Intent(this, PairDeviceActivity::class.java)) }
        b.diagram.onNode = ::openNode
        b.pauseSatellites.setOnClickListener { LinkPause.setSatellites(!LinkPause.satellites) }
        b.pauseHubs.setOnClickListener { LinkPause.setHubs(!LinkPause.hubsPaused) }
        pillA11y(b.pauseSatellites, R.string.linked_pause_satellites) { LinkPause.satellites }
        pillA11y(b.pauseHubs, R.string.linked_pause_hubs) { LinkPause.hubsPaused }
        // TalkBack: a double tap on the map lists its devices.
        b.diagram.onPickFromList = {
            val nodes = diagram().all
            MaterialAlertDialogBuilder(this).setTitle(R.string.linked_title)
                .setItems(nodes.map { n -> n.name + " · " + if (n.thisPhone) getString(R.string.linked_role_this, noun) else roleLabel(n) + (n.type?.let { " · " + it.title } ?: "") }.toTypedArray()) { _, i -> openNode(nodes[i]) }
                .setNegativeButton(R.string.linked_close, null).show()
        }
        buildPairKinds()
        render()
        lifecycleScope.launch { SatelliteStore.changed.collectLatest { render() } }
        lifecycleScope.launch { HubLinks.changes.collectLatest { render() } }
        lifecycleScope.launch { LinkPause.changed.collectLatest { render() } }
        // A newly paired portal's first call, or a removed one: redraw now, not on the next tick.
        lifecycleScope.launch { PortalPairing.portalsChanged(app).collectLatest { render() } }
        // Online dots follow the devices without a manual refresh.
        lifecycleScope.launch { while (true) { delay(10_000); render() } }
        if (savedInstanceState == null) askAboutRequest(intent)
        if (savedInstanceState == null && cc.bridgit.app.BuildConfig.DEBUG) intent.getStringExtra(EXTRA_SAMPLE)?.let { b.root.post { showSample(it) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        askAboutRequest(intent)
    }

    override fun onResume() {
        super.onResume()
        added = null
        if (::b.isInitialized) render()
        // A hub that stopped checking in: is Bridgit still running there? Decides "offline" or "doesn't list this phone".
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.hubLinks.probeStaleHubs() } }
    }

    /** "phone", "tablet", "TV": what this device is, for "This tablet" and "Nothing is linked to this tablet yet." */
    private val noun: String by lazy { cc.bridgit.app.platform.DeviceType.of(this).noun }

    private fun render() {
        // Satellites, like hubs: "2 Satellites Enabled" / "2 Satellites Disabled"; none linked reads "0 Satellites Enabled" in red.
        // Portals are view-only satellites (owner, 5 Nov 2026): they count here too.
        val satCount = app.satellites.count() + runCatching { PortalPairing.granted(app, store).size }.getOrDefault(0)
        pill(b.pauseSatellites, LinkPause.satellites, red = LinkPause.satellites || satCount == 0, text = resources.getQuantityString(
            if (LinkPause.satellites) R.plurals.linked_pill_satellites_disabled else R.plurals.linked_pill_satellites_enabled, satCount, satCount))
        // Hubs: "2 Hubs Enabled" / "2 Hubs Disabled" (paused); with none linked it reads "0 Hubs Enabled" in red (owner, 5 Nov 2026).
        val hubCount = app.hubLinks.ids().size
        pill(b.pauseHubs, LinkPause.hubsPaused, red = LinkPause.hubsPaused || hubCount == 0, text = resources.getQuantityString(
            if (LinkPause.hubsPaused) R.plurals.linked_pill_hubs_disabled else R.plurals.linked_pill_hubs_enabled, hubCount, hubCount))
        val d = diagram()
        buildLegend(d.portals.isNotEmpty())
        b.diagram.show(d)
        b.hint.text = if (d.alone) getString(R.string.linked_hint_alone, noun) else getString(R.string.linked_hint)
    }

    /** Everything this phone knows about, as the map's rows. */
    private fun diagram(now: Long = System.currentTimeMillis()): LinkedDevicesLayout.Diagram {
        val info = app.hubLinks.hubs().associateBy { it.linkId }
        val hubsOf = app.hubLinks.agents().map { a ->
            val heard = maxOf(a.lastUsedAt, info[a.id]?.at ?: 0L)
            val forgotten = app.hubLinks.state(a.id, now) == HubLinks.LinkState.FORGOTTEN
            Peer(a.id, info[a.id]?.name ?: a.name.removePrefix("Hub "), !LinkPause.hubsPaused && !forgotten && now - heard in 0..ONLINE_MS,
                if (LinkPause.hubsPaused) getString(R.string.linked_paused_status)
                else if (forgotten) getString(R.string.linked_hub_forgotten_status, noun) else null, info[a.id]?.deviceType)
        }
        val viewing = runCatching { PortalLinks(this).all() }.getOrDefault(emptyList()).map { l ->
            val seen = PortalViewer.seen(l.device)
            // device_type from a phone on this version; an older one only says phone or tablet (type).
            val dev = seen?.home?.optJSONObject("device")
            Peer(l.device, l.name, seen?.at?.let { now - it in 0..PortalPairing.SHOWN_MS } == true,
                type = cc.bridgit.app.platform.DeviceType.parse(dev?.optString(cc.bridgit.app.platform.DeviceType.FIELD)) ?: cc.bridgit.app.platform.DeviceType.parse(dev?.optString("type")))
        }
        val sats = runCatching { app.satellites.all() }.getOrDefault(emptyList()).map { Peer(it.id, it.name, !LinkPause.satellites && it.online(now),
            if (LinkPause.satellites) getString(R.string.linked_paused_status) else null, cc.bridgit.app.platform.DeviceType.parse(it.manifest?.optString(cc.bridgit.app.platform.DeviceType.FIELD))) }
        val recent = PortalPairing.viewers(app, store, now).associateBy { it.agentId }
        // Every portal granted access is drawn, online or not (owner, 5 Nov 2026): it keeps its access until it is
        // disconnected, so the map shows it, offline when it hasn't called lately. Most recently used first.
        val granted = runCatching { PortalPairing.granted(app, store) }.getOrDefault(emptyList()).sortedByDescending { it.lastUsedAt }
        val names = granted.associate { it.id to (PortalClients.get(app, it.id)?.name ?: recent[it.id]?.kind ?: PortalModule.clientOf(it.id)) }
        // Online only while it is actually connected (called in the last minute); otherwise the map says offline (owner, 5 Nov 2026).
        val viewers = granted.map { a -> Peer(a.id, names[a.id] ?: a.name, recent[a.id]?.live == true,
            type = PortalClients.get(app, a.id)?.type ?: PortalClients.guess(recent[a.id]?.kind)) }
        return LinkedDevicesLayout.build(PortalPairing.phoneName(app), cc.bridgit.app.platform.DeviceType.of(this), hubsOf, viewing, sats, viewers, networkPeers())
    }

    /**
     * Devices on this phone's network for the map's bottom row: the ones the last network scan found (More › Network
     * devices; nothing is scanned from here) and the cameras and smart devices added to Bridgit, even when not found.
     * Unrecognised devices and this phone itself are left out.
     */
    private fun networkPeers(): List<LinkedDevicesLayout.NetPeer> = runCatching {
        val lan = runCatching { cc.bridgit.app.lan.HostLan(this) }.getOrNull()
        val seen = lan?.let { l -> runCatching { cc.bridgit.app.lan.DiscoveryHistory(this).read(l.historyScope(this)) }.getOrNull() }.orEmpty()
            .filter { it.present && it.host != lan?.local }
        val (cameras, added) = addedDevices()
        val cameraHosts = cameras.map { it.first }.toSet()
        val addedHosts = added.map { it.first }.toSet()
        val P = cc.bridgit.app.lan.NetworkDevicePresentation
        val found = seen.mapNotNull { d ->
            val info = P.describe(d)
            // Unrecognised devices, phones and computers stay on Network devices; the map keeps the ones agents use.
            if (info.kind in SKIPPED_KINDS && d.host !in addedHosts) return@mapNotNull null
            // A name that is only an address or a hardware id says nothing: show what it is instead.
            // AirPlay names come as "DC:62:94:45:41:BC@ProLite TE7513A": keep the part after the hardware id.
            val title = info.title.replace(MAC_PREFIX, "").trim().takeUnless { it.isEmpty() || it.matches(ID_LIKE) } ?: info.kind.label
            val name = added.firstOrNull { it.first == d.host }?.second ?: title
            LinkedDevicesLayout.NetPeer(d.host, name, info.kind.label, info.kind.name, d.connected, d.host in addedHosts, P.remotePriority(d))
        }
        // Added ones the last scan did not find still show, dimmed (a camera is the usual kind).
        found + added.filter { (host, _) -> found.none { it.id == host } }.distinctBy { it.first }.map { (host, name) ->
            val camera = host in cameraHosts
            LinkedDevicesLayout.NetPeer(host, name, if (camera) "Camera" else "Smart device", if (camera) "CAMERA" else "SMART", false, true)
        }
    }.getOrDefault(emptyList())

    /** Cameras, and cameras plus smart devices, added to Bridgit (host to name); read once per visit, not every refresh. */
    private var added: Pair<List<Pair<String, String>>, List<Pair<String, String>>>? = null
    private fun addedDevices() = added ?: run {
        val cameras = runCatching { app.lanCameras.all().map { it.host to it.name } }.getOrDefault(emptyList())
        (cameras to cameras + runCatching { app.lanDevices.all().map { it.host to it.name } }.getOrDefault(emptyList())).also { added = it }
    }

    /**
     * A pause pill (Sol 6.1 spec): the map's panel colour inside, a 1dp outline, a dot and the label in green while that
     * side is on, red while paused. Drawn 36dp tall inside its 48dp touch area.
     */
    private fun pill(v: TextView, paused: Boolean, text: String, red: Boolean = paused) {
        val dark = LinkedDevicesGlyph.dark(this)
        val colour = if (red) (if (dark) 0xFFFFAEAA.toInt() else 0xFFA72C2C.toInt()) else LinkedDevicesGlyph.thisPhone(dark)
        val panel = if (dark) 0xFF0D1424.toInt() else 0xFFE6EAF3.toInt()
        v.text = text
        v.setTextColor(colour)
        val outline = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(18).toFloat(); setColor(panel); setStroke(dp(1), colour)
        }
        val ripple = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf((colour and 0x00FFFFFF) or 0x33000000), outline, null)
        v.background = android.graphics.drawable.InsetDrawable(ripple, 0, dp(6), 0, dp(6))
        val dot = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(colour); setSize(dp(8), dp(8))
        }
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        androidx.core.view.ViewCompat.setStateDescription(v, getString(if (paused) R.string.linked_pill_state_paused else R.string.linked_pill_state_on))
    }

    /** TalkBack reads each pill as one switch: "Pause satellite connections, switch, off, enabled"; on means paused. */
    private fun pillA11y(v: TextView, label: Int, paused: () -> Boolean) {
        androidx.core.view.ViewCompat.setAccessibilityDelegate(v, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Switch::class.java.name
                info.contentDescription = getString(label)
                info.isCheckable = true
                info.isChecked = paused()
            }
        })
    }

    private var legendPortal: Boolean? = null

    /** The map's shapes and colours, each with its name, as on the map; Portal only while the map has a portal row. */
    private fun buildLegend(portal: Boolean) {
        if (legendPortal == portal) return
        legendPortal = portal
        b.legend.removeAllViews()
        val dark = LinkedDevicesGlyph.dark(this)
        listOfNotNull(R.string.linked_role_hub to Role.HUB, if (portal) R.string.linked_role_portal to Role.PORTAL else null,
            R.string.linked_role_satellite to Role.SATELLITE).forEach { (label, role) ->
            b.legend.addView(glyphLabel(getString(label), role, LinkedDevicesGlyph.roleColour(dark, role)))
        }
        b.legend.addView(glyphLabel(getString(R.string.linked_role_this, noun), Role.SATELLITE, LinkedDevicesGlyph.thisPhone(dark)))
    }

    /** Pair a device: the three kinds it pairs, as the map draws them (owner, 5 Nov 2026: icons, not a sentence). */
    private fun buildPairKinds() {
        val dark = LinkedDevicesGlyph.dark(this)
        listOf(R.string.linked_role_hub to Role.HUB, R.string.linked_role_satellite to Role.SATELLITE,
            R.string.linked_role_portal to Role.PORTAL).forEachIndexed { i, (label, role) ->
            b.pairKinds.addView(glyphLabel(getString(label), role, LinkedDevicesGlyph.roleColour(dark, role), start = if (i == 0) 0 else 14, size = 16))
        }
    }

    private fun glyphLabel(text: String, role: Role, colour: Int, start: Int = 8, size: Int = 14) = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
        setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        setCompoundDrawablesRelativeWithIntrinsicBounds(LinkedDevicesGlyph.Icon(role, colour, dp(size), 1.6f * resources.displayMetrics.density), null, null, null)
        compoundDrawablePadding = dp(5)
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(start), 0, dp(8), 0)
    }

    // ---- A tapped device ----

    private fun roleLabel(n: Node) = if (n.kind == Kind.VIEWER) getString(R.string.linked_role_view_only) else getString(when (n.role) {
        LinkedDevicesLayout.Role.HUB -> R.string.linked_role_hub
        LinkedDevicesLayout.Role.PORTAL -> R.string.linked_role_portal
        LinkedDevicesLayout.Role.SATELLITE -> R.string.linked_role_satellite
        LinkedDevicesLayout.Role.NETWORK -> R.string.linked_role_network
    })

    private fun openNode(n: Node) {
        when (n.kind) {
            Kind.THIS_PHONE -> openThisPhone()
            Kind.HUB -> openHub(n)
            Kind.SATELLITE -> openSatellite(n.id)
            Kind.VIEWER -> openViewer(n)
            Kind.NETWORK -> openNetworkDevice(n)
            Kind.NETWORK_MORE -> openNetworkDevices()
        }
    }

    // ---- Network devices keep a small dialog (LinkedDeviceSheet); every other kind opens a DeviceDetailSheet, below. ----

    private fun role(r: Int) = getString(r)
    private fun st(r: Int, tone: Tone) = LinkedDeviceSheet.Status(getString(r), tone)

    /** A device on this phone's network: what it is, whether it is added, when it was found and its address. */
    private fun openNetworkDevice(n: Node) {
        val seen = runCatching {
            val lan = cc.bridgit.app.lan.HostLan(this)
            cc.bridgit.app.lan.DiscoveryHistory(this).read(lan.historyScope(this)).firstOrNull { it.host == n.id }
        }.getOrNull()
        networkSheet(n.name, n.label, n.online, n.added, seen?.lastSeen?.takeIf { it > 0 }, n.id).show(this)
    }

    private fun networkSheet(name: String, kind: String, online: Boolean, added: Boolean, lastFound: Long?, address: String) =
        LinkedDeviceSheet(name, kind.ifBlank { null },
            if (online) st(R.string.linked_status_online, Tone.GOOD) else st(R.string.linked_status_not_found, Tone.QUIET),
            listOfNotNull(getString(if (added) R.string.linked_net_added else R.string.linked_net_seen),
                lastFound?.let { getString(R.string.linked_fact_last_found, ago(it)) },
                getString(R.string.linked_net_address, address)),
            primary = LinkedDeviceSheet.Action(getString(R.string.linked_net_open)) { openNetworkDevices() })

    private fun openNetworkDevices() {
        startActivity(Intent(this, cc.bridgit.app.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("owner_setup_target", "network"))
    }

    // ---- Device sheets (DeviceDetailSheet): what a device says about itself, as the iOS Portal shows it, with View in
    // place of Attach. Each builder takes plain data so the debug build can open them with samples (showSample). ----

    /** What a device told this phone: its own Home here, a hub's fetched Home, or a satellite's manifest. Null: not known. */
    private class Known(
        val model: String? = null, val android: String? = null, val bridgit: String? = null, val battery: Pair<Int, Boolean>? = null,
        val address: String? = null, val connections: List<Pair<String, String>> = emptyList(),
        val featuresOn: Int? = null, val featuresAll: Int? = null, val agents: Int? = null,
        val appletsOn: Int? = null, val appletsAll: Int? = null, val network: Int? = null,
        val services: List<Pair<String, String>> = emptyList(),
    )

    private fun subtitle(k: Known?): String? = k?.let {
        listOfNotNull(it.model, it.android?.let { a -> "Android $a" }, it.bridgit?.let { v -> "Bridgit $v" })
            .filter { s -> s.isNotBlank() }.joinToString(" · ").ifEmpty { null }
    }

    private fun where(k: Known?) = k?.let { DeviceDetailSheet.Where(it.address, it.connections) }

    private fun tiles(k: Known?): List<Pair<String, String>> = k?.let {
        listOfNotNull(
            if (it.featuresOn != null && it.featuresAll != null) getString(R.string.linked_n_of_m, it.featuresOn, it.featuresAll) to getString(R.string.linked_tile_features) else null,
            it.agents?.let { n -> n.toString() to getString(R.string.linked_tile_agents) },
            if (it.appletsOn != null && it.appletsAll != null) getString(R.string.linked_n_of_m, it.appletsOn, it.appletsAll) to getString(R.string.linked_tile_applets) else null,
            it.network?.let { n -> n.toString() to getString(R.string.linked_tile_network) })
    }.orEmpty()

    /** A /v1/portal/home reply (this phone's own, or a hub's): device, battery, connections, features, agents. */
    private fun knownFromHome(home: JSONObject?, address: String? = null, agents: Int? = null, applets: Pair<Int, Int>? = null,
                              network: Int? = null, services: List<Pair<String, String>> = emptyList()): Known? {
        home ?: return null
        val d = home.optJSONObject("device")
        val b = home.optJSONObject("battery")?.takeIf { !it.isNull("percent") }
        val c = home.optJSONObject("connections")
        val f = home.optJSONArray("features")
        fun str(o: JSONObject?, k: String) = o?.optString(k)?.takeIf { it.isNotBlank() && it != "null" }
        return Known(
            model = listOfNotNull(str(d, "manufacturer")?.replaceFirstChar { it.uppercase() }, str(d, "model")).joinToString(" ").ifEmpty { null }
                ?.let { m -> if (str(d, "model")?.startsWith(str(d, "manufacturer") ?: "\u0000", ignoreCase = true) == true) str(d, "model") else m },
            android = str(d, "android"), bridgit = str(d, "app_version"),
            battery = b?.let { it.optInt("percent") to it.optBoolean("charging") }, address = address,
            connections = listOf("adb" to R.string.linked_conn_adb, "relay" to R.string.linked_conn_relay, "tailscale" to R.string.linked_conn_tailscale,
                "wifi" to R.string.linked_conn_wifi).mapNotNull { (key, label) -> str(c, key)?.let { getString(label) to it } },
            featuresOn = f?.let { a -> (0 until a.length()).count { a.optJSONObject(it)?.optString("state") == "on" } }, featuresAll = f?.length(),
            agents = agents ?: home.optInt("agents_count", -1).takeIf { it >= 0 },
            appletsOn = applets?.first, appletsAll = applets?.second, network = network, services = services)
    }

    private fun offline(lastAt: Long?) = DeviceDetailSheet.Status(lastAt?.takeIf { it > 0 }?.let { getString(R.string.linked_pill_offline_ago, ago(it)) }
        ?: getString(R.string.linked_status_offline), false)
    private val livePill get() = DeviceDetailSheet.Status(getString(R.string.linked_status_live), true)
    private fun conn(title: Int, body: String, live: Boolean) = DeviceDetailSheet.Connection(getString(title), body, live)
    private fun DeviceDetailSheet.open() = show(this@PortalActivity, getString(R.string.linked_close_details), getString(R.string.linked_connected))

    private fun openThisPhone() {
        val d = diagram()
        val module = app.registry.modules.firstOrNull { it is PortalModule } as? PortalModule
        val lan = runCatching { cc.bridgit.app.lan.HostLan(this) }.getOrNull()
        val network = lan?.let { l -> runCatching { cc.bridgit.app.lan.DiscoveryHistory(this).read(l.historyScope(this)).count { it.present && it.host != l.local } }.getOrNull() }
        val applets = runCatching { app.remoteView.applets() }.getOrNull()?.let { a -> (0 until a.length()).count { a.optJSONObject(it)?.optBoolean("running") == true } to a.length() }
        val svc = runCatching { app.remoteView.snapshot().optJSONObject("services") }.getOrNull()
        val services = listOf("clef" to R.string.linked_service_clef, "jev" to R.string.linked_service_jev, "weather" to R.string.linked_service_weather)
            .mapNotNull { (k, label) -> svc?.optString(k)?.takeIf { it.isNotBlank() && it != "unknown" }?.let { getString(label) to serviceWord(it) } }
        val known = knownFromHome(runCatching { module?.ownHome() }.getOrNull(), lan?.let { "${it.local}/${it.prefix}" },
            cc.bridgit.app.ui.home.HomeStates.controlAgents(app).size, applets, network, services)
        val others = d.all.filter { (it.kind == Kind.SATELLITE || it.kind == Kind.VIEWER) && !it.thisPhone }
        thisPhoneSheet(PortalPairing.phoneName(app), known, others.size, others.count { it.kind == Kind.VIEWER },
            d.hubs.filter { it.satLink != null }.map { it.name }, d.hubs.filter { it.portalLink != null }.map { it.name }).open()
    }

    private fun serviceWord(s: String) = getString(when (s) { "ready" -> R.string.linked_service_ready; "on" -> R.string.linked_service_on
        "off" -> R.string.linked_service_off; else -> R.string.linked_service_blocked })

    /** [satellites]: everything linked to this phone, portals included; [viewOnly]: how many of those are portals. */
    private fun thisPhoneSheet(name: String, known: Known?, satellites: Int, viewOnly: Int, satelliteOf: List<String>, viewOnlyOf: List<String>) =
        DeviceDetailSheet(name, subtitle(known), { livePill }, known?.battery,
            { DeviceDetailSheet.Connection(getString(R.string.linked_conn_this_title, noun), getString(R.string.linked_conn_this_body), true) },
            facts = listOfNotNull(
                satellites.takeIf { it > 0 }?.let { n -> resources.getQuantityString(R.plurals.linked_hub_for, n, n) +
                    (viewOnly.takeIf { it > 0 }?.let { v -> " " + getString(R.string.linked_view_only_part, v) } ?: "") + "." },
                satelliteOf.takeIf { it.isNotEmpty() }?.let { getString(R.string.linked_this_satellite_of, it.joinToString(", ")) },
                viewOnlyOf.takeIf { it.isNotEmpty() }?.let { getString(R.string.linked_this_view_only_of, it.joinToString(", ")) }),
            where = where(known), tiles = tiles(known), services = known?.services.orEmpty(),
            primary = DeviceDetailSheet.Action(getString(R.string.linked_view)) { showHome(null, name) },
            primaryCaption = getString(R.string.linked_view_this_caption, noun))

    /** A hub: this phone is its satellite ([Node.satLink]), its view-only satellite ([Node.portalLink]) or both. */
    private fun openHub(n: Node) {
        val info = n.satLink?.let { id -> app.hubLinks.hubs().firstOrNull { it.linkId == id } }
        val agent = n.satLink?.let { app.agents.get(it) }
        val state = n.satLink?.let { app.hubLinks.state(it) }
        val seen = n.portalLink?.let { PortalViewer.seen(it) }
        val homeId = n.portalLink ?: info?.let { PortalLinks.HUB + it.linkId }
        val heard = maxOf(agent?.lastUsedAt ?: 0L, info?.at ?: 0L, seen?.at ?: 0L).takeIf { it > 0 }
        hubSheet(n.name, knownFromHome(seen?.home), seen?.at, n.online, LinkPause.hubsPaused, state, n.satLink != null, n.portalLink != null, heard,
            info?.let { it.targets.size to it.portals.size },
            showHome = homeId?.let { { showHome(it, n.name) } }, pairAgain = { pairAgain(n.satLink!!) }, remove = { removeHub(n) },
            link = n.satLink?.let { id -> app.links.all().firstOrNull { it.role == cc.bridgit.app.link.Links.Role.SAT && it.ref == id } })
            .open()
    }

    private fun hubSheet(name: String, known: Known?, updatedAt: Long?, online: Boolean, paused: Boolean, state: HubLinks.LinkState?,
                         satellite: Boolean, portal: Boolean, heard: Long?, shared: Pair<Int, Int>?,
                         showHome: (() -> Unit)?, pairAgain: () -> Unit, remove: () -> Unit, link: cc.bridgit.app.link.Links.Rec? = null): DeviceDetailSheet {
        val live = online && !paused
        val stale = satellite && state?.needsPairing == true
        val facts = buildList {
            if (satellite) add(getString(R.string.linked_hub_satellite, noun))
            if (portal && !satellite) add(getString(R.string.linked_hub_view_only, noun))
            shared?.let { (photos, portals) -> add(getString(R.string.linked_hub_uses,
                resources.getQuantityString(R.plurals.linked_n_photo_applets, photos, photos), resources.getQuantityString(R.plurals.linked_n_portals, portals, portals))) }
            when {
                state == HubLinks.LinkState.FORGOTTEN -> add(getString(R.string.linked_hub_forgotten, noun))
                state == HubLinks.LinkState.STALE -> add(getString(R.string.linked_hub_stale, noun))
                satellite && shared == null -> add(getString(R.string.linked_hub_waiting))
            }
            addAll(linkFacts(link))
            updatedAt?.takeIf { known != null }?.let { add(getString(R.string.linked_updated_ago, ago(it))) }
        }
        return DeviceDetailSheet(name, subtitle(known),
            { when { paused -> DeviceDetailSheet.Status(getString(R.string.linked_status_paused), false); live -> livePill; else -> offline(heard) } },
            known?.battery,
            { if (live) conn(R.string.linked_conn_hub_live, getString(R.string.linked_conn_hub_live_body, noun), true)
              else conn(R.string.linked_conn_hub_off, getString(R.string.linked_conn_hub_off_body), false) },
            facts, where(known)?.copy(address = null), tiles(known), known?.services.orEmpty(),
            primary = when {
                stale -> DeviceDetailSheet.Action(getString(R.string.linked_pair_again), run = pairAgain)
                showHome != null -> DeviceDetailSheet.Action(getString(R.string.linked_view), run = showHome)
                else -> null
            },
            primaryCaption = if (!stale && showHome != null) getString(R.string.linked_view_hub_caption) else null,
            pause = link?.let { pauseAction(it, name) },
            destructive = if (link != null && !portal) DeviceDetailSheet.Destructive(getString(R.string.link_disconnect), null) { disconnectWithUndo(link, name) }
            else DeviceDetailSheet.Destructive(getString(if (satellite) R.string.satellite_unlink else R.string.portal_other_remove),
                buildList {
                    if (satellite) add(getString(R.string.satellite_unlink_confirm, name))
                    if (portal) add(getString(R.string.portal_other_remove_body, name, noun))
                }.joinToString("\n\n"), remove))
    }

    /** A hub that forgot this phone: drop the dead link and show a new satellite link to add on the hub. */
    private fun pairAgain(linkId: String) {
        app.hubLinks.unlink(linkId)
        render()
        startActivity(android.content.Intent(this, PairDeviceActivity::class.java).putExtra(PairDeviceActivity.EXTRA_JOIN_SATELLITE, true))
    }

    private fun removeHub(n: Node) {
        n.satLink?.let { app.hubLinks.unlink(it) }
        n.portalLink?.let { PortalLinks(this).remove(it) }
        render()
    }

    /** One of this phone's satellites: what its manifest says, who may use it, Check now and Remove. */
    private fun openSatellite(id: String) {
        val s = app.satellites.get(id) ?: return
        val m = s.manifest
        val bat = m?.optJSONObject("battery")?.takeIf { !it.isNull("percent") }
        val caps = m?.optJSONArray("capabilities")
        val status = SatelliteEndpoints.publicJson(s).optString("status")
        val known = Known(model = m?.optString("model")?.takeIf { it.isNotBlank() }, android = m?.optString("android")?.takeIf { it.isNotBlank() },
            bridgit = m?.optString("app_version")?.takeIf { it.isNotBlank() }, battery = bat?.let { it.optInt("percent") to it.optBoolean("charging") },
            address = s.host.takeIf { it.isNotBlank() },
            connections = listOf(getString(R.string.linked_conn_tailscale) to if (status == "online") "connected" else "unreachable"),
            featuresOn = caps?.let { a -> (0 until a.length()).count { a.optJSONObject(it)?.optBoolean("enabled") == true } }, featuresAll = caps?.length())
        val ret = s.returnAgent?.let { rid -> app.agents.get(rid) }
        val agents = app.agents.all().filter { !app.hubLinks.isHub(it.id) && !app.satellites.isReturnAgent(it.id) }
        satelliteSheet(s.name, known, { app.satellites.get(id)?.let { SatelliteEndpoints.publicJson(it).optString("status") } ?: status },
            s.lastSeen.takeIf { it > 0 }, s.shareError, s.sharedAt.takeIf { it > 0 },
            ret?.let { r -> !r.paused to { on: Boolean ->
                app.agents.update(r.id) { it.copy(paused = !on) }
                app.log.system("audit", "satellite", "${if (on) "Resumed" else "Paused"} satellite ${s.name}") } },
            agents.map { a -> Triple(a.name, a.id in s.agents) { on: Boolean ->
                app.satellites.setAgent(s.id, a.id, on)
                app.log.system("audit", "satellite", "${if (on) "Let" else "Stopped"} ${a.name} ${if (on) "use" else "using"} ${s.name}") } },
            check = { lifecycleScope.launch { withContext(Dispatchers.IO) { SatelliteHub.refresh(app, s.id) }; render() } },
            remove = { SatelliteHub.remove(app, s.id); render() },
            link = app.links.all().firstOrNull { it.role == cc.bridgit.app.link.Links.Role.HUB && it.ref == s.id }).open()
    }

    /**
     * [status]: SatelliteEndpoints' word (online, offline, unlinked, not_checked), read again while the sheet is open.
     * [manage]: whether it may see and manage this phone, and what flipping it does (null: no return link).
     */
    private fun satelliteSheet(name: String, known: Known?, status: () -> String, lastSeen: Long?, shareError: String?, sharedAt: Long?,
                               manage: Pair<Boolean, (Boolean) -> Unit>?, agents: List<Triple<String, Boolean, (Boolean) -> Unit>>,
                               check: () -> Unit, remove: () -> Unit, link: cc.bridgit.app.link.Links.Rec? = null): DeviceDetailSheet {
        fun live() = !LinkPause.satellites && status() == "online"
        val variant = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        return DeviceDetailSheet(name, subtitle(known),
            { when { LinkPause.satellites -> DeviceDetailSheet.Status(getString(R.string.linked_status_paused), false)
                live() -> livePill; status() == "not_checked" -> DeviceDetailSheet.Status(getString(R.string.linked_status_not_checked), false)
                else -> offline(lastSeen) } },
            known?.battery,
            { if (live()) conn(R.string.linked_conn_sat_live, getString(R.string.linked_conn_sat_live_body), true)
              else conn(R.string.linked_conn_sat_off, getString(if (status() == "unlinked") R.string.linked_fact_unlinked else R.string.linked_conn_sat_off_body), false) },
            listOfNotNull(lastSeen?.let { getString(R.string.linked_fact_last_seen, ago(it)) },
                shareError?.let { getString(R.string.satellite_share_failed, it) } ?: sharedAt?.let { getString(R.string.linked_fact_shared, ago(it)) }) + linkFacts(link),
            where(known), tiles(known),
            controls = { box ->
                manage?.let { (on, flip) ->
                    // Paused (owner, 5 Nov 2026): the satellite can't see or change this phone (its Home, applets, agents) until resumed.
                    val hint = TextView(this).apply { text = getString(R.string.satellite_can_manage_hint, noun)
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall); setTextColor(variant)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                    box.addView(com.google.android.material.materialswitch.MaterialSwitch(this).apply {
                        text = getString(R.string.satellite_can_manage, noun); isChecked = on; minHeight = dp(48)
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                        setOnCheckedChangeListener { _, v -> flip(v) }
                        ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
                            override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                                super.onInitializeAccessibilityNodeInfo(host, info); info.hintText = hint.text }
                        })
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    box.addView(hint)
                }
                box.addView(TextView(this).apply {
                    setText(R.string.satellite_agents); setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                    ViewCompat.setAccessibilityHeading(this, true)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (manage != null) 16 else 8); bottomMargin = dp(4) })
                if (agents.isEmpty()) box.addView(TextView(this).apply { text = getString(R.string.satellite_no_agents, noun)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium); setTextColor(variant) })
                agents.forEach { (n, on, set) ->
                    box.addView(CheckBox(this).apply { text = n; isChecked = on; minHeight = dp(48)
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                        setOnCheckedChangeListener { _, v -> set(v) } },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                }
            },
            secondary = DeviceDetailSheet.Action(getString(R.string.satellite_check), run = check),
            pause = link?.let { pauseAction(it, name) },
            destructive = if (link != null) DeviceDetailSheet.Destructive(getString(R.string.link_disconnect), null) { disconnectWithUndo(link, name) }
            else DeviceDetailSheet.Destructive(getString(R.string.satellite_remove), getString(R.string.satellite_remove_confirm, name), remove))
    }

    /** An iPhone, iPad or phone that views this one through Bridgit Portal: a view-only satellite. */
    private fun openViewer(n: Node) {
        val info = PortalClients.get(app, n.id)
        viewerSheet(n.name, info?.device?.takeIf { !it.equals(n.name, ignoreCase = true) },
            live = { PortalPairing.viewers(app, store).firstOrNull { it.agentId == n.id }?.live == true },
            lastAt = { app.agents.get(n.id)?.lastUsedAt?.takeIf { it > 0 } }) {
            PortalPairing.disconnect(app, store, n.id); PortalClients.forget(app, n.id); render()
        }.open()
    }

    /** [live]: it called in the last minute (it asks every 10 s while open), read again every 10 s while the sheet shows. */
    private fun viewerSheet(name: String, model: String?, live: () -> Boolean, lastAt: () -> Long?, disconnect: () -> Unit) =
        DeviceDetailSheet(name, model, { if (live()) livePill else offline(lastAt()) }, null,
            { if (live()) conn(R.string.linked_conn_portal_live, getString(R.string.linked_viewer_body, noun), true)
              else conn(R.string.linked_conn_portal_off, getString(R.string.linked_viewer_body, noun), false) },
            destructive = DeviceDetailSheet.Destructive(getString(R.string.portal_disconnect),
                getString(R.string.portal_disconnect_title, name) + "\n\n" + getString(R.string.portal_disconnect_body, name, noun), disconnect))

    // ---- Linked Bridgits on the device sheet (docs/spec-linked-bridgits.md, LNK-26, LNK-27, LNK-42) ----

    /** One line per direction that isn't off: "This tablet uses: camera, microphone", "It uses this tablet: Home and applets". */
    private fun linkFacts(r: cc.bridgit.app.link.Links.Rec?): List<String> {
        r ?: return emptyList()
        val l = app.links
        fun value(state: cc.bridgit.app.link.LinkModel.State, services: Set<String>) = when (state) {
            cc.bridgit.app.link.LinkModel.State.ON -> serviceWords(services)
            cc.bridgit.app.link.LinkModel.State.PAUSED -> getString(R.string.link_state_paused)
            cc.bridgit.app.link.LinkModel.State.OFF -> null
            else -> getString(R.string.link_state_waiting)
        }
        return listOfNotNull(
            value(l.outState(r), r.farOut?.services ?: emptySet())?.let { getString(R.string.link_this_uses, noun, it) },
            value(l.inState(r), r.inServices)?.let { getString(R.string.link_it_uses, noun, it) })
    }

    private fun serviceWords(services: Set<String>): String = cc.bridgit.app.link.LinkModel.SERVICES.filter { it in services }.map { getString(when (it) {
        "camera" -> R.string.link_service_camera; "mic" -> R.string.link_service_mic; "speaker" -> R.string.link_service_speaker
        "interactions" -> R.string.link_service_interactions; "location" -> R.string.link_service_location; "gallery" -> R.string.link_service_gallery
        "sounds" -> R.string.link_service_sounds; else -> R.string.link_service_view }) }.joinToString(", ").ifEmpty { getString(R.string.link_state_waiting) }

    /** Pause both directions, or Resume them: one tap each way (LNK-27). */
    private fun pauseAction(r: cc.bridgit.app.link.Links.Rec, name: String): DeviceDetailSheet.Action {
        val paused = app.links.inState(r) == cc.bridgit.app.link.LinkModel.State.PAUSED || app.links.outState(r) == cc.bridgit.app.link.LinkModel.State.PAUSED
        return DeviceDetailSheet.Action(getString(if (paused) R.string.link_resume else R.string.link_pause),
            getString(if (paused) R.string.link_resume_a11y else R.string.link_pause_a11y, name)) {
            val person = cc.bridgit.app.link.Granter.person(app.links.selfName())
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { runCatching {
                    if (paused) { app.links.setDirection(r.id, "in", null, null, false, person); app.links.setDirection(r.id, "out", null, null, false, person) }
                    else app.links.disconnect(r.id, "both", person, pause = true)
                } }
                render()
            }
        }
    }

    /** Disconnect at once, with Undo for 10 seconds instead of a question (LNK-26). [name]: as the sheet shows it. */
    private fun disconnectWithUndo(r: cc.bridgit.app.link.Links.Rec, name: String) {
        val person = cc.bridgit.app.link.Granter.person(app.links.selfName())
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { app.links.disconnect(r.id, "both", person) }.isSuccess }
            render()
            if (!ok) return@launch
            com.google.android.material.snackbar.Snackbar.make(b.root, getString(R.string.link_disconnected, name), cc.bridgit.app.link.Links.UNDO_MS.toInt())
                .setAction(R.string.link_undo) { lifecycleScope.launch { withContext(Dispatchers.IO) { runCatching { app.links.undo(r.id, person) } }; render() } }
                .show()
        }
    }

    /** Debug builds: opens a sample (debug_sheet=this|viewer|viewer_live|satellite|satellite_off|hub|hub_stale|network|network_off). */
    private fun showSample(kind: String) {
        val now = System.currentTimeMillis()
        val nop = {}
        val full = Known("Google Pixel 2 XL", "11", "1.8.2943", 100 to true, "192.168.1.50/24",
            listOf("ADB", "Relay", "Tailscale", "Wi-Fi").map { it to "connected" }, 21, 22, 5, 3, 4, 12,
            listOf("Clef" to "Ready", "Jev" to "Ready", "Weather" to "On"))
        when (kind) {
            "this" -> thisPhoneSheet(PortalPairing.phoneName(app), full, 3, 1, emptyList(), emptyList()).open()
            "viewer" -> viewerSheet("iPhone", null, { false }, { now - 2 * 3600_000 }, nop).open()
            "viewer_live" -> viewerSheet("Rahul's iPad Pro 13-inch (M4)", "iPad Pro 13-inch (M4)", { true }, { now }, nop).open()
            "satellite" -> satelliteSheet("A11 Blue Tab (test satellite)", Known("SM-X238U", "15", "1.8.2983", 82 to true, "100.85.71.87",
                listOf("Tailscale" to "connected"), 9, 11), { "online" }, now - 5 * 60_000, null, now - 3 * 60_000, true to { _: Boolean -> },
                listOf("claude test 11-05-26", "ChatGPT", "Grok Commie").map { Triple(it, it != "ChatGPT") { _: Boolean -> } }, nop, nop).open()
            "satellite_off" -> satelliteSheet("Kitchen Pixel", null, { "offline" }, now - 26 * 3600_000, "relay timeout", null, null, emptyList(), nop, nop).open()
            "hub" -> hubSheet("Pixel 2 XL", Known("Google Pixel 2 XL", "11", "1.8.2943", 64 to false, null,
                listOf("ADB", "Relay", "Tailscale", "Wi-Fi").map { it to if (it == "ADB") "disconnected" else "connected" }, 20, 22, 4),
                now - 5 * 60_000, true, false, HubLinks.LinkState.OK, true, true, now - 60_000, 4 to 1, nop, nop, nop).open()
            "hub_stale" -> hubSheet("Living room Pixel 2 XL", null, null, false, false, HubLinks.LinkState.STALE, true, false, now - 3 * 86_400_000L, null, null, nop, nop).open()
            "network" -> networkSheet("Hikvision DS-2CD2143G2-I Front Door", "Camera", true, true, now - 3 * 60_000, "192.168.1.42").show(this)
            "network_off" -> networkSheet("Tapo C210", "Camera", false, false, null, "192.168.1.57").show(this)
        }
    }

    /** Home, Applets and Camera switch to [device] (null: this phone), as the Home hexagon's chooser does. */
    private fun showHome(device: String?, name: String) {
        PortalLinks(this).select(device)
        Toast.makeText(this, getString(R.string.linked_home_now, name), Toast.LENGTH_SHORT).show()
        finish()
    }

    // ---- "Bridgit Portal wants to view this phone" ----

    /** Opened from the notification: the owner allows or denies. */
    private fun askAboutRequest(i: Intent?) {
        val id = i?.getStringExtra(PortalApprovals.EXTRA_REQUEST) ?: return
        i.removeExtra(PortalApprovals.EXTRA_REQUEST)
        val r = PortalApprovals.Request(id, i.getStringExtra(PortalApprovals.EXTRA_EMAIL).orEmpty(),
            i.getStringExtra(PortalApprovals.EXTRA_DEVICE).orEmpty(), i.getLongExtra(PortalApprovals.EXTRA_EXPIRES, 0))
        PortalApprovals.cancelNotification(app, r.id)
        if (r.expiresAt in 1..System.currentTimeMillis()) {
            Toast.makeText(this, R.string.portal_request_expired, Toast.LENGTH_LONG).show(); return
        }
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.portal_request_dialog_title, r.deviceName, noun))
            .setMessage(getString(R.string.portal_request_dialog_body, r.deviceName, r.email, noun))
            .setPositiveButton(R.string.portal_request_allow) { _, _ -> answer(r, true) }
            .setNegativeButton(R.string.portal_request_deny) { _, _ -> answer(r, false) }
            .setCancelable(false).show()
    }

    private fun answer(r: PortalApprovals.Request, allow: Boolean) {
        if (busy) return
        busy = true; b.error.isVisible = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { if (allow) PortalApprovals.allow(app, r) else PortalApprovals.deny(app, r) } }
            busy = false
            result.onSuccess {
                Toast.makeText(this@PortalActivity, if (allow) getString(R.string.portal_request_allowed, r.deviceName, noun)
                    else getString(R.string.portal_request_denied), Toast.LENGTH_LONG).show()
            }.onFailure {
                if (it is cc.bridgit.app.relay.RelaySetup.AddressProblem) {
                    cc.bridgit.app.relay.RelayErrorDialog.show(this@PortalActivity, R.string.portal_approve_failed_title,
                        getString(R.string.portal_failed, it.message ?: ""), it)
                    return@onFailure
                }
                val expired = (it.message ?: "").contains("expired", true) || (it.message ?: "").contains("already answered", true)
                b.error.text = if (expired) getString(R.string.portal_request_expired) else getString(R.string.portal_failed, it.message ?: it.javaClass.simpleName)
                b.error.isVisible = true
            }
            render()
        }
    }

    private fun ago(t: Long): CharSequence = System.currentTimeMillis().let { now ->
        if (now - t < 60_000) getString(R.string.satellite_just_now)
        else android.text.format.DateUtils.getRelativeTimeSpanString(t, now, android.text.format.DateUtils.MINUTE_IN_MILLIS) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Debug builds only: a sample device dialog to open (see showSample). */
        const val EXTRA_SAMPLE = "debug_sheet"
        /** A hub counts as online when it called this satellite in the last 10 minutes (it checks every 5). */
        const val ONLINE_MS = 10 * 60_000L
        private val SKIPPED_KINDS = setOf(cc.bridgit.app.lan.NetworkDevicePresentation.Kind.UNKNOWN,
            cc.bridgit.app.lan.NetworkDevicePresentation.Kind.MOBILE, cc.bridgit.app.lan.NetworkDevicePresentation.Kind.COMPUTER)
        private val MAC_PREFIX = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}@")
        private val ID_LIKE = Regex("(?i)([0-9a-f]{2}[:-]){2,}[0-9a-f:.-]*|[0-9a-f]{10,}|[0-9.]+")
    }
}
