package cc.bridgit.app.ui.tailscale

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.R
import cc.bridgit.app.adb.AdbGrants
import cc.bridgit.app.core.TailscaleHelp
import cc.bridgit.app.databinding.AccessRowBinding
import cc.bridgit.app.databinding.FragmentTailscaleSetupBinding
import cc.bridgit.app.platform.TailscaleStatus
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import cc.bridgit.app.ui.home.UiActions
import cc.bridgit.app.update.TailscaleInstaller

/**
 * Tailscale setup: install, sign in and turn on, keep on, then the agent's computer.
 * Rows re-check every second while the screen is open, like ADB setup, so a step done in
 * Tailscale or Settings shows as done when the owner comes back. "Turn on" asks Tailscale
 * to connect by itself; with Bridgit's ADB link, "Keep it on" is one tap.
 */
class TailscaleSetupFragment : Fragment() {
    private var _b: FragmentTailscaleSetupBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private var lastSig: List<Any?>? = null
    private var connectJob: Job? = null
    private var working = false
    private var installJob: Job? = null
    /** Bridgit started the install, so Tailscale opens for sign-in as soon as it's on the phone. */
    private var openWhenInstalled = false
    /** Android's install box, waiting for this screen to be visible (Android won't show it from the background). */
    private var pendingConfirm: Intent? = null

