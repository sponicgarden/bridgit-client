package cc.bridgit.app.ui.more

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.capability.granted
import cc.bridgit.app.databinding.FeaturesRowBinding
import cc.bridgit.app.databinding.FragmentFeaturesBinding
import cc.bridgit.app.ui.home.PermissionAsker
import cc.bridgit.app.ui.home.UiActions

/**
 * Feature enablement (UI-18, ui-spec 5.8): one switch per built capability, read-only since A0117 (each row opens
 * this device's permissions on the Agents and Linked Devices tab, the one place they change),
 * grouped, with a readiness line when something is missing. Unbuilt
 * capabilities are simply absent from the registry, so they never show.
 * Max Agentic Control sits on top (moved from Home, 2 Nov 2026); Home's quick switches were
 * a subset of the switches here.
 */
class FeaturesFragment : Fragment() {
    companion object { const val ARG_CAPABILITY = "capability" }
    private val focusedCapability get() = arguments?.getString(ARG_CAPABILITY)
    private var _b: FragmentFeaturesBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private val perms = PermissionAsker(this) { dirty.update { it + 1 } }
    private val dirty = MutableStateFlow(0)
    private val onChange: () -> Unit = { dirty.update { it + 1 } }
    private val rows = mutableListOf<Pair<Capability, FeaturesRowBinding>>()
    private val powers by lazy { cc.bridgit.app.power.PowerAccess(app) }
    private val powerSteps by lazy { powers.steps() }
    private var maxPower: com.google.android.material.button.MaterialButton? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentFeaturesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.toolbar.updatePadding(top = bars.top)
            b.scroll.updatePadding(bottom = bars.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        build()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.listener.collect { bind() } }
                launch { dirty.collect { bind(); delay(200) } }
            }
        }
    }

    override fun onStart() { super.onStart(); app.settings.addListener(onChange) }
    override fun onResume() { super.onResume(); bind() }
    override fun onStop() { app.settings.removeListener(onChange); super.onStop() }
    override fun onDestroyView() { super.onDestroyView(); rows.clear(); maxPower = null; _b = null }

    private fun build() {
        b.list.removeAllViews()
        rows.clear()
        val focused = focusedCapability?.let { app.registry.byId(it) }
        if (focused != null) b.toolbar.title = focused.title
        if (focused == null) {
            // Max Agentic Control (moved from Home): turns every switch and setting on, with Undo.
            maxPower = com.google.android.material.button.MaterialButton(requireContext()).apply {
                setOnClickListener {
                    findNavController().navigate(R.id.powers, androidx.core.os.bundleOf(cc.bridgit.app.ui.power.PowersFragment.ARG_AUTO to true))
                }
                val d = resources.displayMetrics.density
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (16 * d).toInt(); marginEnd = (16 * d).toInt() }
            }.also { b.list.addView(it) }
            b.list.addView(compactButton(getString(R.string.files_android_access)) {
                startActivity(android.content.Intent(requireContext(), cc.bridgit.app.planned.OwnerToolsActivity::class.java))
            })
            b.list.addView(compactButton(getString(R.string.listening_button)) { openWakeWords() })
        }
        val groups = app.registry.switchable().filter { focused == null || it.id == focused.id }.groupBy { it.group }
        for ((group, modules) in groups) {
            val header = layoutInflater.inflate(R.layout.features_header, b.list, false) as TextView
            header.text = groupTitle(group)
            b.list.addView(header)
            for (m in modules) {
                val r = FeaturesRowBinding.inflate(layoutInflater, b.list, false)
                r.title.text = m.title
                r.description.text = getString(m.descriptionRes)
                r.row.contentDescription = m.title + ", " + getString(m.descriptionRes)
                // A0117: the switch shows the state; it is changed on the Agents and Linked Devices tab (this device).
                r.toggle.isClickable = false; r.toggle.isFocusable = false
                r.row.contentDescription = r.row.contentDescription.toString() + ", " + getString(if (m.enabled) R.string.home_on else R.string.home_off)
                r.row.setOnClickListener { cc.bridgit.app.ui.agents.AgentsFragment.open(this, "device") }
                b.list.addView(r.root)
                rows += m to r
            }
        }
    }

    /** Navigation uses the same full-width row as More; the bulk action remains a button. */
    private fun compactButton(label: String, onClick: () -> Unit): View =
        cc.bridgit.app.databinding.MoreRowBinding.inflate(layoutInflater, b.list, false).apply {
            title.text = label
            icon.setImageResource(R.drawable.ic_settings)
            root.setOnClickListener { onClick() }
        }.root

    /** Max Agentic Control: on once every switch, every setting and every Android step this build declares is on. */
    private fun renderMaxPower() {
        val btn = maxPower ?: return
        val on = powers.everythingOn() && cc.bridgit.app.power.PowerPlan.status(powerSteps, powers::granted, emptySet()).values
            .all { it == cc.bridgit.app.power.PowerPlan.Status.ON }
        btn.isEnabled = !on
        btn.setText(if (on) R.string.power_max_on else R.string.power_max)
        btn.setIconResource(if (on) R.drawable.ic_check else R.drawable.ic_bolt)
        btn.contentDescription = if (on) getString(R.string.power_max_on) else getString(R.string.power_max) + ", " + getString(R.string.power_max_desc)
    }

    private fun groupTitle(group: String): String = when (group) {
        "capture" -> getString(R.string.features_group_capture)
        "photos" -> getString(R.string.features_group_photos)
        "talk" -> getString(R.string.features_group_talk)
        "where" -> getString(R.string.features_group_where)
        else -> group.replaceFirstChar { it.uppercase() }
    }

    private fun bind() {
        if (_b == null) return
        renderMaxPower()
        val ctx = requireContext()
        for ((m, r) in rows) {
            if (r.toggle.isChecked != m.enabled) r.toggle.isChecked = m.enabled
            val reason = m.blockedReason()
            r.readiness.isVisible = reason != null
            if (reason == null) continue
            val (text, action, onClick) = when {
                reason.startsWith("permission:") -> Triple(
                    getString(R.string.features_needs_permission, UiActions.permissionWord(ctx, reason.removePrefix("permission:"))),
                    getString(R.string.features_grant)) { perms.request(*UiActions.permissionsFor(m)) }
                reason == "open_app_once" && m.id == "video" -> Triple(
                    getString(R.string.video_setup_access), getString(R.string.video_enable_access)) {
                    cc.bridgit.app.ui.home.ListenerCard.resume(app)
                }
                reason == "open_app_once" -> Triple(getString(R.string.features_open_once),
                    getString(R.string.home_banner_fix_now)) { runCatching { BridgeService.start(ctx) }; Unit }
                reason == "notifications_off" -> Triple(getString(R.string.features_notifications_off),
                    getString(R.string.features_turn_on)) {
                    if (!ctx.granted(Manifest.permission.POST_NOTIFICATIONS)) perms.request(Manifest.permission.POST_NOTIFICATIONS)
                    else UiActions.openAppNotificationSettings(ctx)
                }
                reason == "no_hardware" -> Triple(getString(R.string.features_no_hardware), null, null)
                reason == "accessibility_paused" -> Triple(getString(R.string.features_accessibility_paused), null, null)
                reason == "listener_off" -> Triple(getString(R.string.features_listener_off), null, null)
                reason == "android_version" -> Triple(getString(R.string.features_needs_newer_android), null, null)
                reason == "model_missing" -> Triple(getString(R.string.wake_needs_model), getString(R.string.wake_set_up)) { openWakeWords() }
                reason == "no_agent" -> Triple(getString(R.string.sounds_needs_agent), getString(R.string.wake_set_up)) { openWakeWords() }
                reason == "no_wake_words" -> Triple(getString(R.string.wake_needs_words), getString(R.string.wake_set_up)) { openWakeWords() }
                else -> Triple(reason, null, null)
            }
            r.readinessText.text = text
            r.readinessAction.isVisible = action != null
            r.readinessAction.text = action
            r.readinessAction.setOnClickListener { onClick?.invoke() }
        }
    }

    private fun openWakeWords() = startActivity(android.content.Intent(requireContext(), cc.bridgit.app.listen.WakeWordsActivity::class.java))

}
