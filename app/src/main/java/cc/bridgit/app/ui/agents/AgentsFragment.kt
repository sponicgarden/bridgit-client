package cc.bridgit.app.ui.agents

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.AgentRegistry
import cc.bridgit.app.core.Connection
import cc.bridgit.app.BridgeService
import cc.bridgit.app.relay.RelayStore
import cc.bridgit.app.ui.home.ListenerCard
import cc.bridgit.app.databinding.AgentsRowBinding
import cc.bridgit.app.databinding.FragmentAgentsBinding
import cc.bridgit.app.ui.common.modelText
import cc.bridgit.app.ui.common.Fmt
import cc.bridgit.app.ui.common.themeColor
import cc.bridgit.app.ui.feedback.handleFeedbackMenu

/**
 * Agents list (UI-12, ui-spec 5.3): the Control panel (pause every agent, pause external calls), then one card per
 * agent with its own status line and pause button, Add agent FAB, empty state.
 */
class AgentsFragment : Fragment() {
    private var _b: FragmentAgentsBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private lateinit var adapter: Adapter
    private var rows: List<Row> = emptyList()
    private var hubPanel: HubAgentsPanel? = null
    /** Open permission panels: "agent:<id>", "device" (this device) or "linked:<id>". */
    private val expanded = mutableSetOf<String>()
    private val deviceCards = LinkedHashMap<String, cc.bridgit.app.databinding.LinkedDeviceRowBinding>()
    private val access = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { renderDevices() }
    private val settingsChanged: () -> Unit = { view?.post { if (_b != null) refresh() } } // refresh also re-checks the Max banner

