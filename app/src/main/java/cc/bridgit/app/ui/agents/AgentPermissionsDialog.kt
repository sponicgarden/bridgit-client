package cc.bridgit.app.ui.agents

import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.core.Agent
import cc.bridgit.app.power.PowerAccess
import cc.bridgit.app.security.OwnerCheck
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * The one permission panel (A0130), shown while an agent is added and on its
 * Agents screen afterwards: top-level masters (Maximum Agentic Control,
 * Superpowers), then every capability group with its own master (all, some
 * or none) and count, expandable to the single permissions.
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
        val groupsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val advanced = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setText(R.string.agents_advanced_permissions)
        }
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val fixer = switch(R.string.agents_fixer_switch)
        val deviceAccess = MaterialButton(context).apply { setText(R.string.power_max_review) }
        content.addView(apiAccess); content.addView(maximum); content.addView(superpowers); content.addView(groupsBox)
        content.addView(advanced); content.addView(details)
        details.addView(fixer); details.addView(deviceAccess)

        val items = app.registry.modules.filter { it.id !in Agent.ALWAYS_ALLOWED }
            .map { PermissionGroups.Item(it.id, it.title, it.group, context.getString(it.descriptionRes)) }
        val allIds = items.map { it.id }
        val expanded = mutableSetOf<String>()
        var binding = false

        fun setGrants(ids: Collection<String>, on: Boolean) {
            val old = app.agents.get(id) ?: return
            val grants = PermissionGroups.set(old.caps, allIds, ids, on)
            app.agents.update(id) { it.copy(caps = grants) }
            fun label(caps: Set<String>?) = caps?.sorted()?.joinToString(",") ?: "all"
            app.log.system("audit", "agent", "Owner changed ${old.name}'s grants: ${label(old.caps)} -> ${label(grants)}")
        }

        // One row per group: master checkbox (on, partial, off), title, "n of m", chevron.
        class Row(val master: MaterialCheckBox, val count: TextView, val chevron: ImageView,
                  val children: LinearLayout, val boxes: Map<String, MaterialCheckBox>)
        val rows = mutableMapOf<String, Row>()
        lateinit var refresh: (Boolean) -> Unit
        val agentNow = app.agents.get(id)
        for (group in PermissionGroups.of(items) { agentNow?.allows(it) == true }) {
            val title = PermissionGroups.title(context, group.key)
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(48)
                isClickable = true; isFocusable = true
                setBackgroundResource(android.util.TypedValue().also {
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                }.resourceId)
            }
            val master = MaterialCheckBox(context).apply { contentDescription = title }
            val name = TextView(context).apply {
                text = title
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val count = TextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(context.dp(8), 0, context.dp(4), 0)
            }
            val chevron = ImageView(context).apply {
                setImageResource(R.drawable.interactions_ic_expand)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            header.addView(master); header.addView(name); header.addView(count); header.addView(chevron)
            val children = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(32), 0, 0, 0)
                visibility = View.GONE
            }
            val boxes = group.items.associate { item ->
                item.id to MaterialCheckBox(context).apply {
                    // Title, then what it does, where and when, on one control so it reads as one label.
                    text = PermissionGroups.detail(item)?.let { detail ->
                        android.text.SpannableStringBuilder(item.title).append('\n').apply {
                            val start = length
                            append(detail)
                            setSpan(android.text.style.TextAppearanceSpan(context,
                                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium), start, length, 0)
                            setSpan(android.text.style.ForegroundColorSpan(context.themeColor(
                                com.google.android.material.R.attr.colorOnSurfaceVariant)), start, length, 0)
                        }
                    } ?: item.title
                    minHeight = context.dp(48)
                    setPadding(paddingLeft, context.dp(8), paddingRight, context.dp(8))
                    setOnCheckedChangeListener { _, on -> if (!binding) { setGrants(listOf(item.id), on); refresh(true) } }
                }
            }
            boxes.values.forEach { children.addView(it) }
            master.addOnCheckedStateChangedListener { _, state ->
                if (binding) return@addOnCheckedStateChangedListener
                // A tap on a partial group turns the whole group on.
                setGrants(group.items.map { it.id }, state != MaterialCheckBox.STATE_UNCHECKED)
                refresh(true)
            }
            header.setOnClickListener {
                if (!expanded.remove(group.key)) expanded.add(group.key)
                refresh(false)
            }
            groupsBox.addView(header); groupsBox.addView(children)
            rows[group.key] = Row(master, count, chevron, children, boxes)
            header.tag = title
        }

        refresh = refresh@{ notify ->
            val agent = app.agents.get(id) ?: return@refresh
            binding = true
            apiAccess.isChecked = agent.apiAccess
            maximum.isChecked = access.isMaximum(id, requireSuper = false)
            superpowers.isChecked = agent.superPowers
            fixer.isChecked = agent.fixer
            for (group in PermissionGroups.of(items) { agent.allows(it) }) {
                val row = rows[group.key] ?: continue
                row.master.checkedState = when (group.state) {
                    PermissionGroups.State.ON -> MaterialCheckBox.STATE_CHECKED
                    PermissionGroups.State.PARTIAL -> MaterialCheckBox.STATE_INDETERMINATE
                    PermissionGroups.State.OFF -> MaterialCheckBox.STATE_UNCHECKED
                }
                row.count.text = context.getString(R.string.agents_group_count, group.granted, group.items.size)
                row.boxes.forEach { (cap, box) -> box.isChecked = agent.allows(cap) }
                val open = group.key in expanded
                row.children.visibility = if (open) View.VISIBLE else View.GONE
                row.chevron.rotation = if (open) 180f else 0f
                val header = row.master.parent as View
                header.contentDescription = "${header.tag}, ${row.count.text}, " + if (open) "expanded" else "collapsed"
            }
            binding = false
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
            if (!binding) { app.agents.update(id) { it.copy(fixer = on) }; refresh(true) }
        }
        deviceAccess.setOnClickListener {
            close()
            fragment.findNavController().navigate(R.id.powers, androidx.core.os.bundleOf("agentId" to id))
        }
        return content
    }
}