    private class Step(val title: String, val how: String, val done: Boolean?, val button: String?, val action: () -> Unit)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentTailscaleSetupBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.scroll) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        b.sharePrompt.setOnClickListener { share(getString(R.string.ts_share_subject), cc.bridgit.app.ui.common.ShareText.prompt(getString(R.string.share_ts_heading), getString(R.string.share_ts_note), computerPrompt())) }
        b.copyPrompt.setOnClickListener {
            requireContext().getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.ts_share_subject), computerPrompt()))
            Toast.makeText(requireContext(), R.string.ts_prompt_copied, Toast.LENGTH_SHORT).show()
        }
        b.shareLink.setOnClickListener { share(getString(R.string.ts_share_subject), getString(R.string.ts_share_link_text)) }
        b.openRelay.setOnClickListener { startActivity(Intent(requireContext(), cc.bridgit.app.relay.RelayActivity::class.java)) }
        // From first run's "Install Tailscale": start straight away, once.
        if (savedInstanceState == null && arguments?.getBoolean(ARG_AUTO_INSTALL) == true && !TailscaleStatus.installed(requireContext()))
            installTailscale()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { refresh(); delay(1000) }
            }
        }
    }

    override fun onResume() { super.onResume(); showPendingConfirm() }

    private fun showPendingConfirm() {
        val confirm = pendingConfirm ?: return
        if (!isResumed) return
        pendingConfirm = null
        runCatching { startActivity(confirm) }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null; lastSig = null }

    private fun addresses(): List<String> = app.listener.value.boundTo.ifEmpty { BridgeService.tailscaleAddresses() }

    /** The phone's MagicDNS name (only known while the listener is bound) or its Tailscale IPv4. */
    private fun phoneHost(): String? = app.listener.value.magicDns?.trimEnd('.') ?: addresses().firstOrNull { !it.contains(':') }

    private fun computerPrompt() = TailscaleHelp.computerPrompt(phoneHost(), app.listener.value.port.takeIf { it > 0 } ?: app.settings.port, TailscaleHelp.tailnetName(app.listener.value.magicDns))

    private fun adbReady() = BuildConfig.PRIVATE_CONTROL && app.adb.paired

    private fun refresh() {
        val bb = _b ?: return
        val ctx = requireContext()
        val installed = TailscaleStatus.installed(ctx)
        val addrs = addresses()
        val on = addrs.isNotEmpty()
        val alwaysOn = TailscaleStatus.alwaysOn(ctx)
        val battery = TailscaleStatus.batteryExempt(ctx)
        val magic = app.listener.value.magicDns
        val sig = listOf(installed, addrs, alwaysOn, battery, magic, adbReady(), working, connectJob?.isActive, installJob?.isActive)
        if (sig == lastSig) return
        lastSig = sig
        if (installed && openWhenInstalled) {
            openWhenInstalled = false
            bb.state.apply { isVisible = true; setText(R.string.ts_install_done) }
            UiActions.openTailscale(ctx)
        }
        // Connected: the "connecting…" line is done with. Other messages (the Keep it on result) stay.
        if (on && connectJob != null) { connectJob?.cancel(); connectJob = null; bb.state.isVisible = false }

        val tailnet = TailscaleHelp.tailnetName(magic)
        val connectHow = when {
            on && magic != null && tailnet != null -> getString(R.string.ts_step_connect_done, magic.trimEnd('.').substringBefore('.'), tailnet)
            on -> getString(R.string.ts_step_connect_done_ip, addrs.first())
            else -> getString(R.string.ts_step_connect_how)
        }
        val steps = mutableListOf(
            Step(getString(R.string.ts_step_install),
                getString(if (TailscaleInstaller.available()) R.string.ts_step_install_how_direct else R.string.ts_step_install_how), installed,
                getString(R.string.ts_step_install_button).takeIf { installJob?.isActive != true }) {
                if (TailscaleInstaller.available()) installTailscale() else UiActions.openTailscale(ctx)
            },
            Step(getString(R.string.ts_step_connect), connectHow, if (installed) on else false,
                when {
                    !installed -> null
                    connectJob?.isActive == true -> getString(R.string.ts_step_open)
                    else -> getString(R.string.ts_step_turn_on)
                }) { if (connectJob?.isActive == true) UiActions.openTailscale(ctx) else turnOn() },
        )
        if (adbReady()) {
            val keep = installed && TailscaleStatus.keptOn(ctx)
            steps += Step(getString(R.string.ts_step_keep), getString(R.string.ts_step_keep_how_adb), if (installed) keep else null,
                if (installed && !working) getString(R.string.ts_step_keep_adb_button) else null) { keepOnWithAdb() }
        } else {
            steps += Step(getString(R.string.ts_step_keep), getString(R.string.ts_step_keep_how), if (installed) alwaysOn else null,
                if (installed) getString(R.string.ts_step_keep_vpn_button) else null) { open(Intent(Settings.ACTION_VPN_SETTINGS)) }
            steps += Step(getString(R.string.ts_step_battery), getString(R.string.ts_step_battery_how), if (installed) battery else null,
                if (installed) getString(R.string.ts_step_battery_button) else null) {
                    openTailscaleBattery()
                }
        }
        fill(bb.steps, steps)
        bb.computerText.text = if (tailnet != null) getString(R.string.ts_computer_text_net, tailnet) else getString(R.string.ts_computer_text)
    }

    /** Asks Tailscale to connect; if no address shows up within 20 s, says what to do and offers to open it. */
    private fun turnOn() {
        val ctx = requireContext()
        TailscaleStatus.connect(ctx)
        _b?.state?.apply { isVisible = true; setText(R.string.ts_connecting) }
        connectJob?.cancel()
        connectJob = viewLifecycleOwner.lifecycleScope.launch {
            repeat(20) {
                delay(1000)
                if (addresses().isNotEmpty()) { lastSig = null; refresh(); return@launch }
            }
            _b?.state?.setText(R.string.ts_connect_failed)
            lastSig = null; refresh()
        }
        lastSig = null; refresh()
    }

    /** Downloads Tailscale from Tailscale's own site and installs it (private build), showing progress here. */
    private fun installTailscale() {
        if (installJob?.isActive == true) return
        _b?.state?.apply { isVisible = true; setText(R.string.ts_install_starting) }
        installJob = viewLifecycleOwner.lifecycleScope.launch {
            val r = TailscaleInstaller.install(app) { pct ->
                val text = when {
                    pct > 100 -> getString(R.string.ts_install_installing)
                    pct < 0 -> getString(R.string.ts_install_downloading_unknown)
                    else -> getString(R.string.ts_install_downloading, pct)
                }
                view?.post { _b?.state?.apply { isVisible = true; this.text = text } }
            }
            val st = _b?.state
            when (r) {
                is TailscaleInstaller.Result.Installed -> { openWhenInstalled = true; st?.setText(R.string.ts_install_done) }
                is TailscaleInstaller.Result.AskedAndroid -> {
                    openWhenInstalled = true; st?.setText(R.string.ts_install_confirm)
                    pendingConfirm = r.confirm; showPendingConfirm()
                }
                is TailscaleInstaller.Result.NeedsUnknownSources -> {
                    st?.setText(R.string.ts_install_unknown_sources)
                    open(r.settings)
                }
                is TailscaleInstaller.Result.Failed -> st?.text = getString(R.string.ts_install_failed, r.why)
            }
            st?.isVisible = true
            lastSig = null; refresh()
        }
        lastSig = null; refresh()
    }

    private fun keepOnWithAdb() {
        working = true; lastSig = null; refresh()
        _b?.state?.apply { isVisible = true; setText(R.string.ts_keep_working) }
        viewLifecycleOwner.lifecycleScope.launch {
            val text = runCatching {
                val lines = withContext(Dispatchers.IO) { AdbGrants.grantAll(app, setOf("tailscale")) }
                getString(R.string.ts_keep_result, lines.count { it.startsWith("ok") }, lines.size)
            }.getOrElse { getString(R.string.ts_keep_failed, it.message.orEmpty()) }
            working = false
            _b?.state?.apply { isVisible = true; this.text = text }
            lastSig = null; refresh()
        }
    }

    private fun share(subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
        runCatching { startActivity(Intent.createChooser(send, subject)) }
    }

    // Settings opens in its own task, so tapping Bridgit afterwards shows Bridgit, not the Settings page on top of it.
    /**
     * Tailscale's own battery page (Android 14+ and Samsung), where "Unrestricted" sits behind the words
     * "Allow background usage". Older phones don't have it, so they get Tailscale's App info page.
     */
    private fun openTailscaleBattery() {
        val pkg = Uri.parse("package:" + TailscaleHelp.PACKAGE)
        val battery = Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", pkg)
        val ok = runCatching { startActivity(battery.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        if (!ok) open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
    }

    private fun open(i: Intent) {
        runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(requireContext(), "Settings didn't open", Toast.LENGTH_SHORT).show() }
    }

    private fun fill(parent: LinearLayout, steps: List<Step>) {
        parent.removeAllViews()
        val ctx = parent.context
        for (s in steps) {
            val r = AccessRowBinding.inflate(layoutInflater, parent, false)
            r.title.text = s.title
            r.unlocks.text = s.how
            r.status.isVisible = s.done != null
            r.status.setText(if (s.done == true) R.string.ts_done else R.string.ts_not_done)
            val (bg, fg) = if (s.done == true) R.attr.colorSuccessContainer to R.attr.colorOnSuccessContainer
                else com.google.android.material.R.attr.colorSurfaceContainerHigh to com.google.android.material.R.attr.colorOnSurfaceVariant
            r.status.backgroundTintList = ColorStateList.valueOf(ctx.themeColor(bg))
            r.status.setTextColor(ctx.themeColor(fg))
            if (s.done == true) ContextCompat.getDrawable(ctx, R.drawable.ic_check)?.mutate()?.let { d ->
                d.setBounds(0, 0, ctx.dp(14), ctx.dp(14)); d.setTint(ctx.themeColor(fg)); r.status.setCompoundDrawablesRelative(d, null, null, null)
            }
            r.action.isVisible = s.button != null && s.done != true
            r.action.text = s.button
            r.action.setOnClickListener { s.action() }
            parent.addView(r.root)
        }
    }

    companion object {
        /** Boolean: start installing Tailscale as soon as the screen opens (first run's "Install Tailscale"). */
        const val ARG_AUTO_INSTALL = "autoInstall"
    }
}