    /** Registry changes arrive per request (recordUse); coalesce them so a busy agent doesn't flood the log. */
    private val refreshSoon = Runnable { if (_b != null) refresh() }
    private val agentsChanged: () -> Unit = { view?.let { v -> v.removeCallbacks(refreshSoon); v.postDelayed(refreshSoon, 500) } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAgentsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // No title row (owner, 4 Nov 2026): the list starts just under the status bar, with the floating ⋮ menu.
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            b.scroll.updatePadding(top = top + (8 * resources.displayMetrics.density).toInt())
            insets
        }
        b.toolbar.inflateMenu(R.menu.feedback_overflow) // Report a bug / Request a feature (FBK-01)
        b.toolbar.setOnMenuItemClickListener { handleFeedbackMenu(it, "Control agents") }
        adapter = Adapter(
            onClick = { a -> findNavController().navigate(R.id.agentDetail, bundleOf("agentId" to a.id)) },
            onPause = { a -> togglePause(a) },
            expanded = { "agent:${it.id}" in expanded },
            onExpand = { a -> toggleExpanded("agent:${a.id}"); adapter.notifyItemRangeChanged(0, adapter.itemCount) },
            panel = { a -> AgentPermissionsDialog.content(this, a.id, changed = { adapter.notifyItemRangeChanged(0, adapter.itemCount); renderDevices() }) },
            summary = { a -> PermissionHome.summary(AgentPermissionsDialog.agentPerms(app, a)) },
        )
        b.list.adapter = adapter
        b.scroll.setOnScrollChangeListener { _: androidx.core.widget.NestedScrollView, _: Int, y: Int, _: Int, _: Int ->
            b.toolbar.isVisible = y < (24 * resources.displayMetrics.density) // the ⋮ would sit over scrolled cards
        }
        // Max Agentic Control off: the same confirm-then-guided-pass as the Settings switch.
        b.maxBannerEnable.setOnClickListener {
            cc.bridgit.app.ui.power.PowersFragment.confirmMaximum(this, null) { renderMaxBanner() }
        }
        hubPanel = HubAgentsPanel(this, app, b.root as ViewGroup)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { cc.bridgit.app.portal.PortalLinks(app).selected.collect { syncHub() } }
        }
        b.addAgent.setOnClickListener { addAgent() }
        b.activityRow.setOnClickListener { findNavController().navigate(R.id.activity) }
        b.portalRow.setOnClickListener { startActivity(android.content.Intent(requireContext(), cc.bridgit.app.portal.PortalActivity::class.java)) }

        b.pauseAgents.title.setText(R.string.agents_pause_all)
        b.pauseAgents.root.setOnClickListener {
            if (ListenerCard.isPaused(app)) ListenerCard.resume(app) else ListenerCard.pause(app)
            refresh()
        }
        cc.bridgit.app.ui.home.UiActions.asSwitch(b.pauseAgents.root) { ListenerCard.isPaused(app) }
        b.pauseExternal.title.setText(R.string.agents_pause_external)
        // Shown as "External calls" on/off: on (calls allowed) is the default; the stored setting is the pause.
        cc.bridgit.app.ui.home.UiActions.asSwitch(b.pauseExternal.root) { !app.settings.externalCallsPaused }
        b.pauseExternal.root.setOnClickListener {
            val on = !app.settings.externalCallsPaused
            app.settings.externalCallsPaused = on
            app.log.system("audit", "setting", getString(if (on) R.string.agents_pause_external_on_log else R.string.agents_pause_external_off_log))
            refresh()
        }

        app.agents.addListener(agentsChanged)
        refresh()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // New log entries change "N today"; a slow tick keeps "seen 2m ago" and the dot fresh.
                launch { app.log.latestId.collectLatest { delay(500); refresh() } }
                launch { while (true) { delay(30_000); refresh() } }
                launch { app.listener.collect { renderControls(); adapter.status = status(); } }
            }
        }
    }

    private fun addAgent() {
        if (app.agents.all().count { it.connection != cc.bridgit.app.core.Connection.APPLET } >= AgentRegistry.MAX_AGENTS) {
            Snackbar.make(b.root, R.string.agents_full, Snackbar.LENGTH_LONG).show()
            return
        }
        findNavController().navigate(R.id.addAgent)
    }

    private fun togglePause(a: Agent) {
        val paused = !a.paused
        app.agents.update(a.id) { it.copy(paused = paused) } ?: return
        app.log.system("audit", "agent", getString(if (paused) R.string.agents_paused_log else R.string.agents_resumed_log, a.name))
        Snackbar.make(b.root, getString(if (paused) R.string.agents_paused_snack else R.string.agents_resumed_snack, a.name), Snackbar.LENGTH_SHORT).show()
        refresh()
    }

    override fun onStart() { super.onStart(); app.settings.addListener(settingsChanged) }
    // Android's own screens (overlay, permissions) return to this same view, so check again here.
    override fun onResume() {
        super.onResume(); renderMaxBanner(); syncHub()
        pendingFocus?.let { focus ->
            expanded += focus; pendingFocus = null; refresh()
            // Bring the opened panel's section into view.
            val section = if (focus.startsWith("agent:")) b.agentsPanel else b.devicesPanel
            b.scroll.post { if (_b != null) b.scroll.smoothScrollTo(0, section.top) }
        }
        syncProfile()
    }

    private fun toggleExpanded(key: String) { if (!expanded.remove(key)) expanded += key }

    /** The profile goes up to (or comes down from) the team backend with its inherited defaults; offline it stays as it is here. */
    private fun syncProfile() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { cc.bridgit.app.platform.DeviceProfile(app).sync() } }
            renderDevices()
        }
    }

    private fun purposeTitle(id: String): String = when (id) {
        "primary" -> getString(R.string.purpose_primary)
        "dedicated" -> getString(R.string.purpose_dedicated)
        "network_controller" -> getString(R.string.purpose_network_controller)
        "monitor" -> getString(R.string.purpose_monitor)
        "shared" -> getString(R.string.purpose_shared)
        else -> id.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    private fun typeTitle(type: String): String = cc.bridgit.app.platform.DeviceType.parse(type)?.title ?: getString(R.string.device_type_other)

    /** This device (profile and its own switches), then every linked device with its permissions here (A0116). */
    private fun renderDevices() {
        if (_b == null) return
        val ctx = requireContext()
        val profile = cc.bridgit.app.platform.DeviceProfile(app).get()
        val linked = LinkedDevices.all(app)
        val keys = listOf("device") + linked.map { "linked:${it.agent.id}" }
        deviceCards.keys.filter { it !in keys }.forEach { k -> deviceCards.remove(k)?.let { b.devices.removeView(it.root) } }
        keys.forEachIndexed { i, key ->
            val card = deviceCards.getOrPut(key) {
                cc.bridgit.app.databinding.LinkedDeviceRowBinding.inflate(layoutInflater, b.devices, false).also { c ->
                    b.devices.addView(c.root, i.coerceAtMost(b.devices.childCount))
                    c.permissions.root.setOnClickListener { toggleExpanded(key); renderDevices() }
                    if (key == "device") {
                        c.edit.isVisible = true
                        c.edit.setOnClickListener { editProfile() }
                        c.header.setOnClickListener { editProfile() }
                    }
                }
            }
            val perms: List<PermissionHome.Perm>
            if (key == "device") {
                card.name.text = cc.bridgit.app.platform.DeviceName.of(app)
                card.line2.text = listOf(getString(R.string.agents_this_device), typeTitle(profile.deviceType), profile.model).joinToString(" · ")
                card.purposes.text = profile.purposes.map(::purposeTitle).joinToString(", ").ifEmpty { getString(R.string.device_profile_no_purpose) }
                card.icon.setImageResource(R.drawable.ic_nav_device)
                perms = AgentPermissionsDialog.devicePerms(app)
            } else {
                val l = linked.first { "linked:${it.agent.id}" == key }
                card.name.text = l.name
                card.line2.text = getString(when (l.kind) {
                    LinkedDevices.Kind.PORTAL -> R.string.linked_kind_portal
                    LinkedDevices.Kind.HUB -> R.string.linked_kind_hub
                    LinkedDevices.Kind.SATELLITE -> R.string.linked_kind_satellite
                })
                card.purposes.isVisible = false
                card.icon.setImageResource(if (l.kind == LinkedDevices.Kind.PORTAL) R.drawable.ic_computer else R.drawable.ic_nav_device)
                perms = AgentPermissionsDialog.linkedPerms(app, l)
            }
            val s = PermissionHome.summary(perms)
            val open = key in expanded
            card.permissions.count.text = getString(R.string.agents_group_count, s.granted, s.total)
            card.permissions.chevron.rotation = if (open) 180f else 0f
            card.permissions.root.contentDescription = getString(R.string.perm_pointer, s.granted, s.total) + if (open) ", expanded" else ", collapsed"
            card.header.contentDescription = listOf(card.name.text, card.line2.text, card.purposes.text.takeIf { card.purposes.isVisible }).filterNotNull().joinToString(", ")
            card.permissionsPanel.isVisible = open
            if (open) {
                val panel = card.permissionsPanel.getChildAt(0)
                if (panel == null) card.permissionsPanel.addView(
                    if (key == "device") AgentPermissionsDialog.device(this, { access.launch(it) }) { renderDevices() }
                    else AgentPermissionsDialog.linked(this, key.removePrefix("linked:")) { renderDevices() })
                else (panel.tag as? Runnable)?.run()
            } else card.permissionsPanel.removeAllViews()
        }
    }

    /** The profile editor: type (one), purposes (one or more), model. The same rules as the API and the backend. */
    private fun editProfile() {
        val ctx = requireContext()
        val store = cc.bridgit.app.platform.DeviceProfile(app)
        val p = store.get()
        val d = resources.displayMetrics.density
        fun label(text: Int) = android.widget.TextView(ctx).apply {
            setText(text)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setPadding(0, (16 * d).toInt(), 0, (4 * d).toInt())
        }
        fun chip(text: String, checked: Boolean) = com.google.android.material.chip.Chip(ctx).apply {
            setChipDrawable(com.google.android.material.chip.ChipDrawable.createFromAttributes(ctx, null, 0,
                com.google.android.material.R.style.Widget_Material3_Chip_Filter))
            this.text = text; isCheckable = true; isChecked = checked
        }
        val types = com.google.android.material.chip.ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
        val typeIds = cc.bridgit.app.platform.DeviceProfile.Rules.TYPES
        typeIds.forEach { t -> types.addView(chip(typeTitle(t), t == p.deviceType).apply { tag = t }) }
        val purposes = com.google.android.material.chip.ChipGroup(ctx)
        (cc.bridgit.app.platform.DeviceProfile.Rules.PURPOSES + p.purposes).distinct().forEach { id ->
            purposes.addView(chip(purposeTitle(id), id in p.purposes).apply { tag = id })
        }
        val model = com.google.android.material.textfield.TextInputEditText(ctx).apply {
            setText(p.model); isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(cc.bridgit.app.platform.DeviceProfile.Rules.MODEL_CHARS))
        }
        val modelBox = com.google.android.material.textfield.TextInputLayout(ctx).apply { addView(model) }
        val body = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), 0, (24 * d).toInt(), 0)
            addView(label(R.string.device_profile_purposes)); addView(purposes)
            addView(label(R.string.device_profile_type)); addView(types)
            addView(label(R.string.device_profile_model)); addView(modelBox)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.device_profile_title)
            .setView(androidx.core.widget.NestedScrollView(ctx).apply { addView(body) })
            .setPositiveButton(R.string.device_profile_save) { _, _ ->
                val chosenType = (0 until types.childCount).map { types.getChildAt(it) as com.google.android.material.chip.Chip }.firstOrNull { it.isChecked }?.tag as? String
                val chosen = (0 until purposes.childCount).map { purposes.getChildAt(it) as com.google.android.material.chip.Chip }.filter { it.isChecked }.map { it.tag as String }
                try {
                    store.update(chosen, chosenType, model.text?.toString().orEmpty(), by = "Owner")
                } catch (e: cc.bridgit.app.api.ApiError) {
                    Snackbar.make(b.root, e.message ?: "", Snackbar.LENGTH_LONG).show(); return@setPositiveButton
                }
                renderDevices(); syncProfile()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** Switched to the hub: its agents cover this phone's list, and Add agent goes away (agents are added on the hub). */
    private fun syncHub() { val onHub = hubPanel?.sync() == true; _b?.addAgent?.isVisible = !onHub; _b?.toolbar?.isVisible = !onHub }

    /** Pinned at the top while Max Agentic Control is off; the same test as its switch in Settings. */
    private fun renderMaxBanner() {
        if (_b == null) return
        val missing = cc.bridgit.app.power.PowerAccess(app).missingAccess()
        b.maxBanner.isVisible = MaxControlBanner.visible(missing.complete)
        // Without the banner the Agents heading is the first row, under the floating ⋮ menu: keep + Add agent clear of it.
        val d = resources.displayMetrics.density
        b.agentsHeader.updatePadding(right = ((if (b.maxBanner.isVisible) 16 else 56) * d).toInt())
        if (missing.complete) return
        val count = missing.permissions.size + missing.settings.size
        b.maxBannerBody.text = resources.getQuantityString(R.plurals.max_missing_count, count, count)
    }
    override fun onStop() { app.settings.removeListener(settingsChanged); super.onStop() }

    /** What every card needs to know about the phone: everyone paused, Tailscale up, external calls paused. */
    private fun status() = Status(
        allPaused = ListenerCard.isPaused(app),
        tailscaleUp = app.listener.value.boundTo.isNotEmpty() || BridgeService.tailscaleAddresses().isNotEmpty(),
        externalPaused = app.settings.externalCallsPaused,
    )

    private fun renderControls() {
        if (_b == null) return
        val hasAgents = cc.bridgit.app.ui.home.HomeStates.controlAgents(app).isNotEmpty()
        val s = app.listener.value
        val paused = ListenerCard.isPaused(app)
        b.pauseAgents.root.isVisible = hasAgents
        b.pauseAgents.toggle.isChecked = paused
        b.pauseAgents.value.text = when {
            paused -> getString(R.string.agents_pause_all_on)
            s.bindError != null -> getString(R.string.agents_pause_all_error, if (s.port > 0) s.port else app.settings.port)
            s.running && s.boundTo.isEmpty() -> getString(R.string.agents_pause_all_tailscale_down)
            else -> getString(R.string.agents_pause_all_off)
        }
        // Say something only when it needs attention; the default state is the switch itself.
        b.pauseAgents.value.isVisible = paused || s.bindError != null || (s.running && s.boundTo.isEmpty())
        val ext = app.settings.externalCallsPaused
        b.pauseExternal.toggle.isChecked = !ext
        b.pauseExternal.value.setText(R.string.agents_pause_external_on)
        b.pauseExternal.value.isVisible = ext
    }

    private fun refresh() {
        renderControls()
        renderMaxBanner()
        renderTools()
        adapter.status = status()
        // Portal pairings only view the phone: one row links to More › Linked devices instead of agent cards.
        renderDevices()
        val agents = cc.bridgit.app.ui.home.HomeStates.controlAgents(app)
        // Show the cards straight away with the last known counts, then fill in today's numbers.
        val known = rows.associate { it.agent.id to it.today }
        show(agents.map { Row(it, known[it.id] ?: 0, System.currentTimeMillis()) })
        viewLifecycleOwner.lifecycleScope.launch {
            val counted = withContext(Dispatchers.IO) {
                agents.map { Row(it, AgentStats.requestsToday(app.log, it.id), System.currentTimeMillis()) }
            }
            if (_b != null) show(counted)
        }
    }

    private fun show(list: List<Row>) {
        rows = list
        adapter.submitList(list)
        val empty = list.isEmpty()
        b.empty.isVisible = empty
        b.list.isVisible = !empty
        // Switched to the hub, agents are added there: no Add agent over its list.
        b.addAgent.isVisible = cc.bridgit.app.portal.PortalLinks(app).selectedHub() == null
    }

    private var lastCalls: Map<ExternalTools.Id, ExternalTools.LastCall> = emptyMap()

    /** External tools: drawn at once from what's known, then again once the outbound log has been read. */
    private fun renderTools() {
        drawTools()
        viewLifecycleOwner.lifecycleScope.launch {
            val calls = withContext(Dispatchers.IO) {
                runCatching { ExternalTools.lastCalls(app.outbound.query(cc.bridgit.app.outbound.OutboundQuery(limit = 1000), light = true)) }
                    .getOrDefault(emptyMap())
            }
            if (_b != null && calls != lastCalls) { lastCalls = calls; drawTools() }
        }
    }

    private fun drawTools() {
        if (_b == null) return
        val ctx = requireContext()
        val own = runCatching { cc.bridgit.app.judgment.OwnKeys(app) }.getOrNull()
        fun keys(vararg ids: String) = ids.filter { id -> own?.has(id) == true }
            .mapNotNull { id -> cc.bridgit.app.judgment.OwnKeys.PROVIDERS.firstOrNull { it.id == id }?.let { getString(it.label) } }
        val team = cc.bridgit.app.feedback.FeedbackUploader.configured
        data class T(val id: ExternalTools.Id, val name: Int, val detail: Int, val facts: ExternalTools.Facts, val logo: Int = 0)
        val list = listOf(
            T(ExternalTools.Id.JEV, R.string.tools_jev, R.string.tools_jev_detail, ExternalTools.Facts(team, ownKeys = keys("typesafe", "openrouter")), R.drawable.tool_logo_jev),
            T(ExternalTools.Id.CLEF, R.string.tools_clef, R.string.tools_clef_detail, ExternalTools.Facts(team, ownKeys = keys("cloudflare", "openrouter")), R.drawable.tool_logo_clef),
            T(ExternalTools.Id.GLM, R.string.tools_glm, R.string.tools_glm_detail, ExternalTools.Facts(team, ownKeys = keys("openrouter"))),
            T(ExternalTools.Id.LOCATE, R.string.tools_locate, R.string.tools_glm_detail, ExternalTools.Facts(team, ownKeys = keys("openrouter"))),
            T(ExternalTools.Id.APPRAISE, R.string.tools_appraise, R.string.tools_glm_detail, ExternalTools.Facts(team, ownKeys = keys("openrouter"))),
            T(ExternalTools.Id.CHEF, R.string.tools_chef, R.string.tools_glm_detail, ExternalTools.Facts(team, ownKeys = keys("openrouter"))),
            T(ExternalTools.Id.SPEECH, R.string.tools_speech, R.string.tools_speech_detail, ExternalTools.Facts(team)),
            T(ExternalTools.Id.ISSUES, R.string.tools_issues, R.string.tools_issues_detail, ExternalTools.Facts(team, on = app.settings.feedbackUpload)),
            T(ExternalTools.Id.RELAY, R.string.tools_relay, R.string.tools_relay_detail,
                ExternalTools.Facts(cc.bridgit.app.BuildConfig.RELAY_URL.isNotEmpty() || RelayStore(app).enabled, on = RelayStore(app).enabled)),
            T(ExternalTools.Id.WATCH, R.string.tools_watch, R.string.tools_watch_detail, ExternalTools.Facts(team, on = app.remoteView.enabled)),
            T(ExternalTools.Id.UPDATES, R.string.tools_updates, R.string.tools_updates_detail, ExternalTools.Facts(true)),
        )
        val paused = app.settings.externalCallsPaused
        val parent = b.tools
        while (parent.childCount > list.size) parent.removeViewAt(parent.childCount - 1)
        list.forEachIndexed { i, t ->
            val row = (parent.getChildAt(i)?.let { cc.bridgit.app.databinding.AgentsToolRowBinding.bind(it) }
                ?: cc.bridgit.app.databinding.AgentsToolRowBinding.inflate(layoutInflater, parent, true))
            val state = ExternalTools.state(t.id, t.facts, paused)
            row.name.setText(t.name)
            val service = t.id.name.lowercase().takeIf { it in cc.bridgit.app.judgment.ServiceBudgets.NAMES }
            row.root.setOnClickListener(if (service != null) View.OnClickListener { serviceBudgetDialog(app, service) } else null)
            row.logo.visibility = if (t.logo != 0) View.VISIBLE else View.GONE
            if (t.logo != 0) row.logo.setImageResource(t.logo)
            row.state.setText(when (state) {
                ExternalTools.State.READY -> R.string.tools_state_ready
                ExternalTools.State.ON -> R.string.tools_state_on
                ExternalTools.State.OFF -> R.string.tools_state_off
                ExternalTools.State.PAUSED -> R.string.tools_state_paused
                ExternalTools.State.MISSING -> R.string.tools_state_missing
            })
            row.state.setTextColor(ctx.themeColor(when (state) {
                ExternalTools.State.PAUSED -> com.google.android.material.R.attr.colorError
                ExternalTools.State.READY, ExternalTools.State.ON -> com.google.android.material.R.attr.colorPrimary
                else -> com.google.android.material.R.attr.colorOnSurfaceVariant
            }))
            row.dot.setBackgroundResource(when (state) {
                ExternalTools.State.PAUSED -> R.drawable.agents_dot_paused
                ExternalTools.State.READY, ExternalTools.State.ON -> R.drawable.agents_dot_active
                else -> R.drawable.agents_dot_idle
            })
            val call = lastCalls[t.id]
            // What it does, then only what the owner can act on: a failed last call, or when it last worked.
            val parts = mutableListOf(getString(t.detail))
            if (state != ExternalTools.State.MISSING && call != null) parts += when {
                call.ok -> getString(R.string.tools_last_call, Fmt.ago(call.at))
                else -> getString(R.string.tools_last_failed, Fmt.ago(call.at))
            }
            row.detail.text = parts.joinToString(" · ")
            row.root.contentDescription = "${row.name.text}, ${row.state.text}. ${row.detail.text}"
        }
    }

    override fun onDestroyView() {
        hubPanel = null
        deviceCards.clear() // their views go with this view; the next view builds its own
        app.agents.removeListener(agentsChanged)
        view?.removeCallbacks(refreshSoon)
        super.onDestroyView()
        _b = null
    }

    data class Status(val allPaused: Boolean, val tailscaleUp: Boolean, val externalPaused: Boolean)

    companion object {
        /** A read-only pointer elsewhere (A0117) opens this tab with one panel open: "agent:<id>", "device" or "linked:<id>". */
        @Volatile var pendingFocus: String? = null

        /** Every other screen's way into the permissions: switch to this tab with [focus]'s panel open. */
        fun open(fragment: Fragment, focus: String) {
            pendingFocus = focus
            val nav = fragment.findNavController()
            nav.navigate(R.id.agents, null, androidx.navigation.NavOptions.Builder().setLaunchSingleTop(true)
                .setPopUpTo(nav.graph.startDestinationId, inclusive = false, saveState = true).build())
        }
    }

    /** [now] is part of the row so "seen 2m ago" and the dot are rebound on each tick. */
    data class Row(val agent: Agent, val today: Int, val now: Long)

    private class Adapter(
        private val onClick: (Agent) -> Unit,
        private val onPause: (Agent) -> Unit,
        private val expanded: (Agent) -> Boolean,
        private val onExpand: (Agent) -> Unit,
        private val panel: (Agent) -> View,
        private val summary: (Agent) -> PermissionHome.Summary,
    ) : ListAdapter<Row, Adapter.Holder>(DIFF) {
        class Holder(val b: AgentsRowBinding) : RecyclerView.ViewHolder(b.root)

        var status = Status(allPaused = false, tailscaleUp = true, externalPaused = false)
            set(v) { if (v != field) { field = v; notifyItemRangeChanged(0, itemCount) } }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(AgentsRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val r = getItem(position)
            val a = r.agent
            val ctx = h.b.root.context
            val st = status
            h.b.avatar.setAgent(a.name)
            h.b.avatar.alpha = if (a.suspended) 0.5f else 1f
            h.b.name.text = a.name
            val relayMode = if (a.connection == Connection.RELAY) {
                val app = ctx.applicationContext as BridgeApp
                RelayStore(app).agents().firstOrNull { it.optString("id") == a.id }?.optString("mode")
            } else null
            val model = if (a.connection == Connection.RELAY)
                ctx.getString(if (relayMode == "connector") R.string.relay_connector_disclosure else if (relayMode == "sealed") R.string.relay_sealed_label else R.string.relay_setup_required)
            else a.modelText(ctx)
            val powers = when {
                a.fixer && a.superPowers -> ctx.getString(R.string.agents_badge_both)
                a.superPowers -> ctx.getString(R.string.agents_badge_super)
                a.fixer -> ctx.getString(R.string.agents_badge_fixer)
                else -> null
            }
            h.b.connection.text = listOfNotNull(model, powers).joinToString(" · ")

            val active = AgentStats.isActive(a, r.now)
            val rotated = AgentStats.waitingForNewPrompt(a, r.now)
            val relay = a.connection == Connection.RELAY
            // Why this agent can't reach the phone right now, if it can't.
            val blocked: String? = when {
                a.paused -> ctx.getString(R.string.agents_status_paused)
                !a.apiAccess -> ctx.getString(R.string.agents_status_api_off)
                st.allPaused && !relay -> ctx.getString(R.string.agents_status_all_paused)
                relay && st.externalPaused -> ctx.getString(R.string.agents_status_relay_paused)
                !relay && a.connection == Connection.DIRECT && !st.tailscaleUp -> ctx.getString(R.string.agents_status_tailscale_off)
                else -> null
            }
            val via = ctx.getString(when (a.connection) {
                Connection.RELAY -> R.string.agents_status_relay
                Connection.USERSPACE -> R.string.agents_status_userspace
                Connection.DIRECT -> R.string.agents_status_tailscale
                Connection.APPLET -> R.string.agents_status_applet
            })
            val seen = when {
                rotated -> ctx.getString(R.string.agents_status_rotated)
                active -> ctx.getString(R.string.agents_status_active)
                a.lastUsedAt > 0 -> ctx.getString(R.string.agents_status_seen, Fmt.ago(a.lastUsedAt, r.now))
                else -> ctx.getString(R.string.agents_status_never)
            }
            val today = r.today.takeIf { it > 0 }?.let { ctx.getString(R.string.agents_status_today, it) }
            h.b.line2.text = listOfNotNull(blocked ?: via, seen, today).joinToString(" · ")
            h.b.line2.setTextColor(ctx.themeColor(when {
                blocked != null -> com.google.android.material.R.attr.colorError
                rotated -> com.google.android.material.R.attr.colorSecondary
                else -> com.google.android.material.R.attr.colorOnSurfaceVariant
            }))
            h.b.dot.setBackgroundResource(when {
                blocked != null -> R.drawable.agents_dot_paused
                active -> R.drawable.agents_dot_active
                else -> R.drawable.agents_dot_idle
            })

            h.b.pause.setIconResource(if (a.paused) R.drawable.agents_ic_resume else R.drawable.agents_ic_pause)
            h.b.pause.contentDescription = ctx.getString(if (a.paused) R.string.agents_resume_one else R.string.agents_pause_one, a.name)
            h.b.pause.setOnClickListener { onPause(a) }

            val dev = powers?.let { ctx.getString(R.string.agents_card_desc_powers, it) } ?: ""
            h.b.info.contentDescription = listOf(a.name, h.b.connection.text, h.b.line2.text).joinToString(", ") + dev
            h.b.root.contentDescription = h.b.info.contentDescription
            h.b.root.setOnClickListener { onClick(a) }

            // A0115: "Permissions  12 of 20", and the full panel with its switches in place when opened.
            val s = summary(a)
            val open = expanded(a)
            h.b.permissions.count.text = ctx.getString(R.string.agents_group_count, s.granted, s.total)
            h.b.permissions.chevron.rotation = if (open) 180f else 0f
            h.b.permissions.root.contentDescription = ctx.getString(R.string.perm_pointer, s.granted, s.total) + if (open) ", expanded" else ", collapsed"
            h.b.permissions.root.setOnClickListener { onExpand(a) }
            h.b.permissionsPanel.isVisible = open
            val shown = h.b.permissionsPanel.getChildAt(0)
            if (!open || h.b.permissionsPanel.tag != a.id) { h.b.permissionsPanel.removeAllViews(); h.b.permissionsPanel.tag = null }
            if (open) {
                if (h.b.permissionsPanel.childCount == 0) { h.b.permissionsPanel.addView(panel(a)); h.b.permissionsPanel.tag = a.id }
                else (shown?.tag as? Runnable)?.run()
            }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<Row>() {
                override fun areItemsTheSame(x: Row, y: Row) = x.agent.id == y.agent.id
                override fun areContentsTheSame(x: Row, y: Row) =
                    x.agent == y.agent && x.today == y.today && x.now / 30_000 == y.now / 30_000
            }
        }
    }
}
