package cc.bridgit.app.portal

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.R
import cc.bridgit.app.databinding.ActivityPairDeviceBinding
import cc.bridgit.app.databinding.PairOptionBinding
import cc.bridgit.app.databinding.PortalQrSheetBinding
import cc.bridgit.app.portal.LinkedDevicesLayout.Role
import cc.bridgit.app.relay.RelaySetup
import cc.bridgit.app.relay.RelayStore
import cc.bridgit.app.satellite.HubLinks
import cc.bridgit.app.satellite.SatelliteHub
import cc.bridgit.app.satellite.SatelliteLink
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Linked devices › Pair a device (owner, 5 Nov 2026): every pairing in one screen, in two groups.
 * This phone joins another: become a hub's satellite (one-time QR code and link), or view another phone as a Bridgit
 * Portal (scan its code). Another device joins this phone: add a satellite (scan or paste its link), or let an iPhone,
 * iPad or phone view this one (one-time QR code; the relay is turned on if needed). The flows are the ones the old
 * Portals and Satellites screen had; the sheets with codes are secure windows in release builds.
 * Tapped bridgit://satellite links (SatelliteActivity) land here, to add that satellite.
 */
class PairDeviceActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private val store by lazy { RelayStore(app) }
    private lateinit var b: ActivityPairDeviceBinding
    private var busy = false
    private var making: Job? = null

    private val scanSatellite = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(PortalScanActivity.EXTRA_LINK)?.let { addSatellite(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPairDeviceBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.toolbar.updatePadding(top = bars.top)
            b.scroll.updatePadding(bottom = bars.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { finish() }

        option(b.joinAsSatellite, Role.SATELLITE, R.drawable.agentact_ic_phone, R.string.linkpair_join_satellite, R.string.satellite_self_intro, R.string.satellite_make) {
            makeSatelliteLink(b.joinAsSatellite.name.text?.toString().orEmpty())
        }
        b.joinAsSatellite.nameBox.isVisible = true
        // The four cards share ids; only this one's name field keeps its text across a rotation.
        b.joinAsSatellite.name.isSaveEnabled = true
        b.joinAsSatellite.nameBox.hint = getString(R.string.satellite_name_hint)
        b.joinAsSatellite.name.setText(app.hubLinks.name())

        option(b.viewHub, Role.PORTAL, R.drawable.ic_computer, R.string.linkpair_view_hub, R.string.linkpair_view_hub_body, R.string.portal_other_add) {
            startActivity(Intent(this, PortalScanActivity::class.java))
        }
        // Add a device: this phone shows one code for a Bridgit Portal (it joins as a view-only satellite); a Bridgit
        // phone joining as a full satellite shows its own code, which is scanned (or pasted) from here.
        option(b.addDevice, Role.SATELLITE, R.drawable.ic_qr, R.string.linkpair_add_device, R.string.linkpair_add_device_body, R.string.linkpair_show_code) { pair() }
        b.addDevice.alt.isVisible = true
        b.addDevice.alt.setText(R.string.linkpair_scan_its_code)
        b.addDevice.alt.setOnClickListener {
            scanSatellite.launch(Intent(this, PortalScanActivity::class.java).putExtra(PortalScanActivity.EXTRA_SATELLITE, true))
        }

        if (savedInstanceState == null) askAboutSatellite(intent)
        // "Pair again" on a hub that forgot this phone: show the new satellite link at once.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_JOIN_SATELLITE, false))
            makeSatelliteLink(b.joinAsSatellite.name.text?.toString().orEmpty())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        askAboutSatellite(intent)
    }

    /** One card; its icon sits on the colour the Linked devices map gives the role this pairing makes (or adds). */
    private fun option(o: PairOptionBinding, role: Role, icon: Int, title: Int, body: Int, button: Int, go: () -> Unit) {
        o.icon.setImageResource(icon)
        val dark = LinkedDevicesGlyph.dark(this)
        o.icon.backgroundTintList = android.content.res.ColorStateList.valueOf(LinkedDevicesGlyph.roleColour(dark, role))
        o.icon.imageTintList = android.content.res.ColorStateList.valueOf(if (dark) 0xFF0D1424.toInt() else 0xFFFFFFFF.toInt())
        o.title.setText(title)
        o.body.setText(body)
        o.go.setText(button)
        o.go.setOnClickListener { go() }
    }

    /** A pop-up, like the satellite cards' errors: a line at the top of the list was off-screen under the button. */
    private fun showError(e: Throwable) {
        cc.bridgit.app.relay.RelayErrorDialog.show(this, R.string.portal_pair_failed_title, getString(R.string.portal_failed, e.message ?: e.javaClass.simpleName), e)
    }

    // ---- Let an iPhone, iPad or phone view this one (Bridgit Portal) ----

    private fun pair() {
        if (busy) return
        busy = true
        b.addDevice.go.isEnabled = false; b.addDevice.go.setText(R.string.portal_pairing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Always: it registers the phone only when needed, and it starts the listener service, which
                    // isn't running when the phone has no agents yet. The service tells the relay about the new token.
                    RelaySetup.turnOn(app, store)
                    PortalPairing.create(app, store).also {
                        // Pair a device is the "Your Other Devices" flow (Linked devices › Pair › tier, owner 8 Nov 2026).
                        cc.bridgit.app.link.PortalTiers(app).set(cc.bridgit.app.link.PortalTiers.viewer(it.agent.id), cc.bridgit.app.link.PortalTiers.Entry(cc.bridgit.app.link.LinkTier.OWN))
                        cc.bridgit.app.BridgeService.running?.relay?.wake()
                    }
                }
            }
            busy = false
            b.addDevice.go.isEnabled = true; b.addDevice.go.setText(R.string.linkpair_show_code)
            result.onSuccess { showPortalLink(it) }.onFailure { showError(it) }
        }
    }

    private fun showPortalLink(p: PortalPairing.Pairing) {
        val phone = PortalPairing.phoneName(app)
        sheet(p.link, null, null, null, getString(R.string.portal_qr_desc, p.agent.name), getString(R.string.portal_title),
            share = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.portal_share_subject, phone))
                .putExtra(Intent.EXTRA_TEXT, getString(R.string.portal_share_text, phone, PortalData.PAIRING_PAGE, p.link)),
            copied = R.string.portal_copied, portalId = p.agent.id)
    }

    // ---- Make this phone a satellite of a hub ----

    /** Turns the listener on if needed (the hub reaches this phone through it), then shows the one-time QR code and link. */
    private fun makeSatelliteLink(name: String) {
        if (making?.isActive == true) return
        making = lifecycleScope.launch {
            if (app.listener.value.boundTo.isEmpty()) {
                Toast.makeText(this@PairDeviceActivity, R.string.satellite_making, Toast.LENGTH_SHORT).show()
                app.settings.listenerEnabled = true
                runCatching { cc.bridgit.app.BridgeService.start(this@PairDeviceActivity) }
                withTimeoutOrNull(20_000) { while (app.listener.value.boundTo.isEmpty()) delay(300) }
            }
            val since = System.currentTimeMillis()
            runCatching { app.hubLinks.make(name) }
                .onSuccess { link ->
                    sheet(link, R.string.satellite_qr_title, R.string.satellite_link_ready, R.string.satellite_qr_used,
                        getString(R.string.satellite_qr_title), "Bridgit satellite link",
                        share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), copied = R.string.satellite_copied,
                        claimedSince = since)
                }
                .onFailure { MaterialAlertDialogBuilder(this@PairDeviceActivity).setTitle(R.string.satellite_make)
                    .setMessage(it.message ?: it.javaClass.simpleName).setPositiveButton(android.R.string.ok, null).show() }
        }
    }

    // ---- Add a satellite to this phone ----

    /** A tapped bridgit://satellite#… link (sent from the spare phone), forwarded by SatelliteActivity. */
    private fun askAboutSatellite(i: Intent?) {
        val link = i?.dataString?.takeIf { it.startsWith(SatelliteLink.PREFIX) } ?: return
        i.data = null
        val name = runCatching { SatelliteLink.parse(link).name }.getOrNull()
            ?: run { Toast.makeText(this, R.string.satellite_not_link, Toast.LENGTH_SHORT).show(); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.satellite_add).setMessage(name)
            .setPositiveButton(R.string.satellite_add) { _, _ -> addSatellite(link) }.setNegativeButton(R.string.cancel, null).show()
    }


    private fun addSatellite(link: String) {
        Toast.makeText(this, R.string.satellite_adding, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SatelliteHub.add(app, link) } }
            r.onSuccess {
                Toast.makeText(this@PairDeviceActivity, getString(R.string.satellite_added, it.name), Toast.LENGTH_LONG).show()
                finish() // back to the map, where the new satellite now hangs under this phone (tap it to pick its agents)
            }.onFailure { MaterialAlertDialogBuilder(this@PairDeviceActivity).setTitle(R.string.satellite_add)
                .setMessage(it.message ?: it.javaClass.simpleName).setPositiveButton(android.R.string.ok, null).show() }
        }
    }

    // ---- The QR sheet: code, Copy, Share and Done ----

    /**
     * [claimedSince]: a satellite link made at that time; the sheet turns into "Connected" when a hub claims it.
     * [portalId]: the Portal pairing this link is for; the sheet turns into "Connected" on the portal's first call.
     */
    private fun sheet(link: String, title: Int?, body: Int?, singleUse: Int?, qrDesc: String, clipLabel: String, share: Intent, copied: Int,
                      claimedSince: Long? = null, portalId: String? = null) {
        val s = PortalQrSheetBinding.inflate(layoutInflater)
        val size = (264 * resources.displayMetrics.density).toInt()
        title?.let { s.title.setText(it) }
        body?.let { s.body.setText(it) }
        singleUse?.let { s.singleUse.setText(it) }
        s.qr.setImageBitmap(cc.bridgit.app.ui.common.qrBitmap(this, link, size))
        s.qr.contentDescription = qrDesc
        val dialog = BottomSheetDialog(this)
        if (!BuildConfig.DEBUG) cc.bridgit.app.ui.common.ScreenshotProtection.setSecure(dialog.window)
        s.copy.setOnClickListener {
            val clip = ClipData.newPlainText(clipLabel, link)
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
            Toast.makeText(this, copied, Toast.LENGTH_SHORT).show()
        }
        s.share.setOnClickListener { startActivity(Intent.createChooser(share, null)) }
        s.done.setOnClickListener { dialog.dismiss() }
        if (claimedSince != null) {
            val watch = lifecycleScope.launch {
                val c = HubLinks.claims.filterNotNull().first { it.at >= claimedSince }
                showConnected(s, c.hubName)
                // Done now leads back to the map, where the hub shows up.
                s.doneConnected.setOnClickListener { dialog.dismiss(); finish() }
            }
            dialog.setOnDismissListener { watch.cancel() }
        }
        if (portalId != null) {
            val watch = lifecycleScope.launch {
                PortalPairing.awaitFirstCall(app, portalId)
                // The portal names itself on its calls; give it a moment to arrive before falling back.
                val name = withTimeoutOrNull(3_000) {
                    var n = PortalPairing.clientName(app, portalId)
                    while (n == null) { delay(250); n = PortalPairing.clientName(app, portalId) }
                    n
                }
                showPortalConnected(s, name)
                // Done now leads back to the map, where the portal shows up.
                s.doneConnected.setOnClickListener { dialog.dismiss(); finish() }
            }
            dialog.setOnDismissListener { watch.cancel() }
        }
        dialog.setContentView(s.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private fun showConnected(s: PortalQrSheetBinding, hubName: String?) {
        val hub = hubName ?: getString(R.string.satellite_connected_hub)
        s.title.setText(R.string.satellite_connected_title)
        s.body.text = getString(R.string.satellite_connected_body, hub)
        s.qr.isVisible = false
        s.singleUse.isVisible = false
        s.copy.isVisible = false
        s.share.isVisible = false
        s.connected.isVisible = true
        s.connectedText.text = getString(R.string.satellite_connected_text, hub)
        s.done.isVisible = false
        s.doneConnected.isVisible = true
    }

    private fun showPortalConnected(s: PortalQrSheetBinding, name: String?) {
        val who = name ?: getString(R.string.portal_connected_someone)
        val noun = cc.bridgit.app.platform.DeviceType.of(this).noun
        s.title.setText(R.string.satellite_connected_title)
        s.body.text = getString(R.string.portal_connected_body, who, noun)
        s.qr.isVisible = false
        s.singleUse.isVisible = false
        s.copy.isVisible = false
        s.share.isVisible = false
        s.connected.isVisible = true
        s.connectedText.text = getString(R.string.portal_connected_text)
        s.done.isVisible = false
        s.doneConnected.isVisible = true
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Opens straight into "Make this phone a satellite of a hub", with its QR code and link. */
        const val EXTRA_JOIN_SATELLITE = "join_satellite"
    }
}
