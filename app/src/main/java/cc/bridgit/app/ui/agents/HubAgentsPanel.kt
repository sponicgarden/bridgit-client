package cc.bridgit.app.ui.agents

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.portal.PortalLinks
import cc.bridgit.app.satellite.HubLinks
import cc.bridgit.app.satellite.SatelliteClient
import cc.bridgit.app.ui.common.themeColor

/**
 * The Agents tab while a satellite is switched to its hub (owner, 5 Nov 2026): the hub's control agents, each with
 * a Pause / Resume button that acts on the hub. Covers this phone's own list, which comes back on "This phone" in the
 * hub bar. Works only while the hub lets this satellite manage it (its "Can see and manage this phone" switch).
 */
class HubAgentsPanel(private val fragment: Fragment, private val app: BridgeApp, parent: ViewGroup) {
    private val ctx = parent.context
    private val dp = ctx.resources.displayMetrics.density
    private val list = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (96 * dp).toInt())
    }
    val view: NestedScrollView = NestedScrollView(ctx).apply {
        setBackgroundColor(ctx.themeColor(com.google.android.material.R.attr.colorSurface))
        isVisible = false; isClickable = true // don't let taps reach this phone's list underneath
        addView(list)
    }

    init { parent.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }

    /** Shows the hub's agents when a hub is selected; returns whether it is showing. */
    fun sync(): Boolean {
        val hub = PortalLinks(app).selectedHub()
        view.isVisible = hub != null
        if (hub != null) load(hub)
        return hub != null
    }

    private fun load(hub: HubLinks.HubInfo) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { app.hubLinks.hubAgents(hub) } }
            render(hub, r)
        }
    }

    private fun render(hub: HubLinks.HubInfo, r: Result<List<HubLinks.HubAgent>>) {
        list.removeAllViews()
        list.addView(text(ctx.getString(R.string.hub_agents_title, hub.name), 22f, bold = true))
        list.addView(text(ctx.getString(R.string.hub_agents_hint, hub.name), 14f).apply { setPadding(0, (4 * dp).toInt(), 0, (12 * dp).toInt()) })
        r.onFailure { e ->
            val paused = (e as? SatelliteClient.Failure)?.status == 403
            list.addView(text(if (paused) ctx.getString(R.string.hub_agents_paused_by_hub, hub.name)
                else ctx.getString(R.string.hub_agents_unreachable, hub.name, e.message ?: e.javaClass.simpleName), 15f))
            return
        }
        val agents = r.getOrDefault(emptyList())
        if (agents.isEmpty()) list.addView(text(ctx.getString(R.string.hub_agents_none, hub.name), 15f))
        agents.forEach { a -> list.addView(card(hub, a)) }
    }

    private fun card(hub: HubLinks.HubInfo, a: HubLinks.HubAgent): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt()) }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(a.name, 17f, bold = true))
        val status = listOfNotNull(a.model, ctx.getString(if (a.paused) R.string.agents_status_paused else R.string.hub_agent_active))
        texts.addView(text(status.joinToString(" · "), 14f))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(if (a.paused) R.string.hub_agent_resume else R.string.hub_agent_pause)
            setOnClickListener {
                isEnabled = false
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { runCatching { app.hubLinks.pauseHubAgent(hub, a.id, !a.paused) } }
                    load(hub)
                }
            }
        })
        return MaterialCardView(ctx).apply {
            radius = 16 * dp; addView(row)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (10 * dp).toInt() }
        }
    }

    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextColor(ctx.themeColor(com.google.android.material.R.attr.colorOnSurface))
    }
}
