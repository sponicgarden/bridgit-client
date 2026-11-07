package cc.bridgit.app.ui.agents

import android.view.View
import android.widget.LinearLayout
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

/** The same two recommended controls during setup and on an existing agent. */
object AgentPermissionsDialog {
    fun show(fragment: Fragment, id: String, changed: () -> Unit = {}) {
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        dialog = MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.agents_section_permissions)
            .setView(content(fragment, id, changed) { dialog.dismiss() })
            .setPositiveButton(R.string.addagent_done, null).create()
        dialog.show()
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
        val capabilities = MaterialButton(context).apply { setText(R.string.agents_section_permissions) }
        val deviceAccess = MaterialButton(context).apply { setText(R.string.power_max_review) }
        content.addView(maximum); content.addView(superpowers); content.addView(advanced); content.addView(details)
        details.addView(fixer); details.addView(capabilities); details.addView(deviceAccess)
        var binding = false
        fun refresh(notify: Boolean = true) {
            val agent = app.agents.get(id) ?: return
            binding = true
            maximum.isChecked = access.isMaximum(id, requireSuper = false)
            superpowers.isChecked = agent.superPowers
            fixer.isChecked = agent.fixer
            binding = false
            if (notify) changed()
        }
        content.tag = Runnable { refresh(false) }
        refresh(false)
        maximum.setOnCheckedChangeListener { _, on ->
            if (!binding) {
                if (on) OwnerCheck.run(fragment, context.getString(R.string.agents_maximum_control), onCancel = { refresh() }) {
                    access.allSwitchesOn()
                    access.grantAll(id, superPowers = app.agents.get(id)?.superPowers == true)
                    refresh()
                } else {
                    app.platform.revoke(id)
                    app.agents.update(id) { it.copy(caps = emptySet(), fixer = false, maximumControl = false) }
                    refresh()
                }
            }
        }
        superpowers.setOnCheckedChangeListener { _, on ->
            if (!binding) {
                val apply = {
                    app.agents.update(id) { it.copy(superPowers = on) }
                    app.log.system("audit", "agent", "Owner ${if (on) "enabled" else "disabled"} superpowers for ${app.agents.get(id)?.name}")
                    refresh()
                }
                if (on) OwnerCheck.run(fragment, context.getString(R.string.agents_super_switch), onCancel = { refresh() }) { apply() }
                else apply()
            }
        }
        advanced.setOnClickListener {
            details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE
            advanced.isSelected = details.visibility == View.VISIBLE
            advanced.contentDescription = context.getString(R.string.agents_advanced_permissions) + if (advanced.isSelected) ", expanded" else ", collapsed"
        }
        fixer.setOnCheckedChangeListener { _, on ->
            if (!binding) { app.agents.update(id) { it.copy(fixer = on) }; refresh() }
        }
        capabilities.setOnClickListener {
            val agent = app.agents.get(id) ?: return@setOnClickListener
            val modules = app.registry.modules.filter { it.id !in Agent.ALWAYS_ALLOWED }
            val checked = modules.map { agent.allows(it.id) }.toBooleanArray()
            MaterialAlertDialogBuilder(context).setTitle(R.string.agents_advanced_permissions)
                .setMultiChoiceItems(modules.map { it.title }.toTypedArray(), checked) { _, index, on -> checked[index] = on }
                .setPositiveButton(R.string.agents_save) { _, _ ->
                    app.agents.update(id) { it.copy(caps = modules.indices.filter { checked[it] }.map { modules[it].id }.toSet()) }
                    refresh()
                }.setNegativeButton(R.string.cancel, null).show()
        }
        deviceAccess.setOnClickListener {
            close()
            fragment.findNavController().navigate(R.id.powers, androidx.core.os.bundleOf("agentId" to id))
        }
        return content
    }
}
