package cc.bridgit.app.ui.agents

import android.view.View
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.core.Agent
import cc.bridgit.app.power.PowerAccess
import cc.bridgit.app.security.OwnerCheck
import cc.bridgit.app.ui.common.dp
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * The one permission panel (A0130), shown in place on the Agents and Linked Devices tab (A0115) and while an agent is
 * added: top-level masters (API access, Maximum Agentic Control, Superpowers), then every capability group with its
 * own master (all, some or none) and count, expandable to the single permissions. Linked devices (A0116) get the
 * same groups without the agent masters ([linked]); this device gets its own switches ([device]).
 */
object AgentPermissionsDialog {
    fun show(fragment: Fragment, id: String, changed: () -> Unit = {}) {
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val scroll = NestedScrollView(fragment.requireContext()).apply {
            addView(content(fragment, id, changed) { dialog.dismiss() })
        }
        dialog = MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.agents_section_permissions)
            .setView(scroll)
            .setPositiveButton(R.string.addagent_done, null).create()
        dialog.show()
    }

    /** Every permission an agent or linked device can be given (status and issue filing are always allowed). */
    fun items(app: BridgeApp): List<PermissionGroups.Item> = app.registry.modules.filter { it.id !in Agent.ALWAYS_ALLOWED }
        .map { PermissionGroups.Item(it.id, it.title, it.group, app.getString(it.descriptionRes)) }

    /** This device's own switches: what any agent may use here. */
    fun deviceItems(app: BridgeApp): List<PermissionGroups.Item> = app.registry.switchable()
        .map { PermissionGroups.Item(it.id, it.title, it.group, app.getString(it.descriptionRes)) }

    fun agentPerms(app: BridgeApp, agent: Agent) = PermissionHome.agent(items(app), agent)
    fun linkedPerms(app: BridgeApp, l: LinkedDevices.Linked) = PermissionHome.linked(items(app), l.agent, LinkedDevices.ceiling(app, l))
    fun devicePerms(app: BridgeApp): List<PermissionHome.Perm> {
        val profile = cc.bridgit.app.platform.DeviceProfile(app)
        val keys = app.registry.switchable().associate { it.id to it.switchKey!! }
        return PermissionHome.device(deviceItems(app), { app.settings.isOn(keys.getValue(it)) }, { app.settings.isSpecific(keys.getValue(it)) }, profile::inherited)
    }

    /** Grants for an agent or linked device, as the Agents screen always set them, with the audit line. */
    fun setGrants(app: BridgeApp, id: String, ids: Collection<String>, on: Boolean) {
        val old = app.agents.get(id) ?: return
        val grants = PermissionGroups.set(old.caps, items(app).map { it.id }, ids, on)
        if (grants == old.caps) return
        app.agents.update(id) { it.copy(caps = grants) }
        if (!on) app.platform.invalidate(id)
        fun label(caps: Set<String>?) = caps?.sorted()?.joinToString(",") ?: "all"
        app.log.system("audit", "agent", "Owner changed ${old.name}'s grants: ${label(old.caps)} -> ${label(grants)}")
    }

    /** This device's switches in place: the same switch and audit line as Features, then Android's own access if it is needed. */
    fun device(fragment: Fragment, requestAccess: (Array<String>) -> Unit, changed: () -> Unit = {}): View {
        val app = fragment.requireActivity().application as BridgeApp
        val context = fragment.requireContext()
        lateinit var groups: PermissionGroupsView
        groups = PermissionGroupsView(context, deviceItems(app), { devicePerms(app) }) { ids, on ->
            val needs = mutableListOf<String>()
            for (id in ids) {
                val m = app.registry.byId(id) ?: continue
                cc.bridgit.app.ui.home.UiActions.setSwitch(app, m, on)
                if (on && m.blockedReason()?.startsWith("permission:") == true) needs += cc.bridgit.app.ui.home.UiActions.permissionsFor(m)
            }
            if (needs.isNotEmpty()) requestAccess(needs.distinct().toTypedArray())
            changed()
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(8), 0, context.dp(8), 0)
            addView(groups.view)
            tag = Runnable { groups.refresh() }
        }
    }

    /** A linked device's permissions on this phone: the agent groups, capped at what its pairing gave it. */
    fun linked(fragment: Fragment, id: String, changed: () -> Unit = {}): View {
        val app = fragment.requireActivity().application as BridgeApp
        val context = fragment.requireContext()
        val empty = emptyList<PermissionHome.Perm>()
        val groups = PermissionGroupsView(context, items(app), { LinkedDevices.get(app, id)?.let { linkedPerms(app, it) } ?: empty }) { ids, on ->
            val l = LinkedDevices.get(app, id)
            if (l != null) {
                val ceiling = LinkedDevices.ceiling(app, l)
                setGrants(app, id, if (on && ceiling != null) ids.filter { it in ceiling } else ids, on)
                changed()
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(8), 0, context.dp(8), 0)
            addView(groups.view)
            tag = Runnable { groups.refresh() }
        }
    }

    fun content(fragment: Fragment, id: String, changed: () -> Unit = {}, close: () -> Unit = {}): View {
        val app = fragment.requireActivity().application as BridgeApp
        val context = fragment.requireContext()
        val access = PowerAccess(app)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), 0, context.dp(24), 0)
        }
        fun switch(label: Int) = MaterialSwitch(context).apply {
            setText(label)
            minHeight = context.dp(48)
        }
        val apiAccess = switch(R.string.agents_api_access)
        val maximum = switch(R.string.agents_maximum_control)
        val superpowers = switch(R.string.agents_super_switch)
        val advanced = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setText(R.string.agents_advanced_permissions)
        }
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val fixer = switch(R.string.agents_fixer_switch)
        val deviceAccess = MaterialButton(context).apply { setText(R.string.power_max_review) }
        var binding = false
        lateinit var refresh: (Boolean) -> Unit
        val empty = emptyList<PermissionHome.Perm>()
        val groups = PermissionGroupsView(context, items(app), { app.agents.get(id)?.let { agentPerms(app, it) } ?: empty }) { ids, on ->
            setGrants(app, id, ids, on); refresh(true)
        }
        content.addView(apiAccess); content.addView(maximum); content.addView(superpowers); content.addView(groups.view)
        content.addView(advanced); content.addView(details)
        details.addView(fixer); details.addView(deviceAccess)

        refresh = refresh@{ notify ->
            val agent = app.agents.get(id) ?: return@refresh
            binding = true
            apiAccess.isChecked = agent.apiAccess
            maximum.isChecked = access.isMaximum(id, requireSuper = false)
            superpowers.isChecked = agent.superPowers
            fixer.isChecked = agent.fixer
            binding = false
            groups.refresh()
            if (notify) changed()
        }
        content.tag = Runnable { refresh(false) }
        refresh(false)
        // A0120: the one permission over the whole API; off refuses every call while the agent stays paired.
        apiAccess.setOnCheckedChangeListener { _, on ->
            if (!binding) {
                val a = app.agents.update(id) { it.copy(apiAccess = on) }
                if (!on) app.platform.invalidate(id)
                app.log.system("audit", "agent", "Owner turned API access ${if (on) "on" else "off"} for ${a?.name}")
                refresh(true)
            }
        }
        maximum.setOnCheckedChangeListener { _, on ->
            if (!binding) {
                if (on) OwnerCheck.run(fragment, context.getString(R.string.agents_maximum_control), onCancel = { refresh(true) }) {
                    access.allSwitchesOn()
                    access.grantAll(id, superPowers = app.agents.get(id)?.superPowers == true)
                    refresh(true)
                } else {
                    app.platform.revoke(id)
                    app.agents.update(id) { it.copy(caps = emptySet(), fixer = false, maximumControl = false) }
                    refresh(true)
                }
            }
        }
        superpowers.setOnCheckedChangeListener { _, on ->
            if (!binding) {
                val apply = {
                    app.agents.update(id) { it.copy(superPowers = on) }
                    app.log.system("audit", "agent", "Owner ${if (on) "enabled" else "disabled"} superpowers for ${app.agents.get(id)?.name}")
                    refresh(true)
                }
                if (on) OwnerCheck.run(fragment, context.getString(R.string.agents_super_switch), onCancel = { refresh(true) }) { apply() }
                else apply()
            }
        }
        advanced.setOnClickListener {
            details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE
            advanced.isSelected = details.visibility == View.VISIBLE
            advanced.contentDescription = context.getString(R.string.agents_advanced_permissions) + if (advanced.isSelected) ", expanded" else ", collapsed"
        }
        fixer.setOnCheckedChangeListener { _, on ->
            if (!binding) {
                app.agents.update(id) { it.copy(fixer = on) }
                app.log.system("audit", "agent", "Owner turned Fixer Mode ${if (on) "on" else "off"} for ${app.agents.get(id)?.name}")
                refresh(true)
            }
        }
        deviceAccess.setOnClickListener {
            close()
            fragment.findNavController().navigate(R.id.powers, androidx.core.os.bundleOf("agentId" to id))
        }
        return content
    }
}
