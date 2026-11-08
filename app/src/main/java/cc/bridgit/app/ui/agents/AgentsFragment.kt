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
        )
        b.list.adapter = adapter
        b.scroll.setOnScrollChangeListener { _: androidx.core.widget.NestedScrollView, _: Int, y: Int, _: Int, oldY: Int ->
            val dy = y - oldY
            b.toolbar.isVisible = y < (24 * resources.displayMetrics.density) // the ⋮ would sit over scrolled cards
            if (dy > 4 && b.fab.isExtended) b.fab.shrink()
            else if (dy < -4 && !b.fab.isExtended) b.fab.extend()
        }
        // Max Agentic Control off: the same confirm-then-guided-pass as the Settings switch.
        b.maxBannerEnable.setOnClickListener {
            cc.bridgit.app.ui.power.PowersFragment.confirmMaximum(this, null) { renderMaxBanner() }
        }
        hubPanel = HubAgentsPanel(this, app, b.root as ViewGroup)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { cc.bridgit.app.portal.PortalLinks(app).selected.collect { syncHub() } }
        }
        b.fab.setOnClickListener { addAgent() }
        b.emptyAdd.setOnClickListener { addAgent() }
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
    override fun onResume() { super.onResume(); renderMaxBanner(); syncHub() }

    /** Switched to the hub: its agents cover this phone's list, and Add agent goes away (agents are added on the hub). */
    private fun syncHub() { val onHub = hubPanel?.sync() == true; _b?.fab?.isVisible = !onHub; _b?.toolbar?.isVisible = !onHub }

    /** Pinned at the top while Max Agentic Control is off; the same test as its switch in Settings. */
    private fun renderMaxBanner() {
        if (_b == null) return
        val missing = cc.bridgit.app.power.PowerAccess(app).missingAccess()
        b.maxBanner.isVisible = MaxControlBanner.visible(missing.complete)
        if (missing.complete) return
        // Same headings and items as the Max Agentic Control dialog; the list scrolls in at most about a third
        // of the screen, so the heading and the button always fit.
        b.maxBannerScroll.maxHeightPx = (resources.displayMetrics.heightPixels * 0.3f).toInt()
        val list = b.maxBannerList
        list.removeAllViews()
        val sheet = cc.bridgit.app.ui.power.MaxControlSheet
        if (missing.permissions.isNotEmpty()) {
            list.addView(sheet.header(requireContext(), getString(R.string.max_missing_access_title)))
            list.addView(sheet.flow(requireContext(), missing.permissions, pending = true))
        }
        if (missing.settings.isNotEmpty()) {
            list.addView(sheet.header(requireContext(), getString(R.string.max_missing_settings_title)))
            list.addView(sheet.flow(requireContext(), missing.settings, pending = true))
        }
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
        val ext = app.settings.externalCallsPaused
        b.pauseExternal.toggle.isChecked = !ext
        b.pauseExternal.value.setText(if (ext) R.string.agents_pause_external_on else R.string.agents_pause_external_off)
    }

    private fun refresh() {
        renderControls()
        renderMaxBanner()
        renderTools()
        adapter.status = status()
        // Portal pairings only view the phone: one row links to More › Linked devices instead of agent cards.
        val portal = runCatching { cc.bridgit.app.portal.PortalPairing.paired(app, RelayStore(app)) }.getOrDefault(emptyList())
        renderPortalRow(portal)
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

    private fun renderPortalRow(portal: List<Agent>) {
        b.portalRow.isVisible = portal.isNotEmpty()
        if (portal.isEmpty()) return
        // The device it said it is (iPad, iPhone…) once it has called; "iPhone/iPad" until then, as on Home.
        val names = portal.map { cc.bridgit.app.portal.PortalModule.clientOf(it.id) ?: "iPhone/iPad" }.distinct()
        b.portalRow.text = getString(R.string.portal_agents_row, names.joinToString(", "))
    }

    private fun show(list: List<Row>) {
        rows = list
        adapter.submitList(list)
        val empty = list.isEmpty()
        b.empty.isVisible = empty
        b.list.isVisible = !empty
        // Switched to the hub, agents are added there: no Add agent over its list.
        if (empty || cc.bridgit.app.portal.PortalLinks(app).selectedHub() != null) b.fab.hide() else b.fab.show()
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
            val parts = mutableListOf(getString(t.detail))
            if (service != null) parts += getString(R.string.tools_budget)
            t.facts.ownKeys.firstOrNull()?.let { parts += getString(R.string.tools_own_key, t.facts.ownKeys.joinToString(", ")) }
            // A tool this build doesn't have can't be called, so skip the "not called yet" tail.
            if (state != ExternalTools.State.MISSING) parts += when {
                call == null -> getString(R.string.tools_never)
                call.ok -> getString(R.string.tools_last_call, Fmt.ago(call.at))
                else -> getString(R.string.tools_last_failed, Fmt.ago(call.at))
            }
            row.detail.text = parts.joinToString(" · ")
            row.root.contentDescription = "${row.name.text}, ${row.state.text}. ${row.detail.text}"
        }
    }

    override fun onDestroyView() {
        hubPanel = null
        app.agents.removeListener(agentsChanged)
        view?.removeCallbacks(refreshSoon)
        super.onDestroyView()
        _b = null
    }

    data class Status(val allPaused: Boolean, val tailscaleUp: Boolean, val externalPaused: Boolean)

    /** [now] is part of the row so "seen 2m ago" and the dot are rebound on each tick. */
    data class Row(val agent: Agent, val today: Int, val now: Long)

    private class Adapter(private val onClick: (Agent) -> Unit, private val onPause: (Agent) -> Unit) : ListAdapter<Row, Adapter.Holder>(DIFF) {
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
            h.b.avatar.alpha = if (a.paused) 0.5f else 1f
            h.b.name.text = a.name
            val relayMode = if (a.connection == Connection.RELAY) {
                val app = ctx.applicationContext as BridgeApp
                RelayStore(app).agents().firstOrNull { it.optString("id") == a.id }?.optString("mode")
            } else null
            h.b.connection.text = if (a.connection == Connection.RELAY)
                ctx.getString(if (relayMode == "connector") R.string.relay_connector_disclosure else if (relayMode == "sealed") R.string.relay_sealed_label else R.string.relay_setup_required)
            else a.modelText(ctx)
            val powers = when {
                a.fixer && a.superPowers -> ctx.getString(R.string.agents_badge_both)
                a.superPowers -> ctx.getString(R.string.agents_badge_super)
                a.fixer -> ctx.getString(R.string.agents_badge_fixer)
                else -> null
            }
            h.b.developer.isVisible = powers != null
            h.b.developer.text = powers

            val active = AgentStats.isActive(a, r.now)
            val rotated = AgentStats.waitingForNewPrompt(a, r.now)
            val relay = a.connection == Connection.RELAY
            // Why this agent can't reach the phone right now, if it can't.
            val blocked: String? = when {
                a.paused -> ctx.getString(R.string.agents_status_paused)
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
