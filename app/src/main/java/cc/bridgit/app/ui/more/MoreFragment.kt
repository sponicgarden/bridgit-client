package cc.bridgit.app.ui.more

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.feedback.FeedbackStatusSync
import cc.bridgit.app.portal.LinkedDeviceCounts
import cc.bridgit.app.portal.PortalPairing
import cc.bridgit.app.portal.PortalLinks
import cc.bridgit.app.relay.RelayStore
import cc.bridgit.app.satellite.LinkPause
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BuildConfig
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.isVisible
import cc.bridgit.app.R
import cc.bridgit.app.ui.home.UiActions
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.databinding.FragmentMoreBinding
import cc.bridgit.app.databinding.MoreRowBinding
import cc.bridgit.app.feedback.IssueKind
import cc.bridgit.app.ui.feedback.openReport
import cc.bridgit.app.update.UpdateDialog

/** More (UI-07, ui-spec 5.13): Activity (D26), Feature enablement, Hardware access, Software access, Settings, the reporter (FBK-01), Check for updates (UPD-02), About. */
class MoreFragment : Fragment() {
    private var _b: FragmentMoreBinding? = null
    private val b get() = _b!!
    private data class MenuEntry(val binding: MoreRowBinding, val title: Int, val icon: Int, val open: () -> Unit)
    private val menuEntries = mutableListOf<MenuEntry>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentMoreBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        row(b.deviceMonitor, R.drawable.ic_nav_devices, R.string.nav_cameras) { findNavController().navigate(R.id.cameras) }
        row(b.openActivity, R.drawable.ic_nav_activity, R.string.more_activity) { findNavController().navigate(R.id.activity) }
        row(b.features, R.drawable.ic_toggle, R.string.more_features) { findNavController().navigate(R.id.features) }
        row(b.access, R.drawable.ic_shield, R.string.more_access) {
            findNavController().navigate(R.id.access, androidx.core.os.bundleOf(AccessFragment.ARG_KIND to AccessFragment.HARDWARE))
        }
        row(b.softwareAccess, R.drawable.ic_shield, R.string.more_software_access) {
            findNavController().navigate(R.id.access, androidx.core.os.bundleOf(AccessFragment.ARG_KIND to AccessFragment.SOFTWARE))
        }
        row(b.sshConnections, R.drawable.ic_computer, R.string.ssh_title) { findNavController().navigate(R.id.sshConnections) }
        row(b.networkDevices, R.drawable.ic_computer, R.string.network_devices_title) { findNavController().navigate(R.id.networkDevices) }
        row(b.tailscaleSetup, R.drawable.ic_shield, R.string.more_tailscale_setup) { findNavController().navigate(R.id.tailscaleSetup) }
        if (cc.bridgit.app.BuildConfig.PRIVATE_CONTROL) row(b.adbSetup, R.drawable.ic_settings, R.string.more_adb_setup) { findNavController().navigate(R.id.adbSetup) }
        else b.adbSetup.root.isVisible = false
        row(b.relay, R.drawable.ic_shield, R.string.more_relay) { startActivity(android.content.Intent(requireContext(), cc.bridgit.app.relay.RelayActivity::class.java)) }
        row(b.portal, R.drawable.ic_computer, R.string.more_portal) { startActivity(android.content.Intent(requireContext(), cc.bridgit.app.portal.PortalActivity::class.java)) }
        // Moved here from the Home three-dot menu (2 Nov 2026).
        row(b.controlCenter, R.drawable.ic_settings, R.string.more_control_center) { startActivity(android.content.Intent(requireContext(), cc.bridgit.app.platform.ControlCenterActivity::class.java)) }
        if (cc.bridgit.app.feedback.FeedbackUploader.configured)
            row(b.linkWebView, R.drawable.ic_computer, R.string.more_link_web_view) { startActivity(android.content.Intent(requireContext(), cc.bridgit.app.remoteview.LinkWebViewActivity::class.java)) }
        else b.linkWebView.root.isVisible = false
        row(b.settings, R.drawable.ic_settings, R.string.more_settings) { findNavController().navigate(R.id.settings) }
        row(b.reportBug, R.drawable.feedback_ic_bug, R.string.more_report_bug) {
            openReport(this, IssueKind.BUG, "More")
        }
        row(b.requestFeature, R.drawable.feedback_ic_lightbulb, R.string.more_request_feature) {
            openReport(this, IssueKind.FEATURE, "More")
        }
        row(b.checkUpdates, R.drawable.ic_download, R.string.more_check_updates) {
            UpdateDialog.checkForUpdates(requireActivity(), manual = true)
        }
        // The installed version, in quieter type right after the title (5 Nov 2026).
        b.checkUpdates.title.text = withVersion(getString(R.string.more_check_updates))
        row(b.automatedContext, R.drawable.ic_mode_custom, R.string.automated_context) { startActivity(android.content.Intent(requireContext(), cc.bridgit.app.context.ContextActivity::class.java)) }
        row(b.about, R.drawable.ic_info, R.string.more_about) { showAbout() }
        for ((row, kind) in listOf(b.reportBug to IssueKind.BUG, b.requestFeature to IssueKind.FEATURE)) {
            row.metrics.isVisible = true
            row.metrics.setOnClickListener { findNavController().navigate(R.id.myIssues, androidx.core.os.bundleOf("issueKind" to kind.id)) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                (requireActivity().application as BridgeApp).feedback.changed.collect { refreshSummaries() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { FeedbackStatusSync.freshen(requireActivity().application as BridgeApp) }
        row(b.whatCan, R.drawable.feedback_ic_lightbulb, R.string.more_what_can) { findNavController().navigate(R.id.whatCan) }
        b.search.doAfterTextChanged { renderSearch() }
        b.searchContainer.post {
            val binding = _b ?: return@post
            val parent = binding.searchContainer.parent as? View ?: return@post
            val bounds = android.graphics.Rect()
            binding.searchContainer.getHitRect(bounds)
            val minimum = (48 * resources.displayMetrics.density).toInt()
            val extra = ((minimum - bounds.height()) / 2).coerceAtLeast(0)
            bounds.inset(0, -extra)
            parent.touchDelegate = android.view.TouchDelegate(bounds, binding.search)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        renderSearch()
    }

    private fun renderSearch() {
        val query = b.search.text?.toString().orEmpty()
        val searching = query.isNotBlank()
        b.menuSections.isVisible = !searching
        b.searchResults.isVisible = searching
        b.searchResults.removeAllViews()
        b.searchEmpty.isVisible = false
        if (!searching) return
        fun result(title: CharSequence, icon: Int, open: () -> Unit) {
            val result = MoreRowBinding.inflate(layoutInflater, b.searchResults, true)
            result.title.text = title
            result.icon.setImageResource(icon)
            result.root.setOnClickListener {
                androidx.core.view.WindowCompat.getInsetsController(requireActivity().window, b.root)
                    .hide(WindowInsetsCompat.Type.ime())
                open()
            }
        }
        for (entry in menuEntries) {
            if (!entry.binding.root.isVisible) continue
            val aliases = when (entry.binding) {
                b.deviceMonitor -> "camera cameras monitor"
                b.portal -> "portal portals satellites linked phones devices"
                b.openActivity -> "history log requests"
                b.access -> "permissions hardware"
                b.softwareAccess -> "permissions software"
                b.automatedContext -> "automatic context location"
                else -> ""
            }
            if (MoreSearch.matches(query, getString(entry.title) + " " + aliases))
                result(entry.binding.title.text, entry.icon, entry.open)
        }
        fun setting(title: String, row: Int, provider: String? = null) {
            result(getString(R.string.more_search_setting, title), R.drawable.ic_settings) {
                findNavController().navigate(R.id.settings, androidx.core.os.bundleOf(MoreSettingsSearch.ARG_ROW to row, MoreSettingsSearch.ARG_PROVIDER to provider))
            }
        }
        for (entry in MoreSettingsSearch.entries) {
            val title = getString(entry.title)
            val aliases = resources.getResourceEntryName(entry.title).replace('_', ' ')
            if (MoreSearch.matches(query, "$title $aliases")) setting(title, entry.row)
        }
        for (provider in cc.bridgit.app.judgment.OwnKeys.PROVIDERS) {
            val title = getString(provider.label)
            if (MoreSearch.matches(query, "$title API key own AI keys")) setting(title, R.id.ownKeys, provider.id)
        }
        b.searchEmpty.isVisible = b.searchResults.childCount == 0
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch { refreshSummaries() }
    }

    private suspend fun refreshSummaries() {
        val app = requireActivity().application as BridgeApp
        val summaries = withContext(Dispatchers.IO) { listOf(IssueKind.BUG, IssueKind.FEATURE).map { app.feedback.summary(it) } }
        val bb = _b ?: return
        for ((row, counts) in listOf(bb.reportBug to summaries[0], bb.requestFeature to summaries[1])) {
            row.metrics.text = getString(R.string.more_issue_metrics, counts.first, counts.second)
            row.metrics.contentDescription = getString(R.string.more_issue_metrics_cd, row.title.text, counts.first, counts.second)
        }
        val hubInfo = app.hubLinks.hubs().associateBy { it.linkId }
        val counts = LinkedDeviceCounts.count(
            hubs = app.hubLinks.agents().map { a -> LinkedDeviceCounts.Link(a.id,
                hubInfo[a.id]?.name ?: a.name.removePrefix("Hub "), !LinkPause.hubsPaused && !a.paused) },
            viewing = PortalLinks(requireContext()).all().map { LinkedDeviceCounts.Link(it.device, it.name, true) },
            satellites = app.satellites.all().map { LinkedDeviceCounts.Link(it.id, it.name, !LinkPause.satellites) },
            viewers = PortalPairing.granted(app, RelayStore(app)).map { LinkedDeviceCounts.Link(it.id, it.name, !LinkPause.satellites && !it.paused) })
        bb.portal.title.setText(R.string.more_portal)
        bb.portal.metrics.apply {
            isVisible = true
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            text = linkedCounts(counts.first, counts.second)
        }
        bb.portal.root.contentDescription = getString(R.string.more_linked_metrics, counts.first, counts.second)
    }

    private fun row(r: MoreRowBinding, icon: Int, title: Int, onClick: () -> Unit) {
        menuEntries.add(MenuEntry(r, title, icon, onClick))
        r.icon.setImageResource(icon)
        r.title.setText(title)
        r.root.setOnClickListener { onClick() }
    }

    private fun linkedCounts(enabled: Int, disabled: Int): CharSequence {
        val success = com.google.android.material.color.MaterialColors.getColor(b.root, R.attr.colorSuccess)
        val muted = com.google.android.material.color.MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val out = android.text.SpannableStringBuilder()
        for ((count, color, icon) in listOf(Triple(enabled, success, R.drawable.ic_check_circle), Triple(disabled, muted, R.drawable.agents_ic_pause))) {
            if (out.isNotEmpty()) out.append("   ")
            val start = out.length
            out.append("● ").append(count.toString())
            val drawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(requireContext(), icon)!!.mutate()
            val size = (18 * resources.displayMetrics.density).toInt()
            drawable.setBounds(0, 0, size, size)
            androidx.core.graphics.drawable.DrawableCompat.setTint(drawable, color)
            out.setSpan(android.text.style.ImageSpan(drawable, android.text.style.DynamicDrawableSpan.ALIGN_CENTER), start, start + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(android.text.style.ForegroundColorSpan(color), start, out.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun withVersion(title: String): CharSequence {
        val version = cc.bridgit.app.ui.VersionStamp.label()
        val muted = com.google.android.material.color.MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        return android.text.SpannableStringBuilder(title).append("  ").apply {
            val start = length
            append(version)
            setSpan(android.text.style.ForegroundColorSpan(muted), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.TypefaceSpan("sans-serif-light"), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun showAbout() {
        val app = requireActivity().application as BridgeApp
        val commit = if (BuildConfig.DIRTY_BUILD) getString(R.string.more_dirty, BuildConfig.GIT_COMMIT) else BuildConfig.GIT_COMMIT
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.more_about_title)
            .setMessage(getString(R.string.more_about_body, app.appVersion(), BuildConfig.BUILD_TYPE, commit))
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.settings_privacy_policy) { _, _ -> UiActions.openPrivacyPolicy(requireContext()) }
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); menuEntries.clear(); _b = null }
}
